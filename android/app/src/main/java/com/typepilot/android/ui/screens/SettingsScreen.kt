package com.typepilot.android.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.typepilot.android.BuildConfig
import com.typepilot.android.TypePilotApp
import com.typepilot.android.ai.GeminiModel
import com.typepilot.android.ai.LocalCatalog
import com.typepilot.android.ai.OpenAICompatibleModel
import com.typepilot.android.core.AppSettings
import com.typepilot.android.core.LocalBackend
import com.typepilot.android.core.Log
import com.typepilot.android.core.ProviderKind
import com.typepilot.android.core.Secrets
import com.typepilot.android.metrics.LiveMetrics
import com.typepilot.android.ui.components.Gap
import com.typepilot.android.ui.components.Pill
import com.typepilot.android.ui.components.SectionCard
import com.typepilot.android.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SettingsScreen() {
    val app = TypePilotApp.instance
    val s by app.settings.state.collectAsState()
    fun set(change: (AppSettings) -> AppSettings) = app.settings.update(change)

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Gap(12)

        SectionCard("Suggestions") {
            Toggle("Suggestions on top of apps", "Shown under the field as you type (accessibility mode)", s.overlaySuggestions) { v -> set { it.copy(overlaySuggestions = v) } }
            Toggle("✦ button next to fields", "Rewrite or write with AI in any app", s.aiBubble) { v -> set { it.copy(aiBubble = v) } }
            Toggle("Suggest when a field opens", "Your usual value, or the start of a reply, before you type", s.suggestOnFocus) { v -> set { it.copy(suggestOnFocus = v) } }
            Toggle("Suggest in empty fields", null, s.suggestOnEmptyFields) { v -> set { it.copy(suggestOnEmptyFields = v) } }
            Toggle("Read the screen around the field", "So replies fit the conversation", s.useScreenContext) { v -> set { it.copy(useScreenContext = v) } }
            Toggle("Haptic feedback", null, s.haptics) { v -> set { it.copy(haptics = v) } }
            Text("✦ button style", fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                FilterChip(s.bubbleOnEdge, { set { it.copy(bubbleOnEdge = true, bubbleX = -1f, bubbleY = -1f) } }, label = { Text("Edge tab (slides in, fades)") })
                FilterChip(!s.bubbleOnEdge, { set { it.copy(bubbleOnEdge = false, bubbleX = -1f, bubbleY = -1f) } }, label = { Text("Next to the field") })
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("✦ button position", fontSize = 14.sp)
                    Text(if (s.bubbleX >= 0) (if (s.bubbleOnEdge) "Your spot on the ${if (s.bubbleX < 0.5f) "left" else "right"} edge" else "Where you dragged it")
                        else if (s.bubbleOnEdge) "Right edge, at the field's height (drag it up/down or to the other side)" else "Next to each field (drag the ✦ to pin it anywhere)",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { set { it.copy(bubbleX = -1f, bubbleY = -1f) } }, enabled = s.bubbleX >= 0) { Text("Reset") }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Assistant popup position", fontSize = 14.sp)
                    Text(if (s.assistantY >= 0) "Where you dragged it" else "Automatic, next to the field (drag its ═ handle to move it)",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { set { it.copy(assistantY = -1f) } }, enabled = s.assistantY >= 0) { Text("Reset") }
            }
            SliderRow("Wait after typing", "${s.debounceMs} ms", s.debounceMs.toFloat(), 80f..800f) { v -> set { it.copy(debounceMs = v.toInt()) } }
            SliderRow("Suggestion length", "up to ${s.maxWords} words", s.maxWords.toFloat(), 3f..20f) { v -> set { it.copy(maxWords = v.toInt()) } }
            SliderRow("Start after", "${s.minChars} characters", s.minChars.toFloat(), 1f..10f) { v -> set { it.copy(minChars = v.toInt()) } }
            Gap(6)
            var about by remember { mutableStateOf(s.aboutMe) }
            OutlinedTextField(about, { about = it; set { x -> x.copy(aboutMe = it) } }, Modifier.fillMaxWidth(), minLines = 2, shape = RoundedCornerShape(14.dp),
                label = { Text("About you (optional)") }, placeholder = { Text("e.g. Product manager at Acme, Bengaluru. Prefer short, friendly replies.") })
        }
        Gap(16)
        AiSection(s, ::set)
        Gap(16)
        LocalModelsSection(s, ::set)
        Gap(16)
        PrivacySection(s, ::set)
        Gap(16)
        MetricsSection(s, ::set)
        Gap(16)
        SectionCard("About") {
            Text("TypePilot ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.SemiBold)
            Text("AI suggestions, rewriting and memory in every app.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Gap(10)
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Designed & developed by", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Naman Pathak", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    val ctx = LocalContext.current
                    TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:namanpathak503@gmail.com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("namanpathak503@gmail.com") }
                }
            }
        }
        Gap(24)
    }
}

@Composable
private fun AiSection(s: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    val app = TypePilotApp.instance
    val scope = rememberCoroutineScope()
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }
    SectionCard("AI", subtitle = "Which model suggests and writes") {
        Text("Suggestions while typing", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        ProviderPicker(s.completionProvider) { k -> set { it.copy(completionProvider = k) } }
        Gap(8)
        Text("Rewrite & write", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        ProviderPicker(s.writingProvider, allowOff = false) { k -> set { it.copy(writingProvider = k) } }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Text("Gemini", fontWeight = FontWeight.SemiBold)
        Text("Free key at aistudio.google.com. Flash-Lite is the fastest.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Gap(6)
        SecretField("Gemini API key", Secrets.GEMINI)
        Gap(6)
        var gm by remember { mutableStateOf(s.geminiModel) }
        var geminiModels by remember { mutableStateOf<List<String>?>(null) }
        var geminiErr by remember { mutableStateOf<String?>(null) }
        OutlinedTextField(gm, { gm = it; set { x -> x.copy(geminiModel = it.trim()) } }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Model") }, shape = RoundedCornerShape(14.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (m in (geminiModels ?: GeminiModel.suggestedModels)) FilterChip(gm == m, { gm = m; set { it.copy(geminiModel = m) } },
                label = { Text(m + if (m == "gemini-3.5-flash-lite") " · fastest" else "", fontSize = 12.sp) })
        }
        TextButton(onClick = {
            val key = app.secrets.get(Secrets.GEMINI)
            if (key.isNullOrBlank()) geminiErr = "Add your API key first." else scope.launch {
                runCatching { GeminiModel.listModels(key) }.onSuccess { geminiModels = it; geminiErr = null }.onFailure { geminiErr = it.message }
            }
        }) { Text("Load the models my key can use") }
        geminiErr?.let { Text(it, color = Brand.Red, fontSize = 12.sp) }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        Text("OpenAI-compatible", fontWeight = FontWeight.SemiBold)
        Text("Groq (fastest, free tier), OpenRouter, or Ollama on your computer.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        var base by remember { mutableStateOf(s.openAIBaseUrl) }
        var model by remember { mutableStateOf(s.openAIModel) }
        var serverModels by remember { mutableStateOf<List<String>?>(null) }
        var listErr by remember { mutableStateOf<String?>(null) }
        var scanning by remember { mutableStateOf(false) }
        var found by remember { mutableStateOf<List<com.typepilot.android.ai.OllamaDiscovery.Server>?>(null) }
        val context = LocalContext.current
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in OpenAICompatibleModel.presets) FilterChip(base == p.baseUrl, {
                base = p.baseUrl; model = p.model; serverModels = null; set { it.copy(openAIBaseUrl = p.baseUrl, openAIModel = p.model) }
            }, label = { Text(p.title) })
            FilterChip(base.contains(":11434"), {
                scanning = true; found = null
                scope.launch { found = com.typepilot.android.ai.OllamaDiscovery.scan(context); scanning = false }
            }, label = { Text(if (scanning) "Searching Wi-Fi…" else "Find Ollama on Wi-Fi") })
        }
        found?.let { servers ->
            if (servers.isEmpty()) Text("No Ollama found on this Wi-Fi. On your computer, Ollama must listen on the network: Mac: launchctl setenv OLLAMA_HOST 0.0.0.0, then quit and reopen Ollama. Phone and computer must be on the same Wi-Fi.",
                fontSize = 12.sp, color = Brand.Amber)
            for (sv in servers) {
                Text("Ollama at ${sv.host}" + if (sv.host == "127.0.0.1") " (USB: adb reverse)" else "", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in sv.models) FilterChip(base == sv.baseUrl && model == m, {
                        base = sv.baseUrl; model = m
                        set { it.copy(openAIBaseUrl = sv.baseUrl, openAIModel = m) }
                    }, label = { Text(m, fontSize = 12.sp) })
                    if (sv.models.isEmpty()) Text("No models: run 'ollama pull qwen2.5:3b' on the computer", fontSize = 12.sp)
                }
            }
        }
        OutlinedTextField(base, { base = it; serverModels = null; set { x -> x.copy(openAIBaseUrl = it.trim()) } }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Server URL") },
            shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Gap(6)
        OutlinedTextField(model, { model = it; set { x -> x.copy(openAIModel = it.trim()) } }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Model") }, shape = RoundedCornerShape(14.dp))
        serverModels?.let { list ->
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in list.take(40)) FilterChip(model == m, { model = m; set { it.copy(openAIModel = m) } }, label = { Text(m, fontSize = 12.sp) })
            }
        }
        TextButton(onClick = {
            scope.launch {
                runCatching { OpenAICompatibleModel.listModels(base, app.secrets.get(Secrets.OPENAI)) }
                    .onSuccess { serverModels = it; listErr = if (it.isEmpty()) "The server has no models." else null }
                    .onFailure { listErr = (it as? java.io.IOException)?.let { e -> com.typepilot.android.ai.Http.connectionHelp(OpenAICompatibleModel.hostLabel(base), runCatching { java.net.URI(base).host }.getOrDefault(base), e) } ?: it.message }
            }
        }) { Text("Load models from this server") }
        listErr?.let { Text(it, color = Brand.Red, fontSize = 12.sp) }
        Gap(6)
        SecretField("API key", Secrets.OPENAI)
        if (base.startsWith("http://") && !OpenAICompatibleModel.isPrivateHost(base))
            Text("⚠︎ Plain http to a public server sends your text unencrypted. Use https.", color = Brand.Amber, fontSize = 12.sp)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        SliderRow("Time limit", "${s.requestTimeoutMs / 1000} s", s.requestTimeoutMs.toFloat(), 3000f..60000f) { v -> set { it.copy(requestTimeoutMs = v.toInt()) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                testing = true
                scope.launch { testResult = app.engine.test(s.completionProvider); testing = false }
            }, enabled = !testing) { Text(if (testing) "Testing…" else "Test suggestions model") }
            OutlinedButton(onClick = {
                testing = true
                scope.launch { testResult = app.engine.test(s.writingProvider); testing = false }
            }, enabled = !testing) { Text("Test writing") }
        }
        testResult?.let { (ok, msg) -> Text((if (ok) "✓ " else "✕ ") + msg, color = if (ok) Brand.Green else Brand.Red, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable
private fun ProviderPicker(selected: ProviderKind, allowOff: Boolean = true, onPick: (ProviderKind) -> Unit) {
    Column {
        for (k in ProviderKind.entries) {
            if (!allowOff && k == ProviderKind.OFF) continue
            Row(Modifier.fillMaxWidth().selectable(selected == k) { onPick(k) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected == k, { onPick(k) })
                Column {
                    Text(k.title, fontSize = 14.sp)
                    if (k.isLocal) Text(if (k == ProviderKind.OFF) "Instant, offline: phrases, shortcuts and field values only" else "Private and offline, uses more battery",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SecretField(label: String, name: String) {
    val app = TypePilotApp.instance
    var value by remember { mutableStateOf(app.secrets.get(name).orEmpty()) }
    OutlinedTextField(value, { value = it; app.secrets.set(name, it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { if (value.isNotBlank()) Pill("saved", Brand.Green, Modifier.padding(end = 8.dp)) })
}

@Composable
private fun LocalModelsSection(s: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    val app = TypePilotApp.instance
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val device = remember { com.typepilot.android.ai.DeviceProfile.read(context) }
    val installed = remember(open) { app.local.installedIds() }
    val rec = remember { device.recommended(com.typepilot.android.ai.ModelUse.REWRITE) }
    SectionCard("On-device models", subtitle = "Private, offline, free · ${LocalCatalog.models.size} models + live list") {
        Text("${device.model} · ${"%.1f".format(device.totalRamMb / 1024.0)} GB RAM · ${if (device.hasOpenCl) "GPU ready" else "CPU only"}",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (installed.isEmpty()) "No models downloaded yet." else "Downloaded: " + installed.joinToString { app.local.displayName(it) }, fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp))
        if (s.completionProvider == ProviderKind.LOCAL || s.writingProvider == ProviderKind.LOCAL)
            Text("Autocomplete: ${app.local.displayName(s.localModelId).ifBlank { "–" }} · Rewrite: ${app.local.displayName(s.localWritingModelId.ifBlank { s.localModelId }).ifBlank { "–" }}",
                fontSize = 12.sp, color = Brand.Green)
        rec?.let { Text("Recommended for this phone: ${it.name}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
        Gap(8)
        Button(onClick = { open = true }) { Text("Browse models · speed, accuracy & requirements") }
    }
    if (open) ModelLibraryDialog { open = false }
}

@Composable
private fun PrivacySection(s: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    val app = TypePilotApp.instance
    var pickApps by remember { mutableStateOf(false) }
    SectionCard("Apps, memory & privacy") {
        Surface(onClick = { pickApps = true }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Where TypePilot works", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text((if (s.appMode == com.typepilot.android.core.AppMode.ONLY_SELECTED) "Only in ${s.includedPackages.size} picked apps"
                        else "Everywhere except ${s.excludedPackages.size} apps") + if (s.smartSwitch) " · hidden in ${s.hiddenPackages.size} payment apps" else "",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Choose", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
        Gap(8)
        MemorySettingsCard()
        Gap(4)
        Toggle("Use memory in AI prompts", "Lets the model use facts you've typed before", s.memoryInPrompts) { v -> set { it.copy(memoryInPrompts = v) } }
        Gap(8)
        var confirm by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { if (confirm) { app.memory.clear(); confirm = false } else confirm = true }) {
            Text(if (confirm) "Tap again to erase all memory" else "Clear memory", color = Brand.Red)
        }
        Gap(8)
        BackupRow()
    }
    if (pickApps) AppPickerDialog { pickApps = false }
}

/** Save everything TypePilot has learned to a file, or bring it back (new phone, reinstall, switching builds). */
@Composable
private fun BackupRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Uri?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            status = withContext(Dispatchers.IO) {
                runCatching { com.typepilot.android.core.Backup.export(context, uri) }
                    .fold({ "Saved. Keep this file private: it holds your memory (API keys aren't included)." }, { "Backup failed: ${it.message}" })
            }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) pending = uri }
    Text("Backup", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    Text("Facts, documents, phrases, shortcuts, stats and settings in one file. API keys stay on this phone.",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        OutlinedButton(onClick = { save.launch(com.typepilot.android.core.Backup.fileName()) }) { Text("Back up") }
        OutlinedButton(onClick = { open.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }) { Text("Restore") }
    }
    status?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
    pending?.let { uri ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Restore this backup?") },
            text = { Text("Your current memory, phrases, stats and settings are replaced by the backup, then TypePilot restarts. API keys you've entered stay as they are.") },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    scope.launch {
                        val error = withContext(Dispatchers.IO) { runCatching { com.typepilot.android.core.Backup.stage(context, uri) }.exceptionOrNull() }
                        if (error == null) com.typepilot.android.core.Backup.restart(context) else status = error.message
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MetricsSection(s: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    val app = TypePilotApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showLogs by remember { mutableStateOf(false) }
    val logs by Log.lines.collectAsState()
    SectionCard("Stats & diagnostics") {
        Toggle("Record usage stats", "Kept on this phone for the Insights screen (90 days)", s.metricsEnabled) { v -> set { it.copy(metricsEnabled = v) } }
        Toggle("Detailed logs", "For troubleshooting", s.verboseLogs) { v -> set { it.copy(verboseLogs = v) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    val file = withContext(Dispatchers.IO) { File(context.cacheDir, "typepilot-stats.csv").apply { writeText(app.metrics.exportCsv()) } }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Export stats").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Text("Export CSV") }
            var confirm by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { if (confirm) { app.metrics.reset(); LiveMetrics.reset(); confirm = false } else confirm = true }) {
                Text(if (confirm) "Tap to confirm" else "Reset stats", color = Brand.Red)
            }
        }
        TextButton(onClick = { showLogs = !showLogs }) { Text(if (showLogs) "Hide logs" else "Show logs (${logs.size})") }
        AnimatedVisibility(showLogs) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(10.dp)) {
                    for (l in logs.takeLast(200).reversed()) Text(l.toString(), fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                        color = when (l.level) { 'E' -> Brand.Red; 'W' -> Brand.Amber; else -> MaterialTheme.colorScheme.onSurface })
                }
            }
        }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(checked) { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked, onChange)
    }
}

@Composable
private fun SliderRow(title: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row { Text(title, fontSize = 14.sp, modifier = Modifier.weight(1f)); Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
        Slider(current.coerceIn(range), onChange, valueRange = range)
    }
}
