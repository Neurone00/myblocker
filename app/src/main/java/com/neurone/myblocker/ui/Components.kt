package com.neurone.myblocker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.NumberFormat

/** Emits a new value every [periodMs] so screens re-read plain (non-observable) state. */
@Composable
fun rememberTick(periodMs: Long): State<Long> {
    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            tick.longValue = tick.longValue + 1
        }
    }
    return tick
}

fun fmt(n: Long): String = NumberFormat.getIntegerInstance().format(n)

fun fmtBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(java.util.Locale.getDefault(), "%.1f GB", b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> String.format(java.util.Locale.getDefault(), "%.0f MB", b / (1024.0 * 1024))
    else -> String.format(java.util.Locale.getDefault(), "%.0f KB", b / 1024.0)
}

/** Scrollable page with an optional back header. */
@Composable
fun Page(title: String? = null, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.padding(end = 4.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
                Text(title, style = MaterialTheme.typography.headlineSmall)
            }
        }
        content()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun SectionCard(label: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(vertical = 4.dp)) {
            if (label != null) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp),
                )
            }
            content()
        }
    }
}

@Composable
fun RowDivider() = HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant)

@Composable
fun SettingRow(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        if (trailing != null) trailing() else if (onClick != null) {
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(title, subtitle, onClick = { onChange(!checked) }) {
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun StatTile(modifier: Modifier, value: String, label: String) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(vertical = 14.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Minimal rounded bar chart; labels (nullable per bar) are drawn under the bars. */
@Composable
fun BarChart(values: List<Long>, labels: List<String?>, modifier: Modifier = Modifier) {
    val bar = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val text = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val n = values.size
        val labelSpace = 16.dp.toPx()
        val gap = 3.dp.toPx()
        val h = size.height - labelSpace
        val barW = (size.width - gap * (n - 1)) / n
        val max = (values.maxOrNull() ?: 1L).coerceAtLeast(1L)
        val radius = CornerRadius(3.dp.toPx())
        val paint = android.graphics.Paint().apply {
            color = text.toArgbInt()
            textSize = 10.dp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        for (i in 0 until n) {
            val v = values[i]
            val x = i * (barW + gap)
            val barH = if (v == 0L) 2.dp.toPx() else (h * v / max).coerceAtLeast(3.dp.toPx())
            drawRoundRect(if (v == 0L) empty else bar, Offset(x, h - barH), Size(barW, barH), radius)
            val label = labels.getOrNull(i)
            if (label != null) {
                drawIntoCanvas { it.nativeCanvas.drawText(label, x + barW / 2, size.height - 2.dp.toPx(), paint) }
            }
        }
    }
}

private fun Color.toArgbInt(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)
