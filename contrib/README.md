# Standalone voice gateway deployment

The installers download a checksummed release, preserve existing configuration
and credentials, and start a per-user service.

## Install or update

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

Repeat to update. Use `--version v0.4.0` / `-Version v0.4.0` to select a release,
or `--no-service` / `-NoService` to install files only.

## Configuration and services

Configuration is at `~/.config/voice-gateway/config.toml` (Windows:
`%USERPROFILE%\.config\voice-gateway\config.toml`), or under `XDG_CONFIG_HOME`
when set. See [config.toml](config.toml) for settings and
[voice-gateway.env.example](voice-gateway.env.example) for credentials.

| Setting | Linux | macOS | Windows |
| --- | --- | --- | --- |
| Binary directory | `~/.local/bin` | `~/.local/bin` | `%LOCALAPPDATA%\voice-gateway` |
| Credentials file | `~/.config/systemd/user/voice-gateway.env` | Beside configuration | Beside executable |
| Service | systemd user `voice-gateway.service` | launchd user `com.maruel.voice-gateway` | Scheduled task `GoModeVoiceGateway` |
| Logs | `journalctl --user -u voice-gateway.service` | `~/Library/Logs/voice-gateway.log` | `voice-gateway.log` beside executable |

Override paths with `--install-dir`, `--config-path`, and `--env-file` on Unix,
or `-InstallDir`, `-ConfigPath`, and `-EnvFile` on Windows.

## Offline installation

Download a [release archive and checksums.txt](https://github.com/maruel/gomode/releases)
and transfer them together to the offline machine:

```sh
bash contrib/install.sh --archive "$PWD/voice-gateway_linux_amd64.tar.gz" --no-service
# macOS: voice-gateway_darwin_arm64.tar.gz or voice-gateway_darwin_amd64.tar.gz
```

```powershell
& .\contrib\install.ps1 -Archive "$PWD\voice-gateway_windows_amd64.zip" -NoService
```

## Releases

[.goreleaser.yaml](../.goreleaser.yaml) defines the Linux, macOS and Windows
archives for amd64 and arm64. Pushing a `v*` tag publishes a release.
`make test` validates the definition; `make release` publishes the current
version tag with `GITHUB_TOKEN`.
