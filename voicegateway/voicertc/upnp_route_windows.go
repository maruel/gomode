// UPnP gateway route source selection constrained to the interface owning the bound source address.

package voicertc

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"unsafe"

	"golang.org/x/sys/windows"
)

var procGetBestRoute2 = windows.NewLazySystemDLL("iphlpapi.dll").NewProc("GetBestRoute2")

// gatewayRouteSourceIPv4 returns the source address of the route that UPnP
// sockets bound to hostIP use to reach gatewayIP.
//
// Windows uses strong host sends by default: a socket bound to a source
// address only considers routes through the interface owning that address.
// The lookup is therefore restricted to hostIP's interface, so an overlapping
// route on another interface, such as a Tailscale subnet route covering the
// LAN, does not reject an on-link gateway. An interface configured for weak
// host sends uses the unconstrained route like other platforms.
func gatewayRouteSourceIPv4(ctx context.Context, hostIP, gatewayIP net.IP) (net.IP, error) {
	index, err := ipv4InterfaceIndex(hostIP)
	if err != nil {
		return nil, err
	}
	row := windows.MibIpInterfaceRow{Family: windows.AF_INET, InterfaceIndex: index}
	if err := windows.GetIpInterfaceEntry(&row); err != nil {
		return nil, fmt.Errorf("read IPv4 settings of interface %d owning %s: %w", index, hostIP, err)
	}
	if row.WeakHostSend != 0 {
		return unboundRouteSourceIPv4(ctx, gatewayIP)
	}
	return interfaceRouteSourceIPv4(index, gatewayIP)
}

// ipv4InterfaceIndex returns the index of the interface that owns ip.
func ipv4InterfaceIndex(ip net.IP) (uint32, error) {
	ifaces, err := net.Interfaces()
	if err != nil {
		return 0, fmt.Errorf("list network interfaces: %w", err)
	}
	var errs []error
	for _, iface := range ifaces {
		addrs, err := iface.Addrs()
		if err != nil {
			errs = append(errs, fmt.Errorf("list addresses of interface %q: %w", iface.Name, err))
			continue
		}
		for _, addr := range addrs {
			if prefix, ok := addr.(*net.IPNet); ok && prefix.IP.Equal(ip) {
				return uint32(iface.Index), nil //nolint:gosec // Windows interface indexes are NET_IFINDEX (ULONG) values.
			}
		}
	}
	return 0, errors.Join(fmt.Errorf("no network interface owns %s", ip), errors.Join(errs...))
}

// interfaceRouteSourceIPv4 returns the source address of the best route to
// gatewayIP through the interface with the given index.
func interfaceRouteSourceIPv4(index uint32, gatewayIP net.IP) (net.IP, error) {
	v4 := gatewayIP.To4()
	if v4 == nil {
		return nil, fmt.Errorf("route to UPnP gateway requires an IPv4 address, got %s", gatewayIP)
	}
	// SOCKADDR_INET stores an IPv4 sockaddr_in in its first bytes: the family,
	// the port, then the address in network byte order.
	destination := windows.RawSockaddrInet{Family: windows.AF_INET}
	destination.Data[0] = binary.NativeEndian.Uint32(v4)
	var route windows.MibIpForwardRow2
	var source windows.RawSockaddrInet
	r, _, _ := procGetBestRoute2.Call(
		0,
		uintptr(index),
		0,
		uintptr(unsafe.Pointer(&destination)), // #nosec G103 -- destination is a live SOCKADDR_INET.
		0,
		uintptr(unsafe.Pointer(&route)),  // #nosec G103 -- route is a live MIB_IPFORWARD_ROW2 output buffer.
		uintptr(unsafe.Pointer(&source)), // #nosec G103 -- source is a live SOCKADDR_INET output buffer.
	)
	if r != 0 {
		return nil, fmt.Errorf("route to UPnP gateway %s through interface %d: %w", gatewayIP, index, windows.Errno(r))
	}
	if source.Family != windows.AF_INET {
		return nil, fmt.Errorf("route to UPnP gateway %s through interface %d has source family %d", gatewayIP, index, source.Family)
	}
	sourceIP := make(net.IP, net.IPv4len)
	binary.NativeEndian.PutUint32(sourceIP, source.Data[0])
	return sourceIP, nil
}
