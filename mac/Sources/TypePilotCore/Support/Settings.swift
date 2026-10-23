import Foundation

public enum WriteLength: String, Codable, Sendable, CaseIterable, Identifiable {
    case auto, short, medium, long
    public var id: String { rawValue }
    public var title: String { rawValue.capitalized }
    var instruction: String {
        switch self {
        case .auto: "Choose the length that fits the request and the app."
        case .short: "Keep it short: 1–2 sentences."
        case .medium: "Medium length: one short paragraph (3–5 sentences)."
        case .long: "Longer and detailed: 2–4 paragraphs."
        }
    }
}

public enum SuggestionPosition: String, Codable, Sendable, CaseIterable, Identifiable {
    /// Just outside the field (below it, or above when there's no room). Never covers your text.
    case outsideField
    /// Right after the cursor, inside the field, like ghost text.
    case nextToCursor
    public var id: String { rawValue }
    public var title: String { self == .outsideField ? "Below / above the field" : "Next to the cursor" }
}

public enum ProviderKind: String, Codable, Sendable, CaseIterable, Identifiable {
    case apple, ollama, gemini, openAI
    public var id: String { rawValue }
    public var title: String {
        switch self {
        case .apple: "Apple on-device"
        case .ollama: "Ollama (local)"
        case .gemini: "Google Gemini"
        case .openAI: "OpenAI-compatible"
        }
    }
    public var isLocal: Bool { self == .apple || self == .ollama }
}

/// All user settings. Every field has a default, and decoding tolerates missing or
/// unknown keys, so older saved settings keep working after updates.
public struct AppSettings: Codable, Sendable, Equatable {
    // General
    public var enabled = true
    public var ghostText = true
    public var debounceMs = 350
    public var maxWords = 10
    public var minCharsBeforeSuggesting = 3
    public var suggestOnEmptyFields = true
    /// Suggest as soon as a field is focused (memory recall, or how to start / continue), without typing.
    public var suggestOnFocus = true
    /// Read the visible text around the field (the email or chat you're replying to) for context.
    public var useScreenContext = true
    /// A short description of you and how you write, included in every prompt.
    public var aboutMe = ""
    /// Default length for "Ask AI to write".
    public var writeLength: WriteLength = .auto
    /// Where the suggestion appears.
    public var suggestionPosition: SuggestionPosition = .outsideField

    // Providers
    public var completionProvider: ProviderKind = .apple
    public var writingProvider: ProviderKind = .apple
    public var ollamaBaseURL = "http://localhost:11434"
    public var ollamaCompletionModel = "qwen2.5:1.5b"
    public var ollamaWritingModel = "qwen2.5:7b"
    public var geminiModel = "gemini-3.5-flash-lite"
    public var openAIBaseURL = "https://api.groq.com/openai/v1"
    public var openAIModel = "llama-3.1-8b-instant"
    public var requestTimeout: Double = 30

    // Memory
    public var memoryEnabled = true
    public var learnFromTyping = true
    public var memoryInPrompts = true

    // Apps
    public var excludedBundleIDs: [String] = AppSettings.defaultExclusions

    // Debug
    public var logLevel: Log.Level = .info

    public init() {}

    public static let defaultExclusions = [
        "com.1password.1password", "com.agilebits.onepassword7", "com.bitwarden.desktop",
        "com.apple.keychainaccess", "com.apple.Passwords",
        "com.apple.Terminal", "com.googlecode.iterm2", "dev.warp.Warp-Stable", "net.kovidgoyal.kitty",
        "com.mitchellh.ghostty", "com.microsoft.VSCode", "com.todesktop.230313mzl4w4u92", // Cursor
    ]

    public init(from decoder: Decoder) throws {
        self.init()
        let c = try decoder.container(keyedBy: CodingKeys.self)
        func d<T: Decodable>(_ k: CodingKeys, _ cur: T) -> T { (try? c.decodeIfPresent(T.self, forKey: k)) ?? cur }
        enabled = d(.enabled, enabled)
        ghostText = d(.ghostText, ghostText)
        debounceMs = max(50, min(2000, d(.debounceMs, debounceMs)))
        maxWords = max(1, min(40, d(.maxWords, maxWords)))
        minCharsBeforeSuggesting = max(0, d(.minCharsBeforeSuggesting, minCharsBeforeSuggesting))
        suggestOnEmptyFields = d(.suggestOnEmptyFields, suggestOnEmptyFields)
        suggestOnFocus = d(.suggestOnFocus, suggestOnFocus)
        useScreenContext = d(.useScreenContext, useScreenContext)
        aboutMe = d(.aboutMe, aboutMe)
        writeLength = d(.writeLength, writeLength)
        suggestionPosition = d(.suggestionPosition, suggestionPosition)
        completionProvider = d(.completionProvider, completionProvider)
        writingProvider = d(.writingProvider, writingProvider)
        ollamaBaseURL = d(.ollamaBaseURL, ollamaBaseURL)
        ollamaCompletionModel = d(.ollamaCompletionModel, ollamaCompletionModel)
        ollamaWritingModel = d(.ollamaWritingModel, ollamaWritingModel)
        geminiModel = d(.geminiModel, geminiModel)
        openAIBaseURL = d(.openAIBaseURL, openAIBaseURL)
        openAIModel = d(.openAIModel, openAIModel)
        requestTimeout = d(.requestTimeout, requestTimeout)
        memoryEnabled = d(.memoryEnabled, memoryEnabled)
        learnFromTyping = d(.learnFromTyping, learnFromTyping)
        memoryInPrompts = d(.memoryInPrompts, memoryInPrompts)
        excludedBundleIDs = d(.excludedBundleIDs, excludedBundleIDs)
        logLevel = d(.logLevel, logLevel)
    }

    private enum CodingKeys: String, CodingKey {
        case enabled, ghostText, debounceMs, maxWords, minCharsBeforeSuggesting, suggestOnEmptyFields
        case suggestOnFocus, useScreenContext, aboutMe, writeLength, suggestionPosition
        case completionProvider, writingProvider, ollamaBaseURL, ollamaCompletionModel, ollamaWritingModel
        case geminiModel, openAIBaseURL, openAIModel, requestTimeout
        case memoryEnabled, learnFromTyping, memoryInPrompts, excludedBundleIDs, logLevel
    }
}

/// Persists settings as JSON in UserDefaults and notifies observers on change.
public final class SettingsStore: @unchecked Sendable {
    public static let shared = SettingsStore()
    public static let didChange = Notification.Name("TypePilotSettingsDidChange")

    private let key = "settings.v1"
    private let defaults: UserDefaults
    private let lock = NSLock()
    private var cached: AppSettings

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let data = defaults.data(forKey: key), let s = try? JSONDecoder().decode(AppSettings.self, from: data) {
            cached = s
        } else {
            cached = AppSettings()
        }
        Log.minimumLevel = cached.logLevel
    }

    public var value: AppSettings {
        lock.lock(); defer { lock.unlock() }
        return cached
    }

    public func update(_ change: (inout AppSettings) -> Void) {
        lock.lock()
        var s = cached
        change(&s)
        cached = s
        lock.unlock()
        if let data = try? JSONEncoder().encode(s) { defaults.set(data, forKey: key) }
        Log.minimumLevel = s.logLevel
        NotificationCenter.default.post(name: Self.didChange, object: nil)
    }
}
