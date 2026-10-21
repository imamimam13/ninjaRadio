package com.ninjadmr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninjadmr.app.config.RadioProfile
import com.ninjadmr.app.contacts.DmrContact
import com.ninjadmr.app.util.AppLog

@Composable
fun RadioScreen(
    profiles: List<RadioProfile>,
    selectedIndex: Int,
    config: RadioProfile,
    password: String,
    masters: List<Map<String, Any>>,
    talkgroups: List<Map<String, Any>>,
    contacts: List<DmrContact>,
    scanEnabled: Boolean,
    log: List<AppLog.Entry>,
    linkState: String,
    sessionState: String,
    rxCount: Int,
    txCount: Int,
    connected: Boolean,
    transmitting: Boolean,
    status: String,
    vocoder: String,
    vocoderTest: String,
    onConfigChange: (RadioProfile) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPickMaster: (Map<String, Any>) -> Unit,
    onPickTalkgroup: (Map<String, Any>) -> Unit,
    onAddContact: () -> Unit,
    onRemoveContact: (DmrContact) -> Unit,
    onToggleFavourite: (DmrContact) -> Unit,
    onSelectContact: (DmrContact) -> Unit,
    onToggleScan: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onSelectProfile: (Int) -> Unit,
    onSaveProfile: () -> Unit,
    onDeleteProfile: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSelfTest: () -> Unit,
    onVocoderTest: () -> Unit,
    onTogglePtt: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onEnableVolumePtt: () -> Unit,
    onCarMode: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Holo.bgDeep)) {
        HoloHudBackground(Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "NINJADMR",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp,
                            letterSpacing = 5.sp,
                            color = Holo.textPrimary,
                        ),
                    )
                    Text(
                        "HOLOGRAPHIC DMR TERMINAL",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                LinkBadge(sessionState.ifBlank { linkState })
            }

            HoloPanel {
                ProfilePicker(
                    profiles = profiles,
                    selectedIndex = selectedIndex,
                    onSelectProfile = onSelectProfile,
                    onSaveProfile = onSaveProfile,
                    onDeleteProfile = onDeleteProfile,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HoloChip(
                        label = "HOMEBREW",
                        selected = config.protocol == "homebrew",
                        onClick = { onConfigChange(config.copy(protocol = "homebrew", port = "62031")) },
                        modifier = Modifier.weight(1f),
                    )
                    HoloChip(
                        label = "ODTP",
                        selected = config.protocol == "odtp",
                        onClick = { onConfigChange(config.copy(protocol = "odtp", port = "54005")) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (masters.isNotEmpty() || talkgroups.isNotEmpty()) {
                HoloPanel {
                    HoloSectionTitle("Directory")
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DirectoryDropdown(
                            label = "Master",
                            items = masters.map { (it["name"] ?: "?").toString() },
                            onSelect = { index -> masters.getOrNull(index)?.let(onPickMaster) },
                            modifier = Modifier.weight(1f),
                        )
                        DirectoryDropdown(
                            label = "Talkgroup",
                            items = talkgroups.map { "${it["id"]} · ${it["name"]}" },
                            onSelect = { index -> talkgroups.getOrNull(index)?.let(onPickTalkgroup) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            HoloPanel {
                HoloSectionTitle("Station")
                Spacer(Modifier.height(8.dp))
                HoloField(
                    value = config.server,
                    onValueChange = { onConfigChange(config.copy(server = it)) },
                    label = "Master server",
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HoloField(
                        value = config.port,
                        onValueChange = { onConfigChange(config.copy(port = it)) },
                        label = "Port",
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                    HoloField(
                        value = config.dmrId,
                        onValueChange = { onConfigChange(config.copy(dmrId = it)) },
                        label = "DMR ID",
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HoloField(
                        value = config.callsign,
                        onValueChange = { onConfigChange(config.copy(callsign = it)) },
                        label = "Callsign",
                        modifier = Modifier.weight(1f),
                    )
                    HoloField(
                        value = config.essid,
                        onValueChange = { onConfigChange(config.copy(essid = it)) },
                        label = "ESSID",
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                }
                HoloField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = "Password",
                    password = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HoloField(
                        value = config.talkgroup,
                        onValueChange = { onConfigChange(config.copy(talkgroup = it)) },
                        label = "Talkgroup",
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                    HoloField(
                        value = config.slot,
                        onValueChange = { onConfigChange(config.copy(slot = it)) },
                        label = "Slot",
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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

            PttButton(
                transmitting = transmitting,
                enabled = connected,
                onClick = onTogglePtt,
            )

            HoloAction(
                label = "ENTER CAR MODE",
                accent = Holo.brassBright,
                onClick = onCarMode,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            )

            HoloPanel {
                HoloSectionTitle("Telemetry")
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    HoloGauge(label = "RX", value = rxCount, color = Holo.cyan)
                    HoloGauge(label = "TX", value = txCount, color = Holo.magenta)
                }
                Spacer(Modifier.height(10.dp))
                HoloStatusRow("Session", sessionState.ifBlank { "idle" }, accent = stateColor(sessionState))
                HoloStatusRow("Link", linkState, accent = Holo.textPrimary)
                HoloStatusRow(
                    "Vocoder",
                    when {
                        vocoder.startsWith("ok") -> "OK"
                        vocoder == "not started" -> "STANDBY"
                        else -> "UNAVAILABLE"
                    },
                    accent = when {
                        vocoder.startsWith("ok") -> Holo.green
                        vocoder == "not started" -> Holo.textDim
                        else -> Holo.amber
                    },
                )
                if (vocoderTest.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    HoloTerminal(text = "> vocoder-test\n$vocoderTest", color = if (vocoderTest.startsWith("OK")) Holo.green else Holo.amber)
                }
                if (status.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(status, style = MaterialTheme.typography.bodyMedium, color = Holo.textDim)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HoloAction(
                    label = "SELF-TEST",
                    accent = Holo.brassBright,
                    onClick = onSelfTest,
                    modifier = Modifier.weight(1f),
                )
                HoloAction(
                    label = "VOCODER",
                    accent = Holo.violet,
                    onClick = onVocoderTest,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HoloAction(
                    label = "BATTERY",
                    accent = Holo.amber,
                    onClick = onRequestBatteryExemption,
                    modifier = Modifier.weight(1f),
                )
                HoloAction(
                    label = "VOL-PTT",
                    accent = Holo.cyan,
                    onClick = onEnableVolumePtt,
                    modifier = Modifier.weight(1f),
                )
            }

            HoloPanel {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HoloSectionTitle("Activity log")
                    HoloBadge(
                        if (scanEnabled) "SCANNING" else "IDLE",
                        if (scanEnabled) Holo.green else Holo.textDim,
                    )
                }
                Spacer(Modifier.height(8.dp))
                HoloTerminal(
                    text = if (log.isEmpty()) "> awaiting telemetry…" else AppLog.format(log),
                    color = Holo.cyan,
                )
            }

            HoloPanel {
                HoloSectionTitle("Contacts")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HoloAction(label = "ADD", accent = Holo.cyan, onClick = onAddContact, modifier = Modifier.weight(1f))
                    HoloAction(
                        label = if (scanEnabled) "STOP SCAN" else "START SCAN",
                        accent = if (scanEnabled) Holo.red else Holo.green,
                        onClick = onToggleScan,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (contacts.isEmpty()) {
                    Text("(belum ada contact)", style = MaterialTheme.typography.bodyMedium, color = Holo.textFaint)
                } else {
                    contacts.forEach { contact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectContact(contact) },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                contact.label(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Holo.textPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { onToggleFavourite(contact) }) {
                                Icon(
                                    imageVector = if (contact.favourite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                    contentDescription = "Favourite",
                                    tint = if (contact.favourite) Holo.brassBright else Holo.textFaint,
                                )
                            }
                            IconButton(onClick = { onRemoveContact(contact) }) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = "Delete",
                                    tint = Holo.textDim,
                                )
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HoloAction(label = "EXPORT", accent = Holo.brassBright, onClick = onExport, modifier = Modifier.weight(1f))
                HoloAction(label = "IMPORT", accent = Holo.brassBright, onClick = onImport, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun stateColor(state: String): Color = when (state.uppercase()) {
    "CONNECTED" -> Holo.green
    "ERROR", "FAILED" -> Holo.red
    "IDLE", "" -> Holo.textDim
    else -> Holo.cyan
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
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp,
                ),
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Holo.panel,
            labelColor = Holo.textDim,
            selectedContainerColor = Holo.cyan.copy(alpha = 0.16f),
            selectedLabelColor = Holo.cyan,
        ),
        border = FilterChipDefaults.filterChipBorder(
            borderColor = Holo.panelEdge,
            selectedBorderColor = Holo.cyan,
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
            .height(44.dp)
            .clip(shape)
            .background(if (enabled) tint.copy(alpha = 0.10f) else Holo.panel)
            .border(1.dp, tint.copy(alpha = if (enabled) 0.85f else 0.35f), shape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = tint,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 1.4.sp,
            ),
        )
    }
}

@Composable
private fun PttButton(transmitting: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val glow = if (transmitting) Holo.magenta else Holo.cyan
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    if (transmitting) listOf(Holo.magentaDim, Holo.bgDeep)
                    else listOf(Holo.panelRaised, Holo.bgDeep),
                ),
            )
            .border(
                width = if (transmitting) 2.dp else 1.dp,
                color = if (enabled) glow else Holo.panelEdge,
                shape = shape,
            )
            .drawBehind {
                if (enabled) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(glow.copy(alpha = if (transmitting) 0.40f else 0.16f), Color.Transparent),
                            radius = size.minDimension * 0.75f,
                            center = center,
                        ),
                        radius = size.minDimension * 0.75f,
                        center = center,
                    )
                }
            }
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "PUSH TO TALK",
                color = if (enabled) Holo.textPrimary else Holo.textFaint,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    letterSpacing = 3.sp,
                ),
            )
            Text(
                text = when {
                    transmitting -> "● ON AIR"
                    !enabled -> "○ LINK DOWN"
                    else -> "○ STANDBY"
                },
                color = if (transmitting) Holo.magenta else Holo.textDim,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                ),
            )
        }
    }
}

@Composable
private fun LinkBadge(state: String) {
    HoloBadge(state, stateColor(state))
}

@Composable
private fun DirectoryDropdown(
    label: String,
    items: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(label.uppercase(), style = MaterialTheme.typography.labelSmall) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEachIndexed { index, item ->
                DropdownMenuItem(
                    text = { Text(item, style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        expanded = false
                        onSelect(index)
                    },
                )
            }
        }
    }
}

@Composable
private fun ProfilePicker(
    profiles: List<RadioProfile>,
    selectedIndex: Int,
    onSelectProfile: (Int) -> Unit,
    onSaveProfile: () -> Unit,
    onDeleteProfile: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = profiles.getOrNull(selectedIndex)?.name ?: "(none)"
    HoloSectionTitle("Profile")
    Spacer(Modifier.height(6.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    current,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = Holo.textPrimary,
                    ),
                )
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
        HoloAction(label = "SAVE", accent = Holo.green, onClick = onSaveProfile)
        HoloAction(label = "DEL", accent = Holo.red, onClick = onDeleteProfile)
    }
}
