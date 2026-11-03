# 会话分支对比 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把选中的若干会话按逐字相同的前缀合并成一棵谱系树，提供缩进树 / 自由画布 / 横向时间线三种可切换视图，点节点可跳回原会话。

**Architecture:** 纯 Kotlin 层负责指纹、建图、布局（可单测）；Compose 层只做三套渲染器和交互。图只构建一次，三个视图共享同一份 `ComparisonGraph`。

**Tech Stack:** Kotlin、Jetpack Compose（Material3、LazyColumn、Canvas、transformable）、Koin、navigation3、Room（只读，无 schema 变更）、JUnit4。

**Spec:** `rikkahub/docs/superpowers/specs/2026-11-03-conversation-branch-comparison-design.md`

## Global Constraints

- 不新增任何第三方依赖；不修改数据库 schema（保持 33）
- 不写会话数据：对比页只读
- 只有角色 + 可见内容逐字相同才合并；排除 id、时间戳、modelId/modelSnapshot、usage、translation、annotations、metadata
- 本地附件按文件内容 SHA-256 比较；`Tool.output` / `ToolResult.content` 里嵌套的附件也要递归处理
- 本机不能构建（无 Android SDK、1.9 GB 内存）：所有编译与单测在 GitHub Actions（`.github/workflows/rikkahub-apk.yml`）
- Kotlin 风格跟随仓库现有写法（4 空格、显式返回类型用于公开函数、`internal` 用于模块内实现）
- 所有用户可见文案走 `strings.xml`（`values/strings.xml` 与 `values-zh-rCN/strings.xml` 同步加）

## Review Focus

- 两条会话只有开场白不同：主干应为 0 长，画成两条从根分出的支线，不能崩
- 同一会话里连续两条一模一样的消息：必须产生两个节点，不能自环或死循环
- fork 出来的会话（消息 id 和附件 URL 全不同、内容相同）：必须完全合并成同一条链
- 单个会话消息数上千：建图与 A 视图不能卡死或 OOM
- 点节点时该会话已被删除（对比页停留期间）：不能崩，提示并移出选择

---

### Task 1: 内容指纹

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/model/comparison/ComparisonFingerprint.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/model/comparison/ComparisonFingerprintTest.kt`

**Interfaces:**
- Produces:
  - `fun interface FileHasher { suspend fun hash(url: String): String? }`
  - `internal suspend fun messageFingerprint(message: UIMessage, fileHasher: FileHasher): String`（返回 SHA-256 十六进制）

- [ ] **Step 1: 写失败的测试**

```kotlin
class ComparisonFingerprintTest {
    private val noFiles = FileHasher { null }

    @Test
    fun `ignores ids timestamps model and translation`() = runTest {
        val a = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi")))
        val b = a.copy(id = Uuid.random(), modelId = Uuid.random(), translation = "你好")
        assertEquals(messageFingerprint(a, noFiles), messageFingerprint(b, noFiles))
    }

    @Test
    fun `distinguishes role and text`() = runTest {
        val user = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi")))
        val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("hi")))
        val other = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi!")))
        assertNotEquals(messageFingerprint(user, noFiles), messageFingerprint(assistant, noFiles))
        assertNotEquals(messageFingerprint(user, noFiles), messageFingerprint(other, noFiles))
    }

    @Test
    fun `same content different local urls merge when bytes match`() = runTest {
        val hasher = FileHasher { "aaa" }
        val a = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image("file:///a.png")))
        val b = a.copy(parts = listOf(UIMessagePart.Image("file:///b.png")))
        assertEquals(messageFingerprint(a, hasher), messageFingerprint(b, hasher))
    }

    @Test
    fun `hashes nested attachments inside tool output`() = runTest {
        val hasher = FileHasher { "aaa" }
        // 构造两个 Tool，output 里嵌不同 URL、但字节相同
        assertEquals(messageFingerprint(tool("@/a.png"), hasher), messageFingerprint(tool("@/b.png"), hasher))
        // 字节不同则不同
        assertNotEquals(messageFingerprint(tool("@/a.png"), FileHasher { "x" }), messageFingerprint(tool("@/b.png"), FileHasher { "y" }))
    }

    @Test
    fun `json object key order does not matter`() = runTest {
        assertEquals(fingerprintOf(toolCall("""{"a":1,"b":2}""")), fingerprintOf(toolCall("""{"b":2,"a":1}""")))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :app:testGithubDebugUnitTest --tests "*ComparisonFingerprintTest*"`（本机不能跑 → push 后看 CI）
Expected: FAIL，`messageFingerprint` 未定义

- [ ] **Step 3: 实现**

规则（顺序即拼接顺序，段间用 `\u0000`，每段先写长度避免歧义）：

- 角色：`message.role.name`
- `isContextCheckpoint` 为 true 时追加 `"CP"`
- 逐 part：
  - `Text` → `"T" + text`
  - `Reasoning` → `"R" + text`
  - `Search` → `"S"`
  - `Image/Video/Audio/Document` → `"F" + 类型名 + (fileHasher.hash(url) ?: url)`
  - `ToolCall` → `"TC" + toolName + canonicalJson(arguments)`
  - `ToolResult` → `"TR" + canonicalJson(...)` + 递归处理 content 里的 part
  - `Tool` → `"TO" + name + canonicalJson(input) + canonicalJson(output)`
  - `ServerTool` → `"ST" + name + status.name + canonicalJson(...)`
  - 其他 → 类型名
- `canonicalJson`：递归按键名排序的 `JsonObject.toString()`；数组保持顺序
- 最终对整串做 SHA-256，返回小写十六进制

- [ ] **Step 4: 跑测试确认通过**

Expected: 5 个测试全绿

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/model/comparison/ComparisonFingerprint.kt \
        app/src/test/java/me/rerere/rikkahub/data/model/comparison/ComparisonFingerprintTest.kt
git commit -m "feat(compare): content fingerprint for conversations"
```

---

### Task 2: 建图

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/model/comparison/ComparisonGraph.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/model/comparison/ComparisonGraphTest.kt`

**Interfaces:**
- Consumes: `messageFingerprint`, `FileHasher`（Task 1）
- Produces:
```kotlin
data class NodeOccurrence(val conversationId: Uuid, val conversationTitle: String, val messageId: Uuid, val messageNodeId: Uuid)
data class ComparisonNode(val id: String, val parentId: String?, val depth: Int, val message: UIMessage, val occurrences: List<NodeOccurrence>, val childIds: List<String>)
data class ComparisonGraph(val rootIds: List<String>, val nodes: Map<String, ComparisonNode>) {
    val nodeCount: Int get() = nodes.size
}
suspend fun buildComparisonGraph(conversations: List<Conversation>, fileHasher: FileHasher): ComparisonGraph
```

- [ ] **Step 1: 写失败的测试**

```kotlin
@Test fun `identical conversations merge into one chain`() = runTest { ... }
@Test fun `first different reply splits immediately`() = runTest { ... }
@Test fun `same text after divergence does not merge back`() = runTest { ... }
@Test fun `empty conversation contributes nothing`() = runTest { ... }
@Test fun `repeated identical consecutive messages create two nodes`() = runTest { ... }
```

断言点：`nodes.size`、`rootIds.size`、某节点 `childIds.size`、`occurrences.map { it.conversationId }`。

- [ ] **Step 2: 跑测试确认失败** → FAIL，`buildComparisonGraph` 未定义

- [ ] **Step 3: 实现**

对每个会话：遍历 `messageNodes`，取 `node.messages[node.selectIndex]`（下标越界时跳过该节点）；`parent` 从虚拟根开始；`key = messageFingerprint(...)`；在 `parent.childIds` 里找 `nodes[child].fingerprint == key` 的孩子（ComparisonNode 增加 `internal val fingerprint: String`），找到就追加 occurrence，否则新建节点（`id = sha256(parentId + key)` 取前 16 位十六进制，冲突时追加序号）。保持 `childIds` 按首次出现顺序。

- [ ] **Step 4: 跑测试确认通过**

- [ ] **Step 5: 提交**

```bash
git commit -m "feat(compare): build merged conversation tree"
```

---

### Task 3: 布局

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/data/model/comparison/ComparisonLayout.kt`
- Test: `app/src/test/java/me/rerere/rikkahub/data/model/comparison/ComparisonLayoutTest.kt`

**Interfaces:**
- Consumes: `ComparisonGraph`（Task 2）
- Produces:
```kotlin
data class LayoutPoint(val id: String, val x: Float, val y: Float)
data class ComparisonLayout(val points: Map<String, LayoutPoint>, val maxDepth: Int, val leafCount: Int)
fun tidyLayout(graph: ComparisonGraph, columnWidth: Float, rowHeight: Float): ComparisonLayout
fun timelineLayout(graph: ComparisonGraph, columnWidth: Float, laneHeight: Float): ComparisonLayout
fun subtreeSizes(graph: ComparisonGraph): Map<String, Int>
```

- [ ] **Step 1: 写失败的测试**

```kotlin
@Test fun `single chain has one leaf and no overlaps`()
@Test fun `two branches get distinct rows`()      // 同一深度两个节点 y 不同
@Test fun `parent is centered over children`()    // y 介于首末子节点之间
@Test fun `subtree size counts descendants`()
@Test fun `timeline lanes equal leaf count`()
```

- [ ] **Step 2: 跑测试确认失败**

- [ ] **Step 3: 实现**

- `subtreeSizes`：后序遍历累加
- `tidyLayout`：DFS；叶子按顺序分配 `y += rowHeight`；内部节点 `y = 首子 y 与末子 y 的中点`；`x = depth * columnWidth`
- `timelineLayout`：DFS 叶子序号即 lane，`y = lane * laneHeight`，内部节点 `y = 子节点 y 中位数`，`x = depth * columnWidth`
- 节点 id 用 `ComparisonNode.id`；虚拟根不参与布局

- [ ] **Step 4: 跑测试确认通过**

- [ ] **Step 5: 提交**

```bash
git commit -m "feat(compare): tidy-tree and timeline layouts"
```

---

### Task 4: ViewModel、路由与页面骨架

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/comparison/ComparisonVM.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/comparison/ComparisonPage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/RouteActivity.kt`（`Screen` 增加 `Comparison`，`entryProvider` 增加分支）
- Modify: `app/src/main/java/me/rerere/rikkahub/utils/ChatUtil.kt`（新增 `navigateToComparison`、`pushToChatPage`）
- Modify: `app/src/main/java/me/rerere/rikkahub/di/ViewModelModule.kt`（注册 VM）

**Interfaces:**
- Produces:
```kotlin
@Serializable data class Comparison(val ids: List<String>) : Screen
class ComparisonVM(...) : ViewModel() {
    val state: StateFlow<ComparisonUiState>
    fun addConversation(id: Uuid)
    fun removeConversation(id: Uuid)
    fun setViewMode(mode: ComparisonViewMode)
    fun expand(id: String)
}
enum class ComparisonViewMode { INDENTED, CANVAS, TIMELINE }
```

- [ ] **Step 1: 加路由与导航函数**

```kotlin
fun navigateToComparison(navigator: Navigator, ids: List<Uuid>) {
    navigator.navigate(Screen.Comparison(ids.map { it.toString() }))
}

fun pushToChatPage(navigator: Navigator, chatId: Uuid, nodeId: Uuid?) {
    navigator.navigate(Screen.Chat(id = chatId.toString(), nodeId = nodeId?.toString()))
}
```

`RouteActivity` 的 `entryProvider` 加 `entry<Screen.Comparison> { key -> ComparisonPage(ids = key.ids) }`，转场与 `Screen.History` 一致。

- [ ] **Step 2: VM 加载与建图**

`ComparisonVM` 依赖 `ConversationRepository`、`SettingsStore`。`SavedStateHandle` 读 `ids`。`init` 里 `viewModelScope.launch { ids.forEach { repo.getConversationById(it) } ... buildComparisonGraph(...) }`，`FileHasher` 用 `context.contentResolver` 读 `file://` URI 算 SHA-256（`Dispatchers.IO`），失败返回 null。

状态：
```kotlin
data class ComparisonUiState(
    val loading: Boolean = true,
    val hashing: Boolean = false,
    val graph: ComparisonGraph? = null,
    val conversations: List<ConversationSummary> = emptyList(),
    val viewMode: ComparisonViewMode = ComparisonViewMode.INDENTED,
    val expanded: Set<String> = emptySet(),
    val error: String? = null,
)
```

- [ ] **Step 3: 页面骨架**

`Scaffold` + `TopAppBar`（返回、标题、`SingleChoiceSegmentedButtonRow` 三选一、刷新图标）+ 会话 chips 行（`InputChip`，带 ×）+ 内容区按 `viewMode` 分发到三个未实现的占位 composable（Task 5–7 填充）+ 加载/空态/错误态。

- [ ] **Step 4: 编译验证**

Run: push → CI `:app:assembleGithubDebug`
Expected: 编译通过（三个视图先用 `Box {}` 占位）

- [ ] **Step 5: 提交**

```bash
git commit -m "feat(compare): comparison screen skeleton and view model"
```

---

### Task 5: 视图 A（缩进树）

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/comparison/views/IndentedTreeView.kt`

**Interfaces:**
- Consumes: `ComparisonGraph`、`ComparisonLayout`
- Produces: `@Composable fun IndentedTreeView(graph, expanded, onExpand, onNodeClick, modifier)`

- [ ] **Step 1: 实现**

`LazyColumn`，条目按 DFS 顺序展开（虚拟根的子节点起）。每行：
- 左侧 `Spacer(width = depth * 20.dp)` + Canvas 画竖线/肘形连接线（按是否有兄弟、是否最后一个孩子决定线形）
- 角色标签（USER/ASSISTANT 小字）+ 正文（`Text`，不限行数，`SelectionContainer` 可选）
- 分叉节点（`childIds.size > 1`）左侧加 3dp 竖条（`MaterialTheme.colorScheme.primary`）
- 点击回调 `onNodeClick(node)`
- 横向滚动：整行内容放在 `Modifier.horizontalScroll(scrollState)` 容器内

- [ ] **Step 2: 编译验证** → CI 编译通过

- [ ] **Step 3: 提交**

```bash
git commit -m "feat(compare): indented tree view"
```

---

### Task 6: 视图 B（自由画布）

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/comparison/views/CanvasTreeView.kt`

**Interfaces:**
- Consumes: `tidyLayout`、`subtreeSizes`
- Produces: `@Composable fun CanvasTreeView(graph, layout, expanded, onExpand, onNodeClick, modifier)`

- [ ] **Step 1: 实现**

`Box` + `Modifier.transformable(state)`（缩放 0.4–2.5，平移）+ `Canvas` 画贝塞尔连线 + 节点卡片用 `Layout` 绝对定位（`Modifier.offset { IntOffset(...) }`，坐标乘缩放）。
节点卡：宽 220dp，正文最多 3 行 ellipsis，分叉节点加边框。
子树 > 300 且未展开的节点渲染成 `+N 条后续` chip。
初始缩放按图包围盒适配屏幕。

- [ ] **Step 2: 编译验证** → CI 通过

- [ ] **Step 3: 提交**

```bash
git commit -m "feat(compare): canvas tree view"
```

---

### Task 7: 视图 C（横向时间线）

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/comparison/views/TimelineView.kt`

**Interfaces:**
- Consumes: `timelineLayout`
- Produces: `@Composable fun TimelineView(graph, layout, expanded, onExpand, onNodeClick, modifier)`

- [ ] **Step 1: 实现**

`Row` + `horizontalScroll`，每列一个深度：`Column` 内按 y 定位节点卡（宽 260dp，正文最多 4 行）。列间画横向连线（Canvas 覆盖层）。lane 用不同色相区分（`MaterialTheme.colorScheme` 系列，最多 6 色循环）。

- [ ] **Step 2: 编译验证** → CI 通过

- [ ] **Step 3: 提交**

```bash
git commit -m "feat(compare): horizontal timeline view"
```

---

### Task 8: 入口与选择器

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/history/HistoryPage.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/comparison/ComparisonPickerSheet.kt`
- Modify: `app/src/main/res/values/strings.xml`、`app/src/main/res/values-zh-rCN/strings.xml`

**Interfaces:**
- Consumes: `navigateToComparison`
- Produces: `@Composable fun ComparisonPickerSheet(conversations, selected, onToggle, onDismiss)`

- [ ] **Step 1: HistoryPage 加「对比」入口**

顶栏 `actions` 增加一个图标按钮（`HugeIcons.GitCompare` 若无则用 `ArrowLeftRight` 一类现成图标），点击进入选择态：列表项点击改为切换选中、显示 checkbox、顶栏变「已选 N 个」+ 取消；选中 ≥2 时底栏显示「对比分支」FilledTonalButton。退出选择态恢复原行为。

- [ ] **Step 2: 对比页的选择器**

顶栏 chips 行末尾的 `+` 打开 `ModalBottomSheet`，列出当前助手的可见会话（复用 `HistoryVM` 的数据源），已选中的打勾，点一下切换。

- [ ] **Step 3: 文案**

`strings.xml` 增加：`comparison_title`、`comparison_select_mode`、`comparison_compare_action`、`comparison_view_indented`、`comparison_view_canvas`、`comparison_view_timeline`、`comparison_no_common_prefix`、`comparison_empty_selection`、`comparison_choose_source`、`comparison_folded_count`、`comparison_load_failed`；`values-zh-rCN` 同步中文。

- [ ] **Step 4: 编译验证** → CI 通过

- [ ] **Step 5: 提交**

```bash
git commit -m "feat(compare): history entry and conversation picker"
```

---

### Task 9: 跳转、折叠与边界态

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/comparison/ComparisonPage.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/comparison/ComparisonVM.kt`

- [ ] **Step 1: 节点点击**

单 occurrence → `pushToChatPage(nav, conversationId, nodeId)`；多 occurrence → `ModalBottomSheet` 列会话标题，点击跳转。

- [ ] **Step 2: 边界态**

- 无共同开头（`rootIds.size == graph` 里第一层节点数 > 1 且没有共享）→ 顶部提示 `comparison_no_common_prefix`
- 会话在停留期间被删除 → catch null，移出选择并 snackbar
- 哈希进行中显示进度（不确定进度 + `hashing` 文案）
- 图节点 > 2000 → 顶部提示可用 A 视图看全文

- [ ] **Step 3: 编译验证 + 单测全绿** → CI 通过

- [ ] **Step 4: 提交**

```bash
git commit -m "feat(compare): node jump, folding and edge states"
```

---

### Task 10: 真机验收清单

**Files:**
- Modify: `docs/ACCEPTANCE-COMPARISON.md`（新建）

- [ ] **Step 1: 写验收清单**

1. 同一开场、不同回复的三条会话 → 主干只有开场，后面三条平行支线
2. fork 出来的会话 + 源会话 → 完全重合到 fork 点
3. 三视图切换结构一致，滚动位置大致保持
4. 点节点跳回正确会话与消息，返回键回到对比页
5. 含图片的会话 → 不因 URL 不同而假分叉
6. 1000+ 消息的会话 → 打开与切换视图不卡顿
7. 删除会话后对比页的降级表现

- [ ] **Step 2: 提交**

```bash
git commit -m "docs: comparison acceptance checklist"
```
