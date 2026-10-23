package com.typepilot.android.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * A complete QWERTY keyboard drawn on one canvas (no child views): one draw call per frame and
 * touch handling with multi-touch rollover, so fast two-thumb typing never drops a key.
 *
 * Long-press a top-row letter for its number, hold ⌫ to repeat (then whole words), slide on the space
 * bar to move the cursor, long-press space to switch keyboards.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: Listener) : View(context) {
    interface Listener {
        fun onText(text: String)
        fun onDelete(word: Boolean)
        fun onEnter()
        fun onCursor(delta: Int)
        fun onSwitchKeyboard()
    }

    enum class Code { CHAR, SHIFT, DELETE, SPACE, ENTER, TO_SYMBOLS, TO_LETTERS, TO_MORE, SPACER }
    enum class Mode { LETTERS, SYMBOLS, MORE }
    enum class Shift { OFF, ON, LOCK }

    class Key(val label: String, val code: Code = Code.CHAR, val width: Float = 1f, val alt: String? = null) {
        val rect = RectF()
    }

    var mode = Mode.LETTERS
        set(v) { field = v; rows = build(v); layoutKeys(); invalidate() }
    var shift = Shift.OFF
        set(v) { field = v; invalidate() }
    var enterLabel = "⏎"
        set(v) { field = v; invalidate() }
    var haptics = true

    private var rows: List<List<Key>> = build(Mode.LETTERS)
    private val density = resources.displayMetrics.density
    private val rowHeight = 54 * density
    private val pad = 3 * density
    private val radius = 7 * density

    private val dark get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val bgPaint = Paint()
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val accent = Color.rgb(124, 92, 255)

    private val handler = Handler(Looper.getMainLooper())
    private class Touch(val key: Key, val downX: Float) {
        var consumed = false
        var lastCursorX = downX
    }
    private val touches = HashMap<Int, Touch>()

    private fun build(mode: Mode): List<List<Key>> = when (mode) {
        Mode.LETTERS -> listOf(
            "qwertyuiop".mapIndexed { i, c -> Key(c.toString(), alt = ((i + 1) % 10).toString()) },
            listOf(Key("", Code.SPACER, 0.5f)) + "asdfghjkl".map { Key(it.toString()) } + listOf(Key("", Code.SPACER, 0.5f)),
            listOf(Key("⇧", Code.SHIFT, 1.5f)) + "zxcvbnm".map { Key(it.toString()) } + listOf(Key("⌫", Code.DELETE, 1.5f)),
            listOf(Key("?123", Code.TO_SYMBOLS, 1.5f), Key(",", alt = "!"), Key("space", Code.SPACE, 5f), Key(".", alt = "?"), Key("⏎", Code.ENTER, 1.5f)),
        )
        Mode.SYMBOLS -> listOf(
            "1234567890".map { Key(it.toString()) },
            "@#₹_&-+()/".map { Key(it.toString()) },
            listOf(Key("=\\<", Code.TO_MORE, 1.5f)) + "*\"':;!?".map { Key(it.toString()) } + listOf(Key("⌫", Code.DELETE, 1.5f)),
            listOf(Key("ABC", Code.TO_LETTERS, 1.5f), Key(","), Key("space", Code.SPACE, 5f), Key("."), Key("⏎", Code.ENTER, 1.5f)),
        )
        Mode.MORE -> listOf(
            "~`|•√π÷×¶∆".map { Key(it.toString()) },
            "£€$¢^°={}\\".map { Key(it.toString()) },
            listOf(Key("?123", Code.TO_SYMBOLS, 1.5f)) + "%©®™✓[]".map { Key(it.toString()) } + listOf(Key("⌫", Code.DELETE, 1.5f)),
            listOf(Key("ABC", Code.TO_LETTERS, 1.5f), Key("<"), Key("space", Code.SPACE, 5f), Key(">"), Key("⏎", Code.ENTER, 1.5f)),
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (rowHeight * rows.size + pad * 2).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutKeys()

    private fun layoutKeys() {
        if (width == 0) return
        val unit = (width - pad * 2) / 10f
        rows.forEachIndexed { r, row ->
            val total = row.sumOf { it.width.toDouble() }.toFloat()
            var x = pad + (10f - total) * unit / 2
            val top = pad + r * rowHeight
            for (k in row) {
                k.rect.set(x + pad, top + pad, x + k.width * unit - pad, top + rowHeight - pad)
                x += k.width * unit
            }
        }
    }

    private fun display(k: Key): String = when (k.code) {
        Code.CHAR -> if (shift != Shift.OFF && mode == Mode.LETTERS) k.label.uppercase() else k.label
        Code.SHIFT -> if (shift == Shift.LOCK) "⇪" else "⇧"
        Code.ENTER -> enterLabel
        Code.SPACE -> "TypePilot"
        else -> k.label
    }

    override fun onDraw(canvas: Canvas) {
        val d = dark
        bgPaint.color = if (d) Color.rgb(24, 24, 30) else Color.rgb(232, 233, 240)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val pressed = touches.values.map { it.key }.toSet()
        for (row in rows) for (k in row) {
            if (k.code == Code.SPACER) continue
            val special = k.code != Code.CHAR && k.code != Code.SPACE
            keyPaint.color = when {
                k in pressed -> if (d) Color.rgb(80, 80, 96) else Color.rgb(196, 198, 210)
                k.code == Code.ENTER -> accent
                k.code == Code.SHIFT && shift != Shift.OFF -> if (d) Color.rgb(70, 60, 120) else Color.rgb(214, 206, 255)
                special -> if (d) Color.rgb(48, 48, 58) else Color.rgb(204, 206, 218)
                else -> if (d) Color.rgb(62, 62, 74) else Color.WHITE
            }
            canvas.drawRoundRect(k.rect, radius, radius, keyPaint)
            val label = display(k)
            textPaint.color = when {
                k.code == Code.ENTER -> Color.WHITE
                k.code == Code.SPACE -> if (d) Color.rgb(150, 150, 166) else Color.rgb(120, 122, 136)
                else -> if (d) Color.rgb(238, 238, 246) else Color.rgb(30, 30, 38)
            }
            textPaint.textSize = when {
                k.code == Code.SPACE -> 12 * density
                label.length > 2 -> 14 * density
                else -> 21 * density
            }
            val y = k.rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(label, k.rect.centerX(), y, textPaint)
            k.alt?.let {
                hintPaint.textSize = 10 * density
                hintPaint.color = if (d) Color.rgb(140, 140, 156) else Color.rgb(140, 142, 156)
                canvas.drawText(it, k.rect.right - 4 * density, k.rect.top + 12 * density, hintPaint)
            }
        }
    }

    private fun keyAt(x: Float, y: Float): Key? {
        var best: Key? = null
        var bestDist = Float.MAX_VALUE
        for (row in rows) for (k in row) {
            if (k.code == Code.SPACER) continue
            if (k.rect.contains(x, y)) return k
            // Touches in the gaps go to the nearest key.
            val dx = maxOf(k.rect.left - x, 0f, x - k.rect.right)
            val dy = maxOf(k.rect.top - y, 0f, y - k.rect.bottom)
            val dist = dx * dx + dy * dy
            if (dist < bestDist) { bestDist = dist; best = k }
        }
        return if (bestDist < (12 * density) * (12 * density)) best else null
    }

    private fun feedback() { if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        val id = e.getPointerId(i)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // Rollover: a new finger lands before the previous one lifts → commit the previous key now.
                touches.entries.toList().forEach { (pid, t) -> if (!t.consumed && t.key.code == Code.CHAR) { fire(t.key); t.consumed = true }; if (t.key.code == Code.CHAR) touches.remove(pid) }
                val k = keyAt(e.getX(i), e.getY(i)) ?: return true
                val t = Touch(k, e.getX(i))
                touches[id] = t
                feedback()
                when (k.code) {
                    Code.DELETE -> { listener.onDelete(false); t.consumed = true; startRepeat(t) }
                    Code.CHAR -> if (k.alt != null) handler.postDelayed({ if (touches[id] === t && !t.consumed) { t.consumed = true; listener.onText(k.alt); feedback(); invalidate() } }, 320)
                    Code.SPACE -> handler.postDelayed({ if (touches[id] === t && !t.consumed && kotlin.math.abs(t.lastCursorX - t.downX) < 8 * density) { t.consumed = true; listener.onSwitchKeyboard() } }, 600)
                    else -> {}
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                for (p in 0 until e.pointerCount) {
                    val t = touches[e.getPointerId(p)] ?: continue
                    if (t.key.code == Code.SPACE) {
                        // Slide on space: move the cursor one character per ~14 dp.
                        val step = 14 * density
                        val dx = e.getX(p) - t.lastCursorX
                        if (kotlin.math.abs(dx) >= step) {
                            val n = (dx / step).toInt()
                            listener.onCursor(n)
                            t.lastCursorX += n * step
                            t.consumed = true
                            feedback()
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val t = touches.remove(id)
                if (t != null && !t.consumed) fire(t.key)
                if (t?.key?.code == Code.DELETE) handler.removeCallbacksAndMessages(REPEAT)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { touches.clear(); handler.removeCallbacksAndMessages(REPEAT); invalidate() }
        }
        return true
    }

    private val REPEAT = Any()

    private fun startRepeat(t: Touch) {
        val start = System.currentTimeMillis()
        fun tick() {
            if (!touches.values.contains(t)) return
            val held = System.currentTimeMillis() - start
            listener.onDelete(word = held > 1800)
            handler.postAtTime({ tick() }, REPEAT, android.os.SystemClock.uptimeMillis() + if (held > 1800) 120 else 55)
        }
        handler.postAtTime({ tick() }, REPEAT, android.os.SystemClock.uptimeMillis() + 420)
    }

    private var lastShiftTap = 0L

    private fun fire(k: Key) {
        when (k.code) {
            Code.CHAR -> {
                listener.onText(display(k))
                if (shift == Shift.ON) shift = Shift.OFF
            }
            Code.SPACE -> listener.onText(" ")
            Code.ENTER -> listener.onEnter()
            Code.SHIFT -> {
                val now = System.currentTimeMillis()
                shift = when {
                    shift == Shift.OFF -> if (now - lastShiftTap < 350) Shift.LOCK else Shift.ON
                    shift == Shift.ON && now - lastShiftTap < 350 -> Shift.LOCK
                    else -> Shift.OFF
                }
                lastShiftTap = now
            }
            Code.TO_SYMBOLS -> mode = Mode.SYMBOLS
            Code.TO_LETTERS -> mode = Mode.LETTERS
            Code.TO_MORE -> mode = Mode.MORE
            Code.DELETE, Code.SPACER -> {}
        }
    }
}
