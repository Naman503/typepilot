import Foundation

/// Any /chat/completions endpoint: Groq, OpenRouter, LM Studio, OpenAI, Mistral, Together…
public struct OpenAICompatibleModel: TextModel {
    public let baseURL: URL
    public let model: String
    public let apiKey: String?
    public let timeout: TimeInterval
    public var name: String { "\(baseURL.host ?? "API") · \(model)" }

    public init(baseURL: String, model: String, apiKey: String?, timeout: TimeInterval = 30) {
        self.baseURL = URL(string: baseURL) ?? URL(string: "https://api.openai.com/v1")!
        self.model = model
        self.apiKey = apiKey
        self.timeout = timeout
    }

    var isLocal: Bool { ["localhost", "127.0.0.1"].contains(baseURL.host ?? "") }

    public func availability() async -> (ok: Bool, reason: String?) {
        if !isLocal && (apiKey ?? "").isEmpty { return (false, "Add an API key in Settings → AI.") }
        return (true, nil)
    }

    func body(_ r: GenerationRequest, stream: Bool) -> Data {
        var obj: [String: Any] = [
            "model": model,
            "messages": [["role": "system", "content": r.system], ["role": "user", "content": r.prompt]],
            "max_tokens": r.maxTokens, "temperature": r.temperature, "stream": stream,
        ]
        if !r.stop.isEmpty { obj["stop"] = Array(r.stop.prefix(4)) }
        return try! JSONSerialization.data(withJSONObject: obj)
    }

    private func request(_ r: GenerationRequest, stream: Bool) -> URLRequest {
        var req = URLRequest(url: baseURL.appendingPathComponent("chat/completions"))
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let k = apiKey, !k.isEmpty { req.setValue("Bearer \(k)", forHTTPHeaderField: "Authorization") }
        req.httpBody = body(r, stream: stream)
        return req
    }

    public func generate(_ r: GenerationRequest) async throws -> String {
        do {
            let (data, resp) = try await HTTP.session(timeout: timeout).data(for: request(r, stream: false))
            try await HTTP.check(resp, body: { String(data: data, encoding: .utf8) ?? "" }, provider: baseURL.host ?? "API")
            let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any]
            let msg = ((obj?["choices"] as? [[String: Any]])?.first?["message"] as? [String: Any])?["content"] as? String
            return OllamaModel.stripThink(msg ?? "")
        } catch { throw HTTP.mapTransport(error, provider: baseURL.host ?? "API", local: isLocal) }
    }

    public func stream(_ r: GenerationRequest) -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let (bytes, resp) = try await HTTP.session(timeout: timeout).bytes(for: request(r, stream: true))
                    try await HTTP.check(resp, body: { "" }, provider: baseURL.host ?? "API")
                    var text = ""
                    for try await line in bytes.lines {
                        guard line.hasPrefix("data:") else { continue }
                        let payload = line.dropFirst(5).trimmingCharacters(in: .whitespaces)
                        if payload == "[DONE]" { break }
                        guard let d = payload.data(using: .utf8), let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { continue }
                        if let piece = ((o["choices"] as? [[String: Any]])?.first?["delta"] as? [String: Any])?["content"] as? String {
                            text += piece
                            continuation.yield(OllamaModel.stripThink(text))
                        }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: HTTP.mapTransport(error, provider: baseURL.host ?? "API", local: isLocal))
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
