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
import me.rerere.rikkahub.data.model.comparison.ComparisonCandidate
import me.rerere.rikkahub.data.model.comparison.ComparisonGraph
import me.rerere.rikkahub.data.model.comparison.ConversationLoad
import me.rerere.rikkahub.data.model.comparison.FileHasher
import me.rerere.rikkahub.data.model.comparison.branchSignature
import me.rerere.rikkahub.data.model.comparison.buildComparisonGraph
import me.rerere.rikkahub.data.model.comparison.conversationLoad
import me.rerere.rikkahub.data.model.comparison.expandedWithSubtree
import me.rerere.rikkahub.data.model.comparison.hasNoCommonStart
import me.rerere.rikkahub.data.model.comparison.restoreComparisonIds
import me.rerere.rikkahub.ui.pages.comparison.views.LINEAR_SEGMENT_PREFIX
import me.rerere.rikkahub.ui.pages.comparison.views.linearSegmentKey
import me.rerere.rikkahub.data.model.comparison.shouldDrop
import me.rerere.rikkahub.data.model.localFileUrls
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.uuid.Uuid

data class ComparisonConversationSummary(
    val id: Uuid,
    val title: String,
    val messageCount: Int,
)

/** 画布的视图状态：放在 VM 里，保留缩放和平移位置。 */
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
    val expanded: Set<String> = emptySet(),
    /** 画布视图的缩放/平移；pan 单位是像素。 */
    val canvasViewport: ComparisonViewport = ComparisonViewport(),
    /** 画布是否已经做过首次 fit；重建图后复位。 */
    val canvasFitted: Boolean = false,
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

    /** 要跳转的消息节点在会话里已经不存在。 */
    data object MessageMissing : ComparisonEvent
}

/**
 * 只读地加载选中的会话并建图。
 *
 * 会话读完一次就缓存在 [loaded] 里，增删会话只重建图，不再读数据库；附件哈希交给 [ComparisonFileHasher]
 * 按 (路径, 大小, 修改时间) 缓存。消息指纹每次重算：context checkpoint 会原地改写内容而不换 id，
 * 以 (会话, 消息 id) 为键的消息级缓存必然拿到过期指纹。
 * 会话 id、画布视口和展开状态放进 [SavedStateHandle]。
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
        ComparisonUiState(
            canvasViewport = restoreViewport(KEY_CANVAS_SCALE, KEY_CANVAS_PAN_X, KEY_CANVAS_PAN_Y),
            expanded = restoreExpanded(),
            canvasFitted = savedStateHandle.get<Boolean>(KEY_CANVAS_FITTED) ?: false,
        )
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
        if (id in order) return
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

    fun setCanvasViewport(viewport: ComparisonViewport) {
        if (_state.value.canvasViewport == viewport) return
        savedStateHandle[KEY_CANVAS_SCALE] = viewport.scale
        savedStateHandle[KEY_CANVAS_PAN_X] = viewport.panX
        savedStateHandle[KEY_CANVAS_PAN_Y] = viewport.panY
        _state.update { it.copy(canvasViewport = viewport) }
    }

    /** 画布首次 fit 完成；页面返回时保留用户的缩放/平移。 */
    fun markCanvasFitted() {
        if (_state.value.canvasFitted) return
        savedStateHandle[KEY_CANVAS_FITTED] = true
        _state.update { it.copy(canvasFitted = true) }
    }

    /**
     * 折叠 chip 点开时整棵子树一起展开，3000 条消息的长链不必一层层点；
     * 再次点击只收起这个节点，后代留在已展开集合里，重新展开时直接可见。
     */
    fun expand(id: String) {
        val graph = _state.value.graph ?: return
        _state.update { state ->
            if (id in state.expanded) {
                state.copy(expanded = state.expanded - id)
            } else {
                state.copy(expanded = expandedWithSubtree(graph, state.expanded, id))
            }
        }
        persistExpanded(_state.value.expanded)
    }

    /**
     * 展开线性长链被压缩的中段：只恢复这一段，不连带展开后续分叉。
     */
    fun expandSegment(id: String) {
        val key = linearSegmentKey(id)
        if (key in _state.value.expanded) return
        _state.update { it.copy(expanded = it.expanded + key) }
        persistExpanded(_state.value.expanded)
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
                    // 归档与删除同待遇：交给 rebuild 移出并提示
                    if (conversation.deletedAt > 0 || conversation.archivedAt > 0) {
                        reload += id
                        continue
                    }
                    val previous = signatureSnapshot[id] ?: branchSignature(cached)
                    // 改名只写 title 列、不动 updateAt，签名也不含标题，所以要单独比一次
                    if (branchSignature(conversation) != previous || conversation.title != cached.title) {
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
     * 展开状态一起清掉；会话摘要保留，这样顶部 chip 还在，用户可以逐个移除或点刷新重来。
     * 自动重启由 `cancelled` 挡住（[syncWithCandidates] 直接返回），不需要清空摘要来防。
     * 取消状态保持到主动刷新或选择变化（rebuild 会复位）。
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
                expanded = emptySet(),
                failed = false,
                cancelled = true,
            )
        }
    }

    /**
     * 跳转前确认会话和消息节点都还在。
     *
     * 会话已删/已归档时从选择里移出并提示；节点在聊天页被删掉时 `index = -1`，不能静默不滚，
     * 所以单独提示「消息已不存在」并且不导航。
     *
     * @return 可以安全跳转时为 true
     */
    suspend fun verifyOccurrence(conversationId: Uuid, messageNodeId: Uuid): Boolean {
        // 只在点击时读一次整条会话；读库异常按存在处理，避免误删选择
        val conversation = try {
            conversationRepository.getConversationById(conversationId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return true
        }
        if (conversation == null || conversation.deletedAt > 0 || conversation.archivedAt > 0) {
            // 已经在别处移出过就不再重复提示
            if (order.remove(conversationId)) {
                forget(conversationId)
                persistIds()
                rebuild()
                _events.trySend(ComparisonEvent.ConversationsMissing(1))
            }
            return false
        }
        if (conversation.messageNodes.none { it.id == messageNodeId }) {
            _events.trySend(ComparisonEvent.MessageMissing)
            return false
        }
        return true
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
                canvasFitted = false,
            )
        }
        savedStateHandle[KEY_CANVAS_FITTED] = false

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
                        // 线性中段标记不指向节点，不能按节点 id 过滤掉
                        state.expanded.filter {
                            it in graph.nodes || it.startsWith(LINEAR_SEGMENT_PREFIX)
                        }.toSet()
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

    /**
     * 恢复选择：区分「没存过」和「存的是空列表」。
     *
     * 删除到 0 个后重建页面必须保持空态；用 `takeIf { isNotEmpty() }` 会退回路由里的原始 ids，
     * 把用户删掉的会话又「复活」。
     */
    private fun restoreIds(fallback: List<String>): List<Uuid> =
        restoreComparisonIds(savedStateHandle.get<ArrayList<String>>(KEY_IDS), fallback)

    private fun restoreViewport(scaleKey: String, panXKey: String, panYKey: String): ComparisonViewport =
        ComparisonViewport(
            scale = savedStateHandle.get<Float>(scaleKey) ?: 1f,
            panX = savedStateHandle.get<Float>(panXKey) ?: 0f,
            panY = savedStateHandle.get<Float>(panYKey) ?: 0f,
        )

    private fun persistExpanded(expanded: Set<String>) {
        savedStateHandle[KEY_EXPANDED] = ArrayList(expanded)
    }

    private fun restoreExpanded(): Set<String> =
        savedStateHandle.get<ArrayList<String>>(KEY_EXPANDED)?.toSet() ?: emptySet()

    private companion object {
        const val KEY_IDS = "comparison_ids"
        const val KEY_EXPANDED = "comparison_expanded"
        const val KEY_CANVAS_SCALE = "comparison_canvas_scale"
        const val KEY_CANVAS_PAN_X = "comparison_canvas_pan_x"
        const val KEY_CANVAS_PAN_Y = "comparison_canvas_pan_y"
        const val KEY_CANVAS_FITTED = "comparison_canvas_fitted"
    }
}

private fun Conversation.hasLocalFiles(): Boolean = messageNodes.any { node ->
    node.messages.getOrNull(node.selectIndex)?.parts?.localFileUrls()?.isNotEmpty() == true
}
