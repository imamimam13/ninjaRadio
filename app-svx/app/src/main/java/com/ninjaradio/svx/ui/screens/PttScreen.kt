package com.ninjaradio.svx.ui.screens

import android.view.MotionEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninjaradio.svx.MainUiState
import com.ninjaradio.svx.R
import com.ninjaradio.svx.SvxReflectorClient
import com.ninjaradio.svx.ui.components.VerticalSMeterBar
import com.ninjaradio.svx.ui.theme.NinjaBackground
import com.ninjaradio.svx.ui.theme.NinjaRxGreen
import com.ninjaradio.svx.ui.theme.NinjaSurface
import com.ninjaradio.svx.ui.theme.NinjaTextMuted
import com.ninjaradio.svx.ui.theme.NinjaTextPrimary
import com.ninjaradio.svx.ui.theme.NinjaTextSecondary
import com.ninjaradio.svx.ui.theme.NinjaTxBlue
import com.ninjaradio.svx.ui.theme.NinjaTxRed
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PttScreen(
    state: MainUiState,
    onToggleConnection: () -> Unit,
    onToggleCarMode: () -> Unit,
    onOpenLogs: () -> Unit,
    onSelectTg: (Long) -> Unit,
    onPttPressed: () -> Unit,
    onPttReleased: () -> Unit,
) {
    val isConnected = state.connectionState == SvxReflectorClient.State.CONNECTED
    val isReceiving = state.activeTalker != null
    var tgDropdownExpanded by remember { mutableStateOf(false) }

    val activeTgConfig = state.activeServer.talkgroups.find { it.tg == state.currentTg }
    val tgLabelStr = if (activeTgConfig != null && activeTgConfig.label.isNotBlank()) {
        "${activeTgConfig.label} — TG ${state.currentTg}"
    } else {
        "Talkgroup ${state.currentTg}"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NinjaBackground)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // --- Top Bar ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Ninjasvx",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = NinjaTextPrimary,
            )

            Row {
                IconButton(onClick = onOpenLogs) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = "Activity Logs",
                        tint = NinjaTextMuted,
                    )
                }
                IconButton(onClick = onToggleCarMode) {
                    Icon(
                        imageVector = Icons.Default.DirectionsCar,
                        contentDescription = "Car Mode",
                        tint = NinjaTextMuted,
                    )
                }
                IconButton(
                    onClick = onToggleConnection,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(NinjaSurface)
                        .size(42.dp),
                ) {
                    Icon(
                        imageVector = if (isConnected) Icons.Default.Link else Icons.Default.LinkOff,
                        contentDescription = "Connection Toggle",
                        tint = if (isConnected) NinjaRxGreen else NinjaTextMuted,
                    )
                }
            }
        }

        // --- Logo, Server & Talkgroup Header (Top Center) ---
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            // Mascot Logo above Server & TG Info
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = "Logo",
                modifier = Modifier
                    .size(90.dp)
                    .clip(CircleShape),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = state.activeServer.name.uppercase(Locale.US),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = NinjaTextPrimary,
                letterSpacing = 1.sp,
            )

            Box(modifier = Modifier.padding(top = 2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { tgDropdownExpanded = true }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = tgLabelStr,
                        fontSize = 14.sp,
                        color = NinjaTextSecondary,
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Select Talkgroup",
                        tint = NinjaTextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }

                DropdownMenu(
                    expanded = tgDropdownExpanded,
                    onDismissRequest = { tgDropdownExpanded = false },
                    modifier = Modifier.background(NinjaSurface),
                ) {
                    state.activeServer.talkgroups.forEach { tgConfig ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "${tgConfig.label} — TG ${tgConfig.tg}",
                                    color = if (tgConfig.tg == state.currentTg) Color(0xFFFF9E80) else NinjaTextPrimary,
                                )
                            },
                            onClick = {
                                onSelectTg(tgConfig.tg)
                                tgDropdownExpanded = false
                            },
                        )
                    }
                }
            }

            val activityText = when {
                state.isTransmitting -> "Transmitting @ TG ${state.currentTg}"
                isReceiving -> "${state.activeTalker} @ TG ${state.activeTalkerTg ?: state.currentTg}"
                else -> ""
            }

            if (activityText.isNotEmpty()) {
                Text(
                    text = activityText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (state.isTransmitting) NinjaTxRed else NinjaRxGreen,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        // --- Side-by-Side Main Controls: Left Vertical S-Meters & Right Square PTT ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left Side: Vertical RX & TX S-Meter Bars
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(NinjaSurface)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VerticalSMeterBar(
                    label = "RX",
                    levelPct = state.rxLevelPct,
                    dbValue = state.rxDb,
                    activeColor = NinjaRxGreen,
                    modifier = Modifier.fillMaxHeight(),
                )

                VerticalSMeterBar(
                    label = "TX",
                    levelPct = state.txLevelPct,
                    dbValue = state.txDb,
                    activeColor = NinjaTxBlue,
                    modifier = Modifier.fillMaxHeight(),
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Right Side: Square / Rounded Rectangle PTT Button
            val pttBgColor by animateColorAsState(
                targetValue = when {
                    state.isTransmitting -> Color(0xFFD84315)
                    isReceiving -> NinjaRxGreen
                    else -> Color(0xFF20242D)
                },
                label = "ptt_bg",
            )
            val pttBorderColor by animateColorAsState(
                targetValue = when {
                    state.isTransmitting -> Color(0xFFFF5722)
                    isReceiving -> NinjaRxGreen.copy(alpha = 0.8f)
                    else -> Color(0xFF383E4D)
                },
                label = "ptt_border",
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(24.dp))
                    .border(5.dp, pttBorderColor, RoundedCornerShape(24.dp))
                    .background(pttBgColor)
                    .pointerInteropFilter { motionEvent ->
                        if (state.settings.pttMode == "Push-to-Talk") {
                            when (motionEvent.action) {
                                MotionEvent.ACTION_DOWN -> onPttPressed()
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onPttReleased()
                            }
                            true
                        } else false
                    }
                    .clickable {
                        if (state.settings.pttMode == "Latch") {
                            onPttPressed()
                        }
                    },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "PTT",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    val pttSub = if (state.settings.pttMode == "Latch") "TAP TO TALK" else "HOLD TO TALK"
                    Text(
                        text = pttSub,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        // --- Timer / TOT Panel ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(NinjaSurface)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // TX Elapsed Timer
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "↑ TX", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = NinjaTextMuted)
                val txMin = state.txElapsedSeconds / 60
                val txSec = state.txElapsedSeconds % 60
                val txFmt = String.format(Locale.US, "%02d:%02d", txMin, txSec)
                Text(text = txFmt, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = NinjaTextPrimary)
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.1f)),
            )

            // TOT Remaining Timer
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "↓ TOT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = NinjaTextMuted)
                val totMin = state.totRemainingSeconds / 60
                val totSec = state.totRemainingSeconds % 60
                val totFmt = String.format(Locale.US, "%02d:%02d", totMin, totSec)
                Text(
                    text = totFmt,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (state.isTransmitting) Color(0xFFFF9E80) else NinjaTextPrimary,
                )
            }
        }
    }
}
