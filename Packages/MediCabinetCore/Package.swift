// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "MediCabinetCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "MediCabinetCore", targets: ["MediCabinetCore"]),
        // Fakes and the repository contract suite, reused by the app's persistence tests.
        .library(name: "MediCabinetCoreTestSupport", targets: ["MediCabinetCoreTestSupport"])
    ],
    targets: [
        .target(name: "MediCabinetCore"),
        .target(name: "MediCabinetCoreTestSupport", dependencies: ["MediCabinetCore"]),
        .testTarget(name: "MediCabinetCoreTests", dependencies: ["MediCabinetCore", "MediCabinetCoreTestSupport"])
    ]
)
