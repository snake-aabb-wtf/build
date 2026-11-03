package me.rerere.rikkahub.ui.pages.comparison

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.comparison.COMPARISON_MAX_CONVERSATIONS
import me.rerere.rikkahub.data.model.comparison.ComparisonCandidate
import me.rerere.rikkahub.data.model.comparison.ComparisonGraph
import me.rerere.rikkahub.data.model.comparison.ConversationLoad
import me.rerere.rikkahub.data.model.comparison.FileHasher
import me.rerere.rikkahub.data.model.comparison.branchSignature
import me.rerere.rikkahub.data.model.comparison.buildComparisonGraph
import me.rerere.rikkahub.data.model.comparison.canAddConversation
import me.rerere.rikkahub.data.model.comparison.conversationLoad
import me.rerere.rikkahub.data.model.comparison.expandedWithSubtree
import me.rerere.rikkahub.data.model.comparison.hasNoCommonStart
import me.rerere.rikkahub.data.model.comparison.shouldDrop
import me.rerere.rikkahub.data.model.localFileUrls
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.uuid.Uuid

enum class ComparisonViewMode {
    INDENTED,
    CANVAS,
    TIMELINE,
}

data class ComparisonConversationSummary(
    val id: Uuid,
    val title: String,
    val messageCount: Int,
)

/** 画布/时间线的视图状态：放在 VM 里，旋转、切换视图、换图都不重置。 */
data class ComparisonViewport(
    val scale: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
)

data class ComparisonUiState(
    val loading: Boolean = true,
    val hashing: Boolean = false,
    val graph: ComparisonGraph? = null,
    val conversations: List<ComparisonConversationSummary> = emptyList(),
    /** 当前参与对比的会话 id（按选择顺序），选择器和 chip 共用。 */
    val selectedIds: Set<Uuid> = emptySet(),
    val viewMode: ComparisonViewMode = ComparisonViewMode.INDENTED,
    val expanded: Set<String> = emptySet(),
    /** 画布视图的缩放/平移；pan 单位是像素。 */
    val canvasViewport: ComparisonViewport = ComparisonViewport(),
    /** 时间线视图的平移（scale 恒为 1）。 */
    val timelineViewport: ComparisonViewport = ComparisonViewport(),
    /** 加载进度：已读完的会话数 / 本次请求的会话数。 */
    val loadedCount: Int = 0,
    val totalCount: Int = 0,
    /** 加载时已经不存在、被移出对比的会话数 */
    val missingCount: Int = 0,
    /** 完全没有任何共同开头（空会话不计入）。 */
    val noCommonStart: Boolean = false,
    val failed: Boolean = false,
    /** 用户主动取消了本次构建。 */
    val cancelled: Boolean = false,
)

sealed interface ComparisonEvent {
    /** 对比页停留期间有会话被删除，已经从选择里移出。 */
    data class ConversationsMissing(val count: Int) : ComparisonEvent

    /** 选择数量已达到上限。 */
    data object SelectionLimitReached : ComparisonEvent
}

/**
 * 只读地加载选中的会话并建图。
 *
 * 会话读完一次就缓存在 [loaded] 里，增删会话只重建图，不再读数据库；附件哈希交给 [ComparisonFileHasher]
 * 按 (路径, 大小, 修改时间) 缓存。消息指纹每次重算：context checkpoint 会原地改写内容而不换 id，
 * 以 (会话, 消息 id) 为键的消息级缓存必然拿到过期指纹。
 * 视图模式和会话 id 放进 [SavedStateHandle]，进程重建后还能恢复。
 */
class ComparisonVM(
    ids: List<String>,
    private val conversationRepository: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val savedStateHandle: SavedStateHandle,
    private val fileHasher: FileHasher = ComparisonFileHasher(),
) : ViewModel() {
    private val order: MutableList<Uuid> = restoreIds(ids).toMutableList()
    private val loaded = HashMap<Uuid, Conversation>()

    /** 读入时的分支签名，用来判断会话在聊天页里被切了分支/编辑过。 */
    private val loadedSignatures = HashMap<Uuid, String>()

    private var buildJob: Job? = null
    private var syncJob: Job? = null

    /** 每次 rebuild 自增；旧 job 即使还没被调度器停下，也不能再写状态或缓存。 */
    private var generation = 0

    private val _state = MutableStateFlow(
        ComparisonUiState(viewMode = restoreViewMode())
    )
    val state: StateFlow<ComparisonUiState> = _state.asStateFlow()

    private val _events = Channel<ComparisonEvent>(Channel.BUFFERED)
    val events: Flow<ComparisonEvent> = _events.receiveAsFlow()

    /** 添加会话的选择器数据源：当前助手的可见会话，和会话列表页一致。 */
    val candidates: StateFlow<List<ComparisonCandidate>> = settingsStore.settingsFlow
        .map { it.getCurrentAssistant() }
        .flatMapLatest { assistant ->
            if (assistant == null) {
                flowOf(emptyList<Conversation>())
            } else {
                conversationRepository.getConversationsOfAssistant(assistant.id)
            }
        }
        .map { conversations ->
            conversations
                .distinctBy { it.id }
                .map { conversation ->
                    ComparisonCandidate(
                        id = conversation.id,
                        title = conversation.title,
                        updateAt = conversation.updateAt,
                    )
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        persistIds()
        rebuild()
    }

    fun addConversation(id: Uuid) {
        if (!canAddConversation(order.toSet(), id)) {
            if (id !in order) _events.trySend(ComparisonEvent.SelectionLimitReached)
            return
        }
        order += id
        persistIds()
        rebuild()
    }

    fun removeConversation(id: Uuid) {
        if (!order.remove(id)) return
        forget(id)
        persistIds()
        rebuild()
    }

    fun setViewMode(mode: ComparisonViewMode) {
        if (_state.value.viewMode == mode) return
        savedStateHandle[KEY_VIEW_MODE] = mode.name
        _state.update { it.copy(viewMode = mode) }
    }

    fun setCanvasViewport(viewport: ComparisonViewport) {
        _state.update { if (it.canvasViewport == viewport) it else it.copy(canvasViewport = viewport) }
    }

    fun setTimelineViewport(viewport: ComparisonViewport) {
        _state.update { if (it.timelineViewport == viewport) it else it.copy(timelineViewport = viewport) }
    }

    /**
     * 折叠 chip 点开时整棵子树一起展开，3000 条消息的长链不必一层层点；
     * 再次点击只收起这个节点，后代留在已展开集合里，重新展开时直接可见。
     */
    fun expand(id: String) {
        _state.update { state ->
            if (id in state.expanded) {
                state.copy(expanded = state.expanded - id)
            } else {
                val graph = state.graph ?: return@update state
                state.copy(expanded = expandedWithSubtree(graph, state.expanded, id))
            }
        }
    }

    /** 重新读一遍所有会话：会话可能在外部被改过（编辑、重新生成）。 */
    fun refresh() {
        loaded.clear()
        loadedSignatures.clear()
        rebuild()
    }

    /**
     * 从聊天页返回 / ON_RESUME 时调用：重读选中的会话，用分支签名（节点数 + 每个节点的 id/selectIndex
     * + 最后一条消息 id）判断是否被切了分支或编辑过。
     *
     * 不能只看 `updateAt`：`selectMessageNode` / `editMessage` 只走 `ConversationSession.update`，
     * 不改 `updateAt`，否则返回后图还是切换前的。签名没变就什么都不做，避免闪烁。
     */
    fun syncWithCandidates(candidates: List<ComparisonCandidate>) {
        if (candidates.isEmpty() || order.isEmpty()) return
        if (_state.value.loading || _state.value.cancelled) return
        if (buildJob?.isActive == true) return
        // 新一轮触发（候选列表重新发射 / 再次回到前台）以最新一次为准：上一次读到的可能还是
        // 聊天页保存前的数据，直接丢弃比跳过更安全
        syncJob?.cancel()
        val requested = order.toList()
        val byId = candidates.associateBy { it.id }
        val token = generation
        // 缓存由主线程写：先快照，后台只读快照，避免和同时进行的 rebuild 抢 HashMap
        val loadedSnapshot = HashMap(loaded)
        val signatureSnapshot = HashMap(loadedSignatures)
        syncJob = viewModelScope.launch {
            // 读整条会话要碰数据库，放 IO；签名比对是纯字符串
            val changed = withContext(Dispatchers.IO) {
                val reload = LinkedHashSet<Uuid>()
                for (id in requested) {
                    val cached = loadedSnapshot[id]
                    if (cached == null) {
                        reload += id
                        continue
                    }
                    // updateAt 变了就已经足够判定，省掉一次读库
                    val summary = byId[id]
                    if (summary != null && summary.updateAt != cached.updateAt) {
                        reload += id
                        continue
                    }
                    val fresh = try {
                        conversationRepository.getConversationById(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 读库失败不改判断，等用户主动刷新
                        continue
                    }
                    val conversation = when (val outcome = conversationLoad(fresh)) {
                        is ConversationLoad.Loaded -> outcome.conversation
                        else -> continue
                    }
                    if (conversation.deletedAt > 0) continue
                    val previous = signatureSnapshot[id] ?: branchSignature(cached)
                    if (branchSignature(conversation) != previous) {
                        reload += id
                    }
                }
                reload
            }
            if (token != generation) return@launch
            if (_state.value.cancelled) return@launch
            if (changed.isNotEmpty()) {
                rebuild(reload = changed)
            }
        }
    }

    /**
     * 用户在加载中点了取消：停掉 job，旧结果一律丢弃。
     *
     * 会话摘要和展开状态也要一起清掉——留着它们会让 [syncWithCandidates] 以为还能继续，
     * 一回到前台就自动重启构建。取消状态保持到主动刷新或选择变化（rebuild 会复位）。
     */
    fun cancelLoading() {
        buildJob?.cancel()
        syncJob?.cancel()
        generation += 1
        _state.update {
            it.copy(
                loading = false,
                hashing = false,
                graph = null,
                conversations = emptyList(),
                expanded = emptySet(),
                failed = false,
                cancelled = true,
            )
        }
    }

    /**
     * 跳转前确认会话还在。停留期间被删掉的会话从选择里移出并通知界面。
     *
     * @return 会话仍存在时为 true，可以安全跳转
     */
    suspend fun verifyConversation(id: Uuid): Boolean {
        // 只查删除标记，不把整条会话读回内存；读库异常时按存在处理，避免误删选择
        val deletedAt = try {
            conversationRepository.getDeletedAtById(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return true
        }
        if (deletedAt == 0L) return true
        // 已经在别处移出过就不再重复提示
        if (!order.remove(id)) return false
        forget(id)
        persistIds()
        rebuild()
        _events.trySend(ComparisonEvent.ConversationsMissing(1))
        return false
    }

    private fun rebuild(reload: Set<Uuid> = emptySet()) {
        buildJob?.cancel()
        reload.forEach { forget(it) }
        val token = ++generation
        val requested = order.toList()

        // 先把旧图清掉：重建期间的任何输入都作用于旧节点 id，留着只会点出错位的内容
        _state.update {
            it.copy(
                loading = true,
                hashing = false,
                graph = null,
                failed = false,
                cancelled = false,
                loadedCount = 0,
                totalCount = requested.size,
                missingCount = 0,
                noCommonStart = false,
                selectedIds = requested.toSet(),
            )
        }

        buildJob = viewModelScope.launch {
            val conversations = ArrayList<Conversation>(requested.size)
            val missing = ArrayList<Uuid>()
            var unavailable = false

            for ((index, id) in requested.withIndex()) {
                ensureActive()
                val cached = loaded[id]
                if (cached != null) {
                    conversations += cached
                } else {
                    val fetched = try {
                        conversationRepository.getConversationById(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 读库失败不等于会话不存在，保留选择并显示失败
                        unavailable = true
                        break
                    }
                    when (val outcome = conversationLoad(fetched)) {
                        is ConversationLoad.Loaded -> {
                            ensureActive()
                            if (outcome.shouldDrop()) {
                                missing += id
                            } else {
                                if (token != generation) return@launch
                                loaded[id] = outcome.conversation
                                loadedSignatures[id] = branchSignature(outcome.conversation)
                                conversations += outcome.conversation
                            }
                        }

                        ConversationLoad.Missing -> missing += id
                        ConversationLoad.Unavailable -> {
                            unavailable = true
                            break
                        }
                    }
                }
                if (token == generation) {
                    _state.update { it.copy(loadedCount = index + 1) }
                }
            }

            if (unavailable) {
                if (token == generation) {
                    _state.update {
                        it.copy(
                            loading = false,
                            hashing = false,
                            graph = null,
                            failed = true,
                            selectedIds = order.toSet(),
                        )
                    }
                }
                return@launch
            }

            // 取消可能发生在最后一次读库返回后、写 order 之前
            ensureActive()
            var removed = 0
            for (id in missing) {
                if (order.remove(id)) removed += 1
            }
            missing.forEach { forget(it) }
            if (removed > 0) persistIds()
            if (token != generation) return@launch

            val summaries = conversations.map { conversation ->
                ComparisonConversationSummary(
                    id = conversation.id,
                    title = conversation.title,
                    messageCount = conversation.messageNodes.size,
                )
            }
            _state.update {
                it.copy(
                    conversations = summaries,
                    missingCount = removed,
                    loadedCount = requested.size,
                )
            }
            if (removed > 0) {
                _events.trySend(ComparisonEvent.ConversationsMissing(removed))
            }

            // 全量遍历消息找本地附件放到后台，别在主线程卡住首帧
            val hashing = withContext(Dispatchers.Default) {
                conversations.any { conversation -> conversation.hasLocalFiles() }
            }
            ensureActive()
            if (token == generation) {
                _state.update { it.copy(hashing = hashing) }
            }

            // 建图要算哈希和指纹，放到后台；hash 自己再切 IO，避免主线程卡住
            val graph = try {
                withContext(Dispatchers.Default) {
                    buildComparisonGraph(conversations, fileHasher)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }

            ensureActive()
            if (token != generation) return@launch

            _state.update { state ->
                state.copy(
                    loading = false,
                    hashing = false,
                    graph = graph,
                    failed = graph == null,
                    conversations = summaries,
                    noCommonStart = graph != null && hasNoCommonStart(conversations, graph),
                    expanded = if (graph == null) {
                        emptySet()
                    } else {
                        state.expanded.filter { it in graph.nodes }.toSet()
                    },
                    loadedCount = requested.size,
                    totalCount = requested.size,
                    missingCount = removed,
                    selectedIds = order.toSet(),
                )
            }
        }
    }

    private fun forget(id: Uuid) {
        loaded.remove(id)
        loadedSignatures.remove(id)
    }

    private fun persistIds() {
        savedStateHandle[KEY_IDS] = ArrayList(order.map { it.toString() })
    }

    private fun restoreIds(fallback: List<String>): List<Uuid> {
        val saved = savedStateHandle.get<ArrayList<String>>(KEY_IDS)
        val source = saved?.takeIf { it.isNotEmpty() } ?: fallback
        return source
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            .distinct()
            .take(COMPARISON_MAX_CONVERSATIONS)
    }

    private fun restoreViewMode(): ComparisonViewMode {
        val saved = savedStateHandle.get<String>(KEY_VIEW_MODE)
        return ComparisonViewMode.entries.firstOrNull { it.name == saved }
            ?: ComparisonViewMode.INDENTED
    }

    private companion object {
        const val KEY_IDS = "comparison_ids"
        const val KEY_VIEW_MODE = "comparison_view_mode"
    }
}

private fun Conversation.hasLocalFiles(): Boolean = messageNodes.any { node ->
    node.messages.getOrNull(node.selectIndex)?.parts?.localFileUrls()?.isNotEmpty() == true
}
