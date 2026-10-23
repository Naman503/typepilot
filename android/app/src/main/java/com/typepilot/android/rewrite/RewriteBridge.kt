package com.typepilot.android.rewrite

import android.content.Context
import android.content.Intent
import com.typepilot.android.engine.FieldContext
import com.typepilot.android.engine.RewriteMode

/**
 * Hands text from wherever the user is (another app's field, the text-selection menu, a share) to the
 * Rewrite sheet, and the result back. Same process, so plain objects instead of parcels.
 */
object RewriteBridge {
    enum class Tab { REWRITE, WRITE }

    data class Request(
        val text: String,
        val ctx: FieldContext,
        val tab: Tab = Tab.REWRITE,
        val mode: RewriteMode? = null,
        /** Called with the final text when the user taps Replace/Insert; null = copy/share only. */
        val deliver: ((String) -> Unit)? = null,
        val actionLabel: String = "Replace",
        /** Write drafts go in at the cursor (or replace the selection) instead of replacing the whole field. */
        val insert: ((String) -> Unit)? = null,
    )

    @Volatile var current: Request? = null; private set
    /** True while the sheet is on screen: the overlay stays quiet so it doesn't react to our own fields. */
    @Volatile var sheetOpen = false

    fun open(context: Context, request: Request) {
        current = request
        context.startActivity(Intent(context, RewriteActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION))
    }

    fun take(): Request? = current

    fun finish(result: String?) {
        val r = current
        current = null
        if (result != null) r?.deliver?.invoke(result)
    }
}
