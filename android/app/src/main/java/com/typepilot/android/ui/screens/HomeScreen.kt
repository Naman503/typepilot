package com.typepilot.android.ui.screens

import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.typepilot.android.TypePilotApp
import com.typepilot.android.engine.FieldContext
import com.typepilot.android.metrics.LiveMetrics
import com.typepilot.android.metrics.Stage
import com.typepilot.android.rewrite.RewriteBridge
import com.typepilot.android.ui.MainActivity
import com.typepilot.android.ui.Setup
import com.typepilot.android.ui.components.Gap
import com.typepilot.android.ui.components.Kpi
import com.typepilot.android.ui.components.KpiRow
import com.typepilot.android.ui.components.Pill
import com.typepilot.android.ui.components.SectionCard
import com.typepilot.android.ui.components.fmtMs
import com.typepilot.android.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Calendar

@Composable
fun HomeScreen(onOpen: (String) -> Unit) {
    val app = TypePilotApp.instance
    val context = LocalContext.current
    val settings by app.settings.state.collectAsState()
    val status by LiveMetrics.status.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var showDisclosure by remember { mutableStateOf(false) }

    // Re-check setup whenever we come back from system settings.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { while (true) { refresh++; delay(2000) } } }

    val accessibility = remember(refresh) { Setup.accessibilityOn(context) }
    val keyboardEnabled = remember(refresh) { Setup.keyboardEnabled(context) }
    val battery = remember(refresh) { Setup.batteryUnrestricted(context) }
    val providerError = remember(refresh, settings) { app.engine.modelFor(settings.completionProvider)?.configError() }

    val today by produceState<com.typepilot.android.metrics.MetricsStore.Summary?>(null, refresh / 3) {
        value = withContext(Dispatchers.IO) { app.metrics.summary(startOfToday()) }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("TypePilot", style = MaterialTheme.typography.headlineMedium)
        Text("AI writing in every app", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        Gap(16)

        // Hero status
        val active = settings.isActive && (accessibility || keyboardEnabled)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(if (active) Brand.gradient else
            androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.surfaceContainerHigh)).padding(20.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(when {
                            !settings.enabled -> "Turned off"
                            settings.isPaused -> "Paused"
                            !accessibility && !keyboardEnabled -> "Finish setup"
                            else -> "Active everywhere"
                        }, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (active) Color.White else MaterialTheme.colorScheme.onSurface)
                        Text(when {
                            settings.isPaused -> "Resumes automatically"
                            accessibility && status.serviceConnected -> "Suggestions on top of any app" + if (status.currentApp.isNotBlank()) " · last in ${status.currentApp}" else ""
                            keyboardEnabled -> "Using the TypePilot keyboard"
                            else -> "Turn on the assistant below"
                        }, fontSize = 13.sp, color = if (active) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(settings.enabled, { on -> app.settings.update { it.copy(enabled = on, pausedUntil = 0) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = Color.White.copy(alpha = 0.35f), checkedThumbColor = Color.White))
                }
                Gap(14)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val pillColor = if (active) Color.White else MaterialTheme.colorScheme.primary
                    Pill(if (accessibility) "● Overlay on" else "○ Overlay off", pillColor)
                    Pill(if (status.keyboardActive) "● Keyboard in use" else if (keyboardEnabled) "Keyboard ready" else "○ Keyboard off", pillColor)
                    if (settings.isPaused) Pill("Resume", pillColor) { app.settings.update { it.copy(pausedUntil = 0) } }
                    else if (settings.enabled) Pill("Pause 1h", pillColor) { app.settings.update { it.copy(pausedUntil = System.currentTimeMillis() + 3_600_000) } }
                }
            }
        }
        Gap(16)

        // Setup checklist
        val done = listOf(accessibility || keyboardEnabled, battery, providerError == null)
        if (!done.all { it }) {
            SectionCard("Setup", subtitle = "${done.count { it }} of 3 done") {
                SetupRow("Turn on TypePilot in Accessibility", "Suggestions & the ✦ button on top of every app, with your current keyboard (Gboard etc.).", accessibility) {
                    com.typepilot.android.telemetry.Usage.count("setup_step_opened", mapOf("step" to "accessibility"))
                    if (settings.disclosureAccepted) Setup.openAccessibility(context) else showDisclosure = true
                }
                if (!accessibility) {
                    Text("Greyed out? Android blocks this for apps installed outside Play: App info → ⋮ → Allow restricted settings, then try again.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 34.dp, bottom = 6.dp))
                    TextButton(onClick = { com.typepilot.android.telemetry.Usage.count("setup_step_opened", mapOf("step" to "restricted_settings")); Setup.openAppDetails(context) }, modifier = Modifier.padding(start = 26.dp)) { Text("Open App info") }
                }
                SetupRow("Or use the TypePilot keyboard", "Optional: a keyboard with AI in its toolbar. Works where accessibility is blocked.", keyboardEnabled) {
                    com.typepilot.android.telemetry.Usage.count("setup_step_opened", mapOf("step" to "keyboard"))
                    Setup.openKeyboardSettings(context)
                }
                SetupRow("Keep it running all day", if (Setup.aggressiveOem) "Unrestricted battery, plus on ${android.os.Build.MANUFACTURER}: ${Setup.oemSteps()}" else "Allow unrestricted battery so Android doesn't stop it.", battery) {
                    com.typepilot.android.telemetry.Usage.count("setup_step_opened", mapOf("step" to "battery"))
                    if (!battery) Setup.requestBattery(context) else Setup.openAppDetails(context)
                }
                SetupRow("Connect an AI model", providerError ?: "Ready", providerError == null) { com.typepilot.android.telemetry.Usage.count("setup_step_opened", mapOf("step" to "model")); onOpen(MainActivity.TAB_SETTINGS) }
            }
            Gap(16)
        } else if (Setup.aggressiveOem) {
            SectionCard("Stay alive on ${android.os.Build.MANUFACTURER}", subtitle = "Phone makers stop background apps; one more switch helps") {
                Text(Setup.oemSteps(), fontSize = 13.sp)
                TextButton(onClick = { com.typepilot.android.telemetry.Usage.count("setup_step_opened", mapOf("step" to "oem_battery")); Setup.openAppDetails(context) }) { Text("Open App info") }
            }
            Gap(16)
        }

        // Today
        val s = today
        Text("Today", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        KpiRow {
            Kpi("${s?.let { it.accepted + it.words + it.typedThrough } ?: 0}", "accepted", Modifier.weight(1f), Icons.Default.ThumbUp, Brand.Green,
                extra = s?.let { "of ${it.shown} shown" })
            Kpi("${((s?.acceptanceRate ?: 0f) * 100).toInt()}%", "acceptance", Modifier.weight(1f), Icons.Default.Bolt, Brand.Violet)
        }
        Gap(10)
        KpiRow {
            Kpi("${s?.acceptedWords ?: 0}", "words saved", Modifier.weight(1f), Icons.Default.EditNote, Brand.Pink,
                extra = s?.let { timeSaved(it.acceptedChars) })
            Kpi(fmtMs(s?.avgLatency ?: 0.0), "avg suggestion time", Modifier.weight(1f), Icons.Default.Speed, Brand.Cyan,
                extra = "${s?.rewrites ?: 0} rewrites")
        }
        Gap(16)

        // Quick actions
        SectionCard("Quick actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction("Rewrite clipboard", Icons.Default.ContentPaste, Modifier.weight(1f)) {
                    com.typepilot.android.telemetry.Usage.count("home_action", mapOf("action" to "rewrite_clipboard"))
                    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    RewriteBridge.open(context, RewriteBridge.Request(clip, FieldContext(), actionLabel = "Copy",
                        deliver = { com.typepilot.android.service.TextApplier.copy(context, it) }))
                }
                QuickAction("Write with AI", Icons.Default.AutoAwesome, Modifier.weight(1f)) {
                    com.typepilot.android.telemetry.Usage.count("home_action", mapOf("action" to "write"))
                    RewriteBridge.open(context, RewriteBridge.Request("", FieldContext(), tab = RewriteBridge.Tab.WRITE, actionLabel = "Copy",
                        deliver = { com.typepilot.android.service.TextApplier.copy(context, it) }))
                }
            }
            Gap(10)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction("Switch keyboard", Icons.Default.Keyboard, Modifier.weight(1f)) { com.typepilot.android.telemetry.Usage.count("home_action", mapOf("action" to "switch_keyboard")); Setup.pickKeyboard(context) }
                QuickAction(if (settings.isPaused) "Resume" else "Pause 1 hour", Icons.Default.PauseCircle, Modifier.weight(1f)) {
                    com.typepilot.android.telemetry.Usage.count("home_action", mapOf("action" to if (settings.isPaused) "resume" else "pause"))
                    app.settings.update { it.copy(pausedUntil = if (it.isPaused) 0 else System.currentTimeMillis() + 3_600_000) }
                }
            }
        }
        Gap(16)

        // Playground
        SectionCard("Try it here", subtitle = "Type below: suggestions appear like in any other app") {
            // A classic Android EditText (like most apps), so it reports typing the way other apps' fields do.
            val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
            val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
            val border = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            AndroidView(
                factory = { ctx ->
                    android.widget.EditText(ctx).apply {
                        id = com.typepilot.android.R.id.playground
                        hint = "Hi Priya, thanks for the update. I'll"
                        minLines = 3
                        gravity = android.view.Gravity.TOP or android.view.Gravity.START
                        background = null
                        setTextColor(textColor)
                        setHintTextColor(hintColor)
                        textSize = 16f
                        setPadding(36, 28, 36, 28)
                    }
                },
                modifier = Modifier.fillMaxWidth().border(1.5.dp, border, RoundedCornerShape(16.dp)),
            )
            if (status.lastSuggestion.isNotBlank()) {
                Gap(8)
                Text("Last suggestion · ${fmtMs(status.lastLatencyMs)} · ${status.lastProvider}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("“${status.lastSuggestion}”", fontSize = 13.sp)
            }
        }
        Gap(24)
    }

    if (showDisclosure) DisclosureDialog(
        onAccept = { com.typepilot.android.telemetry.Usage.event("disclosure", mapOf("choice" to "accept")); app.settings.update { it.copy(disclosureAccepted = true) }; showDisclosure = false; Setup.openAccessibility(context) },
        onDecline = { com.typepilot.android.telemetry.Usage.event("disclosure", mapOf("choice" to "decline")); showDisclosure = false },
    )
}

@Composable
private fun SetupRow(title: String, detail: String, done: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
            tint = if (done) Brand.Green else MaterialTheme.colorScheme.outline, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!done) FilledTonalButton(onClick = onClick) { Text("Set up") }
    }
}

@Composable
private fun QuickAction(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2)
        }
    }
}

/** Google Play's "prominent disclosure" for the Accessibility API: what we read, why, and where it goes. */
@Composable
fun DisclosureDialog(onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        icon = { Icon(Icons.Default.Timer, null) },
        title = { Text("How TypePilot uses Accessibility") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TypePilot uses the Accessibility service to work on top of your apps. With your permission it will:")
                Text("• Read the text field you're typing in (its text, cursor and label) to suggest how to continue and to rewrite it.")
                Text("• Read visible text near that field (e.g. the message you're replying to) so suggestions fit.")
                Text("• Insert the suggestion or rewrite into the field when you tap it.")
                Text("It never reads password fields, and skips banking/password-manager apps you exclude. Text goes only to the AI provider you choose (or stays on the phone with an on-device model). Your memory and stats stay on this device. Anonymous usage counts (which features and settings are used, how AI requests go, crashes; never text, app names or memory) are shared to improve the app; turn that off in Settings → Apps, memory & privacy.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = onAccept) { Text("Agree & continue") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("No thanks") } },
    )
}

fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Time saved, from characters you didn't have to type at your measured (or a typical 190 cpm) phone typing speed. */
fun timeSaved(chars: Int): String {
    val cpm = LiveMetrics.typingCpm.takeIf { it in 60.0..600.0 } ?: 190.0
    val sec = chars / cpm * 60
    return when {
        sec < 60 -> "${sec.toInt()} s saved"
        sec < 3600 -> "${(sec / 60).toInt()} min saved"
        else -> String.format("%.1f h saved", sec / 3600)
    }
}
