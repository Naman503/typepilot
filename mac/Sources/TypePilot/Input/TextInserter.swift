import AppKit
import ApplicationServices
import TypePilotCore

/// Inserts text at the caret of the focused field, with three strategies:
///  1. AX `kAXSelectedTextAttribute`: native Cocoa apps; the clipboard is untouched; verified by reading back.
///  2. Paste: clipboard + ⌘V, then the previous clipboard is restored. Works in browsers, Electron, Docs.
///  3. Keystrokes: posting the text as typed characters, for terminals and fields that reject paste.
enum TextInserter {
    enum Strategy: String { case ax, paste, keystrokes }

    /// Insert at the caret, first replacing `replacing` characters before it (a typed shortcut).
    @MainActor
    static func insert(_ text: String, into field: FocusedField, replacing: Int = 0) -> Strategy? {
        if replacing > 0 { deleteBackward(replacing, field: field) }
        let app = NSRunningApplication(processIdentifier: field.pid)
        if !AppAdapters.prefersPaste(field.bundleID, app: app), insertViaAX(text, field: field) {
            Log.info("insert", "inserted \(text.count) chars via AX in \(field.appName)")
            return .ax
        }
        if paste(text) {
            Log.info("insert", "inserted \(text.count) chars via paste in \(field.appName)")
            return .paste
        }
        type(text)
        Log.info("insert", "inserted \(text.count) chars via keystrokes in \(field.appName)")
        return .keystrokes
    }

    /// Remove characters before the caret: select them via AX when possible, else send Backspace.
    static func deleteBackward(_ count: Int, field: FocusedField) {
        let loc = field.selection.location
        if loc >= count, AX.isSettable(field.element, kAXSelectedTextRangeAttribute), AX.isSettable(field.element, kAXSelectedTextAttribute),
           !AppAdapters.prefersPaste(field.bundleID, app: NSRunningApplication(processIdentifier: field.pid)) {
            AX.setRange(field.element, CFRange(location: loc - count, length: count))
            if AXUIElementSetAttributeValue(field.element, kAXSelectedTextAttribute as CFString, "" as CFString) == .success { return }
        }
        for _ in 0..<count { postKey(KeyCode.delete, flags: []) }
    }

    static func insertViaAX(_ text: String, field: FocusedField) -> Bool {
        guard AX.isSettable(field.element, kAXSelectedTextAttribute) else { return false }
        let before = AX.string(field.element, kAXValueAttribute) ?? ""
        guard AXUIElementSetAttributeValue(field.element, kAXSelectedTextAttribute as CFString, text as CFString) == .success else { return false }
        // Some apps report success without changing anything: verify.
        let after = AX.string(field.element, kAXValueAttribute) ?? ""
        return after != before && after.contains(text.trimmingCharacters(in: .whitespaces))
    }

    /// Paste through the clipboard, then restore it. The temporary item is marked transient and
    /// concealed, so clipboard managers that honour nspasteboard.org conventions skip it.
    static func paste(_ text: String) -> Bool {
        let pb = NSPasteboard.general
        let saved: [NSPasteboardItem] = (pb.pasteboardItems ?? []).map { item in
            let copy = NSPasteboardItem()
            for t in item.types { if let d = item.data(forType: t) { copy.setData(d, forType: t) } }
            return copy
        }
        pb.clearContents()
        let item = NSPasteboardItem()
        item.setString(text, forType: .string)
        item.setData(Data(), forType: NSPasteboard.PasteboardType("org.nspasteboard.TransientType"))
        item.setData(Data(), forType: NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType"))
        guard pb.writeObjects([item]) else { return false }
        let changeCount = pb.changeCount

        postKey(KeyCode.v, flags: .maskCommand)

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
            // Only restore if nothing else touched the clipboard meanwhile.
            guard pb.changeCount == changeCount else { return }
            pb.clearContents()
            if !saved.isEmpty { pb.writeObjects(saved) }
        }
        return true
    }

    static func type(_ text: String) {
        let units = Array(text.utf16)
        var i = 0
        while i < units.count {
            let chunk = Array(units[i..<min(i + 20, units.count)])
            for down in [true, false] {
                guard let e = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: down) else { continue }
                e.keyboardSetUnicodeString(stringLength: chunk.count, unicodeString: chunk)
                e.setIntegerValueField(.eventSourceUserData, value: syntheticEventMarker)
                e.post(tap: .cghidEventTap)
            }
            i += 20
        }
    }

    static func postKey(_ code: Int64, flags: CGEventFlags) {
        let src = CGEventSource(stateID: .combinedSessionState)
        for down in [true, false] {
            guard let e = CGEvent(keyboardEventSource: src, virtualKey: CGKeyCode(code), keyDown: down) else { continue }
            e.flags = flags
            e.setIntegerValueField(.eventSourceUserData, value: syntheticEventMarker)
            e.post(tap: .cghidEventTap)
        }
    }
}
