# 局域网权限提示 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在主设置页提供 Android 17 本地网络权限的主动授权入口，覆盖局域网自建服务和内嵌 Web 服务的用途说明。

**Architecture:** 小范围移植上游 903e058b 的权限卡片和设置页接入。复用现有 PermissionState、PermissionManager 及生命周期刷新，不修改权限管理框架；用真实卡片和现有状态对象验证点击及刷新行为。

**Tech Stack:** Kotlin、Material 3 Expressive、Jetpack Compose、AndroidX Activity Result、JUnit4、现有 Compose instrumentation。

**Spec:** `rikkahub/docs/superpowers/specs/2026-11-06-upstream-permission-compression-design.md`；本计划只负责其中局域网权限部分，压缩输入见同目录 `2026-11-06-compression-keep-recent-input.md`。

**用户执行调整（2026-11-06）：**取消真机及模拟器运行验证，删除本次模拟器。继续功能实现、JVM 测试、AndroidTest 编译、APK 构建和独立审查。下文涉及设备上的 UI RED/GREEN、授权弹窗及返回交互的步骤不执行，不将编译或源代码审查记为运行验证通过。

## Global Constraints

- 所有文件路径相对仓库根；Gradle 命令从 `rikkahub/` 运行，Windows 使用 `gradlew.bat`。
- 仅 API 37 及以上且权限未授予时显示卡片；用户点击后请求，不自动弹出授权框。
- 保留拒绝说明、永久拒绝后的系统设置入口、生命周期恢复刷新和 Web 服务原有启动流程。
- 八套现有权限用途翻译沿用上游更新；不新增依赖，不改配色库、推荐供应商、赞助信息、版本号、包名、签名、数据库或分支画布。
- 真机操作保持取消；可用模拟器验收与编译/JVM 结果分别报告。需要联网下载依赖时在沙箱外执行。
- 执行前使用 using-git-worktrees 检查现有受管 linked worktree；复用隔离目录，不删除或替换它。保留用户改动，从核实后的远端 main 创建 `codex/upstream-permission-compression`，不继续写发布分支。
- 两份计划共享同一功能分支；由协调者串行整理提交。README 的两处补充避免并发覆盖，设计及计划作为持久文档提交。

## Review Focus

- API 36 以下必须在创建 PermissionLocalNetwork 或权限状态之前退出，而不是在默认参数求值后才检查；Task 1 的 SDK 分段测试和调用链检查覆盖。
- 打开设置本身不能触发权限请求；Task 1 检查组合后 launcher 次数为 0，点击后恰好为 1。
- 已授权或刷新为已授权后，设置页不留下空的 LazyColumn item；Task 1 检查生产条件，并在可用模拟器检查实际页面。
- 被永久拒绝时点击卡片应复用说明及系统设置引导，不能反复直接 launch；Task 1 用真实 PermissionState 的拒绝结果覆盖。
- 手动调用 refresh 的测试不能证明 ON_START/ON_RESUME 链路；Task 1 单列系统设置返回验收，缺少模拟器时明确未验证。

---

## File Structure

- Create: `rikkahub/app/src/main/java/me/rerere/rikkahub/ui/components/ui/permission/LocalNetworkPermissionCard.kt`：上游卡片和受 SDK 保护的状态创建入口。
- Modify: `rikkahub/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPage.kt`：创建状态并按条件加入 LazyColumn。
- Test: `rikkahub/app/src/androidTest/java/me/rerere/rikkahub/ui/components/ui/permission/LocalNetworkPermissionCardTest.kt`：真实卡片、状态、点击及刷新验证，测试替身只放本文件。
- Modify: `rikkahub/app/src/main/res/values/strings.xml`、`values-zh/strings.xml`、`values-zh-rTW/strings.xml`、`values-ar/strings.xml`、`values-de/strings.xml`、`values-ja/strings.xml`、`values-ko-rKR/strings.xml`、`values-ru/strings.xml`（后七项均位于同一 `res/` 下）：只更新 `permission_local_network_desc`。
- Modify: `README.md`：简述 Android 17 局域网授权入口。
- Read only: `PermissionState.kt`、`RememberPermissionState.kt`、`PermissionManager.kt`、`PermissionTypes.kt`、`SettingWebPage.kt`；不为测试重构这些文件。

### Task 1: 移植权限卡片并验证实际权限调用

**Interfaces:**
- Consumes: `rememberPermissionState(info: PermissionInfo): PermissionState`、`PermissionLocalNetwork`、`PermissionManager(permissionState: PermissionState, content: @Composable () -> Unit = {})`。
- Produces: `@Composable fun rememberLocalNetworkPermissionState(): PermissionState?`，SDK < 37 返回 null，SDK >= 37 才创建状态。
- Produces: `@Composable fun LocalNetworkPermissionCard(permissionState: PermissionState, modifier: Modifier = Modifier)`；权限卡片显式调用现有 `requestPermissions()`。
- SettingPage 仅在 `localNetworkPermission?.allRequiredPermissionsGranted == false` 时增加键为 `localNetworkPermission` 的 item，位置在 generalSettings 前；未授予时不要重复增加 PermissionManager。

- [ ] **Step 1: 写真实 Compose 回归测试。**沿用 DrawerDeletionUiTest 的宿主方式及 MaterialExpressiveTheme。构造真实 PermissionState，测试 ContextWrapper 仅控制 `checkPermission`，记录现有 single/multiple launchers，不替换卡片或请求逻辑。

  | 测试名 | 必须断言 |
  | --- | --- |
  | `cardRequestsPermissionOnlyAfterClick` | `assertEquals(0, singleLaunches.size)`；真实卡片点击后 `assertEquals(listOf(Manifest.permission.ACCESS_LOCAL_NETWORK), singleLaunches)`；multiple 次数为 0 |
  | `refreshUpdatesGrantedState` | 初始 `assertFalse(state.allRequiredPermissionsGranted)`；测试 context 改为授权、调用真实 refresh 后 `assertTrue(state.allRequiredPermissionsGranted)`；宿主按生产条件隐藏卡片 |
  | `permanentDenialUsesExistingRationale` | 向真实 state 交付 false 结果后再次点击，原 launcher 次数不增加；现有说明对话框含 `R.string.permission_go_to_settings`，取消后可继续操作 |
  | `stateFactoryRespectsDeviceSdk` | 在实际 SDK < 37 时 `assertNull(state)`；实际 SDK >= 37 时 `assertNotNull(state)` 且权限集合包含 ACCESS_LOCAL_NETWORK |

  假权限 Context 不传给 rememberPermissionState（它需要实际 ComponentActivity）；状态工厂测试使用真实宿主。SDK 分段按实际设备执行，不能修改 Build.VERSION 或声称一台模拟器覆盖两个系统。

- [ ] **Step 2: 取得 RED 证据。**运行 `.\gradlew.bat :app:compileGithubDebugAndroidTestKotlin`。新增接口尚不存在引发的编译失败只算准备检查；补最小可编译声明后，在可用模拟器运行以下命令，确认卡片缺失或点击未请求的实际断言失败。没有可用 Android 环境时记录运行阻塞，不将未运行测试写成 RED。

  `.\gradlew.bat :app:connectedGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.components.ui.permission.LocalNetworkPermissionCardTest`

- [ ] **Step 3: 实现并接入。**按指定上游提交移植上述两个接口及卡片外观、Cookie9Sided 形状和主题色，在 SettingPage 接入条件 item。SDK 判断必须包住 PermissionLocalNetwork 的访问。同步八套用途说明，包含自建服务连接及 Web 访问两类场景，更新 README；保留上游来源于提交说明中。

- [ ] **Step 4: 验证 GREEN 和实际页面。**重新编译并运行相同 instrumentation；预期所有已执行测试通过。API 37 模拟器可用时验收首次拒绝、再次请求、永久拒绝引导、系统设置授予后返回卡片消失；API <= 36 可用时打开实际设置页确认无卡片、无崩溃。检查 SettingPage 中条件插入 item 的实际代码，宿主测试里的相同条件不能替代该检查。真实系统授权链与测试 context 的 refresh 证据分别记录。

- [ ] **Step 5: 独立审查。**按 requesting-code-review 检查本任务实际 diff，确认 SDK 保护、设置页接入、授权流程和八套资源均符合设计，处理实际发现后再进入最终验证。

- [ ] **Step 6: 完整验证。**运行 `.\gradlew.bat :app:testGithubDebugUnitTest`、`.\gradlew.bat :app:assembleGithubDebug` 和根目录 `git diff --check`；若两项功能在同一验证批次，最终完整测试与构建只需覆盖合并后的精确 HEAD，不重复无变化的检查。

- [ ] **Step 7: 提交本任务。**仅 staging 本任务文件和对应文档，提交 `feat(settings): add local network permission prompt`，使用正文标明 `Upstream: rikkahub/rikkahub@903e058b9361bff71d0b79f4ef2d8ac23d6597e6`。

## Verification Limits and Handoff

本机历史上存在 SDK 37.2 元数据/许可问题，执行前检查现状；环境失败与源码失败分开报告，不伪造 SDK 元数据，不改全局配置来绕过。已有 CI 默认构建 Release，不能把其结果记作 Debug 或 instrumentation 通过；需使用满足前置条件的隔离 Android 构建环境完成对应命令，无法完成的项目明确留下限制。Spotless 若缺少 origin/master，记录基线限制，不创建伪造 ref。

本计划没有推送、PR 合并、发布或真机安装步骤。完成后报告实际命令、RED/GREEN、构建结果、审查结论和未执行的 Android 验收项。执行方式待用户审阅选择。
