param(
    [string]$JavaHome,
    [ValidateRange(1, 65535)][int]$Port = 8765,
    [ValidateRange(1, 10000)][int]$Count = 20,
    [ValidateSet('submit-and-advance', 'submit-then-next')][string]$Mode = 'submit-and-advance',
    [ValidateSet('synthetic', 'handwritten-six', 'handwritten-five')][string]$AnswerSet = 'synthetic',
    [switch]$ServerOnly,
    [switch]$TestLedger,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
Initialize-DeskGradeJava -JavaHome $JavaHome
$dgRoot = Split-Path $PSScriptRoot
Push-Location $dgRoot
try {
    if (-not $SkipBuild) {
        & (Join-Path $dgRoot 'mvnw.cmd') -B -ntp -pl tools/mock-grading-site -am verify
        if ($LASTEXITCODE -ne 0) { throw "Mock platform build failed (exit $LASTEXITCODE)." }
    }
    if ($AnswerSet -in @('handwritten-six', 'handwritten-five')) {
        if ($PSBoundParameters.ContainsKey('Count') -and $Count -ne 6) {
            throw 'The handwritten-six answer set contains exactly six images.'
        }
        $Count = 6
        $AnswerSet = 'handwritten-six'
    }
    $dgToolTarget = Join-Path $dgRoot 'tools\mock-grading-site\target'
    $dgClasspath = (Join-Path $dgToolTarget 'classes') + ';' + (Join-Path $dgToolTarget 'lib\*')
    $dgModulePath = Join-Path $dgToolTarget 'lib'
    $dgArguments = @("--port=$Port", "--count=$Count", "--mode=$Mode", "--answer-set=$AnswerSet")
    if (-not $ServerOnly) { $dgArguments += '--window' }
    if ($TestLedger) { $dgArguments += '--test-ledger' }
    $dgNetworkTemp = Join-Path $dgRoot 'local-data\socket-temp'
    New-Item -ItemType Directory -Force -Path $dgNetworkTemp | Out-Null
    & (Join-Path $env:JAVA_HOME 'bin\java.exe') '-Dfile.encoding=UTF-8' "-Djdk.net.unixdomain.tmpdir=$dgNetworkTemp" --module-path $dgModulePath --add-modules javafx.controls,javafx.web -cp $dgClasspath app.deskgrade.mocksite.Launcher @dgArguments
    if ($LASTEXITCODE -ne 0) { throw "Mock platform exited with code $LASTEXITCODE." }
} finally {
    Pop-Location
}
