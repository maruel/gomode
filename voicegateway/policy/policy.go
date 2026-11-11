// Package policy owns voice instructions shared by gateways and bundled offline engines.
package policy

import (
	_ "embed"
	"strings"
)

// Instructions is the immutable common voice policy template. SDK generation bundles
// these exact bytes for offline engines; clients do not maintain a second policy.
//
// The template owns the prompt layout: the shared policy frames the session,
// {{instructions}} receives the server instructions, and the selected language
// comes last so server instructions cannot override it. Offline engines render
// it with the same rules as Render.
//
//go:embed instructions.txt
var Instructions string

// Render returns the system instruction for a session. Blank server
// instructions leave no empty paragraph.
func Render(instructions, language string) string {
	instructions = strings.TrimSpace(instructions)
	paragraph := "\n\n" + instructions
	if instructions == "" {
		paragraph = ""
	}
	out := strings.Replace(strings.TrimSpace(Instructions), "\n\n{{instructions}}", paragraph, 1)
	return strings.Replace(out, "{{language}}", language, 1)
}
