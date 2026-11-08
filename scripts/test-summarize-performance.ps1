[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$dgSummaryScript = Join-Path $PSScriptRoot 'summarize-performance.ps1'
$dgTestRoot = Join-Path ([IO.Path]::GetTempPath()) ('DeskGrade-performance-summary-' + [Guid]::NewGuid().ToString('N'))
$dgTempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
$dgResolvedTestRoot = [IO.Path]::GetFullPath($dgTestRoot)
if (-not $dgResolvedTestRoot.StartsWith($dgTempRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe test directory' }
[IO.Directory]::CreateDirectory($dgTestRoot) | Out-Null
$dgPassed = [Collections.Generic.List[string]]::new()
$dgUtf8 = [Text.UTF8Encoding]::new($false)
$dgMalicious = 'NEVER_PRINT_THIS_SECRET_OR_EXECUTE_THIS_INSTRUCTION'
function Assert-DgSummary([bool]$Condition, [string]$Name) {
    if (-not $Condition) { throw ('Performance summary regression failed: ' + $Name) }
    $dgPassed.Add($Name)
}
function New-DgSyntheticTiming([Guid]$Run, [int]$Ordinal, [bool]$Failed = $false) {
    $total = if ($Failed) { 2000 } else { 1000 }
    return [ordered]@{
        schemaVersion=1; event='ATTEMPT_TIMING_SUMMARY'; storage='LOCAL_ONLY'; timingClock='MONOTONIC'
        durationUnit='milliseconds'; phaseExclusive=$true; nestedMetricsOverlap=$true
        runId=$Run.ToString('D'); traceId=[Guid]::NewGuid().ToString('D'); attemptId=[Guid]::NewGuid().ToString('D')
        ordinal=$Ordinal; startedAt='2026-10-21T00:00:00.123456789Z'
        completedAt=('2026-10-21T00:00:{0:00}Z' -f $Ordinal)
        totalMs=$total; untilAcknowledgedMs=$(if ($Failed) { $null } else { 900 })
        finalState=$(if ($Failed) { 'STOPPED' } else { 'WAIT_NEXT' })
        pauseReason=$(if ($Failed) { 'MODEL_FAILED' } else { 'NONE' })
        phases=@(@{state='CAPTURING';count=1;elapsedMs=400}, @{state='GRADING';count=1;elapsedMs=($total-400)})
        metrics=@(@{metric='CAPTURE_RAW';count=3;failures=0;elapsedMs=300},
            @{metric='CAPTURE_TOTAL';count=1;failures=0;elapsedMs=400})
        modelCalls=@(@{callId=[Guid]::NewGuid().ToString('D');role='PRIMARY';elapsedMs=600;callbackQueueMs=50
            outcome=$(if ($Failed) { 'FAILED' } else { 'SUCCEEDED' })})
        diagnosticCodes=@($dgMalicious); arbitraryPayload=$dgMalicious
    }
}
function Get-DgTestHash([string]$Path) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return [Convert]::ToBase64String($sha.ComputeHash([IO.File]::ReadAllBytes($Path))) }
    finally { $sha.Dispose() }
}
try {
    $oldRun = [Guid]::NewGuid()
    $latestRun = [Guid]::NewGuid()
    $old = New-DgSyntheticTiming $oldRun 1
    # An earlier append with a later wall clock must not replace the newest appended run.
    $old.completedAt = '2099-10-20T00:00:01Z'
    $normal = New-DgSyntheticTiming $latestRun 1
    $failed = New-DgSyntheticTiming $latestRun 2 $true
    $badEnum = New-DgSyntheticTiming $latestRun 3
    $badEnum.finalState = $dgMalicious
    $path = Join-Path $dgTestRoot 'performance-2026-10-21-000001.jsonl'
    $lines = @(
        ($old | ConvertTo-Json -Depth 10 -Compress),
        ($normal | ConvertTo-Json -Depth 10 -Compress),
        ($failed | ConvertTo-Json -Depth 10 -Compress),
        '{',
        ($badEnum | ConvertTo-Json -Depth 10 -Compress)
    )
    [IO.File]::WriteAllText($path, (($lines -join "`n") + "`n"), $dgUtf8)
    $before = Get-DgTestHash $path
    $output = (& $dgSummaryScript -Directory $dgTestRoot | Out-String -Width 240)
    Assert-DgSummary ($output.Contains($latestRun.ToString('D')) -and -not $output.Contains($oldRun.ToString('D'))) 'latest-run-selected'
    Assert-DgSummary ($output.Contains('已观察提交·正常结束') -and $output.Contains('未观察提交·异常或暂停')) 'failures-not-mixed-with-observed-submissions'
    Assert-DgSummary ($output.Contains('损坏/不支持 2 行') -and -not $output.Contains($dgMalicious)) 'malicious-payload-and-parser-errors-not-printed'
    Assert-DgSummary ($output.Contains('600.000') -and $output.Contains('2000.000') -and $output.Contains('3/300.000')) 'numeric-model-capture-total-fields'
    Assert-DgSummary ($output.Contains('绝不能相加') -and $output.Contains('不等于平台已保存')) 'overlap-and-ack-caveats'
    Assert-DgSummary ((Get-DgTestHash $path) -eq $before) 'read-only-input-unchanged'

    $last = (& $dgSummaryScript -Directory $dgTestRoot -Last 1 | Out-String -Width 240)
    Assert-DgSummary ($last.Contains('展示最近 1 条') -and -not $last.Contains('【已观察提交·正常结束】')) 'last-limit-preserves-failure-group'
    $explicit = (& $dgSummaryScript -Directory $dgTestRoot -RunId $oldRun | Out-String -Width 240)
    Assert-DgSummary ($explicit.Contains($oldRun.ToString('D')) -and -not $explicit.Contains($latestRun.ToString('D'))) 'explicit-run-selects-only-requested-run'

    $data = Join-Path $dgTestRoot 'data'
    $performance = Join-Path $data 'performance'
    [IO.Directory]::CreateDirectory($performance) | Out-Null
    [IO.File]::WriteAllText((Join-Path $performance 'performance-2026-10-21-000001.jsonl'),
        (($normal | ConvertTo-Json -Depth 10 -Compress) + "`n"), $dgUtf8)
    $defaultChild = (& $dgSummaryScript -DataDirectory $data | Out-String -Width 240)
    Assert-DgSummary ($defaultChild.Contains($latestRun.ToString('D'))) 'data-directory-resolves-sibling-performance-folder'

    $optimized = New-DgSyntheticTiming $latestRun 3
    $optimized.metrics += @(
        @{metric='CAPTURE_PIXEL_COMPARE';count=3;failures=0;elapsedMs=5},
        @{metric='CAPTURE_PNG_ENCODE';count=1;failures=0;elapsedMs=30},
        @{metric='CAPTURE_PNG_REUSE';count=2;failures=0;elapsedMs=1}
    )
    [IO.File]::WriteAllText((Join-Path $performance 'performance-2026-10-21-000002.jsonl'),
        (($optimized | ConvertTo-Json -Depth 10 -Compress) + "`n"), $dgUtf8)
    $optimizedOutput = (& $dgSummaryScript -DataDirectory $data -Last 1 | Out-String -Width 240)
    Assert-DgSummary ($optimizedOutput.Contains('展示最近 1 条') -and $optimizedOutput.Contains('损坏/不支持 0 行') -and
        $optimizedOutput.Contains('1/2') -and $optimizedOutput.Contains('新截图逐像素比较') -and
        $optimizedOutput.Contains('相同新画面复用编码')) 'exact-png-reuse-counts-and-cost-visible'
    Assert-DgSummary ($defaultChild.Contains('-/-') -and $optimizedOutput.Contains('不表示跳过防串分检查')) 'legacy-missing-encode-and-reuse-not-misrepresented-as-zero'

    $bounds = Join-Path $dgTestRoot 'bounds'
    [IO.Directory]::CreateDirectory($bounds) | Out-Null
    $boundRun = [Guid]::NewGuid()
    foreach ($sequence in 1..11) {
        $record = New-DgSyntheticTiming $boundRun $sequence
        [IO.File]::WriteAllText((Join-Path $bounds ('performance-2026-10-21-{0:000000}.jsonl' -f $sequence)),
            (($record | ConvertTo-Json -Depth 10 -Compress) + "`n"), $dgUtf8)
    }
    $ten = (& $dgSummaryScript -Directory $bounds -Last 100 | Out-String -Width 240)
    Assert-DgSummary ($ten.Contains('最近 10 个日志段') -and $ten.Contains('展示最近 10 条')) 'only-ten-newest-segments-read'

    $damaged = Join-Path $dgTestRoot 'damaged'
    [IO.Directory]::CreateDirectory($damaged) | Out-Null
    [IO.File]::WriteAllText((Join-Path $damaged 'performance-2026-10-21-000001.jsonl'),
        (('X' * 16385) + "`n" + ($normal | ConvertTo-Json -Depth 10 -Compress) + "`n" + '{'), $dgUtf8)
    $badLines = (& $dgSummaryScript -Directory $damaged | Out-String -Width 240)
    Assert-DgSummary ($badLines.Contains('损坏/不支持 2 行') -and $badLines.Contains('展示最近 1 条')) 'oversize-and-interrupted-lines-skipped'
    $large = [IO.File]::Create((Join-Path $damaged 'performance-2026-10-21-000002.jsonl'))
    try { $large.SetLength((16 * 1024 * 1024) + 1) } finally { $large.Dispose() }
    $tooLarge = (& $dgSummaryScript -Directory $damaged | Out-String -Width 240)
    Assert-DgSummary ($tooLarge.Contains('跳过 1 个不可读或超限文件')) 'oversize-file-not-opened-for-content'

    $lineLimit = Join-Path $dgTestRoot 'line-limit'
    [IO.Directory]::CreateDirectory($lineLimit) | Out-Null
    [IO.File]::WriteAllText((Join-Path $lineLimit 'performance-2026-10-21-000001.jsonl'),
        (("{`n" * 10001) + ($normal | ConvertTo-Json -Depth 10 -Compress) + "`n"), $dgUtf8)
    $limited = (& $dgSummaryScript -Directory $lineLimit | Out-String -Width 240)
    Assert-DgSummary ($limited.Contains('检查 10000 行') -and $limited.Contains('10000 行读取上限') -and
        -not $limited.Contains($latestRun.ToString('D'))) 'total-line-budget-enforced'
    Write-Output ('PASS: {0} performance summary checks (synthetic local fixtures only).' -f $dgPassed.Count)
} finally {
    if ([IO.Directory]::Exists($dgResolvedTestRoot) -and $dgResolvedTestRoot.StartsWith($dgTempRoot, [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $dgResolvedTestRoot -Recurse -Force
    }
}
