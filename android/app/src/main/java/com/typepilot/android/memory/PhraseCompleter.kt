package com.typepilot.android.memory

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.exp

/**
 * Instant, model-free suggestions from your own memory (microseconds):
 *  0. a typed shortcut (";addr") expands to its entry;
 *  1. an empty form field whose label matches one you've filled before gets your usual value;
 *  2. the start of something you've written more than once gets its next few words.
 */
class PhraseCompleter {
    private data class Phrase(val text: String, val lower: String, val field: String, val score: Double, val trusted: Boolean, val app: String = "", val manual: Boolean = false)

    data class Hit(val text: String, val replaceLength: Int = 0, val note: String? = null, val kind: String = "phrase")

    @Volatile private var phrases: List<Phrase> = emptyList()
    @Volatile private var byField: Map<String, List<Phrase>> = emptyMap()
    @Volatile private var shortcuts: Map<String, String> = emptyMap()

    val isEmpty get() = phrases.isEmpty() && shortcuts.isEmpty()

    fun rebuild(entries: List<MemoryEntry>, now: Long = System.currentTimeMillis()) {
        val ps = ArrayList<Phrase>()
        val fields = HashMap<String, MutableList<Phrase>>()
        val sc = HashMap<String, String>()
        for (e in entries) {
            if (e.shortcut.isNotEmpty()) sc[e.shortcut] = e.text
            val ageDays = maxOf(0.0, (now - e.lastUsed) / 86_400_000.0)
            val score = e.count * exp(-ageDays / 30) + (if (e.pinned) 100 else 0) + (if (e.kind == MemoryEntry.Kind.MANUAL) 2 else 0)
            val field = normalizeField(e.field)
            val trusted = e.count >= 2 || e.kind == MemoryEntry.Kind.MANUAL || e.pinned
            val whole = Phrase(e.text, e.text.lowercase(), field, score, trusted, e.app, e.kind == MemoryEntry.Kind.MANUAL)
            ps += whole
            if (field.isNotEmpty() && trusted && e.text.length <= MAX_RECALL && '\n' !in e.text) fields.getOrPut(field) { ArrayList() } += whole
            for (sentence in sentences(e.text)) if (sentence.length >= 12 && sentence != e.text) {
                ps += Phrase(sentence, sentence.lowercase(), field, score * 0.8, trusted, e.app, e.kind == MemoryEntry.Kind.MANUAL)
            }
        }
        phrases = ps.sortedByDescending { it.score }
        byField = fields.mapValues { (_, v) -> v.sortedByDescending { it.score } }
        shortcuts = sc
    }

    /** [app]: the app you're typing in (empty = ignore apps). Phrases from the same app win; [sameAppOnly] hides the others. */
    fun suggest(textBefore: String, fieldLabel: String, minChars: Int = 3, multiline: Boolean = false, maxWords: Int = 12,
                app: String = "", sameAppOnly: Boolean = false): Hit? {
        shortcutHit(textBefore)?.let { return it }
        if (textBefore.isBlank()) {
            if (multiline || isComposeLabel(fieldLabel)) return null
            val f = normalizeField(fieldLabel)
            val best = if (f.isEmpty()) null else byField[f]?.firstOrNull() ?: return null
            return best?.let { Hit(it.text, note = "Your usual ${fieldLabel.trim().trimEnd(':', '*')}", kind = "recall") }
        }
        val fragment = currentFragment(textBefore)
        if (fragment.length < maxOf(minChars, 6)) return null
        val lower = fragment.lowercase()
        val fieldNorm = normalizeField(fieldLabel)
        var best: Phrase? = null
        var bestScore = 0.0
        for (p in phrases) {
            if (!p.trusted || p.lower.length <= lower.length + 1 || !p.lower.startsWith(lower)) continue
            val sameApp = app.isNotEmpty() && p.app == app
            if (sameAppOnly && app.isNotEmpty() && !sameApp && !p.manual) continue
            val s = p.score + (if (fieldNorm.isNotEmpty() && p.field == fieldNorm) 5 else 0) + (if (sameApp) 6 else 0)
            if (best == null || s > bestScore) { best = p; bestScore = s }
        }
        val p = best ?: return null
        val rest = firstWords(p.text.substring(fragment.length), maxWords)
        return if (rest.isBlank()) null else Hit(rest)
    }

    private fun shortcutHit(textBefore: String): Hit? {
        val sc = shortcuts
        if (sc.isEmpty()) return null
        val token = textBefore.split(Regex("""\s""")).lastOrNull().orEmpty()
        if (token.length < 2 || token[0] != SHORTCUT_PREFIX || !textBefore.endsWith(token)) return null
        val key = MemoryStore.normalizeShortcut(token.drop(1))
        val text = sc[key] ?: sc.filterKeys { it.startsWith(key) }.values.singleOrNull() ?: return null
        return Hit(text, replaceLength = token.length, note = "$SHORTCUT_PREFIX$key", kind = "shortcut")
    }

    companion object {
        const val SHORTCUT_PREFIX = ';'
        const val MAX_RECALL = 120
        private val composeLabel = Regex("""\b(message|reply|comment|chat|ask|prompt|write|compose|post|note|search|type (a|your)|send|caption|status)\b""", RegexOption.IGNORE_CASE)

        fun isComposeLabel(label: String) = composeLabel.containsMatchIn(label)

        fun normalizeField(s: String) = s.lowercase().replace(Regex("""[^a-z0-9 ]+"""), " ").replace(Regex("""\s+"""), " ").trim()

        fun sentences(text: String): List<String> {
            val it = BreakIterator.getSentenceInstance(Locale.getDefault())
            it.setText(text)
            val out = ArrayList<String>()
            var start = it.first()
            var end = it.next()
            while (end != BreakIterator.DONE) {
                text.substring(start, end).trim().takeIf { s -> s.isNotEmpty() }?.let(out::add)
                start = end; end = it.next()
            }
            return out
        }

        fun currentFragment(textBefore: String): String {
            val idx = textBefore.indexOfLast { it in "\n.!?" }
            return (if (idx < 0) textBefore else textBefore.substring(idx + 1)).trimStart()
        }

        fun firstWords(s: String, max: Int): String {
            var words = 0
            var inWord = false
            for (i in s.indices) {
                if (s[i].isWhitespace()) inWord = false
                else if (!inWord) {
                    inWord = true
                    if (++words > max) return s.substring(0, i).trimEnd()
                }
            }
            return s
        }
    }
}
