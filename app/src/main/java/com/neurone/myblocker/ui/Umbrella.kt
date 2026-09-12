package com.neurone.myblocker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The umbrella, drawn canopy up and handle down. [open] runs from 0 (furled: the ribs hug the
 * shaft) to 1 (a full dome with a scalloped hem); animate it to make the umbrella pop open
 * when protection turns on. Drawn on a canvas so it fills the same box at every state and the
 * card around it never changes size.
 */
@Composable
fun UmbrellaGlyph(open: Float, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val o = open.coerceIn(0f, 1.14f) // a little past full so the spring can flare the ribs
        val cx = w / 2f
        val apex = Offset(cx, h * 0.16f)
        val rib = h * 0.50f // rib length from the apex; also the dome radius when open
        val spread = ((6f + 72f * o) * PI / 180.0).toFloat() // half-angle of the fan
        val ribs = 6
        val tips = List(ribs + 1) { i ->
            val a = -spread + 2f * spread * i / ribs
            Offset(cx + rib * sin(a), apex.y + rib * cos(a))
        }
        val line = w * 0.085f

        // Shaft with a hook at the bottom, under the canopy so the join is hidden.
        val hookR = w * 0.11f
        val hookTop = h * 0.76f
        val shaft = Path().apply {
            moveTo(cx, apex.y - h * 0.10f) // ferrule above the apex
            lineTo(cx, hookTop)
            arcTo(Rect(cx - 2 * hookR, hookTop - hookR, cx, hookTop + hookR), 0f, 180f, false)
        }
        drawPath(shaft, tint, style = Stroke(width = line, cap = StrokeCap.Round))

        // Canopy: two outward-bulging edges from the apex, joined by a scalloped hem.
        val bulge = 0.9f
        val canopy = Path().apply {
            moveTo(apex.x, apex.y)
            val l = tips.first()
            quadraticTo(cx - rib * sin(spread) * bulge, apex.y + rib * cos(spread) * 0.3f, l.x, l.y)
            for (i in 1..ribs) {
                val a = tips[i - 1]
                val b = tips[i]
                val mx = (a.x + b.x) / 2f
                val my = (a.y + b.y) / 2f
                val k = 0.22f * o // scallop depth, none while furled
                quadraticTo(mx + (apex.x - mx) * k, my + (apex.y - my) * k, b.x, b.y)
            }
            quadraticTo(cx + rib * sin(spread) * bulge, apex.y + rib * cos(spread) * 0.3f, apex.x, apex.y)
            close()
        }
        drawPath(canopy, tint)
    }
}
