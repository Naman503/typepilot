import Foundation

/// Cleans "write X for me" drafts before they're shown and inserted:
/// models love template placeholders ("[Your Name]", "[Order Number]") even when told not to.
public enum DraftCleaner {
    private static let placeholder = try! NSRegularExpression(pattern: #"\s?\[(?:[A-Za-z][A-Za-z']*)(?:[ /-][A-Za-z']+){0,5}\]"#)
    private static let echoedInstruction = try! NSRegularExpression(
        pattern: #"^\s*(revise the draft|output only|instruction:|text to rewrite|current draft:|rewritten text:|\"\"\"|<<<|>>>)"#,
        options: [.caseInsensitive])

    public static func clean(_ text: String) -> String {
        var lines: [String] = []
        for raw in ContinuationCleaner.stripPreamble(text).components(separatedBy: "\n") {
            // The model echoed our prompt scaffolding: everything after it is noise.
            if echoedInstruction.firstMatch(in: raw, range: NSRange(raw.startIndex..., in: raw)) != nil {
                if lines.contains(where: { !$0.trimmingCharacters(in: .whitespaces).isEmpty }) { break } else { continue }
            }
            let trimmed = raw.trimmingCharacters(in: .whitespaces)
            // A line that is only a placeholder ("[Your Name]") is dropped entirely.
            if trimmed.hasPrefix("["), trimmed.hasSuffix("]"), trimmed.count < 40 { continue }
            var line = placeholder.stringByReplacingMatches(in: raw, range: NSRange(raw.startIndex..., in: raw), withTemplate: "")
            line = line.replacingOccurrences(of: #" +([,.;:!?])"#, with: "$1", options: .regularExpression)
                .replacingOccurrences(of: #"  +"#, with: " ", options: .regularExpression)
            lines.append(line)
        }
        // Trim dangling sign-offs like "Best regards," with nothing after (the name was a placeholder): keep them, it's natural.
        return lines.joined(separator: "\n").replacingOccurrences(of: #"\n{3,}"#, with: "\n\n", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
