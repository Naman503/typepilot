import XCTest
@testable import TypePilotCore

/// Intercepts provider HTTP calls.
final class MockURLProtocol: URLProtocol {
    nonisolated(unsafe) static var handler: ((URLRequest) -> (Int, String))?
    nonisolated(unsafe) static var lastRequest: URLRequest?
    nonisolated(unsafe) static var lastBody: [String: Any]?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.lastRequest = request
        if let stream = request.httpBodyStream {
            stream.open()
            var data = Data()
            var buf = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable { let n = stream.read(&buf, maxLength: buf.count); if n <= 0 { break }; data.append(buf, count: n) }
            stream.close()
            Self.lastBody = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        } else if let d = request.httpBody {
            Self.lastBody = try? JSONSerialization.jsonObject(with: d) as? [String: Any]
        }
        let (status, body) = Self.handler?(request) ?? (500, "")
        let resp = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: resp, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

final class ProviderTests: XCTestCase {
    override func setUp() { HTTP.protocolClasses = [MockURLProtocol.self] }
    override func tearDown() { HTTP.protocolClasses = []; MockURLProtocol.handler = nil }

    let req = GenerationRequest(system: "sys", prompt: "hello", maxTokens: 16, temperature: 0.2, stop: ["\n\n"])

    func testOllamaGenerate() async throws {
        MockURLProtocol.handler = { _ in (200, #"{"response":"<think>hmm</think> world","done":true}"#) }
        let out = try await OllamaModel(baseURL: "http://localhost:11434", model: "qwen2.5:1.5b").generate(req)
        XCTAssertEqual(out, " world")
        XCTAssertEqual(MockURLProtocol.lastRequest?.url?.path, "/api/generate")
        XCTAssertEqual(MockURLProtocol.lastBody?["model"] as? String, "qwen2.5:1.5b")
        XCTAssertEqual((MockURLProtocol.lastBody?["options"] as? [String: Any])?["num_predict"] as? Int, 16)
    }

    func testOllamaStreaming() async throws {
        MockURLProtocol.handler = { _ in (200, #"{"response":"Hel"}"# + "\n" + #"{"response":"lo","done":true}"# + "\n") }
        var last = ""
        for try await t in OllamaModel(baseURL: "http://localhost:11434", model: "m").stream(req) { last = t }
        XCTAssertEqual(last, "Hello")
    }

    func testOpenAICompatible() async throws {
        MockURLProtocol.handler = { _ in (200, #"{"choices":[{"message":{"content":"Sure thing"}}]}"#) }
        let out = try await OpenAICompatibleModel(baseURL: "https://api.groq.com/openai/v1", model: "llama", apiKey: "k").generate(req)
        XCTAssertEqual(out, "Sure thing")
        XCTAssertEqual(MockURLProtocol.lastRequest?.value(forHTTPHeaderField: "Authorization"), "Bearer k")
        XCTAssertEqual(MockURLProtocol.lastRequest?.url?.path, "/openai/v1/chat/completions")
    }

    func testOpenAIStreaming() async throws {
        MockURLProtocol.handler = { _ in (200, "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\" there\"}}]}\n\ndata: [DONE]\n\n") }
        var last = ""
        for try await t in OpenAICompatibleModel(baseURL: "https://x.test/v1", model: "m", apiKey: "k").stream(req) { last = t }
        XCTAssertEqual(last, "Hi there")
    }

    func testGeminiRetriesWithoutThinkingOn400() async throws {
        var calls = 0
        MockURLProtocol.handler = { _ in
            calls += 1
            return calls == 1 ? (400, #"{"error":{"message":"thinking not supported"}}"#)
                              : (200, #"{"candidates":[{"content":{"parts":[{"text":"thought","thought":true},{"text":"Done."}]}}]}"#)
        }
        let out = try await GeminiModel(model: "gemini-3.5-flash-lite", apiKey: "key").generate(req)
        XCTAssertEqual(out, "Done.")
        XCTAssertEqual(calls, 2)
        XCTAssertEqual(MockURLProtocol.lastRequest?.value(forHTTPHeaderField: "x-goog-api-key"), "key")
        XCTAssertNil((MockURLProtocol.lastBody?["generationConfig"] as? [String: Any])?["thinkingConfig"])
    }

    func testErrorMapping() async {
        MockURLProtocol.handler = { _ in (401, #"{"error":{"message":"Invalid API Key"}}"#) }
        do {
            _ = try await OpenAICompatibleModel(baseURL: "https://x.test/v1", model: "m", apiKey: "bad").generate(req)
            XCTFail("expected error")
        } catch let e as ModelError {
            XCTAssertEqual(e.code, .auth)
        } catch { XCTFail("wrong error \(error)") }

        MockURLProtocol.handler = { _ in (429, "{}") }
        do {
            _ = try await GeminiModel(model: "gemini-2.5-flash", apiKey: "k").generate(req)
            XCTFail("expected error")
        } catch let e as ModelError {
            XCTAssertEqual(e.code, .rateLimited)
        } catch { XCTFail("wrong error \(error)") }
    }

    func testAvailabilityWithoutKeys() async {
        let g = await GeminiModel(model: "x", apiKey: nil).availability()
        XCTAssertFalse(g.ok)
        let o = await OpenAICompatibleModel(baseURL: "https://api.groq.com/openai/v1", model: "m", apiKey: "").availability()
        XCTAssertFalse(o.ok)
        let local = await OpenAICompatibleModel(baseURL: "http://localhost:1234/v1", model: "m", apiKey: nil).availability()
        XCTAssertTrue(local.ok)
    }
}
