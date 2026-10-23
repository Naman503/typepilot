import Foundation

/// Ollama (local): /api/generate with NDJSON streaming. `keep_alive` keeps the model loaded,
/// and Ollama reuses its KV cache for a shared prompt prefix, which keeps completions fast.
public struct OllamaModel: TextModel {
    public let baseURL: URL
    public let model: String
    public let timeout: TimeInterval
    public var name: String { "Ollama · \(model)" }

    public init(baseURL: String, model: String, timeout: TimeInterval = 30) {
        self.baseURL = URL(string: baseURL) ?? URL(string: "http://localhost:11434")!
        self.model = model
        self.timeout = timeout
    }

    public func availability() async -> (ok: Bool, reason: String?) {
        do {
            let models = try await listModels()
            if models.contains(where: { $0 == model || $0 == "\(model):latest" || $0.hasPrefix(model + ":") && !model.contains(":") }) { return (true, nil) }
            return (false, "Model \(model) isn't installed. Run: ollama pull \(model)")
        } catch {
            return (false, "Ollama isn't running at \(baseURL.absoluteString).")
        }
    }

    public func listModels() async throws -> [String] {
        let (data, resp) = try await HTTP.session(timeout: 4).data(from: baseURL.appendingPathComponent("api/tags"))
        try await HTTP.check(resp, body: { String(data: data, encoding: .utf8) ?? "" }, provider: "Ollama")
        let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        return (obj?["models"] as? [[String: Any]] ?? []).compactMap { $0["name"] as? String }
    }

    func body(_ r: GenerationRequest, stream: Bool) -> Data {
        var options: [String: Any] = ["num_predict": r.maxTokens, "temperature": r.temperature]
        if !r.stop.isEmpty { options["stop"] = r.stop }
        let obj: [String: Any] = [
            "model": model, "system": r.system, "prompt": r.prompt, "stream": stream,
            "keep_alive": "30m", "options": options, "think": false,
        ]
        return try! JSONSerialization.data(withJSONObject: obj)
    }

    private func request(_ r: GenerationRequest, stream: Bool) -> URLRequest {
        var req = URLRequest(url: baseURL.appendingPathComponent("api/generate"))
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = body(r, stream: stream)
        return req
    }

    public func generate(_ r: GenerationRequest) async throws -> String {
        do {
            let (data, resp) = try await HTTP.session(timeout: timeout).data(for: request(r, stream: false))
            try await HTTP.check(resp, body: { String(data: data, encoding: .utf8) ?? "" }, provider: "Ollama")
            let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any]
            return Self.stripThink(obj?["response"] as? String ?? "")
        } catch { throw HTTP.mapTransport(error, provider: "Ollama", local: true) }
    }

    public func stream(_ r: GenerationRequest) -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let (bytes, resp) = try await HTTP.session(timeout: timeout).bytes(for: request(r, stream: true))
                    try await HTTP.check(resp, body: { "" }, provider: "Ollama")
                    var text = ""
                    for try await line in bytes.lines {
                        guard let d = line.data(using: .utf8), let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { continue }
                        if let e = o["error"] as? String { throw ModelError(.badResponse, "Ollama: \(e)") }
                        if let piece = o["response"] as? String, !piece.isEmpty {
                            text += piece
                            continuation.yield(Self.stripThink(text))
                        }
                        if o["done"] as? Bool == true { break }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: HTTP.mapTransport(error, provider: "Ollama", local: true))
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    static func stripThink(_ s: String) -> String {
        s.replacingOccurrences(of: #"(?s)<think>.*?(</think>|$)"#, with: "", options: .regularExpression)
    }
}
