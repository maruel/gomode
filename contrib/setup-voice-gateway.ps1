# Install the Windows voice gateway, preserve configuration, and restart its scheduled task during upgrades.
#Requires -Version 5.1
[CmdletBinding()]
param(
    [string]$InstallDir = (Join-Path $env:LOCALAPPDATA 'voice-gateway'),
    [string]$ConfigPath = $(
        $configHome = $env:XDG_CONFIG_HOME
        if (-not $configHome) { $configHome = Join-Path $env:USERPROFILE '.config' }
        Join-Path $configHome 'voice-gateway/config.toml'
    ),
    [string]$TaskName = 'GoModeVoiceGateway'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$InstallDir = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($InstallDir)
$ConfigPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($ConfigPath)
$exe = Join-Path $InstallDir 'voice-gateway.exe'
$task = Get-ScheduledTask | Where-Object { $_.TaskPath -eq '\' -and $_.TaskName -eq $TaskName }
if ($null -ne $task -and (@($task.Actions).Count -ne 1 -or $task.Actions[0].Execute -ne $exe)) {
    throw "Task '$TaskName' does not run '$exe'. Use the original -InstallDir or a different -TaskName."
}
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    & go install ./cmd/voice-gateway
    if ($LASTEXITCODE -ne 0) { throw "go install failed with exit code $LASTEXITCODE" }
    $goEnv = & go env -json GOBIN GOPATH | ConvertFrom-Json
} finally {
    Pop-Location
}
$goBin = $goEnv.GOBIN
if (-not $goBin) { $goBin = Join-Path ($goEnv.GOPATH -split [IO.Path]::PathSeparator)[0] 'bin' }
$source = Join-Path $goBin 'voice-gateway.exe'

New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
if (-not (Test-Path -LiteralPath $ConfigPath)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $ConfigPath -Parent) | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'config.toml') -Destination $ConfigPath
}

try {
    if ($null -ne $task) {
        # Prevent the one-minute trigger from starting the gateway during the copy.
        Disable-ScheduledTask -TaskName $TaskName -TaskPath '\' | Out-Null
        Stop-ScheduledTask -TaskName $TaskName -TaskPath '\'
    }
    if ($source -ne $exe) { Copy-Item -LiteralPath $source -Destination $exe -Force }

    $user = [Environment]::UserDomainName + '\' + [Environment]::UserName
    $action = New-ScheduledTaskAction -Execute $exe -Argument ('-config "' + $ConfigPath + '"') -WorkingDirectory $InstallDir
    # Interactive tasks run only while signed in and use the persistent user environment,
    # not shell-only API keys or PATH changes from this setup session.
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
Write-Host "Edit configuration: $ConfigPath"
Write-Host "Requested start of scheduled task: $TaskName"
Write-Host 'Investigate logs (Task Scheduler): Get-WinEvent -LogName Microsoft-Windows-TaskScheduler/Operational -MaxEvents 50 | Format-List TimeCreated, Message'
