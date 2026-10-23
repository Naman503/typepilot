import Foundation

/// Turns raw model output into a clean inline continuation of what the user typed.
///
/// Models often:
///  - repeat the end of the user's text before continuing ("…I'd like to request a refund for…")
///  - wrap output in quotes or add "Here's a continuation:"
///  - run on for several sentences or lines
/// Ghost text must be one short, natural chunk that starts exactly where the cursor is.
public enum ContinuationCleaner {
    /// "Sure, here's the beginning of the user's message:\n\n…" → "…". Chat-tuned models (Apple's
    /// on-device one especially) like to announce their answer before giving it.
    static let preamble = try! NSRegularExpression(
        pattern: #"^\s*(sure|certainly|of course|okay|ok|absolutely|here('|’)s|here is|i can help)[^\n:]{0,120}:[ \t]*\n*"#,
        options: [.caseInsensitive])

    public static func stripPreamble(_ s: String) -> String {
        let r = NSRange(s.startIndex..., in: s)
        guard let m = preamble.firstMatch(in: s, range: r), let range = Range(m.range, in: s) else { return s }
        return String(s[range.upperBound...])
    }

    public static func clean(_ raw: String, after textBefore: String, maxWords: Int, multiline: Bool = false) -> String? {
        var s = stripPreamble(raw.replacingOccurrences(of: "\r", with: ""))
        // Strip boilerplate and wrapping quotes/fences.
        for prefix in ["Continuation:", "continuation:", "Completion:", "Here's the continuation:", "Here is the continuation:"] {
            if s.trimmingCharacters(in: .whitespaces).hasPrefix(prefix) {
                s = String(s.trimmingCharacters(in: .whitespaces).dropFirst(prefix.count))
            }
        }
        s = s.replacingOccurrences(of: "```", with: "")
        let trimmedForQuotes = s.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmedForQuotes.count >= 2, let f = trimmedForQuotes.first, let l = trimmedForQuotes.last,
           ("\"“'".contains(f) && "\"”'".contains(l)) {
            s = String(trimmedForQuotes.dropFirst().dropLast())
        } else if let f = trimmedForQuotes.first, "\"“".contains(f), !trimmedForQuotes.dropFirst().contains(where: { "\"”".contains($0) }) {
            s = String(trimmedForQuotes.dropFirst()) // opening quote cut off by the token limit
        }

        // Remove an echoed tail of the user's text (longest overlap of ≥ 4 chars).
        s = removeEchoedPrefix(s, textBefore: textBefore)

        // One line unless the field is multi-line, and even then at most one paragraph.
        if let nl = s.firstIndex(of: "\n") {
            let head = String(s[..<nl])
            if !multiline || !head.trimmingCharacters(in: .whitespaces).isEmpty { s = head }
        }

        // Spacing: don't double the space the user already typed.
        if textBefore.isEmpty || textBefore.last?.isWhitespace == true {
            s = String(s.drop(while: { $0 == " " || $0 == "\t" }))
        } else if let last = textBefore.last, ".,!?;:".contains(last), let first = s.first, first.isLetter {
            s = " " + s // "Thanks.Looking" → "Thanks. Looking"
        }

        // Chatty preambles mean the model answered *about* the text instead of continuing it.
        // ("Sure, I'll take a look" is a fine reply; "Sure, here's a suggestion for the user" is not.)
        if s.trimmingCharacters(in: .whitespaces).range(
            of: #"^((sure|certainly|of course|okay)[ ,!.]*)?(here('|’)s|here is|i can help|as an ai)\b|\bthe user('|’)?s?\b|\b(suggestion|continuation)s?\b"#,
            options: [.regularExpression, .caseInsensitive]) != nil { return nil }

        s = limitWords(s, max: maxWords)
        let visible = s.trimmingCharacters(in: .whitespacesAndNewlines)
        if visible.isEmpty { return nil }
        // Pure punctuation or a repeat of the last word isn't useful.
        if visible.allSatisfy({ $0.isPunctuation }) && visible.count > 1 { return nil }
        return s.replacingOccurrences(of: "  ", with: " ")
    }

    static func removeEchoedPrefix(_ s: String, textBefore: String) -> String {
        let trimmed = s.drop(while: { $0 == " " })
        let tail = String(textBefore.suffix(200))
        guard !tail.isEmpty else { return s }
        let lowerOut = trimmed.lowercased()
        let lowerTail = tail.lowercased()
        // Longest suffix of the user's text that the output starts with.
        var best = 0
        var k = min(lowerTail.count, lowerOut.count)
        while k >= 4 {
            if lowerOut.hasPrefix(String(lowerTail.suffix(k))) { best = k; break }
            k -= 1
        }
        guard best > 0 else { return s }
        return String(trimmed.dropFirst(best))
    }

    /// Cut to at most `max` words, preferring to end at a sentence boundary.
    static func limitWords(_ s: String, max: Int) -> String {
        var words = 0
        var inWord = false
        var cut = s.endIndex
        var lastSentenceEnd: String.Index?
        for i in s.indices {
            let ch = s[i]
            if ch.isWhitespace {
                if inWord { inWord = false }
            } else if !inWord {
                inWord = true
                words += 1
                if words > max { cut = i; break }
            }
            if ".!?".contains(ch) { lastSentenceEnd = s.index(after: i) }
        }
        var out = String(s[..<cut])
        if cut != s.endIndex, let end = lastSentenceEnd, end <= cut, s.distance(from: s.startIndex, to: end) > 8 {
            out = String(s[..<end])
        }
        return out.replacingOccurrences(of: #"\s+$"#, with: "", options: .regularExpression)
    }
}
