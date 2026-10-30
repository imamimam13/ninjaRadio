package com.ninjadmr.app

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.ninjadmr.app.util.AppLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Thin Kotlin facade over the `dmr_core` Python package.
 *
 * Python is started lazily; every call is funnelled through [core] so the
 * JVM/Kotlin side never touches ctypes or the vocoder directly.
 */
object PyBridge {

    @Volatile
    private var started = false

    @Volatile
    private var nativeTried = false

    @Volatile
    private var runtime: PyObject? = null

    fun ensureStarted(context: Context) {
        if (started) return
        synchronized(this) {
            if (!started) {
                loadNativeLibs()
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(context.applicationContext))
                }
                started = true
                exposeNativeLibDir(context)
            }
        }
    }

    /**
     * Resolves `libopendmr.so` through the platform loader first, so Python's
     * later `dlopen("libopendmr.so")` hits the already-loaded soname even when
     * the library is not extracted to `nativeLibraryDir`.
     */
    private fun loadNativeLibs() {
        if (nativeTried) return
        nativeTried = true
        try {
            System.loadLibrary("opendmr")
            AppLog.add("lib: System.loadLibrary(opendmr) OK")
        } catch (t: Throwable) {
            AppLog.add("lib: System.loadLibrary(opendmr) failed: $t")
        }
    }

    /**
     * Tell the Python vocoder where ``libopendmr.so`` lives so loading does not
     * depend on the platform linker's default search path.
     */
    private fun exposeNativeLibDir(context: Context) {
        val dir = context.applicationInfo?.nativeLibraryDir
        val file = dir?.let { "$it/libopendmr.so" }
        val exists = file != null && java.io.File(file).exists()
        AppLog.add("lib: nativeLibraryDir=$dir fileExists=$exists")
        try {
            if (!dir.isNullOrEmpty()) {
                Python.getInstance()
                    .getModule("dmr_core.vocoder")
                    .callAttr("set_native_lib_dir", dir)
                AppLog.add("lib: set_native_lib_dir($dir) OK")
            }
        } catch (t: Throwable) {
            AppLog.add("lib: set_native_lib_dir failed: $t")
        }
    }

    private fun core(): PyObject {
        check(started) { "Python runtime not started; call ensureStarted() first" }
        return Python.getInstance().getModule("dmr_core")
    }

    fun coreVersion(): String =
        core().get("__version__")?.toJava(String::class.java) ?: "unknown"

    // -- directory -------------------------------------------------------

    private fun jsonListOfMaps(context: Context, module: String, fn: String): List<Map<String, Any>> {
        ensureStarted(context)
        val raw = Python.getInstance()
            .getModule(module)
            .callAttr(fn)
            .toJava(String::class.java) ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                obj.keys().asSequence().associateWith { k ->
                    when (val v = obj.get(k)) {
                        null, JSONObject.NULL -> ""
                        is Number -> v
                        else -> v.toString()
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun directoryMasters(context: Context): List<Map<String, Any>> =
        jsonListOfMaps(context, "dmr_core.directory", "masters_json")

    fun directoryTalkgroups(context: Context): List<Map<String, Any>> =
        jsonListOfMaps(context, "dmr_core.directory", "talkgroups_json")

    /** Runs the native vocoder smoke test; returns a simple String map. */
    fun selfTest(): Map<String, String> {
        val result = core().callAttr("self_test")
        return result.asMap().map { (k, v) -> k.toString() to (v?.toString() ?: "") }.toMap()
    }

    // -- session runtime -------------------------------------------------

    private fun runtime(context: Context): PyObject {
        ensureStarted(context)
        runtime?.let { return it }
        synchronized(this) {
            runtime?.let { return it }
            val cls = Python.getInstance()
                .getModule("dmr_core.session")
                .get("Runtime")
                ?: error("dmr_core.session.Runtime not found")
            val instance = cls.call()
            runtime = instance
            return instance
        }
    }

    /**
     * Sends the session config to Python as JSON.
     *
     * A Kotlin [Map] crosses the Chaquopy bridge as an opaque
     * `java.util.LinkedHashMap`, which Python cannot iterate, so it must be
     * serialised on this side.
     */
    fun configure(context: Context, config: Map<String, Any>) {
        val json = JSONObject()
        for ((key, value) in config) {
            json.put(key, value)
        }
        runtime(context).callAttr("configure", json.toString())
    }

    fun start(context: Context): Map<String, String> {
        val result = runtime(context).callAttr("start") ?: return emptyMap()
        return try {
            result.asMap().map { (k, v) -> k.toString() to (v?.toString() ?: "") }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun stop(context: Context) {
        runtime(context).callAttr("stop")
    }

    fun feedTx(context: Context, pcm: ByteArray): Boolean {
        val result = runtime(context).callAttr("feed_tx", pcm) ?: return false
        return try {
            result.toJava(Boolean::class.javaObjectType) ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun startCall(context: Context): Boolean {
        val result = runtime(context).callAttr("start_call") ?: return false
        return try {
            result.toJava(Boolean::class.javaObjectType) ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun endCall(context: Context) {
        runtime(context).callAttr("end_call")
    }

    fun setTalkgroup(context: Context, talkgroup: Int, slot: Int? = null) {
        if (slot != null) {
            runtime(context).callAttr("set_talkgroup", talkgroup, slot)
        } else {
            runtime(context).callAttr("set_talkgroup", talkgroup)
        }
    }

    fun sendSms(context: Context, dstId: Int, text: String): Boolean {
        val result = runtime(context).callAttr("send_sms", dstId, text) ?: return false
        return try {
            result.toJava(Boolean::class.javaObjectType) ?: false
        } catch (_: Exception) {
            false
        }
    }
    fun sendLocation(context: Context, lat: Double, lon: Double): Boolean {
        val result = runtime(context).callAttr("send_location", lat, lon) ?: return false
        return try {
            result.toJava(Boolean::class.javaObjectType) ?: false
        } catch (_: Exception) {
            false
        }
    }

    /** Returns the next decoded PCM buffer (960 bytes), or null when idle. */
    fun pollRx(context: Context): ByteArray? {
        val result = runtime(context).callAttr("poll_rx") ?: return null
        return try {
            result.toJava(ByteArray::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun drainTraffic(context: Context): List<Map<String, Any>> {
        ensureStarted(context)
        val pyList = runtime(context).callAttr("drain_traffic") ?: return emptyList()
        return try {
            val list = pyList.asList()
            list.mapNotNull { item ->
                try {
                    val map = item.asMap()
                    map.entries.associate { (k, v) ->
                        k.toString() to (v?.toString() ?: "")
                    }
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun status(context: Context): Map<String, String> {
        val result = runtime(context).callAttr("status") ?: return emptyMap()
        return try {
            result.asMap().map { (k, v) -> k.toString() to (v?.toString() ?: "") }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
