package com.neurone.myblocker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.min
import kotlin.math.pow

/**
 * The umbrella, drawn as a dome: rounded at the shoulders, close to vertical where the ribs end,
 * with the hem scalloped between the rib tips and a hooked handle below. An earlier version swept a
 * circular sector out of a single apex point, which reads as a cone rather than an umbrella.
 *
 * [open] runs from 0 (furled around the shaft) to 1 (full dome) and may overshoot to about 1.14 so a
 * spring can flare the canopy; [tilt] swings the whole glyph about the handle and [squash] stretches
 * it vertically for the anticipation. Everything is drawn inside a square box inset from the bounds,
 * so the card around it never changes size however far the glyph moves.
 */
@Composable
fun UmbrellaGlyph(
    open: Float,
    tint: Color,
    modifier: Modifier = Modifier,
    tilt: Float = 0f,
    squash: Float = 1f,
) {
    Canvas(modifier) {
        val box = min(size.width, size.height)
        val ox = (size.width - box) / 2f
        val oy = (size.height - box) / 2f
        val cx = box / 2f
        val apexY = box * 0.15f
        val handleY = box * 0.76f
        val pivot = Offset(cx, box * 0.80f)

        val o = open.coerceIn(0f, 1.14f)
        val opened = o.coerceAtMost(1f)
        // Width lags the opening (pow 1.5) so half-open still reads as furled rather than as a wide
        // blob; past full open the canopy barely widens and flattens instead, so an overshoot flexes
        // the dome rather than growing it out of the box.
        val wp = opened.pow(1.5f) + (o - opened) * 0.35f
        val halfW = box * (0.050f + 0.405f * wp)
        val domeH = box * (0.42f - 0.095f * wp)
        val hemY = apexY + domeH
        // Open, the rim is nearer the eye in the middle so the inner tips hang a little lower.
        // Furled, that same term becomes the gathered fabric tapering to a point down the shaft.
        val bow = box * (0.030f * opened + 0.090f * (1f - opened))
        val scallop = box * 0.040f * o // fabric between two tips is pulled up
        val line = box * 0.075f

        // Four hem segments, so five tips; the outer two sit where the dome turns vertical.
        val tips = List(SEGMENTS + 1) { i ->
            val x = cx - halfW + 2f * halfW * i / SEGMENTS
            val u = (x - cx) / halfW
            Offset(x, hemY + bow * (1f - u * u))
        }

        val shaft = Path().apply {
            moveTo(cx, apexY - box * 0.085f) // ferrule above the crown
            lineTo(cx, handleY)
            val r = box * 0.105f
            arcTo(Rect(cx - 2f * r, handleY - r, cx, handleY + r), 0f, 180f, false)
        }

        val canopy = Path().apply {
            val first = tips.first()
            val last = tips.last()
            moveTo(first.x, first.y)
            // Left shoulder up to the crown, then the right shoulder back down to the last tip.
            cubicTo(cx - halfW, hemY - domeH * TIP_TANGENT, cx - halfW * SHOULDER, apexY, cx, apexY)
            cubicTo(cx + halfW * SHOULDER, apexY, cx + halfW, hemY - domeH * TIP_TANGENT, last.x, last.y)
            for (i in SEGMENTS downTo 1) {
                val a = tips[i]
                val b = tips[i - 1]
                quadraticTo((a.x + b.x) / 2f, (a.y + b.y) / 2f - scallop, b.x, b.y)
            }
            close()
        }

        withTransform({
            translate(ox, oy)
            scale(FIT, FIT, Offset(cx, box / 2f))
            rotate(tilt, pivot)
            scale(1f, squash, pivot)
        }) {
            drawPath(shaft, tint, style = Stroke(width = line, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(canopy, tint)
        }
    }
}

private const val SEGMENTS = 4
/** How far the crown's flat tangent runs out before the dome falls away. */
private const val SHOULDER = 0.62f
/** How high up the vertical tangent at the rib tips reaches. */
private const val TIP_TANGENT = 0.58f
/** Headroom inside the bounds so a tilt or an overshoot never clips the glyph. */
private const val FIT = 0.90f
