package com.typepilot.android.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.typepilot.android.ime.TypePilotIme
import com.typepilot.android.service.TypePilotAccessibilityService

/** Checks and shortcuts for the one-time setup. */
object Setup {
    fun accessibilityOn(c: Context): Boolean {
        if (TypePilotAccessibilityService.isRunning) return true
        val enabled = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.split(':').any { it.startsWith(c.packageName + "/") }
    }

    fun keyboardEnabled(c: Context) = c.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == c.packageName }
    fun keyboardSelected(c: Context) = Settings.Secure.getString(c.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty().startsWith(c.packageName + "/")
    fun batteryUnrestricted(c: Context) = c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

    /** OEMs that kill background apps aggressively and need an extra toggle (dontkillmyapp.com). */
    val oem: String = Build.MANUFACTURER.lowercase()
    val aggressiveOem = oem in setOf("vivo", "iqoo", "oppo", "realme", "oneplus", "xiaomi", "redmi", "poco", "huawei", "honor", "samsung", "tecno", "infinix", "motorola")

    fun oemSteps(): String = when (oem) {
        "vivo", "iqoo" -> "Settings → Battery → Background power consumption management → TypePilot → Allow. Also lock TypePilot in Recents (swipe down on its card)."
        "oppo", "realme", "oneplus" -> "Settings → Battery → App battery management → TypePilot → Allow background activity, and turn on Auto-launch."
        "xiaomi", "redmi", "poco" -> "Settings → Apps → TypePilot → Battery saver → No restrictions, and turn on Autostart."
        "samsung" -> "Settings → Battery → Background usage limits → Never sleeping apps → add TypePilot."
        "huawei", "honor" -> "Settings → Battery → App launch → TypePilot → Manage manually (allow all three)."
        else -> "Settings → Apps → TypePilot → Battery → Unrestricted."
    }

    fun openAccessibility(c: Context) {
        val component = ComponentName(c, TypePilotAccessibilityService::class.java).flattenToString()
        val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, component)
        val ok = runCatching { c.startActivity(details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (!ok) c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openAppDetails(c: Context) = c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun openKeyboardSettings(c: Context) = c.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    fun pickKeyboard(c: Context) = c.getSystemService(InputMethodManager::class.java).showInputMethodPicker()

    @SuppressLint("BatteryLife")
    fun requestBattery(c: Context) {
        val ok = runCatching {
            c.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!ok) openAppDetails(c)
    }

    val isKeyboardActiveNow get() = TypePilotIme.isActiveInput
}
