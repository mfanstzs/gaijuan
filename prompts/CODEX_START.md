请在当前空白新项目中实施 DeskGrade：Windows 单题自动阅卷助手。

先读 AGENTS.md、docs/PLAN.md 第 0～3 节、docs/DECISIONS.md、docs/STATUS.md，再读 docs/TASKS.md 的 M0。

这是一个独立新项目，旧的“上传 PDF/Word、百度整卷批改”Demo 已废弃，不迁移旧业务。

真实产品的主流程必须是：
框选学校阅卷平台的当前学生单题答案 → 截图 → 按我录入的标准答案与评分细则调用视觉模型 → Java 校验分项并计算总分 → 自动填分 → 提交 → 确认下一份 → 循环。

包括暂停/停止、本地记录、用户主动切换视觉模型。不开发教师复核工作台、审批队列、教材库、班级学生管理、讲评或收费后台。

本次只做 M0，不做完所有阶段：
1. 检查当前文件与环境，不覆盖已有用户修改。
2. 建立 Java 21 + JavaFX + Maven 的三模块工程：grading-core、grading-adapters、grading-desktop。
3. 固定 Maven Wrapper 和依赖版本，记录实际版本与选择理由。
4. 生成能启动的桌面空壳、核心领域/接口最小骨架、MockProvider、内存仓库与必要测试。
5. 建立默认 verify 与开发启动脚本；默认测试不得调用真实付费 API或操控真实学校平台。
6. 不引入 Spring Boot、Redis、MySQL、Docker、Vue、Electron、Python 主服务或智能体框架。
7. 原生 Windows 依赖只能在适配层，核心可跨环境单测。
8. 运行实际能运行的构建和测试；没有 Windows 就明确写哪些未测，不能声称截图、热键、输入或发布包已通过。
9. 更新 docs/STATUS.md。

先用简短文字说明本次将创建哪些文件，然后直接执行 M0，不再把整个项目重新扩写成另一个方案。

完成后报告：实际完成、修改文件、真实测试命令与结果、未执行验证、风险或阻塞。然后停止，等我发 M1。

禁止自动 commit/push/deploy、创建账号、购买额度、读取/打印真实 Key，或在真实学校平台提交成绩。
