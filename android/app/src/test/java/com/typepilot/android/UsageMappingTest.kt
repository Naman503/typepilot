package com.typepilot.android

import com.typepilot.android.core.AppMode
import com.typepilot.android.core.AppSettings
import com.typepilot.android.core.ProviderKind
import com.typepilot.android.core.WriteLength
import com.typepilot.android.telemetry.UsageMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class UsageMappingTest {
    @Test fun providerNeverSendsTheHost() {
        assertEquals("groq", UsageMapping.provider(ProviderKind.OPENAI, "https://api.groq.com/openai/v1"))
        assertEquals("openrouter", UsageMapping.provider(ProviderKind.OPENAI, "https://openrouter.ai/api/v1"))
        assertEquals("ollama", UsageMapping.provider(ProviderKind.OPENAI, "http://192.168.1.20:11434/v1"))
        assertEquals("openai_compatible", UsageMapping.provider(ProviderKind.OPENAI, "http://my-private-box.lan:8080/v1"))
        assertEquals("gemini", UsageMapping.provider(ProviderKind.GEMINI, ""))
        assertEquals("on_device", UsageMapping.provider(ProviderKind.LOCAL, ""))
        assertEquals("none", UsageMapping.provider(ProviderKind.OFF, ""))
    }

    @Test fun keyboardFamilyOnly() {
        assertEquals("gboard", UsageMapping.keyboard("com.google.android.inputmethod.latin", "com.typepilot.android"))
        assertEquals("typepilot", UsageMapping.keyboard("com.typepilot.android", "com.typepilot.android"))
        assertEquals("other", UsageMapping.keyboard("org.example.secretkeyboard", "com.typepilot.android"))
    }

    @Test fun sourcesAndModes() {
        assertEquals("memory", UsageMapping.source("fact"))
        assertEquals("phrase", UsageMapping.source("shortcut"))
        assertEquals("other", UsageMapping.source(null))
        assertEquals("best", UsageMapping.rewriteMode("BEST"))
        assertEquals("fix", UsageMapping.rewriteMode("GRAMMAR"))
        assertEquals("hinglish", UsageMapping.rewriteMode("HINGLISH"))
    }

    @Test fun counterKeysRoundTrip() {
        val key = UsageMapping.counterKey("2026-10-25", "suggestion_accepted", mapOf("source" to "ai"))
        assertEquals(Triple("2026-10-25", "suggestion_accepted", mapOf("source" to "ai")), UsageMapping.parseCounterKey(key))
        assertEquals(Triple("2026-10-25", "suggestions_shown", emptyMap<String, String>()), UsageMapping.parseCounterKey("2026-10-25|suggestions_shown"))
        assertNull(UsageMapping.parseCounterKey("garbage"))
    }

    @Test fun crashFingerprintIgnoresTheMessage() {
        fun boom(msg: String) = IllegalStateException(msg).apply {
            stackTrace = arrayOf(StackTraceElement("com.typepilot.android.service.Overlay", "show", "Overlay.kt", 10))
        }
        assertEquals(UsageMapping.crashFingerprint(boom("a")), UsageMapping.crashFingerprint(boom("user typed this")))
        assertNotEquals(UsageMapping.crashFingerprint(boom("a")), UsageMapping.crashFingerprint(NullPointerException()))
    }

    @Test fun bucketsAreFixedWords() {
        assertEquals("lt100ms", UsageMapping.latency(40))
        assertEquals("300_700ms", UsageMapping.latency(450))
        assertEquals("gt6s", UsageMapping.latency(12_000))
        assertEquals("none", UsageMapping.latency(0))
        assertEquals("lt10s", UsageMapping.duration(3_000))
        assertEquals("2_5m", UsageMapping.duration(200_000))
        assertEquals("gt60m", UsageMapping.duration(4_000_000))
        assertEquals("0", UsageMapping.count(0))
        assertEquals("11_50", UsageMapping.count(42))
        assertEquals("gt1000", UsageMapping.count(5000))
        assertEquals("700mb_1500mb", UsageMapping.sizeMb(1200))
    }

    @Test fun appCategoryNeverLeaksThePackage() {
        assertEquals("chat", UsageMapping.appCategory("com.whatsapp"))
        assertEquals("chat", UsageMapping.appCategory("org.telegram.messenger"))
        assertEquals("email", UsageMapping.appCategory("com.google.android.gm"))
        assertEquals("email", UsageMapping.appCategory("com.example.supermail"))
        assertEquals("browser", UsageMapping.appCategory("com.android.chrome"))
        assertEquals("browser", UsageMapping.appCategory("org.mozilla.firefox_beta"))
        assertEquals("typepilot", UsageMapping.appCategory("com.typepilot.android"))
        assertEquals("other", UsageMapping.appCategory("com.mybank.secret"))
        assertEquals("none", UsageMapping.appCategory(""))
    }

    @Test fun errorCategoriesAndDocTypes() {
        assertEquals("rate_limit", UsageMapping.errorCategory("RATE_LIMIT"))
        assertEquals("timeout", UsageMapping.errorCategory("TIMEOUT"))
        assertEquals("other", UsageMapping.errorCategory("Something the server said"))
        assertEquals("other", UsageMapping.errorCategory(null))
        assertEquals("pdf", UsageMapping.docType("application/pdf"))
        assertEquals("docx", UsageMapping.docType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertEquals("text", UsageMapping.docType("text/plain"))
        assertEquals("other", UsageMapping.docType("image/png"))
    }

    @Test fun settingChangesSendKeysAndFixedWordsOnly() {
        val a = AppSettings()
        val b = a.copy(
            haptics = false, aboutMe = "Jane Doe, CFO at SecretCorp", writeLength = WriteLength.SHORT,
            completionProvider = ProviderKind.OPENAI, openAIBaseUrl = "http://10.0.0.5:11434/v1", openAIModel = "my-private-model",
            geminiModel = "gemini-my-typed-name", excludedPackages = a.excludedPackages + "com.mybank.app", appMode = AppMode.ONLY_SELECTED,
            debounceMs = 300,
        )
        val changes = UsageMapping.settingChanges(a, b, suggestedGemini = listOf("gemini-3.5-flash-lite")).toMap()
        assertEquals("off", changes["haptics"])
        assertEquals("set", changes["about_me"])
        assertEquals("short", changes["write_length"])
        assertEquals("ollama", changes["completion_provider"])
        assertEquals("ollama", changes["openai_server"])
        assertEquals("changed", changes["openai_model"])
        assertEquals("custom", changes["gemini_model"])
        assertEquals("added", changes["excluded_apps"])
        assertEquals("only_selected", changes["app_mode"])
        assertEquals("changed", changes["debounce"])
        val all = changes.entries.joinToString { "${it.key}=${it.value}" }
        for (secret in listOf("Jane", "SecretCorp", "10.0.0.5", "my-private-model", "gemini-my-typed-name", "mybank")) assertFalse(all, all.contains(secret))
        assertTrue(UsageMapping.settingChanges(a, a).isEmpty())
    }

    @Test fun pausingIsReportedAsOnOff() {
        val now = 1_000_000L
        val paused = AppSettings().copy(pausedUntil = now + 3_600_000)
        assertEquals(listOf("paused" to "on"), UsageMapping.settingChanges(AppSettings(), paused, now))
        assertEquals(listOf("paused" to "off"), UsageMapping.settingChanges(paused, paused.copy(pausedUntil = 0), now))
    }

    @Test fun snapshotFitsTheApiAndHoldsNoUserText() {
        val s = AppSettings().copy(aboutMe = "Jane Doe", geminiModel = "typed-model", openAIBaseUrl = "https://secret.example.com/v1",
            includedPackages = setOf("com.secret.app"), localModelId = "some-model.litertlm")
        val snap = UsageMapping.settingsSnapshot(s, suggestedGemini = listOf("gemini-3.5-flash-lite"))
        val name = Regex("^[a-z][a-z0-9_]{0,47}$")
        val word = Regex("^[a-z0-9_]{1,64}$")
        for ((event, props) in snap) {
            assertTrue(event, name.matches(event))
            assertTrue("$event has ${props.size} props", props.size <= 8)
            for ((k, v) in props) {
                assertTrue("$event.$k", Regex("^[a-z][a-z0-9_]{0,31}$").matches(k))
                assertTrue("$event.$k=$v", word.matches(v))
            }
        }
        assertEquals("set", snap["settings_tuning"]!!["about_me"])
        assertEquals("custom", snap["settings_ai"]!!["gemini_model"])
    }

    @Test fun cleanPropsEnforcesTheApiRules() {
        val props = (1..12).associate { "k$it" to "v|=$it" } + ("Bad-Key" to "x") + ("long" to "y".repeat(100))
        val clean = UsageMapping.cleanProps(props)
        assertTrue(clean.size <= 8)
        assertFalse(clean.containsKey("Bad-Key"))
        assertTrue(clean.values.all { it.length <= 64 && '|' !in it && '=' !in it })
        val key = UsageMapping.counterKey("2026-10-25", "x", UsageMapping.cleanProps(mapOf("a" to "b|c=d")))
        assertEquals(mapOf("a" to "b_c_d"), UsageMapping.parseCounterKey(key)!!.third)
    }
}
