import XCTest
@testable import TypePilotCore

/// Records requests made to a FakeModel.
final class RequestLog: @unchecked Sendable {
    var requests: [GenerationRequest] = []
}

/// A scripted model for engine tests.
struct FakeModel: TextModel {
    var reply: String
    var delayMs: UInt64 = 0
    var log = RequestLog()
    var name: String { "fake" }
    func availability() async -> (ok: Bool, reason: String?) { (true, nil) }
    func generate(_ r: GenerationRequest) async throws -> String {
        log.requests.append(r)
        if delayMs > 0 { try await Task.sleep(nanoseconds: delayMs * 1_000_000) }
        return reply
    }
}

final class EngineTests: XCTestCase {
    func makeSettings() -> SettingsStore {
        let d = UserDefaults(suiteName: "tp-tests-\(UUID().uuidString)")!
        let s = SettingsStore(defaults: d)
        s.update { $0.minCharsBeforeSuggesting = 3; $0.maxWords = 8 }
        return s
    }

    func testMemoryFirstThenModelThenCache() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("tp-\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: url) }
        let mem = try MemoryStore(url: url)
        mem.record("Thanks for reaching out, I'll get back to you soon.", app: "Mail", field: "", kind: .typed)
        mem.record("Thanks for reaching out, I'll get back to you soon.", app: "Mail", field: "", kind: .typed) // used twice → trusted
        let engine = AssistantEngine(settings: makeSettings(), memory: mem, modelOverride: FakeModel(reply: "\"tomorrow morning.\""))

        // Memory hit: instant, no model.
        let m = await engine.suggest(FieldContext(textBefore: "Thanks for reaching out, I'll get"))
        XCTAssertEqual(m?.source, .memory)
        XCTAssertEqual(m?.text, " back to you soon.")

        // Model, cleaned.
        let a = await engine.suggest(FieldContext(appName: "Mail", textBefore: "See you "))
        XCTAssertEqual(a?.source, .ai)
        XCTAssertEqual(a?.text, "tomorrow morning.")

        // Same context again: cache.
        let c = await engine.suggest(FieldContext(appName: "Mail", textBefore: "See you "))
        XCTAssertEqual(c?.source, .cache)
    }

    func testCancellationReturnsNil() async {
        let engine = AssistantEngine(settings: makeSettings(), memory: nil, modelOverride: FakeModel(reply: "late", delayMs: 300))
        let task = Task { await engine.suggest(FieldContext(textBefore: "Hello there ")) }
        try? await Task.sleep(nanoseconds: 50_000_000)
        task.cancel()
        let r = await task.value
        XCTAssertNil(r)
    }

    func testLearnRecordsTypedText() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("tp-\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: url) }
        let mem = try MemoryStore(url: url)
        let engine = AssistantEngine(settings: makeSettings(), memory: mem, modelOverride: FakeModel(reply: ""))
        await engine.learn(text: "Flat 4, Rose Villa, Pune 411001", ctx: FieldContext(appName: "Safari", fieldLabel: "Address"), kind: .typed)
        let once = await engine.suggest(FieldContext(fieldLabel: "Address", textBefore: ""))
        XCTAssertNil(once) // typed once isn't offered yet
        await engine.learn(text: "Flat 4, Rose Villa, Pune 411001", ctx: FieldContext(appName: "Chrome", fieldLabel: "Address"), kind: .typed)
        let s = await engine.suggest(FieldContext(fieldLabel: "Address", textBefore: ""))
        XCTAssertEqual(s?.text, "Flat 4, Rose Villa, Pune 411001")
        XCTAssertEqual(s?.source, .memory)
    }

    func testPromptsIncludeContext() {
        let ctx = FieldContext(appName: "Mail", windowTitle: "Re: Refund", fieldLabel: "Message", textBefore: "Hi team,")
        let p = Prompts.completionPrompt(ctx, memory: ["Order #A123"], maxWords: 6)
        XCTAssertTrue(p.contains("App: Mail — Re: Refund"))
        XCTAssertTrue(p.contains("Field: Message"))
        XCTAssertTrue(p.contains("- Order #A123"))
        XCTAssertTrue(p.hasSuffix("Hi team,"))
        let w = Prompts.writePrompt(request: "polite refund request", ctx: FieldContext(selectedText: "pls refund"), memory: [])
        XCTAssertTrue(w.contains("Selected text"))
        XCTAssertTrue(w.contains("pls refund"))
    }

    func testSettingsDecodeIsTolerant() throws {
        let json = #"{"maxWords": 999, "completionProvider": "ollama", "unknownKey": true, "debounceMs": 5}"#.data(using: .utf8)!
        let s = try JSONDecoder().decode(AppSettings.self, from: json)
        XCTAssertEqual(s.maxWords, 40)
        XCTAssertEqual(s.debounceMs, 50)
        XCTAssertEqual(s.completionProvider, .ollama)
        XCTAssertTrue(s.enabled)
        XCTAssertTrue(s.excludedBundleIDs.contains("com.apple.Terminal"))
    }

    func testSuggestsOnFocusWithoutTyping() async {
        let fake = FakeModel(reply: " Thanks for the update!")
        let engine = AssistantEngine(settings: makeSettings(), memory: nil, modelOverride: fake)
        let chat = FieldContext(appName: "Slack", fieldLabel: "Message", textBefore: "",
                                screenContext: "Priya: The release is done, can you verify the build?")
        // Typing with an empty field never calls the model…
        let typing = await engine.suggest(chat, trigger: .typing)
        XCTAssertNil(typing)
        XCTAssertTrue(fake.log.requests.isEmpty)
        // …but focusing it does, with the starter prompt and the screen context.
        let focus = await engine.suggest(chat, trigger: .focus)
        XCTAssertEqual(focus?.text, "Thanks for the update!")
        XCTAssertEqual(fake.log.requests.first?.system, Prompts.starterSystem)
        XCTAssertTrue(fake.log.requests.first?.prompt.contains("can you verify the build?") ?? false)
    }

    func testNoStarterForSearchOrContextlessFields() async {
        let fake = FakeModel(reply: "anything")
        let engine = AssistantEngine(settings: makeSettings(), memory: nil, modelOverride: fake)
        let search = await engine.suggest(FieldContext(fieldLabel: "Search mail", screenContext: "Inbox"), trigger: .focus)
        let bare = await engine.suggest(FieldContext(fieldLabel: "Name"), trigger: .focus)
        XCTAssertNil(search)
        XCTAssertNil(bare)
        XCTAssertTrue(fake.log.requests.isEmpty)
    }

    func testFocusIntoExistingTextContinuesIt() async {
        let fake = FakeModel(reply: "tomorrow.")
        let engine = AssistantEngine(settings: makeSettings(), memory: nil, modelOverride: fake)
        let s = await engine.suggest(FieldContext(textBefore: "I'll send it "), trigger: .focus)
        XCTAssertEqual(s?.text, "tomorrow.")
        XCTAssertEqual(fake.log.requests.first?.system, Prompts.completionSystem)
    }

    func testAboutMeAndScreenContextReachPrompts() async {
        let settings = makeSettings()
        settings.update { $0.aboutMe = "I'm Naman, I keep it brief." }
        let fake = FakeModel(reply: "ok")
        let engine = AssistantEngine(settings: settings, memory: nil, modelOverride: fake)
        _ = await engine.suggest(FieldContext(textBefore: "Hello there", screenContext: "Ravi: lunch?"), trigger: .typing)
        let p = fake.log.requests.first?.prompt ?? ""
        XCTAssertTrue(p.contains("About the user: I'm Naman"))
        XCTAssertTrue(p.contains("Ravi: lunch?"))
        // Screen context off → not sent.
        settings.update { $0.useScreenContext = false }
        _ = await engine.suggest(FieldContext(textBefore: "Hello again", screenContext: "Ravi: lunch?"), trigger: .typing)
        XCTAssertFalse(fake.log.requests.last?.prompt.contains("Ravi: lunch?") ?? true)
    }

    func testWriteAndRevise() async throws {
        let fake = FakeModel(reply: "Dear [Name], refund please.")
        let engine = AssistantEngine(settings: makeSettings(), memory: nil, modelOverride: fake)
        var out = ""
        for try await t in await engine.write("refund request", ctx: FieldContext(appName: "Mail"), length: .short) { out = t }
        XCTAssertEqual(out, "Dear, refund please.")
        XCTAssertTrue(fake.log.requests[0].prompt.contains("Keep it short"))
        for try await t in await engine.write("Make it more formal.", ctx: FieldContext(appName: "Mail"), previousDraft: out) { out = t }
        let revise = fake.log.requests[1]
        XCTAssertEqual(revise.system, Prompts.reviseSystem)
        XCTAssertTrue(revise.prompt.hasPrefix("Instruction: Make it more formal."))
        XCTAssertTrue(revise.prompt.contains("Text to rewrite:\n\"\"\"\nDear, refund please.\n\"\"\""))
    }
}
