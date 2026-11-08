[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ReleaseDirectory,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedManifestSha256,
    [string]$ConfigurationFile,
    [string]$ShortcutDirectory = [Environment]::GetFolderPath('Desktop'),
    [switch]$ValidateOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Keep Windows PowerShell 5.1 from auto-loading a PowerShell 7 Security module
# inherited through PSModulePath when the ACL validation functions use Get-Acl.
if ($PSVersionTable.PSEdition -eq 'Desktop') {
    $dgInboxModulePath = Join-Path $PSHOME 'Modules'
    if (-not [IO.Directory]::Exists($dgInboxModulePath)) {
        throw 'The Windows PowerShell inbox module directory is missing.'
    }
    $env:PSModulePath = $dgInboxModulePath
}

. (Join-Path $PSScriptRoot 'local-launch-validation.ps1')

function Test-DgSamePath([string]$Left, [string]$Right) {
    return $Left.Equals($Right, [StringComparison]::OrdinalIgnoreCase)
}

function Write-DgAtomicPrivateConfig([string]$Path, [byte[]]$Bytes) {
    $directory = Split-Path -Path $Path
    $leaf = Split-Path -Path $Path -Leaf
    $nonce = [Guid]::NewGuid().ToString('N')
    $temporary = Join-Path $directory ('.' + $leaf + '.' + $nonce + '.tmp')
    $backup = Join-Path $directory ('.' + $leaf + '.' + $nonce + '.previous')
    try {
        [IO.File]::WriteAllBytes($temporary, $Bytes)
        Protect-DgPrivateFile $temporary | Out-Null
        [IO.File]::Replace($temporary, $Path, $backup, $true)
        # The commit has already completed atomically. A stale recovery copy is
        # safer than reporting a false activation failure only because cleanup
        # was temporarily blocked by antivirus/indexing software.
        if ([IO.File]::Exists($backup)) {
            try { [IO.File]::Delete($backup) } catch { }
        }
    } finally {
        if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
    }
}

function Test-DgShortcutMatches($Shortcut, $Definition) {
    return (Test-DgSamePath ([string]$Shortcut.TargetPath) ([string]$Definition.TargetPath)) -and
        ([string]$Shortcut.Arguments).Equals([string]$Definition.Arguments, [StringComparison]::Ordinal) -and
        (Test-DgSamePath ([string]$Shortcut.WorkingDirectory) ([string]$Definition.WorkingDirectory)) -and
        ([int]$Shortcut.WindowStyle -eq [int]$Definition.WindowStyle) -and
        ([string]$Shortcut.IconLocation).Equals([string]$Definition.IconLocation, [StringComparison]::OrdinalIgnoreCase) -and
        ([string]$Shortcut.Description).Equals([string]$Definition.Description, [StringComparison]::Ordinal)
}

function New-DgShortcutFile($Shell, [string]$Path, $Definition) {
    $shortcut = $Shell.CreateShortcut($Path)
    $shortcut.TargetPath = $Definition.TargetPath
    $shortcut.Arguments = $Definition.Arguments
    $shortcut.WorkingDirectory = $Definition.WorkingDirectory
    $shortcut.WindowStyle = $Definition.WindowStyle
    $shortcut.IconLocation = $Definition.IconLocation
    $shortcut.Description = $Definition.Description
    $shortcut.Save()
}

function Install-DgShortcutAtomically([string]$Temporary, [string]$Destination) {
    if ([IO.File]::Exists($Destination)) {
        $backup = $Destination + '.' + [Guid]::NewGuid().ToString('N') + '.previous'
        try {
            [IO.File]::Replace($Temporary, $Destination, $backup, $true)
            if ([IO.File]::Exists($backup)) {
                try { [IO.File]::Delete($backup) } catch { }
            }
        } finally {
            if ([IO.File]::Exists($Temporary)) { [IO.File]::Delete($Temporary) }
        }
    } else {
        [IO.File]::Move($Temporary, $Destination)
    }
}

function Publish-DgShortcutShellRefresh([string[]]$ShortcutPaths, [string]$Directory) {
    if ($null -eq ('DeskGrade.ShortcutShellChangeNotifier' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

namespace DeskGrade
{
    public static class ShortcutShellChangeNotifier
    {
        private const uint ShcneUpdatedItem = 0x00002000;
        private const uint ShcneUpdatedDirectory = 0x00001000;
        private const uint ShcnfPathW = 0x0005;
        private const uint ShcnfFlush = 0x1000;

        [DllImport("shell32.dll", EntryPoint = "SHChangeNotify")]
        private static extern void SHChangeNotify(
            uint eventId,
            uint flags,
            IntPtr item1,
            IntPtr item2);

        public static void NotifyPath(uint eventId, string path)
        {
            if (String.IsNullOrWhiteSpace(path))
            {
                throw new ArgumentException("A non-empty Shell path is required.", "path");
            }

            IntPtr pointer = Marshal.StringToCoTaskMemUni(path);
            try
            {
                SHChangeNotify(eventId, ShcnfPathW | ShcnfFlush, pointer, IntPtr.Zero);
            }
            finally
            {
                Marshal.FreeCoTaskMem(pointer);
            }
        }

        public static void NotifyItem(string path)
        {
            NotifyPath(ShcneUpdatedItem, path);
        }

        public static void NotifyDirectory(string path)
        {
            NotifyPath(ShcneUpdatedDirectory, path);
        }
    }
}
'@
    }

    foreach ($dgShortcutPath in $ShortcutPaths) {
        [DeskGrade.ShortcutShellChangeNotifier]::NotifyItem($dgShortcutPath)
    }
    [DeskGrade.ShortcutShellChangeNotifier]::NotifyDirectory($Directory)
}

if (-not $ConfigurationFile) {
    if ([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        throw 'LOCALAPPDATA is unavailable; specify -ConfigurationFile explicitly.'
    }
    $ConfigurationFile = Join-Path $env:LOCALAPPDATA 'DeskGrade/server-launcher/config.json'
}

$dgConfigurationFile = Assert-DgNoReparsePath -Value $ConfigurationFile -BaseDirectory $null
if (-not [IO.File]::Exists($dgConfigurationFile)) { throw 'The installed DeskGrade launcher configuration does not exist.' }
$dgLauncherHome = Assert-DgPrivateTrustRoot (Split-Path -Path $dgConfigurationFile)
Assert-DgNoReparseTree $dgLauncherHome | Out-Null
if (Test-DgBroadWriteAccess $dgConfigurationFile) { throw 'The DeskGrade launcher configuration ACL is unsafe.' }

$dgLauncherScript = Assert-DgNoReparsePath -Value (Join-Path $dgLauncherHome 'launcher.ps1') -BaseDirectory $null
$dgRelease = Assert-DgNoReparseTree $ReleaseDirectory
$dgManifest = Join-Path $dgRelease 'SHA256SUMS.txt'
$dgManifestDigest = Get-DgSha256 $dgManifest
$dgExpectedManifestDigest = $ExpectedManifestSha256.ToLowerInvariant()
if (-not $dgManifestDigest.Equals($dgExpectedManifestDigest, [StringComparison]::Ordinal)) {
    throw 'The release manifest does not match the independently supplied SHA-256 digest.'
}
Assert-DgReleaseIntegrity $dgRelease $dgExpectedManifestDigest

$dgConfiguration = Read-DgLauncherConfiguration $dgConfigurationFile
$dgConfiguration.releaseDirectory = $dgRelease
$dgConfiguration.integrity.releaseManifestSha256 = $dgExpectedManifestDigest

# This validates every preserved launcher/runtime/configuration digest and the
# complete proposed release before any persistent state is changed. It also
# allows recovery when the previously configured release was moved or removed.
Assert-DgInstalledIntegrity $dgConfiguration $dgConfigurationFile $dgLauncherScript

$dgShortcutDirectory = Assert-DgNoReparsePath -Value $ShortcutDirectory -BaseDirectory $null
if (-not [IO.Directory]::Exists($dgShortcutDirectory)) { throw 'The DeskGrade shortcut directory does not exist.' }
$dgPowerShell = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
if (-not [IO.File]::Exists($dgPowerShell)) { throw 'Windows PowerShell 5.1 was not found.' }
$dgIcon = (Join-Path $dgRelease 'DeskGrade/DeskGrade.exe') + ',0'
$dgDescription = 'DeskGrade stable launcher; admin http://127.0.0.1:' + $dgConfiguration.port + '/admin'
$dgDefinitions = @(
    [pscustomobject]@{ Name = 'DeskGrade 一键启动'; Mode = 'App'; TargetPath = $dgPowerShell;
        Arguments = '-NoLogo -NoProfile -ExecutionPolicy RemoteSigned -WindowStyle Hidden -File "' + $dgLauncherScript + '" -Mode App -ShortcutUi';
        WorkingDirectory = $dgLauncherHome; WindowStyle = 7; IconLocation = $dgIcon; Description = $dgDescription },
    [pscustomobject]@{ Name = 'DeskGrade 管理后台'; Mode = 'Admin'; TargetPath = $dgPowerShell;
        Arguments = '-NoLogo -NoProfile -ExecutionPolicy RemoteSigned -File "' + $dgLauncherScript + '" -Mode Admin';
        WorkingDirectory = $dgLauncherHome; WindowStyle = 1; IconLocation = $dgIcon; Description = $dgDescription },
    [pscustomobject]@{ Name = 'DeskGrade 停止后台'; Mode = 'Stop'; TargetPath = $dgPowerShell;
        Arguments = '-NoLogo -NoProfile -ExecutionPolicy RemoteSigned -File "' + $dgLauncherScript + '" -Mode Stop';
        WorkingDirectory = $dgLauncherHome; WindowStyle = 1; IconLocation = $dgIcon; Description = $dgDescription }
)

$dgShell = New-Object -ComObject WScript.Shell
$dgShortcutPlans = @()
foreach ($dgDefinition in $dgDefinitions) {
    $dgShortcutPath = Join-Path $dgShortcutDirectory ($dgDefinition.Name + '.lnk')
    Assert-DgNoReparsePath -Value $dgShortcutPath -BaseDirectory $null | Out-Null
    $dgMatches = $false
    if ([IO.File]::Exists($dgShortcutPath)) {
        $dgExisting = $dgShell.CreateShortcut($dgShortcutPath)
        $dgExpectedFileArgument = '-File "' + $dgLauncherScript + '"'
        if (-not (Test-DgSamePath ([string]$dgExisting.TargetPath) $dgPowerShell) -or
            ([string]$dgExisting.Arguments).IndexOf($dgExpectedFileArgument, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
            throw 'A same-named unrelated shortcut exists; it was not overwritten.'
        }
        $dgMatches = Test-DgShortcutMatches $dgExisting $dgDefinition
    }
    $dgShortcutPlans += [pscustomobject]@{ Definition = $dgDefinition; Path = $dgShortcutPath; Matches = $dgMatches; Temporary = $null }
}

$dgCurrentConfiguration = Read-DgLauncherConfiguration $dgConfigurationFile
$dgConfigurationChanged = -not ((Test-DgSamePath ([string]$dgCurrentConfiguration.releaseDirectory) $dgRelease) -and
    ([string]$dgCurrentConfiguration.integrity.releaseManifestSha256).Equals($dgExpectedManifestDigest, [StringComparison]::OrdinalIgnoreCase))
$dgShortcutsChanged = @($dgShortcutPlans | Where-Object { -not $_.Matches }).Count
$dgShortcutRefreshStatus = $(if ($ValidateOnly) { 'SKIPPED_VALIDATE_ONLY' } else { 'PENDING' })
$dgShortcutRefreshWarning = $null

if (-not $ValidateOnly) {
    # Build every replacement first, so permission/COM failures happen before
    # the atomic configuration commit.
    try {
        foreach ($dgPlan in $dgShortcutPlans) {
            if ($dgPlan.Matches) { continue }
            $dgPlan.Temporary = Join-Path $dgShortcutDirectory ('.deskgrade-' + [Guid]::NewGuid().ToString('N') + '.lnk')
            New-DgShortcutFile $dgShell $dgPlan.Temporary $dgPlan.Definition
        }
        if ($dgConfigurationChanged -or $dgShortcutsChanged -gt 0) {
            # Re-read and fully verify the release at the last possible point
            # before committing its path/digest pair or refreshed icons.
            if (-not (Get-DgSha256 $dgManifest).Equals($dgExpectedManifestDigest, [StringComparison]::Ordinal)) {
                throw 'The release manifest changed before activation could be committed.'
            }
            Assert-DgReleaseIntegrity $dgRelease $dgExpectedManifestDigest
            Assert-DgInstalledIntegrity $dgConfiguration $dgConfigurationFile $dgLauncherScript
        }
        if ($dgConfigurationChanged) {
            $dgOriginalConfigurationBytes = [IO.File]::ReadAllBytes($dgConfigurationFile)
            $dgJson = ConvertTo-Json -InputObject $dgConfiguration -Depth 8
            $dgNewConfigurationBytes = [Text.UTF8Encoding]::new($false).GetBytes($dgJson)
            Write-DgAtomicPrivateConfig $dgConfigurationFile $dgNewConfigurationBytes
            try {
                $dgCommittedConfiguration = Read-DgLauncherConfiguration $dgConfigurationFile
                Assert-DgInstalledIntegrity $dgCommittedConfiguration $dgConfigurationFile $dgLauncherScript
            } catch {
                $dgCommitFailure = $_.Exception
                try { Write-DgAtomicPrivateConfig $dgConfigurationFile $dgOriginalConfigurationBytes }
                catch {
                    throw ('The new launcher configuration failed validation, and restoring the previous atomic snapshot also failed: ' +
                        $dgCommitFailure.Message + ' / rollback: ' + $_.Exception.Message)
                }
                throw ('The new launcher configuration failed validation and the previous configuration was restored: ' + $dgCommitFailure.Message)
            }
        }
        foreach ($dgPlan in $dgShortcutPlans) {
            if ($dgPlan.Matches) { continue }
            Install-DgShortcutAtomically $dgPlan.Temporary $dgPlan.Path
            $dgPlan.Temporary = $null
        }
        try {
            # File.Replace updates the on-disk .lnk atomically, but Explorer can
            # keep an older parsed shortcut target in memory. Notify both every
            # canonical item and its directory, including on an idempotent run,
            # so the desktop re-reads the stable launcher instead of executing a
            # stale release-specific target cached before the migration.
            Publish-DgShortcutShellRefresh -ShortcutPaths @($dgShortcutPlans | ForEach-Object { $_.Path }) `
                -Directory $dgShortcutDirectory
            $dgShortcutRefreshStatus = 'REFRESHED'
        } catch {
            $dgShortcutRefreshStatus = 'FAILED'
            $dgShortcutRefreshWarning = 'The verified shortcut files are installed, but Windows Explorer did not acknowledge the refresh notification. Re-run this activation before using a cached desktop icon.'
            Write-Warning $dgShortcutRefreshWarning
        }
    } finally {
        foreach ($dgPlan in $dgShortcutPlans) {
            if ($dgPlan.Temporary -and [IO.File]::Exists($dgPlan.Temporary)) { [IO.File]::Delete($dgPlan.Temporary) }
        }
    }
}

[pscustomobject][ordered]@{
    status = $(if ($ValidateOnly) { 'VALIDATED' } elseif ($dgShortcutRefreshStatus -eq 'FAILED') { 'ACTIVATED_SHORTCUT_REFRESH_PENDING' } else { 'ACTIVATED' })
    releaseDirectory = $dgRelease
    releaseManifestSha256 = $dgExpectedManifestDigest
    configurationFile = $dgConfigurationFile
    configurationChanged = $dgConfigurationChanged
    shortcutsChanged = $dgShortcutsChanged
    shortcutsRefreshed = $(if ($dgShortcutRefreshStatus -eq 'REFRESHED') { $dgShortcutPlans.Count } else { 0 })
    shortcutRefreshStatus = $dgShortcutRefreshStatus
    shortcutRefreshWarning = $dgShortcutRefreshWarning
    shortcutDirectory = $dgShortcutDirectory
}
