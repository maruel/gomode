// Package policy owns voice instructions shared by gateways and bundled offline engines.
package policy

import _ "embed"

// Instructions is the immutable common voice policy. SDK generation bundles these exact
// bytes for offline engines; clients do not maintain a second policy.
//
//go:embed instructions.txt
var Instructions string
