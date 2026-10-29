//! JNI for `com.unbihex.vpn.EngineBridge`: the VPN service connects without the UI
//! (quick settings tile, notification, always-on VPN) and drives the engine through
//! the same client the UI uses.
//!
//! Kotlin calls these from a worker thread; they block until the work is done.

use std::path::PathBuf;
use std::ptr;

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jlongArray, jstring};

use crate::{SHARED, err, shared_state, start_engine, vpn_start_args};

fn throw(env: &mut JNIEnv, msg: impl std::fmt::Display) {
    let _ = env.throw_new("java/lang/IllegalStateException", msg.to_string());
}

fn not_open(env: &mut JNIEnv) {
    throw(env, "клиент не открыт");
}

/// `StartArgs` JSON for the selected server. Opens the client on first use.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_unbihex_vpn_EngineBridge_tunArgs<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    data_dir: JString<'l>,
) -> jstring {
    let dir: String = match env.get_string(&data_dir) {
        Ok(s) => s.into(),
        Err(e) => {
            throw(&mut env, e);
            return ptr::null_mut();
        }
    };
    let state = shared_state(|| PathBuf::from(dir));
    let json = match vpn_start_args(&state.client)
        .map_err(err)
        .and_then(|(_, args)| serde_json::to_string(&args).map_err(err))
    {
        Ok(json) => json,
        Err(e) => {
            throw(&mut env, e);
            return ptr::null_mut();
        }
    };
    match env.new_string(json) {
        Ok(s) => s.into_raw(),
        Err(e) => {
            throw(&mut env, e);
            ptr::null_mut()
        }
    }
}

/// Starts the engine on the service's TUN descriptor.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_unbihex_vpn_EngineBridge_connect<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    fd: jint,
) {
    let Some(state) = SHARED.get() else {
        return not_open(&mut env);
    };
    let r = tauri::async_runtime::block_on(async {
        // Waiting for the lock could deadlock: the UI may hold it while it waits for the service.
        let Ok(_op) = state.op.try_lock() else {
            return Err("приложение уже подключается".to_string());
        };
        start_engine(&state.client, fd).await.map_err(err)
    });
    if let Err(e) = r {
        throw(&mut env, e);
    }
}

/// Stops the engine; the service closes the TUN itself.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_unbihex_vpn_EngineBridge_disconnect<'l>(_env: JNIEnv<'l>, _class: JClass<'l>) {
    if let Some(state) = SHARED.get() {
        // No `op` lock (see `connect`); disconnecting is idempotent anyway.
        tauri::async_runtime::block_on(state.client.disconnect());
    }
}

/// `[up, down]` bytes through the engine, or null when it is not running.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_unbihex_vpn_EngineBridge_stats<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jlongArray {
    let Some(state) = SHARED.get() else {
        return ptr::null_mut();
    };
    let st = tauri::async_runtime::block_on(state.client.status());
    if !st.connected {
        return ptr::null_mut();
    }
    let values = [
        st.proxy_up.saturating_add(st.direct_up) as i64,
        st.proxy_down.saturating_add(st.direct_down) as i64,
    ];
    let filled = env
        .new_long_array(2)
        .and_then(|arr| env.set_long_array_region(&arr, 0, &values).map(|()| arr));
    match filled {
        Ok(arr) => arr.into_raw(),
        Err(e) => {
            throw(&mut env, e);
            ptr::null_mut()
        }
    }
}
