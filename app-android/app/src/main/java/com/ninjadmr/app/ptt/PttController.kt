package com.ninjadmr.app.ptt

/**
 * Process-wide PTT entry point. The running [com.ninjadmr.app.DmrForegroundService]
 * installs a handler so external triggers (volume keys via the accessibility
 * service, Bluetooth/media buttons) can control Push-To-Talk without binding.
 */
object PttController {
    @Volatile
    private var setHandler: ((Boolean) -> Unit)? = null

    @Volatile
    private var stateProvider: (() -> Boolean)? = null

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
    /**
     * Universal hardware PTT key detector for ALL Android devices:
     * - Standard smartphones: Volume keys, Headset hook, Media keys, Camera
     * - Dedicated POC & Rugged radios: Inrico, Sonim, Kyocera, Samsung XCover, Caraka, Boxchip, Anysecu, Talkpod
     * - Bluetooth PTT buttons: Dellking, B01, Zello PTT, Bluetooth speaker mics
     */
    fun isUniversalPttKey(keyCode: Int): Boolean {
        return when (keyCode) {
            // Standard Android Phone keys
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN,      // 25
            android.view.KeyEvent.KEYCODE_VOLUME_UP,        // 24
            android.view.KeyEvent.KEYCODE_HEADSETHOOK,      // 79 (Wired headset / inline mic button)
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, // 85
            android.view.KeyEvent.KEYCODE_MEDIA_RECORD,     // 130 (Bluetooth PTT buttons like B01 / Dellking)
            android.view.KeyEvent.KEYCODE_STEM_PRIMARY,     // 264 (Wear / rugged handset side smart key)
            android.view.KeyEvent.KEYCODE_CAMERA,           // 27
            android.view.KeyEvent.KEYCODE_FOCUS,            // 80

            // Official Android PTT key
            228,                                            // KEYCODE_PTT (Standard Android PTT)

            // Kyocera DuraForce programmable side key
            149,                                            // KEYCODE_CALCULATOR (Kyocera PTT)

            // Sonim Rugged Radios (XP5 / XP7 / XP8)
            261,                                            // KEYCODE_TV_DATA_SERVICE (Sonim dedicated PTT)
            268,                                            // KEYCODE_TV_RADIO_SERVICE (Sonim yellow key)
            269,                                            // KEYCODE_TV_TELETEXT

            // Function keys (Inrico, Runbo, Bluetooth speaker mics)
            in android.view.KeyEvent.KEYCODE_F1..android.view.KeyEvent.KEYCODE_F12, // 131..142

            // Generic hardware buttons / HID gamepads / Bluetooth triggers
            in android.view.KeyEvent.KEYCODE_BUTTON_1..android.view.KeyEvent.KEYCODE_BUTTON_16, // 188..203
            android.view.KeyEvent.KEYCODE_BUTTON_L1,        // 102
            android.view.KeyEvent.KEYCODE_BUTTON_R1,        // 103
            android.view.KeyEvent.KEYCODE_BUTTON_L2,        // 104
            android.view.KeyEvent.KEYCODE_BUTTON_R2,        // 105

            // MediaTek & ODM POC Rugged Radios (Caraka, Boxchip, Anysecu, Talkpod, SenHaiX, Telo)
            in 284..300,                                    // 289(SOS), 290(PTT 1), 291(PTT 2), 292(SQ), 300(POC2), etc.

            // Samsung Galaxy XCover / Active series
            1011,                                           // XCover key
            1079                                            // Top programmable key
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
            290 -> "Side PTT 1 / PoC (290)"
            291 -> "Side PTT 2 / DMR (291)"
            300 -> "Side PTT 2 / PoC 2 (300)"
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
                val name = android.view.KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
                "$name ($keyCode)"
            }
        }
    }
}
