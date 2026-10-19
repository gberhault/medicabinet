// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "MediCabinetCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "MediCabinetCore", targets: ["MediCabinetCore"])
    ],
    targets: [
        .target(name: "MediCabinetCore"),
        .testTarget(name: "MediCabinetCoreTests", dependencies: ["MediCabinetCore"])
    ]
)
