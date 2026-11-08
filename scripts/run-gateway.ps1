[CmdletBinding()]
param(
    [string]$JavaHome,
    [string]$DataDirectory,
    [string]$StorageConfigurationFile,
    [switch]$DevelopmentH2,
    [ValidateRange(1,65535)][int]$Port = 8787,
    [string]$ProfileFile,
    [string]$CredentialsDirectory,
    [switch]$AllowLive,
    [string]$BootstrapAdmin,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
Initialize-DeskGradeJava -JavaHome $JavaHome
$dgRoot = Split-Path $PSScriptRoot
if (-not $DataDirectory) { $DataDirectory = Join-Path $dgRoot 'local-data/gateway' }
$dgData = [IO.Path]::GetFullPath($DataDirectory)
if ($DevelopmentH2 -and $StorageConfigurationFile) { throw 'Choose explicit development H2 or production MySQL, not both.' }
if (-not $DevelopmentH2) {
    if (-not $StorageConfigurationFile) { $StorageConfigurationFile=Join-Path $dgRoot 'local-data/server-config/storage.json' }
    $StorageConfigurationFile=[IO.Path]::GetFullPath($StorageConfigurationFile)
    if (-not [IO.File]::Exists($StorageConfigurationFile)) { throw 'MySQL / Redis server configuration is required; no H2 fallback is allowed.' }
}
if ($AllowLive -and (-not $ProfileFile -or -not $CredentialsDirectory)) {
    throw 'Live model calls require an explicit verified profile file and server credential directory.'
}
Push-Location $dgRoot
try {
    if (-not $SkipBuild) {
        & (Join-Path $dgRoot 'mvnw.cmd') -B -ntp -pl grading-gateway -am verify
        if ($LASTEXITCODE -ne 0) { throw "Gateway build failed (exit $LASTEXITCODE)." }
    }
    $dgTarget = Join-Path $dgRoot 'grading-gateway/target'
    if (-not (Test-Path -LiteralPath (Join-Path $dgTarget 'classes/app/deskgrade/gateway/GatewayMain.class'))) {
        throw 'Build the gateway before using -SkipBuild.'
    }
    $dgClasspath = (Join-Path $dgTarget 'classes') + ';' + (Join-Path $dgTarget 'lib/*')
    $dgArguments = @('serve','--data-dir',$dgData,'--port',"$Port")
    if ($BootstrapAdmin) { $dgArguments = @('bootstrap-admin','--data-dir',$dgData,'--username',$BootstrapAdmin) }
    elseif ($AllowLive) {
        $dgArguments += @('--profiles',[IO.Path]::GetFullPath($ProfileFile),
            '--credentials-dir',[IO.Path]::GetFullPath($CredentialsDirectory),'--allow-live')
    }
    if ($DevelopmentH2) { $dgArguments += '--development-h2' }
    else { $dgArguments += @('--storage-config',$StorageConfigurationFile) }
    & (Join-Path $env:JAVA_HOME 'bin/java.exe') '-Dfile.encoding=UTF-8' -cp $dgClasspath app.deskgrade.gateway.GatewayMain @dgArguments
    if ($LASTEXITCODE -ne 0) { throw "Gateway command failed (exit $LASTEXITCODE)." }
} finally { Pop-Location }
