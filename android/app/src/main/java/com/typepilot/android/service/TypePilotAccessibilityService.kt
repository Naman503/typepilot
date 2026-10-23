package com.typepilot.android.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.typepilot.android.TypePilotApp
import com.typepilot.android.core.Log
import com.typepilot.android.engine.SuggestTrigger
import com.typepilot.android.engine.Suggestion
import com.typepilot.android.memory.MemoryEntry
import com.typepilot.android.metrics.EventType
import com.typepilot.android.metrics.LiveMetrics
import com.typepilot.android.metrics.MetricEvent
import com.typepilot.android.metrics.Stage
import com.typepilot.android.rewrite.RewriteBridge
import com.typepilot.android.text.SensitiveFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * TypePilot on top of every app, with whatever keyboard you use (Gboard, SwiftKey, Samsung…).
 *
 * Efficiency rules (it runs all day):
 *  - only text-field events are subscribed (see accessibility_service.xml); everything else returns immediately;
 *  - the field is read from the event's own node (one object, no tree walk); the screen is read once per focus, bounded;
 *  - one debounced coroutine per pause; any keystroke cancels it, which cancels the network call or local decode;
 *  - nothing runs while the screen is off, the keyboard is hidden, or TypePilot is paused.
 */
class TypePilotAccessibilityService : AccessibilityService(), OverlayController.Callbacks {
    private val app get() = TypePilotApp.instance
    /** Recreated on every (re)connect: Android may reconnect the same service object after an unbind. */
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var overlay: OverlayController
    private lateinit var assistant: FloatingAssistant
    private val appNames = HashMap<String, String>()

    private var current: FocusedField? = null
    private var typedInCurrent = false
    private var screenContext = ""
    /** The question the focused field asks (its label, or the text right above it). */
    private var fieldQuestion = ""
    private var screenContextNode: AccessibilityNodeInfo? = null
    private var suggestJob: Job? = null
    private var focusJob: Job? = null
    private var aiOptionJob: Job? = null
    private var rediscoverJob: Job? = null
    private var imeGoneJob: Job? = null
    private var dismissedText: String? = null
    private var ownChangeUntil = 0L
    private var imeBounds: Rect? = null
    private val bubbleHiddenFor = HashSet<String>()
    /** The fact last inserted into a field, to notice when you correct it. */
    private var acceptedFact: Triple<AccessibilityNodeInfo, Long, String>? = null

    /** What's on screen now, and what it was suggested for. */
    private data class Shown(var suggestion: Suggestion, val baseBefore: String, var remaining: String, var typedThrough: Int, val shownAt: Long, val pkg: String,
                             /** Still streaming in from the model (recorded as SHOWN once it's complete). */
                             var streaming: Boolean = false,
                             /** An AI answer offered next to memory answers. */
                             var ai: Suggestion? = null)
    private var shown: Shown? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_OFF) { closeAssistant(); leaveField("screen off"); app.metrics.flush() }
        }
    }

    override fun onServiceConnected() {
        instance = this
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        current = null; shown = null; imeBounds = null; typedInCurrent = false
        runCatching { unregisterReceiver(screenReceiver) }
        overlay = OverlayController(this, this)
        assistant = FloatingAssistant(this)
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        LiveMetrics.updateStatus { it.copy(serviceConnected = true) }
        SwitchRecovery.cancelBackup(this)
        app.perf.start()
        // Reconnected while a hidden app is still in front? Switch straight off again.
        rootInActiveWindow?.packageName?.toString()?.let { if (it != packageName) switchOffIfHidden(it) }
        scope.launch(Dispatchers.IO) { app.engine.prewarm() }
        Log.i("service", "connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (instance !== this) return
        instance = null
        runCatching { unregisterReceiver(screenReceiver) }
        if (::overlay.isInitialized) overlay.hideAll()
        if (::assistant.isInitialized) assistant.hide()
        RewriteBridge.sheetOpen = false
        scope.cancel()
        LiveMetrics.updateStatus { it.copy(serviceConnected = false) }
        app.metrics.flush()
        Log.i("service", "disconnected")
    }

    override fun onInterrupt() = hideAll()

    // ---------------------------------------------------------------- events

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        LiveMetrics.eventsReceived.incrementAndGet()
        val s = app.settings.value
        // The floating assistant closes when you switch to another app.
        if (::assistant.isInitialized && assistant.isShowing && e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val p = e.packageName?.toString()
            if (p != null && p != assistant.targetPackage && p != packageName && p != imePackage() && p !in systemPackages) closeAssistant()
        }
        // The TypePilot keyboard has its own strip: don't show the same suggestion twice.
        if (!s.isActive || RewriteBridge.sheetOpen || com.typepilot.android.ime.TypePilotIme.isActiveInput) {
            if (shown != null || overlay.isBubbleShowing) hideAll()
            LiveMetrics.eventsSkipped.incrementAndGet()
            return
        }
        // Window-list changes (keyboard shown/hidden/resized) carry no package name.
        if (e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) { onWindowsChanged(); return }
        val pkg = e.packageName?.toString() ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg != packageName && pkg != imePackage() && pkg !in systemPackages && switchOffIfHidden(pkg)) return
                // A new screen or app: the old field (and its popups) are gone. The keyboard, system UI
                // and our own overlay also send these; they don't mean you left the field.
                if (current != null && pkg != current?.pkg && pkg != packageName && pkg != imePackage() && pkg !in systemPackages) leaveField("app changed")
                // Back in an app (from Recents, the notification shade, a dialog…): the field that still has focus
                // sends no new focus event, so look for it once the window has settled.
                if (pkg != imePackage() && pkg !in systemPackages) {
                    rediscoverJob?.cancel()
                    rediscoverJob = scope.launch { delay(250); rediscoverFocusedField("window $pkg") }
                }
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> onFieldEvent(e, pkg)
        }
    }

    /**
     * After the page settles (keyboard opened, form scrolled) the text above the field may be different from what it was
     * at focus time. If the question changed, the suggestion was for the wrong question: ask again.
     */
    private fun recheckQuestion(f: FocusedField) {
        if (screenContextNode != f.node || f.text.isNotEmpty()) return
        val root = rootInActiveWindow
        scope.launch {
            val q = withContext(Dispatchers.Default) { withTimeoutOrNull(80) { QuestionFinder.find(root, f) }.orEmpty() }
            val now = current ?: return@launch
            if (!now.sameAs(f) || q.isBlank() || q == fieldQuestion) return@launch
            val next = if (QuestionFinder.generic(f.label)) q else if (q.length > f.label.length + 3 && q.contains('?')) q else f.label
            if (next == fieldQuestion) return@launch
            Log.d("field", "question changed after layout: '$fieldQuestion' → '$next'")
            fieldQuestion = next
            hideSuggestion()
            schedule(SuggestTrigger.FOCUS, 0)
        }
    }

    /** Find the field that has input focus right now (no event needed) and start tracking it. */
    private fun rediscoverFocusedField(reason: String) {
        if (!app.settings.value.isActive || RewriteBridge.sheetOpen || com.typepilot.android.ime.TypePilotIme.isActiveInput) return
        val node = runCatching { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull() ?: return
        if (!node.isEditable) return
        val pkg = node.packageName?.toString() ?: return
        val field = acceptField(node, pkg) ?: return
        if (field.sameAs(current) && (shown != null || overlay.isBubbleShowing)) return
        Log.d("focus", "rediscovered field '${field.label}' ($reason)")
        current = field
        typedInCurrent = false
        focused(field)
    }

    /** The shared checks for any field we might work in; null = not here (password, excluded app…). */
    private fun acceptField(node: AccessibilityNodeInfo, pkg: String): FocusedField? {
        val s = app.settings.value
        val ownOtherField = pkg == packageName && node.viewIdResourceName?.endsWith("/playground") != true
        if (!s.allows(pkg) || ownOtherField) return null
        val field = FieldReader.read(node, pkg, appName(pkg)) ?: return null
        if (field.isPassword || SensitiveFilter.isSensitiveLabel(field.label)) return null
        return field
    }

    private fun onFieldEvent(e: AccessibilityEvent, pkg: String) {
        val t0 = SystemClock.uptimeMillis()
        LiveMetrics.record(Stage.DELIVERY, (t0 - e.eventTime).coerceIn(0, 5_000))
        val s = app.settings.value
        val node = e.source ?: return
        if (!node.isEditable) {
            // Some UI toolkits (Compose, WebView) also report focus on a container while the text field keeps
            // input focus. Only leave if input focus really moved away from the field.
            if (e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) return // a tap on a button, not a field
            if (e.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED && current != null) {
                val input = runCatching { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
                if (input == null || !input.isEditable || input != current?.node) leaveField("focus moved")
            }
            LiveMetrics.eventsSkipped.incrementAndGet()
            return
        }
        // Inside TypePilot itself only the "Try it here" field gets suggestions (not settings, search or API keys).
        val ownOtherField = pkg == packageName && node.viewIdResourceName?.endsWith("/playground") != true
        if (!s.allows(pkg) || ownOtherField) { if (current != null) leaveField("app not enabled"); return }
        val field = FieldReader.read(node, pkg, appName(pkg)) ?: return
        LiveMetrics.record(Stage.RECOGNITION, SystemClock.uptimeMillis() - t0)
        LiveMetrics.eventsHandled.incrementAndGet()
        LiveMetrics.updateStatus { if (it.currentApp == field.appName) it else it.copy(currentApp = field.appName) }

        if (field.isPassword || SensitiveFilter.isSensitiveLabel(field.label)) {
            if (!field.sameAs(current)) learnFrom(current)
            current = field; typedInCurrent = false
            hideAll()
            return
        }
        val prev = current
        if (!field.sameAs(prev)) {
            learnFrom(prev)
            current = field
            typedInCurrent = false
            focused(field)
            return
        }
        current = field
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> textChanged(prev!!, field)
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                // Caret moved without typing (a tap in the text): the suggestion no longer fits there.
                if (field.text == prev!!.text && field.selStart != prev.selStart && SystemClock.uptimeMillis() > ownChangeUntil) {
                    shown?.let { record(EventType.IGNORED, it) }
                    hideSuggestion()
                    if (field.caretAtLineEnd && field.textBefore.isNotBlank()) schedule(SuggestTrigger.TYPING)
                }
            }
            // Tapping (or re-focusing) the field you're already in: bring the ✦ and a suggestion back if they're gone.
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_CLICKED ->
                if (shown == null && (!overlay.isBubbleShowing || field.text.isEmpty())) focused(field)
        }
    }

    private fun textChanged(prev: FocusedField, field: FocusedField) {
        val now = SystemClock.uptimeMillis()
        val own = now < ownChangeUntil
        val delta = field.text.length - prev.text.length
        if (!own) {
            if (delta > 0) LiveMetrics.typed(delta, now)
            typedInCurrent = true
            // A chat field that suddenly empties was just sent: remember what was sent.
            if (field.text.isEmpty() && prev.text.trim().length >= 3) learn(prev, prev.text)
        }
        val sh = shown
        if (sh != null && !own) {
            val before = field.textBefore
            // Typed exactly what was suggested? Keep the rest showing (no new request).
            if (before.startsWith(sh.baseBefore) && before.length > sh.baseBefore.length) {
                val typed = before.substring(sh.baseBefore.length)
                if (sh.suggestion.text.startsWith(typed) && sh.suggestion.replaceLength == 0) {
                    sh.remaining = sh.suggestion.text.substring(typed.length)
                    sh.typedThrough = typed.length
                    if (sh.remaining.isBlank()) { record(EventType.TYPED_THROUGH, sh, typed); hideSuggestion() }
                    else overlay.updateSuggestionText(sh.remaining)
                    return
                }
            }
            if (sh.typedThrough > 0) record(EventType.TYPED_THROUGH, sh, sh.suggestion.text.take(sh.typedThrough)) else record(EventType.IGNORED, sh)
            hideSuggestion()
        }
        if (own) schedule(SuggestTrigger.TYPING, 350) else schedule(SuggestTrigger.TYPING)
    }

    private fun onWindowsChanged() {
        val ime = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if (ime == null) {
            // Keyboard closed (it was open before): you're done typing here. The window list can drop the keyboard
            // for a moment during transitions, so it must stay gone for a bit before it counts.
            if ((imeBounds != null || shown != null) && imeGoneJob?.isActive != true) imeGoneJob = scope.launch {
                delay(350)
                if (currentImeBounds() == null) { imeBounds = null; if (current != null || overlay.isBubbleShowing) leaveField("keyboard closed") }
            }
            return
        }
        imeGoneJob?.cancel()
        val r = Rect().also(ime::getBoundsInScreen)
        // The keyboard came up for a field we aren't tracking (returned to an app, re-tapped a focused field).
        if (current == null) scope.launch { delay(120); if (current == null) rediscoverFocusedField("keyboard opened") }
        else if (imeBounds == null && shown == null && app.settings.value.overlaySuggestions) {
            // Suggestions wait for the keyboard (a field can be focused with it closed): now's the time.
            app.scope.launch { app.engine.prewarm(inField = true) }
            schedule(SuggestTrigger.FOCUS, 0)
        }
        if (r != imeBounds) {
            imeBounds = r
            // The keyboard opened or changed height (emoji panel, toolbar…): move out of its way, now and again
            // once the app has finished re-laying out (fields move when the app resizes for the keyboard).
            replaceOverlays()
            scope.launch { delay(300); replaceOverlays() }
        }
    }

    /** Re-read the field's position and put the chip / ✦ back next to it; re-check its question too. */
    private fun replaceOverlays() {
        val f = current ?: return
        runCatching { if (f.node.refresh()) f.node.getBoundsInScreen(f.bounds) }
        recheckQuestion(f)
        val sh = shown
        if (sh != null) {
            val caret = if (f.isMultiline && f.bounds.height() > 300) FieldReader.caretRect(f) else null
            if (!overlay.showSuggestion(sh.suggestion.copy(text = sh.remaining), f, imeBounds, caret, haptics = false)) hideSuggestion()
        } else maybeShowBubble(f)
    }

    private fun currentImeBounds(): Rect? =
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.let { w -> Rect().also(w::getBoundsInScreen) }

    /** Suggestions are only made while the keyboard is on screen: no keyboard, no typing, no tokens spent. */
    private fun keyboardUp() = runCatching { currentImeBounds() != null }.getOrDefault(false)

    // ---------------------------------------------------------------- focus

    private fun focused(field: FocusedField) {
        Log.d("focus", "field '${field.label}' text='${field.text.take(40)}' hint=${field.node.isShowingHintText} in ${field.pkg}")
        hideSuggestion()
        dismissedText = null
        focusJob?.cancel()
        suggestJob?.cancel()
        maybeShowBubble(field)
        val s = app.settings.value
        if (!s.overlaySuggestions) return
        if (keyboardUp()) app.scope.launch { app.engine.prewarm(inField = true) }
        schedule(SuggestTrigger.FOCUS, 0) // instant memory recall (once the keyboard is up)
        fieldQuestion = field.label
        focusJob = scope.launch {
            val root = rootInActiveWindow
            val (ctx, question) = withContext(Dispatchers.Default) {
                val q = withTimeoutOrNull(80) { QuestionFinder.find(root, field) }.orEmpty()
                (if (s.useScreenContext) withTimeoutOrNull(150) { ScreenContextReader.read(root, field) }.orEmpty() else "") to q
            }
            val now = current ?: return@launch
            if (!now.sameAs(field)) return@launch
            screenContext = ctx
            fieldQuestion = if (QuestionFinder.generic(field.label) && question.isNotBlank()) question
                else if (question.isNotBlank() && question.length > field.label.length + 3 && question.contains('?')) question else field.label
            Log.d("field", "label='${field.label}' nearby='$question' → question='$fieldQuestion'")
            screenContextNode = field.node
            if (s.suggestOnFocus && shown == null && now.text == field.text) schedule(SuggestTrigger.FOCUS, 0)
        }
    }

    private fun maybeShowBubble(field: FocusedField) {
        val s = app.settings.value
        if (!s.aiBubble || field.pkg in bubbleHiddenFor || field.isPassword) { overlay.hideBubble(); return }
        if (imeBounds == null) imeBounds = currentImeBounds()
        // Show it right away; it moves out of the keyboard's way once the keyboard has opened.
        overlay.showBubble(field, imeBounds)
    }

    private fun leaveField(reason: String) {
        suggestJob?.cancel()
        aiOptionJob?.cancel()
        focusJob?.cancel()
        shown?.let { record(EventType.IGNORED, it) }
        learnFrom(current)
        current = null
        typedInCurrent = false
        screenContext = ""
        screenContextNode = null
        hideAll()
        Log.d("service", "left field: $reason")
    }

    // ---------------------------------------------------------------- suggestions

    private fun schedule(trigger: SuggestTrigger, delayMs: Int? = null) {
        val s = app.settings.value
        suggestJob?.cancel()
        if (!s.overlaySuggestions) return
        val pauseStart = SystemClock.elapsedRealtime()
        suggestJob = scope.launch {
            delay((delayMs ?: s.debounceMs).toLong())
            val f = current ?: return@launch
            if (!f.caretAtLineEnd || f.isPassword) return@launch
            if (!keyboardUp()) return@launch // asked again when the keyboard opens
            if (dismissedText != null && dismissedText == f.textBefore) return@launch
            val screen = if (screenContextNode == f.node) screenContext else ""
            val question = if (screenContextNode == f.node) fieldQuestion else f.label
            val requestAt = SystemClock.elapsedRealtime()
            fun fresh(): FocusedField? = current?.takeIf { it.sameAs(f) && it.text == f.text && it.selStart == f.selStart }
            overlay.setThinking(true)
            val ctx = f.context(screen, question)
            val sug = try {
                app.engine.suggest(ctx, trigger, onPartial = { partial ->
                    // Words stream in on a background thread: show them as they arrive.
                    scope.launch { fresh()?.let { streamIn(partial, it) } }
                })
            } finally { overlay.setThinking(false) }
            if (sug == null) {
                if (shown?.streaming == true) hideSuggestion()
                return@launch
            }
            val now = fresh() ?: return@launch // stale: typed more, moved the caret or left meanwhile
            present(sug, now, pauseStart, requestAt)
            if (sug.source == "fact") offerAiAlongside(ctx, now)
        }
    }

    /**
     * For a real question ("Why do you want to join us?") memory answers come with an AI answer as an extra row,
     * the way FormPilot fills web forms. Not for plain fields like email or phone.
     */
    private fun offerAiAlongside(ctx: com.typepilot.android.engine.FieldContext, field: FocusedField) {
        val s = app.settings.value
        val q = ctx.question.ifBlank { ctx.fieldLabel }
        val isQuestion = com.typepilot.android.knowledge.FieldMatcher.concept(q) == null && (q.contains('?') || q.trim().split(Regex("""\s+""")).size >= 4)
        if (!s.aiAlongsideMemory || !isQuestion || ctx.textBefore.isNotBlank() || !keyboardUp()) return
        if (app.engine.modelFor(s.completionProvider)?.configError() != null) return
        aiOptionJob?.cancel()
        overlay.setAiOption(null, writing = true)
        aiOptionJob = scope.launch {
            fun stillHere() = shown != null && current?.sameAs(field) == true && current?.text == field.text
            val ai = app.engine.suggest(ctx.copy(textBefore = ""), SuggestTrigger.FOCUS, aiOnly = true, onPartial = { p ->
                scope.launch { if (stillHere()) overlay.setAiOption(p.text, writing = true) }
            })
            if (!stillHere()) return@launch
            if (ai == null) { overlay.setAiOption("No AI answer for this one.", writing = true); return@launch }
            shown?.ai = ai
            overlay.setAiOption(ai.text, writing = false)
        }
    }

    /** A row of the card was tapped: the best answer (0), another memory answer (1..), or the AI answer (-1). */
    override fun onAcceptOption(index: Int) {
        val sh = shown ?: return
        if (index == 0) return onAccept()
        val pick = if (index < 0) sh.ai else sh.suggestion.alternatives.getOrNull(index - 1)
        pick ?: return
        val f = current ?: return
        aiOptionJob?.cancel()
        hideSuggestion()
        ownChangeUntil = SystemClock.uptimeMillis() + 600
        if (TextApplier.insert(this, f, pick.text, pick.replaceLength) < 0) return
        app.metrics.record(MetricEvent(EventType.ACCEPTED, pick.source, sh.pkg, pick.provider, chars = pick.text.length, words = pick.words))
        typedInCurrent = true
        if (pick.source == "fact" && pick.refId > 0) {
            app.scope.launch { app.facts.markUsed(pick.refId, f.pkg) }
            acceptedFact = Triple(f.node, pick.refId, (sh.baseBefore + pick.text).trim())
        }
    }

    /** Partial suggestion while the model is still writing: first words on screen within ~the first token time. */
    private fun streamIn(partial: Suggestion, field: FocusedField) {
        val sh = shown
        if (sh != null && sh.streaming) {
            sh.suggestion = partial; sh.remaining = partial.text
            overlay.updateSuggestionText(partial.text)
            return
        }
        if (sh != null) return
        if (imeBounds == null) imeBounds = currentImeBounds()
        if (!overlay.showSuggestion(partial, field, imeBounds, null, haptics = false)) return
        shown = Shown(partial, field.textBefore, partial.text, 0, SystemClock.elapsedRealtime(), field.pkg, streaming = true)
    }

    private fun present(s: Suggestion, field: FocusedField, pauseStart: Long, requestAt: Long) {
        val settings = app.settings.value
        val streamed = shown?.takeIf { it.streaming }
        val now = SystemClock.elapsedRealtime()
        if (streamed != null) {
            // Already on screen from streaming: just finalize the text.
            overlay.updateSuggestionText(s.text, allowWord = s.replaceLength == 0)
            shown = Shown(s, streamed.baseBefore, s.text, 0, streamed.shownAt, field.pkg)
        } else {
            val caret = if (field.isMultiline && field.bounds.height() > 300) FieldReader.caretRect(field) else null
            if (imeBounds == null) imeBounds = currentImeBounds()
            if (!overlay.showSuggestion(s, field, imeBounds, caret, haptics = false)) return
            shown = Shown(s, field.textBefore, s.text, 0, now, field.pkg)
        }
        LiveMetrics.record(Stage.END_TO_END, now - pauseStart)
        LiveMetrics.updateStatus { it.copy(lastSuggestion = s.text.take(80), lastLatencyMs = now - requestAt, lastProvider = s.provider) }
        app.metrics.record(MetricEvent(EventType.SHOWN, s.source, field.pkg, s.provider, latencyMs = now - requestAt, ttftMs = s.ttftMs,
            chars = s.text.length, words = s.words))
        if (settings.verboseLogs) Log.d("suggest", "${s.source} in ${now - requestAt} ms (engine ${s.engineMs} ms, since pause ${now - pauseStart} ms): ${s.text.take(40)}")
    }

    private fun record(type: EventType, sh: Shown, text: String = sh.remaining) {
        val words = text.trim().split(Regex("""\s+""")).count { it.isNotEmpty() }
        app.metrics.record(MetricEvent(type, sh.suggestion.source, sh.pkg, sh.suggestion.provider,
            latencyMs = SystemClock.elapsedRealtime() - sh.shownAt, chars = if (type == EventType.IGNORED) 0 else text.length,
            words = if (type == EventType.IGNORED) 0 else words))
    }

    private fun hideSuggestion() {
        aiOptionJob?.cancel()
        shown = null
        overlay.hideSuggestion()
        // Re-place the ✦ from the field's current position (the app may have scrolled meanwhile).
        val f = current
        if (f != null) {
            runCatching { f.node.refresh(); f.node.getBoundsInScreen(f.bounds) }
            maybeShowBubble(f)
        } else overlay.restoreBubbleIfHidden()
    }

    private fun hideAll() {
        shown = null
        if (::overlay.isInitialized) overlay.hideAll()
    }

    // ---------------------------------------------------------------- overlay callbacks

    override fun onAccept() {
        val sh = shown ?: return
        val f = current ?: return
        if (sh.streaming) suggestJob?.cancel() // take what's visible now
        hideSuggestion()
        ownChangeUntil = SystemClock.uptimeMillis() + 600
        val caret = TextApplier.insert(this, f, sh.remaining, sh.suggestion.replaceLength)
        if (caret < 0) { Toast.makeText(this, "This app didn't accept the text", Toast.LENGTH_SHORT).show(); return }
        record(EventType.ACCEPTED, sh, sh.remaining)
        typedInCurrent = true
        if (sh.suggestion.source == "fact" && sh.suggestion.refId > 0) {
            app.scope.launch { app.facts.markUsed(sh.suggestion.refId, f.pkg) }
            acceptedFact = Triple(f.node, sh.suggestion.refId, (sh.baseBefore + sh.remaining).trim())
        }
        if (sh.suggestion.source != "ai" && sh.suggestion.source != "cache") app.scope.launch { app.memory.markUsed(sh.suggestion.text) }
    }

    override fun onAcceptWord() {
        val sh = shown ?: return
        val f = current ?: return
        val m = Regex("""^\s*\S+""").find(sh.remaining) ?: return onAccept()
        val word = m.value
        val rest = sh.remaining.substring(word.length)
        ownChangeUntil = SystemClock.uptimeMillis() + 600
        if (TextApplier.insert(this, f, word) < 0) return
        app.metrics.record(MetricEvent(EventType.WORD, sh.suggestion.source, sh.pkg, sh.suggestion.provider, chars = word.length, words = 1))
        typedInCurrent = true
        if (rest.isBlank()) { hideSuggestion(); return }
        suggestJob?.cancel()
        // Keep the rest on screen for the next tap.
        shown = sh.copy(baseBefore = sh.baseBefore + word, remaining = rest, typedThrough = 0)
        overlay.updateSuggestionText(rest)
    }

    override fun onDismiss() {
        val sh = shown ?: return
        record(EventType.DISMISSED, sh)
        if (sh.suggestion.source == "fact" && sh.suggestion.refId > 0) app.scope.launch { app.facts.markRejected(sh.suggestion.refId) }
        dismissedText = current?.textBefore
        suggestJob?.cancel()
        hideSuggestion()
    }

    override fun onBubbleMoved(xFrac: Float, yFrac: Float) {
        app.settings.update { it.copy(bubbleX = xFrac, bubbleY = yFrac) }
        Toast.makeText(this, "✦ stays here now. Reset it in TypePilot → Settings → Suggestions.", Toast.LENGTH_SHORT).show()
    }

    override fun bubblePosition(): Pair<Float, Float>? = app.settings.value.let { if (it.bubbleX >= 0 && it.bubbleY >= 0) it.bubbleX to it.bubbleY else null }

    override fun bubbleOnEdge(): Boolean = app.settings.value.bubbleOnEdge

    override fun onHideBubbleForApp() {
        current?.pkg?.let { bubbleHiddenFor += it }
        overlay.hideBubble()
        Toast.makeText(this, "✦ hidden in this app until restart. Turn it off for good in TypePilot settings.", Toast.LENGTH_SHORT).show()
    }

    /** ✦ (or long-press on a suggestion): rewrite the selection or the whole field, or write something new. */
    override fun onOpenAssistant() {
        val f = current ?: return
        val node = f.node
        val hasSelection = f.selectedText.isNotBlank()
        val source = if (hasSelection) f.selectedText else f.text
        val start = if (hasSelection) f.selStart else 0
        val end = if (hasSelection) f.selEnd else f.text.length
        val screen = if (screenContextNode == f.node) screenContext else ""
        suggestJob?.cancel()
        hideAll()
        if (imeBounds == null) imeBounds = currentImeBounds()
        // A small floating window over the app: the keyboard and the app stay visible.
        RewriteBridge.sheetOpen = true
        assistant.show(RewriteBridge.Request(
            text = source,
            ctx = f.context(screen),
            tab = if (source.isBlank()) RewriteBridge.Tab.WRITE else RewriteBridge.Tab.REWRITE,
            actionLabel = if (source.isBlank()) "Insert" else "Replace",
            deliver = { result -> deliverInto(node, f.pkg, start, end, result) },
            // Drafts: at the cursor, or over the selection; add a space if needed so words don't run together.
            insert = { result ->
                val before = f.text.substring(0, f.selStart.coerceIn(0, f.text.length))
                val sep = if (before.isNotEmpty() && !before.last().isWhitespace() && f.selEnd == f.selStart) " " else ""
                deliverInto(node, f.pkg, f.selStart, f.selEnd, sep + result)
            },
        ), f.bounds, imeBounds) {
            RewriteBridge.sheetOpen = false
            current?.let { maybeShowBubble(it) }
        }
    }

    private fun closeAssistant() {
        if (::assistant.isInitialized && assistant.isShowing) assistant.hide()
        RewriteBridge.sheetOpen = false
    }

    /** The sheet closed with a result: put it back into the field once focus has returned to that app. */
    private fun deliverInto(node: AccessibilityNodeInfo, pkg: String, start: Int, end: Int, text: String) {
        scope.launch {
            delay(60)
            val target = node.takeIf { it.refresh() && it.isEditable }
                ?: rootInActiveWindow?.takeIf { it.packageName == pkg }?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (target == null) {
                TextApplier.copy(this@TypePilotAccessibilityService, text)
                Toast.makeText(this@TypePilotAccessibilityService, "Copied: paste it into the field", Toast.LENGTH_LONG).show()
                return@launch
            }
            val full = if (target.isShowingHintText) "" else target.text?.toString().orEmpty()
            // If the field changed meanwhile, replace the whole thing only when we were replacing everything.
            val (a, b) = if (end <= full.length) start to end else 0 to full.length
            ownChangeUntil = SystemClock.uptimeMillis() + 800
            if (TextApplier.replaceRange(this@TypePilotAccessibilityService, target, full, a, b, text) < 0) {
                TextApplier.copy(this@TypePilotAccessibilityService, text)
                Toast.makeText(this@TypePilotAccessibilityService, "Copied: paste it into the field", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------------------------------------------------------------- learning

    private fun learnFrom(field: FocusedField?) {
        if (field == null || !typedInCurrent || field.isPassword) return
        // Use the last text seen while you typed: once a field loses focus, some apps (Compose) report
        // "value, label" for it, which must not be learned.
        learn(field, field.text)
    }

    private fun learn(field: FocusedField, text: String) {
        if (text.isBlank() || !app.settings.value.learnFromTyping) return
        val q = if (screenContextNode == field.node && fieldQuestion.isNotBlank()) fieldQuestion else field.label
        // You changed what TypePilot filled in: trust that answer less (the new one is learned below).
        acceptedFact?.let { (node, id, value) ->
            if (node == field.node) {
                if (app.settings.value.learnCorrections && text.trim().isNotEmpty() && !text.trim().equals(value, true)) {
                    app.scope.launch { app.facts.markRejected(id) }
                    Log.d("memory", "correction: fact $id replaced by what you typed")
                }
                acceptedFact = null
            }
        }
        app.knowledge.observe(q, text, field.isMultiline, field.pkg)
        val ctx = field.context()
        app.scope.launch { app.engine.learn(text, ctx, MemoryEntry.Kind.TYPED) }
    }

    /** A payment/banking app (or an app outside your list) came to the front: switch the whole service off until you leave. */
    private fun switchOffIfHidden(pkg: String): Boolean {
        val s = app.settings.value
        if (!s.hiddenIn(pkg) || !A11ySwitch.ready(this)) return false
        leaveField("switched off for $pkg")
        Log.i("switch", "off while $pkg is open")
        AppWatcherService.start(this, appName(pkg))
        SwitchRecovery.scheduleBackup(this)
        A11ySwitch.turnOff(this)
        return true
    }

    private fun imePackage(): String =
        android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty().substringBefore('/')

    private val systemPackages = setOf("com.android.systemui", "android")

    private fun appName(pkg: String): String = appNames.getOrPut(pkg) {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    companion object {
        @Volatile var instance: TypePilotAccessibilityService? = null; private set
        val isRunning get() = instance != null
    }
}
