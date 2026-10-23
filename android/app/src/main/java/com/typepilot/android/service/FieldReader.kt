package com.typepilot.android.service

import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import com.typepilot.android.engine.FieldContext

/** A snapshot of the focused text field in another app. */
data class FocusedField(
    val node: AccessibilityNodeInfo,
    val pkg: String,
    val appName: String,
    val text: String,
    val selStart: Int,
    val selEnd: Int,
    val label: String,
    val isPassword: Boolean,
    val isMultiline: Boolean,
    val bounds: Rect,
) {
    val textBefore: String get() = text.substring(0, selStart.coerceIn(0, text.length))
    val textAfter: String get() = text.substring(selEnd.coerceIn(0, text.length))
    val selectedText: String get() = if (selEnd > selStart) text.substring(selStart.coerceIn(0, text.length), selEnd.coerceIn(0, text.length)) else ""
    /** Suggestions only make sense at the end of a line (not mid-sentence). */
    val caretAtLineEnd: Boolean get() = selStart == selEnd && (textAfter.isEmpty() || textAfter.startsWith("\n"))

    fun sameAs(other: FocusedField?) = other != null && other.pkg == pkg && other.node == node

    fun context(screen: String = "", question: String = "") = FieldContext(pkg, appName, label, textBefore, textAfter, selectedText, isMultiline, screen, question)
}

object FieldReader {
    private val passwordVariations = setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
    )

    fun read(node: AccessibilityNodeInfo, pkg: String, appName: String): FocusedField? {
        if (!node.isEditable) return null
        val showingHint = node.isShowingHintText
        val text = if (showingHint) "" else node.text?.toString().orEmpty()
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0 || start > text.length) { start = text.length; end = text.length }
        if (end < start || end > text.length) end = start
        val type = node.inputType
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val password = node.isPassword ||
            (cls == InputType.TYPE_CLASS_TEXT && variation in passwordVariations) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val bounds = Rect().also(node::getBoundsInScreen)
        // Search / address / filter boxes get a marker so the engine never offers a "reply" there.
        val id = node.viewIdResourceName.orEmpty().lowercase()
        val search = (cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_URI || variation == InputType.TYPE_TEXT_VARIATION_FILTER)) ||
            Regex("search|query|url_bar|omnibox|address_bar|find").containsMatchIn(id)
        val label = label(node).let { if (search) "$it (search)".trim() else it }
        return FocusedField(node, pkg, appName, text, start, end, label, password, node.isMultiLine, bounds)
    }

    /** What the field is for: its hint, description, label or view id ("message_input" → "message input"). */
    private fun label(node: AccessibilityNodeInfo): String {
        node.hintText?.toString()?.takeIf { it.isNotBlank() }?.let { return it.take(80) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return it.take(80) }
        node.labeledBy?.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it.take(80) }
        if (node.isShowingHintText) node.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it.take(80) }
        return node.viewIdResourceName?.substringAfterLast('/')?.replace('_', ' ')?.take(60).orEmpty()
    }

    /** On-screen bounds of the character before the caret (for big multi-line editors). One IPC round trip. */
    fun caretRect(field: FocusedField): Rect? {
        if (field.text.isEmpty()) return null
        val idx = (field.selStart - 1).coerceAtLeast(0)
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, idx)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, 1)
        }
        return runCatching {
            if (!field.node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args)) return null
            val rects: Array<out Any?>? = if (Build.VERSION.SDK_INT >= 33)
                field.node.extras.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, RectF::class.java)
            else @Suppress("DEPRECATION") field.node.extras.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)
            (rects?.firstOrNull() as? RectF)?.let { Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) }
        }.getOrNull()
    }
}

/**
 * The question a field asks when its own label is missing or generic: the closest text right above it, or to its
 * left on the same row ("Notice period *" over a web form box). Bounded walk, ~a few ms.
 */
object QuestionFinder {
    fun find(root: AccessibilityNodeInfo?, field: FocusedField, maxNodes: Int = 500, budgetMs: Long = 60): String {
        // 1) A label inside the field itself (Compose / Material floating labels are child text nodes).
        inner(field.node, field)?.let { return it }
        root ?: return ""
        // Fresh position: the page may have scrolled since the field was read (keyboard opening), and comparing old
        // field bounds with new label positions picks the previous question's label.
        val f = Rect(field.bounds).also { r -> runCatching { if (field.node.refresh()) field.node.getBoundsInScreen(r) } }
        val deadline = System.nanoTime() + budgetMs * 1_000_000
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var best: Pair<Int, String>? = null
        var visited = 0
        val r = Rect()
        while (queue.isNotEmpty() && visited < maxNodes && System.nanoTime() < deadline) {
            val n = queue.removeFirst(); visited++
            if (!n.isVisibleToUser) continue
            if (!n.isEditable) {
                val t = n.text?.toString()?.trim().orEmpty()
                // A question has words: skip flags, icons, "+1" country codes and bare numbers.
                if (t.length in 2..200 && t.count { it.isLetter() } >= 2 && t != field.text && !t.equals(field.label, true)) {
                    n.getBoundsInScreen(r)
                    val dist = labelDistance(r.left, r.top, r.right, r.bottom, f.left, f.top, f.right, f.bottom)
                    if (dist != null && (best == null || dist < best.first)) best = dist to t
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
        }
        return best?.second.orEmpty()
    }

    /**
     * How far a text is from the field, if it can be the field's label: above it (or a floating label on its top
     * border), or to its left on the same line. Anything else (below the field, a toast, the next field's label) is
     * never this field's question: null.
     */
    fun labelDistance(l: Int, t: Int, r: Int, b: Int, fl: Int, ft: Int, fr: Int, fb: Int): Int? {
        val overlapsX = r > fl && l < fr
        val onBorder = t <= ft + 8 && b < (ft + fb) / 2 && overlapsX
        val above = onBorder || (b <= ft + 12 && overlapsX && ft - b < 260)
        val left = r <= fl + 12 && b > ft && t < fb && fl - r < 420
        return when { above -> (ft - b).coerceAtLeast(0); left -> (fl - r).coerceAtLeast(0) + 40; else -> null }
    }

    private fun inner(node: AccessibilityNodeInfo, field: FocusedField, depth: Int = 0): String? {
        if (depth > 3) return null
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val t = c.text?.toString()?.trim().orEmpty()
            if (!c.isEditable && t.length in 2..120 && t.count { it.isLetter() } >= 2 && t != field.text) return t
            inner(c, field, depth + 1)?.let { return it }
        }
        return null
    }

    /** Labels that say nothing about what to type. */
    fun generic(label: String) = label.isBlank() || Regex("""^(type here|enter( text| value)?|text|input|answer|edittext|value)$""", RegexOption.IGNORE_CASE).matches(label.trim())
}

/**
 * The text around the field (e.g. the chat messages above the reply box), so suggestions and replies are relevant.
 * Bounded walk: at most [maxNodes] nodes and [budgetMs] ms, then it stops with what it has.
 */
object ScreenContextReader {
    fun read(root: AccessibilityNodeInfo?, field: FocusedField, maxNodes: Int = 450, budgetMs: Long = 90, maxChars: Int = 1500): String {
        root ?: return ""
        val deadline = System.nanoTime() + budgetMs * 1_000_000
        val items = ArrayList<Pair<Int, String>>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val r = Rect()
        while (queue.isNotEmpty() && visited < maxNodes && System.nanoTime() < deadline) {
            val n = queue.removeFirst()
            visited++
            if (!n.isVisibleToUser) continue
            if (!n.isEditable) {
                val t = n.text?.toString()?.trim().orEmpty().ifEmpty { n.contentDescription?.toString()?.trim().orEmpty() }
                if (t.length >= 2) {
                    n.getBoundsInScreen(r)
                    // Text above (or level with) the field is what you're replying to.
                    if (r.top <= field.bounds.bottom) items += r.top to t.take(400)
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
        }
        val sb = StringBuilder()
        for ((_, t) in items.sortedBy { it.first }.distinctBy { it.second }) sb.append(t).append('\n')
        return sb.toString().takeLast(maxChars).trim()
    }
}
