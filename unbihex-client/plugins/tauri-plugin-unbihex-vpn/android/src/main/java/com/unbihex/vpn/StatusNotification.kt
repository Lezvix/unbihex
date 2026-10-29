package com.unbihex.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The foreground notification of [UnbihexVpnService]: state, time connected, speed
 * and a "Disconnect" button. Speed is polled only while the screen is on.
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
    private var ticker: ScheduledExecutorService? = null
    private var last: LongArray? = null

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
            startTicker()
        } else {
            builder.setContentText(service.getString(R.string.unbihex_vpn_connecting))
            stopTicker()
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
        stopTicker()
        since = 0L
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }

    private fun startTicker() {
        if (ticker != null) return
        last = null
        ticker = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay(::tick, 1, 1, TimeUnit.SECONDS)
        }
    }

    private fun stopTicker() {
        ticker?.shutdownNow()
        ticker = null
        connected = null
    }

    private fun tick() {
        if (!power.isInteractive) {
            // A gap would show as one huge burst; start counting anew when the screen is back.
            last = null
            return
        }
        val now = try {
            EngineBridge.stats()
        } catch (e: Throwable) {
            Log.w(TAG, "stats: ${e.message}")
            null
        } ?: return
        val prev = last
        last = now
        if (prev == null) return
        val up = Formatter.formatShortFileSize(service, maxOf(0, now[0] - prev[0]))
        val down = Formatter.formatShortFileSize(service, maxOf(0, now[1] - prev[1]))
        val text = service.getString(R.string.unbihex_vpn_speed, down, up)
        main.post {
            val builder = connected ?: return@post
            manager.notify(ID, builder.setContentText(text).build())
        }
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
