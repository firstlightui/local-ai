import Foundation

enum LocalAIProbeFunctions {
    final class Start: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let request = try ProbeRequest(parameters: parameters)
            Task { await LocalAIProbeRuntime.shared.submit(request) }
            return BridgeResponse.success(data: ["id": request.id, "accepted": true])
        }
    }

    final class Cancel: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            guard let id = parameters["id"] as? String, !id.isEmpty else {
                throw LocalAIProbeError.invalidParameter("id")
            }
            Task { await LocalAIProbeRuntime.shared.cancel(id: id) }
            return BridgeResponse.success(data: ["id": id, "requested": true])
        }
    }

    final class Unload: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            Task { await LocalAIProbeRuntime.shared.unloadWhenIdle() }
            return BridgeResponse.success(data: ["accepted": true])
        }
    }

    final class SetBackgrounded: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            guard let backgrounded = parameters["backgrounded"] as? Bool else {
                throw LocalAIProbeError.invalidParameter("backgrounded")
            }
            Task { await LocalAIProbeRuntime.shared.setBackgrounded(backgrounded) }
            return BridgeResponse.success(data: ["backgrounded": backgrounded])
        }
    }
}

enum LocalAIProbeError: Error {
    case invalidParameter(String)
}
