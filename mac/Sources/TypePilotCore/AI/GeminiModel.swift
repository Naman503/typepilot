import Foundation

/// Google Gemini (generateContent / streamGenerateContent with SSE). 3.x models use
/// `thinkingLevel`; if a model rejects it (400) we retry without it.
public struct GeminiModel: TextModel {
    public let model: String
    public let apiKey: String?
    public let timeout: TimeInterval
    public var name: String { "Gemini · \(model)" }
    let base = URL(string: "https://generativelanguage.googleapis.com/v1beta")!

    public init(model: String, apiKey: String?, timeout: TimeInterval = 30) {
        self.model = model
        self.apiKey = apiKey
        self.timeout = timeout
    }

    public func availability() async -> (ok: Bool, reason: String?) {
        (apiKey ?? "").isEmpty ? (false, "Add a Gemini API key in Settings → AI (free at aistudio.google.com).") : (true, nil)
    }

    func body(_ r: GenerationRequest, thinking: Bool) -> Data {
        var config: [String: Any] = ["maxOutputTokens": r.maxTokens]
        if !model.hasPrefix("gemini-3") { config["temperature"] = r.temperature }
        if !r.stop.isEmpty { config["stopSequences"] = Array(r.stop.prefix(5)) }
        if thinking {
            if model.hasPrefix("gemini-2.5") && !model.contains("pro") { config["thinkingConfig"] = ["thinkingBudget": 0] }
            else if model.hasPrefix("gemini-3") { config["thinkingConfig"] = ["thinkingLevel": model.contains("lite") ? "minimal" : "low"] }
        }
        let obj: [String: Any] = [
            "systemInstruction": ["parts": [["text": r.system]]],
            "contents": [["role": "user", "parts": [["text": r.prompt]]]],
            "generationConfig": config,
        ]
        return try! JSONSerialization.data(withJSONObject: obj)
    }

    private func request(_ r: GenerationRequest, stream: Bool, thinking: Bool) -> URLRequest {
        let path = "models/\(model):" + (stream ? "streamGenerateContent" : "generateContent")
        var comps = URLComponents(url: base.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if stream { comps.queryItems = [URLQueryItem(name: "alt", value: "sse")] }
        var req = URLRequest(url: comps.url!)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(apiKey ?? "", forHTTPHeaderField: "x-goog-api-key")
        req.httpBody = body(r, thinking: thinking)
        return req
    }

    static func text(from obj: [String: Any]?) -> String {
        let parts = ((obj?["candidates"] as? [[String: Any]])?.first?["content"] as? [String: Any])?["parts"] as? [[String: Any]] ?? []
        return parts.filter { ($0["thought"] as? Bool) != true }.compactMap { $0["text"] as? String }.joined()
    }

    public func generate(_ r: GenerationRequest) async throws -> String {
        guard let k = apiKey, !k.isEmpty else { throw ModelError(.notConfigured, "No Gemini API key.") }
        for thinking in [true, false] {
            do {
                let (data, resp) = try await HTTP.session(timeout: timeout).data(for: request(r, stream: false, thinking: thinking))
                if thinking, (resp as? HTTPURLResponse)?.statusCode == 400 { continue }
                try await HTTP.check(resp, body: { String(data: data, encoding: .utf8) ?? "" }, provider: "Gemini")
                return Self.text(from: try JSONSerialization.jsonObject(with: data) as? [String: Any])
            } catch { throw HTTP.mapTransport(error, provider: "Gemini", local: false) }
        }
        throw ModelError(.badResponse, "Gemini rejected the request.")
    }

    public func stream(_ r: GenerationRequest) -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    var (bytes, resp) = try await HTTP.session(timeout: timeout).bytes(for: request(r, stream: true, thinking: true))
                    if (resp as? HTTPURLResponse)?.statusCode == 400 {
                        (bytes, resp) = try await HTTP.session(timeout: timeout).bytes(for: request(r, stream: true, thinking: false))
                    }
                    try await HTTP.check(resp, body: { "" }, provider: "Gemini")
                    var text = ""
                    for try await line in bytes.lines where line.hasPrefix("data:") {
                        let payload = line.dropFirst(5).trimmingCharacters(in: .whitespaces)
                        guard let d = payload.data(using: .utf8) else { continue }
                        let piece = Self.text(from: try? JSONSerialization.jsonObject(with: d) as? [String: Any])
                        if !piece.isEmpty {
                            text += piece
                            continuation.yield(text)
                        }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: HTTP.mapTransport(error, provider: "Gemini", local: false))
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
