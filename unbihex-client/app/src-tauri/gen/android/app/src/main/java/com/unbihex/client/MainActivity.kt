package com.unbihex.client

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import com.unbihex.vpn.UnbihexVpnService

class MainActivity : TauriActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    // Wry's callback, added later, handles web history first and falls through to this one.
    // Closing the last window ends the process and the VPN with it, so hide the app instead.
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        moveTaskToBack(true)
      }
    })
  }

  override fun onDestroy() {
    // Tauri ends the process right after the last window is gone (swiped from recents).
    if (!isChangingConfigurations) UnbihexVpnService.stopBeforeExit()
    super.onDestroy()
  }
}
