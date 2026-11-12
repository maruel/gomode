//go:build !windows

// UPnP gateway route source selection where binding a source address leaves routing unconstrained.

package voicertc

import (
	"context"
	"net"
)

// gatewayRouteSourceIPv4 returns the source address of the route to gatewayIP.
//
// Binding hostIP does not constrain the egress route here, for example under
// Linux policy routing, so the unbound route decides where UPnP requests go.
func gatewayRouteSourceIPv4(ctx context.Context, _, gatewayIP net.IP) (net.IP, error) {
	return unboundRouteSourceIPv4(ctx, gatewayIP)
}
