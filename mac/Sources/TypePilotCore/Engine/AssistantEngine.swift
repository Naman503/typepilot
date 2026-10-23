import Foundation

/// The brain. For each pause in typing:
///   1. memory (field recall / phrase continuation): microseconds, no model
///   2. recent-completion cache: the same text again returns instantly
///   3. the completion model, with a short output cap
/// The caller cancels the Task when the user keeps typing.
public actor AssistantEngine {
    private let settings: SettingsStore
    private let memory: MemoryStore?
    private var phrases = PhraseCompleter()
    private var phrasesDirty = true
    private var cache: [String: String] = [:]
    private var cacheOrder: [String] = []
    private let cacheLimit = 300

    /// Tests can supply a model instead of the one chosen in settings.
    private let modelOverride: TextModel?

    public init(settings: SettingsStore, memory: MemoryStore?, modelOverride: TextModel? = nil) {
        self.settings = settings
        self.memory = memory
        self.modelOverride = modelOverride
    }

    public func memoryChanged() { phrasesDirty = true }

    private func ensurePhrases() {
        guard phrasesDirty, let memory else { return }
        phrases.rebuild(memory.all())
        phrasesDirty = false
    }

    // MARK: Models

    public static func model(for kind: ProviderKind, writing: Bool, settings s: AppSettings) -> TextModel {
        switch kind {
        case .apple: return AppleModel()
        case .ollama: return OllamaModel(baseURL: s.ollamaBaseURL, model: writing ? s.ollamaWritingModel : s.ollamaCompletionModel, timeout: s.requestTimeout)
        case .gemini: return GeminiModel(model: s.geminiModel, apiKey: Keychain.get("gemini"), timeout: s.requestTimeout)
        case .openAI: return OpenAICompatibleModel(baseURL: s.openAIBaseURL, model: s.openAIModel, apiKey: Keychain.get("openai"), timeout: s.requestTimeout)
        }
    }

    /// Memory snippets relevant to this field, for the prompt (most relevant first).
    private func memoryHints(_ ctx: FieldContext, limit: Int) -> [String] {
        let s = settings.value
        guard s.memoryEnabled, s.memoryInPrompts, let memory else { return [] }
        let field = PhraseCompleter.normalizeField(ctx.fieldLabel)
        let words = Set(ctx.textBefore.lowercased().split(whereSeparator: { !$0.isLetter && !$0.isNumber }).suffix(30).map(String.init))
        let scored = memory.all(limit: 2000).map { e -> (String, Double) in
            var score = Double(e.count) + (e.pinned ? 50 : 0)
            if !field.isEmpty && PhraseCompleter.normalizeField(e.field) == field { score += 20 }
            let ew = Set(e.text.lowercased().split(whereSeparator: { !$0.isLetter && !$0.isNumber }).map(String.init))
            score += Double(words.intersection(ew).count) * 3
            return (e.text, score)
        }
        return scored.sorted { $0.1 > $1.1 }.prefix(limit).map(\.0)
    }

    private func redactIfCloud(_ text: String, kind: ProviderKind) -> String {
        kind.isLocal ? text : SensitiveFilter.redact(text)
    }

    // MARK: Completion

    public func suggest(_ ctx: FieldContext, trigger: SuggestTrigger = .typing) async -> Suggestion? {
        let s = settings.value
        let t0 = Date()
        let ms = { Int(Date().timeIntervalSince(t0) * 1000) }
        let typed = ctx.textBefore.trimmingCharacters(in: .whitespacesAndNewlines)

        // 1) Memory: shortcuts, empty-field recall, phrase continuation. Instant.
        if s.memoryEnabled {
            ensurePhrases()
            if !(typed.isEmpty && !s.suggestOnEmptyFields),
               let hit = phrases.suggest(textBefore: ctx.textBefore, fieldLabel: ctx.fieldLabel, minChars: s.minCharsBeforeSuggesting,
                                         multilineField: ctx.isMultiline, maxWords: s.maxWords) {
                return Suggestion(text: hit.text, source: .memory, latencyMs: ms(), replaceLength: hit.replaceLength, note: hit.note)
            }
        }

        // 2) Decide whether the model should run.
        let starter = typed.isEmpty
        if starter {
            // Empty field: only on focus, only with something to go on (screen context or a multi-line field).
            guard trigger == .focus, s.suggestOnFocus,
                  !ctx.screenContext.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || ctx.isMultiline else { return nil }
            guard !Self.looksLikeSearch(ctx.fieldLabel) else { return nil }
        } else if trigger == .typing {
            guard typed.count >= s.minCharsBeforeSuggesting else { return nil }
        } else {
            guard s.suggestOnFocus else { return nil }
        }

        // 3) Cache
        let key = "\(ctx.bundleID)|\(ctx.fieldLabel)|\(ctx.textBefore.suffix(400))|\(ctx.screenContext.suffix(300).hashValue)"
        if let hit = cache[key] { return Suggestion(text: hit, source: .cache, latencyMs: ms()) }

        // 4) Model
        let kind = s.completionProvider
        let model = modelOverride ?? Self.model(for: kind, writing: false, settings: s)
        var c = ctx
        c.textBefore = redactIfCloud(ctx.textBefore, kind: kind)
        c.screenContext = s.useScreenContext ? redactIfCloud(ctx.screenContext, kind: kind) : ""
        let mem = memoryHints(ctx, limit: 4).map { redactIfCloud($0, kind: kind) }
        let req = GenerationRequest(
            system: starter ? Prompts.starterSystem : Prompts.completionSystem,
            prompt: starter ? Prompts.starterPrompt(c, memory: mem, maxWords: s.maxWords, aboutMe: s.aboutMe)
                            : Prompts.completionPrompt(c, memory: mem, maxWords: s.maxWords, aboutMe: s.aboutMe),
            // Room for a chatty preamble that gets stripped (Apple's model adds one to starters).
            maxTokens: starter ? s.maxWords * 2 + 32 : max(8, s.maxWords * 2),
            temperature: 0.2,
            stop: ["\n\n"])
        do {
            let raw = try await model.generate(req)
            try Task.checkCancellation()
            guard let text = ContinuationCleaner.clean(raw, after: ctx.textBefore, maxWords: s.maxWords, multiline: ctx.isMultiline) else {
                Log.debug("engine", "model returned nothing usable (\(raw.prefix(80)))")
                return nil
            }
            remember(key, text)
            return Suggestion(text: text, source: .ai, latencyMs: ms(), model: model.name)
        } catch is CancellationError {
            return nil
        } catch {
            Log.warn("engine", "completion failed: \(error.localizedDescription)")
            return nil
        }
    }

    static func looksLikeSearch(_ label: String) -> Bool {
        label.range(of: #"\b(search|find|filter|query|url|address bar|go to)\b"#, options: [.regularExpression, .caseInsensitive]) != nil
    }

    private func remember(_ key: String, _ value: String) {
        if cache[key] == nil { cacheOrder.append(key) }
        cache[key] = value
        if cacheOrder.count > cacheLimit { cache.removeValue(forKey: cacheOrder.removeFirst()) }
    }

    // MARK: Ask AI to write

    /// Write text for a request, or revise `previousDraft` when given. Streams cumulative text.
    public func write(_ request: String, ctx: FieldContext, length: WriteLength = .auto, previousDraft: String? = nil) -> AsyncThrowingStream<String, Error> {
        let s = settings.value
        let kind = s.writingProvider
        let model = modelOverride ?? Self.model(for: kind, writing: true, settings: s)
        var c = ctx
        c.textBefore = redactIfCloud(ctx.textBefore, kind: kind)
        c.textAfter = redactIfCloud(ctx.textAfter, kind: kind)
        c.selectedText = redactIfCloud(ctx.selectedText, kind: kind)
        c.screenContext = s.useScreenContext ? redactIfCloud(ctx.screenContext, kind: kind) : ""
        let prompt = Prompts.writePrompt(request: request, ctx: c, memory: memoryHints(ctx, limit: 8).map { redactIfCloud($0, kind: kind) },
                                         length: length, aboutMe: s.aboutMe, previousDraft: previousDraft.map { redactIfCloud($0, kind: kind) })
        Log.info("engine", "write via \(model.name): \(request.prefix(80))")
        let system = (previousDraft?.isEmpty == false) ? Prompts.reviseSystem : Prompts.writeSystem
        let upstream = model.stream(GenerationRequest(system: system, prompt: prompt, maxTokens: 700, temperature: 0.5))
        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    for try await text in upstream { continuation.yield(DraftCleaner.clean(text)) }
                    continuation.finish()
                } catch { continuation.finish(throwing: error) }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    // MARK: Learning

    /// Called when the user leaves a field they typed in, or accepts a suggestion.
    public func learn(text: String, ctx: FieldContext, kind: MemoryEntry.Kind) {
        let s = settings.value
        guard s.memoryEnabled, s.learnFromTyping || kind != .typed, let memory else { return }
        if memory.record(text, app: ctx.appName, field: ctx.fieldLabel, kind: kind) {
            phrasesDirty = true
            Log.debug("memory", "remembered \(text.count) chars from \(ctx.appName)")
        }
    }
}
