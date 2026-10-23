import Foundation

/// Prompt construction. Kept compact: small local models do best with short, direct instructions.
public enum Prompts {
    public static let completionSystem = """
    You are an inline autocomplete engine, like Gmail Smart Compose. Continue the user's text \
    exactly where it stops, in their voice and language. Output ONLY the continuation: no quotes, \
    no explanations, no repetition of their text. Keep it short and natural, and stop at a sensible point. \
    If the last word is unfinished, finish it without a leading space; otherwise start with a space. \
    Use what is on screen (e.g. the message being replied to) to stay relevant. \
    Never invent personal facts (names, numbers, addresses, dates) unless they appear in the user's memory or on screen.
    """

    public static let starterSystem = """
    You ARE the user, typing into an empty text box. Write the first words of what the user would type next, \
    in first person: for example the start of a reply to the latest message on screen. \
    Output ONLY those words, exactly as they would be typed. Never talk about the user, never explain, never say \
    "Sure", "Here's" or "Suggestion". No quotes. If nothing sensible fits, output nothing. \
    Don't invent facts (names, dates, numbers) that aren't on screen or in memory.
    """

    private static func header(_ ctx: FieldContext, aboutMe: String) -> [String] {
        var lines: [String] = []
        let where_ = [ctx.appName, ctx.windowTitle].filter { !$0.isEmpty }.joined(separator: " — ")
        if !where_.isEmpty { lines.append("App: \(where_)") }
        if !ctx.fieldLabel.isEmpty { lines.append("Field: \(ctx.fieldLabel)") }
        let me = aboutMe.trimmingCharacters(in: .whitespacesAndNewlines)
        if !me.isEmpty { lines.append("About the user: \(me.prefix(500))") }
        return lines
    }

    private static func screen(_ ctx: FieldContext, maxChars: Int) -> [String] {
        let s = ctx.screenContext.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty else { return [] }
        return ["On screen near the field (most recent last):", String(s.suffix(maxChars))]
    }

    private static func memoryLines(_ memory: [String], limit: Int, chars: Int) -> [String] {
        guard !memory.isEmpty else { return [] }
        return ["Things the user often writes (use only if relevant):"] + memory.prefix(limit).map { "- \($0.prefix(chars))" }
    }

    public static func completionPrompt(_ ctx: FieldContext, memory: [String], maxWords: Int, aboutMe: String = "") -> String {
        var lines = header(ctx, aboutMe: aboutMe)
        lines += screen(ctx, maxChars: 900)
        lines += memoryLines(memory, limit: 5, chars: 200)
        lines.append("Continue with at most \(maxWords) words.")
        lines.append("Text so far:")
        lines.append(String(ctx.textBefore.suffix(1200)))
        return lines.joined(separator: "\n")
    }

    public static func starterPrompt(_ ctx: FieldContext, memory: [String], maxWords: Int, aboutMe: String = "") -> String {
        var lines = header(ctx, aboutMe: aboutMe)
        lines += screen(ctx, maxChars: 1500)
        lines += memoryLines(memory, limit: 5, chars: 200)
        lines.append("Now type the beginning of the user's message (first person, at most \(maxWords) words):")
        return lines.joined(separator: "\n")
    }

    public static let writeSystem = """
    You write text for the user, which will be inserted directly into the text field they are using. \
    Output ONLY the final text, ready to paste: no preamble, no quotes, no headings, no markdown unless asked. \
    Match the language and tone the user asks for, and fit the app (a chat message is short; an email can be longer). \
    When the request is a reply, reply to what is on screen. \
    Use the user's memory for facts about them. Do NOT mention any specific date, number, amount, name, \
    booking or order detail unless it appears in the request, on screen or in memory. \
    NEVER use placeholders in square brackets like [Name], [Date] or [Order Number]: if a detail is unknown, \
    simply leave it out and phrase the sentence so it still reads naturally.
    """

    public static func writePrompt(request: String, ctx: FieldContext, memory: [String],
                                   length: WriteLength = .auto, aboutMe: String = "", previousDraft: String? = nil) -> String {
        var lines = header(ctx, aboutMe: aboutMe)
        if let draft = previousDraft, !draft.isEmpty {
            // Instruction first, then the text between markers: small models follow this best.
            var r = ["Instruction: \(request)\(length == .auto ? "" : " " + length.instruction)", "", "Text to rewrite:", "\"\"\"", String(draft.prefix(6000)), "\"\"\""]
            if !aboutMe.trimmingCharacters(in: .whitespaces).isEmpty { r.insert("About the user: \(aboutMe.prefix(300))", at: 0) }
            return r.joined(separator: "\n")
        }
        lines.insert("Request: \(request)", at: 0)
        if !ctx.selectedText.isEmpty {
            lines.append("Selected text (rewrite or use it as instructed):")
            lines.append(String(ctx.selectedText.prefix(4000)))
        } else if !(ctx.textBefore + ctx.textAfter).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            lines.append("Text already in the field (for context):")
            lines.append(String((ctx.textBefore + ctx.textAfter).suffix(3000)))
        }
        lines += screen(ctx, maxChars: 3000)
        if !memory.isEmpty {
            lines.append("About the user (use when relevant):")
            lines += memory.prefix(8).map { "- \($0.prefix(300))" }
        }
        lines.append("Length: \(length.instruction)")
        lines.append("Write it now. No [placeholders]. Don't make up dates, numbers or names: omit unknown details.")
        return lines.joined(separator: "\n")
    }

    public static let reviseSystem = """
    You rewrite the user's text according to their instruction. Output ONLY the rewritten text, ready to paste: \
    no preamble, no quotes, no explanation, and never repeat the instruction. Keep facts unchanged; don't add new ones. \
    Never use [placeholders].
    """

    /// One-click revisions offered after a draft is written.
    public static let refinements: [(title: String, instruction: String)] = [
        ("Shorter", "Make it noticeably shorter and tighter, keeping the key point."),
        ("Longer", "Make it a bit longer and more detailed."),
        ("More formal", "Make it more formal and professional."),
        ("Friendlier", "Make it warmer and friendlier."),
        ("Fix grammar", "Fix grammar, spelling and punctuation. Keep the wording otherwise."),
        ("Bullet points", "Rewrite it as concise bullet points."),
    ]
}
