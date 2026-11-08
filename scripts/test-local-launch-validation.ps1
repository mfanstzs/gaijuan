[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'local-launch-validation.ps1')
$dgTestRoot = Join-Path ([IO.Path]::GetTempPath()) ('DeskGrade-path-check-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($dgTestRoot) | Out-Null
$dgTestsPassed = [Collections.Generic.List[string]]::new()
function Assert-DgTest([bool]$Condition, [string]$Name) {
    if (-not $Condition) { throw ('Path validation regression failed: ' + $Name) }
    $dgTestsPassed.Add($Name)
}
function Assert-DgTestRejected($Checks, [string]$Field, [string]$Code, [string]$Name) {
    $rejected = $false
    try { Assert-DgLivePathChecks $Checks $dgTestConfig }
    catch {
        $rejected = $_.Exception.Data['DeskGradeErrorCode'] -eq 'LIVE_PATH_VALIDATION_FAILED' -and
            $_.Exception.Message.Contains($dgTestConfig)
    }
    Assert-DgTest ($rejected -and -not $Checks[$Field].valid -and $Checks[$Field].errorCode -eq $Code) $Name
}
try {
    $dgTestConfig = Join-Path $dgTestRoot 'config.json'
    $dgModel = Join-Path $dgTestRoot '中文 模型[1].json'
    $dgCredentials = Join-Path $dgTestRoot '受保护 凭证[2]'
    [IO.File]::WriteAllText($dgModel, '{}')
    [IO.Directory]::CreateDirectory($dgCredentials) | Out-Null
    $dgChecks = Get-DgLivePathChecks $dgTestConfig $dgModel $dgCredentials
    Assert-DgLivePathChecks $dgChecks $dgTestConfig
    Assert-DgTest ($dgChecks.profilesFile.valid -and $dgChecks.credentialsDirectory.valid) 'absolute-chinese-spaces-literal-brackets'

    $dgChecks = Get-DgLivePathChecks $dgTestConfig '中文 模型[1].json' '受保护 凭证[2]'
    Assert-DgLivePathChecks $dgChecks $dgTestConfig
    Assert-DgTest ($dgChecks.profilesFile.path -eq $dgModel -and $dgChecks.credentialsDirectory.path -eq $dgCredentials) 'relative-paths-use-configuration-directory'

    $dgChecks = Get-DgLivePathChecks $dgTestConfig $dgModel ($dgCredentials + [IO.Path]::DirectorySeparatorChar)
    Assert-DgLivePathChecks $dgChecks $dgTestConfig
    Assert-DgTest ($dgChecks.credentialsDirectory.path -eq $dgCredentials) 'trailing-directory-separator'

    $dgChecks = Get-DgLivePathChecks $dgTestConfig 'missing-model.json' $dgCredentials
    Assert-DgTestRejected $dgChecks 'profilesFile' 'PATH_MISSING_OR_WRONG_TYPE' 'missing-model-rejected'
    $dgChecks = Get-DgLivePathChecks $dgTestConfig $dgModel 'missing-credentials'
    Assert-DgTestRejected $dgChecks 'credentialsDirectory' 'PATH_MISSING_OR_WRONG_TYPE' 'missing-credentials-rejected'
    $dgChecks = Get-DgLivePathChecks $dgTestConfig $dgCredentials $dgCredentials
    Assert-DgTestRejected $dgChecks 'profilesFile' 'PATH_MISSING_OR_WRONG_TYPE' 'directory-cannot-be-model-file'
    $dgChecks = Get-DgLivePathChecks $dgTestConfig $dgModel $dgModel
    Assert-DgTestRejected $dgChecks 'credentialsDirectory' 'PATH_MISSING_OR_WRONG_TYPE' 'file-cannot-be-credential-directory'
    $dgChecks = Get-DgLivePathChecks $dgTestConfig '' $dgCredentials
    Assert-DgTestRejected $dgChecks 'profilesFile' 'PATH_EMPTY' 'blank-model-rejected'
    $dgChecks = Get-DgLivePathChecks $dgTestConfig $dgModel ' '
    Assert-DgTestRejected $dgChecks 'credentialsDirectory' 'PATH_EMPTY' 'blank-credentials-rejected'
    $dgChecks = Get-DgLivePathChecks $dgTestConfig 'Env:PATH' $dgCredentials
    Assert-DgTestRejected $dgChecks 'profilesFile' 'PATH_INVALID_OR_NOT_FILESYSTEM' 'non-filesystem-provider-rejected'
    $dgChecks = Get-DgLivePathChecks $dgTestConfig 'C:ambiguous.json' $dgCredentials
    Assert-DgTestRejected $dgChecks 'profilesFile' 'PATH_INVALID_OR_NOT_FILESYSTEM' 'drive-relative-path-rejected'
    $dgChecks = Get-DgLivePathChecks $dgTestConfig '中文 模型?.json' $dgCredentials
    Assert-DgTestRejected $dgChecks 'profilesFile' 'PATH_INVALID_OR_NOT_FILESYSTEM' 'wildcard-does-not-match-existing-model'

    $dgPrivate = Join-Path $dgTestRoot 'private-launcher'
    Protect-DgPrivateDirectory $dgPrivate | Out-Null
    Assert-DgPrivateTrustRoot $dgPrivate | Out-Null
    Assert-DgTest (-not (Test-DgBroadWriteAccess $dgPrivate)) 'private-launcher-acl-removes-broad-write'

    $dgTarget = Join-Path $dgTestRoot 'junction-target'
    $dgJunction = Join-Path $dgTestRoot 'junction-link'
    [IO.Directory]::CreateDirectory($dgTarget) | Out-Null
    New-Item -ItemType Junction -Path $dgJunction -Target $dgTarget | Out-Null
    $dgJunctionRejected = $false
    try { Assert-DgNoReparsePath $dgJunction $null | Out-Null } catch { $dgJunctionRejected = $true }
    Assert-DgTest $dgJunctionRejected 'junction-trust-path-rejected'
    Remove-Item -LiteralPath $dgJunction -Force

    $dgNestedJunction = Join-Path $dgPrivate 'nested-junction'
    New-Item -ItemType Junction -Path $dgNestedJunction -Target $dgTarget | Out-Null
    $dgNestedJunctionRejected = $false
    try { Assert-DgNoReparseTree $dgPrivate | Out-Null } catch { $dgNestedJunctionRejected = $true }
    Assert-DgTest $dgNestedJunctionRejected 'nested-junction-in-trust-tree-rejected'
    Remove-Item -LiteralPath $dgNestedJunction -Force

    $dgRuntime = Join-Path $dgPrivate 'runtime-test'
    Protect-DgPrivateDirectory $dgRuntime | Out-Null
    Protect-DgPrivateDirectory (Join-Path $dgRuntime 'lib') | Out-Null
    $dgRuntimeNames = @('gateway.jar','lib/a.jar','lib/b.jar','lib/c.jar','lib/d.jar')
    $dgRuntimeEntries = @()
    foreach($dgName in $dgRuntimeNames) {
        $dgPath = Join-Path $dgRuntime $dgName
        [IO.File]::WriteAllText($dgPath,('synthetic-'+$dgName))
        $dgRuntimeEntries += [pscustomobject]@{path=$dgName;sha256=(Get-DgSha256 $dgPath)}
    }
    Assert-DgRuntimeIntegrity $dgRuntime $dgRuntimeEntries
    [IO.File]::WriteAllText((Join-Path $dgRuntime 'lib/extra.jar'),'unexpected')
    $dgExtraRejected = $false
    try { Assert-DgRuntimeIntegrity $dgRuntime $dgRuntimeEntries } catch { $dgExtraRejected = $true }
    Assert-DgTest $dgExtraRejected 'unexpected-classpath-jar-rejected'

    $dgRelease = Join-Path $dgTestRoot 'synthetic-release'
    [IO.Directory]::CreateDirectory((Join-Path $dgRelease 'DeskGrade')) | Out-Null
    $dgReleaseNames = @('DeskGrade/DeskGrade.exe','a.txt','b.txt','c.txt','d.txt','e.txt','f.txt','g.txt','h.txt','i.txt')
    $dgManifestLines = @()
    foreach($dgName in $dgReleaseNames) {
        $dgPath = Join-Path $dgRelease $dgName
        [IO.File]::WriteAllText($dgPath,('synthetic-'+$dgName))
        $dgManifestLines += (Get-DgSha256 $dgPath) + '  ' + $dgName
    }
    $dgManifest = Join-Path $dgRelease 'SHA256SUMS.txt'
    [IO.File]::WriteAllText($dgManifest,(($dgManifestLines -join "`n")+"`n"),[Text.UTF8Encoding]::new($false))
    $dgManifestDigest = Get-DgSha256 $dgManifest
    Assert-DgReleaseIntegrity $dgRelease $dgManifestDigest
    [IO.File]::WriteAllText((Join-Path $dgRelease 'a.txt'),'tampered')
    $dgTamperRejected = $false
    try { Assert-DgReleaseIntegrity $dgRelease $dgManifestDigest } catch { $dgTamperRejected = $true }
    Assert-DgTest $dgTamperRejected 'release-content-tamper-rejected'

    $dgDiagnostic = [ordered]@{
        mode='App';status='FAILED';stage='VALIDATE_LIVE_PATHS';configurationFile=$dgTestConfig
        windowsUser='synthetic-user';powerShellVersion=$PSVersionTable.PSVersion.ToString();liveEnabled=$true
        paths=$dgChecks;error=@{code='LIVE_PATH_VALIDATION_FAILED';message='synthetic path failure';exceptionType='System.InvalidOperationException'}
        config=@{password='DO-NOT-LOG-SYNTHETIC-PASSWORD';key='DO-NOT-LOG-SYNTHETIC-KEY'}
        invocationArguments='DO-NOT-LOG-SYNTHETIC-ARGUMENTS'
    }
    Write-DgStartupDiagnostic $dgDiagnostic $dgTestRoot
    $dgDiagnosticJson = [IO.File]::ReadAllText((Join-Path $dgTestRoot 'startup-last.json'))
    $dgParsedDiagnostic = $dgDiagnosticJson | ConvertFrom-Json
    Assert-DgTest ($dgDiagnosticJson -notmatch 'DO-NOT-LOG' -and $dgParsedDiagnostic.status -eq 'FAILED' -and
        $dgParsedDiagnostic.paths.profilesFile.valid -eq $false -and [Text.Encoding]::UTF8.GetByteCount($dgDiagnosticJson) -lt 32KB) 'failed-diagnostic-is-bounded-and-allowlisted'
    $dgDiagnostic.status='SUCCESS'
    $dgDiagnostic.stage='COMPLETE'
    $dgDiagnostic.error=$null
    $dgDiagnostic.paths=Get-DgLivePathChecks $dgTestConfig $dgModel $dgCredentials
    Write-DgStartupDiagnostic $dgDiagnostic $dgTestRoot
    $dgParsedDiagnostic = [IO.File]::ReadAllText((Join-Path $dgTestRoot 'startup-last.json')) | ConvertFrom-Json
    Assert-DgTest ($dgParsedDiagnostic.status -eq 'SUCCESS' -and $dgParsedDiagnostic.paths.profilesFile.valid -and $null -eq $dgParsedDiagnostic.error) 'successful-diagnostic-replaces-failure'
    $dgPreservedFailure = [IO.File]::ReadAllText((Join-Path $dgTestRoot 'startup-failure-last.json')) | ConvertFrom-Json
    Assert-DgTest ($dgPreservedFailure.status -eq 'FAILED' -and $dgPreservedFailure.error.code -eq 'LIVE_PATH_VALIDATION_FAILED') 'later-success-preserves-last-failure'

    [ordered]@{status='PASS';checks=$dgTestsPassed.Count;names=@($dgTestsPassed);powerShellVersion=$PSVersionTable.PSVersion.ToString();nativeCalls=0;modelRequests=0;serviceStarts=0} | ConvertTo-Json -Depth 3
} finally {
    # This run created only this unique directory under the OS temp directory.
    $dgResolvedTestRoot = [IO.Path]::GetFullPath($dgTestRoot)
    $dgResolvedTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([char[]]'\/')
    if (-not $dgResolvedTestRoot.StartsWith($dgResolvedTemp + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
        (Split-Path $dgResolvedTestRoot -Leaf) -notmatch '^DeskGrade-path-check-[a-f0-9]{32}$') { throw 'Refusing cleanup outside the unique test directory.' }
    Remove-Item -LiteralPath $dgResolvedTestRoot -Recurse -Force
}
