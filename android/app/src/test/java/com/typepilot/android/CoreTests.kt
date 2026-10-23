package com.typepilot.android

import com.typepilot.android.memory.MemoryEntry
import com.typepilot.android.memory.MemoryStore
import com.typepilot.android.memory.PhraseCompleter
import com.typepilot.android.metrics.Percentiles
import com.typepilot.android.rewrite.WordDiff
import com.typepilot.android.text.ContinuationCleaner
import com.typepilot.android.text.DraftCleaner
import com.typepilot.android.text.SensitiveFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveFilterTest {
    @Test fun detectsCardsAndKeys() {
        assertTrue(SensitiveFilter.containsSecret("my card 4111 1111 1111 1111 ok"))
        assertTrue(SensitiveFilter.containsSecret("key " + "AI" + "za" + "SyA1234567890abcdefghijklmnopqrstuv"))
        assertTrue(SensitiveFilter.containsSecret("aadhaar 1234 5678 9012"))
        assertTrue(SensitiveFilter.containsSecret("PAN ABCDE1234F"))
        assertFalse(SensitiveFilter.containsSecret("call me at 5pm about order 4521"))
    }

    @Test fun aadhaarPatternDoesNotMatchCardNumbers() {
        // 16 digits in 4-4-4-4 is not a 12-digit Aadhaar (and this one fails Luhn).
        assertFalse(SensitiveFilter.containsSecret("1234 5678 9012 3456"))
    }

    @Test fun redactsBeforeCloud() {
        val out = SensitiveFilter.redact("pay with 4111 1111 1111 1111 please")
        assertTrue(out.contains("[card]"))
        assertFalse(out.contains("4111"))
    }

    @Test fun otpsAndLabels() {
        assertTrue(SensitiveFilter.looksLikeOneTimeCode("482913"))
        assertTrue(SensitiveFilter.isSensitiveLabel("Enter OTP"))
        assertTrue(SensitiveFilter.isSensitiveLabel("UPI PIN"))
        assertFalse(SensitiveFilter.isSensitiveLabel("Message"))
    }
}

class ContinuationCleanerTest {
    @Test fun stripsPreambleAndQuotes() {
        assertEquals("I'll send it by Friday.", ContinuationCleaner.clean("Sure, here's a continuation:\n\"I'll send it by Friday.\"", "Thanks! ", 10))
    }

    @Test fun removesEchoOfUsersText() {
        assertEquals(" for the delay in delivery", ContinuationCleaner.clean("refund for the delay in delivery", "I'd like a refund", 10))
    }

    @Test fun noLeadingSpaceAfterSpaceOrEmpty() {
        assertEquals("tomorrow", ContinuationCleaner.clean(" tomorrow", "See you ", 5))
        assertEquals("Hello there", ContinuationCleaner.clean(" Hello there", "", 5))
    }

    @Test fun addsSpaceAfterPunctuation() {
        assertEquals(" Looking forward", ContinuationCleaner.clean("Looking forward", "Thanks.", 5))
    }

    @Test fun rejectsMetaTalk() {
        assertNull(ContinuationCleaner.clean("Here's a suggestion for the user", "Hi", 10))
        // A natural reply that happens to start with "Sure" is fine.
        assertEquals("Sure, I'll take a look", ContinuationCleaner.clean("Sure, I'll take a look", "", 10))
    }

    @Test fun limitsWordsAndStripsThinking() {
        assertEquals("one two three", ContinuationCleaner.clean("<think>hmm</think>one two three four five", "x ", 3))
    }

    @Test fun firstLineOnlyForSingleLineFields() {
        assertEquals(" and then more", ContinuationCleaner.clean(" and then more\nsecond line", "this", 10))
    }
}

class DraftCleanerTest {
    @Test fun removesPlaceholdersAndEchoes() {
        val raw = "Hi [Name],\n\nThanks for your order [Order Number].\nBest,\n[Your Name]"
        assertEquals("Hi,\n\nThanks for your order.\nBest,", DraftCleaner.clean(raw))
    }

    @Test fun cutsEchoedInstruction() {
        assertEquals("Fixed text here.", DraftCleaner.clean("Fixed text here.\nInstruction: fix grammar"))
    }
}

class PhraseCompleterTest {
    private fun entry(text: String, count: Int = 2, field: String = "", shortcut: String = "", kind: MemoryEntry.Kind = MemoryEntry.Kind.TYPED) =
        MemoryEntry(1, text, "App", field, kind, count, System.currentTimeMillis(), false, shortcut)

    @Test fun continuesRepeatedPhrase() {
        val p = PhraseCompleter().apply { rebuild(listOf(entry("Thanks for reaching out, I'll get back to you soon."))) }
        assertEquals(" I'll get back to", p.suggest("Thanks for reaching out,", "", maxWords = 4)?.text)
    }

    @Test fun oneOffMessagesAreNotResuggested() {
        val p = PhraseCompleter().apply { rebuild(listOf(entry("Thanks for reaching out, I'll get back to you soon.", count = 1))) }
        assertNull(p.suggest("Thanks for reaching out,", ""))
    }

    @Test fun recallsUsualFieldValue() {
        val p = PhraseCompleter().apply { rebuild(listOf(entry("221B Baker Street, London", field = "Address"))) }
        val hit = p.suggest("", "Address:")
        assertEquals("221B Baker Street, London", hit?.text)
        assertEquals("recall", hit?.kind)
        // Never recall into chat boxes.
        assertNull(p.suggest("", "Type a message"))
    }

    @Test fun expandsShortcuts() {
        val p = PhraseCompleter().apply { rebuild(listOf(entry("221B Baker Street", shortcut = "addr", count = 1, kind = MemoryEntry.Kind.MANUAL))) }
        val hit = p.suggest("ship to ;addr", "")
        assertEquals("221B Baker Street", hit?.text)
        assertEquals(5, hit?.replaceLength)
        assertEquals("221B Baker Street", p.suggest(";ad", "")?.text) // unique prefix
    }

    @Test fun phrasesPreferTheSameApp() {
        val wa = MemoryEntry(1, "On my way, see you in 10 minutes", "WhatsApp", "", MemoryEntry.Kind.TYPED, 3, System.currentTimeMillis(), false)
        val mail = MemoryEntry(2, "On my way to the office for the review", "Gmail", "", MemoryEntry.Kind.TYPED, 5, System.currentTimeMillis(), false)
        val p = PhraseCompleter().apply { rebuild(listOf(wa, mail)) }
        assertEquals(" see you in 10", p.suggest("On my way,", "", maxWords = 4, app = "WhatsApp")?.text)
        // Strict: Gmail's phrase doesn't appear in WhatsApp.
        assertNull(p.suggest("On my way to", "", maxWords = 4, app = "WhatsApp", sameAppOnly = true))
        assertEquals(" the office for the", p.suggest("On my way to", "", maxWords = 4, app = "Gmail", sameAppOnly = true)?.text)
    }

    @Test fun worthRemembering() {
        assertFalse(MemoryStore.isWorthRemembering("482913"))
        assertFalse(MemoryStore.isWorthRemembering("hi"))
        assertFalse(MemoryStore.isWorthRemembering("my pin", field = "PIN"))
        assertTrue(MemoryStore.isWorthRemembering("See you at the office tomorrow"))
        assertEquals("addr", MemoryStore.normalizeShortcut(" ;Addr "))
    }
}

class WordDiffTest {
    @Test fun marksOnlyChangedWords() {
        val marked = WordDiff.mark("i has a apple", "I have an apple").filter { it.first.isNotBlank() }
        assertEquals(listOf("I" to true, "have" to true, "an" to true, "apple" to false), marked)
        assertEquals(1, WordDiff.changes("i has a apple", "I have an apple"))
        assertEquals(0, WordDiff.changes("same text", "same text"))
    }
}

class PercentilesTest {
    @Test fun computesPercentiles() {
        val p = Percentiles.of((1L..100L).toList())
        assertEquals(50L, p.p50)
        assertEquals(90L, p.p90)
        assertEquals(99L, p.p99)
        assertEquals(100, p.count)
        assertEquals(Percentiles.EMPTY, Percentiles.of(LongArray(0)))
    }
}

class EchoedWordTest {
    @Test fun dropsRepeatedLastWord() {
        assertEquals(" will be there.", ContinuationCleaner.clean(" I will be there.", "Thanks for the invite. I", 8))
        // A real continuation of a finished word keeps its text.
        assertEquals(" love it", ContinuationCleaner.clean(" love it", "I", 8))
    }
}

class ScaffoldEchoTest {
    @Test fun rejectsEchoedInstructionsAndPunctuation() {
        assertNull(ContinuationCleaner.clean("Continue with at most 8 words.", "I ", 8))
        assertNull(ContinuationCleaner.clean(".", "I will", 8))
        assertNull(ContinuationCleaner.clean(" ...", "I will", 8))
    }
}

class SnakeCaseTest {
    @Test fun fixesSnakeCaseAnswers() {
        assertEquals("me and him went to the market", DraftCleaner.clean("me_and_him_went_to_the_market"))
        assertEquals("Use my_var here.", DraftCleaner.clean("Use my_var here."))
    }
}
