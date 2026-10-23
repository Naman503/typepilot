package com.typepilot.android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import com.typepilot.android.ui.theme.Brand

/** Smooth line chart with a soft gradient fill. */
@Composable
fun LineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = Brand.Violet,
    height: Dp = 140.dp,
    maxY: Float? = null,
    unit: String = "",
    gridLines: Int = 3,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val top = (maxY ?: (values.maxOrNull() ?: 1f)).coerceAtLeast(0.001f) * 1.15f
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(height)) {
            Canvas(Modifier.matchParentSize()) {
                for (g in 0..gridLines) {
                    val y = size.height * g / gridLines
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                if (values.size < 2) return@Canvas
                val step = size.width / (values.size - 1)
                val pts = values.mapIndexed { i, v -> Offset(i * step, size.height - (v / top).coerceIn(0f, 1f) * size.height) }
                val line = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until pts.size) {
                        val p = pts[i - 1]; val c = pts[i]
                        val mx = (p.x + c.x) / 2
                        cubicTo(mx, p.y, mx, c.y, c.x, c.y)
                    }
                }
                val fill = Path().apply { addPath(line); lineTo(pts.last().x, size.height); lineTo(0f, size.height); close() }
                drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.32f), color.copy(alpha = 0.0f))))
                drawPath(line, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
                drawCircle(color, 4.dp.toPx(), pts.last())
                drawCircle(Color.White, 2.dp.toPx(), pts.last())
            }
            Text("${fmt(top / 1.15f)}$unit", fontSize = 10.sp, color = labelColor, modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

/** Vertical bars; each bar can be a stack of segments. */
@Composable
fun BarChart(
    bars: List<List<Float>>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    colors: List<Color> = Brand.chart,
    height: Dp = 150.dp,
    labelEvery: Int = 1,
) {
    val anim by animateFloatAsState(1f, tween(600), label = "bars")
    val maxV = (bars.maxOfOrNull { it.sum() } ?: 1f).coerceAtLeast(1f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            if (bars.isEmpty()) return@Canvas
            val slot = size.width / bars.size
            val bw = (slot * 0.62f).coerceAtMost(28.dp.toPx())
            bars.forEachIndexed { i, segs ->
                val x = i * slot + (slot - bw) / 2
                drawRoundRect(track, Offset(x, 0f), Size(bw, size.height), CornerRadius(6.dp.toPx()))
                var y = size.height
                segs.forEachIndexed { s, v ->
                    val h = v / maxV * size.height * anim
                    if (h > 0.5f) {
                        drawRoundRect(colors[s % colors.size], Offset(x, y - h), Size(bw, h), CornerRadius(6.dp.toPx()))
                        y -= h
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            labels.forEachIndexed { i, l ->
                Text(if (i % labelEvery == 0) l else "", Modifier.weight(1f), fontSize = 10.sp, color = labelColor, maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

data class Slice(val label: String, val value: Float, val color: Color)

/** Donut with a big number in the middle and a legend. */
@Composable
fun Donut(slices: List<Slice>, center: String, caption: String, modifier: Modifier = Modifier, size: Dp = 128.dp) {
    val anim by animateFloatAsState(1f, tween(700), label = "donut")
    val total = slices.sumOf { it.value.toDouble() }.toFloat()
    val track = MaterialTheme.colorScheme.surfaceVariant
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                val stroke = 16.dp.toPx()
                val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
                val tl = Offset(stroke / 2, stroke / 2)
                drawArc(track, 0f, 360f, false, tl, arcSize, style = Stroke(stroke))
                if (total <= 0f) return@Canvas
                var start = -90f
                for (s in slices) {
                    val sweep = s.value / total * 360f * anim
                    if (sweep > 0.5f) drawArc(s.color, start, (sweep - 1.5f).coerceAtLeast(0.5f), false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(center, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(caption, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (s in slices) Legend(s.color, s.label, if (total > 0) "${fmt(s.value)} · ${(s.value / total * 100).toInt()}%" else fmt(s.value))
        }
    }
}

@Composable
fun Legend(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 13.sp)
        Spacer(Modifier.width(6.dp))
        Text(value, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Horizontal bars for rankings (top apps, providers…). */
@Composable
fun RankBars(items: List<Triple<String, Float, String>>, modifier: Modifier = Modifier, color: Color = Brand.Violet) {
    val maxV = (items.maxOfOrNull { it.second } ?: 1f).coerceAtLeast(0.001f)
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((label, v, right) in items) {
            Column {
                Row(Modifier.fillMaxWidth()) {
                    Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(right, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(track)) {
                    Box(Modifier.fillMaxWidth(v / maxV).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Brush.horizontalGradient(listOf(color, color.copy(alpha = 0.6f)))))
                }
            }
        }
    }
}

/** 24 cells, one per hour, shaded by activity. */
@Composable
fun HourHeat(hours: IntArray, modifier: Modifier = Modifier, color: Color = Brand.Violet) {
    val maxV = (hours.maxOrNull() ?: 1).coerceAtLeast(1)
    val empty = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (h in 0 until 24) {
                val a = hours[h].toFloat() / maxV
                Box(Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(4.dp)).background(if (hours[h] == 0) empty else color.copy(alpha = 0.18f + 0.82f * a)))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            for (l in listOf("12a", "6a", "12p", "6p", "")) Text(l, Modifier.weight(1f), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Latency histogram: buckets on a rough log scale (ms). */
@Composable
fun LatencyHistogram(values: LongArray, modifier: Modifier = Modifier, color: Color = Brand.Cyan) {
    val edges = listOf(0L, 25, 50, 100, 200, 350, 500, 750, 1000, 1500, 2500, Long.MAX_VALUE)
    val labels = listOf("25", "50", "100", "200", "350", "500", "750", "1s", "1.5s", "2.5s", "more")
    val counts = FloatArray(edges.size - 1)
    for (v in values) { val i = edges.indexOfLast { it <= v }.coerceIn(0, counts.lastIndex); counts[i]++ }
    BarChart(counts.map { listOf(it) }, labels, modifier, colors = listOf(color), height = 120.dp, labelEvery = 2)
}

fun fmt(v: Float): String = when {
    v >= 10_000 -> "${(v / 1000).toInt()}k"
    v >= 1000 -> String.format("%.1fk", v / 1000)
    v >= 100 || v == v.toInt().toFloat() -> v.toInt().toString()
    v >= 10 -> String.format("%.1f", v)
    else -> String.format("%.2f", v).trimEnd('0').trimEnd('.')
}

fun fmtMs(ms: Number): String {
    val v = ms.toDouble()
    return when {
        v <= 0 -> "–"
        v < 1 -> "<1 ms"
        v < 1000 -> "${v.toInt()} ms"
        else -> String.format("%.2f s", v / 1000)
    }
}
