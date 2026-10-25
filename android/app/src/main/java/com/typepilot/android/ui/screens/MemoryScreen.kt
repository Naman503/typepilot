package com.typepilot.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.typepilot.android.TypePilotApp
import com.typepilot.android.knowledge.Fact
import com.typepilot.android.knowledge.FieldMatcher
import com.typepilot.android.memory.MemoryEntry
import com.typepilot.android.ui.components.Pill
import com.typepilot.android.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Memory: facts (instant answers with %), imported documents, and phrases you write often. */
@Composable
fun MemoryScreen() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val app = TypePilotApp.instance
    val factsVersion by app.facts.version.collectAsState()
    val memVersion by app.memory.version.collectAsState()
    val counts by produceState(Triple(0, 0, 0), factsVersion, memVersion) {
        value = withContext(Dispatchers.IO) { Triple(app.facts.all().size, app.facts.docs().size, app.memory.count()) }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            Text("Memory", style = MaterialTheme.typography.headlineMedium)
            Text("Instant answers from what you've filled and the documents you add. Stored only on this phone.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        PrimaryTabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 8.dp)) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Facts · ${counts.first}") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Documents · ${counts.second}") })
            Tab(tab == 2, { tab = 2 }, text = { Text("Phrases · ${counts.third}") })
        }
        LaunchedEffect(tab) { com.typepilot.android.telemetry.Usage.count("screen_view", mapOf("screen" to listOf("memory_facts", "memory_documents", "memory_phrases").getOrElse(tab) { "memory_facts" })) }
        when (tab) {
            0 -> FactsTab()
            1 -> DocumentsTab()
            else -> PhrasesTab()
        }
    }
}

@Composable
private fun FactsTab() {
    val app = TypePilotApp.instance
    val version by app.facts.version.collectAsState()
    val facts by produceState(emptyList<Fact>(), version) { value = withContext(Dispatchers.IO) { app.facts.all() } }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Fact?>(null) }
    var adding by remember { mutableStateOf(false) }
    val settings by app.settings.state.collectAsState()
    val matches = remember(query, facts) {
        if (query.isBlank()) emptyList() else (listOfNotNull(FieldMatcher.derived(query, facts)) + FieldMatcher.match(query, facts, limit = 5, minPercent = 25)).sortedByDescending { it.percent }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { MemorySettingsCard() }
            item {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("Try a question", fontWeight = FontWeight.SemiBold)
                        Text("Type what a form might ask. This is exactly what TypePilot shows when that field opens, with no AI.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp),
                            leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("e.g. Mobile no.* or What is your notice period?") })
                        for (m in matches) Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            PercentBadge(m.percent)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(m.fact.value, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${m.fact.key} · ${m.reason} · match ${m.match}% × trust ${m.trust}%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (m.percent < settings.factMinPercent) Text("Below your ${settings.factMinPercent}% threshold: won't be shown", fontSize = 11.sp, color = Brand.Amber)
                            }
                        }
                        if (query.isNotBlank() && matches.isEmpty()) Text("No match yet: fill it once in any app, or add a document.", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            if (facts.isEmpty()) item {
                Text("No facts yet. Every short field you fill (name, email, notice period…) is remembered here, and grows more certain each time. Add a resume or profile in Documents to fill this instantly.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
            items(facts, key = { it.id }) { f ->
                Surface(onClick = { editing = f }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        PercentBadge((f.confidence * 100).toInt())
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.key, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(f.value, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(buildString {
                                append(when (f.source) { "doc" -> "from a document"; "manual" -> "added by you"; else -> "typed" })
                                if (f.uses > 0) append(" · used ${f.uses}×")
                                if (f.aliases.isNotEmpty()) append(" · also: ${f.aliases.take(3).joinToString()}")
                            }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
        ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add fact") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp))
    }
    editing?.let { f ->
        FactDialog(f.key, f.value, f.aliases.joinToString(", "), onDismiss = { editing = null }, onDelete = { com.typepilot.android.telemetry.Usage.count("memory_edit", mapOf("item" to "fact", "action" to "delete")); app.facts.delete(f.id); editing = null }) { k, v, a ->
            com.typepilot.android.telemetry.Usage.count("memory_edit", mapOf("item" to "fact", "action" to "edit")); app.facts.update(f.id, k, v, a); editing = null
        }
    }
    if (adding) FactDialog("", "", "", onDismiss = { adding = false }, onDelete = null) { k, v, a ->
        com.typepilot.android.telemetry.Usage.count("memory_edit", mapOf("item" to "fact", "action" to "add")); app.facts.upsert(k, v, "manual", a, confidence = 0.95); adding = false
    }
}

@Composable
fun PercentBadge(pct: Int) {
    val color = when { pct >= 85 -> Brand.Green; pct >= 65 -> Brand.Cyan; pct >= 45 -> Brand.Amber; else -> MaterialTheme.colorScheme.outline }
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        Text("$pct%", color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun FactDialog(key0: String, value0: String, aliases0: String, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (String, String, List<String>) -> Unit) {
    var key by remember { mutableStateOf(key0) }
    var value by remember { mutableStateOf(value0) }
    var aliases by remember { mutableStateOf(aliases0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (onDelete == null) "Add a fact" else "Edit fact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(key, { key = it }, label = { Text("Field or question (e.g. Notice period)") }, singleLine = true)
                OutlinedTextField(value, { value = it }, label = { Text("Your answer") }, minLines = 2)
                OutlinedTextField(aliases, { aliases = it }, label = { Text("Other ways it's asked (comma separated)") })
            }
        },
        confirmButton = { Button(onClick = { onSave(key.trim(), value.trim(), aliases.split(',').map { it.trim() }.filter { it.isNotEmpty() }) },
            enabled = key.isNotBlank() && value.isNotBlank()) { Text("Save") } },
        dismissButton = {
            Row {
                if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = Brand.Red) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun DocumentsTab() {
    val app = TypePilotApp.instance
    val context = LocalContext.current
    val version by app.facts.version.collectAsState()
    val progress by app.knowledge.progress.collectAsState()
    val docs by produceState(emptyList<com.typepilot.android.knowledge.KnowledgeDoc>(), version, progress) { value = withContext(Dispatchers.IO) { app.facts.docs() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) com.typepilot.android.telemetry.Usage.count("memory_edit", mapOf("item" to "document", "action" to "add"), uris.size)
        for (u in uris) app.knowledge.import(u) { msg -> android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() } }
    }
    val aiName = remember { app.engine.writingModelOrNull()?.name }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Add your resume, profile or notes", fontWeight = FontWeight.SemiBold)
                    Text("PDF, Word (.docx), text or Markdown. TypePilot pulls out facts (name, contacts, work, education, skills…) and likely answers to common questions, so forms fill instantly without waiting for AI.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (aiName != null) "Reading with: $aiName" else "No AI set up: only basic facts (email, phone, links, \"Label: value\" lines) will be found.",
                        fontSize = 11.sp, color = if (aiName != null) Brand.Green else Brand.Amber, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { picker.launch(arrayOf("application/pdf", "text/*", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/json")) }) {
                        Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("Add documents")
                    }
                }
            }
        }
        items(docs, key = { it.id }) { d ->
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${if (d.chars >= 1000) "${d.chars / 1000}k" else "${d.chars}"} characters · ${d.facts} facts · ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(d.added))}",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { com.typepilot.android.telemetry.Usage.count("memory_edit", mapOf("item" to "document", "action" to "reread")); app.knowledge.reExtract(d.id) }) { Icon(Icons.Default.Refresh, "Read again") }
                        IconButton(onClick = { com.typepilot.android.telemetry.Usage.count("memory_edit", mapOf("item" to "document", "action" to "delete")); app.facts.deleteDoc(d.id) }) { Icon(Icons.Default.Delete, "Delete", tint = Brand.Red) }
                    }
                    val p = progress[d.id]
                    if (p != null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                        Text(p, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    } else Text(d.error ?: d.status, fontSize = 11.sp, color = if (d.error != null) Brand.Red else Brand.Green)
                }
            }
        }
    }
}

@Composable
private fun PhrasesTab() {
    val app = TypePilotApp.instance
    val version by app.memory.version.collectAsState()
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MemoryEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    val entries by produceState(emptyList<MemoryEntry>(), version) { value = withContext(Dispatchers.IO) { app.memory.all() } }
    val shown = entries.filter { query.isBlank() || it.text.contains(query, true) || it.field.contains(query, true) || it.shortcut.contains(query, true) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp),
                    leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Search phrases") })
                Text("Tip: give an entry a shortcut like \"addr\", then type ;addr in any app to expand it.", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            items(shown, key = { it.id }) { e ->
                Surface(onClick = { editing = e }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(e.text, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                        Spacer(Modifier.padding(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (e.shortcut.isNotEmpty()) Pill(";${e.shortcut}", Brand.Amber)
                            if (e.field.isNotEmpty()) Pill(e.field.take(20), MaterialTheme.colorScheme.primary)
                            Text("×${e.count} · ${e.app.take(16)} · ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(e.lastUsed))}",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
                            IconButton(onClick = { com.typepilot.android.telemetry.Usage.count("memory_edit", mapOf("item" to "phrase", "action" to if (e.pinned) "unpin" else "pin")); app.memory.setPinned(e.id, !e.pinned) }) {
                                Icon(if (e.pinned) Icons.Default.PushPin else Icons.Outlined.PushPin, "Pin", tint = if (e.pinned) Brand.Violet else MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
            if (entries.isEmpty()) item {
                Text("Nothing yet. TypePilot learns sentences you type often (never passwords, codes or card numbers).",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
        ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add phrase") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp))
    }
    editing?.let { e ->
        PhraseDialog(e.text, e.shortcut, e.field, onDismiss = { editing = null }, onDelete = { com.typepilot.android.telemetry.Usage.count("memory_edit", mapOf("item" to "phrase", "action" to "delete")); app.memory.delete(e.id); editing = null }) { text, sc, _ ->
            if (text != e.text) { com.typepilot.android.telemetry.Usage.count("memory_edit", mapOf("item" to "phrase", "action" to "edit")); app.memory.update(e.id, text) }
            if (sc != e.shortcut) { com.typepilot.android.telemetry.Usage.count("memory_edit", mapOf("item" to "phrase", "action" to if (sc.isBlank()) "shortcut_removed" else "shortcut_set")); app.memory.setShortcut(e.id, sc) }
            editing = null
        }
    }
    if (adding) PhraseDialog("", "", "", onDismiss = { adding = false }, onDelete = null) { text, sc, field ->
        com.typepilot.android.telemetry.Usage.event("phrase_saved", mapOf("shortcut" to sc.isNotBlank().toString(), "field" to field.isNotBlank().toString()))
        app.memory.insertManual(text, field, sc); adding = false
    }
}

@Composable
private fun PhraseDialog(text0: String, shortcut0: String, field0: String, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (String, String, String) -> Unit) {
    var text by remember { mutableStateOf(text0) }
    var shortcut by remember { mutableStateOf(shortcut0) }
    var field by remember { mutableStateOf(field0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (onDelete == null) "Add a phrase" else "Edit phrase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text("Text") }, minLines = 2)
                OutlinedTextField(shortcut, { shortcut = it }, label = { Text("Shortcut (type ;shortcut)") }, singleLine = true)
                if (onDelete == null) OutlinedTextField(field, { field = it }, label = { Text("Field it's for (optional)") }, singleLine = true)
            }
        },
        confirmButton = { Button(onClick = { onSave(text.trim(), shortcut.trim(), field.trim()) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = {
            Row {
                if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete", tint = Brand.Red) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

