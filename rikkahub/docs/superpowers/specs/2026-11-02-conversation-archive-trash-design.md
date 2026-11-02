# 会话归档与回收站 · 设计文档

日期：2026-11-02
状态：已确认（2026-11-02）
范围：RikkaHub 个人构建（`me.rerere.rikkahub.sol`）

## 一、要解决什么

现在的「删除会话」是立即销毁：删全文索引、删数据库行、删附件文件，没有回头路。
用户要的是两步式删除，加上一个可以收起来但随时能拿回来的归档。

目标：

1. 删除会话先进入**回收站**，主列表、搜索、计数里都不再出现
2. 回收站里可以**恢复**，也可以**永久删除**；永久删除才真正清理数据（行、消息节点、附件）
3. 归档按**助手**归拢：每个助手的会话列表里多一个「已归档」入口，点进去看这个助手归档的会话，可以恢复
4. 构建产物换包名和应用名，能和官方 RikkaHub 共存

非目标（本次不做）：

- 回收站自动清理（例如 30 天后自动删除）
- 归档列表内的搜索
- 多选批量操作
- Web UI（`web-ui/`）的归档/回收站界面：接口会返回正确数据，界面留到以后

反目标（做了就算失败）：

- 回收站里的会话从主列表、搜索结果、统计数字里漏出来
- 永久删除后附件文件还留在磁盘上
- 恢复后消息内容、分支、收藏关系丢失
- 保存会话时把归档/删除状态覆盖回默认值

## 二、现状（改动前）

- `ConversationEntity`（表 `conversationentity`）：`id`、`assistant_id`、`title`、`nodes`、`create_at`、`update_at`、`suggestions`、`is_pinned`、`custom_system_prompt`、`mode_injection_ids`、`lorebook_ids`、`workspace_cwd`、`folder_id`、`config`
- 数据库 `AppDatabase` 版本 32，`autoMigrations` 覆盖 1..32，另有若干手写迁移（如 `Migration_30_31`）
- `ConversationDAO` 有 17 条会话查询，全部没有状态过滤
- `ConversationRepository.deleteConversation()`：删 FTS 索引 → 事务内删行（消息节点靠 CASCADE）→ 删附件文件
- `ChatService.deleteConversation()`：停生成、清消息队列、把内存会话换成未落库的新会话，再删库
- `ChatService.restoreConversation()`：撤销删除，写回数据库并替换内存会话（已有能力，可直接复用）
- 抽屉 `ChatDrawer`：顶部 `DrawerActions`（助手/菜单/收藏），下方 `FolderBar`（文件夹 chip）+ 排序按钮，再下面是 `ConversationList`
- 文件夹 `conversation_folder` 是「助手内分组」，和归档是两件事：文件夹是用户自建的可见容器，归档是每个助手固定的一个隐藏区
- 全文搜索 `MessageFtsManager.search()` 已经 `JOIN conversationentity AS conversation`，可以直接加过滤条件

## 三、方案选择

- **A（选定）**：会话表加 `archived_at`、`deleted_at` 两个时间戳列，删除与归档都只改状态；永久删除时才执行原有清理逻辑。UI 上用抽屉入口 + 两个独立页面。
- B：独立 `trash` / `archive` 表，删的时候搬行。主表查询不用改，但 `message_node`、收藏、FTS、附件引用都挂在原行上，搬行容易断关联，恢复要反向搬，风险和收益不成比例。
- C：复用文件夹建两个系统文件夹。DAO 改动最小，但文件夹是用户可改名可删除的，系统文件夹要处处特判；且文件夹属于单个助手，回收站必须跨助手，语义冲突。

选 A 的理由：状态和数据分离，永久删除只有一条路径，恢复不用重建关联。

## 四、设计

### 4.1 数据模型

`ConversationEntity` 增加：

```kotlin
@ColumnInfo("archived_at", defaultValue = "0")
val archivedAt: Long = 0,
@ColumnInfo("deleted_at", defaultValue = "0")
val deletedAt: Long = 0,
```

`0` 表示「没有」。数据库 32 → 33，用 `AutoMigration(from = 32, to = 33)`；两列都有默认值，Room 可以自动生成 `ALTER TABLE ADD COLUMN`。

**不变量**：`archived_at` 与 `deleted_at` 至多一个非零。

- 归档：`archived_at = now`，同时 `is_pinned = 0`
- 删除：`deleted_at = now`，`archived_at = 0`（从归档里删除也走这条）
- 恢复（从回收站）：`deleted_at = 0`、`archived_at = 0`，回到主列表
- 取消归档：`archived_at = 0`，回到主列表

**关键风险**：`ConversationRepository.saveConversation()` 是整对象写回（`conversationDAO.update(...)`）。`Conversation` 模型必须同步带上 `archivedAt`、`deletedAt`，并且三个映射函数都要透传，否则任何一次保存都会把状态清零。

需要改的映射：

- `conversationEntityToConversation`
- `conversationToConversationEntity`
- `conversationSummaryToConversation`（`LightConversationEntity` 投影也要加这两列）

`data/model/Conversation.kt` 增加 `archivedAt: Long = 0`、`deletedAt: Long = 0`。

**写库保护**：`ConversationStore.write()` 现在用 `existsConversationById()` 决定 insert 还是 update。
软删后行还在，若不处理，页面里的「空会话」一发送消息就会 update 回 `deleted_at = 0`，
把回收站那条复活并清空消息节点。处理方式：

- 所有删除入口（抽屉、历史页、HTTP DELETE）统一把活跃会话换成**新随机 id 的空会话**并导航过去
- `ConversationStore.write()` 落库前检查目标行的 `deleted_at`：大于 0 时拒绝整对象更新，
  改为插入一条新 id 的会话（数据不丢，也不复活旧条目）

### 4.2 DAO

所有「可见会话」查询加 `deleted_at = 0 AND archived_at = 0`：

| 查询 | 用途 |
|---|---|
| `getAll` / `getAllPaging` | 全部列表、分页 |
| `getConversationsOfAssistant` | 助手会话 |
| `getConversationsOfAssistantPaging` | 助手会话分页 |
| `getUnfiledConversationsOfAssistantPaging` | 抽屉主列表 |
| `getConversationsOfFolderPaging` | 文件夹内 |
| `getRecentConversationsOfAssistant` | 最近会话 |
| `searchConversations` / `searchConversationsPaging` | 按标题搜 |
| `searchConversationsOfAssistant` / `...Paging` | 助手内按标题搜 |
| `getPinnedConversations` | 置顶区 |
| `countAll` / `countByAssistant` / `getConversationCountPerDay` | 统计 |
| `getAllIds` | FTS 重建范围 |
| `getTitlesOfAssistant` | 标题去重（排除归档与回收站，避免新标题撞已隐藏的旧标题） |

新增：

```kotlin
@Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId AND archived_at > 0 AND deleted_at = 0 ORDER BY archived_at DESC")
fun getArchivedOfAssistant(assistantId: String): Flow<List<ConversationEntity>>

@Query("SELECT COUNT(*) FROM conversationentity WHERE assistant_id = :assistantId AND archived_at > 0 AND deleted_at = 0")
fun countArchivedOfAssistant(assistantId: String): Flow<Int>

@Query("SELECT * FROM conversationentity WHERE deleted_at > 0 ORDER BY deleted_at DESC")
fun getTrashed(): Flow<List<ConversationEntity>>

@Query("SELECT COUNT(*) FROM conversationentity WHERE deleted_at > 0")
fun countTrashed(): Flow<Int>

@Query("UPDATE conversationentity SET archived_at = :now, is_pinned = 0 WHERE id = :id")
suspend fun archive(id: String, now: Long)

@Query("UPDATE conversationentity SET archived_at = 0 WHERE id = :id")
suspend fun unarchive(id: String)

@Query("UPDATE conversationentity SET deleted_at = :now, archived_at = 0, is_pinned = 0 WHERE id = :id")
suspend fun moveToTrash(id: String, now: Long)

@Query("UPDATE conversationentity SET deleted_at = 0, archived_at = 0 WHERE id = :id")
suspend fun restoreFromTrash(id: String)

@Query("SELECT id FROM conversationentity WHERE deleted_at > 0")
suspend fun getTrashedIds(): List<String>
```

### 4.3 全文搜索

`MessageFtsManager.search()` 的 SQL 加：

```sql
AND conversation.deleted_at = 0
AND conversation.archived_at = 0
```

索引策略：

- 移到回收站时删掉该会话的 FTS 索引（沿用现有 `deleteConversation`）
- 恢复时重建索引（`indexConversation`）
- 归档时保留索引（数据还在，只是界面不展示；搜索条件已排除）

### 4.4 Repository

保留现有 `deleteConversation()` 作为「永久删除」的内部实现，改名或包一层：

```kotlin
suspend fun permanentlyDeleteConversation(conversation: Conversation)  // 原 deleteConversation 逻辑
suspend fun moveToTrash(id: Uuid)          // 软删 + 删 FTS
suspend fun restoreFromTrash(conversation: Conversation)  // 清白状态 + 重建 FTS
suspend fun archiveConversation(id: Uuid)
suspend fun unarchiveConversation(id: Uuid)
fun getArchivedConversations(assistantId: Uuid): Flow<List<Conversation>>
fun countArchived(assistantId: Uuid): Flow<Int>
fun getTrashedConversations(): Flow<List<Conversation>>
fun countTrashed(): Flow<Int>
suspend fun emptyTrash()                   // 遍历回收站，逐个永久删除（含附件）
```

移动/归档一律走 DAO 的单列 UPDATE，不做整对象写回。

`permanentlyDeleteConversation` 保留原 `deleteConversation` 的全部清理（FTS、行、附件），
并且只接受 `deleted_at > 0` 的会话，防止绕过回收站直接硬删。

### 4.5 ChatService

软删除要和内存会话保持一致（现在 `deleteConversation` 已有这套处理）：

- `moveToTrash(conversationId)`：停生成 → 清队列 → 内存换成未落库的新会话 → 写 `deleted_at` + 删 FTS
- `restoreFromTrash(conversation)`：复用 `restoreConversation` 的替换逻辑，另外清除状态位并重建 FTS
- `permanentlyDelete(conversationId)`：现有 `deleteConversation` 的硬删路径
- `emptyTrash()`：逐个永久删除
- `archiveConversation` / `unarchiveConversation`：只改数据库；内存会话若存在，同步改内存里的 `archivedAt`，避免下一次保存覆盖
- `deleteConversationsOfAssistant(assistantId)`：**保持硬删**，并且按 `assistant_id` 全量删
  （不经过可见性过滤，归档和回收站里的也一起清掉）。理由：助手被删除后，
  回收站条目会指向不存在的助手，恢复后无处可去；归档入口也随助手一起消失，
  这些会话只会变成永远拿不到的孤儿数据。删除助手本身已经是带确认的破坏性操作。

### 4.6 界面

**抽屉（当前助手）**

- 文件夹 chip 行下方加两行入口：
  - `🗄 已归档  12  ›` → `Screen.Archived(assistantId)`，计数来自 `countArchived`，为 0 时仍显示
  - `🗑 回收站  3  ›` → `Screen.Trash`，全局入口，不跟随助手
- 不放底部动作行：那一行已有助手 / 菜单 / 收藏 / 统计 / 设置 五项，300dp 抽屉再加会很挤

**会话项菜单**

- 新增「归档」（放在「移动到文件夹」之后）
- 「删除」文案改为「删除」，行为改为移入回收站
- 删除后 Snackbar 提示「已移到回收站」+「撤销」，撤销调 `restoreFromTrash`

**已归档页**

- TopAppBar：标题「已归档」+ 返回
- 列表：归档时间倒序，显示标题、归档时间
- 点击：打开会话，**可以继续发消息**；发送后会话仍留在归档里，不自动取消归档
  （归档是「收起来」，不是「冻结」；想回到主列表点「恢复」）
- 项菜单：恢复、删除（移入回收站）
- 空状态：「这个助手还没有归档的会话」

**回收站页**

- TopAppBar：标题「回收站」+ 清空按钮（有内容时可用）
- 列表：删除时间倒序，每项显示标题、所属助手名、删除时间
- 项操作：恢复、永久删除（二次确认对话框，写明不可恢复）
- 清空：二次确认对话框
- 空状态：「回收站是空的」
- 回收站项不提供「打开会话」

**导航**

`RouteActivity.kt` 的 `Screen` 增加：

```kotlin
@Serializable data class Archived(val assistantId: String) : Screen
@Serializable data object Trash : Screen
```

**字符串**

`values/strings.xml` 与 `values-zh/strings.xml` 新增：`chat_page_archive`、`chat_page_archived_title`、`chat_page_archive_empty`、`chat_page_moved_to_trash`、`chat_page_undo`、`trash_page_title`、`trash_page_empty`、`trash_page_restore`、`trash_page_delete_forever`、`trash_page_delete_forever_confirm`、`trash_page_empty_confirm`、`trash_page_emptied`。其余语言缺失时回落英文。

### 4.7 与其他功能的交界

- **备份**：`BackupManager` 是整库 `VACUUM INTO` 快照 + 附件目录打包，不是按查询导出。
  本次**不做过滤**：回收站内容会跟着备份走，恢复备份后回收站原样回来。理由是过滤要在快照副本上
  删行、清 `message_fts`、剔除只被回收站引用的附件，工作量和风险都不小，而「备份包含回收站」并不丢数据
- **统计**：`StatsVM.totalMessages` 现在直接 `messageNodeDAO.countAll()`。改为 join 会话表，
  排除 `deleted_at > 0`；归档会话**计入**（内容还在，只是收起来了）
- **HTTP API**：`DELETE /api/conversations/{id}` 改为软删（进回收站），
  新增 `?permanent=true` 走永久删除；`GET /api/conversations/{id}` 对归档/回收站会话仍返回 200
  （带上 `archivedAt` / `deletedAt`），列表接口则排除它们
- **收藏**：收藏指向消息节点，会话进回收站后收藏仍存在；永久删除时节点级联消失。本次不特殊处理，记录为已知行为
- **Web UI**：数据层过滤后自动生效；归档/回收站页面不在本次范围
- **文件夹**：会话在文件夹内被删除时，`folder_id` 保留，恢复后回到原文件夹

### 4.8 包名与应用名

- `app/build.gradle.kts`：`applicationId = "me.rerere.rikkahub.sol"`，`namespace` 保持 `me.rerere.rikkahub`
- debug 构建仍是 `applicationIdSuffix = ".debug"` → 最终 `me.rerere.rikkahub.sol.debug`
- `values/strings.xml`：`app_name` → `RikkaHub Sol`
- CI 占位 `google-services.json` 同时包含 `me.rerere.rikkahub.sol` 与 `me.rerere.rikkahub.sol.debug` 两个 client，否则 Firebase 插件在配置阶段失败

## 五、验证

本地没有 Android SDK、内存 2 GB，跑不了 Gradle 构建。验证靠三层：

1. **静态自查**：`grep` 出 `ConversationDAO` 与 `MessageFtsManager` 里所有 `conversationentity` 查询，逐条确认过滤；确认三处映射函数透传状态列
2. **CI 构建**：push 后 GitHub Actions 出 debug APK，作为编译级验证
3. **真机清单**（装 `me.rerere.rikkahub.sol.debug`）：
   - 删除会话 → 主列表消失 → 回收站出现 → 恢复 → 回到原位置（含文件夹）
   - 回收站永久删除 → 会话消失，附件目录对应文件消失
   - 归档 → 主列表消失、已归档计数 +1 → 进入已归档能看到 → 恢复 → 回主列表
   - 归档会话在全文搜索里搜不到；回收站会话在标题搜索里搜不到
   - 删除时正在生成的会话：生成停止，回收站条目完整
   - 删除当前打开的会话：页面变为空会话，不崩溃
   - 旧版本升级（32 → 33）后历史会话全部还在

## 六、留给实现者的决定

- 已归档页是否需要显示文件夹归属（当前不显示）
- 回收站页是否按助手分组（当前是平铺 + 助手名）
- 删除后 Snackbar 的停留时长与是否影响连续操作
