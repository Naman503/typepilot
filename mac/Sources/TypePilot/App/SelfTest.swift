import AppKit
import TypePilotCore

/// `TypePilot --selftest`: checks permissions and each configured model from the terminal and
/// prints real latencies. Handy for debugging without the UI.
enum SelfTest {
    static func run() async {
        func line(_ s: String) { print(s); fflush(stdout) }
        let s = SettingsStore.shared.value
        line("TypePilot self-test")
        line("  Accessibility (for this terminal process, not the app): \(AX.isTrusted() ? "granted" : "not granted")")
        line("  Suggestions provider: \(s.completionProvider.title) · Writing provider: \(s.writingProvider.title)")

        for kind in ProviderKind.allCases {
            let model = AssistantEngine.model(for: kind, writing: false, settings: s)
            let (ok, reason) = await model.availability()
            guard ok else { line("  • \(model.name): unavailable (\(reason ?? "?"))"); continue }
            // Completion latency on a realistic prompt (twice: cold, then warm).
            let ctx = FieldContext(appName: "Mail", windowTitle: "Re: Refund for order", fieldLabel: "Message",
                                   textBefore: "Hi team,\n\nI'm writing to follow up on my refund request for order 4521. It has been two weeks and I ")
            let req = GenerationRequest(system: Prompts.completionSystem, prompt: Prompts.completionPrompt(ctx, memory: [], maxWords: 10),
                                        maxTokens: 20, temperature: 0.2, stop: ["\n\n"])
            for run in ["cold", "warm"] {
                let t0 = Date()
                do {
                    let raw = try await model.generate(req)
                    let ms = Int(Date().timeIntervalSince(t0) * 1000)
                    let cleaned = ContinuationCleaner.clean(raw, after: ctx.textBefore, maxWords: 10) ?? "∅"
                    line("  • \(model.name) completion (\(run)): \(ms) ms → “\(cleaned)”")
                } catch {
                    line("  • \(model.name) completion failed: \(error.localizedDescription)")
                    break
                }
            }
        }

        // Suggestion on focus (empty field) using screen context, through the real engine.
        let focusEngine = AssistantEngine(settings: .shared, memory: nil)
        let chat = FieldContext(appName: "Slack", windowTitle: "#release", fieldLabel: "Message", textBefore: "",
                                screenContext: "Priya: The 2.4 release build is up on staging. Can you verify the checkout flow before 5pm?")
        let tf = Date()
        let starter = await focusEngine.suggest(chat, trigger: .focus)
        line("  • On-focus suggestion (empty Slack reply, \(s.completionProvider.title)): \(Int(Date().timeIntervalSince(tf) * 1000)) ms → “\(starter?.text ?? "∅")”")

        // Writing via the configured writing provider (streamed).
        let engine = AssistantEngine(settings: .shared, memory: nil)
        let t0 = Date()
        var first: Int?
        var out = ""
        do {
            for try await t in await engine.write("write a short polite message to IndiGo asking about a refund I haven't received",
                                                  ctx: FieldContext(appName: "Mail", fieldLabel: "Message")) {
                if first == nil { first = Int(Date().timeIntervalSince(t0) * 1000) }
                out = t
            }
            line("  • Write (\(s.writingProvider.title)): first text \(first ?? -1) ms, done \(Int(Date().timeIntervalSince(t0) * 1000)) ms")
            line(out.split(separator: "\n").map { "      \($0)" }.joined(separator: "\n"))
            let tr = Date()
            var shorter = ""
            for try await t in await engine.write(Prompts.refinements[0].instruction, ctx: FieldContext(appName: "Mail"), previousDraft: out) { shorter = t }
            line("  • Refine “Shorter”: \(Int(Date().timeIntervalSince(tr) * 1000)) ms, \(out.split(separator: " ").count) → \(shorter.split(separator: " ").count) words")
            line(shorter.split(separator: "\n").map { "      \($0)" }.joined(separator: "\n"))
        } catch {
            line("  • Write failed: \(error.localizedDescription)")
        }
    }
}
