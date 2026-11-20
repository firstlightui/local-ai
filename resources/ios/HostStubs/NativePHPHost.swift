// Stand-ins for NativePHP host types, used only by bin/typecheck-ios. Signatures copied from
// nativephp/mobile 4.8 resources/xcode/NativePHP/Bridge/{BridgeRouter,NativePHP}.swift.
// Keep them in sync when upgrading NativePHP; a real device build is the final check.
// NativePHP copies only resources/ios/Sources into the app.

protocol BridgeFunction {
    func execute(parameters: [String: Any]) throws -> [String: Any]
}

struct BridgeResponse {
    static func success(data: [String: Any] = [:]) -> [String: Any] {
        data
    }

    static func error(code: String, message: String, data: [String: Any] = [:]) -> [String: Any] {
        ["status": "error", "code": code, "message": message, "data": data]
    }
}

final class LaravelBridge {
    static let shared = LaravelBridge()

    var send: ((_ event: String, _ payload: [String: Any?]) -> Void)?
}
