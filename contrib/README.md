# Standalone voice gateway deployment

[docs.gomode.dev](https://docs.gomode.dev/guide/voice#install-the-standalone-gateway)
covers installation, configuration paths, services, and logs. `install.sh` and
`install.ps1` take no arguments and install the latest stable release.

To rebuild from a Windows checkout and restart the installed scheduled task, run
`./contrib/rebuild-windows.ps1` from PowerShell. Go must be installed and the
task must first be set up with `install.ps1`. The task queues relaunch after
updates or configuration changes, starts at logon, and retries failures after
one minute. An intentional stop leaves it stopped until requested again or the
next logon.

[.goreleaser.yaml](../.goreleaser.yaml) defines the Linux, macOS and Windows
release archives for amd64 and arm64. Pushing a `v*` tag runs `make release`
in CI, which publishes them.
