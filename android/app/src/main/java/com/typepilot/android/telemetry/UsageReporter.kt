package com.typepilot.android.telemetry

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.typepilot.android.BuildConfig
import com.typepilot.android.ai.Http
import com.typepilot.android.core.AppSettings
import com.typepilot.android.core.Log
import com.typepilot.android.metrics.EventType
import com.typepilot.android.metrics.MetricEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Anonymous usage reporting (Settings → Apps, memory & privacy → Share anonymous usage),
 * on by default. Sends feature names, daily counts, setup steps and crashes to
 * POST /v1/app/typepilot with a random install id. Never what you type, field contents,
 * app names, contacts, model hosts or keys. Turning it off deletes what was sent.
 *
 * Its state lives in its own "telemetry" prefs, outside Backup, so a restored phone gets
 * a new install id instead of the old one.
 */
class UsageReporter(
    private val context: Context,
    private val settings: () -> AppSettings,
    private val scope: CoroutineScope,
    private val dailyFacts: () -> Map<String, Map<String, String>>,
    private val setupDone: () -> Set<String>,
) {
    private val prefs = context.getSharedPreferences("telemetry", Context.MODE_PRIVATE)
    private val lock = Any()

    val installId: String by lazy {
        prefs.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_ID, it).apply() }
    }

    private val enabled get() = settings().shareUsage

    fun start() {
        installCrashHandler()
        val last = prefs.getString(KEY_VERSION, null)
        if (last == null) once("app_installed")
        else if (last != BuildConfig.VERSION_NAME) event("app_update", mapOf("from" to last.take(16), "to" to BuildConfig.VERSION_NAME))
        prefs.edit().putString(KEY_VERSION, BuildConfig.VERSION_NAME).apply()
        scope.launch {
            delay(30_000) // let the app settle first
            while (true) {
                flush()
                delay(6 * 3_600_000L)
            }
        }
    }

    /** Mirrors the Insights recorder, so reporting works even when local stats are off. */
    fun onMetric(e: MetricEvent) {
        when (e.type) {
            EventType.SHOWN -> count("suggestions_shown")
            EventType.ACCEPTED -> {
                count("suggestion_accepted", mapOf("source" to UsageMapping.source(e.source)))
                once("first_accept")
            }
            EventType.REWRITE -> event("rewrite_used", mapOf("mode" to UsageMapping.rewriteMode(e.mode)))
            EventType.WRITE -> event("write_with_ai")
            else -> Unit
        }
    }

    fun event(name: String, props: Map<String, String> = emptyMap()) {
        if (!enabled) return
        synchronized(lock) {
            val list = pending()
            if (list.length() >= MAX_PENDING) return
            list.put(JSONObject().put("n", name).put("t", System.currentTimeMillis()).apply { if (props.isNotEmpty()) put("p", JSONObject(props)) })
            prefs.edit().putString(KEY_PENDING, list.toString()).apply()
        }
    }

    fun count(name: String, props: Map<String, String> = emptyMap(), n: Int = 1) {
        if (!enabled) return
        synchronized(lock) {
            val c = counters()
            val key = UsageMapping.counterKey(today(), name, props)
            c.put(key, c.optInt(key) + n)
            prefs.edit().putString(KEY_COUNTERS, c.toString()).apply()
        }
    }

    /** A milestone sent once per install (setup steps, first accepted suggestion). */
    fun once(name: String) {
        if (!enabled) return
        synchronized(lock) {
            val done = prefs.getStringSet(KEY_ONCE, emptySet()).orEmpty()
            if (name in done) return
            prefs.edit().putStringSet(KEY_ONCE, done + name).apply()
        }
        event(name)
    }

    /** Sharing switched off: forget the queue and ask the server to delete what this install sent. */
    fun turnedOff() {
        synchronized(lock) {
            prefs.edit().remove(KEY_PENDING).remove(KEY_COUNTERS).remove(KEY_CRASHES).putBoolean(KEY_OFF_PENDING, true).apply()
        }
        scope.launch { flush() }
    }

    fun turnedOn() {
        prefs.edit().putBoolean(KEY_OFF_PENDING, false).apply()
    }

    suspend fun flush() = withContext(Dispatchers.IO) {
        if (prefs.getBoolean(KEY_OFF_PENDING, false)) {
            if (post(base().put("off", true))) prefs.edit().putBoolean(KEY_OFF_PENDING, false).apply()
            return@withContext
        }
        if (!enabled) return@withContext
        dailyCheck()
        val (body, sentCounters, sentEvents, sentCrashes) = synchronized(lock) {
            val events = pending()
            val c = counters()
            val today = today()
            val sentKeys = mutableListOf<String>()
            for (key in c.keys()) {
                val (day, name, props) = UsageMapping.parseCounterKey(key) ?: continue
                if (day == today) continue // only finished days
                events.put(JSONObject().put("n", name).put("t", noonOf(day)).put("c", c.getInt(key)).apply { if (props.isNotEmpty()) put("p", JSONObject(props)) })
                sentKeys += key
            }
            val crashes = crashes()
            Quad(base().put("e", events).put("cr", crashes), sentKeys, pending().length(), crashes.length())
        }
        if (sentCounters.isEmpty() && sentEvents == 0 && sentCrashes == 0) return@withContext
        if (!post(body)) return@withContext
        synchronized(lock) {
            // Drop what was sent; anything queued meanwhile stays for next time.
            val events = pending()
            val rest = JSONArray().apply { for (i in sentEvents until events.length()) put(events.get(i)) }
            val c = counters().apply { sentCounters.forEach { remove(it) } }
            val cr = crashes()
            val crRest = JSONArray().apply { for (i in sentCrashes until cr.length()) put(cr.get(i)) }
            prefs.edit().putString(KEY_PENDING, rest.toString()).putString(KEY_COUNTERS, c.toString()).putString(KEY_CRASHES, crRest.toString()).apply()
        }
    }

    /** Once a day: which provider and keyboard are in use, and setup steps reached. */
    private fun dailyCheck() {
        val day = today()
        if (prefs.getString(KEY_DAILY, null) == day) return
        prefs.edit().putString(KEY_DAILY, day).apply()
        for ((name, props) in dailyFacts()) event(name, props)
        for (step in setupDone()) once(step)
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            try {
                if (enabled) recordCrash(t, fatal = true)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, t)
        }
    }

    /** Exception type and stack only: messages can contain user text, so they are not sent. */
    fun recordCrash(t: Throwable, fatal: Boolean) {
        synchronized(lock) {
            val list = crashes()
            if (list.length() >= MAX_CRASHES) return
            val stack = t.stackTrace.take(25).joinToString("\n") { "at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
            list.put(
                JSONObject().put("fp", UsageMapping.crashFingerprint(t)).put("m", t.javaClass.name.take(200))
                    .put("st", stack.take(4000)).put("t", System.currentTimeMillis()).put("fatal", fatal),
            )
            // commit(): the process is about to die.
            prefs.edit().putString(KEY_CRASHES, list.toString()).commit()
        }
    }

    private fun base(): JSONObject {
        val tablet = (context.resources.configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_LARGE
        return JSONObject()
            .put("i", installId)
            .put("v", BuildConfig.VERSION_NAME)
            .put("pf", "android")
            .put("os", "Android")
            .put("osv", Build.VERSION.RELEASE.take(32))
            .put("dc", if (tablet) "tablet" else "phone")
            .put("dm", "${Build.MANUFACTURER} ${Build.MODEL}".take(48))
            .put("loc", Locale.getDefault().toLanguageTag().take(16))
    }

    private fun post(body: JSONObject): Boolean = try {
        val req = Request.Builder().url(ENDPOINT).post(body.toString().toRequestBody("application/json".toMediaType())).build()
        Http.client.newCall(req).execute().use { it.isSuccessful }
    } catch (e: Exception) {
        Log.i("usage", "report not sent: ${e.javaClass.simpleName}")
        false
    }

    private fun pending() = JSONArray(prefs.getString(KEY_PENDING, null) ?: "[]")
    private fun counters() = JSONObject(prefs.getString(KEY_COUNTERS, null) ?: "{}")
    private fun crashes() = JSONArray(prefs.getString(KEY_CRASHES, null) ?: "[]")

    private data class Quad(val body: JSONObject, val counters: List<String>, val events: Int, val crashes: Int)

    companion object {
        const val ENDPOINT = "https://api.np-dev.duckdns.org/v1/app/typepilot"
        private const val KEY_ID = "install_id"
        private const val KEY_VERSION = "last_version"
        private const val KEY_PENDING = "pending"
        private const val KEY_COUNTERS = "counters"
        private const val KEY_CRASHES = "crashes"
        private const val KEY_ONCE = "once"
        private const val KEY_DAILY = "daily_checked"
        private const val KEY_OFF_PENDING = "off_pending"
        private const val MAX_PENDING = 500
        private const val MAX_CRASHES = 20

        private fun dayFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fun today(): String = dayFormat().format(Date())
        /** A counter's day as a timestamp: noon local time, so it lands on that day in any report. */
        fun noonOf(day: String): Long = (dayFormat().apply { timeZone = TimeZone.getDefault() }.parse(day)?.time ?: System.currentTimeMillis()) + 12 * 3_600_000L
    }
}
