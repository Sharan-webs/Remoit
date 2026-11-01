package com.remoit.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.ClickableText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun openUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) { }
}

/** Text in which every "@sharan.in__" is a tappable link to the owner's Instagram page. */
@Composable
fun LinkText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val annotated = remember(text) {
        buildAnnotatedString {
            var i = 0
            while (i < text.length) {
                val j = text.indexOf(Texts.OWNER_IG, i)
                if (j < 0) { append(text.substring(i)); break }
                append(text.substring(i, j))
                pushStringAnnotation("url", Texts.IG_URL)
                withStyle(SpanStyle(color = C.Accent2, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.SemiBold)) {
                    append(Texts.OWNER_IG)
                }
                pop()
                i = j + Texts.OWNER_IG.length
            }
        }
    }
    ClickableText(text = annotated, modifier = modifier, style = style) { off ->
        annotated.getStringAnnotations("url", off, off).firstOrNull()?.let { openUrl(ctx, it.item) }
    }
}

/**
 * Animated app mark: a video frame with three lines of "text" that a light sweep erases, again and again.
 */
@Composable
fun AnimatedMark(size: Dp = 120.dp, modifier: Modifier = Modifier) {
    val tr = rememberInfiniteTransition(label = "mark")
    val t by tr.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(2600, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "markT"
    )
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val fx = w * 0.06f
        val fy = h * 0.2f
        val fw = w * 0.88f
        val fh = h * 0.6f
        drawRoundRect(
            color = C.Accent, topLeft = Offset(fx, fy), size = Size(fw, fh),
            cornerRadius = CornerRadius(w * 0.07f), style = Stroke(width = w * 0.035f)
        )
        // the sweep: 0 -> 1 crosses the frame, then rests a moment
        val prog = (t / 0.72f).coerceIn(0f, 1f)
        val sweepX = fx - fw * 0.15f + (fw * 1.3f) * prog
        val lineH = fh * 0.1f
        val lines = listOf(0.30f to 0.72f, 0.48f to 0.54f, 0.66f to 0.34f)
        for ((yy, len) in lines) {
            val ly = fy + fh * yy
            val lx = fx + fw * 0.12f
            val lw = fw * len
            // erased part is everything left of the sweep
            clipRect(left = sweepX, top = 0f, right = w, bottom = h) {
                drawRoundRect(
                    color = Color(0xFFEEF0F6), topLeft = Offset(lx, ly - lineH / 2f), size = Size(lw, lineH),
                    cornerRadius = CornerRadius(lineH / 2f)
                )
            }
        }
        if (prog < 1f) {
            clipRect(left = fx, top = fy, right = fx + fw, bottom = fy + fh) {
                drawLine(
                    color = C.Accent2.copy(alpha = 0.9f), start = Offset(sweepX + fh * 0.18f, fy), end = Offset(sweepX - fh * 0.18f, fy + fh),
                    strokeWidth = w * 0.07f, cap = StrokeCap.Butt
                )
            }
        }
    }
}

