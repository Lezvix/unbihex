package com.unbihex.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Owns the TUN interface. The descriptor stays open here; the engine works on a
 * dup() of it, so closing this one (stop or revoke) plus stopping the engine tears
 * the interface down.
 *
 * The app's own package is excluded from the VPN, which keeps the engine's sockets
 * (to the proxy server, DNS and direct sites) out of the tunnel without protect().
 *
 * The app (`START`/`STARTED`/`STOP`) drives the engine itself; the tile, the notification
 * and always-on VPN (`CONNECT`/`DISCONNECT`) leave it to the service. State lives on the
 * main thread; engine calls run on [worker].
 */
class UnbihexVpnService : VpnService() {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    companion object {
        const val ACTION_START = "com.unbihex.vpn.START"
        const val ACTION_STOP = "com.unbihex.vpn.STOP"
        const val ACTION_STARTED = "com.unbihex.vpn.STARTED"
        const val ACTION_CONNECT = "com.unbihex.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.unbihex.vpn.DISCONNECT"
        const val EXTRA_MTU = "mtu"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_PREFIX = "prefix"
        const val EXTRA_DNS = "dns"
        const val EXTRA_SESSION = "session"
        private const val TAG = "UnbihexVpn"

        @Volatile
        var state: State = State.DISCONNECTED
            private set

        /** True while the interface is up. Read by the plugin's `status`. */
        @Volatile
        var isActive: Boolean = false

        /** Called once with (fd, null) or (null, error) after a start request. */
        @Volatile
        var onEstablished: ((Int?, String?) -> Unit)? = null

        // Process-wide, so a disconnect still runs before a connect that follows it.
        private val worker = Executors.newSingleThreadExecutor()

        // Bumped by every start and stop; late results of an older attempt are dropped.
        private var generation = 0

        // Set while the worker starts the engine for the service: a stop meanwhile stops it too.
        private var engineStarting = false

        private var running: UnbihexVpnService? = null

        fun connect(context: Context) {
            val intent = Intent(context, UnbihexVpnService::class.java).setAction(ACTION_CONNECT)
            ContextCompat.startForegroundService(context, intent)
        }

        fun disconnect(context: Context) {
            val intent = Intent(context, UnbihexVpnService::class.java).setAction(ACTION_DISCONNECT)
            try {
                context.startService(intent)
            } catch (_: Exception) {
                // The service is not running: nothing to stop.
            }
        }

        // A foreground service still up when the process exits can leave its notification behind.
        fun stopBeforeExit() {
            running?.stop(stopEngine = true)
        }
    }

    private class TunParams(
        val mtu: Int,
        val address: String,
        val prefix: Int,
        val dns: String,
        val session: String,
    )

    private val main = Handler(Looper.getMainLooper())
    private lateinit var notification: StatusNotification
    private var tun: ParcelFileDescriptor? = null
    private var title = ""

    // The generation of the app's start; `STARTED` counts only while it is current.
    private var appGeneration = -1

    override fun onCreate() {
        super.onCreate()
        title = getString(R.string.unbihex_vpn_name)
        notification = StatusNotification(this)
        running = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startFromApp(intent)
            ACTION_STARTED -> appStarted()
            ACTION_CONNECT, SERVICE_INTERFACE -> connectHere()
            ACTION_DISCONNECT -> stop(stopEngine = true)
            // A null intent is a restart after the process died: the engine is gone too.
            else -> stop(stopEngine = false)
        }
        return START_NOT_STICKY
    }

    private fun startFromApp(intent: Intent) {
        val callback = onEstablished
        onEstablished = null
        if (state == State.CONNECTING) {
            callback?.invoke(null, "VPN уже подключается")
            return
        }
        val gen = ++generation
        val params = TunParams(
            mtu = intent.getIntExtra(EXTRA_MTU, 1500),
            address = intent.getStringExtra(EXTRA_ADDRESS) ?: "10.199.233.1",
            prefix = intent.getIntExtra(EXTRA_PREFIX, 30),
            dns = intent.getStringExtra(EXTRA_DNS) ?: "10.199.233.2",
            session = intent.getStringExtra(EXTRA_SESSION) ?: getString(R.string.unbihex_vpn_name),
        )
        setState(State.CONNECTING, params.session)
        try {
            val fd = establish(params)
            appGeneration = gen
            callback?.invoke(fd, null)
        } catch (e: Exception) {
            Log.e(TAG, "establish failed", e)
            stop(stopEngine = false)
            callback?.invoke(null, e.message ?: e.toString())
        }
    }

    private fun appStarted() {
        if (appGeneration == generation) {
            appGeneration = -1
            setState(State.CONNECTED, title)
        } else if (state == State.DISCONNECTED) {
            // Late news of a stopped attempt; do not keep a service that was created for it.
            stopSelf()
        }
    }

    private fun connectHere() {
        if (state != State.DISCONNECTED) {
            // A foreground-service start must reach startForeground even when nothing changes.
            notification.show(state, title)
            return
        }
        val gen = ++generation
        setState(State.CONNECTING, getString(R.string.unbihex_vpn_name))
        if (prepare(this) != null) {
            fail(getString(R.string.unbihex_vpn_need_app))
            return
        }
        val dir = dataDir.absolutePath
        worker.execute {
            try {
                val args = JSONObject(EngineBridge.tunArgs(dir))
                main.post { if (gen == generation) establishHere(gen, args) }
            } catch (e: Throwable) {
                Log.e(TAG, "no connection to start", e)
                main.post { if (gen == generation) fail(e.message ?: e.toString()) }
            }
        }
    }

    private fun establishHere(gen: Int, args: JSONObject) {
        val params = TunParams(
            mtu = args.getInt("mtu"),
            address = args.getString("address"),
            prefix = args.getInt("prefix"),
            dns = args.getString("dns"),
            session = args.getString("session"),
        )
        val fd = try {
            establish(params)
        } catch (e: Exception) {
            Log.e(TAG, "establish failed", e)
            fail(e.message ?: e.toString())
            return
        }
        setState(State.CONNECTING, params.session)
        engineStarting = true
        worker.execute {
            val error = try {
                EngineBridge.connect(fd)
                null
            } catch (e: Throwable) {
                Log.e(TAG, "engine start failed", e)
                e.message ?: e.toString()
            }
            main.post {
                // A stop since then has already queued the engine stop.
                if (gen != generation) return@post
                engineStarting = false
                if (error != null) fail(error) else setState(State.CONNECTED, params.session)
            }
        }
    }

    private fun establish(params: TunParams): Int {
        closeTun()
        val builder = Builder()
            .setSession(params.session)
            .setMtu(params.mtu)
            .addAddress(params.address, params.prefix)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(params.dns)
        // Route IPv6 into the tunnel too, otherwise it would leak around the VPN.
        try {
            builder.addAddress("fdb5:2f3c:9a17::1", 126)
            builder.addRoute("::", 0)
        } catch (e: Exception) {
            Log.w(TAG, "IPv6 is not available: ${e.message}")
        }
        builder.addDisallowedApplication(packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        val pfd = builder.establish()
            ?: throw IllegalStateException("система не создала VPN-интерфейс (разрешение отозвано?)")
        tun = pfd
        isActive = true
        return pfd.fd
    }

    private fun fail(message: String) {
        Toast.makeText(this, getString(R.string.unbihex_vpn_failed, message), Toast.LENGTH_LONG).show()
        stop(stopEngine = false)
    }

    private fun stop(stopEngine: Boolean) {
        generation++
        closeTun()
        setState(State.DISCONNECTED, title)
        // Queued after a start in progress, so it runs as soon as that one ends.
        if (stopEngine || engineStarting) {
            engineStarting = false
            worker.execute {
                try {
                    EngineBridge.disconnect()
                } catch (e: Throwable) {
                    Log.e(TAG, "engine stop failed", e)
                }
            }
        }
        stopSelf()
    }

    private fun setState(next: State, title: String) {
        state = next
        this.title = title
        if (next == State.DISCONNECTED) notification.hide() else notification.show(next, title)
        UnbihexTileService.update()
    }

    private fun closeTun() {
        isActive = false
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
    }

    override fun onRevoke() {
        // Another VPN took over or the user switched us off in Settings.
        // May come on another thread; the state lives on the main one.
        main.post { stop(stopEngine = true) }
    }

    override fun onDestroy() {
        if (running === this) running = null
        if (state != State.DISCONNECTED) stop(stopEngine = true)
        super.onDestroy()
    }
}
