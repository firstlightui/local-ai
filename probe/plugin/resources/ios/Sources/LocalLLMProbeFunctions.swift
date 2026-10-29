import Foundation

enum LocalLLMProbeFunctions {
    final class Start: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let request = try ProbeRequest(parameters: parameters)
            Task { await LocalLLMProbeRuntime.shared.submit(request) }
            return BridgeResponse.success(data: ["id": request.id, "accepted": true])
        }
    }

    final class Cancel: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            guard let id = parameters["id"] as? String, !id.isEmpty else {
                throw LocalLLMProbeError.invalidParameter("id")
            }
            Task { await LocalLLMProbeRuntime.shared.cancel(id: id) }
            return BridgeResponse.success(data: ["id": id, "requested": true])
        }
    }

    final class Unload: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            Task { await LocalLLMProbeRuntime.shared.unloadWhenIdle() }
            return BridgeResponse.success(data: ["accepted": true])
        }
    }

    final class SetBackgrounded: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            guard let backgrounded = parameters["backgrounded"] as? Bool else {
                throw LocalLLMProbeError.invalidParameter("backgrounded")
            }
            Task { await LocalLLMProbeRuntime.shared.setBackgrounded(backgrounded) }
            return BridgeResponse.success(data: ["backgrounded": backgrounded])
        }
    }
}

enum LocalLLMProbeError: Error {
    case invalidParameter(String)
}
