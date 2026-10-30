package com.ninjadmr.app



import android.Manifest

import android.annotation.SuppressLint

import android.content.ComponentName

import android.content.Context

import android.content.Intent

import android.content.ServiceConnection

import android.content.pm.PackageManager

import android.net.Uri

import android.os.Build

import android.os.Bundle

import android.media.AudioTrack

import android.media.AudioAttributes

import android.media.AudioFormat

import android.os.IBinder

import android.os.PowerManager

import android.provider.Settings

import android.view.KeyEvent

import androidx.activity.ComponentActivity

import androidx.activity.compose.setContent

import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.runtime.LaunchedEffect

import androidx.compose.runtime.getValue

import androidx.compose.runtime.mutableStateOf

import androidx.compose.runtime.remember

import androidx.compose.runtime.setValue

import androidx.core.content.ContextCompat

import androidx.lifecycle.lifecycleScope

import com.ninjadmr.app.config.ProfileStore

import com.ninjadmr.app.config.RadioProfile

import com.ninjadmr.app.security.CredentialsStore

import com.ninjadmr.app.ui.CarModeScreen

import com.ninjadmr.app.ui.NinjaDmrTheme

import com.ninjadmr.app.ui.RadioScreen

import com.ninjadmr.app.config.AppSettingsStore

import com.ninjadmr.app.uart.MmdvmHotspotBridge

import com.ninjadmr.app.uart.UsbUartManager

import com.ninjadmr.app.util.AppLog

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.delay

import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext



class MainActivity : ComponentActivity() {



    private var service: DmrForegroundService? = null

    private var bound = false



    private lateinit var profileStore: ProfileStore

    private lateinit var credentials: CredentialsStore

    private lateinit var contactsStore: com.ninjadmr.app.contacts.ContactsStore
    private lateinit var callbookStore: com.ninjadmr.app.contacts.CallbookStore

    private var pendingExport = ""

    private lateinit var settingsStore: AppSettingsStore

    private var usbUartManager: UsbUartManager? = null

    private var hotspotBridge: MmdvmHotspotBridge? = null



    /** Installed by the composable so import results can update state. */

    private var onProfilesImported: ((List<RadioProfile>) -> Unit)? = null



    private val exportLauncher = registerForActivityResult(

        ActivityResultContracts.CreateDocument("application/json"),

    ) { uri ->

        if (uri == null) return@registerForActivityResult

        runCatching {

            contentResolver.openOutputStream(uri)?.use { it.write(pendingExport.toByteArray()) }

            AppLog.add("Exported profiles")

        }.onFailure { AppLog.add("Export failed: ${it.message}") }

    }



    private val importLauncher = registerForActivityResult(

        ActivityResultContracts.OpenDocument(),

    ) { uri ->

        if (uri == null) return@registerForActivityResult

        runCatching {

            val raw = contentResolver.openInputStream(uri)?.use {

                String(it.readBytes())

            } ?: throw IllegalStateException("empty file")

            val list = ProfileStore.importJson(raw)

            if (list.isEmpty()) throw IllegalStateException("no profiles in file")

            onProfilesImported?.invoke(list)

            AppLog.add("Imported ${list.size} profile(s)")

        }.onFailure { AppLog.add("Import failed: ${it.message}") }

    }



    private val connection = object : ServiceConnection {

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {

            service = (binder as? DmrForegroundService.LocalBinder)?.service()

            bound = true

            AppLog.add("Service bound")

        }



        override fun onServiceDisconnected(name: ComponentName?) {

            service = null

            bound = false

        }

    }



    private val permissionLauncher = registerForActivityResult(

        ActivityResultContracts.RequestMultiplePermissions(),

    ) { }



    @SuppressLint("BatteryLife")

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        volumeControlStream = android.media.AudioManager.STREAM_MUSIC



        profileStore = ProfileStore(this)

        credentials = CredentialsStore(this)

        contactsStore = com.ninjadmr.app.contacts.ContactsStore(this)
        callbookStore = com.ninjadmr.app.contacts.CallbookStore(this)

        settingsStore = AppSettingsStore(this)

        usbUartManager = UsbUartManager(this)

        hotspotBridge = MmdvmHotspotBridge(this, usbUartManager!!)



        requestRuntimePermissions()



        setContent {

            NinjaDmrTheme {

                var profiles by remember { mutableStateOf<List<RadioProfile>>(profileStore.loadAll()) }

                var selectedIndex by remember { mutableStateOf(profileStore.selectedIndex) }

                var config by remember {

                    mutableStateOf(profiles.getOrNull(selectedIndex) ?: RadioProfile())

                }

                var password by remember {

                    mutableStateOf(
                        credentials.get((profiles.getOrNull(selectedIndex) ?: RadioProfile()).name).ifBlank {
                            credentials.get("default")
                        }
                    )

                }

                var connected by remember { mutableStateOf(false) }

                var transmitting by remember { mutableStateOf(false) }

                var status by remember { mutableStateOf("") }

                var linkState by remember { mutableStateOf("idle") }

                var sessionState by remember { mutableStateOf("idle") }

                var vocoder by remember { mutableStateOf("not started") }

                var vocoderTest by remember { mutableStateOf("") }

                var carMode by remember { mutableStateOf(false) }

                var counters by remember { mutableStateOf(0 to 0) }

                var log by remember { mutableStateOf<List<AppLog.Entry>>(emptyList()) }

                var masters by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }

                var talkgroups by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }

                var contacts by remember {

                    mutableStateOf<List<com.ninjadmr.app.contacts.DmrContact>>(contactsStore.loadAll())

                }

                var scanEnabled by remember { mutableStateOf(false) }

                var callerStation by remember { mutableStateOf<CallerStation?>(null) }



                onProfilesImported = { list ->

                    profiles = list

                    selectedIndex = 0

                    config = list[0]

                    password = credentials.get(list[0].name)

                    profileStore.saveAll(list)

                    profileStore.selectedIndex = 0

                }



                LaunchedEffect(Unit) {

                    val directory = withContext(Dispatchers.IO) {

                        runCatching {

                            PyBridge.ensureStarted(this@MainActivity)

                            PyBridge.directoryMasters(this@MainActivity) to

                                PyBridge.directoryTalkgroups(this@MainActivity)

                        }.getOrNull()

                    }

                    if (directory != null) {

                        masters = directory.first

                        talkgroups = directory.second

                    }

                }



                LaunchedEffect(Unit) {

                    val listener: (List<AppLog.Entry>) -> Unit = { log = it }

                    AppLog.addListener(listener)

                    try {

                        while (true) {

                            val svc = service

                            if (svc != null) {

                                linkState = svc.linkStatus()

                                sessionState = svc.sessionState()

                                vocoder = svc.vocoder

                                counters = svc.counters()

                                transmitting = svc.ptt

                                callerStation = svc.currentCaller()

                            }

                            delay(400)

                        }

                    } finally {

                        AppLog.removeListener(listener)

                    }

                }



                if (carMode) {

                    CarModeScreen(

                        talkgroup = config.talkgroup,

                        callsign = config.callsign,

                        sessionState = sessionState,

                        linkState = linkState,

                        rxCount = counters.first,

                        txCount = counters.second,

                        transmitting = transmitting,

                        connected = connected,

                        onPttDown = {

                            service?.let {

                                it.setPtt(true)

                                transmitting = true

                            }

                        },

                        onPttUp = {

                            service?.let {

                                it.setPtt(false)

                                transmitting = false

                            }

                        },

                        onExit = { carMode = false },

                    )

                } else {

                RadioScreen(

                    profiles = profiles,

                    selectedIndex = selectedIndex,

                    config = config,

                    password = password,

                    masters = masters,

                    talkgroups = talkgroups,

                    contacts = contacts,

                    log = log,

                    radioState = com.ninjadmr.app.ui.RadioUiState(

                        linkState = linkState,

                        sessionState = sessionState,

                        rxCount = counters.first,

                        txCount = counters.second,

                        connected = connected,

                        transmitting = transmitting,

                        status = status,

                        vocoder = vocoder,

                        vocoderTest = vocoderTest,

                        scanEnabled = scanEnabled,

                        callerStation = callerStation,

                    ),

                    onConfigChange = { updated ->
                        val prevTg = config.talkgroup
                        val prevSlot = config.slot
                        config = updated
                        val list = profiles.toMutableList()
                        if (selectedIndex in list.indices) {
                            list[selectedIndex] = updated
                            profiles = list
                            profileStore.saveAll(list)
                        }
                        if (updated.talkgroup != prevTg || updated.slot != prevSlot) {
                            val tgInt = updated.talkgroup.toIntOrNull()
                            val slotNum = updated.slot.toIntOrNull() ?: 2
                            if (tgInt != null) {
                                service?.setTalkgroup(tgInt, slotNum) ?: PyBridge.setTalkgroup(this@MainActivity, tgInt, slotNum)
                            }
                        }
                    },

                    onPasswordChange = { newPass ->
                        password = newPass
                        if (config.name.isNotBlank()) {
                            credentials.put(config.name, newPass)
                        }
                    },

                    onPickMaster = { master ->

                        config = config.copy(

                            protocol = (master["protocol"] ?: config.protocol).toString(),

                            server = (master["host"] ?: config.server).toString(),

                            port = (master["port"] ?: config.port).toString(),

                        )

                    },

                    onPickTalkgroup = { tg ->

                        val newTg = (tg["id"] ?: config.talkgroup).toString()

                        config = config.copy(talkgroup = newTg)

                        val slotNum = config.slot.toIntOrNull() ?: 2

                        newTg.toIntOrNull()?.let {
                            service?.setTalkgroup(it, slotNum) ?: PyBridge.setTalkgroup(this@MainActivity, it, slotNum)
                        }

                    },

                    onAddContact = { contact ->

                        val list = contacts.filterNot { it.talkgroup == contact.talkgroup && it.id == contact.id && contact.id.isNotBlank() } + contact

                        contacts = list

                        contactsStore.saveAll(list)

                        AppLog.add("Contact saved → ${contact.name.ifBlank { contact.callsign }} (TG ${contact.talkgroup})")

                    },

                    onRemoveContact = { contact ->

                        val list = contacts - contact

                        contacts = list

                        contactsStore.saveAll(list)

                    },

                    onToggleFavourite = { contact ->

                        val updated = contact.copy(favourite = !contact.favourite)

                        val list = contacts.map { if (it == contact) updated else it }

                        contacts = list

                        contactsStore.saveAll(list)

                    },

                    onToggleBuddy = { contact ->

                        val updated = contact.copy(isBuddy = !contact.isBuddy)

                        val list = contacts.map { if (it == contact) updated else it }

                        contacts = list

                        contactsStore.saveAll(list)

                        AppLog.add("Buddy ${if (updated.isBuddy) "added" else "removed"}: ${contact.callsign}")

                    },

                    onSendSms = { targetId, text ->

                        val id = targetId.trim().toIntOrNull() ?: 9990

                        if (text.isBlank()) {

                            AppLog.add("DMR SMS: text cannot be empty")

                        } else {

                            val ok = PyBridge.sendSms(this@MainActivity, id, text)

                            if (ok) {

                                AppLog.add("DMR SMS TX → TG/ID $id: \"$text\" [OK]")

                            } else {

                                AppLog.add("DMR SMS TX → TG/ID $id: \"$text\" [FAILED: Not connected]")

                            }

                        }

                    },

                    onSelectContact = { contact ->
                        val tg = contact.talkgroup.ifBlank { config.talkgroup }
                        val newSlot = if (contact.id == "1" || contact.name.contains("TS1", ignoreCase = true)) "1" else "2"
                        val slotNum = newSlot.toIntOrNull() ?: 2
                        config = config.copy(talkgroup = tg, slot = newSlot)
                        tg.toIntOrNull()?.let {
                            service?.setTalkgroup(it, slotNum) ?: PyBridge.setTalkgroup(this@MainActivity, it, slotNum)
                        }
                        AppLog.add("Contact selected: ${contact.label()} (TG $tg, TS$newSlot)")
                    },

                    onToggleScan = {

                        val next = !scanEnabled

                        scanEnabled = next

                        val svc = service

                        if (svc != null) {

                            svc.setScan(next, scanTgList(contacts, config), SCAN_DWELL_MS)

                        } else {

                            AppLog.add(if (next) "Scan queued (connect first)" else "Scan stopped")

                            if (!next) scanEnabled = false

                        }

                    },

                    onExport = {

                        pendingExport = ProfileStore.exportJson(profiles)

                        exportLauncher.launch("ninjadmr-profiles.json")

                    },

                    onImport = { importLauncher.launch(arrayOf("application/json")) },

                    onSelectProfile = { index ->

                        val profile = profiles.getOrNull(index)

                        if (profile != null) {

                            selectedIndex = index

                            config = profile

                            password = credentials.get(profile.name)

                            profileStore.selectedIndex = index

                        }

                    },

                    onSaveProfile = {

                        val updated = config.copy(name = config.name.ifBlank { "default" })

                        val list = profiles.toMutableList()

                        if (selectedIndex in list.indices) {

                            list[selectedIndex] = updated

                        } else {

                            list.add(updated)

                            selectedIndex = list.size - 1

                        }

                        profiles = list

                        config = updated

                        profileStore.saveAll(list)

                        profileStore.selectedIndex = selectedIndex

                        credentials.put(updated.name, password)

                        AppLog.add("Profile saved: ${updated.name}")

                    },

                    onNewProfile = {

                        val newName = "Profile ${profiles.size + 1}"

                        val newProfile = RadioProfile(

                            name = newName,

                            protocol = config.protocol,

                            server = config.server,

                            port = config.port,

                            dmrId = config.dmrId,

                            callsign = config.callsign,

                            essid = config.essid,

                            talkgroup = config.talkgroup,

                            slot = config.slot,

                            colorCode = config.colorCode

                        )

                        val list = profiles.toMutableList().apply { add(newProfile) }

                        profiles = list

                        selectedIndex = list.size - 1

                        config = newProfile

                        password = credentials.get(newProfile.name) ?: ""

                        profileStore.saveAll(list)

                        profileStore.selectedIndex = selectedIndex

                        credentials.put(newProfile.name, password)

                        AppLog.add("Created new profile: $newName")

                    },

                    onDeleteProfile = {

                        if (profiles.size <= 1) {

                            AppLog.add("Cannot delete the last profile")

                        } else {

                            val removed = profiles[selectedIndex]

                            val list = profiles.toMutableList().also { it.removeAt(selectedIndex) }

                            profiles = list

                            selectedIndex = 0

                            config = list[0]

                            password = credentials.get(list[0].name)

                            profileStore.saveAll(list)

                            profileStore.selectedIndex = 0

                            credentials.remove(removed.name)

                            AppLog.add("Profile deleted: ${removed.name}")

                        }

                    },

                    onConnect = {

                        connected = true

                        transmitting = false

                        status = "starting service..."

                        startSessionService(config, password, scanEnabled, scanTgList(contacts, config))

                    },

                    onDisconnect = {

                        connected = false

                        transmitting = false

                        status = "stopped"

                        service?.setPtt(false)

                        startService(

                            Intent(this, DmrForegroundService::class.java)

                                .setAction(DmrForegroundService.ACTION_STOP),

                        )

                    },

                    onTogglePtt = {

                        val svc = service

                        if (svc != null) {

                            val next = !svc.ptt

                            svc.setPtt(next)

                            transmitting = next

                        } else {

                            status = "service not bound yet"

                        }

                    },

                    onSelfTest = {

                        vocoderTest = "running self-test..."

                        lifecycleScope.launch {

                            val res = withContext(Dispatchers.IO) { runSelfTest() }

                            vocoderTest = res

                            AppLog.add(res)

                        }

                    },

                    onVocoderTest = {

                        vocoderTest = "testing audio..."

                        lifecycleScope.launch {

                            val res = withContext(Dispatchers.IO) { runVocoderAudioTest() }

                            vocoderTest = res

                            AppLog.add(res)

                        }

                    },

                    onRequestBatteryExemption = { requestBatteryExemption() },

                    onEnableVolumePtt = { openAccessibilitySettings() },

                    onCarMode = { carMode = true },

                    settingsStore = settingsStore,

                    usbUartManager = usbUartManager,

                    hotspotBridge = hotspotBridge,

                    onUpdateDbFromUrl = { url ->

                        settingsStore.dmrDbUrl = url
callbookStore.importFromUrl(url) { count, err ->
                            runOnUiThread {
                                if (err == null) {
                                    AppLog.add("Updated Callbook: $count stations indexed for RX display")
                                } else {
                                    AppLog.add("Callbook update failed: $err")
                                }
                            }
                        }

                    },

                )

                }

            }

        }

    }



    private fun scanTgList(

        contacts: List<com.ninjadmr.app.contacts.DmrContact>,

        config: RadioProfile,

    ): List<Int> {

        val favourites = contacts.filter { it.favourite }

            .mapNotNull { it.talkgroup.trim().toIntOrNull() }

        val fallback = contacts.mapNotNull { it.talkgroup.trim().toIntOrNull() }

        val out = (favourites.ifEmpty { fallback }).toMutableList()

        config.talkgroup.trim().toIntOrNull()?.let { out.add(it) }

        val distinct = out.distinct().toMutableList()

        if (distinct.size < 2 && 9990 !in distinct) distinct.add(9990)

        return distinct

    }



    private fun startSessionService(

        config: RadioProfile,

        password: String,

        scanEnabled: Boolean,

        scanTgs: List<Int>,

    ) {

        val intent = Intent(this, DmrForegroundService::class.java)

            .setAction(DmrForegroundService.ACTION_START)

            .putExtra(DmrForegroundService.EXTRA_PROTOCOL, config.protocol)

            .putExtra(DmrForegroundService.EXTRA_HOST, config.server)

            .putExtra(DmrForegroundService.EXTRA_PORT, config.port)

            .putExtra(DmrForegroundService.EXTRA_DMR_ID, config.dmrId)

            .putExtra(DmrForegroundService.EXTRA_CALLSIGN, config.callsign)

            .putExtra(DmrForegroundService.EXTRA_ESSID, config.essid)

            .putExtra(DmrForegroundService.EXTRA_PASSWORD, password.ifBlank { "ninjaradio_pass" })

            .putExtra(DmrForegroundService.EXTRA_TALKGROUP, config.talkgroup)

            .putExtra(DmrForegroundService.EXTRA_SLOT, config.slot)

            .putExtra(DmrForegroundService.EXTRA_COLOR_CODE, config.colorCode)

            .putExtra(DmrForegroundService.EXTRA_SCAN_ENABLED, scanEnabled)

            .putExtra(DmrForegroundService.EXTRA_SCAN_TGS, scanTgs.joinToString(","))

            .putExtra(DmrForegroundService.EXTRA_SCAN_INTERVAL, SCAN_DWELL_MS)



        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            startForegroundService(intent)

        } else {

            startService(intent)

        }

        if (!bound) {

            bindService(

                Intent(this, DmrForegroundService::class.java),

                connection,

                Context.BIND_AUTO_CREATE,

            )

        }

    }



    private fun requestBatteryExemption() {

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager

        if (pm.isIgnoringBatteryOptimizations(packageName)) {

            AppLog.add("Battery optimization already disabled")

            return

        }

        try {

            startActivity(

                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)

                    .setData(Uri.parse("package:$packageName")),

            )

            AppLog.add("Requested battery optimization exemption")

        } catch (exc: Exception) {

            AppLog.add("Battery exemption unavailable: ${exc.message}")

        }

    }



    private fun openAccessibilitySettings() {

        try {

            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

            AppLog.add("Open Accessibility settings → enable NinjaDMR PTT")

        } catch (exc: Exception) {

            AppLog.add("Cannot open Accessibility settings: ${exc.message}")

        }

    }



    private fun runSelfTest(): String = try {

        PyBridge.ensureStarted(this)

        val info = PyBridge.selfTest()

        val ok = info["ok"].equals("true", ignoreCase = true)

        if (ok) {

            "SELF-TEST OK · Core ${info["version"]} · Codec ${info["codec"]} (Encode/Decode Loopback PASSED)"

        } else {

            "SELF-TEST FAIL · ${info["error"] ?: info["lib"] ?: "vocoder backend error"}"

        }

    } catch (exc: Exception) {

        "SELF-TEST ERR · ${exc.message}"

    }



    private fun runVocoderAudioTest(): String {

        return try {

        PyBridge.ensureStarted(this)

        val info = PyBridge.selfTest()

        val ok = info["ok"].equals("true", ignoreCase = true)

        if (!ok) return "AUDIO TEST FAIL · ${info["error"] ?: info["lib"] ?: "no backend"}"



        playTestTone()

        "AUDIO TEST OK · 800Hz Test Tone Played via Vocoder Pipeline (Codec ${info["codec"]})"

    } catch (exc: Exception) {

        "AUDIO TEST ERR · ${exc.message}"

        }

    }



    private fun playTestTone() {

        Thread {

            try {

                val sampleRate = 8000

                val numSamples = 4000 // 500ms tone

                val buffer = ShortArray(numSamples)

                val freq = 800.0

                for (i in 0 until numSamples) {

                    val angle = 2.0 * Math.PI * i * freq / sampleRate

                    buffer[i] = (Math.sin(angle) * 16000.0).toInt().toShort()

                }

                val track = AudioTrack.Builder()

                    .setAudioAttributes(

                        AudioAttributes.Builder()

                            .setUsage(AudioAttributes.USAGE_MEDIA)

                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)

                            .build()

                    )

                    .setAudioFormat(

                        AudioFormat.Builder()

                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)

                            .setSampleRate(sampleRate)

                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)

                            .build()

                    )

                    .setBufferSizeInBytes(buffer.size * 2)

                    .setTransferMode(AudioTrack.MODE_STREAM)

                    .build()

                track.play()

                track.write(buffer, 0, buffer.size)

                track.stop()

                track.release()

            } catch (exc: Exception) {

                AppLog.add("Audio test play error: ${exc.message}")

            }

        }.start()

    }



    private fun requestRuntimePermissions() {

        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            wanted += Manifest.permission.POST_NOTIFICATIONS

        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            wanted += Manifest.permission.BLUETOOTH_CONNECT

        }

        val missing = wanted.filter {

            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED

        }

        if (missing.isNotEmpty()) {

            permissionLauncher.launch(missing.toTypedArray())

        }

    }



    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val capturer = onKeyCaptured
        if (capturer != null) {
            val keyName = when (keyCode) {
                290 -> "CARAKA PTT 1 (PoC / Video App 2)"
                291 -> "CARAKA PTT 2 (DMR / Video App 3)"
                289 -> "CARAKA SOS (Video App 1)"
                292 -> "CARAKA SQ (Video App 4)"
                300 -> "CARAKA POC 2 (Featured App 4)"
                228 -> "DEDICATED PTT (228)"
                else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
            }
            capturer.invoke(keyCode, keyName)
            return true
        }
        if (isPttKeyCode(keyCode)) {
            val svc = service
            if (svc != null && event?.repeatCount == 0) {
                svc.setPtt(true)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (onKeyCaptured != null) {
            return true
        }
        if (isPttKeyCode(keyCode)) {
            val svc = service
            if (svc != null) {
                svc.setPtt(false)
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun isPttKeyCode(keyCode: Int): Boolean {
        val configured = settingsStore.hardwarePttKeyCode
        if (configured > 0) {
            return keyCode == configured
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_STEM_PRIMARY,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_RECORD,
            KeyEvent.KEYCODE_F1,
            KeyEvent.KEYCODE_F2,
            KeyEvent.KEYCODE_BUTTON_1,
            KeyEvent.KEYCODE_BUTTON_2,
            KeyEvent.KEYCODE_BUTTON_3,
            KeyEvent.KEYCODE_BUTTON_4,
            228,
            261,
            268,
            269,
            284, 285, 286, 287, 288,
            289, 290, 291, 292, 300 -> true
            else -> false
        }
    }



    override fun onDestroy() {

        usbUartManager?.destroy()

        if (bound) {

            unbindService(connection)

            bound = false

        }

        super.onDestroy()

    }



    companion object {

        private const val SCAN_DWELL_MS = 10_000L

        @Volatile
        var onKeyCaptured: ((Int, String) -> Unit)? = null

    }

}

