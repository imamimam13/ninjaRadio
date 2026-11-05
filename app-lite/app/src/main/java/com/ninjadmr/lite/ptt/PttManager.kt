package com.ninjadmr.lite.ptt

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.view.KeyEvent

object PttManager {
    private const val PREF_NAME = "ninjadmr_ptt_prefs"
    const val KEY_CUSTOM_CODE = "ptt_custom_code"
    const val KEY_CUSTOM_SCAN = "ptt_custom_scan"
    const val KEY_CUSTOM_NAME = "ptt_custom_name"

    const val KEY_CH_NEXT_CODE = "ch_next_code"
    const val KEY_CH_NEXT_SCAN = "ch_next_scan"
    const val KEY_CH_NEXT_NAME = "ch_next_name"

    const val KEY_CH_PREV_CODE = "ch_prev_code"
    const val KEY_CH_PREV_SCAN = "ch_prev_scan"
    const val KEY_CH_PREV_NAME = "ch_prev_name"

    enum class KeyTarget { PTT, CH_NEXT, CH_PREV }

    const val CARAKA_POC_KEY = 290   // Side Key 1 / PoC
    const val CARAKA_PTT_KEY = 291   // Side Key 2 / DMR
    const val ANDROID_PTT_KEY = 228  // KeyEvent.KEYCODE_PTT
    const val KNOB_NEXT_KEY = 166    // KeyEvent.KEYCODE_CHANNEL_UP
    const val KNOB_PREV_KEY = 167    // KeyEvent.KEYCODE_CHANNEL_DOWN

    // Universal known PoC keycodes & physical triggers across smartphones and POC radios
    private val UNIVERSAL_KEYCODES = intArrayOf(
        228, // Standard Android KEYCODE_PTT
        290, // Caraka / UNIPRO / Generic PoC Side Key 1
        291, // Caraka / UNIPRO DMR Side Key 2
        289, // SOS / Emergency key
        292, // Squelch / Monitor key
        300, // PoC Side Key 3
        301, // KEYCODE_VIDEO_APP_2 (MediaTek PoC)
        302, // KEYCODE_VIDEO_APP_3 (MediaTek PoC)
        261, // KEYCODE_TV_DATA_SERVICE (Sonim dedicated PTT)
        268, // Sonim yellow key
        269, // KEYCODE_TV_TELETEXT
        264, // KEYCODE_STEM_PRIMARY
        149, // KEYCODE_CALCULATOR (Kyocera DuraForce PTT)
        1011,// Samsung Galaxy XCover Key
        1079,// Samsung Top Key
        79,  // KEYCODE_HEADSETHOOK (PTT headset / mic)
        85,  // KEYCODE_MEDIA_PLAY_PAUSE
        130, // KEYCODE_MEDIA_RECORD (Bluetooth PTT buttons like B01 / Dellking)
        27,  // KEYCODE_CAMERA (some rugged devices map PTT to camera)
        24,  // Volume Up
        25   // Volume Down
    )

    private val UNIVERSAL_SCANCODES = intArrayOf(
        249, // PTT_MAIN (KSUN ZL40 / gpio-keys.kl)
        250, // PTT_HEADSET (KSUN ZL40 / gpio-keys.kl)
        216  // DTT_PTT in gpio-keys.kl
    )

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun isCustomKeyConfigured(context: Context): Boolean {
        val p = getPrefs(context)
        return p.getInt(KEY_CUSTOM_CODE, 0) > 0 || p.getInt(KEY_CUSTOM_SCAN, 0) > 0
    }

    fun saveCustomKey(context: Context, keyCode: Int, scanCode: Int, name: String) {
        saveTargetKey(context, KeyTarget.PTT, keyCode, scanCode, name)
    }

    fun saveTargetKey(context: Context, target: KeyTarget, keyCode: Int, scanCode: Int, name: String) {
        val editor = getPrefs(context).edit()
        when (target) {
            KeyTarget.PTT -> {
                editor.putInt(KEY_CUSTOM_CODE, keyCode)
                editor.putInt(KEY_CUSTOM_SCAN, scanCode)
                editor.putString(KEY_CUSTOM_NAME, name)
            }
            KeyTarget.CH_NEXT -> {
                editor.putInt(KEY_CH_NEXT_CODE, keyCode)
                editor.putInt(KEY_CH_NEXT_SCAN, scanCode)
                editor.putString(KEY_CH_NEXT_NAME, name)
            }
            KeyTarget.CH_PREV -> {
                editor.putInt(KEY_CH_PREV_CODE, keyCode)
                editor.putInt(KEY_CH_PREV_SCAN, scanCode)
                editor.putString(KEY_CH_PREV_NAME, name)
            }
        }
        editor.apply()
    }

    fun clearCustomKey(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_CUSTOM_CODE)
            .remove(KEY_CUSTOM_SCAN)
            .remove(KEY_CUSTOM_NAME)
            .apply()
    }

    fun clearChannelKeys(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_CH_NEXT_CODE)
            .remove(KEY_CH_NEXT_SCAN)
            .remove(KEY_CH_NEXT_NAME)
            .remove(KEY_CH_PREV_CODE)
            .remove(KEY_CH_PREV_SCAN)
            .remove(KEY_CH_PREV_NAME)
            .apply()
    }

    fun clearAllKeys(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    fun getCustomKeyDescription(context: Context): String {
        val p = getPrefs(context)
        val name = p.getString(KEY_CUSTOM_NAME, null)
        val code = p.getInt(KEY_CUSTOM_CODE, 0)
        val scan = p.getInt(KEY_CUSTOM_SCAN, 0)
        if (code > 0 || scan > 0) {
            return "${name ?: formatKeyName(code)} (code:$code)"
        }
        return "Auto-Detect (Semua Tombol PoC)"
    }

    fun getChannelNextDescription(context: Context): String {
        val p = getPrefs(context)
        val name = p.getString(KEY_CH_NEXT_NAME, null)
        val code = p.getInt(KEY_CH_NEXT_CODE, 0)
        val scan = p.getInt(KEY_CH_NEXT_SCAN, 0)
        if (code > 0 || scan > 0) {
            return "${name ?: formatKeyName(code)} (code:$code)"
        }
        return "Knob CH+ / PageUp (Default)"
    }

    fun getChannelPrevDescription(context: Context): String {
        val p = getPrefs(context)
        val name = p.getString(KEY_CH_PREV_NAME, null)
        val code = p.getInt(KEY_CH_PREV_CODE, 0)
        val scan = p.getInt(KEY_CH_PREV_SCAN, 0)
        if (code > 0 || scan > 0) {
            return "${name ?: formatKeyName(code)} (code:$code)"
        }
        return "Knob CH- / PageDown (Default)"
    }

    fun matchesChannelNext(context: Context, keyCode: Int, scanCode: Int): Boolean {
        if (keyCode <= 0 && scanCode <= 0) return false
        val p = getPrefs(context)
        val nextCode = p.getInt(KEY_CH_NEXT_CODE, 0)
        val nextScan = p.getInt(KEY_CH_NEXT_SCAN, 0)
        if (nextCode > 0 && keyCode == nextCode) return true
        if (nextScan > 0 && scanCode == nextScan) return true
        if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP || 
            keyCode == KeyEvent.KEYCODE_PAGE_UP ||
            keyCode == 294 || // Radio Knob Up (Qualcomm / JZDMR)
            keyCode == 292 || // Knob Up alternate
            keyCode == 135    // F5 / Knob Up (ln2.java)
        ) return true
        return false
    }

    fun matchesChannelPrev(context: Context, keyCode: Int, scanCode: Int): Boolean {
        if (keyCode <= 0 && scanCode <= 0) return false
        val p = getPrefs(context)
        val prevCode = p.getInt(KEY_CH_PREV_CODE, 0)
        val prevScan = p.getInt(KEY_CH_PREV_SCAN, 0)
        if (prevCode > 0 && keyCode == prevCode) return true
        if (prevScan > 0 && scanCode == prevScan) return true
        if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN || 
            keyCode == KeyEvent.KEYCODE_PAGE_DOWN ||
            keyCode == 295 || // Radio Knob Down (Qualcomm / JZDMR)
            keyCode == 293 || // Knob Down alternate
            keyCode == 136    // F6 / Knob Down (ln2.java)
        ) return true
        return false
    }

    fun isDedicatedPttKey(keyCode: Int): Boolean {
        return when (keyCode) {
            228, 261, 290, 291, 300, 301, 302, 149, 1011 -> true
            else -> false
        }
    }

    fun isUniversalPttKey(keyCode: Int): Boolean {
        if (UNIVERSAL_KEYCODES.contains(keyCode)) return true
        if (keyCode in 131..142) return true // F1..F12
        if (keyCode in 188..203) return true // Button 1..16
        if (keyCode in 284..302) return true // MediaTek / ODM POC
        return false
    }

    fun matches(context: Context, keyCode: Int, scanCode: Int): Boolean {
        // Never trigger PTT if this key is assigned to Channel Next or Channel Prev!
        if (matchesChannelNext(context, keyCode, scanCode)) return false
        if (matchesChannelPrev(context, keyCode, scanCode)) return false

        val p = getPrefs(context)
        val customCode = p.getInt(KEY_CUSTOM_CODE, 0)
        val customScan = p.getInt(KEY_CUSTOM_SCAN, 0)

        // 1. If user configured a specific key, match it
        if (customCode > 0 && keyCode == customCode) return true
        if (customScan > 0 && scanCode == customScan) return true

        // 2. Always accept dedicated physical radio PTT keys even if custom is set
        if (isDedicatedPttKey(keyCode)) return true

        // 3. Otherwise fallback to universal PoC keycodes if no custom key configured
        if (customCode <= 0 && customScan <= 0) {
            if (isUniversalPttKey(keyCode)) return true
            if (UNIVERSAL_SCANCODES.contains(scanCode)) return true
        }

        return false
    }

    val KNOB_NEXT_ACTIONS = setOf(
        "com.android.knob.right",
        "android.intent.action.KNOB_RIGHT",
        "com.inrico.knob.up",
        "com.inrico.action.CHANNEL_UP",
        "unipro.hotkey.knob.up",
        "com.poc.knob.up",
        "com.runbo.knob.right"
    )

    val KNOB_PREV_ACTIONS = setOf(
        "com.android.knob.left",
        "android.intent.action.KNOB_LEFT",
        "com.inrico.knob.down",
        "com.inrico.action.CHANNEL_DOWN",
        "unipro.hotkey.knob.down",
        "com.poc.knob.down",
        "com.runbo.knob.left"
    )

    val PTT_DOWN_ACTIONS = setOf(
        // MediaTek / Caraka / Generic POC
        "com.android.ptt.key.down",
        "com.android.poc.key.down",
        "com.android.ptt.down",
        "android.intent.action.PTT.down",
        "android.intent.action.PTT_BUTTON_DOWN",
        "android.intent.action.PTT_DOWN",
        "android.intent.action.PTT_BUTTON",
        "com.android.ptt.key",
        "android.intent.action.PTT",

        // Qualcomm / UNIPRO
        "unipro.hotkey.ptt.down",

        // Inrico & Talkpod
        "com.inrico.ptt.down",
        "com.inrico.action.ptt.down",
        "action.ptt.down",
        "com.poc.ptt.down",

        // Boxchip / SenHaiX / Anysecu / Chongdong / Kylin / ZKRT / Zed3
        "com.chongdong.ptt.action.pttdown",
        "com.kylinservice.ptt.action.pttdown",
        "com.zkrt.ptt.down",
        "com.boxchip.ptt.down",
        "com.zed3.action.PTT_DOWN",

        // Runbo
        "com.runbo.poc.ptt.down",
        "com.runbo.ptt.key.down",
        "com.runbo.action.PTT_DOWN",

        // Hytera PoC
        "com.hytera.action.PTT_DOWN",

        // Caltta / ZTE
        "caltta.action.ptt.down",
        "com.caltta.action.PTT_DOWN",

        // Sonim
        "com.sonim.intent.action.PTT_KEY_DOWN",
        "com.sonim.action.PTT_DOWN",

        // Kyocera DuraForce
        "com.kyocera.action.PTT_DOWN",

        // Telo Systems
        "com.telo.action.PTT_DOWN",

        // Samsung Knox / XCover
        "com.samsung.android.knox.intent.action.HARD_KEY_REPORT",

        // Zello OEM Mode
        "com.zello.ptt.down",
        "com.zello.intent.action.PTT_DOWN",
        "com.zello.action.PTT_BUTTON_PRESSED",

        // Motorola WAVE / Kodiak / ESChat
        "com.motorola.audioptt.ACTION_PTT_KEY_DOWN",
        "com.kodiak.intent.action.PTT_BUTTON_DOWN",
        "com.eschat.action.PTT_DOWN"
    )

    val PTT_UP_ACTIONS = setOf(
        // MediaTek / Caraka / Generic POC
        "com.android.ptt.key.up",
        "com.android.poc.key.up",
        "com.android.ptt.up",
        "android.intent.action.PTT.up",
        "android.intent.action.PTT_BUTTON_UP",
        "android.intent.action.PTT_UP",

        // Qualcomm / UNIPRO
        "unipro.hotkey.ptt.up",

        // Inrico & Talkpod
        "com.inrico.ptt.up",
        "com.inrico.action.ptt.up",
        "action.ptt.up",
        "com.poc.ptt.up",

        // Boxchip / SenHaiX / Anysecu / Chongdong / Kylin / ZKRT / Zed3
        "com.chongdong.ptt.action.pttup",
        "com.kylinservice.ptt.action.pttup",
        "com.zkrt.ptt.up",
        "com.boxchip.ptt.up",
        "com.zed3.action.PTT_UP",

        // Runbo
        "com.runbo.poc.ptt.up",
        "com.runbo.ptt.key.up",
        "com.runbo.action.PTT_UP",

        // Hytera PoC
        "com.hytera.action.PTT_UP",

        // Caltta / ZTE
        "caltta.action.ptt.up",
        "com.caltta.action.PTT_UP",

        // Sonim
        "com.sonim.intent.action.PTT_KEY_UP",
        "com.sonim.action.PTT_UP",

        // Kyocera
        "com.kyocera.action.PTT_UP",

        // Telo Systems
        "com.telo.action.PTT_UP",

        // Zello OEM Mode
        "com.zello.ptt.up",
        "com.zello.intent.action.PTT_UP",
        "com.zello.action.PTT_BUTTON_RELEASED",

        // Motorola WAVE / Kodiak / ESChat
        "com.motorola.audioptt.ACTION_PTT_KEY_UP",
        "com.kodiak.intent.action.PTT_BUTTON_UP",
        "com.eschat.action.PTT_UP"
    )

    val ALL_BROADCAST_ACTIONS: List<String> by lazy {
        (PTT_DOWN_ACTIONS + PTT_UP_ACTIONS + KNOB_NEXT_ACTIONS + KNOB_PREV_ACTIONS +
         setOf("com.meigsmart.meigkeyaccessibility.onkeyevent",
               "key.intent.action.smartkey.shortdefine",
               "key.intent.action.smartkey.longdefine")
        ).toList()
    }

    /**
     * Parses an incoming broadcast intent.
     * Returns Triple(isDown, isUp, derivedKeyCode).
     */
    fun parseBroadcast(intent: Intent): Triple<Boolean, Boolean, Int> {
        val action = intent.action ?: return Triple(false, false, 0)
        if (action == "com.meigsmart.meigkeyaccessibility.onkeyevent") {
            val extraCode = intent.getIntExtra("keyCode", 0).takeIf { it != 0 }
                ?: intent.getIntExtra("keycode", 0).takeIf { it != 0 }
                ?: intent.getIntExtra("key_code", 0).takeIf { it != 0 }
                ?: intent.getIntExtra("code", 0).takeIf { it != 0 }
                ?: intent.getStringExtra("keyCode")?.toIntOrNull()
                ?: intent.getStringExtra("code")?.toIntOrNull()
                ?: 0

            if (extraCode <= 0) return Triple(false, false, 0)

            val mappedCode = when (extraCode) {
                135, 292, 294 -> KNOB_NEXT_KEY
                136, 293, 295 -> KNOB_PREV_KEY
                132, 142, 290 -> CARAKA_POC_KEY
                else -> extraCode
            }

            val op = intent.getIntExtra("keyOperation", -1).takeIf { it != -1 }
                ?: intent.getIntExtra("keyoperation", -1).takeIf { it != -1 }
                ?: intent.getIntExtra("operation", -1).takeIf { it != -1 }
                ?: intent.getIntExtra("keyAction", -1).takeIf { it != -1 }
                ?: intent.getIntExtra("action", -1)

            val isDown = op == 0 || (op == -1 && (mappedCode == KNOB_NEXT_KEY || mappedCode == KNOB_PREV_KEY))
            val isUp = op == 1
            return Triple(isDown, isUp, mappedCode)
        }
        if (KNOB_NEXT_ACTIONS.contains(action)) {
            return Triple(true, false, KNOB_NEXT_KEY)
        }
        if (KNOB_PREV_ACTIONS.contains(action)) {
            return Triple(true, false, KNOB_PREV_KEY)
        }

        var isDown = false
        var isUp = false

        val extraCode = intent.getIntExtra("keycode", 0).takeIf { it != 0 }
            ?: intent.getIntExtra("key_code", 0).takeIf { it != 0 }
            ?: intent.getIntExtra("extra_key_code", 0).takeIf { it != 0 }
            ?: intent.getIntExtra("code", 0).takeIf { it != 0 }

        val keyCode = when {
            extraCode != null -> extraCode
            action.contains("poc", ignoreCase = true) -> CARAKA_POC_KEY
            action.contains("sonim", ignoreCase = true) -> 261
            action.contains("kyocera", ignoreCase = true) -> 149
            action.contains("xcover", ignoreCase = true) -> 1011
            action.contains("inrico", ignoreCase = true) -> 131
            action.contains("ptt", ignoreCase = true) -> CARAKA_PTT_KEY
            else -> ANDROID_PTT_KEY
        }

        if (PTT_DOWN_ACTIONS.contains(action)) {
            val stateBool = intent.hasExtra("state") || intent.hasExtra("down") || intent.hasExtra("is_down")
            val stateDown = intent.getBooleanExtra("state", false) ||
                    intent.getBooleanExtra("down", false) ||
                    intent.getBooleanExtra("pressed", false) ||
                    intent.getBooleanExtra("is_down", false) ||
                    intent.getBooleanExtra("keydown", false)
            val stateInt = intent.getIntExtra("state", -1).takeIf { it != -1 }
                ?: intent.getIntExtra("action", -1).takeIf { it != -1 }
                ?: intent.getIntExtra("status", -1)
            val stateStr = (intent.getStringExtra("state") ?: intent.getStringExtra("action") ?: intent.getStringExtra("event") ?: "").lowercase()

            if (stateBool || stateInt != -1 || stateStr.isNotEmpty()) {
                if (stateDown || stateInt == 1 || stateInt == KeyEvent.ACTION_DOWN || stateStr in listOf("down", "press", "pressed", "true", "1")) {
                    isDown = true
                } else if (stateInt == 0 || stateInt == KeyEvent.ACTION_UP || stateStr in listOf("up", "release", "released", "false", "0")) {
                    isUp = true
                } else {
                    isDown = true
                }
            } else {
                isDown = true
            }
        } else if (PTT_UP_ACTIONS.contains(action)) {
            isUp = true
        }

        return Triple(isDown, isUp, keyCode)
    }

    fun isBroadcastDown(action: String): Boolean? {
        if (PTT_DOWN_ACTIONS.contains(action)) return true
        if (PTT_UP_ACTIONS.contains(action)) return false
        return null
    }

    fun formatKeyName(keyCode: Int): String {
        return when (keyCode) {
            0 -> "Auto-Detect (Semua Tombol)"
            228 -> "Android PTT Key (228)"
            290 -> "Caraka / PoC Key 1 (290)"
            291 -> "Caraka / DMR Key 2 (291)"
            300 -> "PoC Side Key 3 (300)"
            289 -> "SOS / Emergency Key (289)"
            292 -> "Knob Up / Monitor (292)"
            294 -> "Rotary Knob Up (294)"
            295 -> "Rotary Knob Down (295)"
            141 -> "Rotary Knob Up F11 (141)"
            142 -> "Rotary Knob Down F12 (142)"
            135 -> "Rotary Knob Up F5 (135)"
            136 -> "Rotary Knob Down F6 (136)"
            261 -> "Sonim Dedicated PTT (261)"
            268, 269 -> "Sonim Extra Key ($keyCode)"
            264 -> "Side Smart Key (264)"
            166 -> "Rotary Knob CH+ (166)"
            167 -> "Rotary Knob CH- (167)"
            92 -> "Page Up / CH+ (92)"
            93 -> "Page Down / CH- (93)"
            25 -> "Volume Down (-)"
            24 -> "Volume Up (+)"
            79 -> "Headset Hook / Kabel HT (79)"
            85 -> "Media Play/Pause (85)"
            130 -> "Bluetooth PTT / Media Record (130)"
            149 -> "Kyocera DuraForce PTT (149)"
            1011 -> "Samsung XCover Key (1011)"
            1079 -> "Samsung Top Key (1079)"
            in 131..142 -> "Function Key F${keyCode - 130} ($keyCode)"
            in 188..203 -> "Hardware Button ${keyCode - 187} ($keyCode)"
            else -> {
                val name = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
                "$name ($keyCode)"
            }
        }
    }
}
