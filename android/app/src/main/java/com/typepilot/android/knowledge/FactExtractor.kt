package com.typepilot.android.knowledge

import com.typepilot.android.ai.GenerationRequest
import com.typepilot.android.ai.TextModel
import com.typepilot.android.ai.generate
import com.typepilot.android.core.Log
import com.typepilot.android.text.SensitiveFilter
import org.json.JSONArray

/**
 * Turns a document into facts: first a fast offline pass (emails, phones, links, "Label: value" lines),
 * then, if a model is set up, the AI reads it in chunks and writes key → value facts plus likely
 * question → answer pairs ("Why are you looking for a change?" → …), each with aliases.
 */
object FactExtractor {
    data class Extracted(val key: String, val value: String, val aliases: List<String>, val confidence: Double)

    private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val phone = Regex("""(?<!\d)(\+?\d[\d\s-]{8,14}\d)(?!\d)""")
    private val linkedin = Regex("""(https?://)?(www\.)?linkedin\.com/in/[A-Za-z0-9_-]+/?""")
    private val github = Regex("""(https?://)?(www\.)?github\.com/[A-Za-z0-9_-]+/?""")
    private val labelLine = Regex("""^\s*([A-Za-z][A-Za-z /&().-]{1,40})\s*[:：]\s*(.{1,200})$""")

    fun heuristic(text: String): List<Extracted> {
        val out = ArrayList<Extracted>()
        email.find(text)?.let { out += Extracted("Email", it.value, listOf("Email address", "E-mail"), 0.85) }
        phone.findAll(text).map { it.value.trim() }.firstOrNull { it.filter(Char::isDigit).length in 10..13 }?.let { out += Extracted("Phone", it, listOf("Mobile number", "Contact number"), 0.8) }
        linkedin.find(text)?.let { out += Extracted("LinkedIn", it.value, listOf("LinkedIn profile URL"), 0.85) }
        github.find(text)?.let { out += Extracted("GitHub", it.value, listOf("GitHub profile"), 0.85) }
        for (line in text.lines().take(400)) {
            val m = labelLine.find(line) ?: continue
            val (k, v) = m.destructured
            if (k.split(' ').size <= 5 && v.isNotBlank()) out += Extracted(k.trim(), v.trim(), emptyList(), 0.6)
        }
        return out.filterNot { SensitiveFilter.containsSecret(it.value) }
    }

    private const val SYSTEM = """You extract facts about a person from their document (resume, profile, notes) so a phone keyboard can autofill forms.
Return ONLY a JSON array, no prose. Each item: {"key": short field name, "value": exact value, "aliases": [other ways a form may ask], "confidence": 0.0-1.0}.
Include: full name, email, phone, address parts, city, state, pincode, country, date of birth, gender, current company, job title, total experience, skills, degree, college, graduation year, links (LinkedIn, GitHub, portfolio), languages, notice period, current and expected salary when present.
Also add 5–10 likely form or interview questions with a short first-person answer drawn ONLY from the document, as {"key": the question, "value": the answer, "aliases": [rephrasings]}.
Never invent anything not in the document. Skip passwords, card numbers, Aadhaar, PAN and other ID numbers."""

    suspend fun withAi(text: String, model: TextModel, onChunk: (Int, Int) -> Unit = { _, _ -> }): List<Extracted> {
        val chunks = text.chunked(7000).take(8)
        val out = ArrayList<Extracted>()
        chunks.forEachIndexed { i, chunk ->
            onChunk(i + 1, chunks.size)
            val raw = runCatching { model.generate(GenerationRequest(SYSTEM, "Document part ${i + 1}/${chunks.size}:\n\"\"\"\n$chunk\n\"\"\"", 1500, 0.1)) }
                .onFailure { Log.w("knowledge", "AI extraction failed: ${it.message}") }.getOrNull() ?: return@forEachIndexed
            out += parse(raw)
        }
        return out
    }

    fun parse(raw: String): List<Extracted> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        return runCatching {
            val arr = JSONArray(raw.substring(start, end + 1))
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val k = o.optString("key").trim()
                val v = o.opt("value")?.let { if (it is JSONArray) (0 until it.length()).joinToString(", ") { j -> it.getString(j) } else it.toString() }?.trim().orEmpty()
                if (k.isEmpty() || v.isEmpty() || v == "null" || SensitiveFilter.containsSecret(v)) return@mapNotNull null
                val aliases = o.optJSONArray("aliases")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                Extracted(k, v, aliases, o.optDouble("confidence", 0.8).coerceIn(0.3, 0.95))
            }
        }.getOrElse { emptyList() }
    }
}
