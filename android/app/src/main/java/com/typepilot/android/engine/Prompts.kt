package com.typepilot.android.engine

import com.typepilot.android.core.WriteLength

/** Prompts are short and direct: small on-device models follow them best. */
object Prompts {
    val completionSystem = """
        You are an inline autocomplete engine on a phone keyboard, like Gmail Smart Compose. Continue the user's text exactly where it stops, in their voice and language. Output ONLY the continuation: no quotes, no explanations, no repetition of their text. Keep it short and natural, and stop at a sensible point. If the last word is unfinished, finish it without a leading space; otherwise start with a space. Use what is on screen (e.g. the message being replied to) to stay relevant. Never invent personal facts (names, numbers, addresses, dates) unless they appear in memory or on screen.
    """.trimIndent()

    val starterSystem = """
        You ARE the user, typing into an empty text box on their phone. Write the first words of what they would type next, in first person: for example the start of a reply to the latest message on screen. Output ONLY those words, exactly as they would be typed. Never talk about the user, never explain, never say "Sure", "Here's" or "Suggestion". No quotes. If nothing sensible fits, output nothing. Don't invent facts that aren't on screen or in memory.
    """.trimIndent()

    val writeSystem = """
        You write text for the user, which will be inserted directly into the text field they are using on their phone. Output ONLY the final text, ready to send: no preamble, no quotes, no headings, no markdown unless asked. Match the language and tone asked for, and fit the app (a chat message is short; an email can be longer). When the request is a reply, reply to what is on screen. Use the user's memory for facts about them. Do NOT mention any specific date, number, amount, name or order detail unless it appears in the request, on screen or in memory. NEVER use placeholders like [Name] or [Date]: if a detail is unknown, leave it out and phrase the sentence so it still reads naturally.
    """.trimIndent()

    val rewriteSystem = """
        You are an expert editor. Rewrite the user's text exactly as instructed. Output ONLY the rewritten text: no preamble, no quotes, no explanation, no notes, and never repeat the instruction. Keep the original language unless asked to translate. Keep names, numbers, facts and meaning; never add new facts. Never use [placeholders]. Keep line breaks and lists where they make sense.
    """.trimIndent()

    private fun header(ctx: FieldContext, aboutMe: String): MutableList<String> {
        val lines = mutableListOf<String>()
        if (ctx.appName.isNotBlank()) lines += "App: ${ctx.appName}"
        if (ctx.fieldLabel.isNotBlank()) lines += "Field: ${ctx.fieldLabel}"
        if (ctx.question.isNotBlank() && ctx.question != ctx.fieldLabel) lines += "This field asks: ${ctx.question}"
        if (aboutMe.isNotBlank()) lines += "About the user: ${aboutMe.trim().take(400)}"
        return lines
    }

    private fun screen(ctx: FieldContext, max: Int): List<String> {
        val s = ctx.screenContext.trim()
        return if (s.isEmpty()) emptyList() else listOf("On screen (most recent last):", s.takeLast(max))
    }

    private fun memory(memory: List<String>, limit: Int, chars: Int): List<String> =
        if (memory.isEmpty()) emptyList() else listOf("Things the user often writes (use only if relevant):") + memory.take(limit).map { "- ${it.take(chars)}" }

    /** For small on-device models: the text alone. Every extra line costs prefill time and gets parroted back. */
    val compactSystem = "Continue the user's text with the next few words, in the same language and voice. Reply with only those words."

    fun compactCompletion(ctx: FieldContext): String = ctx.textBefore.takeLast(300)

    fun completion(ctx: FieldContext, mem: List<String>, maxWords: Int, aboutMe: String): String {
        val lines = header(ctx, aboutMe)
        lines += screen(ctx, 800)
        lines += memory(mem, 4, 160)
        lines += "Continue with at most $maxWords words."
        lines += "Text so far:"
        lines += ctx.textBefore.takeLast(1000)
        return lines.joinToString("\n")
    }

    fun starter(ctx: FieldContext, mem: List<String>, maxWords: Int, aboutMe: String): String {
        val lines = header(ctx, aboutMe)
        lines += screen(ctx, 1200)
        lines += memory(mem, 4, 160)
        lines += if (ctx.question.isNotBlank()) "Answer only what THIS field asks (\"${ctx.question.take(160)}\"), not other questions on screen. First person, at most $maxWords words:"
            else "Now type the beginning of the user's message (first person, at most $maxWords words):"
        return lines.joinToString("\n")
    }

    fun write(request: String, ctx: FieldContext, mem: List<String>, length: WriteLength, aboutMe: String, previousDraft: String?): String {
        if (!previousDraft.isNullOrBlank()) {
            val instruction = request + if (length == WriteLength.AUTO) "" else " " + length.instruction
            return listOfNotNull(
                aboutMe.takeIf { it.isNotBlank() }?.let { "About the user: ${it.take(300)}" },
                "Instruction: $instruction", "", "Text to rewrite:", "\"\"\"", previousDraft.take(6000), "\"\"\"",
            ).joinToString("\n")
        }
        val lines = header(ctx, aboutMe)
        lines.add(0, "Request: $request")
        val existing = (ctx.textBefore + ctx.textAfter).trim()
        if (ctx.selectedText.isNotBlank()) { lines += "Selected text (use it as instructed):"; lines += ctx.selectedText.take(4000) }
        else if (existing.isNotEmpty()) { lines += "Text already in the field (for context):"; lines += existing.takeLast(3000) }
        lines += screen(ctx, 2500)
        if (mem.isNotEmpty()) { lines += "About the user (use when relevant):"; lines += mem.take(8).map { "- ${it.take(300)}" } }
        lines += "Length: ${length.instruction}"
        lines += "Write it now. No [placeholders]. Don't make up dates, numbers or names: omit unknown details."
        return lines.joinToString("\n")
    }

    fun rewrite(text: String, mode: RewriteMode, custom: String, ctx: FieldContext, aboutMe: String): String {
        val instruction = if (mode == RewriteMode.CUSTOM) custom.ifBlank { "Improve it." } else mode.instruction
        val lines = mutableListOf("Instruction: $instruction")
        if (ctx.appName.isNotBlank()) lines += "(It will be sent in ${ctx.appName}; fit that context.)"
        if (aboutMe.isNotBlank() && mode in setOf(RewriteMode.BEST, RewriteMode.LONGER, RewriteMode.CUSTOM)) lines += "About the user: ${aboutMe.take(300)}"
        lines += ""
        lines += "Text:"
        lines += "\"\"\""
        lines += text.take(8000)
        lines += "\"\"\""
        return lines.joinToString("\n")
    }
}
