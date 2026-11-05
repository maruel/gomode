// Command gen-sdk generates the Go Mode, MCP, and voice gateway client SDKs.
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
		apisdkgen.NewAPI(".", output("sdk/gomode", "sdk/gomode/kotlin/src/main/kotlin/com/fghbuild/gomode/sdk/v1", "sdk/gomode/swift/Sources/GoModeSDKV1"), gomode.SDKAPI()),
		apisdkgen.NewAPI("mcp", output("sdk/mcp", "sdk/mcp/kotlin/src/main/kotlin/com/fghbuild/mcp/sdk/v1", "sdk/mcp/swift/Sources/MCPSDKV1"), mcp.SDKAPI()),
		apisdkgen.NewAPI("oauth", output("sdk/oauth", "sdk/oauth/kotlin/src/main/kotlin/com/caic/oauth/sdk/v1", "sdk/oauth/swift/Sources/OAuthSDKV1"), oauth.SDKAPI()),
		apisdkgen.NewAPI("voicegateway/api/v1", output("sdk/voicegateway", "sdk/voicegateway/kotlin/src/main/kotlin/com/caic/voicegateway/sdk/v1", "sdk/voicegateway/swift/Sources/VoiceGatewaySDKV1"), voicev1.SDKAPI()),
		apisdkgen.NewAPI("voicegateway/api/v2", apisdkgen.OutputConfig{
			TypeScriptDir: "sdk/voicegateway/ts/v2",
			KotlinDir:     "sdk/voicegateway/kotlin/src/main/kotlin/com/caic/voicegateway/sdk/v2",
			SwiftDir:      "sdk/voicegateway/swift/Sources/VoiceGatewaySDKV2",
			MarkdownDir:   "sdk/voicegateway/v2",
		}, voicev2.SDKAPI()),
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

func output(dir, kotlin, swift string) apisdkgen.OutputConfig {
	return apisdkgen.OutputConfig{
		TypeScriptDir: dir + "/ts/v1",
		KotlinDir:     kotlin,
		SwiftDir:      swift,
		MarkdownDir:   dir + "/v1",
	}
}
