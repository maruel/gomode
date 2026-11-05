// swift-tools-version: 5.9
// Swift Package Manager manifest for the VoiceGatewaySDK client library.
import PackageDescription

let package = Package(
    name: "VoiceGatewaySDK",
    platforms: [
        .macOS(.v13),
        .iOS(.v16),
    ],
    products: [
        .library(name: "VoiceGatewaySDKV1", targets: ["VoiceGatewaySDKV1"]),
        .library(name: "VoiceGatewaySDKV2", targets: ["VoiceGatewaySDKV2"]),
    ],
    targets: [
        .target(name: "VoiceGatewaySDKV1", path: "Sources/VoiceGatewaySDKV1"),
        .target(name: "VoiceGatewaySDKV2", path: "Sources/VoiceGatewaySDKV2"),
    ]
)
