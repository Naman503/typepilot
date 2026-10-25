package com.typepilot.android.rewrite

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import com.typepilot.android.engine.FieldContext
import com.typepilot.android.engine.RewriteMode
import com.typepilot.android.ui.theme.TypePilotTheme

/**
 * The Rewrite / Write sheet over whatever app you're in. Opened by:
 *  - the ✦ button next to a field (via [RewriteBridge]),
 *  - the text-selection menu in any app ("Rewrite with TypePilot", "Fix grammar") → ACTION_PROCESS_TEXT,
 *  - sharing text to TypePilot → ACTION_SEND.
 * As the foreground activity it can also use on-device models that refuse to run in the background.
 */
class RewriteActivity : ComponentActivity() {
    private var processTextReadOnly = true
    private var fromProcessText = false
    /** How the sheet was opened, as a fixed word for usage reporting. */
    private var entry = "other"

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        RewriteBridge.sheetOpen = true
        val request = buildRequest(intent)
        if (savedInstanceState == null) com.typepilot.android.telemetry.Usage.event("assistant_opened", mapOf(
            "surface" to "sheet", "entry" to entry, "tab" to if (request.tab == RewriteBridge.Tab.WRITE || request.text.isBlank()) "write" else "rewrite",
            "app" to com.typepilot.android.telemetry.UsageMapping.appCategory(request.ctx.packageName)))
        setContent {
            TypePilotTheme {
                val dismiss = remember { MutableInteractionSource() }
                val maxH = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
                // A small floating card (not a full sheet), so you can still see the app behind it.
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f))
                    .clickable(dismiss, indication = null) { finishWith(null) }) {
                    CompactAssistant(
                        initialText = request.text,
                        ctx = request.ctx,
                        initialTab = request.tab,
                        initialMode = request.mode,
                        actionLabel = request.actionLabel.takeIf { request.deliver != null || (fromProcessText && !processTextReadOnly) || intent.action == Intent.ACTION_SEND },
                        onApply = { text, _ -> finishWith(text) },
                        onClose = { finishWith(null) },
                        maxHeight = maxH,
                        surface = "sheet",
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding()
                            .padding(horizontal = 10.dp, vertical = 12.dp)
                            .clickable(remember { MutableInteractionSource() }, indication = null) {},
                    )
                }
            }
        }
    }

    private fun buildRequest(i: Intent): RewriteBridge.Request {
        val aliasMode = runCatching {
            packageManager.getActivityInfo(ComponentName(this, i.component?.className ?: javaClass.name), PackageManager.GET_META_DATA)
                .metaData?.getString("mode")?.let { RewriteMode.valueOf(it) }
        }.getOrNull()
        val caller = callingPackage ?: referrer?.host.orEmpty()
        return when (i.action) {
            Intent.ACTION_PROCESS_TEXT -> {
                fromProcessText = true
                entry = if (aliasMode == RewriteMode.GRAMMAR) "fix_grammar_menu" else "selection_menu"
                processTextReadOnly = i.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
                val text = i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
                RewriteBridge.Request(text, FieldContext(packageName = caller, appName = appName(caller)), mode = aliasMode, actionLabel = "Replace")
            }
            Intent.ACTION_SEND -> { entry = "share"; RewriteBridge.Request(i.getStringExtra(Intent.EXTRA_TEXT).orEmpty(), FieldContext(), actionLabel = "Copy") }
            else -> RewriteBridge.take()?.also { entry = "in_app" } ?: RewriteBridge.Request(i.getStringExtra(EXTRA_TEXT).orEmpty(), FieldContext(),
                tab = if (i.getStringExtra(EXTRA_TAB) == "write") RewriteBridge.Tab.WRITE else RewriteBridge.Tab.REWRITE).also { entry = "shortcut" }
        }.let { r -> if (aliasMode != null) r.copy(mode = aliasMode) else r }
    }

    private fun appName(pkg: String) = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault("")

    private fun finishWith(result: String?) {
        if (fromProcessText) {
            if (result != null && !processTextReadOnly) setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, result))
            else if (result != null) com.typepilot.android.service.TextApplier.copy(this, result)
        } else if (intent.action == Intent.ACTION_SEND && result != null) {
            com.typepilot.android.service.TextApplier.copy(this, result)
        }
        RewriteBridge.finish(result)
        RewriteBridge.sheetOpen = false
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, android.R.anim.fade_out)
    }

    override fun onDestroy() {
        RewriteBridge.sheetOpen = false
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_TAB = "tab"
    }
}
