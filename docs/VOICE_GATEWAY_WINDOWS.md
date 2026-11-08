# Voice gateway on Windows

The standalone gateway runs as a console application. Use Windows PowerShell
5.1 or PowerShell 7, Git, and the Go version specified in [go.mod](../go.mod).
The Go build uses the checked-in frontend assets: Node, pnpm, Android, CGo, and
a C compiler are not required. Local model workers have their own prerequisites;
start with a cloud provider to check the gateway first.

## Install from source

In PowerShell:

```powershell
git clone https://github.com/maruel/gomode.git
Set-Location gomode
.\contrib\setup-voice-gateway.ps1
```

If script execution is disabled, use a session-scoped policy, subject to your
organization's policies:

```powershell
Set-ExecutionPolicy -Scope Process RemoteSigned
.\contrib\setup-voice-gateway.ps1
```

See Microsoft's [execution policy reference](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_execution_policies).
The script installs `%LOCALAPPDATA%\voice-gateway\voice-gateway.exe` and copies
[config.toml](../contrib/config.toml) to
`%USERPROFILE%\.config\voice-gateway\config.toml` if it does not exist. If
`XDG_CONFIG_HOME` is set, configuration goes under that directory instead.
Existing settings are preserved. Override either path with `-InstallDir` or
`-ConfigPath`; use the resulting configuration path with `-config` when starting.
No administrator access is needed for installation.

## Configure and start

Edit the installed TOML. Keep the providers and models you intend to use and
configure `[[trusted_issuers]]` for your Go Mode host as described in the
[gateway guide](VOICE_GATEWAY.md#authorization). Credentials belong in the
process environment. The [credential reference](../contrib/voice-gateway.env.example)
lists provider variable names; its systemd environment-file syntax is not a
PowerShell script.

For example, set `GEMINI_API_KEY` in the launch environment for the contributed
Gemini choices. To enter a key without putting it in shell history:

```powershell
$key = Get-Credential -UserName 'GEMINI_API_KEY' -Message 'Enter the API key as the password'
$env:GEMINI_API_KEY = $key.GetNetworkCredential().Password
Remove-Variable key
$gateway = Join-Path $env:LOCALAPPDATA 'voice-gateway\voice-gateway.exe'
& $gateway -frontend
```

Open <http://127.0.0.1:3480/> for the local test page. The default signaling
listener is `127.0.0.1:3479`; WebRTC uses UDP port `3480`. Only configured
providers available to the launching user appear. Cloud API keys can incur
provider charges. For local models, install their prerequisites from the
[local stack guide](VOICE_LOCAL_STACK.md) and ensure tools such as `uv` are on
the launching process's `PATH`.

For normal host use, omit `-frontend`. Use `-config 'C:\path\config.toml'` for a
custom configuration file, or set `VOICE_GATEWAY_CONFIG`. Press Ctrl+C to stop
and close sessions. Config changes cause the gateway to exit successfully;
start it again to apply them. Stop the gateway before rerunning the setup
script to upgrade its executable. A console launch does not restart itself.
The executable is not a Windows Service Control Manager service; `sc.exe create`
alone cannot run it as a service.

## Remote clients

Keep signaling on loopback behind an HTTPS reverse proxy. Configure the proxy
to forward WebSocket upgrades. Make the configured WebRTC UDP port reachable
through Windows Firewall and any router/NAT. For example, in an elevated
PowerShell session, allow the default UDP port on a trusted private network:

```powershell
New-NetFirewallRule -DisplayName 'Go Mode voice gateway UDP' -Direction Inbound -Action Allow -Protocol UDP -LocalPort 3480 -Profile Private
```

Match the rule to `server.webrtc_udp_port` and the machine's network profile.
Remote microphone access requires HTTPS. The optional test frontend grants
access to voice backends to anyone who can reach it; keep it local or put
authentication on its reverse proxy. See the [deployment and frontend guidance](VOICE_GATEWAY.md#deployment)
for routing and access requirements.

## Go tests

Run the same package selection as the Windows CI job:

```powershell
go test ./cmd/voice-gateway ./voicegateway/...
```

This runs the Go gateway tests without the web or Android test suites, live API
credentials, or model downloads. The Unix executable-replacement test is skipped
on Windows because a running binary must be stopped before an upgrade.
