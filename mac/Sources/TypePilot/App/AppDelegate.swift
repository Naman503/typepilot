import AppKit
import SwiftUI
import TypePilotCore

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate {
    private let coordinator = Coordinator()
    private var statusItem: NSStatusItem!
    private var settingsWindow: NSWindow?

    func applicationDidFinishLaunching(_ notification: Notification) {
        Log.info("app", "launch v\(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev")")
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        statusItem.button?.image = NSImage(systemSymbolName: "character.cursor.ibeam", accessibilityDescription: "TypePilot")
        let menu = NSMenu()
        menu.delegate = self
        statusItem.menu = menu

        coordinator.start()
        if !AX.isTrusted() {
            // First run: explain and ask for the one permission we need.
            openSettings(tab: .permissions)
            _ = AX.isTrusted(prompt: true)
        }
    }

    // Rebuild the menu each time it opens so the status is current.
    func menuNeedsUpdate(_ menu: NSMenu) {
        coordinator.refreshStatus()
        menu.removeAllItems()
        let status: String
        switch coordinator.status {
        case .active: status = "● Active"
        case .paused: status = "Paused"
        case .disabled: status = "Turned off"
        case .needsPermission: status = "⚠︎ Needs Accessibility permission"
        }
        let header = NSMenuItem(title: "TypePilot: \(status)", action: nil, keyEquivalent: "")
        header.isEnabled = false
        menu.addItem(header)
        if coordinator.status == .needsPermission {
            menu.addItem(item("Grant permission…", #selector(openPermissions)))
        }
        menu.addItem(.separator())
        menu.addItem(item("Ask AI to write…  (⌥Space)", #selector(openAsk)))
        let enabled = item("Suggestions enabled", #selector(toggleEnabled))
        enabled.state = SettingsStore.shared.value.enabled ? .on : .off
        menu.addItem(enabled)
        if coordinator.status == .paused {
            menu.addItem(item("Resume", #selector(resume)))
        } else {
            menu.addItem(item("Pause for 1 hour", #selector(pause)))
        }
        menu.addItem(.separator())
        menu.addItem(item("Memory…", #selector(openMemory)))
        menu.addItem(item("Settings…", #selector(openGeneral), key: ","))
        menu.addItem(.separator())
        menu.addItem(item("Quit TypePilot", #selector(quit), key: "q"))
    }

    private func item(_ title: String, _ action: Selector, key: String = "") -> NSMenuItem {
        let i = NSMenuItem(title: title, action: action, keyEquivalent: key)
        i.target = self
        return i
    }

    @objc private func openAsk() { coordinator.openAsk() }
    @objc private func toggleEnabled() { SettingsStore.shared.update { $0.enabled.toggle() } }
    @objc private func pause() { coordinator.pause(minutes: 60) }
    @objc private func resume() { coordinator.resume() }
    @objc private func openMemory() { openSettings(tab: .memory) }
    @objc private func openGeneral() { openSettings(tab: .general) }
    @objc private func openPermissions() { openSettings(tab: .permissions) }
    @objc private func quit() { NSApp.terminate(nil) }

    func openSettings(tab: SettingsTab) {
        let view = SettingsView(coordinator: coordinator, tab: tab)
        if let w = settingsWindow {
            w.contentView = NSHostingView(rootView: view)
        } else {
            let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 860, height: 600),
                             styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                             backing: .buffered, defer: false)
            w.title = "TypePilot"
            w.isReleasedWhenClosed = false
            w.contentView = NSHostingView(rootView: view)
            w.center()
            settingsWindow = w
        }
        NSApp.activate(ignoringOtherApps: true)
        settingsWindow?.makeKeyAndOrderFront(nil)
    }
}
