[CmdletBinding()]
param(
    [string]$JavaHome,
    [string]$ReleaseDirectory,
    [string]$MavenRepository,
    [string]$ServiceUrl,
    [string]$AppVersion,
    [switch]$SkipBuild,
    [switch]$DoNotActivateLocalLauncher
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'common.ps1')
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Write-DgUtf8([string]$Path, [string]$Value) {
    [IO.File]::WriteAllText($Path, $Value, [Text.UTF8Encoding]::new($false))
}

function Get-DgWindowsAppVersion([string]$Value) {
    # Windows Installer product versions have three numeric fields, bounded at 255.255.65535.
    if ($Value -cnotmatch '^(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,4})$') {
        throw 'AppVersion must have three numeric components without leading zeroes (maximum 255.255.65535).'
    }
    $parts = $Value.Split('.')
    if ([int]$parts[0] -gt 255 -or [int]$parts[1] -gt 255 -or [int]$parts[2] -gt 65535) {
        throw 'AppVersion exceeds the Windows Installer maximum 255.255.65535.'
    }
    return $Value
}

function Set-DgBundledLocalEdition([string]$JarPath) {
    $archive = [IO.Compression.ZipFile]::Open($JarPath, [IO.Compression.ZipArchiveMode]::Update)
    try {
        $previous = $archive.GetEntry('app/deskgrade/desktop/service-endpoint.properties')
        if ($null -ne $previous) { $previous.Delete() }
        $resource = 'app/deskgrade/desktop/local-edition.properties'
        $previous = $archive.GetEntry($resource)
        if ($null -ne $previous) { $previous.Delete() }
        $entry = $archive.CreateEntry($resource, [IO.Compression.CompressionLevel]::Optimal)
        $stream = $entry.Open()
        try {
            $bytes = [Text.Encoding]::ASCII.GetBytes("schemaVersion=1`nedition=LOCAL`n")
            $stream.Write($bytes, 0, $bytes.Length)
        } finally { $stream.Dispose() }
    } finally { $archive.Dispose() }
}

function Assert-DgChildPath([string]$Path, [string]$Parent) {
    $full = [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
    $base = [IO.Path]::GetFullPath($Parent).TrimEnd('\', '/')
    if (-not $full.StartsWith($base + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Packaging target must be a child of the workspace dist directory.'
    }
    # Reject junctions/symlinks in any existing ancestor, including dist itself.
    $ancestor = $full
    while ($ancestor) {
        if (Test-Path -LiteralPath $ancestor) {
            $item = Get-Item -LiteralPath $ancestor -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw 'Packaging paths may not traverse a junction or symbolic link.'
            }
        }
        $ancestor = [IO.Path]::GetDirectoryName($ancestor)
    }
    return $full
}

function Invoke-DgBuildTool([string]$Tool, [string[]]$ToolArguments, [string]$LogPath) {
    # Native stderr stays in the build log, including under Windows PowerShell 5.1.
    $oldPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $Tool @ToolArguments *> $LogPath
        $toolExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $oldPreference }
    if ($toolExit -ne 0) { throw "Build tool failed (exit $toolExit). Inspect the local build log: $LogPath" }
}

function Get-DgLicenseMetadata([string]$Group, [string]$Artifact, [string]$Version, [string]$Repository, [string]$Destination) {
    $licenses = @()
    $pomNames = @()
    for ($depth = 0; $depth -lt 8; $depth++) {
        if (($Group + $Artifact + $Version) -notmatch '^[A-Za-z0-9._-]+$') { throw 'Invalid dependency coordinates in POM.' }
        $pomName = "$Artifact-$Version.pom"
        $pom = Join-Path $Repository ($Group.Replace('.', '/') + "/$Artifact/$Version/$pomName")
        if (-not (Test-Path -LiteralPath $pom -PathType Leaf)) { throw "Dependency license metadata is missing: $Group`:$Artifact`:$Version" }
        [xml]$xml = [IO.File]::ReadAllText($pom)
        $savedName = "$Group.$pomName"
        Copy-Item -LiteralPath $pom -Destination (Join-Path $Destination $savedName)
        $pomNames += $savedName
        $licenseNodes = $xml.SelectNodes('/*[local-name()="project"]/*[local-name()="licenses"]/*[local-name()="license"]')
        foreach ($license in $licenseNodes) {
            $nameNode = $license.SelectSingleNode('*[local-name()="name"]')
            $urlNode = $license.SelectSingleNode('*[local-name()="url"]')
            $licenses += [ordered]@{ name = $nameNode.InnerText; url = $(if ($urlNode) { $urlNode.InnerText } else { '' }) }
        }
        if ($licenses.Count -gt 0) { break }
        $parent = $xml.SelectSingleNode('/*[local-name()="project"]/*[local-name()="parent"]')
        if (-not $parent) { break }
        $Group = $parent.SelectSingleNode('*[local-name()="groupId"]').InnerText
        $Artifact = $parent.SelectSingleNode('*[local-name()="artifactId"]').InnerText
        $Version = $parent.SelectSingleNode('*[local-name()="version"]').InnerText
    }
    if ($licenses.Count -eq 0) { throw "No declared upstream license found for $Artifact." }
    return [ordered]@{ licenses = $licenses; pomFiles = $pomNames }
}

function Test-DgCredentialText([string]$Text) {
    return ($Text -match '(?<![A-Za-z0-9])sk-(?:ws-|sp-)?[A-Za-z0-9._-]{20,}' -or
        $Text -match '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----' -or
        $Text -match '(?i)Authorization\s*[:=]\s*["'']?Bearer\s+[A-Za-z0-9._-]{24,}')
}

function Test-DgCredentialStream([IO.Stream]$Stream) {
    # The credential markers are ASCII. Preserve a bounded tail so a marker split across two
    # chunks is still detected without materializing a large native binary in memory.
    $buffer = [byte[]]::new(1MB)
    $tail = ''
    while (($read = $Stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
        $text = $tail + [Text.Encoding]::ASCII.GetString($buffer, 0, $read)
        if (Test-DgCredentialText $text) { return $true }
        $tailLength = [Math]::Min(256, $text.Length)
        $tail = $text.Substring($text.Length - $tailLength, $tailLength)
    }
    return $false
}

function Test-DgReleaseJar([string]$Path, [string]$NoticeDirectory, [string]$JavaFxVersion) {
    $zip = [IO.Compression.ZipFile]::OpenRead($Path)
    $entryCount = 0
    $expandedBytes = [long]0
    $notices = @()
    $streamedLargeEntries = 0
    $archiveName = [IO.Path]::GetFileName($Path)
    $webArchiveName = "javafx-web-$JavaFxVersion-win.jar"
    $webKitEntryCount = 0
    try {
        foreach ($entry in $zip.Entries) {
            $entryCount++
            $expandedBytes += $entry.Length
            $entryName = $entry.FullName.Replace('\', '/')
            $isReviewedWebKitBinary =
                [String]::Equals($archiveName, $webArchiveName, [StringComparison]::Ordinal) -and
                [String]::Equals($entryName, 'jfxwebkit.dll', [StringComparison]::Ordinal)
            if ($isReviewedWebKitBinary) { $webKitEntryCount++ }
            # JavaFX Web 21 ships one roughly 96 MB native WebKit DLL. Only this exact reviewed
            # archive entry receives a larger bound; every other archive entry keeps 32 MB.
            $entryLimit = if ($isReviewedWebKitBinary) { 128MB } else { 32MB }
            if ($entryCount -gt 100000 -or $expandedBytes -gt 512MB -or $entry.Length -gt $entryLimit) {
                throw 'Application archive exceeds the bounded release scanner limits.'
            }
            if ($entryName -match '(^/|(^|/)\.\.(/|$)|:)' -or
                $entryName -match '(?i)(^|/)(app/deskgrade/mocksite|org/junit|test-fixtures|private-fixtures|captures|runtime-data|local-data)/' -or
                $entryName -match '(?i)^app/deskgrade/.*(Test|WindowsIT|LiveVisionApiIT)(\$[^/]*)?\.class$' -or
                $entryName -match '(?i)(\.dpapi|\.mv\.db|\.trace\.db|\.lock\.db|\.pem|\.p12|\.pfx|\.key|\.jks|\.keystore|\.log|\.jsonl)$' -or
                $entryName -match '(?i)(^|/)\.env($|\.)') {
                throw "Disallowed release archive entry in $([IO.Path]::GetFileName($Path)); content is not printed."
            }
            if ($entry.Length -eq 0) { continue }
            if ($isReviewedWebKitBinary) {
                $streamedLargeEntries++
                $stream = $entry.Open()
                try {
                    if (Test-DgCredentialStream $stream) {
                        throw "Potential credential material found in $archiveName; content is not printed."
                    }
                } finally { $stream.Dispose() }
                continue
            }
            $stream = $entry.Open()
            $buffer = [IO.MemoryStream]::new()
            try { $stream.CopyTo($buffer); $bytes = $buffer.ToArray() } finally { $stream.Dispose(); $buffer.Dispose() }
            # Includes class constant pools. Never print matched text or credentials.
            $text = [Text.Encoding]::UTF8.GetString($bytes)
            if (Test-DgCredentialText $text) {
                throw "Potential credential material found in $archiveName; content is not printed."
            }
            if ([IO.Path]::GetFileName($Path) -match '^grading-desktop-' -and
                $text.Contains('deskgrade.developerMode')) {
                throw 'The production desktop archive still contains the retired developer-mode JVM switch.'
            }
            if ($entryName -match '(?i)(^|/)(LICENSE|NOTICE|COPYING|COPYRIGHT)([._-][^/]*)?$') {
                $safeName = $entryName -replace '[^A-Za-z0-9._-]', '_'
                $noticeName = [IO.Path]::GetFileNameWithoutExtension($Path) + '.' + $safeName
                [IO.File]::WriteAllBytes((Join-Path $NoticeDirectory $noticeName), $bytes)
                $notices += $noticeName
            }
        }
        if ([String]::Equals($archiveName, $webArchiveName, [StringComparison]::Ordinal) -and
            $webKitEntryCount -ne 1) {
            throw 'The reviewed JavaFX Web archive does not contain exactly one expected WebKit binary.'
        }
    } finally { $zip.Dispose() }
    return [ordered]@{
        scannedEntries = $entryCount
        expandedBytes = $expandedBytes
        streamedLargeEntries = $streamedLargeEntries
        embeddedNotices = $notices
    }
}

if ($env:OS -ne 'Windows_NT' -or -not [Environment]::Is64BitOperatingSystem) {
    throw 'This package target requires Windows x64; build on the target operating system.'
}
Initialize-DeskGradeJava -JavaHome $JavaHome
if (-not [string]::IsNullOrEmpty($ServiceUrl)) {
    throw 'The local edition does not use a DeskGrade server. Do not pass -ServiceUrl.'
}
$dgServiceOrigin = $null
$dgRoot = (Resolve-Path -LiteralPath (Split-Path $PSScriptRoot)).Path
$dgDist = Join-Path $dgRoot 'dist'
[xml]$dgPom = [IO.File]::ReadAllText((Join-Path $dgRoot 'pom.xml'))
$dgVersion = $dgPom.project.version
if ($dgVersion -notmatch '^\d+\.\d+\.\d+$') { throw 'A numeric release version is required.' }
$dgAppVersion = Get-DgWindowsAppVersion $(if ($PSBoundParameters.ContainsKey('AppVersion')) { $AppVersion } else { $dgVersion })
$dgJdkRelease = [IO.File]::ReadAllText((Join-Path $env:JAVA_HOME 'release'))
if ($dgJdkRelease -notmatch '(?m)^OS_ARCH="(?:amd64|x86_64)"') { throw 'The packaging JDK must be Windows x64.' }
$dgJpackage = Join-Path $env:JAVA_HOME 'bin/jpackage.exe'
if (-not (Test-Path -LiteralPath $dgJpackage)) { throw 'JDK 21 jpackage.exe was not found.' }
if (-not $MavenRepository) { $MavenRepository = Join-Path $env:USERPROFILE '.m2/repository' }
$MavenRepository = [IO.Path]::GetFullPath($MavenRepository)
if (-not $ReleaseDirectory) {
    $ReleaseDirectory = Join-Path $dgDist ("DeskGrade-$dgAppVersion-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 6))
} elseif (-not [IO.Path]::IsPathRooted($ReleaseDirectory)) {
    $ReleaseDirectory = Join-Path $dgRoot $ReleaseDirectory
}
$dgRelease = Assert-DgChildPath $ReleaseDirectory $dgDist
if (Test-Path -LiteralPath $dgRelease) { throw 'Release directory already exists; use a new directory. No existing release is overwritten.' }
[IO.Directory]::CreateDirectory($dgRelease) | Out-Null
$dgBuild = Assert-DgChildPath (Join-Path $dgDist ('.package-work-' + [Guid]::NewGuid().ToString('N'))) $dgDist
[IO.Directory]::CreateDirectory($dgBuild) | Out-Null
$dgInput = Join-Path $dgBuild 'input'
$dgFx = Join-Path $dgInput 'javafx'
$dgLicenses = Join-Path $dgRelease 'licenses'
foreach ($dgDir in @($dgInput, $dgFx, $dgLicenses)) { [IO.Directory]::CreateDirectory($dgDir) | Out-Null }
Write-Host "Packaging DeskGrade $dgAppVersion (Maven artifacts $dgVersion). Build logs: $dgBuild"
Push-Location $dgRoot
try {
    if (-not $SkipBuild) {
        Invoke-DgBuildTool (Join-Path $dgRoot 'mvnw.cmd') @('-B', '-ntp', "-Dmaven.repo.local=$MavenRepository", 'clean', 'verify') (Join-Path $dgBuild 'maven.log')
    }
    # Explicit production inventory. Unknown target/lib JARs fail closed, including with -SkipBuild.
    $dgP = $dgPom.project.properties
    $dgFxVersion = [string]$dgP.'javafx.version'
    $dgJackson = [string]$dgP.'jackson.version'
    # Jackson BOM 2.22 uses patchless annotations; review this when upgrading.
    if ($dgJackson -notmatch '^2\.22\.\d+$') { throw 'Review the Jackson annotations packaging version before upgrading.' }
    $dgDefinitions = @(
        @('app.deskgrade', 'grading-core', $dgVersion, ''),
        @('app.deskgrade', 'grading-adapters', $dgVersion, ''),
        @('com.h2database', 'h2', [string]$dgP.'h2.version', ''),
        @('com.fasterxml.jackson.core', 'jackson-annotations', '2.22', ''),
        @('com.fasterxml.jackson.core', 'jackson-core', $dgJackson, ''),
        @('com.fasterxml.jackson.core', 'jackson-databind', $dgJackson, ''),
        @('org.openjfx', 'javafx-base', $dgFxVersion, 'win'),
        @('org.openjfx', 'javafx-graphics', $dgFxVersion, 'win'),
        @('org.openjfx', 'javafx-controls', $dgFxVersion, 'win'),
        @('org.openjfx', 'javafx-media', $dgFxVersion, 'win'),
        @('org.openjfx', 'javafx-web', $dgFxVersion, 'win'),
        @('net.java.dev.jna', 'jna', [string]$dgP.'jna.version', ''),
        @('net.java.dev.jna', 'jna-platform', [string]$dgP.'jna.version', ''),
        @('org.slf4j', 'slf4j-api', [string]$dgP.'slf4j.version', ''),
        @('ch.qos.logback', 'logback-core', [string]$dgP.'logback.version', ''),
        @('ch.qos.logback', 'logback-classic', [string]$dgP.'logback.version', '')
    )
    $dgLib = Join-Path $dgRoot 'grading-desktop/target/lib'
    $dgExpectedNames = @($dgDefinitions | ForEach-Object { $_[1] + '-' + $_[2] + $(if ($_[3]) { '-' + $_[3] }) + '.jar' })
    $dgEmptyFxNames = @(@('base', 'graphics', 'controls', 'media', 'web') | ForEach-Object { "javafx-$_-$dgFxVersion.jar" })
    $dgActualNames = @(Get-ChildItem -LiteralPath $dgLib -File | Select-Object -ExpandProperty Name)
    foreach ($dgName in $dgActualNames) {
        if ($dgName -notin $dgExpectedNames -and $dgName -notin $dgEmptyFxNames) { throw "Unexpected runtime artifact: $dgName. Clean and review the production dependency inventory." }
    }
    $dgInventory = @()
    $dgAllDefinitions = ,@('app.deskgrade', 'grading-desktop', $dgVersion, '') + $dgDefinitions
    foreach ($dgDefinition in $dgAllDefinitions) {
        $dgGroup, $dgArtifact, $dgArtifactVersion, $dgClassifier = $dgDefinition
        $dgName = "$dgArtifact-$dgArtifactVersion" + $(if ($dgClassifier) { "-$dgClassifier" }) + '.jar'
        $dgSource = if ($dgArtifact -eq 'grading-desktop') { Join-Path $dgRoot "grading-desktop/target/$dgName" } else { Join-Path $dgLib $dgName }
        if (-not (Test-Path -LiteralPath $dgSource -PathType Leaf)) { throw "Missing built production artifact: $dgName" }
        $dgRelativePath = if ($dgGroup -eq 'org.openjfx') { "javafx/$dgName" } else { $dgName }
        $dgTarget = Join-Path $dgInput $dgRelativePath
        Copy-Item -LiteralPath $dgSource -Destination $dgTarget
        if ($dgArtifact -eq 'grading-desktop') { Set-DgBundledLocalEdition $dgTarget }
        $dgArchiveScan = Test-DgReleaseJar $dgTarget $dgLicenses $dgFxVersion
        $dgLicense = if ($dgGroup -eq 'app.deskgrade') {
            [ordered]@{ licenses = @(@{ name = 'Application license not declared in repository'; url = '' }); pomFiles = @() }
        } else { Get-DgLicenseMetadata $dgGroup $dgArtifact $dgArtifactVersion $MavenRepository $dgLicenses }
        $dgInventory += [ordered]@{
            group = $dgGroup; artifact = $dgArtifact; version = $dgArtifactVersion; classifier = $dgClassifier
            file = "DeskGrade/app/$dgRelativePath"; sha256 = (Get-FileHash -LiteralPath $dgTarget -Algorithm SHA256).Hash.ToLowerInvariant()
            licenses = $dgLicense.licenses; licensePoms = $dgLicense.pomFiles
            embeddedNotices = $dgArchiveScan.embeddedNotices; scannedEntries = $dgArchiveScan.scannedEntries
            streamedLargeEntries = $dgArchiveScan.streamedLargeEntries
        }
    }
    # Link JDK modules only; JavaFX modular JARs retain all Windows DLL resources.
    # Literal $APPDIR expands at launch, including when the image is moved to a Chinese path.
    $dgModules = 'java.se,jdk.unsupported,jdk.crypto.ec,jdk.localedata,jdk.jsobject,jdk.xml.dom'
    $dgArgs = @('--type', 'app-image', '--name', 'DeskGrade', '--app-version', $dgAppVersion,
        '--dest', $dgRelease, '--input', $dgInput, '--main-jar', "grading-desktop-$dgVersion.jar",
        '--main-class', 'app.deskgrade.desktop.Launcher', '--vendor', 'DeskGrade',
        '--description', 'DeskGrade desktop application', '--add-modules', $dgModules,
        '--java-options', '-Dfile.encoding=UTF-8', '--java-options', '--module-path=$APPDIR/javafx',
        '--java-options', '--add-modules=javafx.controls,javafx.web', '--temp', (Join-Path $dgBuild 'jpackage-temp'))
    Invoke-DgBuildTool $dgJpackage $dgArgs (Join-Path $dgBuild 'jpackage.log')
    $dgImage = Join-Path $dgRelease 'DeskGrade'
    foreach ($dgRequired in @('DeskGrade.exe', 'app/DeskGrade.cfg', 'runtime/bin/server/jvm.dll', 'runtime/release', 'runtime/legal')) {
        if (-not (Test-Path -LiteralPath (Join-Path $dgImage $dgRequired))) { throw "Incomplete app-image: $dgRequired" }
    }
    $dgConfig = [IO.File]::ReadAllText((Join-Path $dgImage 'app/DeskGrade.cfg'))
    if (-not $dgConfig.Contains('app.deskgrade.desktop.Launcher') -or -not $dgConfig.Contains('$APPDIR') -or
        -not $dgConfig.Contains('javafx.controls') -or -not $dgConfig.Contains('javafx.web') -or
        $dgConfig.Contains($dgRoot) -or
        $dgConfig.Contains($env:JAVA_HOME) -or $dgConfig.Contains('deskgrade.developerMode')) {
        throw 'Launcher configuration is missing modules or contains a non-portable build-machine path.'
    }
    $dgBundledJars = @(Get-ChildItem -LiteralPath (Join-Path $dgImage 'app') -Recurse -File -Filter '*.jar')
    if ($dgBundledJars.Count -ne $dgInventory.Count) { throw 'Bundled JAR inventory does not match isolated release input.' }
    foreach ($dgItem in $dgInventory) {
        $dgFinalJar = Join-Path $dgRelease $dgItem.file
        if ((Get-FileHash -LiteralPath $dgFinalJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $dgItem.sha256) { throw 'Bundled artifact hash differs from scanned input.' }
    }
    Write-DgUtf8 (Join-Path $dgRelease 'dependency-inventory.json') (ConvertTo-Json -InputObject $dgInventory -Depth 8)
    Write-DgUtf8 (Join-Path $dgRelease 'THIRD-PARTY-NOTICES.md') @'
# Bundled software notices

The JSON dependency inventory lists exact versions, SHA-256 hashes, upstream declared licenses,
copied Maven license metadata (including inherited parent POMs), and extracted archive notices.
Original license/notice resources also remain intact in their JARs. The complete bundled JDK
license and third-party notices are in DeskGrade/runtime/legal; its vendor/version are in runtime/release.
License names and URLs reproduce upstream metadata, including alternate licenses where declared.
JavaFX uses GPLv2 with the Classpath Exception; retain applicable notices and source obligations.
Source projects: https://github.com/openjdk/jfx | https://github.com/java-native-access/jna |
https://github.com/h2database/h2database | https://github.com/FasterXML/jackson |
https://github.com/qos-ch/slf4j | https://github.com/qos-ch/logback

The repository currently declares no license grant for DeskGrade's own code. This inventory is
not a grant to redistribute that code or a completed legal review for public distribution.
This build is unsigned. No signing or installer certification is implied.
'@
    $dgReadme = @'
DeskGrade for Windows x64

Open USER_GUIDE.html for Chinese instructions.
Open MANUAL_BINDING_TEST_GUIDE.html for the per-run manual binding acceptance checklist.
Keep the complete DeskGrade directory, including DeskGrade.exe, app, and runtime together.
Double-click DeskGrade\DeskGrade.exe. No separately installed JDK or database is required.
Use a normal writable local directory; no administrator installation step is needed.
Startup does not begin grading. Configure and explicitly start a session yourself.
This is the LOCAL BYOK edition: no DeskGrade account, registration, paid credit or cloud journal.
Every installation supplies its own model API key. No key or current-user settings are bundled.
Configure primary, fallback and rubric-generation profiles in the Models and Key tab.
Keys are cached only for the current Windows user with DPAPI, not in this installation directory.
Requests go from this computer directly to the configured model API. Provider API charges still apply.
The per-run answer quantity is a task stop condition, not an application billing quota.
The header can open HTTPS sites, plus the exact local test URL http://127.0.0.1:8766/,
in a separate built-in JavaFX WebView window. Other plain-HTTP addresses are rejected.
This removes dependence on an external browser shell, but does not by itself establish a verified
real-platform adapter, score readback, submission receipt, next-answer identity or site compatibility.
New answer images are not saved. Local H2 records retain validated JSON and distinct submission facts.
Local JSONL summaries retain grading/call metadata without screenshots, HTTP bodies or keys.
For every run, manually mark the current website's answer, score and submit regions.
For a separate next-question/page flow, also mark a stable submission-success indicator and the
next control; one control may serve both roles only when its rectangle is exactly the same. Positions
are never saved or reused. Bind the freshly marked regions to the current managed WebView window;
the answer region must remain pixel-stable before automation is enabled.
The score control must support exact numeric Ctrl+A/C readback; changed page images must settle.
Clicks and visual transitions are recorded separately from authoritative platform receipts.
See docs/LOCAL_EDITION.md for local setup and data locations. No admin server is needed.
No website receives built-in coordinates, automatic region discovery, or reusable named positions.
Coordinate automation is available only after all required regions of the currently loaded in-app
website have been framed and bound for this process. Restart, navigation, model, layout, zoom or
workflow changes invalidate the binding and stop the run.
Native automatic operation requires an available, unlocked interactive desktop. Blank captures,
lost focus or unverified page state stop further actions. Check the preview before automated grading.
The gateway, development mock web server, tests, API keys, screenshots and databases are not bundled.
User data remains under USERPROFILE\.deskgrade\data, independently of this program directory and AppData virtualization.
Close DeskGrade before switching versions; keep the old image and back up user data first.
No migration or downgrade compatibility beyond the documented schema is implied.
This is an unsigned app-image. A separate per-user EXE installer can be built with
scripts/package-installer.ps1. Windows trust prompts may occur.

SHA256SUMS.txt covers every release file except itself. security-scan.json documents the
automated release inventory/content checks; it is not an antivirus or vulnerability audit.
package-smoke.ps1 (in the source repository) verifies a new isolated, safe startup and writes
its report outside this release. See USER_GUIDE.html and the included docs/PACKAGING.md.
'@
    Write-DgUtf8 (Join-Path $dgRelease 'README-FIRST.txt') $dgReadme
    # Copy only reviewed offline documentation, before scanning and hashing the final release.
    $dgGuideSource = Join-Path $dgRoot 'docs/USER_GUIDE.html'
    if (-not (Test-Path -LiteralPath $dgGuideSource -PathType Leaf)) { throw 'Required release document is missing: USER_GUIDE.html' }
    Copy-Item -LiteralPath $dgGuideSource -Destination (Join-Path $dgRelease 'USER_GUIDE.html')
    $dgManualGuide = Join-Path $dgRoot 'docs/MANUAL_BINDING_TEST_GUIDE.html'
    if (-not (Test-Path -LiteralPath $dgManualGuide -PathType Leaf)) { throw 'Required manual binding test guide is missing.' }
    Copy-Item -LiteralPath $dgManualGuide -Destination (Join-Path $dgRelease 'MANUAL_BINDING_TEST_GUIDE.html')
    $dgReleaseDocs = Join-Path $dgRelease 'docs'
    [IO.Directory]::CreateDirectory($dgReleaseDocs) | Out-Null
    foreach ($dgDocument in @('LOCAL_EDITION.md', 'LOCAL_RECORDS.md', 'LOCAL_MODEL_SETUP.md', 'LOCAL_PERFORMANCE_TIMING_20261021.md')) {
        $dgDocumentSource = Join-Path $dgRoot ('docs/' + $dgDocument)
        if (-not (Test-Path -LiteralPath $dgDocumentSource -PathType Leaf)) { throw "Required release document is missing: $dgDocument" }
        Copy-Item -LiteralPath $dgDocumentSource -Destination (Join-Path $dgReleaseDocs $dgDocument)
    }
    $dgTimingReader = Join-Path $dgRoot 'scripts/summarize-performance.ps1'
    if (-not (Test-Path -LiteralPath $dgTimingReader -PathType Leaf)) { throw 'Required timing reader is missing.' }
    Copy-Item -LiteralPath $dgTimingReader -Destination (Join-Path $dgReleaseDocs 'summarize-performance.ps1')
    # Also include the small, read-only viewer and its instructions in EXE installations.
    $dgInstalledDocs = Join-Path $dgImage 'app/docs'
    [IO.Directory]::CreateDirectory($dgInstalledDocs) | Out-Null
    foreach ($dgLegalName in @('LICENSE', 'NOTICE')) {
        $dgLegalSource = Join-Path $dgRoot $dgLegalName
        if (-not (Test-Path -LiteralPath $dgLegalSource -PathType Leaf)) { throw "Required project license notice is missing: $dgLegalName" }
        Copy-Item -LiteralPath $dgLegalSource -Destination (Join-Path $dgRelease $dgLegalName)
        Copy-Item -LiteralPath $dgLegalSource -Destination (Join-Path $dgImage ('app/' + $dgLegalName))
    }
    foreach ($dgPublicGuide in @('USER_GUIDE.md', 'DEVELOPMENT_LOCAL.md')) {
        $dgPublicGuideSource = Join-Path $dgRoot ('docs/' + $dgPublicGuide)
        if (-not (Test-Path -LiteralPath $dgPublicGuideSource -PathType Leaf)) { throw "Required public guide is missing: $dgPublicGuide" }
        Copy-Item -LiteralPath $dgPublicGuideSource -Destination (Join-Path $dgReleaseDocs $dgPublicGuide)
    }
    Copy-Item -LiteralPath (Join-Path $dgRoot 'docs/USER_GUIDE.md') -Destination $dgInstalledDocs
    Copy-Item -LiteralPath $dgTimingReader -Destination (Join-Path $dgInstalledDocs 'summarize-performance.ps1')
    Copy-Item -LiteralPath (Join-Path $dgReleaseDocs 'LOCAL_PERFORMANCE_TIMING_20261021.md') -Destination $dgInstalledDocs
    foreach ($dgRecordFile in @('contracts/grading-record.schema.json', 'contracts/grade-result.schema.json', 'examples/grading-record.synthetic.example.json')) {
        $dgRecordSource = Join-Path $dgRoot $dgRecordFile
        if (-not (Test-Path -LiteralPath $dgRecordSource -PathType Leaf)) { throw "Required record contract is missing: $dgRecordFile" }
        $dgRecordTarget = Join-Path $dgRelease $dgRecordFile
        [IO.Directory]::CreateDirectory((Split-Path $dgRecordTarget)) | Out-Null
        Copy-Item -LiteralPath $dgRecordSource -Destination $dgRecordTarget
    }
    $dgForbiddenFiles = @(Get-ChildItem -LiteralPath $dgRelease -Recurse -File | Where-Object {
        $_.Name -match '(?i)(grading-gateway|mock-grading-site|junit|test-ledger|\.dpapi$|\.mv\.db$|\.trace\.db$|\.lock\.db$|\.pem$|\.p12$|\.pfx$|\.key$|\.log$|\.jsonl$|^\.env($|\.))'
    })
    if ($dgForbiddenFiles.Count -gt 0) { throw 'A forbidden runtime-data/test/secret file was found in the final release.' }
    # JAR contents were scanned above. Scan every hand-authored text artifact after copying as well;
    # do not print matching content because it may itself be a credential.
    $dgHandwrittenText = @(
        (Join-Path $dgRelease 'README-FIRST.txt'),
        (Join-Path $dgRelease 'LICENSE'),
        (Join-Path $dgRelease 'NOTICE'),
        (Join-Path $dgRelease 'USER_GUIDE.html'),
        (Join-Path $dgRelease 'MANUAL_BINDING_TEST_GUIDE.html')
    ) + @(Get-ChildItem -LiteralPath $dgReleaseDocs -Recurse -File | Select-Object -ExpandProperty FullName) `
      + @(Get-ChildItem -LiteralPath (Join-Path $dgRelease 'contracts') -Recurse -File | Select-Object -ExpandProperty FullName) `
      + @(Get-ChildItem -LiteralPath (Join-Path $dgRelease 'examples') -Recurse -File | Select-Object -ExpandProperty FullName)
    foreach ($dgTextFile in $dgHandwrittenText) {
        if (Test-DgCredentialText ([IO.File]::ReadAllText($dgTextFile))) {
            throw "Potential credential material found in release documentation or contract: $([IO.Path]::GetFileName($dgTextFile)); content is not printed."
        }
    }
    $dgReport = [ordered]@{
        status = 'PASS'; createdUtc = [DateTime]::UtcNow.ToString('o'); version = $dgAppVersion; artifactVersion = $dgVersion; architecture = 'windows-x64'
        buildMode = $(if ($SkipBuild) { 'existing-artifacts; default verify not run by this invocation' } else { 'clean verify passed' })
        signed = $false; payloadJarCount = $dgInventory.Count; runtimeModuleRoots = $dgModules
        edition = 'LOCAL'; serviceOrigin = $null; serviceConfiguration = 'no DeskGrade server; user-configured model APIs only'
        checks = [ordered]@{ productionAllowlist = $true; jarEntriesScanned = $true; credentialPatternsScanned = $true; handwrittenTextScanned = $true;
            boundedLargeBinaryStreamScan = $true; noRuntimeDataFiles = $true; payloadHashesMatch = $true;
            portableLauncher = $true; bundledRuntime = $true }
        limitations = @('Pattern scan is not proof that no unknown secret exists.', 'No antivirus/CVE scan or public redistribution license review is claimed.',
            'App-image startup, Chinese-path operation and non-admin/new-user validation require separate smoke reports.',
            'Embedded WebView compatibility and real-platform automation require per-site validation.')
    }
    Write-DgUtf8 (Join-Path $dgRelease 'security-scan.json') (ConvertTo-Json -InputObject $dgReport -Depth 8)
    $dgHashes = @(Get-ChildItem -LiteralPath $dgRelease -Recurse -File | Sort-Object FullName | ForEach-Object {
        $relative = $_.FullName.Substring($dgRelease.Length + 1).Replace('\', '/')
        (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $relative
    })
    Write-DgUtf8 (Join-Path $dgRelease 'SHA256SUMS.txt') (($dgHashes -join "`n") + "`n")
    $dgReleaseManifestSha256 = (Get-FileHash -LiteralPath (Join-Path $dgRelease 'SHA256SUMS.txt') -Algorithm SHA256).Hash.ToLowerInvariant()
} catch {
    # Preserve build logs and partial output; no recursive deletion of a computed path.
    Write-DgUtf8 (Join-Path $dgRelease 'BUILD-FAILED.txt') 'This directory is incomplete. Do not distribute it. Inspect the associated dist/.package-work-* build logs.'
    throw
} finally { Pop-Location }

# Local BYOK releases never activate or alter a gateway/server launcher. The compatibility
# -DoNotActivateLocalLauncher switch is intentionally a no-op for this edition.

Write-Host "App-image created: $dgImage"
Write-Host "Release scan PASS; $($dgInventory.Count) JARs; $($dgHashes.Count) SHA-256 entries. Startup smoke remains separate."
Write-Output $dgRelease
