// swift-tools-version: 5.9
// Unit tests for the generation queue, with a stand-in model. NativePHP copies only
// Sources/ into the app, never this file, HostStubs/ or Tests/.
import PackageDescription

let package = Package(
    name: "LocalAIRuntime",
    platforms: [.macOS(.v14)],
    targets: [
        .target(
            name: "LocalAIRuntime",
            path: ".",
            sources: ["Sources/LocalAIRuntime.swift", "HostStubs/NativePHPHost.swift"]
        ),
        .testTarget(name: "LocalAIRuntimeTests", dependencies: ["LocalAIRuntime"], path: "Tests/LocalAIRuntimeTests"),
    ]
)
