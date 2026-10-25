package com.typepilot.android.telemetry

import com.typepilot.android.core.ProviderKind

/**
 * Turns what the app knows into the short, fixed values usage reporting sends. Everything
 * here maps to a closed list: no model hosts, no package names, no text ever leaves.
 */
object UsageMapping {
    /** Where an accepted suggestion came from (Suggestion.source). */
    fun source(s: String?): String = when (s) {
        "ai" -> "ai"
        "cache" -> "cache"
        "shortcut" -> "phrase"
        "fact", "memory", "recall" -> "memory"
        else -> "other"
    }

    /** RewriteMode name → the report's mode value. */
    fun rewriteMode(name: String?): String = when (name) {
        "GRAMMAR" -> "fix"
        "SENTENCES" -> "flow"
        "BEST" -> "best"
        "TRANSLATE_HI" -> "hindi"
        "TRANSLATE_EN" -> "english"
        null, "" -> "other"
        else -> name.lowercase().take(24)
    }

    /** The provider family only: never the host, model name or key. */
    fun provider(kind: ProviderKind, openAIBaseUrl: String): String = when (kind) {
        ProviderKind.GEMINI -> "gemini"
        ProviderKind.LOCAL -> "on_device"
        ProviderKind.OFF -> "none"
        ProviderKind.OPENAI -> {
            val url = openAIBaseUrl.lowercase()
            when {
                "groq.com" in url -> "groq"
                "openrouter.ai" in url -> "openrouter"
                ":11434" in url || "ollama" in url -> "ollama"
                else -> "openai_compatible"
            }
        }
    }

    /** The default keyboard's family from its package name. */
    fun keyboard(imePackage: String, ownPackage: String): String = when {
        imePackage == ownPackage -> "typepilot"
        imePackage.startsWith("com.google.android.inputmethod") -> "gboard"
        imePackage.startsWith("com.touchtype.swiftkey") -> "swiftkey"
        imePackage.startsWith("com.samsung.android.honeyboard") -> "samsung"
        else -> "other"
    }

    /** A daily counter's key: day, name and props, so one row per combination per day. */
    fun counterKey(day: String, name: String, props: Map<String, String>): String =
        buildString {
            append(day).append('|').append(name)
            for ((k, v) in props.toSortedMap()) append('|').append(k).append('=').append(v)
        }

    fun parseCounterKey(key: String): Triple<String, String, Map<String, String>>? {
        val parts = key.split('|')
        if (parts.size < 2) return null
        val props = parts.drop(2).mapNotNull { p ->
            val i = p.indexOf('=')
            if (i <= 0) null else p.substring(0, i) to p.substring(i + 1)
        }.toMap()
        return Triple(parts[0], parts[1], props)
    }

    /** A stable, short fingerprint for grouping crashes: exception type plus the top app frames. */
    fun crashFingerprint(t: Throwable): String {
        // Release builds are minified, so app frames may not keep their package: fall back to the top frames.
        val own = t.stackTrace.filter { it.className.startsWith("com.typepilot") }
        val frames = (own.ifEmpty { t.stackTrace.toList() }).take(3)
            .joinToString(";") { "${it.className}.${it.methodName}" }
        val raw = "${t.javaClass.name}|$frames"
        return Integer.toHexString(raw.hashCode()).padStart(8, '0')
    }
}
