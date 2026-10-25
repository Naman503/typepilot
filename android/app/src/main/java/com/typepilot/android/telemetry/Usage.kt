package com.typepilot.android.telemetry

import android.os.SystemClock

/**
 * One place for the app to report usage from, without passing the reporter around. Every call is a no-op
 * until [UsageReporter] is attached (and in unit tests), and the reporter itself does nothing while
 * "Share anonymous usage" is off. Only fixed words, buckets and counts go in: see [UsageMapping].
 */
object Usage {
    @Volatile var reporter: UsageReporter? = null

    fun event(name: String, props: Map<String, String> = emptyMap()) { runCatching { reporter?.event(name, props) } }
    fun count(name: String, props: Map<String, String> = emptyMap(), n: Int = 1) { runCatching { reporter?.count(name, props, n) } }
    fun once(name: String, props: Map<String, String> = emptyMap()) { runCatching { reporter?.once(name, props) } }
    fun nonFatal(t: Throwable, area: String) { runCatching { reporter?.nonFatal(t, area) } }

    /** An AI call finished: which operation, how it ended, which provider family, how long it took. */
    fun aiRequest(op: String, outcome: String, provider: String, latencyMs: Long, ttftMs: Long = 0, error: String? = null) {
        count("ai_request", buildMap {
            put("op", op); put("outcome", outcome); put("provider", provider)
            if (outcome == "ok" || outcome == "empty") put("latency", UsageMapping.latency(latencyMs))
            if (outcome == "ok" && ttftMs > 0) put("ttft", UsageMapping.latency(ttftMs))
        })
        if (error != null) count("ai_error", mapOf("op" to op, "category" to error, "provider" to provider))
    }

    // ---- accessibility state: the smart switch / recovery say why the next change happens

    @Volatile private var expectedCause: String? = null
    @Volatile private var expectedAt = 0L

    /** Called right before TypePilot itself turns the service on or off. */
    fun expectAccessibilityChange(cause: String) { expectedCause = cause; expectedAt = SystemClock.elapsedRealtime() }

    fun accessibilityState(on: Boolean, fallbackCause: String = "user_or_system") {
        val cause = expectedCause?.takeIf { SystemClock.elapsedRealtime() - expectedAt < 15_000 } ?: fallbackCause
        runCatching { reporter?.accessibilityState(on, cause) }
    }

    // ---- sessions

    /** A visit to the TypePilot app itself (MainActivity in front). */
    private var appVisitStart = 0L

    fun appVisible() { appVisitStart = SystemClock.elapsedRealtime() }
    fun appHidden() {
        if (appVisitStart == 0L) return
        count("app_session", mapOf("length" to UsageMapping.duration(SystemClock.elapsedRealtime() - appVisitStart)))
        appVisitStart = 0L
    }

    /**
     * A typing session: from entering a text field to leaving it. [surface] is overlay (accessibility) or
     * keyboard; [typed] whether you typed anything there.
     */
    fun typingSession(surface: String, pkg: String, startedAt: Long, typed: Boolean) {
        if (startedAt <= 0) return
        count("typing_session", mapOf(
            "surface" to surface, "app" to UsageMapping.appCategory(pkg),
            "length" to UsageMapping.duration(SystemClock.elapsedRealtime() - startedAt), "typed" to typed.toString(),
        ))
    }
}
