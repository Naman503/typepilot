import Foundation
#if canImport(FoundationModels)
import FoundationModels
#endif

/// Apple's on-device model (Foundation Models, macOS 26+). Free, offline and private.
/// It needs Apple Intelligence turned on in System Settings.
public struct AppleModel: TextModel {
    public var name: String { "Apple on-device" }
    public init() {}

    public func availability() async -> (ok: Bool, reason: String?) {
        #if canImport(FoundationModels)
        if #available(macOS 26.0, *) {
            switch SystemLanguageModel.default.availability {
            case .available: return (true, nil)
            case .unavailable(.appleIntelligenceNotEnabled):
                return (false, "Turn on Apple Intelligence in System Settings to use the on-device model.")
            case .unavailable(.deviceNotEligible):
                return (false, "This Mac can't run Apple's on-device model. Use Ollama or a cloud provider.")
            case .unavailable(.modelNotReady):
                return (false, "Apple's model is still downloading. Try again in a few minutes.")
            case .unavailable:
                return (false, "Apple's on-device model is unavailable.")
            }
        }
        #endif
        return (false, "Apple's on-device model needs macOS 26.")
    }

    /// Load the model ahead of the first request (cuts first-token latency).
    public static func prewarm(instructions: String) {
        #if canImport(FoundationModels)
        if #available(macOS 26.0, *), SystemLanguageModel.default.isAvailable {
            LanguageModelSession(instructions: instructions).prewarm()
        }
        #endif
    }

    public func generate(_ r: GenerationRequest) async throws -> String {
        #if canImport(FoundationModels)
        if #available(macOS 26.0, *) {
            guard SystemLanguageModel.default.isAvailable else {
                throw ModelError(.unavailable, await availability().reason ?? "Apple's model is unavailable.")
            }
            let session = LanguageModelSession(instructions: r.system)
            let options = GenerationOptions(temperature: r.temperature, maximumResponseTokens: r.maxTokens)
            do {
                return try await session.respond(to: r.prompt, options: options).content
            } catch {
                throw ModelError(.badResponse, "Apple model: \(error.localizedDescription)")
            }
        }
        #endif
        throw ModelError(.unavailable, "Apple's on-device model needs macOS 26.")
    }

    public func stream(_ r: GenerationRequest) -> AsyncThrowingStream<String, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                #if canImport(FoundationModels)
                if #available(macOS 26.0, *) {
                    guard SystemLanguageModel.default.isAvailable else {
                        continuation.finish(throwing: ModelError(.unavailable, await availability().reason ?? "Unavailable"))
                        return
                    }
                    let session = LanguageModelSession(instructions: r.system)
                    let options = GenerationOptions(temperature: r.temperature, maximumResponseTokens: r.maxTokens)
                    do {
                        for try await snapshot in session.streamResponse(to: r.prompt, options: options) {
                            continuation.yield(snapshot.content)
                        }
                        continuation.finish()
                    } catch {
                        continuation.finish(throwing: ModelError(.badResponse, "Apple model: \(error.localizedDescription)"))
                    }
                    return
                }
                #endif
                continuation.finish(throwing: ModelError(.unavailable, "Apple's on-device model needs macOS 26."))
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
