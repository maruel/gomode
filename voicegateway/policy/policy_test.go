// Tests session instruction layout: server instructions before the language, with no residual placeholders.
package policy

import (
	"strings"
	"testing"
)

func TestRender(t *testing.T) {
	t.Run("server instructions precede language", func(t *testing.T) {
		got := Render("  Describe this library.\n", "fr-CA")
		if !strings.HasSuffix(got, "\n\nDescribe this library.\n\nSpeak in fr-CA.") {
			t.Fatalf("Render() = %q", got)
		}
		if strings.Contains(got, "{{") {
			t.Fatalf("placeholder left: %q", got)
		}
	})
	t.Run("blank server instructions", func(t *testing.T) {
		got := Render(" \n", "en-US")
		if strings.Contains(got, "\n\n\n") || strings.Contains(got, "{{") {
			t.Fatalf("Render() = %q", got)
		}
		if !strings.HasSuffix(got, ".\n\nSpeak in en-US.") {
			t.Fatalf("Render() = %q", got)
		}
	})
}
