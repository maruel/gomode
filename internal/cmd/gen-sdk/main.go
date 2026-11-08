// Command gen-sdk generates versioned Go Mode protocol SDK packages.
package main

import (
	"flag"
	"fmt"
	"os"

	"github.com/maruel/apisdkgen"
	"github.com/maruel/gomode"
	"github.com/maruel/gomode/mcp"
	"github.com/maruel/gomode/oauth"
	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
	voicev2 "github.com/maruel/gomode/voicegateway/api/v2"
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
	return nil
}
