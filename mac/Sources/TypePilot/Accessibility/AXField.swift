import AppKit
import ApplicationServices
import Carbon
import TypePilotCore

/// A snapshot of the focused text field, read through the Accessibility API.
struct FocusedField {
    let element: AXUIElement
    let pid: pid_t
    let bundleID: String
    let appName: String
    let role: String
    let subrole: String
    let value: String
    /// Selection in UTF-16 units (AX uses NSString indexing).
    let selection: CFRange
    let label: String
    let windowTitle: String
    let isSecure: Bool
    let isMultiline: Bool

    var textBefore: String {
        let ns = value as NSString
        return ns.substring(to: max(0, min(selection.location, ns.length)))
    }

    var textAfter: String {
        let ns = value as NSString
        let end = max(0, min(selection.location + selection.length, ns.length))
        return ns.substring(from: end)
    }

    var selectedText: String {
        let ns = value as NSString
        let loc = max(0, min(selection.location, ns.length))
        let len = max(0, min(selection.length, ns.length - loc))
        return ns.substring(with: NSRange(location: loc, length: len))
    }

    /// Caret at the end of its line: the only place inline suggestions make sense.
    var caretAtLineEnd: Bool {
        guard selection.length == 0 else { return false }
        let after = textAfter
        return after.isEmpty || after.first == "\n" || after.first == "\r"
    }

    func context() -> FieldContext {
        FieldContext(appName: appName, bundleID: bundleID, windowTitle: windowTitle, fieldLabel: label,
                     textBefore: textBefore, textAfter: textAfter, selectedText: selectedText, isMultiline: isMultiline)
    }

    /// Screen rect of the caret in AX coordinates (top-left origin), with fallbacks.
    func caretRect() -> CGRect? {
        if let r = AX.bounds(element, range: CFRange(location: selection.location, length: 0)), r.height > 0 { return r }
        if selection.location > 0, let r = AX.bounds(element, range: CFRange(location: selection.location - 1, length: 1)), r.height > 0 {
            return CGRect(x: r.maxX, y: r.minY, width: 1, height: r.height)
        }
        return nil
    }

    func frame() -> CGRect? { AX.frame(element) }

    /// Same element as another snapshot?
    func isSameElement(_ other: FocusedField?) -> Bool {
        guard let other else { return false }
        return CFEqual(element, other.element)
    }
}

enum AX {
    static let systemWide: AXUIElement = {
        let e = AXUIElementCreateSystemWide()
        // Never let a hung app block us for long.
        AXUIElementSetMessagingTimeout(e, 0.25)
        return e
    }()

    static let textRoles: Set<String> = ["AXTextField", "AXTextArea", "AXComboBox", "AXSearchField"]

    static func isTrusted(prompt: Bool = false) -> Bool {
        let opts = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: prompt] as CFDictionary
        return AXIsProcessTrustedWithOptions(opts)
    }

    static func copy(_ el: AXUIElement, _ attr: String) -> CFTypeRef? {
        var v: CFTypeRef?
        return AXUIElementCopyAttributeValue(el, attr as CFString, &v) == .success ? v : nil
    }

    static func string(_ el: AXUIElement, _ attr: String) -> String? {
        copy(el, attr) as? String
    }

    static func element(_ el: AXUIElement, _ attr: String) -> AXUIElement? {
        guard let v = copy(el, attr), CFGetTypeID(v) == AXUIElementGetTypeID() else { return nil }
        return (v as! AXUIElement)
    }

    static func isSettable(_ el: AXUIElement, _ attr: String) -> Bool {
        var settable = DarwinBoolean(false)
        return AXUIElementIsAttributeSettable(el, attr as CFString, &settable) == .success && settable.boolValue
    }

    static func range(_ el: AXUIElement) -> CFRange? {
        guard let v = copy(el, kAXSelectedTextRangeAttribute), CFGetTypeID(v) == AXValueGetTypeID() else { return nil }
        var r = CFRange()
        return AXValueGetValue(v as! AXValue, .cfRange, &r) ? r : nil
    }

    static func setRange(_ el: AXUIElement, _ r: CFRange) {
        var r = r
        if let v = AXValueCreate(.cfRange, &r) { AXUIElementSetAttributeValue(el, kAXSelectedTextRangeAttribute as CFString, v) }
    }

    static func bounds(_ el: AXUIElement, range: CFRange) -> CGRect? {
        var r = range
        guard let arg = AXValueCreate(.cfRange, &r) else { return nil }
        var out: CFTypeRef?
        guard AXUIElementCopyParameterizedAttributeValue(el, kAXBoundsForRangeParameterizedAttribute as CFString, arg, &out) == .success,
              let out, CFGetTypeID(out) == AXValueGetTypeID() else { return nil }
        var rect = CGRect.zero
        return AXValueGetValue(out as! AXValue, .cgRect, &rect) && rect.width.isFinite ? rect : nil
    }

    static func frame(_ el: AXUIElement) -> CGRect? {
        guard let p = copy(el, kAXPositionAttribute), let s = copy(el, kAXSizeAttribute) else { return nil }
        var point = CGPoint.zero, size = CGSize.zero
        AXValueGetValue(p as! AXValue, .cgPoint, &point)
        AXValueGetValue(s as! AXValue, .cgSize, &size)
        return CGRect(origin: point, size: size)
    }

    static func label(_ el: AXUIElement) -> String {
        for attr in [kAXTitleAttribute, kAXDescriptionAttribute, "AXPlaceholderValue", kAXHelpAttribute] {
            if let s = string(el, attr)?.trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty { return String(s.prefix(200)) }
        }
        if let t = element(el, kAXTitleUIElementAttribute), let s = string(t, kAXValueAttribute), !s.isEmpty { return String(s.prefix(200)) }
        return ""
    }

    /// Just the focused element: cheap, used for polling.
    static func focusedElement() -> AXUIElement? {
        element(systemWide, kAXFocusedUIElementAttribute)
    }

    /// Read the focused text field, or nil if focus isn't in an editable text element.
    static func focusedField() -> FocusedField? {
        guard let app = NSWorkspace.shared.frontmostApplication else { return nil }
        let appEl = AXUIElementCreateApplication(app.processIdentifier)
        AXUIElementSetMessagingTimeout(appEl, 0.25)
        guard let el = element(systemWide, kAXFocusedUIElementAttribute) ?? element(appEl, kAXFocusedUIElementAttribute) else { return nil }

        let role = string(el, kAXRoleAttribute) ?? ""
        let subrole = string(el, kAXSubroleAttribute) ?? ""
        let secure = subrole == "AXSecureTextField" || IsSecureEventInputEnabled()
        let value = string(el, kAXValueAttribute)
        let editable = textRoles.contains(role) || (value != nil && range(el) != nil && isSettable(el, kAXValueAttribute))
        guard editable else { return nil }
        let sel = range(el) ?? CFRange(location: (value as NSString?)?.length ?? 0, length: 0)
        let window = element(appEl, kAXFocusedWindowAttribute).flatMap { string($0, kAXTitleAttribute) } ?? ""
        return FocusedField(
            element: el, pid: app.processIdentifier, bundleID: app.bundleIdentifier ?? "", appName: app.localizedName ?? "",
            role: role, subrole: subrole, value: secure ? "" : (value ?? ""), selection: sel, label: label(el),
            windowTitle: String(window.prefix(200)), isSecure: secure, isMultiline: role == "AXTextArea")
    }

    /// AX (top-left origin, primary screen) → Cocoa (bottom-left origin).
    static func toCocoa(_ r: CGRect) -> CGRect {
        let primaryHeight = NSScreen.screens.first?.frame.height ?? 0
        return CGRect(x: r.minX, y: primaryHeight - r.maxY, width: r.width, height: r.height)
    }
}
