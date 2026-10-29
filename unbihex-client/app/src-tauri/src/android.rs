//! JNI for `com.unbihex.vpn.EngineBridge`: the VPN service connects without the UI
//! (quick settings tile, notification, always-on VPN) and drives the engine through
//! the same client the UI uses.
//!
//! Kotlin calls these from a worker thread; they block until the work is done.

use std::path::PathBuf;
use std::ptr;
use std::sync::{Mutex, PoisonError};

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jlongArray, jstring};

use crate::{SHARED, VpnPlan, err, session_name, shared_state, start_engine, vpn_plan};

/// The attempt `tunArgs` planned; `connect` starts the engine with exactly this.
static PLAN: Mutex<Option<VpnPlan>> = Mutex::new(None);

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
    let json = match vpn_plan(&state.client).map_err(err).and_then(|plan| {
        let json = serde_json::to_string(&plan.args).map_err(err)?;
        *PLAN.lock().unwrap_or_else(PoisonError::into_inner) = Some(plan);
        Ok(json)
    }) {
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

/// Starts the engine on the service's TUN descriptor, as the last `tunArgs` planned.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_unbihex_vpn_EngineBridge_connect<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    fd: jint,
) {
    let Some(state) = SHARED.get() else {
        return not_open(&mut env);
    };
    let Some(plan) = PLAN.lock().unwrap_or_else(PoisonError::into_inner).take() else {
        return throw(&mut env, "подключение не подготовлено");
    };
    let r = tauri::async_runtime::block_on(async {
        // Waiting for the lock could deadlock: the UI may hold it while it waits for the service.
        let Ok(_op) = state.op.try_lock() else {
            return Err("приложение уже подключается".to_string());
        };
        start_engine(&state.client, &plan, fd).await.map_err(err)
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

/// Session name of the running connection, or null. Follows hot server changes.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_unbihex_vpn_EngineBridge_session<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    let Some(state) = SHARED.get() else {
        return ptr::null_mut();
    };
    let Some(info) = tauri::async_runtime::block_on(state.client.status()).info else {
        return ptr::null_mut();
    };
    match env.new_string(session_name(&info.server_name)) {
        Ok(s) => s.into_raw(),
        Err(e) => {
            throw(&mut env, e);
            ptr::null_mut()
        }
    }
}
