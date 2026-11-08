[CmdletBinding()]
param(
    [string]$OutputDirectory,
    [string]$SourceDirectory = (Split-Path $PSScriptRoot -Parent)
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$dgUtf8 = New-Object System.Text.UTF8Encoding($false, $true)
$dgSource = [IO.Path]::GetFullPath($SourceDirectory).TrimEnd('\', '/')

function Assert-DgPlainPath([string]$Path) {
    $ancestor = [IO.Path]::GetFullPath($Path)
    while ($ancestor) {
        if (Test-Path -LiteralPath $ancestor) {
            if (((Get-Item -LiteralPath $ancestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw 'Source/export paths must not traverse symbolic links or junctions.'
            }
        }
        $ancestor = [IO.Path]::GetDirectoryName($ancestor)
    }
}

function Get-DgTreeFiles([string]$Directory) {
    foreach ($entry in @(Get-ChildItem -LiteralPath $Directory -Force)) {
        if (($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'An included source directory contains a symbolic link or junction.'
        }
        if ($entry.PSIsContainer) { Get-DgTreeFiles $entry.FullName } else { $entry }
    }
}

function Get-DgRelative([string]$Path) {
    return $Path.Substring($dgSource.Length + 1).Replace('\', '/')
}

function Read-DgText([string]$Path) {
    return [IO.File]::ReadAllText($Path, $dgUtf8)
}

function Write-DgText([string]$Path, [string]$Value) {
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path)) | Out-Null
    $encoding = $dgUtf8
    if ([IO.Path]::GetExtension($Path) -eq '.ps1') { $encoding = New-Object System.Text.UTF8Encoding($true, $true) }
    [IO.File]::WriteAllText($Path, $Value, $encoding)
}

function Add-DgRequired([string]$Relative) {
    $path = Join-Path $dgSource $Relative
    Assert-DgPlainPath $path
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required public source is missing: $Relative" }
    if (-not $dgIncluded.Add($Relative.Replace('\', '/'))) { throw "Duplicate source entry: $Relative" }
}

function Add-DgTree([string]$Relative, [string[]]$Extensions) {
    $path = Join-Path $dgSource $Relative
    Assert-DgPlainPath $path
    if (-not (Test-Path -LiteralPath $path -PathType Container)) { throw "Required source directory is missing: $Relative" }
    foreach ($file in @(Get-DgTreeFiles $path)) {
        $relativeFile = Get-DgRelative $file.FullName
        if ($relativeFile -match '^tools/mock-grading-site/src/main/resources/answers/handwritten-answer-0[1-6]\.png$') {
            # Never read/copy supplied answer images. Public fixtures are generated from scratch.
            continue
        }
        if ($file.Extension -notin $Extensions) { throw "Unreviewed file type in included source: $relativeFile" }
        Add-DgRequired $relativeFile
    }
}

Assert-DgPlainPath $dgSource
if (-not (Test-Path -LiteralPath (Join-Path $dgSource 'pom.xml') -PathType Leaf)) { throw 'SourceDirectory must be the project root.' }
$dgDist = Join-Path $dgSource 'dist'
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $dgDist ('DeskGrade-open-source-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
} elseif (-not [IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory = Join-Path $dgSource $OutputDirectory
}
$dgOutput = [IO.Path]::GetFullPath($OutputDirectory).TrimEnd('\', '/')
if (-not $dgOutput.StartsWith($dgDist + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'OutputDirectory must be a new child of the source project dist directory.'
}
Assert-DgPlainPath $dgOutput
if (Test-Path -LiteralPath $dgOutput) { throw 'OutputDirectory already exists; it will not be overwritten.' }
$dgReportPath = $dgOutput + '.report.json'
if (Test-Path -LiteralPath $dgReportPath) { throw 'Export report already exists; choose another output directory.' }

$dgIncluded = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
foreach ($entry in @('.editorconfig', '.gitattributes', '.gitignore', '.mvn/wrapper/maven-wrapper.properties',
        'pom.xml', 'mvnw', 'mvnw.cmd', 'README.md', 'LICENSE', 'NOTICE')) { Add-DgRequired $entry }
$dgModules = @('grading-core', 'grading-adapters', 'grading-desktop', 'grading-gateway', 'tools/mock-grading-site')
foreach ($module in $dgModules) {
    Add-DgRequired ($module + '/pom.xml')
    Add-DgTree ($module + '/src') @('.java', '.xml', '.properties', '.json', '.md', '.sql', '.html', '.css', '.js', '.cjs', '.txt')
}
Add-DgTree 'contracts' @('.json', '.md')
Add-DgTree 'prompts' @('.md')
foreach ($entry in @('grade-result.synthetic-scored.json', 'grade-result.synthetic-unable.json',
        'grading-record.synthetic.example.json', 'provider-profile.example.json',
        'real-platform-intake.template.json', 'server-storage.example.json', 'translation-rubric.json')) {
    Add-DgRequired ('examples/' + $entry)
}
foreach ($entry in @('activate-local-release.ps1', 'common.ps1', 'install-local-shortcuts.ps1',
        'launch-local.ps1', 'local-launch-validation.ps1', 'package-installer.ps1', 'package-smoke.ps1',
        'package-windows.ps1', 'run-dev.ps1', 'run-evaluation.ps1', 'run-gateway.ps1', 'run-mock-platform.ps1',
        'server-credential-migration.ps1', 'summarize-performance.ps1', 'test-activate-local-release.ps1',
        'test-local-launch-validation.ps1', 'test-server-credential-migration.ps1', 'test-summarize-performance.ps1',
        'export-open-source.ps1', 'test-export-open-source.ps1')) { Add-DgRequired ('scripts/' + $entry) }
foreach ($entry in @('USER_GUIDE.md', 'DEVELOPMENT_LOCAL.md', 'LOCAL_EDITION.md', 'LOCAL_RECORDS.md',
        'LOCAL_MODEL_SETUP.md', 'LOCAL_PERFORMANCE_TIMING_20261021.md')) { Add-DgRequired ('docs/' + $entry) }
foreach ($entry in @('backup.sh', 'compose.yaml', 'deskgrade-backup.cron', 'logrotate.conf', 'nginx.conf')) {
    Add-DgRequired ('deploy/linux/' + $entry)
}
foreach ($entry in @('index.html', 'styles.css', 'app.js', 'engine.js', 'package.json', 'test/engine.test.js')) {
    Add-DgRequired ('tools/mock-grading-static/' + $entry)
}
$dgIdeaRun = '.run/DeskGrade Desktop (Current Source).run.xml'
if (Test-Path -LiteralPath (Join-Path $dgSource $dgIdeaRun) -PathType Leaf) { Add-DgRequired $dgIdeaRun }

$dgNginx = Read-DgText (Join-Path $dgSource 'deploy/linux/nginx.conf')
$dgHostMatch = [regex]::Match($dgNginx, '(?m)^\s*server_name\s+([0-9.]+)\s*;')
$dgOwnerHost = if ($dgHostMatch.Success) { $dgHostMatch.Groups[1].Value } else { '' }
$dgPublicAddress = '203.0.113.10'
$dgPrivateName = '(?i)(^|/)(local-data|private-fixtures|runtime-data|target|dist|captures|screenshots|logs|\.git|\.idea|\.vscode)(/|$)|(?:\.dpapi|\.mv\.db|\.trace\.db|\.lock\.db|\.jsonl|\.log|\.pem|\.pfx|\.p12|\.key|\.jks|\.keystore)$|(^|/)\.env($|\.)'
$dgSecretPattern = '(?i)\b(?:sk-(?:proj-)?[a-z0-9_-]{20,}|ark-[a-z0-9_-]{20,})\b|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'
$dgPersonalPath = '(?i)[A-Z]:[/\\]+Users[/\\]+(?!Public(?:[/\\]|$)|Default(?:[/\\]|$)|example-user(?:[/\\]|$)|<)[A-Za-z0-9_.-]+[/\\]'
$dgNormalized = New-Object 'System.Collections.Generic.Dictionary[string,string]' ([StringComparer]::OrdinalIgnoreCase)
foreach ($relative in @($dgIncluded | Sort-Object)) {
    if ($relative -match $dgPrivateName) { throw "Private filename in source allowlist: $relative" }
    $content = Read-DgText (Join-Path $dgSource $relative)
    if ($dgOwnerHost -and $dgOwnerHost -ne $dgPublicAddress -and $relative -in @(
            'deploy/linux/nginx.conf',
            'grading-desktop/src/test/java/app/deskgrade/desktop/ServiceEndpointTest.java',
            'grading-gateway/src/test/java/app/deskgrade/gateway/GatewayProxyConfigurationTest.java')) {
        $content = $content.Replace($dgOwnerHost, $dgPublicAddress)
    }
    if ($relative -eq 'grading-desktop/src/test/java/app/deskgrade/desktop/NativeMockSessionWindowsIT.java') {
        $content = $content.Replace($dgSource.Replace('\', '/') + '/local-data/socket-temp', 'local-data/socket-temp')
        # The shorter portable fallback fits on one argument line. Keep the
        # exported source in the same form required by google-java-format.
        $content = [regex]::Replace($content,
            '("jdk\.net\.unixdomain\.tmpdir",)[ \t]*\r?\n[ \t]*("local-data/socket-temp")', '$1 $2')
    }
    if ($relative -eq 'docs/LOCAL_PERFORMANCE_TIMING_20261021.md') {
        $content = [regex]::Split($content, '(?m)^## 0\.2\.4 ')[0]
        $content = [regex]::Replace($content, '(?m)^0\.2\.3[^\r\n]*\r?\n', '')
    }
    if ($content -match $dgSecretPattern) { throw "Potential secret in public source; content withheld: $relative" }
    if ($content -match $dgPersonalPath) { throw "Personal filesystem path in public source; content withheld: $relative" }
    if ($dgOwnerHost -and $dgOwnerHost -ne $dgPublicAddress -and $content.Contains($dgOwnerHost)) {
        throw "Owner service address remains in public source: $relative"
    }
    if ($content.Contains($dgSource) -or $content.Contains($dgSource.Replace('\', '/'))) {
        throw "Private workspace path remains in public source: $relative"
    }
    $dgNormalized.Add($relative, $content)
}

# All allowlisted input has passed the preflight. No private answer image was opened.
[IO.Directory]::CreateDirectory($dgOutput) | Out-Null
foreach ($relative in @($dgIncluded | Sort-Object)) { Write-DgText (Join-Path $dgOutput $relative) $dgNormalized[$relative] }

Add-Type -AssemblyName System.Drawing
$dgFixtureHashes = New-Object 'System.Collections.Generic.HashSet[string]'
for ($index = 1; $index -le 6; $index++) {
    $bitmap = New-Object System.Drawing.Bitmap(828, 209)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $pen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(55, 70, 90), 2)
    $font = New-Object System.Drawing.Font('Arial', 16, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
    $small = New-Object System.Drawing.Font('Arial', 12, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
    $stream = New-Object IO.MemoryStream
    try {
        $graphics.Clear([System.Drawing.Color]::White)
        $graphics.DrawRectangle($pen, 10, 10, 807, 188)
        $graphics.DrawString('SYNTHETIC TEST FIXTURE - NOT A STUDENT ANSWER', $small, [System.Drawing.Brushes]::DarkSlateGray, 24, 23)
        $graphics.DrawString(('Example answer {0} of 6' -f $index), $font, [System.Drawing.Brushes]::Black, 24, 57)
        $graphics.DrawString(('Test-only statement: condition {0} supports defence, not attack.' -f $index), $font, [System.Drawing.Brushes]::Black, 24, 94)
        $graphics.DrawLine($pen, 24, 131, 800, 131)
        $graphics.DrawString('Artificial text and geometry. No scanned or personal content.', $small, [System.Drawing.Brushes]::DarkSlateGray, 24, 153)
        $bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
        $bytes = $stream.ToArray()
        foreach ($folder in @('tools/mock-grading-site/src/main/resources/answers', 'tools/mock-grading-static/assets/answers')) {
            $path = Join-Path $dgOutput ($folder + ('/handwritten-answer-{0:00}.png' -f $index))
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path)) | Out-Null
            [IO.File]::WriteAllBytes($path, $bytes)
        }
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
        if (-not $dgFixtureHashes.Add($hash)) { throw 'Generated fixtures must be distinguishable.' }
    } finally { $stream.Dispose(); $small.Dispose(); $font.Dispose(); $pen.Dispose(); $graphics.Dispose(); $bitmap.Dispose() }
}

$dgFixtureNotice = @'
# Synthetic grading fixtures

All six PNG files in this public source tree are newly generated text and line drawings.
They contain no scanned papers, student handwriting, names, or private source imagery.
The historical `handwritten-six` option and filenames are retained only for compatibility.
Both fixture directories contain identical copies; images are 828 by 209 pixels and have distinct content.
These fixtures test workflow behavior, not OCR or real-world grading accuracy.
The generated fixture material is distributed under the root Apache-2.0 license.
'@
Write-DgText (Join-Path $dgOutput 'tools/mock-grading-static/README.md') ($dgFixtureNotice + "`nRun the static tests with ``node --test test/*.test.js`` in this directory.`n")
Write-DgText (Join-Path $dgOutput 'tools/mock-grading-site/SYNTHETIC_FIXTURES.md') $dgFixtureNotice
$dgGuide = [Net.WebUtility]::HtmlEncode($dgNormalized['docs/USER_GUIDE.md'])
$dgHtml = '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>DeskGrade User Guide</title><body style="max-width:960px;margin:2em auto;padding:0 1em;font-family:system-ui"><pre style="white-space:pre-wrap;overflow-wrap:anywhere;font-family:inherit">' + $dgGuide + '</pre></body></html>'
Write-DgText (Join-Path $dgOutput 'docs/USER_GUIDE.html') $dgHtml
Write-DgText (Join-Path $dgOutput 'docs/MANUAL_BINDING_TEST_GUIDE.html') $dgHtml
Write-DgText (Join-Path $dgOutput 'deploy/linux/README.md') "# Optional legacy server templates`n`nThe default desktop is local BYOK and does not require these services. The template address 203.0.113.10 is reserved for documentation; replace it and supply your own TLS certificates and secrets before an authorized deployment. No original production server configuration or credentials are included.`n"
Write-DgText (Join-Path $dgOutput 'OPEN_SOURCE_EXPORT.md') @'
# Public source export

This tree was created using a source allowlist. Runtime data, private delivery/analysis documents,
downloaded tool binaries and original answer images were excluded. The six demonstration PNGs
were drawn from scratch and use historical filenames only for compatibility. Server examples
use a documentation address, and current-user settings/credentials are never copied.

The export performs bounded filename, reparse-point and common secret/path checks. Passing these
checks is not proof that every possible secret, legal issue or vulnerability has been identified.
Review third-party licenses and the source before publication. No Git repository was initialized,
no remote was created, and nothing was uploaded. Build/test the exported tree separately.

See README.md, LICENSE, NOTICE and docs/DEVELOPMENT_LOCAL.md for use and development.
'@

$dgExportFiles = @(Get-DgTreeFiles $dgOutput | Sort-Object FullName)
$dgManifest = New-Object 'System.Collections.Generic.List[string]'
foreach ($file in $dgExportFiles) {
    $relative = $file.FullName.Substring($dgOutput.Length + 1).Replace('\', '/')
    if ($relative -match $dgPrivateName) { throw "Private output file detected: $relative" }
    if ($file.Extension -eq '.png') {
        if ($relative -notmatch '^tools/mock-grading-(site/src/main/resources|static/assets)/answers/handwritten-answer-0[1-6]\.png$') { throw 'Unexpected output image.' }
    } else {
        $text = Read-DgText $file.FullName
        if ($text -match $dgSecretPattern -or $text -match $dgPersonalPath) { throw "Final text scan failed; content withheld: $relative" }
    }
    $dgManifest.Add((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $relative)
}
$dgManifestPath = Join-Path $dgOutput 'MANIFEST.sha256'
Write-DgText $dgManifestPath (($dgManifest -join "`n") + "`n")
$dgReport = [ordered]@{
    status = 'PASS'; sourceFiles = $dgIncluded.Count; exportedFiles = $dgExportFiles.Count + 1
    manifestEntries = $dgManifest.Count; manifestSha256 = (Get-FileHash -LiteralPath $dgManifestPath).Hash.ToLowerInvariant()
    generatedSyntheticImages = 12; originalAnswerImagesReadOrCopied = 0; privateRuntimeDataCopied = $false
    historicalPrivateDocsCopied = $false; gitInitialized = $false; uploaded = $false; buildExecuted = $false
    limitations = @('Pattern scanning is not an exhaustive secret or vulnerability audit.', 'Build and tests must be run separately in the exported source tree.')
}
Write-DgText $dgReportPath (($dgReport | ConvertTo-Json -Depth 4) + "`n")
Write-Host ('Public source export PASS: {0} files; 12 synthetic images; build remains separate.' -f $dgReport.exportedFiles)
[pscustomobject]@{ SourceDirectory = $dgOutput; Report = $dgReportPath; ManifestSha256 = $dgReport.manifestSha256 }
