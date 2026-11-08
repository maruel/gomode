# Install the Windows voice gateway, preserve configuration, and restart its scheduled task during upgrades.
#Requires -Version 5.1
[CmdletBinding()]
param(
    [string]$InstallDir = (Join-Path $env:LOCALAPPDATA 'voice-gateway'),
    [string]$TaskName = 'GoModeVoiceGateway'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$InstallDir = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($InstallDir)
$configHome = [Environment]::GetEnvironmentVariable('XDG_CONFIG_HOME', 'User')
if (-not $configHome) { $configHome = [Environment]::GetEnvironmentVariable('XDG_CONFIG_HOME', 'Machine') }
if (-not $configHome) { $configHome = Join-Path $env:USERPROFILE '.config' }
$configPath = Join-Path $configHome 'voice-gateway/config.toml'
if ($configPath -notmatch '^(?:[A-Za-z]:[\\/]|\\\\)') { throw "Configuration path '$configPath' must be absolute." }
$exe = Join-Path $InstallDir 'voice-gateway.exe'
$logFile = Join-Path $InstallDir 'voice-gateway.log'
$envFile = Join-Path $InstallDir 'voice-gateway.env'
$task = Get-ScheduledTask | Where-Object { $_.TaskPath -eq '\' -and $_.TaskName -eq $TaskName }
if ($null -ne $task -and (@($task.Actions).Count -ne 1 -or $task.Actions[0].Execute -ne $exe)) {
    throw "Task '$TaskName' does not run '$exe'. Use the original -InstallDir or a different -TaskName."
}
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    # Build as windowsgui so no console appears on startup.
    & go install -ldflags='-H windowsgui' ./cmd/voice-gateway
    if ($LASTEXITCODE -ne 0) { throw "go install failed with exit code $LASTEXITCODE" }
    $goEnv = & go env -json GOBIN GOPATH | ConvertFrom-Json
} finally {
    Pop-Location
}
$goBin = $goEnv.GOBIN
if (-not $goBin) { $goBin = Join-Path ($goEnv.GOPATH -split [IO.Path]::PathSeparator)[0] 'bin' }
$source = Join-Path $goBin 'voice-gateway.exe'

New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
if (-not (Test-Path -LiteralPath $envFile)) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'voice-gateway.env.example') -Destination $envFile
}
if (-not (Test-Path -LiteralPath $configPath)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $configPath -Parent) | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'config.toml') -Destination $configPath
}

try {
    if ($null -ne $task) {
        # Prevent the one-minute trigger from starting the gateway during the copy.
        Disable-ScheduledTask -TaskName $TaskName -TaskPath '\' | Out-Null
        $gateway = Get-Process -Name 'voice-gateway' -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $exe } | Select-Object -First 1
        # Retain the process handle so PID reuse cannot change which process we wait on.
        if ($null -ne $gateway) { $null = $gateway.Handle }
        if ($null -ne $gateway) {
            $stop = $null
            if ([Threading.EventWaitHandle]::TryOpenExisting("Local\GoModeVoiceGateway-$($gateway.Id)", [ref]$stop)) {
                try { $null = $stop.Set() } finally { $stop.Dispose() }
            }
            # Give the shutdown event ten seconds before Scheduler can force termination.
            if (-not $gateway.WaitForExit(10000)) {
                Stop-ScheduledTask -TaskName $TaskName -TaskPath '\'
                if (-not $gateway.HasExited) { Stop-Process -InputObject $gateway -Force }
            }
            if (-not $gateway.WaitForExit(30000)) {
                throw "Timed out stopping '$exe' (PID $($gateway.Id))."
            }
        }
    }
    if ($source -ne $exe) { Copy-Item -LiteralPath $source -Destination $exe -Force }

    $user = [Environment]::UserDomainName + '\' + [Environment]::UserName
    $action = New-ScheduledTaskAction -Execute $exe -Argument ('-log-file "' + $logFile + '" -env-file "' + $envFile + '"') -WorkingDirectory $InstallDir
    # Interactive tasks run only while signed in and use the persistent user environment,
    # not shell-only variables from this setup session.
    $principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
    # No repetition duration means retry indefinitely after any exit, including config changes.
    $triggers = @(
        New-ScheduledTaskTrigger -AtLogOn -User $user
        New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 1)
    )
    $settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero) -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
    $task = Register-ScheduledTask -TaskName $TaskName -TaskPath '\' -Action $action -Principal $principal -Trigger $triggers -Settings $settings -Description 'Go Mode voice gateway' -Force
} finally {
    # Restart an existing task even when copying or registration fails.
    if ($null -ne $task) {
        Enable-ScheduledTask -TaskName $TaskName -TaskPath '\' | Out-Null
        Start-ScheduledTask -TaskName $TaskName -TaskPath '\'
    }
}

Write-Host "Installed: $exe"
Write-Host "Edit configuration: $configPath"
Write-Host "Edit API keys: $envFile"
Write-Host "Requested start of scheduled task: $TaskName"
Write-Host "Investigate logs: Get-Content -LiteralPath '$($logFile.Replace("'", "''"))' -Tail 100 -Wait"
