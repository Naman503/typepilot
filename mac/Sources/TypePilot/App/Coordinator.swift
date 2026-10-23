import AppKit
import TypePilotCore

/// Orchestrates everything:
///  - focusing a field → instant memory recall, then (after reading the screen) an AI "how to start" suggestion
///  - keystrokes → debounce → read the field → engine → suggestion pill at the caret
///  - Tab (or a click on it) accepts, ⌥→ accepts one word, Esc or ✕ dismisses, ⌥Space opens "Ask AI to write…"
///  - clicking outside the field, leaving it, or switching desktops (Spaces) closes the popups
///  - leaving a field you typed in (or pressing Return) sends its text to memory
@MainActor
final class Coordinator: ObservableObject {
    enum Status: Equatable { case active, paused, disabled, needsPermission }

    @Published private(set) var status: Status = .needsPermission
    @Published private(set) var lastSuggestionInfo = ""

    let settings = SettingsStore.shared
    let memory: MemoryStore?
    let engine: AssistantEngine

    private let ghost = GhostPanel()
    private let ask = AskPanelController()
    private var keyTap: KeyTap?
    private var debounce: DispatchWorkItem?
    private var suggestTask: Task<Void, Never>?
    private var focusTask: Task<Void, Never>?
    private var writeTask: Task<Void, Never>?
    private var focusTimer: Timer?
    private var pausedUntil: Date?

    /// The field the user is working in, whether they typed in it, and what's on screen around it.
    private var current: FocusedField?
    private var typedInCurrent = false
    private var screenContext = ""
    private var screenContextElement: AXUIElement?
    private var dismissedText: String?
    private var askTarget: FocusedField?
    /// The suggestion being shown, or waiting for free space (another popup is in the way).
    private var pending: (suggestion: Suggestion, field: FocusedField)?
    private var obstacleSignature = ""
    /// Where the field with the pending suggestion is (Cocoa), for "clicked outside it".
    private var pendingFieldCocoa: CGRect?
    private var mouseMonitor: Any?

    init() {
        var store: MemoryStore?
        do { store = try MemoryStore() } catch { Log.error("memory", "could not open memory: \(error)") }
        memory = store
        engine = AssistantEngine(settings: SettingsStore.shared, memory: store)
        NotificationCenter.default.addObserver(forName: MemoryStore.didChange, object: nil, queue: .main) { [weak self] _ in
            guard let self else { return }
            Task { await self.engine.memoryChanged() }
        }
        NotificationCenter.default.addObserver(forName: SettingsStore.didChange, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.refreshStatus() }
        }
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didActivateApplicationNotification, object: nil, queue: .main) { [weak self] note in
            MainActor.assumeIsolated {
                guard let self, let app = note.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication else { return }
                self.appActivated(app)
            }
        }
        // Switching desktops: the popups belong to the field on the desktop you left.
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.activeSpaceDidChangeNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { [weak self] in self?.spaceChanged() }
        }
        ghost.onAccept = { [weak self] in self?.accept() }
        ghost.onClose = { [weak self] in self?.closeGhostByUser() }
    }

    // MARK: Lifecycle

    func start() {
        if !AX.isTrusted() {
            status = .needsPermission
            Log.warn("app", "Accessibility permission not granted yet")
            Timer.scheduledTimer(withTimeInterval: 1.5, repeats: true) { [weak self] t in
                MainActor.assumeIsolated {
                    guard let self else { t.invalidate(); return }
                    if AX.isTrusted() { t.invalidate(); self.start() }
                }
            }
            return
        }
        if keyTap == nil {
            keyTap = KeyTap { [weak self] key in
                MainActor.assumeIsolated { self?.handle(key) ?? false }
            }
        }
        if keyTap?.start() != true {
            status = .needsPermission
            return
        }
        focusTimer?.invalidate()
        focusTimer = Timer.scheduledTimer(withTimeInterval: 0.3, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.pollFocus() }
        }
        if mouseMonitor == nil {
            // Clicks in other apps (ours, on the pill or the Ask panel, never arrive here).
            mouseMonitor = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown, .otherMouseDown]) { [weak self] _ in
                MainActor.assumeIsolated { self?.clickedElsewhere(at: NSEvent.mouseLocation) }
            }
        }
        if let app = NSWorkspace.shared.frontmostApplication { AppAdapters.prepare(app) }
        prewarm()
        refreshStatus()
        Log.info("app", "TypePilot is running")
    }

    func refreshStatus() {
        let s = settings.value
        if !AX.isTrusted() || keyTap?.isRunning != true { status = .needsPermission }
        else if !s.enabled { status = .disabled }
        else if let p = pausedUntil, p > Date() { status = .paused }
        else { status = .active }
        if status != .active { dismissGhost() }
    }

    func pause(minutes: Int) {
        pausedUntil = Date().addingTimeInterval(TimeInterval(minutes * 60))
        refreshStatus()
        DispatchQueue.main.asyncAfter(deadline: .now() + .seconds(minutes * 60) + 1) { [weak self] in self?.refreshStatus() }
    }

    func resume() {
        pausedUntil = nil
        refreshStatus()
    }

    private var isActive: Bool {
        if let p = pausedUntil, p > Date() { return false }
        return settings.value.enabled
    }

    private func prewarm() {
        let s = settings.value
        if s.completionProvider == .apple || s.writingProvider == .apple {
            AppleModel.prewarm(instructions: Prompts.completionSystem)
        }
    }

    /// Never suggest here, whatever the settings say: TypePilot itself and the lock/login screen.
    private static let alwaysExcluded: Set<String> = ["com.apple.loginwindow", "com.apple.ScreenSaver.Engine", "com.apple.SecurityAgent"]

    private func isExcluded(_ bundleID: String) -> Bool {
        bundleID == Bundle.main.bundleIdentifier || Self.alwaysExcluded.contains(bundleID) || settings.value.excludedBundleIDs.contains(bundleID)
    }

    private func isUsable(_ field: FocusedField) -> Bool {
        !isExcluded(field.bundleID) && !field.isSecure && !SensitiveFilter.isSensitiveLabel(field.label)
    }

    // MARK: Keys

    /// Returns true to swallow the key.
    private func handle(_ key: KeyTap.Key) -> Bool {
        guard isActive else { return false }
        if ask.isVisible { return false } // typing into our own panel
        let mods = key.flags.intersection([.maskCommand, .maskControl, .maskAlternate, .maskShift])

        // ⌥Space: Ask AI to write
        if key.keyCode == KeyCode.space && mods == .maskAlternate {
            openAsk()
            return true
        }

        if ghost.isVisible {
            if key.keyCode == KeyCode.tab && mods.isEmpty {
                accept()
                return true
            }
            if key.keyCode == KeyCode.rightArrow && mods == .maskAlternate, ghost.suggestion?.replaceLength == 0 {
                acceptWord()
                return true
            }
            if key.keyCode == KeyCode.escape && mods.isEmpty {
                closeGhostByUser()
                return true
            }
            dismissGhost()
        }

        // Return sends chat messages and clears the field: capture the text before it disappears.
        if (key.keyCode == KeyCode.returnKey || key.keyCode == KeyCode.enterKey) && !mods.contains(.maskShift) {
            learnFromFocusedField(reason: "return")
        }

        if mods.contains(.maskCommand) && key.keyCode != KeyCode.v { return false } // shortcuts, not typing
        typedInCurrent = true
        focusTask?.cancel()
        scheduleSuggestion()
        return false
    }

    private func scheduleSuggestion(after ms: Int? = nil) {
        debounce?.cancel()
        suggestTask?.cancel()
        let s = settings.value
        guard s.ghostText else { return }
        let work = DispatchWorkItem { [weak self] in
            MainActor.assumeIsolated { self?.requestSuggestion(trigger: .typing) }
        }
        debounce = work
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(ms ?? s.debounceMs), execute: work)
    }

    // MARK: Suggestions

    private func requestSuggestion(trigger: SuggestTrigger) {
        guard isActive, settings.value.ghostText, let field = AX.focusedField() else { dismissGhost(); return }
        trackFocus(field, typed: trigger == .typing)
        guard isUsable(field), field.caretAtLineEnd else { return }
        var ctx = field.context()
        if let el = screenContextElement, CFEqual(el, field.element) { ctx.screenContext = screenContext }
        if let d = dismissedText, d == ctx.textBefore { return }
        dismissedText = nil

        suggestTask?.cancel()
        let started = Date()
        suggestTask = Task { [weak self] in
            guard let self else { return }
            guard let s = await self.engine.suggest(ctx, trigger: trigger), !Task.isCancelled else { return }
            // Drop it if the user kept typing or moved the caret meanwhile.
            guard let now = AX.focusedField(), now.isSameElement(field), now.value == field.value,
                  now.selection.location == field.selection.location else { return }
            self.present(s, for: now)
            let ms = Int(Date().timeIntervalSince(started) * 1000)
            self.lastSuggestionInfo = "\(s.source.rawValue) · \(ms) ms\(s.model.map { " · \($0)" } ?? "")"
            Log.info("suggest", "\(trigger == .focus ? "on focus" : "typing") · \(s.source.rawValue) in \(ms) ms · \(field.appName): \(s.text.prefix(50))")
        }
    }

    /// Focus moved to a field: memory recall right away, then read the screen and ask for an AI suggestion.
    private func fieldFocused(_ field: FocusedField) {
        focusTask?.cancel()
        guard isActive, isUsable(field) else { return }
        let s = settings.value
        if s.ghostText { requestSuggestion(trigger: .focus) } // instant memory / cached context
        focusTask = Task { [weak self] in
            guard let self else { return }
            var context = ""
            if s.useScreenContext {
                context = await Task.detached(priority: .userInitiated) { ScreenContext.read(for: field) }.value
            }
            guard !Task.isCancelled, let now = AX.focusedField(), now.isSameElement(field) else { return }
            self.screenContext = context
            self.screenContextElement = field.element
            // Only if the user hasn't started typing and nothing is showing yet.
            if s.ghostText, s.suggestOnFocus, !self.ghost.isVisible, now.value == field.value {
                self.requestSuggestion(trigger: .focus)
            }
        }
    }

    private func accept() {
        guard let s = ghost.suggestion else { return }
        dismissGhost()
        guard let field = AX.focusedField() else { return }
        _ = TextInserter.insert(s.text, into: field, replacing: s.replaceLength)
        if s.source == .memory { memory?.markUsed(text: s.text) }
        typedInCurrent = true
        // Keep the flow going: offer the next chunk shortly after.
        scheduleSuggestion(after: 300)
    }

    /// ⌥→: accept just the next word and keep the rest showing.
    private func acceptWord() {
        guard let s = ghost.suggestion, let field = AX.focusedField() else { return }
        let text = s.text
        guard let r = text.range(of: #"^\s*\S+"#, options: .regularExpression) else { accept(); return }
        let word = String(text[r])
        let rest = String(text[r.upperBound...])
        _ = TextInserter.insert(word, into: field)
        typedInCurrent = true
        guard !rest.trimmingCharacters(in: .whitespaces).isEmpty else { dismissGhost(); scheduleSuggestion(after: 300); return }
        var next = s
        next.text = rest
        dismissGhost()
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(90)) { [weak self] in
            guard let self, let now = AX.focusedField() else { return }
            self.present(next, for: now)
        }
    }

    // MARK: Placement

    /// Show a suggestion where it covers neither the field's text nor any other popup near it.
    /// If there's no free spot (e.g. the app's own autocomplete list is open) it waits, and
    /// `recheckPlacement()` shows it once the way is clear.
    private func present(_ s: Suggestion, for field: FocusedField) {
        // Same suggestion for the same field already showing: don't re-place (avoids flicker).
        if let p = pending, p.suggestion.text == s.text, p.field.isSameElement(field), ghost.isVisible { return }
        pending = (s, field)
        place()
    }

    private func place() {
        guard let (s, field) = pending else { return }
        let caret = field.caretRect()
        let frame = field.frame()
        let fieldCocoa = (frame ?? caret).map(AX.toCocoa) ?? .zero
        pendingFieldCocoa = (frame ?? caret).map(AX.toCocoa)
        // A field whose window is on another desktop (or hidden) still reports focus: show nothing for it.
        let visible = (frame ?? caret).map { ScreenObstacles.isOnScreen(fieldAX: $0, pid: field.pid) } ?? true
        guard visible else {
            obstacleSignature = "offscreen"
            if ghost.isVisible { Log.info("ghost", "field isn't on this screen/desktop; hiding") }
            ghost.hide()
            return
        }
        let windowAX = AX.element(AXUIElementCreateApplication(field.pid), kAXFocusedWindowAttribute).flatMap(AX.frame)
        let avoid = ScreenObstacles.popups(near: fieldCocoa, frontPID: field.pid, focusedWindowAX: windowAX)
        let listOpen = ScreenObstacles.fieldPopupOpen(field)
        obstacleSignature = Self.signature(avoid, listOpen)
        let shown = ghost.show(s, caretAX: caret, fieldAX: frame, avoid: avoid, fieldPopupOpen: listOpen,
                               position: settings.value.suggestionPosition)
        if !shown { Log.debug("ghost", "waiting: \(avoid.count) popup(s) near the field\(listOpen ? ", its list is open" : "")") }
    }

    /// Popups come and go (browser autofill opens a moment later): move out of the way or reappear.
    private func recheckPlacement() {
        guard let (_, field) = pending else { return }
        let frame = field.frame() ?? field.caretRect()
        let fieldCocoa = frame.map(AX.toCocoa) ?? .zero
        if let frame, !ScreenObstacles.isOnScreen(fieldAX: frame, pid: field.pid) {
            if obstacleSignature != "offscreen" { place() }
            return
        }
        let windowAX = AX.element(AXUIElementCreateApplication(field.pid), kAXFocusedWindowAttribute).flatMap(AX.frame)
        let sig = Self.signature(ScreenObstacles.popups(near: fieldCocoa, frontPID: field.pid, focusedWindowAX: windowAX),
                                 ScreenObstacles.fieldPopupOpen(field))
        // The field moved (scrolling, window dragged) or popups changed: place again.
        if sig != obstacleSignature || fieldCocoa != (pendingFieldCocoa ?? .zero) { place() }
    }

    private static func signature(_ rects: [CGRect], _ listOpen: Bool) -> String {
        rects.map { "\(Int($0.minX)),\(Int($0.minY)),\(Int($0.width)),\(Int($0.height))" }.sorted().joined(separator: ";") + (listOpen ? "|open" : "")
    }

    private func dismissGhost() {
        pending = nil
        pendingFieldCocoa = nil
        ghost.hide()
    }

    /// Esc or ✕: close it, and don't offer the same thing again until the text changes.
    private func closeGhostByUser() {
        dismissedText = AX.focusedField()?.textBefore ?? pending?.field.textBefore
        focusTask?.cancel()
        suggestTask?.cancel()
        debounce?.cancel()
        dismissGhost()
        Log.debug("ghost", "closed by user")
    }

    /// A click in another app: outside the field closes the suggestion, outside the panel closes Ask.
    private func clickedElsewhere(at p: CGPoint) {
        if ask.isVisible, !ask.frame.contains(p) {
            Log.debug("ask", "clicked outside: closing")
            closeAsk()
        }
        guard pending != nil else { return }
        if ghost.isVisible, ghost.frame.contains(p) { return }
        if let f = pendingFieldCocoa, f.insetBy(dx: -4, dy: -4).contains(p) { return } // pollFocus handles caret moves
        suggestTask?.cancel()
        focusTask?.cancel()
        dismissGhost()
    }

    private func spaceChanged() {
        suggestTask?.cancel()
        focusTask?.cancel()
        dismissGhost()
        if ask.isVisible { closeAsk() }
        // Treat whatever is focused on the new desktop as freshly focused.
        learn(from: current)
        current = nil
        typedInCurrent = false
        Log.debug("app", "switched desktop")
    }

    // MARK: Focus tracking & learning

    private func trackFocus(_ field: FocusedField, typed: Bool) {
        if !field.isSameElement(current) {
            learn(from: current)
            current = field
            typedInCurrent = typed
            if let el = screenContextElement, !CFEqual(el, field.element) { screenContext = ""; screenContextElement = nil }
        } else {
            current = field
            if typed { typedInCurrent = true }
        }
    }

    /// Polls focus so we notice entering and leaving fields (clicks, Tab between fields).
    private func pollFocus() {
        guard isActive, !ask.isVisible else { return }
        // Cheap path: same element as before → only check whether the caret moved (a click).
        if let cur = current, let el = AX.focusedElement(), CFEqual(el, cur.element) {
            if pending != nil, let r = AX.range(el), r.location != cur.selection.location || r.length != cur.selection.length {
                dismissGhost()
            } else if pending != nil {
                recheckPlacement()
            }
            return
        }
        guard let field = AX.focusedField() else {
            if current != nil { learn(from: current); current = nil; typedInCurrent = false; dismissGhost(); focusTask?.cancel() }
            return
        }
        dismissGhost()
        trackFocus(field, typed: false)
        fieldFocused(field)
    }

    private func learnFromFocusedField(reason: String) {
        guard let field = AX.focusedField(), isUsable(field) else { return }
        let text = field.value
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let ctx = field.context()
        Task { await engine.learn(text: text, ctx: ctx, kind: .typed) }
        Log.debug("memory", "captured field on \(reason) in \(field.appName)")
    }

    private func learn(from field: FocusedField?) {
        guard let field, typedInCurrent, settings.value.learnFromTyping, isUsable(field) else { return }
        // Re-read the final value of the field you just left.
        let text = AX.string(field.element, kAXValueAttribute) ?? field.value
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let ctx = field.context()
        Task { await engine.learn(text: text, ctx: ctx, kind: .typed) }
    }

    private func appActivated(_ app: NSRunningApplication) {
        dismissGhost()
        if app.bundleIdentifier == Bundle.main.bundleIdentifier { return }
        learn(from: current)
        current = nil
        typedInCurrent = false
        AppAdapters.prepare(app)
    }

    // MARK: Ask AI to write

    func openAsk() {
        dismissGhost()
        suggestTask?.cancel()
        focusTask?.cancel()
        let field = AX.focusedField()
        askTarget = field.flatMap { isUsable($0) ? $0 : nil }
        let m = ask.model
        m.reset()
        let s = settings.value
        m.providerName = AssistantEngine.model(for: s.writingProvider, writing: true, settings: s).name
        m.hasSelection = !(askTarget?.selectedText.isEmpty ?? true)
        m.length = s.writeLength
        if let f = askTarget {
            let what = m.hasSelection ? "Rewriting \(f.selectedText.count) selected characters" : "Writing into \(f.label.isEmpty ? "the field" : "“\(f.label)”")"
            m.contextLine = "\(what) · \(f.appName)"
            m.insertTitle = m.hasSelection ? "Replace ⏎" : "Insert ⏎"
        } else {
            m.contextLine = "No readable field here: the result will be pasted where your cursor is"
            m.insertTitle = "Paste ⏎"
        }
        m.onSubmit = { [weak self] request, refine in self?.runWrite(request, refine: refine) }
        m.onInsert = { [weak self] in self?.insertAskResult() }
        m.onCancel = { [weak self] in self?.closeAsk() }
        let anchor = askTarget.flatMap { $0.caretRect() ?? $0.frame() }
        ask.show(nearAX: anchor)
        // Read the screen in the background so "reply to this" requests have context.
        if let f = askTarget, s.useScreenContext {
            Task { [weak self] in
                let c = await Task.detached(priority: .userInitiated) { ScreenContext.read(for: f) }.value
                self?.screenContext = c
                self?.screenContextElement = f.element
            }
        }
        Log.info("ask", "opened in \(askTarget?.appName ?? NSWorkspace.shared.frontmostApplication?.localizedName ?? "?")")
    }

    private func runWrite(_ request: String, refine: Bool) {
        writeTask?.cancel()
        let m = ask.model
        let previous = refine ? m.output : nil
        if let previous { m.history.append(previous) }
        m.phase = .generating
        if !refine { m.output = "" }
        var ctx = askTarget?.context() ?? FieldContext(appName: NSWorkspace.shared.frontmostApplication?.localizedName ?? "")
        if let el = screenContextElement, let t = askTarget, CFEqual(el, t.element) { ctx.screenContext = screenContext }
        let length = m.length
        writeTask = Task { [weak self] in
            guard let self else { return }
            do {
                for try await text in await self.engine.write(request, ctx: ctx, length: length, previousDraft: previous) {
                    m.output = text.trimmingCharacters(in: .whitespacesAndNewlines)
                }
                m.phase = m.output.isEmpty ? .failed("The model returned nothing. Try rephrasing.") : .done
            } catch is CancellationError {
                m.phase = m.output.isEmpty ? .idle : .done
            } catch {
                m.phase = .failed(error.localizedDescription)
                Log.warn("ask", "write failed: \(error.localizedDescription)")
            }
        }
    }

    private func insertAskResult() {
        let text = ask.model.output
        guard !text.isEmpty else { return }
        writeTask?.cancel()
        ask.hide()
        guard let target = askTarget else {
            // No readable field: paste into whatever has focus (works in almost any app).
            DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(150)) { _ = TextInserter.paste(text) }
            return
        }
        // Focus returns to the target app's field once our panel closes; make sure, then insert.
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(120)) {
            AXUIElementSetAttributeValue(target.element, kAXFocusedAttribute as CFString, kCFBooleanTrue)
            AX.setRange(target.element, target.selection)
            let field = AX.focusedField().flatMap { $0.isSameElement(target) ? $0 : nil } ?? target
            _ = TextInserter.insert(text, into: field)
        }
    }

    private func closeAsk() {
        writeTask?.cancel()
        ask.hide()
    }
}
