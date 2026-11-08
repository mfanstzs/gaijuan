# Shared by the installed launcher and its filesystem-only regression checks.
function Resolve-DgFilesystemPath([string]$Value, [string]$BaseDirectory) {
    if ([string]::IsNullOrWhiteSpace($Value)) { throw 'A local filesystem path is required.' }
    # .NET Framework and modern .NET differ on when they reject these characters.
    # Reject explicitly before resolution so PS5 and PS7 share literal-path semantics.
    if ($Value -match '[*?"<>|\x00-\x1f]') { throw 'Invalid Windows filesystem path characters.' }
    if ($BaseDirectory -and -not [IO.Path]::IsPathRooted($Value) -and $Value -notmatch '^[^\\/]+:') {
        $Value = Join-Path -Path $BaseDirectory -ChildPath $Value
    }
    if ($Value -match '^[A-Za-z]:[^\\/]') { throw 'Drive-relative paths are ambiguous; use a full or ordinary relative path.' }
    $provider = $null
    $drive = $null
    $resolved = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Value, [ref]$provider, [ref]$drive)
    if ($provider.Name -ne 'FileSystem') { throw 'Only filesystem paths are supported.' }
    $absolute = [IO.Path]::GetFullPath($resolved)
    $root = [IO.Path]::GetPathRoot($absolute)
    if ($absolute.TrimEnd([char[]]'\/') -eq $root.TrimEnd([char[]]'\/')) { return $root }
    return $absolute.TrimEnd([char[]]'\/')
}

function Assert-DgNoReparsePath([string]$Value, [string]$BaseDirectory) {
    $resolved = Resolve-DgFilesystemPath -Value $Value -BaseDirectory $BaseDirectory
    $cursor = $resolved
    while ($cursor) {
        if ([IO.File]::Exists($cursor) -or [IO.Directory]::Exists($cursor)) {
            if (([IO.File]::GetAttributes($cursor) -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw 'DeskGrade trusted paths cannot contain symbolic links, junctions, or other reparse points.'
            }
        }
        $parent = [IO.Path]::GetDirectoryName($cursor)
        if (-not $parent -or $parent -eq $cursor) { break }
        $cursor = $parent
    }
    return $resolved
}

function Assert-DgNoReparseTree([string]$Value) {
    $root = Assert-DgNoReparsePath -Value $Value -BaseDirectory $null
    if (-not [IO.Directory]::Exists($root)) { throw 'The trusted DeskGrade directory does not exist.' }
    $pending = [Collections.Generic.Queue[string]]::new()
    $pending.Enqueue($root)
    while ($pending.Count -gt 0) {
        $directory = $pending.Dequeue()
        foreach ($entry in [IO.Directory]::EnumerateFileSystemEntries($directory)) {
            $attributes = [IO.File]::GetAttributes($entry)
            if (($attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw 'DeskGrade trusted directories cannot contain symbolic links, junctions, or other reparse points.'
            }
            if (($attributes -band [IO.FileAttributes]::Directory) -ne 0) { $pending.Enqueue($entry) }
        }
    }
    return $root
}

function Test-DgBroadWriteAccess([string]$Path) {
    $broadSids = @('S-1-1-0', 'S-1-5-11', 'S-1-5-32-545')
    $dangerous = [Security.AccessControl.FileSystemRights]::WriteData -bor
        [Security.AccessControl.FileSystemRights]::CreateFiles -bor
        [Security.AccessControl.FileSystemRights]::CreateDirectories -bor
        [Security.AccessControl.FileSystemRights]::AppendData -bor
        [Security.AccessControl.FileSystemRights]::Delete -bor
        [Security.AccessControl.FileSystemRights]::DeleteSubdirectoriesAndFiles -bor
        [Security.AccessControl.FileSystemRights]::ChangePermissions -bor
        [Security.AccessControl.FileSystemRights]::TakeOwnership
    foreach ($rule in (Get-Acl -LiteralPath $Path -ErrorAction Stop).Access) {
        if ($rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
            ($rule.FileSystemRights -band $dangerous) -eq 0) { continue }
        try { $sid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value }
        catch { $sid = '' }
        if ($sid -in $broadSids) { return $true }
    }
    return $false
}

function Assert-DgPrivateTrustRoot([string]$Value) {
    $resolved = Assert-DgNoReparsePath -Value $Value -BaseDirectory $null
    if (-not [IO.Directory]::Exists($resolved)) { throw 'The private DeskGrade trust directory does not exist.' }
    $profile = if ($env:USERPROFILE) { [IO.Path]::GetFullPath($env:USERPROFILE).TrimEnd([char[]]'\/') } else { $null }
    $insideProfile = $profile -and ($resolved.Equals($profile, [StringComparison]::OrdinalIgnoreCase) -or
        $resolved.StartsWith($profile + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase))
    $cursor = $resolved
    while ($cursor) {
        if (Test-DgBroadWriteAccess $cursor) {
            throw 'A trusted DeskGrade launcher directory or one of its parents is writable by a broad Windows principal.'
        }
        if ($insideProfile -and $cursor.Equals($profile, [StringComparison]::OrdinalIgnoreCase)) { break }
        $parent = [IO.Path]::GetDirectoryName($cursor)
        if (-not $parent -or $parent -eq $cursor) { break }
        $cursor = $parent
    }
    return $resolved
}

function Protect-DgPrivateDirectory([string]$Value) {
    $resolved = Assert-DgNoReparsePath -Value $Value -BaseDirectory $null
    if ([IO.File]::Exists($resolved)) { throw 'A private DeskGrade directory path points to a file.' }
    [IO.Directory]::CreateDirectory($resolved) | Out-Null
    Assert-DgNoReparsePath -Value $resolved -BaseDirectory $null | Out-Null
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    try { $accountSid = $identity.User }
    finally { $identity.Dispose() }
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetOwner($accountSid)
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($sid in @($accountSid,
            [Security.Principal.SecurityIdentifier]::new('S-1-5-18'),
            [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($sid,
            [Security.AccessControl.FileSystemRights]::FullControl,
            ([Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [Security.AccessControl.InheritanceFlags]::ObjectInherit),
            [Security.AccessControl.PropagationFlags]::None,
            [Security.AccessControl.AccessControlType]::Allow)
        $null = $acl.AddAccessRule($rule)
    }
    # Persist only the modified owner/DACL sections. Set-Acl's generic provider can request SACL
    # privileges that an ordinary desktop process does not have, although this ACL has no audit rules.
    $directoryInfo = [IO.DirectoryInfo]::new($resolved)
    if ($PSVersionTable.PSVersion.Major -lt 6) { $directoryInfo.SetAccessControl($acl) }
    else { [IO.FileSystemAclExtensions]::SetAccessControl($directoryInfo, $acl) }
    if (Test-DgBroadWriteAccess $resolved) { throw 'The private DeskGrade directory ACL could not be secured.' }
    return $resolved
}

function Protect-DgPrivateFile([string]$Value) {
    $resolved = Assert-DgNoReparsePath -Value $Value -BaseDirectory $null
    if (-not [IO.File]::Exists($resolved)) { throw 'A private DeskGrade file does not exist.' }
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    try { $accountSid = $identity.User }
    finally { $identity.Dispose() }
    $acl = [Security.AccessControl.FileSecurity]::new()
    $acl.SetOwner($accountSid)
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($sid in @($accountSid,
            [Security.Principal.SecurityIdentifier]::new('S-1-5-18'),
            [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($sid,
            [Security.AccessControl.FileSystemRights]::FullControl,
            [Security.AccessControl.AccessControlType]::Allow)
        $null = $acl.AddAccessRule($rule)
    }
    # FileSecurity records only owner/DACL changes here; preserve the existing SACL untouched.
    $fileInfo = [IO.FileInfo]::new($resolved)
    if ($PSVersionTable.PSVersion.Major -lt 6) { $fileInfo.SetAccessControl($acl) }
    else { [IO.FileSystemAclExtensions]::SetAccessControl($fileInfo, $acl) }
    if (Test-DgBroadWriteAccess $resolved) { throw 'The private DeskGrade file ACL could not be secured.' }
    return $resolved
}

function Get-DgSha256([string]$Path) {
    $resolved = Assert-DgNoReparsePath -Value $Path -BaseDirectory $null
    if (-not [IO.File]::Exists($resolved)) { throw 'A required integrity file is missing.' }
    return (Get-FileHash -LiteralPath $resolved -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
}

function Assert-DgDigest([string]$Path, [string]$Expected) {
    if ($Expected -notmatch '^[0-9a-f]{64}$' -or (Get-DgSha256 $Path) -ne $Expected) {
        throw 'A trusted DeskGrade file failed its SHA-256 integrity check.'
    }
}

function Get-DgChildRelativePath([string]$Root, [string]$Path) {
    $base = [IO.Path]::GetFullPath($Root).TrimEnd([char[]]'\/')
    $child = [IO.Path]::GetFullPath($Path)
    if (-not $child.StartsWith($base + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'An integrity path escaped its trusted directory.'
    }
    return $child.Substring($base.Length + 1).Replace('\', '/')
}

function Assert-DgReleaseIntegrity([string]$ReleaseDirectory, [string]$ExpectedManifestDigest) {
    $release = Assert-DgNoReparseTree $ReleaseDirectory
    $manifest = Join-Path $release 'SHA256SUMS.txt'
    Assert-DgDigest $manifest $ExpectedManifestDigest
    if (([IO.FileInfo]::new($manifest)).Length -gt 2MB) { throw 'The DeskGrade release manifest is too large.' }
    $expected = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $desktopListed = $false
    foreach ($line in [IO.File]::ReadAllLines($manifest, [Text.Encoding]::UTF8)) {
        if ($line -notmatch '^([0-9a-f]{64})  ([^\x00-\x1f]+)$') { throw 'The DeskGrade release manifest is malformed.' }
        $digest = $matches[1]
        $relative = $matches[2]
        if ($relative.Contains('\') -or $relative.StartsWith('/') -or $relative.Contains(':') -or
            $relative -match '(^|/)\.\.(/|$)' -or -not $expected.Add($relative)) {
            throw 'The DeskGrade release manifest contains an unsafe or duplicate path.'
        }
        $target = Join-Path $release ($relative.Replace('/', [IO.Path]::DirectorySeparatorChar))
        if ((Get-DgChildRelativePath $release $target) -ne $relative) { throw 'The release manifest path is not canonical.' }
        Assert-DgDigest $target $digest
        if ($relative.Equals('DeskGrade/DeskGrade.exe', [StringComparison]::OrdinalIgnoreCase)) { $desktopListed = $true }
    }
    if (-not $desktopListed -or $expected.Count -lt 10) { throw 'The release manifest is incomplete.' }
    $actual = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($file in Get-ChildItem -LiteralPath $release -Recurse -File -Force) {
        $relative = Get-DgChildRelativePath $release $file.FullName
        if ($relative.Equals('SHA256SUMS.txt', [StringComparison]::OrdinalIgnoreCase)) { continue }
        $null = $actual.Add($relative)
    }
    if ($actual.Count -ne $expected.Count) { throw 'The DeskGrade release contains files outside its integrity manifest.' }
    foreach ($relative in $actual) {
        if (-not $expected.Contains($relative)) { throw 'The DeskGrade release contains files outside its integrity manifest.' }
    }
}

function Assert-DgRuntimeIntegrity([string]$RuntimeDirectory, $Entries) {
    $runtime = Assert-DgNoReparseTree $RuntimeDirectory
    $expected = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in @($Entries)) {
        if ($null -eq $entry -or $entry.path -isnot [string] -or $entry.sha256 -isnot [string] -or
            $entry.path -notmatch '^(gateway\.jar|lib/[A-Za-z0-9][A-Za-z0-9._-]*\.jar)$' -or
            $entry.sha256 -notmatch '^[0-9a-f]{64}$' -or $expected.ContainsKey($entry.path)) {
            throw 'The installed gateway integrity inventory is malformed.'
        }
        $expected.Add($entry.path, $entry.sha256)
    }
    if ($expected.Count -lt 5 -or $expected.Count -gt 100 -or -not $expected.ContainsKey('gateway.jar')) {
        throw 'The installed gateway integrity inventory is incomplete.'
    }
    $actual = @(Get-ChildItem -LiteralPath $runtime -Recurse -File -Force)
    if ($actual.Count -ne $expected.Count) { throw 'The gateway runtime contains an unexpected or missing file.' }
    foreach ($file in $actual) {
        $relative = Get-DgChildRelativePath $runtime $file.FullName
        if (-not $expected.ContainsKey($relative)) { throw 'The gateway runtime contains an unexpected file.' }
        Assert-DgDigest $file.FullName $expected[$relative]
    }
}

function Assert-DgExactProperties($Object, [string[]]$Names) {
    if ($null -eq $Object) { throw 'A trusted DeskGrade configuration object is missing.' }
    $actual = @($Object.PSObject.Properties.Name)
    if ($actual.Count -ne $Names.Count) { throw 'The DeskGrade launcher configuration contains unexpected fields.' }
    foreach ($name in $Names) {
        if ($name -notin $actual) { throw 'The DeskGrade launcher configuration is missing a required field.' }
    }
}

function Read-DgLauncherConfiguration([string]$ConfigurationFile) {
    $path = Assert-DgNoReparsePath -Value $ConfigurationFile -BaseDirectory $null
    if (-not [IO.File]::Exists($path) -or ([IO.FileInfo]::new($path)).Length -gt 64KB) {
        throw 'The DeskGrade launcher configuration is missing or too large.'
    }
    try { $config = [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8) | ConvertFrom-Json }
    catch { throw 'The DeskGrade launcher configuration is not valid JSON.' }
    Assert-DgExactProperties $config @('schemaVersion','javaHome','gatewayRuntime','releaseDirectory','dataDirectory','port','allowLive','profilesFile','credentialsDirectory','storageConfigurationFile','integrity')
    if ($config.schemaVersion -ne 2 -or -not ($config.port -is [int] -or $config.port -is [long]) -or
        $config.port -lt 1024 -or $config.port -gt 65535 -or
        $config.allowLive -isnot [bool]) { throw 'The DeskGrade launcher configuration has invalid scalar fields.' }
    Assert-DgExactProperties $config.integrity @('schemaVersion','launcherSha256','validationSha256','javaExecutableSha256','storageConfigurationSha256','profilesFileSha256','releaseManifestSha256','runtimeFiles')
    if ($config.integrity.schemaVersion -ne 1) { throw 'The DeskGrade launcher integrity metadata is unsupported.' }
    return $config
}

function Assert-DgInstalledIntegrity($Configuration, [string]$ConfigurationFile, [string]$LauncherScript) {
    $launcherHome = Assert-DgPrivateTrustRoot (Split-Path $ConfigurationFile)
    Assert-DgNoReparseTree $launcherHome | Out-Null
    if (Test-DgBroadWriteAccess $ConfigurationFile) { throw 'The DeskGrade launcher configuration ACL is unsafe.' }
    $runtime = Resolve-DgFilesystemPath -Value $Configuration.gatewayRuntime -BaseDirectory $launcherHome
    if (-not $runtime.StartsWith($launcherHome + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The gateway runtime must stay inside the private launcher directory.'
    }
    $storage = Resolve-DgFilesystemPath -Value $Configuration.storageConfigurationFile -BaseDirectory $launcherHome
    if (-not $storage.StartsWith($launcherHome + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The server storage configuration must stay inside the private launcher directory.'
    }
    Assert-DgDigest $LauncherScript $Configuration.integrity.launcherSha256
    Assert-DgDigest (Join-Path $launcherHome 'local-launch-validation.ps1') $Configuration.integrity.validationSha256
    Assert-DgDigest (Join-Path $Configuration.javaHome 'bin/java.exe') $Configuration.integrity.javaExecutableSha256
    Assert-DgDigest $storage $Configuration.integrity.storageConfigurationSha256
    if ($Configuration.profilesFile) {
        $profiles = Resolve-DgFilesystemPath -Value $Configuration.profilesFile -BaseDirectory $launcherHome
        if (-not $profiles.StartsWith($launcherHome + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
            $Configuration.integrity.profilesFileSha256 -isnot [string]) { throw 'The server profile integrity metadata is invalid.' }
        Assert-DgDigest $profiles $Configuration.integrity.profilesFileSha256
    } elseif ($null -ne $Configuration.integrity.profilesFileSha256) {
        throw 'A profile digest exists without an installed profile file.'
    }
    Assert-DgRuntimeIntegrity $runtime $Configuration.integrity.runtimeFiles
    Assert-DgReleaseIntegrity $Configuration.releaseDirectory $Configuration.integrity.releaseManifestSha256
}

function ConvertTo-DgSafeDiagnosticText([string]$Value) {
    if ($null -eq $Value) { return '' }
    $clean = [regex]::Replace($Value, '[\x00-\x1f\x7f]', ' ')
    if ($clean.Length -gt 2048) { return $clean.Substring(0, 2048) + '…' }
    return $clean
}

function Get-DgLivePathChecks([string]$ConfigurationFile, [string]$ProfilesFile, [string]$CredentialsDirectory) {
    $baseDirectory = Split-Path -Path $ConfigurationFile
    $checks = [ordered]@{}
    foreach ($item in @(@('profilesFile', $ProfilesFile, 'File'), @('credentialsDirectory', $CredentialsDirectory, 'Directory'))) {
        $check = [ordered]@{ path = $null; expectedType = $item[2]; fileExists = $false; directoryExists = $false; valid = $false; errorCode = $null }
        if ([string]::IsNullOrWhiteSpace($item[1])) { $check.errorCode = 'PATH_EMPTY' }
        else {
            try {
                $resolved = Resolve-DgFilesystemPath -Value $item[1] -BaseDirectory $baseDirectory
                $check.path = ConvertTo-DgSafeDiagnosticText $resolved
                # .NET filesystem APIs are literal and do not expand square brackets or wildcards.
                $check.fileExists = [IO.File]::Exists($resolved)
                $check.directoryExists = [IO.Directory]::Exists($resolved)
                $check.valid = if ($item[2] -eq 'File') { $check.fileExists } else { $check.directoryExists }
                if (-not $check.valid) { $check.errorCode = 'PATH_MISSING_OR_WRONG_TYPE' }
            } catch { $check.errorCode = 'PATH_INVALID_OR_NOT_FILESYSTEM' }
        }
        $checks[$item[0]] = $check
    }
    return $checks
}

function Assert-DgLivePathChecks($Checks, [string]$ConfigurationFile) {
    $failures = @()
    foreach ($item in @(@('profilesFile', '模型配置文件'), @('credentialsDirectory', '受保护凭证目录'))) {
        $check = $Checks[$item[0]]
        if (-not $check.valid) {
            $pathText = if ($check.path) { $check.path } else { '（未配置或不是有效的本地文件系统路径）' }
            $reason = if ($check.errorCode -eq 'PATH_EMPTY') { '未配置' }
                elseif ($check.errorCode -eq 'PATH_INVALID_OR_NOT_FILESYSTEM') { '路径无效' }
                elseif ($check.fileExists -or $check.directoryExists) { '路径类型错误' }
                else { '不存在或当前用户无法访问' }
            $failures += ($item[1] + $reason + '：' + $pathText)
        }
    }
    if ($failures.Count -gt 0) {
        $message = ($failures -join "`n") + "`n启动配置：" + (ConvertTo-DgSafeDiagnosticText $ConfigurationFile)
        $exception = [InvalidOperationException]::new($message)
        $exception.Data['DeskGradeErrorCode'] = 'LIVE_PATH_VALIDATION_FAILED'
        $exception.Data['DeskGradeSafeMessage'] = $message
        throw $exception
    }
}

function Write-DgStartupDiagnostic($Diagnostic, [string]$LauncherDirectory) {
    try {
        # Explicit field allowlist: never serialize configuration, invocation arguments or ErrorRecord.
        $safe = [ordered]@{
            schemaVersion = 1; updatedUtc = [DateTime]::UtcNow.ToString('o')
            mode = (ConvertTo-DgSafeDiagnosticText $Diagnostic.mode)
            status = (ConvertTo-DgSafeDiagnosticText $Diagnostic.status)
            stage = (ConvertTo-DgSafeDiagnosticText $Diagnostic.stage)
            configurationFile = (ConvertTo-DgSafeDiagnosticText $Diagnostic.configurationFile)
            windowsUser = (ConvertTo-DgSafeDiagnosticText $Diagnostic.windowsUser)
            powerShellVersion = (ConvertTo-DgSafeDiagnosticText $Diagnostic.powerShellVersion)
            liveEnabled = $(if ($null -eq $Diagnostic.liveEnabled) { $null } else { [bool]$Diagnostic.liveEnabled }); paths = $null; error = $null
        }
        if ($null -ne $Diagnostic.paths) {
            $safe.paths = [ordered]@{}
            foreach ($name in @('profilesFile', 'credentialsDirectory')) {
                $source = $Diagnostic.paths[$name]
                $safe.paths[$name] = [ordered]@{
                    path = (ConvertTo-DgSafeDiagnosticText $source.path)
                    expectedType = (ConvertTo-DgSafeDiagnosticText $source.expectedType)
                    fileExists = [bool]$source.fileExists; directoryExists = [bool]$source.directoryExists
                    valid = [bool]$source.valid; errorCode = (ConvertTo-DgSafeDiagnosticText $source.errorCode)
                }
            }
        }
        if ($null -ne $Diagnostic.error) {
            $safe.error = [ordered]@{
                code = (ConvertTo-DgSafeDiagnosticText $Diagnostic.error.code)
                message = (ConvertTo-DgSafeDiagnosticText $Diagnostic.error.message)
                exceptionType = (ConvertTo-DgSafeDiagnosticText $Diagnostic.error.exceptionType)
            }
        }
        $json = ConvertTo-Json -InputObject $safe -Depth 6
        if ([Text.Encoding]::UTF8.GetByteCount($json) -gt 32KB) { throw 'Diagnostic exceeds its fixed size limit.' }
        [IO.File]::WriteAllText((Join-Path $LauncherDirectory 'startup-last.json'), $json, [Text.UTF8Encoding]::new($false))
        if ($safe.status -eq 'FAILED') {
            [IO.File]::WriteAllText((Join-Path $LauncherDirectory 'startup-failure-last.json'), $json, [Text.UTF8Encoding]::new($false))
        }
    } catch {
        # Diagnostic failure must not hide the original startup error or stop a healthy service.
        Write-Warning '无法保存启动诊断 startup-last.json；请保留当前窗口的错误文字。'
    }
}
