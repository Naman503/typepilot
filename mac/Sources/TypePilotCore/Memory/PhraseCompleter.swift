import Foundation

/// Instant, model-free suggestions from your own memory, in microseconds:
///
///  1. **Field recall**: an empty field whose label matches one you've filled before ("Address",
///     "Phone") is offered the value you usually type there.
///  2. **Phrase continuation**: if what you're typing is the beginning of something you've written
///     before ("Thanks for reaching out, I'll get b…"), the rest is offered.
public struct PhraseCompleter: Sendable {
    struct Phrase: Sendable {
        let text: String
        let lower: String
        let field: String
        let score: Double
        /// Repeated (used ≥ 2×), added by you, or pinned. Only these are offered as suggestions:
        /// a message you typed once is context for the AI, not something to re-suggest.
        let trusted: Bool
    }

    /// Generic chat/compose labels: never "recall the usual value" there.
    static let composeLabel = try! NSRegularExpression(
        pattern: #"\b(message|reply|comment|chat|ask|prompt|write|compose|post|note|search|type (a|your)|send)\b"#, options: [.caseInsensitive])

    /// Values recalled into an empty field must be short, single-line form values (address, phone, ID…).
    static let maxRecallLength = 120

    public struct Hit: Sendable, Equatable {
        public var text: String
        /// Characters before the caret to replace (the typed ";shortcut").
        public var replaceLength: Int = 0
        public var note: String?
    }

    /// Shortcuts start with this character: type ";addr" to get the entry with shortcut "addr".
    public static let shortcutPrefix: Character = ";"

    private var phrases: [Phrase] = []
    private var byField: [String: [Phrase]] = [:]
    private var shortcuts: [String: String] = [:]

    public init(entries: [MemoryEntry] = [], now: Date = Date()) {
        rebuild(entries, now: now)
    }

    public mutating func rebuild(_ entries: [MemoryEntry], now: Date = Date()) {
        var ps: [Phrase] = []
        var fields: [String: [Phrase]] = [:]
        var sc: [String: String] = [:]
        for e in entries {
            if !e.shortcut.isEmpty { sc[e.shortcut] = e.text }
            let ageDays = max(0, now.timeIntervalSince(e.lastUsed) / 86_400)
            // Frequency × recency, pinned entries first.
            let score = Double(e.count) * exp(-ageDays / 30) + (e.pinned ? 100 : 0) + (e.kind == .manual ? 2 : 0)
            let field = Self.normalizeField(e.field)
            let trusted = e.count >= 2 || e.kind == .manual || e.pinned
            let whole = Phrase(text: e.text, lower: e.text.lowercased(), field: field, score: score, trusted: trusted)
            ps.append(whole)
            if !field.isEmpty, trusted, e.text.count <= Self.maxRecallLength, !e.text.contains("\n") {
                fields[field, default: []].append(whole)
            }
            // Also index each sentence, so a sentence from a long message can be completed on its own.
            for sentence in Self.sentences(e.text) where sentence.count >= 12 && sentence != e.text {
                ps.append(Phrase(text: sentence, lower: sentence.lowercased(), field: field, score: score * 0.8, trusted: trusted))
            }
        }
        phrases = ps.sorted { $0.score > $1.score }
        byField = fields.mapValues { $0.sorted { $0.score > $1.score } }
        shortcuts = sc
    }

    public var isEmpty: Bool { phrases.isEmpty }

    /// Lowercased, punctuation-free label used to match fields across apps ("E-mail:" ≈ "email").
    public static func normalizeField(_ s: String) -> String {
        s.lowercased().replacingOccurrences(of: #"[^a-z0-9 ]+"#, with: " ", options: .regularExpression)
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces)
    }

    static func sentences(_ text: String) -> [String] {
        var out: [String] = []
        text.enumerateSubstrings(in: text.startIndex..., options: [.bySentences, .localized]) { sub, _, _, _ in
            if let s = sub?.trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty { out.append(s) }
        }
        return out
    }

    /// The part of the text the user is currently writing: after the last line break or sentence end.
    static func currentFragment(_ textBefore: String) -> String {
        guard let idx = textBefore.lastIndex(where: { "\n.!?".contains($0) }) else {
            return textBefore.trimmingLeadingWhitespace()
        }
        return String(textBefore[textBefore.index(after: idx)...]).trimmingLeadingWhitespace()
    }

    /// Suggest from memory, or nil if nothing fits.
    public func suggest(textBefore: String, fieldLabel: String, minChars: Int = 3, multilineField: Bool = false, maxWords: Int = 12) -> Hit? {
        // 0) A typed shortcut (";addr") right before the caret.
        if let hit = shortcutHit(textBefore) { return hit }
        // 1) Empty single-line form field: recall what you usually put in a field with this label.
        if textBefore.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            guard !multilineField, !Self.isComposeLabel(fieldLabel) else { return nil }
            let f = Self.normalizeField(fieldLabel)
            guard !f.isEmpty, let best = byField[f]?.first else { return nil }
            return Hit(text: best.text, note: "Your usual \(fieldLabel.trimmingCharacters(in: .punctuationCharacters))")
        }
        // 2) Continue a phrase you've written more than once, offering only the next few words.
        let fragment = Self.currentFragment(textBefore)
        guard fragment.count >= max(minChars, 6) else { return nil }
        let lower = fragment.lowercased()
        let fieldNorm = Self.normalizeField(fieldLabel)
        var best: (Phrase, Double)?
        for p in phrases where p.trusted && p.lower.count > lower.count + 1 && p.lower.hasPrefix(lower) {
            let s = p.score + (p.field == fieldNorm && !fieldNorm.isEmpty ? 5 : 0)
            if best == nil || s > best!.1 { best = (p, s) }
        }
        guard let (p, _) = best else { return nil }
        let rest = Self.firstWords(String(p.text.dropFirst(fragment.count)), max: maxWords)
        return rest.trimmingCharacters(in: .whitespaces).isEmpty ? nil : Hit(text: rest)
    }

    static func isComposeLabel(_ label: String) -> Bool {
        composeLabel.firstMatch(in: label, range: NSRange(label.startIndex..., in: label)) != nil
    }

    /// The first `max` words of `s`, keeping its leading space.
    static func firstWords(_ s: String, max: Int) -> String {
        var words = 0, inWord = false
        for i in s.indices {
            if s[i].isWhitespace { inWord = false }
            else if !inWord {
                inWord = true
                words += 1
                if words > max { return String(s[..<i]).replacingOccurrences(of: #"\s+$"#, with: "", options: .regularExpression) }
            }
        }
        return s
    }

    /// The last token is ";something" that matches a shortcut (exactly, or as the only prefix match).
    func shortcutHit(_ textBefore: String) -> Hit? {
        guard !shortcuts.isEmpty else { return nil }
        let token = textBefore.split(whereSeparator: { $0.isWhitespace }).last.map(String.init) ?? ""
        guard token.count >= 2, token.first == Self.shortcutPrefix, textBefore.hasSuffix(token) else { return nil }
        let key = MemoryStore.normalizeShortcut(String(token.dropFirst()))
        let match = shortcuts[key] ?? {
            let prefixed = shortcuts.filter { $0.key.hasPrefix(key) }
            return prefixed.count == 1 ? prefixed.first?.value : nil
        }()
        guard let text = match else { return nil }
        return Hit(text: text, replaceLength: (token as NSString).length, note: "\(Self.shortcutPrefix)\(key)")
    }
}

extension String {
    func trimmingLeadingWhitespace() -> String { String(drop(while: { $0.isWhitespace })) }
}
