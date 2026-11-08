[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$dgProject = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent)).TrimEnd('\', '/')
$dgExporter = Join-Path $PSScriptRoot 'export-open-source.ps1'
$dgTestRoot = Join-Path $dgProject ('target/open-source-export-tests-' + [Guid]::NewGuid().ToString('N'))
if (-not $dgTestRoot.StartsWith($dgProject + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
    (Test-Path -LiteralPath $dgTestRoot)) { throw 'Unsafe or reused test directory.' }
[IO.Directory]::CreateDirectory($dgTestRoot) | Out-Null
$dgChecks = 0
$dgSkipped = 0

function Write-TestText([string]$Path, [string]$Text) {
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path)) | Out-Null
    [IO.File]::WriteAllText($Path, $Text, (New-Object System.Text.UTF8Encoding($false)))
}

function Assert-Test([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw ('FAIL: ' + $Message) }
    $script:dgChecks++
}

function Assert-Rejected([scriptblock]$Operation, [string]$Expected, [string]$Label) {
    $rejected = $false
    try { & $Operation | Out-Null } catch { $rejected = $_.Exception.Message -match $Expected }
    Assert-Test $rejected $Label
}

function New-TestSource([string]$Name) {
    $source = Join-Path $dgTestRoot $Name
    $files = @('.editorconfig', '.gitattributes', '.gitignore', '.mvn/wrapper/maven-wrapper.properties',
        'pom.xml', 'mvnw', 'mvnw.cmd', 'README.md', 'LICENSE', 'NOTICE',
        'contracts/test.schema.json', 'prompts/GRADING_SYSTEM.md',
        'examples/grade-result.synthetic-scored.json', 'examples/grade-result.synthetic-unable.json',
        'examples/grading-record.synthetic.example.json', 'examples/provider-profile.example.json',
        'examples/real-platform-intake.template.json', 'examples/server-storage.example.json', 'examples/translation-rubric.json',
        'docs/USER_GUIDE.md', 'docs/DEVELOPMENT_LOCAL.md', 'docs/LOCAL_EDITION.md', 'docs/LOCAL_RECORDS.md',
        'docs/LOCAL_MODEL_SETUP.md', 'docs/LOCAL_PERFORMANCE_TIMING_20261021.md',
        'deploy/linux/backup.sh', 'deploy/linux/compose.yaml', 'deploy/linux/deskgrade-backup.cron',
        'deploy/linux/logrotate.conf', 'deploy/linux/nginx.conf',
        'tools/mock-grading-static/index.html', 'tools/mock-grading-static/styles.css', 'tools/mock-grading-static/app.js',
        'tools/mock-grading-static/engine.js', 'tools/mock-grading-static/package.json', 'tools/mock-grading-static/test/engine.test.js')
    foreach ($relative in $files) { Write-TestText (Join-Path $source $relative) 'Synthetic source fixture only.' }
    foreach ($module in @('grading-core', 'grading-adapters', 'grading-desktop', 'grading-gateway', 'tools/mock-grading-site')) {
        Write-TestText (Join-Path $source ($module + '/pom.xml')) '<project />'
        Write-TestText (Join-Path $source ($module + '/src/main/java/Example.java')) 'class Example {}'
    }
    foreach ($entry in @('activate-local-release.ps1', 'common.ps1', 'install-local-shortcuts.ps1',
            'launch-local.ps1', 'local-launch-validation.ps1', 'package-installer.ps1', 'package-smoke.ps1',
            'package-windows.ps1', 'run-dev.ps1', 'run-evaluation.ps1', 'run-gateway.ps1', 'run-mock-platform.ps1',
            'server-credential-migration.ps1', 'summarize-performance.ps1', 'test-activate-local-release.ps1',
            'test-local-launch-validation.ps1', 'test-server-credential-migration.ps1', 'test-summarize-performance.ps1',
            'export-open-source.ps1', 'test-export-open-source.ps1')) { Write-TestText (Join-Path $source ('scripts/' + $entry)) '# Synthetic script fixture.' }
    Write-TestText (Join-Path $source 'deploy/linux/nginx.conf') "server_name 203.0.113.77;`n"
    Write-TestText (Join-Path $source 'grading-desktop/src/test/java/app/deskgrade/desktop/ServiceEndpointTest.java') 'https://203.0.113.77'
    Write-TestText (Join-Path $source 'grading-gateway/src/test/java/app/deskgrade/gateway/GatewayProxyConfigurationTest.java') 'https://203.0.113.77'
    $nativeFixture = @'
                  + Files.createDirectories(
                          Path.of(
                              System.getProperty(
                                  "jdk.net.unixdomain.tmpdir",
                                  "__SOURCE__/local-data/socket-temp")))
                      .toAbsolutePath(),
'@
    Write-TestText (Join-Path $source 'grading-desktop/src/test/java/app/deskgrade/desktop/NativeMockSessionWindowsIT.java') ($nativeFixture.Replace('__SOURCE__', $source.Replace('\', '/')).Replace("`r`n", "`n"))
    Write-TestText (Join-Path $source 'docs/LOCAL_PERFORMANCE_TIMING_20261021.md') "# Timing`n`nGeneric timing description.`n`n## 0.2.4 private historical sample`nPRIVATE-CASE-MUST-NOT-EXPORT`n"
    foreach ($folder in @('tools/mock-grading-site/src/main/resources/answers', 'tools/mock-grading-static/assets/answers')) {
        for ($index = 1; $index -le 6; $index++) {
            Write-TestText (Join-Path $source ($folder + ('/handwritten-answer-{0:00}.png' -f $index))) ('NOT-AN-IMAGE-PRIVATE-' + $index)
        }
    }
    foreach ($relative in @('local-data/records/private.jsonl', 'docs/STATUS.md', 'docs/PRIVATE_REPORT.md',
            'tools/wix3141/tool.zip', 'grading-core/target/private.txt', '.env', 'MANIFEST.sha256')) {
        Write-TestText (Join-Path $source $relative) 'PRIVATE-MUST-NOT-EXPORT'
    }
    return $source
}

$dgSource = New-TestSource 'valid'
$dgPrivateImage = Join-Path $dgSource 'tools/mock-grading-site/src/main/resources/answers/handwritten-answer-01.png'
$dgOriginalHash = (Get-FileHash -LiteralPath $dgPrivateImage).Hash
$dgResult = & $dgExporter -SourceDirectory $dgSource -OutputDirectory 'dist/public-source'
$dgOutput = $dgResult.SourceDirectory
Assert-Test (Test-Path -LiteralPath (Join-Path $dgOutput 'LICENSE')) 'LICENSE is included'
Assert-Test (Test-Path -LiteralPath (Join-Path $dgOutput 'NOTICE')) 'NOTICE is included'
Assert-Test ((Get-FileHash -LiteralPath $dgPrivateImage).Hash -eq $dgOriginalHash) 'Original private fixture remains unchanged'
$dgUnexpected = @(Get-ChildItem -LiteralPath $dgOutput -Recurse -File | Where-Object {
    $_.FullName -match 'PRIVATE_REPORT|STATUS\.md|wix3141|private\.jsonl|\.env$|[/\\]target[/\\]'
    # The test output itself lives under target; compare the export-relative path below instead.
} | Where-Object { $_.FullName.Substring($dgOutput.Length + 1) -match 'PRIVATE_REPORT|STATUS\.md|wix3141|private\.jsonl|\.env$|(^|[/\\])target[/\\]' })
Assert-Test ($dgUnexpected.Count -eq 0) 'Private/runtime source files are excluded'
Assert-Test (-not ([IO.File]::ReadAllText((Join-Path $dgOutput 'docs/LOCAL_PERFORMANCE_TIMING_20261021.md'))).Contains('PRIVATE-CASE')) 'Historical timing case is excluded'
Assert-Test (([IO.File]::ReadAllText((Join-Path $dgOutput 'deploy/linux/nginx.conf'))).Contains('203.0.113.10')) 'Deployment address is replaced'
Assert-Test (-not ([IO.File]::ReadAllText((Join-Path $dgOutput 'grading-desktop/src/test/java/app/deskgrade/desktop/ServiceEndpointTest.java'))).Contains('203.0.113.77')) 'Test address is replaced'
$dgNativeRelative = 'grading-desktop/src/test/java/app/deskgrade/desktop/NativeMockSessionWindowsIT.java'
$dgNativeOutput = [IO.File]::ReadAllText((Join-Path $dgOutput $dgNativeRelative))
Assert-Test ($dgNativeOutput.Contains('"jdk.net.unixdomain.tmpdir", "local-data/socket-temp"')) 'Native fixture has portable, formatter-compatible arguments (LF)'
Assert-Test (-not $dgNativeOutput.Contains($dgSource.Replace('\', '/'))) 'Native fixture contains no private source prefix'
Assert-Test ($dgNativeOutput -notmatch '"jdk\.net\.unixdomain\.tmpdir",[ \t]*\r?\n') 'Short native fallback is not left on a separate argument line'
Assert-Test ((Test-Path -LiteralPath (Join-Path $dgOutput 'docs/USER_GUIDE.html')) -and (Test-Path -LiteralPath (Join-Path $dgOutput 'docs/MANUAL_BINDING_TEST_GUIDE.html'))) 'Packaging HTML documentation is present'

$dgCrlfSource = New-TestSource 'format-crlf'
$dgCrlfPath = Join-Path $dgCrlfSource $dgNativeRelative
$dgCrlfInput = [IO.File]::ReadAllText($dgCrlfPath).Replace("`n", "`r`n")
Write-TestText $dgCrlfPath $dgCrlfInput
$dgCrlfResult = & $dgExporter -SourceDirectory $dgCrlfSource -OutputDirectory 'dist/public-source'
$dgCrlfOutput = [IO.File]::ReadAllText((Join-Path $dgCrlfResult.SourceDirectory $dgNativeRelative))
Assert-Test ($dgCrlfOutput.Contains('"jdk.net.unixdomain.tmpdir", "local-data/socket-temp"')) 'Native fixture has formatter-compatible arguments (CRLF)'
Assert-Test (([IO.File]::ReadAllText($dgCrlfPath)) -eq $dgCrlfInput) 'Formatting normalization does not modify the original source'

$dgCompactSource = New-TestSource 'format-compact'
$dgCompactInput = $dgNativeOutput
Write-TestText (Join-Path $dgCompactSource $dgNativeRelative) $dgCompactInput
$dgCompactResult = & $dgExporter -SourceDirectory $dgCompactSource -OutputDirectory 'dist/public-source'
Assert-Test (([IO.File]::ReadAllText((Join-Path $dgCompactResult.SourceDirectory $dgNativeRelative))) -eq $dgCompactInput) 'An already normalized fallback remains unchanged'

Add-Type -AssemblyName System.Drawing
$dgHashes = New-Object 'System.Collections.Generic.HashSet[string]'
for ($index = 1; $index -le 6; $index++) {
    $name = 'handwritten-answer-{0:00}.png' -f $index
    $first = Join-Path $dgOutput ('tools/mock-grading-site/src/main/resources/answers/' + $name)
    $second = Join-Path $dgOutput ('tools/mock-grading-static/assets/answers/' + $name)
    Assert-Test ((Get-FileHash -LiteralPath $first).Hash -eq (Get-FileHash -LiteralPath $second).Hash) ('Both synthetic copies match: ' + $index)
    $image = [Drawing.Image]::FromFile($first)
    try { Assert-Test ($image.Width -eq 828 -and $image.Height -eq 209) ('Synthetic image dimensions: ' + $index) } finally { $image.Dispose() }
    [void]$dgHashes.Add((Get-FileHash -LiteralPath $first).Hash)
}
Assert-Test ($dgHashes.Count -eq 6) 'All synthetic images are different'
$dgManifestLines = @(Get-Content -LiteralPath (Join-Path $dgOutput 'MANIFEST.sha256'))
foreach ($line in $dgManifestLines) {
    if ($line -notmatch '^([a-f0-9]{64})  (.+)$') { throw 'Malformed output manifest.' }
    $expected = $Matches[1]
    $path = Join-Path $dgOutput $Matches[2]
    if ((Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant() -ne $expected) { throw 'Output manifest hash mismatch.' }
}
Assert-Test ($dgManifestLines.Count -eq @(Get-ChildItem -LiteralPath $dgOutput -Recurse -File).Count - 1) 'Manifest covers the public tree exactly'
$dgReport = Get-Content -LiteralPath $dgResult.Report -Raw | ConvertFrom-Json
Assert-Test ($dgReport.status -eq 'PASS' -and $dgReport.originalAnswerImagesReadOrCopied -eq 0 -and -not $dgReport.buildExecuted) 'Export report does not overclaim verification'
$dgManifestBefore = (Get-FileHash -LiteralPath (Join-Path $dgOutput 'MANIFEST.sha256')).Hash
Assert-Rejected { & $dgExporter -SourceDirectory $dgSource -OutputDirectory 'dist/public-source' } 'already exists' 'Existing export cannot be overwritten'
Assert-Test ((Get-FileHash -LiteralPath (Join-Path $dgOutput 'MANIFEST.sha256')).Hash -eq $dgManifestBefore) 'Rejected overwrite preserves existing files'
Assert-Rejected { & $dgExporter -SourceDirectory $dgSource -OutputDirectory 'outside-dist' } 'new child' 'Export outside dist is refused'
Assert-Rejected { & $dgExporter -SourceDirectory $dgSource -OutputDirectory 'dist/../outside-dist' } 'new child' 'Path traversal is refused'

$dgMissing = New-TestSource 'missing'
$dgMissingLicense = Join-Path $dgMissing 'LICENSE'
Remove-Item -LiteralPath $dgMissingLicense
Assert-Rejected { & $dgExporter -SourceDirectory $dgMissing -OutputDirectory 'dist/public-source' } 'Required public source is missing' 'Missing license refuses export'
Assert-Test (-not (Test-Path -LiteralPath (Join-Path $dgMissing 'dist/public-source'))) 'Preflight failure creates no public directory'
$dgSecret = New-TestSource 'secret'
Write-TestText (Join-Path $dgSecret 'README.md') ('sk-' + ('z' * 36))
Assert-Rejected { & $dgExporter -SourceDirectory $dgSecret -OutputDirectory 'dist/public-source' } 'Potential secret' 'Token-like source is rejected without printing it'
$dgPersonal = New-TestSource 'personal'
Write-TestText (Join-Path $dgPersonal 'README.md') ('C:' + '\Users\' + 'PrivateOwner\notes.txt')
Assert-Rejected { & $dgExporter -SourceDirectory $dgPersonal -OutputDirectory 'dist/public-source' } 'Personal filesystem path' 'Personal path is rejected'
$dgBinary = New-TestSource 'binary'
Write-TestText (Join-Path $dgBinary 'grading-core/src/main/resources/unreviewed.png') 'UNREVIEWED'
Assert-Rejected { & $dgExporter -SourceDirectory $dgBinary -OutputDirectory 'dist/public-source' } 'Unreviewed file type' 'Unexpected binary source is rejected'
$dgOwner = New-TestSource 'owner'
Write-TestText (Join-Path $dgOwner 'README.md') 'Private endpoint 203.0.113.77'
Assert-Rejected { & $dgExporter -SourceDirectory $dgOwner -OutputDirectory 'dist/public-source' } 'Owner service address' 'Unreviewed owner host occurrence is rejected'

$dgLinks = New-TestSource 'links'
$dgLinkTarget = Join-Path $dgTestRoot 'link-target'
[IO.Directory]::CreateDirectory($dgLinkTarget) | Out-Null
Write-TestText (Join-Path $dgLinkTarget 'secret.txt') 'SHOULD-NOT-BE-READ'
try {
    New-Item -ItemType Junction -Path (Join-Path $dgLinks 'grading-core/src/private-link') -Target $dgLinkTarget | Out-Null
    Assert-Rejected { & $dgExporter -SourceDirectory $dgLinks -OutputDirectory 'dist/public-source' } 'symbolic link|junction' 'Included source junction is rejected'
    [IO.Directory]::CreateDirectory((Join-Path $dgSource 'dist')) | Out-Null
    New-Item -ItemType Junction -Path (Join-Path $dgSource 'dist/linked-destination') -Target $dgLinkTarget | Out-Null
    Assert-Rejected { & $dgExporter -SourceDirectory $dgSource -OutputDirectory 'dist/linked-destination/public-source' } 'symbolic link|junction' 'Destination junction is rejected'
} catch {
    if ($_.Exception.Message -like 'FAIL:*') { throw }
    $dgSkipped++
    Write-Host 'Junction setup unavailable; link-specific tests skipped on this host.'
}
Write-Host ('Open-source export tests PASS: {0} checks; {1} host-dependent groups skipped.' -f $dgChecks, $dgSkipped)
Write-Host ('Synthetic test artifacts retained under: ' + $dgTestRoot)
