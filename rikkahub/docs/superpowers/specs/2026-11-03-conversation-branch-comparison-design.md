# 会话分支对比 · 设计文档

日期：2026-11-03
状态：已确认（2026-11-03；技术细节委托给实现者，只在影响真实使用体验时再确认）
范围：RikkaHub 个人构建（`me.rerere.rikkahub.sol`）

## 一、要解决什么

同一个助手、同一个开场，用户常常开好几个会话试不同走向。现在只能来回切换、靠记忆比较，看不出「哪一步开始分道扬镳」、也看不出「哪条路走得更远」。

要做的是一张**会话谱系图**：把选中的几个会话按实际走过的消息序列叠在一起，逐字相同的部分合并成共同主干，一有不同就分叉，各自延伸。点任意节点能跳回那个会话的那条消息继续聊。

不做「从哪 fork 出来」的血缘推断——只看内容是否真的相同。

目标：

1. 从会话列表多选 2–8 个会话，生成一棵合并树
2. 只取每个会话**当前选中**的候选消息，不展开会话内部的重新生成分支
3. 逐字相同（角色 + 可见内容）才合并；一有不同立刻分叉
4. 同一棵树提供三种画法，可自由切换：缩进树（A）、自由画布（B）、横向时间线（C）
5. 点节点 → 跳回对应会话的对应消息
6. 对比页内可以增删会话，图即时重算

非目标（本次不做）：

- 模糊/语义相似度，不做任何「差不多就算同一条」的判断
- 会话内部未选中的候选分支
- fork 来源记录、血缘字段、数据库 schema 变更
- 编辑、合并、导出图片、分享（首版不做）
- Web UI 对应界面（`web/` 模块不动）
- 跨助手限制（允许选不同助手的会话，但界面不特别处理）

反目标（做了就算失败）：

- 内容不同的消息被合并成一个节点（假合并）
- 因为 id、时间戳、模型名不同而被判为不同（假分叉）
- 一个节点有两个父节点，图上出现交叉回环
- 视图 A/B/C 对同一棵树给出不同的结构（三者必须共用同一份图数据）
- 大列表卡顿或 OOM

## 二、现状（改动前）

- `Conversation.messageNodes: List<MessageNode>`，线性列表；每个 `MessageNode` 有 `messages: List<UIMessage>` + `selectIndex`，即同一位置的多个候选（重新生成/编辑产生）
- `Conversation.currentMessages` 已经是「每个节点取 `messages[selectIndex]`」后的线性序列，直接可用
- `UIMessage` 字段：`id`、`role`、`parts`、`annotations`、`createdAt`、`finishedAt`、`modelId`、`modelSnapshot`、`usage`、`generationTimeMs`、`lastUsage`、`stepCount`、`translation`、`isContextCheckpoint`、`isSynthetic`
- `UIMessagePart` 子类：`Text`、`Image`、`Video`、`Audio`、`Document`、`Reasoning`、`Search`、`ToolCall`、`ToolResult`、`ServerTool`、`Tool`
- `navigateToChatPage(navController, conversationId, nodeId = ...)` 已存在（收藏页在用），可直接跳到指定消息
- 会话列表是 `HistoryPage`（按当前助手列可见会话，`getConversationsOfAssistant` 返回的是**不带 messageNodes 的摘要**）；`ChatList.kt` 是**会话内**的消息列表，不是会话列表
- `HistoryPage` 目前没有多选模式，且列表项带 `SwipeToDelete`（`@OptIn(ExperimentalMaterial3Api::class)` 的 `SwipeToDismissBox`）与长按进入文件夹/操作菜单的手势；`ImgGenPage` 里有一套可参考的选择态实现
- 现有导航 `navigateToChatPage` 内部是 `clearAndNavigate`，会把返回栈清空
- `Conversation.fork()` 复制节点时给每个 node 换新 id，本地附件经 `copyChatFile` 复制成新文件；所以 **id 和文件 URL 都不可作为内容标识**
- 归档/回收站（DB v33）已实现，对比入口只从可见会话列表进入；对比页按 id 读取时不过滤状态

## 三、方案选择

画法三选一在对话里讨论过，用户要求**三种都做，可自由切换**。三者共用同一份 `ComparisonGraph`，只是渲染器不同：

| 视图 | 布局 | 适合 | 代价 |
| --- | --- | --- | --- |
| A 缩进树 | 纵向 `LazyColumn`，分叉按深度缩进，Canvas 画折线连接 | 读长正文、大列表、点击跳转 | 深度大时要横向滚动，视觉上不如画布「像图」 |
| B 自由画布 | 树布局算法算坐标，卡片 + 贝塞尔连线，可缩放拖动 | 总览结构、看分叉形状 | 正文截断，文本排版/命中测试手写，节点多会重 |
| C 横向时间线 | 列 = 深度，行 = 分支 lane，左→右 | 一眼看清分叉与平行支线 | 列宽固定，长回复要横拖 |

**默认 A**。切换控件放顶栏（分段按钮），三个视图共享滚动/选中状态并在切换时保持大致位置。

图构建方案：**前缀树（trie）合并**。只有「从根到该节点的整条路径都逐字相同」才合并成同一节点，因此每个节点至多一个父节点，收敛不回并。备选是 DAG（任意相同内容都合并），会在图上产生一个节点多个父节点的交叉线，读起来像面条，不采用。

## 四、数据与算法

### 4.1 内容指纹

`MessageFingerprint` 是确定性字符串（或 64 位哈希），只由「可见内容」决定：

包含：

- `role`
- `Text.text`
- `Reasoning` 的文本
- `ToolCall` / `Tool` / `ToolResult` 的工具名、参数、输出（序列化后按字段固定顺序）
- `ServerTool` 的名称、状态、输入输出
- `Search` 作为一个占位标记
- `Image` / `Video` / `Audio` / `Document`：本地文件取**文件内容的 SHA-256**（fork 会换 URL，URL 不可比）；文件不存在或非本地时退回 URL 字符串
- `Document` 的 `fileName` 与 `mime`（聊天里显示文件名、按 mime 选图标，属于可见内容）
- `Tool` / `ToolCall` 的 `approvalState`（`Denied.reason`、`Answered.answer` 会渲染给用户）
- `UIMessage.annotations`（引用列表会渲染，含标题与 URL）
- 工具输入的空串与 `{}` 视为同一件事（UI 的 `inputAsJson()` 就是这样归一）
- **递归**：`Tool.output` 里嵌套的附件 part 同样按内容哈希处理（`Conversation.fork()` 的 `mapLocalFileUrls` 会递归改写这里）
- **例外**：`ToolResult.content` / `ToolResult.arguments` / `ServerTool.input` / `ServerTool.output` 里的 `file://` 字符串按原文比较。fork 不重写这些位置，按内容哈希等于把「不同路径」这个真实差异擦掉，会造成假合并
- 哈希结果按「文件绝对路径 + 大小 + mtime」缓存，同一次对比内复用
- 消息带 `isContextCheckpoint` 时并入标记（压缩摘要与普通消息不是一回事）

排除：

- `id`、`createdAt`、`finishedAt`
- `modelId`、`modelSnapshot`、`usage`、`lastUsage`、`generationTimeMs`、`stepCount`
- `translation`
- `metadata`、`isSynthetic`
- `toolCallId`（fork 会换新 id）

不做 trim、不做空白归一、不做大小写折叠——就按字符串原样比。

### 4.2 图构建

```kotlin
data class ComparisonNode(
    val id: String,          // 由父 id + 指纹派生
    val parentId: String?,
    val depth: Int,
    val fingerprint: String,
    val message: UIMessage,                    // 代表消息（首次出现）
    val occurrences: List<NodeOccurrence>,     // 哪些会话走过这里
    val childIds: List<String>,
)
data class NodeOccurrence(
    val conversationId: Uuid,
    val conversationTitle: String,
    val messageId: Uuid,
    val messageNodeId: Uuid,
)
```

构建：按会话依次遍历 `currentMessages`，从虚拟根开始，用 `role + fingerprint` 在**当前父节点的子节点**里找同键孩子；找到就复用、追加 occurrence，找不到就新建。复杂度 O(总消息数)。

- 同一会话重复出现相同文本不会串线（每层只沿自己的路径下降）
- 空会话跳过
- 根节点为虚拟节点，`children` 就是所有会话的第一条消息

### 4.3 布局

- A：深度即缩进，叶子按 DFS 顺序占行
- B：标准 tidy tree（子树宽度递归求和，父节点居中于子节点之上），只算一次并缓存
- C：DFS 序遍历，叶子分配 lane，内部节点取子 lane 的中位数

三种布局都由同一棵 `ComparisonGraph` 派生，不允许各自重新解析会话。

## 五、界面

### 5.1 入口

入口在 `HistoryPage`（当前助手的可见会话）。该页已有轻点进会话、左滑删除、长按菜单三套手势，多选不能再抢长按：改为**列表项右侧的「选择」图标进入选择态**，或在顶栏放一个常驻的「对比」按钮——二选一留给实现，进选择态后轻点切换选中、顶栏显示计数、返回键退出。选中 ≥2 时底栏出现「对比分支」。

进入对比页后，顶栏显示已选会话的 chips，可点 × 移除、点 + 打开选择器追加（选择器复用同一份会话列表数据源）。

**范围（已定）**：只能选当前助手的会话，不含归档与回收站。跨助手对比不做：不同助手的系统提示、模型、注入都不同，画在一张树上没有比较意义；将来若要支持，需新增全局会话查询与独立选择器页。

### 5.2 对比页

```
┌──────────────────────────────────┐
│ ←  会话对比   [A][B][C]   ⤴      │  ← 视图切换 + 刷新
├──────────────────────────────────┤
│ [会话1 ×] [会话2 ×] [+ 添加]      │
├──────────────────────────────────┤
│                                  │
│   （当前视图）                     │
│                                  │
└──────────────────────────────────┘
```

- 顶栏分段按钮切换 A/B/C，切换不重建图
- 分叉节点在三种视图里都带一个可辨识标记（例如左侧竖条 / 圆点加重）
- 节点显示：角色标签 + 正文预览（A 全文；B 最多 3 行；C 最多 4 行）
- 点节点：若只有 1 个 occurrence 直接跳转；多个则弹 bottom sheet 选择「哪个会话的这条」

### 5.3 跳回

`nodeId` 用 `NodeOccurrence.messageNodeId`。注意 `navigateToChatPage` 内部是 `clearAndNavigate`，会清空返回栈；对比页改用 **push 导航**进入聊天，返回键回到对比页，已选会话、视图模式、滚动位置都通过 ViewModel + SavedState 保留。

### 5.4 空态与边界

- 选中的会话没有共同开头：正常画成 N 条独立支线，顶部一行提示「这些会话没有共同开头」
- 只有 1 个会话：入口禁用
- 会话为空：不参与构建，chips 上标注「无消息」
- 总节点数超过 300：B/C 把**子树节点数 > 300 的分支**渲染成占位 chip（`+N 条后续`），点击展开；纯渲染态，图结构、lane 分配、命中测试都不变。A 永不折叠。

## 六、约束

- **不新增依赖**：三种视图全部用 Compose 原生（`LazyColumn`、`Canvas`、`transformable`）
- **不改数据库**：无 schema 变更，图完全在内存中构建
- 图构建放 `Dispatchers.Default`，主线程只做渲染
- 附件哈希严格按全文件 SHA-256，**不设大小阈值**（阈值会引入假合并，违反反目标）；首屏显示进度并可取消，哈希过程中不阻塞 UI
- 指纹计算对每个 `(conversationId, messageId)` 做内存缓存，切视图/加会话时复用
- 本机不能构建：所有编译与单测在 GitHub Actions 跑
- 只读：对比页不写任何会话数据

## 七、测试

JVM 单测（`testGithubDebugUnitTest`）：

1. 指纹忽略 id/时间戳/model/translation/usage
2. 指纹区分角色、文本、工具参数
3. 两条相同序列完全合并成一条链，节点数 = 消息数
4. 第一个回复不同 → 根后立刻两个子节点，occurrentces 各 1
5. 中途分叉再出现相同文本 → **不**回并（仍是两条独立支线）
6. 单条会话 → 一条直线，无分叉标记
7. 空会话不产生节点
8. 同一会话连续两条完全相同文本 → 两个节点，不形成环
9. 布局：B/C 的 lane 数 = 叶子数，节点不重叠

## 八、留给实现者定的

- A 的连接线具体画法（折线 vs 圆角肘形）、缩进宽度
- B 的卡片尺寸、缩放上下限、初始适配策略
- C 的列宽与 lane 间距
- 分叉标记的视觉样式
- 长文本折叠的行数与「展开」交互
- 多选态的具体动画与触觉反馈
