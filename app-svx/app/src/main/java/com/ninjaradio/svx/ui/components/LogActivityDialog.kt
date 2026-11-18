package com.ninjaradio.svx.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninjaradio.svx.ui.theme.NinjaSurface
import com.ninjaradio.svx.ui.theme.NinjaTextMuted
import com.ninjaradio.svx.ui.theme.NinjaTextPrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogActivityDialog(
    logs: List<String>,
    sheetState: SheetState,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NinjaSurface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .background(Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = Color(0xFF81C784),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Activity Logs",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = NinjaTextPrimary,
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = NinjaTextMuted,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Console Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF0D1117), RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                if (logs.isEmpty()) {
                    Text(
                        text = "No log activity recorded yet.",
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = NinjaTextMuted,
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(logs) { line ->
                            val color = when {
                                line.contains("error", ignoreCase = true) || line.contains("failed", ignoreCase = true) -> Color(0xFFFF5252)
                                line.contains("OK") || line.contains("connected", ignoreCase = true) -> Color(0xFF81C784)
                                line.contains("SelectTG") || line.contains("TX") -> Color(0xFFFFB74D)
                                else -> Color(0xFFC9D1D9)
                            }

                            Text(
                                text = line,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = color,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
