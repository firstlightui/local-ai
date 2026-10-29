import UIKit

private final class LocalAIProbeLifecycleObserver: NSObject {
    static let shared = LocalAIProbeLifecycleObserver()

    func install() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(didEnterBackground),
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(didBecomeActive),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )
    }

    @objc private func didEnterBackground() {
        Task { await LocalAIProbeRuntime.shared.setBackgrounded(true) }
    }

    @objc private func didBecomeActive() {
        Task { await LocalAIProbeRuntime.shared.setBackgrounded(false) }
    }
}

func initializeLocalAIProbe() {
    LocalAIProbeLifecycleObserver.shared.install()
}
