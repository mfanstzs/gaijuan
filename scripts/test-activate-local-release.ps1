[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($PSVersionTable.PSEdition -eq 'Desktop') {
    $env:PSModulePath = Join-Path $PSHOME 'Modules'
}
. (Join-Path $PSScriptRoot 'local-launch-validation.ps1')

$dgTestRoot = Join-Path ([IO.Path]::GetTempPath()) ('DeskGrade-release-activation-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($dgTestRoot) | Out-Null
$dgTestsPassed = [Collections.Generic.List[string]]::new()

function Assert-DgActivationTest([bool]$Condition, [string]$Name) {
    if (-not $Condition) { throw ('Release activation regression failed: ' + $Name) }
    $dgTestsPassed.Add($Name)
}

function Test-DgBytesEqual([byte[]]$Left, [byte[]]$Right) {
    return [Collections.StructuralComparisons]::StructuralEqualityComparer.Equals($Left, $Right)
}

function New-DgSyntheticRelease([string]$Path, [string]$Marker) {
    [IO.Directory]::CreateDirectory((Join-Path $Path 'DeskGrade')) | Out-Null
    $names = @('DeskGrade/DeskGrade.exe', 'a.txt', 'b.txt', 'c.txt', 'd.txt', 'e.txt', 'f.txt', 'g.txt', 'h.txt', 'i.txt')
    $lines = @()
    foreach ($name in $names) {
        $file = Join-Path $Path $name
        [IO.File]::WriteAllText($file, ($Marker + '-' + $name), [Text.UTF8Encoding]::new($false))
        $lines += (Get-DgSha256 $file) + '  ' + $name
    }
    $manifest = Join-Path $Path 'SHA256SUMS.txt'
    [IO.File]::WriteAllText($manifest, (($lines -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
    return Get-DgSha256 $manifest
}

try {
    $dgLauncherHome = Join-Path $dgTestRoot 'private-launcher'
    Protect-DgPrivateDirectory $dgLauncherHome | Out-Null
    $dgRuntime = Join-Path $dgLauncherHome 'runtime-test'
    Protect-DgPrivateDirectory $dgRuntime | Out-Null
    Protect-DgPrivateDirectory (Join-Path $dgRuntime 'lib') | Out-Null
    $dgJavaHome = Join-Path $dgLauncherHome 'java'
    Protect-DgPrivateDirectory (Join-Path $dgJavaHome 'bin') | Out-Null
    $dgData = Join-Path $dgLauncherHome 'data'
    Protect-DgPrivateDirectory $dgData | Out-Null
    $dgShortcuts = Join-Path $dgTestRoot 'shortcuts'
    [IO.Directory]::CreateDirectory($dgShortcuts) | Out-Null

    $dgLauncher = Join-Path $dgLauncherHome 'launcher.ps1'
    $dgInstalledValidation = Join-Path $dgLauncherHome 'local-launch-validation.ps1'
    $dgStorage = Join-Path $dgLauncherHome 'storage.json'
    $dgJava = Join-Path $dgJavaHome 'bin/java.exe'
    [IO.File]::WriteAllText($dgLauncher, '# synthetic stable launcher')
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'local-launch-validation.ps1') -Destination $dgInstalledValidation
    [IO.File]::WriteAllText($dgStorage, '{"synthetic":true}')
    [IO.File]::WriteAllText($dgJava, 'synthetic-java')

    $dgRuntimeEntries = @()
    foreach ($name in @('gateway.jar', 'lib/a.jar', 'lib/b.jar', 'lib/c.jar', 'lib/d.jar')) {
        $file = Join-Path $dgRuntime $name
        [IO.File]::WriteAllText($file, ('synthetic-' + $name))
        $dgRuntimeEntries += [ordered]@{ path = $name; sha256 = Get-DgSha256 $file }
    }

    $dgOldRelease = Join-Path $dgTestRoot 'release-old'
    $dgNewRelease = Join-Path $dgTestRoot 'release-new'
    $dgThirdRelease = Join-Path $dgTestRoot 'release-third'
    $dgInvalidRelease = Join-Path $dgTestRoot 'release-invalid'
    $dgOldDigest = New-DgSyntheticRelease $dgOldRelease 'old'
    $dgNewDigest = New-DgSyntheticRelease $dgNewRelease 'new'
    $dgThirdDigest = New-DgSyntheticRelease $dgThirdRelease 'third'
    $null = New-DgSyntheticRelease $dgInvalidRelease 'invalid'
    [IO.File]::WriteAllText((Join-Path $dgInvalidRelease 'a.txt'), 'tampered-after-manifest')

    $dgConfig = [ordered]@{
        schemaVersion = 2
        javaHome = $dgJavaHome
        gatewayRuntime = $dgRuntime
        releaseDirectory = $dgOldRelease
        dataDirectory = $dgData
        port = 8787
        allowLive = $false
        profilesFile = $null
        credentialsDirectory = $null
        storageConfigurationFile = $dgStorage
        integrity = [ordered]@{
            schemaVersion = 1
            launcherSha256 = Get-DgSha256 $dgLauncher
            validationSha256 = Get-DgSha256 $dgInstalledValidation
            javaExecutableSha256 = Get-DgSha256 $dgJava
            storageConfigurationSha256 = Get-DgSha256 $dgStorage
            profilesFileSha256 = $null
            releaseManifestSha256 = $dgOldDigest
            runtimeFiles = @($dgRuntimeEntries)
        }
    }
    $dgConfigurationFile = Join-Path $dgLauncherHome 'config.json'
    [IO.File]::WriteAllText($dgConfigurationFile, ($dgConfig | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    Protect-DgPrivateFile $dgConfigurationFile | Out-Null
    $dgActivate = Join-Path $PSScriptRoot 'activate-local-release.ps1'

    $dgBeforeValidation = [IO.File]::ReadAllBytes($dgConfigurationFile)
    $dgValidationResult = & $dgActivate -ReleaseDirectory $dgNewRelease -ExpectedManifestSha256 $dgNewDigest `
        -ConfigurationFile $dgConfigurationFile -ShortcutDirectory $dgShortcuts -ValidateOnly
    Assert-DgActivationTest ($dgValidationResult.status -eq 'VALIDATED' -and $dgValidationResult.configurationChanged -and
        $dgValidationResult.shortcutsChanged -eq 3 -and $dgValidationResult.shortcutsRefreshed -eq 0 -and
        $dgValidationResult.shortcutRefreshStatus -eq 'SKIPPED_VALIDATE_ONLY') 'validate-only-reports-proposed-changes'
    Assert-DgActivationTest (Test-DgBytesEqual $dgBeforeValidation ([IO.File]::ReadAllBytes($dgConfigurationFile))) 'validate-only-does-not-change-config'
    Assert-DgActivationTest (@(Get-ChildItem -LiteralPath $dgShortcuts -Force).Count -eq 0) 'validate-only-does-not-create-shortcuts'

    $dgIndependentDigestRejected = $false
    try { & $dgActivate -ReleaseDirectory $dgNewRelease -ExpectedManifestSha256 ('0' * 64) `
            -ConfigurationFile $dgConfigurationFile -ShortcutDirectory $dgShortcuts -ValidateOnly | Out-Null }
    catch { $dgIndependentDigestRejected = $true }
    Assert-DgActivationTest ($dgIndependentDigestRejected -and
        (Test-DgBytesEqual $dgBeforeValidation ([IO.File]::ReadAllBytes($dgConfigurationFile))) -and
        @(Get-ChildItem -LiteralPath $dgShortcuts -Force).Count -eq 0) 'independent-manifest-digest-required-before-change'

    $dgBefore = Read-DgLauncherConfiguration $dgConfigurationFile
    $dgActivationResult = & $dgActivate -ReleaseDirectory $dgNewRelease -ExpectedManifestSha256 $dgNewDigest `
        -ConfigurationFile $dgConfigurationFile -ShortcutDirectory $dgShortcuts
    $dgAfter = Read-DgLauncherConfiguration $dgConfigurationFile
    Assert-DgInstalledIntegrity $dgAfter $dgConfigurationFile $dgLauncher
    Assert-DgActivationTest ($dgActivationResult.status -eq 'ACTIVATED' -and $dgActivationResult.configurationChanged -and
        $dgActivationResult.shortcutsChanged -eq 3 -and $dgActivationResult.shortcutsRefreshed -eq 3 -and
        $dgActivationResult.shortcutRefreshStatus -eq 'REFRESHED') 'activation-reports-committed-changes'
    Assert-DgActivationTest (([string]$dgAfter.releaseDirectory).Equals($dgNewRelease, [StringComparison]::OrdinalIgnoreCase) -and
        $dgAfter.integrity.releaseManifestSha256 -eq $dgNewDigest) 'configuration-points-to-verified-release'
    Assert-DgActivationTest ($dgAfter.javaHome -eq $dgBefore.javaHome -and $dgAfter.gatewayRuntime -eq $dgBefore.gatewayRuntime -and
        $dgAfter.dataDirectory -eq $dgBefore.dataDirectory -and $dgAfter.port -eq $dgBefore.port -and
        $dgAfter.allowLive -eq $dgBefore.allowLive -and $dgAfter.storageConfigurationFile -eq $dgBefore.storageConfigurationFile -and
        $dgAfter.integrity.launcherSha256 -eq $dgBefore.integrity.launcherSha256 -and
        $dgAfter.integrity.validationSha256 -eq $dgBefore.integrity.validationSha256 -and
        $dgAfter.integrity.javaExecutableSha256 -eq $dgBefore.integrity.javaExecutableSha256 -and
        $dgAfter.integrity.storageConfigurationSha256 -eq $dgBefore.integrity.storageConfigurationSha256 -and
        (($dgAfter.integrity.runtimeFiles | ConvertTo-Json -Depth 4 -Compress) -eq ($dgBefore.integrity.runtimeFiles | ConvertTo-Json -Depth 4 -Compress))) 'unrelated-configuration-and-hashes-preserved'

    $dgShell = New-Object -ComObject WScript.Shell
    foreach ($definition in @(@('DeskGrade 一键启动', 'App'), @('DeskGrade 管理后台', 'Admin'), @('DeskGrade 停止后台', 'Stop'))) {
        $path = Join-Path $dgShortcuts ($definition[0] + '.lnk')
        $shortcut = $dgShell.CreateShortcut($path)
        Assert-DgActivationTest (([string]$shortcut.TargetPath).Equals((Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'), [StringComparison]::OrdinalIgnoreCase) -and
            $shortcut.Arguments.Contains($dgLauncher) -and $shortcut.Arguments.Contains('-Mode ' + $definition[1]) -and
            ([string]$shortcut.IconLocation).StartsWith((Join-Path $dgNewRelease 'DeskGrade/DeskGrade.exe'), [StringComparison]::OrdinalIgnoreCase)) ('stable-shortcut-target-and-new-icon-' + $definition[1])
    }

    $dgBeforeIdempotent = [IO.File]::ReadAllBytes($dgConfigurationFile)
    $dgIdempotent = & $dgActivate -ReleaseDirectory $dgNewRelease -ExpectedManifestSha256 $dgNewDigest `
        -ConfigurationFile $dgConfigurationFile -ShortcutDirectory $dgShortcuts
    Assert-DgActivationTest (-not $dgIdempotent.configurationChanged -and $dgIdempotent.shortcutsChanged -eq 0 -and
        $dgIdempotent.shortcutsRefreshed -eq 3 -and $dgIdempotent.shortcutRefreshStatus -eq 'REFRESHED') 'second-activation-is-idempotent'
    Assert-DgActivationTest (Test-DgBytesEqual $dgBeforeIdempotent ([IO.File]::ReadAllBytes($dgConfigurationFile))) 'idempotent-activation-does-not-rewrite-config'

    $dgBeforeInvalid = [IO.File]::ReadAllBytes($dgConfigurationFile)
    $dgInvalidRejected = $false
    try { & $dgActivate -ReleaseDirectory $dgInvalidRelease -ExpectedManifestSha256 (Get-DgSha256 (Join-Path $dgInvalidRelease 'SHA256SUMS.txt')) `
            -ConfigurationFile $dgConfigurationFile -ShortcutDirectory $dgShortcuts | Out-Null }
    catch { $dgInvalidRejected = $true }
    Assert-DgActivationTest ($dgInvalidRejected -and (Test-DgBytesEqual $dgBeforeInvalid ([IO.File]::ReadAllBytes($dgConfigurationFile)))) 'tampered-release-rejected-before-config-change'

    $dgUnrelatedPath = Join-Path $dgShortcuts 'DeskGrade 停止后台.lnk'
    $dgUnrelated = $dgShell.CreateShortcut($dgUnrelatedPath)
    $dgUnrelated.TargetPath = $dgPowerShell = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
    $dgUnrelated.Arguments = '-NoProfile -Command "exit 0"'
    $dgUnrelated.Save()
    $dgBeforeUnrelated = [IO.File]::ReadAllBytes($dgConfigurationFile)
    $dgUnrelatedRejected = $false
    try { & $dgActivate -ReleaseDirectory $dgThirdRelease -ExpectedManifestSha256 $dgThirdDigest `
            -ConfigurationFile $dgConfigurationFile -ShortcutDirectory $dgShortcuts | Out-Null }
    catch { $dgUnrelatedRejected = $true }
    Assert-DgActivationTest ($dgUnrelatedRejected -and (Test-DgBytesEqual $dgBeforeUnrelated ([IO.File]::ReadAllBytes($dgConfigurationFile))) -and
        (Read-DgLauncherConfiguration $dgConfigurationFile).integrity.releaseManifestSha256 -ne $dgThirdDigest) 'unrelated-shortcut-rejected-before-config-change'

    $dgPackageText = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'package-windows.ps1'))
    $dgFinallyIndex = $dgPackageText.LastIndexOf('} finally { Pop-Location }', [StringComparison]::Ordinal)
    $dgActivationIndex = $dgPackageText.LastIndexOf('& $dgActivationScript', [StringComparison]::Ordinal)
    Assert-DgActivationTest ($dgPackageText.Contains('[switch]$DoNotActivateLocalLauncher') -and
        $dgPackageText.Contains("'DeskGrade/server-launcher/config.json'") -and
        $dgPackageText.Contains('-ExpectedManifestSha256 $dgReleaseManifestSha256') -and
        $dgActivationIndex -gt $dgFinallyIndex) 'packager-activation-is-default-and-outside-build-failure-catch'

    [ordered]@{ status = 'PASS'; checks = $dgTestsPassed.Count; names = @($dgTestsPassed);
        powerShellVersion = $PSVersionTable.PSVersion.ToString(); applicationStarts = 0; serviceStarts = 0 } | ConvertTo-Json -Depth 3
} finally {
    $dgResolvedTestRoot = [IO.Path]::GetFullPath($dgTestRoot)
    $dgResolvedTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char[]]'\/')
    if (-not $dgResolvedTestRoot.StartsWith($dgResolvedTemp + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
        (Split-Path $dgResolvedTestRoot -Leaf) -notmatch '^DeskGrade-release-activation-[a-f0-9]{32}$') {
        throw 'Refusing cleanup outside the unique release activation test directory.'
    }
    Remove-Item -LiteralPath $dgResolvedTestRoot -Recurse -Force
}
