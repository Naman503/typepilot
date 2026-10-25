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
            dailyFacts = {
                val s = settings.value
                val ime = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
                    .orEmpty().substringBefore('/')
                mapOf(
                    "model_used" to mapOf("provider" to com.typepilot.android.telemetry.UsageMapping.provider(s.completionProvider, s.openAIBaseUrl)),
                    "keyboard_used" to mapOf("keyboard" to com.typepilot.android.telemetry.UsageMapping.keyboard(ime, packageName)),
                )
            },
            setupDone = {
                val s = settings.value
                buildSet {
                    if (com.typepilot.android.ui.Setup.accessibilityOn(this@TypePilotApp)) add("setup_accessibility")
                    if (com.typepilot.android.ui.Setup.batteryUnrestricted(this@TypePilotApp)) add("setup_background")
                    if (s.completionProvider != com.typepilot.android.core.ProviderKind.OFF && engine.modelFor(s.completionProvider)?.configError() == null) add("setup_model")
                }
            },
        )
        metrics.observer = usage::onMetric
        usage.start()
        scope.launch {
            var was = settings.value.shareUsage
            settings.state.collect {
                if (it.shareUsage != was) { if (it.shareUsage) usage.turnedOn() else usage.turnedOff() }
                was = it.shareUsage
            }
        }
        // If the smart switch left accessibility off and the process died (update, phone maker cleanup), fix it now.
        scope.launch { com.typepilot.android.service.SwitchRecovery.ensureOn(this@TypePilotApp, "app start") }
        Log.i("app", "TypePilot ${BuildConfig.VERSION_NAME} started")
    }

    companion object {
        lateinit var instance: TypePilotApp; private set
    }
}
