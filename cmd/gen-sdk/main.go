// Command gen-sdk generates the Go Mode, MCP, and voice gateway client SDKs.
package main

import (
	"log"

	"github.com/maruel/apisdkgen"
	"github.com/maruel/gomode"
	"github.com/maruel/gomode/mcp"
	"github.com/maruel/gomode/oauth"
	voicev1 "github.com/maruel/gomode/voicegateway/api/v1"
)

func main() {
	for _, api := range []apisdkgen.API{
		apisdkgen.NewAPI(".", output("sdk/gomode", "sdk/gomode/kotlin/src/main/kotlin/com/fghbuild/gomode/sdk/v1", "sdk/gomode/swift/Sources/GoModeSDK"), gomode.SDKAPI()),
		apisdkgen.NewAPI("mcp", output("sdk/mcp", "sdk/mcp/kotlin/src/main/kotlin/com/fghbuild/mcp/sdk/v1", "sdk/mcp/swift/Sources/MCPSDK"), mcp.SDKAPI()),
		apisdkgen.NewAPI("oauth", output("sdk/oauth", "sdk/oauth/kotlin/src/main/kotlin/com/caic/oauth/sdk/v1", "sdk/oauth/swift/Sources/OAuthSDK"), oauth.SDKAPI()),
		apisdkgen.NewAPI("voicegateway/api/v1", output("sdk/voicegateway", "sdk/voicegateway/kotlin/src/main/kotlin/com/caic/voicegateway/sdk/v1", "sdk/voicegateway/swift/Sources/VoiceGatewaySDK"), voicev1.SDKAPI()),
	} {
		if err := apisdkgen.Generate(&api); err != nil {
			log.Fatal(err)
		}
	}
}

func output(dir, kotlin, swift string) apisdkgen.OutputConfig {
	return apisdkgen.OutputConfig{
		TypeScriptDir: dir + "/ts/v1",
		KotlinDir:     kotlin,
		SwiftDir:      swift,
		MarkdownDir:   dir,
	}
}
