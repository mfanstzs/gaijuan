$ErrorActionPreference = 'Stop'

function Initialize-DeskGradeJava {
    param([string]$JavaHome)

    $dgCandidates = @()
    if ($JavaHome) {
        $dgCandidates = @($JavaHome)
    } else {
        if ($env:JAVA_HOME) { $dgCandidates += $env:JAVA_HOME }
        $dgCandidates += (Join-Path $env:USERPROFILE '.jdks\jdk-21.0.12.1+1')
        $dgJavaCommand = Get-Command java -ErrorAction SilentlyContinue
        if ($dgJavaCommand) { $dgCandidates += (Split-Path (Split-Path $dgJavaCommand.Source)) }
    }
    foreach ($dgCandidate in $dgCandidates) {
        $dgRelease = Join-Path $dgCandidate 'release'
        $dgJava = Join-Path $dgCandidate 'bin\java.exe'
        $dgJavac = Join-Path $dgCandidate 'bin\javac.exe'
        if ((Test-Path -LiteralPath $dgRelease) -and (Test-Path -LiteralPath $dgJava) -and (Test-Path -LiteralPath $dgJavac)) {
            $dgVersionLine = Get-Content -LiteralPath $dgRelease | Where-Object { $_ -match '^JAVA_VERSION="21(?:\.|\")' }
            if ($dgVersionLine) {
                $env:JAVA_HOME = (Resolve-Path -LiteralPath $dgCandidate).Path
                return
            }
        }
    }
    throw 'JDK 21 is required. Set JAVA_HOME or pass -JavaHome. See docs/DEVELOPMENT.md.'
}
