package com.unbihex.client

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import com.unbihex.vpn.UnbihexVpnService

class MainActivity : TauriActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    // Closing the last window ends the process and the VPN with it, so hide the app.
    // Wry's callback, added later, still handles web history first.
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        moveTaskToBack(true)
      }
    })
  }

  override fun onDestroy() {
    // Tauri ends the process right after the last window is gone.
    if (!isChangingConfigurations) UnbihexVpnService.stopBeforeExit()
    super.onDestroy()
  }
}
