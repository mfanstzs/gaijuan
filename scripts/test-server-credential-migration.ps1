[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'local-launch-validation.ps1')
. (Join-Path $PSScriptRoot 'server-credential-migration.ps1')
$dgMigrationRepo=Resolve-DgFilesystemPath (Split-Path $PSScriptRoot)
$dgMigrationTestRoot=Join-Path $dgMigrationRepo ('.credential-migration-test-'+[Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($dgMigrationTestRoot) | Out-Null
$dgMigrationChecks=[Collections.Generic.List[string]]::new()
function Assert-DgMigrationTest([bool]$Condition,[string]$Name) {
    if (-not $Condition) { throw ('Synthetic credential migration regression failed: '+$Name) }
    $dgMigrationChecks.Add($Name)
}
function Set-DgSyntheticProfiles([string[]]$References) {
    $rows=@($References | ForEach-Object { [ordered]@{credentialRef=$_} })
    [IO.File]::WriteAllText($dgMigrationProfiles,([ordered]@{schemaVersion=1;profiles=$rows}|ConvertTo-Json -Depth 3),[Text.UTF8Encoding]::new($false))
}
function Invoke-DgMigrationRejection([string]$Destination,[string]$Name) {
    $failed=$false
    try { $null=Copy-DgServerCredentials $dgMigrationProfiles $dgMigrationSource $Destination }
    catch { $failed=$true }
    Assert-DgMigrationTest $failed $Name
}
try {
    $dgComposedPath=Assert-DgNoReparsePath -Value $dgMigrationRepo -BaseDirectory $null
    Assert-DgMigrationTest ($dgComposedPath -eq $dgMigrationRepo) 'composed-dot-source-preserves-launch-path-validator-contract'
    $dgMigrationProfiles=Join-Path $dgMigrationTestRoot 'profiles.json'
    $dgMigrationSource=Join-Path $dgMigrationTestRoot '源 凭证[1]'
    $dgMigrationDestination=Join-Path $dgMigrationTestRoot 'server 凭证[2]'
    [IO.Directory]::CreateDirectory($dgMigrationSource) | Out-Null
    $dgSyntheticBlob=[byte[]](1,8,3,9,7,2,6,4)
    $dgSyntheticSource=Join-Path $dgMigrationSource 'supplier_a.dpapi'
    $dgSyntheticTarget=Join-Path $dgMigrationDestination 'supplier_a.dpapi'
    [IO.File]::WriteAllBytes($dgSyntheticSource,$dgSyntheticBlob)
    [IO.File]::WriteAllBytes((Join-Path $dgMigrationSource 'commercial-session-v1.dpapi'),[byte[]](5,1,2))
    [IO.File]::WriteAllBytes((Join-Path $dgMigrationSource 'unreferenced_supplier.dpapi'),[byte[]](6,1,2))
    Set-DgSyntheticProfiles @('supplier_a','supplier_a')
    $dgDestinationResult=Copy-DgServerCredentials $dgMigrationProfiles $dgMigrationSource $dgMigrationDestination
    Assert-DgMigrationTest ($dgDestinationResult -eq $dgMigrationDestination -and
        (Get-DgCiphertextDigest $dgSyntheticSource) -eq (Get-DgCiphertextDigest $dgSyntheticTarget)) 'referenced-ciphertext-copied-unchanged'
    Assert-DgMigrationTest (@([IO.Directory]::GetFiles($dgMigrationDestination)).Count -eq 1) 'deduplicates-and-excludes-session-and-unreferenced-blobs'
    Assert-DgMigrationTest (@([IO.Directory]::GetFiles($dgMigrationSource)).Count -eq 3) 'source-files-preserved'
    $dgAcl=Get-Acl -LiteralPath $dgMigrationDestination
    $dgAllowedSids=@($dgAcl.Access | ForEach-Object {$_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value})
    $dgAccountSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    Assert-DgMigrationTest ($dgAcl.AreAccessRulesProtected -and $dgAllowedSids.Count -eq 3 -and
        $dgAllowedSids -contains $dgAccountSid -and $dgAllowedSids -contains 'S-1-5-18' -and $dgAllowedSids -contains 'S-1-5-32-544') 'new-directory-uses-account-system-admin-acl'
    $dgTimestamp=[IO.File]::GetLastWriteTimeUtc($dgSyntheticTarget)
    $dgAclSddl=$dgAcl.Sddl
    $null=Copy-DgServerCredentials $dgMigrationProfiles $dgMigrationSource $dgMigrationDestination
    Assert-DgMigrationTest ([IO.File]::GetLastWriteTimeUtc($dgSyntheticTarget) -eq $dgTimestamp -and
        (Get-Acl -LiteralPath $dgMigrationDestination).Sddl -eq $dgAclSddl) 'reinstall-preserves-identical-file-and-existing-acl'
    $null=Copy-DgServerCredentials $dgMigrationProfiles $dgMigrationDestination $dgMigrationDestination
    Assert-DgMigrationTest ([IO.File]::Exists($dgSyntheticTarget)) 'stable-directory-can-be-reused-as-source'
    [IO.File]::WriteAllBytes($dgSyntheticTarget,[byte[]](9,9,9))
    Invoke-DgMigrationRejection $dgMigrationDestination 'conflicting-existing-blob-rejected'
    Assert-DgMigrationTest (([IO.File]::ReadAllBytes($dgSyntheticTarget) -join ',') -eq '9,9,9') 'conflicting-blob-not-overwritten'
    Set-DgSyntheticProfiles @('supplier_a','missing_supplier')
    $dgMissingDestination=Join-Path $dgMigrationTestRoot 'missing-check'
    Invoke-DgMigrationRejection $dgMissingDestination 'missing-referenced-blob-rejected'
    Assert-DgMigrationTest (-not [IO.Directory]::Exists($dgMissingDestination)) 'all-sources-validated-before-copy'
    Set-DgSyntheticProfiles @('../outside')
    Invoke-DgMigrationRejection (Join-Path $dgMigrationTestRoot 'traversal-check') 'path-traversal-reference-rejected'
    Set-DgSyntheticProfiles @('commercial-session-v1')
    Invoke-DgMigrationRejection (Join-Path $dgMigrationTestRoot 'session-check') 'explicit-session-reference-rejected'
    Set-DgSyntheticProfiles @('supplier_a')
    Invoke-DgMigrationRejection (Join-Path $dgMigrationTestRoot 'AppData/server-credentials') 'appdata-destination-rejected'
    Invoke-DgMigrationRejection $dgSyntheticSource 'file-cannot-be-destination-directory'
    [IO.File]::WriteAllBytes((Join-Path $dgMigrationSource 'empty.dpapi'),[byte[]]@())
    Set-DgSyntheticProfiles @('empty')
    Invoke-DgMigrationRejection (Join-Path $dgMigrationTestRoot 'empty-check') 'empty-blob-rejected'
    [IO.File]::WriteAllBytes((Join-Path $dgMigrationSource 'oversized.dpapi'),[byte[]]::new(65537))
    Set-DgSyntheticProfiles @('oversized')
    Invoke-DgMigrationRejection (Join-Path $dgMigrationTestRoot 'oversized-check') 'oversized-blob-rejected'
    [IO.File]::WriteAllText($dgMigrationProfiles,'not-json')
    Invoke-DgMigrationRejection (Join-Path $dgMigrationTestRoot 'invalid-json-check') 'invalid-profile-json-rejected'
    [ordered]@{status='PASS';checks=$dgMigrationChecks.Count;names=@($dgMigrationChecks);powerShellVersion=$PSVersionTable.PSVersion.ToString();syntheticBytesOnly=$true;decryptionCalls=0;modelRequests=0;serviceStarts=0} | ConvertTo-Json -Depth 3
} finally {
    $dgResolvedMigrationRoot=[IO.Path]::GetFullPath($dgMigrationTestRoot)
    if (-not $dgResolvedMigrationRoot.StartsWith($dgMigrationRepo+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or
        (Split-Path $dgResolvedMigrationRoot -Leaf) -notmatch '^\.credential-migration-test-[a-f0-9]{32}$') { throw 'Refusing cleanup outside this run synthetic directory.' }
    Remove-Item -LiteralPath $dgResolvedMigrationRoot -Recurse -Force
}
