# Build and install the Windows voice gateway and seed configuration without overwriting user settings.
#Requires -Version 5.1
[CmdletBinding()]
param(
    [string]$InstallDir = (Join-Path $env:LOCALAPPDATA 'voice-gateway'),
    [string]$ConfigPath = $(
        $configHome = $env:XDG_CONFIG_HOME
        if (-not $configHome) { $configHome = Join-Path $env:USERPROFILE '.config' }
        Join-Path $configHome 'voice-gateway/config.toml'
    )
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$InstallDir = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($InstallDir)
$ConfigPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($ConfigPath)
$exe = Join-Path $InstallDir 'voice-gateway.exe'
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null

Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    # Stop the gateway before upgrading: Windows locks a running executable.
    & go build -o $exe ./cmd/voice-gateway
    if ($LASTEXITCODE -ne 0) { throw "go build failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath $ConfigPath)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $ConfigPath -Parent) | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'config.toml') -Destination $ConfigPath
}

Write-Host "Installed: $exe"
Write-Host "Edit configuration: $ConfigPath"
Write-Host 'Set provider credentials in the launch environment, then run:'
Write-Host "& '$($exe.Replace("'", "''"))' -config '$($ConfigPath.Replace("'", "''"))'"
