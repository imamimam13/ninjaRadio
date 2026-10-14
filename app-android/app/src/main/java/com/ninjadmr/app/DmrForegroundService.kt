package com.ninjadmr.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.media.session.MediaSession
import androidx.core.app.NotificationCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import com.ninjadmr.app.ptt.PttController
import com.ninjadmr.app.util.AppLog

/**
 * Keeps a DMR radio session alive in the background and bridges the Android
 * audio engine to the Python `dmr_core.session.Runtime`.
 *
 * Mic bursts (60 ms / 480 samples, already AGC'd by the audio engine) are fed to
 * the vocoder only while Push-To-Talk is active; decoded downstream PCM is polled
 * from the runtime and queued to the playback engine's jitter buffer.
 */
class DmrForegroundService : Service() {

    companion object {
        private const val TAG = "NinjaDMR-Service"
        const val ACTION_START = "com.ninjadmr.app.START"
        const val ACTION_STOP = "com.ninjadmr.app.STOP"
        const val ACTION_PTT_ON = "com.ninjadmr.app.PTT_ON"
        const val ACTION_PTT_OFF = "com.ninjadmr.app.PTT_OFF"
        private const val CHANNEL_ID = "dmr_session"
        private const val NOTIFICATION_ID = 1001

        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_PROTOCOL = "protocol"
        const val EXTRA_DMR_ID = "dmr_id"
        const val EXTRA_CALLSIGN = "callsign"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_TALKGROUP = "talkgroup"
        const val EXTRA_SLOT = "slot"
        const val EXTRA_COLOR_CODE = "color_code"
        const val EXTRA_ESSID = "essid"
        const val EXTRA_SCAN_ENABLED = "scan_enabled"
        const val EXTRA_SCAN_TGS = "scan_tgs"
        const val EXTRA_SCAN_INTERVAL = "scan_interval"
    }

    private val binder = LocalBinder()

    private var audio: AudioEngine? = null
    private var pump: Thread? = null
    private var mediaSession: MediaSession? = null

    @Volatile private var running = false

    @Volatile var ptt = false
        private set

    @Volatile var statusLine: String = "idle"
        private set

    @Volatile private var connectionState: String = "idle"
    @Volatile private var lastRxTs = 0L
    @Volatile private var lastTxTs = 0L
    @Volatile private var rxCount = 0
    @Volatile private var txCount = 0

    private val txQueue = ArrayBlockingQueue<ByteArray>(8)

    // -- scan -------------------------------------------------------------
    @Volatile var scanEnabled = false
        private set
    @Volatile var currentTalkgroup: String = ""
        private set
    private var scanTgs: List<Int> = emptyList()
    private var scanIntervalMs = 10_000L
    private var scanThread: Thread? = null

    inner class LocalBinder : Binder() {
        fun service(): DmrForegroundService = this@DmrForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createChannel()
        PttController.install(set = { setPtt(it) }, state = { ptt })
        setupMediaSession()
    }

    /**
     * Media session lets Bluetooth headsets / wired remotes drive PTT via their
     * play/pause button. Play = transmit, pause/stop = idle.
     */
    private fun setupMediaSession() {
        try {
            val session = MediaSession(this, "NinjaDMR")
            session.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            session.setCallback(object : MediaSession.Callback() {
                override fun onPlay() = setPtt(true)
                override fun onPause() = setPtt(false)
                override fun onStop() = setPtt(false)
            })
            session.isActive = true
            mediaSession = session
        } catch (exc: Exception) {
            AppLog.add("Bluetooth PTT unavailable: ${exc.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSession()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PTT_ON -> setPtt(true)
            ACTION_PTT_OFF -> setPtt(false)
            else -> {
                startInForeground()
                if (!running) startSession(intent)
            }
        }
        return START_STICKY
    }

    fun setPtt(enabled: Boolean) {
        if (enabled && !running) return
        if (ptt == enabled) return
        ptt = enabled
        if (enabled) {
            lastTxTs = System.currentTimeMillis()
            AppLog.add("PTT down — transmitting on TG")
        } else {
            try {
                PyBridge.endCall(this)
            } catch (exc: Exception) {
                AppLog.add("endCall failed: ${exc.message}")
            }
            AppLog.add("PTT up")
        }
        updateNotification()
    }

    /** Combined link status shown in the UI: TX / RX / connection state. */
    fun linkStatus(): String {
        val now = System.currentTimeMillis()
        return when {
            ptt -> "TX"
            now - lastRxTs < 1500 -> "RX"
            else -> connectionState
        }
    }

    fun counters(): Pair<Int, Int> = rxCount to txCount

    /** Configure and (re)start the talkgroup scan loop. */
    fun setScan(enabled: Boolean, tgs: List<Int>, intervalMs: Long = 10_000L) {
        stopScan()
        if (!enabled) {
            AppLog.add("Scan stopped")
            return
        }
        if (tgs.size < 2) {
            AppLog.add("Scan needs >= 2 talkgroups (got ${tgs.size})")
            return
        }
        scanTgs = tgs
        scanIntervalMs = intervalMs.coerceAtLeast(2_000L)
        scanEnabled = true
        AppLog.add("Scan started (${tgs.size} TG, ${scanIntervalMs / 1000}s dwell)")
        scanThread = Thread({ scanLoop() }, "dmr-scan").apply {
            isDaemon = true
            start()
        }
    }

    fun stopScan() {
        scanEnabled = false
        val t = scanThread
        scanThread = null
        t?.interrupt()
        currentTalkgroup = ""
    }

    private fun scanLoop() {
        var index = 0
        while (running && scanEnabled && scanTgs.size > 1) {
            val tg = scanTgs[index % scanTgs.size]
            index++
            try {
                PyBridge.setTalkgroup(this, tg)
                currentTalkgroup = tg.toString()
            } catch (exc: Exception) {
                AppLog.add("Scan retune failed: ${exc.message}")
            }
            try {
                Thread.sleep(scanIntervalMs)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    // -- session ---------------------------------------------------------

    private fun startSession(intent: Intent?) {
        val config = buildConfig(intent)
        running = true
        statusLine = "starting"
        connectionState = "starting"
        scanTgs = (intent?.getStringExtra(EXTRA_SCAN_TGS) ?: "")
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
        scanIntervalMs = intent?.getLongExtra(EXTRA_SCAN_INTERVAL, 10_000L) ?: 10_000L
        pendingScan = intent?.getBooleanExtra(EXTRA_SCAN_ENABLED, false) ?: false
        AppLog.add("Starting session → ${config["host"]}:${config["port"]} (${config["protocol"]})")
        Thread({ doStart(config) }, "dmr-start").start()
    }

    @Volatile private var pendingScan = false

    private fun doStart(config: Map<String, Any>) {
        try {
            PyBridge.ensureStarted(this)
            PyBridge.configure(this, config)
            val status = PyBridge.start(this)
            connectionState = status["state"] ?: "starting"
            statusLine = connectionState
            AppLog.add("Session state: $connectionState")
        } catch (exc: Exception) {
            AppLog.add("Failed to start Python session: ${exc.message}")
            statusLine = "error: ${exc.message}"
            connectionState = "error"
            running = false
            updateNotification()
            return
        }
        if (!running) {
            try {
                PyBridge.stop(this)
            } catch (_: Exception) {
            }
            return
        }

        val engine = AudioEngine(
            onMicFrame = ::onMicFrame,
            onError = { msg -> AppLog.add("audio: $msg"); statusLine = msg },
        )
        engine.startPlayback()
        engine.startCapture()
        audio = engine

        if (pendingScan) setScan(true, scanTgs, scanIntervalMs)

        pump = Thread({ pumpLoop() }, "dmr-pump").apply { start() }
        updateNotification()
    }

    private fun buildConfig(intent: Intent?): Map<String, Any> {
        fun str(key: String, def: String) = intent?.getStringExtra(key) ?: def
        fun num(key: String, def: Int) = str(key, def.toString()).toIntOrNull() ?: def
        val dmrId = str(EXTRA_DMR_ID, "")
        val essid = str(EXTRA_ESSID, "")
        val effective = if (essid.isBlank()) dmrId else "$dmrId${essid.filter { it.isDigit() }.take(2)}"
        return mapOf(
            "protocol" to str(EXTRA_PROTOCOL, "homebrew"),
            "host" to str(EXTRA_HOST, ""),
            "port" to num(EXTRA_PORT, 62031),
            "dmr_id" to (effective.toIntOrNull() ?: 0),
            "callsign" to str(EXTRA_CALLSIGN, ""),
            "password" to str(EXTRA_PASSWORD, ""),
            "talkgroup" to num(EXTRA_TALKGROUP, 9990),
            "slot" to num(EXTRA_SLOT, 2),
            "color_code" to num(EXTRA_COLOR_CODE, 1),
            "subscribed_tgs" to str(EXTRA_TALKGROUP, "9990"),
        )
    }

    private fun pumpLoop() {
        var statusTick = 0
        var notifTick = 0
        while (running) {
            var busy = false
            try {
                val rx = PyBridge.pollRx(this)
                if (rx != null && rx.isNotEmpty()) {
                    audio?.submitRx(bytesToShorts(rx))
                    lastRxTs = System.currentTimeMillis()
                    rxCount++
                    busy = true
                }
                val tx = txQueue.poll()
                if (tx != null) {
                    PyBridge.feedTx(this, tx)
                    lastTxTs = System.currentTimeMillis()
                    txCount++
                    busy = true
                }
                if (++statusTick >= 50) {
                    statusTick = 0
                    val state = PyBridge.status(this)["state"] ?: connectionState
                    if (state != connectionState) {
                        connectionState = state
                        AppLog.add("Session state: $state")
                    }
                }
                if (++notifTick >= 100) {
                    notifTick = 0
                    updateNotification()
                }
            } catch (exc: Exception) {
                AppLog.add("pump error: ${exc.message}")
            }
            if (!busy) {
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun onMicFrame(frame: ShortArray) {
        if (!running || !ptt) return
        // Drop oldest if the bridge is backed up so we stay real-time.
        if (txQueue.remainingCapacity() == 0) txQueue.poll()
        txQueue.offer(shortsToBytes(frame))
    }

    private fun stopSession() {
        stopScan()
        running = false
        ptt = false
        pump?.join(500)
        pump = null
        audio?.stop()
        audio = null
        try {
            PyBridge.stop(this)
        } catch (exc: Exception) {
            AppLog.add("stop failed: ${exc.message}")
        }
        connectionState = "idle"
        statusLine = "idle"
        updateNotification()
        AppLog.add("Session stopped")
    }

    private fun shortsToBytes(samples: ShortArray): ByteArray {
        val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) bb.putShort(s)
        return bb.array()
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = ShortArray(bytes.size / 2)
        for (i in out.indices) out[i] = bb.short
        return out
    }

    // -- notification ----------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE
        val contentIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            flags,
        )
        val stopIntent = Intent(this, DmrForegroundService::class.java).setAction(ACTION_STOP)
        val pendingStop = PendingIntent.getService(this, 0, stopIntent, flags)
        val detail = when {
            ptt -> "Transmitting…"
            linkStatus() == "RX" -> "Receiving…"
            else -> statusLine
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("$connectionState · $detail · RX $rxCount / TX $txCount")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .addAction(0, "Stop", pendingStop)
            .build()
    }

    private fun updateNotification() {
        if (!running && connectionState == "idle") return
        try {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        stopSession()
        PttController.uninstall()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
