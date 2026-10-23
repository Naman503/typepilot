package com.typepilot.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.typepilot.android.TypePilotApp
import com.typepilot.android.core.AppMode
import com.typepilot.android.core.AppSettings
import com.typepilot.android.service.A11ySwitch
import com.typepilot.android.service.PaymentApps
import com.typepilot.android.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class AppEntry(val pkg: String, val label: String, val system: Boolean, val payment: Boolean)

/** On: suggestions + ✦. Off: TypePilot ignores the app. Hidden: accessibility switched fully off while it's open. */
private enum class AppState(val label: String) { ON("On"), OFF("Off"), HIDDEN("Hidden") }

private enum class AppFilter(val label: String) { ALL("All"), ON("On"), OFF("Off"), HIDDEN("Hidden") }

@Composable
fun AppPickerDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AppPicker(onDismiss) }
    }
}

private fun stateOf(s: AppSettings, pkg: String): AppState = when {
    pkg in s.hiddenPackages -> AppState.HIDDEN
    s.appMode == AppMode.ONLY_SELECTED -> if (pkg in s.includedPackages) AppState.ON else AppState.OFF
    else -> if (pkg in s.excludedPackages) AppState.OFF else AppState.ON
}

private fun withState(s: AppSettings, pkg: String, state: AppState): AppSettings {
    val hidden = if (state == AppState.HIDDEN) s.hiddenPackages + pkg else s.hiddenPackages - pkg
    return if (s.appMode == AppMode.ONLY_SELECTED) s.copy(hiddenPackages = hidden,
        includedPackages = if (state == AppState.ON) s.includedPackages + pkg else s.includedPackages - pkg)
    else s.copy(hiddenPackages = hidden, excludedPackages = if (state == AppState.ON) s.excludedPackages - pkg else s.excludedPackages + pkg)
}

@Composable
private fun AppPicker(onBack: () -> Unit) {
    val app = TypePilotApp.instance
    val context = LocalContext.current
    val s by app.settings.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AppFilter.ALL) }
    var showSystem by remember { mutableStateOf(false) }
    // Re-check the switch permissions whenever we come back from system settings.
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { while (true) { tick++; delay(1500) } } }
    val canToggle = remember(tick) { A11ySwitch.canToggle(context) }
    val usage = remember(tick) { A11ySwitch.hasUsageAccess(context) }
    val ready = canToggle && usage

    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { ri ->
                    val ai = ri.activityInfo.applicationInfo
                    val label = ri.loadLabel(pm).toString()
                    AppEntry(ri.activityInfo.packageName, label,
                        (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0,
                        PaymentApps.isLikely(ri.activityInfo.packageName, label))
                }
                .distinctBy { it.pkg }.filter { it.pkg != context.packageName }.sortedBy { it.label.lowercase() }
        }
    }
    fun set(pkg: String, st: AppState) = app.settings.update { withState(it, pkg, st) }

    val list = apps.orEmpty().filter { a ->
        val st = stateOf(s, a.pkg)
        (showSystem || !a.system || a.payment || st != AppState.ON) &&
            (query.isBlank() || a.label.contains(query, true) || a.pkg.contains(query, true)) &&
            when (filter) { AppFilter.ON -> st == AppState.ON; AppFilter.OFF -> st == AppState.OFF; AppFilter.HIDDEN -> st == AppState.HIDDEN; else -> true }
    }
    val payments = list.filter { it.payment }
    val others = list.filterNot { it.payment }
    val counts = apps.orEmpty().groupingBy { stateOf(s, it.pkg) }.eachCount()

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column {
                    Text("Where TypePilot works", style = MaterialTheme.typography.titleLarge)
                    Text("${counts[AppState.ON] ?: 0} on · ${counts[AppState.OFF] ?: 0} off · ${counts[AppState.HIDDEN] ?: 0} hidden",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(10.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(s.appMode == AppMode.ALL_EXCEPT, { app.settings.update { it.copy(appMode = AppMode.ALL_EXCEPT) } }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Everywhere, except…", fontSize = 13.sp) }
                SegmentedButton(s.appMode == AppMode.ONLY_SELECTED, { app.settings.update { it.copy(appMode = AppMode.ONLY_SELECTED) } }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Only in apps I pick", fontSize = 13.sp) }
            }
            Spacer(Modifier.height(10.dp))
            SmartSwitchCard(s, canToggle, usage, payments = apps.orEmpty().filter { it.payment })
            Spacer(Modifier.height(10.dp))
            Legend()
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp),
                leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Search ${apps?.size ?: ""} apps") },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear") } })
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (f in AppFilter.entries) FilterChip(filter == f, { filter = f }, label = { Text(f.label, fontSize = 12.sp) })
                FilterChip(showSystem, { showSystem = !showSystem }, label = { Text("System apps", fontSize = 12.sp) })
            }
        }
        if (apps == null) item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        if (payments.isNotEmpty()) {
            item { SectionHeader("Payments & banking", "These usually refuse to open while an accessibility app is on: set them to Hidden.") }
            items(payments, key = { "p" + it.pkg }) { a -> AppRow(a, stateOf(s, a.pkg), ready) { set(a.pkg, it) } }
        }
        if (others.isNotEmpty()) {
            item { SectionHeader("All apps", null) }
            items(others, key = { it.pkg }) { a -> AppRow(a, stateOf(s, a.pkg), ready) { set(a.pkg, it) } }
        }
        if (apps != null && list.isEmpty()) item { Text("No apps match.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
    }
}

@Composable
private fun SectionHeader(title: String, sub: String?) {
    Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        if (sub != null) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((st, text) in listOf(AppState.ON to "works", AppState.OFF to "ignored", AppState.HIDDEN to "accessibility off")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(stateColor(st)))
                Spacer(Modifier.width(4.dp))
                Text("${st.label}: $text", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun stateColor(st: AppState) = when (st) { AppState.ON -> Brand.Green; AppState.OFF -> MaterialTheme.colorScheme.outline; AppState.HIDDEN -> Brand.Violet }

@Composable
private fun SmartSwitchCard(s: AppSettings, canToggle: Boolean, usage: Boolean, payments: List<AppEntry>) {
    val app = TypePilotApp.instance
    val context = LocalContext.current
    val ready = canToggle && usage
    Surface(shape = RoundedCornerShape(20.dp), color = Brand.Violet.copy(alpha = 0.08f)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, null, tint = Brand.Violet)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Smart switch for payment apps", fontWeight = FontWeight.SemiBold)
                    Text("Turns TypePilot's accessibility fully off while a Hidden app is open (GPay, PhonePe, banks…), and back on when you leave.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(s.smartSwitch && ready, { on ->
                    app.settings.update {
                        // First time on: hide the payment & banking apps found on this phone.
                        if (on && it.hiddenPackages.isEmpty()) it.copy(smartSwitch = true, hiddenPackages = payments.map { p -> p.pkg }.toSet())
                        else it.copy(smartSwitch = on)
                    }
                }, enabled = ready)
            }
            if (!ready) {
                Spacer(Modifier.height(8.dp))
                Step(usage, "1. Allow usage access", "So TypePilot can tell when you leave the app while it's switched off.") {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                Step(canToggle, "2. One-time permission from a computer", "Android only lets an app switch its own accessibility on/off after this, run once over USB:", null)
                if (!canToggle) Row(Modifier.fillMaxWidth().padding(start = 30.dp, top = 4.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(A11ySwitch.ADB_COMMAND, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                    IconButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("adb", A11ySwitch.ADB_COMMAND))
                    }) { Icon(Icons.Default.ContentCopy, "Copy", Modifier.size(18.dp)) }
                }
            } else if (s.appMode == AppMode.ONLY_SELECTED) {
                Text("Apps you didn't pick are simply ignored. Accessibility is switched fully off only in Hidden apps, so it never gets stuck off.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun Step(done: Boolean, title: String, detail: String, onClick: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (done) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null, tint = if (done) Brand.Green else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!done && onClick != null) FilledTonalButton(onClick) { Text("Open", fontSize = 12.sp) }
    }
}

@Composable
private fun AppRow(a: AppEntry, state: AppState, hideAvailable: Boolean, onChange: (AppState) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
        .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(a.pkg)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(a.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(a.pkg, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Compact three-way control.
        Row(Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(2.dp)) {
            for (st in AppState.entries) {
                val enabled = st != AppState.HIDDEN || hideAvailable || state == AppState.HIDDEN
                val selected = state == st
                Text(st.label, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = when { selected -> Color.White; !enabled -> MaterialTheme.colorScheme.outlineVariant; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (selected) stateColor(st) else Color.Transparent)
                        .clickable(enabled = enabled) { onChange(st) }.padding(horizontal = 9.dp, vertical = 6.dp))
            }
        }
    }
}

private val iconCache = HashMap<String, ImageBitmap?>()

@Composable
private fun AppIcon(pkg: String) {
    val context = LocalContext.current
    val icon by produceState(iconCache[pkg], pkg) {
        if (value == null && !iconCache.containsKey(pkg)) {
            value = withContext(Dispatchers.IO) {
                runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
            }.also { iconCache[pkg] = it }
        }
    }
    val bmp = icon
    if (bmp != null) Image(bmp, null, Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)))
    else Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
}

