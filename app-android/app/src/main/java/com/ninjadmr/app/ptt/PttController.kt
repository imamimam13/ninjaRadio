package com.ninjadmr.app.ptt

import android.content.Intent
import android.view.KeyEvent

/**
 * Process-wide PTT entry point. The running [com.ninjadmr.app.DmrForegroundService]
 * installs a handler so external triggers (volume keys via the accessibility
 * service, Bluetooth/media buttons, hardware PTT broadcasts) can control Push-To-Talk without binding.
 */
object PttController {
    @Volatile
    private var setHandler: ((Boolean) -> Unit)? = null

    @Volatile
    private var stateProvider: (() -> Boolean)? = null

    /**
     * Optional callback for key-learning / configuration dialogs.
     * When any hardware key or system PTT broadcast is detected, this is notified.
     */
    @Volatile
    var onHardwareKeyDetected: ((Int, String) -> Unit)? = null

    fun install(set: (Boolean) -> Unit, state: () -> Boolean) {
        setHandler = set
        stateProvider = state
    }

    fun uninstall() {
        setHandler = null
        stateProvider = null
    }

    fun setPtt(on: Boolean) {
        setHandler?.invoke(on)
    }

    fun toggle() {
        val handler = setHandler ?: return
        handler(!(stateProvider?.invoke() ?: false))
    }

    val available: Boolean
        get() = setHandler != null
}

object PttKeyMap {
    const val CARAKA_POC_KEY = 290   // Side Key 1 / PoC (com.android.poc.key.down)
    const val CARAKA_PTT_KEY = 291   // Side Key 2 / DMR (com.android.ptt.key.down)
    const val CARAKA_POC2_KEY = 300

    val PTT_DOWN_ACTIONS = setOf(
        // MediaTek / Caraka / Generic POC
        "com.android.ptt.key.down",
        "com.android.poc.key.down",
        "com.android.ptt.down",
        "android.intent.action.PTT.down",
        "android.intent.action.PTT_BUTTON_DOWN",
        "android.intent.action.PTT_DOWN",
        "android.intent.action.PTT_BUTTON",

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
        "com.runbo.action.PTT_DOWN",

        // Hytera PoC (PNC series)
        "com.hytera.action.PTT_DOWN",

        // Caltta / ZTE
        "caltta.action.ptt.down",
        "com.caltta.action.PTT_DOWN",

        // Sonim (XP5, XP7, XP8, XP10)
        "com.sonim.intent.action.PTT_KEY_DOWN",
        "com.sonim.action.PTT_DOWN",

        // Kyocera DuraForce
        "com.kyocera.action.PTT_DOWN",

        // Telo Systems
        "com.telo.action.PTT_DOWN",

        // Samsung Knox / XCover
        "com.samsung.android.knox.intent.action.HARD_KEY_REPORT",

        // Zello OEM Mode
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
        "com.zello.intent.action.PTT_UP",
        "com.zello.action.PTT_BUTTON_RELEASED",

        // Motorola WAVE / Kodiak / ESChat
        "com.motorola.audioptt.ACTION_PTT_KEY_UP",
        "com.kodiak.intent.action.PTT_BUTTON_UP",
        "com.eschat.action.PTT_UP"
    )

    /**
     * Determines whether an incoming broadcast intent is a PTT press or release.
     * Returns Triple(isDown: Boolean, isUp: Boolean, derivedKeyCode: Int)
     */
    fun parsePttBroadcast(intent: Intent): Triple<Boolean, Boolean, Int> {
        val action = intent.action ?: return Triple(false, false, 0)
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
            else -> 228
        }

        if (PTT_DOWN_ACTIONS.contains(action)) {
            isDown = true
        } else if (PTT_UP_ACTIONS.contains(action)) {
            isUp = true
        } else {
            val stateBool = intent.getBooleanExtra("state", false) ||
                intent.getBooleanExtra("down", false) ||
                intent.getBooleanExtra("pressed", false) ||
                intent.getBooleanExtra("is_down", false) ||
                intent.getBooleanExtra("keydown", false)
            val stateInt = intent.getIntExtra("state", -1).takeIf { it != -1 }
                ?: intent.getIntExtra("action", -1).takeIf { it != -1 }
                ?: intent.getIntExtra("status", -1)
            val stateStr = (intent.getStringExtra("state") ?: intent.getStringExtra("action") ?: intent.getStringExtra("event") ?: "").lowercase()

            if (stateBool || stateInt == 1 || stateInt == KeyEvent.ACTION_DOWN || stateStr in listOf("down", "press", "pressed", "true", "1")) {
                isDown = true
            } else if (stateInt == 0 || stateInt == KeyEvent.ACTION_UP || stateStr in listOf("up", "release", "released", "false", "0")) {
                isUp = true
            }
        }
        return Triple(isDown, isUp, keyCode)
    }

    /**
     * Checks if an event keycode matches the user's configured keycode.
     * 0 means ANY / ALL keys are allowed.
     */
    fun matchesConfigured(eventCode: Int, configuredCode: Int): Boolean {
        if (configuredCode <= 0) return true
        return configuredCode == eventCode
    }

    /**
     * Universal hardware PTT key detector for ALL Android devices:
     * - Standard smartphones: Volume keys, Headset hook, Media keys, Camera
     * - Dedicated POC & Rugged radios: Inrico, Sonim, Kyocera, Samsung XCover, Caraka, Boxchip, Anysecu, Talkpod
     * - Bluetooth PTT buttons: Dellking, B01, Zello PTT, Bluetooth speaker mics
     */
    fun isUniversalPttKey(keyCode: Int): Boolean {
        return when (keyCode) {
            // Standard Android Phone keys
            KeyEvent.KEYCODE_VOLUME_DOWN,      // 25
            KeyEvent.KEYCODE_VOLUME_UP,        // 24
            KeyEvent.KEYCODE_HEADSETHOOK,      // 79 (Wired headset / inline mic button)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, // 85
            KeyEvent.KEYCODE_MEDIA_RECORD,     // 130 (Bluetooth PTT buttons like B01 / Dellking)
            KeyEvent.KEYCODE_STEM_PRIMARY,     // 264 (Wear / rugged handset side smart key)
            KeyEvent.KEYCODE_CAMERA,           // 27
            KeyEvent.KEYCODE_FOCUS,            // 80

            // Official Android PTT key
            228,                               // KEYCODE_PTT (Standard Android PTT)

            // Kyocera DuraForce programmable side key
            149,                               // KEYCODE_CALCULATOR (Kyocera PTT)

            // Sonim Rugged Radios (XP5 / XP7 / XP8 / XP10)
            261,                               // KEYCODE_TV_DATA_SERVICE (Sonim dedicated PTT)
            268,                               // KEYCODE_TV_RADIO_SERVICE (Sonim yellow key)
            269,                               // KEYCODE_TV_TELETEXT

            // Function keys (Inrico, Runbo, Bluetooth speaker mics)
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12, // 131..142

            // Generic hardware buttons / HID gamepads / Bluetooth triggers
            in KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16, // 188..203
            KeyEvent.KEYCODE_BUTTON_L1,        // 102
            KeyEvent.KEYCODE_BUTTON_R1,        // 103
            KeyEvent.KEYCODE_BUTTON_L2,        // 104
            KeyEvent.KEYCODE_BUTTON_R2,        // 105

            // MediaTek & ODM POC Rugged Radios (Caraka, Boxchip, Anysecu, Talkpod, SenHaiX, Telo)
            in 284..300,                        // 289(SOS), 290(PTT 1), 291(PTT 2), 292(SQ), 300(POC2), etc.

            // Samsung Galaxy XCover / Active series
            1011,                              // XCover key
            1079                               // Top programmable key
            -> true
            else -> false
        }
    }

    /**
     * Returns a human-friendly universal label for any keycode on any Android phone.
     */
    fun formatKeyName(keyCode: Int): String {
        return when (keyCode) {
            0 -> "ALL / ANY KEY (Semua Tombol)"
            228 -> "Dedicated PTT (228)"
            290 -> "Caraka / PoC Key 1 (290)"
            291 -> "Caraka / DMR Key 2 (291)"
            300 -> "PoC Side Key 3 (300)"
            289 -> "SOS / Emergency Key (289)"
            292 -> "Squelch / Monitor Key (292)"
            261 -> "Sonim PTT Key (261)"
            268, 269 -> "Sonim Extra Key ($keyCode)"
            264 -> "Side Smart Key (264)"
            25 -> "Volume Down (-)"
            24 -> "Volume Up (+)"
            79 -> "Headset Hook / Inline Mic (79)"
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
