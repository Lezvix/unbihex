package com.unbihex.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The foreground notification of [UnbihexVpnService]: state, time connected, speed
 * and a "Disconnect" button. Speed is polled only while the screen is on, and the
 * notification is redrawn only when its text changes.
 */
internal class StatusNotification(private val service: Service) {
    companion object {
        private const val ID = 0x55_42
        private const val CHANNEL = "vpn_status"
        private const val TAG = "UnbihexVpn"
    }

    private val manager = service.getSystemService(NotificationManager::class.java)
    private val power = service.getSystemService(PowerManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var connected: NotificationCompat.Builder? = null
    private var since = 0L
    private var shownTitle: String? = null
    private var shownText: String? = null
    private var poller: ScheduledExecutorService? = null

    // Screen on/off reach only receivers registered at run time.
    private var screenWatch: BroadcastReceiver? = null

    fun show(state: UnbihexVpnService.State, title: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    service.getString(R.string.unbihex_vpn_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val builder = NotificationCompat.Builder(service, CHANNEL)
            .setSmallIcon(R.drawable.ic_unbihex_vpn)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        openAppIntent()?.let { builder.setContentIntent(it) }
        if (state == UnbihexVpnService.State.CONNECTED) {
            if (since == 0L) since = System.currentTimeMillis()
            builder
                .setContentText(service.getString(R.string.unbihex_vpn_connected))
                .setShowWhen(true)
                .setWhen(since)
                .setUsesChronometer(true)
                .addAction(0, service.getString(R.string.unbihex_vpn_disconnect), disconnectIntent())
            connected = builder
            shownTitle = title
            shownText = null
            watchSpeed()
        } else {
            builder.setContentText(service.getString(R.string.unbihex_vpn_connecting))
            unwatchSpeed()
            since = 0L
        }
        val n = builder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            service.startForeground(ID, n)
        }
    }

    fun hide() {
        unwatchSpeed()
        since = 0L
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }

    private fun watchSpeed() {
        if (screenWatch == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_SCREEN_ON) startPolling() else stopPolling()
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            service.registerReceiver(receiver, filter)
            screenWatch = receiver
        }
        if (power.isInteractive) startPolling()
    }

    private fun unwatchSpeed() {
        screenWatch?.let { service.unregisterReceiver(it) }
        screenWatch = null
        stopPolling()
        connected = null
    }

    private class Sample(val at: Long, val up: Long, val down: Long)

    private fun startPolling() {
        if (poller != null) return
        val executor = Executors.newSingleThreadScheduledExecutor()
        // Each poll counts anew: a gap would show as one huge burst.
        var last: Sample? = null
        executor.scheduleWithFixedDelay({ last = tick(executor, last) }, 1, 1, TimeUnit.SECONDS)
        poller = executor
    }

    private fun stopPolling() {
        poller?.shutdownNow()
        poller = null
    }

    /** Shows the speed since [prev] and returns the new sample. Runs on [executor]. */
    private fun tick(executor: ScheduledExecutorService, prev: Sample?): Sample? {
        val (stats, title) = try {
            (EngineBridge.stats() ?: return null) to (EngineBridge.session() ?: return null)
        } catch (e: Throwable) {
            Log.w(TAG, "stats: ${e.message}")
            return null
        }
        val now = Sample(SystemClock.elapsedRealtime(), stats[0], stats[1])
        val ms = now.at - (prev ?: return now).at
        if (ms <= 0) return now
        val up = Formatter.formatShortFileSize(service, maxOf(0, now.up - prev.up) * 1000 / ms)
        val down = Formatter.formatShortFileSize(service, maxOf(0, now.down - prev.down) * 1000 / ms)
        val text = service.getString(R.string.unbihex_vpn_speed, down, up)
        main.post {
            // A stopped poll may still finish its call.
            if (poller !== executor) return@post
            val builder = connected ?: return@post
            if (title == shownTitle && text == shownText) return@post
            shownTitle = title
            shownText = text
            manager.notify(ID, builder.setContentTitle(title).setContentText(text).build())
        }
        return now
    }

    private fun openAppIntent(): PendingIntent? {
        val intent = service.packageManager.getLaunchIntentForPackage(service.packageName) ?: return null
        return PendingIntent.getActivity(service, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun disconnectIntent(): PendingIntent {
        val intent = Intent(service, UnbihexVpnService::class.java)
            .setAction(UnbihexVpnService.ACTION_DISCONNECT)
        return PendingIntent.getService(service, 1, intent, PendingIntent.FLAG_IMMUTABLE)
    }
}
