package com.typepilot.android

import com.typepilot.android.core.ProviderKind
import com.typepilot.android.telemetry.UsageMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
}
