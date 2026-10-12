// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "CouchTourKit",
    platforms: [.macOS(.v14)],
    products: [
        .library(name: "CouchTourKit", targets: ["CouchTourKit"]),
    ],
    dependencies: [
        .package(url: "https://github.com/groue/GRDB.swift.git", "7.8.0"..<"7.9.0"), // 7.9+ needs Swift tools 6.1; CI pins Xcode 16.2 (Swift 6.0)
    ],
    targets: [
        .target(
            name: "CouchTourKit",
            dependencies: [
                .product(name: "GRDB", package: "GRDB.swift"),
            ],
            resources: [.process("Resources")]
        ),
        .testTarget(
            name: "CouchTourKitTests",
            dependencies: [
                "CouchTourKit",
                .product(name: "GRDB", package: "GRDB.swift"),
            ],
            resources: [.copy("Fixtures")]
        ),
    ]
)
