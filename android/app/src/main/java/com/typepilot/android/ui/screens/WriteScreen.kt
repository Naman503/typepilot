package com.typepilot.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.typepilot.android.engine.FieldContext
import com.typepilot.android.rewrite.RewriteBridge
import com.typepilot.android.rewrite.RewritePanel

/** The rewrite / write tools inside the app: paste text in, copy or share the result out. */
@Composable
fun WriteScreen() {
    Column(Modifier.padding(16.dp)) {
        Text("Write", style = MaterialTheme.typography.headlineMedium)
        Text("Also in any app: tap ✦ next to a field, or select text → Rewrite with TypePilot.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        RewritePanel("", FieldContext(appName = "TypePilot"), RewriteBridge.Tab.REWRITE, actionLabel = null, onApply = null, onClose = null,
            modifier = Modifier.padding(top = 12.dp))
    }
}
