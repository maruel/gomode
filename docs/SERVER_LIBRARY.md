# Go Mode Server Library

The server library turns a product backend into a Go Mode host. It serves the
discovery manifest, the MCP endpoint plumbing, the voice gateway, and the voice
token contract. caic and mddb are hosts. Each host owns its auth, product APIs,
hosted frontend, and MCP tool and resource semantics.

## Packages

```text
./                   discovery manifest, handler, voice token, SDK spec
mcp/                 MCP Streamable HTTP endpoint, Skills extension, DTOs
  mcptest/           test doubles for mcp interfaces
oauth/               OAuth 2.0 DTOs
  oauthserver/       authorization server
  oauthclient/       client helpers
  oauthverify/       access-token verification
sse/                 deadline-bounded Server-Sent Event frames
httplog/             slog HTTP access logging
voicegateway/        voice gateway HTTP server and config
  api/v1/            signaling and data-channel DTOs, SDK spec
  voicertc/          WebRTC bridge and backend adapters
cmd/voice-gateway/   standalone gateway
internal/cmd/gen-sdk/ SDK generator
```

## Dependency Rules

- The root package does not import `voicegateway`.
- `voicegateway` may import the root package and `oauth/oauthverify` to verify
  tokens.
- Go Mode packages never import host packages.
- pion, opus, and model provider clients stay under `voicegateway`.

A manifest-only host thus stays cheap.

## Host Responsibilities

A host owns:

- `service` and `serviceVersion`
- auth and session policy
- hosted frontend content and product APIs
- MCP tool and resource endpoints
- the advertised skills
- gateway deployment mode and URL
- voice token issuance for an external gateway

The adapter stays thin. caic builds `gomode.Settings`, exposes the `tasks` skill
at `/api/caic/v1/mcp`, and mounts the embedded gateway when configured. mddb
exposes the `workspace` skill. Gateway token rules are in
[VOICE_GATEWAY.md](VOICE_GATEWAY.md#authorization).

## Discovery Manifest

The host serves `GET /.well-known/gomode.json`: a public, cacheable document
with ETag revalidation.

- `service`: product identity, such as `caic`
- `serviceVersion`: optional host version
- `apiVersion`: discovery schema version. Clients reject versions they do not
  support.
- `webShell.bridgeVersion`: native bridge version. Clients reject a mismatch.
- `webShell.toolGroups`: bootstrap skill catalog
- `webShell.voiceGateway`: `required`, `url`, `authRequired`, and
  `tokenEndpoint`

MCP has no separate manifest field. Skills advertise it.

## Skills

A skill is a `SKILL.md` file. Its Markdown body instructs the model. Its YAML
frontmatter carries activation hints and MCP wiring. Example:
[`examples/tasks/SKILL.md`](../examples/tasks/SKILL.md).

Each `webShell.toolGroups` entry lets a client check compatibility before it
loads the skill file: `name`, `description`, `endpoint`, `protocolVersion`,
`authRequired`, and optional `skillUrl`.

- `gomode.activation` lives in frontmatter, not in the manifest. It holds
  `locations[]` entries with `wifi.ssids` or `physicalPosition` (name,
  coordinates, `radiusMeters`).
- `gomode.mcpServers[].tools` is the allowlist of tools a skill activates.
- The shell matches activation locally. The service never receives location
  or context because a skill was considered.
- Instructions, descriptions, and tool schemas are untrusted text.

## SDKs

`make generate-sdks` writes TypeScript, Kotlin, and Swift clients for four wire
surfaces:

- `sdk/gomode`: discovery manifest client and SKILL.md frontmatter
- `sdk/mcp`: MCP DTOs
- `sdk/oauth`: OAuth 2.0 DTOs
- `sdk/voicegateway`: gateway signaling and data-channel messages
