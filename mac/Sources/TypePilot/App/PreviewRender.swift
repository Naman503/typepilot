import AppKit
import SwiftUI
import TypePilotCore

/// `--render-previews <dir>`: lays out the suggestion pill and the Ask panel with long text and
/// saves PNGs plus their frames relative to the screen. A development aid for layout checks.
@MainActor
enum PreviewRender {
    static func run(dir: String) {
        let vf = NSScreen.main?.visibleFrame ?? .zero
        print("screen visible frame: \(Int(vf.width))×\(Int(vf.height))")
        let long = String(repeating: "Thanks for the detailed update on the release. I'll verify the checkout flow on staging, then share the test results with the team before the 5pm cutoff. ", count: 6)

        let ghost = GhostPanel()
        let primaryH = NSScreen.screens.first?.frame.height ?? 900
        func ax(_ r: CGRect) -> CGRect { CGRect(x: r.minX, y: primaryH - r.maxY, width: r.width, height: r.height) } // Cocoa → AX
        // A single-line field in the middle of the screen (Cocoa coords) with the caret inside it.
        let field = CGRect(x: vf.midX - 300, y: vf.midY, width: 600, height: 32)
        let caret = CGRect(x: field.minX + 220, y: field.minY + 7, width: 1, height: 18)
        let autofill = CGRect(x: field.minX, y: field.minY - 180, width: 420, height: 176) // browser list below the field
        let topPopup = CGRect(x: field.minX, y: field.maxY + 2, width: 420, height: 160)
        let tall = CGRect(x: vf.midX - 400, y: vf.midY - 250, width: 800, height: 500) // email body
        let tallCaret = CGRect(x: tall.minX + 300, y: tall.midY, width: 1, height: 18)
        let sug = Suggestion(text: " I'll verify the checkout flow and share results by 5pm.", source: .ai, latencyMs: 0)
        let scenarios: [(String, CGRect, CGRect, [CGRect], Bool, SuggestionPosition)] = [
            ("plain", caret, field, [], false, .outsideField),
            ("autofill-below", caret, field, [autofill], false, .outsideField),
            ("field-list-open", caret, field, [], true, .outsideField),
            ("blocked-both", caret, field, [autofill, topPopup], false, .outsideField),
            ("tall-editor", tallCaret, tall, [], false, .outsideField),
            ("next-to-cursor", caret, field, [], false, .nextToCursor),
        ]
        // The Claude desktop app: chat box at the bottom of the screen, and Electron reports no caret position.
        let bottomBox = CGRect(x: vf.midX - 350, y: vf.minY + 24, width: 700, height: 56)
        let s1 = ghost.show(sug, caretAX: nil, fieldAX: ax(bottomBox), avoid: [], fieldPopupOpen: false, position: .outsideField)
        if s1, let w = NSApp.windows.first(where: { $0.isVisible && $0.level == .popUpMenu && !($0 is KeyablePanel) }) {
            let r = w.frame
            print("claude-bottom-box (no caret): \(r.minY >= bottomBox.maxY ? "above field" : r.maxY <= bottomBox.minY ? "below field" : "inside field") · gap \(Int(r.minY - bottomBox.maxY)) pt · fully on screen \(vf.contains(r))")
        } else { print("claude-bottom-box: hidden ✗") }
        ghost.hide()
        for (name, c, f, avoid, open, pos) in scenarios {
            let shown = ghost.show(sug, caretAX: ax(c), fieldAX: ax(f), avoid: avoid, fieldPopupOpen: open, position: pos)
            let w = NSApp.windows.first(where: { $0.isVisible && $0.level == .popUpMenu && !($0 is KeyablePanel) })
            guard shown, let r = w?.frame else { print("\(name): hidden (no free spot) ✓"); continue }
            let line = CGRect(x: f.minX, y: c.minY - 2, width: pos == .nextToCursor ? c.maxX + 2 - f.minX : f.width, height: c.height + 4)
            let coversField = pos == .outsideField && f.height < 90 && r.intersects(f)
            let coversLine = r.intersects(line)
            let hitsPopup = avoid.contains { $0.intersects(r) }
            let where_ = r.minY >= f.maxY ? "above field" : r.maxY <= f.minY ? "below field" : r.minX >= f.maxX ? "right of field" : r.minX > c.maxX ? "after the cursor" : "inside field"
            print("\(name): \(where_) · covers field \(coversField) · covers typing line \(coversLine) · overlaps popup \(hitsPopup)")
            ghost.hide()
        }
        // Composite: the field and the suggestion at their real relative positions.
        for (name, text) in [("composite-short", " I'll verify the checkout flow and share results by 5pm."), ("composite-long", long)] {
            ghost.show(Suggestion(text: text, source: .ai, latencyMs: 0), caretAX: ax(caret), fieldAX: ax(field))
            if let w = NSApp.windows.first(where: { $0.isVisible && $0.level == .popUpMenu && !($0 is KeyablePanel) }), let v = w.contentView,
               let rep = v.bitmapImageRepForCachingDisplay(in: v.bounds) {
                v.cacheDisplay(in: v.bounds, to: rep)
                let pill = NSImage(size: v.bounds.size); pill.addRepresentation(rep)
                let canvas = field.union(w.frame).insetBy(dx: -24, dy: -24)
                let img = NSImage(size: canvas.size)
                img.lockFocus()
                NSColor(white: 0.97, alpha: 1).setFill(); NSRect(origin: .zero, size: canvas.size).fill()
                let fr = field.offsetBy(dx: -canvas.minX, dy: -canvas.minY)
                NSColor.white.setFill(); NSBezierPath(roundedRect: fr, xRadius: 8, yRadius: 8).fill()
                NSColor.systemBlue.setStroke(); let b = NSBezierPath(roundedRect: fr, xRadius: 8, yRadius: 8); b.lineWidth = 1.5; b.stroke()
                ("Hi Priya, sure —" as NSString).draw(at: CGPoint(x: fr.minX + 12, y: fr.minY + 8), withAttributes: [.font: NSFont.systemFont(ofSize: 14), .foregroundColor: NSColor.black])
                NSColor.black.withAlphaComponent(0.25).setFill()
                NSBezierPath(roundedRect: w.frame.offsetBy(dx: -canvas.minX, dy: -canvas.minY - 2), xRadius: 8, yRadius: 8).fill() // shadow hint
                pill.draw(in: w.frame.offsetBy(dx: -canvas.minX, dy: -canvas.minY))
                img.unlockFocus()
                if let t = img.tiffRepresentation, let r = NSBitmapImageRep(data: t) { try? r.representation(using: .png, properties: [:])?.write(to: URL(fileURLWithPath: "\(dir)/\(name).png")) }
                print("\(name): gap below field = \(Int(field.minY - w.frame.maxY)) pt, left edge offset = \(Int(w.frame.minX - field.minX)) pt")
            }
            ghost.hide()
        }
        for (name, text) in [("pill-long", long)] {
            ghost.show(Suggestion(text: text, source: .ai, latencyMs: 0), caretAX: ax(caret), fieldAX: ax(field))
            if let w = NSApp.windows.first(where: { $0.isVisible && $0.level == .popUpMenu && !($0 is KeyablePanel) }) {
                report(name, w.frame, vf)
                save(w.contentView, to: "\(dir)/\(name).png")
            }
            ghost.hide()
        }

        let ask = AskPanelController()
        ask.show(nearAX: CGRect(x: vf.minX + 200, y: primaryH - (vf.minY + 120), width: 300, height: 20)) // field near the bottom
        ask.model.request = "write a detailed message"
        ask.model.output = long + "\n\n" + long
        ask.model.phase = .done
        RunLoop.main.run(until: Date().addingTimeInterval(0.6))
        if let w = NSApp.windows.first(where: { $0.isVisible && $0 is KeyablePanel }) {
            report("ask-long", w.frame, vf)
            save(w.contentView, to: "\(dir)/ask-long.png")
        }
    }

    static func report(_ name: String, _ f: CGRect, _ vf: CGRect) {
        let inside = vf.insetBy(dx: -1, dy: -1).contains(f)
        print("\(name): \(Int(f.width))×\(Int(f.height)) at (\(Int(f.minX)),\(Int(f.minY))) · \(Int(f.width / vf.width * 100))% wide, \(Int(f.height / vf.height * 100))% tall · on screen: \(inside)")
    }

    static func save(_ view: NSView?, to path: String) {
        guard let v = view, let rep = v.bitmapImageRepForCachingDisplay(in: v.bounds) else { return }
        v.cacheDisplay(in: v.bounds, to: rep)
        try? rep.representation(using: .png, properties: [:])?.write(to: URL(fileURLWithPath: path))
    }
}
