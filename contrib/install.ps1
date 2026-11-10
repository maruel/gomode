# Install the latest checksummed Windows voice gateway release and start its per-user task.
#Requires -Version 5.1
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($args.Count -ne 0) { throw 'This installer takes no arguments.' }

$InstallDir = Join-Path $env:LOCALAPPDATA 'voice-gateway'
$configHome = [Environment]::GetEnvironmentVariable('XDG_CONFIG_HOME', 'User')
if (-not $configHome) { $configHome = [Environment]::GetEnvironmentVariable('XDG_CONFIG_HOME', 'Machine') }
if (-not $configHome) { $configHome = Join-Path $env:USERPROFILE '.config' }
$ConfigPath = Join-Path $configHome 'voice-gateway/config.toml'
$EnvFile = Join-Path $InstallDir 'voice-gateway.env'
$exe = Join-Path $InstallDir 'voice-gateway.exe'
$logFile = Join-Path $InstallDir 'voice-gateway.log'
$TaskName = 'GoModeVoiceGateway'
foreach ($path in @($ConfigPath, $EnvFile, $exe, $logFile)) {
    if (-not [IO.Path]::IsPathRooted($path) -or $path -match '[\x00-\x1f\x7f"]') { throw 'User paths must be absolute and contain no control characters or double quotes.' }
    try { $item = Get-Item -LiteralPath $path -Force }
    catch [System.Management.Automation.ItemNotFoundException] { continue }
    if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $item.LinkType -eq 'HardLink') {
        throw "Expected an independent regular file: $path"
    }
}
$task = Get-ScheduledTask | Where-Object { $_.TaskPath -eq '\' -and $_.TaskName -eq $TaskName }
if ($null -ne $task -and (@($task.Actions).Count -ne 1 -or $task.Actions[0].Execute -ne $exe)) {
    throw "Scheduled task '$TaskName' runs a different executable; correct it before installing."
}
$architecture = $env:PROCESSOR_ARCHITEW6432
if (-not $architecture) { $architecture = $env:PROCESSOR_ARCHITECTURE }
if (-not $architecture) { $architecture = [Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString() }
switch ($architecture) {
    { $_ -in @('AMD64', 'X64') } { $arch = 'amd64' }
    'ARM64' { $arch = 'arm64' }
    default { throw 'Supported architectures are amd64 and arm64.' }
}
$asset = "voice-gateway_windows_$arch.zip"
$workDir = Join-Path ([IO.Path]::GetTempPath()) ('voice-gateway-install-' + [Guid]::NewGuid().ToString('N'))
$null = [IO.Directory]::CreateDirectory($workDir)
$binaryTemp = $null
$publishing = $false
try {
    $sourceArchive = Join-Path $workDir $asset
    $sourceChecksums = Join-Path $workDir 'checksums.txt'
    $release = Invoke-RestMethod -Uri 'https://api.github.com/repos/maruel/gomode/releases/latest'
    $version = $release.tag_name
    if ($version -notmatch '^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$') { throw 'The latest stable release could not be resolved.' }
    $base = "https://github.com/maruel/gomode/releases/download/$version"
    Invoke-WebRequest -UseBasicParsing -Uri "$base/$asset" -OutFile $sourceArchive
    Invoke-WebRequest -UseBasicParsing -Uri "$base/checksums.txt" -OutFile $sourceChecksums
    $hashes = @()
    $checksumPattern = '^([0-9a-fA-F]{64})  ' + [regex]::Escape($asset) + '$'
    foreach ($line in [IO.File]::ReadLines($sourceChecksums)) {
        if ($line -cmatch $checksumPattern) { $hashes += $Matches[1] }
    }
    if ($hashes.Count -ne 1) { throw "Expected exactly one SHA-256 entry for $asset" }
    if ((Get-FileHash -LiteralPath $sourceArchive -Algorithm SHA256).Hash -ne $hashes[0]) { throw 'Archive SHA-256 checksum mismatch.' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($sourceArchive)
    $filesDir = Join-Path $workDir 'files'
    $null = [IO.Directory]::CreateDirectory($filesDir)
    try {
        $expected = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($name in @('LICENSE', 'config.toml', 'voice-gateway.env.example', 'voice-gateway.exe')) { $null = $expected.Add($name) }
        foreach ($entry in $zip.Entries) {
            if (-not $expected.Remove($entry.FullName)) { throw "Unexpected or duplicate archive member: $($entry.FullName)" }
            $fileType = ($entry.ExternalAttributes -shr 16) -band 0xf000
            if ($fileType -ne 0 -and $fileType -ne 0x8000) { throw 'Archive members must be regular files.' }
        }
        if ($expected.Count -ne 0) { throw 'Archive must contain exactly the expected release files at its root.' }
        foreach ($entry in $zip.Entries) {
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $filesDir $entry.FullName))
        }
    } finally {
        $zip.Dispose()
    }
    $source = Join-Path $filesDir 'voice-gateway.exe'
    if ((Get-Item -LiteralPath $source).Length -eq 0) { throw 'Archive binary must be nonempty.' }

    $publishing = $true
    foreach ($directory in @($InstallDir, (Split-Path $ConfigPath -Parent), (Split-Path $EnvFile -Parent))) {
        $null = [IO.Directory]::CreateDirectory($directory)
    }
    if (-not (Test-Path -LiteralPath $EnvFile)) { [IO.File]::Copy((Join-Path $filesDir 'voice-gateway.env.example'), $EnvFile) }
    if (-not (Test-Path -LiteralPath $ConfigPath)) { [IO.File]::Copy((Join-Path $filesDir 'config.toml'), $ConfigPath) }
    $binaryTemp = Join-Path $InstallDir ('.voice-gateway-' + [Guid]::NewGuid().ToString('N') + '.exe')
    [IO.File]::Copy($source, $binaryTemp)
    try {
        if ($null -ne $task) {
            # Prevent queued launches and crash recovery during replacement.
            Disable-ScheduledTask -TaskName $TaskName -TaskPath '\' | Out-Null
            $gateway = Get-Process -Name 'voice-gateway' -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $exe } | Select-Object -First 1
            # Retain the process handle so PID reuse cannot change which process we wait on.
            if ($null -ne $gateway) { $null = $gateway.Handle }
            if ($null -ne $gateway) {
                $stop = $null
                if ([Threading.EventWaitHandle]::TryOpenExisting("Local\GoModeVoiceGateway-$($gateway.Id)", [ref]$stop)) {
                    try { $null = $stop.Set() } finally { $stop.Dispose() }
                }
                if (-not $gateway.WaitForExit(10000)) {
                    Stop-ScheduledTask -TaskName $TaskName -TaskPath '\'
                    if (-not $gateway.HasExited) { Stop-Process -InputObject $gateway -Force }
                }
                if (-not $gateway.WaitForExit(30000)) { throw "Timed out stopping '$exe' (PID $($gateway.Id))." }
            }
        }
        if (Test-Path -LiteralPath $exe) {
            [IO.File]::Replace($binaryTemp, $exe, [NullString]::Value)
        } else {
            [IO.File]::Move($binaryTemp, $exe)
        }
        $binaryTemp = $null
        $user = [Environment]::UserDomainName + '\' + [Environment]::UserName
        $arguments = '-task-name "' + $TaskName + '" -config "' + $ConfigPath + '" -log-file "' + $logFile + '" -env-file "' + $EnvFile + '"'
        $action = New-ScheduledTaskAction -Execute $exe -Argument $arguments -WorkingDirectory $InstallDir
        # Interactive tasks use the persistent user environment and need no elevation.
        $principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
        $triggers = @(
            New-ScheduledTaskTrigger -AtLogOn -User $user
        )
        $settings = New-ScheduledTaskSettingsSet -MultipleInstances Queue -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero) -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
        $task = Register-ScheduledTask -TaskName $TaskName -TaskPath '\' -Action $action -Principal $principal -Trigger $triggers -Settings $settings -Description 'Go Mode voice gateway' -Force
    } finally {
        # Restart an existing task even when replacement or registration fails.
        if ($null -ne $task) {
            Enable-ScheduledTask -TaskName $TaskName -TaskPath '\' | Out-Null
            Start-ScheduledTask -TaskName $TaskName -TaskPath '\'
        }
    }
    Write-Host "Installed: $exe"
    Write-Host "Edit configuration: $ConfigPath"
    Write-Host "Edit API keys: $EnvFile"
    Write-Host "Requested start of scheduled task: $TaskName"
    Write-Host "Investigate logs: Get-Content -LiteralPath '$($logFile.Replace("'", "''"))' -Tail 100 -Wait"
} catch {
    if ($publishing) { Write-Warning 'Installation failed after publication began; copied files remain installed. Correct the error and rerun.' }
    throw
} finally {
    if ($binaryTemp -and (Test-Path -LiteralPath $binaryTemp)) { Remove-Item -LiteralPath $binaryTemp -Force }
    Remove-Item -LiteralPath $workDir -Recurse -Force
}
