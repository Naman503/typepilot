package com.typepilot.android.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray

/**
 * The latest LiteRT-LM models published by litert-community on Hugging Face, fetched live so the list stays
 * current without an app update. Filters out what doesn't suit a writing assistant (speech, vision,
 * embeddings, reasoning-only, very large) and files built for other chips.
 */
object ModelDiscovery {
    data class Repo(val id: String, val name: String, val downloads: Int, val updated: String, val gated: Boolean)
    data class RemoteFile(val name: String, val sizeMb: Int)

    private val unsuitable = Regex(
        """asr|tts|vl\b|vlm|vision|ocr|embedding|function|mobile-actions|tiny-garden|reasoning|thinking|r1-distill|coder|medgemma|""" +
            """(^|[^0-9.])(7|8|12|14|26|31)b\b|a4b|fastcontext|polaris|jan-nano|paddle|llava|internvl|ovis|minicpm-v|fastvlm|smolvlm""",
        RegexOption.IGNORE_CASE)

    suspend fun repos(): List<Repo> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://huggingface.co/api/models?author=litert-community&sort=downloads&direction=-1&limit=300&full=true").build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ModelException(ModelException.Kind.NETWORK, "Hugging Face returned ${resp.code}")
            val arr = JSONArray(resp.body.string())
            (0 until arr.length()).map { arr.getJSONObject(it) }.filter { o ->
                val sib = o.optJSONArray("siblings") ?: return@filter false
                (0 until sib.length()).any { sib.getJSONObject(it).optString("rfilename").endsWith(".litertlm") }
            }.map { o ->
                val id = o.getString("id")
                Repo(id, id.substringAfter('/'), o.optInt("downloads"), o.optString("lastModified").take(10), o.opt("gated").let { it != null && it != false && it.toString() != "false" })
            }.filter { !unsuitable.containsMatchIn(it.name) }
        }
    }

    /** .litertlm files in a repo with sizes; chip-specific builds are kept only if they match this phone's chip. */
    suspend fun files(repoId: String, soc: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://huggingface.co/api/models/$repoId/tree/main").build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ModelException(ModelException.Kind.NETWORK, "Hugging Face returned ${resp.code}")
            val arr = JSONArray(resp.body.string())
            val chip = Regex("""(mt\d{4}|sm\d{4}|qcs\d{4}|tensor_g\d|intel_\w+)""", RegexOption.IGNORE_CASE)
            val mySoc = soc.lowercase().replace(" ", "")
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { it.optString("path").endsWith(".litertlm") && !it.optString("path").contains("-web.") }
                .filter { f -> chip.find(f.optString("path"))?.value?.lowercase()?.let { mySoc.contains(it) } ?: true }
                .map { RemoteFile(it.getString("path"), (it.optLong("size") / 1_000_000).toInt()) }
        }
    }

    fun asModel(repoId: String, f: RemoteFile, gated: Boolean): LocalModelInfo = LocalModelInfo(
        id = f.name, name = repoId.substringAfter('/'), publisher = "litert-community", params = "", repo = repoId.substringAfter('/'),
        file = f.name, sizeMb = f.sizeMb, quant = "", context = 0, license = "see model card",
        backend = if (f.name.contains("gpu", true)) PreferredBackend.GPU else PreferredBackend.BOTH,
        peakRamMb = (f.sizeMb * 1.4).toInt(), estDecodeTps = 0.0, estTtftSec = 0.0, quality = 0.0,
        uses = setOf(ModelUse.REWRITE), notes = "From Hugging Face. Run Benchmark after downloading to see how it performs on this phone.", gated = gated,
    )
}
