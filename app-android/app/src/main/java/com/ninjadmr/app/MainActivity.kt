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
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
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
import com.ninjadmr.app.ui.NinjaDmrTheme
import com.ninjadmr.app.ui.RadioScreen
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
    private var pendingExport = ""

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

        profileStore = ProfileStore(this)
        credentials = CredentialsStore(this)
        contactsStore = com.ninjadmr.app.contacts.ContactsStore(this)

        requestRuntimePermissions()

        setContent {
            NinjaDmrTheme {
                var profiles by remember { mutableStateOf<List<RadioProfile>>(profileStore.loadAll()) }
                var selectedIndex by remember { mutableStateOf(profileStore.selectedIndex) }
                var config by remember {
                    mutableStateOf(profiles.getOrNull(selectedIndex) ?: RadioProfile())
                }
                var password by remember {
                    mutableStateOf(credentials.get((profiles.getOrNull(selectedIndex) ?: RadioProfile()).name))
                }
                var connected by remember { mutableStateOf(false) }
                var transmitting by remember { mutableStateOf(false) }
                var status by remember { mutableStateOf("") }
                var linkState by remember { mutableStateOf("idle") }
                var counters by remember { mutableStateOf(0 to 0) }
                var log by remember { mutableStateOf<List<AppLog.Entry>>(emptyList()) }
                var masters by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
                var talkgroups by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
                var contacts by remember {
                    mutableStateOf<List<com.ninjadmr.app.contacts.DmrContact>>(contactsStore.loadAll())
                }
                var scanEnabled by remember { mutableStateOf(false) }

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
                                counters = svc.counters()
                                transmitting = svc.ptt
                            }
                            delay(400)
                        }
                    } finally {
                        AppLog.removeListener(listener)
                    }
                }

                RadioScreen(
                    profiles = profiles,
                    selectedIndex = selectedIndex,
                    config = config,
                    password = password,
                    masters = masters,
                    talkgroups = talkgroups,
                    contacts = contacts,
                    scanEnabled = scanEnabled,
                    log = log,
                    linkState = linkState,
                    rxCount = counters.first,
                    txCount = counters.second,
                    connected = connected,
                    transmitting = transmitting,
                    status = status,
                    onConfigChange = { config = it },
                    onPasswordChange = { password = it },
                    onPickMaster = { master ->
                        config = config.copy(
                            protocol = (master["protocol"] ?: config.protocol).toString(),
                            server = (master["host"] ?: config.server).toString(),
                            port = (master["port"] ?: config.port).toString(),
                        )
                    },
                    onPickTalkgroup = { tg ->
                        config = config.copy(talkgroup = (tg["id"] ?: config.talkgroup).toString())
                    },
                    onAddContact = {
                        val contact = com.ninjadmr.app.contacts.DmrContact(
                            id = config.dmrId,
                            callsign = config.callsign,
                            name = "",
                            talkgroup = config.talkgroup,
                            favourite = false,
                        )
                        val list = contacts + contact
                        contacts = list
                        contactsStore.saveAll(list)
                        AppLog.add("Contact added → TG ${contact.talkgroup}")
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
                    onSelectContact = { contact ->
                        config = config.copy(
                            talkgroup = contact.talkgroup.ifBlank { config.talkgroup },
                            callsign = contact.callsign.ifBlank { config.callsign },
                            dmrId = contact.id.ifBlank { config.dmrId },
                        )
                        AppLog.add("Contact selected: ${contact.label()}")
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
                        if (selectedIndex in list.indices) list[selectedIndex] = updated else list.add(updated)
                        profiles = list
                        config = updated
                        profileStore.saveAll(list)
                        credentials.put(updated.name, password)
                        AppLog.add("Profile saved: ${updated.name}")
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
                        status = "running self-test..."
                        lifecycleScope.launch {
                            status = withContext(Dispatchers.IO) { runSelfTest() }
                        }
                    },
                    onRequestBatteryExemption = { requestBatteryExemption() },
                    onEnableVolumePtt = { openAccessibilitySettings() },
                )
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
            .putExtra(DmrForegroundService.EXTRA_PASSWORD, password)
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
        "core ${info["version"]} / codec ${info["codec"]} / ok=${info["ok"]}" +
            (info["error"]?.let { " / error=$it" } ?: "")
    } catch (exc: Exception) {
        "self-test failed: ${exc.message}"
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

    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    companion object {
        private const val SCAN_DWELL_MS = 10_000L
    }
}
