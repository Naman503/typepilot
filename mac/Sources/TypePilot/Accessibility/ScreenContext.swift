import AppKit
import ApplicationServices
import TypePilotCore

/// Reads the visible text around the focused field (the email or chat you're replying to, a form's
/// question) by walking the window's accessibility tree in reading order. Bounded by a node count
/// and a time budget, and meant to run off the main thread.
enum ScreenContext {
    private static let skipRoles: Set<String> = ["AXMenuBar", "AXMenu", "AXMenuItem", "AXToolbar", "AXScrollBar", "AXImage", "AXButton", "AXPopUpButton", "AXCheckBox", "AXRadioButton", "AXTabGroup"]

    static func read(for field: FocusedField, maxChars: Int = 3000, budget: TimeInterval = 0.25, maxNodes: Int = 3000) -> String {
        let t0 = Date()
        let appEl = AXUIElementCreateApplication(field.pid)
        AXUIElementSetMessagingTimeout(appEl, 0.1)
        guard let window = AX.element(appEl, kAXFocusedWindowAttribute) else { return "" }

        var texts: [String] = []
        var seen = Set<String>()
        var fieldIndex: Int?
        var visited = 0
        let deadline = t0.addingTimeInterval(budget)

        func add(_ s: String?) {
            guard var t = s?.trimmingCharacters(in: .whitespacesAndNewlines), t.count >= 2 else { return }
            if t.count > 1500 { t = String(t.suffix(1500)) }
            guard !seen.contains(t) else { return }
            seen.insert(t)
            texts.append(t)
        }

        func visit(_ el: AXUIElement, depth: Int) {
            guard visited < maxNodes, depth < 45, Date() < deadline, fieldIndex == nil else { return }
            visited += 1
            if CFEqual(el, field.element) { fieldIndex = texts.count; return }
            let role = AX.string(el, kAXRoleAttribute) ?? ""
            if skipRoles.contains(role) { return }
            switch role {
            case "AXStaticText":
                add(AX.string(el, kAXValueAttribute) ?? AX.string(el, kAXDescriptionAttribute))
                return
            case "AXTextArea", "AXTextField":
                // Another text field: e.g. a read-only quoted message or a previous answer.
                if AX.string(el, kAXSubroleAttribute) != "AXSecureTextField" { add(AX.string(el, kAXValueAttribute)) }
                return
            case "AXHeading":
                add(AX.string(el, kAXTitleAttribute) ?? AX.string(el, kAXDescriptionAttribute))
            default:
                break
            }
            guard let children = AX.copy(el, kAXChildrenAttribute) as? [AXUIElement] else { return }
            for c in children { visit(c, depth: depth + 1) }
        }
        visit(window, depth: 0)

        // Text before the field in reading order, nearest last, within the budget.
        let before = texts[..<(fieldIndex ?? texts.count)]
        var picked: [String] = []
        var total = 0
        for t in before.reversed() {
            if total + t.count > maxChars { break }
            picked.append(t)
            total += t.count + 1
        }
        var out = picked.reversed().joined(separator: "\n")
        // A multi-line field with text below the caret (Mail's quoted reply) is context too.
        let after = field.textAfter.trimmingCharacters(in: .whitespacesAndNewlines)
        if field.isMultiline, after.count > 20 { out += (out.isEmpty ? "" : "\n") + "Below the cursor in this field:\n" + after.prefix(1500) }
        Log.debug("screen", "read \(out.count) chars from \(visited) nodes in \(Int(Date().timeIntervalSince(t0) * 1000)) ms (\(field.appName))")
        return SensitiveFilter.redact(out)
    }
}
