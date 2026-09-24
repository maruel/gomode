# Go Mode

Standalone Go Mode contracts, MCP and OAuth transports, generated SDKs, browser
integration, and Android shell. Host repositories own product APIs, auth policy,
content, and MCP tool/resource semantics. Keep the root Go package free of heavy
voice gateway dependencies and host imports.

Read the narrower guides before editing `mcp/`, `oauth/`, `android/gomode/`,
`sdk/halo/`, or `voicegateway/voicertc/`. The MCP guide requires checking the
current protocol draft before protocol changes.

Use `make fix`, `make verify`, `make test`, and `make test-race` after changes. Regenerate SDKs
with `make generate-sdks` after DTO or route changes. `make android-check`
builds the Android app and SDKs, compiles instrumented tests, and runs lint,
unit tests, and coverage.

The TypeScript package `@maruel/gomode` exports source for Solid/Vite hosts.
Keep `web/src` host neutral and configure its MCP endpoint in each host.
