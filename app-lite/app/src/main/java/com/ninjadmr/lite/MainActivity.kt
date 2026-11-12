package com.ninjadmr.lite

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ninjadmr.lite.audio.AudioEngine
import com.ninjadmr.lite.audio.AudioPreferences
import com.ninjadmr.lite.audio.NinjaAudioFx
import com.ninjadmr.lite.contacts.CallbookManager
import com.ninjadmr.lite.dmr.HbpClient
import com.ninjadmr.lite.model.DmrProfile
import com.ninjadmr.lite.model.ProfileStore
import com.ninjadmr.lite.ptt.PttManager
import com.ninjadmr.lite.service.DmrService
import com.ninjadmr.lite.ui.HudSmeterView
import com.ninjadmr.lite.ui.HudWaveformView

class MainActivity : AppCompatActivity(), DmrService.Listener {

    private lateinit var profileStore: ProfileStore
    private var dmrService: DmrService? = null
    private var isBound = false
    private var isLearningPtt = false
    private var learningTarget: PttManager.KeyTarget = PttManager.KeyTarget.PTT
    private var lastHeardDisplay: String = ""
    private var lastHeardSrcId: Int = 0
    private var previousHeardDisplay: String = ""
    private var pttBroadcastReceiver: BroadcastReceiver? = null
    private var headsetPlugReceiver: BroadcastReceiver? = null
    private var pttCalibDialog: AlertDialog? = null
    private var pendingLearnedKey: Triple<Int, Int, String>? = null

    private lateinit var layoutLcdPanel: LinearLayout
    private lateinit var tvChannelBadge: TextView
    private lateinit var tvProfileName: TextView
    private lateinit var tvAudioModeBadge: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvTg: TextView
    private lateinit var tvModeBadge: TextView
    private lateinit var tvQsoTimer: TextView
    private lateinit var tvSlot: TextView
    private lateinit var tvLastHeard: TextView
    private lateinit var tvCallsignId: TextView
    private lateinit var tvRxInfo: TextView
    private lateinit var hudWaveform: HudWaveformView
    private lateinit var hudSmeter: HudSmeterView
    private lateinit var pbVuMeter: ProgressBar
    private lateinit var btnConnect: Button
    private lateinit var btnPtt: Button
    private lateinit var btnProfiles: Button
    private lateinit var btnApps: Button

    private val uiHandler = Handler(Looper.getMainLooper())
    private var statusPulseAnimator: ObjectAnimator? = null
    private var callsignPulseAnimator: ValueAnimator? = null
    private var qsoStartMs: Long = 0L
    private var isQsoActive = false

    private val qsoTimerRunnable = object : Runnable {
        override fun run() {
            if (isQsoActive) {
                val elapsedSec = ((System.currentTimeMillis() - qsoStartMs) / 1000L).coerceAtLeast(0L)
                val mins = elapsedSec / 60L
                val secs = elapsedSec % 60L
                tvQsoTimer.text = String.format("%02d:%02d", mins, secs)
                uiHandler.postDelayed(this, 500L)
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as DmrService.LocalBinder
            val s = binder.service
            dmrService = s
            s.listener = this@MainActivity
            isBound = true
            s.tryAutoConnect()
            updateUiFromService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            dmrService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        setContentView(R.layout.activity_main)

        profileStore = ProfileStore(this)

        layoutLcdPanel = findViewById(R.id.layoutLcdPanel)
        tvChannelBadge = findViewById(R.id.tvChannelBadge)
        tvProfileName = findViewById(R.id.tvProfileName)
        tvAudioModeBadge = findViewById(R.id.tvAudioModeBadge)
        tvStatus = findViewById(R.id.tvStatus)
        tvTg = findViewById(R.id.tvTg)
        tvModeBadge = findViewById(R.id.tvModeBadge)
        tvQsoTimer = findViewById(R.id.tvQsoTimer)
        tvSlot = findViewById(R.id.tvSlot)
        tvLastHeard = findViewById(R.id.tvLastHeard)
        tvCallsignId = findViewById(R.id.tvCallsignId)
        tvRxInfo = findViewById(R.id.tvRxInfo)
        tvRxInfo.isSelected = true
        hudWaveform = findViewById(R.id.hudWaveform)
        hudSmeter = findViewById(R.id.hudSmeter)
        pbVuMeter = findViewById(R.id.pbVuMeter)
        btnConnect = findViewById(R.id.btnConnect)
        btnPtt = findViewById(R.id.btnPtt)
        btnProfiles = findViewById(R.id.btnProfiles)
        btnApps = findViewById(R.id.btnApps)

        checkPermissions()
        startAndBindService()
        setupListeners()
        attachTacticalButtonAnimations(btnConnect, btnPtt, btnProfiles, btnApps)
        updateProfileDisplay()
        updateAudioModeIndicator()

        // Auto-sync callbook on launch if not yet populated
        if (CallbookManager.count(this) < 10) {
            CallbookManager.importFromUrl(this, CallbookManager.getCustomUrl(this), {}, { count, _ ->
                if (count > 0) {
                    runOnUiThread { updateProfileDisplay() }
                }
            })
        }

        NinjaAudioFx.init(this)
        registerPttBroadcastReceiver()
        registerHeadsetPlugReceiver()

        btnConnect.requestFocus()
    }

    private fun attachTacticalButtonAnimations(vararg buttons: View) {
        for (btn in buttons) {
            btn.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.03f else 1.0f)
                    .scaleY(if (hasFocus) 1.03f else 1.0f)
                    .setDuration(110L)
                    .start()
            }
        }
    }

    private fun animatePress(v: View) {
        v.animate()
            .scaleX(0.93f)
            .scaleY(0.93f)
            .setDuration(75L)
            .withEndAction {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(110L).start()
            }
            .start()
    }

    private fun animatePop(v: View) {
        v.alpha = 0.35f
        v.translationY = 6f
        v.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180L)
            .start()
    }

    private fun startStatusPulse(enable: Boolean) {
        statusPulseAnimator?.cancel()
        if (enable) {
            val alphaHolder = PropertyValuesHolder.ofFloat(View.ALPHA, 1.0f, 0.45f)
            statusPulseAnimator = ObjectAnimator.ofPropertyValuesHolder(tvStatus, alphaHolder).apply {
                duration = 1100L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        } else {
            tvStatus.alpha = 1.0f
        }
    }

    private fun startQsoTimer(colorHex: String) {
        qsoStartMs = System.currentTimeMillis()
        isQsoActive = true
        tvQsoTimer.setTextColor(Color.parseColor(colorHex))
        tvQsoTimer.text = "00:00"
        uiHandler.removeCallbacks(qsoTimerRunnable)
        uiHandler.post(qsoTimerRunnable)
    }

    private fun stopQsoTimer() {
        isQsoActive = false
        uiHandler.removeCallbacks(qsoTimerRunnable)
        tvQsoTimer.text = "00:00"
        tvQsoTimer.setTextColor(Color.parseColor("#64748B"))
    }

    private fun updateAudioModeIndicator() {
        val voxOn = AudioPreferences.isVoxEnabled(this)
        val jackIn = AudioEngine.isExternalAudioConnected(this)
        val ncOn = AudioPreferences.isNoiseCancelEnabled(this)
        val ncTag = if (ncOn) "·NC" else ""
        when {
            voxOn && jackIn -> {
                tvAudioModeBadge.visibility = View.VISIBLE
                tvAudioModeBadge.text = "VOX+JACK$ncTag"
                tvAudioModeBadge.setTextColor(Color.parseColor("#34D399"))
            }
            voxOn -> {
                tvAudioModeBadge.visibility = View.VISIBLE
                tvAudioModeBadge.text = "VOX$ncTag"
                tvAudioModeBadge.setTextColor(Color.parseColor("#34D399"))
            }
            jackIn -> {
                tvAudioModeBadge.visibility = View.VISIBLE
                tvAudioModeBadge.text = "JACK$ncTag"
                tvAudioModeBadge.setTextColor(Color.parseColor("#38BDF8"))
            }
            ncOn -> {
                tvAudioModeBadge.visibility = View.VISIBLE
                tvAudioModeBadge.text = "NC+DSP"
                tvAudioModeBadge.setTextColor(Color.parseColor("#34D399"))
            }
            else -> {
                tvAudioModeBadge.visibility = View.GONE
            }
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQ_AUDIO_PERM
            )
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, DmrService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun setupListeners() {
        btnConnect.setOnClickListener {
            animatePress(it)
            val s = dmrService ?: return@setOnClickListener
            if (s.isConnectingOrConnected) {
                s.disconnect(manual = true)
            } else {
                val profile = profileStore.getActiveProfile()
                if (profile == null) {
                    Toast.makeText(this, "Buat channel/profil DMR terlebih dahulu", Toast.LENGTH_SHORT).show()
                    openProfiles()
                } else {
                    s.connectWithProfile(profile)
                }
            }
        }

        btnConnect.setOnLongClickListener {
            showCockpitSettingsDialog("server")
            true
        }

        // Tap CH badge to cycle to next channel & auto-reconnect!
        tvChannelBadge.setOnClickListener {
            animatePress(it)
            dmrService?.switchChannel(+1)
        }

        tvChannelBadge.setOnLongClickListener {
            showPttCalibrationDialog()
            true
        }

        findViewById<View>(R.id.layoutTopBar)?.setOnClickListener {
            showCockpitSettingsDialog("audio")
        }

        tvCallsignId.setOnClickListener {
            showCockpitSettingsDialog("callbook")
        }

        tvCallsignId.setOnLongClickListener {
            showCockpitSettingsDialog("callbook")
            true
        }

        btnProfiles.setOnClickListener {
            animatePress(it)
            openProfiles()
        }

        btnProfiles.setOnLongClickListener {
            showPttCalibrationDialog()
            true
        }

        btnApps.setOnClickListener {
            animatePress(it)
            showCockpitAppsDialog()
        }

        // PTT Touch Listener
        btnPtt.setOnTouchListener { v, event ->
            val s = dmrService ?: return@setOnTouchListener false
            if (AudioPreferences.isPttToggle(this)) {
                if (event.action == MotionEvent.ACTION_UP) {
                    animatePress(v)
                    if (s.isTx) s.endTx() else s.startTx()
                    true
                } else if (event.action == MotionEvent.ACTION_DOWN) {
                    true
                } else false
            } else {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(70L).start()
                        s.startTx()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(90L).start()
                        s.endTx()
                        true
                    }
                    else -> false
                }
            }
        }

        // PTT D-pad Center Key Listener on btnPtt
        btnPtt.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                val s = dmrService ?: return@setOnKeyListener false
                if (AudioPreferences.isPttToggle(this)) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        if (s.isTx) s.endTx() else s.startTx()
                        return@setOnKeyListener true
                    }
                } else {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        s.startTx()
                        return@setOnKeyListener true
                    } else if (event.action == KeyEvent.ACTION_UP) {
                        s.endTx()
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }
    }

    private fun openProfiles() {
        val intent = Intent(this, ProfilesActivity::class.java)
        startActivityForResult(intent, REQ_PROFILES)
    }

    private fun openDeviceSettings() {
        val candidates = listOf(
            Intent().setComponent(ComponentName("cc.unipro.usettings", "cc.unipro.usettings.MainActivity")),
            Intent(android.provider.Settings.ACTION_SETTINGS),
            Intent(android.provider.Settings.ACTION_WIFI_SETTINGS),
            Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
        )
        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            } catch (_: Exception) {}
        }
        Toast.makeText(this, "Tidak dapat membuka Pengaturan", Toast.LENGTH_SHORT).show()
    }

    private fun openAllApps() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.dtt.moreapp")
            if (intent != null) {
                startActivity(intent)
            } else {
                val fallback = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                startActivity(Intent.createChooser(fallback, "All Apps"))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak dapat membuka MoreApp", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROFILES) {
            updateProfileDisplay()
            val s = dmrService
            val active = profileStore.getActiveProfile()
            if (s != null && active != null) {
                if (s.isConnected && s.activeProfile?.id != active.id) {
                    s.connectWithProfile(active)
                } else if (!s.isConnected && resultCode == RESULT_OK) {
                    s.connectWithProfile(active)
                }
            }
        }
    }

    private fun startCallsignPulse(baseColor: Int, glowColor: Int) {
        callsignPulseAnimator?.cancel()
        callsignPulseAnimator = null
        tvCallsignId.scaleX = 1.0f
        tvCallsignId.scaleY = 1.0f
        tvCallsignId.alpha = 1.0f
        tvCallsignId.translationY = 0f
        // Layar fisik PoC 160x128 (98 dpi SPI TFT): gunakan warna solid statis agar pixel huruf 100% tajam tanpa flickering
        tvCallsignId.setTextColor(baseColor)
    }

    private fun updateLastHeardBanner(isRxActive: Boolean = (dmrService?.isRx == true)) {
        tvLastHeard.visibility = View.VISIBLE
        // Saat sedang ada stasiun masuk (RX), tampilkan stasiun SEBELUMNYA di baris LH agar tidak kembar dengan Callsign RX di bawahnya.
        // Begitu RX selesai (Standby) atau saat kita tekan PTT (TX), tampilkan stasiun terakhir yang barusan bicara (lastHeardDisplay).
        val targetLh = if (isRxActive) previousHeardDisplay else lastHeardDisplay
        if (targetLh.isNotEmpty()) {
            tvLastHeard.text = "LH: $targetLh"
            tvLastHeard.setTextColor(Color.parseColor("#FBBF24"))
        } else {
            tvLastHeard.text = "LH: -"
            tvLastHeard.setTextColor(Color.parseColor("#64748B"))
        }
    }

    private fun updateProfileDisplay() {
        val p = profileStore.getActiveProfile()
        val (chNum, totalCh) = profileStore.getActiveChannelIndex()
        updateLastHeardBanner()
        if (p != null) {
            if (p.callsign.isNotBlank()) {
                CallbookManager.cacheCallsign(this, p.dmrId, p.callsign)
            }
            tvChannelBadge.text = if (totalCh > 0) String.format("CH%02d", chNum) else "CH--"
            tvProfileName.text = p.name
            tvTg.text = "TG ${p.talkgroup}"
            tvSlot.text = "TS ${p.slot}"
            tvCallsignId.text = CallbookManager.resolveStationLabel(this, p.dmrId, p.callsign)
            startCallsignPulse(Color.parseColor("#38BDF8"), Color.parseColor("#BAE6FD"))
            tvRxInfo.text = "${p.name} · TG ${p.talkgroup} · TS ${p.slot} · STANDBY"
        } else {
            tvChannelBadge.text = "CH--"
            tvProfileName.text = "NINJA DMR"
            tvTg.text = "TG ---"
            tvSlot.text = "TS -"
            tvCallsignId.text = "NOCALL (0)"
            startCallsignPulse(Color.parseColor("#64748B"), Color.parseColor("#94A3B8"))
            tvRxInfo.text = "STANDBY · SIAP MEMANCAR"
        }
    }

    private fun showAudioSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_audio_settings, null)
        val tvMic = view.findViewById<TextView>(R.id.tvMicGainVal)
        val tvSpk = view.findViewById<TextView>(R.id.tvSpkGainVal)
        val btnMicDown = view.findViewById<Button>(R.id.btnMicDown)
        val btnMicUp = view.findViewById<Button>(R.id.btnMicUp)
        val btnSpkDown = view.findViewById<Button>(R.id.btnSpkDown)
        val btnSpkUp = view.findViewById<Button>(R.id.btnSpkUp)
        val btnClose = view.findViewById<Button>(R.id.btnAudioClose)

        var curMic = AudioPreferences.getMicGain(this)
        var curSpk = AudioPreferences.getSpkGain(this)
        var chirpOn = AudioPreferences.isNinjaChirpEnabled(this)
        var pttToggleOn = AudioPreferences.isPttToggle(this)
        var voxOn = AudioPreferences.isVoxEnabled(this)
        var headsetPrio = AudioPreferences.isHeadsetPriority(this)
        var totSec = AudioPreferences.getTotSeconds(this)
        var ncOn = AudioPreferences.isNoiseCancelEnabled(this)
        var autoConnOn = AudioPreferences.isAutoConnectEnabled(this)

        val btnToggleChirp = view.findViewById<Button>(R.id.btnToggleChirp)
        val btnTogglePttMode = view.findViewById<Button>(R.id.btnTogglePttMode)
        val btnToggleVox = view.findViewById<Button>(R.id.btnToggleVox)
        val btnToggleHeadset = view.findViewById<Button>(R.id.btnToggleHeadset)
        val btnToggleTot = view.findViewById<Button>(R.id.btnToggleTot)
        val btnToggleNc = view.findViewById<Button>(R.id.btnToggleNc)
        val btnToggleAutoConn = view.findViewById<Button>(R.id.btnToggleAutoConn)

        fun updateLabels() {
            tvMic.text = "${(curMic * 100).toInt()}%"
            tvSpk.text = "${(curSpk * 100).toInt()}%"
        }
        updateLabels()

        fun updateNcBtn() {
            btnToggleNc?.text = if (ncOn) "NC+DSP: ON" else "NC+DSP: OFF"
            btnToggleNc?.setTextColor(if (ncOn) Color.parseColor("#34D399") else Color.parseColor("#94A3B8"))
        }
        updateNcBtn()

        fun updateAutoConnBtn() {
            btnToggleAutoConn?.text = if (autoConnOn) "AUTO-CONN: ON" else "AUTO-CONN: OFF"
            btnToggleAutoConn?.setTextColor(if (autoConnOn) Color.parseColor("#34D399") else Color.parseColor("#94A3B8"))
        }
        updateAutoConnBtn()

        btnToggleNc?.setOnClickListener {
            ncOn = !ncOn
            updateNcBtn()
            AudioPreferences.setNoiseCancelEnabled(this, ncOn)
            dmrService?.updateAudioSettings()
            updateAudioModeIndicator()
            Toast.makeText(
                this,
                if (ncOn) "Noise Cancelling & Voice Clarity DSP Aktif" else "Noise Cancelling DSP Nonaktif",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnToggleAutoConn?.setOnClickListener {
            autoConnOn = !autoConnOn
            updateAutoConnBtn()
            AudioPreferences.setAutoConnectEnabled(this, autoConnOn)
            Toast.makeText(
                this,
                if (autoConnOn) "Auto-Connect Aktif saat aplikasi dibuka" else "Auto-Connect Nonaktif",
                Toast.LENGTH_SHORT
            ).show()
        }

        fun updateChirpBtn() {
            btnToggleChirp?.text = if (chirpOn) "CHIRP: ON" else "CHIRP: OFF"
            btnToggleChirp?.setTextColor(if (chirpOn) Color.parseColor("#34D399") else Color.parseColor("#64748B"))
        }
        updateChirpBtn()

        fun updateVoxBtn() {
            btnToggleVox?.text = if (voxOn) "VOX HT: ON (AUTO-TX)" else "VOX HT: OFF"
            btnToggleVox?.setTextColor(if (voxOn) Color.parseColor("#34D399") else Color.parseColor("#94A3B8"))
        }
        updateVoxBtn()

        fun updateHeadsetBtn() {
            btnToggleHeadset?.text = if (headsetPrio) "JACK HT: PRIORITAS" else "INPUT: MIC HP"
            btnToggleHeadset?.setTextColor(if (headsetPrio) Color.parseColor("#38BDF8") else Color.parseColor("#94A3B8"))
        }
        updateHeadsetBtn()

        fun updatePttModeBtn() {
            btnTogglePttMode?.text = if (pttToggleOn) "PTT: TOGGLE" else "PTT: HOLD"
            btnTogglePttMode?.setTextColor(if (pttToggleOn) Color.parseColor("#34D399") else Color.parseColor("#38BDF8"))
        }
        updatePttModeBtn()

        fun updateTotBtn() {
            btnToggleTot?.text = if (totSec == 0) "TOT: OFF" else "TOT: ${totSec}s"
            btnToggleTot?.setTextColor(if (totSec == 0) Color.parseColor("#94A3B8") else Color.parseColor("#38BDF8"))
        }
        updateTotBtn()

        btnToggleVox?.setOnClickListener {
            voxOn = !voxOn
            updateVoxBtn()
            AudioPreferences.setVoxEnabled(this, voxOn)
            dmrService?.updateAudioSettings()
            updateAudioModeIndicator()
            Toast.makeText(
                this,
                if (voxOn) "VOX HT Aktif: Otomatis TX saat ada suara masuk dari kabel HT/Jack" else "VOX HT Nonaktif",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnToggleHeadset?.setOnClickListener {
            headsetPrio = !headsetPrio
            updateHeadsetBtn()
            AudioPreferences.setHeadsetPriority(this, headsetPrio)
            dmrService?.updateAudioSettings()
        }

        btnToggleChirp?.setOnClickListener {
            chirpOn = !chirpOn
            updateChirpBtn()
            AudioPreferences.setNinjaChirpEnabled(this, chirpOn)
            if (chirpOn) {
                NinjaAudioFx.playSwordClang(this, force = true)
            }
        }

        btnTogglePttMode?.setOnClickListener {
            pttToggleOn = !pttToggleOn
            updatePttModeBtn()
            AudioPreferences.setPttToggle(this, pttToggleOn)
            Toast.makeText(
                this,
                if (pttToggleOn) "Mode TOGGLE: Tekan sekali TX, tekan lagi STOP" else "Mode HOLD: Tekan & tahan untuk TX",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnToggleTot?.setOnClickListener {
            totSec = when (totSec) {
                60 -> 120
                120 -> 180
                180 -> 300
                300 -> 0 // Unlimited
                else -> 60
            }
            updateTotBtn()
            AudioPreferences.setTotSeconds(this, totSec)
            Toast.makeText(
                this,
                if (totSec == 0) "TOT Cutoff Nonaktif (Unlimited TX)" else "TOT Limit diatur ke ${totSec}s",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Allow Mic Gain down to 10% (0.10f) for HT Line-In cables up to 500% (5.0f)
        btnMicDown.setOnClickListener {
            val step = if (curMic <= 1.0f) 0.10f else 0.25f
            curMic = (curMic - step).coerceAtLeast(0.10f)
            updateLabels()
        }
        btnMicUp.setOnClickListener {
            val step = if (curMic < 1.0f) 0.10f else 0.25f
            curMic = (curMic + step).coerceAtMost(5.0f)
            updateLabels()
        }
        btnSpkDown.setOnClickListener {
            curSpk = (curSpk - 0.10f).coerceAtLeast(0.20f)
            updateLabels()
        }
        btnSpkUp.setOnClickListener {
            curSpk = (curSpk + 0.10f).coerceAtMost(2.5f)
            updateLabels()
        }

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        val btnOpenCallbook = view.findViewById<Button>(R.id.btnOpenCallbook)
        btnOpenCallbook?.setOnClickListener {
            dialog.dismiss()
            showCallbookSettingsDialog()
        }

        view.findViewById<Button>(R.id.btnCalibratePtt)?.setOnClickListener {
            dialog.dismiss()
            showPttCalibrationDialog()
        }

        btnClose.setOnClickListener {
            AudioPreferences.setMicGain(this, curMic)
            AudioPreferences.setSpkGain(this, curSpk)
            AudioPreferences.setNinjaChirpEnabled(this, chirpOn)
            AudioPreferences.setVoxEnabled(this, voxOn)
            AudioPreferences.setHeadsetPriority(this, headsetPrio)
            AudioPreferences.setTotSeconds(this, totSec)
            AudioPreferences.setNoiseCancelEnabled(this, ncOn)
            AudioPreferences.setAutoConnectEnabled(this, autoConnOn)
            dmrService?.updateAudioSettings()
            updateAudioModeIndicator()
            Toast.makeText(this, "Pengaturan disimpan", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showCallbookSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_callbook_settings, null)
        val tvStatusCb = view.findViewById<TextView>(R.id.tvCbStatus)
        val etUrl = view.findViewById<EditText>(R.id.etCbUrl)
        val btnSync = view.findViewById<Button>(R.id.btnCbSync)
        val btnClear = view.findViewById<Button>(R.id.btnCbClear)
        val btnClose = view.findViewById<Button>(R.id.btnCbClose)

        fun refreshCount() {
            val n = CallbookManager.count(this)
            tvStatusCb.text = "Database: $n stasiun"
        }
        refreshCount()

        etUrl.setText(CallbookManager.getCustomUrl(this))

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        btnSync.setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "Masukkan link Callbook", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            CallbookManager.setCustomUrl(this, url)
            btnSync.isEnabled = false
            tvStatusCb.text = "Menghubungkan..."

            CallbookManager.importFromUrl(this, url,
                onProgress = { p ->
                    tvStatusCb.text = p
                },
                onComplete = { count, err ->
                    btnSync.isEnabled = true
                    if (err != null) {
                        tvStatusCb.text = "Gagal: $err"
                        Toast.makeText(this, "Gagal mengunduh: $err", Toast.LENGTH_LONG).show()
                    } else {
                        refreshCount()
                        updateProfileDisplay()
                        Toast.makeText(this, "Selesai! $count stasiun tersimpan", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        btnClear.setOnClickListener {
            CallbookManager.clear(this)
            refreshCount()
            Toast.makeText(this, "Database Callbook dikosongkan", Toast.LENGTH_SHORT).show()
        }

        btnClose.setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                CallbookManager.setCustomUrl(this, url)
            }
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showCockpitAppsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_cockpit_apps, null)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        view.findViewById<Button>(R.id.btnAppsClose)?.setOnClickListener {
            dialog.dismiss()
        }
        view.findViewById<Button>(R.id.btnAppCallbook)?.setOnClickListener {
            dialog.dismiss()
            showCockpitSettingsDialog("callbook")
        }
        view.findViewById<Button>(R.id.btnAppAudio)?.setOnClickListener {
            dialog.dismiss()
            showCockpitSettingsDialog("audio")
        }
        view.findViewById<Button>(R.id.btnAppCalib)?.setOnClickListener {
            dialog.dismiss()
            showPttCalibrationDialog()
        }
        view.findViewById<Button>(R.id.btnAppChannels)?.setOnClickListener {
            dialog.dismiss()
            openProfiles()
        }
        view.findViewById<Button>(R.id.btnAppSettings)?.setOnClickListener {
            dialog.dismiss()
            openDeviceSettings()
        }
        view.findViewById<Button>(R.id.btnAppMoreApp)?.setOnClickListener {
            dialog.dismiss()
            openAllApps()
        }

        dialog.show()
    }

    private fun showCockpitSettingsDialog(initialTab: String = "server") {
        val view = layoutInflater.inflate(R.layout.dialog_cockpit_full_conn, null)

        val btnTabServer = view.findViewById<Button>(R.id.btnTabServer)
        val btnTabAudio = view.findViewById<Button>(R.id.btnTabAudio)
        val btnTabCallbook = view.findViewById<Button>(R.id.btnTabCallbook)
        val btnSave = view.findViewById<Button>(R.id.btnCockpitSave)

        val layoutTabServer = view.findViewById<View>(R.id.layoutTabServer)
        val layoutTabAudio = view.findViewById<View>(R.id.layoutTabAudio)
        val layoutTabCallbook = view.findViewById<View>(R.id.layoutTabCallbook)

        fun selectTab(tab: String) {
            when (tab) {
                "server" -> {
                    layoutTabServer.visibility = View.VISIBLE
                    layoutTabAudio.visibility = View.GONE
                    layoutTabCallbook.visibility = View.GONE

                    btnTabServer.setBackgroundResource(R.drawable.badge_pill)
                    btnTabServer.setTextColor(Color.WHITE)
                    btnTabAudio.setBackgroundResource(R.drawable.btn_dpad)
                    btnTabAudio.setTextColor(Color.parseColor("#94A3B8"))
                    btnTabCallbook.setBackgroundResource(R.drawable.btn_dpad)
                    btnTabCallbook.setTextColor(Color.parseColor("#94A3B8"))
                }
                "audio" -> {
                    layoutTabServer.visibility = View.GONE
                    layoutTabAudio.visibility = View.VISIBLE
                    layoutTabCallbook.visibility = View.GONE

                    btnTabAudio.setBackgroundResource(R.drawable.badge_pill)
                    btnTabAudio.setTextColor(Color.WHITE)
                    btnTabServer.setBackgroundResource(R.drawable.btn_dpad)
                    btnTabServer.setTextColor(Color.parseColor("#94A3B8"))
                    btnTabCallbook.setBackgroundResource(R.drawable.btn_dpad)
                    btnTabCallbook.setTextColor(Color.parseColor("#94A3B8"))
                }
                "callbook" -> {
                    layoutTabServer.visibility = View.GONE
                    layoutTabAudio.visibility = View.GONE
                    layoutTabCallbook.visibility = View.VISIBLE

                    btnTabCallbook.setBackgroundResource(R.drawable.badge_pill)
                    btnTabCallbook.setTextColor(Color.WHITE)
                    btnTabServer.setBackgroundResource(R.drawable.btn_dpad)
                    btnTabServer.setTextColor(Color.parseColor("#94A3B8"))
                    btnTabAudio.setBackgroundResource(R.drawable.btn_dpad)
                    btnTabAudio.setTextColor(Color.parseColor("#94A3B8"))
                }
            }
        }

        btnTabServer.setOnClickListener { selectTab("server") }
        btnTabAudio.setOnClickListener { selectTab("audio") }
        btnTabCallbook.setOnClickListener { selectTab("callbook") }

        // --- TAB 1: SERVER DMR ---
        val etProfileName = view.findViewById<EditText>(R.id.etProfileName)
        val etMasterHost = view.findViewById<EditText>(R.id.etMasterHost)
        val etPort = view.findViewById<EditText>(R.id.etPort)
        val etPassword = view.findViewById<EditText>(R.id.etPassword)
        val etCallsign = view.findViewById<EditText>(R.id.etCallsign)
        val etDmrId = view.findViewById<EditText>(R.id.etDmrId)
        val etEssid = view.findViewById<EditText>(R.id.etEssid)
        val etTalkgroup = view.findViewById<EditText>(R.id.etTalkgroup)
        val btnSlotToggle = view.findViewById<Button>(R.id.btnSlotToggle)
        val etOptions = view.findViewById<EditText>(R.id.etOptions)
        val etSubscribedTgs = view.findViewById<EditText>(R.id.etSubscribedTgs)
        val btnQuickSlotMode = view.findViewById<Button>(R.id.btnQuickSlotMode)
        val etQuickFreq = view.findViewById<EditText>(R.id.etQuickFreq)

        val activeProfile = profileStore.getActiveProfile()
        var currentSlot = activeProfile?.slot ?: 2
        fun updateSlotBtn() {
            btnSlotToggle.text = "TS $currentSlot"
        }
        btnSlotToggle.setOnClickListener {
            currentSlot = if (currentSlot == 1) 2 else 1
            updateSlotBtn()
        }

        var currentSlotMode = activeProfile?.slotMode ?: "3"
        fun updateSlotModeBtn() {
            when (currentSlotMode) {
                "4" -> {
                    btnQuickSlotMode?.text = "MODE: RADIO (SIMP)"
                    btnQuickSlotMode?.setTextColor(Color.parseColor("#38BDF8"))
                }
                "2" -> {
                    btnQuickSlotMode?.text = "MODE: IP NETWORK"
                    btnQuickSlotMode?.setTextColor(Color.parseColor("#F59E0B"))
                }
                else -> {
                    btnQuickSlotMode?.text = "MODE: REPEATER (DUP)"
                    btnQuickSlotMode?.setTextColor(Color.parseColor("#34D399"))
                }
            }
        }
        updateSlotModeBtn()
        btnQuickSlotMode?.setOnClickListener {
            currentSlotMode = when (currentSlotMode) {
                "3" -> "4"
                "4" -> "2"
                else -> "3"
            }
            updateSlotModeBtn()
        }

        if (activeProfile != null) {
            etProfileName.setText(activeProfile.name)
            etMasterHost.setText(activeProfile.host)
            etPort.setText(activeProfile.port.toString())
            etPassword.setText(activeProfile.password)
            etCallsign.setText(activeProfile.callsign)
            etDmrId.setText(if (activeProfile.dmrId > 0) activeProfile.dmrId.toString() else "")
            etEssid.setText(activeProfile.essid.toString())
            etTalkgroup.setText(if (activeProfile.talkgroup > 0) activeProfile.talkgroup.toString() else "")
            updateSlotBtn()
            etOptions.setText(activeProfile.options)
            etSubscribedTgs.setText(activeProfile.rxGroup)
            etQuickFreq?.setText(if (activeProfile.rxFreq.isNotBlank()) activeProfile.rxFreq else "145.5000")
        }

        // --- TAB 2: AUDIO & HT ---
        val tvMic = view.findViewById<TextView>(R.id.tvMicGainVal)
        val tvSpk = view.findViewById<TextView>(R.id.tvSpkGainVal)
        val btnMicDown = view.findViewById<Button>(R.id.btnMicDown)
        val btnMicUp = view.findViewById<Button>(R.id.btnMicUp)
        val btnSpkDown = view.findViewById<Button>(R.id.btnSpkDown)
        val btnSpkUp = view.findViewById<Button>(R.id.btnSpkUp)
        val btnTotCycle = view.findViewById<Button>(R.id.btnTotCycle)
        val btnVoxToggle = view.findViewById<Button>(R.id.btnVoxToggle)
        val btnJackPriority = view.findViewById<Button>(R.id.btnJackPriority)
        val btnChirpToggle = view.findViewById<Button>(R.id.btnChirpToggle)
        val btnPttModeToggle = view.findViewById<Button>(R.id.btnPttModeToggle)
        val btnNcToggle = view.findViewById<Button>(R.id.btnNcToggle)
        val btnAutoConnToggle = view.findViewById<Button>(R.id.btnAutoConnToggle)

        var curMic = AudioPreferences.getMicGain(this)
        var curSpk = AudioPreferences.getSpkGain(this)
        var chirpOn = AudioPreferences.isNinjaChirpEnabled(this)
        var pttToggleOn = AudioPreferences.isPttToggle(this)
        var voxOn = AudioPreferences.isVoxEnabled(this)
        var headsetPrio = AudioPreferences.isHeadsetPriority(this)
        var totSec = AudioPreferences.getTotSeconds(this)
        var ncOn = AudioPreferences.isNoiseCancelEnabled(this)
        var autoConnOn = AudioPreferences.isAutoConnectEnabled(this)

        fun updateAudioUi() {
            tvMic.text = "${(curMic * 100).toInt()}%"
            tvSpk.text = "${(curSpk * 100).toInt()}%"
            btnChirpToggle.text = if (chirpOn) "CHIRP: ON" else "CHIRP: OFF"
            btnChirpToggle.setTextColor(if (chirpOn) Color.parseColor("#34D399") else Color.parseColor("#64748B"))
            btnVoxToggle.text = if (voxOn) "VOX HT: ON" else "VOX HT: OFF"
            btnVoxToggle.setTextColor(if (voxOn) Color.parseColor("#34D399") else Color.parseColor("#94A3B8"))
            btnJackPriority.text = if (headsetPrio) "JACK: PRIORITAS" else "JACK: AUTO"
            btnJackPriority.setTextColor(if (headsetPrio) Color.parseColor("#38BDF8") else Color.parseColor("#94A3B8"))
            btnPttModeToggle.text = if (pttToggleOn) "PTT: TOGGLE" else "PTT: HOLD"
            btnPttModeToggle.setTextColor(if (pttToggleOn) Color.parseColor("#34D399") else Color.parseColor("#38BDF8"))
            btnTotCycle.text = if (totSec == 0) "TOT: OFF" else "TOT: ${totSec}s"
            btnTotCycle.setTextColor(if (totSec == 0) Color.parseColor("#94A3B8") else Color.parseColor("#38BDF8"))
            btnNcToggle?.text = if (ncOn) "NC+DSP: ON" else "NC+DSP: OFF"
            btnNcToggle?.setTextColor(if (ncOn) Color.parseColor("#34D399") else Color.parseColor("#94A3B8"))
            btnAutoConnToggle?.text = if (autoConnOn) "AUTO-CONN: ON" else "AUTO-CONN: OFF"
            btnAutoConnToggle?.setTextColor(if (autoConnOn) Color.parseColor("#34D399") else Color.parseColor("#94A3B8"))
        }
        updateAudioUi()

        btnNcToggle?.setOnClickListener {
            ncOn = !ncOn
            updateAudioUi()
            AudioPreferences.setNoiseCancelEnabled(this, ncOn)
            dmrService?.updateAudioSettings()
            updateAudioModeIndicator()
        }
        btnAutoConnToggle?.setOnClickListener {
            autoConnOn = !autoConnOn
            updateAudioUi()
            AudioPreferences.setAutoConnectEnabled(this, autoConnOn)
        }

        btnMicDown.setOnClickListener {
            curMic = (curMic - 0.25f).coerceAtLeast(0.1f)
            updateAudioUi()
            AudioPreferences.setMicGain(this, curMic)
            dmrService?.updateAudioSettings()
        }
        btnMicUp.setOnClickListener {
            curMic = (curMic + 0.25f).coerceAtMost(5.0f)
            updateAudioUi()
            AudioPreferences.setMicGain(this, curMic)
            dmrService?.updateAudioSettings()
        }
        btnSpkDown.setOnClickListener {
            curSpk = (curSpk - 0.1f).coerceAtLeast(0.1f)
            updateAudioUi()
            AudioPreferences.setSpkGain(this, curSpk)
            dmrService?.updateAudioSettings()
        }
        btnSpkUp.setOnClickListener {
            curSpk = (curSpk + 0.1f).coerceAtMost(3.0f)
            updateAudioUi()
            AudioPreferences.setSpkGain(this, curSpk)
            dmrService?.updateAudioSettings()
        }
        btnTotCycle.setOnClickListener {
            val list = listOf(0, 30, 60, 90, 120, 180, 240, 300)
            val idx = list.indexOf(totSec)
            totSec = list[(idx + 1) % list.size]
            updateAudioUi()
            AudioPreferences.setTotSeconds(this, totSec)
        }
        btnVoxToggle.setOnClickListener {
            voxOn = !voxOn
            updateAudioUi()
            AudioPreferences.setVoxEnabled(this, voxOn)
            dmrService?.updateAudioSettings()
            updateAudioModeIndicator()
        }
        btnJackPriority.setOnClickListener {
            headsetPrio = !headsetPrio
            updateAudioUi()
            AudioPreferences.setHeadsetPriority(this, headsetPrio)
            dmrService?.updateAudioSettings()
        }
        btnChirpToggle.setOnClickListener {
            chirpOn = !chirpOn
            updateAudioUi()
            AudioPreferences.setNinjaChirpEnabled(this, chirpOn)
            if (chirpOn) NinjaAudioFx.playSwordClang(this, force = true)
        }
        btnPttModeToggle.setOnClickListener {
            pttToggleOn = !pttToggleOn
            updateAudioUi()
            AudioPreferences.setPttToggle(this, pttToggleOn)
        }

        // --- TAB 3: CALLBOOK ---
        val tvCbCount = view.findViewById<TextView>(R.id.tvCbCountVal)
        val etCbUrl = view.findViewById<EditText>(R.id.etCbUrlInput)
        val btnCbSync = view.findViewById<Button>(R.id.btnCbSyncNow)
        val btnCbClear = view.findViewById<Button>(R.id.btnCbClearNow)
        val tvCbMsg = view.findViewById<TextView>(R.id.tvCbProgressMsg)

        fun refreshCbCount() {
            val cnt = CallbookManager.count(this)
            tvCbCount.text = "$cnt stasiun"
        }
        refreshCbCount()
        etCbUrl.setText(CallbookManager.getCustomUrl(this))

        btnCbSync.setOnClickListener {
            val url = etCbUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "Masukkan link Callbook", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            CallbookManager.setCustomUrl(this, url)
            btnCbSync.isEnabled = false
            tvCbMsg.text = "Menghubungkan & mengunduh..."
            CallbookManager.importFromUrl(this, url,
                onProgress = { p -> runOnUiThread { tvCbMsg.text = p } },
                onComplete = { count, err ->
                    runOnUiThread {
                        btnCbSync.isEnabled = true
                        if (err != null) {
                            tvCbMsg.text = "Gagal: $err"
                            Toast.makeText(this, "Gagal mengunduh: $err", Toast.LENGTH_LONG).show()
                        } else {
                            refreshCbCount()
                            updateProfileDisplay()
                            tvCbMsg.text = "✓ Selesai! $count stasiun tersimpan"
                            Toast.makeText(this, "Selesai! $count stasiun terindeks", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }

        btnCbClear.setOnClickListener {
            CallbookManager.clear(this)
            refreshCbCount()
            tvCbMsg.text = "Database dikosongkan"
            Toast.makeText(this, "Database Callbook dikosongkan", Toast.LENGTH_SHORT).show()
        }

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        btnSave.setOnClickListener {
            // Save Server Profile if modified
            if (activeProfile != null) {
                val newName = etProfileName.text.toString().trim()
                if (newName.isNotEmpty()) activeProfile.name = newName
                val newHost = etMasterHost.text.toString().trim()
                if (newHost.isNotEmpty()) activeProfile.host = newHost
                val newPort = etPort.text.toString().trim().toIntOrNull()
                if (newPort != null && newPort > 0) activeProfile.port = newPort
                activeProfile.password = etPassword.text.toString().trim()
                val newCall = etCallsign.text.toString().trim().uppercase()
                if (newCall.isNotEmpty()) activeProfile.callsign = newCall
                val newId = etDmrId.text.toString().trim().toIntOrNull()
                if (newId != null && newId > 0) activeProfile.dmrId = newId
                val newEssid = etEssid.text.toString().trim().toIntOrNull()
                if (newEssid != null) activeProfile.essid = newEssid
                val newTg = etTalkgroup.text.toString().trim().toIntOrNull()
                if (newTg != null) activeProfile.talkgroup = newTg
                activeProfile.slot = currentSlot
                activeProfile.options = etOptions.text.toString().trim()
                activeProfile.rxGroup = etSubscribedTgs.text.toString().trim()
                activeProfile.slotMode = currentSlotMode
                val newFreq = etQuickFreq?.text?.toString()?.trim()?.ifBlank { "145.5000" } ?: "145.5000"
                activeProfile.rxFreq = newFreq
                activeProfile.txFreq = newFreq

                profileStore.saveOrUpdateProfile(activeProfile)
                updateProfileDisplay()

                val s = dmrService
                if (s != null && s.isConnected) {
                    s.connectWithProfile(activeProfile)
                }
            }

            val cbUrl = etCbUrl.text.toString().trim()
            if (cbUrl.isNotEmpty()) {
                CallbookManager.setCustomUrl(this, cbUrl)
            }

            dialog.dismiss()
            Toast.makeText(this, "Pengaturan Disimpan", Toast.LENGTH_SHORT).show()
        }

        selectTab(initialTab)
        dialog.show()
    }

    private fun updateUiFromService() {
        val s = dmrService ?: return
        updateAudioModeIndicator()
        onStateChanged(s.currentState, "")
        if (s.isTx) {
            onTxStateChanged(true)
        } else if (s.isRx) {
            onRxStateChanged(true, s.currentRxSrc, s.currentRxDst)
        }
    }

    private fun showPttCalibrationDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_ptt_calibration, null)
        val tvCurrent = view.findViewById<TextView>(R.id.tvCurrentPttSetting)
        val tvDetect = view.findViewById<TextView>(R.id.tvPttDetectStatus)
        val btnTopClose = view.findViewById<Button>(R.id.btnTopCloseCalib)
        val btnTargetPtt = view.findViewById<Button>(R.id.btnTargetPtt)
        val btnTargetChNext = view.findViewById<Button>(R.id.btnTargetChNext)
        val btnTargetChPrev = view.findViewById<Button>(R.id.btnTargetChPrev)
        val btnSave = view.findViewById<Button>(R.id.btnSaveLearnedKey)
        val btnReset = view.findViewById<Button>(R.id.btnResetPttDefault)
        val btnCancel = view.findViewById<Button>(R.id.btnCancelPttCalib)

        learningTarget = PttManager.KeyTarget.PTT
        pendingLearnedKey = null
        isLearningPtt = true

        fun updateSummaryText() {
            val pttDesc = PttManager.getCustomKeyDescription(this)
            val chNextDesc = PttManager.getChannelNextDescription(this)
            val chPrevDesc = PttManager.getChannelPrevDescription(this)
            tvCurrent.text = "PTT: $pttDesc | CH+: $chNextDesc | CH-: $chPrevDesc"
        }

        fun updateTargetUi() {
            updateSummaryText()
            btnTargetPtt.alpha = if (learningTarget == PttManager.KeyTarget.PTT) 1.0f else 0.50f
            btnTargetChNext.alpha = if (learningTarget == PttManager.KeyTarget.CH_NEXT) 1.0f else 0.50f
            btnTargetChPrev.alpha = if (learningTarget == PttManager.KeyTarget.CH_PREV) 1.0f else 0.50f

            val targetLabel = when (learningTarget) {
                PttManager.KeyTarget.PTT -> "1. TOMBOL PTT (TX)"
                PttManager.KeyTarget.CH_NEXT -> "2. TOMBOL CH+ (NEXT)"
                PttManager.KeyTarget.CH_PREV -> "3. TOMBOL CH- (PREV)"
            }
            tvDetect.text = "TARGET: $targetLabel\nTekan tombol fisik HT untuk simpan otomatis..."
            tvDetect.setTextColor(Color.parseColor("#F8FAFC"))
            btnSave.isEnabled = false
            pendingLearnedKey = null
        }

        btnTargetPtt.setOnClickListener {
            learningTarget = PttManager.KeyTarget.PTT
            updateTargetUi()
        }
        btnTargetChNext.setOnClickListener {
            learningTarget = PttManager.KeyTarget.CH_NEXT
            updateTargetUi()
        }
        btnTargetChPrev.setOnClickListener {
            learningTarget = PttManager.KeyTarget.CH_PREV
            updateTargetUi()
        }

        updateTargetUi()

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .setOnCancelListener {
                isLearningPtt = false
                pttCalibDialog = null
            }
            .create()

        pttCalibDialog = dialog

        val closeAction = View.OnClickListener {
            isLearningPtt = false
            pttCalibDialog = null
            dialog.dismiss()
        }
        btnTopClose?.setOnClickListener(closeAction)
        btnCancel.setOnClickListener(closeAction)

        btnSave.setOnClickListener {
            val key = pendingLearnedKey
            if (key != null) {
                PttManager.saveTargetKey(this, learningTarget, key.first, key.second, key.third)
                val label = when (learningTarget) {
                    PttManager.KeyTarget.PTT -> "PTT"
                    PttManager.KeyTarget.CH_NEXT -> "CH+ (Next)"
                    PttManager.KeyTarget.CH_PREV -> "CH- (Prev)"
                }
                Toast.makeText(this, "Tombol $label disimpan: ${key.third}", Toast.LENGTH_SHORT).show()
                updateSummaryText()
            }
            isLearningPtt = false
            pttCalibDialog = null
            dialog.dismiss()
        }

        btnReset.setOnClickListener {
            PttManager.clearAllKeys(this)
            pendingLearnedKey = null
            updateTargetUi()
            tvDetect.text = "Semua Tombol Direset ke Default!"
            tvDetect.setTextColor(Color.parseColor("#34D399"))
            btnSave.isEnabled = false
            Toast.makeText(this, "Tombol PTT & Channel direset ke Default", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun onPttKeyLearned(keyCode: Int, scanCode: Int, name: String) {
        runOnUiThread {
            pendingLearnedKey = Triple(keyCode, scanCode, name)
            // Auto-save immediately on detection so user doesn't even lose it if they just close!
            PttManager.saveTargetKey(this, learningTarget, keyCode, scanCode, name)
            val targetName = when (learningTarget) {
                PttManager.KeyTarget.PTT -> "PTT"
                PttManager.KeyTarget.CH_NEXT -> "CH+ (Next)"
                PttManager.KeyTarget.CH_PREV -> "CH- (Prev)"
            }
            pttCalibDialog?.findViewById<TextView>(R.id.tvCurrentPttSetting)?.let { tvCur ->
                val pttDesc = PttManager.getCustomKeyDescription(this)
                val chNextDesc = PttManager.getChannelNextDescription(this)
                val chPrevDesc = PttManager.getChannelPrevDescription(this)
                tvCur.text = "PTT: $pttDesc | CH+: $chNextDesc | CH-: $chPrevDesc"
            }
            pttCalibDialog?.findViewById<TextView>(R.id.tvPttDetectStatus)?.let { tv ->
                tv.text = "✓ TERSIMPAN UNTUK $targetName:\n$name (Code:$keyCode, Scan:$scanCode)"
                tv.setTextColor(Color.parseColor("#34D399"))
            }
            pttCalibDialog?.findViewById<Button>(R.id.btnSaveLearnedKey)?.isEnabled = true
        }
    }

    private fun registerHeadsetPlugReceiver() {
        if (headsetPlugReceiver != null) return
        val filter = IntentFilter(Intent.ACTION_HEADSET_PLUG).apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                uiHandler.postDelayed({
                    updateAudioModeIndicator()
                    dmrService?.updateAudioSettings()
                }, 200L)
            }
        }
        headsetPlugReceiver = receiver
        try {
            registerReceiver(receiver, filter)
        } catch (_: Exception) {}
    }

    private fun unregisterHeadsetPlugReceiver() {
        headsetPlugReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        headsetPlugReceiver = null
    }

    private fun registerPttBroadcastReceiver() {
        if (pttBroadcastReceiver != null) return
        val filter = IntentFilter().apply {
            PttManager.ALL_BROADCAST_ACTIONS.forEach { addAction(it) }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val (isDown, isUp, keyCode) = PttManager.parseBroadcast(intent ?: return)
                val keyName = PttManager.formatKeyName(keyCode)

                if (isLearningPtt) {
                    if (isDown) {
                        onPttKeyLearned(keyCode, 0, keyName)
                    }
                    return
                }
                // When not learning PTT, DmrService background receiver handles channel/PTT broadcasts.
            }
        }
        pttBroadcastReceiver = receiver
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
        } catch (_: Exception) {}
    }

    private fun unregisterPttBroadcastReceiver() {
        pttBroadcastReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        pttBroadcastReceiver = null
    }

    // Intercept hardware keys across all focused views (PTT, CH+, CH-)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isLearningPtt) {
            // Allow D-pad navigation inside the dialog if user is clicking Save/Cancel with D-Pad
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_BACK) {
                return super.dispatchKeyEvent(event)
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val name = PttManager.formatKeyName(event.keyCode)
                onPttKeyLearned(event.keyCode, event.scanCode, name)
            }
            return true
        }

        // Check Channel Next (CH+)
        if (PttManager.matchesChannelNext(this, event.keyCode, event.scanCode)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                dmrService?.switchChannel(+1)
            }
            return true
        }

        // Check Channel Prev (CH-)
        if (PttManager.matchesChannelPrev(this, event.keyCode, event.scanCode)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                dmrService?.switchChannel(-1)
            }
            return true
        }

        // Check PTT Key
        if (PttManager.matches(this, event.keyCode, event.scanCode)) {
            val s = dmrService ?: return true
            if (AudioPreferences.isPttToggle(this)) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (s.isTx) s.endTx() else s.startTx()
                }
            } else {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    s.startTx()
                } else if (event.action == KeyEvent.ACTION_UP) {
                    s.endTx()
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showPttCalibrationDialog()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_POUND || keyCode == KeyEvent.KEYCODE_STAR) {
            showCallbookSettingsDialog()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) return true
        return super.onKeyUp(keyCode, event)
    }

    // DmrService.Listener
    override fun onChannelSwitched(profile: DmrProfile, channelIndex: Int, totalChannels: Int) {
        runOnUiThread {
            lastHeardDisplay = ""
            lastHeardSrcId = 0
            previousHeardDisplay = ""
            updateProfileDisplay()
            animatePop(tvChannelBadge)
            animatePop(tvProfileName)
            animatePop(tvTg)
            tvRxInfo.text = String.format("PINDAH KE CH%02d · MENGHUBUNGKAN...", channelIndex)
            tvRxInfo.setTextColor(Color.parseColor("#FBBF24"))
            Toast.makeText(
                this,
                String.format("CH %02d/%02d: %s (TG %d)", channelIndex, totalChannels, profile.name, profile.talkgroup),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onStateChanged(state: HbpClient.State, info: String) {
        runOnUiThread {
            updateAudioModeIndicator()
            when (state) {
                HbpClient.State.CONNECTED -> {
                    btnConnect.text = "DISC"
                    btnConnect.setTextColor(Color.parseColor("#34D399"))
                    tvStatus.text = "● ONLINE"
                    tvStatus.setTextColor(Color.parseColor("#34D399"))
                    tvStatus.setBackgroundResource(R.drawable.badge_status_online)
                    tvModeBadge.text = "STANDBY"
                    tvModeBadge.setTextColor(Color.parseColor("#38BDF8"))
                    tvRxInfo.text = "STANDBY · SIAP MEMANCAR"
                    tvRxInfo.setTextColor(Color.parseColor("#64748B"))
                    layoutLcdPanel.setBackgroundResource(R.drawable.card_lcd)
                    startStatusPulse(false)
                }
                HbpClient.State.LOGIN,
                HbpClient.State.AUTH,
                HbpClient.State.CONFIG -> {
                    btnConnect.text = "DISC"
                    btnConnect.setTextColor(Color.parseColor("#FBBF24"))
                    tvStatus.text = "● SYNC"
                    tvStatus.setTextColor(Color.parseColor("#FBBF24"))
                    tvStatus.setBackgroundResource(R.drawable.badge_status_offline)
                    tvModeBadge.text = "SYNCING"
                    tvModeBadge.setTextColor(Color.parseColor("#FBBF24"))
                    startStatusPulse(true)
                }
                HbpClient.State.RETRY -> {
                    btnConnect.text = "DISC"
                    btnConnect.setTextColor(Color.parseColor("#F59E0B"))
                    tvStatus.text = "● RETRY"
                    tvStatus.setTextColor(Color.parseColor("#F59E0B"))
                    tvStatus.setBackgroundResource(R.drawable.badge_status_offline)
                    tvModeBadge.text = "RETRY"
                    tvModeBadge.setTextColor(Color.parseColor("#F59E0B"))
                    startStatusPulse(true)
                }
                HbpClient.State.STOPPED -> {
                    btnConnect.text = "CONN"
                    btnConnect.setTextColor(Color.parseColor("#38BDF8"))
                    tvStatus.text = "● OFFLINE"
                    tvStatus.setTextColor(Color.parseColor("#94A3B8"))
                    tvStatus.setBackgroundResource(R.drawable.badge_status_offline)
                    tvModeBadge.text = "OFFLINE"
                    tvModeBadge.setTextColor(Color.parseColor("#64748B"))
                    tvRxInfo.text = "STANDBY · OFFLINE"
                    tvRxInfo.setTextColor(Color.parseColor("#64748B"))
                    layoutLcdPanel.setBackgroundResource(R.drawable.card_lcd)
                    hudSmeter.setLevel(0f)
                    hudWaveform.setModeAndLevel(HudWaveformView.Mode.STANDBY, 0f)
                    pbVuMeter.progress = 0
                    stopQsoTimer()
                    startStatusPulse(false)
                }
            }
        }
    }

    override fun onRxStateChanged(active: Boolean, srcId: Int, talkgroup: Int) {
        runOnUiThread {
            if (active) {
                layoutLcdPanel.setBackgroundResource(R.drawable.card_lcd_rx)
                tvStatus.text = "● RX"
                tvStatus.setTextColor(Color.parseColor("#34D399"))
                tvStatus.setBackgroundResource(R.drawable.badge_status_rx)
                tvModeBadge.text = "● RX"
                tvModeBadge.setTextColor(Color.parseColor("#34D399"))
                startStatusPulse(true)
                startQsoTimer("#34D399")
                hudWaveform.setModeAndLevel(HudWaveformView.Mode.RX, 0.35f)

                if (talkgroup > 0) {
                    tvTg.text = "TG $talkgroup"
                    tvTg.setTextColor(Color.parseColor("#34D399"))
                }

                val p = profileStore.getActiveProfile()
                val knownCall = CallbookManager.getCallsign(this, srcId)
                    ?: if (p != null && srcId == p.dmrId) CallbookManager.resolveStationLabel(this, p.dmrId, p.callsign) else null

                val displayLabel = knownCall ?: "$srcId"
                if (lastHeardSrcId != 0 && lastHeardSrcId != srcId && lastHeardDisplay.isNotEmpty()) {
                    previousHeardDisplay = lastHeardDisplay
                }
                lastHeardSrcId = srcId
                lastHeardDisplay = "$displayLabel"
                updateLastHeardBanner(isRxActive = true)
                tvCallsignId.text = "$displayLabel"
                startCallsignPulse(Color.parseColor("#34D399"), Color.parseColor("#A7F3D0"))
                val targetTg = if (talkgroup > 0) talkgroup else (p?.talkgroup ?: 0)
                tvRxInfo.text = if (knownCall != null) "RX ID: $srcId · TG $targetTg · SUARA MASUK" else "MENCARI CALLSIGN ($srcId)..."
                tvRxInfo.setTextColor(Color.parseColor("#34D399"))

                if (knownCall == null) {
                    CallbookManager.resolveAsync(this, srcId) { resolved ->
                        if (lastHeardSrcId == srcId) {
                            lastHeardDisplay = "$resolved"
                        }
                        if (dmrService?.isRx == true && dmrService?.currentRxSrc == srcId) {
                            tvCallsignId.text = "$resolved"
                            tvRxInfo.text = "RX ID: $srcId · TG $targetTg · SUARA MASUK"
                            updateLastHeardBanner(isRxActive = true)
                        } else {
                            updateLastHeardBanner(isRxActive = false)
                        }
                    }
                }
            } else {
                layoutLcdPanel.setBackgroundResource(R.drawable.card_lcd)
                stopQsoTimer()
                startStatusPulse(false)
                hudWaveform.setModeAndLevel(HudWaveformView.Mode.STANDBY, 0f)
                hudSmeter.setLevel(0f)
                updateLastHeardBanner(isRxActive = false)

                val s = dmrService
                if (s?.isConnected == true) {
                    tvStatus.text = "● ONLINE"
                    tvStatus.setTextColor(Color.parseColor("#34D399"))
                    tvStatus.setBackgroundResource(R.drawable.badge_status_online)
                    tvModeBadge.text = "STBY"
                    tvModeBadge.setTextColor(Color.parseColor("#38BDF8"))
                }
                val p = profileStore.getActiveProfile()
                if (p != null) {
                    tvTg.text = "TG ${p.talkgroup}"
                    tvTg.setTextColor(Color.parseColor("#38BDF8"))
                    tvCallsignId.text = CallbookManager.resolveStationLabel(this, p.dmrId, p.callsign)
                } else {
                    updateProfileDisplay()
                }
                startCallsignPulse(Color.parseColor("#38BDF8"), Color.parseColor("#BAE6FD"))
                tvRxInfo.text = "STANDBY · SIAP MEMANCAR"
                tvRxInfo.setTextColor(Color.parseColor("#64748B"))
                pbVuMeter.progress = 0
            }
        }
    }

    override fun onTxStateChanged(active: Boolean) {
        runOnUiThread {
            if (active) {
                layoutLcdPanel.setBackgroundResource(R.drawable.card_lcd_tx)
                tvStatus.text = "● TX"
                tvStatus.setTextColor(Color.parseColor("#F43F5E"))
                tvStatus.setBackgroundResource(R.drawable.badge_status_tx)
                tvModeBadge.text = "● TX"
                tvModeBadge.setTextColor(Color.parseColor("#F43F5E"))
                tvTg.setTextColor(Color.parseColor("#F43F5E"))
                startStatusPulse(true)
                startQsoTimer("#F43F5E")
                hudWaveform.setModeAndLevel(HudWaveformView.Mode.TX, 0.4f)

                // Tampilkan info Last Heard (LH) tepat di atas Callsign User
                updateLastHeardBanner(isRxActive = false)

                val p = profileStore.getActiveProfile()
                val call = p?.callsign?.ifBlank { "ME" } ?: "ME"
                tvCallsignId.text = CallbookManager.resolveStationLabel(this, p?.dmrId ?: 0, call)
                startCallsignPulse(Color.parseColor("#FEF08A"), Color.parseColor("#FEF08A"))
                tvRxInfo.text = "TRANSMIT KE TG ${p?.talkgroup ?: 0} · MIC AKTIF"
                tvRxInfo.setTextColor(Color.parseColor("#F43F5E"))
                btnPtt.setBackgroundResource(R.drawable.btn_ptt_active)
            } else {
                layoutLcdPanel.setBackgroundResource(R.drawable.card_lcd)
                stopQsoTimer()
                startStatusPulse(false)
                hudWaveform.setModeAndLevel(HudWaveformView.Mode.STANDBY, 0f)
                hudSmeter.setLevel(0f)
                updateLastHeardBanner(isRxActive = false)

                val s = dmrService
                if (s?.isConnected == true) {
                    tvStatus.text = "● ONLINE"
                    tvStatus.setTextColor(Color.parseColor("#34D399"))
                    tvStatus.setBackgroundResource(R.drawable.badge_status_online)
                    tvModeBadge.text = "STBY"
                    tvModeBadge.setTextColor(Color.parseColor("#38BDF8"))
                }
                val p = profileStore.getActiveProfile()
                if (p != null) {
                    tvTg.text = "TG ${p.talkgroup}"
                    tvTg.setTextColor(Color.parseColor("#38BDF8"))
                    tvCallsignId.text = CallbookManager.resolveStationLabel(this, p.dmrId, p.callsign)
                } else {
                    updateProfileDisplay()
                }
                startCallsignPulse(Color.parseColor("#38BDF8"), Color.parseColor("#BAE6FD"))
                tvRxInfo.text = "STANDBY · SIAP MEMANCAR"
                tvRxInfo.setTextColor(Color.parseColor("#64748B"))
                btnPtt.setBackgroundResource(R.drawable.btn_ptt)
                pbVuMeter.progress = 0
            }
        }
    }

    override fun onAudioLevel(level: Float, isTx: Boolean) {
        runOnUiThread {
            val clamped = level.coerceIn(0f, 1f)
            hudSmeter.setLevel(clamped)
            val mode = when {
                isTx || dmrService?.isTx == true -> HudWaveformView.Mode.TX
                dmrService?.isRx == true -> HudWaveformView.Mode.RX
                else -> HudWaveformView.Mode.STANDBY
            }
            hudWaveform.setModeAndLevel(mode, clamped)
            pbVuMeter.progress = (clamped * 100).toInt()
        }
    }

    override fun onDestroy() {
        stopQsoTimer()
        statusPulseAnimator?.cancel()
        unregisterPttBroadcastReceiver()
        unregisterHeadsetPlugReceiver()
        pttCalibDialog?.dismiss()
        pttCalibDialog = null
        if (isBound) {
            dmrService?.listener = null
            unbindService(connection)
            isBound = false
        }
        super.onDestroy()
    }

    companion object {
        private const val REQ_PROFILES = 1001
        private const val REQ_AUDIO_PERM = 1002
    }
}
