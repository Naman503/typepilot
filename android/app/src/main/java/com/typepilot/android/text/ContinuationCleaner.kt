package com.typepilot.android.text

/**
 * Turns raw model output into a clean inline continuation of what the user typed.
 * Models repeat the user's text, wrap output in quotes, announce their answer ("Sure, here's…")
 * or run on; a suggestion must be one short chunk that starts exactly at the cursor.
 */
object ContinuationCleaner {
    private val preamble = Regex(
        """^\s*(sure|certainly|of course|okay|ok|absolutely|here('|’)s|here is|i can help)[^\n:]{0,120}:[ \t]*\n*""",
        RegexOption.IGNORE_CASE)
    private val think = Regex("""(?s)<think>.*?(</think>|$)""")
    /** The model repeated our prompt scaffolding instead of continuing (tiny models do this). */
    private val scaffold = Regex("""^\s*(continue with|text so far|app:|field:|on screen|now type|things the user|about the user|output only)""", RegexOption.IGNORE_CASE)
    private val meta = Regex(
        """^((sure|certainly|of course|okay)[ ,!.]*)?(here('|’)s|here is|i can help|as an ai)\b|\bthe user('|’)?s?\b|\b(suggestion|continuation)s?\b""",
        RegexOption.IGNORE_CASE)

    fun stripPreamble(s: String): String {
        val noThink = think.replace(s, "")
        val m = preamble.find(noThink) ?: return noThink
        return noThink.substring(m.range.last + 1)
    }

    fun clean(raw: String, textBefore: String, maxWords: Int, multiline: Boolean = false): String? {
        var s = stripPreamble(raw.replace("\r", ""))
        for (prefix in listOf("Continuation:", "Completion:", "Here's the continuation:", "Here is the continuation:")) {
            if (s.trimStart().startsWith(prefix, ignoreCase = true)) s = s.trimStart().substring(prefix.length)
        }
        s = s.replace("```", "")
        val t = s.trim()
        if (t.length >= 2 && t.first() in "\"“'" && t.last() in "\"”'") s = t.substring(1, t.length - 1)
        else if (t.isNotEmpty() && t.first() in "\"“" && t.drop(1).none { it in "\"”" }) s = t.substring(1)

        s = removeEchoedPrefix(s, textBefore)

        val nl = s.indexOf('\n')
        if (nl >= 0) {
            val head = s.substring(0, nl)
            if (!multiline || head.isNotBlank()) s = head
        }

        if (textBefore.isEmpty() || textBefore.last().isWhitespace()) {
            s = s.trimStart(' ', '\t')
        } else if (textBefore.last() in ".,!?;:" && s.firstOrNull()?.isLetter() == true) {
            s = " $s"
        }

        if (meta.containsMatchIn(s.trim()) || scaffold.containsMatchIn(s)) return null
        s = limitWords(s, maxWords)
        val visible = s.trim()
        if (visible.isEmpty()) return null
        if (visible.none { it.isLetterOrDigit() }) return null
        return s.replace("  ", " ")
    }

    fun removeEchoedPrefix(s: String, textBefore: String): String {
        val trimmed = s.trimStart(' ')
        val tail = textBefore.takeLast(200)
        if (tail.isEmpty()) return s
        val lowerOut = trimmed.lowercase()
        val lowerTail = tail.lowercase()
        var k = minOf(lowerTail.length, lowerOut.length)
        while (k >= 4) {
            if (lowerOut.startsWith(lowerTail.takeLast(k))) return trimmed.substring(k)
            k--
        }
        // Small models often restart with the user's last word: "…party. I" → " I will be there." → " will be there."
        if (!textBefore.last().isWhitespace()) {
            val lastWord = Regex("""(\S+)$""").find(tail)?.value?.lowercase()
            val firstWord = Regex("""^(\S+)""").find(trimmed)?.value?.lowercase()
            if (lastWord != null && lastWord == firstWord && s.startsWith(" ")) return trimmed.substring(firstWord.length)
        }
        return s
    }

    /** At most [max] words, preferring to end at a sentence boundary. */
    fun limitWords(s: String, max: Int): String {
        var words = 0
        var inWord = false
        var cut = s.length
        var lastSentenceEnd = -1
        for (i in s.indices) {
            val ch = s[i]
            if (ch.isWhitespace()) inWord = false
            else if (!inWord) {
                inWord = true
                words++
                if (words > max) { cut = i; break }
            }
            if (ch in ".!?") lastSentenceEnd = i + 1
        }
        var out = s.substring(0, cut)
        if (cut != s.length && lastSentenceEnd in 9..cut) out = s.substring(0, lastSentenceEnd)
        return out.trimEnd()
    }
}

/** Cleans drafts and rewrites: models love "[Your Name]" placeholders and echoing our scaffolding. */
object DraftCleaner {
    private val placeholder = Regex("""\s?\[(?:[A-Za-z][A-Za-z']*)(?:[ /-][A-Za-z']+){0,5}\]""")
    private val echoed = Regex(
        """^\s*(revise the draft|output only|instruction:|text to rewrite|current draft:|rewritten text:|original text:|"{3}|<<<|>>>)""",
        RegexOption.IGNORE_CASE)

    fun clean(text: String): String {
        // Tiny models sometimes answer in snake_case ("i_have_an_apple"): turn that back into words.
        val t0 = text.trim()
        if (t0.length > 12 && t0.none { it.isWhitespace() } && t0.count { it == '_' } >= 2) return clean(t0.replace('_', ' '))
        val lines = mutableListOf<String>()
        for (raw in ContinuationCleaner.stripPreamble(text).split("\n")) {
            if (echoed.containsMatchIn(raw)) {
                if (lines.any { it.isNotBlank() }) break else continue
            }
            val trimmed = raw.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length < 40) continue
            lines += placeholder.replace(raw, "").replace(Regex(""" +([,.;:!?])"""), "$1").replace(Regex("""  +"""), " ")
        }
        return lines.joinToString("\n").replace(Regex("""\n{3,}"""), "\n\n").trim()
    }
}
