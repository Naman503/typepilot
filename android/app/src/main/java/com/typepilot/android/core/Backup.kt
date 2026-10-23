package com.typepilot.android.core

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import com.typepilot.android.BuildConfig
import com.typepilot.android.TypePilotApp
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Back up and restore everything TypePilot has learned: facts and documents, phrases and shortcuts,
 * stats, and settings. API keys are never included (they're encrypted with a key that can't leave the phone).
 *
 * A restore is staged into a folder and applied on the next start, before any database is opened,
 * so nothing is replaced under an open connection.
 */
object Backup {
    private val DATABASES = listOf("knowledge.db", "memory.db", "metrics.db")
    private const val STAGED = "restore-staged"
    private const val MAX_ENTRY = 200L * 1024 * 1024
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray()

    class BadBackup(message: String) : Exception(message)

    fun fileName(): String = "typepilot-backup-${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}.zip"

    /** Write the backup zip to [uri] (from the system "Save as" picker). */
    fun export(context: Context, uri: Uri) {
        val app = TypePilotApp.instance
        val helpers: Map<String, SQLiteOpenHelper> = mapOf("knowledge.db" to app.facts, "memory.db" to app.memory, "metrics.db" to app.metrics)
        val out = context.contentResolver.openOutputStream(uri) ?: throw BadBackup("Couldn't open the file to write")
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(JSONObject().put("app", "TypePilot").put("format", 1).put("version", BuildConfig.VERSION_NAME)
                .put("created", System.currentTimeMillis()).toString().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("settings.json"))
            zip.write(app.settings.value.toJson().toString().toByteArray())
            zip.closeEntry()
            for ((name, helper) in helpers) {
                val db = helper.writableDatabase
                // An exclusive transaction keeps every writer out while the file is copied, so the copy is consistent.
                db.beginTransaction()
                try {
                    val file = context.getDatabasePath(name)
                    if (!file.exists()) continue
                    zip.putNextEntry(ZipEntry("databases/$name"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                } finally { db.endTransaction() }
            }
        }
        Log.i("backup", "exported memory, phrases, stats and settings")
    }

    /** Check and stage the backup at [uri]; [restart] applies it. Only known entries are read (no paths from the zip are used). */
    fun stage(context: Context, uri: Uri) {
        val dir = File(context.filesDir, STAGED).apply { deleteRecursively(); mkdirs() }
        var manifest: JSONObject? = null
        var databases = 0
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw BadBackup("Couldn't open the file")
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val target = when {
                        e.name == "manifest.json" -> null
                        e.name == "settings.json" -> File(dir, "settings.json")
                        e.name.startsWith("databases/") && e.name.removePrefix("databases/") in DATABASES -> File(dir, e.name.removePrefix("databases/"))
                        else -> { zip.closeEntry(); continue }
                    }
                    val bytes = readLimited(zip)
                    if (target == null) manifest = runCatching { JSONObject(String(bytes)) }.getOrNull()
                    else {
                        if (target.name.endsWith(".db")) {
                            if (bytes.size < SQLITE_HEADER.size || !bytes.copyOfRange(0, SQLITE_HEADER.size).contentEquals(SQLITE_HEADER)) throw BadBackup("${target.name} isn't a database")
                            databases++
                        } else runCatching { JSONObject(String(bytes)) }.getOrElse { throw BadBackup("The settings in this backup are damaged") }
                        target.writeBytes(bytes)
                    }
                }
            }
            if (manifest?.optString("app") != "TypePilot") throw BadBackup("This isn't a TypePilot backup")
            if (databases == 0) throw BadBackup("This backup has no memory in it")
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw if (e is BadBackup) e else BadBackup("Couldn't read the backup: ${e.message}")
        }
        Log.i("backup", "restore staged ($databases databases, from ${manifest?.optString("version")})")
    }

    private fun readLimited(zip: ZipInputStream): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = zip.read(chunk)
            if (n < 0) break
            total += n
            if (total > MAX_ENTRY) throw BadBackup("The backup is too large")
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }

    /** Restart TypePilot so the staged backup is applied. */
    fun restart(context: Context) {
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            context.startActivity(Intent.makeRestartActivityTask(it.component))
        }
        Runtime.getRuntime().exit(0)
    }

    /** Called first thing at app start: swap in a staged backup before the stores open their files. */
    fun applyStaged(context: Context) {
        val dir = File(context.filesDir, STAGED)
        if (!dir.isDirectory) return
        try {
            for (name in DATABASES) {
                val staged = File(dir, name)
                if (!staged.exists()) continue
                val db = context.getDatabasePath(name)
                db.parentFile?.mkdirs()
                for (suffix in listOf("", "-journal", "-wal", "-shm")) File(db.path + suffix).delete()
                staged.copyTo(db, overwrite = true)
            }
            File(dir, "settings.json").takeIf { it.exists() }?.let {
                context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("json", it.readText()).commit()
            }
            Log.i("backup", "restored a backup")
        } catch (e: Exception) {
            Log.e("backup", "restore failed", e)
        } finally {
            dir.deleteRecursively()
        }
    }
}
