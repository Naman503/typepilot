package com.typepilot.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Brand {
    val Violet = Color(0xFF7C5CFF)
    val Pink = Color(0xFFEC4899)
    val Cyan = Color(0xFF22D3EE)
    val Green = Color(0xFF22C55E)
    val Amber = Color(0xFFF59E0B)
    val Red = Color(0xFFEF4444)
    val Blue = Color(0xFF3B82F6)
    val gradient = Brush.linearGradient(listOf(Violet, Pink))
    val chart = listOf(Violet, Cyan, Pink, Amber, Green, Blue, Red)
}

private val Light = lightColorScheme(
    primary = Brand.Violet, secondary = Brand.Pink, tertiary = Brand.Cyan,
    background = Color(0xFFF7F7FB), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFEFEFF6),
    surfaceContainer = Color(0xFFF1F1F8), surfaceContainerHigh = Color(0xFFEAEAF3),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFA996FF), secondary = Color(0xFFF472B6), tertiary = Brand.Cyan,
    background = Color(0xFF0E0E13), surface = Color(0xFF15151C), surfaceVariant = Color(0xFF22222C),
    surfaceContainer = Color(0xFF1A1A22), surfaceContainerHigh = Color(0xFF22222B),
)

private val Type = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

@Composable
fun TypePilotTheme(dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme: ColorScheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, typography = Type, content = content)
}
