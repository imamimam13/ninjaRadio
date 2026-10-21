package com.ninjadmr.app.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Driving screen: one huge hold-to-talk pad, minimum chrome, screen kept on.
 * Tap and hold anywhere on the pad to transmit; release to stop.
 */
@Composable
fun CarModeScreen(
    talkgroup: String,
    callsign: String,
    sessionState: String,
    linkState: String,
    rxCount: Int,
    txCount: Int,
    transmitting: Boolean,
    connected: Boolean,
    onPttDown: () -> Unit,
    onPttUp: () -> Unit,
    onExit: () -> Unit,
) {
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Box(Modifier.fillMaxSize().background(Holo.bgDeep)) {
        HoloHudBackground(Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "CAR MODE",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            letterSpacing = 6.sp,
                            color = Holo.textPrimary,
                        ),
                    )
                    Text(
                        "$callsign · TG $talkgroup",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                HoloBadge(sessionState, stateColour(sessionState))
            }

            Spacer(Modifier.height(18.dp))

            PttPad(
                transmitting = transmitting,
                enabled = connected,
                onDown = onPttDown,
                onUp = onPttUp,
                modifier = Modifier.weight(1f),
            )

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                HoloGauge(label = "RX", value = rxCount, color = Holo.cyan)
                HoloGauge(label = "TX", value = txCount, color = Holo.magenta)
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = when {
                    transmitting -> "TRANSMITTING — release to stop"
                    !connected -> "LINK DOWN — open NinjaDMR to connect"
                    else -> "HOLD THE PAD TO TALK"
                },
                color = when {
                    transmitting -> Holo.magenta
                    !connected -> Holo.red
                    else -> Holo.textDim
                },
                textAlign = TextAlign.Center,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    letterSpacing = 2.sp,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(14.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Holo.panelRaised)
                    .border(1.dp, Holo.brass, RoundedCornerShape(10.dp))
                    .clickable { onExit() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "EXIT CAR MODE",
                    color = Holo.brassBright,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        letterSpacing = 2.sp,
                    ),
                )
            }
        }
    }
}

@Composable
private fun PttPad(
    transmitting: Boolean,
    enabled: Boolean,
    onDown: () -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val glow = if (transmitting) Holo.magenta else Holo.cyan
    val ring = if (transmitting) Holo.magenta else Holo.cyan
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = if (transmitting) listOf(Holo.magentaDim, Holo.bgDeep)
                    else listOf(Holo.panelRaised, Holo.bgDeep),
                ),
            )
            .border(width = if (transmitting) 4.dp else 2.dp, color = if (enabled) ring else Holo.panelEdge, shape = CircleShape)
            .drawBehind {
                if (enabled) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(glow.copy(alpha = if (transmitting) 0.55f else 0.18f), Color.Transparent),
                            radius = size.minDimension * 0.8f,
                            center = center,
                        ),
                        radius = size.minDimension * 0.8f,
                        center = center,
                    )
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        onDown()
                        try {
                            tryAwaitRelease()
                        } finally {
                            onUp()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (transmitting) "ON AIR" else "PTT",
                color = if (enabled) Holo.textPrimary else Holo.textFaint,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 42.sp,
                    letterSpacing = 8.sp,
                ),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (enabled) "HOLD TO TALK" else "STANDBY",
                color = if (transmitting) Holo.magenta else Holo.textDim,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    letterSpacing = 3.sp,
                ),
            )
        }
    }
}

private fun stateColour(state: String): Color = when (state.uppercase()) {
    "CONNECTED" -> Holo.green
    "ERROR", "FAILED" -> Holo.red
    "IDLE", "" -> Holo.textDim
    else -> Holo.cyan
}
