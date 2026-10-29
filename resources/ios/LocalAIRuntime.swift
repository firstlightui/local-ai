import Foundation
import UIKit
#if canImport(FoundationModels)
import FoundationModels
#endif

struct LocalAIFailure: Error {
    let code: String
    let message: String
}

struct LocalAIMessage {
    let role: String
    let content: String
}

struct LocalAIRequest {
    let id: String
    let instructions: String
    let messages: [LocalAIMessage]
    let temperature: Double?
    let maxTokens: Int?

    init(parameters: [String: Any]) throws {
        guard let id = parameters["id"] as? String, !id.isEmpty else {
            throw LocalAIFailure(code: "invalid_request", message: "id is required")
        }
        guard let raw = parameters["messages"] as? [[String: Any]], !raw.isEmpty else {
            throw LocalAIFailure(code: "invalid_request", message: "messages are required")
        }
        let messages = try raw.map { item -> LocalAIMessage in
            guard let role = item["role"] as? String, role == "user" || role == "assistant",
                  let content = item["content"] as? String, !content.isEmpty else {
                throw LocalAIFailure(code: "invalid_request", message: "messages must have a user or assistant role and content")
            }
            return LocalAIMessage(role: role, content: content)
        }
        guard messages.last?.role == "user" else {
            throw LocalAIFailure(code: "invalid_request", message: "the last message must come from the user")
        }
        if let maxTokens = parameters["maxTokens"] as? Int, maxTokens < 1 {
            throw LocalAIFailure(code: "invalid_request", message: "maxTokens must be positive")
        }

        self.id = id
        self.instructions = parameters["instructions"] as? String ?? ""
        self.messages = messages
        self.temperature = (parameters["temperature"] as? NSNumber)?.doubleValue
        self.maxTokens = parameters["maxTokens"] as? Int
    }
}

private final class LocalAIOperation {
    let request: LocalAIRequest
    var status = "queued"
    var text = ""
    var sequence = 0
    var errorCode = ""
    var errorMessage = ""
    var cancelRequested = false
    var task: Task<Void, Never>?

    init(request: LocalAIRequest) {
        self.request = request
    }

    var isTerminal: Bool {
        status == "completed" || status == "failed" || status == "cancelled"
    }
}

/// Process-scoped owner of on-device generation: one FIFO queue, one active
/// generation, and in-memory snapshots that outlive any screen.
final class LocalAIRuntime {
    static let shared = LocalAIRuntime()

    private static let maxPending = 8
    private static let maxRetainedTerminal = 32

    private let lock = NSLock()
    private var operations: [String: LocalAIOperation] = [:]
    private var pending: [String] = []
    private var terminalOrder: [String] = []
    private var activeId: String?
    private var backgrounded = false

    // MARK: Availability

    func availability() -> [String: Any] {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            switch SystemLanguageModel.default.availability {
            case .available:
                return ["status": "available", "reason": "", "platform": "ios"]
            case .unavailable(.modelNotReady):
                return ["status": "downloading", "reason": "model_not_ready", "platform": "ios"]
            case .unavailable(.appleIntelligenceNotEnabled):
                return ["status": "unavailable", "reason": "not_enabled", "platform": "ios"]
            case .unavailable(.deviceNotEligible):
                return ["status": "unavailable", "reason": "device_not_eligible", "platform": "ios"]
            @unknown default:
                return ["status": "unavailable", "reason": "unknown", "platform": "ios"]
            }
        }
        #endif
        return ["status": "unavailable", "reason": "os_unsupported", "platform": "ios"]
    }

    // MARK: Operations

    func submit(_ request: LocalAIRequest) throws {
        guard availability()["status"] as? String == "available" else {
            throw LocalAIFailure(code: "unavailable", message: "The on-device model is not available.")
        }

        lock.lock()
        defer { lock.unlock() }
        guard operations[request.id] == nil else {
            throw LocalAIFailure(code: "duplicate_id", message: "An operation with this id already exists.")
        }
        guard pending.count < Self.maxPending else {
            throw LocalAIFailure(code: "busy", message: "Too many generations are queued.")
        }
        operations[request.id] = LocalAIOperation(request: request)
        pending.append(request.id)
        startNextLocked()
    }

    func cancel(id: String) throws {
        lock.lock()
        guard let operation = operations[id] else {
            lock.unlock()
            throw LocalAIFailure(code: "unknown_operation", message: "No operation has this id.")
        }
        if operation.isTerminal {
            lock.unlock()
            return
        }
        operation.cancelRequested = true
        if operation.status == "queued" {
            pending.removeAll { $0 == id }
            let text = finishLocked(operation, status: "cancelled")
            lock.unlock()
            emit("GenerationCancelled", ["id": id, "text": text])
            return
        }
        let task = operation.task
        lock.unlock()
        task?.cancel()
    }

    func snapshot(id: String) throws -> [String: Any] {
        lock.lock()
        defer { lock.unlock() }
        guard let operation = operations[id] else {
            throw LocalAIFailure(code: "unknown_operation", message: "No operation has this id; it expired or the app restarted.")
        }
        return [
            "id": id,
            "status": operation.status,
            "text": operation.text,
            "sequence": operation.sequence,
            "errorCode": operation.errorCode,
            "errorMessage": operation.errorMessage,
        ]
    }

    func setBackgrounded(_ value: Bool) {
        lock.lock()
        defer { lock.unlock() }
        backgrounded = value
        startNextLocked()
    }

    // MARK: Queue

    private func startNextLocked() {
        guard activeId == nil, !backgrounded, !pending.isEmpty else { return }
        let id = pending.removeFirst()
        guard let operation = operations[id] else { return }
        activeId = id
        operation.status = "running"
        operation.task = Task.detached(priority: .userInitiated) { [weak self] in
            await self?.run(operation)
        }
    }

    private func run(_ operation: LocalAIOperation) async {
        let request = operation.request
        do {
            try await generate(request) { [weak self] text in
                self?.receive(operation, cumulative: text)
            }
            complete(operation, status: "completed")
        } catch is CancellationError {
            complete(operation, status: "cancelled")
        } catch {
            if operation.cancelRequested || Task.isCancelled {
                complete(operation, status: "cancelled")
            } else {
                let failure = Self.failure(from: error)
                complete(operation, status: "failed", code: failure.code, message: failure.message)
            }
        }
    }

    private func generate(_ request: LocalAIRequest, onText: @escaping (String) -> Void) async throws {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            var entries: [Transcript.Entry] = []
            if !request.instructions.isEmpty {
                entries.append(.instructions(Transcript.Instructions(
                    id: UUID().uuidString,
                    segments: [Self.segment(request.instructions)],
                    toolDefinitions: []
                )))
            }
            for message in request.messages.dropLast() {
                if message.role == "user" {
                    entries.append(.prompt(Transcript.Prompt(
                        id: UUID().uuidString,
                        segments: [Self.segment(message.content)],
                        options: GenerationOptions(),
                        responseFormat: nil
                    )))
                } else {
                    entries.append(.response(Transcript.Response(
                        id: UUID().uuidString,
                        assetIDs: [],
                        segments: [Self.segment(message.content)]
                    )))
                }
            }

            let session = LanguageModelSession(
                model: SystemLanguageModel.default,
                tools: [],
                transcript: Transcript(entries: entries)
            )
            let options = GenerationOptions(
                sampling: nil,
                temperature: request.temperature,
                maximumResponseTokens: request.maxTokens
            )
            let stream = session.streamResponse(to: request.messages.last!.content, options: options)
            for try await snapshot in stream {
                try Task.checkCancellation()
                onText(snapshot.content)
            }
            return
        }
        #endif
        throw LocalAIFailure(code: "unavailable", message: "Foundation Models require iOS 26 or later.")
    }

    #if canImport(FoundationModels)
    @available(iOS 26.0, *)
    private static func segment(_ content: String) -> Transcript.Segment {
        .text(Transcript.TextSegment(id: UUID().uuidString, content: content))
    }
    #endif

    /// Foundation Models streams cumulative snapshots; forward only the new suffix.
    private func receive(_ operation: LocalAIOperation, cumulative: String) {
        lock.lock()
        guard operation.status == "running", !operation.cancelRequested else {
            lock.unlock()
            return
        }
        let delta: String
        if cumulative.hasPrefix(operation.text) {
            delta = String(cumulative.dropFirst(operation.text.count))
        } else {
            // The model revised earlier text; the snapshot holds the truth.
            delta = ""
        }
        operation.text = cumulative
        guard !delta.isEmpty else {
            lock.unlock()
            return
        }
        operation.sequence += 1
        let sequence = operation.sequence
        lock.unlock()
        emit("TextDeltaGenerated", ["id": operation.request.id, "sequence": sequence, "delta": delta])
    }

    private func complete(_ operation: LocalAIOperation, status: String, code: String = "", message: String = "") {
        lock.lock()
        guard !operation.isTerminal else {
            lock.unlock()
            return
        }
        operation.errorCode = code
        operation.errorMessage = message
        let text = finishLocked(operation, status: status)
        activeId = nil
        startNextLocked()
        lock.unlock()

        let id = operation.request.id
        switch status {
        case "completed":
            emit("GenerationCompleted", ["id": id, "text": text])
        case "cancelled":
            emit("GenerationCancelled", ["id": id, "text": text])
        default:
            emit("GenerationFailed", ["id": id, "code": code, "message": message, "text": text])
        }
    }

    private func finishLocked(_ operation: LocalAIOperation, status: String) -> String {
        operation.status = status
        operation.task = nil
        terminalOrder.append(operation.request.id)
        while terminalOrder.count > Self.maxRetainedTerminal {
            operations.removeValue(forKey: terminalOrder.removeFirst())
        }
        return operation.text
    }

    private static func failure(from error: Error) -> LocalAIFailure {
        if let failure = error as? LocalAIFailure {
            return failure
        }
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *), let error = error as? LanguageModelSession.GenerationError {
            let message = error.errorDescription ?? "Generation failed."
            switch error {
            case .exceededContextWindowSize:
                return LocalAIFailure(code: "context_exceeded", message: message)
            case .guardrailViolation, .refusal:
                return LocalAIFailure(code: "guardrail", message: message)
            case .unsupportedLanguageOrLocale:
                return LocalAIFailure(code: "unsupported_language", message: message)
            case .rateLimited, .concurrentRequests:
                return LocalAIFailure(code: "rate_limited", message: message)
            case .assetsUnavailable:
                return LocalAIFailure(code: "unavailable", message: message)
            default:
                return LocalAIFailure(code: "generation_failed", message: message)
            }
        }
        #endif
        return LocalAIFailure(code: "generation_failed", message: "Generation failed.")
    }

    private func emit(_ event: String, _ payload: [String: Any]) {
        DispatchQueue.main.async {
            LaravelBridge.shared.send?("FirstlightUI\\LocalAI\\Events\\\(event)", payload)
        }
    }
}

private final class LocalAILifecycleObserver: NSObject {
    static let shared = LocalAILifecycleObserver()

    func install() {
        NotificationCenter.default.addObserver(
            self, selector: #selector(didEnterBackground),
            name: UIApplication.didEnterBackgroundNotification, object: nil
        )
        NotificationCenter.default.addObserver(
            self, selector: #selector(willEnterForeground),
            name: UIApplication.willEnterForegroundNotification, object: nil
        )
    }

    @objc private func didEnterBackground() {
        LocalAIRuntime.shared.setBackgrounded(true)
    }

    @objc private func willEnterForeground() {
        LocalAIRuntime.shared.setBackgrounded(false)
    }
}

func initializeLocalAI() {
    LocalAILifecycleObserver.shared.install()
}
