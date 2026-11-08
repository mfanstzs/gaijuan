# DeskGrade 本地版开发指南

本页介绍当前本地 BYOK 桌面版的构建和验证。默认桌面启动不需要运行 `grading-gateway`、MySQL 或 Redis；历史云端模块仍在工程中，不应据旧文档把账号服务当作本地版依赖。

## 环境

- Windows x64：原生截图、全局热键、输入操作和 DPAPI 验证依赖 Windows。
- 完整 JDK 21：需包含 `java`、`javac`，打包还需要 `jpackage`。
- PowerShell 与仓库中的 Maven Wrapper。
- 首次构建可访问 Maven 依赖下载源。

项目按 Java 21 编译并使用 JavaFX。仅运行安装版时不需要额外 JDK。依赖版本由根 `pom.xml` 与 Wrapper 配置固定；更新依赖时应一并执行测试和发布检查。

## 构建和启动

在仓库根目录执行：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
.\mvnw.cmd -B -ntp verify
```

或由启动脚本指定 JDK 并在验证后启动当前源码：

```powershell
.\scripts\run-dev.ps1 -JavaHome 'C:\path\to\jdk-21'
```

只构建、不打开窗口：

```powershell
.\scripts\run-dev.ps1 -JavaHome 'C:\path\to\jdk-21' -BuildOnly
```

默认 `verify` 使用本地测试替身，不主动请求真实供应商，也不执行需要交互桌面的输入测试。不要并行运行多个 Maven 构建；先退出正在使用同一用户数据目录的 DeskGrade，避免单实例或数据库占用冲突。

开发启动同样可能使用当前用户的真实本地数据目录。需要隔离验收时使用专门的测试和包烟测，不要把真实 Key 或答卷复制到测试资源中。

## IntelliJ IDEA

1. 打开仓库根目录并导入 Maven 项目。
2. 将 Project SDK 与 Maven Runner JRE 设为完整 JDK 21，Maven 选择项目 Wrapper。
3. 选择仓库提供的 **DeskGrade 当前源码** 运行配置。

该配置位于 `.run/DeskGrade Desktop (Current Source).run.xml`，入口为 `app.deskgrade.desktop.Launcher`。启动前运行项目 `verify`，读取本次源码构建结果，不通过桌面安装快捷方式或旧 `dist` 包。

## 测试分层

默认回归：

```powershell
.\mvnw.cmd -B -ntp verify
```

Windows 交互桌面测试：

```powershell
.\mvnw.cmd -B -ntp -Pwindows-it verify
```

交互测试会涉及专用合成窗口、截图、焦点、热键和输入。执行前停止真实阅卷，保持桌面解锁，按测试要求让合成窗口处于前台，不要同时操作鼠标键盘。测试报告保存在各模块 `target/surefire-reports` 或 `target/failsafe-reports`。

需要网页练习时，可以单独运行合成站点：

```powershell
.\scripts\run-mock-platform.ps1 -Port 8766 -Count 5 -AnswerSet synthetic -ServerOnly
```

保持该终端运行，再在 DeskGrade 中打开 `http://127.0.0.1:8766/`，仍需手动框选。不要选用未经授权的真实手写数据作为公开测试夹具。模型能力验证和真实评估与合成网页测试不同，必须明确使用自己的 Key 并接受供应商费用；不要将付费 API 调用塞入默认 CI。

## 构建安装包

为每次发布选择新的三段数字版本号和不存在的输出目录。以下以 0.2.6 为例；如果相应目录已存在，请使用新的发布目录而不是覆盖：

```powershell
.\scripts\package-windows.ps1 -JavaHome 'C:\path\to\jdk-21' -AppVersion '0.2.6' -ReleaseDirectory 'dist\DeskGrade-0.2.6-release'
.\scripts\package-smoke.ps1 -ImageDirectory 'dist\DeskGrade-0.2.6-release\DeskGrade'
.\scripts\package-smoke.ps1 -ImageDirectory 'dist\DeskGrade-0.2.6-release\DeskGrade' -ChinesePath
.\scripts\package-installer.ps1 -JavaHome 'C:\path\to\jdk-21' -ImageDirectory 'dist\DeskGrade-0.2.6-release\DeskGrade' -OutputDirectory 'dist\DeskGrade-0.2.6-installer'
```

打包脚本使用生产白名单，生成安全扫描、依赖说明和 SHA-256 清单；安装器还会核对源发布清单。首次构建安装器可能下载已固定摘要的 WiX 工具。不要将 `local-data`、数据库、日志、截图、Key 或个人配置手工复制进发布目录。

包烟测使用新的隔离数据目录和合成内容，不会证明真实学校平台兼容或评分准确。正式发布还需检查：新用户首次配置、升级后原数据保留、桌面入口指向正确版本，以及实际可用的下载文件与本地摘要一致。未配置代码签名的包可能显示未知发布者，不能将本地验证通过写成已经获得签名。

## 修改原则

- Java 负责校验允许分值、分项与总分，模型不能直接获得桌面操作权限。
- 修改模型、规则、目标网页或坐标后，原有动作授权不得继续使用。
- 未知提交不能自动重发；模型失败、图片不明或非法结果不能补成默认 0 分。
- 更换端点不能悄悄复用其他供应商 Key；新配置不能冒用旧能力验证。
- 配置与日志均视为不可信输入，诊断输出不得带出 Key 或完整上游错误正文。
- 不以跳过测试、削弱截图校验或提高容忍阈值来掩盖真实逻辑错误。

维护默认提示词时，同时检查 `prompts/GRADING_SYSTEM.md` 与运行资源 `grading-adapters/src/main/resources/model/GRADING_SYSTEM.md`，避免源码说明更新但包内资源仍旧。必要时补充测试，覆盖旧配置、取消编辑、异常返回和重启读取等路径。

## 首次公开仓库

**不要在包含历史运维资料的原开发工作目录直接执行 `git add .` 并推送。** 先导出独立的可公开源码目录：

```powershell
.\scripts\export-open-source.ps1 -OutputDirectory 'dist\DeskGrade-open-source'
```

输出目录必须全新，脚本不覆盖已有目录。导出按白名单保留源码、公开文档和必要构建文件，排除运行数据、私有报告等内容，并在公开副本中用合成素材替换六张历史手写测试图片。原开发资料不删除、不改写；脚本不创建 GitHub 仓库，也不推送。

检查导出报告与目录内容后，在导出目录执行完整构建验证，再决定初始化 Git、创建提交和推送到自己的仓库。不要把目录外的私有数据、日志或旧仓库历史重新加入。`.gitignore` 和模式扫描不是全面隐私审计，仍需人工复核暂存内容与后续历史。

误公开过的 Key 需要在供应商处撤销或轮换，删除文件本身不能撤回已经泄露的凭据。测试素材与第三方依赖分别核对许可；本项目采用的 [Apache License 2.0](../LICENSE) 不自动替代它们自己的授权。
