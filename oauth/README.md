# oauth

Standalone Go OAuth 2.0 authorization server and client library.

Implements: RFC 6749 (Authorization Framework), 6750 (Bearer Token Usage),
7636 (PKCE), 7009 (Token Revocation), 7662 (Token Introspection),
9068 (JWT Profile), 8414 (AS Metadata),
7591/7592 (Dynamic Client Registration), 8628 (Device Authorization),
9126 (Pushed Authorization Requests), 9207 (Issuer Identification),
9449 (DPoP), 9700 (Security BCP), and the Client ID Metadata Document
Internet-Draft (client identifiers backed by HTTPS metadata documents).
The client-credentials grant (RFC 6749 §4.4) supports only confidential
clients authenticating with private_key_jwt (RFC 7523) assertions; the
server issues no shared secrets. It backs the official MCP OAuth Client
Credentials extension (`io.modelcontextprotocol/oauth-client-credentials`).

Not implemented: RFC 8693 (Token Exchange). Mint audience-scoped tokens at
the authorization endpoint via the `resource` parameter (RFC 8707) instead.

Zero external dependencies — stdlib only.

## Packages

- [`oauth/`](./): shared token and client configuration types.
- [`oauthserver/`](oauthserver/): authorization server and its HTTP routes.
- [`oauthclient/`](oauthclient/): authorization-code client with PKCE and
  provider configuration helpers.
- [`oauthverify/`](oauthverify/): JWT access-token verification against a
  trusted issuer's discovered keys.

Import these packages from the `github.com/maruel/gomode` module. The server
constructor is `oauthserver.NewServer` with an `oauthserver.ServerConfig`;
clients use `oauthclient.NewPKCEChallenge`, `oauthclient.AuthorizationURL`,
and `oauthclient.ExchangeCode`.

## Interfaces

The `oauthserver` package delegates product-specific concerns to two interfaces:

- **SessionManager** — user login state, session attachment, user lookup, and end-session redirect
- **AuthorizationUI** — login redirect URL, consent page rendering, OIDC provider labels

Optional seams include `AuditRecorder`, `RateLimiter`, and
`IntrospectionAuthenticator`.

## License

Apache 2.0
