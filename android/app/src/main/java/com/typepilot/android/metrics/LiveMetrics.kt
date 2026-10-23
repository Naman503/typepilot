package com.typepilot.android.metrics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/** Pipeline stages we time, from the accessibility event to the suggestion on screen. */
enum class Stage(val title: String, val help: String) {
    RECOGNITION("Field recognition", "Accessibility event → field, text and cursor read"),
    DELIVERY("Event delivery", "Keystroke in the app → event reaches TypePilot"),
    MEMORY("Memory lookup", "Phrase / shortcut / recall search"),
    MODEL_TTFT("Model first token", "Request sent → first token back"),
    MODEL_TOTAL("Model total", "Request sent → full suggestion"),
    END_TO_END("End to end", "Typing pause → suggestion visible"),
    RENDER("Overlay render", "Suggestion ready → drawn on screen"),
    REWRITE_TTFT("Rewrite first token", "Rewrite started → first words visible"),
    REWRITE_TOTAL("Rewrite total", "Rewrite started → finished"),
}

/** A fixed-size ring of recent samples: O(1) to record, percentiles computed only when a screen asks. */
class Ring(private val capacity: Int = 256) {
    private val data = LongArray(capacity)
    private var count = 0
    private var next = 0

    @Synchronized fun add(v: Long) { data[next] = v; next = (next + 1) % capacity; if (count < capacity) count++ }
    @Synchronized fun snapshot(): LongArray {
        val out = LongArray(count)
        for (i in 0 until count) out[i] = data[(next - count + i + capacity) % capacity]
        return out
    }
    @Synchronized fun clear() { count = 0; next = 0 }
}

data class Percentiles(val count: Int, val p50: Long, val p90: Long, val p99: Long, val min: Long, val max: Long, val avg: Double) {
    companion object {
        val EMPTY = Percentiles(0, 0, 0, 0, 0, 0, 0.0)
        fun of(values: LongArray): Percentiles {
            if (values.isEmpty()) return EMPTY
            val s = values.sortedArray()
            fun p(q: Double) = s[((s.size - 1) * q).toInt()]
            return Percentiles(s.size, p(0.5), p(0.9), p(0.99), s.first(), s.last(), s.average())
        }
        fun of(values: List<Long>) = of(values.toLongArray())
    }
}

/** In-memory, process-wide live stats (reset when the app restarts). */
object LiveMetrics {
    private val rings = Stage.entries.associateWith { Ring() }
    val eventsReceived = AtomicLong()
    val eventsHandled = AtomicLong()
    val eventsSkipped = AtomicLong()
    val suggestionsRequested = AtomicLong()
    val cacheHits = AtomicLong()
    val modelCalls = AtomicLong()
    val cancelled = AtomicLong()
    @Volatile var startedAt = System.currentTimeMillis()

    /** Typing speed (characters per minute), smoothed. */
    @Volatile var typingCpm = 0.0; private set
    private var lastTypeAt = 0L

    data class Status(
        val serviceConnected: Boolean = false,
        val keyboardActive: Boolean = false,
        val currentApp: String = "",
        val lastSuggestion: String = "",
        val lastLatencyMs: Long = 0,
        val lastProvider: String = "",
    )
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    fun record(stage: Stage, ms: Long) { if (ms >= 0) rings.getValue(stage).add(ms) }
    fun samples(stage: Stage): LongArray = rings.getValue(stage).snapshot()
    fun percentiles(stage: Stage) = Percentiles.of(samples(stage))

    fun updateStatus(change: (Status) -> Status) { _status.value = change(_status.value) }

    /** Called with the number of characters typed since the last text change. */
    fun typed(chars: Int, now: Long) {
        val gap = now - lastTypeAt
        lastTypeAt = now
        if (chars !in 1..3 || gap !in 40..2_000) return // pastes and long pauses aren't typing
        val cpm = chars * 60_000.0 / gap
        typingCpm = if (typingCpm == 0.0) cpm else typingCpm * 0.97 + cpm * 0.03
    }

    fun reset() {
        rings.values.forEach { it.clear() }
        listOf(eventsReceived, eventsHandled, eventsSkipped, suggestionsRequested, cacheHits, modelCalls, cancelled).forEach { it.set(0) }
        startedAt = System.currentTimeMillis()
    }
}
