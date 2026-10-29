package com.unbihex.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/** Quick settings tile: connects to the selected server or disconnects. */
class UnbihexTileService : TileService() {
    companion object {
        private const val TAG = "UnbihexVpn"

        // Set while the shade shows the tile.
        @Volatile
        private var listening: UnbihexTileService? = null

        /** Redraws a visible tile after a state change; a hidden one is drawn when the shade opens. Main thread. */
        fun update() {
            listening?.render()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        listening = this
        render()
    }

    override fun onStopListening() {
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        if (isLocked) unlockAndRun(::toggle) else toggle()
    }

    private fun toggle() {
        when (UnbihexVpnService.state) {
            UnbihexVpnService.State.DISCONNECTED -> {
                // The permission dialog needs an activity: let the app ask for it.
                if (VpnService.prepare(this) != null) {
                    openApp()
                    return
                }
                try {
                    UnbihexVpnService.connect(this)
                } catch (e: Exception) {
                    // Android may refuse a foreground service start from the background.
                    Log.w(TAG, "tile connect: ${e.message}")
                    openApp()
                }
            }
            UnbihexVpnService.State.CONNECTED -> UnbihexVpnService.disconnect(this)
            UnbihexVpnService.State.CONNECTING -> Unit
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val state = UnbihexVpnService.state
        tile.state = if (state == UnbihexVpnService.State.DISCONNECTED) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                when (state) {
                    UnbihexVpnService.State.DISCONNECTED -> R.string.unbihex_vpn_disconnected
                    UnbihexVpnService.State.CONNECTING -> R.string.unbihex_vpn_connecting
                    UnbihexVpnService.State.CONNECTED -> R.string.unbihex_vpn_connected
                }
            )
        }
        tile.updateTile()
    }

    private fun openApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
