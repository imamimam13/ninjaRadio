package com.ninjaradio.svx

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), SvxReflectorClient.Listener, UsbSerialController.Listener {

    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etCallsign: EditText
    private lateinit var etPassword: EditText
    private lateinit var etTg: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnPtt: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvTalker: TextView
    private lateinit var tvNodes: TextView
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView
    private lateinit var pbLevel: ProgressBar

    // HT Gateway Controls
    private lateinit var spAudioRoute: Spinner
    private lateinit var spBaudRate: Spinner
    private lateinit var spPttMode: Spinner
    private lateinit var spCosMode: Spinner
    private lateinit var btnConnectUsb: Button
    private lateinit var tvUsbStatus: TextView
    private lateinit var tvCosIndicator: TextView
    private lateinit var tvPttIndicator: TextView

    private val client = SvxReflectorClient(this)
    private lateinit var audio: AudioEngine
    private lateinit var usbSerial: UsbSerialController

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var transmitting = false
    private var isRemoteTalking = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        audio = AudioEngine(this) { log(it) }
        usbSerial = UsbSerialController(this) { log(it) }
        usbSerial.listener = this

        initViews()
        setupSpinners()
        loadPrefs()
        setupListeners()

        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        log("SvxLink HT Gateway Siap. Pilih Baud, COS, dan Jalur Audio sesuai kebutuhan.")
    }

    private fun initViews() {
        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        etCallsign = findViewById(R.id.etCallsign)
        etPassword = findViewById(R.id.etPassword)
        etTg = findViewById(R.id.etTg)
        btnConnect = findViewById(R.id.btnConnect)
        btnPtt = findViewById(R.id.btnPtt)
        tvStatus = findViewById(R.id.tvStatus)
        tvTalker = findViewById(R.id.tvTalker)
        tvNodes = findViewById(R.id.tvNodes)
        tvLog = findViewById(R.id.tvLog)
        svLog = findViewById(R.id.svLog)
        pbLevel = findViewById(R.id.pbLevel)

        spAudioRoute = findViewById(R.id.spAudioRoute)
        spBaudRate = findViewById(R.id.spBaudRate)
        spPttMode = findViewById(R.id.spPttMode)
        spCosMode = findViewById(R.id.spCosMode)
        btnConnectUsb = findViewById(R.id.btnConnectUsb)
        tvUsbStatus = findViewById(R.id.tvUsbStatus)
        tvCosIndicator = findViewById(R.id.tvCosIndicator)
        tvPttIndicator = findViewById(R.id.tvPttIndicator)
    }

    private fun setupSpinners() {
        // Audio Route
        val audioRoutes = arrayOf("Default / 3.5mm TRRS", "USB Soundcard (OTG Audio)", "Speakerphone")
        val audioAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, audioRoutes)
        spAudioRoute.adapter = audioAdapter

        // Baud Rate
        val baudRates = arrayOf("9600", "115200", "19200", "38400", "57600", "4800", "2400", "1200")
        val baudAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, baudRates)
        spBaudRate.adapter = baudAdapter

        // PTT Out Mode
        val pttModes = arrayOf("RTS Active HIGH", "RTS Active LOW", "DTR Active HIGH", "DTR Active LOW", "Serial Command (PTT:1/0)")
        val pttAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, pttModes)
        spPttMode.adapter = pttAdapter

        // COS In Mode
        val cosModes = arrayOf("Hardware CTS", "Hardware DSR", "Software VOX", "Serial Command (COS:1/0)", "Manual Only")
        val cosAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cosModes)
        spCosMode.adapter = cosAdapter
    }

    private fun setupListeners() {
        btnConnect.setOnClickListener {
            if (client.state == SvxReflectorClient.State.DISCONNECTED) doConnect()
            else { stopTx(); client.disconnect() }
        }

        findViewById<Button>(R.id.btnTg).setOnClickListener {
            val tg = etTg.text.toString().toLongOrNull() ?: return@setOnClickListener
            client.selectTg(tg)
            savePrefs()
        }

        btnPtt.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> startTx()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopTx()
            }
            true
        }

        btnConnectUsb.setOnClickListener {
            if (usbSerial.isConnected) {
                usbSerial.disconnect()
            } else {
                doConnectUsb()
            }
        }

        // Apply config changes from Spinners
        audio.onVoxStateChanged = { voxActive ->
            runOnUiThread {
                if (spCosMode.selectedItemPosition == 2) { // Software VOX
                    handleCosTrigger(voxActive)
                }
            }
        }
    }

    private fun doConnectUsb() {
        val baud = spBaudRate.selectedItem.toString().toIntOrNull() ?: 9600
        val devices = usbSerial.getConnectedDevices()
        if (devices.isEmpty()) {
            log("Tidak ada perangkat USB terdeteksi. Hubungkan modul USB-UART via kabel OTG.")
            return
        }

        val device = devices.first()
        // Configure PTT mode
        usbSerial.pttMode = when (spPttMode.selectedItemPosition) {
            0 -> UsbSerialController.PttMode.RTS_ACTIVE_HIGH
            1 -> UsbSerialController.PttMode.RTS_ACTIVE_LOW
            2 -> UsbSerialController.PttMode.DTR_ACTIVE_HIGH
            3 -> UsbSerialController.PttMode.DTR_ACTIVE_LOW
            else -> UsbSerialController.PttMode.SERIAL_COMMAND
        }

        // Configure COS mode
        usbSerial.cosMode = when (spCosMode.selectedItemPosition) {
            0 -> UsbSerialController.CosMode.CTS_ACTIVE_HIGH
            1 -> UsbSerialController.CosMode.CTS_ACTIVE_LOW
            2 -> UsbSerialController.CosMode.DISABLED_OR_VOX
            3 -> UsbSerialController.CosMode.SERIAL_COMMAND
            else -> UsbSerialController.CosMode.DISABLED_OR_VOX
        }

        usbSerial.connect(device, baud)
    }

    private fun applyAudioRouteConfig() {
        audio.currentRoute = when (spAudioRoute.selectedItemPosition) {
            1 -> AudioEngine.AudioRoute.USB_SOUNDCARD
            2 -> AudioEngine.AudioRoute.SPEAKERPHONE
            else -> AudioEngine.AudioRoute.DEFAULT_OR_TRRS
        }
        audio.voxEnabled = (spCosMode.selectedItemPosition == 2)
    }

    private fun handleCosTrigger(active: Boolean) {
        if (isRemoteTalking) return // Prevent feedback loop while remote SvxLink audio is playing

        runOnUiThread {
            if (active) {
                tvCosIndicator.text = "COS: RX ACTIVE"
                tvCosIndicator.setBackgroundColor(Color.parseColor("#2E7D32"))
                tvCosIndicator.setTextColor(Color.WHITE)
                startTx()
            } else {
                tvCosIndicator.text = "COS: IDLE"
                tvCosIndicator.setBackgroundColor(Color.parseColor("#212121"))
                tvCosIndicator.setTextColor(Color.parseColor("#757575"))
                stopTx()
            }
        }
    }

    override fun onDestroy() {
        stopTx()
        client.disconnect()
        audio.stop()
        usbSerial.release()
        super.onDestroy()
    }

    private fun loadPrefs() {
        val p = getSharedPreferences("svx", MODE_PRIVATE)
        etHost.setText(p.getString("host", "43.173.13.117"))
        etPort.setText(p.getString("port", "5300"))
        etCallsign.setText(p.getString("callsign", ""))
        etPassword.setText(p.getString("password", ""))
        etTg.setText(p.getString("tg", "1025"))
        spBaudRate.setSelection(p.getInt("baud_pos", 0))
        spAudioRoute.setSelection(p.getInt("audio_pos", 0))
        spPttMode.setSelection(p.getInt("ptt_pos", 0))
        spCosMode.setSelection(p.getInt("cos_pos", 0))
    }

    private fun savePrefs() {
        getSharedPreferences("svx", MODE_PRIVATE).edit()
            .putString("host", etHost.text.toString())
            .putString("port", etPort.text.toString())
            .putString("callsign", etCallsign.text.toString())
            .putString("password", etPassword.text.toString())
            .putString("tg", etTg.text.toString())
            .putInt("baud_pos", spBaudRate.selectedItemPosition)
            .putInt("audio_pos", spAudioRoute.selectedItemPosition)
            .putInt("ptt_pos", spPttMode.selectedItemPosition)
            .putInt("cos_pos", spCosMode.selectedItemPosition)
            .apply()
    }

    private fun doConnect() {
        val host = etHost.text.toString().trim()
        val port = etPort.text.toString().toIntOrNull() ?: 5300
        val cs = etCallsign.text.toString().trim().uppercase()
        val pw = etPassword.text.toString().trim()
        val tg = etTg.text.toString().toLongOrNull() ?: 0L
        if (host.isEmpty() || cs.isEmpty() || pw.isEmpty()) {
            log("Host, callsign dan password wajib diisi"); return
        }
        savePrefs()
        applyAudioRouteConfig()
        audio.start()
        client.connect(host, port, cs, pw, tg)
    }

    private fun startTx() {
        if (transmitting || client.state != SvxReflectorClient.State.CONNECTED) return
        if (client.currentTg == 0L) { log("Pilih TG dulu sebelum TX"); return }
        transmitting = true
        btnPtt.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#C62828"))
        btnPtt.text = "TX  TG #${client.currentTg}"
        audio.startTx(
            onFrame = { client.sendAudio(it) },
            onLevel = { lvl -> runOnUiThread { pbLevel.progress = lvl } }
        )
    }

    private fun stopTx() {
        if (!transmitting) return
        transmitting = false
        audio.stopTx()
        client.sendFlush()
        btnPtt.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#37474F"))
        btnPtt.text = "MANUAL PTT (Tahan)"
    }

    // ------------------------------------------------- USB Controller Callbacks

    override fun onDeviceAttached(chipName: String) = runOnUiThread {
        tvUsbStatus.text = "USB: Terdeteksi ($chipName)"
        tvUsbStatus.setTextColor(Color.parseColor("#81D4FA"))
    }

    override fun onDeviceDetached() = runOnUiThread {
        tvUsbStatus.text = "USB: Terputus"
        tvUsbStatus.setTextColor(Color.parseColor("#FFCC80"))
        btnConnectUsb.text = "Hubungkan USB"
    }

    override fun onConnected(chipName: String, baudRate: Int) = runOnUiThread {
        tvUsbStatus.text = "USB: Terhubung ($chipName @ $baudRate)"
        tvUsbStatus.setTextColor(Color.parseColor("#81C784"))
        btnConnectUsb.text = "Putus USB"
    }

    override fun onDisconnected() = runOnUiThread {
        tvUsbStatus.text = "USB: Tidak Terhubung"
        tvUsbStatus.setTextColor(Color.parseColor("#FFCC80"))
        btnConnectUsb.text = "Hubungkan USB"
    }

    override fun onCosStateChanged(active: Boolean) {
        handleCosTrigger(active)
    }

    override fun onError(msg: String) = log("USB Error: $msg")

    // ------------------------------------------------- SvxReflector Client Callbacks

    override fun onLog(msg: String) = log(msg)

    override fun onState(state: SvxReflectorClient.State) = runOnUiThread {
        tvStatus.text = state.name
        tvStatus.setTextColor(
            when (state) {
                SvxReflectorClient.State.CONNECTED -> Color.parseColor("#81C784")
                SvxReflectorClient.State.DISCONNECTED -> Color.parseColor("#FF8A65")
                else -> Color.parseColor("#FFD54F")
            }
        )
        btnConnect.text = if (state == SvxReflectorClient.State.DISCONNECTED) "Connect" else "Disconnect"
        if (state == SvxReflectorClient.State.DISCONNECTED) {
            stopTx()
            tvTalker.text = "—"
            isRemoteTalking = false
            usbSerial.setHtPtt(false)
        }
    }

    override fun onNodes(nodes: List<String>) = runOnUiThread {
        tvNodes.text = "Nodes (${nodes.size}): ${nodes.joinToString(", ")}"
    }

    override fun onTalkerStart(tg: Long, callsign: String) {
        log("▶ $callsign @ TG #$tg")
        if (tg == client.currentTg) runOnUiThread {
            isRemoteTalking = true
            tvTalker.text = "$callsign  •  TG $tg"
            tvTalker.setBackgroundColor(Color.parseColor("#1B5E20"))

            // Trigger PTT to Analog HT so the HT transmits the voice from SvxLink!
            usbSerial.setHtPtt(true)
            tvPttIndicator.text = "HT PTT: TX KEYED"
            tvPttIndicator.setBackgroundColor(Color.parseColor("#B71C1C"))
            tvPttIndicator.setTextColor(Color.WHITE)
        }
    }

    override fun onTalkerStop(tg: Long, callsign: String) {
        log("■ $callsign @ TG #$tg")
        if (tg == client.currentTg) runOnUiThread {
            isRemoteTalking = false
            tvTalker.text = "—"
            tvTalker.setBackgroundColor(Color.parseColor("#1C252E"))

            // Unkey HT PTT
            usbSerial.setHtPtt(false)
            tvPttIndicator.text = "HT PTT: OFF"
            tvPttIndicator.setBackgroundColor(Color.parseColor("#212121"))
            tvPttIndicator.setTextColor(Color.parseColor("#757575"))
        }
    }

    override fun onAudio(opus: ByteArray) = audio.onOpusPacket(opus)

    override fun onFlush() = audio.flush()

    private fun log(msg: String) {
        val line = "${timeFmt.format(Date())} $msg\n"
        runOnUiThread {
            tvLog.append(line)
            if (tvLog.lineCount > 400) tvLog.text = tvLog.text.toString().takeLast(12000)
            svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
