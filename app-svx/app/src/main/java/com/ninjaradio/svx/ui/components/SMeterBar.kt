package com.ninjaradio.svx.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninjaradio.svx.ui.theme.NinjaSurface
import com.ninjaradio.svx.ui.theme.NinjaTextMuted
import com.ninjaradio.svx.ui.theme.NinjaTxRed
import java.util.Locale

@Composable
fun SMeterBar(
    label: String,
    levelPct: Int,
    dbValue: Double,
    activeColor: Color,
    totalSegments: Int = 16,
) {
    val animatedPct by animateFloatAsState(
        targetValue = (levelPct.coerceIn(0, 100) / 100f),
        animationSpec = tween(durationMillis = 80),
        label = "smeter_anim",
    )

    val activeCount = (animatedPct * totalSegments).toInt()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = activeColor,
            )
            val dbText = if (dbValue > -90.0) String.format(Locale.US, "%.1f dB", dbValue) else "— dB"
            Text(
                text = dbText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = NinjaTextMuted,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            for (i in 0 until totalSegments) {
                val isFilled = i < activeCount
                val blockColor = if (isFilled) {
                    if (i >= (totalSegments - 3)) NinjaTxRed else activeColor
                } else {
                    NinjaSurface
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(blockColor),
                )
            }
        }
    }
}

@Composable
fun VerticalSMeterBar(
    label: String,
    levelPct: Int,
    dbValue: Double,
    activeColor: Color,
    modifier: Modifier = Modifier,
    totalSegments: Int = 14,
) {
    val animatedPct by animateFloatAsState(
        targetValue = (levelPct.coerceIn(0, 100) / 100f),
        animationSpec = tween(durationMillis = 80),
        label = "v_smeter_anim",
    )

    val activeCount = (animatedPct * totalSegments).toInt()

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = activeColor,
        )

        // Vertical Segmented Stack (fills from bottom to top!)
        Column(
            modifier = Modifier
                .weight(1f)
                .width(22.dp)
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            for (i in (totalSegments - 1) downTo 0) {
                val isFilled = i < activeCount
                val blockColor = if (isFilled) {
                    if (i >= (totalSegments - 3)) NinjaTxRed else activeColor
                } else {
                    NinjaSurface
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(3.dp))
                        .background(blockColor),
                )
            }
        }

        val dbText = if (dbValue > -90.0) String.format(Locale.US, "%.0f", dbValue) else "—"
        Text(
            text = dbText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = NinjaTextMuted,
        )
    }
}
