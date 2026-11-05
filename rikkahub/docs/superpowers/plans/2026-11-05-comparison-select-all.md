# 会话对比 · 一键全选与去除上限 · 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让会话对比支持一键全选，并彻底移除 8 个会话的硬上限，改为超过 32 个时只提示不拦。

**Architecture:** 选择逻辑集中在 `ComparisonInteractions.kt` 的纯函数里（可单测），`HistoryPage` 底栏新增全选按钮，`ComparisonPage`/`ComparisonPickerSheet` 去掉上限判断并加一条阈值提示；建图流程不动。

**Tech Stack:** Kotlin、Jetpack Compose、JUnit4、GitHub Actions（本机无 Android SDK，全部验证走 CI）。

**Spec:** `docs/superpowers/specs/2026-11-05-comparison-select-all-design.md`

## Global Constraints

- 仓库：`/mnt/data1/rikkahub/build-hub`（git 根），App 在 `rikkahub/` 子目录；远端 `snake-aabb-wtf/build`。
- 只改 App 代码与必要的 CI；不动 `rikkahub/upstream` 相关逻辑，不改建图算法与三种视图。
- 工作分支：`feat/comparison-select-all`（从 `main` `77c9fb08` 切出）。
- 本机**不能**运行 Gradle/Android 构建；单测与编译必须在 GitHub Actions 跑。
- CI 验证复用现有 `RikkaHub APK` workflow 的 `workflow_dispatch`（ref 指向功能分支）：它先跑 `Run unit tests`，红则在此失败，绿则继续编译整包；不新增测试专用 workflow。
- 所有新增用户可见文案必须同时提供 `values/strings.xml`（英）与 `values-zh/strings.xml`（中）。
- 不发布 APK、不改版本号、不创建 Release。
- 提交信息用 `feat:`/`refactor:`/`test:`/`ci:` 前缀，一个任务一次提交。

## Review Focus

- 列表为空（会话全被删）时，全选按钮仍显示「取消全选」——必须显示「全选」且点击无副作用。
- 全选后删除某个会话，`selectedIds` 里的残留 id 不能让「已全选」误判；`isAllSelected` 只按候选集合判断。
- 候选里有重复 id 时，全选结果不能重复。
- 阈值边界：恰好 32 个会话不提示，33 个提示。
- 移除上限后：恢复 12 个已保存 id、直接跳转 12 个 id 都不得被截断；对比页取消构建仍可用。

---

### Task 1: 选择逻辑去上限 + 全选纯函数

**Files:**
- Modify: `rikkahub/app/src/main/java/me/rerere/rikkahub/data/model/comparison/ComparisonInteractions.kt`
- Test: `rikkahub/app/src/test/java/me/rerere/rikkahub/data/model/comparison/ComparisonInteractionsTest.kt`

**Interfaces:**
- Consumes: 现有 `ComparisonCandidate(id: Uuid, title: String, updateAt: Instant)`
- Produces:
  - `const val COMPARISON_LARGE_SELECTION_THRESHOLD = 32`
  - `fun toggleComparisonSelection(selected: Set<Uuid>, id: Uuid): Set<Uuid>`
  - `fun restoreComparisonIds(saved: List<String>?, fallback: List<String>): List<Uuid>`
  - `fun selectAllCandidates(candidates: List<ComparisonCandidate>): Set<Uuid>`
  - `fun isAllSelected(selected: Set<Uuid>, candidates: List<ComparisonCandidate>): Boolean`
  - `fun isLargeSelection(count: Int): Boolean`
  - 删除 `COMPARISON_MAX_CONVERSATIONS` 与 `canAddConversation`

- [ ] **Step 1: 写失败测试**

在 `ComparisonInteractionsTest` 中删除 `selection stops at the cap...` 用例，替换为：

```kotlin
@Test
fun `toggle keeps adding past the old cap`() {
    val nine = (1..9).map { Uuid.random() }.toSet()
    val extra = Uuid.random()
    assertEquals(nine + extra, toggleComparisonSelection(nine, extra))
}

@Test
fun `restore keeps every saved id`() {
    val many = (1..12).map { Uuid.random().toString() }
    assertEquals(12, restoreComparisonIds(saved = many, fallback = emptyList()).size)
}

@Test
fun `select all takes every candidate once`() {
    val first = ComparisonCandidate(Uuid.random(), "a", Instant.EPOCH)
    val second = ComparisonCandidate(Uuid.random(), "b", Instant.EPOCH)
    val duplicate = first.copy(title = "a again")
    assertEquals(
        setOf(first.id, second.id),
        selectAllCandidates(listOf(first, second, duplicate)),
    )
}

@Test
fun `all selected is false for an empty list`() {
    assertFalse(isAllSelected(emptySet(), emptyList()))
    assertFalse(isAllSelected(setOf(Uuid.random()), emptyList()))
}

@Test
fun `all selected requires every visible candidate`() {
    val first = ComparisonCandidate(Uuid.random(), "a", Instant.EPOCH)
    val second = ComparisonCandidate(Uuid.random(), "b", Instant.EPOCH)
    assertTrue(isAllSelected(setOf(first.id, second.id), listOf(first, second)))
    assertFalse(isAllSelected(setOf(first.id), listOf(first, second)))
}

@Test
fun `large selection starts above the threshold`() {
    assertFalse(isLargeSelection(COMPARISON_LARGE_SELECTION_THRESHOLD))
    assertTrue(isLargeSelection(COMPARISON_LARGE_SELECTION_THRESHOLD + 1))
}
```

同时把 `restoreComparisonIds` 的旧断言「超出上限只取前 8 个」删掉。

- [ ] **Step 2: 推送分支，跑测试确认失败**

推送 `feat/comparison-select-all` 后触发 `RikkaHub Unit Tests`。Expected: 编译失败，提示 `selectAllCandidates`/`isAllSelected`/`isLargeSelection` 未定义。

- [ ] **Step 3: 实现**

`ComparisonInteractions.kt`：

```kotlin
/** 超过这个会话数时在对比页提示建图可能较慢，只提示不阻断。 */
const val COMPARISON_LARGE_SELECTION_THRESHOLD = 32

fun toggleComparisonSelection(selected: Set<Uuid>, id: Uuid): Set<Uuid> =
    if (id in selected) selected - id else selected + id

fun restoreComparisonIds(saved: List<String>?, fallback: List<String>): List<Uuid> =
    (saved ?: fallback)
        .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
        .distinct()

fun selectAllCandidates(candidates: List<ComparisonCandidate>): Set<Uuid> =
    candidates.mapTo(HashSet()) { it.id }

fun isAllSelected(selected: Set<Uuid>, candidates: List<ComparisonCandidate>): Boolean =
    candidates.isNotEmpty() && candidates.all { it.id in selected }

fun isLargeSelection(count: Int): Boolean = count > COMPARISON_LARGE_SELECTION_THRESHOLD
```

删除 `COMPARISON_MAX_CONVERSATIONS` 与 `canAddConversation`；更新 `restoreComparisonIds` 的 KDoc（不再提上限）。

- [ ] **Step 4: 跑测试确认通过**

触发 `RikkaHub Unit Tests`。Expected: 全部 success，`ComparisonInteractionsTest` 无失败。

- [ ] **Step 5: 提交**

```bash
git add rikkahub/app/src/main/java/me/rerere/rikkahub/data/model/comparison/ComparisonInteractions.kt \
        rikkahub/app/src/test/java/me/rerere/rikkahub/data/model/comparison/ComparisonInteractionsTest.kt
git commit -m "refactor(comparison): drop the 8-conversation cap and add select-all helpers"
```

---

### Task 2: 会话列表底栏「全选 / 取消全选」

**Files:**
- Modify: `rikkahub/app/src/main/java/me/rerere/rikkahub/ui/pages/history/HistoryPage.kt`
- Modify: `rikkahub/app/src/main/res/values/strings.xml`
- Modify: `rikkahub/app/src/main/res/values-zh/strings.xml`

**Interfaces:**
- Consumes: Task 1 的 `selectAllCandidates`、`isAllSelected`
- Produces: `SelectionBottomBar(selectedCount, canCompare, allSelected, onToggleAll, onCompare)`

- [ ] **Step 1: 加文案**

`values/strings.xml` 在 `comparison_selected_count` 附近加：

```xml
<string name="comparison_select_all">Select all</string>
<string name="comparison_clear_selection">Clear selection</string>
```

`values-zh/strings.xml` 对应：

```xml
<string name="comparison_select_all">全选</string>
<string name="comparison_clear_selection">取消全选</string>
```

- [ ] **Step 2: 改 HistoryPage**

- 删除 import `COMPARISON_MAX_CONVERSATIONS` 与 `toggleComparisonSelection` 的上限说明；保留 `toggleComparisonSelection`。
- `toggleSelection` 简化：

```kotlin
fun toggleSelection(id: Uuid) {
    selectedIds = toggleComparisonSelection(selectedIds, id)
}
```

- 计算：

```kotlin
val candidates = remember(conversations) {
    conversations.map { ComparisonCandidate(it.id, it.title, it.updateAt) }
}
val allSelected = isAllSelected(selectedIds, candidates)
```

- `SelectionBottomBar` 调用新增：

```kotlin
allSelected = allSelected,
onToggleAll = {
    selectedIds = if (allSelected) emptySet() else selectAllCandidates(candidates)
},
```

- `SelectionBottomBar` 在计数 `Text` 与「对比分支」按钮之间插入：

```kotlin
TextButton(onClick = onToggleAll) {
    Text(
        stringResource(
            if (allSelected) R.string.comparison_clear_selection
            else R.string.comparison_select_all
        )
    )
}
```

`TextButton` 已 import；`ComparisonCandidate`、`selectAllCandidates`、`isAllSelected` 需补 import。

- [ ] **Step 3: 推送并跑单测**

提交后由编排者用 `workflow_dispatch` 在功能分支上运行 `RikkaHub APK`。Expected: `Run unit tests` success（本任务无新增单测，验证编译与既有测试）。

- [ ] **Step 4: 提交**

```bash
git add rikkahub/app/src/main/java/me/rerere/rikkahub/ui/pages/history/HistoryPage.kt \
        rikkahub/app/src/main/res/values/strings.xml \
        rikkahub/app/src/main/res/values-zh/strings.xml
git commit -m "feat(comparison): add a select-all button to multi-select mode"
```

---

### Task 3: 对比页/VM/选择器去上限 + 软提示

**Files:**
- Modify: `rikkahub/app/src/main/java/me/rerere/rikkahub/ui/pages/comparison/ComparisonVM.kt`
- Modify: `rikkahub/app/src/main/java/me/rerere/rikkahub/ui/pages/comparison/ComparisonPage.kt`
- Modify: `rikkahub/app/src/main/java/me/rerere/rikkahub/ui/pages/comparison/ComparisonPickerSheet.kt`
- Modify: `rikkahub/app/src/main/java/me/rerere/rikkahub/utils/ChatUtil.kt`
- Modify: `rikkahub/app/src/main/res/values/strings.xml`
- Modify: `rikkahub/app/src/main/res/values-zh/strings.xml`

**Interfaces:**
- Consumes: Task 1 的 `isLargeSelection`、`COMPARISON_LARGE_SELECTION_THRESHOLD`
- Produces: `ComparisonPickerSheet(conversations, selected, enabled, onToggle, onDismiss)`（移除 `atLimit`）

- [ ] **Step 1: VM 去上限**

`ComparisonVM.addConversation`：

```kotlin
fun addConversation(id: Uuid) {
    if (id in order) return
    order += id
    persistIds()
    rebuild()
}
```

删除 `ComparisonEvent.SelectionLimitReached` 及 `canAddConversation` 的 import；`addConversation` 里不再发事件。

- [ ] **Step 2: 对比页去上限、加提示**

- 删除 `COMPARISON_MAX_CONVERSATIONS` import，改 import `isLargeSelection`。
- `LaunchedEffect(vm)` 的 `when` 删除 `SelectionLimitReached` 分支。
- 工具栏调用：`canAdd = !state.loading`。
- 选择器调用：删 `atLimit = ...`，`enabled = !state.loading`。
- 在说明文字（`comparison_explainer`）下方加：

```kotlin
if (isLargeSelection(state.selectedIds.size)) {
    Text(
        text = stringResource(R.string.comparison_many_conversations),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
```

- [ ] **Step 3: 选择器与跳转去上限**

- `ComparisonPickerSheet`：删除 `atLimit` 参数、`COMPARISON_MAX_CONVERSATIONS` import、`atLimit` 文案块。
- `ChatUtil.navigateToComparison`：`val selected = ids.distinct()`，注释改为「少于两条没得比」。

- [ ] **Step 4: 文案清理**

- 两个 `strings.xml` 删除 `comparison_limit`。
- 新增（英）：`<string name="comparison_many_conversations">Many conversations — building the graph may take a while. You can cancel anytime.</string>`
- 新增（中）：`<string name="comparison_many_conversations">会话较多，建图可能需要几十秒；可随时点取消。</string>`

- [ ] **Step 5: 推送并跑单测**

提交后由编排者用 `workflow_dispatch` 在功能分支上运行 `RikkaHub APK`。Expected: `Run unit tests` success（无残留引用、编译通过）。

- [ ] **Step 6: 提交**

```bash
git add rikkahub/app/src/main/java/me/rerere/rikkahub/ui/pages/comparison/ComparisonVM.kt \
        rikkahub/app/src/main/java/me/rerere/rikkahub/ui/pages/comparison/ComparisonPage.kt \
        rikkahub/app/src/main/java/me/rerere/rikkahub/ui/pages/comparison/ComparisonPickerSheet.kt \
        rikkahub/app/src/main/java/me/rerere/rikkahub/utils/ChatUtil.kt \
        rikkahub/app/src/main/res/values/strings.xml \
        rikkahub/app/src/main/res/values-zh/strings.xml
git commit -m "refactor(comparison): remove the selection cap and hint on large sets"
```

---

### Task 4: 整包编译验证

**Files:** 无（只验证）

- [ ] **Step 1: 触发 APK 流水线**

用 `workflow_dispatch` 在 `feat/comparison-select-all` 上运行 `RikkaHub APK`（默认 `:app:assembleGithubRelease`）。Expected：`Run unit tests`、`Build`、`Sign APK`、`Verify APK signature` 全 success。

- [ ] **Step 2: 合并前检查**

```bash
git diff main --stat
git status --short
```

Expected：只有本计划涉及的文件；无未提交改动。

- [ ] **Step 3: 不发布**

不创建 tag、不改版本号；合并到 `main` 由人工决定。

---

## Self-Review

- 设计稿的每一条都有任务覆盖：全选按钮（Task 2）、去上限（Task 1/3）、软提示（Task 3）、清理事件与文案（Task 3）、测试（Task 1）、整包验证（Task 4）。
- 类型一致：`selectAllCandidates`、`isAllSelected`、`isLargeSelection` 在 Task 1 定义，Task 2/3 按同名调用。
- Review Focus 的五个输入分别由 Task 1 的单测（空列表、重复 id、阈值）与 Task 3 的编译/单测（去上限后恢复与跳转）覆盖；「删除会话后全选误判」由 `isAllSelected` 只依赖候选集合的设计保证，现有 `HistoryPage` 的 `conversations` 刷新路径不变。
- 计划长度小于代码量，未逐行抄写实现。
