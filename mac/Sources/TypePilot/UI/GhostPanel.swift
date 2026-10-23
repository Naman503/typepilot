import AppKit
import TypePilotCore

/// The inline suggestion: a small translucent pill under the field, showing the suggested text, a ⇥ hint
/// and a ✕ to close it. Clicking the text inserts it. Non-activating: clicks never take focus away from
/// the field you're typing in.
///
/// Sizing adapts to the screen: short suggestions sit on the caret line; long ones wrap into a block
/// below (or above) the line, never wider than half the screen or taller than ~40% of it.
/// The pill's background. Every click on it (except the ✕) means "insert", and it takes the first
/// click even though TypePilot is never the active app.
private final class GhostContentView: NSVisualEffectView {
    var onClick: (() -> Void)?
    weak var closeButton: NSButton?
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
    override func hitTest(_ point: NSPoint) -> NSView? {
        guard let hit = super.hitTest(point) else { return nil }
        if let b = closeButton, hit === b || hit.isDescendant(of: b) { return b }
        return self
    }
    override func mouseDown(with event: NSEvent) { onClick?() }
    override func resetCursorRects() { addCursorRect(bounds, cursor: .pointingHand) }
}

private final class FirstClickButton: NSButton {
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
    override func resetCursorRects() { addCursorRect(bounds, cursor: .arrow) }
}

@MainActor
final class GhostPanel {
    private let panel: NSPanel
    private let effect = GhostContentView()
    private let icon = NSImageView()
    private let note = NSTextField(labelWithString: "")
    private let label = NSTextField(wrappingLabelWithString: "")
    private let hint = NSTextField(labelWithString: "⇥ Tab")
    private let close = FirstClickButton()
    /// Clicked the suggestion (insert it) / clicked ✕ (close it).
    var onAccept: (() -> Void)?
    var onClose: (() -> Void)?
    private(set) var suggestion: Suggestion?
    private var lastPlacementKey = ""

    private let padX: CGFloat = 8
    private let padY: CGFloat = 4
    private let gap: CGFloat = 6
    private let iconSize: CGFloat = 12
    private let closeSize: CGFloat = 14

    init() {
        panel = NSPanel(contentRect: .zero, styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: true)
        panel.isFloatingPanel = true
        panel.level = .popUpMenu
        panel.backgroundColor = .clear
        panel.isOpaque = false
        panel.hasShadow = true
        panel.ignoresMouseEvents = false        // for the ✕ and click-to-insert; it still never becomes key
        panel.becomesKeyOnlyIfNeeded = true
        panel.hidesOnDeactivate = false
        // Not on every Space: the pill opens on the desktop you're on and is dismissed when you switch.
        panel.collectionBehavior = [.moveToActiveSpace, .fullScreenAuxiliary, .transient, .ignoresCycle]

        effect.material = .popover
        effect.blendingMode = .behindWindow
        effect.state = .active
        effect.wantsLayer = true
        effect.layer?.cornerRadius = 8
        effect.layer?.masksToBounds = true
        effect.layer?.borderWidth = 1
        effect.layer?.borderColor = NSColor.controlAccentColor.withAlphaComponent(0.35).cgColor

        icon.imageScaling = .scaleProportionallyDown
        icon.contentTintColor = .controlAccentColor
        label.textColor = .secondaryLabelColor
        label.lineBreakMode = .byWordWrapping
        label.cell?.truncatesLastVisibleLine = true
        label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        note.font = .systemFont(ofSize: 10, weight: .semibold)
        note.textColor = .controlAccentColor
        hint.font = .systemFont(ofSize: 10, weight: .semibold)
        hint.textColor = .tertiaryLabelColor
        hint.setContentHuggingPriority(.required, for: .horizontal)
        hint.setContentCompressionResistancePriority(.required, for: .horizontal)

        close.image = NSImage(systemSymbolName: "xmark.circle.fill", accessibilityDescription: "Close suggestion")
        close.imageScaling = .scaleProportionallyDown
        close.isBordered = false
        close.bezelStyle = .regularSquare
        close.contentTintColor = .secondaryLabelColor
        close.toolTip = "Close (Esc)"
        close.target = self
        close.action = #selector(closeClicked)
        effect.closeButton = close
        effect.toolTip = "Click to insert"
        effect.onClick = { [weak self] in self?.onAccept?() }

        for v in [icon, note, label, hint, close] { effect.addSubview(v) }
        panel.contentView = effect
    }

    var isVisible: Bool { panel.isVisible && suggestion != nil }
    var frame: CGRect { panel.frame }

    @objc private func closeClicked() { onClose?() }

    /// Show the suggestion without covering the field's text or any other popup near it.
    /// Returns false (and stays hidden) when there's no free spot, e.g. while the app's own
    /// suggestion list is open. Coordinates in are AX (top-left origin).
    @discardableResult
    func show(_ s: Suggestion, caretAX: CGRect?, fieldAX: CGRect?, avoid: [CGRect] = [], fieldPopupOpen: Bool = false,
              position: SuggestionPosition = .outsideField) -> Bool {
        let lineHeight = caretAX?.height ?? 16
        let fontSize = min(15, max(11, lineHeight * 0.72))
        label.font = .systemFont(ofSize: fontSize)
        label.textColor = .labelColor.withAlphaComponent(0.78)
        label.stringValue = s.text.trimmingCharacters(in: .newlines)
        icon.image = NSImage(systemSymbolName: s.source == .memory ? "brain" : "sparkles", accessibilityDescription: nil)
        note.stringValue = s.note.map { "\($0) →" } ?? ""
        note.isHidden = s.note == nil
        hint.stringValue = s.replaceLength > 0 ? "⇥ expand" : (s.source == .ai && s.text.split(separator: " ").count > 1 ? "⇥ all · ⌥→ word" : "⇥ Tab")

        let caret = caretAX.map(AX.toCocoa)
        let field = fieldAX.map(AX.toCocoa)
        let reference = caret ?? field ?? CGRect(origin: NSEvent.mouseLocation, size: .zero)
        // The display the field is on (most of it), never just "the main screen".
        let screen = NSScreen.best(for: field ?? caret ?? reference)
        let vf = screen?.visibleFrame ?? CGRect(x: 0, y: 0, width: 1440, height: 900)
        let font = label.font ?? .systemFont(ofSize: fontSize)
        let lineH = ceil(font.ascender - font.descender + font.leading) + 1

        // Size: one line if it fits within half the screen, else a wrapped block (≤ ~40% of the screen tall).
        let maxWidth = min(600, vf.width * 0.5)
        let noteW = note.isHidden ? 0 : ceil(note.intrinsicContentSize.width) + gap
        let chrome = padX * 2 + iconSize + gap + noteW + gap + ceil(hint.intrinsicContentSize.width) + gap + closeSize
        let textMaxW = max(80, maxWidth - chrome)
        let text = label.stringValue as NSString
        // +6: the label cell's own padding, so a single line never gets clipped with "…".
        let oneLineW = ceil(text.size(withAttributes: [.font: font]).width) + 6
        let multiline = label.stringValue.contains("\n") || oneLineW > textMaxW
        let textW = min(textMaxW, oneLineW)
        var textH = lineH
        if multiline {
            // Measure with the label itself (same insets and wrapping as on screen), so no line is clipped.
            let maxLines = max(1, min(12, Int((vf.height * 0.4 - padY * 2) / lineH)))
            label.maximumNumberOfLines = maxLines
            label.preferredMaxLayoutWidth = textW
            textH = ceil(label.sizeThatFits(NSSize(width: textW, height: .greatestFiniteMagnitude)).height)
        }
        let size = CGSize(width: textW + chrome, height: textH + padY * 2)

        // What must stay visible: the line being typed on (across the field) and other popups.
        // ("Next to the cursor" may sit on the line after the caret; then only the text before it is protected.)
        let caretLine: CGRect? = caret.map { c in
            let left = field?.minX ?? c.minX - 400
            let right = position == .nextToCursor ? c.maxX + 2 : (field?.maxX ?? c.maxX + 400)
            return CGRect(x: left, y: c.minY - 2, width: right - left, height: c.height + 4)
        }
        var blocked = avoid.map { $0.insetBy(dx: -4, dy: -4) }
        if fieldPopupOpen, let f = field {
            // The field's own list (web autocomplete) almost always opens right below it.
            blocked.append(CGRect(x: f.minX - 20, y: f.minY - 320, width: max(f.width, 360) + 40, height: 320))
        }
        if let line = caretLine { blocked.append(line) }

        // Clear space between the field's bottom edge and the suggestion, so both are easy to read.
        let gapToField: CGFloat = 10
        let tallField = (field?.height ?? 0) > max(110, lineH * 5)
        // Left-align with the field (or, in a big editor, with the start of the current line).
        let alignX = (field.map { $0.minX + (tallField ? 8 : 0) }) ?? (caret?.minX ?? reference.minX)
        var candidates: [(String, CGPoint)] = []
        if position == .nextToCursor, !multiline, let c = caret {
            candidates.append(("after cursor", CGPoint(x: c.maxX + 4, y: c.midY - size.height / 2)))
        }
        if tallField, let c = caret {
            // Big editors (email body): just under the line you're typing, else just over it.
            candidates.append(("below line", CGPoint(x: alignX, y: c.minY - size.height - gapToField)))
            candidates.append(("above line", CGPoint(x: alignX, y: c.maxY + gapToField)))
        } else if let f = field {
            candidates.append(("below field", CGPoint(x: alignX, y: f.minY - size.height - gapToField)))
            candidates.append(("above field", CGPoint(x: alignX, y: f.maxY + gapToField)))
        } else if let c = caret {
            candidates.append(("below line", CGPoint(x: alignX, y: c.minY - size.height - gapToField)))
            candidates.append(("above line", CGPoint(x: alignX, y: c.maxY + gapToField)))
        }
        // Fallbacks for apps that report no caret or a huge field (Electron editors, web areas).
        if let f = field {
            if !tallField, let c = caret {
                candidates.append(("below line", CGPoint(x: alignX, y: c.minY - size.height - gapToField)))
            }
            if f.height > size.height + 24 {
                candidates.append(("inside field, bottom", CGPoint(x: f.minX + 8, y: f.minY + 8)))
            }
        }
        if field == nil && caret == nil {
            candidates.append(("near mouse", CGPoint(x: reference.minX + 12, y: reference.minY - size.height - 12)))
        }
        // Last resort, so a suggestion never just vanishes: clear of the popups, under or over all of them.
        let popupBlocks = blocked.filter { b in caretLine.map { b != $0 } ?? true }
        if let lowest = popupBlocks.map(\.minY).min(), let highest = popupBlocks.map(\.maxY).max() {
            candidates.append(("below popups", CGPoint(x: alignX, y: lowest - size.height - 6)))
            candidates.append(("above popups", CGPoint(x: alignX, y: highest + 6)))
        }

        for (placement, p) in candidates {
            var r = CGRect(origin: p, size: size)
            r.origin.x = min(max(r.minX, vf.minX + 8), vf.maxX - r.width - 8)   // slide sideways to stay on screen
            guard r.minY >= vf.minY + 4, r.maxY <= vf.maxY - 4 else { continue }   // but never vertically
            if let hit = blocked.first(where: { $0.intersects(r) }) {
                Log.debug("ghost", "\(placement) blocked by \(Int(hit.width))×\(Int(hit.height)) at (\(Int(hit.minX)),\(Int(hit.minY)))")
                continue
            }
            let key = "\(placement)|\(Int(field?.minY ?? 0))|\(Int(field?.height ?? 0))"
            if key != lastPlacementKey {
                lastPlacementKey = key
                Log.info("ghost", "placed \(placement) \(Int(r.width))×\(Int(r.height)) · field \(field.map { "\(Int($0.width))×\(Int($0.height)) at y \(Int($0.minY))" } ?? "unknown") · caret \(caret == nil ? "unknown" : "known") · \(avoid.count) popup(s) nearby")
            }
            layout(textWidth: textW, textHeight: textH, height: size.height, multiline: multiline)
            suggestion = s
            panel.setFrame(r, display: true)
            panel.orderFrontRegardless()
            return true
        }
        Log.info("ghost", "no free spot (\(avoid.count) popup(s) nearby\(fieldPopupOpen ? ", field's own list open" : ""), field \(field.map { "\(Int($0.width))×\(Int($0.height))" } ?? "unknown")); waiting")
        hide()
        return false
    }

    /// Manual layout: icon · note · text · hint, left to right.
    private func layout(textWidth: CGFloat, textHeight: CGFloat, height: CGFloat, multiline: Bool) {
        label.maximumNumberOfLines = multiline ? label.maximumNumberOfLines : 1
        let top = height - padY                       // flipped = false: y grows upward
        let firstLineMid = multiline ? top - (label.font?.pointSize ?? 13) * 0.65 : height / 2
        var x = padX
        icon.frame = CGRect(x: x, y: firstLineMid - iconSize / 2, width: iconSize, height: iconSize)
        x += iconSize + gap
        if !note.isHidden {
            let w = ceil(note.intrinsicContentSize.width), h = note.intrinsicContentSize.height
            note.frame = CGRect(x: x, y: firstLineMid - h / 2, width: w, height: h)
            x += w + gap
        }
        label.frame = CGRect(x: x, y: padY, width: textWidth, height: textHeight)
        x += textWidth + gap
        let hs = hint.intrinsicContentSize
        hint.frame = CGRect(x: x, y: firstLineMid - hs.height / 2, width: ceil(hs.width), height: hs.height)
        x += ceil(hs.width) + gap
        close.frame = CGRect(x: x, y: firstLineMid - closeSize / 2, width: closeSize, height: closeSize)
        effect.window?.invalidateCursorRects(for: effect)
    }

    func hide() {
        suggestion = nil
        panel.orderOut(nil)
    }
}
