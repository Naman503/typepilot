package com.typepilot.android.service

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import com.typepilot.android.core.Log

/**
 * Turns TypePilot's accessibility service fully off and on, so payment and banking apps (which refuse to run while
 * an unknown accessibility service is enabled) never see it.
 *
 * Needs two one-time permissions: WRITE_SECURE_SETTINGS (granted once from a computer with adb; it lets us edit
 * the enabled-services list) and Usage access (to notice which app is open while the service is off).
 */
object A11ySwitch {
    const val ADB_COMMAND = "adb shell pm grant com.typepilot.android android.permission.WRITE_SECURE_SETTINGS"

    private fun component(c: Context) = ComponentName(c, TypePilotAccessibilityService::class.java)

    fun canToggle(c: Context) = c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun hasUsageAccess(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
    }

    fun ready(c: Context) = canToggle(c) && hasUsageAccess(c)

    private fun enabledList(c: Context) = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        .split(':').filter { it.isNotBlank() }

    private fun isOurs(c: Context, entry: String) = ComponentName.unflattenFromString(entry) == component(c)

    fun isOn(c: Context) = enabledList(c).any { isOurs(c, it) }

    fun turnOn(c: Context): Boolean = runCatching {
        if (isOn(c)) return true
        val list = enabledList(c).filterNot { isOurs(c, it) } + component(c).flattenToString()
        Settings.Secure.putString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
        Settings.Secure.putInt(c.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        Log.i("switch", "accessibility on")
        true
    }.getOrElse { Log.e("switch", "couldn't turn accessibility on", it); false }

    /** Other accessibility services (screen readers etc.) are left exactly as they were. */
    fun turnOff(c: Context): Boolean = runCatching {
        val rest = enabledList(c).filterNot { isOurs(c, it) }
        Settings.Secure.putString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, rest.joinToString(":"))
        if (rest.isEmpty()) Settings.Secure.putInt(c.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        Log.i("switch", "accessibility off")
        true
    }.getOrElse { Log.e("switch", "couldn't turn accessibility off", it); false }

    /** The app in front right now, from usage events (works while our accessibility service is off). */
    fun foregroundPackage(c: Context): String? {
        val usm = c.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 15_000, now)
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
        }
        return last
    }
}

/** Payment, UPI, banking, trading and wallet apps: these usually block accessibility services. */
object PaymentApps {
    private val known = setOf(
        "com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp", "com.dreamplug.androidapp",
        "com.mobikwik_new", "com.freecharge.android", "com.sbi.lotusintouch", "com.sbi.SBIFreedomPlus", "com.sbi.upi", "com.snapwork.hdfc",
        "com.hdfcbank.payzapp", "com.csam.icici.bank.imobile", "com.icicibank.pockets", "com.axis.mobile", "com.msf.kbank.mobile",
        "com.kotak811mobilebankingapp", "com.bankofbaroda.mconnect", "com.infrasofttech.indianBank", "com.canarabank.mobility",
        "com.idfcfirstbank.optimus", "com.yesbank", "com.zerodha.kite3", "com.nextbillion.groww", "in.upstox.app", "com.angelbroking.angelapp",
        "com.whatsapp.w4b.payments", "com.amazon.mShop.android.shopping.pay", "com.fampay.in", "com.jupiter.money", "com.fi.money",
    )
    private val pattern = Regex("""\b(pay|upi|bank|banking|wallet|money|finance|credit|loan|trade|trading|invest|stock|broker|mutual fund|insurance)\b""", RegexOption.IGNORE_CASE)
    private val pkgPattern = Regex("""(bank|upi|paisa|wallet|trade|kite|groww|upstox|payzapp|imobile|yono|lotusintouch)""", RegexOption.IGNORE_CASE)

    fun isLikely(pkg: String, label: String) = pkg in known || pattern.containsMatchIn(label) || pkgPattern.containsMatchIn(pkg)
}
