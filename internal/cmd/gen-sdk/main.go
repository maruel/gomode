// Command gen-sdk generates versioned Go Mode protocol SDK packages.
//
// It writes TypeScript, Kotlin, and Swift clients and an API.<version>.md reference under
// sdk/<family> for discovery and SKILL.md frontmatter (gomode v1), MCP (v1), OAuth (v1), and the
// voice gateway (v1 and v2). Imports name their version: TypeScript exports
// @maruel/gomode/sdk/<family>/<version>/*, Kotlin packages end in .sdk.v1 or .sdk.v2, and Swift
// library products are GoModeSDKV1, MCPSDKV1, OAuthSDKV1, VoiceGatewaySDKV1, and VoiceGatewaySDKV2.
package main

import (
	"bytes"
	"flag"
	"fmt"
	"os"

	"github.com/maruel/apisdkgen"
	"github.com/maruel/gomode"
	"github.com/maruel/gomode/mcp"
	"github.com/maruel/gomode/oauth"
	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
	voicev2 "github.com/maruel/gomode/voicegateway/api/v2"
	"github.com/maruel/gomode/voicegateway/policy"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "gen-sdk:", err)
		os.Exit(1)
	}
}

func run() error {
	check := flag.Bool("check", false, "verify generated SDKs without writing them")
	flag.Parse()
	for _, api := range []apisdkgen.API{
		apisdkgen.NewAPI("sdk/gomode", "GoModeSDK",
			apisdkgen.NewVersion("v1", ".", gomode.SDKAPI)),
		apisdkgen.NewAPI("sdk/mcp", "MCPSDK",
			apisdkgen.NewVersion("v1", "mcp", mcp.SDKAPI)),
		apisdkgen.NewAPI("sdk/oauth", "OAuthSDK",
			apisdkgen.NewVersion("v1", "oauth", oauth.SDKAPI)),
		apisdkgen.NewAPI("sdk/voicegateway", "VoiceGatewaySDK",
			apisdkgen.NewVersion("v1", "voicegateway/api/v1", voicev1.SDKAPI),
			apisdkgen.NewVersion("v2", "voicegateway/api/v2", voicev2.SDKAPI)),
	} {
		var err error
		if *check {
			err = api.Verify()
		} else {
			err = api.Generate()
		}
		if err != nil {
			return err
		}
	}
	const destination = "android/gomode/src/main/assets/voice-policy.txt"
	if *check {
		data, err := os.ReadFile(destination)
		if err != nil {
			return err
		}
		if !bytes.Equal(data, []byte(policy.Instructions)) {
			return fmt.Errorf("%s is stale; run make generate-sdks", destination)
		}
		return nil
	}
	if err := os.MkdirAll("android/gomode/src/main/assets", 0o750); err != nil {
		return err
	}
	return os.WriteFile(destination, []byte(policy.Instructions), 0o600)
}
