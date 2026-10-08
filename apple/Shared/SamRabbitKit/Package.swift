// swift-tools-version: 6.2
// SamRabbitKit: everything the SamRabbit iPhone app, its widgets and the Apple Watch app share -
// models of the Mac bridge's mobile API, the API client (with the SSE stream), pairing, token and
// App Group storage, the summary cache, the conversation timeline, the theme and the fluid orb.
// macOS is listed only so `swift test` can run the client against apple/dev/fake_bridge.py.
import PackageDescription

let package = Package(
    name: "SamRabbitKit",
    platforms: [.iOS("26.0"), .watchOS("26.0"), .macOS("26.0")],
    products: [
        .library(name: "SamRabbitKit", targets: ["SamRabbitKit"]),
    ],
    targets: [
        .target(
            name: "SamRabbitKit",
            swiftSettings: [
                // The orb field is drawn on the CPU every frame; keep it fast in Debug builds too.
                .unsafeFlags(["-O"], .when(configuration: .debug)),
            ]
        ),
        .testTarget(
            name: "SamRabbitKitTests",
            dependencies: ["SamRabbitKit"]
        ),
    ],
    swiftLanguageModes: [.v6]
)
