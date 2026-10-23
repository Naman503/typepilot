import AppKit
import SwiftUI
import TypePilotCore

/// A panel that can take keyboard focus without activating TypePilot (like Spotlight/Raycast):
/// the target app stays active, and focus returns to its field when the panel closes.
final class KeyablePanel: NSPanel {
    var onCancel: (() -> Void)?
    /// Focus went elsewhere (you clicked another app or window).
    var onResignKey: (() -> Void)?
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { false }
    override func cancelOperation(_ sender: Any?) { onCancel?() }
    override func resignKey() {
        super.resignKey()
        onResignKey?()
    }
}

@MainActor
final class AskModel: ObservableObject {
    enum Phase: Equatable { case idle, generating, done, failed(String) }
    @Published var request = ""
    @Published var output = ""
    @Published var phase: Phase = .idle
    @Published var contextLine = ""
    @Published var providerName = ""
    @Published var hasSelection = false
    @Published var insertTitle = "Insert ⏎"
    @Published var length: WriteLength = .auto
    /// Earlier versions of the draft, for Undo.
    @Published var history: [String] = []
    /// Set from the screen size: the panel never exceeds half the screen.
    @Published var panelWidth: CGFloat = 560
    @Published var outputMaxHeight: CGFloat = 260
    var lastSubmitted = ""

    var onSubmit: (_ request: String, _ refine: Bool) -> Void = { _, _ in }
    var onInsert: () -> Void = {}
    var onCancel: () -> Void = {}

    var hasDraft: Bool { !output.isEmpty && phase != .generating }

    /// ⏎: write; with a draft, a changed instruction revises it and an unchanged one inserts it.
    func submitOrInsert() {
        let r = request.trimmingCharacters(in: .whitespacesAndNewlines)
        if hasDraft, r.isEmpty || r == lastSubmitted { onInsert(); return }
        guard !r.isEmpty, phase != .generating else { return }
        let refine = hasDraft
        lastSubmitted = r
        onSubmit(r, refine)
    }

    func refine(_ instruction: String) {
        guard hasDraft else { return }
        request = ""
        lastSubmitted = ""
        onSubmit(instruction, true)
    }

    func undo() {
        guard let prev = history.popLast() else { return }
        output = prev
        phase = .done
    }

    func reset() {
        request = ""
        output = ""
        phase = .idle
        lastSubmitted = ""
        history = []
    }

    var quickActions: [String] {
        hasSelection
            ? ["Fix grammar and spelling", "Make it shorter", "Make it more formal", "Make it friendlier", "Translate to English"]
            : ["Reply to this", "Write a polite reply", "Write a short follow-up", "Write a thank-you note"]
    }
}

struct AskView: View {
    @ObservedObject var model: AskModel
    @FocusState private var focused: Bool

    private var placeholder: String {
        if model.hasDraft { return "Ask for a change (e.g. mention order 4521), or ⏎ to \(model.insertTitle.lowercased().replacingOccurrences(of: " ⏎", with: ""))" }
        return model.hasSelection ? "What should I do with the selected text?" : "e.g. write a short message to IndiGo about a refund I haven't received"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "sparkles")
                    .foregroundStyle(.white)
                    .frame(width: 22, height: 22)
                    .background(LinearGradient(colors: [.purple, .pink], startPoint: .topLeading, endPoint: .bottomTrailing),
                                in: RoundedRectangle(cornerRadius: 6))
                VStack(alignment: .leading, spacing: 0) {
                    Text("Ask AI to write").font(.system(size: 13, weight: .semibold))
                    if !model.contextLine.isEmpty {
                        Text(model.contextLine).font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                Spacer()
                Picker("", selection: $model.length) {
                    ForEach(WriteLength.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .frame(width: min(230, model.panelWidth * 0.42))
                .help("Length of the text")
                .onChange(of: model.length) { _, new in
                    // Changing the length with a draft on screen re-writes it to that length.
                    if model.hasDraft { model.refine(new == .auto ? "Adjust to a natural length." : "Adjust the length. \(new.title) version.") }
                }
                Button { model.onCancel() } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 15))
                        .foregroundStyle(.tertiary)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .help("Close (Esc)")
            }

            TextField(placeholder, text: $model.request, axis: .vertical)
                .textFieldStyle(.plain)
                .font(.system(size: 14))
                .lineLimit(1...4)
                .padding(10)
                .background(.background.opacity(0.6), in: RoundedRectangle(cornerRadius: 9))
                .overlay(RoundedRectangle(cornerRadius: 9).stroke(Color.accentColor.opacity(focused ? 0.6 : 0.15), lineWidth: 1))
                .focused($focused)
                .onSubmit { model.submitOrInsert() }

            if model.output.isEmpty && model.phase == .idle {
                chips(model.quickActions) { q in model.request = q; model.submitOrInsert() }
            }

            if !model.output.isEmpty || model.phase == .generating {
                ScrollView {
                    Text(model.output.isEmpty ? "Writing…" : model.output)
                        .font(.system(size: 13))
                        .foregroundStyle(model.output.isEmpty ? .secondary : .primary)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(10)
                }
                // ScrollViews don't size to their content: measure the text so the panel grows with the
                // draft, up to the half-screen limit, and scrolls beyond that.
                .frame(height: min(model.outputMaxHeight, max(60, Self.textHeight(model.output.isEmpty ? "Writing…" : model.output, width: model.panelWidth - 48) + 22)))
                .background(.quaternary.opacity(0.35), in: RoundedRectangle(cornerRadius: 9))
            }

            if model.hasDraft {
                chips(Prompts.refinements.map(\.title)) { title in
                    if let r = Prompts.refinements.first(where: { $0.title == title }) { model.refine(r.instruction) }
                }
            }

            if case .failed(let msg) = model.phase {
                Label(msg, systemImage: "exclamationmark.triangle.fill")
                    .font(.system(size: 12)).foregroundStyle(.red).lineLimit(3)
            }

            HStack(spacing: 8) {
                if model.phase == .generating { ProgressView().controlSize(.small) }
                Text(model.providerName).font(.system(size: 11)).foregroundStyle(.tertiary)
                if model.output.count > 0 {
                    Text("· \(model.output.split(whereSeparator: { $0.isWhitespace }).count) words").font(.system(size: 11)).foregroundStyle(.tertiary)
                }
                Spacer()
                if model.hasDraft {
                    if !model.history.isEmpty { Button("Undo") { model.undo() }.help("Back to the previous version") }
                    Button("Copy") {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(model.output, forType: .string)
                    }
                    Button(model.insertTitle) { model.onInsert() }
                        .keyboardShortcut(.defaultAction)
                        .buttonStyle(.borderedProminent)
                } else if model.phase != .generating {
                    Text("⏎ to write").font(.system(size: 11)).foregroundStyle(.tertiary)
                }
            }
            .controlSize(.small)
        }
        .padding(14)
        .frame(width: model.panelWidth)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(.white.opacity(0.08)))
        .onAppear { focused = true }
    }

    static func textHeight(_ text: String, width: CGFloat) -> CGFloat {
        let r = (text as NSString).boundingRect(with: NSSize(width: max(50, width), height: .greatestFiniteMagnitude),
                                                options: [.usesLineFragmentOrigin, .usesFontLeading],
                                                attributes: [.font: NSFont.systemFont(ofSize: 13)])
        return ceil(r.height)
    }

    @ViewBuilder private func chips(_ items: [String], action: @escaping (String) -> Void) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(items, id: \.self) { q in
                    Button(q) { action(q) }
                        .buttonStyle(.plain)
                        .font(.system(size: 12))
                        .padding(.horizontal, 9).padding(.vertical, 4)
                        .background(.quaternary.opacity(0.6), in: Capsule())
                        .disabled(model.phase == .generating)
                }
            }
        }
    }
}

@MainActor
final class AskPanelController {
    let model = AskModel()
    private let panel: KeyablePanel
    private let hosting: NSHostingView<AskView>

    init() {
        hosting = NSHostingView(rootView: AskView(model: model))
        // We size and place the panel ourselves (so it stays on screen); don't let SwiftUI resize the window.
        hosting.sizingOptions = [.intrinsicContentSize]
        panel = KeyablePanel(contentRect: NSRect(x: 0, y: 0, width: 560, height: 160),
                             styleMask: [.borderless, .nonactivatingPanel, .fullSizeContentView], backing: .buffered, defer: true)
        panel.isFloatingPanel = true
        panel.level = .popUpMenu
        panel.backgroundColor = .clear
        panel.isOpaque = false
        panel.hasShadow = true
        panel.hidesOnDeactivate = false
        panel.becomesKeyOnlyIfNeeded = false
        // Not on every Space: it opens on the desktop you're on and closes when you switch.
        panel.collectionBehavior = [.moveToActiveSpace, .fullScreenAuxiliary, .transient]
        panel.contentView = hosting
        panel.onCancel = { [weak self] in self?.model.onCancel() }
        panel.onResignKey = { [weak self] in
            // Clicking anywhere else closes it (like a menu). Ignore the brief hand-over right after opening.
            guard let self, self.panel.isVisible, Date().timeIntervalSince(self.shownAt) > 0.4 else { return }
            Log.debug("ask", "lost focus: closing")
            self.model.onCancel()
        }
    }

    var isVisible: Bool { panel.isVisible }
    var frame: NSRect { panel.frame }
    private var shownAt = Date.distantPast

    private var placeAbove = false
    private var anchorCocoa: CGRect?
    private var screenFrame: CGRect = .zero

    /// Show near the caret/field (AX coordinates), or near the mouse. The panel is sized to at most
    /// half the screen and opens below the field, or above it when there's more room there.
    func show(nearAX rect: CGRect?) {
        model.reset()
        let anchor = rect.map(AX.toCocoa)
        let point = anchor.map { CGPoint(x: $0.midX, y: $0.midY) } ?? NSEvent.mouseLocation
        let screen = NSScreen.best(for: anchor ?? CGRect(origin: point, size: .zero))
        let vf = screen?.visibleFrame ?? CGRect(x: 0, y: 0, width: 1440, height: 900)
        screenFrame = vf
        anchorCocoa = anchor ?? CGRect(origin: point, size: .zero)

        model.panelWidth = min(560, max(380, vf.width * 0.5))
        // Header, input, chips and footer take ~200 pt; the draft area gets the rest of half the screen.
        model.outputMaxHeight = max(90, vf.height * 0.5 - 200)

        let a = anchorCocoa!
        placeAbove = (a.minY - vf.minY) < vf.height * 0.5 && (vf.maxY - a.maxY) > (a.minY - vf.minY)
        let size = hosting.fittingSize
        panel.setFrame(clamped(NSRect(origin: origin(for: size), size: size)), display: true)
        shownAt = Date()
        panel.makeKeyAndOrderFront(nil)
        observeSize()
    }

    /// Below the anchor (top edge fixed) or above it (bottom edge fixed), left-aligned with it.
    private func origin(for size: CGSize) -> CGPoint {
        let a = anchorCocoa ?? .zero
        let x = a.minX - 8
        let y = placeAbove ? a.maxY + 8 : a.minY - size.height - 8
        return CGPoint(x: x, y: y)
    }

    /// Keep the whole panel on screen.
    private func clamped(_ f: NSRect) -> NSRect {
        var r = f
        let vf = screenFrame
        r.size.height = min(r.height, vf.height - 16)
        r.origin.x = min(max(r.minX, vf.minX + 8), vf.maxX - r.width - 8)
        r.origin.y = min(max(r.minY, vf.minY + 8), vf.maxY - r.height - 8)
        return r
    }

    func hide() { panel.orderOut(nil) }

    /// Re-fit as the draft grows: the edge next to the field stays put, and the panel never leaves the screen.
    private func observeSize() {
        Task { @MainActor in
            while panel.isVisible {
                let s = hosting.fittingSize
                var f = panel.frame
                if s != f.size {
                    if !placeAbove { f.origin.y += f.height - s.height } // grow downward from a fixed top
                    f.size = s
                }
                let target = clamped(f)
                if target != panel.frame { panel.setFrame(target, display: true, animate: false) }
                try? await Task.sleep(for: .milliseconds(60))
            }
        }
    }
}
