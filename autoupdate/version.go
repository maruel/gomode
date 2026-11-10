// Release identity and stable-update eligibility from Go build metadata.

package autoupdate

import (
	"runtime/debug"
	"strings"

	"golang.org/x/mod/semver"
)

// Version returns the running application's Go module version verbatim.
// Go preserves dirty, pseudo-version and development identities in build metadata.
func Version() string {
	b, ok := debug.ReadBuildInfo()
	if !ok {
		return "unknown"
	}
	return b.Main.Version
}

// IsStable reports whether version identifies a complete stable release.
// Development builds, pseudo-versions, prereleases and build metadata are excluded.
func IsStable(version string) bool {
	v := "v" + strings.TrimPrefix(version, "v")
	return len(v) <= 128 && semver.IsValid(v) && semver.Canonical(v) == v && semver.Prerelease(v) == "" && strings.Count(v, ".") == 2
}

// IsNewer reports whether both identities are stable and latest is strictly newer.
func IsNewer(latest, current string) bool {
	return IsStable(latest) && IsStable(current) && semver.Compare("v"+strings.TrimPrefix(latest, "v"), "v"+strings.TrimPrefix(current, "v")) > 0
}
