# 会话对比 · 一键全选与去除上限 · 设计文档

日期：2026-11-05
状态：待确认
范围：RikkaHub 个人构建（`me.rerere.rikkahub.sol`）

## 一、要解决什么

对比分支目前最多只能选 8 个会话，且只能一个个点。想比较「同一助手下所有分支」时，8 个不够，几十次点击也很烦。

本次做两件事：

1. 多选模式加一个「全选 / 取消全选」按钮，一次选中当前列表里的全部会话。
2. 去掉 8 个的硬上限，选多少比多少；会话很多时给一条不阻断的提示。

不做（非目标）：

- 不改建图算法、三种视图、布局与折叠逻辑
- 不做按标题/时间/标签筛选后再全选（全选范围就是当前列表）
- 不做跨助手的批量选择（列表本身只显示当前助手）
- 不新增「选择记忆」：退出多选即清空

反目标（做了算失败）：

- 用一个新的硬上限替换 8（比如 100 个）
- 全选时把归档、回收站里的会话也拉进来
- 全选按钮在部分会话不可选时行为含糊（必须明确：全选 = 列表里全部可选项）
- 放开上限后主线程卡顿或建图不可取消

## 二、现状（改动前）

- `COMPARISON_MAX_CONVERSATIONS = 8` 定义在 `data/model/comparison/ComparisonInteractions.kt`，被 6 处引用：
  - `toggleComparisonSelection`（到上限不再加入）
  - `canAddConversation`（到上限返回 false）
  - `restoreComparisonIds`（恢复时 `take(8)`）
  - `utils/ChatUtil.kt` 的 `navigateToComparison`（`take(8)`）
  - `ComparisonVM.addConversation`（超限发 `SelectionLimitReached`）
  - `HistoryPage` / `ComparisonPage` / `ComparisonPickerSheet` 的 `canAdd`、`atLimit` UI 判断
- 建图在 `ComparisonVM.rebuild()` 的后台协程里：逐个读库 → `buildComparisonGraph` 在 `Dispatchers.Default`；有 `loadedCount/totalCount` 进度、`cancelLoading` 可取消、`generation` token 防过期结果。
- 选择状态存活于 `savedStateHandle`，`restoreComparisonIds` 目前会截断到 8。
- 现有单测 `ComparisonInteractionsTest` 直接断言「到 8 个停止」。

## 三、选定的做法

**B：去掉硬上限 + 软提示。**

备选 A 是彻底放开、不加任何提示；B 多一条超过 32 个会话时的顶部提示，不阻断、不改变可选数量。选 B 是因为全选后可能有上百条会话，用户需要知道为什么建图要等。

### 行为

- **全选按钮**：多选模式底栏，位于「已选 N 个」与「对比分支」之间，文案随状态切换「全选 / 取消全选」。
  - 点击「全选」→ `selectedIds` = 当前 `conversations` 列表全部 id。
  - 点击「取消全选」→ `selectedIds` = 空集，仍留在多选模式。
  - 判定「已全选」：列表非空且每个 id 都在 `selectedIds` 里。
- **上限**：完全移除。`toggleComparisonSelection` 只做增删；`restoreComparisonIds` 不再截断；`navigateToComparison` 不再 `take`。
- **软提示**：对比页顶部、说明文字下方，当参与对比的会话数 **> 32** 时显示一行：
  「会话较多，建图可能需要几十秒；可随时点取消。」
  与现有的「图较大」提示并列，不合并。
- **选择器（对比页内的追加弹层）**：去掉 `atLimit` 参数与提示；候选仍然只列未选中的会话，点击即加入。
- **清理**：`SelectionLimitReached` 事件、`comparison_limit` 字符串（`values` / `values-zh`）不再有使用者，一并删除，避免留下会误导的文案。

### 常量与文案

- 删除 `COMPARISON_MAX_CONVERSATIONS`
- 新增 `COMPARISON_LARGE_SELECTION_THRESHOLD = 32`
- 新增字符串（中/英）：
  - `comparison_select_all` = 全选 / Select all
  - `comparison_clear_selection` = 取消全选 / Clear selection
  - `comparison_many_conversations` = 会话较多，建图可能需要几十秒；可随时点取消。/ Many conversations — building the graph may take a while. You can cancel anytime.

## 四、实现边界（留给实现者）

- 纯逻辑（`ComparisonInteractions.kt`）保持可单测：新增 `selectAllCandidates(candidates): Set<Uuid>` 与 `isAllSelected(selected, candidates): Boolean`，UI 只调用它们。
- `HistoryPage` 底栏布局要保证窄屏不挤：计数文字 `weight(1f)` 保留，两个按钮用紧凑样式。
- `ComparisonVM.addConversation` 只保留「已在列表中则忽略」判断，不再有上限分支；`addConversation` 的注释同步改。
- 现有单测改写：删除上限断言，新增全选/全不选、恢复不截断、候选列表完整、32 阈值判定的用例。
- 验证：本机无 Android SDK，单测与编译走 GitHub Actions（push 分支触发 `daily-build` 的 Gradle Build 步骤），CI 绿 + 本地 `git diff` 复核为完成标准；不发布 APK。

## 五、我替你定的

- 按钮位置：底栏，而不是顶栏。顶栏在多选时只有标题和取消，塞图标会降低可发现性。
- 全选 = 列表里全部会话（含置顶），不含归档与回收站——跟列表显示范围一致。
- 取消全选后仍停留在多选模式，方便重新选。
- 「已全选」判定要求列表非空，避免空列表时按钮显示「取消全选」。
- 阈值 32：大约是一屏能扫完、建图仍在十几秒内的量级；仅提示，不拦。
