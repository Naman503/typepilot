import AppKit
import ApplicationServices
import TypePilotCore

/// Per-app behaviour. Chromium and Electron only build their accessibility tree when they
/// detect an assistive app, so we ask them to; and they prefer paste over AX text insertion.
enum AppAdapters {
    static let chromiumBrowsers: Set<String> = [
        "com.google.Chrome", "com.google.Chrome.canary", "com.brave.Browser", "com.microsoft.edgemac",
        "company.thebrowser.Browser", "company.thebrowser.dia", "com.vivaldi.Vivaldi", "com.operasoftware.Opera",
    ]
    static let otherBrowsers: Set<String> = ["org.mozilla.firefox", "com.apple.Safari"]

    private static var electronCache: [String: Bool] = [:]
    private static var enabledPIDs: Set<pid_t> = []

    static func isElectron(_ app: NSRunningApplication) -> Bool {
        guard let id = app.bundleIdentifier else { return false }
        if let v = electronCache[id] { return v }
        let fw = app.bundleURL?.appendingPathComponent("Contents/Frameworks/Electron Framework.framework")
        let v = fw.map { FileManager.default.fileExists(atPath: $0.path) } ?? false
        electronCache[id] = v
        return v
    }

    /// Ask Chromium/Electron apps to expose their accessibility tree (once per process).
    static func prepare(_ app: NSRunningApplication) {
        let pid = app.processIdentifier
        guard !enabledPIDs.contains(pid) else { return }
        let el = AXUIElementCreateApplication(pid)
        if let id = app.bundleIdentifier, chromiumBrowsers.contains(id) {
            AXUIElementSetAttributeValue(el, "AXEnhancedUserInterface" as CFString, kCFBooleanTrue)
            Log.info("ax", "enabled accessibility tree for \(app.localizedName ?? id)")
        } else if isElectron(app) {
            AXUIElementSetAttributeValue(el, "AXManualAccessibility" as CFString, kCFBooleanTrue)
            Log.info("ax", "enabled accessibility tree for Electron app \(app.localizedName ?? "")")
        }
        enabledPIDs.insert(pid)
    }

    /// Apps where AX text insertion is unreliable, so paste is used directly.
    static func prefersPaste(_ bundleID: String, app: NSRunningApplication?) -> Bool {
        if chromiumBrowsers.contains(bundleID) || otherBrowsers.contains(bundleID) { return true }
        if let app, isElectron(app) { return true }
        return false
    }
}
