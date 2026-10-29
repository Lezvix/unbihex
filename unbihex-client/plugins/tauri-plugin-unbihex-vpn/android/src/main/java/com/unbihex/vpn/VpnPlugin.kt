package com.unbihex.vpn

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.activity.result.ActivityResult
import app.tauri.PermissionState
import app.tauri.annotation.ActivityCallback
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.Permission
import app.tauri.annotation.PermissionCallback
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin

@InvokeArg
class StartArgs {
    var mtu: Int = 1500
    var address: String = "10.199.233.1"
    var prefix: Int = 30
    var dns: String = "10.199.233.2"
    var session: String = "Unbihex"
    // Used on iOS only; accepted here so both platforms share one argument type.
    var configJson: String = ""
    var serverAddress: String = ""
}

/**
 * Bridge between Rust and [UnbihexVpnService].
 *
 * `start` asks the user for VPN permission (first run only), starts the service and
 * resolves with the TUN file descriptor once the interface is up. The Unbihex engine
 * then runs inside this app process on that descriptor.
 */
@TauriPlugin(
    permissions = [Permission(strings = [Manifest.permission.POST_NOTIFICATIONS], alias = "notifications")]
)
class VpnPlugin(private val activity: Activity) : Plugin(activity) {
    private var pendingArgs: StartArgs? = null

    @Command
    fun start(invoke: Invoke) {
        val args = invoke.parseArgs(StartArgs::class.java)
        val permission = VpnService.prepare(activity)
        if (permission != null) {
            pendingArgs = args
            startActivityForResult(invoke, permission, "onVpnPermission")
        } else {
            askForNotifications(invoke, args)
        }
    }

    @ActivityCallback
    fun onVpnPermission(invoke: Invoke, result: ActivityResult) {
        val args = pendingArgs
        pendingArgs = null
        if (result.resultCode == Activity.RESULT_OK && args != null) {
            askForNotifications(invoke, args)
        } else {
            invoke.reject("Разрешение на VPN не выдано")
        }
    }

    // A notification posted before the answer stays hidden, so the VPN waits for it.
    private fun askForNotifications(invoke: Invoke, args: StartArgs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            getPermissionState("notifications") != PermissionState.GRANTED
        ) {
            pendingArgs = args
            requestPermissionForAlias("notifications", invoke, "onNotificationPermission")
        } else {
            launch(invoke, args)
        }
    }

    @PermissionCallback
    fun onNotificationPermission(invoke: Invoke) {
        val args = pendingArgs
        pendingArgs = null
        if (args != null) launch(invoke, args) else invoke.reject("Не удалось запустить VPN")
    }

    private fun launch(invoke: Invoke, args: StartArgs) {
        UnbihexVpnService.onEstablished = { fd, error ->
            if (fd != null) {
                val res = JSObject()
                res.put("fd", fd)
                invoke.resolve(res)
            } else {
                invoke.reject(error ?: "Не удалось запустить VPN")
            }
        }
        val intent = Intent(activity, UnbihexVpnService::class.java).apply {
            action = UnbihexVpnService.ACTION_START
            putExtra(UnbihexVpnService.EXTRA_MTU, args.mtu)
            putExtra(UnbihexVpnService.EXTRA_ADDRESS, args.address)
            putExtra(UnbihexVpnService.EXTRA_PREFIX, args.prefix)
            putExtra(UnbihexVpnService.EXTRA_DNS, args.dns)
            putExtra(UnbihexVpnService.EXTRA_SESSION, args.session)
        }
        try {
            activity.startService(intent)
        } catch (e: Exception) {
            UnbihexVpnService.onEstablished = null
            invoke.reject("Не удалось запустить VPN-сервис: ${e.message}")
        }
    }

    @Command
    fun started(invoke: Invoke) {
        if (UnbihexVpnService.state != UnbihexVpnService.State.DISCONNECTED) {
            val intent = Intent(activity, UnbihexVpnService::class.java).apply {
                action = UnbihexVpnService.ACTION_STARTED
            }
            try {
                activity.startService(intent)
            } catch (_: Exception) {
                // The service is gone: nothing to show.
            }
        }
        invoke.resolve()
    }

    @Command
    fun stop(invoke: Invoke) {
        val intent = Intent(activity, UnbihexVpnService::class.java).apply {
            action = UnbihexVpnService.ACTION_STOP
        }
        try {
            activity.startService(intent)
        } catch (_: Exception) {
            // The service is not running: nothing to stop.
        }
        invoke.resolve()
    }

    @Command
    fun status(invoke: Invoke) {
        val active = UnbihexVpnService.isActive
        val res = JSObject()
        res.put("active", active)
        res.put("state", if (active) "connected" else "disconnected")
        invoke.resolve(res)
    }
}
