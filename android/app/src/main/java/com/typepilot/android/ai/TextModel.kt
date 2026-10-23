package com.typepilot.android.ai

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

data class GenerationRequest(
    val system: String,
    val prompt: String,
    val maxTokens: Int,
    val temperature: Double = 0.2,
    val stop: List<String> = emptyList(),
)

/** What a finished generation cost: shown on the Performance screen. */
data class GenerationStats(val ttftMs: Long, val totalMs: Long, val outputChars: Int, val tokensPerSec: Double)

class ModelException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { NOT_CONFIGURED, AUTH, RATE_LIMIT, NETWORK, TIMEOUT, BAD_RESPONSE, MODEL_MISSING, BACKGROUND_BLOCKED }
}

interface TextModel {
    val name: String
    val isLocal: Boolean
    /** Cumulative text, growing as tokens arrive. */
    fun stream(r: GenerationRequest): Flow<String>
    /** Open the connection / load the model before the user needs it. */
    suspend fun prewarm() {}
    fun configError(): String? = null
}

/** Collects a stream, measuring time-to-first-token and throughput. */
suspend fun TextModel.generate(r: GenerationRequest, onStats: (GenerationStats) -> Unit = {}): String {
    val t0 = SystemClock.elapsedRealtime()
    var first = -1L
    var last = ""
    stream(r).collect { text ->
        if (first < 0 && text.isNotEmpty()) first = SystemClock.elapsedRealtime() - t0
        last = text
    }
    val total = SystemClock.elapsedRealtime() - t0
    val genMs = (total - maxOf(first, 0)).coerceAtLeast(1)
    // ~4 characters per token is close enough for a speed indicator.
    onStats(GenerationStats(maxOf(first, total), total, last.length, (last.length / 4.0) / (genMs / 1000.0)))
    return last
}

object Http {
    private val json = "application/json".toMediaType()

    /** One shared client: HTTP/2 + a warm connection pool means no TLS handshake on each keystroke. */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(4, 5, TimeUnit.MINUTES))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun withTimeout(ms: Int): OkHttpClient = client.newBuilder().callTimeout(ms.toLong(), TimeUnit.MILLISECONDS).build()

    fun post(url: String, body: String, headers: Map<String, String>): Request =
        Request.Builder().url(url).post(body.toRequestBody(json)).apply { headers.forEach { (k, v) -> header(k, v) } }.build()

    /** Server-sent events: emits each `data:` payload. Cancelling the collector cancels the HTTP call. */
    fun sse(client: OkHttpClient, request: Request, provider: String, onHttpError: ((Int, String) -> Boolean)? = null): Flow<String> = callbackFlow {
        val call = client.newCall(request)
        launch(Dispatchers.IO) {
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val body = resp.body.string().take(400)
                        if (onHttpError?.invoke(resp.code, body) == true) { close(); return@launch }
                        throw httpError(resp.code, body, provider)
                    }
                    val src = resp.body.source()
                    while (true) {
                        val line = src.readUtf8Line() ?: break
                        if (line.startsWith("data:")) {
                            val payload = line.substring(5).trim()
                            if (payload == "[DONE]") break
                            if (payload.isNotEmpty()) send(payload)
                        }
                    }
                }
                close()
            } catch (e: CancellationException) {
                close()
            } catch (e: ModelException) {
                close(e)
            } catch (e: InterruptedIOException) {
                close(if (call.isCanceled()) null else ModelException(ModelException.Kind.TIMEOUT, "$provider took too long to answer."))
            } catch (e: IOException) {
                close(if (call.isCanceled()) null else ModelException(ModelException.Kind.NETWORK, connectionHelp(provider, request.url.host, e)))
            } catch (e: Exception) {
                close(e)
            }
        }
        awaitClose { call.cancel() }
    }

    /** What to do when a server can't be reached, in plain words (Ollama's default is the #1 cause). */
    fun connectionHelp(provider: String, host: String, e: IOException): String {
        val refused = e is java.net.ConnectException || e.message?.contains("refused", true) == true || e.message?.contains("failed to connect", true) == true
        return when {
            provider == "Ollama" && refused -> "Can't reach Ollama at $host. Ollama only listens on the computer itself by default. " +
                "On a Mac run: launchctl setenv OLLAMA_HOST 0.0.0.0 and restart Ollama (Windows/Linux: set OLLAMA_HOST=0.0.0.0). Phone and computer must be on the same Wi-Fi."
            e is java.net.UnknownHostException -> "No internet connection (couldn't look up $host)."
            refused -> "Can't reach $provider at $host (connection refused). Check the server address and that it's running."
            else -> "Can't reach $provider: ${e.message}"
        }
    }

    fun httpError(code: Int, body: String, provider: String): ModelException = when (code) {
        400 -> ModelException(ModelException.Kind.BAD_RESPONSE, "$provider rejected the request: ${body.take(160)}")
        401, 403 -> ModelException(ModelException.Kind.AUTH, "$provider rejected the API key ($code).")
        404 -> ModelException(ModelException.Kind.MODEL_MISSING, "$provider doesn't know this model ($code). Check the model name.")
        429 -> ModelException(ModelException.Kind.RATE_LIMIT, "$provider rate limit reached. Try again shortly.")
        else -> ModelException(ModelException.Kind.BAD_RESPONSE, "$provider error $code: ${body.take(160)}")
    }

    /** A cheap request that opens (and pools) the TLS connection to [url]'s host. */
    fun warm(url: String) {
        runCatching { client.newCall(Request.Builder().url(url).head().build()).execute().close() }
    }
}
