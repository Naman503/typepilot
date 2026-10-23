package com.typepilot.android.metrics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

data class PerfSample(
    val ts: Long,
    /** This app's CPU use, as % of the whole device (all cores). */
    val cpuPct: Float,
    /** …and as % of one core, which is what most profilers show. */
    val cpuCorePct: Float,
    val rssMb: Float,
    val javaHeapMb: Float,
    val nativeHeapMb: Float,
    val threads: Int,
    val batteryPct: Int,
    val batteryTempC: Float,
    val deviceCurrentMa: Int,
    val cpuTimeSec: Double,
)

/**
 * Measures TypePilot's own footprint without root: CPU time from [Process.getElapsedCpuTime], memory (PSS)
 * from [Debug.getPss], threads from /proc/self/status.
 *
 * Two speeds: once a second while a dashboard is open, once a minute in the background (persisted),
 * and nothing at all while the screen is off.
 */
class PerfSampler(private val context: Context, private val store: MetricsStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private val pageKb = (android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) / 1024).coerceAtLeast(4)
    private val liveViewers = AtomicInteger()
    private var lastCpu = Process.getElapsedCpuTime()
    private var lastWall = SystemClock.elapsedRealtime()
    private var lastEvents = LiveMetrics.eventsHandled.get()
    private var job: Job? = null

    private val _live = MutableStateFlow<List<PerfSample>>(emptyList())
    /** The last ~3 minutes at 1 s resolution, for the live charts. */
    val live: StateFlow<List<PerfSample>> = _live
    private val _latest = MutableStateFlow<PerfSample?>(null)
    val latest: StateFlow<PerfSample?> = _latest

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var sinceSaved = 0L
            while (isActive) {
                val interactive = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
                val liveMode = liveViewers.get() > 0
                if (interactive) {
                    val s = sample()
                    _latest.value = s
                    if (liveMode) _live.value = (_live.value + s).takeLast(180)
                    sinceSaved += if (liveMode) 1_000 else 60_000
                    if (sinceSaved >= 60_000) {
                        sinceSaved = 0
                        val events = LiveMetrics.eventsHandled.get()
                        val detect = LiveMetrics.percentiles(Stage.RECOGNITION).avg.toFloat()
                        store.recordPerf(PerfRow(s.ts, s.cpuPct, s.rssMb, s.javaHeapMb, s.threads, (events - lastEvents).toInt(), detect, s.batteryPct))
                        lastEvents = events
                    }
                }
                delay(if (liveMode) 1_000 else 60_000)
            }
        }
    }

    /** Dashboards call this while visible to get 1 s samples. */
    fun acquireLive() { if (liveViewers.incrementAndGet() == 1) { job?.cancel(); job = null; start() } }
    fun releaseLive() { liveViewers.decrementAndGet() }

    fun sample(): PerfSample {
        val cpu = Process.getElapsedCpuTime()
        val wall = SystemClock.elapsedRealtime()
        val dCpu = (cpu - lastCpu).coerceAtLeast(0)
        val dWall = (wall - lastWall).coerceAtLeast(1)
        lastCpu = cpu; lastWall = wall
        val corePct = dCpu * 100f / dWall
        // PSS: this process's fair share of memory (shared libraries split between users). Debug.getPss() is the cheap path.
        val rss = runCatching { Debug.getPss() / 1024f }.getOrNull()?.takeIf { it > 0 }
            ?: runCatching { File("/proc/self/statm").readText().split(" ")[1].toLong() * pageKb / 1024f }.getOrDefault(0f)
        val threads = runCatching {
            File("/proc/self/status").useLines { lines -> lines.first { it.startsWith("Threads:") }.substringAfter(":").trim().toInt() }
        }.getOrDefault(0)
        val rt = Runtime.getRuntime()
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        var current = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        // Some devices report µA, others mA.
        current = if (kotlin.math.abs(current) > 20_000) current / 1000 else current
        return PerfSample(
            ts = System.currentTimeMillis(),
            cpuPct = (corePct / cores).coerceIn(0f, 100f),
            cpuCorePct = corePct.coerceAtLeast(0f),
            rssMb = rss,
            javaHeapMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576f,
            nativeHeapMb = Debug.getNativeHeapAllocatedSize() / 1_048_576f,
            threads = threads,
            batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            batteryTempC = temp,
            deviceCurrentMa = current,
            cpuTimeSec = cpu / 1000.0,
        )
    }
}
