# 压缩保留消息数输入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 支持直接填写压缩时保留的消息数，并对空值、格式、溢出、当前会话上限及加减按钮使用同一套规则。

**Architecture:** 从上游 e85c1d90 移植可编辑数字入口，在一个小型输入文件中集中解析、范围和步进逻辑。FilesPicker 已持有 Conversation，传入最后一个当前选中检查点之后的节点数；真实弹窗在显示、步进和确认时使用这些规则，ChatService 的最新会话检查保持不变。

**Tech Stack:** Kotlin、Jetpack Compose、Material 3 Expressive、JUnit4、现有 Compose instrumentation、kotlinx.coroutines.Job。

**Spec:** `rikkahub/docs/superpowers/specs/2026-11-06-upstream-permission-compression-design.md`；本计划只负责压缩输入部分，局域网权限见同目录 `2026-11-06-local-network-permission.md`。

## Global Constraints

- 所有文件路径相对仓库根；Gradle 命令从 `rikkahub/` 运行，Windows 使用 `gradlew.bat`。
- 默认保留数为 0，说明全部参与本次压缩且原始历史保留；保留额外提示词、现有取消和执行中行为，不记忆跨次打开的输入。
- 空输入、负数、小数、混杂文本和溢出不能确认，不能回退为 0；过大值不静默截断或改为上限。
- 全角数字转半角，前后空白可忽略，前导零不改变数值；不采用上游的四位截断规则。
- N 为最后一个当前选中检查点之后的节点数；N > 0 时有效范围 0..N−1；N = 0 时不存在合法提交。加减与手动输入共用范围，会话变化后重算。
- 不改 ChatService、ChatVM、ChatPage 接口、检查点持久化、数据库、包名、签名、版本号、分支画布或其他上游提交；不新增依赖。
- 复用隔离 worktree 和功能分支 `codex/upstream-permission-compression`，执行前由 using-git-worktrees 核实基线和已有改动；与权限计划共享的 README 由协调者整合。
- 真机操作保持取消；依赖如需联网下载，在沙箱外进行。环境故障、未执行的 UI 测试和真正失败分别记录。

## Review Focus

- 粘贴 `000012`、全角 `００１２` 或 ` 12 `必须得到 12，不能变成 0；Task 1 的标准化断言覆盖。
- `-1`、`1.5`、`1a2`、内部空格、非约定 Unicode 数字及整数溢出不能变成另一个有效数；Task 1 覆盖格式，Task 2 证明确认回调未调用。
- 一个节点含多个候选时，只认 currentMessage 中的检查点；Task 1 使用真实 MessageNode/selectIndex 夹具覆盖。
- 编辑期间 N 缩小必须让原输入变为无效，不悄悄改值；Task 1 重算验证，Task 2 用可变 N 验证真实弹窗。
- 点击确认及取消的实际 Job 行为不能只用 helper 测试代替；Task 2 验证传入数值、无效提交次数、执行中状态和取消。

---

## File Structure

- Create: `rikkahub/app/src/main/java/me/rerere/rikkahub/ui/components/ai/CompressionKeepRecentInput.kt`：输入验证、步进和只读节点计数。
- Test: `rikkahub/app/src/test/java/me/rerere/rikkahub/ui/components/ai/CompressionKeepRecentInputTest.kt`：纯输入规则及真实会话/候选计数。
- Modify: `rikkahub/app/src/main/java/me/rerere/rikkahub/ui/components/ai/CompressContextDialog.kt`：输入控件、错误、按钮和确认校验。
- Modify: `rikkahub/app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt`：唯一调用点传入节点数。
- Test: `rikkahub/app/src/androidTest/java/me/rerere/rikkahub/ui/components/ai/CompressContextDialogTest.kt`：实际弹窗的编辑及回调测试。
- Modify: `rikkahub/app/src/main/res/values/strings.xml`、`rikkahub/app/src/main/res/values-zh/strings.xml`：必要提示和可访问文案。
- Modify: `README.md`：简述手动输入及范围校验。
- Read only: `ChatService.kt:829` 的压缩范围与检查，现有 `ConversationCheckpointTest`、`CompressionSummaryTest`。

### Task 1: 集中输入、范围和步进规则

**Interfaces:** 全部为 `internal`，仅供同一 app 使用，不新增持久化类型。

```kotlin
enum class CompressionInputError { EMPTY, INVALID_INTEGER, OUT_OF_RANGE, NOTHING_TO_COMPRESS }
data class CompressionKeepRecentInput(val value: Int?, val maximum: Int?, val error: CompressionInputError?)
fun validateCompressionKeepRecent(text: String, messagesAfterLastCheckpoint: Int): CompressionKeepRecentInput
fun stepCompressionKeepRecent(text: String, messagesAfterLastCheckpoint: Int, increase: Boolean): String
fun Conversation.messagesAfterLastContextCheckpoint(): Int
```

`value` 只在有效时非 null；有效时 error 为 null。N <= 0 时 maximum 和 value 为 null，error 为 NOTHING_TO_COMPRESS；N > 0 时 maximum 为 N−1。空白输入用 EMPTY，非完整整数或溢出用 INVALID_INTEGER，解析成功但超过上限用 OUT_OF_RANGE。只转换 `０..９` 后验证完整 ASCII 十进制文本；去掉前导零后再解析，避免很多前导零造成假溢出。步进前校验，输入无效或方向已达边界时原样返回文本，正常步进返回标准十进制字符串。

- [ ] **Step 1: 写失败单测。**测试夹具用真实 UIMessage、Conversation、MessageNode；检查点用 `UIMessage.user("summary").copy(isContextCheckpoint = true)`，不替换生产计数逻辑。至少包含下列命名测试和断言：

  | 测试名 | 断言（省略重复的 validate 调用参数名） |
  | --- | --- |
  | `acceptsNormalizedDecimalInput` | N=21 时，`"0" → value=0`、`"20" → 20`、`"000012" → 12`、`"００１２" → 12`、`" 12 " → 12`，error 均 null；大量前导零后接 12 也为 12 |
  | `rejectsEmptyAndNonIntegerInput` | `""`、`"   "` 的 value 为 null、error=EMPTY；`"-1"`、`"+1"`、`"1.5"`、`"1a2"`、`"1 2"`、`"1e2"`、阿拉伯数字 `"١٢"`、`"2147483648"`、100 位 9 的 value 为 null、error=INVALID_INTEGER |
  | `usesLiveMessageCountBounds` | `validate("20",21).value == 20`；`validate("21",21).error == OUT_OF_RANGE`；`validate("20",20).error == OUT_OF_RANGE`；`validate("0",1).value == 0`；`validate("0",0).error == NOTHING_TO_COMPRESS` |
  | `stepsOnlyWithinTheSameValidRange` | N=21 时 `step("0",21,false)=="0"`、`step("20",21,true)=="20"`、`step("００１２",21,true)=="13"`；空/无效/过大输入两方向原样返回；N=Int.MAX_VALUE 时从 2147483646 增加仍为原数，避免溢出 |
  | `countsOnlyNodesAfterTheLastSelectedCheckpoint` | 无检查点 4 节点→4；两个检查点只计最后一个之后；检查点位于末尾→0；一个节点→1；空会话→0；同一节点未选中的检查点候选不影响数量，切换 selectIndex 后数量变化 |

- [ ] **Step 2: 运行并记录 RED。**`.\gradlew.bat :app:testGithubDebugUnitTest --tests 'me.rerere.rikkahub.ui.components.ai.CompressionKeepRecentInputTest'`。缺少接口的编译错误只算准备；补最小可编译声明后，取得不满足输入/计数断言的失败证据，再实现行为。不得以手写的算法副本替代生产文件运行测试。

- [ ] **Step 3: 实现上述接口。**计数公式为 `messageNodes.size - messageNodes.indexOfLast { it.currentMessage.isContextCheckpoint } - 1`。验证只读，不修改文本或会话；step 先比较边界再加减。UI 不再另写一份解析或范围算法。

- [ ] **Step 4: 运行 GREEN 和检查点回归。**先重跑同一定向测试，再运行 `.\gradlew.bat :app:testGithubDebugUnitTest --tests 'me.rerere.rikkahub.data.model.ConversationCheckpointTest' --tests 'me.rerere.rikkahub.data.ai.CompressionSummaryTest'`，预期全部通过。

- [ ] **Step 5: 审查本任务。**确认只读计算与 ChatService 的范围语义一致，所有输入测试覆盖批准设计，`git diff --check` 通过。

- [ ] **Step 6: 提交本任务。**提交 `feat(chat): validate compression keep counts`；为 Task 2 提供以上精确接口。

### Task 2: 在实际弹窗接入手动输入与有效范围

**Interfaces:**
- Consumes: Task 1 的四个接口及类型；现有 `onConfirm: (additionalPrompt: String, keepRecentMessages: Int) -> Job`。
- Produces: `@Composable fun CompressContextDialog(messagesAfterLastCheckpoint: Int, onDismiss: () -> Unit, onConfirm: (additionalPrompt: String, keepRecentMessages: Int) -> Job)`；唯一 FilesPicker 调用使用 `conversation.messagesAfterLastContextCheckpoint()`。
- 输入状态为原始 String，初始 `"0"`；验证结果随文本和 N 重算。确认点击内部再次验证且仅在 value 非 null 时调用 onConfirm。加减按钮仅在有效且对应方向未达边界时启用，调用 Task 1 的 step。

- [ ] **Step 1: 为真实弹窗写失败 Compose 测试。**使用现有 Compose rule、MaterialExpressiveTheme、实际输入标签定位控件；onConfirm 记录参数并返回未完成的真实 `Job()`，不调用模型或写数据库。用现有资源获取 Confirm/Cancel 文案，避免依赖模拟器语言。

  | 测试名 | 必须断言 |
  | --- | --- |
  | `submitsExactNormalizedKeepCount` | N=21，输入 `"００１２"` 及额外提示词后确认，回调恰好一次且参数为原提示词和 12；默认 0 也准确传递 |
  | `invalidTextCannotStartCompression` | 空、`"-1"`、`"1a2"`、超长数字、`"21"` 在 N=21 时 Confirm disabled、两步进 disabled、回调次数为 0；恢复合法值后可确认 |
  | `buttonsRespectBoundsAndNoMessages` | N=1 时值 0 可确认而两步进禁用；N=0 时确认禁用并有无消息说明；N=21 的 0/20 分别禁用减/加，其他方向准确变化 |
  | `messageCountChangeInvalidatesWithoutClamping` | 使用可变 N=21 输入 20，重组为 N=20 后仍显示文本 20，Confirm disabled，出现范围 0..19，回调为 0 |
  | `cancelsOnlyTheStartedJob` | 合法确认后显示既有执行中 UI；点击 Cancel 后记录的 Job.isCancelled 为 true；未开始时取消只调用 onDismiss |

- [ ] **Step 2: 取得 UI RED。**编译 `.\gradlew.bat :app:compileGithubDebugAndroidTestKotlin` 后，在可用模拟器运行 `.\gradlew.bat :app:connectedGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.ui.components.ai.CompressContextDialogTest`，记录原控件不能编辑或未阻止无效提交的断言失败。无模拟器时保留为未执行，不把编译成功当作 UI 通过。

- [ ] **Step 3: 接入输入与资源。**用项目现有 Material 3 数字输入样式替换静态数值，保留两侧按钮，提供明确的 `chat_page_compress_keep_recent` 输入标签、数字键盘、单行及就地错误。保留原始编辑文本，禁止字符过滤后拼接数值和 `toIntOrNull() ?: 0`。补齐英/简中文案：`chat_page_compress_keep_recent_required`、`chat_page_compress_keep_recent_invalid`、`chat_page_compress_keep_recent_out_of_range`（最大值 `%1$d`）、`chat_page_compress_keep_recent_decrease`、`chat_page_compress_keep_recent_increase`；无消息沿用 `chat_page_compress_nothing_to_compress`，说明补充原始历史保留。FilesPicker 传入节点数，README 补充操作说明。文本框布局应在窄屏与大字体保持按钮和输入可用，不缩小点击区域来挤下整行。

- [ ] **Step 4: 验证 GREEN。**重跑定向 JVM、检查点与 Compose 测试，预期全部已执行测试通过。可用模拟器上检查数字键盘、粘贴、错误朗读、窄屏/大字体布局、取消及重新打开恢复 0；没有模拟器时明确未验收这些交互。

- [ ] **Step 5: 独立审查。**reviewer 检查实际 FilesPicker→Dialog→校验→onConfirm 调用链、确认前复查和原服务保护未变，处理发现后进入最终验证。

- [ ] **Step 6: 完整验证。**运行 `.\gradlew.bat :app:testGithubDebugUnitTest`、`.\gradlew.bat :app:assembleGithubDebug`、`.\gradlew.bat :app:compileGithubDebugAndroidTestKotlin`，根目录 `git diff --check`；两计划最终合并后的精确 HEAD 通过一轮即可。

- [ ] **Step 7: 提交本任务。**提交 `feat(chat): allow validated compression keep count input`，正文注明 `Upstream: rikkahub/rikkahub@e85c1d901d30fa32d3c21a423432d940d77acddb` 及 Sol 的边界增强。

## Verification Limits and Handoff

使用真实项目依赖与生产源文件；若本机 SDK/许可仍不满足，记录阻塞并在可用隔离 Android 构建环境执行，不伪造 SDK 元数据。现有 Release CI 不能证明 Debug 或 instrumentation 已通过；Spotless 缺少 origin/master 属基线限制，不伪造 ref。新 UI 不要求恢复真机测试，不能把 JVM GREEN、测试 APK 编译或代码审查等同于 Android UI 验收。

完成全部任务后做一次整分支 review，核对本计划和权限计划的范围、文档及实际验证结果。本计划不包含推送、合并或发布步骤；执行方式待用户审阅选择。
