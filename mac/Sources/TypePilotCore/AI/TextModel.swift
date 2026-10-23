import Foundation

public struct GenerationRequest: Sendable {
    public var system: String
    public var prompt: String
    public var maxTokens: Int
    public var temperature: Double
    public var stop: [String]

    public init(system: String, prompt: String, maxTokens: Int, temperature: Double = 0.4, stop: [String] = []) {
        self.system = system
        self.prompt = prompt
        self.maxTokens = maxTokens
        self.temperature = temperature
        self.stop = stop
    }
}

public struct ModelError: LocalizedError, Sendable {
    public enum Code: String, Sendable { case unavailable, auth, rateLimited, network, badResponse, notConfigured, cancelled }
    public let code: Code
    public let message: String
    public let hint: String?
    public init(_ code: Code, _ message: String, hint: String? = nil) {
        self.code = code
        self.message = message
        self.hint = hint
    }
    public var errorDescription: String? { hint.map { "\(message) \($0)" } ?? message }
}

/// A text-generation backend.
public protocol TextModel: Sendable {
    var name: String { get }
    /// Whether it can be used right now (key present, model ready…), with a reason if not.
    func availability() async -> (ok: Bool, reason: String?)
    func generate(_ request: GenerationRequest) async throws -> String
    /// Streams the cumulative text generated so far.
    func stream(_ request: GenerationRequest) -> AsyncThrowingStream<String, Error>
}

extension TextModel {
    /// Default streaming: generate once and emit the whole result.
    public func stream(_ request: GenerationRequest) -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    continuation.yield(try await generate(request))
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}

// MARK: - HTTP helpers

enum HTTP {
    static func check(_ response: URLResponse, body: () async -> String, provider: String) async throws {
        guard let http = response as? HTTPURLResponse else { throw ModelError(.badResponse, "\(provider): no HTTP response.") }
        guard !(200..<300).contains(http.statusCode) else { return }
        let text = await body()
        let message = extractMessage(text) ?? HTTPURLResponse.localizedString(forStatusCode: http.statusCode)
        switch http.statusCode {
        case 401, 403: throw ModelError(.auth, "\(provider): \(message)", hint: "Check the API key in Settings → AI.")
        case 429: throw ModelError(.rateLimited, "\(provider) rate limit reached.", hint: "Wait a moment or switch provider.")
        case 404: throw ModelError(.badResponse, "\(provider): model not found (\(message)).", hint: "Pick an installed model in Settings → AI.")
        default: throw ModelError(.badResponse, "\(provider) error \(http.statusCode): \(message)")
        }
    }

    static func extractMessage(_ body: String) -> String? {
        guard let data = body.data(using: .utf8), let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return body.isEmpty ? nil : String(body.prefix(200))
        }
        if let e = obj["error"] as? [String: Any], let m = e["message"] as? String { return m }
        if let e = obj["error"] as? String { return e }
        return nil
    }

    static func mapTransport(_ error: Error, provider: String, local: Bool) -> Error {
        if error is CancellationError { return error }
        if let u = error as? URLError {
            if u.code == .cancelled { return CancellationError() }
            if u.code == .timedOut { return ModelError(.network, "\(provider) timed out.") }
            return ModelError(.network, "Could not reach \(provider).", hint: local ? "Is it running?" : "Check your internet connection.")
        }
        return error
    }

    /// Tests inject a mock URLProtocol here.
    nonisolated(unsafe) static var protocolClasses: [AnyClass] = []

    static func session(timeout: TimeInterval) -> URLSession {
        let c = URLSessionConfiguration.ephemeral
        if !protocolClasses.isEmpty { c.protocolClasses = protocolClasses }
        c.timeoutIntervalForRequest = timeout
        c.timeoutIntervalForResource = timeout * 4
        return URLSession(configuration: c)
    }
}
