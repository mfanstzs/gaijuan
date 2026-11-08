[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$ReleaseDirectory,
    [string]$JavaHome,
    [string]$ProfilesFile,
    [string]$CredentialsDirectory,
    [string]$ServerCredentialsDirectory,
    [string]$StorageConfigurationFile,
    [string]$DataDirectory,
    [string]$LauncherDirectory,
    [string]$ShortcutDirectory = [Environment]::GetFolderPath('Desktop'),
    [ValidateRange(1024,65535)][int]$Port = 8787,
    [switch]$EnableLive,
    [switch]$DoNotSeedUserSettings
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'local-launch-validation.ps1')
. (Join-Path $PSScriptRoot 'server-credential-migration.ps1')
function Assert-DgNonRootDirectory([string]$Value) {
    if ($Value.TrimEnd([char[]]'\/') -eq ([IO.Path]::GetPathRoot($Value)).TrimEnd([char[]]'\/')) {
        throw 'Choose a dedicated data or launcher directory, not a drive or share root.'
    }
}
. (Join-Path $PSScriptRoot 'common.ps1')
Initialize-DeskGradeJava -JavaHome $JavaHome
$dgRoot=Split-Path $PSScriptRoot
if (-not $StorageConfigurationFile) { $StorageConfigurationFile=Join-Path $dgRoot 'local-data/server-config/storage.json' }
$StorageConfigurationFile=Resolve-DgFilesystemPath $StorageConfigurationFile
if (-not [IO.File]::Exists($StorageConfigurationFile)) { throw 'Prepare the server MySQL / Redis configuration before installing production shortcuts.' }
$dgRelease=Resolve-DgFilesystemPath $ReleaseDirectory
if (-not(Test-Path -LiteralPath (Join-Path $dgRelease 'DeskGrade/DeskGrade.exe'))) { throw 'A complete verified DeskGrade app-image is required.' }
if (-not $DataDirectory) { $DataDirectory=Join-Path $env:LOCALAPPDATA 'DeskGrade/server-data' }
if (-not $LauncherDirectory) { $LauncherDirectory=Join-Path $env:LOCALAPPDATA 'DeskGrade/server-launcher' }
$DataDirectory=Resolve-DgFilesystemPath $DataDirectory
Assert-DgNonRootDirectory $DataDirectory
if ($EnableLive -and (-not $ProfilesFile -or -not $CredentialsDirectory)) { throw 'Live mode needs explicit server profiles and protected credential directory.' }
if ($ProfilesFile) {
    $ProfilesFile=Resolve-DgFilesystemPath $ProfilesFile
    if(-not(Test-Path -LiteralPath $ProfilesFile -PathType Leaf)) { throw 'Server profile file was not found.' }
}
if ($CredentialsDirectory) {
    $CredentialsDirectory=Resolve-DgFilesystemPath $CredentialsDirectory
    if(-not(Test-Path -LiteralPath $CredentialsDirectory -PathType Container)) { throw 'Protected credential directory was not found.' }
}
$dgHome=Resolve-DgFilesystemPath $LauncherDirectory
Assert-DgNonRootDirectory $dgHome
if ($EnableLive) {
    if (-not $ServerCredentialsDirectory) { $ServerCredentialsDirectory=Join-Path $dgRoot 'local-data/gateway-credentials' }
    $CredentialsDirectory=Copy-DgServerCredentials -ProfilesFile $ProfilesFile -SourceDirectory $CredentialsDirectory -DestinationDirectory $ServerCredentialsDirectory
}
$ShortcutDirectory=Resolve-DgFilesystemPath $ShortcutDirectory
[IO.Directory]::CreateDirectory((Split-Path $dgHome)) | Out-Null
Protect-DgPrivateDirectory $dgHome | Out-Null
Assert-DgPrivateTrustRoot $dgHome | Out-Null
Assert-DgNoReparseTree $dgHome | Out-Null
Protect-DgPrivateDirectory $DataDirectory | Out-Null
[IO.Directory]::CreateDirectory($ShortcutDirectory) | Out-Null
$dgRuntime=Join-Path $dgHome ('runtime-' + [Guid]::NewGuid().ToString('N'))
Protect-DgPrivateDirectory $dgRuntime | Out-Null
Protect-DgPrivateDirectory (Join-Path $dgRuntime 'lib') | Out-Null
[xml]$dgPom=Get-Content -LiteralPath (Join-Path $dgRoot 'pom.xml')
$dgVersion=$dgPom.project.version
$dgGateway=Join-Path $dgRoot ('grading-gateway/target/grading-gateway-'+$dgVersion+'.jar')
if (-not(Test-Path -LiteralPath $dgGateway)) { throw 'Build grading-gateway before installing shortcuts.' }
Copy-Item -LiteralPath $dgGateway -Destination (Join-Path $dgRuntime 'gateway.jar')
$dgLibraries=@(Get-ChildItem -LiteralPath (Join-Path $dgRoot 'grading-gateway/target/lib') -Filter '*.jar' -File)
if ($dgLibraries.Count -lt 4 -or @($dgLibraries | Where-Object {$_.Name -match '(?i)(junit|test|mock)'}).Count -gt 0) { throw 'Unexpected gateway runtime library inventory.' }
foreach($dgLibrary in $dgLibraries) { Copy-Item -LiteralPath $dgLibrary.FullName -Destination (Join-Path $dgRuntime 'lib') }
$dgInstalledScript=Join-Path $dgHome 'launcher.ps1'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'launch-local.ps1') -Destination $dgInstalledScript -Force
$dgInstalledValidation=Join-Path $dgHome 'local-launch-validation.ps1'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'local-launch-validation.ps1') -Destination $dgInstalledValidation -Force
$dgInstalledStorage=Join-Path $dgHome 'storage.json'
if (-not $StorageConfigurationFile.Equals($dgInstalledStorage,[StringComparison]::OrdinalIgnoreCase)) {
    Copy-Item -LiteralPath $StorageConfigurationFile -Destination $dgInstalledStorage -Force
}
$StorageConfigurationFile=$dgInstalledStorage
$dgInstalledProfiles=$null
if ($ProfilesFile) {
    $dgInstalledProfiles=Join-Path $dgHome 'server-profiles.json'
    if (-not $ProfilesFile.Equals($dgInstalledProfiles,[StringComparison]::OrdinalIgnoreCase)) {
        Copy-Item -LiteralPath $ProfilesFile -Destination $dgInstalledProfiles -Force
    }
    $ProfilesFile=$dgInstalledProfiles
}
$dgReleaseManifest=Join-Path $dgRelease 'SHA256SUMS.txt'
if (-not [IO.File]::Exists($dgReleaseManifest)) { throw 'The verified DeskGrade release SHA256SUMS.txt is missing.' }
$dgReleaseManifestDigest=Get-DgSha256 $dgReleaseManifest
Assert-DgReleaseIntegrity $dgRelease $dgReleaseManifestDigest
$dgJavaExecutable=Join-Path $env:JAVA_HOME 'bin/java.exe'
if (Test-DgBroadWriteAccess $dgJavaExecutable) { throw 'The selected Java executable is broadly writable and cannot be trusted.' }
$dgRuntimeFiles=@()
foreach($dgRuntimeFile in Get-ChildItem -LiteralPath $dgRuntime -Recurse -File | Sort-Object FullName) {
    Protect-DgPrivateFile $dgRuntimeFile.FullName | Out-Null
    $dgRuntimeFiles += [ordered]@{path=(Get-DgChildRelativePath $dgRuntime $dgRuntimeFile.FullName);sha256=(Get-DgSha256 $dgRuntimeFile.FullName)}
}
foreach($dgPrivateFile in @($dgInstalledScript,$dgInstalledValidation,$dgInstalledStorage)) { Protect-DgPrivateFile $dgPrivateFile | Out-Null }
if($dgInstalledProfiles) { Protect-DgPrivateFile $dgInstalledProfiles | Out-Null }
$dgIntegrity=[ordered]@{
    schemaVersion=1
    launcherSha256=(Get-DgSha256 $dgInstalledScript)
    validationSha256=(Get-DgSha256 $dgInstalledValidation)
    javaExecutableSha256=(Get-DgSha256 $dgJavaExecutable)
    storageConfigurationSha256=(Get-DgSha256 $dgInstalledStorage)
    profilesFileSha256=$(if($dgInstalledProfiles){Get-DgSha256 $dgInstalledProfiles}else{$null})
    releaseManifestSha256=$dgReleaseManifestDigest
    runtimeFiles=@($dgRuntimeFiles)
}
$dgConfig=[ordered]@{schemaVersion=2;javaHome=$env:JAVA_HOME;gatewayRuntime=$dgRuntime;releaseDirectory=$dgRelease;dataDirectory=$DataDirectory;port=$Port;allowLive=[bool]$EnableLive;profilesFile=$ProfilesFile;credentialsDirectory=$CredentialsDirectory;storageConfigurationFile=$StorageConfigurationFile;integrity=$dgIntegrity}
$dgConfigFile=Join-Path $dgHome 'config.json'
if(Test-Path -LiteralPath $dgConfigFile) { Copy-Item -LiteralPath $dgConfigFile -Destination (Join-Path $dgHome ('config-backup-'+[Guid]::NewGuid().ToString('N')+'.json')) }
[IO.File]::WriteAllText($dgConfigFile,($dgConfig | ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
Protect-DgPrivateFile $dgConfigFile | Out-Null
$dgVerifiedConfig=Read-DgLauncherConfiguration $dgConfigFile
Assert-DgInstalledIntegrity $dgVerifiedConfig $dgConfigFile $dgInstalledScript
$dgPowerShell=Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
$dgShell=New-Object -ComObject WScript.Shell
$dgLinks=@()
foreach($dgItem in @(@('DeskGrade 一键启动','App'),@('DeskGrade 管理后台','Admin'),@('DeskGrade 停止后台','Stop'))) {
    $dgLinkPath=Join-Path $ShortcutDirectory ($dgItem[0]+'.lnk')
    if(Test-Path -LiteralPath $dgLinkPath) {
        $dgExisting=$dgShell.CreateShortcut($dgLinkPath)
        if(-not $dgExisting.Arguments.Contains($dgInstalledScript)) { throw 'A same-named unrelated shortcut exists; it was not overwritten.' }
    }
    $dgLink=$dgShell.CreateShortcut($dgLinkPath)
    $dgLink.TargetPath=$dgPowerShell
    if($dgItem[1] -eq 'App') {
        $dgLink.Arguments='-NoLogo -NoProfile -ExecutionPolicy RemoteSigned -WindowStyle Hidden -File "'+$dgInstalledScript+'" -Mode App -ShortcutUi'
    } else {
        $dgLink.Arguments='-NoLogo -NoProfile -ExecutionPolicy RemoteSigned -File "'+$dgInstalledScript+'" -Mode '+$dgItem[1]
    }
    $dgLink.WorkingDirectory=$dgHome
    $dgLink.WindowStyle=$(if($dgItem[1] -eq 'App'){7}else{1})
    $dgLink.IconLocation=(Join-Path $dgRelease 'DeskGrade/DeskGrade.exe')+',0'
    $dgLink.Description='DeskGrade stable launcher; admin http://127.0.0.1:'+$Port+'/admin'
    $dgLink.Save()
    $dgLinks+=$dgLinkPath
}
if(-not $DoNotSeedUserSettings) {
    $dgSettings=Join-Path $env:LOCALAPPDATA 'DeskGrade/settings'
    [IO.Directory]::CreateDirectory($dgSettings) | Out-Null
    $dgAccess=Join-Path $dgSettings 'access.json'
    if(-not(Test-Path -LiteralPath $dgAccess)) {
        $dgInitial=@{schemaVersion=1;mode='COMMERCIAL';serviceAddress=('http://127.0.0.1:'+$Port)} | ConvertTo-Json
        [IO.File]::WriteAllText($dgAccess,$dgInitial,[Text.UTF8Encoding]::new($false))
    }
}
[ordered]@{configuration=$dgConfigFile;shortcuts=$dgLinks;adminUrl=('http://127.0.0.1:'+$Port+'/admin');administratorSetupPromptedIfNeeded=$false;explicitSetupCommand=('powershell.exe -NoProfile -File "'+$dgInstalledScript+'" -Mode Setup');liveCallsRequireUserAction=$true} | ConvertTo-Json -Depth 4
