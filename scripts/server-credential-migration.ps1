# Filesystem-only migration of the DPAPI blobs referenced by server profiles. No decryption.
function Assert-DgNoReparseCredentialPath([string]$Path) {
    $ancestor = $Path
    while ($ancestor) {
        if ([IO.File]::Exists($ancestor) -or [IO.Directory]::Exists($ancestor)) {
            if (([IO.File]::GetAttributes($ancestor) -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw 'Credential migration does not follow symbolic links or junctions.'
            }
        }
        $ancestor = [IO.Path]::GetDirectoryName($ancestor)
    }
}

function Get-DgCiphertextDigest([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    $digest = [Security.Cryptography.SHA256]::Create()
    try { return [Convert]::ToBase64String($digest.ComputeHash($stream)) }
    finally { $digest.Dispose(); $stream.Dispose() }
}

function Protect-DgNewServerCredentialDirectory([string]$Path) {
    # Use the stable account SID, never a logon-session or restricted-token SID.
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    try { $accountSid = $identity.User }
    finally { $identity.Dispose() }
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetOwner($accountSid)
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($sid in @($accountSid, [Security.Principal.SecurityIdentifier]::new('S-1-5-18'), [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($sid,
            [Security.AccessControl.FileSystemRights]::FullControl,
            ([Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [Security.AccessControl.InheritanceFlags]::ObjectInherit),
            [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow)
        $acl.AddAccessRule($rule)
    }
    # Write the new owner/DACL without requesting audit-policy privileges or changing any SACL.
    $directoryInfo = [IO.DirectoryInfo]::new($Path)
    if ($PSVersionTable.PSVersion.Major -lt 6) { $directoryInfo.SetAccessControl($acl) }
    else { [IO.FileSystemAclExtensions]::SetAccessControl($directoryInfo, $acl) }
}

function Copy-DgServerCredentials([string]$ProfilesFile, [string]$SourceDirectory, [string]$DestinationDirectory) {
    $profilesPath = Resolve-DgFilesystemPath $ProfilesFile
    $source = Resolve-DgFilesystemPath $SourceDirectory
    $destination = Resolve-DgFilesystemPath $DestinationDirectory
    if (-not [IO.File]::Exists($profilesPath) -or -not [IO.Directory]::Exists($source)) {
        throw 'Server profiles and the source credential directory must exist.'
    }
    if ($destination.TrimEnd([char[]]'\/') -eq ([IO.Path]::GetPathRoot($destination)).TrimEnd([char[]]'\/') -or
        $destination -match '(?i)(^|[\\/])AppData([\\/]|$)') {
        throw 'Server credentials must use a dedicated directory outside AppData to avoid packaged-app virtualization.'
    }
    Assert-DgNoReparseCredentialPath $destination
    if ([IO.File]::Exists($destination)) { throw 'The server credential destination must be a directory.' }
    if (([IO.FileInfo]::new($profilesPath)).Length -gt 1MB) { throw 'The server profile file is too large.' }
    try { $profiles = [IO.File]::ReadAllText($profilesPath, [Text.Encoding]::UTF8) | ConvertFrom-Json }
    catch { throw 'The server profile file is not valid JSON.' }
    if ($null -eq $profiles -or $null -eq $profiles.PSObject.Properties['schemaVersion'] -or
        $profiles.schemaVersion -ne 1 -or $null -eq $profiles.PSObject.Properties['profiles'] -or
        $profiles.profiles -isnot [Array] -or $profiles.profiles.Count -lt 1 -or $profiles.profiles.Count -gt 20) {
        throw 'Expected schemaVersion 1 and an array of 1 to 20 server profiles.'
    }
    $references = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($profile in $profiles.profiles) {
        if ($null -eq $profile -or $null -eq $profile.PSObject.Properties['credentialRef'] -or
            $profile.credentialRef -isnot [string] -or $profile.credentialRef -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$' -or
            $profile.credentialRef -match '(?i)^commercial-session(?:-|$)') {
            throw 'Each server profile must reference a valid supplier credential; desktop login sessions cannot be migrated.'
        }
        $null = $references.Add($profile.credentialRef)
    }
    $planned = [Collections.Generic.List[object]]::new()
    # Validate every source and every existing target before creating any credential file.
    foreach ($reference in $references) {
        $sourceFile = Join-Path $source ($reference + '.dpapi')
        $targetFile = Join-Path $destination ($reference + '.dpapi')
        Assert-DgNoReparseCredentialPath $sourceFile
        Assert-DgNoReparseCredentialPath $targetFile
        if (-not [IO.File]::Exists($sourceFile)) { throw ('A referenced encrypted server credential is missing: ' + $reference) }
        $sourceLength = ([IO.FileInfo]::new($sourceFile)).Length
        if ($sourceLength -lt 1 -or $sourceLength -gt 65536) { throw 'A referenced encrypted credential has an invalid size.' }
        if ([IO.Directory]::Exists($targetFile)) { throw 'An existing directory conflicts with a server credential filename.' }
        $sourceDigest = Get-DgCiphertextDigest $sourceFile
        $exists = [IO.File]::Exists($targetFile)
        if ($exists -and $sourceDigest -ne (Get-DgCiphertextDigest $targetFile)) {
            throw ('An existing server credential differs; it was not overwritten: ' + $reference)
        }
        $planned.Add([pscustomobject]@{Source=$sourceFile;Target=$targetFile;Digest=$sourceDigest;Exists=$exists})
    }
    if (-not [IO.Directory]::Exists($destination)) {
        [IO.Directory]::CreateDirectory($destination) | Out-Null
        try { Protect-DgNewServerCredentialDirectory $destination }
        catch {
            # No blobs have been copied. Remove only the newly created directory if still empty.
            try { [IO.Directory]::Delete($destination, $false) } catch { }
            throw 'Could not protect the new server credential directory; no credentials were copied.'
        }
    }
    foreach ($item in $planned) {
        if (-not $item.Exists) {
            # Copy refuses an existing filename, including one created after preflight.
            [IO.File]::Copy($item.Source, $item.Target, $false)
        }
        if ($item.Digest -ne (Get-DgCiphertextDigest $item.Target)) { throw 'Encrypted credential verification failed; the launcher configuration was not updated.' }
    }
    return $destination
}
