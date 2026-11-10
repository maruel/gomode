# Standalone voice gateway deployment

The installers download the latest stable release, verify its checksum, preserve
existing configuration and credentials, and start the user service. Repeat the
same command to update. The scripts take no arguments.

Linux or macOS:

```sh
curl -fsSL https://raw.githubusercontent.com/maruel/gomode/main/contrib/install.sh -o /tmp/install-voice-gateway.sh
bash /tmp/install-voice-gateway.sh
```

Windows PowerShell:

```powershell
Invoke-WebRequest -UseBasicParsing https://raw.githubusercontent.com/maruel/gomode/main/contrib/install.ps1 -OutFile "$env:TEMP\install-voice-gateway.ps1"
& "$env:TEMP\install-voice-gateway.ps1"
```

Configuration is at `~/.config/voice-gateway/config.toml` (Windows:
`%USERPROFILE%\.config\voice-gateway\config.toml`), or under `XDG_CONFIG_HOME`
when set. Windows uses the persistent user or machine value. See
[config.toml](config.toml) for settings and
[voice-gateway.env.example](voice-gateway.env.example) for credentials.

| Setting | Linux | macOS | Windows |
| --- | --- | --- | --- |
| Binary directory | `~/.local/bin` | `~/.local/bin` | `%LOCALAPPDATA%\voice-gateway` |
| Credentials file | `~/.config/systemd/user/voice-gateway.env` | Beside configuration | Beside executable |
| Service | systemd user `voice-gateway.service` | launchd user `com.maruel.voice-gateway` | Scheduled task `GoModeVoiceGateway` |
| Logs | `journalctl --user -u voice-gateway.service` | `~/Library/Logs/voice-gateway.log` | `voice-gateway.log` beside executable |

To rebuild from a Windows checkout and restart the installed scheduled task, run
`./contrib/rebuild-windows.ps1` from PowerShell. Go must be installed and
the task must first be set up using the Windows installer above.

For other installation workflows, build from source:

```sh
go install github.com/maruel/gomode/cmd/voice-gateway@latest
```

[.goreleaser.yaml](../.goreleaser.yaml) defines the Linux, macOS and Windows
release archives for amd64 and arm64. `make test` validates the definition;
pushing a `v*` tag publishes a release.
