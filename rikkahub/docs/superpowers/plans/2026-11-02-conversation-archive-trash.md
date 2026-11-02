# 会话归档与回收站 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 RikkaHub 的会话加上「归档」和「回收站」，删除改为两步式，并把构建产物换成可共存的包名 `me.rerere.rikkahub.sol` / 应用名 `RikkaHub Sol`。

**Architecture:** 在 `ConversationEntity` 上增加 `archived_at` / `deleted_at` 两个时间戳列（0 表示没有），所有可见性由 SQL 过滤决定；删除只打标记并移除全文索引，永久删除才执行原有清理。界面在抽屉里增加「已归档」和「回收站」两个入口，各配一个独立页面。

**Tech Stack:** Kotlin、Jetpack Compose、Room 2.x（autoMigration，schema 导出到 `app/schemas`）、Paging 3、Koin、GitHub Actions。

**Spec:** `docs/superpowers/specs/2026-11-02-conversation-archive-trash-design.md`

## Global Constraints

- 包名：`applicationId = "me.rerere.rikkahub.sol"`，`namespace` 保持 `me.rerere.rikkahub`；debug 后缀 `.debug` → 最终 `me.rerere.rikkahub.sol.debug`
- 应用名：`app_name = "RikkaHub Sol"`（`values/strings.xml`）
- 数据库版本：32 → 33，使用 `AutoMigration(from = 32, to = 33)`
- `archived_at` 与 `deleted_at` 至多一个非零；`0` 表示「没有」
- 本机没有 Android SDK、内存 2 GB，**不能本地跑 Gradle**。所有编译与单元测试在 GitHub Actions 上验证：`/mnt/data1/build-hub` push 触发 `.github/workflows/rikkahub-apk.yml`
- 代码工作副本：`/mnt/data1/build-hub/rikkahub`（上游纯净副本在 `/mnt/data1/projects/rikkahub`，只用于 diff 对照）
- 源码根：`app/src/main/java/me/rerere/rikkahub/`
- 每个任务结束都要 commit；提交信息用 `feat:` / `fix:` / `test:` 前缀
- 不新增第三方依赖

## Review Focus

1. **整对象写回把状态清零**：任何一次 `saveConversation` 都不能把 `archived_at` / `deleted_at` 写回 0（Task 1 的映射单测钉住）
2. **已删会话被新消息复活**：对回收站里的 id 写入时不能 update 原行（Task 4 的守卫 + Task 5 的会话替换）
3. **漏过滤导致回收站条目泄漏到主列表或搜索**：每条 `conversationentity` 查询都要有可见性条件（DAO 测试需要 Android 运行时，本计划用 Task 2 Step 4 的静态审计代替自动化测试）
4. **永久删除后附件残留**：只有 `permanentlyDeleteConversation` 路径清理文件，且必须真的调用 `filesManager.deleteChatFiles`（Task 3）
5. **恢复后内容或归属丢失**：恢复要重建全文索引、保留 `folderId`（Task 3、Task 5）

---

### Task 1: 状态列、模型透传与映射单测

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/db/entity/ConversationEntity.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/data/repository/ConversationMapper.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt`（三个映射函数改为委托 Mapper，`LightConversationEntity` 加两列）
- Modify: `app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt`（version 33 + AutoMigration）
- Test: `app/src/test/java/me/rerere/rikkahub/data/repository/ConversationStatusMapperTest.kt`

**Interfaces:**
- Produces:
  - `Conversation.archivedAt: Long`、`Conversation.deletedAt: Long`
  - `ConversationEntity.archivedAt: Long`、`ConversationEntity.deletedAt: Long`
  - `LightConversationEntity.archivedAt: Long`、`LightConversationEntity.deletedAt: Long`
  - `object ConversationMapper { fun toEntity(c: Conversation): ConversationEntity; fun toConversation(e: ConversationEntity, nodes: List<MessageNode>): Conversation; fun toSummary(e: LightConversationEntity): Conversation }`

- [ ] **Step 1: 写失败的测试**

```kotlin
class ConversationStatusMapperTest {
    @Test
    fun `entity round trip keeps archive and trash flags`() {
        val conversation = sampleConversation(archivedAt = 111L, deletedAt = 0L)
        val entity = ConversationMapper.toEntity(conversation)
        assertEquals(111L, entity.archivedAt)
        assertEquals(0L, entity.deletedAt)

        val back = ConversationMapper.toConversation(entity, emptyList())
        assertEquals(111L, back.archivedAt)
        assertEquals(0L, back.deletedAt)
    }

    @Test
    fun `summary mapping keeps flags`() {
        val summary = ConversationMapper.toSummary(
            LightConversationEntity(
                id = "00000000-0000-0000-0000-000000000001",
                assistantId = "00000000-0000-0000-0000-000000000002",
                title = "t", isPinned = false, createAt = 0, updateAt = 0,
                folderId = "", archivedAt = 0, deletedAt = 222L,
            )
        )
        assertEquals(222L, summary.deletedAt)
    }

    private fun sampleConversation(archivedAt: Long, deletedAt: Long) = Conversation(
        id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
        assistantId = Uuid.parse("00000000-0000-0000-0000-000000000002"),
        messageNodes = emptyList(),
        archivedAt = archivedAt,
        deletedAt = deletedAt,
    )
}
```

- [ ] **Step 2: 实现数据列与映射**

`ConversationEntity` 末尾追加（带默认值，Room 自动迁移才能生成 ADD COLUMN）：

```kotlin
@ColumnInfo("archived_at", defaultValue = "0")
val archivedAt: Long = 0,
@ColumnInfo("deleted_at", defaultValue = "0")
val deletedAt: Long = 0,
```

`Conversation` 在 `folderId` 后追加 `val archivedAt: Long = 0`、`val deletedAt: Long = 0`。

`ConversationMapper.kt` 把 `conversationToConversationEntity`、`conversationEntityToConversation`、`conversationSummaryToConversation` 的现有函数体原样搬进来，并透传两列；Repository 里三个同名函数改成一行委托，保持现有调用点不变。

`AppDatabase`：`version = 33`，`autoMigrations` 数组末尾加 `AutoMigration(from = 32, to = 33)`。

- [ ] **Step 3: 确认没有遗漏的构造点**

Run: `grep -rn "ConversationEntity(" app/src --include=*.kt | grep -v "data class"`
Expected: 只有测试/迁移代码，全部用命名参数或能接受新默认值

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/data app/src/test/java/me/rerere/rikkahub/data
git commit -m "feat(db): add archived_at/deleted_at columns and mapping"
```

---

### Task 2: DAO 可见性过滤与归档/回收站查询

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/db/dao/ConversationDAO.kt`

**Interfaces:**
- Produces:
  - `fun getArchivedOfAssistant(assistantId: String): Flow<List<ConversationEntity>>`
  - `fun countArchivedOfAssistant(assistantId: String): Flow<Int>`
  - `fun getTrashed(): Flow<List<ConversationEntity>>`
  - `fun countTrashed(): Flow<Int>`
  - `suspend fun archive(id: String, now: Long)`
  - `suspend fun unarchive(id: String)`
  - `suspend fun moveToTrash(id: String, now: Long)`
  - `suspend fun restoreFromTrash(id: String)`
  - `suspend fun getTrashedIds(): List<String>`
  - `suspend fun getAllIdsOfAssistant(assistantId: String): List<String>`

- [ ] **Step 1: 给 17 条可见查询加条件**

统一追加 `AND deleted_at = 0 AND archived_at = 0`（已有 WHERE 用 AND，没有则加 WHERE）：

`getAll`、`getAllPaging`、`getConversationsOfAssistant`、`getTitlesOfAssistant`、`getConversationsOfAssistantPaging`、`getUnfiledConversationsOfAssistantPaging`、`getConversationsOfFolderPaging`、`getRecentConversationsOfAssistant`、`searchConversations`、`searchConversationsPaging`、`searchConversationsOfAssistant`、`searchConversationsOfAssistantPaging`、`getPinnedConversations`、`countAll`、`countByAssistant`、`getConversationCountPerDay`、`getAllIds`。

`LightConversationEntity` 的投影同步补 `archived_at as archivedAt, deleted_at as deletedAt`。

- [ ] **Step 2: 新增归档/回收站查询**

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

@Query("UPDATE conversationentity SET deleted_at = 0 WHERE id = :id")
suspend fun restoreFromTrash(id: String)

@Query("SELECT id FROM conversationentity WHERE deleted_at > 0")
suspend fun getTrashedIds(): List<String>

@Query("SELECT id FROM conversationentity WHERE assistant_id = :assistantId")
suspend fun getAllIdsOfAssistant(assistantId: String): List<String>
```

- [ ] **Step 3: 全文搜索加同样的过滤**

`app/src/main/java/me/rerere/rikkahub/data/db/fts/MessageFtsManager.kt` 的 `search()`，在 `WHERE message_fts.text MATCH ?` 之后、`$assistantFilter` 之前插入：

```sql
AND conversation.deleted_at = 0
AND conversation.archived_at = 0
```

（`search()` 已经 `JOIN conversationentity AS conversation`，不需要额外 join。）

- [ ] **Step 4: 逐条核对，确认没有裸查询**

Run: `grep -n "FROM conversationentity" app/src/main/java/me/rerere/rikkahub/data/db/dao/ConversationDAO.kt`
Expected: 每条 `SELECT` 都含 `deleted_at` 条件，或有明确的 `archived_at > 0` / `deleted_at > 0` 意图

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/db/dao/ConversationDAO.kt app/src/main/java/me/rerere/rikkahub/data/db/fts/MessageFtsManager.kt
git commit -m "feat(db): filter archived and trashed conversations from visible queries"
```

---

### Task 3: Repository 的归档/软删/恢复/永久删除

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt`

**Interfaces:**
- Consumes: Task 2 的 DAO 方法
- Produces:
  - `suspend fun moveToTrash(id: Uuid)`
  - `suspend fun restoreFromTrash(conversation: Conversation)`
  - `suspend fun permanentlyDeleteConversation(conversation: Conversation)`
  - `suspend fun archiveConversation(id: Uuid)`
  - `suspend fun unarchiveConversation(id: Uuid)`
  - `fun getArchivedConversations(assistantId: Uuid): Flow<List<Conversation>>`
  - `fun countArchived(assistantId: Uuid): Flow<Int>`
  - `fun getTrashedConversations(): Flow<List<Conversation>>`
  - `fun countTrashed(): Flow<Int>`
  - `suspend fun emptyTrash()`
  - `suspend fun permanentlyDeleteAllOfAssistant(assistantId: Uuid)`

- [ ] **Step 1: 现有 `deleteConversation` 改名为 `permanentlyDeleteConversation`**

函数体不变（删 FTS → 事务删行 → `filesManager.deleteChatFiles`）。在入口加守卫：

```kotlin
val persistedDeletedAt = conversationDAO.getConversationById(conversation.id.toString())?.deletedAt ?: 0L
require(conversation.deletedAt > 0L || persistedDeletedAt > 0L) {
    "permanentlyDeleteConversation only accepts trashed conversations"
}
```

- [ ] **Step 2: 新增软删与恢复**

```kotlin
suspend fun moveToTrash(id: Uuid) {
    messageFtsManager.deleteConversation(id.toString())
    conversationDAO.moveToTrash(id.toString(), System.currentTimeMillis())
}

suspend fun restoreFromTrash(conversation: Conversation) {
    conversationDAO.restoreFromTrash(conversation.id.toString())
    messageFtsManager.indexConversation(conversation.copy(deletedAt = 0, archivedAt = 0))
}
```

`emptyTrash()`：取 `getTrashedIds()`，逐个 `getConversationById` → `permanentlyDeleteConversation`，外层 `database.withTransaction` 之外执行（附件删除是 IO，不放进事务）。

`permanentlyDeleteAllOfAssistant()`：`getAllIdsOfAssistant` 全量走永久删除，不经过可见性过滤。

- [ ] **Step 3: 新增归档查询与操作**

六个方法按 Task 2 的 DAO 一一对应；`getArchivedConversations` / `getTrashedConversations` 用 `conversationSummaryToConversation` 映射（列表不需要 nodes）。

- [ ] **Step 4: 更新所有调用点**

Run: `grep -rn "deleteConversation(" app/src/main/java --include=*.kt`
Expected: 只剩余 `messageFtsManager.deleteConversation`（索引层）与 `permanentlyDeleteConversation`；其余调用点改到 Task 5 的 ChatService 方法

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt
git commit -m "feat(repo): soft delete, restore, archive and permanent delete"
```

---

### Task 4: 落库保护，防止回收站会话被复活

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt:133`（`conversationStore.write`）
- Modify: `app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt`（`updateConversation` 前的判定）
- Test: `app/src/test/java/me/rerere/rikkahub/data/repository/TrashedWriteGuardTest.kt`（纯函数部分）

**Interfaces:**
- Produces: `fun ConversationRepository.shouldInsertInsteadOfUpdate(existsInDb: Boolean, existingDeletedAt: Long): Boolean`

- [ ] **Step 1: 写失败的测试**

```kotlin
class TrashedWriteGuardTest {
    @Test fun `trashed row forces insert`() = assertTrue(shouldInsertInsteadOfUpdate(existsInDb = true, existingDeletedAt = 1L))
    @Test fun `visible row keeps update`() = assertFalse(shouldInsertInsteadOfUpdate(existsInDb = true, existingDeletedAt = 0L))
    @Test fun `missing row inserts`() = assertTrue(shouldInsertInsteadOfUpdate(existsInDb = false, existingDeletedAt = 0L))
}
```

- [ ] **Step 2: 实现判定并在 write 里使用**

`shouldInsertInsteadOfUpdate` 作为 `ConversationRepository.kt` 顶层函数。`write()` 先查目标行状态（`getConversationById`），命中 `deletedAt > 0` 时改为 `insertConversation(current.copy(id = Uuid.random()))`，并 `Log.w` 记录一次，避免页面继续往已删 id 写。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/service/ChatService.kt app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt app/src/test/java/me/rerere/rikkahub/data/repository/TrashedWriteGuardTest.kt
git commit -m "fix(chat): never resurrect a trashed conversation on save"
```

---

### Task 5: ChatService 的软删/恢复/永久删除与调用点迁移

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt:1087-1115`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt:306`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/history/HistoryVM.kt:37`
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatDrawer.kt:328`

**Interfaces:**
- Produces:
  - `suspend fun moveToTrash(conversationId: Uuid)`
  - `suspend fun restoreFromTrash(conversation: Conversation)`
  - `suspend fun permanentlyDelete(conversationId: Uuid)`
  - `suspend fun emptyTrash()`
  - `suspend fun archiveConversation(id: Uuid)` / `suspend fun unarchiveConversation(id: Uuid)`
  - `suspend fun deleteConversationsOfAssistant(assistantId: Uuid)`（保持硬删，改用 `permanentlyDeleteAllOfAssistant`）

- [ ] **Step 1: `deleteConversation` 拆成两个**

`moveToTrash`：复用现有 `deleteConversation` 的会话处理（停生成、`discardQueuedMessages`、`session.replace(newConversation(...), existsInDatabase = false)`），事务体换成 `conversationRepo.moveToTrash(id)`。`permanentlyDelete`：同样的会话处理 + `conversationRepo.permanentlyDeleteConversation(it)` + `filesManager.deleteChatFiles(previous.files)`。

`deleteConversationsOfAssistant` 改为：`conversationRepo.permanentlyDeleteAllOfAssistant(assistantId)` 之后再清理活跃 session（现有 `loaded` 分支逻辑保留）。

- [ ] **Step 2: 归档时同步内存态**

`archiveConversation` / `unarchiveConversation`：写库后若 `sessionManager` 里有该会话，`session.replace(state.copy(archivedAt = ...))`，避免下一次整对象保存把归档状态覆盖掉。

- [ ] **Step 3: 迁移调用点**

- `ChatVM.deleteConversation` → `chatService.moveToTrash`，并保留现有的导航到新会话
- `HistoryVM.deleteConversation` → `chatService.moveToTrash`
- `ChatDrawer` 删除项 → `vm.deleteConversation(it)`（语义已变，无需改调用名）
- `ConversationRoutes.delete` → 见 Task 6

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/service app/src/main/java/me/rerere/rikkahub/ui/pages
git commit -m "feat(chat): route deletions through trash, keep assistant delete hard"
```

---

### Task 6: 统计口径与 HTTP API

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/stats/StatsVM.kt:118`
- Modify: `app/src/main/java/me/rerere/rikkahub/data/db/dao/MessageNodeDAO.kt`（新增计数）
- Modify: `app/src/main/java/me/rerere/rikkahub/web/routes/ConversationRoutes.kt:156-162`

**Interfaces:**
- Produces: `suspend fun MessageNodeDAO.countVisible(): Int`
- Produces: `DELETE /api/conversations/{id}`（软删）、`DELETE /api/conversations/{id}?permanent=true`（永久删）

- [ ] **Step 1: 统计只算可见会话的消息**

```sql
SELECT COUNT(*) FROM message_node mn
JOIN conversationentity c ON c.id = mn.conversation_id
WHERE c.deleted_at = 0
```

`StatsVM` 改调 `countVisible()`；归档会话计入（不额外过滤 `archived_at`）。

- [ ] **Step 2: HTTP 语义**

```kotlin
val permanent = call.request.queryParameters["permanent"]?.toBoolean() ?: false
if (permanent) chatService.permanentlyDelete(uuid) else chatService.moveToTrash(uuid)
```

`GET /api/conversations/{id}` 保持返回归档/回收站会话（带 `archivedAt`/`deletedAt`）；列表接口因 DAO 过滤自动排除。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/pages/stats app/src/main/java/me/rerere/rikkahub/data/db/dao/MessageNodeDAO.kt app/src/main/java/me/rerere/rikkahub/web/routes/ConversationRoutes.kt
git commit -m "feat(stats,api): exclude trashed messages, soft delete by default"
```

---

### Task 7: 抽屉入口与会话项菜单

**Files:**
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatDrawer.kt`（`drawerHeader` 里 `FolderBar` 之后、`DrawerActions` 之后）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatDrawerVM.kt`（暴露计数）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ConversationList.kt`（菜单加「归档」）
- Modify: `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt`（归档动作 + Snackbar）

**Interfaces:**
- Consumes: `countArchived(assistantId)`、`countTrashed()`、`archiveConversation(id)`
- Produces: `ChatDrawerVM.archivedCount: StateFlow<Int>`、`ChatDrawerVM.trashedCount: StateFlow<Int>`

- [ ] **Step 1: 加两行入口**

在文件夹 chip 行下方插入两个 `DrawerEntry`：

```kotlin
DrawerEntry(icon = HugeIcons.Inbox, text = stringResource(R.string.chat_page_archived, archivedCount), onClick = { navController.navigate(Screen.Archived(settings.assistantId.toString())) })
DrawerEntry(icon = HugeIcons.Delete02, text = stringResource(R.string.trash_page_title, trashedCount), onClick = { navController.navigate(Screen.Trash) })
```

两个计数用 `stateIn(viewModelScope, WhileSubscribed(5000), 0)`。

- [ ] **Step 2: 会话项菜单加「归档」**

`ConversationList` 的参数表加 `onArchive: (Conversation) -> Unit = {}`，菜单在「移动到文件夹」后插入「归档」项，图标 `HugeIcons.Archive`（若该图标不存在，用 `HugeIcons.Inbox`）。

- [ ] **Step 3: 删除后的 Snackbar**

`ChatVM.deleteConversation` 成功后把被删会话暂存，通过现有的 Snackbar host 显示 `chat_page_moved_to_trash` + `chat_page_undo`，撤销时调 `chatService.restoreFromTrash(conversation)`。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/pages/chat
git commit -m "feat(ui): archive and trash entries in drawer"
```

---

### Task 8: 已归档页

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/archive/ArchivePage.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/archive/ArchiveVM.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/RouteActivity.kt`（`Screen` 加 `Archived`，注册导航目标）

**Interfaces:**
- Consumes: `getArchivedConversations(assistantId)`、`unarchiveConversation(id)`、`ChatService.moveToTrash(id)`
- Produces: `@Serializable data class Screen.Archived(val assistantId: String) : Screen`

- [ ] **Step 1: VM**

`ArchiveVM(assistantId: String)`：`val conversations: StateFlow<List<Conversation>>`、`fun restore(id: Uuid)`、`fun delete(id: Uuid)`。

- [ ] **Step 2: 页面**

`Scaffold` + `TopAppBar`（标题 `chat_page_archived_title`，返回）+ `LazyColumn`。每项显示标题与归档日期；点击 `navigateToChatPage(navController, conversation.id)`（保持归档状态）；行尾菜单两项：恢复、删除。

空状态文案 `chat_page_archive_empty`。

- [ ] **Step 3: 注册导航**

在 `RouteActivity` 的 `NavDisplay` entryProvider 里，紧邻 `Screen.History` 的分支加 `entry<Screen.Archived> { ... }`。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/pages/archive app/src/main/java/me/rerere/rikkahub/RouteActivity.kt
git commit -m "feat(ui): archived conversations page per assistant"
```

---

### Task 9: 回收站页

**Files:**
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/trash/TrashPage.kt`
- Create: `app/src/main/java/me/rerere/rikkahub/ui/pages/trash/TrashVM.kt`
- Modify: `app/src/main/java/me/rerere/rikkahub/RouteActivity.kt`

**Interfaces:**
- Consumes: `getTrashedConversations()`、`countTrashed()`、`restoreFromTrash(conversation)`、`permanentlyDelete(id)`、`emptyTrash()`
- Produces: `@Serializable data object Screen.Trash : Screen`

- [ ] **Step 1: VM**

`TrashVM`：`conversations: StateFlow<List<Conversation>>`、`fun restore(conversation: Conversation)`、`fun deleteForever(id: Uuid)`、`fun empty()`。

- [ ] **Step 2: 页面**

列表项显示标题、所属助手名（从 `settingsStore` 的 `assistants` 按 `assistantId` 查，找不到显示「未知助手」）、删除日期。每项两个动作：恢复、永久删除（`AlertDialog` 二次确认）。`TopAppBar` 右上角清空按钮（有内容时启用，对话框确认）。空状态 `trash_page_empty`。回收站项不响应点击打开会话。

- [ ] **Step 3: 注册导航**

`entry<Screen.Trash> { ... }`。

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/me/rerere/rikkahub/ui/pages/trash app/src/main/java/me/rerere/rikkahub/RouteActivity.kt
git commit -m "feat(ui): trash page with restore and permanent delete"
```

---

### Task 10: 字符串

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`

- [ ] **Step 1: 加键**

英文值：

```xml
<string name="chat_page_archive">Archive</string>
<string name="chat_page_archived">Archived %1$d</string>
<string name="chat_page_archived_title">Archived</string>
<string name="chat_page_archive_empty">No archived conversations for this assistant</string>
<string name="chat_page_moved_to_trash">Moved to trash</string>
<string name="chat_page_undo">Undo</string>
<string name="trash_page_title">Trash %1$d</string>
<string name="trash_page_title_plain">Trash</string>
<string name="trash_page_empty">Trash is empty</string>
<string name="trash_page_restore">Restore</string>
<string name="trash_page_delete_forever">Delete forever</string>
<string name="trash_page_delete_forever_confirm">This cannot be undone. Delete this conversation permanently?</string>
<string name="trash_page_empty_confirm">Permanently delete everything in the trash?</string>
<string name="trash_page_unknown_assistant">Unknown assistant</string>
```

中文值对应：归档 / 已归档 %1$d / 已归档 / 这个助手还没有归档的会话 / 已移到回收站 / 撤销 / 回收站 %1$d / 回收站 / 回收站是空的 / 恢复 / 永久删除 / 此操作不可撤销，确定永久删除这条会话？ / 确定永久删除回收站里的所有会话？ / 未知助手。

- [ ] **Step 2: 提交**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml
git commit -m "feat(i18n): archive and trash strings"
```

---

### Task 11: 包名、应用名与 Firebase 占位

**Files:**
- Modify: `app/build.gradle.kts:26`、`app/build.gradle.kts:86`
- Modify: `app/src/main/res/values/strings.xml:10`
- Modify: `/mnt/data1/build-hub/.github/ci/google-services.json`

- [ ] **Step 1: 改 applicationId 与应用名**

```kotlin
applicationId = "me.rerere.rikkahub.sol"
```

`<string name="app_name">RikkaHub Sol</string>`。`namespace` 不动；debug 的 `applicationIdSuffix = ".debug"` 保留。

- [ ] **Step 2: 占位 google-services 覆盖两个包名**

`client` 数组里确保存在 `package_name` 为 `me.rerere.rikkahub.sol` 和 `me.rerere.rikkahub.sol.debug` 的两项（复制现有 client 结构，改包名即可）。

- [ ] **Step 3: 静态核对**

Run: `grep -rn "me.rerere.rikkahub\"" app/build.gradle.kts app/src/main/AndroidManifest.xml`
Expected: 只剩 `namespace`；`applicationId` 已是 `.sol`

- [ ] **Step 4: 提交**

```bash
git add app/build.gradle.kts app/src/main/res/values/strings.xml
git -C /mnt/data1/build-hub add .github/ci/google-services.json
git commit -m "build: rename app id to me.rerere.rikkahub.sol"
```

---

### Task 12: CI 单元测试与整包验证

**Files:**
- Modify: `/mnt/data1/build-hub/.github/workflows/rikkahub-apk.yml`

**Interfaces:**
- Consumes: 前 11 个任务的代码

- [ ] **Step 1: CI 加单元测试步骤**

在 `Build` 之前插入：

```yaml
- name: Run unit tests
  run: ./gradlew :app:testGithubDebugUnitTest --no-daemon --stacktrace
```

- [ ] **Step 2: push 并观察**

Run: `git -C /mnt/data1/build-hub push origin main`
Expected: Actions 触发新 run；`Run unit tests` 与 `Build` 均 success

- [ ] **Step 3: 失败时按 systematic-debugging 处理**

失败先读日志定位到具体任务，不叠加猜测式修改；同一处修三次不成立就回到 Phase 1 重新收集证据。

- [ ] **Step 4: 产物核对**

Run: 下载 artifact，`unzip -l` 确认三个 APK 存在；`aapt2 dump badging`（runner 上）确认 `package: name='me.rerere.rikkahub.sol.debug'`、`application-label:'RikkaHub Sol'`

- [ ] **Step 5: 提交**

```bash
git -C /mnt/data1/build-hub add .github/workflows/rikkahub-apk.yml
git -C /mnt/data1/build-hub commit -m "ci: run unit tests before build"
```

---

## 真机验收清单（构建成功后）

- [ ] 删除会话 → 主列表消失 → 回收站出现 → 恢复 → 回到原位置（含文件夹归属）
- [ ] 回收站永久删除 → 会话消失，`files/` 下对应附件消失
- [ ] 归档 → 主列表消失、已归档计数 +1 → 已归档页可见 → 恢复 → 回主列表
- [ ] 已归档会话打开后发消息：能发，且仍留在归档里
- [ ] 归档与回收站的会话在标题搜索、全文搜索里都搜不到
- [ ] 删除正在生成的会话：生成停止，回收站条目完整
- [ ] 删除当前打开的会话：页面变为空会话，再发消息不复活旧条目
- [ ] 统计页会话数与消息数同步下降（仅回收站部分）
- [ ] 升级安装（旧 32 版 → 新 33 版）后历史会话完整
