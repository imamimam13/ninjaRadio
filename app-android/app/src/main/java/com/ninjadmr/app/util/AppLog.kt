package com.ninjadmr.app.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Small process-wide ring buffer of human-readable activity messages, shared
 * between the foreground service, the UI and the vocoder bridge.
 */
object AppLog {
    private const val TAG = "NinjaDMR-Log"
    private const val MAX_ENTRIES = 300

    data class Entry(val ts: Long, val text: String)

    private val entries = ArrayDeque<Entry>()
    private val listeners = CopyOnWriteArrayList<(List<Entry>) -> Unit>()
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun add(text: String) {
        synchronized(this) {
            entries.addLast(Entry(System.currentTimeMillis(), text))
            while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        }
        Log.i(TAG, text)
        val snapshot = snapshot()
        for (l in listeners) {
            try {
                l(snapshot)
            } catch (_: Exception) {
            }
        }
    }

    fun addListener(listener: (List<Entry>) -> Unit) {
        listeners.add(listener)
        listener(snapshot())
    }

    fun removeListener(listener: (List<Entry>) -> Unit) {
        listeners.remove(listener)
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    fun format(list: List<Entry>): String = list.joinToString("\n") { "${stamp.format(Date(it.ts))}  ${it.text}" }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}
