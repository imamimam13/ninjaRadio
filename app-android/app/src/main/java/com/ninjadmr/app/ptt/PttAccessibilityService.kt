package com.ninjadmr.app.ptt

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Hardware PTT trigger for Android HT POC radios (Inrico, Ellpplus, Zello radios, etc.).
 *
 * Intercepts side physical PTT buttons (PTT, Volume Up/Down, Stem, Headset, F1/F2)
 * so holding the physical side button transmits and releasing stops even while screen is locked.
 */
class PttAccessibilityService : AccessibilityService() {

    companion object {
        private const val KEYCODE_PTT = 228
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No window content is consumed.
    }

    override fun onInterrupt() {
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val prefs = getSharedPreferences("ninjadmr_app_settings", Context.MODE_PRIVATE)
        val configured = prefs.getInt("hardware_ptt_key_code", 0)

        val isPttKey = if (configured > 0) {
            event.keyCode == configured
        } else {
            PttKeyMap.isUniversalPttKey(event.keyCode)
        }

        if (isPttKey) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                PttController.setPtt(true)
            } else if (event.action == KeyEvent.ACTION_UP) {
                PttController.setPtt(false)
            }
            return true
        }
        return super.onKeyEvent(event)
    }
}
