package com.typepilot.android.text

/**
 * Decides what must never be remembered or sent to a model: card numbers, one-time codes,
 * secrets, government IDs. Conservative on purpose: when in doubt, don't store.
 */
object SensitiveFilter {
    private val sensitiveLabel = Regex(
        """\b(pass(word|code|phrase)?|pwd|pin|otp|one[- ]?time|verification code|2fa|mfa|cvv|cvc|security code|card number|credit card|debit card|iban|routing|account number|ssn|social security|aadha?ar|pan (card|number)|passport|api[ _-]?key|secret|token|seed phrase|recovery (phrase|key)|private key|upi pin)\b""",
        RegexOption.IGNORE_CASE)

    private val patterns = listOf(
        """-----BEGIN [A-Z ]*PRIVATE KEY-----""",
        """\b(sk|pk|rk)-[A-Za-z0-9_-]{20,}\b""",
        """\bAIza[0-9A-Za-z_-]{30,}\b""",
        """\bgh[pousr]_[A-Za-z0-9]{30,}\b""",
        """\bgsk_[A-Za-z0-9]{20,}\b""",
        """\bhf_[A-Za-z0-9]{20,}\b""",
        """\bxox[abpr]-[A-Za-z0-9-]{10,}\b""",
        """\b[A-Z]{2}\d{2}[A-Z0-9]{11,30}\b""",          // IBAN
        """\b\d{3}-\d{2}-\d{4}\b""",                       // US SSN
        """(?<!\d)(?<!\d )\d{4}\s\d{4}\s\d{4}(?![ ]?\d)""", // Aadhaar: exactly 12 digits in 4-4-4, not part of a longer number
        """\b[A-Z]{5}\d{4}[A-Z]\b""",                      // Indian PAN
    ).map { Regex(it) }

    private val digitRun = Regex("""(?:\d[ -]?){13,19}""")

    fun isSensitiveLabel(label: String) = label.isNotEmpty() && sensitiveLabel.containsMatchIn(label)

    fun containsSecret(text: String): Boolean {
        if (patterns.any { it.containsMatchIn(text) }) return true
        return digitRun.findAll(text).any { m ->
            val digits = m.value.filter(Char::isDigit)
            digits.length in 13..19 && luhn(digits)
        }
    }

    fun looksLikeOneTimeCode(text: String): Boolean {
        val t = text.trim()
        return t.length in 4..8 && t.all(Char::isDigit)
    }

    fun luhn(digits: String): Boolean {
        var sum = 0
        digits.reversed().forEachIndexed { i, ch ->
            var n = ch.digitToIntOrNull() ?: return false
            if (i % 2 == 1) { n *= 2; if (n > 9) n -= 9 }
            sum += n
        }
        return sum % 10 == 0
    }

    /** Mask secrets before text goes to a cloud model. */
    fun redact(text: String): String {
        var out = text
        for (p in patterns) out = p.replace(out, "[redacted]")
        return digitRun.replace(out) { m ->
            val digits = m.value.filter(Char::isDigit)
            if (digits.length in 13..19 && luhn(digits)) "[card]" else m.value
        }
    }
}
