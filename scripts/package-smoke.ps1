[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ImageDirectory,
    [switch]$ChinesePath,
    [ValidateRange(20, 300)][int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Get-DgFullPath([string]$Path) {
    return [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
}

function Assert-DgSmokeChild([string]$Path, [string]$Parent) {
    $full = Get-DgFullPath $Path
    $base = Get-DgFullPath $Parent
    if (-not $full.StartsWith($base + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Smoke output must stay inside the workspace dist directory.'
    }
    $ancestor = $full
    while ($ancestor) {
        if (Test-Path -LiteralPath $ancestor) {
            if (((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw 'Smoke paths may not traverse junctions or symbolic links.'
            }
        }
        $ancestor = [IO.Path]::GetDirectoryName($ancestor)
    }
    return $full
}

function Test-DgChecksums([string]$ReleaseRoot) {
    $manifest = Join-Path $ReleaseRoot 'SHA256SUMS.txt'
    if (-not (Test-Path -LiteralPath $manifest -PathType Leaf)) { throw 'Release SHA256SUMS.txt is missing.' }
    $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($line in [IO.File]::ReadLines($manifest)) {
        if ($line -notmatch '^([a-f0-9]{64})  (.+)$') { throw 'Malformed checksum entry.' }
        $expected = $Matches[1]
        $relative = $Matches[2]
        if ($relative -match '(^[\\/]|:|(^|[\\/])\.\.([\\/]|$))' -or -not $names.Add($relative.Replace('\', '/'))) {
            throw 'Unsafe or duplicate checksum path.'
        }
        $path = Assert-DgSmokeChild (Join-Path $ReleaseRoot $relative) $ReleaseRoot
        if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or
            (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
            throw "Release checksum mismatch: $relative"
        }
    }
    $tree = @(Get-ChildItem -LiteralPath $ReleaseRoot -Recurse -Force)
    if (@($tree | Where-Object { ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 }).Count -gt 0) {
        throw 'Release contains a junction or symbolic link.'
    }
    $actualFiles = @($tree | Where-Object { -not $_.PSIsContainer })
    if ($actualFiles.Count -ne $names.Count + 1) { throw 'Release has files not covered by the checksum inventory.' }
    if ($names.Count -lt 10) { throw 'Release checksum inventory is incomplete.' }
    return $names.Count
}

if ($env:OS -ne 'Windows_NT') { throw 'Package smoke requires an interactive Windows desktop.' }
$dgRoot = (Resolve-Path -LiteralPath (Split-Path $PSScriptRoot)).Path
$dgDist = Join-Path $dgRoot 'dist'
if (-not [IO.Path]::IsPathRooted($ImageDirectory)) { $ImageDirectory = Join-Path (Get-Location) $ImageDirectory }
$dgImage = Get-DgFullPath $ImageDirectory
$dgRelease = Split-Path $dgImage
if (-not (Test-Path -LiteralPath (Join-Path $dgImage 'DeskGrade.exe') -PathType Leaf)) { throw 'Pass the complete DeskGrade app-image directory.' }
$dgHashCount = Test-DgChecksums $dgRelease
$dgSmokeRoot = Assert-DgSmokeChild (Join-Path $dgDist ('.package-smoke-' + [Guid]::NewGuid().ToString('N'))) $dgDist
if (Test-Path -LiteralPath $dgSmokeRoot) { throw 'Smoke output already exists.' }
[IO.Directory]::CreateDirectory($dgSmokeRoot) | Out-Null
if ($ChinesePath) {
    # Construct Unicode without relying on Windows PowerShell 5.1's BOM-less script encoding.
    $dgChineseName = ([char]0x4e2d).ToString() + [char]0x6587 + ' ' + [char]0x8def + [char]0x5f84
    $dgCopy = Assert-DgSmokeChild (Join-Path $dgSmokeRoot $dgChineseName) $dgDist
    # The source inventory has been checked for traversal/reparse points; destination is new and inside dist.
    Copy-Item -LiteralPath $dgRelease -Destination $dgCopy -Recurse
    $dgRelease = $dgCopy
    $dgImage = Join-Path $dgRelease 'DeskGrade'
    $null = Test-DgChecksums $dgRelease
}
$dgChildRoot = Assert-DgSmokeChild (Join-Path $dgSmokeRoot 'isolated-startup') $dgDist
$dgExecutable = Join-Path $dgImage 'DeskGrade.exe'
$dgProcess = $null
$dgSavedEnvironment = @{}
try {
    # Remove ambient JDKs/injected JVM options only from the child launch environment, then restore.
    foreach ($dgVariable in @('JAVA_HOME', 'JDK_HOME', 'JDK_JAVA_OPTIONS', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'PATH')) {
        $dgSavedEnvironment[$dgVariable] = [Environment]::GetEnvironmentVariable($dgVariable, 'Process')
        [Environment]::SetEnvironmentVariable($dgVariable, $null, 'Process')
    }
    [Environment]::SetEnvironmentVariable('PATH', (Join-Path $env:SystemRoot 'System32') + ';' + $env:SystemRoot, 'Process')
    try {
        $dgProcess = Start-Process -FilePath $dgExecutable -ArgumentList @('--package-smoke', ('"' + $dgChildRoot + '"')) -WorkingDirectory $dgImage -WindowStyle Hidden -PassThru
    } finally {
        foreach ($dgVariable in $dgSavedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($dgVariable, $dgSavedEnvironment[$dgVariable], 'Process') }
    }
    Write-Host "Waiting for isolated bundle startup (up to $TimeoutSeconds seconds)."
    if (-not $dgProcess.WaitForExit($TimeoutSeconds * 1000)) {
        $dgProcess.Kill()
        if (-not $dgProcess.WaitForExit(10000)) { throw 'Timed-out smoke process did not exit after termination.' }
        throw 'Bundle startup smoke timed out; its isolated output was retained.'
    }
    # Refresh .NET Process state before reading a native GUI process exit code.
    $dgProcess.Refresh()
    if ($dgProcess.ExitCode -ne 0) { throw "Bundle startup exited with code $($dgProcess.ExitCode)." }
    $dgReportPath = Join-Path $dgChildRoot 'report.json'
    if (-not (Test-Path -LiteralPath $dgReportPath -PathType Leaf)) { throw 'Bundle startup exited without a smoke report.' }
    if ((Get-Item -LiteralPath $dgReportPath).Length -gt 64KB) { throw 'Unexpectedly large smoke report.' }
    $dgReport = [IO.File]::ReadAllText($dgReportPath) | ConvertFrom-Json
    if ($dgReport.status -ne 'PASS' -or $dgReport.source -ne 'PACKAGED_RUNTIME_SYNTHETIC_LOCAL_ONLY') { throw 'Packaged application reported smoke failure.' }
    $dgRequiredChecks = @('javaFxVisible', 'javaFxWebLocalContent', 'localWorkspaceVisible', 'defaultActionsDisabled', 'noGatewayClient', 'localModeDefault', 'developerModeDisabled', 'mainDatabaseReady',
        'emptyRuns', 'h2Writable', 'nativeLibrary', 'dpapiRoundTrip', 'syntheticCredentialRemoved', 'nioClientCreated',
        'noSessionOrHotkeys', 'resourcesClosed', 'instanceLockReleased')
    foreach ($dgCheck in $dgRequiredChecks) {
        $dgProperty = $dgReport.checks.PSObject.Properties[$dgCheck]
        if ($null -eq $dgProperty -or $dgProperty.Value -isnot [bool] -or $dgProperty.Value -ne $true) { throw "Required bundle smoke check failed or missing: $dgCheck" }
    }
    if ((Get-DgFullPath $dgReport.javaHome) -ne (Get-DgFullPath (Join-Path $dgImage 'runtime'))) {
        throw 'Smoke did not use the app-image bundled Java runtime.'
    }
    if ((Get-DgFullPath $dgReport.dataDirectory) -ne (Get-DgFullPath (Join-Path $dgChildRoot 'data'))) {
        throw 'Smoke did not use the isolated application data directory.'
    }
    $null = Test-DgChecksums $dgRelease
    $dgSummary = [ordered]@{
        status = 'PASS'; checkedUtc = [DateTime]::UtcNow.ToString('o'); checksums = $dgHashCount
        bundledRuntimeConfirmed = $true; noMachineJdkEnvironment = $true; chineseAndSpacePath = [bool]$ChinesePath
        applicationReport = $dgReportPath; image = $dgImage
        limitation = 'This is the current Windows user; it does not establish clean-machine, non-admin, signing, or real-platform compatibility.'
    }
    [IO.File]::WriteAllText((Join-Path $dgSmokeRoot 'smoke-summary.json'), (ConvertTo-Json -InputObject $dgSummary -Depth 4), [Text.UTF8Encoding]::new($false))
    Write-Host "Bundle smoke PASS: $($dgRequiredChecks.Count) application checks; bundled runtime; unchanged image checksums."
    Write-Output (Join-Path $dgSmokeRoot 'smoke-summary.json')
} finally {
    if ($null -ne $dgProcess) {
        if (-not $dgProcess.HasExited) {
            $dgProcess.Kill()
            if (-not $dgProcess.WaitForExit(10000)) { throw 'Smoke child process could not be stopped.' }
        }
        $dgProcess.Dispose()
    }
    # Retain only this synthetic smoke output for review; no user data is read or removed.
}
