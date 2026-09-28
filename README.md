# Go Mode

[![Go Reference](https://pkg.go.dev/badge/github.com/maruel/gomode.svg)](https://pkg.go.dev/github.com/maruel/gomode)

Go Mode provides a reusable host contract and a native Android shell for
backend-hosted web applications. The repository contains the Go discovery
handler, MCP and OAuth 2.1 transports, WebRTC voice gateway, generated API
SDKs, and a SolidJS browser integration package.

Host applications own user accounts, authorization policy, product APIs,
content, and the MCP tools and resources they expose. The Android app connects
to a host through `/.well-known/gomode.json` and renders its web frontend.

## Packages

- Go module `github.com/maruel/gomode`: discovery manifest and voice token
  contracts.
    - `mcp/`, `oauth/`, `sse/`: reusable HTTP protocol implementations.
    - `voicegateway/`: standalone or embedded WebRTC voice gateway.
- `sdk/`: generated TypeScript, Kotlin, and Swift protocol clients plus the
  Halo Android SDK.
- `web/src/`: `@maruel/gomode` SolidJS browser integration.
- `android/gomode/`: native WebView, voice, notifications, settings, and Halo
  shell.

See [the server library guide](docs/SERVER_LIBRARY.md) to integrate a host.
