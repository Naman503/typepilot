package com.typepilot.android.rewrite

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typepilot.android.TypePilotApp
import com.typepilot.android.core.WriteLength
import com.typepilot.android.engine.FieldContext
import com.typepilot.android.engine.RewriteMode
import com.typepilot.android.metrics.EventType
import com.typepilot.android.metrics.MetricEvent
import com.typepilot.android.ui.theme.Brand
import com.typepilot.android.telemetry.Usage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * The small floating assistant: Rewrite (Fix grammar · Grammar + flow · Best version + tones) and Write.
 * Compact on purpose (13 sp text, small chips) so the app underneath stays visible.
 *
 * [onNeedKeyboard] asks the host window to take keyboard focus (only while you type an instruction);
 * [onDrag] moves the floating window.
 */
@Composable
fun CompactAssistant(
    initialText: String,
    ctx: FieldContext,
    initialTab: RewriteBridge.Tab,
    actionLabel: String?,
    /** (text, replace): replace = rewrite of the field/selection; false = insert a Write draft at the cursor. */
    onApply: ((String, Boolean) -> Unit)?,
    onClose: () -> Unit,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    initialMode: RewriteMode? = null,
    onNeedKeyboard: (Boolean) -> Unit = {},
    onDrag: ((Float, Float) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    /** For usage reporting only: floating (over an app) or sheet (text menu, share, shortcuts). */
    surface: String = "floating",
) {
    val app = TypePilotApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(if (initialText.isBlank()) RewriteBridge.Tab.WRITE else initialTab) }
    var mode by remember { mutableStateOf<RewriteMode?>(null) }
    var request by remember { mutableStateOf("") }
    var length by remember { mutableStateOf(app.settings.value.writeLength) }
    var output by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val history = remember { mutableStateListOf<String>() }
    var job by remember { mutableStateOf<Job?>(null) }
    val configError = remember { app.engine.writingConfigError() }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    /** The instruction box is live (window focusable + keyboard). Off by default so the app keeps the keyboard. */
    var typing by remember { mutableStateOf(false) }
    var applied by remember { mutableStateOf(false) }
    fun action(name: String) = Usage.count("ai_panel_action", mapOf("surface" to surface, "action" to name))
    // Closed with a result that wasn't used: a discard (applied ones are reported as rewrite_applied / write_applied).
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            if (output.isNotBlank()) Usage.event("ai_result_closed", mapOf("surface" to surface,
                "tab" to if (tab == RewriteBridge.Tab.REWRITE) "rewrite" else "write", "outcome" to if (applied) "applied" else "discarded"))
        }
    }
    LaunchedEffect(typing) {
        if (typing) { delay(150); runCatching { focus.requestFocus() }; keyboard?.show() }
    }

    fun run(block: suspend () -> Unit) {
        if (output.isNotBlank()) history += output
        job?.cancel()
        error = null; busy = true; output = ""
        job = scope.launch { block(); busy = false; if (output.isBlank() && error == null) error = "No result. Try again or pick another option." }
    }
    fun rewrite(m: RewriteMode) { if (initialText.isBlank()) return; mode = m; run { app.engine.rewrite(initialText, m, ctx).catch { e -> error = e.message }.collect { output = it } } }
    fun write(instruction: String) {
        if (instruction.isBlank()) return
        typing = false
        keyboard?.hide()
        onNeedKeyboard(false)
        mode = null
        run { app.engine.write(instruction, ctx.copy(selectedText = initialText), length).catch { e -> error = e.message }.collect { output = it } }
    }

    LaunchedEffect(Unit) { if (initialMode != null && initialText.isNotBlank()) rewrite(initialMode) }

    val accent = MaterialTheme.colorScheme.primary
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 10.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.18f))) {
        Column(Modifier.heightIn(max = maxHeight).padding(horizontal = 12.dp, vertical = 10.dp)) {
            // Header: drag handle · tabs · close
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = if (onDrag != null) Modifier.pointerInput(Unit) {
                    detectDragGestures(onDragEnd = { onDragEnd?.invoke() }) { change, d -> change.consume(); onDrag(d.x, d.y) }
                } else Modifier) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(Brand.gradient), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(13.dp))
                }
                Spacer(Modifier.width(8.dp))
                MiniTab("Rewrite", tab == RewriteBridge.Tab.REWRITE, enabled = initialText.isNotBlank()) {
                    tab = RewriteBridge.Tab.REWRITE; output = ""; error = null; typing = false; keyboard?.hide(); onNeedKeyboard(false)
                }
                Spacer(Modifier.width(4.dp))
                MiniTab("Write", tab == RewriteBridge.Tab.WRITE) { tab = RewriteBridge.Tab.WRITE; output = ""; error = null }
                Spacer(Modifier.weight(1f))
                if (onDrag != null) Icon(Icons.Default.DragHandle, "Drag", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Default.Close, "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp).clip(CircleShape).clickable { if (busy) action("cancel"); job?.cancel(); onClose() }.padding(5.dp))
            }
            Spacer(Modifier.height(8.dp))
            if (configError != null) {
                Text("⚠︎ $configError", fontSize = 12.sp, color = Brand.Amber, modifier = Modifier.padding(bottom = 6.dp))
            }

            if (tab == RewriteBridge.Tab.REWRITE) {
                Text(initialText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in RewriteMode.entries.filter { it.primary }) {
                        ModeChip("${m.emoji} ${m.title}", mode == m, primary = true, modifier = Modifier.weight(1f)) { rewrite(m) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in RewriteMode.entries.filter { !it.primary && it != RewriteMode.CUSTOM }) ModeChip("${m.emoji} ${m.title}", mode == m) { rewrite(m) }
                }
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((i, q) in listOf("Reply to this", "Polite reply", "Short follow-up", "Thank you", "Say no politely", "Ask for an update").withIndex()) {
                        ModeChip(q, false) { request = q; Usage.count("write_preset", mapOf("preset" to WRITE_PRESETS[i], "surface" to surface)); write(q) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)) {
                    Box(Modifier.weight(1f).heightIn(min = 34.dp).padding(vertical = 8.dp)) {
                        if (request.isEmpty()) Text(if (typing) "Type what to write, then send" else "What should I write? Tap to type",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (typing) BasicTextField(request, { v ->
                                // Enter sends (hardware keyboards and IMEs that insert a newline instead of the Send action).
                                if (v.contains('\n')) { request = v.replace("\n", "").trim(); write(request) } else request = v
                            },
                            textStyle = TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(accent), maxLines = 3,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { write(request) }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus))
                        else Box(Modifier.matchParentSize().clickable(remember { MutableInteractionSource() }, indication = null) {
                            // Step 1: let the floating window take the keyboard; step 2 (LaunchedEffect): focus the box.
                            onNeedKeyboard(true)
                            typing = true
                        }) { if (request.isNotEmpty()) Text(request, fontSize = 13.sp, maxLines = 3) }
                    }
                    Icon(Icons.AutoMirrored.Filled.Send, "Write", tint = if (request.isBlank()) MaterialTheme.colorScheme.outline else accent,
                        modifier = Modifier.size(34.dp).clip(CircleShape).clickable(enabled = request.isNotBlank()) { write(request) }.padding(7.dp))
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (l in WriteLength.entries) ModeChip(l.title, length == l) { if (length != l) action("length_${l.name.lowercase()}"); length = l }
                }
            }

            AnimatedVisibility(busy || output.isNotBlank() || error != null) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .heightIn(max = maxHeight * 0.42f).verticalScroll(rememberScrollState()).padding(10.dp)) {
                        when {
                            error != null -> Text("⚠︎ $error", color = Brand.Red, fontSize = 12.sp)
                            output.isBlank() -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp); Spacer(Modifier.width(8.dp))
                                Text("Writing…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            tab == RewriteBridge.Tab.REWRITE && !busy -> {
                                val marked = remember(output) { WordDiff.mark(initialText, output) }
                                Text(buildAnnotatedString {
                                    for ((w, changed) in marked) if (changed) withStyle(SpanStyle(background = accent.copy(alpha = 0.16f), color = accent)) { append(w) } else append(w)
                                }, fontSize = 13.sp, lineHeight = 18.sp)
                            }
                            else -> Text(output, fontSize = 13.sp, lineHeight = 18.sp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (history.isNotEmpty()) SmallIcon(Icons.AutoMirrored.Filled.Undo, "Undo") { action("undo"); output = history.removeAt(history.lastIndex) }
                        SmallIcon(Icons.Default.Refresh, "Retry", enabled = !busy) { action("retry"); mode?.let { rewrite(it) } ?: write(request) }
                        SmallIcon(Icons.Default.ContentCopy, "Copy", enabled = output.isNotBlank()) {
                            action("copy")
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("TypePilot", output))
                        }
                        if (busy) { Spacer(Modifier.width(6.dp)); CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp) }
                        Spacer(Modifier.weight(1f))
                        if (onApply != null && actionLabel != null) {
                            Box(Modifier.clip(RoundedCornerShape(50)).background(if (output.isNotBlank() && !busy) Brand.gradient else SolidColor(MaterialTheme.colorScheme.outlineVariant))
                                .clickable(enabled = output.isNotBlank() && !busy) {
                                    app.metrics.record(MetricEvent(if (tab == RewriteBridge.Tab.REWRITE) EventType.REWRITE_APPLIED else EventType.WRITE_APPLIED,
                                        "ai", ctx.packageName, mode = mode?.name ?: length.name, chars = output.length))
                                    applied = true
                                    onApply(output, tab == RewriteBridge.Tab.REWRITE)
                                }.padding(horizontal = 16.dp, vertical = 7.dp)) {
                                Text(if (tab == RewriteBridge.Tab.WRITE) "Insert" else actionLabel, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniTab(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = when { !enabled -> MaterialTheme.colorScheme.outline; selected -> accent; else -> MaterialTheme.colorScheme.onSurfaceVariant },
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable
private fun ModeChip(label: String, selected: Boolean, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier.clip(RoundedCornerShape(12.dp))
        .background(if (selected) accent.copy(alpha = 0.16f) else if (primary) accent.copy(alpha = 0.06f) else MaterialTheme.colorScheme.surfaceContainerHigh)
        .border(1.dp, if (selected) accent else Color.Transparent, RoundedCornerShape(12.dp))
        .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal, color = if (selected || primary) accent else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SmallIcon(icon: ImageVector, desc: String, enabled: Boolean = true, onClick: () -> Unit) {
    Icon(icon, desc, tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.size(32.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick).padding(7.dp))
}

/** The quick Write chips as fixed words for usage reporting (same order as the chips). */
internal val WRITE_PRESETS = listOf("reply", "polite_reply", "follow_up", "thank_you", "decline", "ask_update")
