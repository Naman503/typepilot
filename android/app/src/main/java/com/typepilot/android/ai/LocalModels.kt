package com.typepilot.android.ai

import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.typepilot.android.core.LocalBackend
import com.typepilot.android.core.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile

/**
 * Owns the LiteRT-LM engine. Loads lazily on first use, keeps one model resident, and unloads it after
 * a few idle minutes so an always-on assistant doesn't hold ~1 GB of RAM all day.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalModelManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** The engine isn't thread-safe: every call runs on this one thread. */
    val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var loadedKey = ""
    private var idleJob: Job? = null

    data class State(val loadedId: String = "", val loading: Boolean = false, val loadMs: Long = 0, val error: String? = null)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    val modelsDir: File get() = File(context.filesDir, "models").apply { mkdirs() }

    /** Catalog ids map to their file; anything else (imported or discovered on Hugging Face) is a file name. */
    fun fileFor(id: String): File? {
        val info = LocalCatalog.byId(id)
        val f = File(modelsDir, info?.fileName ?: id.substringAfterLast('/'))
        return f.takeIf { it.exists() && it.length() > 1_000_000 }
    }
    fun installedIds(): List<String> = LocalCatalog.models.filter { fileFor(it.id) != null }.map { it.id } +
        (modelsDir.listFiles()?.filter { f -> f.name.endsWith(".litertlm") && LocalCatalog.models.none { it.fileName == f.name } }?.map { it.name } ?: emptyList())

    fun displayName(id: String) = LocalCatalog.byId(id)?.name ?: id.removeSuffix(".litertlm")

    /** AUTO picks the backend the file was built for; GPU falls back to CPU if this phone has no OpenCL. */
    fun resolveBackend(id: String, setting: LocalBackend): LocalBackend {
        if (setting != LocalBackend.AUTO) return setting
        val preferred = LocalCatalog.byId(id)?.backend ?: if (id.contains("gpu", true)) PreferredBackend.GPU else PreferredBackend.CPU
        return if (preferred == PreferredBackend.GPU && DeviceProfile.read(context).hasOpenCl) LocalBackend.GPU else LocalBackend.CPU
    }

    private val benchPrefs = context.getSharedPreferences("benchmarks", Context.MODE_PRIVATE)
    private val _benchmarks = MutableStateFlow(loadBenchmarks())
    val benchmarks: StateFlow<Map<String, BenchmarkResult>> = _benchmarks

    private fun loadBenchmarks(): Map<String, BenchmarkResult> = benchPrefs.all.mapNotNull { (k, v) ->
        runCatching {
            val o = org.json.JSONObject(v as String)
            k to BenchmarkResult(k, o.getString("backend"), o.getLong("load"), o.getLong("ttft"), o.getDouble("tps"), o.getInt("mem"), o.getLong("at"))
        }.getOrNull()
    }.toMap()

    /**
     * Measure this model on this phone: load time, first token and decode speed for a typical rewrite prompt,
     * plus the memory it added. Runs on the engine thread; takes a few seconds.
     */
    suspend fun benchmark(id: String, setting: LocalBackend): BenchmarkResult {
        val backend = resolveBackend(id, setting)
        val before = android.os.Debug.getPss()
        unload()
        val t0 = SystemClock.elapsedRealtime()
        return withEngine(id, backend, 5) { engine ->
            val loadMs = SystemClock.elapsedRealtime() - t0
            withContext(dispatcher) {
                val conversation = engine.createConversation(ConversationConfig(
                    systemInstruction = Contents.of("You rewrite text. Output only the rewritten text."),
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                    maxOutputToken = 64, thinkingConfig = ThinkingConfig(false)))
                try {
                    val start = SystemClock.elapsedRealtime()
                    var first = 0L
                    var text = ""
                    conversation.sendMessageAsync("Rewrite this message so it sounds polite and professional: hey, send me the report today, its late again and the client is waiting for it.")
                        .collect { m ->
                            val piece = m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                            if (piece.isNotEmpty() && first == 0L) first = SystemClock.elapsedRealtime() - start
                            text += piece
                        }
                    val total = SystemClock.elapsedRealtime() - start
                    val genSec = ((total - first).coerceAtLeast(1)) / 1000.0
                    val tokens = (text.length / 4.0).coerceAtLeast(1.0)
                    val r = BenchmarkResult(id, backend.name, loadMs, first, tokens / genSec, ((android.os.Debug.getPss() - before) / 1024).toInt(), System.currentTimeMillis())
                    benchPrefs.edit().putString(id, org.json.JSONObject().put("backend", r.backend).put("load", r.loadMs).put("ttft", r.ttftMs)
                        .put("tps", r.decodeTps).put("mem", r.memMb).put("at", r.at).toString()).apply()
                    _benchmarks.value = loadBenchmarks()
                    Log.i("local", "benchmark $id on ${r.backend}: load ${r.loadMs} ms, first token ${r.ttftMs} ms, ${"%.1f".format(r.decodeTps)} tok/s")
                    r
                } finally { runCatching { conversation.close() } }
            }
        }
    }

    suspend fun <T> withEngine(id: String, backend: LocalBackend, idleMinutes: Int, block: suspend (Engine) -> T): T = mutex.withLock {
        val file = fileFor(id) ?: throw ModelException(ModelException.Kind.MODEL_MISSING, "Download an on-device model in Settings → On-device models.")
        val key = "${file.absolutePath}|$backend"
        if (engine == null || loadedKey != key) {
            withContext(dispatcher) {
                engine?.close(); engine = null
                _state.value = State(loading = true)
                val t0 = SystemClock.elapsedRealtime()
                try {
                    val e = Engine(EngineConfig(
                        modelPath = file.absolutePath,
                        backend = if (backend == LocalBackend.GPU) Backend.GPU() else Backend.CPU(),
                        cacheDir = context.cacheDir.absolutePath,
                    ))
                    e.initialize()
                    engine = e
                    loadedKey = key
                    val ms = SystemClock.elapsedRealtime() - t0
                    _state.value = State(loadedId = id, loadMs = ms)
                    Log.i("local", "loaded $id on $backend in $ms ms")
                } catch (t: Throwable) {
                    _state.value = State(error = t.message)
                    throw ModelException(ModelException.Kind.MODEL_MISSING, "Couldn't load the model: ${t.message}")
                }
            }
        }
        scheduleUnload(idleMinutes)
        block(engine!!)
    }

    private fun scheduleUnload(minutes: Int) {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(minutes * 60_000L)
            unload()
        }
    }

    suspend fun unload() = mutex.withLock {
        withContext(dispatcher) {
            engine?.close(); engine = null; loadedKey = ""
            _state.value = State()
            Log.i("local", "model unloaded (idle)")
        }
    }

    fun delete(id: String) {
        scope.launch {
            if (_state.value.loadedId == id) unload()
            fileFor(id)?.delete()
        }
    }
}

/** A [TextModel] backed by the on-device engine. */
class LocalModel(
    private val manager: LocalModelManager,
    private val id: String,
    private val backend: LocalBackend,
    private val idleMinutes: Int,
) : TextModel {
    override val name = "On-device · ${manager.displayName(id)}"
    override val isLocal = true

    override fun configError() = if (id.isBlank() || manager.fileFor(id) == null) "Download an on-device model in Settings → On-device models." else null

    override fun stream(r: GenerationRequest): Flow<String> = flow {
        configError()?.let { throw ModelException(ModelException.Kind.MODEL_MISSING, it) }
        manager.withEngine(id, manager.resolveBackend(id, backend), idleMinutes) { engine ->
            val conversation = engine.createConversation(ConversationConfig(
                systemInstruction = Contents.of(r.system),
                samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = r.temperature),
                maxOutputToken = r.maxTokens,
                thinkingConfig = ThinkingConfig(false),
            ))
            try {
                var text = ""
                conversation.sendMessageAsync(r.prompt).collect { message ->
                    val piece = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    if (piece.isNotEmpty()) {
                        text += piece
                        val stopAt = r.stop.map { text.indexOf(it) }.filter { it >= 0 }.minOrNull()
                        if (stopAt != null) { emit(text.substring(0, stopAt)); conversation.cancelProcess(); return@collect }
                        emit(text)
                    }
                }
            } finally {
                runCatching { conversation.cancelProcess() }
                runCatching { conversation.close() }
            }
        }
    }.flowOn(manager.dispatcher)

    override suspend fun prewarm() {
        if (configError() == null) runCatching { manager.withEngine(id, manager.resolveBackend(id, backend), idleMinutes) { } }
    }
}

/** Resumable downloads into app storage (models are big: never re-download what's already there). */
class ModelDownloader(private val manager: LocalModelManager, private val token: () -> String?) {
    data class Progress(val bytes: Long, val total: Long, val error: String? = null, val done: Boolean = false) {
        val fraction get() = if (total > 0) bytes.toFloat() / total else 0f
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()
    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress

    private fun set(id: String, p: Progress?) {
        _progress.value = _progress.value.toMutableMap().apply { if (p == null) remove(id) else put(id, p) }
    }

    /** Usage reporting: the download's size bucket and whether it's from the built-in catalog (never the model's name). */
    private fun report(info: LocalModelInfo, action: String, extra: Map<String, String> = emptyMap()) =
        com.typepilot.android.telemetry.Usage.event("local_model", mapOf("action" to action,
            "catalog" to (LocalCatalog.byId(info.id) != null).toString(),
            "size" to com.typepilot.android.telemetry.UsageMapping.sizeMb(info.sizeMb.toLong())) + extra)

    fun start(info: LocalModelInfo) {
        if (jobs[info.id]?.isActive == true) return
        val t0 = SystemClock.elapsedRealtime()
        report(info, "download_start")
        jobs[info.id] = scope.launch {
            val target = File(manager.modelsDir, info.fileName)
            val part = File(manager.modelsDir, info.fileName + ".part")
            try {
                val free = android.os.StatFs(manager.modelsDir.absolutePath).availableBytes / 1_048_576
                if (free < info.sizeMb + 200) throw ModelException(ModelException.Kind.NETWORK, "Not enough storage: ${info.sizeMb} MB needed, $free MB free.")
                val have = if (part.exists()) part.length() else 0L
                val req = Request.Builder().url(info.url).apply {
                    if (have > 0) header("Range", "bytes=$have-")
                    token()?.takeIf { info.gated && it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
                }.build()
                Http.client.newCall(req).execute().use { resp ->
                    if (resp.code == 401 || resp.code == 403) throw ModelException(ModelException.Kind.AUTH,
                        "This model needs a Hugging Face token, and you must accept its license on huggingface.co first.")
                    if (!resp.isSuccessful) throw ModelException(ModelException.Kind.NETWORK, "Download failed (${resp.code}).")
                    val resumed = resp.code == 206
                    val start = if (resumed) have else 0L
                    val total = start + resp.body.contentLength()
                    RandomAccessFile(part, "rw").use { out ->
                        out.setLength(start); out.seek(start)
                        val src = resp.body.byteStream()
                        val buf = ByteArray(256 * 1024)
                        var done = start
                        var lastEmit = 0L
                        while (true) {
                            val n = src.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastEmit > 250) { set(info.id, Progress(done, total)); lastEmit = now }
                        }
                    }
                }
                part.renameTo(target)
                set(info.id, Progress(target.length(), target.length(), done = true))
                Log.i("local", "downloaded ${info.id} (${target.length() / 1_000_000} MB)")
                report(info, "download_done", mapOf("time" to com.typepilot.android.telemetry.UsageMapping.duration(SystemClock.elapsedRealtime() - t0)))
            } catch (e: kotlinx.coroutines.CancellationException) {
                set(info.id, null)
                report(info, "download_paused")
            } catch (e: Exception) {
                report(info, "download_failed", mapOf("reason" to when {
                    e is ModelException -> if (e.message?.startsWith("Not enough storage") == true) "storage" else com.typepilot.android.telemetry.UsageMapping.errorCategory(e.kind.name)
                    e is java.io.IOException -> "network"
                    else -> "other"
                }))
                set(info.id, Progress(part.length(), info.sizeMb * 1_000_000L, error = e.message))
                Log.w("local", "download ${info.id} failed: ${e.message}")
            }
        }
    }

    fun cancel(id: String) { jobs[id]?.cancel() }
}
