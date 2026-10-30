package com.ninjadmr.app



import android.app.Notification

import android.app.NotificationChannel

import android.app.NotificationManager

import android.app.PendingIntent

import android.app.Service

import android.content.Context

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager

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



    @Volatile var vocoder: String = "not started"

        private set



    @Volatile private var connectionState: String = "idle"

    @Volatile private var lastRxTs = 0L

    @Volatile private var lastTxTs = 0L

    @Volatile private var rxCount = 0

    @Volatile private var txCount = 0



    private val txQueue = ArrayBlockingQueue<ByteArray>(8)

    private lateinit var contactsStore: com.ninjadmr.app.contacts.ContactsStore
    private lateinit var callbookStore: com.ninjadmr.app.contacts.CallbookStore



    // -- scan -------------------------------------------------------------

    @Volatile var scanEnabled = false

        private set

    @Volatile var currentTalkgroup: String = ""
        private set
    @Volatile var currentSlot: String = "2"
        private set
    @Volatile var currentDmrId: String = ""
        private set

    @Volatile var activeCaller: CallerStation? = null

        private set

    fun currentCaller(): CallerStation? {
        val caller = activeCaller ?: return null
        val isRx = (System.currentTimeMillis() - lastRxTs) < 1800
        return if (caller.isReceiving != isRx) {
            caller.copy(isReceiving = isRx)
        } else {
            caller
        }
    }

    private var scanTgs: List<Int> = emptyList()

    private var scanIntervalMs = 10_000L

    private var scanThread: Thread? = null



    inner class LocalBinder : Binder() {

        fun service(): DmrForegroundService = this@DmrForegroundService

    }



    override fun onBind(intent: Intent?): IBinder = binder



    private var pttBroadcastReceiver: BroadcastReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ninjadmr:ptt_net")?.apply {
                setReferenceCounted(false)
            }
        }
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(12 * 60 * 60 * 1000L)
                AppLog.add("WakeLock acquired for background / screen-off DMR")
            }
        } catch (e: Exception) {
            AppLog.add("WakeLock acquire error: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                AppLog.add("WakeLock released")
            }
        } catch (e: Exception) {
            AppLog.add("WakeLock release error: ${e.message}")
        }
    }

    private fun registerPttBroadcastReceiver() {
        if (pttBroadcastReceiver != null) return
        val filter = IntentFilter().apply {
            com.ninjadmr.app.ptt.PttKeyMap.PTT_DOWN_ACTIONS.forEach { addAction(it) }
            com.ninjadmr.app.ptt.PttKeyMap.PTT_UP_ACTIONS.forEach { addAction(it) }
            addAction("android.intent.action.PTT_BUTTON")
            addAction("com.android.ptt.key")
            addAction("android.intent.action.PTT")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                val (isDown, isUp, keyCode) = com.ninjadmr.app.ptt.PttKeyMap.parsePttBroadcast(intent)
                val keyName = com.ninjadmr.app.ptt.PttKeyMap.formatKeyName(keyCode)

                // Notify UI key-learning dialog if active
                com.ninjadmr.app.ptt.PttController.onHardwareKeyDetected?.invoke(keyCode, keyName)

                val prefs = getSharedPreferences("ninjadmr_app_settings", Context.MODE_PRIVATE)
                val configured = prefs.getInt("hardware_ptt_key_code", 0)

                val matches = com.ninjadmr.app.ptt.PttKeyMap.matchesConfigured(keyCode, configured)
                if (matches) {
                    if (isDown) {
                        AppLog.add("Hardware PTT DOWN ($action -> $keyName)")
                        setPtt(true)
                    } else if (isUp) {
                        AppLog.add("Hardware PTT UP ($action -> $keyName)")
                        setPtt(false)
                    }
                }
            }
        }
        pttBroadcastReceiver = receiver
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
            AppLog.add("Universal PTT Broadcast Receiver active")
        } catch (e: Exception) {
            AppLog.add("Failed to register PTT broadcast receiver: ${e.message}")
        }
    }

    private fun unregisterPttBroadcastReceiver() {
        val receiver = pttBroadcastReceiver ?: return
        pttBroadcastReceiver = null
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {}
    }

    override fun onCreate() {

        super.onCreate()

        contactsStore = com.ninjadmr.app.contacts.ContactsStore(this)
        callbookStore = com.ninjadmr.app.contacts.CallbookStore(this)

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

            if (audio?.isCapturing() != true) {

                audio?.startCapture()

            }

            try {

                PyBridge.startCall(this)

            } catch (exc: Exception) {

                AppLog.add("startCall failed: ${exc.message}")

            }

            AppLog.add("PTT down — transmitting on TG (vocoder=$vocoder)")

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

    fun micLevel(): Int = audio?.micRms() ?: 0



    /** Raw session state from Python (idle / RESOLVING / CONNECTED / error). */

    fun sessionState(): String = connectionState



    /** Configure and (re)start the talkgroup scan loop. */

    fun setTalkgroup(talkgroup: Int, slot: Int? = null) {
        currentTalkgroup = talkgroup.toString()
        if (slot != null) {
            currentSlot = slot.toString()
        }
        PyBridge.setTalkgroup(this, talkgroup, slot)
    }

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

        currentTalkgroup = config["talkgroup"]?.toString() ?: "1025"
        currentSlot = config["slot"]?.toString() ?: "2"
        currentDmrId = config["dmr_id"]?.toString() ?: "1022057"

        running = true

        statusLine = "starting"
        acquireWakeLock()

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

            if (status.containsKey("error")) {
                AppLog.add("Session error: ${status["error"]}")
            }

            status["vocoder"]?.let { voc ->

                vocoder = voc

                AppLog.add("vocoder: $voc")

            }

        } catch (exc: Throwable) {

            val err = exc.message ?: exc.toString()

            AppLog.add("Failed to start Python session: $err")

            android.util.Log.e("NinjaDMR", "Failed to start Python session", exc)

            statusLine = "error: $err"

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

        fun str(key: String, def: String): String {

            val v = intent?.getStringExtra(key)

            return if (v.isNullOrBlank()) def else v

        }

        fun num(key: String, def: Int): Int {

            val v = str(key, def.toString())

            return v.toIntOrNull() ?: def

        }

        val rawDmrId = str(EXTRA_DMR_ID, "1022057")

        val essid = str(EXTRA_ESSID, "")

        val dmrId = if (rawDmrId.isBlank()) "1022057" else rawDmrId

        val effective = if (essid.isBlank()) dmrId else "$dmrId${essid.filter { it.isDigit() }.take(2)}"

        val activeTg = str(EXTRA_TALKGROUP, "1025")

        val activeTgInt = activeTg.toIntOrNull() ?: 1025

        val scanEnabledExtra = intent?.getBooleanExtra(EXTRA_SCAN_ENABLED, false) ?: false

        val scanTgsExtra = intent?.getStringExtra(EXTRA_SCAN_TGS) ?: ""

        val extraTgs = if (scanEnabledExtra && scanTgsExtra.isNotBlank()) {
            scanTgsExtra.split(",").mapNotNull { it.trim().toIntOrNull() }
        } else {
            emptyList()
        }

        val allSubscribed = (listOf(activeTgInt) + extraTgs).distinct().joinToString(",")

        return mapOf(

            "protocol" to str(EXTRA_PROTOCOL, "homebrew"),

            "host" to str(EXTRA_HOST, "43.173.13.117.sslip.io"),

            "port" to num(EXTRA_PORT, 62301),

            "dmr_id" to effective,

            "callsign" to str(EXTRA_CALLSIGN, "BD62BOT"),

            "password" to str(EXTRA_PASSWORD, "").ifBlank { "ninjaradio_pass" },

            "talkgroup" to num(EXTRA_TALKGROUP, 1025),

            "slot" to num(EXTRA_SLOT, 2),

            "color_code" to num(EXTRA_COLOR_CODE, 1),

            "subscribed_tgs" to allSubscribed,
            "station_hardware" to com.ninjadmr.app.config.AppSettingsStore(this).stationHardware.ifBlank { "NinjaRadio" },
            "station_software" to com.ninjadmr.app.config.AppSettingsStore(this).stationSoftware.ifBlank { "NinjaRadio" },
            "station_location" to com.ninjadmr.app.config.AppSettingsStore(this).stationLocation.ifBlank { "Mobile, ID" },
            "station_description" to com.ninjadmr.app.config.AppSettingsStore(this).stationDescription.ifBlank { "NinjaDMR" },
            "station_url" to com.ninjadmr.app.config.AppSettingsStore(this).stationUrl.ifBlank { "https://ninjadmr.app" },
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

                val trafficList = PyBridge.drainTraffic(this)

                if (trafficList.isNotEmpty()) {

                    busy = true

                    for (item in trafficList) {

                        val srcId = item["src_id"]?.toString() ?: ""

                        val tg = item["dst_tg"]?.toString() ?: ""

                        val dt = item["data_type"]?.toString() ?: ""

                        val currentTg = currentTalkgroup.ifBlank { "1025" }
                        val isAcceptedTg = tg.isBlank() || currentTg.isBlank() || tg == currentTg ||
                                           tg == "9" || tg == "9990" || tg == "9999" || tg == "16777215" ||
                                           tg == currentDmrId || srcId == currentTg
                        if (!isAcceptedTg) {
                            continue
                        }

                        if (srcId.isNotEmpty()) {

                            lastRxTs = System.currentTimeMillis()

                            val callbookUser = callbookStore.find(srcId)
                            val contactMatch = if (callbookUser == null) contactsStore.loadAll().firstOrNull { it.id == srcId || it.talkgroup == srcId } else null
                            val callsign = callbookUser?.callsign?.takeIf { it.isNotBlank() }
                                ?: contactMatch?.callsign?.takeIf { it.isNotBlank() }
                                ?: "ID $srcId"
                            val name = callbookUser?.name?.takeIf { it.isNotBlank() }
                                ?: contactMatch?.name?.takeIf { it.isNotBlank() }
                                ?: ""
                            val peer = if (name.isNotBlank()) "$callsign ($name)" else callsign
                            statusLine = "RX: $peer"

                            val slotStr = item["slot"]?.toString()?.takeIf { it.isNotBlank() } ?: currentSlot.ifBlank { "2" }
                            val tgStr = tg.ifBlank { currentTalkgroup.ifBlank { "1025" } }

                            activeCaller = CallerStation(
                                callsign = callsign,
                                name = name,
                                dmrId = srcId,
                                talkgroup = tgStr,
                                slot = slotStr,
                                timestamp = System.currentTimeMillis(),
                                isReceiving = true,
                            )

                            if (dt == "0" || dt == "START") {

                                AppLog.add("RX Call ← $peer on TG $tg")

                            }

                        }

                    }

                }

                val tx = txQueue.poll()

                if (tx != null) {

                    val ok = PyBridge.feedTx(this, tx)

                    lastTxTs = System.currentTimeMillis()

                    txCount++

                    if (txCount == 1 || txCount % 15 == 0) {

                        val rms = audio?.micRms() ?: 0

                        AppLog.add("TX burst #$txCount (ok=$ok, micRMS=$rms)")

                    }

                    busy = true

                }

                if (++statusTick >= 50) {

                    statusTick = 0

                    val snap = PyBridge.status(this)

                    val state = snap["state"] ?: connectionState

                    if (state != connectionState) {

                        connectionState = state

                        AppLog.add("Session state: $state")

                    }

                    snap["vocoder"]?.let { if (it != vocoder) vocoder = it }

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

        releaseWakeLock()
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

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

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

        unregisterPttBroadcastReceiver()
        releaseWakeLock()
        PttController.uninstall()

        mediaSession?.release()

        mediaSession = null

        super.onDestroy()

    }

}

