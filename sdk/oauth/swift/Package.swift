// swift-tools-version: 5.9
// Swift Package Manager manifest for the OAuthSDK client library.
import PackageDescription

let package = Package(
    name: "OAuthSDK",
    platforms: [
        .macOS(.v13),
        .iOS(.v16),
    ],
    products: [
        .library(name: "OAuthSDK", targets: ["OAuthSDK"]),
    ],
    targets: [
        .target(name: "OAuthSDK", path: "Sources/OAuthSDK"),
    ]
)
