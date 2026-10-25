package com.typepilot.android.ime

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.typepilot.android.TypePilotApp
import com.typepilot.android.core.Log
import com.typepilot.android.engine.FieldContext
import com.typepilot.android.engine.RewriteMode
import com.typepilot.android.engine.SuggestTrigger
import com.typepilot.android.engine.Suggestion
import com.typepilot.android.memory.MemoryEntry
import com.typepilot.android.metrics.EventType
import com.typepilot.android.metrics.LiveMetrics
import com.typepilot.android.metrics.MetricEvent
import com.typepilot.android.metrics.Stage
import com.typepilot.android.text.SensitiveFilter
import com.typepilot.android.telemetry.Usage
import com.typepilot.android.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * The TypePilot keyboard: a full keyboard with AI built into the strip above the keys.
 *  - suggestion strip: memory / AI continuation, tap to insert, ✕ to close
 *  - ✦ Rewrite: Fix grammar · Grammar + flow · Best version · Shorter · Formal · … on the selection or the whole field
 *  - ✎ Write: type a request on the keyboard itself, the draft appears above the keys, Insert
 * Works where accessibility services are blocked (Advanced Protection) and is the Play-friendly route.
 */
class TypePilotIme : InputMethodService(), KeyboardView.Listener {
    private val app get() = TypePilotApp.instance
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var root: LinearLayout
    private lateinit var strip: LinearLayout
    private lateinit var body: FrameLayout
    private lateinit var keyboard: KeyboardView
    private lateinit var result: LinearLayout
    private lateinit var resultText: TextView
    private lateinit var resultActions: LinearLayout

    private enum class StripMode { TOOLS, SUGGESTION, MODES, ASK, BUSY }
    private var stripMode = StripMode.TOOLS
    private var suggestion: Suggestion? = null
    private var suggestionShownAt = 0L
    private var suggestJob: Job? = null
    private var aiJob: Job? = null
    private var askBuffer = StringBuilder()
    private var typedInField = false
    private var noLearning = false
    private var secureField = false
    private var lastSpaceAt = 0L
    /** Typing-session start, and the tab ("rewrite"/"write") of an AI result on screen that isn't applied yet. */
    private var sessionSince = 0L
    private var sessionPkg = ""
    private var sessionTyped = false
    private var resultTab: String? = null

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()
    private val dark get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val fg get() = if (dark) Color.rgb(236, 236, 244) else Color.rgb(28, 28, 36)
    private val muted get() = if (dark) Color.rgb(150, 150, 166) else Color.rgb(110, 112, 128)
    private val stripBg get() = if (dark) Color.rgb(30, 30, 38) else Color.rgb(244, 244, 250)
    private val accent = Color.rgb(124, 92, 255)

    override fun onCreateInputView(): View {
        keyboard = KeyboardView(this, this)
        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6f), 0, dp(6f), 0)
        }
        resultText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextIsSelectable(true)
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        }
        resultActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(dp(8f), dp(4f), dp(8f), dp(8f)) }
        result = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ScrollView(this@TypePilotIme).apply { addView(resultText) }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(resultActions, LinearLayout.LayoutParams(-1, -2))
            visibility = View.GONE
        }
        body = FrameLayout(this).apply {
            addView(keyboard, FrameLayout.LayoutParams(-1, -2))
            addView(result, FrameLayout.LayoutParams(-1, -1))
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(strip, LinearLayout.LayoutParams(-1, dp(46f)))
            addView(body, LinearLayout.LayoutParams(-1, -2))
            // Android 15+ draws keyboards edge-to-edge: keep the keys above the navigation/gesture bar.
            setOnApplyWindowInsetsListener { v, insets ->
                val nav = insets.getInsets(android.view.WindowInsets.Type.navigationBars())
                v.setPadding(nav.left, 0, nav.right, nav.bottom)
                insets
            }
        }
        applyTheme()
        showTools()
        return root
    }

    private fun applyTheme() {
        root.setBackgroundColor(stripBg)
        strip.setBackgroundColor(stripBg)
        result.setBackgroundColor(if (dark) Color.rgb(24, 24, 30) else Color.rgb(232, 233, 240))
        resultText.setTextColor(fg)
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        isActiveInput = true
        LiveMetrics.updateStatus { it.copy(keyboardActive = true) }
        keyboard.haptics = app.settings.value.haptics
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        secureField = (cls == InputType.TYPE_CLASS_TEXT && variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) ||
            SensitiveFilter.isSensitiveLabel(info.hintText?.toString().orEmpty())
        noLearning = secureField || (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        keyboard.mode = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) KeyboardView.Mode.SYMBOLS else KeyboardView.Mode.LETTERS
        keyboard.enterLabel = when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_SEARCH -> "🔍"
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_DONE -> "Done"
            else -> "⏎"
        }
        typedInField = false
        closeResult()
        endSession()
        sessionSince = SystemClock.elapsedRealtime(); sessionPkg = info.packageName.orEmpty(); sessionTyped = false
        updateAutoCaps()
        if (!secureField && app.settings.value.isActive) requestSuggestion(SuggestTrigger.FOCUS, 0) else showTools()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (typedInField) sessionTyped = true
        learnField()
        resultDone("discarded")
        endSession()
        isActiveInput = false
        LiveMetrics.updateStatus { it.copy(keyboardActive = false) }
        suggestJob?.cancel()
        suggestion?.let { recordSuggestion(EventType.IGNORED, it, "") }
        suggestion = null
        super.onFinishInputView(finishingInput)
    }

    /**
     * The text or cursor changed: by us, by a paste, a tap in the field or a hardware keyboard.
     * A suggestion computed for the old position is stale, so drop it and look again after the usual pause.
     */
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (newSelStart == lastSelStart && newSelEnd == lastSelEnd) return
        lastSelStart = newSelStart; lastSelEnd = newSelEnd
        if (stripMode == StripMode.ASK || stripMode == StripMode.MODES || stripMode == StripMode.BUSY) return
        val s = suggestion
        if (s != null && SystemClock.uptimeMillis() > ownEditUntil) {
            recordSuggestion(EventType.IGNORED, s, "")
            suggestion = null
            showTools()
        }
        updateAutoCaps()
        if (SystemClock.uptimeMillis() > ownEditUntil) requestSuggestion(SuggestTrigger.TYPING)
    }

    private var lastSelStart = -1
    private var lastSelEnd = -1
    /** Our own commits also trigger onUpdateSelection; they're handled where they happen. */
    private var ownEditUntil = 0L

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ctx(): FieldContext {
        val ic = currentInputConnection
        val info = currentInputEditorInfo
        val before = ic?.getTextBeforeCursor(1500, 0)?.toString().orEmpty()
        val after = ic?.getTextAfterCursor(300, 0)?.toString().orEmpty()
        val selected = ic?.getSelectedText(0)?.toString().orEmpty()
        val pkg = info?.packageName.orEmpty()
        val appName = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        // Search / address / filter boxes: tell the engine, so it never offers a "reply" there.
        val variation = (info?.inputType ?: 0) and InputType.TYPE_MASK_VARIATION
        val search = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION == EditorInfo.IME_ACTION_SEARCH ||
            variation == InputType.TYPE_TEXT_VARIATION_URI || variation == InputType.TYPE_TEXT_VARIATION_FILTER
        val label = info?.hintText?.toString().orEmpty() + if (search) " (search)" else ""
        return FieldContext(pkg, appName, label.trim(), before, after, selected,
            isMultiline = (info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
    }

    // ------------------------------------------------------------ keys

    override fun onText(text: String) {
        if (stripMode == StripMode.ASK) { askBuffer.append(text); renderAsk(); return }
        val ic = currentInputConnection ?: return
        ownEditUntil = SystemClock.uptimeMillis() + 250
        val now = SystemClock.uptimeMillis()
        // Double space → ". "
        if (text == " " && now - lastSpaceAt < 350) {
            val before = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
            if (before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                ic.deleteSurroundingText(1, 0); ic.commitText(". ", 1); lastSpaceAt = 0; afterEdit(2)
                Usage.count("keyboard_shortcut", mapOf("kind" to "double_space_period")); return
            }
        }
        if (text == " ") lastSpaceAt = now
        val sug = suggestion
        ic.commitText(text, 1)
        // Typing exactly what's suggested keeps the rest showing.
        if (sug != null && sug.replaceLength == 0 && sug.text.startsWith(text) && sug.text.length > text.length) {
            suggestion = sug.copy(text = sug.text.substring(text.length))
            renderSuggestion()
            LiveMetrics.typed(text.length, now)
            typedInField = true
            updateAutoCaps()
            return
        }
        afterEdit(text.length)
    }

    override fun onDelete(word: Boolean) {
        if (stripMode == StripMode.ASK) {
            if (askBuffer.isNotEmpty()) askBuffer.setLength(if (word) askBuffer.trimEnd().lastIndexOf(' ').coerceAtLeast(0) else askBuffer.length - 1)
            renderAsk(); return
        }
        val ic = currentInputConnection ?: return
        ownEditUntil = SystemClock.uptimeMillis() + 250
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1)
        else if (word) {
            val before = ic.getTextBeforeCursor(64, 0)?.toString().orEmpty()
            val n = before.length - before.trimEnd().dropLastWhile { !it.isWhitespace() }.length
            ic.deleteSurroundingText(n.coerceAtLeast(1), 0)
        } else ic.deleteSurroundingText(1, 0)
        afterEdit(0)
    }

    override fun onEnter() {
        if (stripMode == StripMode.ASK) { runWrite(); return }
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val multiline = (info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        if (!multiline && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED &&
            (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0) {
            if (action == EditorInfo.IME_ACTION_SEND) learnField()
            ic.performEditorAction(action)
        } else ic.commitText("\n", 1)
        afterEdit(1)
    }

    override fun onCursor(delta: Int) {
        val ic = currentInputConnection ?: return
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        val pos = (extracted?.selectionStart ?: return) + delta
        val len = extracted.text?.length ?: 0
        val p = pos.coerceIn(0, len)
        ic.setSelection(p, p)
    }

    override fun onSwitchKeyboard() {
        tool("switch_keyboard")
        getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    private fun afterEdit(typedChars: Int) {
        typedInField = true
        sessionTyped = true
        if (typedChars > 0) LiveMetrics.typed(typedChars, SystemClock.uptimeMillis())
        suggestion?.let { recordSuggestion(EventType.IGNORED, it, "") }
        suggestion = null
        updateAutoCaps()
        if (stripMode == StripMode.SUGGESTION) showTools()
        requestSuggestion(SuggestTrigger.TYPING)
    }

    private fun updateAutoCaps() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: return
        if (keyboard.shift == KeyboardView.Shift.LOCK || keyboard.mode != KeyboardView.Mode.LETTERS) return
        val caps = ic.getCursorCapsMode(info.inputType) != 0
        keyboard.shift = if (caps) KeyboardView.Shift.ON else KeyboardView.Shift.OFF
    }

    // ------------------------------------------------------------ suggestions

    private fun requestSuggestion(trigger: SuggestTrigger, delayMs: Int? = null) {
        suggestJob?.cancel()
        val s = app.settings.value
        if (!s.isActive || secureField || stripMode == StripMode.ASK || stripMode == StripMode.MODES || result.visibility == View.VISIBLE) return
        suggestJob = scope.launch {
            delay((delayMs ?: s.debounceMs).toLong())
            val c = ctx()
            if (c.selectedText.isNotEmpty() || (c.textAfter.isNotEmpty() && !c.textAfter.startsWith("\n"))) return@launch
            val t0 = SystemClock.elapsedRealtime()
            val sug = app.engine.suggest(c, trigger) ?: return@launch
            val fresh = currentInputConnection?.getTextBeforeCursor(1500, 0)?.toString().orEmpty()
            if (fresh != c.textBefore) return@launch
            suggestion = sug
            suggestionShownAt = SystemClock.elapsedRealtime()
            renderSuggestion()
            LiveMetrics.record(Stage.END_TO_END, suggestionShownAt - t0 + (delayMs ?: s.debounceMs))
            app.metrics.record(MetricEvent(EventType.SHOWN, sug.source, c.packageName, sug.provider, latencyMs = suggestionShownAt - t0,
                ttftMs = sug.ttftMs, chars = sug.text.length, words = sug.words))
        }
    }

    private fun recordSuggestion(type: EventType, s: Suggestion, text: String) {
        app.metrics.record(MetricEvent(type, s.source, currentInputEditorInfo?.packageName.orEmpty(), s.provider,
            latencyMs = SystemClock.elapsedRealtime() - suggestionShownAt, chars = text.length,
            words = text.trim().split(Regex("""\s+""")).count { it.isNotEmpty() }))
    }

    private fun acceptSuggestion() {
        val s = suggestion ?: return
        val ic = currentInputConnection ?: return
        ownEditUntil = SystemClock.uptimeMillis() + 400
        ic.beginBatchEdit()
        if (s.replaceLength > 0) ic.deleteSurroundingText(s.replaceLength, 0)
        ic.commitText(s.text, 1)
        ic.endBatchEdit()
        recordSuggestion(EventType.ACCEPTED, s, s.text)
        if (s.source != "ai" && s.source != "cache") app.scope.launch { app.memory.markUsed(s.text) }
        suggestion = null
        typedInField = true
        showTools()
        requestSuggestion(SuggestTrigger.TYPING, 300)
    }

    // ------------------------------------------------------------ strip

    private fun chip(text: String, primary: Boolean = false, bold: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(if (primary) Color.WHITE else fg)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
        background = GradientDrawable().apply {
            cornerRadius = dp(16f).toFloat()
            setColor(if (primary) accent else if (dark) Color.rgb(48, 48, 60) else Color.rgb(226, 226, 236))
        }
        setOnClickListener { if (app.settings.value.haptics) performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); onClick() }
    }

    private fun lp(weight: Float = 0f) = LinearLayout.LayoutParams(if (weight > 0) 0 else -2, -2, weight).apply { marginEnd = dp(6f) }

    private fun showTools() {
        stripMode = StripMode.TOOLS
        strip.removeAllViews()
        strip.addView(chip("✦ Rewrite", primary = true, bold = true) { tool("rewrite_menu"); showModes() }, lp())
        strip.addView(chip("✎ Write") { tool("write"); startAsk() }, lp())
        strip.addView(chip("Fix") { tool("fix"); runRewrite(RewriteMode.GRAMMAR) }, lp())
        strip.addView(View(this), lp(1f))
        strip.addView(chip("🧠") { tool("memory"); open(MainActivity.TAB_MEMORY) }, lp())
        strip.addView(chip("📊") { tool("insights"); open(MainActivity.TAB_INSIGHTS) }, lp())
    }

    private fun renderSuggestion() {
        val s = suggestion ?: return showTools()
        stripMode = StripMode.SUGGESTION
        strip.removeAllViews()
        val icon = when (s.source) { "memory", "recall" -> "🧠"; "shortcut" -> "⚡"; else -> "✦" }
        val label = (s.note?.let { "$it  " } ?: "") + s.text.trim()
        strip.addView(chip("$icon  $label") { acceptSuggestion() }.apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }, lp(1f))
        strip.addView(chip("✕") {
            recordSuggestion(EventType.DISMISSED, s, "")
            suggestion = null
            showTools()
        }, lp())
    }

    private fun showModes() {
        stripMode = StripMode.MODES
        suggestJob?.cancel()
        strip.removeAllViews()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(chip("‹") { showTools() }, lp())
        for (m in RewriteMode.entries) if (m != RewriteMode.CUSTOM) row.addView(chip("${m.emoji} ${m.title}", primary = m.primary) { runRewrite(m) }, lp())
        strip.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun startAsk() {
        stripMode = StripMode.ASK
        suggestJob?.cancel()
        askBuffer = StringBuilder()
        renderAsk()
    }

    private fun renderAsk() {
        strip.removeAllViews()
        strip.addView(chip("✕") { showTools() }, lp())
        strip.addView(TextView(this).apply {
            text = if (askBuffer.isEmpty()) "Type what to write, e.g. \"polite reply saying I'll call tomorrow\"" else askBuffer.toString() + "▏"
            setTextColor(if (askBuffer.isEmpty()) muted else fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.START
        }, lp(1f))
        strip.addView(chip("Write", primary = true, bold = true) { runWrite() }, lp())
    }

    private fun busy(label: String) {
        stripMode = StripMode.BUSY
        strip.removeAllViews()
        strip.addView(TextView(this).apply { text = "✦  $label"; setTextColor(accent); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Typeface.DEFAULT_BOLD }, lp(1f))
        strip.addView(chip("Stop") { tool("stop"); aiJob?.cancel(); closeResult(); showTools() }, lp())
    }

    // ------------------------------------------------------------ AI rewrite & write

    private fun showResult(text: String) {
        result.layoutParams = result.layoutParams.apply { height = keyboard.height.coerceAtLeast(dp(220f)) }
        result.visibility = View.VISIBLE
        keyboard.visibility = View.INVISIBLE
        resultText.text = text
    }

    private fun closeResult() {
        resultDone("discarded")
        if (!::result.isInitialized) return
        result.visibility = View.GONE
        keyboard.visibility = View.VISIBLE
    }

    private fun resultButtons(vararg buttons: Pair<String, () -> Unit>) {
        resultActions.removeAllViews()
        buttons.forEachIndexed { i, (label, action) -> resultActions.addView(chip(label, primary = i == buttons.lastIndex, bold = i == buttons.lastIndex) { action() }, lp()) }
    }

    private fun runRewrite(mode: RewriteMode) {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        val whole = ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()
            ?: (ic.getTextBeforeCursor(5000, 0)?.toString().orEmpty() + ic.getTextAfterCursor(5000, 0)?.toString().orEmpty())
        val source = selected.ifBlank { whole }
        if (source.isBlank()) { showTools(); return }
        app.engine.writingConfigError()?.let { showResult("⚠︎ $it"); resultButtons("Keyboard" to { closeResult(); showTools() }); return }
        val c = ctx()
        resultTab = null // a retry replaces the old result: not a discard
        busy("${mode.title}…")
        showResult("…")
        resultButtons("Cancel" to { aiJob?.cancel(); closeResult(); showTools() })
        aiJob?.cancel()
        aiJob = scope.launch {
            var out = ""
            var failed: String? = null
            app.engine.rewrite(source, mode, c).catch { e -> failed = e.message }.collect { out = it; resultText.text = it }
            if (failed != null || out.isBlank()) {
                resultText.text = "⚠︎ ${failed ?: "The model returned nothing."}"
                resultButtons("Keyboard" to { closeResult(); showTools() }, "Retry" to { tool("retry"); runRewrite(mode) })
            } else {
                resultTab = "rewrite"
                resultButtons(
                    "Keyboard" to { closeResult(); showTools() },
                    "Retry" to { tool("retry"); runRewrite(mode) },
                    (if (selected.isNotBlank()) "Replace selection" else "Replace all") to {
                        resultDone("applied")
                        replaceText(out, selectionOnly = selected.isNotBlank())
                        app.metrics.record(MetricEvent(EventType.REWRITE_APPLIED, "ai", c.packageName, mode = mode.name, chars = out.length))
                        closeResult(); showTools()
                    },
                )
            }
            showModesOrTools()
        }
    }

    private fun showModesOrTools() { if (stripMode == StripMode.BUSY) showModes() }

    private fun runWrite() {
        val request = askBuffer.toString().trim()
        if (request.isEmpty()) return
        app.engine.writingConfigError()?.let { showResult("⚠︎ $it"); resultButtons("Keyboard" to { closeResult(); showTools() }); return }
        val c = ctx()
        resultTab = null
        busy("Writing…")
        showResult("…")
        resultButtons("Cancel" to { aiJob?.cancel(); closeResult(); showTools() })
        aiJob?.cancel()
        aiJob = scope.launch {
            var out = ""
            var failed: String? = null
            app.engine.write(request, c, app.settings.value.writeLength).catch { e -> failed = e.message }.collect { out = it; resultText.text = it }
            if (failed != null || out.isBlank()) {
                resultText.text = "⚠︎ ${failed ?: "The model returned nothing."}"
                resultButtons("Keyboard" to { closeResult(); showTools() })
            } else {
                resultTab = "write"
                resultButtons(
                    "Keyboard" to { closeResult(); showTools() },
                    "Shorter" to { tool("shorter"); askBuffer = StringBuilder("$request. Keep it short."); runWrite() },
                    "Insert" to {
                        resultDone("applied")
                        currentInputConnection?.commitText(out, 1)
                        app.metrics.record(MetricEvent(EventType.WRITE_APPLIED, "ai", c.packageName, chars = out.length))
                        closeResult(); showTools()
                    },
                )
            }
            if (stripMode == StripMode.BUSY) showTools()
        }
    }

    private fun replaceText(text: String, selectionOnly: Boolean) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (!selectionOnly) {
            val et = ic.getExtractedText(ExtractedTextRequest(), 0)
            if (et?.text != null) ic.setSelection(0, et.text.length)
            else ic.performContextMenuAction(android.R.id.selectAll)
        }
        ic.commitText(text, 1)
        ic.endBatchEdit()
        typedInField = true
    }

    private fun tool(name: String) = Usage.count("keyboard_tool", mapOf("tool" to name))

    /** The AI result on screen was used or thrown away (closed, keyboard hidden, field left). */
    private fun resultDone(outcome: String) {
        val tab = resultTab ?: return
        resultTab = null
        Usage.event("ai_result_closed", mapOf("surface" to "keyboard", "tab" to tab, "outcome" to outcome))
    }

    private fun endSession() {
        if (sessionSince == 0L) return
        Usage.typingSession("keyboard", sessionPkg, sessionSince, sessionTyped)
        sessionSince = 0L
    }

    private fun open(tab: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun learnField() {
        if (!typedInField || noLearning) return
        val c = runCatching { ctx() }.getOrNull() ?: return
        val text = (c.textBefore + c.textAfter).trim()
        if (text.isNotEmpty()) app.scope.launch { app.engine.learn(text, c, MemoryEntry.Kind.TYPED) }
        app.knowledge.observe(c.fieldLabel, text, c.isMultiline)
        typedInField = false
        Log.d("ime", "learned field in ${c.appName}")
    }

    companion object {
        /** True while this keyboard is serving a field: the overlay stays out of the way then. */
        @Volatile var isActiveInput = false
    }
}
