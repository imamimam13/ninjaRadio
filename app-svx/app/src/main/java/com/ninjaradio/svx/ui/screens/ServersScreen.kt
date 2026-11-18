package com.ninjaradio.svx.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Publish
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninjaradio.svx.MainUiState
import com.ninjaradio.svx.SvxReflectorClient
import com.ninjaradio.svx.data.ServerConfig
import com.ninjaradio.svx.ui.theme.NinjaBackground
import com.ninjaradio.svx.ui.theme.NinjaCardBorder
import com.ninjaradio.svx.ui.theme.NinjaRxGreen
import com.ninjaradio.svx.ui.theme.NinjaSurface
import com.ninjaradio.svx.ui.theme.NinjaTextMuted
import com.ninjaradio.svx.ui.theme.NinjaTextPrimary
import com.ninjaradio.svx.ui.theme.NinjaTextSecondary

@Composable
fun ServersScreen(
    state: MainUiState,
    onSelectServer: (ServerConfig) -> Unit,
    onToggleConnection: () -> Unit,
    onAddServer: () -> Unit,
    onEditServer: (ServerConfig) -> Unit,
    onDeleteServer: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NinjaBackground)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        // --- Header ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Servers",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = NinjaTextPrimary,
            )

            Row {
                IconButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.Default.Publish,
                        contentDescription = "Export Servers",
                        tint = NinjaTextPrimary,
                    )
                }
                IconButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Import Servers",
                        tint = NinjaTextPrimary,
                    )
                }
                IconButton(onClick = onAddServer) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Server",
                        tint = NinjaTextPrimary,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Server Cards List ---
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.servers, key = { it.id }) { server ->
                val isSelectedServer = server.id == state.activeServer.id
                val isConnected = isSelectedServer && (state.connectionState == SvxReflectorClient.State.CONNECTED)
                var dropdownExpanded by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(NinjaSurface)
                        .border(
                            width = 1.5.dp,
                            color = if (isConnected) NinjaRxGreen else NinjaCardBorder,
                            shape = RoundedCornerShape(18.dp),
                        )
                        .padding(18.dp),
                ) {
                    // Connected Status Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isConnected) NinjaRxGreen.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.08f),
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = if (isConnected) "● Connected" else "● Disconnected",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isConnected) NinjaRxGreen else NinjaTextMuted,
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Server Name
                    Text(
                        text = server.name.uppercase(),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = NinjaTextPrimary,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Badges Row (Callsign + Talkgroup)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Callsign Badge
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Icon(imageVector = Icons.Default.Radio, contentDescription = null, tint = Color(0xFFFF9E80), modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = server.callsign, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF9E80))
                        }

                        // Talkgroup Badge
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Icon(imageVector = Icons.Default.Group, contentDescription = null, tint = NinjaTextSecondary, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "Talkgroup ${server.defaultTg}", fontSize = 12.sp, color = NinjaTextSecondary)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Host:Port info
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Dns, contentDescription = null, tint = NinjaTextMuted, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "${server.host}:${server.port}", fontSize = 13.sp, color = NinjaTextMuted)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Action Button (Connect / Disconnect + Dropdown arrow)
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                if (!isSelectedServer) {
                                    onSelectServer(server)
                                } else {
                                    onToggleConnection()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isConnected) Color(0xFFE65100) else Color(0xFFFF9E80),
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (isConnected) Icons.Default.LinkOff else Icons.Default.Radio,
                                        contentDescription = null,
                                        tint = if (isConnected) Color.White else Color.Black,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isConnected) "Disconnect" else "Connect",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isConnected) Color.White else Color.Black,
                                    )
                                }

                                IconButton(
                                    onClick = { dropdownExpanded = true },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = "Options",
                                        tint = if (isConnected) Color.White else Color.Black,
                                    )
                                }
                            }
                        }

                        DropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false },
                            modifier = Modifier.background(NinjaSurface),
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(imageVector = Icons.Default.Edit, contentDescription = null, tint = NinjaTextPrimary, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text("Edit", color = NinjaTextPrimary)
                                    }
                                },
                                onClick = {
                                    onEditServer(server)
                                    dropdownExpanded = false
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(imageVector = Icons.Default.Bookmark, contentDescription = null, tint = NinjaTextPrimary, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text("Talkgroups", color = NinjaTextPrimary)
                                    }
                                },
                                onClick = {
                                    onEditServer(server)
                                    dropdownExpanded = false
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(imageVector = Icons.Default.Delete, contentDescription = null, tint = Color(0xFFFF5722), modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text("Delete", color = Color(0xFFFF5722))
                                    }
                                },
                                onClick = {
                                    onDeleteServer(server.id)
                                    dropdownExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
