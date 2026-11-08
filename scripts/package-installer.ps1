[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ImageDirectory,
    [string]$OutputDirectory,
    [string]$JavaHome,
    [string]$WixHome,
    [ValidateSet('exe', 'msi')][string]$Type = 'exe'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'common.ps1')
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Assert-DgInstallerPath([string]$Path, [string]$Parent) {
    $full = [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
    if ($Parent) {
        $base = [IO.Path]::GetFullPath($Parent).TrimEnd('\', '/')
        if (-not $full.StartsWith($base + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Installer output must be a new child of the workspace dist directory.'
        }
    }
    $ancestor = $full
    while ($ancestor) {
        if (Test-Path -LiteralPath $ancestor) {
            if (((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw 'Installer paths may not traverse junctions or symbolic links.'
            }
        }
        $ancestor = [IO.Path]::GetDirectoryName($ancestor)
    }
    return $full
}

function Assert-DgReleaseManifest([string]$Release) {
    $manifest = Join-Path $Release 'SHA256SUMS.txt'
    if (-not (Test-Path -LiteralPath $manifest -PathType Leaf)) { throw 'The source release has no SHA256SUMS.txt.' }
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($line in [IO.File]::ReadLines($manifest)) {
        if ($line -notmatch '^([a-f0-9]{64})  (.+)$') { throw 'Malformed source release checksum.' }
        $expected = $Matches[1]
        $relative = $Matches[2]
        if ($relative -match '(^[\\/]|:|(^|[\\/])\.\.([\\/]|$))' -or
            -not $seen.Add($relative.Replace('\', '/'))) { throw 'Unsafe or duplicate source checksum path.' }
        $file = Assert-DgInstallerPath (Join-Path $Release $relative) $Release
        if (-not (Test-Path -LiteralPath $file -PathType Leaf) -or
            (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
            throw "Source release checksum mismatch: $relative"
        }
    }
    $tree = @(Get-ChildItem -LiteralPath $Release -Recurse -Force)
    if (@($tree | Where-Object { ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 }).Count -ne 0) {
        throw 'Source release contains a junction or symbolic link.'
    }
    if (@($tree | Where-Object { -not $_.PSIsContainer }).Count -ne $seen.Count + 1) {
        throw 'Source release contains files outside its checksum manifest.'
    }
    if ($seen.Count -lt 10) { throw 'Source release inventory is incomplete.' }
    return (Get-FileHash -LiteralPath $manifest -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-DgPortableWix([string]$WorkDirectory) {
    # Fixed official WiX v3.14.1 portable archive. No installer or machine-wide setup is run.
    $uri = 'https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip'
    $sha256 = '6ac824e1642d6f7277d0ed7ea09411a508f6116ba6fae0aa5f2c7daa2ff43d31'
    $cache = Assert-DgInstallerPath (Join-Path $dgDist '.installer-tools') $dgDist
    [IO.Directory]::CreateDirectory($cache) | Out-Null
    $zip = Join-Path $cache 'wix3141-binaries.zip'
    if (-not (Test-Path -LiteralPath $zip -PathType Leaf)) {
        Write-Host 'Downloading the fixed official portable WiX toolset (about 40 MB).'
        $download = Join-Path $cache ('download-' + [Guid]::NewGuid().ToString('N') + '.zip')
        $savedProtocol = [Net.ServicePointManager]::SecurityProtocol
        try {
            [Net.ServicePointManager]::SecurityProtocol = $savedProtocol -bor [Net.SecurityProtocolType]::Tls12
            Invoke-WebRequest -UseBasicParsing -Uri $uri -OutFile $download
        } finally { [Net.ServicePointManager]::SecurityProtocol = $savedProtocol }
        if ((Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash.ToLowerInvariant() -ne $sha256) {
            throw 'Downloaded WiX archive does not match the pinned SHA-256; it will not be executed.'
        }
        Move-Item -LiteralPath $download -Destination $zip
    }
    if ((Get-Item -LiteralPath $zip).Length -ne 41297555 -or
        (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant() -ne $sha256) {
        throw 'Cached WiX archive failed the pinned integrity check.'
    }
    $destination = Join-Path $WorkDirectory 'wix'
    $archive = [IO.Compression.ZipFile]::OpenRead($zip)
    try {
        $total = [long]0
        if ($archive.Entries.Count -gt 10000) { throw 'Unexpected WiX archive entry count.' }
        foreach ($entry in $archive.Entries) {
            $total += $entry.Length
            if ($entry.FullName -match '(^[\\/]|:|(^|[\\/])\.\.([\\/]|$))' -or
                $entry.Length -gt 64MB -or $total -gt 256MB) { throw 'Unsafe WiX archive entry.' }
        }
    } finally { $archive.Dispose() }
    [IO.Compression.ZipFile]::ExtractToDirectory($zip, $destination)
    return $destination
}

if ($env:OS -ne 'Windows_NT' -or -not [Environment]::Is64BitOperatingSystem) {
    throw 'Installer packaging requires Windows x64.'
}
Initialize-DeskGradeJava -JavaHome $JavaHome
$dgRoot = (Resolve-Path -LiteralPath (Split-Path $PSScriptRoot)).Path
$dgDist = Join-Path $dgRoot 'dist'
$dgImage = Assert-DgInstallerPath $ImageDirectory ''
$dgRelease = Split-Path $dgImage
foreach ($required in @('DeskGrade.exe', 'app/DeskGrade.cfg', 'runtime/bin/server/jvm.dll')) {
    if (-not (Test-Path -LiteralPath (Join-Path $dgImage $required) -PathType Leaf)) {
        throw "Source app-image is incomplete: $required"
    }
}
$dgSourceManifest = Assert-DgReleaseManifest $dgRelease
$dgScan = [IO.File]::ReadAllText((Join-Path $dgRelease 'security-scan.json')) | ConvertFrom-Json
if ($dgScan.status -ne 'PASS' -or -not $dgScan.checks.productionAllowlist -or
    -not $dgScan.checks.credentialPatternsScanned -or -not $dgScan.checks.bundledRuntime) {
    throw 'A verified production app-image is required.'
}
if ($dgScan.edition -cne 'LOCAL' -or -not [string]::IsNullOrEmpty([string]$dgScan.serviceOrigin)) {
    throw 'The local installer requires a local-only app-image without a DeskGrade service origin.'
}
$dgVersion = [string]$dgScan.version
if ($dgVersion -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid source app version.' }
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $dgDist ("DeskGrade-Installer-$dgVersion-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 6))
} elseif (-not [IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory = Join-Path $dgRoot $OutputDirectory
}
$dgOutput = Assert-DgInstallerPath $OutputDirectory $dgDist
if (Test-Path -LiteralPath $dgOutput) { throw 'Installer output already exists; no release is overwritten.' }
[IO.Directory]::CreateDirectory($dgOutput) | Out-Null
$dgWork = Assert-DgInstallerPath (Join-Path $dgDist ('.installer-work-' + [Guid]::NewGuid().ToString('N'))) $dgDist
[IO.Directory]::CreateDirectory($dgWork) | Out-Null
$dgWix = if ($WixHome) { Assert-DgInstallerPath $WixHome '' } else { Get-DgPortableWix $dgWork }
foreach ($tool in @('candle.exe', 'light.exe', 'WixUIExtension.dll', 'WixUtilExtension.dll')) {
    if (-not (Test-Path -LiteralPath (Join-Path $dgWix $tool) -PathType Leaf)) { throw "Portable WiX is incomplete: $tool" }
}
$dgJpackage = Join-Path $env:JAVA_HOME 'bin/jpackage.exe'
$dgLog = Join-Path $dgWork 'jpackage-installer.log'
$dgSavedPath = $env:PATH
try {
    $env:PATH = $dgWix + ';' + $env:PATH
    $arguments = @('--type', $Type, '--name', 'DeskGrade', '--app-version', $dgVersion,
        '--app-image', $dgImage, '--dest', $dgOutput, '--vendor', 'DeskGrade',
        '--description', 'DeskGrade grading assistant', '--win-per-user-install',
        '--install-dir', 'DeskGrade Client',
        '--win-menu', '--win-menu-group', 'DeskGrade', '--win-shortcut', '--win-dir-chooser',
        '--win-upgrade-uuid', 'fa60d91b-5b2d-47dc-aef7-981cc463c56b',
        '--temp', (Join-Path $dgWork 'jpackage-temp'))
    $dgOldPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $dgJpackage @arguments *> $dgLog
        $dgExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $dgOldPreference }
    if ($dgExit -ne 0) { throw "Installer packaging failed (exit $dgExit). See $dgLog" }
} finally { $env:PATH = $dgSavedPath }
$dgArtifacts = @(Get-ChildItem -LiteralPath $dgOutput -File -Filter "*.$Type")
if ($dgArtifacts.Count -ne 1 -or $dgArtifacts[0].Length -lt 1MB) { throw 'Expected exactly one complete Windows installer.' }
$dgArtifact = $dgArtifacts[0]
if ((Assert-DgReleaseManifest $dgRelease) -ne $dgSourceManifest) { throw 'Source image changed during installer packaging.' }
$dgHash = (Get-FileHash -LiteralPath $dgArtifact.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
$dgReport = [ordered]@{
    status = 'PASS'; createdUtc = [DateTime]::UtcNow.ToString('o'); version = $dgVersion
    installer = $dgArtifact.Name; sha256 = $dgHash; bytes = $dgArtifact.Length
    sourceManifestSha256 = $dgSourceManifest; edition = 'LOCAL'; serviceOrigin = $null
    perUser = $true; installDirectoryName = 'DeskGrade Client'; userDataDirectory = 'LOCALAPPDATA/DeskGrade'
    bundledRuntime = $true; containsServerRuntime = $false
    signed = $false; installerExecuted = $false
    toolchain = 'JDK 21 jpackage + portable WiX v3; no system-wide build-tool installation'
    limitations = @('Unsigned: Windows SmartScreen may warn.', 'Installer creation does not establish clean-machine installation or real-site grading compatibility.')
}
[IO.File]::WriteAllText((Join-Path $dgOutput 'installer-report.json'), (ConvertTo-Json $dgReport -Depth 6), [Text.UTF8Encoding]::new($false))
$dgHashes = @(Get-ChildItem -LiteralPath $dgOutput -File | Sort-Object Name | ForEach-Object {
    (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $_.Name
})
[IO.File]::WriteAllText((Join-Path $dgOutput 'SHA256SUMS.txt'), (($dgHashes -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
Write-Host "Per-user installer created: $($dgArtifact.FullName)"
Write-Output $dgArtifact.FullName
