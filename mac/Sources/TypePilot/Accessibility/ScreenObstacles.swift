import AppKit
import CoreGraphics
import TypePilotCore

/// Finds other popups next to a field, so TypePilot's suggestion never covers them:
/// browser autofill/autocomplete lists, app suggestion dropdowns and the system password AutoFill.
/// It uses the window list (bounds only, which needs no Screen Recording permission).
enum ScreenObstacles {
    /// System processes whose popups attach to the field you're typing in (password AutoFill).
    static let systemPopupOwners: Set<String> = ["AutoFill", "Passwords", "AuthenticationServicesAgent"]

    /// Popups that hug `field` (Cocoa coordinates): windows of the app you're typing in (or the system
    /// AutoFill) whose top edge sits just under the field, or bottom edge just over it.
    ///
    /// Windows of other apps, overlays that cover the field itself and big windows never count:
    /// they're not dropdowns, and treating them as such left no room for the suggestion at all.
    static func popups(near field: CGRect, frontPID: pid_t, focusedWindowAX: CGRect?) -> [CGRect] {
        guard let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]] else { return [] }
        let primaryH = NSScreen.screens.first?.frame.height ?? 0
        let screen = NSScreen.best(for: field)?.frame ?? CGRect(x: 0, y: 0, width: 1440, height: 900)
        let center = CGPoint(x: field.midX, y: field.midY)
        var out: [CGRect] = []
        for w in list {
            guard let pid = w[kCGWindowOwnerPID as String] as? pid_t,
                  let b = w[kCGWindowBounds as String] as? [String: CGFloat],
                  let alpha = w[kCGWindowAlpha as String] as? CGFloat, alpha > 0.05 else { continue }
            let owner = w[kCGWindowOwnerName as String] as? String ?? ""
            guard pid == frontPID || systemPopupOwners.contains(owner) else { continue }
            let layer = w[kCGWindowLayer as String] as? Int ?? 0
            if (20...25).contains(layer) || layer >= 1000 { continue } // Dock, menu bar, overlays
            let ax = CGRect(x: b["X"] ?? 0, y: b["Y"] ?? 0, width: b["Width"] ?? 0, height: b["Height"] ?? 0)
            // List-sized only: tooltips and badges are too small to matter, big windows aren't popups.
            guard ax.width >= 100, ax.height >= 40,
                  ax.width < min(900, screen.width * 0.7), ax.height < screen.height * 0.6 else { continue }
            if let fw = focusedWindowAX, abs(fw.minX - ax.minX) < 2, abs(fw.minY - ax.minY) < 2, abs(fw.width - ax.width) < 2 { continue }
            let cocoa = CGRect(x: ax.minX, y: primaryH - ax.maxY, width: ax.width, height: ax.height)
            // A window over the field itself is the field's own window or an overlay, not a dropdown.
            if cocoa.contains(center) { continue }
            let below = abs(cocoa.maxY - field.minY) < 48
            let above = abs(cocoa.minY - field.maxY) < 48
            let overlapsX = cocoa.maxX > field.minX && cocoa.minX < field.maxX
            guard (below || above) && overlapsX else { continue }
            out.append(cocoa)
        }
        return out
    }

    /// Whether the field is actually visible on the current Space: some on-screen window of its app
    /// contains it. A field in a window on another desktop (Space) still reports focus, but no
    /// suggestion should appear for it here.
    static func isOnScreen(fieldAX: CGRect, pid: pid_t) -> Bool {
        guard let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]] else { return true }
        let center = CGPoint(x: fieldAX.midX, y: fieldAX.midY)
        for w in list {
            guard let p = w[kCGWindowOwnerPID as String] as? pid_t, p == pid,
                  let b = w[kCGWindowBounds as String] as? [String: CGFloat] else { continue }
            let r = CGRect(x: b["X"] ?? 0, y: b["Y"] ?? 0, width: b["Width"] ?? 0, height: b["Height"] ?? 0)
            if r.insetBy(dx: -2, dy: -2).contains(center) { return true }
        }
        return false
    }

    /// The field says its own suggestion list is open (web comboboxes, search boxes, address fields…).
    static func fieldPopupOpen(_ field: FocusedField) -> Bool {
        if let expanded = AX.copy(field.element, "AXExpanded") as? Bool, expanded { return true }
        return false
    }
}

extension NSScreen {
    /// The display showing most of `rect` (Cocoa coordinates); the one under the mouse when it's on none.
    static func best(for rect: CGRect?) -> NSScreen? {
        if let rect {
            let r = rect.width < 1 || rect.height < 1 ? rect.insetBy(dx: -1, dy: -1) : rect
            let scored = screens.map { s -> (NSScreen, CGFloat) in
                let i = s.frame.intersection(r)
                return (s, i.isNull ? 0 : i.width * i.height)
            }
            if let top = scored.max(by: { $0.1 < $1.1 }), top.1 > 0 { return top.0 }
        }
        let mouse = NSEvent.mouseLocation
        return screens.first(where: { $0.frame.contains(mouse) }) ?? NSScreen.main
    }
}
