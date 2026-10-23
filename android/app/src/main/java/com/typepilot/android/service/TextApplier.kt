package com.typepilot.android.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.typepilot.android.core.Log

/**
 * Writes into another app's field. ACTION_SET_TEXT + ACTION_SET_SELECTION works in nearly every app
 * (native, Compose, Chrome/WebView). If an app refuses, we fall back to a paste and restore the clipboard.
 */
object TextApplier {
    private val main = Handler(Looper.getMainLooper())

    /** Insert at the caret (replacing [replaceLength] chars before it, e.g. a ";shortcut"). Returns the new caret or -1. */
    fun insert(context: Context, field: FocusedField, text: String, replaceLength: Int = 0): Int {
        val node = field.node
        node.refresh()
        val full = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        var s = node.textSelectionStart.takeIf { it in 0..full.length } ?: full.length
        val e = node.textSelectionEnd.takeIf { it in s..full.length } ?: s
        s = (s - replaceLength).coerceAtLeast(0)
        return replaceRange(context, node, full, s, e, text)
    }

    /** Replace [start, end) of the current text with [text]. */
    fun replaceRange(context: Context, node: AccessibilityNodeInfo, full: String, start: Int, end: Int, text: String): Int {
        val a = start.coerceIn(0, full.length)
        val b = end.coerceIn(a, full.length)
        val next = full.substring(0, a) + text + full.substring(b)
        val caret = a + text.length
        if (setText(node, next)) {
            setSelection(node, caret)
            return caret
        }
        Log.w("insert", "SET_TEXT refused; pasting instead")
        if (a != b) setSelection(node, a, b)
        return if (paste(context, node, text)) caret else -1
    }

    fun replaceAll(context: Context, node: AccessibilityNodeInfo, text: String): Boolean {
        node.refresh()
        val full = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        return replaceRange(context, node, full, 0, full.length, text) >= 0
    }

    fun setText(node: AccessibilityNodeInfo, text: String): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })

    fun setSelection(node: AccessibilityNodeInfo, start: Int, end: Int = start): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        })

    private fun paste(context: Context, node: AccessibilityNodeInfo, text: String): Boolean {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return false
        val previous = runCatching { cm.primaryClip }.getOrNull()
        cm.setPrimaryClip(ClipData.newPlainText("TypePilot", text))
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        main.postDelayed({ runCatching { if (previous != null) cm.setPrimaryClip(previous) else cm.clearPrimaryClip() } }, 700)
        return ok
    }

    fun copy(context: Context, text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("TypePilot", text))
    }
}
