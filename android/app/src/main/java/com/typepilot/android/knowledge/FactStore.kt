package com.typepilot.android.knowledge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.typepilot.android.core.Log
import com.typepilot.android.text.SensitiveFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One thing TypePilot knows about you: "Email" → "naman@…", or a question → answer from a document. */
data class Fact(
    val id: Long,
    val key: String,
    val value: String,
    val aliases: List<String>,
    /** typed | manual | doc */
    val source: String,
    val docId: Long,
    /** 0–1: how sure we are this is right (grows each time you type or accept it). */
    val confidence: Double,
    val uses: Int,
    val rejects: Int,
    val lastUsed: Long,
    /** Apps (package names) where you typed or used this answer. */
    val apps: List<String> = emptyList(),
)

data class KnowledgeDoc(val id: Long, val name: String, val mime: String, val chars: Int, val added: Long, val status: String, val facts: Int, val error: String?)

/**
 * Facts and imported documents (on the phone only). Everything is kept in memory as well, so matching a
 * field against hundreds of facts takes well under a millisecond.
 */
class FactStore(context: Context) : SQLiteOpenHelper(context, "knowledge.db", null, 2) {
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version
    @Volatile private var cache: List<Fact>? = null

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE facts (id INTEGER PRIMARY KEY AUTOINCREMENT, key TEXT NOT NULL, norm_key TEXT NOT NULL, value TEXT NOT NULL,
            norm_value TEXT NOT NULL, aliases TEXT NOT NULL DEFAULT '', source TEXT NOT NULL, doc_id INTEGER NOT NULL DEFAULT 0,
            confidence REAL NOT NULL DEFAULT 0.5, uses INTEGER NOT NULL DEFAULT 0, rejects INTEGER NOT NULL DEFAULT 0,
            created INTEGER NOT NULL, last_used INTEGER NOT NULL, apps TEXT NOT NULL DEFAULT '', UNIQUE(norm_key, norm_value))""")
        db.execSQL("""CREATE TABLE docs (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, mime TEXT NOT NULL, chars INTEGER NOT NULL,
            added INTEGER NOT NULL, status TEXT NOT NULL, facts INTEGER NOT NULL DEFAULT 0, error TEXT, text TEXT)""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) runCatching { db.execSQL("ALTER TABLE facts ADD COLUMN apps TEXT NOT NULL DEFAULT ''") }
    }

    private fun changed() { cache = null; _version.value = _version.value + 1 }

    fun all(): List<Fact> = cache ?: synchronized(this) {
        val out = ArrayList<Fact>()
        readableDatabase.rawQuery("SELECT id, key, value, aliases, source, doc_id, confidence, uses, rejects, last_used, apps FROM facts ORDER BY confidence DESC, uses DESC", null).use { c ->
            while (c.moveToNext()) out += Fact(c.getLong(0), c.getString(1), c.getString(2), c.getString(3).split('\n').filter { it.isNotBlank() },
                c.getString(4), c.getLong(5), c.getDouble(6), c.getInt(7), c.getInt(8), c.getLong(9), c.getString(10).split(',').filter { it.isNotBlank() })
        }
        out.also { cache = it }
    }

    /**
     * Add or reinforce a fact. Seeing the same key + value again raises its confidence; a new value for a
     * key you've filled before starts lower, so your usual answer stays on top.
     */
    fun upsert(key: String, value: String, source: String, aliases: List<String> = emptyList(), docId: Long = 0, confidence: Double = 0.5, app: String = ""): Boolean {
        val k = key.trim().trimEnd(':', '*', '?').trim().take(120)
        val v = value.trim().take(2000)
        if (k.length < 2 || v.isEmpty()) return false
        if (SensitiveFilter.containsSecret(v) || SensitiveFilter.looksLikeOneTimeCode(v) || SensitiveFilter.isSensitiveLabel(k)) return false
        val now = System.currentTimeMillis()
        val nk = FieldMatcher.normalize(k)
        val nv = v.lowercase().replace(Regex("""\s+"""), " ")
        return try {
            synchronized(this) {
                val db = writableDatabase
                val existing = db.rawQuery("SELECT id, confidence, aliases, apps FROM facts WHERE norm_key = ? AND norm_value = ?", arrayOf(nk, nv)).use { c ->
                    if (c.moveToFirst()) listOf(c.getLong(0), c.getDouble(1), c.getString(2), c.getString(3)) else null
                }
                if (existing != null) {
                    val merged = ((existing[2] as String).split('\n') + aliases).map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString("\n")
                    db.execSQL("UPDATE facts SET confidence = ?, uses = uses + 1, last_used = ?, aliases = ?, apps = ? WHERE id = ?",
                        arrayOf<Any>(minOf(1.0, maxOf(existing[1] as Double, confidence) + 0.15), now, merged, addApp(existing[3] as String, app), existing[0] as Long))
                } else {
                    db.execSQL("""INSERT INTO facts (key, norm_key, value, norm_value, aliases, source, doc_id, confidence, uses, created, last_used, apps)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)""",
                        arrayOf<Any>(k, nk, v, nv, aliases.map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString("\n"), source, docId, confidence, now, now, app))
                }
            }
            changed(); true
        } catch (e: Exception) { Log.e("knowledge", "upsert failed", e); false }
    }

    fun markUsed(id: Long, app: String = "") {
        val apps = readableDatabase.rawQuery("SELECT apps FROM facts WHERE id = ?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getString(0) else "" }
        exec("UPDATE facts SET uses = uses + 1, confidence = MIN(1.0, confidence + 0.1), last_used = ?, apps = ? WHERE id = ?", System.currentTimeMillis(), addApp(apps, app), id)
    }

    private fun addApp(apps: String, app: String): String =
        if (app.isBlank()) apps else (apps.split(',').filter { it.isNotBlank() } + app).distinct().takeLast(12).joinToString(",")
    fun markRejected(id: Long) = exec("UPDATE facts SET rejects = rejects + 1, confidence = MAX(0.05, confidence - 0.1) WHERE id = ?", id)
    fun update(id: Long, key: String, value: String, aliases: List<String>) =
        exec("UPDATE facts SET key = ?, norm_key = ?, value = ?, norm_value = ?, aliases = ?, source = CASE WHEN source = 'doc' THEN 'doc' ELSE 'manual' END, confidence = MAX(confidence, 0.9) WHERE id = ?",
            key.trim(), FieldMatcher.normalize(key), value.trim(), value.trim().lowercase(), aliases.joinToString("\n"), id)
    fun delete(id: Long) = exec("DELETE FROM facts WHERE id = ?", id)
    fun clearFacts() = exec("DELETE FROM facts")

    private fun exec(sql: String, vararg args: Any) {
        runCatching { synchronized(this) { writableDatabase.execSQL(sql, args) } }.onFailure { Log.e("knowledge", "update failed", it) }
        changed()
    }

    // ---------------- Documents ----------------

    fun addDoc(name: String, mime: String, text: String): Long = synchronized(this) {
        writableDatabase.compileStatement("INSERT INTO docs (name, mime, chars, added, status, text) VALUES (?, ?, ?, ?, 'reading', ?)").run {
            bindString(1, name); bindString(2, mime); bindLong(3, text.length.toLong()); bindLong(4, System.currentTimeMillis()); bindString(5, text.take(400_000))
            executeInsert()
        }.also { changed() }
    }

    fun setDocStatus(id: Long, status: String, error: String? = null) {
        val count = readableDatabase.rawQuery("SELECT COUNT(*) FROM facts WHERE doc_id = ?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        exec("UPDATE docs SET status = ?, error = ?, facts = ? WHERE id = ?", status, error ?: "", count, id)
    }

    fun docs(): List<KnowledgeDoc> {
        val out = ArrayList<KnowledgeDoc>()
        readableDatabase.rawQuery("SELECT id, name, mime, chars, added, status, facts, error FROM docs ORDER BY added DESC", null).use { c ->
            while (c.moveToNext()) out += KnowledgeDoc(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getLong(4), c.getString(5), c.getInt(6), c.getString(7)?.ifBlank { null })
        }
        return out
    }

    fun docText(id: Long): String = readableDatabase.rawQuery("SELECT text FROM docs WHERE id = ?", arrayOf(id.toString())).use { if (it.moveToFirst()) it.getString(0) ?: "" else "" }

    fun deleteDoc(id: Long) {
        exec("DELETE FROM facts WHERE doc_id = ?", id)
        exec("DELETE FROM docs WHERE id = ?", id)
    }
}
