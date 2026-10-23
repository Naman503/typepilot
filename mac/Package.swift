// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "TypePilot",
    platforms: [.macOS(.v14)],
    products: [
        .executable(name: "TypePilot", targets: ["TypePilot"]),
        .library(name: "TypePilotCore", targets: ["TypePilotCore"]),
    ],
    targets: [
        // Pure Swift: AI providers, memory, text processing, completion engine. Fully unit-tested.
        .target(
            name: "TypePilotCore",
            linkerSettings: [.linkedLibrary("sqlite3")]
        ),
        // The AppKit app: accessibility, event tap, overlay panels, settings UI.
        .executableTarget(
            name: "TypePilot",
            dependencies: ["TypePilotCore"],
            linkerSettings: [
                .linkedFramework("AppKit"),
                .linkedFramework("ApplicationServices"),
                .linkedFramework("Carbon"),
            ]
        ),
        .testTarget(name: "TypePilotCoreTests", dependencies: ["TypePilotCore"]),
    ],
    swiftLanguageModes: [.v5]
)
