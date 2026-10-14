package com.ninjadmr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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
    rxCount: Int,
    txCount: Int,
    connected: Boolean,
    transmitting: Boolean,
    status: String,
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
    onTogglePtt: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onEnableVolumePtt: () -> Unit,
) {
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
        ) {
            Text("NinjaDMR", style = MaterialTheme.typography.headlineMedium)
            LinkBadge(linkState)
        }

        ProfilePicker(
            profiles = profiles,
            selectedIndex = selectedIndex,
            onSelectProfile = onSelectProfile,
            onSaveProfile = onSaveProfile,
            onDeleteProfile = onDeleteProfile,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = config.protocol == "homebrew",
                onClick = { onConfigChange(config.copy(protocol = "homebrew", port = "62031")) },
                label = { Text("HomeBrew") },
            )
            FilterChip(
                selected = config.protocol == "odtp",
                onClick = { onConfigChange(config.copy(protocol = "odtp", port = "54005")) },
                label = { Text("ODTP") },
            )
        }

        if (masters.isNotEmpty() || talkgroups.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DirectoryDropdown(
                    label = "Master",
                    items = masters.map { (it["name"] ?: "?").toString() },
                    onSelect = { index -> masters.getOrNull(index)?.let(onPickMaster) },
                    modifier = Modifier.weight(1f),
                )
                DirectoryDropdown(
                    label = "Talkgroup",
                    items = talkgroups.map {
                        "${it["id"]} · ${it["name"]}"
                    },
                    onSelect = { index -> talkgroups.getOrNull(index)?.let(onPickTalkgroup) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        OutlinedTextField(
            value = config.server,
            onValueChange = { onConfigChange(config.copy(server = it)) },
            label = { Text("Master server") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = config.port,
                onValueChange = { onConfigChange(config.copy(port = it)) },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = config.dmrId,
                onValueChange = { onConfigChange(config.copy(dmrId = it)) },
                label = { Text("DMR ID") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = config.callsign,
                onValueChange = { onConfigChange(config.copy(callsign = it)) },
                label = { Text("Callsign") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = config.essid,
                onValueChange = { onConfigChange(config.copy(essid = it)) },
                label = { Text("ESSID") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = config.talkgroup,
                onValueChange = { onConfigChange(config.copy(talkgroup = it)) },
                label = { Text("Talkgroup") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = config.slot,
                onValueChange = { onConfigChange(config.copy(slot = it)) },
                label = { Text("Slot") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onConnect,
                enabled = !connected,
                modifier = Modifier.weight(1f),
            ) { Text("Connect") }
            OutlinedButton(
                onClick = onDisconnect,
                enabled = connected,
                modifier = Modifier.weight(1f),
            ) { Text("Disconnect") }
        }

        Button(
            onClick = onTogglePtt,
            enabled = connected,
            colors = if (transmitting) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.buttonColors()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) { Text(if (transmitting) "TRANSMITTING — tap to stop" else "Push To Talk") }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onSelfTest, modifier = Modifier.weight(1f)) {
                Text("Self-test")
            }
            OutlinedButton(onClick = onRequestBatteryExemption, modifier = Modifier.weight(1f)) {
                Text("Battery exempt")
            }
        }

        OutlinedButton(onClick = onEnableVolumePtt, modifier = Modifier.fillMaxWidth()) {
            Text("Enable volume-key PTT")
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Status", style = MaterialTheme.typography.titleMedium)
                Text(status.ifBlank { "idle" })
                Spacer(Modifier.height(4.dp))
                Text("link: $linkState · RX $rxCount · TX $txCount")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Activity log", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                if (log.isEmpty()) {
                    Text("(kosong)", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        text = AppLog.format(log),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Contacts & scan", style = MaterialTheme.typography.titleMedium)
                    Text(if (scanEnabled) "SCANNING" else "idle",
                        style = MaterialTheme.typography.labelMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onAddContact) { Text("Add current") }
                    OutlinedButton(onClick = onToggleScan) {
                        Text(if (scanEnabled) "Stop scan" else "Start scan")
                    }
                }
                if (contacts.isEmpty()) {
                    Text("(belum ada contact)", style = MaterialTheme.typography.bodySmall)
                } else {
                    contacts.forEach { contact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectContact(contact) },
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                contact.label(),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { onToggleFavourite(contact) }) {
                                Icon(
                                    imageVector = if (contact.favourite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                    contentDescription = "Favourite",
                                    tint = if (contact.favourite) Color(0xFFFFC107) else MaterialTheme.colorScheme.outline,
                                )
                            }
                            IconButton(onClick = { onRemoveContact(contact) }) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = "Delete",
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                Text("Export")
            }
            OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                Text("Import")
            }
        }
    }
}

@Composable
private fun LinkBadge(state: String) {
    val color = when (state) {
        "TX" -> MaterialTheme.colorScheme.error
        "RX" -> MaterialTheme.colorScheme.primary
        "connected", "CONNECTED" -> Color(0xFF2E7D32)
        "error", "ERROR" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    Text(
        text = state,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .background(color, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun DirectoryDropdown(
    label: String,
    items: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEachIndexed { index, item ->
                DropdownMenuItem(
                    text = { Text(item) },
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
    Column {
        Text("Profile", style = MaterialTheme.typography.labelLarge)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.weight(1f),
            ) { Text(current) }
            Button(onClick = onSaveProfile) { Text("Save") }
            OutlinedButton(onClick = onDeleteProfile) { Text("Del") }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            profiles.forEachIndexed { index, profile ->
                DropdownMenuItem(
                    text = { Text(profile.name) },
                    onClick = {
                        expanded = false
                        onSelectProfile(index)
                    },
                )
            }
        }
    }
}
