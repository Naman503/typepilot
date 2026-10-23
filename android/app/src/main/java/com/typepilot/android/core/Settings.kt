package com.typepilot.android.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class ProviderKind(val title: String, val isLocal: Boolean) {
    GEMINI("Gemini (Google)", false),
    OPENAI("OpenAI-compatible (Groq, OpenRouter, Ollama…)", false),
    LOCAL("On-device model", true),
    OFF("Memory only (no AI)", true),
}

enum class WriteLength(val title: String, val instruction: String) {
    AUTO("Auto", "A natural length for this app and request."),
    SHORT("Short", "Keep it short: 1–2 sentences."),
    MEDIUM("Medium", "A short paragraph: 3–5 sentences."),
    LONG("Long", "Detailed: a few paragraphs if useful."),
}

/** AUTO: the backend each model was built for (many INT4 files are GPU specialists). */
enum class LocalBackend { AUTO, CPU, GPU }

/** Where TypePilot appears: everywhere except the excluded apps, or only in the apps you pick. */
enum class AppMode { ALL_EXCEPT, ONLY_SELECTED }

/** Everything the user can change. Stored as one JSON blob; unknown/missing keys fall back to defaults. */
data class AppSettings(
    val enabled: Boolean = true,
    val pausedUntil: Long = 0,
    // Suggestions
    val overlaySuggestions: Boolean = true,
    val aiBubble: Boolean = true,
    val suggestOnFocus: Boolean = true,
    val suggestOnEmptyFields: Boolean = true,
    val debounceMs: Int = 160,
    val maxWords: Int = 8,
    val minChars: Int = 3,
    val useScreenContext: Boolean = true,
    val aboutMe: String = "",
    val writeLength: WriteLength = WriteLength.AUTO,
    val haptics: Boolean = true,
    /** Where you dragged the ✦ button (fractions of the screen), or -1 = next to each field. */
    /** The ✦ as a translucent tab docked to the screen edge (true) or a circle next to the field (false). */
    val bubbleOnEdge: Boolean = true,
    val bubbleX: Float = -1f,
    val bubbleY: Float = -1f,
    /** Where you dragged the assistant popup (fraction of screen height for its top), or -1 = automatic. */
    val assistantY: Float = -1f,
    // Providers
    val completionProvider: ProviderKind = ProviderKind.GEMINI,
    val writingProvider: ProviderKind = ProviderKind.GEMINI,
    val geminiModel: String = "gemini-3.5-flash-lite",
    val openAIBaseUrl: String = "https://api.groq.com/openai/v1",
    val openAIModel: String = "llama-3.1-8b-instant",
    val localModelId: String = "",
    /** On-device model for Rewrite / Write; empty = same as [localModelId]. */
    val localWritingModelId: String = "",
    val localBackend: LocalBackend = LocalBackend.AUTO,
    val localIdleUnloadMin: Int = 5,
    val requestTimeoutMs: Int = 15_000,
    // Memory & privacy
    val memoryEnabled: Boolean = true,
    val learnFromTyping: Boolean = true,
    val memoryInPrompts: Boolean = true,
    // Knowledge memory (facts)
    /** Learn question → answer from what you type into form fields. */
    val learnFacts: Boolean = true,
    /** When you change an answer TypePilot filled in, trust the old one less and learn the new one. */
    val learnCorrections: Boolean = true,
    /** Show a fact only when it's at least this likely (%) to be the answer. */
    val factMinPercent: Int = 55,
    /** Times you must type an answer before it's offered (1 = after the first time). */
    val factMinUses: Int = 1,
    val factShowPercent: Boolean = true,
    /** Prefer answers and phrases typed in the same app (WhatsApp text comes back in WhatsApp first). */
    val preferSameApp: Boolean = true,
    /** Strict: what you typed in an app is only suggested in that app. */
    val sameAppOnly: Boolean = false,
    /** Next to memory answers, also offer an AI answer for real questions ("Why do you want this job?"). */
    val aiAlongsideMemory: Boolean = true,
    val excludedPackages: Set<String> = DEFAULT_EXCLUDED,
    val appMode: AppMode = AppMode.ALL_EXCEPT,
    val includedPackages: Set<String> = emptySet(),
    /** Smart switch: turn the accessibility service fully off in [hiddenPackages] (payment apps), and back on after. */
    val smartSwitch: Boolean = false,
    val hiddenPackages: Set<String> = emptySet(),
    /** With "Only selected apps": accessibility is fully off everywhere else too. */
    val offOutsideSelected: Boolean = false,
    // Metrics
    val metricsEnabled: Boolean = true,
    val verboseLogs: Boolean = false,
    val disclosureAccepted: Boolean = false,
) {
    val isPaused: Boolean get() = pausedUntil > System.currentTimeMillis()
    val isActive: Boolean get() = enabled && !isPaused

    /** Should TypePilot work in this app? */
    fun allows(pkg: String): Boolean = pkg !in hiddenPackages && when (appMode) {
        AppMode.ALL_EXCEPT -> pkg !in excludedPackages
        AppMode.ONLY_SELECTED -> pkg in includedPackages || pkg == "com.typepilot.android"
    }

    /** Should the accessibility service be completely off while this app is open? */
    fun hiddenIn(pkg: String): Boolean = smartSwitch && pkg != "com.typepilot.android" && pkg in hiddenPackages

    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled); put("pausedUntil", pausedUntil)
        put("overlaySuggestions", overlaySuggestions); put("aiBubble", aiBubble)
        put("suggestOnFocus", suggestOnFocus); put("suggestOnEmptyFields", suggestOnEmptyFields)
        put("debounceMs", debounceMs); put("maxWords", maxWords); put("minChars", minChars)
        put("useScreenContext", useScreenContext); put("aboutMe", aboutMe); put("writeLength", writeLength.name)
        put("haptics", haptics); put("bubbleOnEdge", bubbleOnEdge); put("bubbleX", bubbleX.toDouble()); put("bubbleY", bubbleY.toDouble()); put("assistantY", assistantY.toDouble())
        put("completionProvider", completionProvider.name); put("writingProvider", writingProvider.name)
        put("geminiModel", geminiModel); put("openAIBaseUrl", openAIBaseUrl); put("openAIModel", openAIModel)
        put("localModelId", localModelId); put("localWritingModelId", localWritingModelId); put("localBackend", localBackend.name); put("localIdleUnloadMin", localIdleUnloadMin)
        put("requestTimeoutMs", requestTimeoutMs)
        put("memoryEnabled", memoryEnabled); put("learnFromTyping", learnFromTyping); put("memoryInPrompts", memoryInPrompts)
        put("learnFacts", learnFacts); put("learnCorrections", learnCorrections); put("factMinPercent", factMinPercent)
        put("factMinUses", factMinUses); put("factShowPercent", factShowPercent)
        put("preferSameApp", preferSameApp); put("sameAppOnly", sameAppOnly); put("aiAlongsideMemory", aiAlongsideMemory)
        put("excludedPackages", JSONArray(excludedPackages.toList()))
        put("appMode", appMode.name); put("includedPackages", JSONArray(includedPackages.toList()))
        put("smartSwitch", smartSwitch); put("hiddenPackages", JSONArray(hiddenPackages.toList())); put("offOutsideSelected", offOutsideSelected)
        put("metricsEnabled", metricsEnabled); put("verboseLogs", verboseLogs); put("disclosureAccepted", disclosureAccepted)
    }

    companion object {
        /** Password managers, banking-style secure apps and our own settings never get suggestions. */
        val DEFAULT_EXCLUDED = setOf(
            "com.google.android.apps.authenticator2", "com.x8bit.bitwarden", "com.onepassword.android",
            "com.lastpass.lpandroid", "com.dashlane", "keepass2android.keepass2android", "com.kunzisoft.keepass.free",
            "com.android.systemui", "com.android.settings",
        )

        fun fromJson(o: JSONObject): AppSettings {
            val d = AppSettings()
            fun <T : Enum<T>> enum(key: String, values: Array<T>, def: T): T =
                values.firstOrNull { it.name == o.optString(key) } ?: def
            val excluded = o.optJSONArray("excludedPackages")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            val included = o.optJSONArray("includedPackages")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            val hidden = o.optJSONArray("hiddenPackages")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            return AppSettings(
                enabled = o.optBoolean("enabled", d.enabled), pausedUntil = o.optLong("pausedUntil", 0),
                overlaySuggestions = o.optBoolean("overlaySuggestions", d.overlaySuggestions), aiBubble = o.optBoolean("aiBubble", d.aiBubble),
                suggestOnFocus = o.optBoolean("suggestOnFocus", d.suggestOnFocus),
                suggestOnEmptyFields = o.optBoolean("suggestOnEmptyFields", d.suggestOnEmptyFields),
                debounceMs = o.optInt("debounceMs", d.debounceMs).let { if (it == 220) d.debounceMs else it }.coerceIn(80, 1500), // 220 was the old default
                maxWords = o.optInt("maxWords", d.maxWords).coerceIn(2, 30),
                minChars = o.optInt("minChars", d.minChars).coerceIn(1, 20),
                useScreenContext = o.optBoolean("useScreenContext", d.useScreenContext), aboutMe = o.optString("aboutMe", ""),
                writeLength = enum("writeLength", WriteLength.entries.toTypedArray(), d.writeLength),
                haptics = o.optBoolean("haptics", d.haptics),
                bubbleOnEdge = o.optBoolean("bubbleOnEdge", d.bubbleOnEdge),
                bubbleX = o.optDouble("bubbleX", -1.0).toFloat(), bubbleY = o.optDouble("bubbleY", -1.0).toFloat(),
                assistantY = o.optDouble("assistantY", -1.0).toFloat(),
                completionProvider = enum("completionProvider", ProviderKind.entries.toTypedArray(), d.completionProvider),
                writingProvider = enum("writingProvider", ProviderKind.entries.toTypedArray(), d.writingProvider),
                // Gemini 2.x Flash-Lite models are retired (404): move people to the current fastest one.
                geminiModel = o.optString("geminiModel", d.geminiModel).ifBlank { d.geminiModel }.let { if (it.startsWith("gemini-2")) d.geminiModel else it },
                openAIBaseUrl = o.optString("openAIBaseUrl", d.openAIBaseUrl), openAIModel = o.optString("openAIModel", d.openAIModel),
                localModelId = o.optString("localModelId", ""),
                localWritingModelId = o.optString("localWritingModelId", ""),
                localBackend = enum("localBackend", LocalBackend.entries.toTypedArray(), d.localBackend),
                localIdleUnloadMin = o.optInt("localIdleUnloadMin", d.localIdleUnloadMin).coerceIn(1, 120),
                requestTimeoutMs = o.optInt("requestTimeoutMs", d.requestTimeoutMs).coerceIn(3_000, 120_000),
                memoryEnabled = o.optBoolean("memoryEnabled", d.memoryEnabled), learnFromTyping = o.optBoolean("learnFromTyping", d.learnFromTyping),
                memoryInPrompts = o.optBoolean("memoryInPrompts", d.memoryInPrompts),
                learnFacts = o.optBoolean("learnFacts", d.learnFacts), learnCorrections = o.optBoolean("learnCorrections", d.learnCorrections),
                factMinPercent = o.optInt("factMinPercent", d.factMinPercent).coerceIn(20, 98),
                factMinUses = o.optInt("factMinUses", d.factMinUses).coerceIn(1, 5),
                factShowPercent = o.optBoolean("factShowPercent", d.factShowPercent),
                preferSameApp = o.optBoolean("preferSameApp", d.preferSameApp), sameAppOnly = o.optBoolean("sameAppOnly", d.sameAppOnly),
                aiAlongsideMemory = o.optBoolean("aiAlongsideMemory", d.aiAlongsideMemory),
                excludedPackages = excluded ?: d.excludedPackages,
                appMode = enum("appMode", AppMode.entries.toTypedArray(), d.appMode),
                includedPackages = included ?: emptySet(),
                smartSwitch = o.optBoolean("smartSwitch", false), hiddenPackages = hidden ?: emptySet(),
                // Retired: switching accessibility off in every unpicked app needed a watcher alive all day, which phones
                // kill. Unpicked apps are now just ignored; only Hidden (payment) apps switch it off.
                offOutsideSelected = false,
                metricsEnabled = o.optBoolean("metricsEnabled", d.metricsEnabled), verboseLogs = o.optBoolean("verboseLogs", false),
                disclosureAccepted = o.optBoolean("disclosureAccepted", false),
            )
        }
    }
}

/** Settings as a StateFlow: reads are a field access (the accessibility service reads on every event). */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state
    val value: AppSettings get() = _state.value

    private fun load(): AppSettings = try {
        prefs.getString("json", null)?.let { AppSettings.fromJson(JSONObject(it)) } ?: AppSettings()
    } catch (e: Exception) { AppSettings() }

    @Synchronized
    fun update(change: (AppSettings) -> AppSettings) {
        val next = change(_state.value)
        if (next == _state.value) return
        _state.value = next
        Log.verbose = next.verboseLogs
        prefs.edit().putString("json", next.toJson().toString()).apply()
    }
}
