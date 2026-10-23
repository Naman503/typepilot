package com.typepilot.android.service

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.typepilot.android.R
import com.typepilot.android.core.Log
import com.typepilot.android.engine.Suggestion
import com.typepilot.android.metrics.LiveMetrics
import com.typepilot.android.metrics.Stage
import kotlin.math.abs

/**
 * Draws TypePilot on top of other apps using accessibility overlay windows (no "draw over apps" permission).
 *
 *  - The suggestion card: the best answer, plus other likely answers (memory) and an AI answer as rows.
 *    Each row shows its source icon and, for memory, a separate percentage badge. Tap a row to insert it.
 *  - The ✦: by default a translucent tab docked to the screen edge (in your phone's theme colour) that slides in
 *    when a field is focused and fades while idle; or a small circle next to the field. Drag to move, tap to open
 *    the assistant, hold to hide it in this app.
 *
 * Everything is non-focusable, so the keyboard and the field keep focus. Plain Views: this runs all day.
 */
class OverlayController(private val context: Context, private val callbacks: Callbacks) {
    interface Callbacks {
        fun onAccept()
        fun onAcceptWord()
        fun onDismiss()
        fun onOpenAssistant()
        fun onHideBubbleForApp()
        /** Row [index] of the card was tapped: 0 = best answer, 1.. = other answers, -1 = the AI answer. */
        fun onAcceptOption(index: Int) { if (index == 0) onAccept() }
        /** The ✦ was dragged to (x, y) as fractions of the screen. */
        fun onBubbleMoved(xFrac: Float, yFrac: Float) {}
        /** Your saved ✦ position (fractions), or null = automatic. */
        fun bubblePosition(): Pair<Float, Float>? = null
        /** true = edge tab, false = circle next to the field. */
        fun bubbleOnEdge(): Boolean = true
    }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()
    private val main = Handler(Looper.getMainLooper())

    private var card: LinearLayout? = null
    private var cardParams: WindowManager.LayoutParams? = null
    private var primaryText: TextView? = null
    private var wordButton: View? = null
    private var aiRow: LinearLayout? = null
    private var aiText: TextView? = null
    private var bubble: FrameLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var pulse: ObjectAnimator? = null
    /** Tracked ourselves: addView attaches asynchronously, so isAttachedToWindow lags behind. */
    private var cardAdded = false
    private var bubbleAdded = false
    private var current: Suggestion? = null

    val isChipShowing get() = cardAdded && card?.visibility == View.VISIBLE
    val isBubbleShowing get() = bubbleAdded && bubble?.visibility == View.VISIBLE

    private val dark get() = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val surface get() = if (dark) Color.rgb(30, 30, 38) else Color.WHITE
    private val onSurface get() = if (dark) Color.rgb(236, 236, 244) else Color.rgb(28, 28, 36)
    private val muted get() = if (dark) Color.rgb(150, 152, 168) else Color.rgb(120, 122, 138)
    private val divider get() = if (dark) Color.argb(40, 255, 255, 255) else Color.argb(26, 0, 0, 0)
    private val violet = Color.rgb(124, 92, 255)
    private val pink = Color.rgb(236, 72, 153)

    /** Your phone's theme colour (Material You) on Android 12+, TypePilot violet before. */
    private val accent: Int get() = if (Build.VERSION.SDK_INT >= 31)
        context.getColor(if (dark) android.R.color.system_accent1_300 else android.R.color.system_accent1_500) else violet

    private fun gradientCircle() = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(violet, pink)).apply { shape = GradientDrawable.OVAL }

    private fun baseParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        windowAnimations = android.R.style.Animation_Toast
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private fun icon(res: Int, tint: Int, sizeDp: Float, padDp: Float, desc: String) = ImageView(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        val pad = dp(padDp)
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp + padDp * 2), dp(sizeDp + padDp * 2))
        contentDescription = desc
    }

    private fun haptic(v: View) {
        v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private data class ScreenInfo(val width: Int, val height: Int, val top: Int)

    private fun screen(): ScreenInfo {
        val m = wm.currentWindowMetrics
        val insets = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
        return ScreenInfo(m.bounds.width(), m.bounds.height(), insets.top)
    }

    // ------------------------------------------------------------------ suggestion card

    private fun iconFor(source: String) = when (source) {
        "fact" -> R.drawable.ic_fact; "memory", "recall" -> R.drawable.ic_history; "shortcut" -> R.drawable.ic_bolt; else -> R.drawable.ic_sparkle
    }

    /** Small round source badge (gradient for AI, soft tint for memory). */
    private fun badge(source: String, sizeDp: Float = 22f) = ImageView(context).apply {
        setImageResource(iconFor(source))
        val ai = source == "ai" || source == "cache"
        background = if (ai) gradientCircle() else GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(if (dark) 70 else 36, 124, 92, 255)) }
        imageTintList = ColorStateList.valueOf(if (ai) Color.WHITE else violet)
        val p = dp(sizeDp * 0.2f)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { marginEnd = dp(8f) }
    }

    /** The likelihood, as its own coloured pill (never part of the answer text). */
    private fun percentPill(text: String): TextView {
        val pct = text.removeSuffix("%").toIntOrNull()
        val color = when { pct == null -> violet; pct >= 85 -> Color.rgb(22, 163, 74); pct >= 65 -> Color.rgb(8, 145, 178); else -> Color.rgb(217, 119, 6) }
        return TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color)
            includeFontPadding = false
            setPadding(dp(6f), dp(3f), dp(6f), dp(3f))
            background = GradientDrawable().apply { cornerRadius = dp(8f).toFloat(); setColor(Color.argb(if (dark) 60 else 30, Color.red(color), Color.green(color), Color.blue(color))) }
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6f) }
        }
    }

    private fun answerText(text: String, maxW: Int, lines: Int, size: Float = 14f) = TextView(context).apply {
        this.text = text.trim()
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(onSurface)
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        setLineSpacing(0f, 1.1f)
        maxWidth = maxW
    }

    private fun row(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8f), dp(6f), dp(4f), dp(6f))
        isClickable = true
        background = GradientDrawable().apply { cornerRadius = dp(14f).toFloat(); setColor(Color.TRANSPARENT) }
    }

    /** A hairline that takes the card's width without forcing the card to full screen width. */
    private fun line() = object : View(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec) else 0
            setMeasuredDimension(w, 1)
        }
    }.apply { setBackgroundColor(divider); layoutParams = LinearLayout.LayoutParams(-1, 1).apply { setMargins(dp(10f), 0, dp(10f), 0) } }

    /** (Re)build the card's rows for [s] and its alternatives. */
    private fun buildCard(s: Suggestion, maxW: Int): LinearLayout {
        val box = card ?: LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18f).toFloat()
                setColor(surface)
                setStroke(dp(1f), Color.argb(if (dark) 110 else 64, 124, 92, 255))
            }
            elevation = dp(6f).toFloat()
            setPadding(dp(2f), dp(2f), dp(2f), dp(2f))
        }.also { card = it }
        box.removeAllViews()
        aiRow = null; aiText = null
        val many = s.alternatives.isNotEmpty()
        // Room for the text: the card minus the badge, % pill, and buttons.
        val textW = maxW - dp(8f + 22f + 8f) - (if (s.note != null) dp(46f) else 0) - dp(if (many) 34f else 64f)

        val first = row().apply {
            setOnClickListener { haptic(this); callbacks.onAcceptOption(0) }
            setOnLongClickListener { haptic(this); callbacks.onOpenAssistant(); true }
            contentDescription = "Suggestion: ${s.text}. Tap to insert."
        }
        first.addView(badge(s.source))
        primaryText = answerText(s.text, textW, if (many) 1 else 2).also { first.addView(it, LinearLayout.LayoutParams(-2, -2)) }
        s.note?.let { first.addView(percentPill(it)) }
        wordButton = icon(R.drawable.ic_word, violet, 16f, 7f, "Insert next word").apply {
            setOnClickListener { haptic(this); callbacks.onAcceptWord() }
            visibility = if (!many && s.replaceLength == 0 && s.source != "fact" && s.words > 1) View.VISIBLE else View.GONE
        }.also { first.addView(it) }
        first.addView(icon(R.drawable.ic_close, muted, 14f, 7f, "Close").apply { setOnClickListener { callbacks.onDismiss() } })
        box.addView(first)

        s.alternatives.forEachIndexed { i, alt ->
            box.addView(line())
            val r = row().apply {
                setOnClickListener { haptic(this); callbacks.onAcceptOption(i + 1) }
                contentDescription = "Other answer: ${alt.text}"
            }
            r.addView(badge(alt.source, 20f))
            r.addView(answerText(alt.text, textW, 1, 13.5f), LinearLayout.LayoutParams(-2, -2))
            alt.note?.let { r.addView(percentPill(it)) }
            box.addView(r)
        }
        return box
    }

    /**
     * Show a suggestion (and its alternatives) without covering the field you're typing in or the keyboard:
     * below the field if there's room above the keyboard, else above the field (chat apps), else just above
     * the keyboard. Returns false if there's no clear spot.
     */
    fun showSuggestion(s: Suggestion, field: FocusedField, ime: Rect?, caret: Rect?, haptics: Boolean): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        // Same answers after a scroll or relayout: just move the card, keeping its AI row (no flicker).
        val same = cardAdded && card != null && current == s
        current = s
        val sc = screen()
        val margin = dp(10f)
        val maxW = sc.width - margin * 2
        val view = if (same) card!! else buildCard(s, maxW)
        lastField = field; lastIme = ime; lastCaret = caret
        if (!place(view, field, ime, caret)) return false
        lastBubbleField?.let { showBubble(it, lastIme) } // stay visible, clear of the card
        if (haptics) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        LiveMetrics.record(Stage.RENDER, SystemClock.elapsedRealtime() - t0)
        return true
    }

    private var lastField: FocusedField? = null
    private var lastCaret: Rect? = null

    private fun place(view: LinearLayout, field: FocusedField, ime: Rect?, caret: Rect?): Boolean {
        val sc = screen()
        val margin = dp(10f)
        val gap = dp(8f)
        val maxW = sc.width - margin * 2
        view.forceLayout()
        view.measure(View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(sc.height / 2, View.MeasureSpec.AT_MOST))
        val w = view.measuredWidth.coerceAtMost(maxW)
        val h = view.measuredHeight
        val imeTop = ime?.top?.takeIf { it > 0 } ?: sc.height
        val f = Rect(field.bounds).apply { bottom = minOf(bottom, imeTop) }
        val minY = sc.top + gap
        val maxY = imeTop - gap - h
        val candidates = ArrayList<Pair<String, Int>>()
        if (f.height() > h * 3 && caret != null) {
            candidates += "below line" to caret.bottom + gap
            candidates += "above line" to caret.top - gap - h
        }
        candidates += "below field" to f.bottom + gap
        candidates += "above field" to f.top - gap - h
        candidates += "above keyboard" to maxY
        val chosen = candidates.firstOrNull { (name, y) ->
            if (y < minY || y > maxY) return@firstOrNull false
            name.endsWith("line") || !Rect.intersects(Rect(0, y, sc.width, y + h), f)
        } ?: run { hideSuggestion(); return false }
        val x = f.left.coerceIn(margin, sc.width - w - margin)
        val p = cardParams ?: baseParams().also { cardParams = it }
        p.x = x; p.y = chosen.second; p.width = w
        try {
            if (cardAdded) { view.visibility = View.VISIBLE; wm.updateViewLayout(view, p) } else { wm.addView(view, p); cardAdded = true }
        } catch (e: Exception) { Log.e("overlay", "couldn't show suggestion", e); return false }
        cardRect = Rect(x, chosen.second, x + w, chosen.second + h)
        Log.d("overlay", "card ${chosen.first} ${w}x$h")
        return true
    }

    /** Streaming or typed-through: change the best answer's text in place, then re-fit. */
    fun updateSuggestionText(text: String, allowWord: Boolean = true) {
        val t = primaryText ?: return
        t.text = text.trim()
        val s = current
        wordButton?.visibility = if (allowWord && s?.alternatives.isNullOrEmpty() && s?.source != "fact" && text.trim().contains(' ')) View.VISIBLE else View.GONE
        refit()
    }

    /** An AI answer next to the memory answers: a "writing…" row first, then the text as it streams. */
    fun setAiOption(text: String?, writing: Boolean) {
        val box = card ?: return
        if (!cardAdded) return
        if (aiRow == null) {
            box.addView(line())
            val r = row().apply {
                setOnClickListener { if (!aiText?.text.isNullOrBlank() && aiText?.tag != "writing") { haptic(this); callbacks.onAcceptOption(-1) } }
                contentDescription = "AI answer"
            }
            r.addView(badge("ai", 20f))
            val maxW = screen().width - dp(20f) - dp(8f + 20f + 8f + 46f + 34f)
            aiText = answerText("", maxW, 2, 13.5f).also { r.addView(it, LinearLayout.LayoutParams(-2, -2)) }
            r.addView(percentPill("AI"))
            aiRow = r
            box.addView(r)
        }
        // A new TextView for each text: one whose text changes after it was first laid out keeps its one-line height.
        val row = aiRow ?: return
        val old = aiText
        val maxW = screen().width - dp(20f) - dp(8f + 20f + 8f + 46f + 34f)
        aiText = answerText(if (text.isNullOrBlank()) "Writing an answer…" else text, maxW, 2, 13.5f).apply {
            if (text.isNullOrBlank()) { setTextColor(muted); tag = "writing" } else tag = if (writing) "writing" else null
        }
        val i = if (old != null) row.indexOfChild(old) else -1
        if (i >= 0) { row.removeViewAt(i); row.addView(aiText, i, LinearLayout.LayoutParams(-2, -2)) }
        refit()
    }

    private fun refit() {
        val c = card ?: return
        val f = lastField ?: return
        if (!cardAdded) return
        place(c, f, lastIme, lastCaret)
    }

    fun hideSuggestion() {
        card?.let { if (cardAdded) { runCatching { wm.removeView(it) }; cardAdded = false } }
        cardRect = null
        current = null
    }

    private var cardRect: Rect? = null
    private var lastBubbleField: FocusedField? = null
    private var lastIme: Rect? = null

    // ------------------------------------------------------------------ the ✦

    private var bubbleEdge = true
    private var bubbleOnLeft = false
    private val fade = Runnable { bubble?.animate()?.alpha(IDLE_ALPHA)?.setDuration(400)?.start() }

    private fun buildBubble(edge: Boolean, left: Boolean): FrameLayout {
        bubble?.let { if (bubbleAdded) runCatching { wm.removeView(it) }; bubbleAdded = false }
        val frame = FrameLayout(context).apply {
            background = if (edge) GradientDrawable().apply {
                // A half pill hugging the screen edge: rounded only on the inner side.
                val r = dp(26f).toFloat()
                cornerRadii = if (left) floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f) else floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                setColor(Color.argb(if (dark) 200 else 215, Color.red(accent), Color.green(accent), Color.blue(accent)))
            } else GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(if (dark) 190 else 210, Color.red(accent), Color.green(accent), Color.blue(accent)))
                setStroke(dp(1f), Color.argb(150, 255, 255, 255))
            }
            elevation = dp(4f).toFloat()
            contentDescription = "TypePilot: rewrite or write with AI. Drag to move."
        }
        dragHandling(frame)
        frame.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_sparkle)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
        }, FrameLayout.LayoutParams(dp(18f), dp(18f), Gravity.CENTER).apply { if (edge) { if (left) marginEnd = dp(3f) else marginStart = dp(3f) } })
        bubbleEdge = edge; bubbleOnLeft = left
        return frame
    }

    /** Tap = open the assistant, drag = move the ✦ (saved), hold still = hide it in this app. */
    @SuppressLint("ClickableViewAccessibility")
    private fun dragHandling(v: View) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var dragging = false; var longPressed = false
        val longPress = Runnable { if (!dragging) { longPressed = true; haptic(v); callbacks.onHideBubbleForApp() } }
        v.setOnTouchListener { view, e ->
            val p = bubbleParams ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y
                    dragging = false; longPressed = false
                    main.removeCallbacks(fade)
                    view.animate().alpha(1f).scaleX(0.94f).scaleY(0.94f).setDuration(80).start()
                    main.postDelayed(longPress, 650)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) { dragging = true; main.removeCallbacks(longPress) }
                    if (dragging) {
                        val sc = screen()
                        p.x = (startX + dx.toInt()).coerceIn(0, sc.width - view.width)
                        p.y = (startY + dy.toInt()).coerceIn(sc.top, sc.height - view.height)
                        runCatching { wm.updateViewLayout(view, p) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (e.actionMasked == MotionEvent.ACTION_UP) {
                        val sc = screen()
                        when {
                            dragging -> {
                                val xFrac = (p.x + view.width / 2f) / sc.width
                                callbacks.onBubbleMoved(if (bubbleEdge) (if (xFrac < 0.5f) 0f else 1f) else p.x.toFloat() / sc.width, p.y.toFloat() / sc.height)
                                lastBubbleField?.let { showBubble(it, lastIme) } // snap to the edge
                            }
                            !longPressed -> { haptic(view); callbacks.onOpenAssistant() }
                        }
                    }
                    scheduleFade()
                }
            }
            true
        }
    }

    private fun scheduleFade() { main.removeCallbacks(fade); main.postDelayed(fade, 2500) }

    /** Show the ✦ as soon as a field is focused; re-placed when the keyboard opens or a suggestion appears. */
    fun showBubble(field: FocusedField, ime: Rect?) {
        lastBubbleField = field; lastIme = ime
        val sc = screen()
        val edge = callbacks.bubbleOnEdge()
        val saved = callbacks.bubblePosition()
        val left = edge && saved != null && saved.first < 0.5f
        val firstShow = !bubbleAdded
        val view = if (bubble == null || edge != bubbleEdge || left != bubbleOnLeft) buildBubble(edge, left).also { bubble = it } else bubble!!
        val gap = dp(6f)
        val imeTop = ime?.top?.takeIf { it > 0 } ?: sc.height
        val f = Rect(field.bounds).apply { bottom = minOf(bottom, imeTop) }
        val p = bubbleParams ?: baseParams().also { bubbleParams = it }
        if (edge) {
            // Docked tab: at the field's height (or where you left it), never under the keyboard or the card.
            p.width = dp(30f); p.height = dp(48f)
            p.x = if (left) 0 else sc.width - p.width
            var y = if (saved != null) (saved.second * sc.height).toInt() else (f.centerY() - p.height / 2)
            val card = cardRect
            if (card != null && Rect.intersects(Rect(p.x, y, p.x + p.width, y + p.height), card)) y = card.top - p.height - gap
            p.y = y.coerceIn(sc.top + gap, imeTop - p.height - gap)
        } else {
            val size = dp(34f)
            p.width = size; p.height = size
            if (saved != null) {
                p.x = (saved.first * sc.width).toInt().coerceIn(0, sc.width - size)
                p.y = (saved.second * sc.height).toInt().coerceIn(sc.top, imeTop - size - gap)
            } else {
                // Just above the field's right edge; else below it; else above the keyboard. Never on the card.
                val card = cardRect
                val x = (f.right - size - dp(4f)).coerceIn(dp(8f), sc.width - size - dp(8f))
                val y = listOf(f.top - gap - size, f.bottom + gap, imeTop - gap - size, f.top + gap)
                    .firstOrNull { y0 ->
                        val r = Rect(x, y0, x + size, y0 + size)
                        y0 >= sc.top + gap && y0 + size <= imeTop - gap && (card == null || !Rect.intersects(r, card)) &&
                            (y0 == f.top + gap || !Rect.intersects(Rect(0, y0, sc.width, y0 + size), f))
                    } ?: (imeTop - gap - size)
                p.x = x; p.y = y
            }
        }
        try {
            if (bubbleAdded) { view.visibility = View.VISIBLE; wm.updateViewLayout(view, p) } else { wm.addView(view, p); bubbleAdded = true }
        } catch (e: Exception) { Log.e("overlay", "couldn't show ✦", e); return }
        if (firstShow) {
            // Slide in from the edge (tab) or pop in (circle).
            if (edge) { view.translationX = if (left) -p.width.toFloat() else p.width.toFloat(); view.alpha = 1f; view.animate().translationX(0f).setDuration(220).start() }
            else { view.scaleX = 0.6f; view.scaleY = 0.6f; view.alpha = 0f; view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).start() }
        }
        scheduleFade()
    }

    /** A soft pulse on the ✦ while the AI is preparing a suggestion. */
    fun setThinking(thinking: Boolean) {
        val b = bubble ?: return
        if (thinking) {
            if (pulse?.isRunning == true) return
            main.removeCallbacks(fade)
            pulse = ObjectAnimator.ofPropertyValuesHolder(b,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.12f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.12f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.7f)).apply {
                duration = 520; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
            }
        } else {
            pulse?.cancel(); pulse = null
            b.scaleX = 1f; b.scaleY = 1f; b.alpha = 1f
            scheduleFade()
        }
    }

    fun hideBubble(keep: Boolean = false) {
        setThinking(false)
        main.removeCallbacks(fade)
        bubble?.let { if (bubbleAdded) { if (keep) it.visibility = View.GONE else { runCatching { wm.removeView(it) }; bubbleAdded = false } } }
    }

    fun restoreBubbleIfHidden() { bubble?.let { if (bubbleAdded) it.visibility = View.VISIBLE } }

    fun hideAll() { hideSuggestion(); hideBubble(); lastBubbleField = null }

    companion object {
        /** How visible the ✦ stays while you're not touching it. */
        const val IDLE_ALPHA = 0.55f
    }
}
