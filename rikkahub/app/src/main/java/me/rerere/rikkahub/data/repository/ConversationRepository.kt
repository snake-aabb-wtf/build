package me.rerere.rikkahub.data.repository

import android.database.sqlite.SQLiteBlobTooBigException
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.ConversationSortOrder
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

class ConversationRepository(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val favoriteDAO: FavoriteDAO,
    private val database: AppDatabase,
    private val filesManager: FilesManager,
    private val messageFtsManager: MessageFtsManager,
) {
    companion object {
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40
    }

    suspend fun hasFileReference(fileUrl: String): Boolean =
        messageNodeDAO.hasFileReference(JsonInstant.encodeToString(fileUrl))

    suspend fun getRecentConversations(assistantId: Uuid, limit: Int = 10): List<Conversation> {
        return conversationDAO.getRecentConversationsOfAssistant(
            assistantId = assistantId.toString(),
            limit = limit
        ).map { entity ->
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        }
    }

    fun getConversationsOfAssistant(assistantId: Uuid): Flow<List<Conversation>> {
        return conversationDAO
            .getConversationsOfAssistant(assistantId.toString())
            .map { flow ->
                flow.map { entity ->
                    // 列表视图不需要完整的 nodes，使用空列表
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    suspend fun getTitlesOfAssistant(assistantId: Uuid): Set<String> =
        conversationDAO.getTitlesOfAssistant(assistantId.toString()).toSet()

    fun getConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getUnfiledConversationsOfAssistantPaging(
        assistantId: Uuid,
        sortOrder: ConversationSortOrder = ConversationSortOrder.UPDATE_TIME,
    ): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = {
            conversationDAO.getUnfiledConversationsOfAssistantPaging(
                assistantId = assistantId.toString(),
                sortByCreateTime = sortOrder == ConversationSortOrder.CREATE_TIME,
            )
        }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getConversationsOfFolderPaging(
        folderId: Uuid,
        sortOrder: ConversationSortOrder = ConversationSortOrder.UPDATE_TIME,
    ): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = {
            conversationDAO.getConversationsOfFolderPaging(
                folderId = folderId.toString(),
                sortByCreateTime = sortOrder == ConversationSortOrder.CREATE_TIME,
            )
        }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    suspend fun getConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.getConversationsOfAssistantPaging(assistantId.toString())
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun searchConversationsOfAssistantPage(
        assistantId: Uuid,
        titleKeyword: String,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.searchConversationsOfAssistantPaging(
            assistantId = assistantId.toString(),
            searchText = titleKeyword
        )
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun getUnfiledConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString(), sortByCreateTime = false),
        offset,
        limit,
    )

    suspend fun getConversationsOfFolderPage(
        folderId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getConversationsOfFolderPaging(folderId.toString(), sortByCreateTime = false),
        offset,
        limit,
    )

    private suspend fun loadConversationPage(
        pagingSource: PagingSource<Int, LightConversationEntity>,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    fun searchConversations(titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversations(titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsPaging(titleKeyword: String): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.searchConversationsPaging(titleKeyword) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun searchConversationsOfAssistant(assistantId: Uuid, titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversationsOfAssistant(assistantId.toString(), titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsOfAssistantPaging(assistantId: Uuid, titleKeyword: String): Flow<PagingData<Conversation>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = INITIAL_LOAD_SIZE,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                conversationDAO.searchConversationsOfAssistantPaging(
                    assistantId.toString(),
                    titleKeyword
                )
            }
        ).flow.map { pagingData ->
            pagingData.map { entity ->
                conversationSummaryToConversation(entity)
            }
        }

    suspend fun getConversationById(uuid: Uuid): Conversation? {
        val entity = conversationDAO.getConversationById(uuid.toString())
        return if (entity != null) {
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        } else {
            null
        }
    }

    suspend fun getDeletedAtById(id: Uuid): Long? = conversationDAO.getDeletedAtById(id.toString())

    suspend fun existsConversationById(uuid: Uuid): Boolean {
        return conversationDAO.existsById(uuid.toString())
    }

    suspend fun countConversations(): Int {
        return conversationDAO.countAll()
    }

    suspend fun countConversationsByAssistant(): Map<Uuid, Int> {
        return conversationDAO.countByAssistant().mapNotNull { row ->
            runCatching { Uuid.parse(row.assistantId) }.getOrNull()?.let { it to row.count }
        }.toMap()
    }

    suspend fun insertConversation(conversation: Conversation) {
        database.withTransaction {
            conversationDAO.insert(
                conversationToConversationEntity(conversation)
            )
            saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
        }
        messageFtsManager.indexConversation(conversation)
    }

    suspend fun updateConversation(conversation: Conversation) {
        updateConversationIfVisible(conversation)
    }

    /**
     * 与 [updateConversation] 相同，但会告知调用方这一笔写入是否真的落库。
     * 返回 false 表示会话在事务内已被移入回收站，写入被跳过、数据库仍指向旧数据。
     */
    suspend fun updateConversationIfVisible(conversation: Conversation): Boolean {
        val written = database.withTransaction {
            // 事务内复核：读取与写入之间可能有删除落库，此时不能复活该行
            if ((conversationDAO.getDeletedAtById(conversation.id.toString()) ?: 0L) > 0L) {
                false
            } else {
                conversationDAO.update(
                    conversationToConversationEntity(conversation)
                )
                // 删除旧的节点，插入新的节点
                messageNodeDAO.deleteByConversation(conversation.id.toString())
                saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
                true
            }
        }
        if (written) {
            messageFtsManager.indexConversation(conversation)
        }
        return written
    }

    /**
     * 永久删除会话。只接受已经在回收站里的会话；可见会话请先 [moveToTrash]。
     */
    suspend fun permanentlyDeleteConversation(conversation: Conversation) {
        val persistedDeletedAt =
            conversationDAO.getConversationById(conversation.id.toString())?.deletedAt ?: 0L
        require(conversation.deletedAt > 0L || persistedDeletedAt > 0L) {
            "permanentlyDeleteConversation only accepts trashed conversations"
        }
        hardDeleteConversationUnchecked(conversation)
    }

    /**
     * 不做回收站校验的硬删除，仅供 [permanentlyDeleteConversation] 与
     * [permanentlyDeleteAllOfAssistant]（助手删除是可见会话的硬删除）使用。
     */
    private suspend fun hardDeleteConversationUnchecked(conversation: Conversation) {
        // 获取完整的 Conversation（包含 messageNodes）以正确清理文件
        val fullConversation = if (conversation.messageNodes.isEmpty()) {
            getConversationById(conversation.id) ?: conversation
        } else {
            conversation
        }
        messageFtsManager.deleteConversation(conversation.id.toString())
        database.withTransaction {
            // message_node 会通过 CASCADE 自动删除
            conversationDAO.delete(
                conversationToConversationEntity(conversation)
            )
        }
        filesManager.deleteChatFiles(fullConversation.files)
    }

    /** 软删除：移入回收站，同时把消息从搜索索引里摘掉 */
    suspend fun moveToTrash(id: Uuid) {
        messageFtsManager.deleteConversation(id.toString())
        conversationDAO.moveToTrash(id.toString(), System.currentTimeMillis())
    }

    /** 从回收站恢复：清掉删除标记并重建搜索索引 */
    suspend fun restoreFromTrash(conversation: Conversation) {
        conversationDAO.restoreFromTrash(conversation.id.toString())
        // 回收站列表给的是不带 nodes 的会话，必须回库取完整对象再建索引
        val restored = getConversationById(conversation.id) ?: return
        messageFtsManager.indexConversation(restored)
    }

    /** 清空回收站：逐个永久删除。附件清理是 IO，不放进外层事务 */
    suspend fun emptyTrash() {
        conversationDAO.getTrashedIds().forEach { id ->
            val uuid = runCatching { Uuid.parse(id) }.getOrNull() ?: return@forEach
            val conversation = getConversationById(uuid) ?: return@forEach
            // 快照与删除之间可能已被恢复，跳过而不是让守卫中断整批
            if (conversation.deletedAt <= 0L) return@forEach
            permanentlyDeleteConversation(conversation)
        }
    }

    /** 助手被删除时硬删除它名下的全部会话，不经过可见性过滤 */
    suspend fun permanentlyDeleteAllOfAssistant(assistantId: Uuid) {
        conversationDAO.getAllIdsOfAssistant(assistantId.toString()).forEach { id ->
            val uuid = runCatching { Uuid.parse(id) }.getOrNull() ?: return@forEach
            getConversationById(uuid)?.let { hardDeleteConversationUnchecked(it) }
        }
    }

    suspend fun archiveConversation(id: Uuid) {
        conversationDAO.archive(id.toString(), System.currentTimeMillis())
    }

    suspend fun unarchiveConversation(id: Uuid) {
        conversationDAO.unarchive(id.toString())
        // rebuildAllIndexes 只遍历可见会话，取消归档后必须在这里补一次索引
        getConversationById(id)?.let { messageFtsManager.indexConversation(it) }
    }

    fun getArchivedConversations(assistantId: Uuid): Flow<List<Conversation>> {
        return conversationDAO
            .getArchivedOfAssistant(assistantId.toString())
            .map { conversations ->
                conversations.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun countArchived(assistantId: Uuid): Flow<Int> {
        return conversationDAO.countArchivedOfAssistant(assistantId.toString())
    }

    fun getTrashedConversations(): Flow<List<Conversation>> {
        return conversationDAO
            .getTrashed()
            .map { conversations ->
                conversations.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun countTrashed(): Flow<Int> {
        return conversationDAO.countTrashed()
    }

    suspend fun searchMessages(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        assistantId: Uuid? = null,
    ) = messageFtsManager.search(keyword, sort, assistantId?.let { listOf(it.toString()) })

    /** 只在 [assistantIds] 这些助手的对话里搜索 */
    suspend fun searchMessagesOfAssistants(keyword: String, assistantIds: Set<Uuid>) =
        messageFtsManager.search(keyword, MessageSearchSort.RELEVANCE, assistantIds.map { it.toString() })

    suspend fun rebuildAllIndexes(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }) {
        messageFtsManager.deleteAll()
        val allIds = conversationDAO.getAllIds()
        val total = allIds.size
        allIds.forEachIndexed { index, id ->
            val entity = conversationDAO.getConversationById(id) ?: return@forEachIndexed
            val nodes = loadMessageNodes(entity.id)
            val conversation = conversationEntityToConversation(entity, nodes)
            messageFtsManager.indexConversation(conversation)
            onProgress(index + 1, total)
        }
    }

    fun conversationToConversationEntity(conversation: Conversation): ConversationEntity =
        ConversationMapper.toEntity(conversation)

    fun conversationEntityToConversation(
        conversationEntity: ConversationEntity,
        messageNodes: List<MessageNode>
    ): Conversation = ConversationMapper.toConversation(conversationEntity, messageNodes)

    fun getPinnedConversations(): Flow<List<Conversation>> {
        return conversationDAO
            .getPinnedConversations()
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    suspend fun updatePinStatus(conversationId: Uuid, isPinned: Boolean) {
        conversationDAO.updatePinStatus(
            id = conversationId.toString(),
            isPinned = isPinned,
        )
    }

    suspend fun updateConversationAssistant(conversationId: Uuid, assistantId: Uuid) {
        conversationDAO.updateAssistantId(conversationId.toString(), assistantId.toString())
    }

    /**
     * 只更新会话持有的配置（含注入绑定和工作目录），不重写消息节点。
     */
    suspend fun updateConversationConfig(conversation: Conversation) {
        conversationDAO.updateConfig(
            id = conversation.id.toString(),
            config = conversation.config?.let { JsonInstant.encodeToString(it) } ?: "",
            modeInjectionIds = JsonInstant.encodeToString(conversation.modeInjectionIds),
            lorebookIds = JsonInstant.encodeToString(conversation.lorebookIds),
            workspaceCwd = conversation.workspaceCwd ?: "",
        )
    }

    suspend fun updateTitle(conversationId: Uuid, title: String) {
        conversationDAO.updateTitle(conversationId.toString(), title)
    }

    suspend fun updateCustomSystemPrompt(conversationId: Uuid, customSystemPrompt: String?) {
        conversationDAO.updateCustomSystemPrompt(conversationId.toString(), customSystemPrompt ?: "")
    }

    suspend fun updateChatSuggestions(conversationId: Uuid, chatSuggestions: List<String>) {
        conversationDAO.updateChatSuggestions(
            id = conversationId.toString(),
            chatSuggestions = JsonInstant.encodeToString(chatSuggestions),
        )
    }

    /**
     * 单列更新会话的文件夹归属，folderId 为 null 表示移出文件夹（未归类）。
     */
    suspend fun updateConversationFolderId(conversationId: Uuid, folderId: Uuid?) {
        conversationDAO.updateFolderId(
            id = conversationId.toString(),
            folderId = folderId?.toString() ?: ""
        )
    }

    private fun conversationSummaryToConversation(entity: LightConversationEntity): Conversation =
        ConversationMapper.toSummary(entity)

    private suspend fun loadMessageNodes(conversationId: String): List<MessageNode> {
        val favoriteNodeIds = favoriteDAO
            .getFavoriteNodeIdsOfConversation(conversationId)
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            .toSet()

        return database.withTransaction {
            val nodes = mutableListOf<MessageNode>()
            var offset = 0
            val pageSize = 64
            while (true) {
                val page = try {
                    messageNodeDAO.getNodesOfConversationPaged(conversationId, pageSize, offset)
                } catch (e: SQLiteBlobTooBigException) {
                    e.printStackTrace()
                    offset += pageSize
                    continue
                } catch (e: CancellationException) {
                    // CancellationException 是 IllegalStateException 的子类，必须先重新抛出，否则取消后会在事务内无限循环
                    throw e
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                    offset += pageSize
                    continue
                }
                if (page.isEmpty()) break
                page.forEach { entity ->
                    val messages = JsonInstant.decodeFromString<List<UIMessage>>(entity.messages)
                    val nodeId = Uuid.parse(entity.id)
                    nodes.add(
                        MessageNode(
                            id = nodeId,
                            messages = messages,
                            selectIndex = entity.selectIndex,
                            isFavorite = favoriteNodeIds.contains(nodeId)
                        )
                    )
                }
                offset += page.size
            }
            nodes
        }
    }

    private suspend fun saveMessageNodes(conversationId: String, nodes: List<MessageNode>) {
        val entities = nodes.mapIndexed { index, node ->
            MessageNodeEntity(
                id = node.id.toString(),
                conversationId = conversationId,
                nodeIndex = index,
                messages = JsonInstant.encodeToString(node.messages),
                selectIndex = node.selectIndex
            )
        }
        messageNodeDAO.insertAll(entities)
    }
}

/**
 * 轻量级的会话查询结果，不包含 nodes 和 suggestions 字段
 */
data class LightConversationEntity(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val folderId: String = "",
    val archivedAt: Long = 0,
    val deletedAt: Long = 0,
)

data class ConversationPageResult(
    val items: List<Conversation>,
    val nextOffset: Int?,
)

/**
 * 已进入回收站的行（deletedAt > 0）不能再被普通保存覆盖，否则会复活会话并抹掉消息节点。
 * 这是最后一道防线：调用方应先驱逐或替换会话。
 */
fun shouldSkipTrashedWrite(existingDeletedAt: Long): Boolean = existingDeletedAt > 0L
