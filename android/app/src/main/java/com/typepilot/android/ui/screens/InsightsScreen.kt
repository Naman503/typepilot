package com.typepilot.android.ui.screens

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typepilot.android.TypePilotApp
import com.typepilot.android.metrics.EventType
import com.typepilot.android.metrics.LiveMetrics
import com.typepilot.android.metrics.MetricsStore
import com.typepilot.android.metrics.Percentiles
import com.typepilot.android.metrics.Stage
import com.typepilot.android.ui.components.BarChart
import com.typepilot.android.ui.components.Donut
import com.typepilot.android.ui.components.Gap
import com.typepilot.android.ui.components.HourHeat
import com.typepilot.android.ui.components.Kpi
import com.typepilot.android.ui.components.KpiRow
import com.typepilot.android.ui.components.LatencyHistogram
import com.typepilot.android.ui.components.Legend
import com.typepilot.android.ui.components.LineChart
import com.typepilot.android.ui.components.RankBars
import com.typepilot.android.ui.components.SectionCard
import com.typepilot.android.ui.components.Slice
import com.typepilot.android.ui.components.StatLine
import com.typepilot.android.ui.components.fmt
import com.typepilot.android.ui.components.fmtMs
import com.typepilot.android.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Range(val title: String, val days: Int) { TODAY("Today", 1), WEEK("7 days", 7), MONTH("30 days", 30) }

private data class UsageData(
    val summary: MetricsStore.Summary,
    val daily: List<MetricsStore.Day>,
    val hourly: IntArray,
    val apps: List<MetricsStore.AppUsage>,
    val modes: List<Pair<String, Int>>,
    val latencies: List<Long>,
    val providers: List<MetricsStore.ProviderStats>,
    val errors: List<Pair<String, Int>>,
    val perf: List<com.typepilot.android.metrics.PerfRow>,
)

@Composable
fun InsightsScreen() {
    val app = TypePilotApp.instance
    var range by rememberSaveable { mutableIntStateOf(1) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(5_000); tick = app.metrics.version } }

    val r = Range.entries[range]
    val since = if (r == Range.TODAY) startOfToday() else System.currentTimeMillis() - r.days * 86_400_000L
    val data by produceState<UsageData?>(null, range, tick) {
        value = withContext(Dispatchers.IO) {
            val m = app.metrics
            UsageData(
                m.summary(since), m.daily(maxOf(r.days, 7)), m.hourly(since), m.topApps(since),
                m.countBy("mode", listOf(EventType.REWRITE, EventType.WRITE), since),
                m.latencies(since, EventType.SHOWN), m.providers(since),
                m.countBy("mode", listOf(EventType.ERROR), since), m.perf(System.currentTimeMillis() - 86_400_000L),
            )
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            Text("Insights", style = MaterialTheme.typography.headlineMedium)
            Gap(10)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Range.entries.forEachIndexed { i, x ->
                    SegmentedButton(range == i, { range = i }, SegmentedButtonDefaults.itemShape(i, Range.entries.size)) { Text(x.title) }
                }
            }
        }
        PrimaryTabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 8.dp)) {
            listOf("Usage", "Performance", "AI").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            val d = data
            if (d == null) Text("Loading…") else when (tab) {
                0 -> UsageTab(d, r)
                1 -> PerformanceTab(d)
                else -> AiTab(d)
            }
            Gap(24)
        }
    }
}

@Composable
private fun UsageTab(d: UsageData, r: Range) {
    val s = d.summary
    val accepted = s.accepted + s.words + s.typedThrough
    KpiRow {
        Kpi("${s.shown}", "suggestions shown", Modifier.weight(1f), Icons.Default.Visibility, Brand.Blue)
        Kpi("$accepted", "accepted", Modifier.weight(1f), Icons.Default.ThumbUp, Brand.Green, extra = "${(s.acceptanceRate * 100).toInt()}% acceptance")
    }
    Gap(10)
    KpiRow {
        Kpi("${s.acceptedWords}", "words saved", Modifier.weight(1f), Icons.Default.EditNote, Brand.Pink, extra = timeSaved(s.acceptedChars))
        Kpi("${s.acceptedChars}", "keystrokes saved", Modifier.weight(1f), Icons.Default.Keyboard, Brand.Violet,
            extra = if (LiveMetrics.typingCpm > 0) "you type ~${(LiveMetrics.typingCpm / 5).toInt()} wpm" else null)
    }
    Gap(10)
    KpiRow {
        Kpi("${s.rewrites + s.writes}", "AI rewrites & drafts", Modifier.weight(1f), Icons.Default.AutoAwesome, Brand.Amber,
            extra = "${s.rewritesApplied + s.writesApplied} used")
        Kpi(fmtMs(s.avgLatency), "avg suggestion time", Modifier.weight(1f), Icons.Default.Speed, Brand.Cyan)
    }
    Gap(16)

    SectionCard("What happened to suggestions") {
        Donut(listOf(
            Slice("Accepted", s.accepted.toFloat(), Brand.Green),
            Slice("Word by word", s.words.toFloat(), Brand.Cyan),
            Slice("Typed it yourself", s.typedThrough.toFloat(), Brand.Blue),
            Slice("Closed (✕)", s.dismissed.toFloat(), Brand.Amber),
            Slice("Ignored", s.ignored.toFloat(), MaterialTheme.colorScheme.outline),
        ), "${(s.acceptanceRate * 100).toInt()}%", "acceptance")
    }
    Gap(16)

    SectionCard("Daily activity", subtitle = "Accepted vs. not accepted") {
        val days = d.daily.takeLast(if (r == Range.MONTH) 30 else 7)
        val fmtDay = SimpleDateFormat(if (days.size > 7) "d" else "EEE", Locale.getDefault())
        BarChart(days.map { listOf(it.accepted.toFloat(), (it.shown - it.accepted).coerceAtLeast(0).toFloat()) },
            days.map { fmtDay.format(Date(it.start)) }, colors = listOf(Brand.Violet, Brand.Violet.copy(alpha = 0.3f)), labelEvery = if (days.size > 7) 5 else 1)
        Gap(8)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend(Brand.Violet, "Accepted", ""); Legend(Brand.Violet.copy(alpha = 0.3f), "Shown only", "")
        }
    }
    Gap(16)

    SectionCard("Where suggestions come from", subtitle = "Shown · acceptance rate") {
        val names = mapOf("fact" to "📇 Your facts", "memory" to "🧠 Your phrases", "recall" to "🧠 Usual field values", "shortcut" to "⚡ Text shortcuts", "ai" to "✦ AI model", "cache" to "↺ Instant repeat")
        if (s.bySource.isEmpty()) Text("No suggestions yet in this period.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        RankBars(s.bySource.entries.sortedByDescending { it.value.first }.map { (k, v) ->
            Triple(names[k] ?: k, v.first.toFloat(), "${v.first} · ${if (v.first > 0) v.second * 100 / v.first else 0}%")
        })
    }
    Gap(16)

    SectionCard("Top apps") {
        if (d.apps.isEmpty()) Text("Use TypePilot in a few apps to see this.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        val ctx = LocalContext.current
        RankBars(d.apps.map { a ->
            Triple(appLabel(ctx, a.app), (a.shown + a.rewrites).toFloat(), "${a.accepted}/${a.shown} accepted · ${a.rewrites} rewrites")
        }, color = Brand.Pink)
    }
    Gap(16)

    SectionCard("When you write", subtitle = "Activity by hour of day") { HourHeat(d.hourly) }
    Gap(16)

    SectionCard("Rewrite & write modes") {
        if (d.modes.isEmpty()) Text("Try ✦ Rewrite in any app.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Donut(d.modes.take(6).mapIndexed { i, (m, n) -> Slice(prettyMode(m), n.toFloat(), Brand.chart[i % Brand.chart.size]) },
            "${d.modes.sumOf { it.second }}", "runs")
    }
}

@Composable
private fun PerformanceTab(d: UsageData) {
    val app = TypePilotApp.instance
    DisposableEffect(Unit) { app.perf.acquireLive(); onDispose { app.perf.releaseLive() } }
    val live by app.perf.live.collectAsState()
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { now = SystemClock.elapsedRealtime(); delay(1000) } }
    val latest = live.lastOrNull()

    KpiRow {
        Kpi(latest?.let { String.format("%.1f%%", it.cpuPct) } ?: "–", "CPU now (device)", Modifier.weight(1f), Icons.Default.Speed, Brand.Violet,
            extra = latest?.let { String.format("%.0f%% of one core", it.cpuCorePct) })
        Kpi(latest?.let { "${it.rssMb.toInt()} MB" } ?: "–", "memory (PSS)", Modifier.weight(1f), Icons.Default.Memory, Brand.Cyan,
            extra = latest?.let { "heap ${it.javaHeapMb.toInt()} MB · ${it.threads} threads" })
    }
    Gap(10)
    KpiRow {
        Kpi(latest?.let { String.format("%.1f s", it.cpuTimeSec) } ?: "–", "CPU time since start", Modifier.weight(1f), Icons.Default.Timer, Brand.Amber,
            extra = "up ${uptime()}")
        Kpi(latest?.let { "${it.batteryPct}%" } ?: "–", "battery", Modifier.weight(1f), Icons.Default.Battery5Bar, Brand.Green,
            extra = latest?.let { "${it.batteryTempC}°C · device ${it.deviceCurrentMa} mA" })
    }
    Gap(16)

    SectionCard("CPU usage · live", subtitle = "TypePilot's share of the whole device, 1 s samples") {
        LineChart(live.map { it.cpuPct }, color = Brand.Violet, unit = "%", maxY = maxOf(5f, live.maxOfOrNull { it.cpuPct } ?: 0f))
    }
    Gap(16)
    SectionCard("Memory · live", subtitle = "Proportional memory (PSS, MB)") {
        LineChart(live.map { it.rssMb }, color = Brand.Cyan, unit = " MB")
    }
    Gap(16)

    SectionCard("Latency pipeline", subtitle = "This session · p50 / p90 / p99") {
        for (stage in Stage.entries) {
            val p = LiveMetrics.percentiles(stage)
            if (p.count == 0 && stage in setOf(Stage.REWRITE_TTFT, Stage.REWRITE_TOTAL, Stage.MODEL_TTFT, Stage.MODEL_TOTAL)) continue
            LatencyRow(stage, p)
        }
        if (now == 0L) Unit
    }
    Gap(16)

    SectionCard("Suggestion speed", subtitle = "Request → on screen, this period") {
        LatencyHistogram(d.latencies.toLongArray())
        val p = Percentiles.of(d.latencies)
        Gap(6)
        Text("p50 ${fmtMs(p.p50)} · p90 ${fmtMs(p.p90)} · p99 ${fmtMs(p.p99)} · ${p.count} samples", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Gap(16)

    SectionCard("Efficiency", subtitle = "How little work TypePilot does per keystroke") {
        val received = LiveMetrics.eventsReceived.get()
        val handled = LiveMetrics.eventsHandled.get()
        val requested = LiveMetrics.suggestionsRequested.get()
        StatLine("Accessibility events", "$received", help = "$handled were text fields · ${LiveMetrics.eventsSkipped.get()} skipped instantly")
        StatLine("Field recognition", fmtMs(LiveMetrics.percentiles(Stage.RECOGNITION).avg), help = "Average time to read a field and cursor")
        StatLine("Suggestion requests", "$requested", help = "${LiveMetrics.modelCalls.get()} needed the model · ${LiveMetrics.cacheHits.get()} from cache")
        StatLine("Cancelled as stale", "${LiveMetrics.cancelled.get()}", help = "You kept typing, so the request was dropped (saves battery & data)")
        StatLine("Cache hit rate", if (requested > 0) "${LiveMetrics.cacheHits.get() * 100 / requested}%" else "–")
        StatLine("Your typing speed", if (LiveMetrics.typingCpm > 0) "${(LiveMetrics.typingCpm / 5).toInt()} wpm" else "–", help = "Measured from your keystrokes, used for time saved")
    }
    Gap(16)

    if (d.perf.size >= 2) {
        SectionCard("Last 24 hours", subtitle = "Background samples, once a minute while the screen is on") {
            Text("CPU %", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LineChart(d.perf.map { it.cpu }, color = Brand.Violet, unit = "%", height = 100.dp)
            Gap(10)
            Text("Memory MB", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LineChart(d.perf.map { it.rssMb }, color = Brand.Cyan, unit = " MB", height = 100.dp)
            Gap(10)
            StatLine("Average CPU", String.format("%.2f%%", d.perf.map { it.cpu }.average()))
            StatLine("Peak memory", "${d.perf.maxOf { it.rssMb }.toInt()} MB")
        }
    }
}

@Composable
private fun LatencyRow(stage: Stage, p: Percentiles) {
    val good = when (stage) { Stage.RECOGNITION, Stage.MEMORY, Stage.RENDER, Stage.DELIVERY -> 30L; else -> 600L }
    val color = when {
        p.count == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        p.p50 <= good -> Brand.Green
        p.p50 <= good * 2 -> Brand.Amber
        else -> Brand.Red
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stage.title, fontSize = 14.sp)
            Text(stage.help, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
            Text(if (p.count == 0) "–" else fmtMs(p.p50), fontWeight = FontWeight.Bold, color = color, fontSize = 14.sp)
            if (p.count > 0) Text("${fmtMs(p.p90)} · ${fmtMs(p.p99)} · n=${p.count}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AiTab(d: UsageData) {
    val app = TypePilotApp.instance
    val localState by app.local.state.collectAsState()
    val ttft = LiveMetrics.percentiles(Stage.MODEL_TTFT)
    val total = LiveMetrics.percentiles(Stage.MODEL_TOTAL)
    KpiRow {
        Kpi(fmtMs(ttft.p50), "first token (p50)", Modifier.weight(1f), Icons.Default.Bolt, Brand.Violet, extra = "p90 ${fmtMs(ttft.p90)}")
        Kpi(fmtMs(total.p50), "full suggestion (p50)", Modifier.weight(1f), Icons.Default.Timer, Brand.Cyan, extra = "p90 ${fmtMs(total.p90)}")
    }
    Gap(10)
    KpiRow {
        val rw = LiveMetrics.percentiles(Stage.REWRITE_TTFT)
        Kpi(fmtMs(rw.p50), "rewrite first words", Modifier.weight(1f), Icons.Default.AutoAwesome, Brand.Pink, extra = "total ${fmtMs(LiveMetrics.percentiles(Stage.REWRITE_TOTAL).p50)}")
        Kpi("${d.summary.errors}", "errors", Modifier.weight(1f), Icons.Default.Speed, if (d.summary.errors > 0) Brand.Red else Brand.Green,
            extra = if (d.summary.errors == 0) "all good" else "see below")
    }
    Gap(16)
    SectionCard("Providers", subtitle = "Calls · avg time · avg first token · errors") {
        if (d.providers.isEmpty()) Text("No AI calls in this period.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (p in d.providers) StatLine(p.provider, "${p.calls} · ${fmtMs(p.avgLatency)}",
            help = "first token ${fmtMs(p.avgTtft)} · ${p.errors} errors", valueColor = if (p.errors > p.calls / 4) Brand.Red else MaterialTheme.colorScheme.onSurface)
    }
    Gap(16)
    if (d.errors.isNotEmpty()) {
        SectionCard("Errors by kind") {
            RankBars(d.errors.map { Triple(prettyError(it.first), it.second.toFloat(), "${it.second}") }, color = Brand.Red)
        }
        Gap(16)
    }
    SectionCard("On-device model") {
        StatLine("Loaded", localState.loadedId.ifBlank { if (localState.loading) "loading…" else "no (unloads when idle)" })
        if (localState.loadMs > 0) StatLine("Load time", fmtMs(localState.loadMs))
        localState.error?.let { StatLine("Last error", it.take(60), valueColor = Brand.Red) }
        StatLine("Suggestions from memory (no AI)", "${d.summary.bySource.filterKeys { it != "ai" && it != "cache" }.values.sumOf { it.first }}",
            help = "Instant, offline and free")
    }
}

private fun prettyMode(m: String) = com.typepilot.android.engine.RewriteMode.entries.firstOrNull { it.name == m }?.title
    ?: com.typepilot.android.core.WriteLength.entries.firstOrNull { it.name == m }?.let { "Write · ${it.title}" } ?: m

private fun prettyError(k: String) = when (k) {
    "TIMEOUT" -> "Timed out"; "NETWORK" -> "No connection"; "AUTH" -> "API key rejected"; "RATE_LIMIT" -> "Rate limited"
    "MODEL_MISSING" -> "Model not found"; "NOT_CONFIGURED" -> "Not set up"; else -> k.lowercase().replaceFirstChar { it.uppercase() }
}

private val labelCache = HashMap<String, String>()
fun appLabel(c: Context, pkg: String): String = labelCache.getOrPut(pkg) {
    runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg.substringAfterLast('.'))
}

private fun uptime(): String {
    val m = (System.currentTimeMillis() - LiveMetrics.startedAt) / 60_000
    return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min"
}

@Suppress("unused") private fun f(v: Float) = fmt(v)
