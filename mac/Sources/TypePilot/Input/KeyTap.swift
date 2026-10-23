import AppKit
import CoreGraphics
import TypePilotCore

/// Marks events that TypePilot itself posts (paste / typed text), so the tap ignores them.
let syntheticEventMarker: Int64 = 0x5459_5045 // "TYPE"

/// Global keyboard tap. It runs on the main run loop, so callbacks are on the main thread.
/// Returning `true` from the handler swallows the key (e.g. Tab when accepting a suggestion).
final class KeyTap {
    struct Key {
        let keyCode: Int64
        let flags: CGEventFlags
        let characters: String
    }

    private var tap: CFMachPort?
    private var source: CFRunLoopSource?
    private let handler: (Key) -> Bool
    nonisolated(unsafe) private static var current: KeyTap?

    init(handler: @escaping (Key) -> Bool) {
        self.handler = handler
    }

    var isRunning: Bool { tap.map { CGEvent.tapIsEnabled(tap: $0) } ?? false }

    @discardableResult
    func start() -> Bool {
        stop()
        KeyTap.current = self
        let mask = CGEventMask(1 << CGEventType.keyDown.rawValue)
        let callback: CGEventTapCallBack = { _, type, event, _ in
            guard let me = KeyTap.current else { return Unmanaged.passUnretained(event) }
            if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
                if let t = me.tap { CGEvent.tapEnable(tap: t, enable: true) }
                Log.warn("keys", "event tap was disabled by the system; re-enabled")
                return Unmanaged.passUnretained(event)
            }
            guard type == .keyDown, event.getIntegerValueField(.eventSourceUserData) != syntheticEventMarker else {
                return Unmanaged.passUnretained(event)
            }
            var length = 0
            var chars = [UniChar](repeating: 0, count: 8)
            event.keyboardGetUnicodeString(maxStringLength: 8, actualStringLength: &length, unicodeString: &chars)
            let key = Key(keyCode: event.getIntegerValueField(.keyboardEventKeycode), flags: event.flags,
                          characters: String(utf16CodeUnits: chars, count: length))
            return me.handler(key) ? nil : Unmanaged.passUnretained(event)
        }
        guard let t = CGEvent.tapCreate(tap: .cgSessionEventTap, place: .headInsertEventTap, options: .defaultTap,
                                        eventsOfInterest: mask, callback: callback, userInfo: nil) else {
            Log.error("keys", "could not create the event tap (Accessibility permission missing?)")
            return false
        }
        tap = t
        source = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, t, 0)
        CFRunLoopAddSource(CFRunLoopGetMain(), source, .commonModes)
        CGEvent.tapEnable(tap: t, enable: true)
        Log.info("keys", "keyboard tap started")
        return true
    }

    func stop() {
        if let t = tap { CGEvent.tapEnable(tap: t, enable: false) }
        if let s = source { CFRunLoopRemoveSource(CFRunLoopGetMain(), s, .commonModes) }
        tap = nil
        source = nil
    }
}

enum KeyCode {
    static let tab: Int64 = 48
    static let escape: Int64 = 53
    static let space: Int64 = 49
    static let returnKey: Int64 = 36
    static let enterKey: Int64 = 76
    static let v: Int64 = 9
    static let rightArrow: Int64 = 124
    static let delete: Int64 = 51
}
