package com.typepilot.android.core

import android.util.Log as ALog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Logcat (tag "TypePilot") plus a small ring buffer for the in-app Logs screen. */
object Log {
    data class Line(val time: Long, val level: Char, val area: String, val message: String) {
        override fun toString(): String = "${fmt.format(Date(time))} $level [$area] $message"
    }

    private const val TAG = "TypePilot"
    private const val CAPACITY = 400
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val buffer = ArrayDeque<Line>(CAPACITY)
    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines
    @Volatile var verbose = false
    /** Errors logged with an exception are also reported (type and stack only) when usage sharing is on. */
    @Volatile var onError: ((area: String, t: Throwable) -> Unit)? = null

    fun d(area: String, msg: String) { if (verbose) add('D', area, msg) }
    fun i(area: String, msg: String) = add('I', area, msg)
    fun w(area: String, msg: String) = add('W', area, msg)
    fun e(area: String, msg: String, t: Throwable? = null) {
        add('E', area, msg + (t?.let { ": ${it.message}" } ?: ""))
        if (t != null) runCatching { onError?.invoke(area, t) }
    }

    private fun add(level: Char, area: String, msg: String) {
        when (level) {
            'D' -> ALog.d(TAG, "[$area] $msg")
            'I' -> ALog.i(TAG, "[$area] $msg")
            'W' -> ALog.w(TAG, "[$area] $msg")
            else -> ALog.e(TAG, "[$area] $msg")
        }
        synchronized(buffer) {
            if (buffer.size == CAPACITY) buffer.removeFirst()
            buffer.addLast(Line(System.currentTimeMillis(), level, area, msg))
            _lines.value = buffer.toList()
        }
    }

    fun clear() = synchronized(buffer) { buffer.clear(); _lines.value = emptyList() }
}
