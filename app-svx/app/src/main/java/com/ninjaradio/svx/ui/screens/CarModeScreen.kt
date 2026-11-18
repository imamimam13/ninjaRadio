package com.ninjaradio.svx.ui.screens

import android.view.MotionEvent
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninjaradio.svx.MainUiState
import com.ninjaradio.svx.ui.components.SMeterBar
import com.ninjaradio.svx.ui.theme.NinjaBackground
import com.ninjaradio.svx.ui.theme.NinjaRxGreen
import com.ninjaradio.svx.ui.theme.NinjaSurface
import com.ninjaradio.svx.ui.theme.NinjaTextMuted
import com.ninjaradio.svx.ui.theme.NinjaTextPrimary
import com.ninjaradio.svx.ui.theme.NinjaTextSecondary
import com.ninjaradio.svx.ui.theme.NinjaTxBlue
import com.ninjaradio.svx.ui.theme.NinjaTxRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CarModeScreen(
    state: MainUiState,
    onCloseCarMode: () -> Unit,
    onSelectTg: (Long) -> Unit,
    onPttPressed: () -> Unit,
    onPttReleased: () -> Unit,
) {
    val activeTgConfig = state.activeServer.talkgroups.find { it.tg == state.currentTg }
    val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
    val currentTimeStr = timeFmt.format(Date())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NinjaBackground)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // --- Top Bar ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "NET", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFB74D))
                Text(text = "SCAN", fontSize = 13.sp, color = NinjaTextMuted)
                Text(text = "VOX", fontSize = 13.sp, color = NinjaTextMuted)
                Text(text = "APRS", fontSize = 13.sp, color = NinjaTextMuted)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = currentTimeStr, fontSize = 14.sp, color = NinjaTextMuted)
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onCloseCarMode) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Exit Car Mode",
                        tint = NinjaTextMuted,
                    )
                }
            }
        }

        // --- MONITORING ---
        Text(
            text = "MONITORING",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = NinjaTextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )

        // --- CHANNEL SECTION ---
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Text(text = "CHANNEL", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = NinjaTextMuted)
            Text(
                text = state.currentTg.toString(),
                fontSize = 54.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFB0BEC5),
                letterSpacing = 2.sp,
            )
            Text(
                text = activeTgConfig?.label ?: "Talkgroup",
                fontSize = 18.sp,
                color = NinjaTextSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        // --- ACTIVITY SECTION (Segmented S-Meters) ---
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "ACTIVITY", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = NinjaTextMuted)

            SMeterBar(
                label = "RX",
                levelPct = state.rxLevelPct,
                dbValue = state.rxDb,
                activeColor = NinjaRxGreen,
            )

            SMeterBar(
                label = "MIC",
                levelPct = state.txLevelPct,
                dbValue = state.txDb,
                activeColor = NinjaTxBlue,
            )
        }

        // --- CHANNELS SWITCHER ---
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "CHANNELS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = NinjaTextMuted,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.activeServer.talkgroups) { tgConfig ->
                    val isSelected = tgConfig.tg == state.currentTg

                    Box(
                        modifier = Modifier
                            .width(130.dp)
                            .height(76.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(NinjaSurface)
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) Color(0xFFFFB74D) else Color.Transparent,
                                shape = RoundedCornerShape(12.dp),
                            )
                            .clickable { onSelectTg(tgConfig.tg) }
                            .padding(12.dp),
                    ) {
                        Column {
                            Text(
                                text = tgConfig.tg.toString(),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Color(0xFFFFB74D) else NinjaTextPrimary,
                            )
                            Text(
                                text = tgConfig.label,
                                fontSize = 12.sp,
                                color = NinjaTextSecondary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }

        // --- GIANT CAR MODE PTT BUTTON ---
        val pttBgColor by animateColorAsState(
            targetValue = when {
                state.isTransmitting -> NinjaTxRed
                state.activeTalker != null -> NinjaRxGreen
                else -> Color(0xFF282422)
            },
            label = "car_ptt_bg",
        )
        val pttBorderColor by animateColorAsState(
            targetValue = when {
                state.isTransmitting -> NinjaTxRed
                state.activeTalker != null -> NinjaRxGreen
                else -> Color(0xFF4A3B34)
            },
            label = "car_ptt_border",
        )

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(2.dp, pttBorderColor, RoundedCornerShape(20.dp))
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
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    text = if (state.settings.pttMode == "Latch") "TAP TO TALK" else "HOLD TO TALK",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
