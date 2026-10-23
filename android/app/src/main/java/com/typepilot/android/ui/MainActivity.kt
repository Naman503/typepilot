package com.typepilot.android.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.typepilot.android.ui.screens.HomeScreen
import com.typepilot.android.ui.screens.InsightsScreen
import com.typepilot.android.ui.screens.MemoryScreen
import com.typepilot.android.ui.screens.SettingsScreen
import com.typepilot.android.ui.screens.WriteScreen
import com.typepilot.android.ui.theme.TypePilotTheme

class MainActivity : ComponentActivity() {
    private var tabState = mutableStateOf(TAB_HOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        intent?.getStringExtra(EXTRA_TAB)?.let { tabState.value = it }
        setContent {
            TypePilotTheme {
                var tab by tabState
                Scaffold(bottomBar = {
                    NavigationBar {
                        for ((id, label, icon) in tabs) {
                            NavigationBarItem(selected = tab == id, onClick = { tab = id }, icon = { Icon(icon, label) }, label = { Text(label) })
                        }
                    }
                }) { pad ->
                    Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()) {
                        when (tab) {
                            TAB_HOME -> HomeScreen(onOpen = { tab = it })
                            TAB_INSIGHTS -> InsightsScreen()
                            TAB_WRITE -> WriteScreen()
                            TAB_MEMORY -> MemoryScreen()
                            else -> SettingsScreen()
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_TAB)?.let { tabState.value = it }
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_HOME = "home"
        const val TAB_INSIGHTS = "insights"
        const val TAB_WRITE = "write"
        const val TAB_MEMORY = "memory"
        const val TAB_SETTINGS = "settings"
        private val tabs = listOf(
            Triple(TAB_HOME, "Home", Icons.Default.Home),
            Triple(TAB_INSIGHTS, "Insights", Icons.Default.Insights),
            Triple(TAB_WRITE, "Write", Icons.Default.AutoAwesome),
            Triple(TAB_MEMORY, "Memory", Icons.Default.Psychology),
            Triple(TAB_SETTINGS, "Settings", Icons.Default.Tune),
        )
    }
}
