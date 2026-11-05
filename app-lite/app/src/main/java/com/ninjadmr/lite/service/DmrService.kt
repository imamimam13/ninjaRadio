package com.ninjadmr.lite.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.hblink.AmbeVocoder
import com.example.hblink.AmbeVocoders
import com.ninjadmr.lite.MainActivity
import com.ninjadmr.lite.audio.AudioEngine
import com.ninjadmr.lite.audio.AudioLevel
import com.ninjadmr.lite.audio.AudioPreferences
import com.ninjadmr.lite.audio.NinjaAudioFx
import com.ninjadmr.lite.dmr.Dmr
import com.ninjadmr.lite.dmr.DmrCall
import com.ninjadmr.lite.dmr.Dmrd
import com.ninjadmr.lite.dmr.HbpClient
import com.ninjadmr.lite.model.DmrProfile
import com.ninjadmr.lite.model.ProfileStore
import com.ninjadmr.lite.ptt.PttManager

class DmrService : Service(), HbpClient.Callback {
    companion object {
        private const val TAG = "DmrService"
        private const val VOX_TRIGGER_LEVEL = 0.14f
        private const val VOX_HOLD_LEVEL = 0.09f
        private const val VOX_HANG_TIME_MS = 1350L
    }

    interface Listener {
        fun onStateChanged(state: HbpClient.State, info: String)
        fun onRxStateChanged(active: Boolean, srcId: Int, talkgroup: Int = 0)
        fun onTxStateChanged(active: Boolean)
        fun onAudioLevel(level: Float, isTx: Boolean)
        fun onChannelSwitched(profile: DmrProfile, channelIndex: Int, totalChannels: Int) {}
    }

    inner class LocalBinder : Binder() {
        val service: DmrService get() = this@DmrService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var client: HbpClient? = null
    private val audio = AudioEngine()
    private var vocoder: AmbeVocoder = com.example.hblink.NullVocoder()
    private lateinit var profileStore: ProfileStore

    private var wakeLock: PowerManager.WakeLock? = null
    private var pttReceiver: BroadcastReceiver? = null

    var listener: Listener? = null
    var activeProfile: DmrProfile? = null
        private set

    // State tracking
    var isConnected = false
        private set
    var isTx = false
        private set
    var isRx = false
        private set
    var currentRxSrc = 0
        private set
    var currentRxDst = 0
        private set

    // VOX state (for HT Headset Jack / Gateway Auto-TX)
    @Volatile private var isVoxTx = false
    @Volatile private var voxHotFrames = 0
    @Volatile private var lastVoxVoiceMs = 0L
    @Volatile private var rxEndedCooldownMs = 0L

    // TX state
    private var txStream = 0
    private var txSeq = 0
    private var txVseq = 0
    private val txAmbeQueue = ArrayList<ByteArray>()
    private var txCall: DmrCall? = null

    // RX state
    private var rxStream = 0
    private var rxLastMs = 0L

    private val rxTimeoutRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (isRx && now - rxLastMs > 1500) {
                endRx()
            }
            if (isTx && isVoxTx && now - lastVoxVoiceMs > VOX_HANG_TIME_MS) {
                Log.i(TAG, "VOX Hang Time elapsed, ending VOX TX")
                endTx()
            }
            mainHandler.postDelayed(this, 250)
        }
    }

    private val totRunnable = Runnable {
        if (isTx) {
            val totSec = AudioPreferences.getTotSeconds(this@DmrService)
            Log.w(TAG, "TOT Limit (${totSec}s) reached - stopping transmission")
            mainHandler.post {
                Toast.makeText(this@DmrService, "TOT Timeout (${totSec}s) - Transmisi Selesai", Toast.LENGTH_SHORT).show()
            }
            endTx()
        }
    }

    override fun onCreate() {
        super.onCreate()
        vocoder = AmbeVocoders.get(this)
        profileStore = ProfileStore(this)
        updateAudioSettings()
        registerPttReceiver()
        mainHandler.post(rxTimeoutRunnable)
    }

    fun updateAudioSettings() {
        val mic = AudioPreferences.getMicGain(this)
        val spk = AudioPreferences.getSpkGain(this)
        audio.configure(mic, spk)
        syncVoxMonitorState()
    }

    private fun syncVoxMonitorState() {
        val voxOn = AudioPreferences.isVoxEnabled(this)
        if (isConnected && voxOn && !isTx && !isRx) {
            audio.startCapture(this) { pcm ->
                onCaptureData(pcm)
            }
        } else if (!voxOn && !isTx) {
            audio.stopCapture()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceNotification("Standby")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        mainHandler.removeCallbacks(rxTimeoutRunnable)
        disconnect()
        unregisterPttReceiver()
        audio.stopPlayback()
        audio.stopCapture()
        super.onDestroy()
    }

    private fun startForegroundServiceNotification(status: String) {
        val channelId = "dmr_service_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "DMR Service",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("NinjaRadio Lite")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(101, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(101, notification)
        }
    }

    /**
     * Switches to the next (+1) or previous (-1) channel, automatically disconnecting
     * from the current channel and connecting to the new channel like a real HT rotary knob.
     */
    private var lastSwitchTime = 0L

    fun switchChannel(delta: Int): DmrProfile? {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSwitchTime < 350L) {
            Log.w(TAG, "switchChannel ignored by debounce (${now - lastSwitchTime}ms)")
            return null
        }
        lastSwitchTime = now
        val (nextProfile, chNum) = profileStore.cycleProfile(delta)
        if (nextProfile == null) {
            mainHandler.post {
                Toast.makeText(this, "Belum ada channel/profil tersimpan", Toast.LENGTH_SHORT).show()
            }
            return null
        }
        val (_, total) = profileStore.getActiveChannelIndex()
        NinjaAudioFx.playShuriken(this)
        connectWithProfile(nextProfile)
        mainHandler.post {
            listener?.onChannelSwitched(nextProfile, chNum, total)
        }
        return nextProfile
    }

    fun connectWithProfile(profile: DmrProfile) {
        if (client != null) {
            disconnect()
        }
        activeProfile = profile
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NinjaDmr:ServiceWakeLock")
        wakeLock?.acquire(3600 * 1000L) // 1 hour max

        audio.startPlayback(this)

        val hbp = HbpClient(
            host = profile.host,
            port = profile.port,
            peerId = profile.peerId,
            password = profile.password,
            callsign = profile.callsign,
            options = profile.options,
            cb = this,
            location = profile.location,
            software = profile.software,
            hardware = profile.hardware,
            description = profile.description,
            slot = profile.slot,
            talkgroup = profile.talkgroup
        )
        client = hbp
        hbp.start()
        startForegroundServiceNotification("Menghubungkan ke ${profile.name}...")
    }

    fun disconnect() {
        if (isTx) endTx()
        if (isRx) endRx()

        client?.stop()
        client = null
        isConnected = false
        isVoxTx = false

        audio.stopPlayback()
        audio.stopCapture()

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null

        startForegroundServiceNotification("Terputus")
        mainHandler.post {
            listener?.onStateChanged(HbpClient.State.STOPPED, "Terputus")
        }
    }

    // DMR Callbacks
    override fun onState(s: HbpClient.State, info: String) {
        isConnected = (s == HbpClient.State.CONNECTED)
        mainHandler.post {
            startForegroundServiceNotification(info)
            listener?.onStateChanged(s, info)
            syncVoxMonitorState()
        }
    }

    override fun onDmrd(p: Dmrd) {
        val profile = activeProfile ?: return
        if (isTx) return

        // Filter: Slot and TG
        if (p.slot != profile.slot) return

        val rxGrp = profile.rxGroup.trim()
        val allowAll = rxGrp == "*" || rxGrp.equals("ALL", ignoreCase = true) || profile.talkgroup == 0
        val allowedTgs = if (rxGrp.isNotEmpty() && !allowAll) {
            rxGrp.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
        } else emptySet()

        val tgMatches = allowAll || p.dst == profile.talkgroup || allowedTgs.contains(p.dst)
        if (!p.isPrivate && !tgMatches) return
        if (p.isPrivate && p.dst != profile.dmrId) return
        if (p.src == profile.dmrId) return

        // Check frame type: 2 = Terminator or Voice Header
        if (p.frameType == 2) {
            if (p.dtype == 2) {
                endRx()
                return
            } else if (p.dtype == 1) {
                if (!isRx || p.streamId != rxStream) {
                    beginRx(p)
                }
                return
            }
        }

        // Voice frame
        if (!isRx || p.streamId != rxStream) {
            beginRx(p)
        }
        rxLastMs = System.currentTimeMillis()

        if (vocoder.available) {
            val pcmAll = (vocoder as? com.example.hblink.Md380AmbeVocoder)?.decodeDmrd(p.payload)
            if (pcmAll != null && pcmAll.isNotEmpty()) {
                audio.play(pcmAll)
                val lvl = AudioLevel.of(pcmAll)
                mainHandler.post {
                    listener?.onAudioLevel(lvl, false)
                }
            } else {
                val subframes = Dmr.extractAmbe(p.payload)
                var maxLvl = 0.0f
                for (f in subframes) {
                    val pcm = vocoder.decode(f)
                    maxLvl = maxLvl.coerceAtLeast(AudioLevel.of(pcm))
                    audio.play(pcm)
                }
                mainHandler.post {
                    listener?.onAudioLevel(maxLvl, false)
                }
            }
        }
    }

    private fun beginRx(p: Dmrd) {
        vocoder.resetDecoder()
        rxStream = p.streamId
        rxLastMs = System.currentTimeMillis()
        currentRxSrc = p.src
        currentRxDst = p.dst
        isRx = true
        voxHotFrames = 0
        mainHandler.post {
            listener?.onRxStateChanged(true, p.src, p.dst)
        }
    }

    private fun endRx() {
        if (!isRx) return
        isRx = false
        currentRxSrc = 0
        val lastDst = currentRxDst
        currentRxDst = 0
        rxEndedCooldownMs = System.currentTimeMillis()
        mainHandler.post {
            listener?.onRxStateChanged(false, 0, lastDst)
            listener?.onAudioLevel(0f, false)
            syncVoxMonitorState()
        }
    }

    // TX Logic
    @Volatile private var isTerminatingTx = false

    fun startTx(fromVox: Boolean = false): Boolean {
        Log.i(TAG, "startTx(fromVox=$fromVox) invoked: isConnected=$isConnected, isTx=$isTx, isRx=$isRx")
        val c = client ?: return false
        val profile = activeProfile ?: return false
        if (!isConnected || isTx || isTerminatingTx || !vocoder.available) {
            return false
        }

        // PTT priority: interrupt active RX immediately
        if (isRx) {
            endRx()
        }

        vocoder.resetEncoder()
        txStream = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
        txSeq = 0
        txVseq = 0
        synchronized(txAmbeQueue) { txAmbeQueue.clear() }

        val call = DmrCall(
            src = if (profile.dmrId > 16777215) profile.dmrId / 100 else profile.dmrId,
            dst = profile.talkgroup,
            colorCode = 1,
            isPrivate = false
        )
        txCall = call
        isVoxTx = fromVox
        lastVoxVoiceMs = System.currentTimeMillis()
        isTx = true

        // Play local sidetone
        NinjaAudioFx.playSwordClang(this)

        // Send 1 Voice LC Header frame (0x21) per HomeBrew spec
        sendFrame(c, 2, 1, call.header())

        val started = audio.startCapture(this) { pcm ->
            onCaptureData(pcm)
        }

        if (!started) {
            Log.e(TAG, "audio.startCapture failed! Sending terminator and aborting TX")
            isTx = false
            isVoxTx = false
            sendFrame(c, 2, 2, call.terminator())
            txCall = null
            return false
        }

        mainHandler.removeCallbacks(totRunnable)
        val totSec = AudioPreferences.getTotSeconds(this)
        if (totSec > 0) {
            mainHandler.postDelayed(totRunnable, totSec * 1000L)
        }
        mainHandler.post {
            listener?.onTxStateChanged(true)
        }
        return true
    }

    fun endTx() {
        mainHandler.removeCallbacks(totRunnable)
        if (!isTx || isTerminatingTx) return
        isTerminatingTx = true
        val keepMonitoringVox = AudioPreferences.isVoxEnabled(this) && isConnected
        if (!keepMonitoringVox) {
            audio.stopCapture() // Silence mic immediately unless VOX monitor stays active
        }

        // Play local roger beep sidetone
        NinjaAudioFx.playShuriken(this)

        val c = client
        val call = txCall
        finishTx(c, call)
    }

    private fun finishTx(c: HbpClient?, call: DmrCall?) {
        try {
            if (c != null && call != null) {
                synchronized(txAmbeQueue) {
                    if (txAmbeQueue.isNotEmpty()) {
                        while (txAmbeQueue.size < 3) {
                            txAmbeQueue.add(Dmr.AMBE_SILENCE)
                        }
                        sendVoice(txAmbeQueue[0], txAmbeQueue[1], txAmbeQueue[2])
                        txAmbeQueue.clear()
                    }
                }
                sendFrame(c, 2, 2, call.terminator())
            }
        } catch (e: Exception) {
            Log.e(TAG, "finishTx exception: ${e.message}", e)
        } finally {
            isTx = false
            isVoxTx = false
            voxHotFrames = 0
            isTerminatingTx = false
            txCall = null
        }

        mainHandler.post {
            listener?.onTxStateChanged(false)
            listener?.onAudioLevel(0f, true)
            syncVoxMonitorState()
        }
    }

    private fun onCaptureData(pcm: ShortArray) {
        val lvl = AudioLevel.of(pcm)
        val now = System.currentTimeMillis()

        // If currently in Standby with VOX enabled, check if HT audio triggers VOX TX
        if (!isTx && !isTerminatingTx) {
            if (AudioPreferences.isVoxEnabled(this) && isConnected && !isRx && (now - rxEndedCooldownMs > 500L)) {
                if (lvl >= VOX_TRIGGER_LEVEL) {
                    voxHotFrames++
                    if (voxHotFrames >= 2) {
                        voxHotFrames = 0
                        mainHandler.post {
                            if (!isTx && !isRx && isConnected) {
                                startTx(fromVox = true)
                            }
                        }
                    }
                } else {
                    voxHotFrames = 0
                }
            }
            return
        }

        if (!isTx || isTerminatingTx) return

        if (isVoxTx && lvl >= VOX_HOLD_LEVEL) {
            lastVoxVoiceMs = now
        }

        mainHandler.post {
            listener?.onAudioLevel(lvl, true)
        }

        val ambe = vocoder.encode(pcm)
        synchronized(txAmbeQueue) {
            txAmbeQueue.add(ambe)
            if (txAmbeQueue.size == 3) {
                sendVoice(txAmbeQueue[0], txAmbeQueue[1], txAmbeQueue[2])
                txAmbeQueue.clear()
            }
        }
    }

    private fun sendVoice(a: ByteArray, b: ByteArray, c: ByteArray) {
        val cl = client ?: return
        val call = txCall ?: return
        val v = txVseq
        txVseq = (txVseq + 1) % 6
        val frameType = if (v != 0) 0 else 1
        val payload = Dmr.buildPayload(a, b, c, call.voiceMid(v))
        sendFrame(cl, frameType, v, payload)
    }

    private fun sendFrame(c: HbpClient, frameType: Int, dtype: Int, payload: ByteArray) {
        val profile = activeProfile ?: return
        val dmrd = Dmr.buildDmrd(
            seq = txSeq,
            src = profile.dmrId,
            dst = profile.talkgroup,
            peer = profile.peerId,
            slot = profile.slot,
            isPrivate = false,
            frameType = frameType,
            dtype = dtype,
            streamId = txStream,
            payload = payload
        )
        c.send(dmrd)
        txSeq = (txSeq + 1) and 0xFF
    }

    // Hardware PTT & Channel Knob Intents
    private fun registerPttReceiver() {
        val filter = IntentFilter().apply {
            PttManager.ALL_BROADCAST_ACTIONS.forEach { addAction(it) }
        }

        pttReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val (isDown, isUp, keyCode) = PttManager.parseBroadcast(intent ?: return)
                if (isDown && PttManager.matchesChannelNext(this@DmrService, keyCode, 0)) {
                    switchChannel(+1)
                    return
                }
                if (isDown && PttManager.matchesChannelPrev(this@DmrService, keyCode, 0)) {
                    switchChannel(-1)
                    return
                }
                val matches = PttManager.matches(this@DmrService, keyCode, 0)
                if (matches) {
                    if (AudioPreferences.isPttToggle(this@DmrService)) {
                        if (isDown) {
                            if (isTx) endTx() else startTx()
                        }
                    } else {
                        if (isDown) startTx()
                        else if (isUp) endTx()
                    }
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(pttReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(pttReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "registerPttReceiver failed: ${e.message}")
        }
    }

    private fun unregisterPttReceiver() {
        pttReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
        }
        pttReceiver = null
    }
}
