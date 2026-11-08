param(
    [string]$JavaHome,
    [switch]$BuildOnly
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
Initialize-DeskGradeJava -JavaHome $JavaHome
$dgRoot = Split-Path $PSScriptRoot
Push-Location $dgRoot
try {
    & (Join-Path $dgRoot 'mvnw.cmd') -B -ntp verify
    if ($LASTEXITCODE -ne 0) { throw "DeskGrade build failed (exit $LASTEXITCODE)." }
    if (-not $BuildOnly) {
        $dgClasspath = (Join-Path $dgRoot 'grading-desktop\target\classes') + ';' + (Join-Path $dgRoot 'grading-desktop\target\lib\*')
        $dgModulePath = Join-Path $dgRoot 'grading-desktop\target\lib'
        $dgNetworkTemp = Join-Path $dgRoot 'local-data\socket-temp'
        New-Item -ItemType Directory -Force -Path $dgNetworkTemp | Out-Null
        & (Join-Path $env:JAVA_HOME 'bin\java.exe') '-Dfile.encoding=UTF-8' "-Djdk.net.unixdomain.tmpdir=$dgNetworkTemp" --module-path $dgModulePath --add-modules javafx.controls,javafx.web -cp $dgClasspath app.deskgrade.desktop.Launcher
        if ($LASTEXITCODE -ne 0) { throw "DeskGrade exited with code $LASTEXITCODE." }
    }
} finally {
    Pop-Location
}
