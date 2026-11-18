package com.ninjaradio.svx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninjaradio.svx.data.ServerConfig
import com.ninjaradio.svx.ui.theme.NinjaBackground
import com.ninjaradio.svx.ui.theme.NinjaPrimaryBlue
import com.ninjaradio.svx.ui.theme.NinjaSurface
import com.ninjaradio.svx.ui.theme.NinjaTextMuted
import com.ninjaradio.svx.ui.theme.NinjaTextPrimary
import com.ninjaradio.svx.ui.theme.NinjaTextSecondary
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditServerSheet(
    sheetState: SheetState,
    serverToEdit: ServerConfig?,
    onDismiss: () -> Unit,
    onOpenAddTalkgroup: () -> Unit,
    onSave: (ServerConfig) -> Unit,
) {
    var name by remember(serverToEdit) { mutableStateOf(serverToEdit?.name ?: "") }
    var host by remember(serverToEdit) { mutableStateOf(serverToEdit?.host ?: "") }
    var port by remember(serverToEdit) { mutableStateOf(serverToEdit?.port?.toString() ?: "5300") }
    var callsign by remember(serverToEdit) { mutableStateOf(serverToEdit?.callsign ?: "") }
    var password by remember(serverToEdit) { mutableStateOf(serverToEdit?.password ?: "") }
    var talkgroups by remember(serverToEdit?.talkgroups) { mutableStateOf(serverToEdit?.talkgroups ?: emptyList()) }
    var followQsy by remember(serverToEdit) { mutableStateOf(serverToEdit?.followQsy ?: true) }
    var passwordVisible by remember { mutableStateOf(false) }

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
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = if (serverToEdit?.name.isNullOrEmpty()) "Add Server" else "Edit Server",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = NinjaTextPrimary,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            VoxTextField(label = "Friendly Name", value = name, onValueChange = { name = it })
            Spacer(modifier = Modifier.height(10.dp))

            VoxTextField(label = "Host", value = host, onValueChange = { host = it })
            Spacer(modifier = Modifier.height(10.dp))

            VoxTextField(label = "Port", value = port, onValueChange = { port = it })
            Spacer(modifier = Modifier.height(10.dp))

            VoxTextField(label = "Callsign", value = callsign, onValueChange = { callsign = it })
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password", color = NinjaTextSecondary) },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle password visibility",
                            tint = NinjaTextSecondary,
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NinjaPrimaryBlue,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = NinjaBackground,
                    unfocusedContainerColor = NinjaBackground,
                    focusedTextColor = NinjaTextPrimary,
                    unfocusedTextColor = NinjaTextPrimary,
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(20.dp))

            // --- Talkgroups Section ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Talkgroups",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = NinjaTextPrimary,
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onOpenAddTalkgroup() },
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Talkgroup",
                        tint = Color(0xFFFF9E80),
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text(
                        text = "Add Talkgroup",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFFF9E80),
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (talkgroups.isEmpty()) {
                Text(
                    text = "No talkgroups yet",
                    fontSize = 14.sp,
                    color = NinjaTextMuted,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                talkgroups.forEach { tgItem ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.DragHandle,
                                contentDescription = null,
                                tint = NinjaTextMuted,
                                modifier = Modifier.padding(end = 12.dp),
                            )
                            Column {
                                Text(
                                    text = tgItem.label,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NinjaTextPrimary,
                                )
                                Text(
                                    text = "TG ${tgItem.tg}",
                                    fontSize = 12.sp,
                                    color = NinjaTextMuted,
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {}) {
                                Icon(imageVector = Icons.Default.Headphones, contentDescription = null, tint = NinjaTextMuted)
                            }
                            IconButton(onClick = {}) {
                                Icon(imageVector = Icons.Default.PinDrop, contentDescription = null, tint = NinjaTextMuted)
                            }
                            IconButton(onClick = {
                                talkgroups = talkgroups.filterNot { it.id == tgItem.id }
                            }) {
                                Icon(imageVector = Icons.Default.Delete, contentDescription = null, tint = NinjaTextMuted)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // --- Follow server talkgroup moves (QSY) ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Follow server talkgroup moves (QSY)",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = NinjaTextPrimary,
                    )
                    Text(
                        text = "Let the reflector move you off an idle talkgroup.",
                        fontSize = 13.sp,
                        color = NinjaTextMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                Switch(
                    checked = followQsy,
                    onCheckedChange = { followQsy = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFFFF9E80),
                    ),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    val p = port.toIntOrNull() ?: 5300
                    val defaultTgNum = talkgroups.firstOrNull()?.tg ?: 1025L
                    val config = ServerConfig(
                        id = serverToEdit?.id ?: UUID.randomUUID().toString(),
                        name = name.ifBlank { "Server" },
                        host = host.trim(),
                        port = p,
                        callsign = callsign.trim().uppercase(),
                        password = password,
                        defaultTg = defaultTgNum,
                        talkgroups = talkgroups,
                        followQsy = followQsy,
                    )
                    onSave(config)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9E80)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                Text(
                    text = "Save",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                )
            }
            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}

@Composable
private fun VoxTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = NinjaTextSecondary) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = NinjaPrimaryBlue,
            unfocusedBorderColor = Color.Transparent,
            focusedContainerColor = NinjaBackground,
            unfocusedContainerColor = NinjaBackground,
            focusedTextColor = NinjaTextPrimary,
            unfocusedTextColor = NinjaTextPrimary,
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}
