[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('generate-synthetic', 'validate', 'run')]
    [string]$Action,
    [string]$JavaHome,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$EvaluationArguments
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
Initialize-DeskGradeJava -JavaHome $JavaHome
$dgRoot = Split-Path $PSScriptRoot
$dgLibraries = Join-Path $dgRoot 'grading-desktop/target/lib'
if (-not (Test-Path -LiteralPath (Join-Path $dgLibraries 'grading-adapters-0.1.0.jar'))) {
    throw 'Build the project with mvnw.cmd -B verify before running evaluation tools.'
}
$dgSocketDirectory = Join-Path $dgRoot 'local-data/socket-temp'
New-Item -ItemType Directory -Force -Path $dgSocketDirectory | Out-Null
& (Join-Path $env:JAVA_HOME 'bin/java.exe') '-Dfile.encoding=UTF-8' "-Djdk.net.unixdomain.tmpdir=$dgSocketDirectory" -cp (Join-Path $dgLibraries '*') app.deskgrade.adapters.model.ModelEvaluationCli $Action @EvaluationArguments
if ($LASTEXITCODE -ne 0) { throw "Evaluation command failed (exit $LASTEXITCODE). No automatic retry is performed." }
