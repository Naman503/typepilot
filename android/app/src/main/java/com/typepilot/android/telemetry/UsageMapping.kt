package com.typepilot.android.telemetry

import com.typepilot.android.core.AppSettings
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

    /** Where the app's own setting lives: the provider family for a picked ProviderKind (writing/completion). */
    fun provider(s: AppSettings, kind: ProviderKind): String = provider(kind, s.openAIBaseUrl)

    /** ModelException.Kind name (or anything else) → a fixed error category. */
    fun errorCategory(kind: String?): String = when (kind) {
        "NOT_CONFIGURED" -> "not_configured"
        "AUTH" -> "auth"
        "RATE_LIMIT" -> "rate_limit"
        "NETWORK" -> "network"
        "TIMEOUT" -> "timeout"
        "BAD_RESPONSE" -> "bad_response"
        "MODEL_MISSING" -> "model_missing"
        "BACKGROUND_BLOCKED" -> "background_blocked"
        else -> "other"
    }

    /** Response times: a handful of fixed buckets, never the exact value. */
    fun latency(ms: Long): String = when {
        ms <= 0 -> "none"
        ms < 100 -> "lt100ms"
        ms < 300 -> "100_300ms"
        ms < 700 -> "300_700ms"
        ms < 1_500 -> "700ms_1500ms"
        ms < 3_000 -> "1500ms_3s"
        ms < 6_000 -> "3_6s"
        else -> "gt6s"
    }

    /** How long something stayed open (an app visit, a field you typed in). */
    fun duration(ms: Long): String = when {
        ms < 10_000 -> "lt10s"
        ms < 30_000 -> "10_30s"
        ms < 120_000 -> "30s_2m"
        ms < 300_000 -> "2_5m"
        ms < 900_000 -> "5_15m"
        ms < 3_600_000 -> "15_60m"
        else -> "gt60m"
    }

    /** Sizes of collections (memory entries, apps in a list…). */
    fun count(n: Int): String = when {
        n <= 0 -> "0"
        n <= 10 -> "1_10"
        n <= 50 -> "11_50"
        n <= 200 -> "51_200"
        n <= 1000 -> "201_1000"
        else -> "gt1000"
    }

    /** Download sizes of on-device models. */
    fun sizeMb(mb: Long): String = when {
        mb <= 0 -> "unknown"
        mb < 700 -> "lt700mb"
        mb < 1_500 -> "700mb_1500mb"
        mb < 3_000 -> "1500mb_3gb"
        else -> "gt3gb"
    }

    /** A document's kind from its mime type or extension (never its name). */
    fun docType(mime: String): String {
        val m = mime.lowercase()
        return when {
            "pdf" in m -> "pdf"
            "wordprocessingml" in m || m == "docx" -> "docx"
            "json" in m -> "json"
            m.startsWith("text/") || m in setOf("txt", "md", "csv") -> "text"
            else -> "other"
        }
    }

    /**
     * The kind of app a field is in, decided on the phone from its package name. Only the category is sent,
     * never the package. Anything not clearly a chat, email or browser app is "other".
     */
    fun appCategory(pkg: String?): String {
        val p = pkg.orEmpty().lowercase()
        if (p.isEmpty()) return "none"
        if (p == "com.typepilot.android") return "typepilot"
        if (p in CHAT || CHAT_PREFIX.any { p.startsWith(it) }) return "chat"
        if (p in EMAIL || "mail" in p) return "email"
        if (p in BROWSER || BROWSER_PREFIX.any { p.startsWith(it) } || "browser" in p) return "browser"
        return "other"
    }

    private val CHAT = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web", "org.thoughtcrime.securesms",
        "com.facebook.orca", "com.facebook.mlite", "com.instagram.android", "com.snapchat.android", "com.discord", "com.slack",
        "com.microsoft.teams", "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
        "jp.naver.line.android", "com.viber.voip", "com.tencent.mm", "com.kakao.talk", "com.google.android.apps.dynamite",
        "com.linkedin.android", "com.twitter.android", "com.truecaller", "us.zoom.videomeetings", "im.vector.app", "chat.rocket.android",
    )
    private val CHAT_PREFIX = listOf("org.telegram.", "com.whatsapp", "com.discord", "com.slack")
    private val EMAIL = setOf(
        "com.google.android.gm", "com.microsoft.office.outlook", "com.samsung.android.email.provider", "ch.protonmail.android",
        "com.yahoo.mobile.client.android.mail", "com.readdle.spark", "com.fsck.k9", "com.easilydo.mail", "me.bluemail.mail",
        "com.zoho.mail", "net.thunderbird.android",
    )
    private val BROWSER = setOf(
        "com.android.chrome", "org.mozilla.firefox", "org.mozilla.focus", "com.brave.browser", "com.microsoft.emmx",
        "com.sec.android.app.sbrowser", "com.opera.browser", "com.opera.mini.native", "com.duckduckgo.mobile.android",
        "com.vivaldi.browser", "com.kiwibrowser.browser", "com.UCMobile.intl", "com.mi.globalbrowser", "com.heytap.browser",
        "com.vivo.browser", "com.google.android.googlequicksearchbox",
    )
    private val BROWSER_PREFIX = listOf("com.chrome.", "org.mozilla.", "com.brave.", "com.opera.")

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    /** Gemini model: one of the app's own suggestions, or something the user typed (never sent). */
    fun geminiChoice(model: String, suggested: List<String>): String = if (model in suggested) "preset" else "custom"

    /**
     * What changed between two settings, as (key, value) pairs. Values are fixed words only: on/off, an enum,
     * a provider family, "added"/"removed" for app lists, "changed" for numbers and text (never the text).
     */
    fun settingChanges(a: AppSettings, b: AppSettings, now: Long = System.currentTimeMillis(), suggestedGemini: List<String> = emptyList()): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        fun bool(key: String, x: Boolean, y: Boolean) { if (x != y) out += key to onOff(y) }
        fun changed(key: String, x: Any, y: Any) { if (x != y) out += key to "changed" }
        fun set(key: String, x: Set<String>, y: Set<String>) { if (x != y) out += key to (if (y.size >= x.size) "added" else "removed") }
        bool("enabled", a.enabled, b.enabled)
        val pa = a.pausedUntil > now; val pb = b.pausedUntil > now
        if (a.pausedUntil != b.pausedUntil && pa != pb) out += "paused" to onOff(pb)
        bool("overlay_suggestions", a.overlaySuggestions, b.overlaySuggestions)
        bool("ai_bubble", a.aiBubble, b.aiBubble)
        bool("suggest_on_focus", a.suggestOnFocus, b.suggestOnFocus)
        bool("suggest_on_empty_fields", a.suggestOnEmptyFields, b.suggestOnEmptyFields)
        changed("debounce", a.debounceMs, b.debounceMs)
        changed("max_words", a.maxWords, b.maxWords)
        changed("min_chars", a.minChars, b.minChars)
        bool("use_screen_context", a.useScreenContext, b.useScreenContext)
        if (a.aboutMe != b.aboutMe) out += "about_me" to (if (b.aboutMe.isBlank()) "empty" else "set")
        if (a.writeLength != b.writeLength) out += "write_length" to b.writeLength.name.lowercase()
        bool("haptics", a.haptics, b.haptics)
        if (a.bubbleOnEdge != b.bubbleOnEdge) out += "bubble_style" to (if (b.bubbleOnEdge) "edge" else "field")
        if ((a.bubbleX >= 0) != (b.bubbleX >= 0) || (b.bubbleX >= 0 && (a.bubbleX != b.bubbleX || a.bubbleY != b.bubbleY)))
            out += "bubble_position" to (if (b.bubbleX >= 0) "custom" else "auto")
        if (a.assistantY != b.assistantY) out += "assistant_position" to (if (b.assistantY >= 0) "custom" else "auto")
        if (a.completionProvider != b.completionProvider) out += "completion_provider" to provider(b, b.completionProvider)
        if (a.writingProvider != b.writingProvider) out += "writing_provider" to provider(b, b.writingProvider)
        if (a.geminiModel != b.geminiModel) out += "gemini_model" to geminiChoice(b.geminiModel, suggestedGemini)
        if (a.openAIBaseUrl != b.openAIBaseUrl) out += "openai_server" to provider(ProviderKind.OPENAI, b.openAIBaseUrl)
        changed("openai_model", a.openAIModel, b.openAIModel)
        changed("local_model", a.localModelId, b.localModelId)
        changed("local_writing_model", a.localWritingModelId, b.localWritingModelId)
        if (a.localBackend != b.localBackend) out += "local_backend" to b.localBackend.name.lowercase()
        changed("local_idle_unload", a.localIdleUnloadMin, b.localIdleUnloadMin)
        changed("request_timeout", a.requestTimeoutMs, b.requestTimeoutMs)
        bool("memory_enabled", a.memoryEnabled, b.memoryEnabled)
        bool("learn_from_typing", a.learnFromTyping, b.learnFromTyping)
        bool("memory_in_prompts", a.memoryInPrompts, b.memoryInPrompts)
        bool("learn_facts", a.learnFacts, b.learnFacts)
        bool("learn_corrections", a.learnCorrections, b.learnCorrections)
        changed("fact_min_percent", a.factMinPercent, b.factMinPercent)
        changed("fact_min_uses", a.factMinUses, b.factMinUses)
        bool("fact_show_percent", a.factShowPercent, b.factShowPercent)
        bool("prefer_same_app", a.preferSameApp, b.preferSameApp)
        bool("same_app_only", a.sameAppOnly, b.sameAppOnly)
        bool("ai_alongside_memory", a.aiAlongsideMemory, b.aiAlongsideMemory)
        set("excluded_apps", a.excludedPackages, b.excludedPackages)
        if (a.appMode != b.appMode) out += "app_mode" to b.appMode.name.lowercase()
        set("included_apps", a.includedPackages, b.includedPackages)
        bool("smart_switch", a.smartSwitch, b.smartSwitch)
        set("hidden_apps", a.hiddenPackages, b.hiddenPackages)
        bool("metrics_enabled", a.metricsEnabled, b.metricsEnabled)
        bool("share_usage", a.shareUsage, b.shareUsage)
        bool("verbose_logs", a.verboseLogs, b.verboseLogs)
        return out
    }

    /**
     * The once-a-day configuration snapshot: several events of at most 8 props each, all fixed words,
     * booleans as on/off, numbers as buckets. [extra] adds facts the settings object doesn't hold.
     */
    fun settingsSnapshot(s: AppSettings, now: Long = System.currentTimeMillis(), suggestedGemini: List<String> = emptyList()): Map<String, Map<String, String>> = mapOf(
        "settings_suggest" to mapOf(
            "overlay" to onOff(s.overlaySuggestions), "bubble" to onOff(s.aiBubble), "bubble_style" to (if (s.bubbleOnEdge) "edge" else "field"),
            "on_focus" to onOff(s.suggestOnFocus), "empty_fields" to onOff(s.suggestOnEmptyFields), "screen_context" to onOff(s.useScreenContext),
            "haptics" to onOff(s.haptics), "write_length" to s.writeLength.name.lowercase(),
        ),
        "settings_tuning" to mapOf(
            "debounce" to when { s.debounceMs < 150 -> "fast"; s.debounceMs <= 200 -> "default"; s.debounceMs <= 400 -> "slow"; else -> "very_slow" },
            "max_words" to when { s.maxWords <= 5 -> "short"; s.maxWords <= 10 -> "default"; else -> "long" },
            "min_chars" to when { s.minChars <= 2 -> "1_2"; s.minChars <= 4 -> "3_4"; else -> "5_plus" },
            "timeout" to when { s.requestTimeoutMs <= 8_000 -> "short"; s.requestTimeoutMs <= 20_000 -> "default"; else -> "long" },
            "about_me" to (if (s.aboutMe.isBlank()) "empty" else "set"),
            "bubble_position" to (if (s.bubbleX >= 0) "custom" else "auto"),
            "assistant_position" to (if (s.assistantY >= 0) "custom" else "auto"),
        ),
        "settings_ai" to mapOf(
            "completion" to provider(s, s.completionProvider), "writing" to provider(s, s.writingProvider),
            "gemini_model" to geminiChoice(s.geminiModel, suggestedGemini), "local_backend" to s.localBackend.name.lowercase(),
            "local_model" to (if (s.localModelId.isBlank()) "none" else "set"),
            "local_writing_model" to (if (s.localWritingModelId.isBlank()) "same" else "set"),
        ),
        "settings_memory" to mapOf(
            "memory" to onOff(s.memoryEnabled), "learn_typing" to onOff(s.learnFromTyping), "in_prompts" to onOff(s.memoryInPrompts),
            "learn_facts" to onOff(s.learnFacts), "learn_corrections" to onOff(s.learnCorrections),
            "fact_min_percent" to when { s.factMinPercent < 50 -> "lt50"; s.factMinPercent < 70 -> "50_69"; else -> "70_plus" },
            "fact_min_uses" to s.factMinUses.coerceIn(1, 5).toString(), "show_percent" to onOff(s.factShowPercent),
        ),
        "settings_apps" to mapOf(
            "app_mode" to s.appMode.name.lowercase(), "excluded" to count(s.excludedPackages.size), "included" to count(s.includedPackages.size),
            "smart_switch" to onOff(s.smartSwitch), "hidden" to count(s.hiddenPackages.size),
            "prefer_same_app" to onOff(s.preferSameApp), "same_app_only" to onOff(s.sameAppOnly), "ai_alongside" to onOff(s.aiAlongsideMemory),
        ),
        "settings_general" to mapOf(
            "enabled" to onOff(s.enabled), "paused" to onOff(s.pausedUntil > now), "local_stats" to onOff(s.metricsEnabled),
            "verbose_logs" to onOff(s.verboseLogs), "disclosure" to onOff(s.disclosureAccepted),
        ),
    )

    /** The API's prop rules: at most 8 keys, snake_case keys, values up to 64 chars, and no '|' or '=' (counter keys). */
    fun cleanProps(p: Map<String, String>): Map<String, String> =
        p.entries.filter { KEY.matches(it.key) }.take(8).associate { it.key to it.value.replace('|', '_').replace('=', '_').take(64) }

    private val KEY = Regex("^[a-z][a-z0-9_]{0,31}$")

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
