package com.ninjaradio.svx.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.ninjaradio.svx.ui.theme.NinjaBackground
import com.ninjaradio.svx.ui.theme.NinjaPrimaryBlue
import com.ninjaradio.svx.ui.theme.NinjaSurface
import com.ninjaradio.svx.ui.theme.NinjaTextPrimary
import com.ninjaradio.svx.ui.theme.NinjaTextSecondary

@Composable
fun ChangeTgDialog(
    currentTg: Long,
    onDismiss: () -> Unit,
    onSelectTg: (Long) -> Unit,
) {
    var tgInput by remember { mutableStateOf(currentTg.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NinjaSurface,
        title = {
            Text(
                text = "Select Talkgroup",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = NinjaTextPrimary
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Enter Talkgroup ID:",
                    fontSize = 14.sp,
                    color = NinjaTextSecondary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = tgInput,
                    onValueChange = { tgInput = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NinjaPrimaryBlue,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = NinjaBackground,
                        unfocusedContainerColor = NinjaBackground,
                        focusedTextColor = NinjaTextPrimary,
                        unfocusedTextColor = NinjaTextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val tg = tgInput.toLongOrNull() ?: currentTg
                    onSelectTg(tg)
                },
                colors = ButtonDefaults.buttonColors(containerColor = NinjaPrimaryBlue),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Select", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = NinjaTextSecondary)
            }
        }
    )
}
