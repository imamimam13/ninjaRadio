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
