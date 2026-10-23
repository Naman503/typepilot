import XCTest
@testable import TypePilotCore

final class SensitiveFilterTests: XCTestCase {
    func testDetectsSecrets() {
        XCTAssertTrue(SensitiveFilter.containsSecret("my card 4111 1111 1111 1111 thanks"))
        XCTAssertTrue(SensitiveFilter.containsSecret("key: " + ["AIza", "SyA1234567890abcdefghijklmnopqrstuv"].joined()))
        XCTAssertTrue(SensitiveFilter.containsSecret("token " + ["gh", "p_"].joined() + String(repeating: "a", count: 36)))
        XCTAssertTrue(SensitiveFilter.containsSecret("PAN ABCDE1234F"))
        XCTAssertFalse(SensitiveFilter.containsSecret("Call me at 9876543210"))
        XCTAssertFalse(SensitiveFilter.containsSecret("Order 1234 5678 9012 3456 shipped")) // fails Luhn
        XCTAssertFalse(SensitiveFilter.containsSecret("Thanks for reaching out, I'll get back to you soon."))
    }

    func testSensitiveLabelsAndCodes() {
        XCTAssertTrue(SensitiveFilter.isSensitiveLabel("Enter OTP"))
        XCTAssertTrue(SensitiveFilter.isSensitiveLabel("Password"))
        XCTAssertTrue(SensitiveFilter.isSensitiveLabel("Card number"))
        XCTAssertFalse(SensitiveFilter.isSensitiveLabel("Message"))
        XCTAssertTrue(SensitiveFilter.looksLikeOneTimeCode(" 483920 "))
        XCTAssertFalse(SensitiveFilter.looksLikeOneTimeCode("Flat 402"))
    }

    func testRedact() {
        let out = SensitiveFilter.redact("pay with 4111 1111 1111 1111 please")
        XCTAssertFalse(out.contains("4111"))
        XCTAssertTrue(out.contains("[card]"))
    }
}

final class ContinuationCleanerTests: XCTestCase {
    func testStripsEchoQuotesAndBoilerplate() {
        XCTAssertEqual(ContinuationCleaner.clean("\"get back to you soon.\"", after: "I'll ", maxWords: 10), "get back to you soon.")
        XCTAssertEqual(ContinuationCleaner.clean("Continuation: soon.", after: "talk ", maxWords: 10), "soon.")
        // Model repeats the end of the user's text before continuing.
        XCTAssertEqual(ContinuationCleaner.clean("I would like a refund for my order", after: "Hi team, I would like a ", maxWords: 10), "refund for my order")
    }

    func testSpacingRules() {
        XCTAssertEqual(ContinuationCleaner.clean(" there!", after: "Hi ", maxWords: 5), "there!")
        XCTAssertEqual(ContinuationCleaner.clean("lo world", after: "hel", maxWords: 5), "lo world")
        XCTAssertEqual(ContinuationCleaner.clean("Looking forward.", after: "Thanks.", maxWords: 5), " Looking forward.")
    }

    func testLimitsLengthAndLines() {
        let out = ContinuationCleaner.clean(" one two three four five six seven eight", after: "Count:", maxWords: 4)
        XCTAssertEqual(out, " one two three four")
        XCTAssertEqual(ContinuationCleaner.clean(" first line\nsecond line", after: "x", maxWords: 10), " first line")
        XCTAssertNil(ContinuationCleaner.clean("   ", after: "x", maxWords: 10))
        XCTAssertNil(ContinuationCleaner.clean("...", after: "x", maxWords: 10))
    }

    func testRejectsMetaAnswers() {
        XCTAssertNil(ContinuationCleaner.clean("Sure, here's a suggestion for how the user might start", after: "", maxWords: 10))
        XCTAssertEqual(ContinuationCleaner.clean("Here is the continuation: soon", after: "x ", maxWords: 10), "soon")
        // Apple's model: preamble, blank line, then the real answer (quote cut off by the token cap).
        XCTAssertEqual(ContinuationCleaner.clean("Sure, I can help with that. Here is the beginning of the user's message:\n\n\"Hi Priya, I'll check", after: "", maxWords: 10), "Hi Priya, I'll check")
        XCTAssertEqual(ContinuationCleaner.clean("Sure, here's the beginning of the user's message:\n\nI'll check the checkout flow", after: "", maxWords: 10), "I'll check the checkout flow")
        XCTAssertEqual(ContinuationCleaner.clean("Sure, I'll take a look", after: "", maxWords: 10), "Sure, I'll take a look") // a real reply
        XCTAssertEqual(ContinuationCleaner.clean("Surely we can", after: "", maxWords: 10), "Surely we can")
    }

    func testPrefersSentenceEndWhenCutting() {
        let out = ContinuationCleaner.clean(" Thanks for the update. I will check and get back to you by tomorrow", after: "Hi.", maxWords: 8)
        XCTAssertEqual(out, " Thanks for the update.")
    }
}

final class DraftCleanerTests: XCTestCase {
    func testRemovesPlaceholders() {
        let draft = """
        Dear IndiGo,
        I am writing about the refund for flight [Flight Number] booked on [Booking Date].
        Thank you.
        Best regards,
        [Your Name]
        """
        let out = DraftCleaner.clean(draft)
        XCTAssertFalse(out.contains("["))
        XCTAssertTrue(out.contains("refund for flight booked on."))
        XCTAssertTrue(out.hasSuffix("Best regards,"))
    }

    func testRemovesLowercasePlaceholdersButKeepsCitations() {
        XCTAssertEqual(DraftCleaner.clean("My booking from [origin] to [destination] was cancelled."), "My booking from to was cancelled.")
        XCTAssertEqual(DraftCleaner.clean("As shown in [1], it works."), "As shown in [1], it works.")
    }

    func testStripsPreambleAndEchoedInstructions() {
        let raw = """
        Sure, here's the revised text:

        Hello IndiGo,
        I still haven't received my refund.
        Revise the draft: Make it noticeably shorter.
        Output only the revised text.
        Hello IndiGo,
        """
        XCTAssertEqual(DraftCleaner.clean(raw), "Hello IndiGo,\nI still haven't received my refund.")
    }
}
