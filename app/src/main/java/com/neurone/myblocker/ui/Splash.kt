package com.neurone.myblocker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Launch splash: the umbrella pops open, three "ads" fall in and bounce off,
 * the wordmark fades in, and the whole thing dissolves into the dashboard.
 * Tap to skip. Roughly 1.6 s end to end; the brand then stays out of the way.
 */
@Composable
fun SplashOverlay(onDone: () -> Unit) {
    val open = remember { Animatable(0f) }
    val rain = remember { Animatable(0f) }
    val wordmark = remember { Animatable(0f) }
    var visible by remember { mutableStateOf(true) }
    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        launch { open.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) }
        delay(250)
        launch { rain.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
        delay(350)
        launch { wordmark.animateTo(1f, tween(450)) }
        delay(1000)
        visible = false
        delay(320)
        finished = true
    }
    LaunchedEffect(finished) { if (finished) onDone() }

    AnimatedVisibility(visible = visible, exit = fadeOut(tween(300))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brand.SplashGradient)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { visible = false },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.size(220.dp, 200.dp)) {
                    drawUmbrella(open.value, rain.value)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Adbrella",
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.alpha(wordmark.value),
                )
                Text(
                    "Keeps the ads off you.",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.alpha(wordmark.value),
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawUmbrella(open: Float, rain: Float) {
    val w = size.width
    val h = size.height
    val cx = w / 2
    val topY = h * 0.18f
    val rimY = h * 0.56f
    val halfSpan = w * 0.42f
    val white = Color.White

    // Stem and hook are always there; the canopy opens around the stem.
    val stem = Path().apply {
        moveTo(cx, rimY - 4f)
        lineTo(cx, h * 0.82f)
        arcTo(Rect(Offset(cx - w * 0.09f, h * 0.74f), Size(w * 0.09f, h * 0.16f)), 0f, 180f, false)
    }
    drawPath(stem, white, style = Stroke(width = w * 0.035f, cap = StrokeCap.Round))
    drawCircle(white, radius = w * 0.02f, center = Offset(cx, topY - w * 0.02f))

    // Canopy: wide arc plus six scallops, scaled horizontally by [open] around the stem.
    val scaleX = 0.12f + 0.88f * open.coerceIn(0f, 1.15f)
    scale(scaleX = scaleX, scaleY = 1f, pivot = Offset(cx, rimY)) {
        val canopy = Path().apply {
            moveTo(cx - halfSpan, rimY)
            arcTo(Rect(Offset(cx - halfSpan, topY), Size(halfSpan * 2, (rimY - topY) * 2)), 180f, 180f, false)
            val scallop = halfSpan * 2 / 6
            for (i in 6 downTo 1) {
                val x1 = cx - halfSpan + scallop * i
                arcTo(Rect(Offset(x1 - scallop, rimY - scallop * 0.36f), Size(scallop, scallop * 0.72f)), 0f, 180f, false)
            }
            close()
        }
        drawPath(canopy, white)
        val ribs = Path().apply {
            moveTo(cx, topY); lineTo(cx - halfSpan * 0.5f, rimY)
            moveTo(cx, topY); lineTo(cx + halfSpan * 0.5f, rimY)
        }
        drawPath(ribs, Color.Black.copy(alpha = 0.08f), style = Stroke(width = 2f))
    }

    // Three drops: fall from the top, meet the canopy, slide off sideways and fade.
    if (rain > 0f) {
        val drops = listOf(-0.6f to 0f, 0.05f to 0.22f, 0.65f to 0.44f)
        for ((rel, delay) in drops) {
            val t = ((rain - delay) / (1f - delay)).coerceIn(0f, 1f)
            if (t <= 0f) continue
            val startX = cx + halfSpan * rel
            val canopyY = topY + (rimY - topY) * (1f - (1f - kotlin.math.abs(rel)) * 0.85f)
            val x: Float
            val y: Float
            val alpha: Float
            if (t < 0.55f) {
                val k = t / 0.55f
                x = startX
                y = -w * 0.05f + (canopyY - w * 0.06f + w * 0.05f) * k
                alpha = 0.9f
            } else {
                val k = (t - 0.55f) / 0.45f
                val dir = if (rel < 0f) -1f else 1f
                x = startX + dir * halfSpan * 0.6f * k
                y = canopyY - w * 0.06f + h * 0.55f * k * k
                alpha = 0.9f * (1f - k)
            }
            drawCircle(white.copy(alpha = alpha), radius = w * 0.028f, center = Offset(x, y))
        }
    }
}
