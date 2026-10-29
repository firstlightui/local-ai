import Foundation

enum LocalAIFunctions {
    final class Availability: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            BridgeResponse.success(data: LocalAIRuntime.shared.availability())
        }
    }

    /// Apple downloads Apple Intelligence assets itself; report the current state.
    final class Download: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            BridgeResponse.success(data: LocalAIRuntime.shared.availability())
        }
    }

    final class Generate: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            LocalAIFunctions.respond {
                let request = try LocalAIRequest(parameters: parameters)
                try LocalAIRuntime.shared.submit(request)
                return ["id": request.id, "status": "queued"]
            }
        }
    }

    final class Cancel: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            LocalAIFunctions.respond {
                let id = try LocalAIFunctions.id(parameters)
                try LocalAIRuntime.shared.cancel(id: id)
                return ["id": id, "requested": true]
            }
        }
    }

    final class Status: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            LocalAIFunctions.respond {
                try LocalAIRuntime.shared.snapshot(id: LocalAIFunctions.id(parameters))
            }
        }
    }

    fileprivate static func id(_ parameters: [String: Any]) throws -> String {
        guard let id = parameters["id"] as? String, !id.isEmpty else {
            throw LocalAIFailure(code: "invalid_request", message: "id is required")
        }
        return id
    }

    fileprivate static func respond(_ body: () throws -> [String: Any]) -> [String: Any] {
        do {
            return BridgeResponse.success(data: try body())
        } catch let failure as LocalAIFailure {
            return BridgeResponse.error(code: failure.code, message: failure.message)
        } catch {
            return BridgeResponse.error(code: "bridge_failed", message: "Local AI request failed.")
        }
    }
}
