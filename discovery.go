// Package gomode defines Go Mode service discovery and voice token contracts.
//
// A host serves the discovery manifest with [NewHandler] and owns its accounts, authorization,
// hosted frontend, product APIs, and MCP tool and resource semantics. This package stays cheap for
// manifest-only hosts: it never imports voicegateway, Go Mode packages never import host packages,
// and WebRTC, Opus, and model provider clients stay under voicegateway. voicegateway may import this
// package and oauth/oauthverify to verify tokens.
//
// User and integration guides are at https://docs.gomode.dev.
package gomode

// settingsPath is the well-known discovery document path for the Go Mode root
// settings. It is a static, publicly readable bootstrap manifest, not a REST
// API, so it lives under /.well-known/ per RFC 8615 rather than /api/.
const settingsPath = "/.well-known/gomode.json"
