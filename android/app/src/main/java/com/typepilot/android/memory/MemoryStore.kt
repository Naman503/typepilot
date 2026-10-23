package com.typepilot.android.memory

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.typepilot.android.core.Log
import com.typepilot.android.text.SensitiveFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class MemoryEntry(
    val id: Long,
    val text: String,
    val app: String,
    val field: String,
    val kind: Kind,
    val count: Int,
    val lastUsed: Long,
    val pinned: Boolean,
    val shortcut: String = "",
) {
    enum class Kind { TYPED, ACCEPTED, MANUAL }
}

/** On-device memory of what you type often. Nothing leaves the phone unless "use memory in prompts" is on with a cloud provider. */
class MemoryStore(context: Context) : SQLiteOpenHelper(context, "memory.db", null, 1) {
    /** Bumped on every change, so the phrase index and the Memory screen refresh. */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE entries (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              text TEXT NOT NULL,
              norm TEXT NOT NULL UNIQUE,
              app TEXT NOT NULL DEFAULT '',
              field TEXT NOT NULL DEFAULT '',
              kind TEXT NOT NULL DEFAULT 'TYPED',
              count INTEGER NOT NULL DEFAULT 1,
              first_seen INTEGER NOT NULL,
              last_used INTEGER NOT NULL,
              pinned INTEGER NOT NULL DEFAULT 0,
              shortcut TEXT NOT NULL DEFAULT ''
            )""")
        db.execSQL("CREATE INDEX entries_field ON entries(field)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun changed() { _version.value = _version.value + 1 }

    fun record(text: String, app: String, field: String, kind: MemoryEntry.Kind, shortcut: String = ""): Boolean {
        val t = text.trim()
        if (!isWorthRemembering(t, field)) return false
        val now = System.currentTimeMillis()
        return try {
            writableDatabase.execSQL("""
                INSERT INTO entries (text, norm, app, field, kind, count, first_seen, last_used)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?)
                ON CONFLICT(norm) DO UPDATE SET count = count + 1, last_used = excluded.last_used,
                  app = excluded.app, field = CASE WHEN excluded.field <> '' THEN excluded.field ELSE field END,
                  kind = CASE WHEN kind = 'MANUAL' THEN kind ELSE excluded.kind END""",
                arrayOf<Any>(t, normalize(t), app, field, kind.name, now, now))
            val sc = normalizeShortcut(shortcut)
            if (sc.isNotEmpty()) writableDatabase.execSQL("UPDATE entries SET shortcut = ? WHERE norm = ?", arrayOf<Any>(sc, normalize(t)))
            changed()
            true
        } catch (e: Exception) { Log.e("memory", "record failed", e); false }
    }

    fun all(limit: Int = 5000): List<MemoryEntry> {
        val out = ArrayList<MemoryEntry>()
        readableDatabase.rawQuery(
            "SELECT id, text, app, field, kind, count, last_used, pinned, shortcut FROM entries ORDER BY pinned DESC, last_used DESC LIMIT ?",
            arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) out += MemoryEntry(
                c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                runCatching { MemoryEntry.Kind.valueOf(c.getString(4)) }.getOrDefault(MemoryEntry.Kind.TYPED),
                c.getInt(5), c.getLong(6), c.getInt(7) != 0, c.getString(8))
        }
        return out
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM entries", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun update(id: Long, text: String) = exec("UPDATE entries SET text = ?, norm = ?, kind = 'MANUAL' WHERE id = ?", text.trim(), normalize(text), id)
    fun setShortcut(id: Long, shortcut: String) = exec("UPDATE entries SET shortcut = ? WHERE id = ?", normalizeShortcut(shortcut), id)
    fun setPinned(id: Long, pinned: Boolean) = exec("UPDATE entries SET pinned = ? WHERE id = ?", if (pinned) 1 else 0, id)
    fun delete(id: Long) = exec("DELETE FROM entries WHERE id = ?", id)
    fun clear() = exec("DELETE FROM entries")

    fun markUsed(text: String) {
        runCatching {
            writableDatabase.execSQL("UPDATE entries SET count = count + 1, last_used = ? WHERE norm = ?",
                arrayOf<Any>(System.currentTimeMillis(), normalize(text)))
        }
    }

    private fun exec(sql: String, vararg args: Any) {
        runCatching { writableDatabase.execSQL(sql, args) }.onFailure { Log.e("memory", "update failed", it) }
        changed()
    }

    fun insertManual(text: String, field: String, shortcut: String) =
        record(text, "TypePilot", field, MemoryEntry.Kind.MANUAL, shortcut)

    companion object {
        fun normalize(s: String) = s.lowercase().replace(Regex("""\s+"""), " ").trim()
        fun normalizeShortcut(s: String) = s.lowercase().filter { it.isLetterOrDigit() || it == '-' || it == '_' }

        fun isWorthRemembering(text: String, field: String = ""): Boolean {
            val t = text.trim()
            if (t.length !in 3..4000) return false
            if (SensitiveFilter.containsSecret(t) || SensitiveFilter.looksLikeOneTimeCode(t)) return false
            if (field.isNotEmpty() && SensitiveFilter.isSensitiveLabel(field)) return false
            if (t.length < 6 && t.none(Char::isLetter)) return false
            return true
        }
    }
}
