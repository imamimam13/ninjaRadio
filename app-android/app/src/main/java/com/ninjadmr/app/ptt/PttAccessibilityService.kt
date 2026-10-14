package com.ninjadmr.app.ptt

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Optional PTT trigger: while enabled, holding the volume-up key transmits and
 * releasing it stops (volume-down also stops). Useful when the screen is off or
 * the device is in a pocket.
 *
 * The service never inspects screen content; it only watches hardware key events.
 */
class PttAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No window content is consumed.
    }

    override fun onInterrupt() {
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    PttController.setPtt(true)
                } else if (event.action == KeyEvent.ACTION_UP) {
                    PttController.setPtt(false)
                }
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (event.repeatCount == 0) {
                    PttController.setPtt(false)
                }
                return true
            }
        }
        return super.onKeyEvent(event)
    }
}
