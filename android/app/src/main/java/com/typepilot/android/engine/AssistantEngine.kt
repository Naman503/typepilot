package com.typepilot.android.engine

import android.os.SystemClock
import com.typepilot.android.ai.GeminiModel
import com.typepilot.android.ai.GenerationRequest
import com.typepilot.android.ai.LocalModel
import com.typepilot.android.ai.LocalModelManager
import com.typepilot.android.ai.ModelException
import com.typepilot.android.ai.OpenAICompatibleModel
import com.typepilot.android.ai.TextModel
import com.typepilot.android.ai.generate
import com.typepilot.android.core.AppSettings
import com.typepilot.android.core.Log
import com.typepilot.android.core.ProviderKind
import com.typepilot.android.core.Secrets
import com.typepilot.android.core.SettingsStore
import com.typepilot.android.core.WriteLength
import com.typepilot.android.memory.MemoryEntry
import com.typepilot.android.memory.MemoryStore
import com.typepilot.android.memory.PhraseCompleter
import com.typepilot.android.metrics.EventType
import com.typepilot.android.metrics.LiveMetrics
import com.typepilot.android.metrics.MetricEvent
import com.typepilot.android.metrics.MetricsStore
import com.typepilot.android.metrics.Stage
import com.typepilot.android.text.ContinuationCleaner
import com.typepilot.android.text.DraftCleaner
import com.typepilot.android.text.SensitiveFilter
import com.typepilot.android.telemetry.Usage
import com.typepilot.android.telemetry.UsageMapping
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * The brain. For each pause in typing:
 *   1. memory (shortcut / field recall / phrase continuation): microseconds, no model
 *   2. a small LRU cache: the same text again returns instantly
 *   3. the completion model, with a tight output cap
 * Callers cancel the coroutine when the user keeps typing; that cancels the HTTP call or local decode too.
 */
class AssistantEngine(
    private val settings: SettingsStore,
    private val secrets: Secrets,
    private val memory: MemoryStore,
    private val metrics: MetricsStore,
    private val local: LocalModelManager,
    private val facts: com.typepilot.android.knowledge.FactStore,
) {
    private val phrases = PhraseCompleter()
    @Volatile private var phrasesVersion = -1L
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 200
    }

    fun modelFor(kind: ProviderKind, s: AppSettings = settings.value, writing: Boolean = false): TextModel? = when (kind) {
        ProviderKind.GEMINI -> GeminiModel(s.geminiModel, secrets.get(Secrets.GEMINI), s.requestTimeoutMs)
        ProviderKind.OPENAI -> OpenAICompatibleModel(s.openAIBaseUrl, s.openAIModel, secrets.get(Secrets.OPENAI), s.requestTimeoutMs)
        ProviderKind.LOCAL -> LocalModel(local, if (writing) s.localWritingModelId.ifBlank { s.localModelId } else s.localModelId, s.localBackend, s.localIdleUnloadMin)
        ProviderKind.OFF -> null
    }

    private fun ensurePhrases() {
        val v = memory.version.value
        if (v != phrasesVersion) {
            phrases.rebuild(memory.all())
            phrasesVersion = v
        }
    }

    /** Memory snippets relevant to this field, most relevant first. */
    private fun memoryHints(ctx: FieldContext, limit: Int): List<String> {
        val s = settings.value
        if (!s.memoryEnabled || !s.memoryInPrompts) return emptyList()
        val field = PhraseCompleter.normalizeField(ctx.fieldLabel)
        val words = ctx.textBefore.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).takeLast(30).filter { it.length > 2 }.toSet()
        return memory.all(1500).map { e ->
            var score = e.count + (if (e.pinned) 50.0 else 0.0)
            if (field.isNotEmpty() && PhraseCompleter.normalizeField(e.field) == field) score += 20
            score += e.text.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).count { it in words } * 3
            e.text to score
        }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    private fun redact(text: String, model: TextModel) = if (model.isLocal) text else SensitiveFilter.redact(text)

    // ---------------- Completion ----------------

    /**
     * [onPartial] receives the suggestion as it streams in (whole words only), so it can be shown before the
     * model has finished. The return value is the final, fully cleaned suggestion.
     */
    suspend fun suggest(ctx: FieldContext, trigger: SuggestTrigger, onPartial: ((Suggestion) -> Unit)? = null, aiOnly: Boolean = false): Suggestion? = withContext(Dispatchers.Default) {
        val s = settings.value
        val t0 = SystemClock.elapsedRealtime()
        val typed = ctx.textBefore.trim()
        LiveMetrics.suggestionsRequested.incrementAndGet()

        // 0) Your facts: the field's question matched against everything you've filled or imported. Instant, with a percentage.
        if (s.memoryEnabled && !aiOnly) {
            val f0 = SystemClock.elapsedRealtimeNanos()
            val question = ctx.question.ifBlank { ctx.fieldLabel }
            val typedPrefix = if (ctx.textBefore.contains('\n') || ctx.textBefore.length > 60) "" else ctx.textBefore
            val all = facts.all()
            val ranked = (listOfNotNull(com.typepilot.android.knowledge.FieldMatcher.derived(question, all)?.takeIf { it.percent >= s.factMinPercent }) +
                com.typepilot.android.knowledge.FieldMatcher.match(question, all, typedPrefix, limit = 4, minPercent = s.factMinPercent, minUses = s.factMinUses,
                    app = ctx.packageName, preferSameApp = s.preferSameApp, sameAppOnly = s.sameAppOnly))
                .sortedByDescending { it.percent }.distinctBy { it.fact.value.lowercase() }
                .filter { typedPrefix.isEmpty() || it.fact.value.length > typedPrefix.length }
            LiveMetrics.record(Stage.MEMORY, (SystemClock.elapsedRealtimeNanos() - f0) / 1_000_000)
            if (ranked.isNotEmpty()) {
                fun toSuggestion(m: com.typepilot.android.knowledge.FieldMatcher.Match) = Suggestion(
                    if (typedPrefix.isEmpty()) m.fact.value else m.fact.value.substring(typedPrefix.length), "fact",
                    note = if (s.factShowPercent) "${m.percent}%" else null, provider = "Your facts",
                    engineMs = SystemClock.elapsedRealtime() - t0, refId = m.fact.id, percent = m.percent)
                // More than one likely answer: show them all (the best first), as long as they're reasonably close.
                val best = ranked.first()
                val alts = ranked.drop(1).filter { it.percent >= best.percent - 25 }.take(2).map(::toSuggestion)
                return@withContext toSuggestion(best).copy(alternatives = alts)
            }
        }

        // 1) Memory: instant.
        if (s.memoryEnabled && !aiOnly) {
            val m0 = SystemClock.elapsedRealtimeNanos()
            ensurePhrases()
            val hit = if (typed.isEmpty() && !s.suggestOnEmptyFields) null
                else phrases.suggest(ctx.textBefore, ctx.fieldLabel, s.minChars, ctx.isMultiline, s.maxWords,
                    app = if (s.preferSameApp || s.sameAppOnly) ctx.appName else "", sameAppOnly = s.sameAppOnly)
            LiveMetrics.record(Stage.MEMORY, (SystemClock.elapsedRealtimeNanos() - m0) / 1_000_000)
            if (hit != null) {
                val src = when (hit.kind) { "shortcut" -> "shortcut"; "recall" -> "recall"; else -> "memory" }
                return@withContext Suggestion(hit.text, src, hit.replaceLength, hit.note, "Memory", SystemClock.elapsedRealtime() - t0)
            }
        }

        // 2) Should the model run at all?
        val starter = typed.isEmpty()
        if (starter) {
            if (trigger != SuggestTrigger.FOCUS || !s.suggestOnFocus || !s.suggestOnEmptyFields) return@withContext null
            if (ctx.screenContext.isBlank() && !ctx.isMultiline) return@withContext null
            // Reply starters belong in message / comment / email boxes, never in form fields (name, company…).
            val q = ctx.question.ifBlank { ctx.fieldLabel }
            if (!ctx.isMultiline && !com.typepilot.android.memory.PhraseCompleter.isComposeLabel(q)) return@withContext null
            if (com.typepilot.android.knowledge.FieldMatcher.concept(q) != null) return@withContext null
        } else if (typed.length < s.minChars) return@withContext null
        // Search and address boxes: memory/shortcuts only. Queries are short, private, and the app has its own suggestions.
        if (looksLikeSearch(ctx.fieldLabel)) return@withContext null
        // Form fields (name, email, phone, company…) get answers from memory, never an AI "continuation".
        if (!ctx.isMultiline && com.typepilot.android.knowledge.FieldMatcher.concept(ctx.question.ifBlank { ctx.fieldLabel }) != null) return@withContext null

        val model = modelFor(s.completionProvider, s) ?: return@withContext null
        if (model.configError() != null) return@withContext null

        // 3) Cache
        // The question is part of the key: fields on the same screen share the screen text, and must not share answers.
        val key = "${if (aiOnly) "answer|" else ""}${ctx.packageName}|${ctx.fieldLabel}|${ctx.question}|${ctx.textBefore.takeLast(300)}|${ctx.screenContext.takeLast(200).hashCode()}"
        synchronized(cache) { cache[key] }?.let {
            LiveMetrics.cacheHits.incrementAndGet()
            return@withContext Suggestion(it, "cache", provider = model.name, engineMs = SystemClock.elapsedRealtime() - t0)
        }

        // 4) Model
        var c = ctx.copy(
            textBefore = redact(ctx.textBefore, model),
            screenContext = if (s.useScreenContext) redact(ctx.screenContext, model) else "",
        )
        // On-device models pay for every prompt token (prefill runs on the phone's CPU/GPU): send them less context.
        val compact = model.isLocal && s.completionProvider == ProviderKind.LOCAL
        val mem = if (compact) emptyList() else memoryHints(ctx, 4).map { redact(it, model) }
        if (compact) c = c.copy(textBefore = c.textBefore.takeLast(300), screenContext = c.screenContext.takeLast(300))
        // Answering a form question (the AI row under memory answers) needs a full answer, not an 8-word continuation.
        val words = if (aiOnly) maxOf(s.maxWords, 45) else s.maxWords
        val req = GenerationRequest(
            system = if (starter) Prompts.starterSystem else if (compact) Prompts.compactSystem else Prompts.completionSystem,
            prompt = if (starter) Prompts.starter(c, mem, words, s.aboutMe)
                else if (compact) Prompts.compactCompletion(c) else Prompts.completion(c, mem, words, s.aboutMe),
            maxTokens = if (starter) words * 2 + 24 else maxOf(10, words * 2 + 4),
            temperature = 0.2,
            stop = listOf("\n\n"),
        )
        LiveMetrics.modelCalls.incrementAndGet()
        val op = if (aiOnly) "answer" else "complete"
        val family = UsageMapping.provider(s, s.completionProvider)
        val m0 = SystemClock.elapsedRealtime()
        var ttft = 0L
        try {
            var raw = ""
            var lastPartial = ""
            model.stream(req).collect { text ->
                if (ttft == 0L && text.isNotEmpty()) { ttft = SystemClock.elapsedRealtime() - m0; LiveMetrics.record(Stage.MODEL_TTFT, ttft) }
                raw = text
                // Stream whole words to the screen as they arrive.
                if (onPartial != null && text.isNotEmpty() && (text.last().isWhitespace() || text.last() in ".,!?;:")) {
                    val partial = ContinuationCleaner.clean(text, ctx.textBefore, words, ctx.isMultiline)
                    if (partial != null && partial != lastPartial && partial.trim().isNotEmpty()) {
                        lastPartial = partial
                        onPartial(Suggestion(partial, "ai", provider = model.name, engineMs = SystemClock.elapsedRealtime() - t0, ttftMs = ttft))
                    }
                }
            }
            LiveMetrics.record(Stage.MODEL_TOTAL, SystemClock.elapsedRealtime() - m0)
            val text = ContinuationCleaner.clean(raw, ctx.textBefore, words, ctx.isMultiline) ?: run {
                Log.d("engine", "nothing usable from model: ${raw.take(60)}")
                Usage.aiRequest(op, "empty", family, SystemClock.elapsedRealtime() - m0)
                return@withContext null
            }
            Usage.aiRequest(op, "ok", family, SystemClock.elapsedRealtime() - m0, ttft)
            synchronized(cache) { cache[key] = text }
            Suggestion(text, "ai", provider = model.name, engineMs = SystemClock.elapsedRealtime() - t0, ttftMs = ttft)
        } catch (e: CancellationException) {
            LiveMetrics.cancelled.incrementAndGet()
            Usage.aiRequest(op, "cancelled", family, SystemClock.elapsedRealtime() - m0)
            throw e
        } catch (e: ModelException) {
            metrics.record(MetricEvent(EventType.ERROR, app = ctx.packageName, provider = model.name, mode = e.kind.name))
            Usage.aiRequest(op, "error", family, SystemClock.elapsedRealtime() - m0, error = UsageMapping.errorCategory(e.kind.name))
            Log.w("engine", "completion failed: ${e.message}")
            null
        } catch (e: Exception) {
            metrics.record(MetricEvent(EventType.ERROR, app = ctx.packageName, provider = model.name, mode = "OTHER"))
            Usage.aiRequest(op, "error", family, SystemClock.elapsedRealtime() - m0, error = "other")
            Usage.nonFatal(e, "engine")
            Log.w("engine", "completion failed: ${e.message}")
            null
        }
    }

    // ---------------- Rewrite & write ----------------

    private fun instrumented(flow: Flow<String>, type: EventType, mode: String, ctx: FieldContext, model: TextModel, inputChars: Int): Flow<String> = flow {
        val t0 = SystemClock.elapsedRealtime()
        var ttft = -1L
        var last = ""
        val op = if (type == EventType.WRITE) "write" else "rewrite"
        val family = UsageMapping.provider(settings.value, writingKind())
        try {
            flow.collect {
                if (ttft < 0 && it.isNotEmpty()) { ttft = SystemClock.elapsedRealtime() - t0; LiveMetrics.record(Stage.REWRITE_TTFT, ttft) }
                last = DraftCleaner.clean(it)
                emit(last)
            }
            val total = SystemClock.elapsedRealtime() - t0
            LiveMetrics.record(Stage.REWRITE_TOTAL, total)
            metrics.record(MetricEvent(type, source = "ai", app = ctx.packageName, provider = model.name, mode = mode,
                latencyMs = total, ttftMs = maxOf(ttft, 0), chars = last.length, words = inputChars))
            Usage.aiRequest(op, if (last.isBlank()) "empty" else "ok", family, total, maxOf(ttft, 0))
        } catch (e: CancellationException) {
            Usage.aiRequest(op, "cancelled", family, SystemClock.elapsedRealtime() - t0)
            throw e
        } catch (e: ModelException) {
            metrics.record(MetricEvent(EventType.ERROR, app = ctx.packageName, provider = model.name, mode = e.kind.name))
            Usage.aiRequest(op, "error", family, SystemClock.elapsedRealtime() - t0, error = UsageMapping.errorCategory(e.kind.name))
            throw e
        } catch (e: Exception) {
            Usage.aiRequest(op, "error", family, SystemClock.elapsedRealtime() - t0, error = "other")
            Usage.nonFatal(e, "engine")
            throw e
        }
    }

    /** "Memory only" can't write: fall back to the completion provider, then Gemini. */
    private fun writingKind(s: AppSettings = settings.value): ProviderKind =
        listOf(s.writingProvider, s.completionProvider, ProviderKind.GEMINI).first { it != ProviderKind.OFF }

    private fun writingModel(): TextModel {
        val s = settings.value
        return modelFor(writingKind(s), s, writing = true)!!
    }

    fun writingModelName(): String = writingModel().name
    /** The writing model if it's set up (used to read imported documents), else null. */
    fun writingModelOrNull(): TextModel? = runCatching { writingModel() }.getOrNull()?.takeIf { it.configError() == null }
    fun writingConfigError(): String? = writingModel().configError()

    fun rewrite(text: String, mode: RewriteMode, ctx: FieldContext, custom: String = ""): Flow<String> {
        val s = settings.value
        val model = writingModel()
        val prompt = Prompts.rewrite(redact(text, model), mode, custom, ctx, s.aboutMe)
        val maxTokens = (text.length / 3.0 * if (mode == RewriteMode.LONGER) 2.2 else 1.5).toInt().coerceIn(96, 1600)
        Log.i("engine", "rewrite ${mode.name} via ${model.name} (${text.length} chars)")
        return instrumented(model.stream(GenerationRequest(Prompts.rewriteSystem, prompt, maxTokens, mode.temperature)),
            EventType.REWRITE, mode.name, ctx, model, text.length).flowOn(Dispatchers.Default)
    }

    fun write(request: String, ctx: FieldContext, length: WriteLength, previousDraft: String? = null): Flow<String> {
        val s = settings.value
        val model = writingModel()
        val c = ctx.copy(textBefore = redact(ctx.textBefore, model), textAfter = redact(ctx.textAfter, model),
            selectedText = redact(ctx.selectedText, model), screenContext = if (s.useScreenContext) redact(ctx.screenContext, model) else "")
        val prompt = Prompts.write(request, c, memoryHints(ctx, 8).map { redact(it, model) }, length, s.aboutMe, previousDraft?.let { redact(it, model) })
        val system = if (previousDraft.isNullOrBlank()) Prompts.writeSystem else Prompts.rewriteSystem
        Log.i("engine", "write via ${model.name}: ${request.take(60)}")
        return instrumented(model.stream(GenerationRequest(system, prompt, 900, 0.5)), EventType.WRITE, length.name, ctx, model, request.length)
            .flowOn(Dispatchers.Default)
    }

    /** Test a provider from Settings: returns (ok, message with latency). */
    suspend fun test(kind: ProviderKind): Pair<Boolean, String> {
        val family = UsageMapping.provider(settings.value, kind)
        val model = modelFor(kind) ?: return true to "Memory only: no model needed."
        model.configError()?.let { Usage.aiRequest("test", "error", family, 0, error = "not_configured"); return false to it }
        val t0 = SystemClock.elapsedRealtime()
        return try {
            var ttft = 0L
            val out = model.generate(GenerationRequest("Reply with exactly: OK", "Say OK.", 8, 0.0)) { ttft = it.ttftMs }
            Usage.aiRequest("test", "ok", family, SystemClock.elapsedRealtime() - t0, ttft)
            (true to "Works · first token ${ttft} ms · total ${SystemClock.elapsedRealtime() - t0} ms · \"${out.trim().take(20)}\"")
                .also { Log.i("test", "${model.name}: ${it.second}") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Usage.aiRequest("test", "error", family, SystemClock.elapsedRealtime() - t0, error = UsageMapping.errorCategory((e as? ModelException)?.kind?.name))
            (false to (e.message ?: "Failed")).also { Log.w("test", "${model.name}: ${it.second}") }
        }
    }

    /** Open the cloud connection, or (when [inField]) load the on-device model so the first suggestion is fast. */
    suspend fun prewarm(inField: Boolean = false) {
        val s = settings.value
        val model = modelFor(s.completionProvider, s)?.takeIf { it.configError() == null } ?: return
        if (model.isLocal && s.completionProvider == ProviderKind.LOCAL && !inField) return // don't hold ~1 GB of RAM until you type
        runCatching { model.prewarm() }
    }

    fun learn(text: String, ctx: FieldContext, kind: MemoryEntry.Kind) {
        val s = settings.value
        if (!s.memoryEnabled || (!s.learnFromTyping && kind == MemoryEntry.Kind.TYPED)) return
        if (SensitiveFilter.isSensitiveLabel(ctx.fieldLabel)) return
        if (memory.record(text, ctx.appName, ctx.fieldLabel, kind)) {
            Usage.count("memory_learned", mapOf("kind" to "phrase", "via" to kind.name.lowercase()))
            Log.d("memory", "remembered ${text.length} chars from ${ctx.appName}")
        }
    }

    companion object {
        fun looksLikeSearch(label: String) = Regex("""\b(search|find|filter|query|url|address bar|go to|ask google|search or type)\b""", RegexOption.IGNORE_CASE).containsMatchIn(label)
    }
}

/** Swallows cancellation-unrelated errors into a message for UI flows. */
fun Flow<String>.catchToMessage(onError: (String) -> Unit) = catch { e -> if (e !is CancellationException) onError(e.message ?: "Something went wrong") }
