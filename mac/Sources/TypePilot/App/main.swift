import AppKit

if let i = CommandLine.arguments.firstIndex(of: "--render-previews") {
    let dir = CommandLine.arguments.count > i + 1 ? CommandLine.arguments[i + 1] : "."
    let app = NSApplication.shared
    app.setActivationPolicy(.accessory)
    MainActor.assumeIsolated { PreviewRender.run(dir: dir) }
    exit(0)
}
if CommandLine.arguments.contains("--selftest-raw") {
    Task { await SelfTestRaw.run(); exit(0) }
    RunLoop.main.run()
}
if CommandLine.arguments.contains("--selftest") {
    Task {
        await SelfTest.run()
        exit(0)
    }
    RunLoop.main.run()
}

// TypePilot is a menu-bar app (LSUIElement): no Dock icon, no main window.
let app = NSApplication.shared
let delegate = MainActor.assumeIsolated { AppDelegate() }
app.delegate = delegate
app.setActivationPolicy(.accessory)
app.run()
