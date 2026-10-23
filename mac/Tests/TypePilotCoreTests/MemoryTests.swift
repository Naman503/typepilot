import XCTest
@testable import TypePilotCore

final class MemoryStoreTests: XCTestCase {
    var url: URL!
    var store: MemoryStore!

    override func setUpWithError() throws {
        url = FileManager.default.temporaryDirectory.appendingPathComponent("tp-\(UUID().uuidString).sqlite")
        store = try MemoryStore(url: url)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: url)
    }

    func testRecordDedupesAndCounts() {
        XCTAssertTrue(store.record("221B Baker Street, London", app: "Safari", field: "Address", kind: .typed))
        XCTAssertTrue(store.record("221b  baker street, london ", app: "Mail", field: "", kind: .typed))
        let all = store.all()
        XCTAssertEqual(all.count, 1)
        XCTAssertEqual(all[0].count, 2)
        XCTAssertEqual(all[0].field, "Address") // empty field label doesn't overwrite
    }

    func testRejectsSensitiveAndJunk() {
        XCTAssertFalse(store.record("4111 1111 1111 1111", app: "", field: "", kind: .typed))
        XCTAssertFalse(store.record("483920", app: "", field: "", kind: .typed))
        XCTAssertFalse(store.record("hunter2pass", app: "", field: "Password", kind: .typed))
        XCTAssertFalse(store.record("ok", app: "", field: "", kind: .typed))
        XCTAssertTrue(store.all().isEmpty)
    }

    func testPinDeleteClear() {
        store.record("Please find the invoice attached.", app: "Mail", field: "", kind: .typed)
        store.record("Regards, Alex", app: "Mail", field: "", kind: .manual)
        let e = store.all().first { $0.text.hasPrefix("Regards") }!
        store.setPinned(id: e.id, true)
        XCTAssertTrue(store.all().first!.pinned)
        store.delete(id: e.id)
        XCTAssertEqual(store.all().count, 1)
        store.clear()
        XCTAssertTrue(store.all().isEmpty)
    }

    func testPersistsAcrossReopen() throws {
        store.record("See you at the standup.", app: "Slack", field: "", kind: .typed)
        store = try MemoryStore(url: url)
        XCTAssertEqual(store.all().first?.text, "See you at the standup.")
    }
}

final class PhraseCompleterTests: XCTestCase {
    func entry(_ text: String, field: String = "", count: Int = 2, pinned: Bool = false, daysAgo: Double = 0) -> MemoryEntry {
        MemoryEntry(id: Int64.random(in: 1...999_999), text: text, app: "", field: field, kind: .typed, count: count,
                    lastUsed: Date().addingTimeInterval(-daysAgo * 86_400), pinned: pinned)
    }

    func testContinuesAPhraseYouWroteBefore() {
        let pc = PhraseCompleter(entries: [entry("Thanks for reaching out, I'll get back to you by tomorrow.")])
        XCTAssertEqual(pc.suggest(textBefore: "Hi Sam,\nThanks for reaching out, I'll get", fieldLabel: "")?.text, " back to you by tomorrow.")
        XCTAssertNil(pc.suggest(textBefore: "Th", fieldLabel: ""))            // too short
        XCTAssertNil(pc.suggest(textBefore: "Completely different", fieldLabel: ""))
    }

    func testRecallsValueForAnEmptyField() {
        let pc = PhraseCompleter(entries: [
            entry("221B Baker Street, London", field: "Address", count: 5),
            entry("10 Downing Street", field: "Address", count: 2),
        ])
        XCTAssertEqual(pc.suggest(textBefore: "", fieldLabel: "address:")?.text, "221B Baker Street, London")
        XCTAssertNil(pc.suggest(textBefore: "", fieldLabel: "Phone"))
    }

    func testRanksByFrequencyRecencyAndPin() {
        let pc = PhraseCompleter(entries: [
            entry("Best regards, Alex Doe", count: 2, daysAgo: 0),
            entry("Best regards, Alex", count: 9, daysAgo: 200),
            entry("Best regards, the team", count: 1, pinned: true),
        ])
        XCTAssertEqual(pc.suggest(textBefore: "Best reg", fieldLabel: "")?.text, "ards, the team")
    }

    func testMatchesSentencesInsideLongerEntries() {
        let pc = PhraseCompleter(entries: [entry("Hello! Could you please share the tracking number for my order? Thanks.")])
        XCTAssertEqual(pc.suggest(textBefore: "Could you please share the", fieldLabel: "", maxWords: 12)?.text, " tracking number for my order?")
    }

    func testOneOffMessagesAreNotResuggested() {
        // Typed once: context for the AI, never offered back.
        let pc = PhraseCompleter(entries: [entry("we should also give option for the home page to search", field: "Message", count: 1)])
        XCTAssertNil(pc.suggest(textBefore: "we should also give", fieldLabel: "Message"))
        XCTAssertNil(pc.suggest(textBefore: "", fieldLabel: "Message"))
    }

    func testNoRecallInChatBoxesOrForLongValues() {
        let pc = PhraseCompleter(entries: [
            entry("Thanks, I'll take a look at it later today and let you know.", field: "Reply", count: 5),
            entry(String(repeating: "long value ", count: 20), field: "Notes field", count: 5),
            entry("Flat 4, Rose Villa, Pune", field: "Address", count: 3),
        ])
        XCTAssertNil(pc.suggest(textBefore: "", fieldLabel: "Reply"))                       // chat box
        XCTAssertNil(pc.suggest(textBefore: "", fieldLabel: "Notes field"))                 // too long
        XCTAssertNil(pc.suggest(textBefore: "", fieldLabel: "Address", multilineField: true)) // multi-line editor
        XCTAssertEqual(pc.suggest(textBefore: "", fieldLabel: "Address")?.text, "Flat 4, Rose Villa, Pune")
    }

    func testContinuationIsCappedToAFewWords() {
        let pc = PhraseCompleter(entries: [entry("Thanks for reaching out, I will get back to you with the full details by tomorrow evening.")])
        XCTAssertEqual(pc.suggest(textBefore: "Thanks for reaching out, I", fieldLabel: "", maxWords: 5)?.text, " will get back to you")
    }

    func testFieldNormalisation() {
        XCTAssertEqual(PhraseCompleter.normalizeField("E-mail Address:"), "e mail address")
    }
}

final class ShortcutTests: XCTestCase {
    func entry(_ text: String, shortcut: String) -> MemoryEntry {
        MemoryEntry(id: 1, text: text, app: "", field: "", kind: .manual, count: 1, lastUsed: Date(), pinned: false, shortcut: shortcut)
    }

    func testExpandsTypedShortcut() {
        let pc = PhraseCompleter(entries: [entry("221B Baker Street, London NW1", shortcut: "addr"), entry("+91 98765 43210", shortcut: "phone")])
        let hit = pc.suggest(textBefore: "Deliver to ;addr", fieldLabel: "")
        XCTAssertEqual(hit?.text, "221B Baker Street, London NW1")
        XCTAssertEqual(hit?.replaceLength, 5)
        XCTAssertEqual(hit?.note, ";addr")
        XCTAssertEqual(pc.suggest(textBefore: ";ph", fieldLabel: "")?.text, "+91 98765 43210") // unique prefix
        XCTAssertNil(pc.suggest(textBefore: "addr", fieldLabel: ""))                        // needs the ; prefix
        XCTAssertNil(pc.suggest(textBefore: ";addr ", fieldLabel: ""))                      // already past it
    }

    func testShortcutPersistsAndMigrates() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("tp-\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: url) }
        // An old (v1) database without the shortcut column.
        let old = try SQLiteDB(path: url.path)
        try old.exec("CREATE TABLE entries (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, norm TEXT NOT NULL UNIQUE, app TEXT NOT NULL DEFAULT '', field TEXT NOT NULL DEFAULT '', kind TEXT NOT NULL DEFAULT 'typed', count INTEGER NOT NULL DEFAULT 1, first_seen REAL NOT NULL, last_used REAL NOT NULL, pinned INTEGER NOT NULL DEFAULT 0);")
        try old.run("INSERT INTO entries (text, norm, first_seen, last_used) VALUES ('Old entry text', 'old entry text', 0, 0)")
        let store = try MemoryStore(url: url)
        XCTAssertEqual(store.all().first?.text, "Old entry text")
        let id = store.all().first!.id
        store.setShortcut(id: id, ";Old-Entry ")
        XCTAssertEqual(store.all().first?.shortcut, "old-entry")
        store.record("Thanks, Naman", app: "", field: "", kind: .manual, shortcut: "sig")
        XCTAssertEqual(store.all().first { $0.text == "Thanks, Naman" }?.shortcut, "sig")
    }
}
