# RikkaHub Sol 开发指南

本文件适用于整个仓库，记录这个衍生版本的结构、工作方式和需要保留的行为。用户当前的任务要求优先；进入子目录修改时，还应阅读对应的子目录说明。

## 开始之前

1. 阅读根 [README.md](README.md)，了解本版本的用途、安装方式和构建前置条件。
2. 修改 Android 工程前阅读 [rikkahub/AGENTS.md](rikkahub/AGENTS.md)，其中解释了会话模型、模块职责与 Material 3 Expressive 设计规范。
3. 修改 Web 界面前再阅读 [rikkahub/web-ui/CLAUDE.md](rikkahub/web-ui/CLAUDE.md)。该目录的 `AGENTS.md` 当前只有 `CLAUDE.md` 这一行文本，需要继续打开实际文件；命令以当前 `package.json` 为准。
4. 检查 `git status --short`，阅读涉及文件和对应测试，再查看相关提交的 diff。保留已有未提交改动，控制修改范围。

## 仓库与历史的边界

- 这是 **RikkaHub Sol Android 衍生版本**，主要定制为会话归档、回收站、会话分支对比及签名 APK 构建。应用名称为 RikkaHub Sol，发布包名为 `me.rerere.rikkahub.sol`，Debug 包名追加 `.debug`。Kotlin namespace 仍为 `me.rerere.rikkahub`；仓库改名不意味着需要批量改源码包名。
- 默认开发分支是 `main`。根目录负责本版本的文档、许可证与 `.github/` 构建配置；真正的 Gradle 工程在 `rikkahub/`，根目录没有 Gradle 或 Web 应用入口。
- `rikkahub/` 通过 Git subtree 引入，保留上游历史。`material3/material-color-utilities/` 已改为普通源码目录，不是待初始化的 submodule。同步上游时保留这些本地差异，不要直接用上游目录覆盖它们。
- GitHub 只执行根目录 `.github/workflows/`；`rikkahub/.github/` 保存的是上游文件。
- `rikkahub/docs/superpowers/` 是设计与实现过程记录。部分早期方案已被后续提交替代，不能据此恢复 8 会话上限、消息 ID 指纹缓存或附件大小降级策略。
- `HANDOFF.md`、`.superpowers/` 是根 `.gitignore` 明确排除的本地内容。提交持久文档时使用仓库相对路径，不把个人机器目录或临时交接状态写进项目规则。

查看历史时先区分本地演进和导入前的上游演进：

```bash
# 本仓库的主线演进，避免被整段上游历史淹没
git log --first-parent --oneline

# 导入后的某个文件及其具体修改
git log --oneline -- rikkahub/path/to/file
git show <commit> -- rikkahub/path/to/file

# 导入前的上游历史：路径当时没有 rikkahub/ 前缀
git log 5ad19405 -- app/src/main/java/me/rerere/rikkahub/service/ConversationSession.kt
```

关键历史入口如下。它们用于解释约束，当前行为仍须与最新源码、测试核对。

| 提交 | 需要理解的背景 |
| --- | --- |
| `d474d299`、`df052e6c` | subtree 导入与 material-color-utilities 去 submodule 化 |
| `9c5331d4`（上游） | 内存会话成为唯一权威，避免旧快照覆盖与回复写错节点 |
| `81e83aee`、`30d7d5fe`、`317f53f5` | 回收站写入保护、附件清理顺序、归档串行化与删除入口一致性 |
| `cc1d66b5` | 指纹中的可见字段、工具字面路径与附件的不同语义 |
| `ddae2899`、`7f5c9ebd`、`ce21f94b`、`20629c05` | 可取消建图、分支变化检测、全文件哈希、大图渲染与最新同步胜出 |
| `e4212c33`、`b288dc28` | 去除会话数量上限、全选，以及构建中的添加入口状态 |
| `0af219ab`、`77c9fb08` | 外部发布签名与 v1 兼容校验 |

## 找代码

下表中的路径相对 `rikkahub/`；应用内的包路径为 `app/src/main/java/me/rerere/rikkahub/`。

| 任务 | 主要入口 |
| --- | --- |
| 启动与依赖注入 | 应用内 `RikkaHubApp.kt`、`di/`；恢复备份发生在 Koin 初始化之前 |
| 会话状态与持久化 | 应用内 `service/` 下的 `ChatService.kt`、`ConversationSession.kt`、`ConversationSessionManager.kt`，以及 `data/repository/ConversationRepository.kt` |
| 数据模型与数据库 | 应用内 `data/model/`、`data/db/`、`data/datastore/`；Room 导出结构在 `app/schemas/` |
| 归档、回收站、历史列表 | 应用内 `ui/pages/` 下的 `archive/`、`trash/`、`history/`；侧栏入口见 `ui/pages/chat/ChatDrawer.kt` |
| 会话分支对比 | 应用内 `data/model/comparison/`、`ui/pages/comparison/` 及其 `views/` |
| 模型请求与工具 | `ai/` 中的供应商与消息协议；应用内 `data/ai/` 中的请求构造、转换器、工具与 MCP |
| Web 访问 | 应用内 `web/routes/`、`web/dto/`；`web/` 为 Android 内嵌 Ktor 模块，`web-ui/` 为 React Router 前端 |
| 工作区与终端 | `workspace/`、`terminal/`；修改终端兼容层先读 `terminal/README.md` |
| 公共能力 | `search/`、`speech/`、`document/`、`mediagen/`、`oauth/`、`common/`、`ui/`、`material3/` |

新增依赖或 ViewModel 时沿用现有模块边界与 Koin 注册方式。Android UI 遵循子目录指南中的 Material 3 Expressive、`MaterialExpressiveTheme` 和 `CardGroup`；跨前后端的数据字段同时检查 Kotlin DTO、TypeScript 类型和消费方。

## 会话与数据安全约束

- **活跃会话从内存状态修改。**通过 `ChatService` 在 `ConversationSession` 的最新状态上做变换并持久化，不把 DAO 旧对象、列表摘要或 UI 持有的整份快照保存回去。会话级模型与参数读取会话的 `ConversationConfig` 快照，不能直接退回助手当前配置。
- **保留写入的并发保护。**元数据更新与 `flush` 共用写锁；删除、恢复等替换操作保留 `replace` 的任务取消/等待和 incarnation 校验。不能为了减少查询而删除服务层检查或事务内的删除状态复查。
- **拒绝写回已删除会话。**`updateConversationIfVisible` 需要同时拒绝回收站行和已不存在的行。只有数据库写入成功，才可执行未引用附件清理；拒绝写入时数据库可能仍引用旧附件。
- **状态语义跨入口一致。**普通删除移入回收站；普通会话永久删除入口要求先在回收站。删除整个助手是清理其全部会话的特殊路径，需要包含归档与回收站。归档会话仍可继续写入，发送消息不自动取消归档；从回收站恢复回普通列表。
- **不仅修改列表 UI。**变更归档/删除语义时同时检查 DAO 查询、摘要映射、全文索引、统计、Android 打开路径和 Web API。保存与恢复状态不能丢掉 `archivedAt` / `deletedAt`；移入回收站后的旧生成任务不能将会话重新写回。
- **数据库变更保持可升级。**检查 `AppDatabase` 的迁移配置和 `app/schemas/`，随结构变更提交对应 schema，并覆盖状态映射或迁移回归；不要以清库绕过升级问题。

## 分支对比约束

- 候选为当前助手的普通可见会话，不含归档/回收站；至少 2 个即可对比，支持全选与清空，无数量硬上限。超过 32 个仅提示耗时。
- 只取每个消息节点当前选中的候选，按共同前缀建树；分叉后不重新汇合。对比页仅保留自由画布，使用 `ComparisonGraph` 解释消息；横轴表示消息层级，不是真实时间间隔。
- 指纹比较角色与可见内容，不让 ID、时间或模型名产生假分叉。新增消息 part、引用或审批状态时，检查 `ComparisonFingerprint.kt` 与对应测试；工具 JSON 中的字面路径不能直接当成附件 part 处理。
- 可读取的本地附件使用完整文件 SHA-256，不用文件大小、mtime、路径或大小阈值代替内容。文件哈希可按现有文件属性键缓存；不要恢复仅以会话/消息 ID 缓存消息指纹的策略，压缩点等内容可能原地变化。
- 建图与文件读取放后台，保留取消检查和 generation 隔离；旧 job 不得覆盖新状态。新的同步请求应使旧请求失效；读库异常与“会话不存在”要区分，异常不能导致删除选择。取消后的建图不能被返回页面的自动同步立即重启。
- 从聊天页返回对比页后，不能仅凭 `updateAt` 判断是否重建，还需检查当前分支。保存过的空选择就是空，不能从原始路由参数中“恢复”已移除会话。节点跳转前校验会话和消息节点，并保留返回栈及视口状态。
- 大图通过视口裁剪、卡片数量限制和可展开长链处理。不要创建与整张巨图等大的 Compose 容器，也不要把渲染限制变成图数据截断。构建中的添加入口保持可见但禁用；保留画布缩放、平移和返回后的视口恢复。

## 构建与验证

环境要求和完整的跨平台步骤见根 README。主要依赖为 JDK 21、Android SDK/NDK/CMake、Node.js 22 和 pnpm 11。工具版本以当前 Gradle 配置、工作流与 lockfile 为准；8 GB 的默认 Gradle 堆设置可按机器内存调整，不要为本机环境顺手提交全局配置变更。

以下命令从**仓库根目录**开始准备环境；占位配置只用于编译，不提供实际 Firebase 后端：

```bash
cp .github/ci/google-services.json rikkahub/app/google-services.json
cd rikkahub/web-ui
pnpm install --frozen-lockfile
cd ..
```

之后从 **`rikkahub/`** 运行：

```bash
./gradlew :app:testGithubDebugUnitTest
./gradlew :app:assembleGithubDebug

# 修改 ai 模块时，补跑其 JVM 测试
./gradlew :ai:testDebugUnitTest

# 对比功能的定向回归，可用于开发中快速检查
./gradlew :app:testGithubDebugUnitTest --tests 'me.rerere.rikkahub.data.model.comparison.*' --tests 'me.rerere.rikkahub.ui.pages.comparison.views.*'
```

Windows 使用 PowerShell 的 `Copy-Item` 和 `.\gradlew.bat`。Gradle 的 `web:preBuild` 会构建前端，但不会先安装 pnpm 依赖。

验证按修改范围选择：

| 修改范围 | 重点验证 |
| --- | --- |
| 根文档 | `git diff --check`，核对路径、命令和下载链接；无需仅为文档重建 APK |
| Android 应用逻辑 | 相关回归测试、`:app:testGithubDebugUnitTest` 与 `:app:assembleGithubDebug` |
| 会话生命周期、归档、删除 | `ConversationSessionTest`、`ConversationSessionManagerTest`、`ConversationStatusMapperTest`、`TrashedWriteGuardTest`；另检查实际入口与索引行为 |
| 分支对比 | `data/model/comparison/` 的测试与 `ComparisonVisibilityTest`；UI 变动检查全选、取消、返回重建、三键导航底栏和缩放/点击 |
| Web 前端 | 在 `web-ui/` 运行 `pnpm run typecheck`、`pnpm exec oxlint app`、`pnpm run fmt:check`、`pnpm run build`，并检查实际页面交互 |
| 其他模块 | 对应模块的测试与受影响的应用构建，不把应用测试当作所有模块测试 |

应用测试在 `rikkahub/app/src/test/java/me/rerere/rikkahub/`。修复并发或数据问题时，优先扩展现有 fake/store 和回归测试；不要只验证新 helper，遗漏调用链和原始故障。

两处工具细节容易误用：

- **Spotless 基线：**`rikkahub/build.gradle.kts` 仍使用 `ratchetFrom("origin/master")`，普通克隆只有 `origin/main`。先用 `git rev-parse --verify origin/master` 检查；基线缺失时说明限制，不把它报成代码格式错误。不要临时伪造 `origin/master`，也不要将未带 subtree 前缀的上游树直接用作基线而批量格式化源码。需要修正基线时单独处理，避免顺手重排整个仓库。
- **Web 工具会写文件：**`pnpm run lint` 实际带 `--fix`，只读检查用 `pnpm exec oxlint app`；`pnpm run build` 会重建并复制到 `web/src/main/resources/static/`。不要手改生成的静态资源。`:web` 是 Android library，旧说明中的 `:web:run` 不是当前可用的服务启动方式。

`web-ui` 使用 SPA 模式，服务由 Android 应用启动，不是独立的 Node SSR 部署项目。`tools/i18n`、`tools/trace` 各自使用 Bun；修改这些工具时阅读各自 README，在对应目录安装依赖与运行 `bun test`，不要用顶层 pnpm 安装替代。

报告实际运行的命令和结果。若缺少 SDK、网络、设备或签名材料，明确未验证的部分；JVM 测试与编译通过不代表真机手势、导航和安装已验证。

## 签名、发布与提交边界

- 当前构建入口是根 [`.github/workflows/rikkahub-apk.yml`](.github/workflows/rikkahub-apk.yml)：应用单测 → 默认 `:app:assembleGithubRelease` → 对齐/签名/校验 → 上传 Actions artifacts。工作流不自动创建 GitHub Release。
- 本地 Release 构建不带发布签名。CI 通过 `RIKKAHUB_KEYSTORE_BASE64`、`RIKKAHUB_KEYSTORE_PASSWORD`、`RIKKAHUB_KEY_ALIAS`、`RIKKAHUB_KEY_PASSWORD` 恢复固定证书，外部启用 v1/v2/v3。保留 v1 校验中的 `--min-sdk-version 23`；不要用临时 Debug 证书替换发布证书。
- 手动输入 `gradle_task` 不会同时改变后续签名步骤固定的 `github/release` 路径，不要把它当作任意构建类型的通用打包器；签名流程改动需核对最终 APK、证书 CN 和签名校验结果。
- 普通修复不顺手改包名、渠道、签名或版本号。发布升级需要更高的 `versionCode` 和相同的包名/证书；正式版与旧 `.debug` 版的数据空间不同。`github` / `play` 的差异继续通过 `BuildConfig.IS_PLAY_STORE` 处理。
- CI 使用 runner 已有 Android SDK，不要重复引入历史上已移除的 `setup-android` 或假设 `sdkmanager` 在 PATH。变更 runner/工具链时先核实实际环境。
- 不提交本机 `local.properties`、签名私钥、真实凭据、构建缓存或生成 APK。保留根 `LICENSE`、上游许可证及第三方来源说明。
- 提交聚焦一个目的，沿用 `feat`、`fix`、`test`、`docs`、`build`、`ci` 等历史前缀。功能、安装或构建流程变化时同步根 README；协作约束变化时更新本文件。提交前检查完整 diff，确保没有夹带格式化或生成文件。
