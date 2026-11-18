package com.ninjaradio.svx.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninjaradio.svx.MainUiState
import com.ninjaradio.svx.data.AppSettingsData
import com.ninjaradio.svx.ui.theme.NinjaBackground
import com.ninjaradio.svx.ui.theme.NinjaPrimaryBlue
import com.ninjaradio.svx.ui.theme.NinjaSurface
import com.ninjaradio.svx.ui.theme.NinjaTextMuted
import com.ninjaradio.svx.ui.theme.NinjaTextPrimary
import com.ninjaradio.svx.ui.theme.NinjaTextSecondary

@Composable
fun SettingsScreen(
    state: MainUiState,
    onOpenLogs: () -> Unit = {},
    onUpdateSettings: (AppSettingsData) -> Unit,
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    val settings = state.settings

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NinjaBackground)
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Text(
            text = "Settings",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = NinjaTextPrimary,
            modifier = Modifier.padding(bottom = 14.dp)
        )

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search settings...", color = NinjaTextMuted) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = NinjaTextMuted
                )
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NinjaPrimaryBlue,
                unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = NinjaSurface,
                unfocusedContainerColor = NinjaSurface,
                focusedTextColor = NinjaTextPrimary,
                unfocusedTextColor = NinjaTextPrimary
            ),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))

        // --- SECTION 1: TRANSMISSION ---
        SettingsSectionHeader(title = "TRANSMISSION")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(NinjaSurface)
        ) {
            // PTT Mode
            SettingsDropdownRow(
                icon = Icons.Default.Radio,
                title = "PTT Mode",
                currentValue = settings.pttMode,
                options = listOf("Latch", "Push-to-Talk"),
                onOptionSelected = { onUpdateSettings(settings.copy(pttMode = it)) }
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

            // Max TX Time
            val maxTxLabel = when (settings.maxTxTimeSeconds) {
                30 -> "30s"
                60 -> "1m"
                90 -> "1m 30s"
                120 -> "2m"
                180 -> "3m"
                else -> "Disabled"
            }
            SettingsDropdownRow(
                icon = Icons.Default.HourglassBottom,
                title = "Max TX Time",
                currentValue = maxTxLabel,
                options = listOf("30s", "1m", "1m 30s", "2m", "3m", "Disabled"),
                onOptionSelected = { label ->
                    val sec = when (label) {
                        "30s" -> 30
                        "1m" -> 60
                        "1m 30s" -> 90
                        "2m" -> 120
                        "3m" -> 180
                        else -> 0
                    }
                    onUpdateSettings(settings.copy(maxTxTimeSeconds = sec))
                }
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

            // Timeout Warning
            SettingsSwitchRow(
                icon = Icons.Default.Warning,
                title = "Timeout Warning",
                subtitle = "Vibrate before timeout",
                checked = settings.timeoutWarning,
                onCheckedChange = { onUpdateSettings(settings.copy(timeoutWarning = it)) }
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

            // Warning Time
            SettingsDropdownRow(
                icon = Icons.Default.Warning,
                title = "Warning Time",
                currentValue = "${settings.warningTimeSeconds}s",
                options = listOf("5s", "10s", "15s", "30s"),
                onOptionSelected = { label ->
                    val sec = label.replace("s", "").toIntOrNull() ?: 15
                    onUpdateSettings(settings.copy(warningTimeSeconds = sec))
                }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- SECTION 2: AUDIO ---
        SettingsSectionHeader(title = "AUDIO")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(NinjaSurface)
        ) {
            // Audio Output
            SettingsDropdownRow(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                title = "Audio Output",
                currentValue = settings.audioOutput,
                options = listOf("Loudspeaker", "Earpiece"),
                onOptionSelected = { onUpdateSettings(settings.copy(audioOutput = it)) }
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

            // Audio Input
            SettingsInfoRow(
                icon = Icons.Default.Mic,
                title = "Audio Input",
                subtitle = "Audio capture device for transmission",
                value = "Microphone"
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

            // Mic Volume
            SettingsSliderRow(
                icon = Icons.Default.Mic,
                title = "Mic Volume",
                subtitle = "Adjust microphone input gain",
                value = settings.micVolume,
                onValueChange = { onUpdateSettings(settings.copy(micVolume = it)) }
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

            // RX Volume
            SettingsSliderRow(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                title = "RX Volume",
                subtitle = "Adjust received audio volume",
                value = settings.rxVolume,
                onValueChange = { onUpdateSettings(settings.copy(rxVolume = it)) }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- SECTION 3: CONNECTION ---
        SettingsSectionHeader(title = "CONNECTION")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(NinjaSurface)
        ) {
            // Auto-Connect
            SettingsSwitchRow(
                icon = Icons.Default.Wifi,
                title = "Auto-Connect",
                subtitle = "Connect to last server on launch",
                checked = settings.autoConnect,
                onCheckedChange = { onUpdateSettings(settings.copy(autoConnect = it)) }
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

            // Keep Screen On
            SettingsSwitchRow(
                icon = Icons.Default.Info,
                title = "Keep Screen On",
                subtitle = "Prevent timeout while connected",
                checked = settings.keepScreenOn,
                onCheckedChange = { onUpdateSettings(settings.copy(keepScreenOn = it)) }
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

            // Battery Optimization
            SettingsActionRow(
                icon = Icons.Default.BatteryStd,
                title = "Battery Optimization",
                subtitle = "Open system settings to exempt from battery saving",
                onClick = {
                    try {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        val intent = Intent(Settings.ACTION_SETTINGS)
                        context.startActivity(intent)
                    }
                }
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

            // Activity Logs
            SettingsActionRow(
                icon = Icons.Default.Terminal,
                title = "Activity Logs",
                subtitle = "View live TCP/UDP reflector connection logs",
                onClick = onOpenLogs
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- SECTION 4: APPEARANCE ---
        SettingsSectionHeader(title = "APPEARANCE")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(NinjaSurface)
        ) {
            SettingsDropdownRow(
                icon = Icons.Default.ColorLens,
                title = "Accent Color",
                currentValue = settings.accentColor,
                options = listOf("Blue", "Green", "Red", "Purple"),
                onOptionSelected = { onUpdateSettings(settings.copy(accentColor = it)) }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- SECTION 5: ABOUT ---
        SettingsSectionHeader(title = "ABOUT")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(NinjaSurface)
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = androidx.compose.ui.res.painterResource(id = com.ninjaradio.svx.R.drawable.logo),
                contentDescription = "Ninja Radio Logo",
                modifier = Modifier
                    .size(90.dp)
                    .clip(CircleShape)
            )
            Text(
                text = "Ninjasvx",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = NinjaTextPrimary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(NinjaSurface)
        ) {
            SettingsInfoRow(
                icon = Icons.Default.Info,
                title = "Version",
                subtitle = null,
                value = "1.1.0"
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

            SettingsActionRow(
                icon = Icons.Default.Security,
                title = "Licenses",
                subtitle = null,
                onClick = {}
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

            SettingsInfoRow(
                icon = Icons.Default.Person,
                title = "Developer",
                subtitle = null,
                value = "Ninja Radio"
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

            SettingsActionRow(
                icon = Icons.Default.Language,
                title = "Website",
                subtitle = null,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, "https://github.com/ninjaRadio".toUri())
                    context.startActivity(intent)
                }
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

            SettingsActionRow(
                icon = Icons.Default.Code,
                title = "GitHub",
                subtitle = null,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, "https://github.com/ninjaRadio".toUri())
                    context.startActivity(intent)
                }
            )
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = NinjaTextMuted,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = NinjaTextSecondary, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = NinjaTextPrimary)
                if (subtitle != null) {
                    Text(text = subtitle, fontSize = 12.sp, color = NinjaTextMuted)
                }
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = NinjaPrimaryBlue
            )
        )
    }
}

@Composable
private fun SettingsDropdownRow(
    icon: ImageVector,
    title: String,
    currentValue: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = NinjaTextSecondary, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(14.dp))
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = NinjaTextPrimary)
        }

        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = currentValue, fontSize = 14.sp, color = NinjaTextSecondary)
                Spacer(modifier = Modifier.width(4.dp))
                Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = NinjaTextMuted, modifier = Modifier.size(16.dp))
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(NinjaSurface)
            ) {
                options.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(opt, color = NinjaTextPrimary) },
                        onClick = {
                            onOptionSelected(opt)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSliderRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = icon, contentDescription = null, tint = NinjaTextSecondary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(14.dp))
                Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = NinjaTextPrimary)
            }
            Text(text = "${(value * 100).toInt()}%", fontSize = 14.sp, color = NinjaTextSecondary)
        }

        Text(text = subtitle, fontSize = 12.sp, color = NinjaTextMuted, modifier = Modifier.padding(start = 34.dp, top = 2.dp))

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0.0f..2.0f,
            colors = SliderDefaults.colors(
                thumbColor = NinjaPrimaryBlue,
                activeTrackColor = NinjaPrimaryBlue,
                inactiveTrackColor = Color.White.copy(alpha = 0.1f)
            ),
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun SettingsInfoRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = NinjaTextSecondary, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = NinjaTextPrimary)
                if (subtitle != null) {
                    Text(text = subtitle, fontSize = 12.sp, color = NinjaTextMuted)
                }
            }
        }
        Text(text = value, fontSize = 14.sp, color = NinjaTextSecondary)
    }
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = NinjaTextSecondary, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = NinjaTextPrimary)
                if (subtitle != null) {
                    Text(text = subtitle, fontSize = 12.sp, color = NinjaTextMuted)
                }
            }
        }
        Icon(imageVector = Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = NinjaTextMuted, modifier = Modifier.size(16.dp))
    }
}
