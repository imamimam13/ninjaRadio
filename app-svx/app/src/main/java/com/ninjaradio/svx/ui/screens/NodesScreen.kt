package com.ninjaradio.svx.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninjaradio.svx.MainUiState
import com.ninjaradio.svx.ui.theme.NinjaBackground
import com.ninjaradio.svx.ui.theme.NinjaNavActive
import com.ninjaradio.svx.ui.theme.NinjaRxGreen
import com.ninjaradio.svx.ui.theme.NinjaSurface
import com.ninjaradio.svx.ui.theme.NinjaTextMuted
import com.ninjaradio.svx.ui.theme.NinjaTextPrimary
import com.ninjaradio.svx.ui.theme.NinjaTextSecondary

@Composable
fun NodesScreen(
    state: MainUiState,
    onOpenChangeTg: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NinjaBackground)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Nodes",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = NinjaTextPrimary
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(NinjaNavActive)
                    .clickable { onOpenChangeTg() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Talkgroup ${state.currentTg}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NinjaTextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "${state.nodes.size} Connected",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = NinjaTextMuted,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        if (state.nodes.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(NinjaSurface)
            ) {
                Text(text = "No nodes connected", color = NinjaTextMuted, fontSize = 14.sp)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(NinjaSurface)
            ) {
                state.nodes.forEachIndexed { index, nodeCallsign ->
                    val isTalking = nodeCallsign.equals(state.activeTalker, ignoreCase = true)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = nodeCallsign,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isTalking) NinjaRxGreen else NinjaTextPrimary
                        )

                        if (isTalking) {
                            Text(
                                text = "TX",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = NinjaRxGreen,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(NinjaRxGreen.copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (index < (state.nodes.size - 1)) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                    }
                }
            }
        }
    }
}
