# RikkaHub Sol

基于 [RikkaHub](https://github.com/rikkahub/rikkahub) 的个人 Android AI 聊天客户端，重点增强**会话归档、回收站与会话分支对比**。本仓库维护修改后的完整源码、APK 构建流程和签名版下载。

适合在同一个助手下反复尝试不同提问、模型回复或对话走向，并希望整理会话、找出分歧起点的使用者。应用沿用上游的多供应商聊天、多模态输入、Markdown、联网搜索、MCP、Skills 和工作区等能力。

这是独立维护的个人衍生版本；上游项目与官方版本见 [RikkaHub](https://github.com/rikkahub/rikkahub)。

[下载最新签名版](https://github.com/snake-aabb-wtf/rikkahub-sol/releases/latest) · [全部版本](https://github.com/snake-aabb-wtf/rikkahub-sol/releases) · [构建记录](https://github.com/snake-aabb-wtf/rikkahub-sol/actions/workflows/rikkahub-apk.yml)

## 下载安装

从 [最新 Release](https://github.com/snake-aabb-wtf/rikkahub-sol/releases/latest) 下载 `app-github-arm64-v8a-release.apk`，允许安装来自该来源的应用后安装。

| 项目 | 说明 |
| --- | --- |
| 系统要求 | Android 8.0（API 26）及以上 |
| 当前发布架构 | arm64-v8a，适用于大多数现代 Android 手机 |
| 应用名称 | RikkaHub Sol |
| 签名版包名 | `me.rerere.rikkahub.sol` |
| 与官方版共存 | 支持，包名不同 |
| 模型配置 | 安装后自行配置模型供应商和访问凭据 |

签名版使用固定发布证书，并启用 APK v1 / v2 / v3 签名。升级时使用相同包名、相同证书且构建号更高的安装包，可以覆盖安装；重要记录建议先备份。各版本的变更、附件及校验信息以对应 Release 为准。

**从旧 Debug 版迁移：**旧包名为 `me.rerere.rikkahub.sol.debug`，与当前签名版是两个独立应用。先在旧版中备份或导出，再在签名版中恢复或导入。当前 Release 提供 arm64 签名版，不要将历史 Debug 或 universal 包当作当前版本。

## 增强功能

### 会话归档与回收站

- **归档整理：**归档后的会话从普通列表隐藏，在侧栏“已归档”中按当前助手查看；仍可打开继续聊天，也可取消归档。继续聊天不会自动解除归档。
- **删除可恢复：**普通删除会先移入回收站，可撤销或稍后恢复。回收站汇总所有助手的已删除会话。
- **隔离已删除内容：**回收站中的会话不参与普通列表和搜索，也不能通过普通聊天入口继续打开。
- **明确的永久删除：**回收站支持逐条永久删除与清空，操作前有确认。恢复会话后回到普通列表。

### 会话分支对比

在历史会话列表中，长按会话进入多选，选择**至少 2 个会话**，点击“对比分支”。支持全选与清空选择；当前没有数量硬上限，超过 32 个时会提示建图可能较慢。

对比范围为**当前助手的普通可见会话**，不含归档和回收站。进入对比页后，可以通过顶部标签移除会话，或用添加按钮继续追加。

对比页直接使用**自由画布**，支持双指缩放、拖动平移和重置视图，查看共同主干与整体分叉结构。大分叉和超长消息链可按需展开；大图通过视口裁剪和卡片数量限制保持可操作。

点击节点可跳回原会话的对应消息；共同节点会先让你选择来源会话，返回时保留画布的缩放与位置。

**对比规则：**读取每条会话当前选中的消息序列，按角色与内容精确比较，将相同的消息前缀合并成共同主干。一旦分叉，后续即使出现相同文本也不会重新汇合。消息 ID、模型名和时间戳不作为内容差异；不做语义相似度合并，也不推断真实的 fork 来源。

可读取的本地文件附件按完整文件的 SHA-256 比较，包括工具输出中嵌套的附件；网络地址、非文件 URI 或不可读取的附件退回地址比较。会话很多或附件较大时，首次建图可能耗时，可以取消。对比页只读，不修改原会话。

## 仓库结构

```text
.
├── README.md                        本衍生版本的入口说明
├── .github/
│   ├── workflows/rikkahub-apk.yml   当前仓库的 APK 构建、签名与校验
│   └── ci/google-services.json      编译用 Firebase 占位配置
└── rikkahub/                        Android 工程与上游源码
    ├── app/                        应用、会话管理与分支对比
    ├── ai/                         模型供应商与消息抽象
    ├── web-ui/                     内嵌 Web 界面源码
    ├── web/                        内嵌服务与 Web 静态资源打包
    ├── workspace/、terminal/        工作区与终端
    ├── search/、speech/、mediagen/   搜索、语音与媒体生成
    └── docs/superpowers/            本地增强功能的设计与实现记录
```

Android Studio 应打开 **`rikkahub/`**。该目录还保留上游文档和辅助文件；本衍生版本的安装、构建与功能差异以本 README 为准。GitHub Actions 使用根目录 `.github/workflows/`，不会执行 `rikkahub/.github/workflows/` 中保存的上游工作流。

## 从源码构建

### 环境

- JDK 21（CI 使用 Temurin）。
- Android SDK：项目使用 API 37，应用的 compile SDK 配置为 37.2；需配置 SDK 路径并接受相关许可。
- Android NDK 和 CMake 3.22.1，用于工作区与终端的原生代码。
- Node.js 22、pnpm 11，用于构建内嵌 Web 界面。
- 使用仓库自带的 Gradle Wrapper；无需另外安装 Gradle。

`rikkahub/gradle.properties` 默认给 Gradle 分配最大 8 GB 堆内存，内存较少的机器可调整 `org.gradle.jvmargs`。

### 本地 Debug 构建

Linux / macOS：

```bash
git clone https://github.com/snake-aabb-wtf/rikkahub-sol.git
cd rikkahub-sol
cp .github/ci/google-services.json rikkahub/app/google-services.json
cd rikkahub/web-ui
pnpm install --frozen-lockfile
cd ..
./gradlew :app:assembleGithubDebug
```

Windows PowerShell：

```powershell
git clone https://github.com/snake-aabb-wtf/rikkahub-sol.git
Set-Location rikkahub-sol
Copy-Item .github/ci/google-services.json rikkahub/app/google-services.json
Set-Location rikkahub/web-ui
pnpm install --frozen-lockfile
Set-Location ..
.\gradlew.bat :app:assembleGithubDebug
```

Debug APK 输出到 `rikkahub/app/build/outputs/apk/github/debug/`，包名带 `.debug` 后缀。

编译需要 `app/google-services.json`。仓库中的占位文件覆盖本版本包名，仅用于让本地和 CI 编译通过，不提供可用的 Firebase 后端。Gradle 会在构建时运行 Web 前端构建，因此首次构建前必须安装 `web-ui` 的 pnpm 依赖。

在 `rikkahub/` 下运行应用 JVM 单元测试：

```bash
./gradlew :app:testGithubDebugUnitTest
```

Windows 使用 `.\gradlew.bat :app:testGithubDebugUnitTest`。

### 签名与发布

[APK 工作流](.github/workflows/rikkahub-apk.yml) 在 `main` 的相关源码或构建配置变更时运行，也可手动触发。默认流程为：安装前端依赖 → 应用单元测试 → `:app:assembleGithubRelease` → 对齐与签名 → 签名校验 → 上传 Actions 构建产物。

签名配置使用以下 GitHub Actions secrets：

```text
RIKKAHUB_KEYSTORE_BASE64
RIKKAHUB_KEYSTORE_PASSWORD
RIKKAHUB_KEY_ALIAS
RIKKAHUB_KEY_PASSWORD
```

本地执行 `:app:assembleGithubRelease` 生成的是未签名 APK，发布签名由工作流单独完成。自己构建的 APK 不会自动获得本仓库的发布证书。当前工作流**不自动创建 GitHub Release**；对外下载以 Releases 中实际上传的附件为准。

## 上游与维护

`rikkahub/` 通过 Git subtree 引入，上游为 [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)。同步上游时需处理本版本会话管理、分支对比、应用包名与签名配置的差异，并在合并后验证构建和测试。

反馈问题时，请提供 Release 标签或构建号、Android 版本、机型和复现步骤。与本版本增强功能相关的问题可在[本仓库 Issues](https://github.com/snake-aabb-wtf/rikkahub-sol/issues) 中记录。

## 许可证与致谢

感谢 RikkaHub 上游作者及贡献者。本项目源代码沿用 [GNU Affero General Public License v3.0（AGPL-3.0）](LICENSE)；终端等第三方组件保留各自的许可证与来源说明。
