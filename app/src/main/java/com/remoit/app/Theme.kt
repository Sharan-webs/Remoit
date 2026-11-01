package com.remoit.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin

object C {
    val Bg = Color(0xFF07080C)
    val Surface = Color(0xFF0F1219)
    val Surface2 = Color(0xFF161B26)
    val Border = Color(0xFF242B3B)
    val Text = Color(0xFFEEF0F6)
    val Muted = Color(0xFF8E97AD)
    val Accent = Color(0xFF7C6CFF)
    val Accent2 = Color(0xFF29D3F0)
    val Good = Color(0xFF34D399)
    val Danger = Color(0xFFF87171)
    val Warn = Color(0xFFFBBF24)
}

val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_medium, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold)
)
val Mono = FontFamily.Monospace

fun ts(size: Int, weight: FontWeight = FontWeight.Normal, color: Color = C.Text, family: FontFamily = Poppins, spacing: Float = 0f, line: Int = 0): TextStyle =
    TextStyle(
        fontSize = size.sp, fontWeight = weight, color = color, fontFamily = family,
        letterSpacing = spacing.sp,
        lineHeight = if (line > 0) line.sp else TextUnit.Unspecified
    )

@Composable
fun PxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Accent, background = C.Bg, surface = C.Surface,
            onSurface = C.Text, onBackground = C.Text, onPrimary = Color.White
        ),
        content = content
    )
}

@Composable
fun PxButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, secondary: Boolean = false, danger: Boolean = false
) {
    val shape = RoundedCornerShape(16.dp)
    val brush = when {
        !enabled || secondary -> Brush.linearGradient(listOf(C.Surface2, C.Surface2))
        danger -> Brush.linearGradient(listOf(Color(0xFFE5484D), Color(0xFFC93A6B)))
        else -> Brush.linearGradient(listOf(C.Accent, Color(0xFF4F7CFF)))
    }
    Box(
        modifier
            .height(56.dp)
            .clip(shape)
            .background(brush)
            .border(BorderStroke(1.dp, if (secondary || !enabled) C.Border else Color.Transparent), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = ts(15, FontWeight.SemiBold, if (enabled) Color.White else C.Muted, spacing = 0.3f))
    }
}

@Composable
fun PxCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .clip(shape)
            .background(C.Surface)
            .border(1.dp, C.Border, shape)
            .padding(18.dp)
    ) { content() }
}

/** Animated field of pixel blocks: used on the boot, home and processing screens. */
@Composable
fun PixelField(modifier: Modifier = Modifier, periodMs: Int = 3600, cell: Dp = 14.dp, strength: Float = 0.55f) {
    val tr = rememberInfiniteTransition(label = "pixelfield")
    val ph by tr.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(periodMs, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "phase"
    )
    Canvas(modifier) {
        val cs = cell.toPx()
        val cols = (size.width / cs).toInt() + 1
        val rows = (size.height / cs).toInt() + 1
        for (i in 0 until cols) {
            for (j in 0 until rows) {
                val d = i * 0.6f + j * 0.9f
                val wv = (sin((ph * 2f - d * 0.07f) * 6.2831855f) + 1f) / 2f
                val a = wv * wv * strength
                if (a > 0.03f) {
                    drawRect(
                        color = lerp(C.Accent, C.Accent2, i.toFloat() / cols).copy(alpha = a),
                        topLeft = Offset(i * cs + 1f, j * cs + 1f),
                        size = Size(cs - 2f, cs - 2f)
                    )
                }
            }
        }
    }
}

@Composable
fun Logo(size: Int = 22) {
    val tr = rememberInfiniteTransition(label = "logo")
    val ph by tr.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(2400, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "logoPhase"
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (r in 0 until 3) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (c in 0 until 3) {
                        val k = (r + c) / 4f
                        val wave = (sin((ph - k) * 6.2831855f) + 1f) / 2f
                        val col = lerp(C.Accent, C.Accent2, c / 2f).copy(alpha = 0.22f + 0.78f * wave)
                        Box(Modifier.size((size / 3.2f).dp).clip(RoundedCornerShape(2.dp)).background(col))
                    }
                }
            }
        }
        Row {
            Text("remo", style = ts(size, FontWeight.Bold, C.Text, spacing = 1f))
            Text("it", style = ts(size, FontWeight.Bold, C.Accent2, spacing = 1f))
        }
    }
}

@Composable
fun Pill(text: String, color: Color = C.Accent, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) { Text(text, style = ts(11, FontWeight.Medium, color, Mono, 0.8f)) }
}

@Composable
fun Gap(h: Int) { Spacer(Modifier.height(h.dp)) }
