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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.BoxScope
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

/** Ethereal ambient background: deep obsidian base with soft atmospheric aurora glow. */
@Composable
fun HoloHudBackground(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        // Deep midnight canvas
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF080C14),
                    Color(0xFF0D1424),
                    Color(0xFF090E1A),
                )
            )
        )

        // Subtle ambient atmospheric glow (top-right cyan/indigo bloom)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF38BDF8).copy(alpha = 0.07f),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.85f, size.height * 0.12f),
                radius = size.width * 0.8f,
            ),
            center = Offset(size.width * 0.85f, size.height * 0.12f),
            radius = size.width * 0.8f,
        )

        // Subtle ambient atmospheric glow (center-left soft violet bloom)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFA78BFA).copy(alpha = 0.045f),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.10f, size.height * 0.50f),
                radius = size.width * 0.7f,
            ),
            center = Offset(size.width * 0.10f, size.height * 0.50f),
            radius = size.width * 0.7f,
        )

        // Subtle ambient atmospheric glow (bottom-right mint/teal bloom)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF34D399).copy(alpha = 0.05f),
                    Color.Transparent,
                ),
                center = Offset(size.width * 0.80f, size.height * 0.85f),
                radius = size.width * 0.75f,
            ),
            center = Offset(size.width * 0.80f, size.height * 0.85f),
            radius = size.width * 0.75f,
        )

        // Ultra-faint calm aesthetic grid (barely visible texture, zero eye fatigue)
        val step = 32.dp.toPx()
        val gridColor = Color(0xFF38BDF8).copy(alpha = 0.016f)
        var x = 0f
        while (x < size.width) {
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1f)
            x += step
        }
        var y = 0f
        while (y < size.height) {
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
            y += step
        }
    }
}

/** Frosted glassmorphism panel with soft ambient gradient edge. */
@Composable
fun HoloPanel(
    modifier: Modifier = Modifier,
    accent: Color = Holo.brass,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(modifier = modifier) {
        // Frosted glass background with subtle top-to-bottom depth
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xE6141D2E),
                            Color(0xCC0E1624),
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.18f),
                            accent.copy(alpha = 0.40f),
                            Holo.panelEdge.copy(alpha = 0.30f),
                        )
                    ),
                    shape = shape,
                ),
        )
        // Subtle top specular highlight shimmer
        Canvas(Modifier.matchParentSize()) {
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.02f),
                        Color.White.copy(alpha = 0.16f),
                        Color.White.copy(alpha = 0.02f),
                    )
                ),
                start = Offset(16.dp.toPx(), 1.dp.toPx()),
                end = Offset(size.width - 16.dp.toPx(), 1.dp.toPx()),
                strokeWidth = 1.2f,
            )
        }
        Column(Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun HoloSectionTitle(text: String, modifier: Modifier = Modifier, accent: Color = Holo.brassBright) {
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
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .clip(shape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.45f), shape)
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
        shape = RoundedCornerShape(10.dp),
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
            focusedContainerColor = Holo.panelRaised.copy(alpha = 0.75f),
            unfocusedContainerColor = Holo.panel.copy(alpha = 0.55f),
        ),
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
        modifier = modifier,
    )
}

@Composable
fun HoloTerminal(text: String, modifier: Modifier = Modifier, color: Color = Holo.cyan) {
    val scrollState = rememberScrollState()
    LaunchedEffect(text) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Holo.bgDeep)
            .border(1.dp, Holo.panelEdge, RoundedCornerShape(6.dp))
            .height(160.dp)
            .verticalScroll(scrollState)
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
    Row(
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
