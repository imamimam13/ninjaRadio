package com.ninjadmr.app.ui

import androidx.compose.foundation.Image
import com.ninjadmr.app.AudioVisualizer
import kotlinx.coroutines.delay
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import com.ninjadmr.app.R

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninjadmr.app.config.AppSettingsStore
import com.ninjadmr.app.config.RadioProfile
import com.ninjadmr.app.contacts.DmrContact
import com.ninjadmr.app.uart.MmdvmHotspotBridge
import com.ninjadmr.app.uart.UsbUartManager
import com.ninjadmr.app.util.AppLog

data class RadioUiState(
    val linkState: String = "",
    val sessionState: String = "",
    val rxCount: Int = 0,
    val txCount: Int = 0,
    val connected: Boolean = false,
    val transmitting: Boolean = false,
    val status: String = "",
    val vocoder: String = "",
    val vocoderTest: String = "",
    val scanEnabled: Boolean = false,
    val callerStation: com.ninjadmr.app.CallerStation? = null,
)

@Composable
fun RadioScreen(
    profiles: List<RadioProfile>,
    selectedIndex: Int,
    config: RadioProfile,
    password: String,
    masters: List<Map<String, Any>>,
    talkgroups: List<Map<String, Any>>,
    contacts: List<DmrContact>,
    radioState: RadioUiState,
    log: List<AppLog.Entry>,
    onConfigChange: (RadioProfile) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPickMaster: (Map<String, Any>) -> Unit,
    onPickTalkgroup: (Map<String, Any>) -> Unit,
    onAddContact: (DmrContact) -> Unit,
    onRemoveContact: (DmrContact) -> Unit,
    onResetChannels: () -> Unit = {},
    onToggleFavourite: (DmrContact) -> Unit,
    onToggleBuddy: (DmrContact) -> Unit = {},
    onSendSms: (String, String) -> Unit = { _, _ -> },
    onSelectContact: (DmrContact) -> Unit,
    onToggleScan: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onSelectProfile: (Int) -> Unit,
    onSaveProfile: () -> Unit,
    onNewProfile: () -> Unit,
    onDeleteProfile: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSelfTest: () -> Unit,
    onVocoderTest: () -> Unit,
    onTogglePtt: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onEnableVolumePtt: () -> Unit,
    onCarMode: () -> Unit,
    onUpdateDbFromUrl: (String) -> Unit,
    settingsStore: AppSettingsStore? = null,
    usbUartManager: UsbUartManager? = null,
    hotspotBridge: MmdvmHotspotBridge? = null,
) {
    val linkState = radioState.linkState
    val sessionState = radioState.sessionState
    val rxCount = radioState.rxCount
    val txCount = radioState.txCount
    val connected = radioState.connected
    val transmitting = radioState.transmitting
    val status = radioState.status
    val vocoder = radioState.vocoder
    val vocoderTest = radioState.vocoderTest
    val scanEnabled = radioState.scanEnabled
    val callerStation = radioState.callerStation

    // Current primary navigation tab:
    // 0: RADIO, 1: PROFILES, 2: CHANNELS, 3: HOTSPOT (MMDVM), 4: SETTINGS
    var currentTab by remember { mutableStateOf(0) }
    var channelFilter by remember { mutableStateOf("ALL") } // ALL, FAVORITES, RECENTS, BUDDY WATCH, SCAN LIST
        var dbUrlInput by remember { mutableStateOf(settingsStore?.dmrDbUrl ?: "https://43.173.13.117.sslip.io/callbook/bridi.user.json") }

    // Settings category expansion
    var selectedSettingsSection by remember { mutableStateOf("RADIO") }

    // POC Screen options state
    var compactMode by remember { mutableStateOf(settingsStore?.pocCompactMode ?: true) }
    var showPtt by remember { mutableStateOf(settingsStore?.showOnScreenPtt ?: true) }
    var compactPttBar by remember { mutableStateOf(settingsStore?.compactPttBar ?: true) }

    // SMS message simulator state
    var smsTargetId by remember { mutableStateOf("1025") }
    var smsMessageText by remember { mutableStateOf("73 de NinjaRadio DMR") }

    // Hotspot UART state
    var selectedBaudRate by remember { mutableStateOf(115200) }
    var hotspotMode by remember { mutableStateOf(hotspotBridge?.mode ?: MmdvmHotspotBridge.HotspotMode.DMR) }
    var hotspotFreq by remember { mutableStateOf(hotspotBridge?.rfFrequency ?: "438.50000") }
    var showWiringGuide by remember { mutableStateOf(false) }
    var showVfoEditDialog by remember { mutableStateOf(false) }
    var showQuickTgDialog by remember { mutableStateOf(false) }
    var showHardwarePttDialog by remember { mutableStateOf(false) }
    var showContactDialog by remember { mutableStateOf(false) }
    var contactToEdit by remember { mutableStateOf<DmrContact?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Holo.bgDeep)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        HoloHudBackground(Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (compactMode) 4.dp else 8.dp, vertical = if (compactMode) 2.dp else 4.dp),
            verticalArrangement = Arrangement.spacedBy(if (compactMode) 4.dp else 6.dp),
        ) {
            // Adaptive Top Bar (Never wraps or collides on 240px wide screens)
            TopStatusBar(
                callsign = config.callsign.ifBlank { "NOCALL" },
                talkgroup = config.talkgroup,
                slot = config.slot,
                connected = connected,
                transmitting = transmitting,
                compact = compactMode,
                rxStation = if (status.startsWith("RX:")) status.removePrefix("RX:").trim() else "",
            )

            // Horizontally Scrollable Tabs Row (Zero overlap guarantee on 1-3" screens!)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(
                    0 to "RADIO",
                    1 to "PROFILES",
                    2 to "CHANNELS",
                    3 to "HOTSPOT",
                    4 to "SETTINGS",
                ).forEach { (idx, title) ->
                    HoloChip(
                        label = title,
                        selected = currentTab == idx,
                        onClick = { currentTab = idx },
                    )
                }
                Spacer(Modifier.width(16.dp))
            }

            // Tab Content Body
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (currentTab) {
                    0 -> {
                        // TAB 0: RADIO (VFO Main/Sub, Spectrum, Live Call, Quick TG, PTT, Activity Log)
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(if (compactMode) 6.dp else 8.dp),
                        ) {
                            // VFO-A MAIN Card (Dynamic & Editable by tapping)
                            VfoMainCard(
                                talkgroup = config.talkgroup,
                                slot = config.slot,
                                colorCode = config.colorCode,
                                frequency = config.frequency.ifBlank { "438.50000" },
                                channelName = config.channelName.ifBlank { "LOCAL TACTICAL" },
                                zone = config.zone.ifBlank { "NUSANTARA LINK A" },
                                compact = compactMode,
                                activeCaller = callerStation,
                                onClick = { showVfoEditDialog = true },
                            )

                            // S-Meter & Audio FFT Spectrum
                            if (settingsStore?.showSpectrum != false) {
                                SMeterSpectrumPanel(
                                    transmitting = transmitting,
                                    connected = connected,
                                    compact = compactMode,
                                )
                            }

                            // Dedicated Live RX Caller ID / Station Slot
                            ActiveCallerCard(
                                caller = callerStation,
                                linkState = linkState,
                                status = status,
                                compact = compactMode,
                            )

                            // Quick Talkgroup Selector (Dynamic from profile quickTgs & contacts)
                            HoloPanel {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    HoloSectionTitle("Quick Talkgroups")
                                    HoloIconButton(
                                        icon = Icons.Default.Edit,
                                        contentDescription = "Edit Quick TGs",
                                        accent = Holo.cyan,
                                        onClick = { showQuickTgDialog = true },
                                        size = 28.dp,
                                        iconSize = 15.dp,
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                val quickTgList = remember(config.quickTgs) {
                                    config.quickTgs.split(",")
                                        .map { it.trim() }
                                        .filter { it.isNotEmpty() }
                                        .ifEmpty { listOf("9990", "1", "515", "1025") }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    quickTgList.forEach { tg ->
                                        val contact = contacts.find { it.talkgroup == tg }
                                        val label = contact?.name ?: when (tg) {
                                            "9990" -> "Echo"
                                            "1" -> "WW"
                                            "515" -> "Indo"
                                            "1025" -> "Local"
                                            "91" -> "WW-BM"
                                            else -> "TG"
                                        }
                                        HoloChip(
                                            label = "$label($tg)",
                                            selected = config.talkgroup == tg,
                                            onClick = {
                                                val updated = config.copy(talkgroup = tg)
                                                onConfigChange(updated)
                                                onSaveProfile()
                                                onPickTalkgroup(mapOf("id" to tg))
                                            },
                                        )
                                    }
                                    HoloChip(
                                        label = "+ TG",
                                        selected = false,
                                        onClick = { showQuickTgDialog = true },
                                    )
                                }
                            }

                            // Connect / Disconnect Buttons
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                HoloAction(
                                    label = "CONNECT",
                                    accent = Holo.green,
                                    onClick = onConnect,
                                    enabled = !connected,
                                    modifier = Modifier.weight(1f),
                                )
                                HoloAction(
                                    label = "DISCONNECT",
                                    accent = Holo.red,
                                    onClick = onDisconnect,
                                    enabled = connected,
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            // RX / TX Packet Gauges
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                            ) {
                                HoloGauge(label = "RX PACKETS", value = rxCount, color = Holo.cyan)
                                HoloGauge(label = "TX PACKETS", value = txCount, color = Holo.magenta)
                            }

                            // PTT Area: Compact Bar or Circular Button or Hidden for Hardware PTT
                            if (showPtt) {
                                if (compactPttBar || compactMode) {
                                    CompactPttBar(
                                        transmitting = transmitting,
                                        enabled = connected,
                                        onClick = onTogglePtt,
                                    )
                                } else {
                                    CircularPttButton(
                                        transmitting = transmitting,
                                        enabled = connected,
                                        onClick = onTogglePtt,
                                    )
                                }
                            } else {
                                Text(
                                    text = "[Hardware POC PTT Active - On-screen button hidden]",
                                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Holo.textDim),
                                )
                            }

                            // Activity Log Table (Horizontally scrollable for small screens)
                            if (settingsStore?.showActivityLog != false) {
                                ActivityLogTable(log = log, compact = compactMode)
                            }
                        }
                    }

                    1 -> {
                        // TAB 1: PROFILES (Codeplug manager, servers, DMR ID, callsign, talkgroups)
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            HoloPanel {
                                ProfilePicker(
                                    profiles = profiles,
                                    selectedIndex = selectedIndex,
                                    config = config,
                                    onConfigChange = onConfigChange,
                                    onSelectProfile = onSelectProfile,
                                    onSaveProfile = onSaveProfile,
                                    onNewProfile = onNewProfile,
                                    onDeleteProfile = onDeleteProfile,
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    HoloChip(
                                        label = "HOMEBREW",
                                        selected = config.protocol == "homebrew",
                                        onClick = { val p = config.port.ifBlank { "62301" }; onConfigChange(config.copy(protocol = "homebrew", port = p)) },
                                        modifier = Modifier.weight(1f),
                                    )
                                    HoloChip(
                                        label = "ODTP",
                                        selected = config.protocol == "odtp",
                                        onClick = { val p = config.port.ifBlank { "54005" }; onConfigChange(config.copy(protocol = "odtp", port = p)) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }

                            HoloPanel {
                                HoloSectionTitle("Station & DMR Parameters")
                                Spacer(Modifier.height(6.dp))
                                HoloField(
                                    value = config.server,
                                    onValueChange = { onConfigChange(config.copy(server = it)) },
                                    label = "Master Server Host",
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    HoloField(
                                        value = config.port,
                                        onValueChange = { onConfigChange(config.copy(port = it)) },
                                        label = "Port",
                                        keyboardType = KeyboardType.Number,
                                        modifier = Modifier.weight(1f),
                                    )
                                    HoloField(
                                        value = config.callsign,
                                        onValueChange = { onConfigChange(config.copy(callsign = it.uppercase())) },
                                        label = "Callsign",
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    HoloField(
                                        value = config.dmrId,
                                        onValueChange = { onConfigChange(config.copy(dmrId = it)) },
                                        label = "DMR ID",
                                        keyboardType = KeyboardType.Number,
                                        modifier = Modifier.weight(1.5f),
                                    )
                                    HoloField(
                                        value = config.essid,
                                        onValueChange = { onConfigChange(config.copy(essid = it)) },
                                        label = "ESSID (0-99)",
                                        keyboardType = KeyboardType.Number,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    HoloField(
                                        value = config.talkgroup,
                                        onValueChange = { onConfigChange(config.copy(talkgroup = it)) },
                                        label = "Talkgroup (TG)",
                                        keyboardType = KeyboardType.Number,
                                        modifier = Modifier.weight(1f),
                                    )
                                    HoloField(
                                        value = config.slot,
                                        onValueChange = { onConfigChange(config.copy(slot = it)) },
                                        label = "Slot (1 or 2)",
                                        keyboardType = KeyboardType.Number,
                                        modifier = Modifier.weight(1f),
                                    )
                                    HoloField(
                                        value = config.colorCode,
                                        onValueChange = { onConfigChange(config.copy(colorCode = it)) },
                                        label = "Color Code",
                                        keyboardType = KeyboardType.Number,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    HoloField(
                                        value = config.channelName,
                                        onValueChange = { onConfigChange(config.copy(channelName = it)) },
                                        label = "Channel Name",
                                        modifier = Modifier.weight(1f),
                                    )
                                    HoloField(
                                        value = config.zone,
                                        onValueChange = { onConfigChange(config.copy(zone = it)) },
                                        label = "Zone",
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    HoloField(
                                        value = config.frequency,
                                        onValueChange = { onConfigChange(config.copy(frequency = it)) },
                                        label = "Frequency (MHz)",
                                        modifier = Modifier.weight(1f),
                                    )
                                    HoloField(
                                        value = config.quickTgs,
                                        onValueChange = { onConfigChange(config.copy(quickTgs = it)) },
                                        label = "Quick TGs (csv)",
                                        modifier = Modifier.weight(1.5f),
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                HoloField(
                                    value = password,
                                    onValueChange = onPasswordChange,
                                    label = "Hotspot / Master Password",
                                    password = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                HoloAction(label = "EXPORT PROFILES (JSON)", accent = Holo.cyan, onClick = onExport, modifier = Modifier.weight(1f))
                                HoloAction(label = "IMPORT PROFILES (JSON)", accent = Holo.cyan, onClick = onImport, modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    2 -> {
                        // TAB 2: SAVED CHANNELS & TALKGROUPS
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val validChannels = contacts.filter { it.talkgroup.isNotBlank() }
                            val favCount = validChannels.count { it.favourite }
                            val scanCount = validChannels.count { it.favourite }

                            val filterTabs = listOf(
                                "ALL" to "ALL (${validChannels.size})",
                                "FAVORITES" to "FAV ($favCount)",
                                "SCAN LIST" to "SCAN ($scanCount)",
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                filterTabs.forEach { (secKey, secLabel) ->
                                    HoloChip(
                                        label = secLabel,
                                        selected = channelFilter == secKey,
                                        onClick = { channelFilter = secKey },
                                    )
                                }
                            }

                            HoloPanel {
                                HoloSectionTitle("Saved Channels & Talkgroups (${validChannels.size}) · $channelFilter")
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    HoloAction(
                                        label = "+ CHANNEL",
                                        accent = Holo.cyan,
                                        onClick = {
                                            contactToEdit = DmrContact(talkgroup = config.talkgroup, name = "")
                                            showContactDialog = true
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                    HoloAction(
                                        label = if (scanEnabled) "STOP SCAN" else "START SCAN",
                                        accent = if (scanEnabled) Holo.red else Holo.green,
                                        onClick = onToggleScan,
                                        modifier = Modifier.weight(1f),
                                    )
                                    HoloAction(
                                        label = "RESET",
                                        accent = Holo.amber,
                                        onClick = onResetChannels,
                                        modifier = Modifier.weight(0.8f),
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                val displayedChannels = when (channelFilter) {
                                    "FAVORITES" -> validChannels.filter { it.favourite }
                                    "SCAN LIST" -> validChannels.filter { it.favourite }
                                    else -> validChannels
                                }
                                if (displayedChannels.isEmpty()) {
                                    Text("(no saved talkgroups in $channelFilter)", style = MaterialTheme.typography.bodySmall, color = Holo.textFaint)
                                } else {
                                    displayedChannels.forEach { channel ->
                                        val isActive = config.talkgroup == channel.talkgroup
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (isActive) Holo.cyan.copy(alpha = 0.08f) else Color(0xFF070C15))
                                                .border(1.dp, if (isActive) Holo.cyan else Holo.panelEdge, RoundedCornerShape(6.dp))
                                                .clickable { onSelectContact(channel) }
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    Text(
                                                        channel.name.ifBlank { "TALKGROUP ${channel.talkgroup}" },
                                                        style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Holo.textPrimary)
                                                    )
                                                    if (channel.favourite) {
                                                        PillBadge("★ SCAN", Holo.amber, filled = false)
                                                    }
                                                }
                                                Spacer(Modifier.height(2.dp))
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    PillBadge("TG ${channel.talkgroup}", Holo.cyan)
                                                    PillBadge(if (channel.id == "1") "TS1" else "TS2", Holo.amber)
                                                }
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                if (isActive) {
                                                    HoloIconButton(
                                                        icon = Icons.Default.CheckCircle,
                                                        contentDescription = "Active Channel",
                                                        accent = Holo.green,
                                                        onClick = { onSelectContact(channel) },
                                                        size = 32.dp,
                                                        iconSize = 17.dp,
                                                    )
                                                } else {
                                                    HoloIconButton(
                                                        icon = Icons.Default.PlayArrow,
                                                        contentDescription = "Tune Channel",
                                                        accent = Holo.cyan,
                                                        onClick = { onSelectContact(channel) },
                                                        size = 32.dp,
                                                        iconSize = 17.dp,
                                                    )
                                                }
                                                HoloIconButton(
                                                    icon = Icons.Default.Edit,
                                                    contentDescription = "Edit Channel",
                                                    accent = Holo.cyan,
                                                    onClick = {
                                                        contactToEdit = channel
                                                        showContactDialog = true
                                                    },
                                                    size = 32.dp,
                                                    iconSize = 15.dp,
                                                )
                                                HoloIconButton(
                                                    icon = if (channel.favourite) Icons.Default.Star else Icons.Default.StarBorder,
                                                    contentDescription = "Scan / Favourite",
                                                    accent = if (channel.favourite) Holo.amber else Holo.textFaint,
                                                    onClick = { onToggleFavourite(channel) },
                                                    size = 32.dp,
                                                    iconSize = 17.dp,
                                                )
                                                HoloIconButton(
                                                    icon = Icons.Outlined.DeleteOutline,
                                                    contentDescription = "Delete Channel",
                                                    accent = Holo.red,
                                                    onClick = { onRemoveContact(channel) },
                                                    size = 32.dp,
                                                    iconSize = 17.dp,
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(4.dp))
                                    }
                                }
                            }
                        }
                    }

                    3 -> {
                        // TAB 3: HOTSPOT (MMDVM UART) - The Killer Feature!
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            HoloPanel {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    HoloSectionTitle("MMDVM Software Hotspot")
                                    PillBadge("NO ROOT NEEDED", Holo.green, filled = true)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Connect your HT (DMR, M17, or Analog FM) directly via USB UART cable. Replaces Raspberry Pi + Pi-Star + MMDVM Hat!",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Holo.textDim,
                                )
                            }

                            // USB UART Device Discovery & Connection
                            HoloPanel {
                                HoloSectionTitle("USB UART Connection")
                                Spacer(Modifier.height(4.dp))
                                val devices = usbUartManager?.scanDevices() ?: emptyList()
                                if (devices.isEmpty()) {
                                    Text(
                                        text = "No USB UART cable detected. Plug in your HT programming cable (CH340/CP2102/FTDI) via USB-OTG adapter.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Holo.amber,
                                    )
                                } else {
                                    devices.forEach { dev ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(dev.chipName, style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Holo.cyan))
                                                Text(dev.description, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Holo.textDim))
                                            }
                                            HoloAction(
                                                label = if (usbUartManager?.isConnected == true) "DISCONNECT" else "CONNECT",
                                                accent = if (usbUartManager?.isConnected == true) Holo.red else Holo.green,
                                                onClick = {
                                                    if (usbUartManager?.isConnected == true) {
                                                        usbUartManager.disconnect()
                                                    } else {
                                                        usbUartManager?.connect(dev, selectedBaudRate)
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("Baud:", style = MaterialTheme.typography.labelSmall, color = Holo.textDim)
                                    listOf(9600, 38400, 57600, 115200, 460800).forEach { rate ->
                                        HoloChip(
                                            label = "$rate",
                                            selected = selectedBaudRate == rate,
                                            onClick = { selectedBaudRate = rate },
                                        )
                                    }
                                }
                            }

                            // Hotspot Mode Selection
                            HoloPanel {
                                HoloSectionTitle("Hotspot Operation Mode")
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    HoloChip(
                                        label = "DMR HOTSPOT",
                                        selected = hotspotMode == MmdvmHotspotBridge.HotspotMode.DMR,
                                        onClick = {
                                            hotspotMode = MmdvmHotspotBridge.HotspotMode.DMR
                                            hotspotBridge?.mode = MmdvmHotspotBridge.HotspotMode.DMR
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                    HoloChip(
                                        label = "M17 HOTSPOT",
                                        selected = hotspotMode == MmdvmHotspotBridge.HotspotMode.M17,
                                        onClick = {
                                            hotspotMode = MmdvmHotspotBridge.HotspotMode.M17
                                            hotspotBridge?.mode = MmdvmHotspotBridge.HotspotMode.M17
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                    HoloChip(
                                        label = "ANALOG GATEWAY",
                                        selected = hotspotMode == MmdvmHotspotBridge.HotspotMode.ANALOG_FM,
                                        onClick = {
                                            hotspotMode = MmdvmHotspotBridge.HotspotMode.ANALOG_FM
                                            hotspotBridge?.mode = MmdvmHotspotBridge.HotspotMode.ANALOG_FM
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }

                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    HoloField(
                                        value = hotspotFreq,
                                        onValueChange = {
                                            hotspotFreq = it
                                            hotspotBridge?.tuneFrequency(it)
                                        },
                                        label = "RF Frequency (MHz)",
                                        modifier = Modifier.weight(1f),
                                    )
                                    HoloAction(
                                        label = "SET FREQ",
                                        accent = Holo.cyan,
                                        onClick = { hotspotBridge?.tuneFrequency(hotspotFreq) },
                                        modifier = Modifier.height(56.dp),
                                    )
                                }
                            }

                            // Live RF Packet Counters & RF State
                            HoloPanel {
                                HoloSectionTitle("RF Activity Monitor")
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    PillBadge(
                                        text = when (hotspotBridge?.rfState) {
                                            MmdvmHotspotBridge.RfState.RECEIVING_FROM_RF -> "RF RX (FROM HT)"
                                            MmdvmHotspotBridge.RfState.TRANSMITTING_TO_RF -> "RF TX (TO HT)"
                                            else -> "RF STANDBY"
                                        },
                                        color = when (hotspotBridge?.rfState) {
                                            MmdvmHotspotBridge.RfState.RECEIVING_FROM_RF -> Holo.cyan
                                            MmdvmHotspotBridge.RfState.TRANSMITTING_TO_RF -> Holo.red
                                            else -> Holo.textDim
                                        },
                                        filled = true,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("FROM RF: ${hotspotBridge?.packetsFromRf ?: 0}", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Holo.cyan))
                                        Text("TO RF: ${hotspotBridge?.packetsToRf ?: 0}", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Holo.green))
                                    }
                                }
                            }

                            // Cable Wiring Guide Accordion
                            HoloPanel {
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { showWiringGuide = !showWiringGuide },
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    HoloSectionTitle("HT Interface Cable Pinout Guide")
                                    Text(if (showWiringGuide) "▲ HIDE" else "▼ SHOW", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Holo.cyan))
                                }
                                if (showWiringGuide) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = """
                                            KENWOOD 2-PIN (K-PLUG) WIRING:
                                            • 3.5mm Jack: SPK / Audio Out (Tip), GND (Sleeve)
                                            • 2.5mm Jack: MIC / Audio In (Tip), PTT (Ring), GND (Sleeve)
                                            • UART TTL (Quansheng UV-K5/Baofeng):
                                              TX = 2.5mm Tip, RX = 3.5mm Tip, GND = Sleeve
                                            
                                            NO ROOT NEEDED:
                                            Android USB Host automatically powers and controls the CH340 or CP2102 chip in your programming cable.
                                        """.trimIndent(),
                                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Holo.textDim),
                                    )
                                }
                            }
                        }
                    }

                    4 -> {
                        // TAB 4: SETTINGS (Full JZDMR Settings System!)
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Scrollable Category Selector with Icons (Never cut off or squished!)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val settingsCategories = listOf(
                                    Triple("RADIO", "Radio", Icons.Default.Radio),
                                    Triple("AUDIO", "Audio", Icons.Default.VolumeUp),
                                    Triple("NETWORK", "Network", Icons.Default.Wifi),
                                    Triple("APRS", "APRS", Icons.Default.NearMe),
                                    Triple("APPEARANCE", "Display", Icons.Default.Palette),
                                    Triple("VOCODER", "Vocoder", Icons.Default.GraphicEq),
                                    Triple("SYSTEM", "System", Icons.Default.Settings),
                                )
                                settingsCategories.forEach { (secKey, secLabel, secIcon) ->
                                    val selected = selectedSettingsSection == secKey
                                    val shape = RoundedCornerShape(8.dp)
                                    Box(
                                        modifier = Modifier
                                            .height(34.dp)
                                            .clip(shape)
                                            .background(if (selected) Holo.cyan.copy(alpha = 0.22f) else Color(0xFF0C1322))
                                            .border(1.dp, if (selected) Holo.cyan else Holo.panelEdge, shape)
                                            .clickable { selectedSettingsSection = secKey }
                                            .padding(horizontal = 10.dp, vertical = 4.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                imageVector = secIcon,
                                                contentDescription = secLabel,
                                                tint = if (selected) Holo.cyan else Holo.textDim,
                                                modifier = Modifier.size(15.dp),
                                            )
                                            Text(
                                                text = secLabel,
                                                style = TextStyle(
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                    fontSize = 11.sp,
                                                    color = if (selected) Holo.cyan else Holo.textDim,
                                                ),
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.width(16.dp))
                            }
                            when (selectedSettingsSection) {
                                "RADIO" -> {
                                    HoloPanel {
                                        HoloSectionTitle("Radio & PTT Settings")
                                        Spacer(Modifier.height(6.dp))
                                        SettingRow(
                                            title = "Hardware PTT Key",
                                            subtitle = "Assigned: ${settingsStore?.hardwarePttKeyName ?: "ALL / ANY KEY"}",
                                        ) {
                                            HoloAction(
                                                label = "CHANGE",
                                                accent = Holo.cyan,
                                                onClick = { showHardwarePttDialog = true },
                                            )
                                        }
                                        SettingRow(
                                            title = "PTT Mode",
                                            subtitle = "Hold to talk or tap once to start / tap again to stop",
                                        ) {
                                            HoloChip(
                                                label = settingsStore?.pttMode ?: "HOLD",
                                                selected = true,
                                                onClick = {
                                                    settingsStore?.let {
                                                        it.pttMode = if (it.pttMode == "HOLD") "TOGGLE" else "HOLD"
                                                    }
                                                },
                                            )
                                        }
                                        SettingRow(
                                            title = "Talk while screen is locked",
                                            subtitle = "Allow hardware PTT button while screen is off",
                                        ) {
                                            HoloIconButton(
                                                icon = Icons.Default.Settings,
                                                contentDescription = "Configure Volume PTT",
                                                accent = Holo.cyan,
                                                onClick = onEnableVolumePtt,
                                                size = 34.dp,
                                                iconSize = 18.dp,
                                            )
                                        }
                                        SettingRow(
                                            title = "TOT (Time-Out Timer)",
                                            subtitle = "${settingsStore?.totSeconds ?: 60} seconds limit per TX",
                                        ) {
                                            HoloChip(
                                                label = "${settingsStore?.totSeconds ?: 60}s",
                                                selected = true,
                                                onClick = {
                                                    settingsStore?.let {
                                                        it.totSeconds = when (it.totSeconds) {
                                                            30 -> 60
                                                            60 -> 120
                                                            120 -> 180
                                                            else -> 30
                                                        }
                                                    }
                                                },
                                            )
                                        }
                                        SettingToggleRow(
                                            title = "PTT Busy Lock",
                                            subtitle = "Inhibit TX while talkgroup is active",
                                            checked = settingsStore?.busyLock ?: false,
                                            onCheckedChange = { settingsStore?.busyLock = it },
                                        )
                                        SettingToggleRow(
                                            title = "Talk Permit Tone",
                                            subtitle = "Beep when starting transmission",
                                            checked = settingsStore?.permitTone ?: true,
                                            onCheckedChange = { settingsStore?.permitTone = it },
                                        )
                                        SettingToggleRow(
                                            title = "Roger Beep (Rx End Tone)",
                                            subtitle = "Beep when incoming transmission ends",
                                            checked = settingsStore?.rogerBeep ?: true,
                                            onCheckedChange = { settingsStore?.rogerBeep = it },
                                        )
                                    }
                                }

                                "AUDIO" -> {
                                    HoloPanel {
                                        HoloSectionTitle("Audio & DSP Settings")
                                        Spacer(Modifier.height(6.dp))
                                        SettingToggleRow(
                                            title = "Automatic Gain Control (AGC)",
                                            subtitle = "Level audio volume automatically",
                                            checked = settingsStore?.agcEnabled ?: true,
                                            onCheckedChange = { settingsStore?.agcEnabled = it },
                                        )
                                        SettingToggleRow(
                                            title = "DSP Noise Reduction",
                                            subtitle = "Filter background acoustic noise from microphone",
                                            checked = settingsStore?.noiseReduction ?: true,
                                            onCheckedChange = { settingsStore?.noiseReduction = it },
                                        )
                                        SettingToggleRow(
                                            title = "Key Click Tone",
                                            subtitle = "Audio beep on UI clicks and knob turns",
                                            checked = settingsStore?.keyTone ?: false,
                                            onCheckedChange = { settingsStore?.keyTone = it },
                                        )
                                    }
                                }

                                "NETWORK" -> {
                                    HoloPanel {
                                        HoloSectionTitle("Network Settings")
                                        Spacer(Modifier.height(6.dp))
                                        SettingToggleRow(
                                            title = "Auto-Connect on Startup",
                                            subtitle = "Connect to last used profile when app opens",
                                            checked = settingsStore?.autoConnect ?: false,
                                            onCheckedChange = { settingsStore?.autoConnect = it },
                                        )
                                        SettingToggleRow(
                                            title = "Auto-Reconnect",
                                            subtitle = "Reconnect automatically if connection drops",
                                            checked = settingsStore?.autoReconnect ?: true,
                                            onCheckedChange = { settingsStore?.autoReconnect = it },
                                        )
                                        SettingToggleRow(
                                            title = "Open Talkgroup on Connect",
                                            subtitle = "Briefly key selected talkgroup upon connect",
                                            checked = settingsStore?.kerchunkConnect ?: true,
                                            onCheckedChange = { settingsStore?.kerchunkConnect = it },
                                        )
                                        SettingToggleRow(
                                            title = "Unlink with TG 4000 on Change",
                                            subtitle = "Key TG 4000 first to drop old talkgroup",
                                            checked = settingsStore?.unlinkTg4000 ?: true,
                                            onCheckedChange = { settingsStore?.unlinkTg4000 = it },
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        HoloSectionTitle("Callbook & User Database (Caller ID)")
                                        Spacer(Modifier.height(4.dp))
                                        Text("Indexes station DMR IDs & callsigns for RX identification without cluttering your channels list.", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Holo.textDim))
                                        Spacer(Modifier.height(6.dp))
                                        HoloField(
                                            value = dbUrlInput,
                                            onValueChange = { dbUrlInput = it },
                                            label = "Database URL (.json / .dat / .csv)",
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        HoloAction(
                                            label = "UPDATE DATABASE (CALLBOOK)",
                                            accent = Holo.cyan,
                                            onClick = { onUpdateDbFromUrl(dbUrlInput) },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                    HoloPanel {
                                        HoloSectionTitle("DMR Broadcast Station Info (Dashboard)")
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "Sent during HomeBrew handshake (RPTC) and displayed on repeater web dashboards.",
                                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Holo.textDim)
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        HoloField(
                                            value = settingsStore?.stationHardware ?: "NinjaRadio",
                                            onValueChange = { settingsStore?.stationHardware = it },
                                            label = "Hardware / Model (e.g. NinjaRadio, MMDVM)",
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        HoloField(
                                            value = settingsStore?.stationSoftware ?: "NinjaRadio",
                                            onValueChange = { settingsStore?.stationSoftware = it },
                                            label = "Software Version (e.g. NinjaRadio v1.0)",
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        HoloField(
                                            value = settingsStore?.stationLocation ?: "Mobile, ID",
                                            onValueChange = { settingsStore?.stationLocation = it },
                                            label = "Location / QTH Info (e.g. Jakarta, ID)",
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        HoloField(
                                            value = settingsStore?.stationDescription ?: "NinjaDMR",
                                            onValueChange = { settingsStore?.stationDescription = it },
                                            label = "Station Description",
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        HoloField(
                                            value = settingsStore?.stationUrl ?: "https://ninjadmr.app",
                                            onValueChange = { settingsStore?.stationUrl = it },
                                            label = "Station URL",
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }

                                "APRS" -> {
                                    HoloPanel {
                                        HoloSectionTitle("APRS Position Beaconing")
                                        Spacer(Modifier.height(6.dp))
                                        SettingToggleRow(
                                            title = "APRS-IS Beaconing",
                                            subtitle = "Send GPS position to aprs.fi while connected",
                                            checked = settingsStore?.aprsEnabled ?: false,
                                            onCheckedChange = { settingsStore?.aprsEnabled = it },
                                        )
                                        HoloField(
                                            value = settingsStore?.aprsSsid ?: "-7",
                                            onValueChange = { settingsStore?.aprsSsid = it },
                                            label = "Callsign SSID (e.g. -7, -9)",
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        HoloField(
                                            value = settingsStore?.aprsComment ?: "NinjaRadio POC Mobile",
                                            onValueChange = { settingsStore?.aprsComment = it },
                                            label = "Status Comment",
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }

                                "APPEARANCE" -> {
                                    HoloPanel {
                                        HoloSectionTitle("Appearance & POC Screen Optimization")
                                        Spacer(Modifier.height(6.dp))
                                        SettingToggleRow(
                                            title = "POC Compact Mode (1-3\" Screens)",
                                            subtitle = "Tight margins, compact cards, zero overlap for POC radios",
                                            checked = compactMode,
                                            onCheckedChange = {
                                                compactMode = it
                                                settingsStore?.pocCompactMode = it
                                            },
                                        )
                                        SettingToggleRow(
                                            title = "Show On-Screen PTT Button",
                                            subtitle = "Turn off if your POC radio has a hardware side PTT key",
                                            checked = showPtt,
                                            onCheckedChange = {
                                                showPtt = it
                                                settingsStore?.showOnScreenPtt = it
                                            },
                                        )
                                        SettingToggleRow(
                                            title = "Compact PTT Bar",
                                            subtitle = "Use 44dp bar instead of large circular button",
                                            checked = compactPttBar,
                                            onCheckedChange = {
                                                compactPttBar = it
                                                settingsStore?.compactPttBar = it
                                            },
                                        )
                                        SettingToggleRow(
                                            title = "Show S-Meter & Spectrum",
                                            subtitle = "Display audio FFT scope on Radio tab",
                                            checked = settingsStore?.showSpectrum != false,
                                            onCheckedChange = { settingsStore?.showSpectrum = it },
                                        )
                                        SettingToggleRow(
                                            title = "Show Activity Log Table",
                                            subtitle = "Display live traffic history on Radio tab",
                                            checked = settingsStore?.showActivityLog != false,
                                            onCheckedChange = { settingsStore?.showActivityLog = it },
                                        )
                                    }
                                }

                                "VOCODER" -> {
                                    HoloPanel {
                                        HoloSectionTitle("Firmware & Vocoder")
                                        Spacer(Modifier.height(6.dp))
                                        Text("Active Vocoder: libopendmr (Native AMBE+2)", style = MaterialTheme.typography.bodySmall, color = Holo.cyan)
                                        Text("Engine Status: $vocoder", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Holo.textDim))
                                        Spacer(Modifier.height(6.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            HoloAction(label = "SELF-TEST", accent = Holo.green, onClick = onSelfTest, modifier = Modifier.weight(1f))
                                            HoloAction(label = "AUDIO TEST", accent = Holo.cyan, onClick = onVocoderTest, modifier = Modifier.weight(1f))
                                        }
                                        if (vocoderTest.isNotBlank()) {
                                            Spacer(Modifier.height(4.dp))
                                            Text(vocoderTest, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Holo.textDim))
                                        }
                                    }
                                }

                                "SYSTEM" -> {
                                    HoloPanel {
                                        HoloSectionTitle("System & Diagnostics")
                                        Spacer(Modifier.height(6.dp))
                                        HoloAction(label = "ENTER CAR MODE (DRIVING HUD)", accent = Holo.brassBright, onClick = onCarMode, modifier = Modifier.fillMaxWidth())
                                        Spacer(Modifier.height(4.dp))
                                        HoloAction(label = "REQUEST BATTERY EXEMPTION", accent = Holo.amber, onClick = onRequestBatteryExemption, modifier = Modifier.fillMaxWidth())
                                        Spacer(Modifier.height(6.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            modifier = Modifier.padding(vertical = 4.dp),
                                        ) {
                                            Image(
                                                painter = painterResource(id = R.drawable.app_logo_cute),
                                                contentDescription = "NinjaRadio Mascot",
                                                modifier = Modifier
                                                    .size(52.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .border(1.5.dp, Holo.cyan, RoundedCornerShape(12.dp)),
                                            )
                                            Column {
                                                Text("NinjaRadio DMR v0.1.0", style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Holo.textPrimary, fontSize = 13.sp))
                                                Text("Tactical DMR Mobile & Hotspot Client", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Holo.cyan))
                                                Text("Compatible with Z37CPR JZDMR & OpenDMR", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Holo.textDim))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showVfoEditDialog) {
        var editChannelName by remember { mutableStateOf(config.channelName) }
        var editZone by remember { mutableStateOf(config.zone) }
        var editFreq by remember { mutableStateOf(config.frequency) }
        var editTg by remember { mutableStateOf(config.talkgroup) }
        var editSlot by remember { mutableStateOf(config.slot) }
        var editCc by remember { mutableStateOf(config.colorCode) }

        AlertDialog(
            onDismissRequest = { showVfoEditDialog = false },
            containerColor = Holo.panelRaised,
            title = {
                Text(
                    "EDIT VFO / CHANNEL",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Holo.cyan,
                    ),
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HoloField(
                        value = editChannelName,
                        onValueChange = { editChannelName = it },
                        label = "Channel Name",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    HoloField(
                        value = editZone,
                        onValueChange = { editZone = it },
                        label = "Zone",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    HoloField(
                        value = editFreq,
                        onValueChange = { editFreq = it },
                        label = "Frequency (MHz)",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        HoloField(
                            value = editTg,
                            onValueChange = { editTg = it },
                            label = "Talkgroup",
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                            modifier = Modifier.weight(1.5f),
                        )
                        HoloField(
                            value = editSlot,
                            onValueChange = { editSlot = it },
                            label = "Slot",
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                            modifier = Modifier.weight(1f),
                        )
                        HoloField(
                            value = editCc,
                            onValueChange = { editCc = it },
                            label = "CC",
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            },
            confirmButton = {
                HoloAction(
                    label = "SAVE",
                    accent = Holo.green,
                    onClick = {
                        val updated = config.copy(
                            channelName = editChannelName.trim(),
                            zone = editZone.trim(),
                            frequency = editFreq.trim(),
                            talkgroup = editTg.trim(),
                            slot = editSlot.trim(),
                            colorCode = editCc.trim(),
                        )
                        onConfigChange(updated)
                        onSaveProfile()
                        showVfoEditDialog = false
                    },
                )
            },
            dismissButton = {
                HoloAction(
                    label = "CANCEL",
                    accent = Holo.textDim,
                    onClick = { showVfoEditDialog = false },
                )
            },
        )
    }

    if (showQuickTgDialog) {
        var editQuickTgs by remember { mutableStateOf(config.quickTgs) }

        AlertDialog(
            onDismissRequest = { showQuickTgDialog = false },
            containerColor = Holo.panelRaised,
            title = {
                Text(
                    "QUICK TALKGROUPS",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Holo.cyan,
                    ),
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Enter comma-separated talkgroups to display as quick buttons on the Radio screen:",
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Holo.textDim),
                    )
                    HoloField(
                        value = editQuickTgs,
                        onValueChange = { editQuickTgs = it },
                        label = "Talkgroups (e.g. 9990, 1, 515, 1025)",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                HoloAction(
                    label = "SAVE",
                    accent = Holo.green,
                    onClick = {
                        val updated = config.copy(quickTgs = editQuickTgs.trim())
                        onConfigChange(updated)
                        onSaveProfile()
                        showQuickTgDialog = false
                    },
                )
            },
            dismissButton = {
                HoloAction(
                    label = "CANCEL",
                    accent = Holo.textDim,
                    onClick = { showQuickTgDialog = false },
                )
            },
        )
    }

    if (showHardwarePttDialog) {
        var detectedCode by remember { mutableIntStateOf(settingsStore?.hardwarePttKeyCode ?: 0) }
        var detectedName by remember { mutableStateOf(settingsStore?.hardwarePttKeyName ?: "ALL / ANY KEY") }
        var customInput by remember { mutableStateOf("") }

        DisposableEffect(showHardwarePttDialog) {
            com.ninjadmr.app.MainActivity.onKeyCaptured = { code, name ->
                detectedCode = code
                detectedName = if (name.isNotBlank()) name else "KEY_$code"
            }
            onDispose {
                com.ninjadmr.app.MainActivity.onKeyCaptured = null
            }
        }

        AlertDialog(
            onDismissRequest = { showHardwarePttDialog = false },
            containerColor = Holo.panelRaised,
            title = {
                Text(
                    "HARDWARE PTT BUTTON",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Holo.cyan,
                    ),
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Tekan tombol fisik di handphone Anda (tombol PTT samping, volume, dsb) untuk deteksi otomatis.",
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Holo.textDim),
                    )

                    // Live Key Detection Banner
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Holo.cyan.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                            .border(1.dp, Holo.cyan.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "⚡ DETEKSI TOMBOL AKTIF",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = Holo.cyan,
                                ),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (detectedCode == 0) "Menunggu tombol ditekan..." else "Terdeteksi: $detectedName (Code: $detectedCode)",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = if (detectedCode != 0) Holo.green else Holo.amber,
                                ),
                            )
                        }
                    }

                    Text(
                        "Atau Pilih Preset Tombol:",
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = Holo.textPrimary),
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HoloAction(
                            label = "CARAKA PTT 1 (290)",
                            accent = if (detectedCode == 290) Holo.green else Holo.textDim,
                            onClick = {
                                detectedCode = 290
                                detectedName = "CARAKA PTT 1 (PoC / Video App 2)"
                            },
                            modifier = Modifier.weight(1f),
                        )
                        HoloAction(
                            label = "CARAKA PTT 2 (291)",
                            accent = if (detectedCode == 291) Holo.green else Holo.textDim,
                            onClick = {
                                detectedCode = 291
                                detectedName = "CARAKA PTT 2 (DMR / Video App 3)"
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HoloAction(
                            label = "SEMUA / BEBAS",
                            accent = if (detectedCode == 0) Holo.green else Holo.textDim,
                            onClick = {
                                detectedCode = 0
                                detectedName = "ALL / ANY KEY"
                            },
                            modifier = Modifier.weight(1f),
                        )
                        HoloAction(
                            label = "PTT DEDICATED (228)",
                            accent = if (detectedCode == 228) Holo.green else Holo.textDim,
                            onClick = {
                                detectedCode = 228
                                detectedName = "KEYCODE_PTT (228)"
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HoloAction(
                            label = "VOLUME DOWN (25)",
                            accent = if (detectedCode == 25) Holo.green else Holo.textDim,
                            onClick = {
                                detectedCode = 25
                                detectedName = "VOLUME_DOWN (25)"
                            },
                            modifier = Modifier.weight(1f),
                        )
                        HoloAction(
                            label = "VOLUME UP (24)",
                            accent = if (detectedCode == 24) Holo.green else Holo.textDim,
                            onClick = {
                                detectedCode = 24
                                detectedName = "VOLUME_UP (24)"
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HoloAction(
                            label = "HEADSET HOOK (79)",
                            accent = if (detectedCode == 79) Holo.green else Holo.textDim,
                            onClick = {
                                detectedCode = 79
                                detectedName = "HEADSETHOOK (79)"
                            },
                            modifier = Modifier.weight(1f),
                        )
                        HoloAction(
                            label = "F1 / POC (131)",
                            accent = if (detectedCode == 131) Holo.green else Holo.textDim,
                            onClick = {
                                detectedCode = 131
                                detectedName = "KEYCODE_F1 (131)"
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        HoloField(
                            value = customInput,
                            onValueChange = { customInput = it.filter { ch -> ch.isDigit() } },
                            label = "Custom KeyCode",
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                            modifier = Modifier.weight(1f),
                        )
                        HoloAction(
                            label = "SET",
                            accent = Holo.cyan,
                            onClick = {
                                customInput.toIntOrNull()?.let { code ->
                                    detectedCode = code
                                    detectedName = "KEY_$code"
                                }
                            },
                        )
                    }
                }
            },
            confirmButton = {
                HoloAction(
                    label = "SIMPAN",
                    accent = Holo.green,
                    onClick = {
                        settingsStore?.let {
                            it.hardwarePttKeyCode = detectedCode
                            it.hardwarePttKeyName = detectedName
                        }
                        showHardwarePttDialog = false
                    },
                )
            },
            dismissButton = {
                HoloAction(
                    label = "BATAL",
                    accent = Holo.textDim,
                    onClick = { showHardwarePttDialog = false },
                )
            },
        )
    }

    if (showContactDialog) {
        var editName by remember { mutableStateOf(contactToEdit?.name ?: "") }
        var editTg by remember { mutableStateOf(contactToEdit?.talkgroup ?: "") }
        var editCallsign by remember { mutableStateOf(contactToEdit?.callsign ?: "") }
        var editFav by remember { mutableStateOf(contactToEdit?.favourite ?: false) }
        var editBud by remember { mutableStateOf(contactToEdit?.isBuddy ?: false) }

        AlertDialog(
            onDismissRequest = { showContactDialog = false },
            containerColor = Holo.panelRaised,
            title = {
                Text(
                    if (contactToEdit == null || contactToEdit?.name.isNullOrBlank()) "ADD CONTACT / CHANNEL" else "EDIT CONTACT / CHANNEL",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Holo.cyan,
                    ),
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HoloField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = "Contact / Channel Name",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        HoloField(
                            value = editTg,
                            onValueChange = { editTg = it },
                            label = "Talkgroup (TG)",
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                            modifier = Modifier.weight(1.2f),
                        )
                        HoloField(
                            value = editCallsign,
                            onValueChange = { editCallsign = it.uppercase() },
                            label = "Callsign",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HoloChip(
                            label = if (editFav) "★ FAVOURITE" else "☆ FAVOURITE",
                            selected = editFav,
                            onClick = { editFav = !editFav },
                        )
                        HoloChip(
                            label = if (editBud) "★ BUDDY" else "☆ BUDDY",
                            selected = editBud,
                            onClick = { editBud = !editBud },
                        )
                    }
                }
            },
            confirmButton = {
                HoloAction(
                    label = "SAVE",
                    accent = Holo.green,
                    onClick = {
                        if (editTg.isNotBlank() || editName.isNotBlank() || editCallsign.isNotBlank()) {
                            val base = contactToEdit ?: DmrContact()
                            val newContact = base.copy(
                                id = base.id.ifBlank { editCallsign.ifBlank { editTg } },
                                name = editName.trim(),
                                talkgroup = editTg.trim(),
                                callsign = editCallsign.trim(),
                                favourite = editFav,
                                isBuddy = editBud,
                            )
                            if (contactToEdit != null && contactToEdit?.name?.isNotBlank() == true) {
                                onRemoveContact(contactToEdit!!)
                            }
                            onAddContact(newContact)
                        }
                        showContactDialog = false
                    },
                )
            },
            dismissButton = {
                HoloAction(
                    label = "CANCEL",
                    accent = Holo.textDim,
                    onClick = { showContactDialog = false },
                )
            },
        )
    }

}

/** Adaptive Top Status Bar */
@Composable
private fun TopStatusBar(
    callsign: String,
    talkgroup: String,
    slot: String,
    connected: Boolean,
    transmitting: Boolean,
    compact: Boolean,
    rxStation: String = "",
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF070C15))
            .border(1.dp, Holo.panelEdge, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.app_logo_cute),
                contentDescription = "NinjaRadio Mascot",
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .border(1.dp, Holo.cyan.copy(alpha = 0.6f), CircleShape),
            )
            PillBadge(callsign, Holo.textPrimary)
            PillBadge("TG $talkgroup", Holo.cyan)
            PillBadge("TS$slot", Holo.amber)
            if (rxStation.isNotBlank()) {
                PillBadge("RX: $rxStation", Holo.green, filled = true)
            }
        }
        PillBadge(
            text = when {
                transmitting -> "TX ON-AIR"
                connected -> "CONNECTED"
                else -> "STANDBY"
            },
            color = when {
                transmitting -> Holo.red
                connected -> Holo.green
                else -> Holo.textDim
            },
            filled = true,
        )
    }
}

/** Adaptive VFO Main Card */
@Composable
private fun VfoMainCard(
    talkgroup: String,
    slot: String,
    colorCode: String,
    frequency: String,
    channelName: String,
    zone: String,
    compact: Boolean,
    activeCaller: com.ninjadmr.app.CallerStation? = null,
    onClick: () -> Unit = {},
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        Color(0xE6141F33),
                        Color(0xCC0D1626),
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.22f),
                        Holo.cyan.copy(alpha = 0.45f),
                        Holo.panelEdge.copy(alpha = 0.30f),
                    )
                ),
                shape = shape,
            )
            .clickable { onClick() }
            .padding(if (compact) 8.dp else 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PillBadge("VFO-A", Holo.cyan, filled = true)
                    PillBadge("DMR", Holo.textDim)
                    PillBadge("TS$slot", Holo.amber)
                    PillBadge("CC$colorCode", Holo.green)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "$frequency MHz",
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = if (compact) 10.sp else 12.sp, color = Holo.textPrimary, fontWeight = FontWeight.Bold),
                    )
                    PillBadge("EDIT", Holo.cyan, filled = false)
                }
            }

            Spacer(Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = channelName,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = if (compact) 14.sp else 18.sp,
                            color = Holo.textPrimary,
                        ),
                        maxLines = 1,
                    )
                    Text(
                        text = "ZONE: $zone",
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Holo.textDim),
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "TALKGROUP",
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = Holo.textDim),
                    )
                    Text(
                        text = "TG $talkgroup",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = if (compact) 16.sp else 20.sp,
                            color = Holo.cyan,
                        ),
                    )
                }
            }

            if (activeCaller != null && activeCaller.isReceiving) {
                Spacer(Modifier.height(3.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Holo.green.copy(alpha = 0.16f))
                        .border(1.dp, Holo.green, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(Holo.green))
                            Text(
                                text = "RX: ${activeCaller.callsign}",
                                style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Holo.green),
                            )
                            if (activeCaller.name.isNotBlank()) {
                                Text(
                                    text = "(${activeCaller.name})",
                                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Holo.textPrimary),
                                    maxLines = 1,
                                )
                            }
                        }
                        Text(
                            text = "ID: ${activeCaller.dmrId}",
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Holo.green),
                        )
                    }
                }
            }
        }
    }
}

/** Real-time Adaptive S-Meter & Audio Spectrum Analyzer */
@Composable
private fun SMeterSpectrumPanel(transmitting: Boolean, connected: Boolean, compact: Boolean) {
    val shape = RoundedCornerShape(6.dp)

    // Dynamic real-time audio state refreshed at ~30 FPS
    var bars by remember { mutableStateOf(FloatArray(AudioVisualizer.NUM_BARS)) }
    var sMeterText by remember { mutableStateOf("S-METER S0" to "-121 dBm") }

    LaunchedEffect(Unit) {
        while (true) {
            bars = AudioVisualizer.getBars()
            sMeterText = AudioVisualizer.getSMeter()
            delay(33) // ~30 FPS smooth update
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xFF060B14))
            .border(1.dp, Holo.panelEdge, shape)
            .padding(if (compact) 4.dp else 6.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val label = if (transmitting) "TX MIC LEVEL" else sMeterText.first
                val valText = if (transmitting) {
                    val p = (AudioVisualizer.currentRms / 60f).toInt().coerceIn(0, 100)
                    "$p% MOD"
                } else {
                    sMeterText.second
                }
                val valColor = if (transmitting) Holo.red else Holo.green
                Text(label, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Holo.textDim, fontWeight = FontWeight.Bold))
                Text(valText, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = valColor))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 26.dp else 36.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF04070D))
                    .padding(2.dp),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val count = bars.size
                    val barWidth = size.width / count
                    for (i in 0 until count) {
                        val level = bars[i]
                        // Baseline minimum floor height (3px when connected) so meter has clear presence
                        val minHeight = if (connected || transmitting) 3f else 1f
                        val barHeight = maxOf(minHeight, size.height * level)

                        val barColor = when {
                            transmitting -> Holo.red
                            level > 0.75f -> Holo.amber
                            connected -> Holo.cyan
                            else -> Holo.textFaint
                        }

                        drawRect(
                            color = barColor.copy(alpha = if (level > 0.04f) 0.95f else 0.45f),
                            topLeft = Offset(i * barWidth + 1.5f, size.height - barHeight),
                            size = androidx.compose.ui.geometry.Size(maxOf(1f, barWidth - 3f), barHeight),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dedicated Live RX Caller ID & Station Slot.
 * Displays prominent Callsign, Name, DMR ID, Talkgroup, Slot, and timestamp.
 */
@Composable
private fun ActiveCallerCard(
    caller: com.ninjadmr.app.CallerStation?,
    linkState: String,
    status: String,
    compact: Boolean,
) {
    val isReceiving = caller?.isReceiving == true || (linkState == "RX" && caller != null)
    val shape = RoundedCornerShape(10.dp)

    val borderColor = when {
        isReceiving -> Holo.green
        caller != null -> Holo.cyan.copy(alpha = 0.55f)
        else -> Holo.panelEdge
    }

    val bgColor = when {
        isReceiving -> Color(0xFF0B1F17)
        caller != null -> Color(0xFF0C1424)
        else -> Color(0xFF0A101C)
    }

    val timeFormatted = remember(caller?.timestamp) {
        if (caller != null && caller.timestamp > 0L) {
            try {
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(caller.timestamp))
            } catch (_: Exception) {
                "--:--:--"
            }
        } else {
            "--:--:--"
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bgColor)
            .border(if (isReceiving) 1.5.dp else 1.dp, borderColor, shape)
            .padding(if (compact) 8.dp else 10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Header bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isReceiving) Holo.green else if (caller != null) Holo.cyan else Holo.textFaint),
                    )
                    Text(
                        text = if (isReceiving) "● LIVE RX CALLER ID" else if (caller != null) "LAST HEARD STATION" else "STATION CALLER ID",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isReceiving) Holo.green else if (caller != null) Holo.cyan else Holo.textDim,
                        ),
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isReceiving) {
                        PillBadge("RECEIVING VOICE", Holo.green, filled = true)
                    } else if (caller != null) {
                        PillBadge("STANDBY", Holo.textDim, filled = false)
                    } else {
                        PillBadge(if (linkState.isNotBlank()) linkState else "STANDBY", Holo.cyan, filled = false)
                    }

                    if (caller != null && caller.timestamp > 0L) {
                        Text(
                            text = timeFormatted,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = Holo.textDim,
                            ),
                        )
                    }
                }
            }

            // Main Caller Information
            if (caller != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = caller.callsign.ifBlank { "ID ${caller.dmrId}" },
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Black,
                                fontSize = if (compact) 19.sp else 23.sp,
                                color = if (isReceiving) Holo.green else Holo.cyan,
                            ),
                            maxLines = 1,
                        )

                        val subtitle = buildString {
                            if (caller.name.isNotBlank()) append(caller.name)
                            if (caller.dmrId.isNotBlank()) {
                                if (isNotEmpty()) append(" • ")
                                append("ID: ${caller.dmrId}")
                            }
                        }

                        if (subtitle.isNotBlank()) {
                            Text(
                                text = subtitle,
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isReceiving) Holo.textPrimary else Holo.textDim,
                                ),
                                maxLines = 1,
                            )
                        }
                    }

                    // Routing details (TG & Slot)
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (caller.talkgroup.isNotBlank()) {
                                PillBadge("TG ${caller.talkgroup}", Holo.cyan, filled = isReceiving)
                            }
                            if (caller.slot.isNotBlank()) {
                                PillBadge("TS${caller.slot}", Holo.amber, filled = false)
                            }
                        }
                    }
                }
            } else {
                // Empty Standby State
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        val fallbackPeer = if (status.startsWith("RX:")) status.removePrefix("RX:").trim() else ""
                        if (fallbackPeer.isNotBlank()) {
                            Text(
                                text = fallbackPeer,
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = if (compact) 16.sp else 20.sp,
                                    color = Holo.green,
                                ),
                            )
                        } else {
                            Text(
                                text = "STANDBY • WAITING FOR RX",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = if (compact) 12.sp else 14.sp,
                                    color = Holo.textDim,
                                ),
                            )
                            Text(
                                text = "Caller callsign and operator name will appear here during transmission",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp,
                                    color = Holo.textFaint,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Compact Tactile PTT Bar for Small POC Screens */
@Composable
private fun CompactPttBar(transmitting: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val color = if (transmitting) Holo.red else if (enabled) Holo.green else Holo.panelEdge

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(shape)
            .background(if (transmitting) Holo.red.copy(alpha = 0.25f) else Holo.panelRaised)
            .border(2.dp, color, shape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (transmitting) Holo.red else if (enabled) Holo.green else Holo.textFaint),
            )
            Text(
                text = if (transmitting) "ON AIR - TRANSMITTING (TAP TO STOP)" else if (enabled) "PTT - PRESS TO TALK" else "OFFLINE",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = if (transmitting) Holo.red else if (enabled) Holo.textPrimary else Holo.textFaint,
                ),
            )
        }
    }
}

/** Circular Tactical Heavy PTT Button */
@Composable
private fun CircularPttButton(transmitting: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val glow = if (transmitting) Holo.red else Holo.green
    val shape = CircleShape

    Box(
        modifier = Modifier
            .size(110.dp)
            .clip(shape)
            .background(
                Brush.radialGradient(
                    if (transmitting) listOf(Holo.red, Holo.bgDeep)
                    else listOf(Holo.panelRaised, Holo.bgDeep),
                ),
            )
            .border(
                width = if (transmitting) 3.dp else 2.dp,
                color = if (enabled) glow else Holo.panelEdge,
                shape = shape,
            )
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "PTT",
                color = if (enabled) Holo.textPrimary else Holo.textFaint,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, letterSpacing = 2.sp),
            )
            Text(
                text = when {
                    transmitting -> "ON AIR"
                    !enabled -> "OFFLINE"
                    else -> "READY"
                },
                color = if (transmitting) Holo.red else Holo.textDim,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp),
            )
        }
    }
}

/** Activity Log Table with Fixed Viewport Height & Containment (Never Stretches Outer Screen) */
@Composable
private fun ActivityLogTable(log: List<AppLog.Entry>, compact: Boolean) {
    val tableHeight = if (compact) 85.dp else 115.dp
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()

    HoloPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            HoloSectionTitle("Activity Log (Live Traffic)")
            Text(
                text = "${log.size} events",
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = Holo.textDim)
            )
        }
        Spacer(Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(tableHeight)
                .horizontalScroll(hScroll)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(vScroll)
                    .width(460.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                // Table Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0E1624))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf("TIME" to 55.dp, "TYPE" to 50.dp, "SRC" to 70.dp, "DETAILS" to 175.dp, "STATUS" to 50.dp).forEach { (col, width) ->
                        Text(
                            text = col,
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Holo.cyan),
                            modifier = Modifier.width(width),
                        )
                    }
                }
                // Log Rows (Capped to latest 40 entries, strictly inside scrollable viewport)
                val entries = log.takeLast(40).reversed()
                if (entries.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        Text("(waiting for transmissions...)", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = Holo.textFaint))
                    }
                } else {
                    val timeFormat = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US) }
                    entries.forEach { entry ->
                        val timeStr = timeFormat.format(java.util.Date(entry.ts))
                        val isTx = entry.text.contains("TX", ignoreCase = true) || entry.text.contains("PTT down", ignoreCase = true)
                        val isRx = entry.text.contains("RX", ignoreCase = true)
                        val typeColor = if (isTx) Holo.red else if (isRx) Holo.green else Holo.textDim
                        val typeLabel = if (isTx) "TX" else if (isRx) "RX" else "SYS"

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isTx) Holo.red.copy(alpha = 0.08f) else if (isRx) Holo.green.copy(alpha = 0.08f) else Color.Transparent)
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(timeStr, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = Holo.textDim), modifier = Modifier.width(55.dp))
                            Text(typeLabel, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = typeColor), modifier = Modifier.width(50.dp))
                            Text(if (isTx) "LOCAL" else if (isRx) "REMOTE" else "LINK", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = Holo.textDim), modifier = Modifier.width(70.dp))
                            Text(entry.text.take(32), style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = Holo.textPrimary), modifier = Modifier.width(175.dp), maxLines = 1)
                            Text(if (isTx || isRx) "OK" else "INFO", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = Holo.green), modifier = Modifier.width(50.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Settings Row Component */
@Composable
private fun SettingRow(title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Holo.textPrimary))
            Text(subtitle, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = Holo.textDim))
        }
        trailing()
    }
}

/** Settings Toggle Row Component */
@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 5.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = Holo.textPrimary,
                ),
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = Holo.textDim,
                    ),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Holo.cyan,
                checkedTrackColor = Holo.cyan.copy(alpha = 0.35f),
                uncheckedThumbColor = Holo.textDim,
                uncheckedTrackColor = Holo.panelEdge,
            ),
        )
    }
}

/** Pill Badge helper */
@Composable
private fun PillBadge(text: String, color: Color, filled: Boolean = false) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (filled) color.copy(alpha = 0.22f) else color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.45f), shape)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = color,
            ),
        )
    }
}

@Composable
private fun HoloChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                label,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                ),
            )
        },
        shape = RoundedCornerShape(8.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Holo.panel.copy(alpha = 0.55f),
            labelColor = Holo.textDim,
            selectedContainerColor = Holo.cyan.copy(alpha = 0.18f),
            selectedLabelColor = Holo.cyan,
        ),
        border = FilterChipDefaults.filterChipBorder(
            borderColor = Holo.panelEdge.copy(alpha = 0.45f),
            selectedBorderColor = Holo.cyan.copy(alpha = 0.65f),
            enabled = true,
            selected = selected,
        ),
        modifier = modifier,
    )
}

@Composable
private fun HoloAction(
    label: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(8.dp)
    val tint = if (enabled) accent else Holo.textFaint
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(shape)
            .background(if (enabled) tint.copy(alpha = 0.14f) else Holo.panel.copy(alpha = 0.45f))
            .border(1.dp, tint.copy(alpha = if (enabled) 0.50f else 0.18f), shape)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = tint,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            softWrap = false,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 0.2.sp,
            ),
        )
    }
}

@Composable
private fun HoloIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: androidx.compose.ui.unit.Dp = 34.dp,
    iconSize: androidx.compose.ui.unit.Dp = 18.dp,
) {
    val shape = RoundedCornerShape(8.dp)
    val tint = if (enabled) accent else Holo.textFaint
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(if (enabled) tint.copy(alpha = 0.14f) else Holo.panel.copy(alpha = 0.45f))
            .border(1.dp, tint.copy(alpha = if (enabled) 0.50f else 0.18f), shape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun ProfilePicker(
    profiles: List<RadioProfile>,
    selectedIndex: Int,
    config: RadioProfile,
    onConfigChange: (RadioProfile) -> Unit,
    onSelectProfile: (Int) -> Unit,
    onSaveProfile: () -> Unit,
    onNewProfile: () -> Unit,
    onDeleteProfile: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = profiles.getOrNull(selectedIndex)?.name ?: "(none)"
    HoloSectionTitle("Profile")
    Spacer(Modifier.height(4.dp))
    HoloField(
        value = config.name,
        onValueChange = { onConfigChange(config.copy(name = it)) },
        label = "Profile Name",
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Holo.panel.copy(alpha = 0.55f),
                    contentColor = Holo.textPrimary,
                ),
                border = BorderStroke(1.dp, Holo.panelEdge),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        current,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Holo.cyan,
                        ),
                        maxLines = 1,
                    )
                    Text("▼", fontSize = 10.sp, color = Holo.textDim)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                profiles.forEachIndexed { index, profile ->
                    DropdownMenuItem(
                        text = { Text(profile.name, style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            expanded = false
                            onSelectProfile(index)
                        },
                    )
                }
            }
        }
        HoloIconButton(
            icon = Icons.Default.Add,
            contentDescription = "New Profile",
            accent = Holo.cyan,
            onClick = onNewProfile,
        )
        HoloIconButton(
            icon = Icons.Default.Save,
            contentDescription = "Save Profile",
            accent = Holo.green,
            onClick = onSaveProfile,
        )
        HoloIconButton(
            icon = Icons.Outlined.DeleteOutline,
            contentDescription = "Delete Profile",
            accent = Holo.red,
            onClick = onDeleteProfile,
        )
    }
}
