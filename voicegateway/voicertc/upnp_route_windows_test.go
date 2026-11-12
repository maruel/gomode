// Tests interface-constrained UPnP route lookup through the real Windows routing table.

package voicertc

import (
	"net"
	"strings"
	"testing"
)

func TestGatewayRouteSourceIPv4(t *testing.T) {
	t.Parallel()
	t.Run("valid", func(t *testing.T) {
		t.Parallel()
		hostIP, peerIP := onLinkIPv4Peer(t)
		got, err := gatewayRouteSourceIPv4(t.Context(), hostIP, peerIP)
		if err != nil {
			t.Fatal(err)
		}
		if !got.Equal(hostIP) {
			t.Fatalf("route source to on-link %s = %s, want %s", peerIP, got, hostIP)
		}
	})
	t.Run("error unowned host", func(t *testing.T) {
		t.Parallel()
		_, err := gatewayRouteSourceIPv4(t.Context(), net.IPv4(192, 0, 2, 1), net.IPv4(192, 0, 2, 2))
		if err == nil {
			t.Fatal("expected error")
		}
		if !strings.Contains(err.Error(), "no network interface owns 192.0.2.1") {
			t.Fatalf("unexpected error: %v", err)
		}
	})
}

// onLinkIPv4Peer returns a local non-loopback IPv4 address and another address
// in its on-link prefix.
func onLinkIPv4Peer(t *testing.T) (hostIP, peerIP net.IP) {
	ifaces, err := net.Interfaces()
	if err != nil {
		t.Fatal(err)
	}
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			t.Fatal(err)
		}
		for _, addr := range addrs {
			prefix, ok := addr.(*net.IPNet)
			if !ok {
				continue
			}
			v4 := prefix.IP.To4()
			if v4 == nil || v4.IsLinkLocalUnicast() {
				continue
			}
			if ones, _ := prefix.Mask.Size(); ones > 30 {
				continue
			}
			peer := make(net.IP, net.IPv4len)
			copy(peer, v4.Mask(prefix.Mask))
			peer[3]++
			if peer.Equal(v4) {
				peer[3]++
			}
			return v4, peer
		}
	}
	t.Skip("no up non-loopback IPv4 interface with an on-link peer")
	return nil, nil
}
