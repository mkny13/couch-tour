// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "CouchTourKit",
    platforms: [.macOS(.v14)],
    products: [
        .library(name: "CouchTourKit", targets: ["CouchTourKit"]),
    ],
    dependencies: [
        .package(url: "https://github.com/groue/GRDB.swift.git", from: "6.29.0"),
    ],
    targets: [
        .target(
            name: "CouchTourKit",
            dependencies: [
                .product(name: "GRDB", package: "GRDB.swift"),
            ]
        ),
        .testTarget(
            name: "CouchTourKitTests",
            dependencies: ["CouchTourKit"],
            resources: [.copy("Fixtures")]
        ),
    ]
)
