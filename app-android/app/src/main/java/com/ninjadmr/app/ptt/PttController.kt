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
        "com.android.ptt.key.down",         // Caraka 22 / MediaTek PoC PTT 2
        "com.android.poc.key.down",         // Caraka 22 PoC side key 1
        "com.android.ptt.down",
        "android.intent.action.PTT.down",   // MediaTek & Qualcomm PoC standard
        "com.zkrt.ptt.down",
        "com.kylinservice.ptt.action.pttdown",
        "com.chongdong.ptt.action.pttdown",
        "action.ptt.down",
        "com.poc.ptt.down",
        "com.inrico.ptt.down",
        "com.zed3.action.PTT_DOWN",
        "com.motorola.audioptt.ACTION_PTT_KEY_DOWN"
    )

    val PTT_UP_ACTIONS = setOf(
        "com.android.ptt.key.up",           // Caraka 22 / MediaTek PoC PTT 2
        "com.android.poc.key.up",           // Caraka 22 PoC side key 1
        "com.android.ptt.up",
        "android.intent.action.PTT.up",
        "com.zkrt.ptt.up",
        "com.kylinservice.ptt.action.pttup",
        "com.chongdong.ptt.action.pttup",
        "action.ptt.up",
        "com.poc.ptt.up",
        "com.inrico.ptt.up",
        "com.zed3.action.PTT_UP",
        "com.motorola.audioptt.ACTION_PTT_KEY_UP"
    )

    /**
     * Determines whether an incoming broadcast intent is a PTT press or release.
     * Returns Triple(isDown: Boolean, isUp: Boolean, derivedKeyCode: Int)
     */
    fun parsePttBroadcast(intent: Intent): Triple<Boolean, Boolean, Int> {
        val action = intent.action ?: return Triple(false, false, 0)
        var isDown = false
        var isUp = false
        var keyCode = 228

        if (action.contains("poc", ignoreCase = true)) {
            keyCode = CARAKA_POC_KEY
        } else if (action.contains("ptt", ignoreCase = true)) {
            keyCode = CARAKA_PTT_KEY
        }

        if (PTT_DOWN_ACTIONS.contains(action)) {
            isDown = true
        } else if (PTT_UP_ACTIONS.contains(action)) {
            isUp = true
        } else {
            val stateBool = intent.getBooleanExtra("state", false) ||
                intent.getBooleanExtra("down", false) ||
                intent.getBooleanExtra("pressed", false)
            val stateInt = intent.getIntExtra("state", -1)
            val stateStr = intent.getStringExtra("state") ?: intent.getStringExtra("action") ?: ""
            if (stateBool || stateInt == 1 || stateStr.equals("down", ignoreCase = true) || stateStr.equals("true", ignoreCase = true)) {
                isDown = true
            } else if (!stateBool || stateInt == 0 || stateStr.equals("up", ignoreCase = true) || stateStr.equals("false", ignoreCase = true)) {
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

            // Sonim Rugged Radios (XP5 / XP7 / XP8)
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
            290 -> "Caraka PTT 1 / PoC (290)"
            291 -> "Caraka PTT 2 / DMR (291)"
            300 -> "Caraka Side Key 3 (300)"
            289 -> "SOS / Orange Key (289)"
            292 -> "Squelch / Monitor Key (292)"
            261 -> "Sonim / Rugged PTT (261)"
            268, 269 -> "Sonim Function Key ($keyCode)"
            264 -> "Side Smart Key (264)"
            25 -> "Volume Down (-)"
            24 -> "Volume Up (+)"
            79 -> "Headset / Mic Button (79)"
            85 -> "Media Play/Pause (85)"
            130 -> "Bluetooth PTT (130 - Media Record)"
            149 -> "Kyocera Side PTT (149)"
            1011, 1079 -> "Samsung XCover Key ($keyCode)"
            in 131..142 -> "Function Key F${keyCode - 130} ($keyCode)"
            in 188..203 -> "Hardware Button ${keyCode - 187} ($keyCode)"
            else -> {
                val name = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
                "$name ($keyCode)"
            }
        }
    }
}
