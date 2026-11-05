// swift-tools-version: 5.9
// Swift Package Manager manifest for the GoModeSDK client library.
import PackageDescription

let package = Package(
    name: "GoModeSDK",
    platforms: [
        .macOS(.v13),
        .iOS(.v16),
    ],
    products: [
        .library(name: "GoModeSDKV1", targets: ["GoModeSDKV1"]),
    ],
    targets: [
        .target(name: "GoModeSDKV1", path: "Sources/GoModeSDKV1"),
    ]
)
