import Foundation

/// Decides what must never be remembered or sent to a model: card numbers, one-time codes,
/// secrets, government IDs. It is conservative on purpose: when in doubt, don't store.
public enum SensitiveFilter {
    /// Field labels that indicate sensitive input even when the field isn't a secure text field.
    private static let sensitiveLabel = try! NSRegularExpression(
        pattern: #"\b(pass(word|code|phrase)?|pwd|pin|otp|one[- ]?time|verification code|2fa|mfa|cvv|cvc|security code|card number|credit card|debit card|iban|routing|account number|ssn|social security|aadha?ar|pan (card|number)|passport|api[ _-]?key|secret|token|seed phrase|recovery (phrase|key)|private key)\b"#,
        options: [.caseInsensitive])

    private static let patterns: [NSRegularExpression] = [
        // Private keys, API keys and tokens
        #"-----BEGIN [A-Z ]*PRIVATE KEY-----"#,
        #"\b(sk|pk|rk)-[A-Za-z0-9_-]{20,}\b"#,
        #"\bAIza[0-9A-Za-z_-]{30,}\b"#,
        #"\bgh[pousr]_[A-Za-z0-9]{30,}\b"#,
        #"\bgsk_[A-Za-z0-9]{20,}\b"#,
        #"\bxox[abpr]-[A-Za-z0-9-]{10,}\b"#,
        // IBAN
        #"\b[A-Z]{2}\d{2}[A-Z0-9]{11,30}\b"#,
        // US SSN, Indian Aadhaar (12 digits in 4-4-4), Indian PAN
        #"\b\d{3}-\d{2}-\d{4}\b"#,
        #"(?<!\d)(?<!\d )\d{4}\s\d{4}\s\d{4}(?![ ]?\d)"#, // exactly 12 digits, not part of a longer number
        #"\b[A-Z]{5}\d{4}[A-Z]\b"#,
    ].map { try! NSRegularExpression(pattern: $0) }

    private static let digitRun = try! NSRegularExpression(pattern: #"(?:\d[ -]?){13,19}"#)

    public static func isSensitiveLabel(_ label: String) -> Bool {
        let r = NSRange(label.startIndex..., in: label)
        return sensitiveLabel.firstMatch(in: label, range: r) != nil
    }

    /// True if the text contains something we must never store.
    public static func containsSecret(_ text: String) -> Bool {
        let r = NSRange(text.startIndex..., in: text)
        for p in patterns where p.firstMatch(in: text, range: r) != nil { return true }
        for m in digitRun.matches(in: text, range: r) {
            let digits = (text as NSString).substring(with: m.range).filter(\.isNumber)
            if (13...19).contains(digits.count), luhn(digits) { return true }
        }
        return false
    }

    /// A bare short number (OTP, PIN) isn't worth remembering and is often a one-time secret.
    public static func looksLikeOneTimeCode(_ text: String) -> Bool {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return (4...8).contains(t.count) && t.allSatisfy(\.isNumber)
    }

    public static func luhn(_ digits: String) -> Bool {
        var sum = 0
        for (i, ch) in digits.reversed().enumerated() {
            guard var n = ch.wholeNumberValue else { return false }
            if i % 2 == 1 { n *= 2; if n > 9 { n -= 9 } }
            sum += n
        }
        return sum % 10 == 0
    }

    /// Mask secrets before text is sent to a cloud model.
    public static func redact(_ text: String) -> String {
        var out = text
        for p in patterns {
            out = p.stringByReplacingMatches(in: out, range: NSRange(out.startIndex..., in: out), withTemplate: "[redacted]")
        }
        let ns = out as NSString
        var result = out
        for m in digitRun.matches(in: out, range: NSRange(location: 0, length: ns.length)).reversed() {
            let s = ns.substring(with: m.range)
            let digits = s.filter(\.isNumber)
            if (13...19).contains(digits.count), luhn(digits), let r = Range(m.range, in: result) {
                result.replaceSubrange(r, with: "[card]")
            }
        }
        return result
    }
}
