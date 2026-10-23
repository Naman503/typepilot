import Foundation
import os

/// Unified logging. Every line goes to the macOS unified log (Console.app, subsystem
/// `com.typepilot.app`) and to an in-memory ring buffer that Settings → Logs displays.
///
/// Stream live logs in a terminal with:
///   log stream --predicate 'subsystem == "com.typepilot.app"' --level debug
public enum Log {
    public enum Level: String, Sendable, Codable, CaseIterable { case debug, info, warn, error }

    public struct Entry: Sendable, Identifiable {
        public let id = UUID()
        public let date: Date
        public let level: Level
        public let category: String
        public let message: String
    }

    private static let subsystem = "com.typepilot.app"
    private static let lock = NSLock()
    nonisolated(unsafe) private static var ring: [Entry] = []
    nonisolated(unsafe) private static var loggers: [String: Logger] = [:]
    nonisolated(unsafe) public static var minimumLevel: Level = .info
    private static let maxEntries = 2000

    private static func logger(_ category: String) -> Logger {
        lock.lock(); defer { lock.unlock() }
        if let l = loggers[category] { return l }
        let l = Logger(subsystem: subsystem, category: category)
        loggers[category] = l
        return l
    }

    private static func rank(_ l: Level) -> Int { [.debug: 0, .info: 1, .warn: 2, .error: 3][l] ?? 1 }

    public static func write(_ level: Level, _ category: String, _ message: String) {
        lock.lock()
        ring.append(Entry(date: Date(), level: level, category: category, message: message))
        if ring.count > maxEntries { ring.removeFirst(ring.count - maxEntries) }
        lock.unlock()
        guard rank(level) >= rank(minimumLevel) else { return }
        let l = logger(category)
        switch level {
        case .debug: l.debug("\(message, privacy: .public)")
        case .info: l.notice("\(message, privacy: .public)") // notice is persisted, so `log show` can see it
        case .warn: l.warning("\(message, privacy: .public)")
        case .error: l.error("\(message, privacy: .public)")
        }
    }

    public static func debug(_ c: String, _ m: @autoclosure () -> String) { if rank(.debug) >= rank(minimumLevel) { write(.debug, c, m()) } }
    public static func info(_ c: String, _ m: String) { write(.info, c, m) }
    public static func warn(_ c: String, _ m: String) { write(.warn, c, m) }
    public static func error(_ c: String, _ m: String) { write(.error, c, m) }

    public static func recent() -> [Entry] {
        lock.lock(); defer { lock.unlock() }
        return ring
    }

    public static func clear() {
        lock.lock(); ring.removeAll(); lock.unlock()
    }
}
