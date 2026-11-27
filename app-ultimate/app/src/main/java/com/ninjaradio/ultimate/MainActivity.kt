package com.ninjaradio.ultimate

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ninjaradio.ultimate.audio.AudioEngine
import com.ninjaradio.ultimate.model.AppSettings
import com.ninjaradio.ultimate.model.ProfileStore
import com.ninjaradio.ultimate.model.RadioRoom
import com.ninjaradio.ultimate.model.ServerProfile
import com.ninjaradio.ultimate.model.ServerType
import com.ninjaradio.ultimate.model.TgManager
import com.ninjaradio.ultimate.protocol.RoipBridgeClient
import com.ninjaradio.ultimate.protocol.SvxReflectorClient
import com.ninjaradio.ultimate.protocol.dmr.Dmrd
import com.ninjaradio.ultimate.protocol.dmr.HbpClient
import com.ninjaradio.ultimate.protocol.mumble.MumbleClient
import com.ninjaradio.ultimate.ptt.PttManager
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audioEngine: AudioEngine

    private var activeProfile: ServerProfile? = null
    private var svxClient: SvxReflectorClient? = null
    private var hbpClient: HbpClient? = null
    private var roipClient: RoipBridgeClient? = null
    private var mumbleClient: MumbleClient? = null
    private var zelloClient: com.ninjaradio.ultimate.protocol.zello.ZelloClient? = null
    private var ts3Client: com.ninjaradio.ultimate.protocol.ts3.Ts3Client? = null
    private var customPocClient: com.ninjaradio.ultimate.protocol.poc.CustomPocClient? = null
    private var currentRoomName: String = ""

    @Volatile private var isTransmitting = false
    private var qsoStartTime: Long = 0L

    // UI elements
    private var tvSystemInfo: TextView? = null
    private var tvStatusBadge: TextView? = null
    private var tvPttIndicator: TextView? = null
    private var tvServerBadge: TextView? = null
    private var tvTalker: TextView? = null
    private var tvTalkerDetails: TextView? = null
    private var tvTg: TextView? = null
    private var tvSlot: TextView? = null
    private var tvTelemetry: TextView? = null
    private var tvQsoTimer: TextView? = null
    private var tvLevelDbfs: TextView? = null
    private var pbLevel: ProgressBar? = null
    private var tvStatus: TextView? = null
    private var btnPtt: Button? = null
    private var tvHardwarePttBanner: TextView? = null
    private var viewCallerAccent: View? = null
    private var tvAudioMode: TextView? = null
    private var tvRogerBeep: TextView? = null

    // Bottom Navigation for PoC & D-Pad
    private var btnNavRadio: Button? = null
    private var btnNavServer: Button? = null
    private var btnNavTg: Button? = null
    private var btnNavSettings: Button? = null
    private var onLiveRoomsChanged: (() -> Unit)? = null

    // PTT Hardware Calibration Callback
    private var pttCalibrationCallback: ((Int, Int) -> Boolean)? = null
    private var calibrationDialog: Dialog? = null

    // Hardware PTT Broadcast Receiver for PoC Radios (CARAKA, Inrico, Runbo, Sonim, Zello intents)
    private val pttBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            android.util.Log.e("NinjaUltimate", "PttBroadcastReceiver onReceive: action=$action")

            val cb = pttCalibrationCallback
            if (cb != null) {
                if (action.endsWith(".down", ignoreCase = true) || action.endsWith("_DOWN", ignoreCase = true)) {
                    cb(290, 0)
                }
                return
            }

            when (action) {
                "com.android.poc.key.down",
                "com.android.ptt.key.down",
                "com.runbo.ptt.key.down",
                "com.sonim.intent.action.PTT_KEY_DOWN",
                "com.sonim.intent.action.PTT_KEYDOWN",
                "com.sonim.remotecontrol.intent.action.PTT_BUTTON_DOWN",
                "com.motorolasolutions.intent.action.ACTION_PTT_BUTTON_DOWN",
                "com.zello.ptt.down",
                "com.zello.intent.extraButton1.down",
                "com.zello.intent.extraButton2.down" -> {
                    startTransmission()
                }

                "com.android.poc.key.up",
                "com.android.ptt.key.up",
                "com.runbo.ptt.key.up",
                "com.sonim.intent.action.PTT_KEY_UP",
                "com.sonim.intent.action.PTT_KEYUP",
                "com.sonim.remotecontrol.intent.action.PTT_BUTTON_UP",
                "com.motorolasolutions.intent.action.ACTION_PTT_BUTTON_UP",
                "com.zello.ptt.up",
                "com.zello.intent.extraButton1.up",
                "com.zello.intent.extraButton2.up" -> {
                    stopTransmission()
                }
            }
        }
    }

    private fun registerPttReceiver() {
        val filter = IntentFilter().apply {
            addAction("com.android.poc.key.down")
            addAction("com.android.poc.key.up")
            addAction("com.android.ptt.key.down")
            addAction("com.android.ptt.key.up")
            addAction("com.runbo.ptt.key.down")
            addAction("com.runbo.ptt.key.up")
            addAction("com.sonim.intent.action.PTT_KEY_DOWN")
            addAction("com.sonim.intent.action.PTT_KEY_UP")
            addAction("com.sonim.intent.action.PTT_KEYDOWN")
            addAction("com.sonim.intent.action.PTT_KEYUP")
            addAction("com.sonim.remotecontrol.intent.action.PTT_BUTTON_DOWN")
            addAction("com.sonim.remotecontrol.intent.action.PTT_BUTTON_UP")
            addAction("com.motorolasolutions.intent.action.ACTION_PTT_BUTTON_DOWN")
            addAction("com.motorolasolutions.intent.action.ACTION_PTT_BUTTON_UP")
            addAction("com.zello.ptt.down")
            addAction("com.zello.ptt.up")
            addAction("com.zello.intent.extraButton1.down")
            addAction("com.zello.intent.extraButton1.up")
            addAction("com.zello.intent.extraButton2.down")
            addAction("com.zello.intent.extraButton2.up")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(pttBroadcastReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(pttBroadcastReceiver, filter)
            }
        } catch (e: Exception) {
            android.util.Log.e("NinjaUltimate", "Failed to register PTT broadcast receiver: ${e.message}")
        }
    }

    // AMBE Vocoder and Talker Alias
    private var vocoder: com.example.hblink.AmbeVocoder? = null
    private var dmrTxCall: com.ninjaradio.ultimate.protocol.dmr.DmrCall? = null
    private var dmrTxStream: Int = 0
    private var dmrTxSeq: Int = 0
    private var dmrTxVseq: Int = 0
    private val dmrTxAmbeQueue = ArrayList<ByteArray>()
    @Volatile private var lastActiveTalker: com.ninjaradio.ultimate.protocol.dmr.TalkerProfile? = null

    private fun displayActiveTalker(profile: com.ninjaradio.ultimate.protocol.dmr.TalkerProfile) {
        handler.post {
            val curTg = TgManager.getCurrentTg(this@MainActivity)
            val newDisplayName = profile.getDisplayName()
            val newDetails = profile.getDetails(fallbackTg = curTg)

            if (lastActiveTalker == null ||
                lastActiveTalker?.getDisplayName() != newDisplayName ||
                lastActiveTalker?.getDetails(fallbackTg = curTg) != newDetails
            ) {
                lastActiveTalker = profile
                tvTalker?.text = newDisplayName
                tvTalkerDetails?.text = newDetails
                viewCallerAccent?.setBackgroundColor(0xFF10B981.toInt()) // Emerald Green on RX
            }

            if (qsoStartTime == 0L) {
                qsoStartTime = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun displayTalkerIdle(lastProfile: com.ninjaradio.ultimate.protocol.dmr.TalkerProfile? = lastActiveTalker) {
        handler.post {
            viewCallerAccent?.setBackgroundColor(0xFF00F0FF.toInt()) // Cyan Idle
            tvTalker?.text = "STANDBY (IDLE)"
            val curTg = TgManager.getCurrentTg(this@MainActivity)
            if (lastProfile != null && lastProfile.callsign.isNotBlank() && lastProfile.callsign != "STANDBY (IDLE)") {
                val lastInfo = lastProfile.getDisplayName()
                val lastCity = if (lastProfile.city.isNotBlank()) " • ${lastProfile.city}" else ""
                val tgStr = if (curTg.isNotBlank()) "TG $curTg • " else ""
                tvTalkerDetails?.text = "${tgStr}Terakhir: $lastInfo$lastCity".trim()
            } else {
                tvTalkerDetails?.text = if (curTg.isNotBlank()) "TG $curTg • STANDBY" else "STANDBY"
            }
            qsoStartTime = 0L
            updateSmeter(0, isTx = false)
        }
    }

    private val taDecoder = com.ninjaradio.ultimate.protocol.dmr.TalkerAliasDecoder { srcId, alias ->
        com.ninjaradio.ultimate.protocol.dmr.OnlineTalkerResolver.registerAlias(srcId, alias)
        com.ninjaradio.ultimate.protocol.dmr.OnlineTalkerResolver.resolve(srcId) { prof ->
            displayActiveTalker(prof)
        }
    }

    private val timeTickRunnable = object : Runnable {
        override fun run() {
            updateSystemInfo()
            updateQsoTimer()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Enforce full screen before creating view
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        super.onCreate(savedInstanceState)

        // Keep screen on for Radio operation
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Dynamic Orientation: Reverse Landscape for tiny 160x128 PoC (inverted panel), Portrait for CARAKA & standard PoC/smartphones
        val metrics = resources.displayMetrics
        val maxDim = maxOf(metrics.widthPixels, metrics.heightPixels)
        if (maxDim <= 240) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        applyImmersiveFullscreen()
        setContentView(R.layout.activity_main)

        bindViews()
        setupAudioEngine()
        setupListeners()

        checkAudioPermission()

        loadAndConnectActiveProfile()
        registerPttReceiver()
        handler.post(timeTickRunnable)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveFullscreen()
        }
    }

    private fun applyImmersiveFullscreen() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    private fun bindViews() {
        tvSystemInfo = findViewById(R.id.tvSystemInfo)
        tvStatusBadge = findViewById(R.id.tvStatusBadge)
        tvPttIndicator = findViewById(R.id.tvPttIndicator)
        tvServerBadge = findViewById(R.id.tvServerBadge)
        tvTalker = findViewById(R.id.tvTalker)
        tvTalkerDetails = findViewById(R.id.tvTalkerDetails)
        tvTg = findViewById(R.id.tvTg)
        tvSlot = findViewById(R.id.tvSlot)
        tvTelemetry = findViewById(R.id.tvTelemetry)
        tvQsoTimer = findViewById(R.id.tvQsoTimer)
        tvLevelDbfs = findViewById(R.id.tvLevelDbfs)
        pbLevel = findViewById(R.id.pbLevel)
        tvStatus = findViewById(R.id.tvStatus)
        viewCallerAccent = findViewById(R.id.viewCallerAccent)
        tvAudioMode = findViewById(R.id.tvAudioMode)
        tvRogerBeep = findViewById(R.id.tvRogerBeep)

        btnNavRadio = findViewById(R.id.btnNavRadio)
        btnNavServer = findViewById(R.id.btnNavServer)
        btnNavTg = findViewById(R.id.btnNavTg)
        btnNavSettings = findViewById(R.id.btnNavSettings)
        btnPtt = findViewById(R.id.btnPtt) ?: btnNavSettings
        tvHardwarePttBanner = findViewById(R.id.tvHardwarePttBanner)

        // Quick Server Switcher (Left / Right buttons & Badge click)
        findViewById<View?>(R.id.btnServerPrev)?.setOnClickListener { cycleServerProfile(forward = false) }
        findViewById<View?>(R.id.btnServerNext)?.setOnClickListener { cycleServerProfile(forward = true) }
        tvServerBadge?.setOnClickListener { showServerSetupDialog() }
        findViewById<View?>(R.id.layoutServerRow)?.setOnClickListener { showServerSetupDialog() }

        // Quick Talkgroup Switcher (Left / Right buttons & direct click)
        findViewById<View?>(R.id.btnTgPrev)?.setOnClickListener {
            if (activeProfile?.type == ServerType.MUMBLE || activeProfile?.type == ServerType.TS3 || activeProfile?.type == ServerType.ZELLO) {
                showRoomPickerDialog()
            } else {
                cycleTalkgroup(forward = false)
            }
        }
        findViewById<View?>(R.id.btnTgNext)?.setOnClickListener {
            if (activeProfile?.type == ServerType.MUMBLE || activeProfile?.type == ServerType.TS3 || activeProfile?.type == ServerType.ZELLO) {
                showRoomPickerDialog()
            } else {
                cycleTalkgroup(forward = true)
            }
        }
        tvTg?.setOnClickListener { openChannelOrRoomDialog() }
        findViewById<View?>(R.id.layoutTgBar)?.setOnClickListener { openChannelOrRoomDialog() }

        // Quick Talkgroup Chips
        findViewById<View?>(R.id.btnTg1025)?.setOnClickListener { selectTalkgroup("1025") }
        findViewById<View?>(R.id.btnTg91)?.setOnClickListener { selectTalkgroup("91") }
        findViewById<View?>(R.id.btnTg38)?.setOnClickListener { selectTalkgroup("38") }
        findViewById<View?>(R.id.btnTg510)?.setOnClickListener { selectTalkgroup("510") }

        // Bottom Navigation Buttons
        btnNavRadio?.setOnClickListener {
            audioEngine.playKeyBeep()
            Toast.makeText(this, "Mode Radio Standby", Toast.LENGTH_SHORT).show()
        }
        btnNavServer?.setOnClickListener { showServerSetupDialog() }
        btnNavTg?.setOnClickListener { openChannelOrRoomDialog() }
        btnNavSettings?.setOnClickListener { showSettingsDialog() }

        // Large On-Screen PTT Button & Hardware PTT Banner
        val pttTouchListener = View.OnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    startTransmission()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    stopTransmission()
                    true
                }
                else -> false
            }
        }
        btnPtt?.setOnTouchListener(pttTouchListener)
        tvHardwarePttBanner?.isClickable = false
        tvHardwarePttBanner?.isFocusable = false

        // Setup D-pad focus highlight for hardware navigation
        val serverRow = findViewById<View?>(R.id.layoutServerRow)
        val tgBar = findViewById<View?>(R.id.layoutTgBar)

        serverRow?.isFocusable = true
        serverRow?.setOnClickListener { showServerSetupDialog() }
        serverRow?.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.setBackgroundResource(R.drawable.card_zone_caller)
            } else {
                v.setBackgroundColor(0xFF0F192D.toInt())
            }
        }

        tgBar?.isFocusable = true
        tgBar?.setOnClickListener { openChannelOrRoomDialog() }
        tgBar?.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.setBackgroundResource(R.drawable.badge_ts_amber)
            } else {
                v.setBackgroundResource(R.drawable.card_zone_a)
            }
        }

        listOf(btnNavRadio, btnNavServer, btnNavTg, btnNavSettings).forEach { btn ->
            btn?.isFocusable = true
            btn?.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    (v as? Button)?.setBackgroundResource(R.drawable.badge_pill)
                    (v as? Button)?.setTextColor(0xFF00FFA3.toInt())
                } else {
                    (v as? Button)?.setTextColor(0xFFCBD5E1.toInt())
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        val pttTouchHandler = View.OnTouchListener { _, event ->
            android.util.Log.e("NinjaUltimate", "PTT onTouch: action=${event.action}")
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startTransmission()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopTransmission()
                    true
                }
                else -> false
            }
        }
        btnPtt?.setOnTouchListener(pttTouchHandler)
    }

    private fun setupAudioEngine() {
        vocoder = com.example.hblink.Md380AmbeVocoder.tryLoad(this) ?: com.example.hblink.NullVocoder()
        audioEngine = AudioEngine(this) { msg ->
            log("Audio: $msg")
        }
        audioEngine.onRxLevel = { level ->
            handler.post {
                updateSmeter(level, isTx = false)
            }
        }
        audioEngine.onVoxStateChanged = { isTx ->
            handler.post {
                if (isTx) startTransmission() else stopTransmission()
            }
        }
        applySettingsToEngine()
        audioEngine.start()
    }

    private fun applySettingsToEngine() {
        val micGain = AppSettings.getMicGainDb(this)
        val rxGain = AppSettings.getRxGainDb(this)
        val routeStr = AppSettings.getAudioRoute(this)
        val vox = AppSettings.isVoxEnabled(this)
        val voxSens = AppSettings.getVoxSensitivity(this)
        val voxHang = AppSettings.getVoxHangoverMs(this)
        val roger = AppSettings.isRogerBeep(this)
        val keyBeep = AppSettings.isKeyBeep(this)

        audioEngine.micGainDb = micGain
        audioEngine.rxGainDb = rxGain
        audioEngine.currentRoute = try {
            AudioEngine.AudioRoute.valueOf(routeStr)
        } catch (_: Exception) {
            AudioEngine.AudioRoute.SPEAKERPHONE
        }
        audioEngine.voxEnabled = vox
        audioEngine.voxAttackThreshold = voxSens
        audioEngine.voxHangoverMs = voxHang
        audioEngine.rogerBeepEnabled = roger
        audioEngine.keyBeepEnabled = keyBeep

        tvAudioMode?.text = "MIC ${if (micGain >= 0) "+$micGain" else micGain}dB"
        tvRogerBeep?.visibility = if (roger) View.VISIBLE else View.GONE

        // Screen PTT Visibility
        val hideScreenPtt = AppSettings.isHideScreenPtt(this)
        btnPtt?.visibility = if (hideScreenPtt) View.GONE else View.VISIBLE
        findViewById<View?>(R.id.tvHardwarePttBanner)?.visibility = if (hideScreenPtt) View.VISIBLE else View.GONE
    }

    private fun checkAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }
    }

    private fun loadAndConnectActiveProfile() {
        val profile = ProfileStore.getActiveProfile(this)
        connectProfile(profile)
    }

    private fun connectProfile(profile: ServerProfile) {
        disconnectCurrent()
        activeProfile = profile
        ProfileStore.setActiveProfile(this, profile)

        val cleanHost = profile.host.replace(" ", "").trim()
        profile.host = cleanHost

        val badgeTitle = if (profile.name.isNotBlank()) profile.name else profile.type.displayName
        tvServerBadge?.text = "⚡ $badgeTitle • ${profile.type.name}"
        if (cleanHost.isEmpty()) {
            tvStatus?.text = "Server Belum Diatur (Ketuk untuk Isi)"
            tvStatusBadge?.text = "○ DISCONNECTED"
            tvStatusBadge?.setBackgroundResource(R.drawable.badge_ts_amber)
            tvTalker?.text = "STANDBY (IDLE)"
            viewCallerAccent?.setBackgroundColor(0xFF00F0FF.toInt())
            return
        }

        tvStatus?.text = "$cleanHost:${profile.port} (Menghubungkan...)"
        tvStatusBadge?.text = "● CONNECTING"
        tvStatusBadge?.setBackgroundResource(R.drawable.badge_ts_amber)
        tvTalker?.text = "STANDBY (IDLE)"
        viewCallerAccent?.setBackgroundColor(0xFF00F0FF.toInt())

        val isRoomMode = profile.type == ServerType.MUMBLE || profile.type == ServerType.TS3 || profile.type == ServerType.ZELLO
        val roomBtnLabel = if (profile.type == ServerType.ZELLO) "CHANNEL" else if (isRoomMode) "ROOM" else "TG / ROOM"
        btnNavTg?.text = roomBtnLabel

        if (isRoomMode) {
            val defaultName = if (profile.type == ServerType.ZELLO) "NINJARADIOZL" else "Lobby (Ketuk untuk Pilih)"
            val rName = if (currentRoomName.isNotEmpty()) currentRoomName else defaultName
            val prefix = if (profile.type == ServerType.ZELLO) "CHANNEL" else "ROOM"
            tvTg?.text = "$prefix: $rName"
            tvTalkerDetails?.text = "$prefix $rName | ${profile.callsign}"
        } else {
            val curTg = TgManager.getCurrentTg(this)
            val name = TgManager.getTgName(this, curTg)
            val isNum = curTg.all { it.isDigit() }
            val prefix = if (isNum) "TG" else "ROOM"
            val displayLabel = if (curTg.isBlank()) "PILIH TG / ROOM" else if (name.isNotBlank()) "$prefix $curTg • $name" else "$prefix $curTg"
            tvTg?.text = displayLabel
            tvTalkerDetails?.text = if (curTg.isBlank()) "STANDBY | ${profile.callsign}" else "$displayLabel | ${profile.callsign}"
        }
        updatePttIndicator()

        // Configure audio engine sample rate dynamically based on protocol
        val targetRate = when (profile.type) {
            ServerType.MUMBLE -> 48000
            ServerType.SVXLINK, ServerType.ZELLO -> 16000
            ServerType.DMR_DIRECT, ServerType.TS3, ServerType.ALLSTAR, ServerType.ECHOLINK, ServerType.CUSTOM_POC -> 8000
        }
        audioEngine.configure(targetRate)

        // Validate server host
        if (profile.host.trim().isBlank()) {
            tvStatus?.text = "Alamat Host server kosong. Silakan atur di SERVER SETUP"
            tvStatusBadge?.text = "● PERLU HOST"
            tvStatusBadge?.setBackgroundResource(R.drawable.badge_status_offline)
            return
        }

        when (profile.type) {
            ServerType.SVXLINK -> connectSvxLink(profile)
            ServerType.DMR_DIRECT -> connectDmrDirect(profile)
            ServerType.MUMBLE -> connectMumble(profile)
            ServerType.TS3 -> connectTs3(profile)
            ServerType.ZELLO -> connectZello(profile)
            ServerType.ALLSTAR, ServerType.ECHOLINK -> connectRoip(profile)
            ServerType.CUSTOM_POC -> connectCustomPoc(profile)
        }
    }

    private fun disconnectCurrent() {
        stopTransmission()
        svxClient?.disconnect()
        svxClient = null
        hbpClient?.stop()
        hbpClient = null
        roipClient?.stop()
        roipClient = null
        mumbleClient?.disconnect("DISCONNECT_SILENT")
        mumbleClient = null
        zelloClient?.stop()
        zelloClient = null
        ts3Client?.disconnect()
        ts3Client = null
        customPocClient?.stop()
        customPocClient = null
        audioEngine.flush()

        handler.post {
            tvTalker?.text = "STANDBY (IDLE)"
            tvTalkerDetails?.text = "STANDBY"
            tvStatus?.text = "Standby"
            viewCallerAccent?.setBackgroundColor(0xFF00F0FF.toInt())
            updateSmeter(0, isTx = false)
            qsoStartTime = 0L
        }
    }

    // ------------------------------------------------------------- SvxLink Reflector
    private fun connectSvxLink(profile: ServerProfile) {
        svxClient = SvxReflectorClient(object : SvxReflectorClient.Listener {
            override fun onLog(msg: String) { log(msg) }
            override fun onState(state: SvxReflectorClient.State) {
                handler.post {
                    if (activeProfile?.id != profile.id || activeProfile?.type != ServerType.SVXLINK) return@post
                    tvStatus?.text = "${profile.host}:${profile.port} ($state)"
                    if (state == SvxReflectorClient.State.CONNECTED) {
                        tvStatusBadge?.text = "● ONLINE"
                        tvStatusBadge?.setBackgroundResource(R.drawable.badge_status_online)
                    } else {
                        tvStatusBadge?.text = "● OFFLINE"
                        tvStatusBadge?.setBackgroundResource(R.drawable.badge_status_offline)
                    }
                }
            }
            override fun onNodes(nodes: List<String>) {
                log("Online nodes: ${nodes.size}")
            }
            override fun onTalkerStart(tg: Long, callsign: String) {
                val curTg = TgManager.getCurrentTg(this@MainActivity).toLongOrNull() ?: 0L
                if (curTg > 0L && tg != curTg) return
                com.ninjaradio.ultimate.protocol.dmr.OnlineTalkerResolver.resolveCallsign(callsign, tg = "$tg", mode = "SVXLINK") { prof ->
                    displayActiveTalker(prof)
                }
            }
            override fun onTalkerStop(tg: Long, callsign: String) {
                val curTg = TgManager.getCurrentTg(this@MainActivity).toLongOrNull() ?: 0L
                if (curTg > 0L && tg != curTg) return
                displayTalkerIdle()
            }
            override fun onAudio(opus: ByteArray) {
                audioEngine.onOpusPacket(opus)
            }
            override fun onFlush() {
                audioEngine.flush()
            }
        })
        val tg = TgManager.getCurrentTg(this)
        val tgLong = tg.toLongOrNull() ?: 1025L
        svxClient?.connect(profile.host, profile.port, profile.callsign, profile.password, tgLong)
    }

    // ------------------------------------------------------------- DMR Direct HomeBrew
    private fun connectDmrDirect(profile: ServerProfile) {
        val tg = TgManager.getCurrentTg(this)
        val tgNum = tg.toIntOrNull() ?: 1025
        val pass = if (profile.password.isBlank() || profile.password == "PASSWORD") "ninjaradio_pass" else profile.password
        hbpClient = HbpClient(
            host = profile.host,
            port = profile.port,
            peerId = profile.dmrId,
            password = pass,
            callsign = profile.callsign,
            options = "TS2_1=$tgNum;StartTG=$tgNum;",
            cb = object : HbpClient.Callback {
                override fun onDmrd(p: Dmrd) {
                    val prof = activeProfile
                    if (p.src == prof?.dmrId) return // Drop self-echo

                    val curTgStr = TgManager.getCurrentTg(this@MainActivity)
                    val curTgNum = curTgStr.toIntOrNull()
                    val isMatchingTg = (curTgNum != null && curTgNum > 0 && p.dst == curTgNum)
                    val isMyPrivateCall = (p.isPrivate && prof != null && p.dst == prof.dmrId)
                    if (!isMatchingTg && !isMyPrivateCall) {
                        return // Strictly ignore and drop audio & metadata from other talkgroups!
                    }

                    if (p.frameType == 2) {
                        if (p.dtype == 2) {
                            // Voice Terminator
                            displayTalkerIdle()
                        } else if (p.dtype == 1) {
                            // Voice LC Header
                            com.ninjaradio.ultimate.protocol.dmr.OnlineTalkerResolver.resolve(p.src, tg = "${p.dst}", mode = "DMR TS${p.slot}") { prof ->
                                displayActiveTalker(prof)
                            }
                        }
                        return
                    }

                    if (p.frameType > 1) return // Non-voice CSBK/data frame

                    // Voice burst - dynamic talker resolution
                    if (lastActiveTalker?.dmrId != p.src || lastActiveTalker?.callsign?.startsWith("ID ") == true) {
                        com.ninjaradio.ultimate.protocol.dmr.OnlineTalkerResolver.resolve(p.src, tg = "${p.dst}", mode = "DMR TS${p.slot}") { prof ->
                            displayActiveTalker(prof)
                        }
                    }

                    // In-band OTA Talker Alias processing
                    taDecoder.processBurst(p.payload, p.src)

                    // AMBE decoding to PCM & direct audio playback
                    if (p.payload.size >= 33) {
                        val pcm8 = (vocoder as? com.example.hblink.Md380AmbeVocoder)?.decodeDmrd(p.payload) ?: vocoder?.decode(p.payload)
                        if (pcm8 != null && pcm8.isNotEmpty()) {
                            audioEngine.onPcmAudio(pcm8)
                        }
                    }
                }
                override fun onState(s: HbpClient.State, info: String) {
                    handler.post {
                        if (activeProfile?.id != profile.id || activeProfile?.type != ServerType.DMR_DIRECT) return@post
                        tvStatus?.text = "${profile.host}:${profile.port} ($s)"
                        if (s == HbpClient.State.CONNECTED) {
                            tvStatusBadge?.text = "● DMR OK"
                            tvStatusBadge?.setBackgroundResource(R.drawable.badge_status_online)
                        } else {
                            tvStatusBadge?.text = "● OFFLINE"
                            tvStatusBadge?.setBackgroundResource(R.drawable.badge_status_offline)
                        }
                    }
                }
            },
            talkgroup = tgNum
        )
        hbpClient?.start()
    }

    // ------------------------------------------------------------- RoIP Bridge (AllStar / EchoLink / TS3 / Mumble / Zello)
    private fun connectRoip(profile: ServerProfile) {
        val roipPort = if (profile.port > 0) profile.port else 9987
        val curTg = TgManager.getCurrentTg(this).toLongOrNull() ?: 1025L
        roipClient = RoipBridgeClient(
            host = profile.host,
            port = roipPort,
            callsign = profile.callsign,
            dmrId = profile.dmrId,
            initialTg = curTg,
            serverType = profile.type,
            listener = object : RoipBridgeClient.Listener {
                override fun onLog(msg: String) { log(msg) }
                override fun onState(connected: Boolean, info: String) {
                    handler.post {
                        if (activeProfile?.id != profile.id || activeProfile?.type != profile.type) return@post
                        tvStatus?.text = info
                        if (connected) {
                            tvStatusBadge?.text = when (profile.type) {
                                ServerType.TS3 -> "● TS3 OK"
                                ServerType.ZELLO -> "● ZELLO OK"
                                else -> "● ONLINE"
                            }
                            tvStatusBadge?.setBackgroundResource(R.drawable.badge_status_online)
                        } else {
                            tvStatusBadge?.text = "● OFFLINE"
                            tvStatusBadge?.setBackgroundResource(R.drawable.badge_status_offline)
                        }
                    }
                }
                override fun onTalker(callsign: String, tg: Long) {
                    val modeLabel = when (profile.type) {
                        ServerType.TS3 -> "TS3"
                        ServerType.ZELLO -> "ZELLO"
                        else -> profile.type.name
                    }
                    val targetLabel = if (currentRoomName.isNotEmpty()) currentRoomName else "$tg"
                    com.ninjaradio.ultimate.protocol.dmr.OnlineTalkerResolver.resolveCallsign(callsign, tg = targetLabel, mode = modeLabel) { prof ->
                        displayActiveTalker(prof)
                    }
                }
                override fun onPcmAudio(pcm: ShortArray) {
                    audioEngine.onPcmAudio(pcm)
                }
                override fun onFlush() {
                    audioEngine.flush()
                }
            }
        )
        roipClient?.start()
    }

    // ------------------------------------------------------------- Mumble Native Client (Open-Source TLS + Opus)
    private fun connectMumble(profile: ServerProfile) {
        val targetRoom = currentRoomName
        mumbleClient = MumbleClient(
            context = this@MainActivity,
            host = profile.host,
            port = profile.port,
            username = profile.callsign,
            password = profile.password,
            targetRoom = targetRoom,
            listener = object : MumbleClient.Listener {
                override fun onMumbleStateChanged(state: MumbleClient.State, info: String) {
                    handler.post {
                        if (activeProfile?.id != profile.id || activeProfile?.type != ServerType.MUMBLE) return@post
                        tvStatus?.text = "${profile.host}:${profile.port} ($info)"
                        if (state == MumbleClient.State.CONNECTED) {
                            tvStatusBadge?.text = "● MUMBLE OK"
                            tvStatusBadge?.setBackgroundResource(R.drawable.badge_status_online)
                        } else {
                            tvStatusBadge?.text = "● OFFLINE"
                            tvStatusBadge?.setBackgroundResource(R.drawable.badge_status_offline)
                        }
                    }
                }
                override fun onMumbleAudioReceived(opusPacket: ByteArray) {
                    audioEngine.onOpusPacket(opusPacket)
                }
                override fun onMumbleUserTalking(username: String, isTalking: Boolean) {
                    if (isTalking) {
                        val rName = if (currentRoomName.isNotEmpty()) currentRoomName else "ROOM"
                        com.ninjaradio.ultimate.protocol.dmr.OnlineTalkerResolver.resolveCallsign(username, tg = rName, mode = "MUMBLE") { prof ->
                            displayActiveTalker(prof)
                        }
                    } else {
                        displayTalkerIdle()
                    }
                }
                override fun onMumbleChannelListUpdated(channels: Map<Int, String>) {
                    handler.post {
                        log("Mumble channels updated: ${channels.size} channels")
                        val prof = activeProfile
                        if (prof != null && prof.type == ServerType.MUMBLE) {
                            kotlin.concurrent.thread(isDaemon = true) {
                                val list = channels.map { (cid, cname) ->
                                    RadioRoom("mum_$cid", cname, 0L, 0, false, "Channel #$cid")
                                }
                                com.ninjaradio.ultimate.model.RoomManager.saveRooms(this@MainActivity, prof.host, prof.type, list)
                            }
                            onLiveRoomsChanged?.invoke()
                        }
                    }
                }
            }
        )
        mumbleClient?.connect()
    }

    // ------------------------------------------------------------- TeamSpeak 3 (TS3) Native Client
    private fun connectTs3(profile: ServerProfile) {
        ts3Client?.disconnect()
        val rName = if (currentRoomName.isNotEmpty()) currentRoomName else "LOBBY"
        currentRoomName = rName
        tvTg?.text = "ROOM: $rName"
        tvTalkerDetails?.text = "ROOM $rName | ${profile.callsign}"

        val cl = com.ninjaradio.ultimate.protocol.ts3.Ts3Client(
            context = this,
            host = profile.host,
            port = profile.port,
            nickname = profile.callsign,
            serverPassword = profile.password,
            listener = object : com.ninjaradio.ultimate.protocol.ts3.Ts3Client.Listener {
                override fun onState(connected: Boolean, info: String) {
                    handler.post {
                        if (activeProfile?.id != profile.id || activeProfile?.type != ServerType.TS3) return@post
                        tvStatus?.text = info
                        if (connected) {
                            tvStatusBadge?.text = "● TS3 OK"
                            tvStatusBadge?.setTextColor(android.graphics.Color.parseColor("#00FF66"))
                        } else {
                            tvStatusBadge?.text = "○ TS3 OFFLINE"
                            tvStatusBadge?.setTextColor(android.graphics.Color.parseColor("#FF4444"))
                        }
                    }
                }

                override fun onTalker(callsign: String, isTalking: Boolean) {
                    handler.post {
                        if (isTalking) {
                            val curRoom = if (currentRoomName.isNotEmpty()) currentRoomName else "TS3 ROOM"
                            com.ninjaradio.ultimate.protocol.dmr.OnlineTalkerResolver.resolveCallsign(callsign, tg = curRoom, mode = "TS3") { prof ->
                                displayActiveTalker(prof)
                            }
                        } else {
                            displayTalkerIdle()
                        }
                    }
                }

                override fun onVoiceAudio(opusPacket: ByteArray) {
                    audioEngine.onOpusPacket(opusPacket)
                }

                override fun onRoomsLoaded(rooms: List<RadioRoom>) {
                    com.ninjaradio.ultimate.model.RoomManager.saveRooms(this@MainActivity, profile.host, profile.type, rooms)
                    handler.post {
                        onLiveRoomsChanged?.invoke()
                    }
                }

                override fun onLog(msg: String) {
                    log(msg)
                }
            }
        )
        ts3Client = cl
        cl.connect()
    }

    // ------------------------------------------------------------- Zello Native Channel Client
    private fun connectZello(profile: ServerProfile) {
        val rawHost = profile.host.trim()
        val targetChannel = if (currentRoomName.isNotEmpty()) {
            currentRoomName
        } else if (rawHost.isNotBlank() && !rawHost.contains(".") && !rawHost.contains("://")) {
            rawHost
        } else {
            "NINJARADIOZL"
        }
        currentRoomName = targetChannel
        tvTg?.text = "CHANNEL: $targetChannel"
        tvTalkerDetails?.text = "CHANNEL $targetChannel | ${profile.callsign}"

        val effectiveHost = if (rawHost.contains(".") || rawHost.startsWith("ws://") || rawHost.startsWith("wss://")) {
            rawHost
        } else {
            "zello.io"
        }

        zelloClient = com.ninjaradio.ultimate.protocol.zello.ZelloClient(
            channel = targetChannel,
            username = profile.callsign,
            passwordOrTokenOrKey = profile.password,
            host = effectiveHost,
            listener = object : com.ninjaradio.ultimate.protocol.zello.ZelloClient.Listener {
                override fun onLog(msg: String) { log(msg) }
                override fun onState(connected: Boolean, info: String) {
                    handler.post {
                        if (activeProfile?.id != profile.id || activeProfile?.type != profile.type) return@post
                        tvStatus?.text = info
                        if (connected) {
                            tvStatusBadge?.text = "● ZELLO OK"
                            tvStatusBadge?.setBackgroundResource(R.drawable.badge_status_online)
                        } else {
                            tvStatusBadge?.text = "● OFFLINE"
                            tvStatusBadge?.setBackgroundResource(R.drawable.badge_status_offline)
                        }
                    }
                }
                override fun onTalker(callsign: String, isTalking: Boolean) {
                    if (isTalking) {
                        val chName = if (currentRoomName.isNotEmpty()) currentRoomName else "ZELLO"
                        com.ninjaradio.ultimate.protocol.dmr.OnlineTalkerResolver.resolveCallsign(callsign, tg = chName, mode = "ZELLO") { prof ->
                            displayActiveTalker(prof)
                        }
                    } else {
                        displayTalkerIdle()
                    }
                }
                override fun onOpusAudio(opusBytes: ByteArray) {
                    audioEngine.onOpusPacket(opusBytes)
                }
            }
        )
        zelloClient?.start()
    }

    // ------------------------------------------------------------- Custom PoC (Opus UDP)
    private fun connectCustomPoc(profile: ServerProfile) {
        customPocClient?.stop()
        customPocClient = null
        audioEngine.flush()

        val curTg = TgManager.getCurrentTg(this).toIntOrNull() ?: if (profile.dmrId > 0) profile.dmrId else 1025
        val user = if (profile.callsign.isNotBlank()) profile.callsign else (if (profile.dmrId > 0) profile.dmrId.toString() else "NINJA")

        customPocClient = com.ninjaradio.ultimate.protocol.poc.CustomPocClient(
            host = profile.host,
            port = profile.port,
            user = user,
            secret = profile.password,
            tg = curTg,
            clientModel = profile.customModel,
            listener = object : com.ninjaradio.ultimate.protocol.poc.CustomPocClient.Listener {
                override fun onLog(msg: String) { log(msg) }
                override fun onState(connected: Boolean, info: String) {
                    handler.post {
                        if (activeProfile?.id != profile.id || activeProfile?.type != ServerType.CUSTOM_POC) return@post
                        tvStatus?.text = "${profile.host}:${profile.port} ($info)"
                        if (connected) {
                            tvStatusBadge?.text = "● POC OK"
                            tvStatusBadge?.setBackgroundResource(R.drawable.badge_status_online)
                        } else {
                            tvStatusBadge?.text = "● OFFLINE"
                            tvStatusBadge?.setBackgroundResource(R.drawable.badge_status_offline)
                        }
                    }
                }
                override fun onTalkerProfile(profile: com.ninjaradio.ultimate.protocol.dmr.TalkerProfile, isTalking: Boolean) {
                    if (isTalking) {
                        displayActiveTalker(profile)
                    } else {
                        displayTalkerIdle(profile)
                    }
                }
                override fun onTalker(callsign: String, isTalking: Boolean, details: String) {
                    // Deprecated: Handled cleanly by onTalkerProfile
                }
                override fun onPcmAudio(pcm: ShortArray) {
                    audioEngine.onPcmAudio(pcm)
                }
                override fun onFlush() {
                    audioEngine.flush()
                }
            }
        )
        customPocClient?.start()
        val badgeTitle = if (profile.name.isNotBlank()) profile.name else profile.type.displayName
        tvServerBadge?.text = "⚡ $badgeTitle • POC UDP"
        log("Menghubungkan ke Custom PoC UDP ${profile.host}:${profile.port} (User: $user, TG: $curTg)...")
    }

    // ------------------------------------------------------------- PTT Transmission
    private fun startTransmission() {
        android.util.Log.e("NinjaUltimate", "startTransmission() triggered! type=${activeProfile?.type}, hbp=${hbpClient != null}")
        if (isTransmitting) return

        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 101)
            Toast.makeText(this, "Izinkan akses Mikrofon di pengaturan aplikasi!", Toast.LENGTH_SHORT).show()
            return
        }

        isTransmitting = true
        qsoStartTime = SystemClock.elapsedRealtime()

        viewCallerAccent?.setBackgroundColor(0xFFF43F5E.toInt()) // Red on TX
        btnPtt?.setBackgroundColor(0xFFDC2626.toInt())
        btnPtt?.text = "TRANSMIT (PTT AKTIF)"
        tvHardwarePttBanner?.setBackgroundColor(0xFFDC2626.toInt())
        tvHardwarePttBanner?.setTextColor(0xFFFFFFFF.toInt())
        tvHardwarePttBanner?.text = "● SEDANG TRANSMISI (PTT AKTIF)\nLepas tombol untuk unkey"
        tvTalker?.text = activeProfile?.callsign ?: "TRANSMIT"
        val curTg = TgManager.getCurrentTg(this)
        tvTalkerDetails?.text = "TG $curTg • TX ONLINE"

        // Setup DMR TX if active profile is DMR
        if (activeProfile?.type == ServerType.DMR_DIRECT) {
            val prof = activeProfile
            val cl = hbpClient
            if (prof != null && cl != null) {
                val curTgInt = curTg.toIntOrNull() ?: 1025
                val call = com.ninjaradio.ultimate.protocol.dmr.DmrCall(
                    src = prof.dmrId,
                    dst = curTgInt,
                    colorCode = 1,
                    isPrivate = false
                )
                dmrTxCall = call
                dmrTxStream = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
                dmrTxSeq = 0
                dmrTxVseq = 0
                synchronized(dmrTxAmbeQueue) { dmrTxAmbeQueue.clear() }

                // Send 1 Voice LC Header frame (frameType = 2, dtype = 1) per HomeBrew spec
                val header = call.header()
                val dmrd = com.ninjaradio.ultimate.protocol.dmr.Dmr.buildDmrd(
                    seq = dmrTxSeq++,
                    src = prof.dmrId,
                    dst = curTgInt,
                    peer = prof.dmrId,
                    slot = 2,
                    isPrivate = false,
                    frameType = 2,
                    dtype = 1,
                    streamId = dmrTxStream,
                    payload = header
                )
                cl.send(dmrd)
            }
        }

        if (activeProfile?.type == ServerType.ZELLO) {
            zelloClient?.startTx()
        }
        if (activeProfile?.type == ServerType.CUSTOM_POC) {
            customPocClient?.startTx()
        }

        audioEngine.startTx(
            onOpusFrame = { frame ->
                svxClient?.sendAudio(frame)
                mumbleClient?.sendOpusAudio(frame)
                zelloClient?.sendOpusFrame(frame)
                ts3Client?.sendVoice(frame)
            },
            onPcm8Frame = { pcm8 ->
                roipClient?.sendPcmAudio(pcm8, isKeyed = true)
                customPocClient?.feedPcm(pcm8)
                if (activeProfile?.type == ServerType.DMR_DIRECT) {
                    val ambe = vocoder?.encode(pcm8) ?: com.ninjaradio.ultimate.protocol.dmr.Dmr.AMBE_SILENCE.copyOf()
                    synchronized(dmrTxAmbeQueue) {
                        dmrTxAmbeQueue.add(ambe)
                        if (dmrTxAmbeQueue.size == 3) {
                            val cl = hbpClient
                            val call = dmrTxCall
                            val prof = activeProfile
                            if (cl != null && call != null && prof != null) {
                                val v = dmrTxVseq
                                dmrTxVseq = (dmrTxVseq + 1) % 6
                                val frameType = if (v != 0) 0 else 1
                                val payload = com.ninjaradio.ultimate.protocol.dmr.Dmr.buildPayload(
                                    dmrTxAmbeQueue[0],
                                    dmrTxAmbeQueue[1],
                                    dmrTxAmbeQueue[2],
                                    call.voiceMid(v)
                                )
                                val curTgNum = TgManager.getCurrentTg(this@MainActivity).toIntOrNull() ?: 1025
                                val dmrd = com.ninjaradio.ultimate.protocol.dmr.Dmr.buildDmrd(
                                    seq = dmrTxSeq++ and 0xFF,
                                    src = prof.dmrId,
                                    dst = curTgNum,
                                    peer = prof.dmrId,
                                    slot = 2,
                                    isPrivate = false,
                                    frameType = frameType,
                                    dtype = v,
                                    streamId = dmrTxStream,
                                    payload = payload
                                )
                                cl.send(dmrd)
                            }
                            dmrTxAmbeQueue.clear()
                        }
                    }
                }
            },
            onLevel = { level ->
                handler.post {
                    updateSmeter(level, isTx = true)
                }
            }
        )
    }

    private fun stopTransmission() {
        if (!isTransmitting) return
        isTransmitting = false
        qsoStartTime = 0L

        viewCallerAccent?.setBackgroundColor(0xFF00F0FF.toInt())
        btnPtt?.setBackgroundColor(0xFF059669.toInt())
        btnPtt?.text = "TEKAN UNTUK BICARA\n(HOLD PTT)"
        tvHardwarePttBanner?.setBackgroundColor(0xFF0F172A.toInt())
        tvHardwarePttBanner?.setTextColor(0xFF94A3B8.toInt())
        tvHardwarePttBanner?.text = "TOMBOL PTT LAYAR DISEMBUNYIKAN\nGunakan Tombol Fisik PTT Samping untuk Berbicara"
        tvTalker?.text = "STANDBY (IDLE)"
        updateSmeter(0, isTx = false)

        audioEngine.stopTx()
        svxClient?.sendFlush()
        mumbleClient?.sendOpusAudio(byteArrayOf(), isLastFrame = true)
        roipClient?.sendPcmAudio(ShortArray(160), isKeyed = false)
        zelloClient?.stopTx()
        if (activeProfile?.type == ServerType.CUSTOM_POC) {
            customPocClient?.stopTx()
        }

        if (activeProfile?.type == ServerType.DMR_DIRECT) {
            val cl = hbpClient
            val call = dmrTxCall
            val prof = activeProfile
            val curTg = TgManager.getCurrentTg(this).toIntOrNull() ?: 1025
            if (cl != null && call != null && prof != null) {
                synchronized(dmrTxAmbeQueue) {
                    if (dmrTxAmbeQueue.isNotEmpty()) {
                        while (dmrTxAmbeQueue.size < 3) {
                            dmrTxAmbeQueue.add(com.ninjaradio.ultimate.protocol.dmr.Dmr.AMBE_SILENCE)
                        }
                        val v = dmrTxVseq
                        dmrTxVseq = (dmrTxVseq + 1) % 6
                        val frameType = if (v != 0) 0 else 1
                        val payload = com.ninjaradio.ultimate.protocol.dmr.Dmr.buildPayload(
                            dmrTxAmbeQueue[0],
                            dmrTxAmbeQueue[1],
                            dmrTxAmbeQueue[2],
                            call.voiceMid(v)
                        )
                        val dmrd = com.ninjaradio.ultimate.protocol.dmr.Dmr.buildDmrd(
                            seq = dmrTxSeq++ and 0xFF,
                            src = prof.dmrId,
                            dst = curTg,
                            peer = prof.dmrId,
                            slot = 2,
                            isPrivate = false,
                            frameType = frameType,
                            dtype = v,
                            streamId = dmrTxStream,
                            payload = payload
                        )
                        cl.send(dmrd)
                        dmrTxAmbeQueue.clear()
                    }
                }
                // Send Voice Terminator frame (frameType = 2, dtype = 2) per HomeBrew spec
                val term = call.terminator()
                val dmrd = com.ninjaradio.ultimate.protocol.dmr.Dmr.buildDmrd(
                    seq = dmrTxSeq++ and 0xFF,
                    src = prof.dmrId,
                    dst = curTg,
                    peer = prof.dmrId,
                    slot = 2,
                    isPrivate = false,
                    frameType = 2,
                    dtype = 2,
                    streamId = dmrTxStream,
                    payload = term
                )
                cl.send(dmrd)
            }
            dmrTxCall = null
        }
        audioEngine.playRogerBeep()
    }

    // ------------------------------------------------------------- Quick Switchers & Talkgroups
    fun selectTalkgroup(tg: String) {
        val cleanTg = tg.trim()
        if (cleanTg.isEmpty()) return
        TgManager.setCurrentTg(this, cleanTg)
        val name = TgManager.getTgName(this, cleanTg)

        val isNum = cleanTg.all { it.isDigit() }
        val prefix = if (isNum) "TG" else "ROOM"
        val displayLabel = if (name.isNotBlank()) "$prefix $cleanTg • $name" else "$prefix $cleanTg"
        tvTg?.text = displayLabel
        tvTalkerDetails?.text = "$displayLabel | ${activeProfile?.callsign}"

        val numericTg = cleanTg.toLongOrNull() ?: 0L
        if (numericTg > 0L) {
            svxClient?.selectTg(numericTg)
            hbpClient?.updateTalkgroupSubscription(2, numericTg.toInt())
            roipClient?.selectTalkgroup(numericTg)
            if (activeProfile?.type == ServerType.CUSTOM_POC) {
                activeProfile?.let { connectCustomPoc(it) }
            }
        }

        audioEngine.flush() // Instantly flush old TG audio buffer!
        audioEngine.playKeyBeep()
        Toast.makeText(this, displayLabel, Toast.LENGTH_SHORT).show()
    }

    private fun cycleTalkgroup(forward: Boolean) {
        val list = TgManager.loadTalkgroups(this)
        if (list.isEmpty()) {
            Toast.makeText(this, "Daftar TG kosong. Silakan tambah TG!", Toast.LENGTH_SHORT).show()
            showTalkgroupDialog()
            return
        }
        val cur = TgManager.getCurrentTg(this)
        val curIdx = list.indexOfFirst { it.tg.equals(cur, ignoreCase = true) }
        val nextIdx = if (curIdx < 0) {
            0
        } else if (forward) {
            (curIdx + 1) % list.size
        } else {
            if (curIdx <= 0) list.size - 1 else curIdx - 1
        }
        selectTalkgroup(list[nextIdx].tg)
    }

    private fun cycleServerProfile(forward: Boolean) {
        val profiles = ProfileStore.loadProfiles(this)
        if (profiles.isEmpty()) return
        val currentIdx = profiles.indexOfFirst { it.id == activeProfile?.id }
        val nextIdx = if (forward) {
            (currentIdx + 1) % profiles.size
        } else {
            if (currentIdx <= 0) profiles.size - 1 else currentIdx - 1
        }
        audioEngine.playKeyBeep()
        connectProfile(profiles[nextIdx])
        Toast.makeText(this, "Server: ${profiles[nextIdx].name}", Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------- Hardware PTT & D-Pad
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val scanCode = event.scanCode

        // Calibration mode has highest priority
        val cb = pttCalibrationCallback
        if (cb != null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (cb(keyCode, scanCode)) {
                    return true
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        // Global Hardware PTT Intercept before views consume it
        if (!PttManager.isLockOrIgnoredKey(keyCode, scanCode) && PttManager.isPttKey(this, keyCode, scanCode)) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        android.util.Log.e("NinjaUltimate", "Hardware PTT DOWN: code=$keyCode, scan=$scanCode")
                        startTransmission()
                    }
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    android.util.Log.e("NinjaUltimate", "Hardware PTT UP: code=$keyCode, scan=$scanCode")
                    stopTransmission()
                    return true
                }
            }
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (pttCalibrationCallback != null) {
            return false
        }
        // Strict guardrail 1: NEVER trigger PTT on Lock/Power buttons
        if (PttManager.isLockOrIgnoredKey(keyCode, event.scanCode)) {
            return false
        }

        // Guardrail 2: Hardware PTT key
        if (PttManager.isPttKey(this, keyCode, event.scanCode)) {
            if (event.repeatCount == 0) {
                startTransmission()
            }
            return true
        }

        // D-Pad and Navigation: Natural row navigation without hijacking channel
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val serverRow = findViewById<View?>(R.id.layoutServerRow)
                val tgBar = findViewById<View?>(R.id.layoutTgBar)
                when {
                    serverRow?.hasFocus() == true -> {
                        audioEngine.playKeyBeep()
                        showServerSetupDialog()
                        return true
                    }
                    tgBar?.hasFocus() == true -> {
                        audioEngine.playKeyBeep()
                        showTalkgroupDialog()
                        return true
                    }
                    else -> {
                        val focused = currentFocus
                        if (focused != null && focused.isClickable) {
                            focused.performClick()
                            return true
                        }
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                focusPreviousItem()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                focusNextItem()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                handleDpadHorizontal(forward = false)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                handleDpadHorizontal(forward = true)
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CALL, 1, 5 -> { // Soft Left / Green Call key -> Server Setup
                audioEngine.playKeyBeep()
                showServerSetupDialog()
                return true
            }
            KeyEvent.KEYCODE_ENDCALL, 6 -> { // Red End Call key -> Return to Radio Standby
                audioEngine.playKeyBeep()
                btnNavRadio?.performClick()
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (pttCalibrationCallback != null) {
            return false
        }
        if (PttManager.isLockOrIgnoredKey(keyCode, event.scanCode)) {
            return false
        }
        if (PttManager.isPttKey(this, keyCode, event.scanCode)) {
            stopTransmission()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun focusPreviousItem() {
        audioEngine.playKeyBeep()
        val serverRow = findViewById<View?>(R.id.layoutServerRow)
        val tgBar = findViewById<View?>(R.id.layoutTgBar)
        val navs = listOfNotNull(btnNavRadio, btnNavServer, btnNavTg, btnNavSettings)

        when {
            navs.any { it.hasFocus() } -> {
                tgBar?.requestFocus()
            }
            tgBar?.hasFocus() == true -> {
                serverRow?.requestFocus()
            }
            serverRow?.hasFocus() == true -> {
                navs[0].requestFocus()
            }
            else -> {
                serverRow?.requestFocus()
            }
        }
    }

    private fun focusNextItem() {
        audioEngine.playKeyBeep()
        val serverRow = findViewById<View?>(R.id.layoutServerRow)
        val tgBar = findViewById<View?>(R.id.layoutTgBar)
        val navs = listOfNotNull(btnNavRadio, btnNavServer, btnNavTg, btnNavSettings)

        when {
            serverRow?.hasFocus() == true -> {
                tgBar?.requestFocus()
            }
            tgBar?.hasFocus() == true -> {
                navs[0].requestFocus()
            }
            navs.any { it.hasFocus() } -> {
                serverRow?.requestFocus()
            }
            else -> {
                tgBar?.requestFocus()
            }
        }
    }

    private fun handleDpadHorizontal(forward: Boolean) {
        val serverRow = findViewById<View?>(R.id.layoutServerRow)
        val tgBar = findViewById<View?>(R.id.layoutTgBar)
        when {
            serverRow?.hasFocus() == true -> {
                cycleServerProfile(forward)
            }
            tgBar?.hasFocus() == true -> {
                cycleTalkgroup(forward)
            }
            btnNavRadio?.hasFocus() == true || btnNavServer?.hasFocus() == true ||
            btnNavTg?.hasFocus() == true || btnNavSettings?.hasFocus() == true -> {
                if (forward) focusNextNavButton() else focusPreviousNavButton()
            }
        }
    }

    private fun focusPreviousNavButton() {
        audioEngine.playKeyBeep()
        val navs = listOfNotNull(btnNavRadio, btnNavServer, btnNavTg, btnNavSettings)
        if (navs.isEmpty()) return
        val currentIdx = navs.indexOfFirst { it.hasFocus() }
        val prevIdx = if (currentIdx <= 0) navs.size - 1 else currentIdx - 1
        navs[prevIdx].requestFocus()
    }

    private fun focusNextNavButton() {
        audioEngine.playKeyBeep()
        val navs = listOfNotNull(btnNavRadio, btnNavServer, btnNavTg, btnNavSettings)
        if (navs.isEmpty()) return
        val currentIdx = navs.indexOfFirst { it.hasFocus() }
        val nextIdx = (currentIdx + 1) % navs.size
        navs[nextIdx].requestFocus()
    }

    // ------------------------------------------------------------- S-Meter & Telemetry
    private fun updateSmeter(level: Int, isTx: Boolean) {
        pbLevel?.progress = level
        val sText = when {
            level >= 85 -> "S9+40 (0 dBFS)"
            level >= 70 -> "S9+20 (-6 dBFS)"
            level >= 50 -> "S9 (-12 dBFS)"
            level >= 35 -> "S7 (-18 dBFS)"
            level >= 20 -> "S5 (-24 dBFS)"
            level >= 10 -> "S3 (-30 dBFS)"
            level > 0 -> "S1 (-36 dBFS)"
            else -> "S0 (-INF)"
        }
        tvLevelDbfs?.text = sText
    }

    private fun updatePttIndicator() {
        val desc = PttManager.getPttDescription(this)
        tvPttIndicator?.text = "[PTT: $desc]"
    }

    private fun updateSystemInfo() {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        val timeStr = sdf.format(Date())
        val batteryPct = getBatteryPercentage()
        tvSystemInfo?.text = "$timeStr | $batteryPct%"
    }

    private fun updateQsoTimer() {
        if (qsoStartTime > 0L) {
            val elapsedSec = (SystemClock.elapsedRealtime() - qsoStartTime) / 1000
            val min = elapsedSec / 60
            val sec = elapsedSec % 60
            tvQsoTimer?.text = "%02d:%02d".format(min, sec)
        } else {
            tvQsoTimer?.text = "00:00"
        }
    }

    private fun getBatteryPercentage(): Int {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100) / scale else 100
    }

    private fun createRadioDialog(view: View): Dialog {
        val dialog = object : Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (!PttManager.isLockOrIgnoredKey(event.keyCode, event.scanCode) &&
                    PttManager.isPttKey(this@MainActivity, event.keyCode, event.scanCode)) {
                    dismiss()
                    return this@MainActivity.dispatchKeyEvent(event)
                }
                return super.dispatchKeyEvent(event)
            }
        }
        dialog.setContentView(view)
        return dialog
    }

    // ------------------------------------------------------------- Dialogs
    private fun openChannelOrRoomDialog() {
        audioEngine.playKeyBeep()
        if (activeProfile?.type == ServerType.MUMBLE || activeProfile?.type == ServerType.TS3) {
            showRoomPickerDialog()
        } else {
            showTalkgroupDialog()
        }
    }

    private fun showRoomPickerDialog() {
        audioEngine.playKeyBeep()
        val view = layoutInflater.inflate(R.layout.dialog_room_picker, null)
        val dialog = createRadioDialog(view)

        val tvTitle = view.findViewById<TextView>(R.id.tvRoomPickerTitle)
        val tvCurrentRoomBanner = view.findViewById<TextView>(R.id.tvCurrentRoomBanner)
        val btnRefreshRooms = view.findViewById<Button>(R.id.btnRefreshRooms)
        val btnClose = view.findViewById<Button>(R.id.btnCloseRoomPicker)
        val etSearch = view.findViewById<EditText>(R.id.etSearchRoom)
        val layoutRoomList = view.findViewById<LinearLayout>(R.id.layoutRoomList)
        val etManual = view.findViewById<EditText>(R.id.etManualRoom)
        val btnJoinManual = view.findViewById<Button>(R.id.btnJoinManualRoom)

        val srvType = activeProfile?.type ?: ServerType.TS3
        tvTitle?.text = "PILIH ROOM / CHANNEL (${srvType.name})"
        tvCurrentRoomBanner?.text = "● Sedang di: ${if (currentRoomName.isNotEmpty()) currentRoomName else "LOBBY"}"

        val rooms = mutableListOf<RadioRoom>()
        val density = resources.displayMetrics.density
        val isSmall = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) <= 240
        var refreshRoomsList: (() -> Unit)? = null

        fun renderRooms(filter: String = "") {
            layoutRoomList?.removeAllViews()
            val filtered = if (filter.isBlank()) rooms else rooms.filter {
                it.name.contains(filter, ignoreCase = true) || it.description.contains(filter, ignoreCase = true)
            }

            if (filtered.isEmpty()) {
                val tvEmpty = TextView(this@MainActivity).apply {
                    text = if (rooms.isEmpty()) "Memuat daftar room dari server..." else "Tidak ada room yang cocok dengan \"$filter\""
                    setTextColor(0xFF64748B.toInt())
                    textSize = if (isSmall) 9f else 12f
                    setPadding(16, 24, 16, 24)
                    gravity = android.view.Gravity.CENTER
                }
                layoutRoomList?.addView(tvEmpty)
                return
            }

            for (room in filtered) {
                val card = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, ((if (isSmall) 3 else 6) * density).toInt())
                    }
                    val isCurrent = room.name.equals(currentRoomName, ignoreCase = true)
                    setBackgroundResource(if (isCurrent) R.drawable.card_zone_caller else R.drawable.card_zone_a)
                    setPadding(
                        ((if (isSmall) 8 else 12) * density).toInt(),
                        ((if (isSmall) 6 else 10) * density).toInt(),
                        ((if (isSmall) 8 else 12) * density).toInt(),
                        ((if (isSmall) 6 else 10) * density).toInt()
                    )
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    isFocusable = true
                    isClickable = true
                }

                // Icon
                val tvIcon = TextView(this@MainActivity).apply {
                    text = if (room.isPasswordProtected) "🔒" else "📻"
                    textSize = if (isSmall) 12f else 18f
                    setPadding(0, 0, ((if (isSmall) 6 else 10) * density).toInt(), 0)
                }

                // Text info (Name + Description)
                val infoLayout = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }

                val tvName = TextView(this@MainActivity).apply {
                    text = room.name
                    textSize = if (isSmall) 10f else 13f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    val isCurrent = room.name.equals(currentRoomName, ignoreCase = true)
                    setTextColor(if (isCurrent) 0xFF00FFA3.toInt() else 0xFF38BDF8.toInt())
                }

                val tvDesc = TextView(this@MainActivity).apply {
                    val clientsText = if (room.totalClients > 0) " • 👥 ${room.totalClients} online" else ""
                    val desc = if (room.description.isNotBlank()) room.description else (if (room.targetTg > 0L) "TG ${room.targetTg}" else "Channel")
                    text = "$desc$clientsText"
                    textSize = if (isSmall) 8.5f else 11f
                    setTextColor(0xFF94A3B8.toInt())
                }

                infoLayout.addView(tvName)
                infoLayout.addView(tvDesc)

                // Join button / pill
                val btnJoin = Button(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ((if (isSmall) 48 else 68) * density).toInt(),
                        ((if (isSmall) 26 else 34) * density).toInt()
                    )
                    val isCurrent = room.name.equals(currentRoomName, ignoreCase = true)
                    text = if (isCurrent) "AKTIF" else "MASUK"
                    textSize = if (isSmall) 8f else 10f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setBackgroundResource(if (isCurrent) R.drawable.badge_status_online else R.drawable.badge_pill)
                    setTextColor(if (isCurrent) 0xFF052E16.toInt() else 0xFF00F0FF.toInt())
                    setOnClickListener {
                        joinSelectedRoom(room)
                        dialog.dismiss()
                    }
                }

                card.setOnClickListener {
                    joinSelectedRoom(room)
                    dialog.dismiss()
                }

                card.setOnLongClickListener {
                    val prof = activeProfile
                    if (prof != null) {
                        android.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("Hapus Room")
                            .setMessage("Hapus '${room.name}' dari daftar server ini?")
                            .setPositiveButton("Hapus") { _, _ ->
                                com.ninjaradio.ultimate.model.RoomManager.deleteRoom(this@MainActivity, prof.host, prof.type, room.name)
                                refreshRoomsList?.invoke()
                            }
                            .setNegativeButton("Batal", null)
                            .show()
                    }
                    true
                }

                card.addView(tvIcon)
                card.addView(infoLayout)
                card.addView(btnJoin)
                layoutRoomList?.addView(card)
            }
        }

        fun loadRooms() {
            rooms.clear()
            val prof = activeProfile
            val host = prof?.host?.trim() ?: ""

            // 1. Load dynamically saved rooms for this specific server
            val saved = com.ninjaradio.ultimate.model.RoomManager.loadRooms(this@MainActivity, host, prof?.type ?: ServerType.TS3)
            rooms.addAll(saved)

            // 2. For Mumble & TS3, sync live channels if connected
            if (prof?.type == ServerType.MUMBLE) {
                val cl = mumbleClient
                if (cl != null && cl.channels.isNotEmpty()) {
                    cl.channels.forEach { (cid, cname) ->
                        if (rooms.none { it.name.equals(cname, ignoreCase = true) }) {
                            val r = RadioRoom("mum_$cid", cname, 0L, 0, false, "Channel #$cid")
                            rooms.add(r)
                        }
                    }
                }
            } else if (prof?.type == ServerType.TS3) {
                val cl = ts3Client
                if (cl != null) {
                    val liveRooms = cl.getCachedRooms()
                    if (liveRooms.isNotEmpty()) {
                        rooms.clear()
                        rooms.addAll(liveRooms)
                    }
                }
            }

            renderRooms(etSearch?.text?.toString() ?: "")
        }

        refreshRoomsList = { loadRooms() }

        etSearch?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderRooms(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        val btnResetRooms = view.findViewById<Button>(R.id.btnResetRooms)
        btnResetRooms?.setOnClickListener {
            audioEngine.playKeyBeep()
            val prof = activeProfile
            if (prof != null) {
                com.ninjaradio.ultimate.model.RoomManager.clearRooms(this@MainActivity, prof.host, prof.type)
                loadRooms()
                Toast.makeText(this@MainActivity, "Daftar room direset ke default!", Toast.LENGTH_SHORT).show()
            }
        }

        btnRefreshRooms?.setOnClickListener {
            audioEngine.playKeyBeep()
            val prof = activeProfile
            if (prof?.type == ServerType.TS3) {
                val cl = ts3Client
                if (cl != null) {
                    Toast.makeText(this, "Memperbarui daftar room dari server TS3...", Toast.LENGTH_SHORT).show()
                    cl.requestChannelList()
                    loadRooms()
                } else {
                    Toast.makeText(this, "Mengambil daftar room TS3 dari ${prof.host}:${prof.port}...", Toast.LENGTH_SHORT).show()
                    com.ninjaradio.ultimate.protocol.ts3.Ts3ChannelFetcher.fetchRooms(
                        context = this@MainActivity,
                        host = prof.host,
                        port = prof.port,
                        nickname = prof.callsign,
                        serverPassword = prof.password
                    ) { fetchedRooms, error ->
                        runOnUiThread {
                            if (fetchedRooms != null && fetchedRooms.isNotEmpty()) {
                                com.ninjaradio.ultimate.model.RoomManager.saveRooms(this@MainActivity, prof.host, prof.type, fetchedRooms)
                                loadRooms()
                                Toast.makeText(this@MainActivity, "Berhasil memuat ${fetchedRooms.size} room TS3!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@MainActivity, "Gagal memuat room TS3: ${error ?: "Timeout"}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            } else {
                loadRooms()
                Toast.makeText(this, "Daftar room diperbarui", Toast.LENGTH_SHORT).show()
            }
        }

        btnJoinManual?.setOnClickListener {
            val typed = etManual?.text?.toString()?.trim() ?: ""
            if (typed.isNotEmpty()) {
                val manualRoom = RadioRoom(
                    id = "manual_${System.currentTimeMillis()}",
                    name = typed,
                    targetTg = typed.filter { it.isDigit() }.toLongOrNull() ?: 0L,
                    description = "Custom Room"
                )
                activeProfile?.let { prof ->
                    com.ninjaradio.ultimate.model.RoomManager.addOrUpdateRoom(this@MainActivity, prof.host, prof.type, manualRoom)
                }
                joinSelectedRoom(manualRoom)
                dialog.dismiss()
            }
        }

        btnClose?.setOnClickListener { dialog.dismiss() }

        onLiveRoomsChanged = {
            runOnUiThread {
                loadRooms()
            }
        }
        dialog.setOnDismissListener {
            onLiveRoomsChanged = null
        }

        loadRooms()

        val activeProf = activeProfile
        android.util.Log.i("MainActivity", "showRoomPickerDialog opened: type=${activeProf?.type}, host=${activeProf?.host}, port=${activeProf?.port}, rooms.size=${rooms.size}")
        if (activeProf?.type == ServerType.TS3) {
            val cl = ts3Client
            if (cl != null) {
                cl.requestChannelList()
            } else if (rooms.size <= 1) {
                com.ninjaradio.ultimate.protocol.ts3.Ts3ChannelFetcher.fetchRooms(
                    context = this@MainActivity,
                    host = activeProf.host,
                    port = activeProf.port,
                    nickname = activeProf.callsign,
                    serverPassword = activeProf.password
                ) { fetchedRooms, error ->
                    runOnUiThread {
                        android.util.Log.i("MainActivity", "TS3 fetchRooms callback: count=${fetchedRooms?.size}, error=$error, dialog.isShowing=${dialog.isShowing}")
                        if (fetchedRooms != null && fetchedRooms.isNotEmpty() && dialog.isShowing) {
                            com.ninjaradio.ultimate.model.RoomManager.saveRooms(this@MainActivity, activeProf.host, activeProf.type, fetchedRooms)
                            loadRooms()
                        }
                    }
                }
            }
        }

        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.show()
    }

    private fun joinSelectedRoom(room: RadioRoom) {
        audioEngine.playKeyBeep()
        currentRoomName = room.name
        val prefix = if (activeProfile?.type == ServerType.ZELLO) "CHANNEL" else "ROOM"
        tvTg?.text = "$prefix: ${room.name}"
        tvTalkerDetails?.text = "$prefix: ${room.name} | ${activeProfile?.callsign}"

        activeProfile?.let { prof ->
            com.ninjaradio.ultimate.model.RoomManager.addOrUpdateRoom(this@MainActivity, prof.host, prof.type, room)
        }

        if (activeProfile?.type == ServerType.MUMBLE) {
            val cl = mumbleClient
            if (cl != null) {
                val cid = cl.channels.entries.firstOrNull {
                    it.value.equals(room.name, ignoreCase = true) ||
                    it.value.contains(room.name, ignoreCase = true) ||
                    room.name.contains(it.value, ignoreCase = true)
                }?.key
                if (cid != null) {
                    cl.joinChannel(cid)
                }
            }
        } else if (activeProfile?.type == ServerType.TS3) {
            ts3Client?.moveToChannel(room.targetTg)
        } else if (activeProfile?.type == ServerType.ZELLO) {
            if (room.targetTg > 0L) {
                val cleanTg = room.targetTg.toString()
                TgManager.setCurrentTg(this, cleanTg)
                svxClient?.selectTg(room.targetTg)
                hbpClient?.updateTalkgroupSubscription(2, room.targetTg.toInt())
                roipClient?.selectTalkgroup(room.targetTg)
            }
            zelloClient?.stop()
            activeProfile?.let { connectZello(it) }
        }

        val joinMsg = if (activeProfile?.type == ServerType.ZELLO) "Tergabung ke channel: ${room.name}" else "Tergabung ke room: ${room.name}"
        Toast.makeText(this, joinMsg, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------- Dialogs
    private fun showTalkgroupDialog() {
        audioEngine.playKeyBeep()
        val view = layoutInflater.inflate(R.layout.dialog_set_talkgroup, null)
        val dialog = createRadioDialog(view)

        val etTargetTg = view.findViewById<EditText>(R.id.etTargetTg)
        val etTargetName = view.findViewById<EditText>(R.id.etTargetName)
        val layoutSavedTgList = view.findViewById<LinearLayout>(R.id.layoutSavedTgList)
        val curTg = TgManager.getCurrentTg(this)
        val curName = TgManager.getTgName(this, curTg)

        etTargetTg.setText(curTg)
        etTargetName.setText(curName)
        etTargetTg.selectAll()

        fun refreshSavedList() {
            layoutSavedTgList?.removeAllViews()
            val list = TgManager.loadTalkgroups(this@MainActivity)
            val isSmall = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) <= 240
            val rowHeight = if (isSmall) 24 else 42
            val textSz = if (isSmall) 8.5f else 13f

            for (item in list) {
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (rowHeight * resources.displayMetrics.density).toInt()
                    ).apply {
                        setMargins(0, 0, 0, ((if (isSmall) 2 else 4) * resources.displayMetrics.density).toInt())
                    }
                    setBackgroundColor(0xFF1E293B.toInt())
                }

                val btnSelect = Button(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    background = null
                    isAllCaps = false
                    val isNum = item.tg.all { it.isDigit() }
                    val pfx = if (isNum) "TG" else "ROOM"
                    text = "$pfx ${item.tg} • ${item.name}"
                    setTextColor(0xFFFBBF24.toInt())
                    textSize = textSz
                    gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                    setPadding((10 * resources.displayMetrics.density).toInt(), 0, 0, 0)
                    setOnClickListener {
                        etTargetTg.setText(item.tg)
                        etTargetName.setText(item.name)
                        selectTalkgroup(item.tg)
                        dialog.dismiss()
                    }
                }

                val btnDelete = Button(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ((if (isSmall) 28 else 44) * resources.displayMetrics.density).toInt(),
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    background = null
                    text = "✕"
                    setTextColor(0xFFEF4444.toInt())
                    textSize = if (isSmall) 9f else 14f
                    setOnClickListener {
                        TgManager.deleteTalkgroup(this@MainActivity, item.tg)
                        refreshSavedList()
                        Toast.makeText(this@MainActivity, "Dihapus: ${item.tg}", Toast.LENGTH_SHORT).show()
                    }
                }

                row.addView(btnSelect)
                row.addView(btnDelete)
                layoutSavedTgList?.addView(row)
            }
        }

        refreshSavedList()

        view.findViewById<Button>(R.id.btnTgCancel).setOnClickListener {
            dialog.dismiss()
        }

        view.findViewById<Button>(R.id.btnTgSaveOnly).setOnClickListener {
            val tg = etTargetTg.text.toString().trim()
            val name = etTargetName.text.toString().trim()
            if (tg.isNotEmpty()) {
                TgManager.addOrUpdateTalkgroup(this, tg, name)
                refreshSavedList()
                Toast.makeText(this, "Disimpan ke daftar!", Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<Button>(R.id.btnTgSaveApply).setOnClickListener {
            val tg = etTargetTg.text.toString().trim()
            val name = etTargetName.text.toString().trim()
            if (tg.isNotEmpty()) {
                TgManager.addOrUpdateTalkgroup(this, tg, name)
                selectTalkgroup(tg)
                dialog.dismiss()
            }
        }

        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.show()
    }

    private fun showServerSetupDialog() {
        audioEngine.playKeyBeep()
        val view = layoutInflater.inflate(R.layout.dialog_server_setup, null)
        val dialog = createRadioDialog(view)

        val spnType = view.findViewById<Spinner>(R.id.spnServerType)
        val spnProfiles = view.findViewById<Spinner>(R.id.spnProfiles)
        val etProfileName = view.findViewById<EditText>(R.id.etProfileName)
        val btnNewProfile = view.findViewById<Button>(R.id.btnNewProfile)
        val btnDeleteProfile = view.findViewById<Button>(R.id.btnDeleteProfile)
        val etHost = view.findViewById<EditText>(R.id.etHost)
        val etPort = view.findViewById<EditText>(R.id.etPort)
        val etCallsign = view.findViewById<EditText>(R.id.etCallsign)
        val etPassword = view.findViewById<EditText>(R.id.etPassword)
        val etDmrId = view.findViewById<EditText>(R.id.etDmrId)
        val containerDmrId = view.findViewById<View>(R.id.containerDmrId)
        val tvDmrIdLabel = view.findViewById<TextView>(R.id.tvDmrIdLabel)
        val containerCustomPoc = view.findViewById<View>(R.id.containerCustomPoc)
        val etCustomModel = view.findViewById<EditText>(R.id.etCustomModel)
        val btnGetPairingCode = view.findViewById<Button>(R.id.btnGetPairingCode)
        val btnSaveProfileOnly = view.findViewById<Button>(R.id.btnSaveProfileOnly)
        val btnSaveConnect = view.findViewById<Button>(R.id.btnSaveConnect)

        val profiles = ProfileStore.loadProfiles(this)
        val typeNames = ServerType.values().map { it.displayName }
        spnType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, typeNames)

        fun getProfileDisplayList(): MutableList<String> {
            return profiles.map { if (it.name.isNotBlank()) it.name else "${it.type.name} (${it.host})" }.toMutableList()
        }

        var profileAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, getProfileDisplayList())
        spnProfiles.adapter = profileAdapter

        fun updateFormForProfile(p: ServerProfile) {
            etProfileName?.setText(p.name)
            etHost.setText(p.host)
            etPort.setText(p.port.toString())
            etCallsign.setText(p.callsign)
            etPassword.setText(p.password)
            etDmrId.setText(p.dmrId.toString())
            etCustomModel?.setText(p.customModel)

            when (p.type) {
                ServerType.DMR_DIRECT -> {
                    containerDmrId?.visibility = View.VISIBLE
                    tvDmrIdLabel?.text = "DMR RADIO ID (Khusus DMR Direct):"
                    containerCustomPoc?.visibility = View.GONE
                }
                ServerType.CUSTOM_POC -> {
                    containerDmrId?.visibility = View.VISIBLE
                    tvDmrIdLabel?.text = "TARGET TALKGROUP (Contoh: 1025):"
                    containerCustomPoc?.visibility = View.VISIBLE
                }
                else -> {
                    containerDmrId?.visibility = View.GONE
                    containerCustomPoc?.visibility = View.GONE
                }
            }
        }

        fun saveFormToCurrent(): ServerProfile? {
            if (profiles.isEmpty()) return null
            val pos = spnProfiles.selectedItemPosition.coerceIn(0, profiles.size - 1)
            val selectedType = ServerType.values()[spnType.selectedItemPosition]
            val profName = etProfileName?.text.toString().trim().ifEmpty { "Server ${pos + 1}" }
            val host = etHost.text.toString().replace(" ", "").trim()
            val port = etPort.text.toString().trim().toIntOrNull() ?: selectedType.defaultPort
            val rawCall = etCallsign.text.toString().trim()
            val callsign = if (selectedType == ServerType.DMR_DIRECT) rawCall.uppercase() else rawCall
            val pass = etPassword.text.toString().trim()
            val dmrId = etDmrId.text.toString().trim().toIntOrNull() ?: 1022057
            val modelName = etCustomModel?.text?.toString()?.trim().let { if (it.isNullOrBlank()) "Hytera PDC680 BRIDI" else it }

            val curProf = profiles[pos]
            curProf.name = profName
            curProf.type = selectedType
            curProf.host = host
            curProf.port = port
            curProf.callsign = callsign
            curProf.password = pass
            curProf.dmrId = dmrId
            curProf.customModel = modelName
            return curProf
        }

        fun refreshProfileSpinner(selectedPos: Int) {
            profileAdapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, getProfileDisplayList())
            spnProfiles.adapter = profileAdapter
            spnProfiles.setSelection(selectedPos.coerceIn(0, profiles.size - 1))
        }

        // Select current active profile
        val curIdx = profiles.indexOfFirst { it.id == activeProfile?.id }.let { if (it >= 0) it else 0 }
        spnProfiles.setSelection(curIdx)
        val initialProfile = profiles[curIdx]
        spnType.setSelection(initialProfile.type.ordinal)
        updateFormForProfile(initialProfile)

        // When user selects a profile in the primary spinner
        spnProfiles.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                val p = profiles[pos]
                spnType.setSelection(p.type.ordinal)
                updateFormForProfile(p)
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        // When user clicks + BARU
        btnNewProfile?.setOnClickListener {
            audioEngine.playKeyBeep()
            saveFormToCurrent()
            val newNum = profiles.size + 1
            val newProf = ServerProfile(
                name = "Server $newNum",
                type = ServerType.DMR_DIRECT,
                host = "",
                port = ServerType.DMR_DIRECT.defaultPort,
                callsign = "",
                password = "",
                dmrId = 1022057
            )
            profiles.add(newProf)
            ProfileStore.saveProfiles(this@MainActivity, profiles)
            refreshProfileSpinner(profiles.size - 1)
            spnType.setSelection(newProf.type.ordinal)
            updateFormForProfile(newProf)
            Toast.makeText(this@MainActivity, "Profil baru ditambahkan. Silakan isi detail server.", Toast.LENGTH_SHORT).show()
        }

        // When user clicks HAPUS
        btnDeleteProfile?.setOnClickListener {
            if (profiles.size <= 1) {
                Toast.makeText(this@MainActivity, "Minimal harus ada 1 server profil tersimpan!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            audioEngine.playKeyBeep()
            val curPos = spnProfiles.selectedItemPosition
            val removed = profiles.removeAt(curPos)
            ProfileStore.saveProfiles(this@MainActivity, profiles)
            val nextPos = (curPos - 1).coerceAtLeast(0)
            refreshProfileSpinner(nextPos)
            val p = profiles[nextPos]
            spnType.setSelection(p.type.ordinal)
            updateFormForProfile(p)
            Toast.makeText(this@MainActivity, "Profil '${removed.name}' dihapus.", Toast.LENGTH_SHORT).show()
        }

        // When user changes server protocol type
        spnType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                val selectedType = ServerType.values()[pos]
                val curPort = etPort.text.toString().toIntOrNull()
                val isDefaultPortOfAnyType = ServerType.values().any { it.defaultPort == curPort }
                if (curPort == null || isDefaultPortOfAnyType) {
                    etPort.setText(selectedType.defaultPort.toString())
                }
                when (selectedType) {
                    ServerType.DMR_DIRECT -> {
                        containerDmrId?.visibility = View.VISIBLE
                        tvDmrIdLabel?.text = "DMR RADIO ID (Khusus DMR Direct):"
                        containerCustomPoc?.visibility = View.GONE
                    }
                    ServerType.CUSTOM_POC -> {
                        containerDmrId?.visibility = View.VISIBLE
                        tvDmrIdLabel?.text = "TARGET TALKGROUP (Contoh: 1025):"
                        containerCustomPoc?.visibility = View.VISIBLE
                    }
                    else -> {
                        containerDmrId?.visibility = View.GONE
                        containerCustomPoc?.visibility = View.GONE
                    }
                }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        btnGetPairingCode?.setOnClickListener {
            val rawHost = etHost.text.toString().trim()
            if (rawHost.isEmpty()) {
                Toast.makeText(this@MainActivity, "Isi Host/Domain server terlebih dahulu!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val portalUrl = if (rawHost.startsWith("http://") || rawHost.startsWith("https://")) rawHost else "https://$rawHost"
            val progressDialog = android.app.AlertDialog.Builder(this)
                .setTitle("Pairing Perangkat PoC")
                .setMessage("Menghubungi portal $portalUrl untuk mendapatkan kode 6 digit...")
                .setNegativeButton("Batal", null)
                .show()

            com.ninjaradio.ultimate.protocol.poc.CustomPocClient.requestPairingCode(portalUrl) { code, devId, error ->
                handler.post {
                    if (code == null || devId == null) {
                        progressDialog.dismiss()
                        Toast.makeText(this@MainActivity, "Gagal mendapatkan kode dari $portalUrl: ${error ?: "Koneksi gagal"}", Toast.LENGTH_LONG).show()
                    } else {
                        progressDialog.setMessage("KODE PERANGKAT: $code\n\nBuka portal web server, pilih Tambah HT/Perangkat, lalu masukkan kode di atas.\n\nMenunggu otorisasi admin/portal...")
                        Thread {
                            var attempts = 0
                            while (attempts < 40 && progressDialog.isShowing) {
                                Thread.sleep(3000)
                                attempts++
                                com.ninjaradio.ultimate.protocol.poc.CustomPocClient.checkPairingStatus(portalUrl, devId) { ok, user, secret, host, port, tg ->
                                    if (ok) {
                                        handler.post {
                                            progressDialog.dismiss()
                                            if (!user.isNullOrBlank()) etCallsign.setText(user)
                                            if (!secret.isNullOrBlank()) etPassword.setText(secret)
                                            if (port > 0) etPort.setText(port.toString())
                                            if (tg > 0) etDmrId.setText(tg.toString())
                                            Toast.makeText(this@MainActivity, "Perangkat di-pairing! Detail otomatis terisi.", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        }.start()
                    }
                }
            }
        }

        view.findViewById<Button>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }

        btnSaveProfileOnly?.setOnClickListener {
            val curProf = saveFormToCurrent() ?: return@setOnClickListener
            ProfileStore.saveProfiles(this@MainActivity, profiles)
            refreshProfileSpinner(spnProfiles.selectedItemPosition)
            Toast.makeText(this@MainActivity, "Profil '${curProf.name}' disimpan ke daftar!", Toast.LENGTH_SHORT).show()
        }

        btnSaveConnect?.setOnClickListener {
            val curProf = saveFormToCurrent() ?: return@setOnClickListener
            if (curProf.host.isEmpty()) {
                Toast.makeText(this@MainActivity, "Host / IP server tidak boleh kosong!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            ProfileStore.saveProfiles(this@MainActivity, profiles)
            connectProfile(curProf)
            dialog.dismiss()
        }

        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.show()
    }

    private fun showSettingsDialog() {
        audioEngine.playKeyBeep()
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val dialog = createRadioDialog(view)

        // Spinners
        val spnMicGain = view.findViewById<Spinner>(R.id.spnMicGain)
        val spnRxGain = view.findViewById<Spinner>(R.id.spnRxGain)
        val spnAudioRoute = view.findViewById<Spinner>(R.id.spnAudioRoute)
        val spnTxMode = view.findViewById<Spinner>(R.id.spnTxMode)
        val spnVoxSensitivity = view.findViewById<Spinner>(R.id.spnVoxSensitivity)
        val spnVoxHangover = view.findViewById<Spinner>(R.id.spnVoxHangover)
        val spnTot = view.findViewById<Spinner>(R.id.spnTot)

        // Checkboxes
        val cbRogerBeep = view.findViewById<CheckBox>(R.id.cbRogerBeep)
        val cbKeyBeep = view.findViewById<CheckBox>(R.id.cbKeyBeep)
        val cbHideScreenPtt = view.findViewById<CheckBox>(R.id.cbHideScreenPtt)
        cbHideScreenPtt?.isChecked = AppSettings.isHideScreenPtt(this)

        // PTT Hardware info
        val tvCurrentPttKey = view.findViewById<TextView>(R.id.tvCurrentPttKey)
        val btnCalibratePtt = view.findViewById<Button>(R.id.btnCalibratePtt)
        val btnResetPtt = view.findViewById<Button>(R.id.btnResetPtt)

        fun updatePttText() {
            val desc = PttManager.getPttDescription(this@MainActivity)
            tvCurrentPttKey?.text = "Tombol Aktif: $desc"
        }
        updatePttText()

        view.findViewById<Button>(R.id.btnQuickCaraka290)?.setOnClickListener {
            audioEngine.playKeyBeep()
            PttManager.saveCustomKey(this@MainActivity, 290, 0, "CARAKA PTT 1")
            updatePttIndicator()
            updatePttText()
            Toast.makeText(this@MainActivity, "PTT: CARAKA (290) Aktif", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<Button>(R.id.btnQuickCaraka291)?.setOnClickListener {
            audioEngine.playKeyBeep()
            PttManager.saveCustomKey(this@MainActivity, 291, 0, "CARAKA PTT 2")
            updatePttIndicator()
            updatePttText()
            Toast.makeText(this@MainActivity, "PTT: CARAKA (291) Aktif", Toast.LENGTH_SHORT).show()
        }

        btnCalibratePtt?.setOnClickListener {
            showPttCalibrationDialog {
                updatePttText()
            }
        }
        btnResetPtt?.setOnClickListener {
            PttManager.clearCustomKey(this@MainActivity)
            updatePttText()
            updatePttIndicator()
            Toast.makeText(this@MainActivity, "Keycode PTT direset ke bawaan", Toast.LENGTH_SHORT).show()
        }

        // Setup Spinners Data
        val micGainLabels = arrayOf("-6 dB", "0 dB (Normal)", "+3 dB", "+6 dB (Bawaan)", "+9 dB", "+12 dB", "+15 dB", "+18 dB (Maksimal)")
        val micGainValues = intArrayOf(-6, 0, 3, 6, 9, 12, 15, 18)
        spnMicGain?.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, micGainLabels)
        val currentMicGain = AppSettings.getMicGainDb(this)
        val micIdx = micGainValues.indexOf(currentMicGain).coerceAtLeast(0)
        spnMicGain?.setSelection(micIdx)

        val rxGainLabels = arrayOf("0 dB (Normal)", "+3 dB", "+6 dB", "+9 dB", "+12 dB (Maksimal)")
        val rxGainValues = intArrayOf(0, 3, 6, 9, 12)
        spnRxGain?.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, rxGainLabels)
        val currentRxGain = AppSettings.getRxGainDb(this)
        val rxIdx = rxGainValues.indexOf(currentRxGain).coerceAtLeast(0)
        spnRxGain?.setSelection(rxIdx)

        val routeLabels = arrayOf("Loudspeaker / Speakerphone", "Earpiece / TRRS Headset", "USB Soundcard")
        val routeValues = arrayOf("SPEAKERPHONE", "DEFAULT_OR_TRRS", "USB_SOUNDCARD")
        spnAudioRoute?.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, routeLabels)
        val currentRoute = AppSettings.getAudioRoute(this)
        val routeIdx = routeValues.indexOf(currentRoute).coerceAtLeast(0)
        spnAudioRoute?.setSelection(routeIdx)

        val txModeLabels = arrayOf("Push-To-Talk Manual (PTT)", "VOX Otomatis (Voice Activated)")
        spnTxMode?.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, txModeLabels)
        spnTxMode?.setSelection(if (AppSettings.isVoxEnabled(this)) 1 else 0)

        val voxSensLabels = arrayOf("Tinggi / Sangat Peka (15%)", "Sedang (25%)", "Rendah / Kebal Noise (40%)")
        val voxSensValues = intArrayOf(15, 25, 40)
        spnVoxSensitivity?.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voxSensLabels)
        val currentVoxSens = AppSettings.getVoxSensitivity(this)
        val voxSensIdx = voxSensValues.indexOf(currentVoxSens).coerceAtLeast(0)
        spnVoxSensitivity?.setSelection(voxSensIdx)

        val voxHangoverLabels = arrayOf("300 ms", "450 ms (Bawaan)", "600 ms", "800 ms")
        val voxHangoverValues = longArrayOf(300L, 450L, 600L, 800L)
        spnVoxHangover?.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voxHangoverLabels)
        val currentVoxHang = AppSettings.getVoxHangoverMs(this)
        val voxHangIdx = voxHangoverValues.indexOf(currentVoxHang).coerceAtLeast(0)
        spnVoxHangover?.setSelection(voxHangIdx)

        val totLabels = arrayOf("60 Detik", "120 Detik (Bawaan)", "180 Detik", "Tanpa Batas")
        val totValues = intArrayOf(60, 120, 180, 0)
        spnTot?.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, totLabels)
        val currentTot = AppSettings.getTotSeconds(this)
        val totIdx = totValues.indexOf(currentTot).coerceAtLeast(0)
        spnTot?.setSelection(totIdx)

        cbRogerBeep?.isChecked = AppSettings.isRogerBeep(this)
        cbKeyBeep?.isChecked = AppSettings.isKeyBeep(this)

        // Buttons
        view.findViewById<Button>(R.id.btnSettingsCancel)?.setOnClickListener {
            dialog.dismiss()
        }

        view.findViewById<Button>(R.id.btnSettingsSave)?.setOnClickListener {
            val selMic = micGainValues[spnMicGain?.selectedItemPosition ?: 3]
            val selRx = rxGainValues[spnRxGain?.selectedItemPosition ?: 0]
            val selRoute = routeValues[spnAudioRoute?.selectedItemPosition ?: 0]
            val selVox = (spnTxMode?.selectedItemPosition == 1)
            val selVoxSens = voxSensValues[spnVoxSensitivity?.selectedItemPosition ?: 1]
            val selVoxHang = voxHangoverValues[spnVoxHangover?.selectedItemPosition ?: 1]
            val selTot = totValues[spnTot?.selectedItemPosition ?: 1]
            val selRoger = cbRogerBeep?.isChecked == true
            val selKey = cbKeyBeep?.isChecked == true

            // Save to prefs
            AppSettings.setMicGainDb(this, selMic)
            AppSettings.setRxGainDb(this, selRx)
            AppSettings.setAudioRoute(this, selRoute)
            AppSettings.setVoxEnabled(this, selVox)
            AppSettings.setVoxSensitivity(this, selVoxSens)
            AppSettings.setVoxHangoverMs(this, selVoxHang)
            AppSettings.setTotSeconds(this, selTot)
            AppSettings.setRogerBeep(this, selRoger)
            AppSettings.setKeyBeep(this, selKey)
            AppSettings.setHideScreenPtt(this, cbHideScreenPtt?.isChecked == true)

            // Apply immediately to AudioEngine and UI
            applySettingsToEngine()

            Toast.makeText(this, "Pengaturan berhasil disimpan!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        dialog.show()
    }

    private fun showPttCalibrationDialog(onKeySaved: (() -> Unit)? = null) {
        audioEngine.playKeyBeep()
        val view = layoutInflater.inflate(R.layout.dialog_ptt_calibration, null)
        val tvKey = view.findViewById<TextView>(R.id.tvDetectedKey)
        val tvWarn = view.findViewById<TextView>(R.id.tvWarning)
        val btnSave = view.findViewById<Button>(R.id.btnSavePtt)
        val btnReset = view.findViewById<Button>(R.id.btnResetPtt)
        val btnPreset290 = view.findViewById<Button>(R.id.btnPreset290)
        val btnPreset291 = view.findViewById<Button>(R.id.btnPreset291)
        val btnPreset216 = view.findViewById<Button>(R.id.btnPreset216)

        var detectedCode = 0
        var detectedScan = 0
        var detectedName = ""

        fun applyKey(keyCode: Int, scan: Int) {
            if (PttManager.isLockOrIgnoredKey(keyCode, scan)) {
                tvWarn?.visibility = View.VISIBLE
                tvWarn?.text = "TOMBOL LOCK / POWER DIABAIKAN!\n(Proteksi tombol power/lock aktif)"
                tvKey?.text = "TOMBOL TIDAK DIIZINKAN"
                btnSave?.isEnabled = false
                return
            }

            tvWarn?.visibility = View.GONE
            val keyName = when (keyCode) {
                290 -> "CARAKA PTT 1"
                291 -> "CARAKA PTT 2"
                216 -> "SIDE KEY 216"
                228 -> "ANDROID PTT"
                else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
            }
            detectedCode = keyCode
            detectedScan = scan
            detectedName = keyName

            tvKey?.text = "$keyName ($keyCode)\nTERSIMPAN OTOMATIS!"
            tvKey?.setTextColor(0xFF00FFA3.toInt())
            btnSave?.isEnabled = true

            audioEngine.playKeyBeep()
            PttManager.saveCustomKey(this@MainActivity, keyCode, scan, keyName)
            updatePttIndicator()
            onKeySaved?.invoke()

            handler.postDelayed({
                try { calibrationDialog?.dismiss() } catch (_: Exception) {}
                Toast.makeText(this@MainActivity, "PTT Disetel: $keyName ($keyCode)", Toast.LENGTH_SHORT).show()
            }, 350)
        }

        val dialog = object : Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_Alert) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    applyKey(event.keyCode, event.scanCode)
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        calibrationDialog = dialog
        dialog.setContentView(view)

        pttCalibrationCallback = { keyCode, scan ->
            applyKey(keyCode, scan)
            true
        }

        btnPreset290?.setOnClickListener { applyKey(290, 0) }
        btnPreset291?.setOnClickListener { applyKey(291, 0) }
        btnPreset216?.setOnClickListener { applyKey(216, 0) }

        btnReset?.setOnClickListener {
            PttManager.clearCustomKey(this)
            updatePttIndicator()
            onKeySaved?.invoke()
            dialog.dismiss()
            Toast.makeText(this, "PTT direset ke Auto-Detect (Tombol 216)", Toast.LENGTH_SHORT).show()
        }

        btnSave?.setOnClickListener {
            if (detectedCode > 0 || detectedScan > 0) {
                PttManager.saveCustomKey(this@MainActivity, detectedCode, detectedScan, detectedName)
                updatePttIndicator()
                onKeySaved?.invoke()
            }
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            pttCalibrationCallback = null
            calibrationDialog = null
        }

        dialog.show()
    }

    private fun log(msg: String) {
        android.util.Log.e("NinjaUltimate", msg)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(pttBroadcastReceiver)
        } catch (_: Exception) {}
        handler.removeCallbacks(timeTickRunnable)
        disconnectCurrent()
        audioEngine.stop()
    }
}
