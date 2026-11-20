import XCTest
@testable import LocalAIRuntime

final class LocalAIRuntimeTests: XCTestCase {
    /// Behaves like Foundation Models: streams cumulative text, and when its task
    /// is cancelled it ends the stream normally instead of throwing.
    private func runtime(onStart: XCTestExpectation) -> LocalAIRuntime {
        LocalAIRuntime(availability: { ["status": "available"] }, model: { _, onText in
            onText("Once")
            onStart.fulfill()
            for _ in 0..<200 where !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 10_000_000)
            }
            onText("Once upon a time")
        })
    }

    private func request(_ id: String) throws -> LocalAIRequest {
        try LocalAIRequest(parameters: ["id": id, "messages": [["role": "user", "content": "Tell a story."]]])
    }

    private func settled(_ runtime: LocalAIRuntime, _ id: String) async throws -> [String: Any] {
        for _ in 0..<500 {
            let snapshot = try runtime.snapshot(id: id)
            if !["queued", "running"].contains(snapshot["status"] as? String) {
                return snapshot
            }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        return try runtime.snapshot(id: id)
    }

    func testCancellingARunningGenerationEndsCancelled() async throws {
        let started = expectation(description: "started")
        let runtime = runtime(onStart: started)

        try runtime.submit(request("a"))
        await fulfillment(of: [started], timeout: 5)
        try runtime.cancel(id: "a")

        let snapshot = try await settled(runtime, "a")
        XCTAssertEqual(snapshot["status"] as? String, "cancelled")
        XCTAssertEqual(snapshot["text"] as? String, "Once")
    }

    func testAnUncancelledGenerationCompletes() async throws {
        let started = expectation(description: "started")
        let runtime = runtime(onStart: started)

        try runtime.submit(request("b"))

        let snapshot = try await settled(runtime, "b")
        XCTAssertEqual(snapshot["status"] as? String, "completed")
        XCTAssertEqual(snapshot["text"] as? String, "Once upon a time")
        await fulfillment(of: [started], timeout: 1)
    }
}
