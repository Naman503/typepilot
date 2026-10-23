package com.typepilot.android.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typepilot.android.TypePilotApp
import com.typepilot.android.ui.theme.Brand

/** Learning switches and the answer threshold, with an explanation of the percentage. Used in Memory and Settings. */
@Composable
fun MemorySettingsCard(startOpen: Boolean = false) {
    val app = TypePilotApp.instance
    val s by app.settings.state.collectAsState()
    var open by remember { mutableStateOf(startOpen) }
    var explain by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Memory settings", fontWeight = FontWeight.SemiBold)
                    Text((if (s.learnFacts) "Learning on" else "Learning off") + " · answers shown at ${s.factMinPercent}%+",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(top = 8.dp)) {
                    Line("Use memory", "Instant answers and phrases from what you've saved", s.memoryEnabled) { v -> app.settings.update { it.copy(memoryEnabled = v) } }
                    Line("Learn from what I type in forms", "Each field you fill is saved as question → answer", s.learnFacts && s.memoryEnabled, enabled = s.memoryEnabled) { v ->
                        app.settings.update { it.copy(learnFacts = v) }
                    }
                    Line("Learn from my corrections", "If you change an answer TypePilot filled in, it trusts that answer less and learns yours", s.learnCorrections,
                        enabled = s.memoryEnabled && s.learnFacts) { v -> app.settings.update { it.copy(learnCorrections = v) } }
                    Line("Learn sentences I write often", "Phrase completion while typing messages", s.learnFromTyping, enabled = s.memoryEnabled) { v ->
                        app.settings.update { it.copy(learnFromTyping = v) }
                    }
                    Line("Show the % on suggestions", "A small coloured badge next to each answer", s.factShowPercent) { v -> app.settings.update { it.copy(factShowPercent = v) } }
                    Line("Prefer answers from the same app", "What you typed in WhatsApp comes first in WhatsApp", s.preferSameApp) { v -> app.settings.update { it.copy(preferSameApp = v) } }
                    Line("Only in the app I typed it", "Strict: answers and phrases you typed stay in that app (documents and added facts work everywhere)", s.sameAppOnly) { v ->
                        app.settings.update { it.copy(sameAppOnly = v) }
                    }
                    Line("Also offer an AI answer", "For real questions, an AI answer is added under your saved answers", s.aiAlongsideMemory) { v -> app.settings.update { it.copy(aiAlongsideMemory = v) } }

                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Show an answer when it's at least", fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text("${s.factMinPercent}%", fontWeight = FontWeight.Bold, color = levelColor(s.factMinPercent), fontSize = 16.sp)
                    }
                    Slider(s.factMinPercent.toFloat(), { v -> app.settings.update { it.copy(factMinPercent = (v / 5).toInt() * 5) } }, valueRange = 30f..95f, steps = 12)
                    Row(Modifier.fillMaxWidth()) {
                        Text("More answers", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text("Only sure answers", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(levelText(s.factMinPercent), fontSize = 12.sp, color = levelColor(s.factMinPercent), modifier = Modifier.padding(top = 4.dp))

                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Offer a typed answer after", fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(if (s.factMinUses == 1) "1st time" else "${s.factMinUses} times", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(s.factMinUses.toFloat(), { v -> app.settings.update { it.copy(factMinUses = v.toInt()) } }, valueRange = 1f..5f, steps = 3)
                    Text("Answers from your documents or added by you are always offered.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().clickable { explain = !explain }, verticalAlignment = Alignment.CenterVertically) {
                        Text("How the % works", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                        Icon(if (explain) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    AnimatedVisibility(explain) {
                        Column(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("% = match × trust", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Match: how well the field's question fits the saved one. The same kind of field (\"Contact no.\" and \"Mobile number\" are both a phone) is 97%. Otherwise it's how many words and letters they share. Different kinds of field (email vs phone) are cut to a third, so they never mix up.", fontSize = 12.sp)
                            Text("Trust: how sure TypePilot is of the answer itself. It starts at 45% the first time you type something and goes up 15% each time you type it again. Answers from documents start at 60–95%, ones you add yourself at 95%. Using an answer adds 10% and closing it takes 10% away.", fontSize = 12.sp)
                            Text("Example: \"Contact no.\" vs your saved \"Phone\" (97% match) × a number you've typed 3 times (≈98% trust) ≈ 95%.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun levelText(p: Int) = when {
    p < 45 -> "Shows loose guesses too: expect some wrong answers."
    p < 60 -> "Balanced: answers that clearly fit the field (recommended)."
    p < 80 -> "Careful: only well-matched answers you've used before."
    else -> "Strict: only near-certain answers (same field, used often)."
}

@Composable
private fun levelColor(p: Int) = when { p < 45 -> Brand.Amber; p < 80 -> Brand.Green; else -> Brand.Cyan }

@Composable
private fun Line(title: String, sub: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, enabled = enabled)
    }
}
