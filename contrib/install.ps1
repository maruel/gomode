# Install checksummed Windows voice gateway archives, preserve settings, and restart the per-user scheduled task.
#Requires -Version 5.1
[CmdletBinding()]
param(
    [ValidateNotNullOrEmpty()]
    [string]$Version,
    [ValidateNotNullOrEmpty()]
    [string]$Archive,
    [ValidateNotNullOrEmpty()]
    [string]$Checksums,
    [ValidateNotNullOrEmpty()]
    [string]$InstallDir = (Join-Path $env:LOCALAPPDATA 'voice-gateway'),
    [ValidateNotNullOrEmpty()]
    [string]$ConfigPath,
    [ValidateNotNullOrEmpty()]
    [string]$EnvFile,
    [ValidateNotNullOrEmpty()]
    [ValidatePattern('^[^\\/:*?"<>|\x00-\x1f\x7f]+$')]
    [string]$TaskName = 'GoModeVoiceGateway',
    [switch]$NoService
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Get-InstallPath([string]$Path) {
    if ($Path.StartsWith('\\?\') -or $Path.StartsWith('\\.\')) { throw 'Device namespace paths are not supported.' }
    $full = [IO.Path]::GetFullPath($ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path))
    if ($full -match '[\x00-\x1f\x7f"]') { throw 'Paths cannot contain control characters or double quotes.' }
    foreach ($component in ($full -split '[\\/]')) {
        if ($component -match '[ .]$' -or $component -match '~[0-9]+($|\.)' -or ($component.Contains(':') -and $component -notmatch '^[A-Za-z]:$')) {
            throw 'Use ordinary paths without trailing dots/spaces, short-name aliases, or alternate data streams.'
        }
    }
    # PowerShell 5.1 cannot resolve every junction/short-name alias uniformly.
    # Reject reparse points instead of risking replacement through an alias.
    $current = $full
    $root = [IO.Path]::GetPathRoot($full).TrimEnd([char[]]'\/')
    while ($current) {
        $item = Get-Item -LiteralPath $current -Force -ErrorAction SilentlyContinue
        if ($null -ne $item -and ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Use physical paths without symbolic links or junctions.'
        }
        # Reject shared file identities uniformly, including the derived log path.
        # LinkType is provided by the FileSystem provider in PowerShell 5.1+.
        if ($null -ne $item -and -not $item.PSIsContainer -and $item.LinkType -eq 'HardLink') {
            throw 'Use independent files without hard links for installation destinations.'
        }
        $parent = Split-Path $current -Parent
        if ($null -ne $item -and $parent -and $current.TrimEnd([char[]]'\/') -ne $root) {
            $name = Split-Path $current -Leaf
            $physical = Get-ChildItem -LiteralPath $parent -Filter $name -Force | Where-Object { $_.Name -eq $name } | Select-Object -First 1
            if ($null -eq $physical) { throw 'Use physical directory-entry names instead of filesystem aliases.' }
        }
        if ($parent -eq $current) { break }
        $current = $parent
    }
    return $full
}

$releaseTagPattern = '^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$'
if ($Archive -and $Version) { throw '-Archive and -Version are mutually exclusive.' }
if ($Checksums -and -not $Archive) { throw '-Checksums requires -Archive.' }
if ($Version -and $Version -notmatch $releaseTagPattern) {
    throw 'Version must be a full release tag, such as v0.4.0.'
}
$InstallDir = Get-InstallPath $InstallDir
if (-not $ConfigPath) {
    $configHome = [Environment]::GetEnvironmentVariable('XDG_CONFIG_HOME', 'User')
    if (-not $configHome) { $configHome = [Environment]::GetEnvironmentVariable('XDG_CONFIG_HOME', 'Machine') }
    if (-not $configHome) { $configHome = Join-Path $env:USERPROFILE '.config' }
    $ConfigPath = Join-Path $configHome 'voice-gateway/config.toml'
}
$ConfigPath = Get-InstallPath $ConfigPath
if (-not $EnvFile) { $EnvFile = Join-Path $InstallDir 'voice-gateway.env' }
$EnvFile = Get-InstallPath $EnvFile
$exe = Get-InstallPath (Join-Path $InstallDir 'voice-gateway.exe')
$logFile = Get-InstallPath (Join-Path $InstallDir 'voice-gateway.log')
foreach ($path in @($exe, $ConfigPath, $EnvFile, $logFile)) {
    if ((Test-Path -LiteralPath $path) -and -not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Expected a file: $path" }
}
if ($exe -eq $ConfigPath -or $exe -eq $EnvFile -or $ConfigPath -eq $EnvFile -or $ConfigPath -eq $logFile -or $EnvFile -eq $logFile) {
    throw 'Binary, config, credentials, and log paths must differ.'
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
if ($Archive) {
    $Archive = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Archive)
    if (-not (Test-Path -LiteralPath $Archive -PathType Leaf)) { throw "Archive not found: $Archive" }
    if ([IO.Path]::GetFileName($Archive) -cne $asset) { throw "Expected archive basename: $asset" }
    if (-not $Checksums) { $Checksums = Join-Path (Split-Path $Archive -Parent) 'checksums.txt' }
    $Checksums = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Checksums)
    if (-not (Test-Path -LiteralPath $Checksums -PathType Leaf)) { throw "Checksums not found: $Checksums" }
}
$task = $null
if (-not $NoService) {
    # This guard also preflights the native Task Scheduler before changing files.
    $task = Get-ScheduledTask | Where-Object { $_.TaskPath -eq '\' -and $_.TaskName -eq $TaskName }
    if ($null -ne $task -and (@($task.Actions).Count -ne 1 -or $task.Actions[0].Execute -ne $exe)) {
        throw "Task '$TaskName' does not run '$exe'. Use the original -InstallDir or a different -TaskName."
    }
}
$workDir = Join-Path ([IO.Path]::GetTempPath()) ('voice-gateway-install-' + [Guid]::NewGuid().ToString('N'))
$null = [IO.Directory]::CreateDirectory($workDir)
$binaryTemp = $null
$publishing = $false
try {
    $sourceArchive = Join-Path $workDir $asset
    $sourceChecksums = Join-Path $workDir 'checksums.txt'
    if ($Archive) {
        Copy-Item -LiteralPath $Archive -Destination $sourceArchive
        Copy-Item -LiteralPath $Checksums -Destination $sourceChecksums
    } else {
        if (-not $Version) {
            $release = Invoke-RestMethod -Uri 'https://api.github.com/repos/maruel/gomode/releases/latest'
            $Version = $release.tag_name
            if ($Version -notmatch $releaseTagPattern) {
                throw 'No stable binary release resolved; use -Archive for a local snapshot.'
            }
        }
        $base = "https://github.com/maruel/gomode/releases/download/$Version"
        Invoke-WebRequest -UseBasicParsing -Uri "$base/$asset" -OutFile $sourceArchive
        Invoke-WebRequest -UseBasicParsing -Uri "$base/checksums.txt" -OutFile $sourceChecksums
    }
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
            # Prevent the one-minute trigger from starting the gateway during replacement.
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
        if (-not $NoService) {
            $user = [Environment]::UserDomainName + '\' + [Environment]::UserName
            $arguments = '-config "' + $ConfigPath + '" -log-file "' + $logFile + '" -env-file "' + $EnvFile + '"'
            $action = New-ScheduledTaskAction -Execute $exe -Argument $arguments -WorkingDirectory $InstallDir
            # Interactive tasks use the persistent user environment and need no elevation.
            $principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
            $triggers = @(
                New-ScheduledTaskTrigger -AtLogOn -User $user
                New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 1)
            )
            $settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero) -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
            $task = Register-ScheduledTask -TaskName $TaskName -TaskPath '\' -Action $action -Principal $principal -Trigger $triggers -Settings $settings -Description 'Go Mode voice gateway' -Force
        }
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
    if (-not $NoService) { Write-Host "Requested start of scheduled task: $TaskName" }
    Write-Host "Investigate logs: Get-Content -LiteralPath '$($logFile.Replace("'", "''"))' -Tail 100 -Wait"
} catch {
    if ($publishing) { Write-Warning 'Installation failed after publication began; copied files remain installed. Correct the error and rerun.' }
    throw
} finally {
    if ($binaryTemp -and (Test-Path -LiteralPath $binaryTemp)) { Remove-Item -LiteralPath $binaryTemp -Force }
    Remove-Item -LiteralPath $workDir -Recurse -Force
}
