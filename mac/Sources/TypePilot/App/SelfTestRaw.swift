import Foundation
import TypePilotCore

/// `--selftest-raw`: prints raw model output for the starter prompt (prompt tuning aid).
enum SelfTestRaw {
    static func run() async {
        let ctx = FieldContext(appName: "Slack", windowTitle: "#release", fieldLabel: "Message", textBefore: "",
                               screenContext: "Priya: The 2.4 release build is up on staging. Can you verify the checkout flow before 5pm?")
        let m = AppleModel()
        for i in 0..<3 {
            let raw = (try? await m.generate(GenerationRequest(system: Prompts.starterSystem, prompt: Prompts.starterPrompt(ctx, memory: [], maxWords: 10), maxTokens: 24, temperature: 0.2))) ?? "ERROR"
            print("starter raw \(i): \(raw.debugDescription)")
        }
    }
}
