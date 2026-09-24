# Standalone Go Mode host library and shell

Extract Go Mode contracts, MCP and OAuth 2.1 transports, browser integration, generated SDKs, and the Android shell into this repository. caic and mddb then consume the shared code through host adapters.

## Phase 1 — extract-core: Standalone contracts and transports

- **Scope:** Go Mode discovery, voice gateway, MCP, OAuth, SSE, SDK generation.
- **Preserve:** Host-specific identity, auth, and resource semantics stay with each host.
- **Verify:** Standalone Go tests and generated SDK checks pass.

## Phase 2 — extract-clients: Standalone Android and browser clients

- **Scope:** Android shell, its dependencies, and reusable browser package.
- **Verify:** Android and browser builds and focused tests pass in this repository.

## Phase 3 — adopt-hosts: caic and mddb use Go Mode

- **Depends on:** extract-core, extract-clients
- **Scope:** caic import and build cleanup; mddb discovery, MCP adapter, frontend integration.
- **Verify:** Both hosts pass their required repository checks and end-to-end tests.
