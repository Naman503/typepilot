package com.typepilot.android.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Google Gemini (streamGenerateContent, SSE). Thinking is turned down to the minimum for speed. */
class GeminiModel(private val model: String, private val apiKey: String?, private val timeoutMs: Int) : TextModel {
    override val name = "Gemini · $model"
    override val isLocal = false
    private val base = "https://generativelanguage.googleapis.com/v1beta"

    override fun configError() = if (apiKey.isNullOrBlank()) "Add a Gemini API key (free at aistudio.google.com) in Settings → AI." else null

    private fun body(r: GenerationRequest, thinking: Boolean): String {
        val config = JSONObject().put("maxOutputTokens", r.maxTokens)
        if (!model.startsWith("gemini-3")) config.put("temperature", r.temperature)
        if (r.stop.isNotEmpty()) config.put("stopSequences", JSONArray(r.stop.take(5)))
        if (thinking) {
            when {
                model.startsWith("gemini-2.5") && "pro" !in model -> config.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                model.startsWith("gemini-3") -> config.put("thinkingConfig", JSONObject().put("thinkingLevel", if ("pro" in model) "low" else "minimal"))
            }
        }
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", r.system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", r.prompt)))))
            .put("generationConfig", config)
            .toString()
    }

    override fun stream(r: GenerationRequest): Flow<String> = flow {
        val key = apiKey ?: throw ModelException(ModelException.Kind.NOT_CONFIGURED, configError()!!)
        val client = Http.withTimeout(timeoutMs)
        val url = "$base/models/$model:streamGenerateContent?alt=sse"
        var text = ""
        // Some models reject thinking settings (400): retry once without them.
        var rejectedThinking = false
        for (thinking in listOf(true, false)) {
            if (!thinking && !rejectedThinking) break
            Http.sse(client, Http.post(url, body(r, thinking), mapOf("x-goog-api-key" to key)), "Gemini") { code, b ->
                if (thinking && code == 400 && "thinking" in b.lowercase()) { rejectedThinking = true; true } else false
            }.collect { payload ->
                val piece = textOf(JSONObject(payload))
                if (piece.isNotEmpty()) { text += piece; emit(text) }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun prewarm() = withContext(Dispatchers.IO) { Http.warm(base) }

    companion object {
        fun textOf(o: JSONObject): String {
            val parts = o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (!p.optBoolean("thought")) sb.append(p.optString("text"))
            }
            return sb.toString()
        }

        /** Measured on a Dimensity 7200 phone: 3.5 Flash-Lite ≈ 0.8–1.1 s to first token, 3.1 Flash-Lite ≈ 2.5–3.3 s; 2.5 models are retired (404). */
        val suggestedModels = listOf("gemini-3.5-flash-lite", "gemini-3.5-flash", "gemini-3.1-flash-lite")

        /** The text models this key can use (newest first), so a retired name can't be picked. */
        suspend fun listModels(apiKey: String): List<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
            val req = okhttp3.Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
                .header("x-goog-api-key", apiKey).build()
            Http.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Http.httpError(resp.code, resp.body.string().take(200), "Gemini")
                val arr = JSONObject(resp.body.string()).optJSONArray("models") ?: JSONArray()
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .filter { m -> m.optJSONArray("supportedGenerationMethods")?.toString()?.contains("generateContent") == true }
                    .map { it.getString("name").removePrefix("models/") }
                    .filter { it.startsWith("gemini") && !it.contains("embedding") && !it.contains("image") && !it.contains("tts") && !it.contains("audio") && !it.contains("live") }
                    .sortedWith(compareByDescending<String> { Regex("""gemini-(\d+(\.\d+)?)""").find(it)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0 }
                        .thenBy { if (it.contains("lite")) 0 else if (it.contains("flash")) 1 else 2 })
            }
        }
    }
}

/** Any OpenAI-style /chat/completions endpoint: Groq, OpenRouter, Together, LM Studio, Ollama (/v1)… */
class OpenAICompatibleModel(private val baseUrl: String, private val model: String, private val apiKey: String?, private val timeoutMs: Int) : TextModel {
    override val name = "${hostLabel(baseUrl)} · $model"
    override val isLocal = isPrivateHost(baseUrl)

    override fun configError(): String? = when {
        baseUrl.isBlank() || model.isBlank() -> "Set the server URL and model in Settings → AI."
        apiKey.isNullOrBlank() && !isLocal -> "Add an API key for ${hostLabel(baseUrl)} in Settings → AI."
        else -> null
    }

    override fun stream(r: GenerationRequest): Flow<String> = flow {
        configError()?.let { throw ModelException(ModelException.Kind.NOT_CONFIGURED, it) }
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", r.system))
                .put(JSONObject().put("role", "user").put("content", r.prompt)))
            .put("max_tokens", r.maxTokens)
            .put("temperature", r.temperature)
            .put("stream", true)
        if (r.stop.isNotEmpty()) body.put("stop", JSONArray(r.stop.take(4)))
        val headers = buildMap { if (!apiKey.isNullOrBlank()) put("Authorization", "Bearer $apiKey") }
        var text = ""
        Http.sse(Http.withTimeout(timeoutMs), Http.post(baseUrl.trimEnd('/') + "/chat/completions", body.toString(), headers), hostLabel(baseUrl))
            .collect { payload ->
                val piece = JSONObject(payload).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content").orEmpty()
                if (piece.isNotEmpty() && piece != "null") { text += piece; emit(text) }
            }
    }.flowOn(Dispatchers.IO)

    override suspend fun prewarm() = withContext(Dispatchers.IO) { Http.warm(baseUrl) }

    companion object {
        data class Preset(val title: String, val baseUrl: String, val model: String, val note: String)
        val presets = listOf(
            Preset("Groq", "https://api.groq.com/openai/v1", "llama-3.1-8b-instant", "Free tier, the fastest responses (~200 ms)"),
            Preset("OpenRouter", "https://openrouter.ai/api/v1", "google/gemini-3.5-flash-lite", "One key, hundreds of models"),
        )

        /** Model ids from any OpenAI-compatible server (GET /models; Ollama, Groq, OpenRouter, LM Studio all support it). */
        suspend fun listModels(baseUrl: String, apiKey: String?): List<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
            val req = okhttp3.Request.Builder().url(baseUrl.trimEnd('/') + "/models").apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }.build()
            Http.withTimeout(8000).newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Http.httpError(resp.code, resp.body.string().take(200), hostLabel(baseUrl))
                val arr = JSONObject(resp.body.string()).optJSONArray("data") ?: JSONArray()
                (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }.sorted()
            }
        }

        fun hostLabel(url: String) = runCatching {
            val u = java.net.URI(url)
            if (u.port == 11434) "Ollama" else u.host?.removePrefix("api.") ?: url
        }.getOrDefault(url)

        fun isPrivateHost(url: String): Boolean {
            val h = runCatching { java.net.URI(url).host }.getOrNull() ?: return false
            return h == "localhost" || h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.") ||
                Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(h) || h.endsWith(".local")
        }
    }
}
