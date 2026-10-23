import Foundation
import SQLite3

/// One remembered piece of text: something you typed in a field, an accepted suggestion,
/// or an entry you added yourself.
public struct MemoryEntry: Sendable, Identifiable, Equatable {
    public enum Kind: String, Sendable { case typed, accepted, manual }
    public let id: Int64
    public var text: String
    public var app: String
    public var field: String
    public var kind: Kind
    public var count: Int
    public var lastUsed: Date
    public var pinned: Bool
    /// Optional text shortcut: typing ";<shortcut>" offers this entry.
    public var shortcut: String = ""
}

/// Local, on-device memory (SQLite in Application Support). Nothing here leaves the Mac unless
/// "use memory in prompts" is on and the chosen provider is a cloud one.
public final class MemoryStore: @unchecked Sendable {
    private let db: SQLiteDB
    private let lock = NSLock()
    public static let didChange = Notification.Name("TypePilotMemoryDidChange")

    public static func defaultURL() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("TypePilot", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true,
                                                 attributes: [.posixPermissions: 0o700])
        return base.appendingPathComponent("memory.sqlite")
    }

    public init(url: URL = MemoryStore.defaultURL()) throws {
        db = try SQLiteDB(path: url.path)
        try db.exec("""
        CREATE TABLE IF NOT EXISTS entries (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          text TEXT NOT NULL,
          norm TEXT NOT NULL UNIQUE,
          app TEXT NOT NULL DEFAULT '',
          field TEXT NOT NULL DEFAULT '',
          kind TEXT NOT NULL DEFAULT 'typed',
          count INTEGER NOT NULL DEFAULT 1,
          first_seen REAL NOT NULL,
          last_used REAL NOT NULL,
          pinned INTEGER NOT NULL DEFAULT 0
        );
        CREATE INDEX IF NOT EXISTS entries_field ON entries(field);
        """)
        // v2: text shortcuts. ALTER fails harmlessly if the column already exists.
        try? db.exec("ALTER TABLE entries ADD COLUMN shortcut TEXT NOT NULL DEFAULT ''")
        // The memory file is only readable by this user.
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }

    public static func normalize(_ s: String) -> String {
        s.lowercased().replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Decide whether a piece of typed text is worth keeping.
    public static func isWorthRemembering(_ text: String, field: String = "") -> Bool {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard t.count >= 3, t.count <= 4000 else { return false }
        if SensitiveFilter.containsSecret(t) || SensitiveFilter.looksLikeOneTimeCode(t) { return false }
        if !field.isEmpty, SensitiveFilter.isSensitiveLabel(field) { return false }
        // Single short tokens without letters (e.g. "42", "---") aren't useful.
        if t.count < 6, !t.contains(where: \.isLetter) { return false }
        return true
    }

    /// Store (or bump) an entry. Returns false if it was rejected as not worth keeping.
    @discardableResult
    public func record(_ text: String, app: String, field: String, kind: MemoryEntry.Kind, shortcut: String = "") -> Bool {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard Self.isWorthRemembering(t, field: field) else { return false }
        let now = Date().timeIntervalSince1970
        lock.lock(); defer { lock.unlock() }
        do {
            try db.run("""
            INSERT INTO entries (text, norm, app, field, kind, count, first_seen, last_used)
            VALUES (?, ?, ?, ?, ?, 1, ?, ?)
            ON CONFLICT(norm) DO UPDATE SET count = count + 1, last_used = excluded.last_used,
              app = excluded.app, field = CASE WHEN excluded.field <> '' THEN excluded.field ELSE field END,
              kind = CASE WHEN kind = 'manual' THEN kind ELSE excluded.kind END
            """, [.text(t), .text(Self.normalize(t)), .text(app), .text(field), .text(kind.rawValue), .double(now), .double(now)])
            let sc = Self.normalizeShortcut(shortcut)
            if !sc.isEmpty { _ = try? db.run("UPDATE entries SET shortcut = ? WHERE norm = ?", [.text(sc), .text(Self.normalize(t))]) }
        } catch {
            Log.error("memory", "record failed: \(error)")
            return false
        }
        NotificationCenter.default.post(name: Self.didChange, object: nil)
        return true
    }

    public func all(limit: Int = 5000) -> [MemoryEntry] {
        lock.lock(); defer { lock.unlock() }
        var out: [MemoryEntry] = []
        try? db.query("SELECT id, text, app, field, kind, count, last_used, pinned, shortcut FROM entries ORDER BY pinned DESC, last_used DESC LIMIT ?",
                      [.int(Int64(limit))]) { s in
            out.append(MemoryEntry(
                id: sqlite3_column_int64(s, 0), text: SQLiteDB.text(s, 1), app: SQLiteDB.text(s, 2), field: SQLiteDB.text(s, 3),
                kind: MemoryEntry.Kind(rawValue: SQLiteDB.text(s, 4)) ?? .typed, count: Int(sqlite3_column_int64(s, 5)),
                lastUsed: Date(timeIntervalSince1970: sqlite3_column_double(s, 6)), pinned: sqlite3_column_int64(s, 7) != 0,
                shortcut: SQLiteDB.text(s, 8)))
        }
        return out
    }

    public func update(id: Int64, text: String) {
        lock.lock()
        _ = try? db.run("UPDATE entries SET text = ?, norm = ?, kind = 'manual' WHERE id = ?", [.text(text), .text(Self.normalize(text)), .int(id)])
        lock.unlock()
        NotificationCenter.default.post(name: Self.didChange, object: nil)
    }

    /// "Addr", ";addr", " ADDR " → "addr" (letters/digits/-/_ only).
    public static func normalizeShortcut(_ s: String) -> String {
        s.lowercased().filter { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }
    }

    public func setShortcut(id: Int64, _ shortcut: String) {
        lock.lock()
        _ = try? db.run("UPDATE entries SET shortcut = ? WHERE id = ?", [.text(Self.normalizeShortcut(shortcut)), .int(id)])
        lock.unlock()
        NotificationCenter.default.post(name: Self.didChange, object: nil)
    }

    public func setPinned(id: Int64, _ pinned: Bool) {
        lock.lock()
        _ = try? db.run("UPDATE entries SET pinned = ? WHERE id = ?", [.int(pinned ? 1 : 0), .int(id)])
        lock.unlock()
        NotificationCenter.default.post(name: Self.didChange, object: nil)
    }

    public func delete(id: Int64) {
        lock.lock()
        _ = try? db.run("DELETE FROM entries WHERE id = ?", [.int(id)])
        lock.unlock()
        NotificationCenter.default.post(name: Self.didChange, object: nil)
    }

    public func clear() {
        lock.lock()
        _ = try? db.run("DELETE FROM entries")
        lock.unlock()
        NotificationCenter.default.post(name: Self.didChange, object: nil)
    }

    public func markUsed(text: String) {
        lock.lock()
        _ = try? db.run("UPDATE entries SET count = count + 1, last_used = ? WHERE norm = ?",
                    [.double(Date().timeIntervalSince1970), .text(Self.normalize(text))])
        lock.unlock()
    }
}
