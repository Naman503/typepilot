import AppKit
import SwiftUI
import TypePilotCore

/// Bridges SettingsStore into SwiftUI bindings (every change is saved immediately).
@MainActor
final class SettingsModel: ObservableObject {
    @Published var s: AppSettings
    private var token: NSObjectProtocol?

    init() {
        s = SettingsStore.shared.value
        token = NotificationCenter.default.addObserver(forName: SettingsStore.didChange, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.s = SettingsStore.shared.value }
        }
    }

    func binding<T>(_ kp: WritableKeyPath<AppSettings, T>) -> Binding<T> {
        Binding(get: { self.s[keyPath: kp] }, set: { v in
            self.s[keyPath: kp] = v
            SettingsStore.shared.update { $0[keyPath: kp] = v }
        })
    }
}

enum SettingsTab: String, CaseIterable, Identifiable {
    case general = "General", ai = "AI", memory = "Memory", apps = "Apps", permissions = "Permissions", logs = "Logs", about = "About"
    var id: String { rawValue }
    var icon: String {
        switch self {
        case .general: "gearshape"
        case .ai: "sparkles"
        case .memory: "brain"
        case .apps: "square.grid.2x2"
        case .permissions: "lock.shield"
        case .logs: "doc.text.magnifyingglass"
        case .about: "info.circle"
        }
    }
}

struct SettingsView: View {
    @ObservedObject var coordinator: Coordinator
    @StateObject private var model = SettingsModel()
    @State var tab: SettingsTab

    var body: some View {
        NavigationSplitView {
            List(SettingsTab.allCases, selection: Binding(get: { tab }, set: { if let t = $0 { tab = t } })) { t in
                Label(t.rawValue, systemImage: t.icon).tag(t)
            }
            .navigationSplitViewColumnWidth(min: 170, ideal: 180)
        } detail: {
            Group {
                switch tab {
                case .general: GeneralPane(model: model, coordinator: coordinator)
                case .ai: AIPane(model: model)
                case .memory: MemoryPane(model: model, memory: coordinator.memory)
                case .apps: AppsPane(model: model)
                case .permissions: PermissionsPane(coordinator: coordinator)
                case .logs: LogsPane(model: model)
                case .about: AboutPane()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
        .frame(minWidth: 760, minHeight: 520)
    }
}

// MARK: - General

struct GeneralPane: View {
    @ObservedObject var model: SettingsModel
    @ObservedObject var coordinator: Coordinator

    var body: some View {
        Form {
            Section {
                HStack(spacing: 12) {
                    Image(systemName: statusIcon).font(.title2).foregroundStyle(statusColor)
                    VStack(alignment: .leading) {
                        Text(statusTitle).font(.headline)
                        Text(coordinator.lastSuggestionInfo.isEmpty ? "Start typing in any app." : "Last suggestion: \(coordinator.lastSuggestionInfo)")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            Section("Suggestions") {
                Toggle("Enable TypePilot", isOn: model.binding(\.enabled))
                Toggle("Inline suggestions", isOn: model.binding(\.ghostText))
                Toggle(isOn: model.binding(\.suggestOnFocus)) {
                    Text("Suggest as soon as a field is focused")
                    Text("No need to start typing: empty fields get your usual value or how to start (e.g. a reply).").font(.caption).foregroundStyle(.secondary)
                }
                Toggle(isOn: model.binding(\.useScreenContext)) {
                    Text("Read what's on screen for context")
                    Text("Uses the visible text near the field (the email or chat you're replying to) so suggestions fit.").font(.caption).foregroundStyle(.secondary)
                }
                Toggle("Offer remembered values in empty fields", isOn: model.binding(\.suggestOnEmptyFields))
                Picker(selection: model.binding(\.suggestionPosition)) {
                    ForEach(SuggestionPosition.allCases) { Text($0.title).tag($0) }
                } label: {
                    Text("Where suggestions appear")
                    Text("Below/above the field never covers your text, and moves out of the way of browser autofill and other popups.").font(.caption).foregroundStyle(.secondary)
                }
                LabeledContent("Wait after typing") {
                    HStack {
                        Slider(value: Binding(get: { Double(model.s.debounceMs) }, set: { model.binding(\.debounceMs).wrappedValue = Int($0) }), in: 100...1200, step: 50)
                        Text("\(model.s.debounceMs) ms").monospacedDigit().frame(width: 64, alignment: .trailing)
                    }
                }
                Stepper("Suggestion length: up to \(model.s.maxWords) words", value: model.binding(\.maxWords), in: 2...30)
                Stepper("Start after \(model.s.minCharsBeforeSuggesting) characters", value: model.binding(\.minCharsBeforeSuggesting), in: 0...20)
            }
            Section {
                TextEditor(text: model.binding(\.aboutMe))
                    .font(.body)
                    .frame(minHeight: 70)
                Picker("Default length for “Ask AI to write”", selection: model.binding(\.writeLength)) {
                    ForEach(WriteLength.allCases) { Text($0.title).tag($0) }
                }
            } header: {
                Text("About me & writing style")
            } footer: {
                Text("e.g. “I'm Naman, a software engineer in Bengaluru. I write short, friendly, professional messages in English and sign off with ‘Thanks, Naman’.” Used in every suggestion and draft.")
            }
            Section("Keyboard") {
                LabeledContent("Accept suggestion", value: "Tab")
                LabeledContent("Accept next word", value: "⌥ →")
                LabeledContent("Dismiss suggestion", value: "Esc")
                LabeledContent("Ask AI to write / rewrite selection", value: "⌥ Space")
                LabeledContent("Text shortcut", value: ";name (see Memory)")
            }
        }
        .formStyle(.grouped)
    }

    private var statusTitle: String {
        switch coordinator.status {
        case .active: "TypePilot is active"
        case .paused: "Paused"
        case .disabled: "Turned off"
        case .needsPermission: "Needs Accessibility permission"
        }
    }
    private var statusIcon: String { coordinator.status == .active ? "checkmark.circle.fill" : coordinator.status == .needsPermission ? "exclamationmark.triangle.fill" : "pause.circle.fill" }
    private var statusColor: Color { coordinator.status == .active ? .green : coordinator.status == .needsPermission ? .orange : .secondary }
}

// MARK: - AI

struct AIPane: View {
    @ObservedObject var model: SettingsModel
    @State private var geminiKey = Keychain.get("gemini") ?? ""
    @State private var openAIKey = Keychain.get("openai") ?? ""
    @State private var ollamaModels: [String] = []
    @State private var testResult: [ProviderKind: String] = [:]
    @State private var testing: ProviderKind?

    var body: some View {
        Form {
            Section {
                Picker("Inline suggestions", selection: model.binding(\.completionProvider)) {
                    ForEach(ProviderKind.allCases) { Text($0.title).tag($0) }
                }
                Picker("Ask AI to write", selection: model.binding(\.writingProvider)) {
                    ForEach(ProviderKind.allCases) { Text($0.title).tag($0) }
                }
            } header: {
                Text("Which model does what")
            } footer: {
                Text("Local models (Apple, Ollama) keep everything on your Mac. Cloud providers receive the text around your cursor, with secrets redacted.")
            }

            Section("Apple on-device (free, private)") {
                providerStatusRow(.apple)
            }

            Section("Ollama (local)") {
                TextField("Server", text: model.binding(\.ollamaBaseURL))
                modelField("Suggestions model", value: model.binding(\.ollamaCompletionModel))
                modelField("Writing model", value: model.binding(\.ollamaWritingModel))
                HStack {
                    Button("Refresh installed models") { Task { await loadOllama() } }
                    if !ollamaModels.isEmpty { Text("\(ollamaModels.count) installed").foregroundStyle(.secondary) }
                }
                providerStatusRow(.ollama)
                Text("Small models feel fastest for suggestions (e.g. qwen2.5:1.5b, gemma3:1b, llama3.2:3b). Install with `ollama pull <model>`.")
                    .font(.caption).foregroundStyle(.secondary)
            }

            Section("Google Gemini (free tier)") {
                SecureField("API key", text: $geminiKey).onChange(of: geminiKey) { _, v in Keychain.set(v.trimmingCharacters(in: .whitespaces), for: "gemini") }
                TextField("Model", text: model.binding(\.geminiModel))
                providerStatusRow(.gemini)
                Link("Get a free key at aistudio.google.com", destination: URL(string: "https://aistudio.google.com/app/apikey")!).font(.caption)
            }

            Section("OpenAI-compatible (Groq, OpenRouter, LM Studio…)") {
                TextField("Base URL", text: model.binding(\.openAIBaseURL))
                TextField("Model", text: model.binding(\.openAIModel))
                SecureField("API key", text: $openAIKey).onChange(of: openAIKey) { _, v in Keychain.set(v.trimmingCharacters(in: .whitespaces), for: "openai") }
                providerStatusRow(.openAI)
            }

            Section("Advanced") {
                Stepper("Request timeout: \(Int(model.s.requestTimeout)) s", value: model.binding(\.requestTimeout), in: 5...120, step: 5)
            }
        }
        .formStyle(.grouped)
        .task { await loadOllama() }
    }

    @ViewBuilder private func modelField(_ title: String, value: Binding<String>) -> some View {
        HStack {
            TextField(title, text: value)
            if !ollamaModels.isEmpty {
                Menu("Installed") { ForEach(ollamaModels, id: \.self) { m in Button(m) { value.wrappedValue = m } } }
                    .fixedSize()
            }
        }
    }

    @ViewBuilder private func providerStatusRow(_ kind: ProviderKind) -> some View {
        HStack {
            Button(testing == kind ? "Testing…" : "Test") { Task { await test(kind) } }.disabled(testing != nil)
            if let r = testResult[kind] {
                Text(r).font(.caption).foregroundStyle(r.hasPrefix("✓") ? .green : .red).lineLimit(2).textSelection(.enabled)
            }
        }
    }

    private func loadOllama() async {
        ollamaModels = (try? await OllamaModel(baseURL: model.s.ollamaBaseURL, model: "").listModels()) ?? []
    }

    private func test(_ kind: ProviderKind) async {
        testing = kind
        defer { testing = nil }
        let m = AssistantEngine.model(for: kind, writing: false, settings: model.s)
        let (ok, reason) = await m.availability()
        guard ok else { testResult[kind] = "✗ \(reason ?? "Unavailable")"; return }
        let t0 = Date()
        do {
            let out = try await m.generate(GenerationRequest(system: "Reply with exactly: OK", prompt: "Say OK", maxTokens: 8, temperature: 0))
            testResult[kind] = "✓ \(m.name) answered in \(Int(Date().timeIntervalSince(t0) * 1000)) ms: \(out.prefix(20))"
        } catch {
            testResult[kind] = "✗ \(error.localizedDescription)"
        }
    }
}

// MARK: - Memory

struct MemoryPane: View {
    @ObservedObject var model: SettingsModel
    let memory: MemoryStore?
    @State private var entries: [MemoryEntry] = []
    @State private var query = ""
    @State private var newText = ""
    @State private var newField = ""
    @State private var newShortcut = ""

    var filtered: [MemoryEntry] {
        query.isEmpty ? entries : entries.filter { $0.text.localizedCaseInsensitiveContains(query) || $0.field.localizedCaseInsensitiveContains(query) || $0.app.localizedCaseInsensitiveContains(query) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Form {
                Section {
                    Toggle("Use memory for instant suggestions", isOn: model.binding(\.memoryEnabled))
                    Toggle("Learn from what I type", isOn: model.binding(\.learnFromTyping))
                    Toggle("Share relevant memory with the AI model", isOn: model.binding(\.memoryInPrompts))
                } footer: {
                    Text("Stored only on this Mac. Passwords, secure fields, excluded apps, card numbers, one-time codes and keys are never saved.")
                }
                Section {
                    TextField("Text (e.g. your address, a standard reply)", text: $newText, axis: .vertical).lineLimit(1...4)
                    HStack {
                        TextField("Field label (optional, e.g. Address)", text: $newField)
                        TextField("Shortcut (optional, e.g. addr)", text: $newShortcut).frame(width: 190)
                        Button("Add") {
                            memory?.record(newText, app: "", field: newField, kind: .manual, shortcut: newShortcut)
                            newText = ""; newField = ""; newShortcut = ""; reload()
                        }.disabled(newText.trimmingCharacters(in: .whitespaces).count < 3)
                    }
                } header: {
                    Text("Add something to remember")
                } footer: {
                    Text("With a shortcut, typing ;addr anywhere offers the full text: press Tab to expand.")
                }
            }
            .formStyle(.grouped)
            .frame(height: 300)

            HStack {
                TextField("Search \(entries.count) memories", text: $query).textFieldStyle(.roundedBorder)
                Button("Clear all…", role: .destructive) {
                    let a = NSAlert()
                    a.messageText = "Delete everything TypePilot remembers?"
                    a.addButton(withTitle: "Delete all")
                    a.addButton(withTitle: "Cancel")
                    if a.runModal() == .alertFirstButtonReturn { memory?.clear(); reload() }
                }
            }
            .padding(.horizontal, 20).padding(.vertical, 8)

            List(filtered) { e in
                HStack(alignment: .top) {
                    Button { memory?.setPinned(id: e.id, !e.pinned); reload() } label: {
                        Image(systemName: e.pinned ? "pin.fill" : "pin").foregroundStyle(e.pinned ? Color.accentColor : .secondary)
                    }.buttonStyle(.plain).help(e.pinned ? "Unpin" : "Pin: always prefer this")
                    VStack(alignment: .leading, spacing: 2) {
                        Text(e.text).lineLimit(3).textSelection(.enabled)
                        Text([e.shortcut.isEmpty ? nil : ";\(e.shortcut)", e.field.isEmpty ? nil : "Field: \(e.field)", e.app.isEmpty ? nil : e.app, "used \(e.count)×", e.kind.rawValue]
                            .compactMap { $0 }.joined(separator: " · "))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button { setShortcut(e) } label: { Image(systemName: "keyboard") }.buttonStyle(.borderless).help("Set a text shortcut")
                    Button(role: .destructive) { memory?.delete(id: e.id); reload() } label: { Image(systemName: "trash") }.buttonStyle(.borderless)
                }
            }
        }
        .onAppear(perform: reload)
        .onReceive(NotificationCenter.default.publisher(for: MemoryStore.didChange)) { _ in reload() }
    }

    private func reload() { entries = memory?.all() ?? [] }

    private func setShortcut(_ e: MemoryEntry) {
        let a = NSAlert()
        a.messageText = "Text shortcut"
        a.informativeText = "Type ; followed by this word anywhere to insert:\n“\(e.text.prefix(120))”"
        let tf = NSTextField(string: e.shortcut)
        tf.placeholderString = "e.g. addr"
        tf.frame = NSRect(x: 0, y: 0, width: 220, height: 24)
        a.accessoryView = tf
        a.addButton(withTitle: "Save")
        a.addButton(withTitle: "Cancel")
        if a.runModal() == .alertFirstButtonReturn { memory?.setShortcut(id: e.id, tf.stringValue); reload() }
    }
}

// MARK: - Apps

struct AppsPane: View {
    @ObservedObject var model: SettingsModel

    var body: some View {
        Form {
            Section {
                ForEach(model.s.excludedBundleIDs, id: \.self) { id in
                    HStack {
                        Text(appName(id)).frame(maxWidth: .infinity, alignment: .leading)
                        Text(id).font(.caption).foregroundStyle(.secondary)
                        Button { model.binding(\.excludedBundleIDs).wrappedValue.removeAll { $0 == id } } label: { Image(systemName: "minus.circle") }.buttonStyle(.borderless)
                    }
                }
                Menu("Add a running app") {
                    ForEach(runningApps(), id: \.self) { id in
                        Button(appName(id)) { model.binding(\.excludedBundleIDs).wrappedValue.append(id) }
                    }
                }
                Button("Restore defaults") { model.binding(\.excludedBundleIDs).wrappedValue = AppSettings.defaultExclusions }
            } header: {
                Text("TypePilot stays completely off in these apps")
            } footer: {
                Text("Password managers, terminals and code editors are excluded by default. Password fields are always skipped in every app.")
            }
        }
        .formStyle(.grouped)
    }

    private func appName(_ id: String) -> String {
        if let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: id) {
            return FileManager.default.displayName(atPath: url.path).replacingOccurrences(of: ".app", with: "")
        }
        return id
    }

    private func runningApps() -> [String] {
        NSWorkspace.shared.runningApplications.filter { $0.activationPolicy == .regular }
            .compactMap(\.bundleIdentifier).filter { !model.s.excludedBundleIDs.contains($0) }.sorted()
    }
}

// MARK: - Permissions

struct PermissionsPane: View {
    @ObservedObject var coordinator: Coordinator
    @State private var trusted = AX.isTrusted()
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        Form {
            Section {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: trusted ? "checkmark.seal.fill" : "hand.raised.fill").font(.title).foregroundStyle(trusted ? .green : .orange)
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Accessibility").font(.headline)
                        Text("Lets TypePilot read the text field you're typing in, show suggestions at your cursor, and insert text. It's required.")
                            .font(.callout).foregroundStyle(.secondary)
                        if !trusted {
                            Text("1. Click “Open System Settings”.\n2. Turn on TypePilot under Privacy & Security → Accessibility.\n3. Come back. TypePilot starts automatically.")
                                .font(.callout)
                            HStack {
                                Button("Open System Settings") {
                                    _ = AX.isTrusted(prompt: true)
                                    NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")!)
                                }.buttonStyle(.borderedProminent)
                            }
                        } else {
                            Text("Granted ✓").foregroundStyle(.green)
                        }
                    }
                }
            }
            Section("Privacy") {
                Label("Password fields and secure input are never read.", systemImage: "lock.fill")
                Label("Memory is stored locally in ~/Library/Application Support/TypePilot.", systemImage: "internaldrive")
                Label("API keys are kept in your Keychain.", systemImage: "key.fill")
            }
        }
        .formStyle(.grouped)
        .onReceive(timer) { _ in
            let t = AX.isTrusted()
            if t != trusted { trusted = t; coordinator.refreshStatus() }
        }
    }
}

// MARK: - Logs

struct LogsPane: View {
    @ObservedObject var model: SettingsModel
    @State private var entries: [Log.Entry] = []
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Picker("Log level", selection: model.binding(\.logLevel)) {
                    ForEach(Log.Level.allCases, id: \.self) { Text($0.rawValue.capitalized).tag($0) }
                }.pickerStyle(.segmented).frame(width: 300)
                Spacer()
                Button("Copy") {
                    let text = entries.map { "\($0.date.formatted(date: .omitted, time: .standard)) [\($0.level.rawValue)] \($0.category): \($0.message)" }.joined(separator: "\n")
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(text, forType: .string)
                }
                Button("Clear") { Log.clear(); entries = [] }
            }
            .padding(12)
            List(entries.reversed()) { e in
                HStack(alignment: .top, spacing: 8) {
                    Text(e.date.formatted(date: .omitted, time: .standard)).foregroundStyle(.secondary).monospacedDigit()
                    Text(e.level.rawValue.uppercased()).foregroundStyle(color(e.level)).frame(width: 44, alignment: .leading)
                    Text(e.category).foregroundStyle(.secondary).frame(width: 64, alignment: .leading)
                    Text(e.message).textSelection(.enabled)
                }
                .font(.system(size: 11, design: .monospaced))
            }
            Text("Live log in Terminal: log stream --predicate 'subsystem == \"com.typepilot.app\"' --level debug")
                .font(.caption).foregroundStyle(.secondary).textSelection(.enabled).padding(8)
        }
        .onAppear { entries = Log.recent() }
        .onReceive(timer) { _ in entries = Log.recent() }
    }

    private func color(_ l: Log.Level) -> Color {
        switch l { case .debug: .secondary; case .info: .blue; case .warn: .orange; case .error: .red }
    }
}

// MARK: - About

struct AboutPane: View {
    var body: some View {
        VStack(spacing: 14) {
            Image(nsImage: NSApp.applicationIconImage).resizable().frame(width: 88, height: 88)
            Text("TypePilot").font(.largeTitle.bold())
            Text("AI suggestions and autocomplete in every text field.").foregroundStyle(.secondary)
            Text("Version \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev")")
                .font(.caption).foregroundStyle(.tertiary)
            Divider().frame(width: 260)
            HStack(spacing: 12) {
                Text("NP").font(.headline).foregroundStyle(.white).frame(width: 40, height: 40)
                    .background(LinearGradient(colors: [.purple, .pink, .orange], startPoint: .topLeading, endPoint: .bottomTrailing), in: RoundedRectangle(cornerRadius: 10))
                VStack(alignment: .leading) {
                    Text("Developed by Naman Pathak").font(.headline)
                    Link("namanpathak503@gmail.com", destination: URL(string: "mailto:namanpathak503@gmail.com")!)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(40)
    }
}
