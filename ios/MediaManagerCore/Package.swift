// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "MediaManagerCore",
    defaultLocalization: "en",
    platforms: [
        .iOS(.v18),
    ],
    products: [
        .library(name: "MediaManagerProtos", targets: ["MediaManagerProtos"]),
        .library(name: "MediaManagerCore", targets: ["MediaManagerCore"]),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-protobuf.git", from: "1.28.1"),
        .package(url: "https://github.com/grpc/grpc-swift-2.git", from: "2.0.0"),
        .package(url: "https://github.com/grpc/grpc-swift-nio-transport.git", from: "2.0.0"),
        .package(url: "https://github.com/grpc/grpc-swift-protobuf.git", from: "2.0.0"),
    ],
    targets: [
        .target(
            name: "MediaManagerProtos",
            dependencies: [
                .product(name: "SwiftProtobuf", package: "swift-protobuf"),
                .product(name: "GRPCCore", package: "grpc-swift-2"),
                .product(name: "GRPCNIOTransportHTTP2", package: "grpc-swift-nio-transport"),
                .product(name: "GRPCProtobuf", package: "grpc-swift-protobuf"),
            ]
        ),
        .target(
            name: "MediaManagerCore",
            dependencies: [
                "MediaManagerProtos",
                .product(name: "SwiftProtobuf", package: "swift-protobuf"),
                .product(name: "GRPCCore", package: "grpc-swift-2"),
                .product(name: "GRPCNIOTransportHTTP2", package: "grpc-swift-nio-transport"),
                .product(name: "GRPCProtobuf", package: "grpc-swift-protobuf"),
            ],
            // Localizable.xcstrings: text this package shows to people.
            resources: [.process("Resources")]
        ),
        // Pure-logic tests. iOS-only package, so run on a simulator:
        // xcodebuild test -scheme MediaManagerCore -destination 'platform=iOS Simulator,name=…'
        .testTarget(
            name: "MediaManagerCoreTests",
            dependencies: [
                "MediaManagerCore",
                "MediaManagerProtos",
                .product(name: "GRPCCore", package: "grpc-swift-2"),
            ]
        ),
    ]
)
