package com.neurone.myblocker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.neurone.myblocker.R

/** Minimal bar chart: values with optional labels under some bars. */
class BarChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var values: LongArray = LongArray(0)
    private var labels: Array<String?> = emptyArray()
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.accent) }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.bar_empty) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.muted)
        textSize = Ui.dp(context, 10).toFloat()
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    fun setData(values: LongArray, labels: Array<String?>) {
        this.values = values
        this.labels = labels
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return
        val n = values.size
        val labelSpace = Ui.dp(context, 16).toFloat()
        val w = width.toFloat()
        val h = height.toFloat() - labelSpace
        val gap = Ui.dp(context, 2).toFloat()
        val barW = (w - gap * (n - 1)) / n
        val max = values.maxOrNull()?.coerceAtLeast(1) ?: 1
        val radius = Ui.dp(context, 3).toFloat()
        for (i in 0 until n) {
            val x = i * (barW + gap)
            val v = values[i]
            val barH = if (v == 0L) Ui.dp(context, 2).toFloat() else (h * v / max).coerceAtLeast(Ui.dp(context, 3).toFloat())
            rect.set(x, h - barH, x + barW, h)
            canvas.drawRoundRect(rect, radius, radius, if (v == 0L) emptyPaint else barPaint)
            val label = labels.getOrNull(i)
            if (label != null) canvas.drawText(label, x + barW / 2, h + labelSpace - Ui.dp(context, 3), textPaint)
        }
    }
}
