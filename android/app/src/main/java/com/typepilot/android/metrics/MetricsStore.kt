package com.typepilot.android.metrics

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.typepilot.android.core.Log
import java.util.Calendar
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class EventType {
    SHOWN,            // a suggestion appeared
    ACCEPTED,         // accepted in full (tap, Tab-equivalent, strip)
    WORD,             // accepted one word
    TYPED_THROUGH,    // the user typed exactly what was suggested
    DISMISSED,        // closed with ✕
    IGNORED,          // replaced by different typing / focus left
    REWRITE,          // a rewrite was generated
    REWRITE_APPLIED,  // …and inserted
    WRITE,            // "write for me" draft generated
    WRITE_APPLIED,
    ERROR,
}

data class MetricEvent(
    val type: EventType,
    val source: String = "",     // memory | shortcut | recall | ai | cache
    val app: String = "",
    val provider: String = "",
    val mode: String = "",
    val latencyMs: Long = 0,
    val ttftMs: Long = 0,
    val detectMs: Long = 0,
    val chars: Int = 0,
    val words: Int = 0,
    val ts: Long = System.currentTimeMillis(),
)

data class PerfRow(val ts: Long, val cpu: Float, val rssMb: Float, val heapMb: Float, val threads: Int, val events: Int, val detectMs: Float, val battery: Int)

/**
 * Usage and performance history. Writes are queued and flushed in batches every few seconds on one
 * background thread, so recording an event costs the typing path a queue insert, nothing more.
 */
class MetricsStore(context: Context) : SQLiteOpenHelper(context, "metrics.db", null, 1) {
    private val queue = ConcurrentLinkedQueue<MetricEvent>()
    private val perfQueue = ConcurrentLinkedQueue<PerfRow>()
    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "metrics-io").apply { priority = Thread.MIN_PRIORITY } }
    @Volatile var enabled = true
    /** Bumped after each flush so dashboards can refresh. */
    @Volatile var version = 0L; private set

    init {
        io.scheduleWithFixedDelay({ flush() }, 5, 5, TimeUnit.SECONDS)
        io.schedule({ prune() }, 30, TimeUnit.SECONDS)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE events (ts INTEGER NOT NULL, type TEXT NOT NULL, source TEXT, app TEXT, provider TEXT, mode TEXT,
            latency INTEGER, ttft INTEGER, detect INTEGER, chars INTEGER, words INTEGER)""")
        db.execSQL("CREATE INDEX events_ts ON events(ts)")
        db.execSQL("""CREATE TABLE perf (ts INTEGER NOT NULL, cpu REAL, rss REAL, heap REAL, threads INTEGER, events INTEGER, detect REAL, battery INTEGER)""")
        db.execSQL("CREATE INDEX perf_ts ON perf(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** Sees every event, even with local stats off (usage reporting keeps its own counts). */
    @Volatile var observer: ((MetricEvent) -> Unit)? = null

    fun record(e: MetricEvent) {
        observer?.invoke(e)
        if (enabled) queue.add(e)
    }
    fun recordPerf(p: PerfRow) { if (enabled) perfQueue.add(p) }

    fun flush() {
        if (queue.isEmpty() && perfQueue.isEmpty()) return
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val ins = db.compileStatement("INSERT INTO events VALUES (?,?,?,?,?,?,?,?,?,?,?)")
                while (true) {
                    val e = queue.poll() ?: break
                    ins.clearBindings()
                    ins.bindLong(1, e.ts); ins.bindString(2, e.type.name); ins.bindString(3, e.source); ins.bindString(4, e.app)
                    ins.bindString(5, e.provider); ins.bindString(6, e.mode); ins.bindLong(7, e.latencyMs); ins.bindLong(8, e.ttftMs)
                    ins.bindLong(9, e.detectMs); ins.bindLong(10, e.chars.toLong()); ins.bindLong(11, e.words.toLong())
                    ins.executeInsert()
                }
                val perf = db.compileStatement("INSERT INTO perf VALUES (?,?,?,?,?,?,?,?)")
                while (true) {
                    val p = perfQueue.poll() ?: break
                    perf.clearBindings()
                    perf.bindLong(1, p.ts); perf.bindDouble(2, p.cpu.toDouble()); perf.bindDouble(3, p.rssMb.toDouble())
                    perf.bindDouble(4, p.heapMb.toDouble()); perf.bindLong(5, p.threads.toLong()); perf.bindLong(6, p.events.toLong())
                    perf.bindDouble(7, p.detectMs.toDouble()); perf.bindLong(8, p.battery.toLong())
                    perf.executeInsert()
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            version++
        } catch (e: Exception) { Log.e("metrics", "flush failed", e) }
    }

    /** Keep 90 days of events and 14 days of performance samples. */
    private fun prune() {
        runCatching {
            val now = System.currentTimeMillis()
            writableDatabase.execSQL("DELETE FROM events WHERE ts < ?", arrayOf(now - 90L * 86_400_000))
            writableDatabase.execSQL("DELETE FROM perf WHERE ts < ?", arrayOf(now - 14L * 86_400_000))
        }
    }

    fun reset() {
        queue.clear(); perfQueue.clear()
        io.execute { runCatching { writableDatabase.execSQL("DELETE FROM events"); writableDatabase.execSQL("DELETE FROM perf") }; version++ }
    }

    // ---------- Queries (call off the main thread) ----------

    data class Summary(
        val shown: Int, val accepted: Int, val words: Int, val typedThrough: Int, val dismissed: Int, val ignored: Int,
        val acceptedChars: Int, val acceptedWords: Int, val rewrites: Int, val rewritesApplied: Int, val writes: Int, val writesApplied: Int,
        val errors: Int, val avgLatency: Double, val bySource: Map<String, Pair<Int, Int>>,
    ) {
        /** Accepted (in full or one word at a time, or typed exactly) out of everything shown. */
        val acceptanceRate: Float get() = if (shown == 0) 0f else ((accepted + words + typedThrough).toFloat() / shown).coerceAtMost(1f)
    }

    fun summary(since: Long): Summary {
        flush()
        val counts = HashMap<String, Int>()
        var chars = 0; var wordsSaved = 0
        readableDatabase.rawQuery("SELECT type, COUNT(*), SUM(chars), SUM(words) FROM events WHERE ts >= ? GROUP BY type", arrayOf(since.toString())).use { c ->
            while (c.moveToNext()) {
                counts[c.getString(0)] = c.getInt(1)
                if (c.getString(0) in setOf("ACCEPTED", "WORD", "TYPED_THROUGH")) { chars += c.getInt(2); wordsSaved += c.getInt(3) }
            }
        }
        val avg = readableDatabase.rawQuery("SELECT AVG(latency) FROM events WHERE ts >= ? AND type = 'SHOWN'", arrayOf(since.toString()))
            .use { if (it.moveToFirst()) it.getDouble(0) else 0.0 }
        val bySource = HashMap<String, Pair<Int, Int>>()
        readableDatabase.rawQuery("""SELECT source, SUM(type = 'SHOWN'), SUM(type IN ('ACCEPTED','WORD','TYPED_THROUGH'))
            FROM events WHERE ts >= ? AND source <> '' AND type IN ('SHOWN','ACCEPTED','WORD','TYPED_THROUGH') GROUP BY source""", arrayOf(since.toString())).use { c ->
            while (c.moveToNext()) bySource[c.getString(0)] = c.getInt(1) to c.getInt(2)
        }
        fun n(t: EventType) = counts[t.name] ?: 0
        return Summary(n(EventType.SHOWN), n(EventType.ACCEPTED), n(EventType.WORD), n(EventType.TYPED_THROUGH), n(EventType.DISMISSED),
            n(EventType.IGNORED), chars, wordsSaved, n(EventType.REWRITE), n(EventType.REWRITE_APPLIED), n(EventType.WRITE),
            n(EventType.WRITE_APPLIED), n(EventType.ERROR), avg, bySource)
    }

    data class Day(val start: Long, val shown: Int, val accepted: Int, val rewrites: Int, val wordsSaved: Int)

    fun daily(days: Int): List<Day> {
        flush()
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        cal.add(Calendar.DAY_OF_YEAR, -(days - 1))
        val starts = (0 until days).map { cal.timeInMillis.also { cal.add(Calendar.DAY_OF_YEAR, 1) } }
        val out = starts.map { Day(it, 0, 0, 0, 0) }.toMutableList()
        readableDatabase.rawQuery("SELECT ts, type, words FROM events WHERE ts >= ?", arrayOf(starts.first().toString())).use { c ->
            while (c.moveToNext()) {
                val ts = c.getLong(0)
                val i = starts.indexOfLast { it <= ts }.takeIf { it >= 0 } ?: continue
                val d = out[i]
                out[i] = when (c.getString(1)) {
                    "SHOWN" -> d.copy(shown = d.shown + 1)
                    "ACCEPTED", "WORD", "TYPED_THROUGH" -> d.copy(accepted = d.accepted + 1, wordsSaved = d.wordsSaved + c.getInt(2))
                    "REWRITE", "WRITE" -> d.copy(rewrites = d.rewrites + 1)
                    else -> d
                }
            }
        }
        return out
    }

    /** Activity per hour of the day (0–23): suggestions shown + accepted + rewrites. */
    fun hourly(since: Long): IntArray {
        val out = IntArray(24)
        val cal = Calendar.getInstance()
        readableDatabase.rawQuery("SELECT ts FROM events WHERE ts >= ? AND type IN ('SHOWN','REWRITE','WRITE')", arrayOf(since.toString())).use { c ->
            while (c.moveToNext()) { cal.timeInMillis = c.getLong(0); out[cal.get(Calendar.HOUR_OF_DAY)]++ }
        }
        return out
    }

    data class AppUsage(val app: String, val shown: Int, val accepted: Int, val rewrites: Int)

    fun topApps(since: Long, limit: Int = 8): List<AppUsage> {
        val out = ArrayList<AppUsage>()
        readableDatabase.rawQuery("""SELECT app, SUM(type='SHOWN'), SUM(type IN ('ACCEPTED','WORD','TYPED_THROUGH')), SUM(type IN ('REWRITE','WRITE'))
            FROM events WHERE ts >= ? AND app <> '' GROUP BY app ORDER BY COUNT(*) DESC LIMIT ?""", arrayOf(since.toString(), limit.toString())).use { c ->
            while (c.moveToNext()) out += AppUsage(c.getString(0), c.getInt(1), c.getInt(2), c.getInt(3))
        }
        return out
    }

    fun countBy(column: String, types: List<EventType>, since: Long): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        val inList = types.joinToString(",") { "'${it.name}'" }
        readableDatabase.rawQuery("SELECT $column, COUNT(*) FROM events WHERE ts >= ? AND type IN ($inList) AND $column <> '' GROUP BY $column ORDER BY 2 DESC",
            arrayOf(since.toString())).use { c -> while (c.moveToNext()) out += c.getString(0) to c.getInt(1) }
        return out
    }

    fun latencies(since: Long, type: EventType, column: String = "latency", source: String? = null, limit: Int = 4000): List<Long> {
        val out = ArrayList<Long>()
        val q = "SELECT $column FROM events WHERE ts >= ? AND type = ? AND $column > 0" + (if (source != null) " AND source = ?" else "") + " ORDER BY ts DESC LIMIT $limit"
        val args = listOfNotNull(since.toString(), type.name, source).toTypedArray()
        readableDatabase.rawQuery(q, args).use { c -> while (c.moveToNext()) out += c.getLong(0) }
        return out
    }

    data class ProviderStats(val provider: String, val calls: Int, val avgLatency: Double, val avgTtft: Double, val errors: Int)

    fun providers(since: Long): List<ProviderStats> {
        val out = ArrayList<ProviderStats>()
        readableDatabase.rawQuery("""SELECT provider, SUM(type <> 'ERROR'), AVG(CASE WHEN type <> 'ERROR' THEN latency END),
            AVG(CASE WHEN ttft > 0 THEN ttft END), SUM(type = 'ERROR')
            FROM events WHERE ts >= ? AND provider <> '' AND type IN ('SHOWN','REWRITE','WRITE','ERROR') GROUP BY provider ORDER BY 2 DESC""",
            arrayOf(since.toString())).use { c ->
            while (c.moveToNext()) out += ProviderStats(c.getString(0), c.getInt(1), c.getDouble(2), c.getDouble(3), c.getInt(4))
        }
        return out
    }

    fun perf(since: Long): List<PerfRow> {
        flush()
        val out = ArrayList<PerfRow>()
        readableDatabase.rawQuery("SELECT ts, cpu, rss, heap, threads, events, detect, battery FROM perf WHERE ts >= ? ORDER BY ts", arrayOf(since.toString())).use { c ->
            while (c.moveToNext()) out += PerfRow(c.getLong(0), c.getFloat(1), c.getFloat(2), c.getFloat(3), c.getInt(4), c.getInt(5), c.getFloat(6), c.getInt(7))
        }
        return out
    }

    fun exportCsv(): String {
        flush()
        val sb = StringBuilder("time,type,source,app,provider,mode,latency_ms,ttft_ms,detect_ms,chars,words\n")
        readableDatabase.rawQuery("SELECT * FROM events ORDER BY ts", null).use { c ->
            while (c.moveToNext()) {
                sb.append(c.getLong(0)).append(',')
                for (i in 1..10) { sb.append(c.getString(i)?.replace(",", " ") ?: ""); if (i < 10) sb.append(',') }
                sb.append('\n')
            }
        }
        return sb.toString()
    }
}
