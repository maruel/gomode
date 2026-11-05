// Tests route-selected text subprotocol matching for published gateway versions.

package api

import "testing"

func TestProtocolVersion(t *testing.T) {
	t.Parallel()
	t.Run("AcceptsTextSubprotocol", func(t *testing.T) {
		for _, version := range []ProtocolVersion{ProtocolV1, ProtocolV2} {
			t.Run(version.String(), func(t *testing.T) {
				t.Parallel()
				for _, headers := range [][]string{nil, {""}, {version.TextSubprotocol()}, {version.TextSubprotocol() + ", gomode.ticket.secret"}, {"gomode.text.future, " + version.TextSubprotocol()}} {
					if !version.AcceptsTextSubprotocol(headers) {
						t.Fatalf("matching/omitted protocol rejected: %v", headers)
					}
				}
				other := ProtocolV1
				if version == other {
					other = ProtocolV2
				}
				for _, headers := range [][]string{{other.TextSubprotocol()}, {"unrelated"}} {
					if version.AcceptsTextSubprotocol(headers) {
						t.Fatalf("mismatched protocol accepted: %v", headers)
					}
				}
			})
		}
	})
}
