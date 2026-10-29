import Foundation
import MLX
import MLXHuggingFace
import MLXLLM
import MLXLMCommon
import Tokenizers

struct ProbeRequest: Sendable {
    struct Message: Sendable {
        let role: String
        let content: String
    }

    let id: String
    let modelPath: String
    let mode: String
    let prompt: String?
    let messages: [Message]
    let maxTokens: Int

    init(parameters: [String: Any]) throws {
        guard let id = parameters["id"] as? String, !id.isEmpty else {
            throw LocalAIProbeError.invalidParameter("id")
        }
        guard let modelPath = parameters["modelPath"] as? String, !modelPath.isEmpty else {
            throw LocalAIProbeError.invalidParameter("modelPath")
        }

        let mode = parameters["mode"] as? String ?? "text"
        guard mode == "text" || mode == "chat" else {
            throw LocalAIProbeError.invalidParameter("mode")
        }

        let prompt = parameters["prompt"] as? String
        let rawMessages = parameters["messages"] as? [[String: Any]] ?? []
        let messages = rawMessages.compactMap { value -> Message? in
            guard let role = value["role"] as? String,
                  let content = value["content"] as? String else { return nil }
            return Message(role: role, content: content)
        }

        guard mode != "text" || prompt?.isEmpty == false else {
            throw LocalAIProbeError.invalidParameter("prompt")
        }
        guard mode != "chat" || messages.count == rawMessages.count && !messages.isEmpty else {
            throw LocalAIProbeError.invalidParameter("messages")
        }

        self.id = id
        self.modelPath = modelPath
        self.mode = mode
        self.prompt = prompt
        self.messages = messages
        self.maxTokens = min(max(parameters["maxTokens"] as? Int ?? 128, 1), 512)
    }
}

actor LocalAIProbeRuntime {
    static let shared = LocalAIProbeRuntime()

    private var model: ModelContainer?
    private var modelPath: String?
    private var pending: [ProbeRequest] = []
    private var active: (id: String, task: Task<Void, Never>)?
    private var backgrounded = false
    private var unloadRequested = false

    func submit(_ request: ProbeRequest) {
        pending.append(request)
        startNextIfAllowed()
    }

    func cancel(id: String) {
        if let index = pending.firstIndex(where: { $0.id == id }) {
            pending.remove(at: index)
            emitTerminal(id: id, status: "cancelled", phase: "queued")
            return
        }
        if active?.id == id {
            active?.task.cancel()
        }
    }

    func setBackgrounded(_ value: Bool) {
        backgrounded = value
        if !value {
            startNextIfAllowed()
        }
    }

    func unloadWhenIdle() {
        unloadRequested = true
        releaseModelIfIdle()
    }

    private func startNextIfAllowed() {
        guard active == nil, !backgrounded, !pending.isEmpty else { return }
        let request = pending.removeFirst()
        let task = Task { await self.run(request) }
        active = (request.id, task)
    }

    private func run(_ request: ProbeRequest) async {
        do {
            try Task.checkCancellation()
            let resolvedModelURL = try resolveModelURL(request.modelPath)
            if model == nil || modelPath != resolvedModelURL.path {
                model = nil
                Memory.clearCache()
                emitTerminal(id: request.id, status: "running", phase: "loading")
                model = try await loadModelContainer(
                    from: resolvedModelURL,
                    using: #huggingFaceTokenizerLoader()
                )
                modelPath = resolvedModelURL.path
            }

            try Task.checkCancellation()
            emitTerminal(id: request.id, status: "running", phase: "prompt")
            let input = try await model!.prepare(input: try userInput(for: request))
            try Task.checkCancellation()
            let stream = try await model!.generate(
                input: input,
                parameters: GenerateParameters(
                    maxTokens: request.maxTokens,
                    temperature: 0,
                    prefillStepSize: 64
                )
            )
            try Task.checkCancellation()

            var sequence = 0
            var output = ""
            for await value in stream {
                try Task.checkCancellation()
                if case .chunk(let delta) = value, !delta.isEmpty {
                    sequence += 1
                    output += delta
                    emitDelta(id: request.id, sequence: sequence, delta: delta)
                }
            }
            try Task.checkCancellation()
            emitTerminal(id: request.id, status: "completed", text: output)
        } catch is CancellationError {
            model = nil
            modelPath = nil
            Memory.clearCache()
            emitTerminal(id: request.id, status: "cancelled", phase: "running")
        } catch {
            model = nil
            modelPath = nil
            Memory.clearCache()
            emitTerminal(id: request.id, status: "failed", error: String(describing: error))
        }

        active = nil
        releaseModelIfIdle()
        startNextIfAllowed()
    }

    private func resolveModelURL(_ path: String) throws -> URL {
        if path.hasPrefix("/") {
            return URL(fileURLWithPath: path, isDirectory: true)
        }

        let documents = try FileManager.default.url(
            for: .documentDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let documentURL = documents.appending(path: path, directoryHint: .isDirectory)
        if FileManager.default.fileExists(atPath: documentURL.path) {
            return documentURL
        }
        if let bundledURL = Bundle.main.url(forResource: path, withExtension: nil) {
            return bundledURL
        }
        return documentURL
    }

    private func userInput(for request: ProbeRequest) throws -> UserInput {
        if request.mode == "text" {
            return UserInput(prompt: .text(request.prompt!))
        }

        let chat = try request.messages.map { message -> Chat.Message in
            guard let role = Chat.Message.Role(rawValue: message.role) else {
                throw LocalAIProbeError.invalidParameter("messages.role")
            }
            return Chat.Message(role: role, content: message.content)
        }
        return UserInput(chat: chat)
    }

    private func releaseModelIfIdle() {
        guard unloadRequested, active == nil, pending.isEmpty else { return }
        model = nil
        modelPath = nil
        unloadRequested = false
        Memory.clearCache()
    }

    private nonisolated func emitDelta(id: String, sequence: Int, delta: String) {
        LaravelBridge.shared.send?("FirstlightUI\\LocalAI\\Probe\\Events\\ProbeDelta", [
            "id": id,
            "sequence": sequence,
            "delta": delta,
        ])
    }

    private nonisolated func emitTerminal(
        id: String,
        status: String,
        text: String? = nil,
        phase: String? = nil,
        error: String? = nil
    ) {
        LaravelBridge.shared.send?("FirstlightUI\\LocalAI\\Probe\\Events\\ProbeTerminal", [
            "id": id,
            "status": status,
            "text": text,
            "phase": phase,
            "error": error,
        ])
    }
}
