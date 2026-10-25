package com.typepilot.android.knowledge

import android.content.Context
import android.net.Uri
import com.typepilot.android.core.Log
import com.typepilot.android.engine.AssistantEngine
import com.typepilot.android.telemetry.Usage
import com.typepilot.android.telemetry.UsageMapping
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Imports documents into facts and records what you type into forms. */
class KnowledgeBase(private val context: Context, val facts: FactStore, private val engine: () -> AssistantEngine) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _progress = MutableStateFlow<Map<Long, String>>(emptyMap())
    /** Doc id → "Reading…" / "AI: part 2 of 3…" while importing. */
    val progress: StateFlow<Map<Long, String>> = _progress

    private fun setProgress(id: Long, s: String?) { _progress.value = _progress.value.toMutableMap().apply { if (s == null) remove(id) else put(id, s) } }

    fun import(uri: Uri, onError: (String) -> Unit = {}) {
        scope.launch {
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('.', "").orEmpty()
            val doc = runCatching { DocumentImporter.read(context, uri) }.getOrElse {
                Usage.event("document_import_failed", mapOf("reason" to if (it is OutOfMemoryError) "too_large" else "unreadable", "type" to UsageMapping.docType(mime)))
                onError("Couldn't read the file: ${it.message}"); return@launch
            }
            if (doc.text.isBlank()) {
                Usage.event("document_import_failed", mapOf("reason" to "no_text", "type" to UsageMapping.docType(doc.mime)))
                onError("No text found in ${doc.name} (scanned PDFs without text aren't supported yet)."); return@launch
            }
            val id = facts.addDoc(doc.name, doc.mime, doc.text)
            extract(id, doc.text, UsageMapping.docType(doc.mime))
        }
    }

    fun reExtract(id: Long) = scope.launch { extract(id, facts.docText(id), null) }

    /** [type] is the document kind for usage reporting; null when it's a re-read of one already added. */
    private suspend fun extract(id: Long, text: String, type: String?) {
        setProgress(id, "Finding facts…")
        val t0 = android.os.SystemClock.elapsedRealtime()
        val basic = FactExtractor.heuristic(text)
        for (f in basic) facts.upsert(f.key, f.value, "doc", f.aliases, id, f.confidence)
        val model = engine().writingModelOrNull()
        var aiFound = 0
        if (model != null) {
            val found = FactExtractor.withAi(text, model) { i, n -> setProgress(id, "AI reading part $i of $n…") }
            for (f in found) facts.upsert(f.key, f.value, "doc", f.aliases, id, f.confidence)
            aiFound = found.size
            Log.i("knowledge", "document $id: ${found.size} facts from ${model.name}")
        }
        facts.setDocStatus(id, if (model == null) "done (no AI: basic facts only)" else "done")
        Usage.event(if (type != null) "document_added" else "document_reread", buildMap {
            if (type != null) put("type", type)
            put("ai", (model != null).toString())
            put("facts", UsageMapping.count(basic.size + aiFound))
            put("time", UsageMapping.duration(android.os.SystemClock.elapsedRealtime() - t0))
        })
        setProgress(id, null)
    }

    /**
     * Every single-line field you fill becomes a fact: question → answer. Typing the same answer again
     * makes it more certain, which is what lets the field matcher answer instantly next time.
     */
    fun observe(question: String, value: String, multiline: Boolean, app: String = "") {
        val s = com.typepilot.android.TypePilotApp.instance.settings.value
        if (!s.memoryEnabled || !s.learnFacts) return
        val v = value.trim()
        if (question.isBlank() || v.isEmpty() || multiline || v.length > 160 || v.contains('\n')) return
        if (com.typepilot.android.memory.PhraseCompleter.isComposeLabel(question)) return
        if (question.count { it.isLetter() } < 2) return
        scope.launch { if (facts.upsert(question, v, "typed", confidence = 0.45, app = app)) Usage.count("memory_learned", mapOf("kind" to "fact", "via" to "typed")) }
    }
}
