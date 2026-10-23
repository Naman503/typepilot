package com.typepilot.android.engine

/** Everything the engine knows about the field being typed in. */
data class FieldContext(
    val packageName: String = "",
    val appName: String = "",
    val fieldLabel: String = "",
    val textBefore: String = "",
    val textAfter: String = "",
    val selectedText: String = "",
    val isMultiline: Boolean = false,
    val screenContext: String = "",
    /** The question the field asks, from its label or the text right above it ("What is your notice period?"). */
    val question: String = "",
)

enum class SuggestTrigger { TYPING, FOCUS }

data class Suggestion(
    val text: String,
    /** memory | shortcut | recall | ai | cache */
    val source: String,
    val replaceLength: Int = 0,
    val note: String? = null,
    val provider: String = "",
    val engineMs: Long = 0,
    val ttftMs: Long = 0,
    /** Fact id for "fact" suggestions (accept/dismiss feed back into its confidence). */
    val refId: Long = 0,
    val percent: Int = 0,
    /** Other likely answers (memory), shown as a short list under the best one. */
    val alternatives: List<Suggestion> = emptyList(),
) {
    val words: Int get() = text.trim().split(Regex("""\s+""")).count { it.isNotEmpty() }
}

/** One-tap rewrites. The first three are the headline options. */
enum class RewriteMode(val title: String, val subtitle: String, val emoji: String, val instruction: String, val primary: Boolean = false, val temperature: Double = 0.3) {
    GRAMMAR("Fix grammar", "Spelling, grammar & punctuation only", "✓",
        "Fix only grammar, spelling, punctuation and capitalization. Keep the wording, tone, language and meaning exactly as they are; change as little as possible.",
        primary = true, temperature = 0.1),
    SENTENCES("Grammar + flow", "Better sentences, same meaning", "≋",
        "Fix grammar and improve sentence formation: restructure, split or join sentences so it reads clearly and naturally. Keep the meaning, tone and language. Don't add information.",
        primary = true, temperature = 0.3),
    BEST("Best version", "Polished, clear and natural", "✦",
        "Rewrite it into the best version of itself: clear, natural, confident and well-structured, keeping every fact and the intent. Fit the context: a chat message stays short and casual, an email stays an email.",
        primary = true, temperature = 0.5),
    SHORTER("Shorter", "", "−", "Make it noticeably shorter and tighter, keeping the key point."),
    LONGER("Longer", "", "+", "Make it somewhat longer and more complete, without inventing facts."),
    FORMAL("Formal", "", "👔", "Make it formal and professional."),
    FRIENDLY("Friendly", "", "😊", "Make it warmer and friendlier."),
    CONFIDENT("Confident", "", "💪", "Make it more confident and direct; remove hedging."),
    SIMPLER("Simpler", "", "🧩", "Use simpler words and shorter sentences so anyone can understand it."),
    BULLETS("Bullet points", "", "•", "Rewrite it as concise bullet points (one idea per line, starting with •)."),
    EMOJIFY("Add emoji", "", "✨", "Keep the text and add a few fitting emoji where they feel natural. Don't overdo it."),
    TRANSLATE_EN("English", "", "🇬🇧", "Translate it into natural English. Keep the tone."),
    TRANSLATE_HI("हिन्दी", "", "🇮🇳", "Translate it into natural Hindi (Devanagari script). Keep the tone."),
    HINGLISH("Hinglish", "", "💬", "Rewrite it in natural, casual Hinglish (Hindi in Latin script mixed with English), as people text in India."),
    CUSTOM("Custom", "Your own instruction", "✎", ""),
}
