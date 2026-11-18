package com.ninjaradio.svx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninjaradio.svx.data.TalkgroupConfig
import com.ninjaradio.svx.ui.theme.NinjaBackground
import com.ninjaradio.svx.ui.theme.NinjaPrimaryBlue
import com.ninjaradio.svx.ui.theme.NinjaSurface
import com.ninjaradio.svx.ui.theme.NinjaTextPrimary
import com.ninjaradio.svx.ui.theme.NinjaTextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTalkgroupDialog(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onSave: (TalkgroupConfig) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var tgInput by remember { mutableStateOf("") }

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
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text(
                text = "Add Talkgroup",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = NinjaTextPrimary,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label", color = NinjaTextSecondary) },
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

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = tgInput,
                onValueChange = { tgInput = it },
                label = { Text("Talkgroup", color = NinjaTextSecondary) },
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

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    val tgNumber = tgInput.toLongOrNull() ?: 1025L
                    val newTg = TalkgroupConfig(
                        label = label.ifBlank { "TG $tgNumber" },
                        tg = tgNumber,
                    )
                    onSave(newTg)
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

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
