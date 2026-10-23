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
        // If the smart switch left accessibility off and the process died (update, phone maker cleanup), fix it now.
        scope.launch { com.typepilot.android.service.SwitchRecovery.ensureOn(this@TypePilotApp, "app start") }
        Log.i("app", "TypePilot ${BuildConfig.VERSION_NAME} started")
    }

    companion object {
        lateinit var instance: TypePilotApp; private set
    }
}
