package com.typepilot.android.rewrite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typepilot.android.TypePilotApp
import com.typepilot.android.core.WriteLength
import com.typepilot.android.engine.FieldContext
import com.typepilot.android.engine.RewriteMode
import com.typepilot.android.metrics.EventType
import com.typepilot.android.metrics.MetricEvent
import com.typepilot.android.ui.components.Pill
import com.typepilot.android.ui.theme.Brand
import com.typepilot.android.telemetry.Usage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * Rewrite with AI (three headline modes plus tone/format/translate) and Write with AI (a request → draft).
 * Streams the result, highlights what changed, keeps earlier versions for Undo.
 */
@Composable
fun RewritePanel(
    initialText: String,
    ctx: FieldContext,
    initialTab: RewriteBridge.Tab,
    actionLabel: String?,
    onApply: ((String) -> Unit)?,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    initialMode: RewriteMode? = null,
) {
    val app = TypePilotApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(initialTab) }
    var source by remember { mutableStateOf(initialText) }
    var mode by remember { mutableStateOf<RewriteMode?>(null) }
    var custom by remember { mutableStateOf("") }
    var request by remember { mutableStateOf("") }
    var length by remember { mutableStateOf(app.settings.value.writeLength) }
    var output by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDiff by remember { mutableStateOf(true) }
    val history = remember { mutableStateListOf<String>() }
    var job by remember { mutableStateOf<Job?>(null) }
    val configError = remember { app.engine.writingConfigError() }
    val surface = "write_tab"
    fun action(name: String) = Usage.count("ai_panel_action", mapOf("surface" to surface, "action" to name))
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            if (output.isNotBlank()) Usage.event("ai_result_closed", mapOf("surface" to surface,
                "tab" to if (tab == RewriteBridge.Tab.REWRITE) "rewrite" else "write", "outcome" to "left"))
        }
    }

    fun runRewrite(m: RewriteMode) {
        if (source.isBlank()) return
        mode = m
        if (output.isNotBlank()) history += output
        job?.cancel()
        error = null; busy = true; output = ""
        job = scope.launch {
            app.engine.rewrite(source, m, ctx, custom).catch { e -> error = e.message }.collect { output = it }
            busy = false
            if (output.isBlank() && error == null) error = "The model returned nothing. Try again or pick another mode."
        }
    }

    fun runWrite(previous: String? = null, instruction: String = request) {
        if (instruction.isBlank()) return
        if (output.isNotBlank()) history += output
        job?.cancel()
        error = null; busy = true
        if (previous == null) output = ""
        job = scope.launch {
            app.engine.write(instruction, ctx.copy(selectedText = source), length, previous).catch { e -> error = e.message }.collect { output = it }
            busy = false
            if (output.isBlank() && error == null) error = "The model returned nothing. Try rephrasing."
        }
    }

    // Opened from a specific mode (e.g. the text-selection menu entry "Fix grammar").
    androidx.compose.runtime.LaunchedEffect(Unit) { if (initialMode != null && initialText.isNotBlank()) runRewrite(initialMode) }

    Column(modifier.verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Brand.gradient), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (tab == RewriteBridge.Tab.REWRITE) "Rewrite with AI" else "Write with AI", style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull(ctx.appName.takeIf { it.isNotBlank() }, app.engine.writingModelName()).joinToString(" · "),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (onClose != null) IconButton(onClick = { job?.cancel(); onClose() }) { Icon(Icons.Default.Close, "Close") }
        }
        Spacer(Modifier.size(12.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            RewriteBridge.Tab.entries.forEachIndexed { i, t ->
                SegmentedButton(selected = tab == t, onClick = { if (tab != t) action("tab_${if (t == RewriteBridge.Tab.REWRITE) "rewrite" else "write"}"); tab = t; output = ""; error = null; history.clear() },
                    shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(if (t == RewriteBridge.Tab.REWRITE) "Rewrite" else "Write new") }
            }
        }
        Spacer(Modifier.size(12.dp))
        if (configError != null) {
            Surface(color = Brand.Amber.copy(alpha = 0.14f), shape = RoundedCornerShape(14.dp)) {
                Text("⚠︎ $configError", Modifier.padding(12.dp), fontSize = 13.sp)
            }
            Spacer(Modifier.size(12.dp))
        }

        if (tab == RewriteBridge.Tab.REWRITE) {
            OutlinedTextField(source, { source = it }, Modifier.fillMaxWidth().heightIn(min = 90.dp, max = 220.dp),
                label = { Text("Your text") }, shape = RoundedCornerShape(16.dp), textStyle = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.size(12.dp))
            // The three headline options.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in RewriteMode.entries.filter { it.primary }) {
                    val selected = mode == m
                    Surface(onClick = { runRewrite(m) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp),
                        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null) {
                        Column(Modifier.padding(10.dp)) {
                            Text(m.emoji, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Text(m.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(m.subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, lineHeight = 12.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in RewriteMode.entries.filter { !it.primary && it != RewriteMode.CUSTOM }) {
                    FilterChip(selected = mode == m, onClick = { runRewrite(m) }, label = { Text("${m.emoji} ${m.title}") })
                }
            }
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(custom, { custom = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp),
                placeholder = { Text("Or say how: \"make it sound like a CEO\"") },
                trailingIcon = { TextButton(onClick = { runRewrite(RewriteMode.CUSTOM) }, enabled = custom.isNotBlank()) { Text("Go") } })
        } else {
            OutlinedTextField(request, { request = it }, Modifier.fillMaxWidth().heightIn(min = 80.dp), shape = RoundedCornerShape(16.dp),
                label = { Text("What should I write?") },
                placeholder = { Text("e.g. a polite reply saying I'll send the report by Friday") })
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (l in WriteLength.entries) FilterChip(selected = length == l, onClick = { if (length != l) action("length_${l.name.lowercase()}"); length = l }, label = { Text(l.title) })
            }
            Spacer(Modifier.size(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((i, q) in listOf("Reply to this", "Polite reply", "Short follow-up", "Thank-you note", "Say no politely", "Ask for an update").withIndex()) {
                    Pill(q, MaterialTheme.colorScheme.primary) { request = q; Usage.count("write_preset", mapOf("preset" to WRITE_PRESETS[i], "surface" to surface)); runWrite() }
                }
            }
            Spacer(Modifier.size(10.dp))
            Button(onClick = { runWrite() }, enabled = request.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) { Text("Write it") }
        }

        AnimatedVisibility(busy || output.isNotBlank() || error != null) {
            Column {
                Spacer(Modifier.size(14.dp))
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (busy) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                            Text(if (busy) "Writing…" else mode?.title ?: "Draft", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            if (tab == RewriteBridge.Tab.REWRITE && output.isNotBlank() && !busy) {
                                val changes = remember(output, source) { WordDiff.changes(source, output) }
                                Text("$changes change${if (changes == 1) "" else "s"}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(6.dp))
                                Switch(showDiff, { showDiff = it; action(if (it) "diff_on" else "diff_off") }, Modifier.size(width = 40.dp, height = 24.dp))
                            }
                        }
                        Spacer(Modifier.size(8.dp))
                        if (error != null) Text("⚠︎ $error", color = Brand.Red, fontSize = 13.sp)
                        else if (tab == RewriteBridge.Tab.REWRITE && showDiff && !busy && output.isNotBlank()) {
                            val accent = MaterialTheme.colorScheme.primary
                            val annotated = remember(output, source) {
                                buildAnnotatedString {
                                    for ((word, changed) in WordDiff.mark(source, output)) {
                                        if (changed) withStyle(SpanStyle(background = accent.copy(alpha = 0.18f), color = accent, fontWeight = FontWeight.Medium)) { append(word) }
                                        else append(word)
                                    }
                                }
                            }
                            Text(annotated, fontSize = 15.sp, lineHeight = 21.sp)
                        } else Text(output.ifBlank { "…" }, fontSize = 15.sp, lineHeight = 21.sp)
                    }
                }
                Spacer(Modifier.size(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (history.isNotEmpty()) IconButton(onClick = { action("undo"); output = history.removeAt(history.lastIndex) }) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                    IconButton(onClick = { action("retry"); mode?.let { runRewrite(it) } ?: runWrite() }, enabled = !busy) { Icon(Icons.Default.Refresh, "Retry") }
                    IconButton(onClick = {
                        action("copy")
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("TypePilot", output))
                    }, enabled = output.isNotBlank()) { Icon(Icons.Default.ContentCopy, "Copy") }
                    IconButton(onClick = {
                        action("share")
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, output), null)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }, enabled = output.isNotBlank()) { Icon(Icons.Default.Share, "Share") }
                    if (tab == RewriteBridge.Tab.WRITE && output.isNotBlank() && !busy) {
                        TextButton(onClick = { action("shorter"); runWrite(output, "Make it shorter.") }) { Text("Shorter") }
                    }
                    Spacer(Modifier.weight(1f))
                    if (onApply != null && actionLabel != null) {
                        Button(onClick = {
                            app.metrics.record(MetricEvent(if (tab == RewriteBridge.Tab.REWRITE) EventType.REWRITE_APPLIED else EventType.WRITE_APPLIED,
                                "ai", ctx.packageName, mode = mode?.name ?: length.name, chars = output.length))
                            onApply(output)
                        }, enabled = output.isNotBlank() && !busy) { Text(actionLabel) }
                    }
                }
            }
        }
    }
}

/** Word-level diff (LCS), so a grammar fix shows exactly which words changed. */
object WordDiff {
    private fun tokens(s: String): List<String> = Regex("""\s+|[^\s]+""").findAll(s).map { it.value }.toList()

    /** Output tokens, each flagged true if it's new or changed. Falls back to "nothing marked" for huge texts. */
    fun mark(before: String, after: String): List<Pair<String, Boolean>> {
        val a = tokens(before).filter { it.isNotBlank() }
        val b = tokens(after)
        val bWords = b.filter { it.isNotBlank() }
        if (a.size * bWords.size > 400_000) return b.map { it to false }
        val dp = Array(a.size + 1) { IntArray(bWords.size + 1) }
        for (i in a.indices.reversed()) for (j in bWords.indices.reversed())
            dp[i][j] = if (norm(a[i]) == norm(bWords[j])) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        val keep = BooleanArray(bWords.size)
        var i = 0; var j = 0
        while (i < a.size && j < bWords.size) {
            when {
                norm(a[i]) == norm(bWords[j]) -> { keep[j] = true; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        var w = 0
        return b.map { t -> if (t.isBlank()) t to false else (t to !keep[w]).also { w++ } }
    }

    fun changes(before: String, after: String): Int {
        var n = 0
        var inRun = false
        for ((t, changed) in mark(before, after)) {
            if (t.isBlank()) continue
            if (changed && !inRun) n++
            inRun = changed
        }
        return n
    }

    private fun norm(s: String) = s
}
