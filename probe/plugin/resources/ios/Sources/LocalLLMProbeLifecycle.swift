import UIKit

private final class LocalLLMProbeLifecycleObserver: NSObject {
    static let shared = LocalLLMProbeLifecycleObserver()

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
        Task { await LocalLLMProbeRuntime.shared.setBackgrounded(true) }
    }

    @objc private func didBecomeActive() {
        Task { await LocalLLMProbeRuntime.shared.setBackgrounded(false) }
    }
}

func initializeLocalLLMProbe() {
    LocalLLMProbeLifecycleObserver.shared.install()
}
