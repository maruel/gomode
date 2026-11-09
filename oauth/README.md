# oauth

Standalone Go OAuth 2.0 authorization server and client library using the standard
library, imported from the `github.com/maruel/gomode` module.

- [`oauth/`](./): shared constants, protocol DTOs, and protocol coverage.
- [`oauthserver/`](oauthserver/): authorization server, account authority, and durable authorization state.
- [`oauthclient/`](oauthclient/): authorization-code client with PKCE and provider helpers.
- [`oauthverify/`](oauthverify/): JWT access-token verification against trusted issuers.

See the Go package and interface documentation for integration contracts.

## License

Apache 2.0
