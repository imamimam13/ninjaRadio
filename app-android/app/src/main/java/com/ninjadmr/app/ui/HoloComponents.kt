package com.ninjadmr.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** HUD field: dark base with a slow cyan scanline over a faint grid. */
@Composable
fun HoloHudBackground(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "holo")
    val scan by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart),
        label = "scan",
    )
    Canvas(modifier.fillMaxSize()) {
        val step = 26.dp.toPx()
        val grid = Holo.cyan.copy(alpha = 0.045f)
        var x = 0f
        while (x < size.width) {
            drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1f)
            x += step
        }
        var y = 0f
        while (y < size.height) {
            drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
            y += step
        }
        val band = 44.dp.toPx()
        val top = size.height * scan - band
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Holo.cyan.copy(alpha = 0f),
                    Holo.cyan.copy(alpha = 0.10f),
                    Holo.cyan.copy(alpha = 0f),
                ),
                startY = top,
                endY = top + band * 2,
            ),
            topLeft = Offset(0f, top),
            size = Size(size.width, band * 2),
        )
    }
}

/** Brass-edged panel with corner rivets. */
@Composable
fun HoloPanel(
    modifier: Modifier = Modifier,
    accent: Color = Holo.brass,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(modifier = modifier) {
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Holo.panelRaised, Holo.panel)))
                .border(1.dp, accent.copy(alpha = 0.55f), shape),
        )
        Canvas(Modifier.matchParentSize()) {
            val inset = 8.dp.toPx()
            val dot = 3.2.dp.toPx()
            listOf(
                Offset(inset, inset),
                Offset(size.width - inset, inset),
                Offset(inset, size.height - inset),
                Offset(size.width - inset, size.height - inset),
            ).forEach { p ->
                drawCircle(Holo.brassBright.copy(alpha = 0.85f), radius = dot, center = p)
                drawCircle(Holo.brassDim, radius = dot * 0.45f, center = p)
            }
        }
        Column(Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun HoloSectionTitle(text: String, accent: Color = Holo.brassBright, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = accent,
        style = MaterialTheme.typography.titleMedium,
        letterSpacing = 2.sp,
        modifier = modifier,
    )
}

@Composable
fun HoloBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier
            .clip(shape)
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color, shape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text.uppercase(),
            color = color,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.4.sp,
            ),
        )
    }
}

/** Small arc gauge: value over max, neon sweep. */
@Composable
fun HoloGauge(
    label: String,
    value: Int,
    max: Int = 24,
    color: Color = Holo.cyan,
    modifier: Modifier = Modifier,
) {
    val fraction = if (max <= 0) 0f else (value.toFloat() / max).coerceIn(0f, 1f)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(52.dp)) {
            val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2f
            val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
            drawArc(
                color = Holo.panelEdge,
                startAngle = 140f,
                sweepAngle = 260f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = stroke,
            )
            drawArc(
                color = color,
                startAngle = 140f,
                sweepAngle = 260f * fraction,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = stroke,
            )
        }
        Text(
            text = value.toString(),
            color = color,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            ),
        )
        Text(text = label.uppercase(), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun HoloField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label.uppercase()) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Holo.cyan,
            unfocusedBorderColor = Holo.panelEdge,
            focusedTextColor = Holo.textPrimary,
            unfocusedTextColor = Holo.textPrimary,
            focusedLabelColor = Holo.cyan,
            unfocusedLabelColor = Holo.textDim,
            cursorColor = Holo.cyan,
            focusedContainerColor = Holo.panelRaised,
            unfocusedContainerColor = Holo.panel,
        ),
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
        modifier = modifier,
    )
}

@Composable
fun HoloTerminal(text: String, modifier: Modifier = Modifier, color: Color = Holo.cyan) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Holo.bgDeep)
            .border(1.dp, Holo.panelEdge, RoundedCornerShape(6.dp))
            .padding(10.dp),
    ) {
        Text(
            text = text,
            color = color,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            ),
        )
    }
}

@Composable
fun HoloStatusRow(label: String, value: String, modifier: Modifier = Modifier, accent: Color = Holo.textPrimary) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            text = value,
            color = accent,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                letterSpacing = 0.5.sp,
            ),
        )
    }
}
