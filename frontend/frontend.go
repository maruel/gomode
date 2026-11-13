// Package frontend embeds the standalone gateway's production browser application.
package frontend

import "embed"

// Files contains only precompressed production assets. Regenerate with make fix.
//
//go:embed dist
var Files embed.FS
