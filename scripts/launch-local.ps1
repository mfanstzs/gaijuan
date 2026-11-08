[CmdletBinding()]
param(
    [ValidateSet('App','Admin','Setup','Stop','Status')][string]$Mode = 'App',
    [string]$ConfigurationFile,
    [switch]$NoOpen,
    [switch]$ShortcutUi
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
function Show-DgShortcutFailure([string]$Message) {
    try {
        $dgPopupShell = New-Object -ComObject WScript.Shell
        $dgPopupShell.Popup($Message, 0, 'DeskGrade 启动失败', 16) | Out-Null
    } catch {
        Write-Host $Message -ForegroundColor Red
    }
}
trap {
    $dgUnhandled = 'DeskGrade 启动未完成。' + [Environment]::NewLine + [Environment]::NewLine + $_.Exception.Message
    if ($ShortcutUi) {
        Show-DgShortcutFailure $dgUnhandled
    } else {
        Write-Host ('DeskGrade: ' + $_.Exception.Message) -ForegroundColor Red
        if (-not $NoOpen) { Read-Host '按回车关闭此窗口' | Out-Null }
    }
    exit 1
}
# Explorer can retain a PSModulePath polluted by PowerShell 7 or duplicate module roots.
# Windows PowerShell 5.1 then auto-loads incompatible Security type data before the
# launcher can validate ACLs. Restrict Desktop edition to its signed inbox modules;
# PowerShell Core keeps its own module resolution unchanged.
if ($PSVersionTable.PSEdition -eq 'Desktop') {
    $dgInboxModulePath = Join-Path $PSHOME 'Modules'
    if (-not [IO.Directory]::Exists($dgInboxModulePath)) {
        throw 'The Windows PowerShell inbox module directory is missing.'
    }
    $env:PSModulePath = $dgInboxModulePath
}
$script:dgExpectedApplicationWindowTitle = 'DeskGrade · 单题自动阅卷助手'
. (Join-Path $PSScriptRoot 'local-launch-validation.ps1')
function Assert-DgNonRootDirectory([string]$Value) {
    if ($Value.TrimEnd([char[]]'\/') -eq ([IO.Path]::GetPathRoot($Value)).TrimEnd([char[]]'\/')) {
        throw 'Choose a dedicated data or launcher directory, not a drive or share root.'
    }
}
if (-not $ConfigurationFile) { $ConfigurationFile = Join-Path $PSScriptRoot 'config.json' }
$ConfigurationFile = Resolve-DgFilesystemPath $ConfigurationFile
$dgLauncherHome = Split-Path $ConfigurationFile
Assert-DgPrivateTrustRoot $dgLauncherHome | Out-Null
$dgConfig = Read-DgLauncherConfiguration $ConfigurationFile
Assert-DgInstalledIntegrity $dgConfig $ConfigurationFile $PSCommandPath
$script:dgDiagnostic = [ordered]@{
    schemaVersion = 1; updatedUtc = [DateTime]::UtcNow.ToString('o'); mode = $Mode
    status = 'STARTING'; stage = 'READ_CONFIGURATION'; configurationFile = (ConvertTo-DgSafeDiagnosticText $ConfigurationFile)
    windowsUser = (ConvertTo-DgSafeDiagnosticText ([Environment]::UserDomainName + '\' + [Environment]::UserName))
    powerShellVersion = $PSVersionTable.PSVersion.ToString(); liveEnabled = $null; paths = $null; error = $null
}
$dgMutex = $null
$dgLocked = $false
try {
$script:dgDiagnostic.liveEnabled = [bool]$dgConfig.allowLive
$script:dgDiagnostic.stage = 'CHECK_RUNTIME'
$dgPidFile = Join-Path $dgLauncherHome 'gateway-process.json'
$dgJava = Join-Path $dgConfig.javaHome 'bin/java.exe'
$dgClasspath = (Join-Path $dgConfig.gatewayRuntime 'gateway.jar') + ';' + (Join-Path $dgConfig.gatewayRuntime 'lib/*')
$dgUrl = 'http://127.0.0.1:' + $dgConfig.port
$dgData = Resolve-DgFilesystemPath -Value $dgConfig.dataDirectory -BaseDirectory $dgLauncherHome
$dgStorageConfig = $null
$dgStorageDigest = $null
if ($Mode -ne 'Stop') {
    if (-not $dgConfig.PSObject.Properties['storageConfigurationFile']) { throw '缺少 MySQL / Redis 服务端配置，请更新本机安装；不会创建空白本地数据库。' }
    $dgStorageConfig = Resolve-DgFilesystemPath -Value $dgConfig.storageConfigurationFile -BaseDirectory $dgLauncherHome
    if (-not [IO.File]::Exists($dgStorageConfig)) { throw ('MySQL / Redis 配置文件不存在：' + $dgStorageConfig) }
    $dgStorageDigest = (Get-FileHash -LiteralPath $dgStorageConfig -Algorithm SHA256).Hash
}
Assert-DgNonRootDirectory $dgLauncherHome
Assert-DgNonRootDirectory $dgData
$script:dgAdministratorCreated = $false
foreach ($dgRequired in @($dgJava,(Join-Path $dgConfig.gatewayRuntime 'gateway.jar'))) {
    if (-not (Test-Path -LiteralPath $dgRequired -PathType Leaf)) { throw 'Local runtime is missing. Reinstall the DeskGrade shortcuts.' }
}
function Quote-DgArgument([string]$Value) {
    if ($Value.Contains('"') -or $Value.Contains("`n") -or $Value.Contains("`r")) { throw 'Invalid local launch argument.' }
    # Windows argv parsing requires trailing backslashes to be doubled before the closing quote.
    return '"' + [regex]::Replace($Value, '(\\+)$', '$1$1') + '"'
}
function Get-DgApplicationProcesses([string]$ApplicationPath) {
    $dgExpectedApplication = [IO.Path]::GetFullPath($ApplicationPath)
    $dgApplicationName = [IO.Path]::GetFileNameWithoutExtension($dgExpectedApplication)
    $dgMatches = @()
    foreach ($dgCandidate in @(Get-Process -Name $dgApplicationName -ErrorAction SilentlyContinue)) {
        try {
            if ($dgCandidate.Path -and
                [string]::Equals([IO.Path]::GetFullPath($dgCandidate.Path), $dgExpectedApplication, [StringComparison]::OrdinalIgnoreCase)) {
                $dgCandidate.Refresh()
                $dgMatches += $dgCandidate
            }
        } catch {
            # A process may exit while it is being inspected; the next poll will retry.
        }
    }
    return @($dgMatches)
}
function Test-DgDesktopInstanceLockHeld {
    $dgUserDataHome = [Environment]::GetFolderPath('UserProfile')
    if ([string]::IsNullOrWhiteSpace($dgUserDataHome)) { $dgUserDataHome = $env:USERPROFILE }
    if ([string]::IsNullOrWhiteSpace($dgUserDataHome)) {
        throw 'The DeskGrade desktop data directory could not be resolved.'
    }
    $dgDesktopLock = Join-Path (Join-Path $dgUserDataHome '.deskgrade\data') '.deskgrade-instance.lock'
    if (-not [IO.File]::Exists($dgDesktopLock)) { return $false }
    $dgLockStream = $null
    try {
        $dgLockStream = [IO.FileStream]::new(
            $dgDesktopLock,
            [IO.FileMode]::Open,
            [IO.FileAccess]::ReadWrite,
            [IO.FileShare]::ReadWrite)
        try {
            $dgLockStream.Lock(0, [long]1)
            $dgLockStream.Unlock(0, [long]1)
            return $false
        } catch [IO.IOException] {
            return $true
        }
    } catch [IO.IOException] {
        # Fail closed: an inaccessible lock file must not lead to a second desktop instance.
        return $true
    } finally {
        if ($null -ne $dgLockStream) { $dgLockStream.Dispose() }
    }
}
function Wait-DgApplicationWindow([string]$ApplicationPath, [int]$TimeoutSeconds) {
    $dgDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $dgVisible = @(Get-DgApplicationProcesses $ApplicationPath | Where-Object {
            $_.MainWindowHandle -ne [IntPtr]::Zero -and
            $_.MainWindowTitle -eq $script:dgExpectedApplicationWindowTitle
        })
        if ($dgVisible.Count -gt 0) { return $dgVisible[0] }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $dgDeadline)
    return $null
}
function Focus-DgApplication([System.Diagnostics.Process]$Process) {
    try {
        $dgActivationShell = New-Object -ComObject WScript.Shell
        $dgActivationShell.AppActivate([int]$Process.Id) | Out-Null
    } catch {
        # Windows may reject foreground activation; the existing taskbar window remains available.
    }
}
function Get-DgOwnedProcess {
    if (-not (Test-Path -LiteralPath $dgPidFile)) { return $null }
    $record = Get-Content -LiteralPath $dgPidFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $process = Get-Process -Id $record.pid -ErrorAction SilentlyContinue
    if (-not $process) { return $null }
    $info = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $record.pid)
    if (-not $info -or -not $info.CommandLine -or
        $process.StartTime.ToUniversalTime().Ticks.ToString() -ne $record.startTicks -or
        $info.ExecutablePath -ne $dgJava -or
        -not $info.CommandLine.Contains('app.deskgrade.gateway.GatewayMain') -or
        -not $info.CommandLine.Contains((Quote-DgArgument $record.dataDirectory)) -or
        -not $info.CommandLine.Contains((Quote-DgArgument $record.classpath))) {
        throw 'The saved process identity no longer matches. No process was stopped or replaced.'
    }
    if ($record.dataDirectory -ne $dgData -or [int]$record.port -ne [int]$dgConfig.port) {
        throw 'A different DeskGrade service configuration is running. Stop it from its original launcher first.'
    }
    return $process
}
function Test-DgListenerOwnership([int]$ExpectedProcessId) {
    $owned = Get-DgOwnedProcess
    if (-not $owned -or $ExpectedProcessId -le 0 -or $owned.Id -ne $ExpectedProcessId) { return $false }
    $owned.Refresh()
    if ($owned.HasExited) { return $false }
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort ([int]$dgConfig.port) -ErrorAction Stop |
        Where-Object { $_.LocalAddress -eq '127.0.0.1' })
    return $listeners.Count -eq 1 -and $listeners[0].OwningProcess -eq $ExpectedProcessId
}
function Test-DgHealth([int]$ExpectedProcessId) {
    try {
        if (-not (Test-DgListenerOwnership $ExpectedProcessId)) { return $false }
        $reply = Invoke-RestMethod -Uri ($dgUrl + '/health') -TimeoutSec 2
        return $reply.service -eq 'deskgrade-gateway' -and [int]$reply.apiVersion -eq 2 -and
            (Test-DgListenerOwnership $ExpectedProcessId)
    } catch { return $false }
}
function Get-DgAdminState {
    $probePreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $dgJava '-Dfile.encoding=UTF-8' -cp $dgClasspath app.deskgrade.gateway.GatewayMain admin-status --data-dir $dgData --storage-config $dgStorageConfig 2> (Join-Path $dgLauncherHome 'admin-probe-stderr.log'))
        $probeExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $probePreference }
    if ($probeExit -ne 0) { throw 'MySQL 安装尚未就绪或服务无法访问。请核对数据库服务和迁移报告；普通启动不会创建新管理员或空白数据库。全新空库请明确运行 Setup。' }
    $statuses = @($output | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ -eq 'ADMIN_READY' })
    if ($statuses.Count -ne 1) { throw 'Unexpected administrator setup status.' }
    return $statuses[0]
}
function Initialize-DgAdmin {
    Write-Host '显式新装：仅允许在全新空白 MySQL 库中创建管理员。已有安装和待完成迁移均不会被重置。'
    Write-Host '步骤 1：设置用户名，直接按回车可使用 owner。'
    Write-Host '步骤 2：输入两遍 15–128 位密码，每次输入完成后按回车。密码输入时不显示字符或星号，这是正常现象。'
    Write-Host '设置完成后将继续启动；请记住账号密码，用于登录管理后台。'
    $username = Read-Host '管理员用户名（3–32 位英文字母、数字或下划线；直接回车使用 owner）'
    if ([string]::IsNullOrWhiteSpace($username)) { $username = 'owner' }
    if ($username -notmatch '^[A-Za-z0-9_]{3,32}$') { throw '管理员用户名须为 3–32 位英文字母、数字或下划线，不能使用中文。重新打开快捷方式后也可直接回车使用 owner。' }
    # Keep Java attached to the user's console even when Start-DgGateway's result is assigned.
    $bootstrapArguments = @('-Dfile.encoding=UTF-8','-cp',(Quote-DgArgument $dgClasspath),
        'app.deskgrade.gateway.GatewayMain','bootstrap-admin','--data-dir',(Quote-DgArgument $dgData),'--username',$username,'--storage-config',(Quote-DgArgument $dgStorageConfig))
    $bootstrap = Start-Process -FilePath $dgJava -ArgumentList $bootstrapArguments -NoNewWindow -Wait -PassThru
    if ($bootstrap.ExitCode -ne 0 -or (Get-DgAdminState) -ne 'ADMIN_READY') { throw '新装设置未完成。只有全新空白 MySQL 库允许 Setup；已有安装或迁移中的库不可重置。密码须两次一致且为 15–128 位。' }
    $script:dgAdministratorCreated = $true
}
function Start-DgGateway {
    $script:dgDiagnostic.stage = 'CHECK_SERVICE'
    if ($dgConfig.allowLive) {
        $script:dgDiagnostic.paths = Get-DgLivePathChecks -ConfigurationFile $ConfigurationFile -ProfilesFile $dgConfig.profilesFile -CredentialsDirectory $dgConfig.credentialsDirectory
    }
    $process = Get-DgOwnedProcess
    if ($process) {
        $running = Get-Content -LiteralPath $dgPidFile -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($running.classpath -ne $dgClasspath) { throw 'An older DeskGrade gateway runtime is running. Use the Stop Server shortcut before opening the new version.' }
        if (-not $running.PSObject.Properties['storageConfigurationFile'] -or
            -not $running.PSObject.Properties['storageConfigurationSha256'] -or
            $running.storageConfigurationFile -ne $dgStorageConfig -or
            $running.storageConfigurationSha256 -ne $dgStorageDigest) {
            throw '运行中的后台使用另一份或旧版数据库配置。请先使用“DeskGrade 停止后台”，再重新启动；不会复用来源不明的账户数据。'
        }
        $runningInfo = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $process.Id)
        if (-not $runningInfo.CommandLine.Contains('--storage-config') -or
            -not $runningInfo.CommandLine.Contains((Quote-DgArgument $dgStorageConfig))) {
            throw '运行中的后台不是当前 MySQL 配置启动的服务。请先停止旧后台，再重新启动。'
        }
        if (-not (Test-DgHealth $process.Id)) { throw 'The owned service is still starting or is unhealthy. It was not restarted.' }
        return $process
    }
    if (@(Get-NetTCPConnection -State Listen -LocalPort $dgConfig.port -ErrorAction SilentlyContinue).Count -gt 0) {
        throw ('Port ' + $dgConfig.port + ' is occupied. No unrelated service was replaced.')
    }
    if ($dgConfig.allowLive) {
        $script:dgDiagnostic.stage = 'VALIDATE_LIVE_PATHS'
        Assert-DgLivePathChecks -Checks $script:dgDiagnostic.paths -ConfigurationFile $ConfigurationFile
        $dgConfig.profilesFile = Resolve-DgFilesystemPath -Value $dgConfig.profilesFile -BaseDirectory $dgLauncherHome
        $dgConfig.credentialsDirectory = Resolve-DgFilesystemPath -Value $dgConfig.credentialsDirectory -BaseDirectory $dgLauncherHome
    }
    $script:dgDiagnostic.stage = 'VERIFY_MYSQL_INSTALLATION'
    if ((Get-DgAdminState) -ne 'ADMIN_READY') { throw 'MySQL 安装未就绪；普通启动不会初始化账户。' }
    if (@(Get-NetTCPConnection -State Listen -LocalPort $dgConfig.port -ErrorAction SilentlyContinue).Count -gt 0) {
        throw ('Port ' + $dgConfig.port + ' became occupied during setup. No unrelated service was replaced.')
    }
    $arguments = @('-Dfile.encoding=UTF-8','-cp',(Quote-DgArgument $dgClasspath),
        'app.deskgrade.gateway.GatewayMain','serve','--data-dir',(Quote-DgArgument $dgData),'--port',([string]$dgConfig.port),'--storage-config',(Quote-DgArgument $dgStorageConfig))
    if ($dgConfig.allowLive) {
        $arguments += @('--profiles',(Quote-DgArgument $dgConfig.profilesFile),'--credentials-dir',(Quote-DgArgument $dgConfig.credentialsDirectory),'--allow-live')
    }
    $script:dgDiagnostic.stage = 'START_GATEWAY'
    $process = Start-Process -FilePath $dgJava -ArgumentList $arguments -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $dgLauncherHome 'gateway-stdout.log') -RedirectStandardError (Join-Path $dgLauncherHome 'gateway-stderr.log')
    $record = [ordered]@{pid=$process.Id;startTicks=$process.StartTime.ToUniversalTime().Ticks.ToString();classpath=$dgClasspath;dataDirectory=$dgData;port=[int]$dgConfig.port;storageConfigurationFile=$dgStorageConfig;storageConfigurationSha256=$dgStorageDigest}
    [IO.File]::WriteAllText($dgPidFile,($record | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    $script:dgDiagnostic.stage = 'WAIT_GATEWAY_HEALTH'
    for ($attempt=0; $attempt -lt 15; $attempt++) {
        $process.Refresh()
        if ($process.HasExited) { throw 'Gateway startup failed. Inspect the local gateway-stderr.log; no credentials are printed.' }
        if (Test-DgHealth $process.Id) { return $process }
        Start-Sleep -Milliseconds 700
    }
    throw 'The gateway did not become ready. No second copy was started.'
}
$dgDigest = [Security.Cryptography.SHA256]::Create()
try { $dgMutexId = ([BitConverter]::ToString($dgDigest.ComputeHash([Text.Encoding]::UTF8.GetBytes($dgLauncherHome.ToUpperInvariant())))).Replace('-','') }
finally { $dgDigest.Dispose() }
$dgMutex = [Threading.Mutex]::new($false,('Local\DeskGradeLauncher-' + $dgMutexId))
    $script:dgDiagnostic.stage = 'WAIT_LAUNCHER_LOCK'
    try { $dgLocked = $dgMutex.WaitOne(30000) } catch [Threading.AbandonedMutexException] { $dgLocked = $true }
    if (-not $dgLocked) { throw '另一个启动窗口仍在运行。请先在那个窗口完成管理员设置，或关闭后再启动。' }
    if ($Mode -eq 'Status') {
        $script:dgDiagnostic.stage = 'READ_STATUS'
        $dgProcess = Get-DgOwnedProcess
        [ordered]@{running=($null -ne $dgProcess);healthy=($null -ne $dgProcess -and (Test-DgHealth $dgProcess.Id));adminUrl=($dgUrl+'/admin')} | ConvertTo-Json -Compress
    } elseif ($Mode -eq 'Stop') {
        $script:dgDiagnostic.stage = 'STOP_GATEWAY'
        $dgProcess = Get-DgOwnedProcess
        if ($dgProcess) { Stop-Process -Id $dgProcess.Id -ErrorAction Stop; $dgProcess.WaitForExit(10000) | Out-Null }
        if (Test-Path -LiteralPath $dgPidFile) { Remove-Item -LiteralPath $dgPidFile }
        Write-Host 'The owned DeskGrade gateway is stopped. Already dispatched requests may remain charged.'
    } elseif ($Mode -eq 'Setup') {
        $script:dgDiagnostic.stage = 'INITIALIZE_ADMINISTRATOR'
        if (Get-DgOwnedProcess) { throw 'The gateway is running. Existing administrators log in through the admin page.' }
        Initialize-DgAdmin
    } else {
        $dgProcess = Start-DgGateway
        Write-Host ('后台已启动，管理地址：' + $dgUrl + '/admin')
        if (-not $NoOpen) {
            $script:dgDiagnostic.stage = 'OPEN_APPLICATION'
            if ($Mode -eq 'Admin') { Start-Process ($dgUrl + '/admin') }
            else {
                if ($script:dgAdministratorCreated) { Start-Process ($dgUrl + '/admin') }
                $dgApplication = Join-Path $dgConfig.releaseDirectory 'DeskGrade/DeskGrade.exe'
                if (-not (Test-Path -LiteralPath $dgApplication)) { throw 'The desktop app-image is missing.' }
                $dgExistingApplications = @(Get-DgApplicationProcesses $dgApplication)
                if ($dgExistingApplications.Count -eq 0 -and (Test-DgDesktopInstanceLockHeld)) {
                    throw '另一个版本的 DeskGrade 仍在运行。请先关闭已打开的 DeskGrade 窗口，再重新双击“一键启动”；不会强行结束或覆盖正在运行的版本。'
                }
                $dgReusedApplication = $dgExistingApplications.Count -gt 0
                $dgStartedApplication = $null
                if ($dgReusedApplication) {
                    $script:dgDiagnostic.stage = 'FOCUS_EXISTING_APPLICATION'
                    $dgReadyApplication = Wait-DgApplicationWindow $dgApplication 8
                } else {
                    $script:dgDiagnostic.stage = 'START_APPLICATION'
                    $dgStartedApplication = Start-Process -FilePath $dgApplication -WorkingDirectory (Split-Path $dgApplication) -WindowStyle Normal -PassThru
                    $script:dgDiagnostic.stage = 'WAIT_DESKTOP_READY'
                    $dgReadyApplication = Wait-DgApplicationWindow $dgApplication 20
                }
                if ($null -eq $dgReadyApplication) {
                    $dgRunningApplications = @(Get-DgApplicationProcesses $dgApplication)
                    if ($dgRunningApplications.Count -gt 0) {
                        throw 'DeskGrade 进程已启动，但主窗口在等待时间内未就绪。请查看启动诊断和应用日志。'
                    }
                    $dgExitSuffix = ''
                    if ($null -ne $dgStartedApplication) {
                        try {
                            $dgStartedApplication.Refresh()
                            if ($dgStartedApplication.HasExited) { $dgExitSuffix = ' 退出码：' + $dgStartedApplication.ExitCode }
                        } catch { }
                    }
                    throw ('DeskGrade 桌面程序启动后过早退出。请查看启动诊断和应用日志。' + $dgExitSuffix)
                }
                Focus-DgApplication $dgReadyApplication
                $script:dgDiagnostic['application'] = [ordered]@{
                    processId = [int]$dgReadyApplication.Id
                    reused = [bool]$dgReusedApplication
                    windowReady = $true
                }
            }
        }
    }
    $script:dgDiagnostic.status = 'SUCCESS'
    $script:dgDiagnostic.stage = 'COMPLETE'
    Write-DgStartupDiagnostic -Diagnostic $script:dgDiagnostic -LauncherDirectory $dgLauncherHome
} catch {
    $script:dgDiagnostic.status = 'FAILED'
    $dgSafeMessage = '启动未完成。请查看启动窗口中的具体错误；此诊断不会记录密码、Key 或配置文件内容。'
    $dgErrorCode = 'STARTUP_FAILED'
    if ($_.Exception.Data.Contains('DeskGradeSafeMessage')) { $dgSafeMessage = $_.Exception.Data['DeskGradeSafeMessage'] }
    if ($_.Exception.Data.Contains('DeskGradeErrorCode')) { $dgErrorCode = $_.Exception.Data['DeskGradeErrorCode'] }
    $script:dgDiagnostic.error = [ordered]@{ code = $dgErrorCode; message = $dgSafeMessage; exceptionType = $_.Exception.GetType().FullName }
    Write-DgStartupDiagnostic -Diagnostic $script:dgDiagnostic -LauncherDirectory $dgLauncherHome
    Write-Host ('DeskGrade: ' + $_.Exception.Message) -ForegroundColor Red
    Write-Host ('启动诊断：' + (Join-Path $dgLauncherHome 'startup-last.json'))
    if ($ShortcutUi) {
        Show-DgShortcutFailure ('DeskGrade 启动未完成。' + [Environment]::NewLine + [Environment]::NewLine + $_.Exception.Message + [Environment]::NewLine + [Environment]::NewLine + '启动诊断：' + (Join-Path $dgLauncherHome 'startup-last.json'))
    } elseif (-not $NoOpen) {
        Read-Host '按回车关闭此窗口' | Out-Null
    }
    exit 1
} finally {
    if ($dgLocked) { $dgMutex.ReleaseMutex() }
    if ($null -ne $dgMutex) { $dgMutex.Dispose() }
}
