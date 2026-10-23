package com.typepilot.android.service

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.typepilot.android.core.Log
import com.typepilot.android.rewrite.CompactAssistant
import com.typepilot.android.rewrite.RewriteBridge
import com.typepilot.android.ui.theme.TypePilotTheme

/**
 * The AI assistant as a small floating window over the app you're in. Unlike a full screen it doesn't
 * close the keyboard or hide the app: it's non-focusable (the field keeps focus) except while you type an
 * instruction. Drag it by its header. Placed in the larger free area above or below the field.
 */
class FloatingAssistant(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var params: WindowManager.LayoutParams? = null
    private var anchoredToBottom = false
    /** Keyboard height at open time: a bottom-anchored window is measured from the keyboard's top while it owns the keyboard. */
    private var imeHeight = 0
    private var shifted = false

    val isShowing get() = view != null
    var targetPackage: String = ""; private set

    fun show(request: RewriteBridge.Request, field: Rect, ime: Rect?, onClosed: () -> Unit) {
        hide()
        targetPackage = request.ctx.packageName
        val m = wm.currentWindowMetrics
        val insets = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
        val screenH = m.bounds.height()
        val top = insets.top + (8 * density).toInt()
        val imeTop = ime?.top?.takeIf { it > 0 } ?: (screenH - (8 * density).toInt())
        imeHeight = if (ime != null && ime.top > 0) screenH - ime.top else 0
        shifted = false
        val gap = (8 * density).toInt()
        val below = imeTop - gap - (field.bottom + gap)   // room between the field and the keyboard
        val above = field.top - gap - top                  // room above the field
        val saved = com.typepilot.android.TypePilotApp.instance.settings.value.assistantY
        // Too little room on either side of the field (a short screen, a big keyboard): use the space from the top
        // down to the keyboard and cover the field. The assistant shows the field's text anyway.
        val cramped = saved < 0 && maxOf(below, above) < (320 * density).toInt()
        anchoredToBottom = saved < 0 && !cramped && above > below
        val room = if (saved >= 0 || cramped) imeTop - gap - top else maxOf(below, above)
        val maxH = minOf(room, (360 * density).toInt())

        val p = WindowManager.LayoutParams(
            m.bounds.width() - (20 * density).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = (if (anchoredToBottom) Gravity.BOTTOM else Gravity.TOP) or Gravity.CENTER_HORIZONTAL
            // BOTTOM gravity measures y from the screen's bottom edge: sit just above the field.
            // A position you dragged it to wins (kept clear of the keyboard).
            y = when {
                saved >= 0 -> (saved * screenH).toInt().coerceIn(top, (imeTop - (180 * density).toInt()).coerceAtLeast(top))
                cramped -> top
                anchoredToBottom -> screenH - (field.top - gap)
                else -> field.bottom + gap
            }
            windowAnimations = android.R.style.Animation_Dialog
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val o = OverlayOwner().also { it.start() }
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setViewTreeViewModelStoreOwner(o)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                TypePilotTheme {
                    CompactAssistant(
                        initialText = request.text,
                        ctx = request.ctx,
                        initialTab = request.tab,
                        initialMode = request.mode,
                        actionLabel = request.actionLabel,
                        onApply = { result, replace ->
                            hide()
                            (if (replace) request.deliver else request.insert ?: request.deliver)?.invoke(result)
                            onClosed()
                        },
                        onClose = { hide(); onClosed() },
                        maxHeight = (maxH / density).dp,
                        modifier = Modifier.fillMaxWidth(),
                        onNeedKeyboard = { this@FloatingAssistant.setKeyboardFocus(it) },
                        onDrag = { dx, dy -> this@FloatingAssistant.drag(dx, dy) },
                        onDragEnd = { this@FloatingAssistant.savePosition() },
                    )
                }
            }
        }
        try {
            wm.addView(v, p)
            view = v; owner = o; params = p
        } catch (e: Exception) { Log.e("assistant", "couldn't show floating assistant", e); o.stop() }
    }

    /** Take keyboard focus only while typing an instruction; otherwise the app's field keeps it. */
    /** Named so it can't be confused with View.setFocusable inside the ComposeView builder. */
    private fun setKeyboardFocus(focusable: Boolean) {
        val v = view ?: return
        val p = params ?: return
        val flags = if (focusable) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (flags == p.flags) return
        p.flags = flags
        if (anchoredToBottom && imeHeight > 0 && focusable != shifted) {
            p.y = (p.y + if (focusable) -imeHeight else imeHeight).coerceAtLeast(0)
            shifted = focusable
        }
        p.softInputMode = if (focusable) WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        runCatching { wm.updateViewLayout(v, p) }.onFailure { Log.e("assistant", "focus change failed", it) }
    }

    private fun drag(dx: Float, dy: Float) {
        val v = view ?: return
        val p = params ?: return
        p.y += if (anchoredToBottom) -dy.toInt() else dy.toInt()
        p.y = p.y.coerceAtLeast(0)
        runCatching { wm.updateViewLayout(v, p) }
    }

    /** Remember where you dragged it (its top edge, as a fraction of the screen height). */
    private fun savePosition() {
        val v = view ?: return
        val p = params ?: return
        val screenH = wm.currentWindowMetrics.bounds.height()
        val topPx = if (anchoredToBottom) screenH - p.y - v.height else p.y
        com.typepilot.android.TypePilotApp.instance.settings.update { it.copy(assistantY = topPx.toFloat() / screenH) }
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        owner?.stop()
        view = null; owner = null; params = null
    }

    /** Lifecycle + saved state for Compose in a window that has no Activity. */
    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        override val viewModelStore: ViewModelStore get() = store

        fun start() {
            saved.performRestore(null)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun stop() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            store.clear()
        }
    }
}
