package com.typepilot.android

import android.app.Application
import com.typepilot.android.ai.LocalModelManager
import com.typepilot.android.ai.ModelDownloader
import com.typepilot.android.core.Log
import com.typepilot.android.core.Secrets
import com.typepilot.android.core.SettingsStore
import com.typepilot.android.engine.AssistantEngine
import com.typepilot.android.memory.MemoryStore
import com.typepilot.android.metrics.MetricsStore
import com.typepilot.android.metrics.PerfSampler
import com.typepilot.android.telemetry.Usage
import com.typepilot.android.telemetry.UsageMapping
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Process-wide singletons. Everything is created lazily-cheap: no disk or network work happens here. */
class TypePilotApp : Application() {
    lateinit var settings: SettingsStore; private set
    lateinit var secrets: Secrets; private set
    lateinit var memory: MemoryStore; private set
    lateinit var metrics: MetricsStore; private set
    lateinit var local: LocalModelManager; private set
    lateinit var downloader: ModelDownloader; private set
    lateinit var engine: AssistantEngine; private set
    lateinit var perf: PerfSampler; private set
    lateinit var facts: com.typepilot.android.knowledge.FactStore; private set
    lateinit var knowledge: com.typepilot.android.knowledge.KnowledgeBase; private set
    lateinit var usage: com.typepilot.android.telemetry.UsageReporter; private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        com.typepilot.android.core.Backup.applyStaged(this) // a restore picked in Settings, before anything opens its files
        settings = SettingsStore(this)
        secrets = Secrets(this)
        memory = MemoryStore(this)
        metrics = MetricsStore(this)
        local = LocalModelManager(this)
        downloader = ModelDownloader(local) { secrets.get(Secrets.HUGGINGFACE) }
        facts = com.typepilot.android.knowledge.FactStore(this)
        engine = AssistantEngine(settings, secrets, memory, metrics, local, facts)
        knowledge = com.typepilot.android.knowledge.KnowledgeBase(this, facts) { engine }
        perf = PerfSampler(this, metrics)
        Log.verbose = settings.value.verboseLogs
        scope.launch { settings.state.collect { metrics.enabled = it.metricsEnabled } }
        usage = com.typepilot.android.telemetry.UsageReporter(
            this, { settings.value }, scope,
            dailyFacts = ::dailyUsageFacts,
            setupDone = {
                val s = settings.value
                buildSet {
                    if (s.disclosureAccepted) add("setup_disclosure")
                    if (com.typepilot.android.ui.Setup.accessibilityOn(this@TypePilotApp)) add("setup_accessibility")
                    if (com.typepilot.android.ui.Setup.keyboardEnabled(this@TypePilotApp)) add("setup_keyboard")
                    if (com.typepilot.android.ui.Setup.batteryUnrestricted(this@TypePilotApp)) add("setup_background")
                    if (s.completionProvider != com.typepilot.android.core.ProviderKind.OFF && engine.modelFor(s.completionProvider)?.configError() == null) add("setup_model")
                }
            },
        )
        Usage.reporter = usage
        Log.onError = { area, t -> usage.nonFatal(t, area) }
        metrics.observer = usage::onMetric
        usage.start()
        scope.launch {
            var was = settings.value
            settings.state.collect {
                if (it.shareUsage != was.shareUsage) { if (it.shareUsage) usage.turnedOn() else usage.turnedOff() }
                // Which settings people change: keys and fixed words only (counted per day, so typing in a box is one row).
                for ((key, value) in UsageMapping.settingChanges(was, it, suggestedGemini = com.typepilot.android.ai.GeminiModel.suggestedModels))
                    Usage.count("setting_changed", mapOf("key" to key, "value" to value))
                was = it
            }
        }
        scope.launch { Usage.accessibilityState(com.typepilot.android.ui.Setup.accessibilityOn(this@TypePilotApp), "detected") }
        // If the smart switch left accessibility off and the process died (update, phone maker cleanup), fix it now.
        scope.launch { com.typepilot.android.service.SwitchRecovery.ensureOn(this@TypePilotApp, "app start") }
        Log.i("app", "TypePilot ${BuildConfig.VERSION_NAME} started")
    }

    /** Once a day (on the reporting thread): configuration and state as fixed words and buckets. */
    private fun dailyUsageFacts(): Map<String, Map<String, String>> {
        val s = settings.value
        val ime = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
            .orEmpty().substringBefore('/')
        val setup = com.typepilot.android.ui.Setup
        fun onOff(b: Boolean) = if (b) "on" else "off"
        val notifications = runCatching { getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled() }.getOrDefault(false)
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val memoryEntries = runCatching { memory.all() }.getOrDefault(emptyList())
        val docs = runCatching { facts.docs().size }.getOrDefault(0)
        val factCount = runCatching { facts.all().size }.getOrDefault(0)
        val installedModels = runCatching { local.installedIds().size }.getOrDefault(0)
        return UsageMapping.settingsSnapshot(s, suggestedGemini = com.typepilot.android.ai.GeminiModel.suggestedModels) + mapOf(
            "model_used" to mapOf("provider" to UsageMapping.provider(s.completionProvider, s.openAIBaseUrl)),
            "keyboard_used" to mapOf("keyboard" to UsageMapping.keyboard(ime, packageName)),
            "permissions" to mapOf(
                "accessibility" to onOff(setup.accessibilityOn(this)), "keyboard_enabled" to onOff(setup.keyboardEnabled(this)),
                "keyboard_selected" to onOff(setup.keyboardSelected(this)), "battery_unrestricted" to onOff(setup.batteryUnrestricted(this)),
                "notifications" to onOff(notifications), "usage_access" to onOff(com.typepilot.android.service.A11ySwitch.hasUsageAccess(this)),
                "secure_settings" to onOff(com.typepilot.android.service.A11ySwitch.canToggle(this)),
                "aggressive_oem" to onOff(setup.aggressiveOem),
            ),
            "memory_size" to mapOf(
                "phrases" to UsageMapping.count(memoryEntries.size), "shortcuts" to UsageMapping.count(memoryEntries.count { it.shortcut.isNotBlank() }),
                "pinned" to UsageMapping.count(memoryEntries.count { it.pinned }),
                "manual" to UsageMapping.count(memoryEntries.count { it.kind == com.typepilot.android.memory.MemoryEntry.Kind.MANUAL }),
                "facts" to UsageMapping.count(factCount), "documents" to UsageMapping.count(docs),
            ),
            "keys_and_models" to mapOf(
                "gemini_key" to onOff(!secrets.get(Secrets.GEMINI).isNullOrBlank()), "openai_key" to onOff(!secrets.get(Secrets.OPENAI).isNullOrBlank()),
                "hf_token" to onOff(!secrets.get(Secrets.HUGGINGFACE).isNullOrBlank()), "local_models" to UsageMapping.count(installedModels),
            ),
            "keyboard_theme" to mapOf("theme" to if (dark) "dark" else "light"),
        )
    }

    companion object {
        lateinit var instance: TypePilotApp; private set
    }
}
