package com.typepilot.android.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarHalf
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.typepilot.android.TypePilotApp
import com.typepilot.android.ai.BenchmarkResult
import com.typepilot.android.ai.DeviceProfile
import com.typepilot.android.ai.LocalCatalog
import com.typepilot.android.ai.LocalModelInfo
import com.typepilot.android.ai.ModelDiscovery
import com.typepilot.android.ai.ModelUse
import com.typepilot.android.ai.PreferredBackend
import com.typepilot.android.core.AppSettings
import com.typepilot.android.core.LocalBackend
import com.typepilot.android.core.ProviderKind
import com.typepilot.android.core.Secrets
import com.typepilot.android.ui.components.Pill
import com.typepilot.android.ui.components.fmtMs
import com.typepilot.android.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class ModelFilter(val label: String) { ALL("All"), FITS("Fits my phone"), AUTOCOMPLETE("Autocomplete"), REWRITE("Rewrite"), TRANSLATE("Translation"), INSTALLED("Downloaded") }
private enum class ModelSort(val label: String) { RECOMMENDED("Recommended"), FASTEST("Fastest"), QUALITY("Best quality"), SMALLEST("Smallest") }

/** Full-screen library of on-device models: what fits this phone, how fast and how good, with one-tap download and benchmark. */
@Composable
fun ModelLibraryDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { ModelLibrary(onDismiss) }
    }
}

@Composable
private fun ModelLibrary(onBack: () -> Unit) {
    val app = TypePilotApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by app.settings.state.collectAsState()
    val progress by app.downloader.progress.collectAsState()
    val bench by app.local.benchmarks.collectAsState()
    val localState by app.local.state.collectAsState()
    var refresh by remember { mutableStateOf(0) }
    val device = remember(refresh) { DeviceProfile.read(context) }
    val installed = remember(progress, refresh) { app.local.installedIds().toSet() }
    var filter by remember { mutableStateOf(ModelFilter.FITS) }
    var sort by remember { mutableStateOf(ModelSort.RECOMMENDED) }
    var benchmarking by remember { mutableStateOf<String?>(null) }
    var benchError by remember { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val name = (uri.lastPathSegment?.substringAfterLast('/') ?: "custom").let { if (it.endsWith(".litertlm")) it else "$it.litertlm" }
            context.contentResolver.openInputStream(uri)?.use { input -> File(app.local.modelsDir, name).outputStream().use { input.copyTo(it, 1 shl 20) } }
            withContext(Dispatchers.Main) { refresh++ }
        }
    }
    fun set(change: (AppSettings) -> AppSettings) = app.settings.update(change)
    fun benchmark(id: String) {
        benchmarking = id; benchError = null
        scope.launch {
            runCatching { app.local.benchmark(id, s.localBackend) }.onFailure { benchError = it.message }
            benchmarking = null
        }
    }

    val models = LocalCatalog.models.filter { m ->
        when (filter) {
            ModelFilter.ALL -> true
            ModelFilter.FITS -> device.fit(m) <= DeviceProfile.Fit.GOOD
            ModelFilter.AUTOCOMPLETE -> ModelUse.AUTOCOMPLETE in m.uses
            ModelFilter.REWRITE -> ModelUse.REWRITE in m.uses || ModelUse.WRITE in m.uses
            ModelFilter.TRANSLATE -> ModelUse.TRANSLATE in m.uses
            ModelFilter.INSTALLED -> m.id in installed
        }
    }.let { list ->
        when (sort) {
            ModelSort.RECOMMENDED -> list.sortedWith(compareBy<LocalModelInfo> { device.fit(it).ordinal }.thenByDescending { it.quality * 10 + device.estDecode(it) / 10 })
            ModelSort.FASTEST -> list.sortedByDescending { device.estDecode(it) }
            ModelSort.QUALITY -> list.sortedByDescending { it.quality }
            ModelSort.SMALLEST -> list.sortedBy { it.sizeMb }
        }
    }
    val recAuto = device.recommended(ModelUse.AUTOCOMPLETE)
    val recRewrite = device.recommended(ModelUse.REWRITE)

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column {
                    Text("On-device models", style = MaterialTheme.typography.titleLarge)
                    Text("Private, offline and free · LiteRT-LM", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { DeviceCard(device) }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Best for your phone", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    for ((label, m, isAuto) in listOf(Triple("Autocomplete", recAuto, true), Triple("Rewrite & write", recRewrite, false))) {
                        if (m == null) continue
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${m.name} · ${m.sizeMb} MB · ~${"%.0f".format(device.estDecode(m))} tok/s", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                            val inUse = if (isAuto) s.localModelId == m.id else s.localWritingModelId.ifBlank { s.localModelId } == m.id
                            when {
                                inUse -> Pill("in use", Brand.Green)
                                m.id in installed -> TextButton(onClick = {
                                    set { if (isAuto) it.copy(localModelId = m.id, completionProvider = ProviderKind.LOCAL) else it.copy(localWritingModelId = m.id, writingProvider = ProviderKind.LOCAL) }
                                }) { Text("Use") }
                                else -> TextButton(onClick = { app.downloader.start(m) }) { Text("Download") }
                            }
                        }
                    }
                }
            }
        }
        item { InUseCard(s, installed, localState.loadedId, ::set) }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (f in ModelFilter.entries) FilterChip(filter == f, { filter = f }, label = { Text(f.label, fontSize = 12.sp) })
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Sort", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (o in ModelSort.entries) FilterChip(sort == o, { sort = o }, label = { Text(o.label, fontSize = 12.sp) })
            }
            benchError?.let { Text("Benchmark failed: $it", color = Brand.Red, fontSize = 12.sp) }
        }
        items(models, key = { it.id }) { m ->
            ModelCard(m, device, installed.contains(m.id), progress[m.id], bench[m.id], benchmarking == m.id, s,
                onDownload = { app.downloader.start(m) }, onPause = { app.downloader.cancel(m.id) },
                onDelete = { app.local.delete(m.id); refresh++ },
                onUseAuto = { set { it.copy(localModelId = m.id, completionProvider = ProviderKind.LOCAL) } },
                onUseRewrite = { set { it.copy(localWritingModelId = m.id, writingProvider = ProviderKind.LOCAL) } },
                onBenchmark = { benchmark(m.id) })
        }
        if (models.isEmpty()) item { Text("No models match this filter.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { DiscoverSection(device, installed, progress, bench, benchmarking, s, ::benchmark) }
        item {
            TextButton(onClick = { importer.launch(arrayOf("*/*")) }) { Text("Import a .litertlm file from storage…") }
            Text("Speeds are estimates for your chip (scaled from published LiteRT-LM benchmarks on Samsung, Pixel and MediaTek phones). Tap Benchmark on a downloaded model to measure it on this phone.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DeviceCard(d: DeviceProfile) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Memory, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(d.model, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(d.soc.ifBlank { "Unknown chip" }, MaterialTheme.colorScheme.primary)
                Pill(if (d.maxGhz > 0.1) "${d.cores} cores · ${d.bigCores} fast @ ${"%.1f".format(d.maxGhz)} GHz" else "${d.cores} cores", MaterialTheme.colorScheme.primary)
                Pill("RAM ${"%.1f".format(d.totalRamMb / 1024.0)} GB · ${"%.1f".format(d.availRamMb / 1024.0)} GB free", if (d.availRamMb > 2500) Brand.Green else Brand.Amber)
                Pill(if (d.hasOpenCl) "GPU: OpenCL ✓" else "GPU: no OpenCL (CPU only)", if (d.hasOpenCl) Brand.Green else Brand.Amber)
                Pill("Storage ${"%.1f".format(d.freeStorageMb / 1024.0)} GB free", if (d.freeStorageMb > 4000) Brand.Green else Brand.Amber)
                Pill("Speed ×${"%.2f".format(d.speedFactor)} vs Dimensity 7200", MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun InUseCard(s: AppSettings, installed: Set<String>, loadedId: String, set: ((AppSettings) -> AppSettings) -> Unit) {
    val app = TypePilotApp.instance
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("In use", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            ModelPickerRow("Autocomplete", s.localModelId, installed) { id -> set { it.copy(localModelId = id, completionProvider = ProviderKind.LOCAL) } }
            ModelPickerRow("Rewrite & write", s.localWritingModelId.ifBlank { s.localModelId }, installed) { id -> set { it.copy(localWritingModelId = id, writingProvider = ProviderKind.LOCAL) } }
            if (s.localWritingModelId.isNotBlank() && s.localWritingModelId != s.localModelId)
                Text("Two different models: switching between them reloads (a few seconds). One model for both is fastest.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (loadedId.isNotBlank()) Text("Loaded now: ${app.local.displayName(loadedId)}", fontSize = 11.sp, color = Brand.Green)
            Spacer(Modifier.height(8.dp))
            Text("Run on", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (b in LocalBackend.entries) FilterChip(s.localBackend == b, { set { it.copy(localBackend = b) } },
                    label = { Text(when (b) { LocalBackend.AUTO -> "Auto (best per model)"; LocalBackend.CPU -> "CPU"; LocalBackend.GPU -> "GPU" }, fontSize = 12.sp) })
            }
            Text("Free memory after ${s.localIdleUnloadMin} idle min", fontSize = 13.sp)
            Slider(s.localIdleUnloadMin.toFloat(), { v -> set { it.copy(localIdleUnloadMin = v.toInt()) } }, valueRange = 1f..30f)
            var token by remember { mutableStateOf(app.secrets.get(Secrets.HUGGINGFACE).orEmpty()) }
            androidx.compose.material3.OutlinedTextField(token, { token = it; app.secrets.set(Secrets.HUGGINGFACE, it) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Hugging Face token (only for Gemma 3 models)", fontSize = 12.sp) },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), shape = RoundedCornerShape(14.dp))
        }
    }
}

@Composable
private fun ModelPickerRow(label: String, selected: String, installed: Set<String>, onPick: (String) -> Unit) {
    val app = TypePilotApp.instance
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = !open }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(if (selected.isBlank() || selected !in installed) "None downloaded" else app.local.displayName(selected), fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp))
    }
    AnimatedVisibility(open) {
        Column(Modifier.padding(start = 8.dp)) {
            if (installed.isEmpty()) Text("Download a model below first.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (id in installed) Text((if (id == selected) "● " else "○ ") + app.local.displayName(id), fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().clickable { onPick(id); open = false }.padding(vertical = 6.dp))
        }
    }
}

@Composable
private fun ModelCard(
    m: LocalModelInfo, d: DeviceProfile, have: Boolean, p: com.typepilot.android.ai.ModelDownloader.Progress?, b: BenchmarkResult?,
    benchmarking: Boolean, s: AppSettings,
    onDownload: () -> Unit, onPause: () -> Unit, onDelete: () -> Unit, onUseAuto: () -> Unit, onUseRewrite: () -> Unit, onBenchmark: () -> Unit,
) {
    val context = LocalContext.current
    var details by remember { mutableStateOf(false) }
    val fit = d.fit(m)
    val fitColor = when (fit) { DeviceProfile.Fit.GREAT -> Brand.Green; DeviceProfile.Fit.GOOD -> Brand.Cyan; DeviceProfile.Fit.HEAVY -> Brand.Amber; DeviceProfile.Fit.TOO_LARGE -> Brand.Red }
    val recommended = m == d.recommended(ModelUse.AUTOCOMPLETE) || m == d.recommended(ModelUse.REWRITE)
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(m.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("${m.publisher} · ${m.params} · ${m.languages}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Stars(m.quality)
            }
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(fit.label, fitColor)
                if (recommended) Pill("★ Recommended", Brand.Violet)
                if (m.gated) Pill("Needs HF token", Brand.Amber)
                if (m.backend == PreferredBackend.GPU) Pill("GPU model", MaterialTheme.colorScheme.primary)
                for (u in m.uses) Pill(u.label, MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.height(10.dp))
            // Key numbers
            Row(Modifier.fillMaxWidth()) {
                Stat("${m.sizeMb} MB", "download", Modifier.weight(1f))
                Stat("${"%.1f".format(m.peakRamMb / 1024.0)} GB", "RAM", Modifier.weight(1f))
                Stat(b?.let { "%.0f".format(it.decodeTps) } ?: "~${"%.0f".format(d.estDecode(m))}", "tok/s", Modifier.weight(1f), measured = b != null)
                Stat(b?.let { fmtMs(it.ttftMs) } ?: "~${"%.1f".format(d.estTtft(m))} s", "first word", Modifier.weight(1f), measured = b != null)
            }
            if (b != null) Text("Measured on this phone (${b.backend}): load ${fmtMs(b.loadMs)} · +${b.memMb} MB memory", fontSize = 11.sp, color = Brand.Green,
                modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(6.dp))
            Text(m.notes, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (fit == DeviceProfile.Fit.HEAVY || fit == DeviceProfile.Fit.TOO_LARGE) Text(fit.detail, fontSize = 12.sp, color = fitColor)

            Row(Modifier.fillMaxWidth().clickable { details = !details }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Developer details", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                Icon(if (details) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            }
            AnimatedVisibility(details) {
                Column(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(10.dp)) {
                    for ((k, v) in listOf(
                        "Parameters" to m.params, "Quantization" to m.quant, "Context" to if (m.context > 0) "${m.context} tokens" else "see card",
                        "Best backend" to m.backend.label, "Peak memory" to "${m.peakRamMb} MB", "Benchmark" to m.benchmark.ifBlank { "–" },
                        "License" to m.license, "Updated" to m.updated, "File" to m.file,
                    )) Row(Modifier.padding(vertical = 2.dp)) {
                        Text(k, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
                        Text(v, fontSize = 11.sp, fontFamily = if (k == "File") FontFamily.Monospace else FontFamily.Default)
                    }
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(m.repoUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                        contentPadding = PaddingValues(0.dp)) { Text("Model card on Hugging Face", fontSize = 12.sp) }
                }
            }

            if (p != null && !p.done) {
                LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                Text(p.error ?: "${p.bytes / 1_000_000} / ${p.total / 1_000_000} MB", fontSize = 11.sp,
                    color = if (p.error != null) Brand.Red else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    have -> {
                        val autoInUse = s.localModelId == m.id
                        val rwInUse = s.localWritingModelId.ifBlank { s.localModelId } == m.id
                        if (ModelUse.AUTOCOMPLETE in m.uses) (if (autoInUse) FilledTonalButton(onUseAuto) { Text("✓ Autocomplete", fontSize = 12.sp) } else OutlinedButton(onUseAuto) { Text("Use for autocomplete", fontSize = 12.sp) })
                        if (rwInUse) FilledTonalButton(onUseRewrite) { Text("✓ Rewrite", fontSize = 12.sp) } else OutlinedButton(onUseRewrite) { Text("Use for rewrite", fontSize = 12.sp) }
                        if (benchmarking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else TextButton(onClick = onBenchmark) { Text("Benchmark", fontSize = 12.sp) }
                        TextButton(onClick = onDelete) { Text("Delete", color = Brand.Red, fontSize = 12.sp) }
                    }
                    p != null && !p.done && p.error == null -> OutlinedButton(onPause) { Text("Pause", fontSize = 12.sp) }
                    fit == DeviceProfile.Fit.TOO_LARGE -> OutlinedButton(onDownload) { Text("Download anyway (${m.sizeMb} MB)", fontSize = 12.sp) }
                    else -> Button(onDownload) { Text(if (p?.error != null) "Retry download" else "Download · ${m.sizeMb} MB", fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier, measured: Boolean = false) {
    Column(modifier) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (measured) Brand.Green else MaterialTheme.colorScheme.onSurface)
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Stars(q: Double) {
    Row {
        for (i in 1..5) {
            val icon = when { q >= i -> Icons.Default.Star; q >= i - 0.5 -> Icons.Default.StarHalf; else -> Icons.Default.StarOutline }
            Icon(icon, null, tint = Brand.Amber, modifier = Modifier.size(14.dp))
        }
    }
}

/** Live list from Hugging Face: everything new that litert-community publishes. */
@Composable
private fun DiscoverSection(
    device: DeviceProfile, installed: Set<String>, progress: Map<String, com.typepilot.android.ai.ModelDownloader.Progress>,
    bench: Map<String, BenchmarkResult>, benchmarking: String?, s: AppSettings, onBenchmark: (String) -> Unit,
) {
    val app = TypePilotApp.instance
    val scope = rememberCoroutineScope()
    var repos by remember { mutableStateOf<List<ModelDiscovery.Repo>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val files = remember { mutableStateMapOf<String, List<ModelDiscovery.RemoteFile>>() }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("More from Hugging Face", fontWeight = FontWeight.SemiBold)
            Text("The latest LiteRT-LM models, fetched live. Builds for other chips are hidden.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (repos == null) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    loading = true; error = null
                    scope.launch { runCatching { ModelDiscovery.repos() }.onSuccess { repos = it }.onFailure { error = it.message }; loading = false }
                }, enabled = !loading) { Text(if (loading) "Loading…" else "Show latest models") }
            }
            error?.let { Text(it, color = Brand.Red, fontSize = 12.sp) }
            val known = LocalCatalog.models.map { it.repo }.toSet()
            for (r in repos.orEmpty().filter { it.name !in known }) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth().clickable {
                    if (files[r.id] == null) scope.launch { runCatching { ModelDiscovery.files(r.id, device.soc) }.onSuccess { files[r.id] = it }.onFailure { error = it.message } }
                    else files.remove(r.id)
                }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("${r.downloads} downloads · updated ${r.updated}${if (r.gated) " · needs HF token" else ""}", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (files[r.id] != null) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
                for (f in files[r.id].orEmpty()) {
                    val info = ModelDiscovery.asModel(r.id, f, r.gated)
                    val have = info.id in installed
                    val p = progress[info.id]
                    val fit = device.fit(info)
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text("${f.sizeMb} MB · ${fit.label}" + (bench[info.id]?.let { " · measured ${"%.0f".format(it.decodeTps)} tok/s" } ?: ""), fontSize = 11.sp,
                                color = if (fit <= DeviceProfile.Fit.GOOD) Brand.Green else Brand.Amber)
                            if (p != null && !p.done) LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                        }
                        when {
                            have && benchmarking == info.id -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            have -> Row {
                                TextButton(onClick = { app.settings.update { it.copy(localWritingModelId = info.id, writingProvider = ProviderKind.LOCAL) } }) {
                                    Text(if (s.localWritingModelId == info.id) "✓ In use" else "Use", fontSize = 12.sp)
                                }
                                TextButton(onClick = { onBenchmark(info.id) }) { Text("Test", fontSize = 12.sp) }
                            }
                            p != null && !p.done && p.error == null -> TextButton(onClick = { app.downloader.cancel(info.id) }) { Text("Pause", fontSize = 12.sp) }
                            else -> TextButton(onClick = { app.downloader.start(info) }) { Text("Download", fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    }
}
