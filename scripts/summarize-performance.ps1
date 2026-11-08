[CmdletBinding()]
param(
    [string]$DataDirectory = (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.deskgrade/data'),
    [string]$Directory,
    [Guid]$RunId = [Guid]::Empty,
    [ValidateRange(1, 1000)][int]$Last = 20
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Local JSON is untrusted data, never a command. Only validated numeric/enum fields leave this reader.
$dgMaximumFileBytes = 16 * 1024 * 1024
$dgMaximumLineBytes = 16 * 1024
$dgMaximumLines = 10000
$dgMaximumMilliseconds = 9223372036855.0
$dgInvariant = [Globalization.CultureInfo]::InvariantCulture
$dgJsonDatesAsString = (Get-Command ConvertFrom-Json).Parameters.ContainsKey('DateKind')
$dgStates = @{
    IDLE='空闲'; PRECHECK='预检查'; WAIT_READY='等待稳定'; CAPTURING='截取答卷'; GRADING='等待模型'
    VALIDATING='校验评分'; BEFORE_INPUT='输入前检查'; FILLING='填写分数'; VERIFYING_SCORE='读回分数'
    SUBMIT_PREPARED='准备提交'; SUBMIT_SENT='执行提交'; WAIT_ACK='等待提交确认'; NAVIGATING='切换答卷'
    WAIT_NEXT='等待下一份'; PAUSED='暂停'; SUBMISSION_UNKNOWN='提交结果未知'; STOPPED='停止'
    COMPLETED='完成'; FAILED='失败'
}
$dgReasons = @{
    NONE='无异常'; USER_PAUSED='用户暂停'; USER_STOPPED='用户停止'; CONFIG_CHANGED='配置改变'
    TARGET_CHANGED='目标改变'; PLATFORM_UNSUPPORTED='平台条件不满足'; HOTKEY_UNAVAILABLE='停止热键不可用'
    STORAGE_UNAVAILABLE='本地存储不可用'; QUESTION_CHANGED='题目改变'; PAGE_TIMEOUT='页面等待超时'
    MODEL_FAILED='模型失败'; INVALID_RESULT='评分未通过校验'; FALLBACK_UNAVAILABLE='备用模型不可用'
    FALLBACK_FAILED='备用模型失败'; SCORE_MISMATCH='分数读回不一致'; DUPLICATE_SCREEN='重复答卷'
    SUBMISSION_UNCERTAIN='提交结果未知'; NEXT_UNCERTAIN='下一份未确认'; LIMIT_REACHED='达到本轮数量'
    CREDITS_EXHAUSTED='旧版额度限制'; COST_UNKNOWN='费用未知'; RECOVERED='中断后恢复'; LOCAL_FAILURE='本地错误'
}
$dgMetrics = @{
    OBSERVE='观察页面'; CAPTURE_EVIDENCE='截取证据'; EVIDENCE_SAVE='保存证据'; JOURNAL_WRITE='写入阅卷记录'
    MODEL_DISPATCH='发送模型任务'; VALIDATE_GRADE='校验评分'; FILL_SCORE='填分操作'; READ_SCORE='读回分数'
    VERIFY_TARGET='核验当前答卷'; SUBMIT_ACTION='提交全过程'; NEXT_ACTION='下一份操作'; CAPTURE_TOTAL='截图全过程'
    CAPTURE_BOUNDARY_PREPARE='截图边界准备'; CAPTURE_SAFETY_BEFORE='截图前窗口检查'; CAPTURE_RAW='原始截图'
    CAPTURE_SAFETY_AFTER='截图后窗口检查'; CAPTURE_PNG_ENCODE='图片编码'; CAPTURE_DIGEST_AND_RESULT='图片摘要与结果'
    CAPTURE_PIXEL_COMPARE='新截图逐像素比较'; CAPTURE_PNG_REUSE='相同新画面复用编码'
    CAPTURE_BOUNDARY_CLOSE='截图边界收尾'; NATIVE_SUBMIT_CLICK='原生提交点击'; FINAL_SCORE_READBACK='提交前最终读分'
    CLIPBOARD_READ='剪贴板读分'
}

function Test-DgTimingNumber($Value, [double]$Maximum, [bool]$Integer = $false) {
    if ($Value -isnot [byte] -and $Value -isnot [int16] -and $Value -isnot [int32] -and
        $Value -isnot [int64] -and $Value -isnot [single] -and $Value -isnot [double] -and
        $Value -isnot [decimal]) { return $false }
    $number = [double]$Value
    return -not [double]::IsNaN($number) -and -not [double]::IsInfinity($number) -and
        $number -ge 0 -and $number -le $Maximum -and (-not $Integer -or [Math]::Truncate($number) -eq $number)
}

function ConvertTo-DgTimingGuid($Value) {
    if ($Value -isnot [string] -or $Value -notmatch '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$') { return $null }
    $parsed = [Guid]::Empty
    if (-not [Guid]::TryParseExact($Value, 'D', [ref]$parsed)) { return $null }
    return $parsed.ToString('D')
}

function ConvertTo-DgTimingInstant($Value) {
    if ($Value -isnot [string] -or $Value -notmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$') { return $null }
    # Java Instant has nanoseconds; .NET DateTimeOffset stores at most seven decimal places.
    $normal = $Value -replace '(\.\d{7})\d+Z$', '$1Z'
    $parsed = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse($normal, $dgInvariant, [Globalization.DateTimeStyles]::None, [ref]$parsed)) { return $null }
    return $parsed
}

function ConvertTo-DgTimingRecord($Raw) {
    if ($Raw -isnot [pscustomobject]) { return $null }
    foreach ($required in @('schemaVersion','event','storage','timingClock','durationUnit','phaseExclusive','nestedMetricsOverlap',
            'runId','traceId','attemptId','ordinal','startedAt','completedAt','totalMs','untilAcknowledgedMs',
            'finalState','pauseReason','phases','metrics','modelCalls')) {
        if ($null -eq $Raw.PSObject.Properties[$required]) { return $null }
    }
    if ($Raw.schemaVersion -isnot [int] -and $Raw.schemaVersion -isnot [long]) { return $null }
    if ($Raw.schemaVersion -ne 1 -or $Raw.event -cne 'ATTEMPT_TIMING_SUMMARY' -or $Raw.storage -cne 'LOCAL_ONLY' -or
        $Raw.timingClock -cne 'MONOTONIC' -or $Raw.durationUnit -cne 'milliseconds' -or
        $Raw.phaseExclusive -isnot [bool] -or -not $Raw.phaseExclusive -or
        $Raw.nestedMetricsOverlap -isnot [bool] -or -not $Raw.nestedMetricsOverlap) { return $null }
    $run = ConvertTo-DgTimingGuid $Raw.runId
    $trace = ConvertTo-DgTimingGuid $Raw.traceId
    if ($null -eq $run -or $null -eq $trace -or
        ($null -ne $Raw.attemptId -and $null -eq (ConvertTo-DgTimingGuid $Raw.attemptId))) { return $null }
    $started = ConvertTo-DgTimingInstant $Raw.startedAt
    $completed = ConvertTo-DgTimingInstant $Raw.completedAt
    if ($null -eq $started -or $null -eq $completed -or
        -not (Test-DgTimingNumber $Raw.ordinal ([int]::MaxValue) $true) -or $Raw.ordinal -lt 1 -or
        -not (Test-DgTimingNumber $Raw.totalMs $dgMaximumMilliseconds) -or
        ($null -ne $Raw.untilAcknowledgedMs -and
            (-not (Test-DgTimingNumber $Raw.untilAcknowledgedMs $dgMaximumMilliseconds) -or $Raw.untilAcknowledgedMs -gt $Raw.totalMs)) -or
        $Raw.finalState -isnot [string] -or $Raw.finalState -cnotin $dgStates.Keys -or
        $Raw.pauseReason -isnot [string] -or $Raw.pauseReason -cnotin $dgReasons.Keys) { return $null }
    if ($Raw.phases -isnot [array] -or $Raw.metrics -isnot [array] -or $Raw.modelCalls -isnot [array]) { return $null }
    if ($Raw.phases.Count -lt 1 -or $Raw.phases.Count -gt $dgStates.Count -or
        $Raw.metrics.Count -gt $dgMetrics.Count -or $Raw.modelCalls.Count -gt 16) { return $null }
    $phases = @{}
    $phaseSum = 0.0
    foreach ($phase in $Raw.phases) {
        if ($phase.state -isnot [string] -or $phase.state -cnotin $dgStates.Keys -or $phases.ContainsKey($phase.state) -or
            -not (Test-DgTimingNumber $phase.count ([int]::MaxValue) $true) -or
            -not (Test-DgTimingNumber $phase.elapsedMs $dgMaximumMilliseconds) -or
            ($phase.count -eq 0 -and $phase.elapsedMs -ne 0)) { return $null }
        $phases[$phase.state] = [double]$phase.elapsedMs
        $phaseSum += [double]$phase.elapsedMs
    }
    # The writer rounds each nanosecond duration independently to 0.001 ms.
    if ([Math]::Abs($phaseSum - [double]$Raw.totalMs) -gt 0.050) { return $null }
    $metrics = @{}
    foreach ($metric in $Raw.metrics) {
        if ($metric.metric -isnot [string] -or $metric.metric -cnotin $dgMetrics.Keys -or $metrics.ContainsKey($metric.metric) -or
            -not (Test-DgTimingNumber $metric.count ([int]::MaxValue) $true) -or
            -not (Test-DgTimingNumber $metric.failures ([int]::MaxValue) $true) -or $metric.failures -gt $metric.count -or
            -not (Test-DgTimingNumber $metric.elapsedMs $dgMaximumMilliseconds) -or
            ($metric.count -eq 0 -and $metric.elapsedMs -ne 0)) { return $null }
        $metrics[$metric.metric] = [pscustomobject]@{
            Count=[long]$metric.count; Milliseconds=[double]$metric.elapsedMs; Failures=[long]$metric.failures
        }
    }
    $calls = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $modelMs = 0.0
    $queueMs = 0.0
    foreach ($call in $Raw.modelCalls) {
        $callId = ConvertTo-DgTimingGuid $call.callId
        if ($null -eq $callId -or -not $calls.Add($callId) -or $call.role -cnotin @('PRIMARY','FALLBACK') -or
            $call.outcome -cnotin @('IN_FLIGHT','SUCCEEDED','FAILED') -or
            -not (Test-DgTimingNumber $call.elapsedMs $dgMaximumMilliseconds) -or
            -not (Test-DgTimingNumber $call.callbackQueueMs $dgMaximumMilliseconds) -or
            ([double]$call.elapsedMs + [double]$call.callbackQueueMs -gt [double]$Raw.totalMs + 0.010)) { return $null }
        $modelMs += [double]$call.elapsedMs
        $queueMs += [double]$call.callbackQueueMs
    }
    $group = 2
    if ($null -ne $Raw.untilAcknowledgedMs) {
        $group = 1
        if ($Raw.pauseReason -cin @('NONE','LIMIT_REACHED') -and $Raw.finalState -cin @('WAIT_NEXT','COMPLETED','STOPPED')) { $group = 0 }
    }
    return [pscustomobject]@{
        RunId=$run; TraceId=$trace; Ordinal=[int]$Raw.ordinal; Completed=$completed
        TotalMs=[double]$Raw.totalMs; UntilAck=$Raw.untilAcknowledgedMs
        FinalState=$Raw.finalState; PauseReason=$Raw.pauseReason; Group=$group
        Phases=$phases; Metrics=$metrics; ModelMs=$modelMs; QueueMs=$queueMs
    }
}

function Add-DgTimingLine([byte[]]$Buffer, [int]$Length, [bool]$Oversize, $State) {
    $State.Lines++
    if ($Oversize -or $Length -eq 0) { $State.Damaged++; return }
    try {
        $text = $State.Utf8.GetString($Buffer, 0, $Length).TrimStart([char]0xFEFF).TrimEnd([char]13)
        $jsonOptions = @{ InputObject=$text; ErrorAction='Stop' }
        if ($dgJsonDatesAsString) { $jsonOptions.DateKind = 'String' }
        $raw = ConvertFrom-Json @jsonOptions
        $record = ConvertTo-DgTimingRecord $raw
        if ($null -eq $record) { $State.Damaged++; return }
        $record | Add-Member -NotePropertyName SourceFileRank -NotePropertyValue $State.CurrentFileRank
        $record | Add-Member -NotePropertyName SourceLine -NotePropertyValue $State.Lines
        $State.Records.Add($record)
    } catch {
        # Never print the exception: parser messages can contain credentials or other log payloads.
        $State.Damaged++
    }
}

function Read-DgTimingFile([IO.FileInfo]$File, $State) {
    if (($File.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or $File.Length -gt $dgMaximumFileBytes) {
        $State.SkippedFiles++; return
    }
    $stream = $null
    try {
        $stream = [IO.File]::Open($File.FullName, [IO.FileMode]::Open, [IO.FileAccess]::Read,
            ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
        $remaining = $stream.Length
        if ($remaining -gt $dgMaximumFileBytes) { $State.SkippedFiles++; return }
        $chunk = [byte[]]::new(4096)
        $line = [byte[]]::new($dgMaximumLineBytes)
        $length = 0
        $oversize = $false
        while ($remaining -gt 0 -and $State.Lines -lt $dgMaximumLines) {
            $read = $stream.Read($chunk, 0, [int][Math]::Min($chunk.Length, $remaining))
            if ($read -eq 0) { break }
            $remaining -= $read
            for ($index=0; $index -lt $read; $index++) {
                if ($chunk[$index] -eq 10) {
                    Add-DgTimingLine $line $length $oversize $State
                    $length = 0; $oversize = $false
                    if ($State.Lines -ge $dgMaximumLines) { $State.Limited = $true; break }
                } elseif (-not $oversize) {
                    if ($length -ge $line.Length) { $oversize = $true }
                    else { $line[$length] = $chunk[$index]; $length++ }
                }
            }
        }
        if ($State.Lines -lt $dgMaximumLines -and ($length -gt 0 -or $oversize)) {
            # A missing newline is an interrupted append, not a trustworthy complete summary.
            $State.Lines++; $State.Damaged++
        }
    } catch { $State.SkippedFiles++ }
    finally { if ($null -ne $stream) { $stream.Dispose() } }
}

function Format-DgMilliseconds([double]$Value) { return $Value.ToString('0.000', $dgInvariant) }
function Write-DgTable($Rows) {
    $rendered = @($Rows) | Format-Table -AutoSize | Out-String -Width 220
    Write-Output $rendered.TrimEnd()
}

if ([string]::IsNullOrWhiteSpace($Directory)) { $Directory = Join-Path $DataDirectory 'performance' }
if (-not [IO.Directory]::Exists($Directory)) {
    Write-Output '尚无本地耗时日志。请使用新版完成一次阅卷，或通过 -Directory 指定 performance 文件夹。'
    return
}
$dgState = [pscustomobject]@{
    Lines=0; Damaged=0; SkippedFiles=0; Limited=$false; CurrentFileRank=0
    Records=[Collections.Generic.List[object]]::new()
    Utf8=[Text.UTF8Encoding]::new($false, $true)
}
$dgFiles = @(Get-ChildItem -LiteralPath $Directory -Filter 'performance-*.jsonl' -File |
    Where-Object { $_.Name -cmatch '^performance-\d{4}-\d{2}-\d{2}-\d{6}\.jsonl$' } |
    Sort-Object -Property Name -Descending | Select-Object -First 10)
foreach ($dgFile in $dgFiles) {
    if ($dgState.Lines -ge $dgMaximumLines) { $dgState.Limited = $true; break }
    Read-DgTimingFile $dgFile $dgState
    $dgState.CurrentFileRank++
}
Write-Output ('读取范围：最近 {0} 个日志段，检查 {1} 行；损坏/不支持 {2} 行，跳过 {3} 个不可读或超限文件。' -f
    $dgFiles.Count, $dgState.Lines, $dgState.Damaged, $dgState.SkippedFiles)
if ($dgState.Limited) { Write-Output '提醒：已达到 10000 行读取上限，结果仅代表本次有限读取窗口。' }
if ($dgState.Records.Count -eq 0) { Write-Output '没有符合当前格式的完整耗时记录。'; return }
# Select append order within the newest segment, not a potentially adjusted wall clock.
$dgOrdered = @($dgState.Records | Sort-Object -Property SourceFileRank, @{Expression='SourceLine';Descending=$true})
$dgSelectedRun = if ($PSBoundParameters.ContainsKey('RunId')) { $RunId.ToString('D') } else { $dgOrdered[0].RunId }
$dgSeen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$dgSelected = @($dgOrdered | Where-Object { $_.RunId -eq $dgSelectedRun } |
    Where-Object { $dgSeen.Add($_.TraceId) } | Select-Object -First $Last |
    Sort-Object -Property Ordinal, SourceFileRank, SourceLine)
if ($dgSelected.Count -eq 0) { Write-Output '所选批次不在本次读取窗口内；未读取更早日志。'; return }
Write-Output ('批次：{0}；展示最近 {1} 条答卷耗时记录（不是整批历史的保证）。单位：毫秒。' -f $dgSelectedRun, $dgSelected.Count)
Write-Output '已观察提交只表示读到确认或页面推进，不等于平台已保存；最终结果请以阅卷平台核对为准。'
Write-Output '总耗时含等待页面稳定、模型、输入、提交及下一份确认；模型累计含主/备用及内部安全重试等待。'
Write-Output '原始截图列仅统计获取画面的次数/耗时，不含窗口安全检查和图片编码；截图整体成本请看细分中的“截图全过程”。'
Write-Output 'PNG 编码/复用列为实际执行次数；复用必须先取得新截图并逐像素完全相同，不表示跳过防串分检查。旧日志未记录复用，显示“-”。'

$dgGroupNames = @('已观察提交·正常结束', '已观察提交·异常或暂停', '未观察提交·异常或暂停')
for ($dgGroup=0; $dgGroup -lt $dgGroupNames.Count; $dgGroup++) {
    $dgItems = @($dgSelected | Where-Object { $_.Group -eq $dgGroup })
    if ($dgItems.Count -eq 0) { continue }
    Write-Output ''
    Write-Output ('【{0}】样本 {1}；与其他组分开计算。' -f $dgGroupNames[$dgGroup], $dgItems.Count)
    $dgRows = foreach ($dgItem in $dgItems) {
        $dgCaptureCount = 0; $dgCaptureMs = 0.0
        if ($dgItem.Metrics.ContainsKey('CAPTURE_RAW')) {
            $dgCaptureCount = $dgItem.Metrics['CAPTURE_RAW'].Count
            $dgCaptureMs = $dgItem.Metrics['CAPTURE_RAW'].Milliseconds
        }
        [pscustomobject][ordered]@{
            '序号'=$dgItem.Ordinal
            '总耗时ms'=(Format-DgMilliseconds $dgItem.TotalMs)
            '观察提交前ms'=$(if ($null -eq $dgItem.UntilAck) { '-' } else { Format-DgMilliseconds $dgItem.UntilAck })
            '模型累计ms'=(Format-DgMilliseconds $dgItem.ModelMs)
            '原始截图次数/ms'=('{0}/{1}' -f $dgCaptureCount, (Format-DgMilliseconds $dgCaptureMs))
            'PNG编码/复用次数'=('{0}/{1}' -f $(if ($dgItem.Metrics.ContainsKey('CAPTURE_PNG_ENCODE')) { $dgItem.Metrics['CAPTURE_PNG_ENCODE'].Count } elseif ($dgItem.Metrics.ContainsKey('CAPTURE_PIXEL_COMPARE') -or $dgItem.Metrics.ContainsKey('CAPTURE_PNG_REUSE')) { 0 } else { '-' }),
                $(if ($dgItem.Metrics.ContainsKey('CAPTURE_PNG_REUSE')) { $dgItem.Metrics['CAPTURE_PNG_REUSE'].Count } elseif ($dgItem.Metrics.ContainsKey('CAPTURE_PIXEL_COMPARE')) { 0 } else { '-' }))
            '状态'=($dgStates[$dgItem.FinalState] + ' / ' + $dgReasons[$dgItem.PauseReason])
        }
    }
    Write-DgTable $dgRows
    $dgTotal = [double](($dgItems | Measure-Object -Property TotalMs -Sum).Sum)
    $dgQueue = [double](($dgItems | Measure-Object -Property QueueMs -Sum).Sum)
    Write-Output ('该组平均总耗时 {0} ms；模型回调排队平均 {1} ms（模型等待之外）。' -f
        (Format-DgMilliseconds ($dgTotal / $dgItems.Count)), (Format-DgMilliseconds ($dgQueue / $dgItems.Count)))
    Write-Output '主相位（互斥；平均按该组每份答卷计算，占比按该组总耗时计算）：'
    $dgPhaseTotals = @{}
    $dgMetricTotals = @{}
    foreach ($dgItem in $dgItems) {
        foreach ($dgKey in $dgItem.Phases.Keys) {
            if (-not $dgPhaseTotals.ContainsKey($dgKey)) { $dgPhaseTotals[$dgKey] = 0.0 }
            $dgPhaseTotals[$dgKey] += $dgItem.Phases[$dgKey]
        }
        foreach ($dgKey in $dgItem.Metrics.Keys) {
            if (-not $dgMetricTotals.ContainsKey($dgKey)) {
                $dgMetricTotals[$dgKey] = [pscustomobject]@{ Milliseconds=0.0; Count=0L; Failures=0L }
            }
            $dgMetricTotals[$dgKey].Milliseconds += $dgItem.Metrics[$dgKey].Milliseconds
            $dgMetricTotals[$dgKey].Count += $dgItem.Metrics[$dgKey].Count
            $dgMetricTotals[$dgKey].Failures += $dgItem.Metrics[$dgKey].Failures
        }
    }
    $dgPhaseRows = foreach ($dgKey in $dgPhaseTotals.Keys) {
        [pscustomobject][ordered]@{
            '相位'=$dgStates[$dgKey]; '平均ms'=(Format-DgMilliseconds ($dgPhaseTotals[$dgKey] / $dgItems.Count))
            '占比%'=$(if ($dgTotal -eq 0) { '0.00' } else { (100 * $dgPhaseTotals[$dgKey] / $dgTotal).ToString('0.00', $dgInvariant) })
            SortMs=$dgPhaseTotals[$dgKey]
        }
    }
    Write-DgTable @($dgPhaseRows | Sort-Object SortMs -Descending | Select-Object '相位','平均ms','占比%')
    Write-Output '细分 TopNested（最多 8 项；包含父子嵌套与重叠，绝不能相加当总耗时）：'
    $dgMetricRows = foreach ($dgKey in $dgMetricTotals.Keys) {
        $dgValue = $dgMetricTotals[$dgKey]
        [pscustomobject][ordered]@{
            '细分项目'=$dgMetrics[$dgKey]; '累计次数'=$dgValue.Count
            '每份平均ms'=(Format-DgMilliseconds ($dgValue.Milliseconds / $dgItems.Count))
            '失败次数'=$dgValue.Failures; SortMs=$dgValue.Milliseconds
        }
    }
    if (@($dgMetricRows).Count -eq 0) { Write-Output '该组没有细分计时。' }
    else { Write-DgTable @($dgMetricRows | Sort-Object SortMs -Descending | Select-Object -First 8 | Select-Object '细分项目','累计次数','每份平均ms','失败次数') }
    $dgEncodingRows = foreach ($dgKey in @('CAPTURE_PIXEL_COMPARE','CAPTURE_PNG_ENCODE','CAPTURE_PNG_REUSE')) {
        if ($dgMetricTotals.ContainsKey($dgKey)) {
            $dgValue = $dgMetricTotals[$dgKey]
            [pscustomobject][ordered]@{
                '图片处理项目'=$dgMetrics[$dgKey]; '累计次数'=$dgValue.Count
                '每份平均ms'=(Format-DgMilliseconds ($dgValue.Milliseconds / $dgItems.Count))
            }
        }
    }
    if (@($dgEncodingRows).Count -gt 0) {
        Write-Output '图片编码优化明细（已包含在截图全过程中，不与上表重复相加）：'
        Write-DgTable $dgEncodingRows
    }
}
