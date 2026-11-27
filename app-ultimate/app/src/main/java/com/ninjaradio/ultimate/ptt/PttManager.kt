package com.ninjaradio.ultimate.ptt

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent

/**
 * Hardware PTT and Knob Key Manager with Strict Protection Guardrails.
 *
 * Invariants:
 * 1. Physical Lock / Power buttons MUST NEVER trigger PTT or be hijacked:
 *    - Keycode 264 (KEYCODE_STEM_PRIMARY)
 *    - Keycode 131 (KEYCODE_F1 on MTK radios)
 *    - Keycode 26 (KEYCODE_POWER)
 *    - Scancode 63 (Linux KEY_F5 / Lock / FN1)
 *    - Scancode 232 (KEY_HOMEPAGE / Lock button)
 * 2. Hardware PTT scancode 216 (DTT_PTT) and keycodes 216/228/290/291 trigger PTT cleanly.
 */
object PttManager {
    private const val PREFS_NAME = "ninja_ultimate_ptt_prefs"
    const val KEY_CUSTOM_CODE = "ptt_custom_code"
    const val KEY_CUSTOM_SCAN = "ptt_custom_scan"
    const val KEY_CUSTOM_NAME = "ptt_custom_name"

    // Default universal known physical PTT keycodes (EXCLUDING lock buttons)
    private val KNOWN_PTT_KEYCODES = intArrayOf(
        216, // Hardware DTT_PTT
        228, // Android KEYCODE_PTT
        290, // PoC Side Key 1
        291, // DMR Side Key 2
        289, // SOS Key
        261, // Sonim dedicated PTT
        268, // Sonim Yellow Key
        149, // Kyocera DuraForce PTT
        1011 // Samsung Galaxy XCover Key
    )

    private val KNOWN_PTT_SCANCODES = intArrayOf(
        216, // DTT_PTT (UNIPRO ZX / Motorola shell)
        249, // PTT_MAIN (KSUN / gpio-keys)
        250  // PTT_HEADSET
    )

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Strict check: Is this key a physical Lock or Power button that MUST BE IGNORED?
     */
    fun isLockOrIgnoredKey(keyCode: Int, scanCode: Int): Boolean {
        if (keyCode == 264 || keyCode == 131 || keyCode == KeyEvent.KEYCODE_POWER) {
            return true
        }
        if (scanCode == 63 || scanCode == 232) {
            return true
        }
        return false
    }

    fun isPttKey(context: Context, keyCode: Int, scanCode: Int): Boolean {
        // Step 1: Reject lock or power keys immediately
        if (isLockOrIgnoredKey(keyCode, scanCode)) {
            return false
        }

        // Step 2: Always recognize Universal / CARAKA Known PTT keys (290, 291, 216, 228)
        if (KNOWN_PTT_SCANCODES.contains(scanCode)) return true
        if (KNOWN_PTT_KEYCODES.contains(keyCode)) return true

        // Step 3: Check custom calibrated key (if user calibrated an extra custom key)
        val prefs = getPrefs(context)
        val customCode = prefs.getInt(KEY_CUSTOM_CODE, 0)
        val customScan = prefs.getInt(KEY_CUSTOM_SCAN, 0)

        if (customCode > 0 && keyCode == customCode) return true
        if (customScan > 0 && scanCode == customScan) return true

        return false
    }

    fun saveCustomKey(context: Context, keyCode: Int, scanCode: Int, name: String): Boolean {
        if (isLockOrIgnoredKey(keyCode, scanCode)) {
            return false // Refuse to save lock button as PTT
        }
        getPrefs(context).edit()
            .putInt(KEY_CUSTOM_CODE, keyCode)
            .putInt(KEY_CUSTOM_SCAN, scanCode)
            .putString(KEY_CUSTOM_NAME, name)
            .apply()
        return true
    }

    fun clearCustomKey(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_CUSTOM_CODE)
            .remove(KEY_CUSTOM_SCAN)
            .remove(KEY_CUSTOM_NAME)
            .apply()
    }

    fun getPttDescription(context: Context): String {
        val prefs = getPrefs(context)
        val name = prefs.getString(KEY_CUSTOM_NAME, null)
        val code = prefs.getInt(KEY_CUSTOM_CODE, 0)
        val scan = prefs.getInt(KEY_CUSTOM_SCAN, 0)
        return when {
            name != null && code > 0 -> "$name ($code)"
            name != null -> name
            code == 290 -> "CARAKA PTT 1 (290)"
            code == 291 -> "CARAKA PTT 2 (291)"
            code == 216 || scan == 216 -> "SIDE KEY (216)"
            code == 228 -> "ANDROID PTT (228)"
            else -> "SIDE KEY (216)"
        }
    }
}
