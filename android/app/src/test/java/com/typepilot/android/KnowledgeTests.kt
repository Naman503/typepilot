package com.typepilot.android

import com.typepilot.android.knowledge.Fact
import com.typepilot.android.knowledge.FactExtractor
import com.typepilot.android.knowledge.FieldMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldMatcherTest {
    private var nextId = 1L
    private fun fact(key: String, value: String, conf: Double = 0.8, aliases: List<String> = emptyList()) =
        Fact(nextId++, key, value, aliases, "typed", 0, conf, 0, 0, 0)

    private val facts = listOf(
        fact("Email", "naman@example.com"),
        fact("Mobile number", "+91 98765 43210"),
        fact("Full name", "Naman Pathak"),
        fact("Notice period", "30 days"),
        fact("Current CTC", "12 LPA"),
        fact("Why are you looking for a change?", "I want to work on larger products.", aliases = listOf("Reason for change")),
    )

    @Test fun matchesSynonymsAcrossWording() {
        assertEquals("+91 98765 43210", FieldMatcher.match("Contact no.*", facts).first().fact.value)
        assertEquals("naman@example.com", FieldMatcher.match("E-mail ID", facts).first().fact.value)
        assertEquals("12 LPA", FieldMatcher.match("What is your current salary?", facts).first().fact.value)
        assertTrue(FieldMatcher.match("Contact no.*", facts).first().percent >= 75)
    }

    @Test fun differentConceptsDontMatch() {
        // An email question must never be answered with a phone number.
        assertTrue(FieldMatcher.match("Email address", facts).none { it.fact.value.startsWith("+91") })
    }

    @Test fun questionsMatchByWords() {
        assertEquals("I want to work on larger products.", FieldMatcher.match("Reason for job change", facts, minPercent = 30).first().fact.value)
    }

    @Test fun typedPrefixFiltersAndCompletes() {
        val m = FieldMatcher.match("Email", facts, typed = "nam")
        assertEquals("naman@example.com", m.first().fact.value)
        assertTrue(FieldMatcher.match("Email", facts, typed = "xyz").isEmpty())
    }

    @Test fun splitsFullNameForFirstAndLast() {
        assertEquals("Naman", FieldMatcher.derived("First name", facts)?.fact?.value)
        assertEquals("Pathak", FieldMatcher.derived("Surname", facts)?.fact?.value)
        assertNull(FieldMatcher.derived("City", facts))
    }

    @Test fun thresholdAndRepeatRules() {
        val once = Fact(99, "Company", "Acme", emptyList(), "typed", 0, 0.45, 0, 0, 0)
        // Typed once: ≈ 97% match × 75% trust ≈ 72%.
        val m = FieldMatcher.match("Employer", listOf(once)).single()
        assertTrue(m.percent in 65..80)
        assertEquals(97, m.match)
        assertTrue(FieldMatcher.match("Employer", listOf(once), minPercent = 85).isEmpty())
        // "Offer after 2 times": a once-typed answer waits; a document answer doesn't.
        assertTrue(FieldMatcher.match("Employer", listOf(once), minUses = 2).isEmpty())
        assertEquals(1, FieldMatcher.match("Employer", listOf(once.copy(source = "doc")), minUses = 2).size)
        // Closing it lowers trust.
        val rejected = once.copy(rejects = 2)
        assertTrue(FieldMatcher.match("Employer", listOf(rejected), minPercent = 0).single().percent < m.percent)
    }

    @Test fun sameAppAnswersWin() {
        val wa = Fact(201, "Address", "Flat 4, Indiranagar", emptyList(), "typed", 0, 0.6, 1, 0, 0, apps = listOf("com.whatsapp"))
        val gmail = Fact(202, "Address", "Office 12, MG Road", emptyList(), "typed", 0, 0.6, 1, 0, 0, apps = listOf("com.google.android.gm"))
        val inWhatsApp = FieldMatcher.match("Address", listOf(wa, gmail), app = "com.whatsapp")
        assertEquals("Flat 4, Indiranagar", inWhatsApp.first().fact.value)
        assertEquals(2, inWhatsApp.size) // the other one is still offered, lower
        val inGmail = FieldMatcher.match("Address", listOf(wa, gmail), app = "com.google.android.gm")
        assertEquals("Office 12, MG Road", inGmail.first().fact.value)
        // Strict mode: only this app's answers (document facts would still show).
        assertEquals(listOf("Flat 4, Indiranagar"), FieldMatcher.match("Address", listOf(wa, gmail), app = "com.whatsapp", sameAppOnly = true).map { it.fact.value })
        val doc = wa.copy(id = 203, value = "From resume", source = "doc", apps = emptyList())
        assertTrue(FieldMatcher.match("Address", listOf(doc), app = "com.whatsapp", sameAppOnly = true).isNotEmpty())
    }

    @Test fun conceptDetection() {
        assertEquals("phone", FieldMatcher.concept("WhatsApp number"))
        assertEquals("pincode", FieldMatcher.concept("PIN code"))
        assertEquals("expected_ctc", FieldMatcher.concept("Expected CTC (in LPA)"))
    }
}

class FactExtractorTest {
    @Test fun heuristicFindsContactsAndLabels() {
        val text = "Naman Pathak\nEmail: naman@example.com\nPhone: +91 98765 43210\nlinkedin.com/in/naman\nNotice Period: 30 days"
        val facts = FactExtractor.heuristic(text)
        assertTrue(facts.any { it.key == "Email" && it.value == "naman@example.com" })
        assertTrue(facts.any { it.key == "Phone" })
        assertTrue(facts.any { it.key == "LinkedIn" })
        assertTrue(facts.any { it.key == "Notice Period" && it.value == "30 days" })
    }

    @Test fun parsesModelJsonAndSkipsSecrets() {
        val raw = "Here you go:\n```json\n[{\"key\":\"City\",\"value\":\"Pune\",\"aliases\":[\"Location\"]},{\"key\":\"Card\",\"value\":\"4111 1111 1111 1111\"},{\"key\":\"Skills\",\"value\":[\"Kotlin\",\"Swift\"]}]\n```"
        val facts = FactExtractor.parse(raw)
        assertEquals(listOf("City", "Skills"), facts.map { it.key })
        assertEquals("Kotlin, Swift", facts[1].value)
        assertEquals(listOf("Location"), facts[0].aliases)
    }
}

class QuestionPositionTest {
    private val d = com.typepilot.android.service.QuestionFinder::labelDistance
    // An Email field at y 680..760 with "Email" above it and the next question's label below it.
    @Test fun labelAboveWins() { assertEquals(20, d(80, 620, 300, 660, 80, 680, 1200, 760)) }
    @Test fun nextFieldsLabelIsNotThisQuestion() { assertNull(d(80, 800, 700, 840, 80, 680, 1200, 760)) }
    @Test fun toastAtTheBottomIsIgnored() { assertNull(d(40, 2500, 1240, 2650, 80, 680, 1200, 760)) }
    @Test fun floatingLabelOnTheBorder() { assertEquals(0, d(100, 665, 260, 700, 80, 680, 1200, 760)) }
    @Test fun labelToTheLeft() { assertEquals(60, d(10, 690, 60, 740, 80, 680, 1200, 760)) }
}
