package me.rerere.rikkahub.ui.pages.comparison

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
import me.rerere.rikkahub.data.model.comparison.ComparisonGraph
import me.rerere.rikkahub.data.model.comparison.FileHasher
import me.rerere.rikkahub.data.model.comparison.buildComparisonGraph
import me.rerere.rikkahub.data.model.comparison.expandedWithSubtree
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

/** 选择器里的一条候选会话：列表查询不带消息体，只需要标题。 */
data class ComparisonCandidate(
    val id: Uuid,
    val title: String,
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
    val viewMode: ComparisonViewMode = ComparisonViewMode.INDENTED,
    val expanded: Set<String> = emptySet(),
    /** 画布视图的缩放/平移；pan 单位是像素。 */
    val canvasViewport: ComparisonViewport = ComparisonViewport(),
    /** 时间线视图的平移（scale 恒为 1）。 */
    val timelineViewport: ComparisonViewport = ComparisonViewport(),
    /** 加载时已经不存在、被移出对比的会话数 */
    val missingCount: Int = 0,
    val failed: Boolean = false,
)

sealed interface ComparisonEvent {
    /** 对比页停留期间有会话被删除，已经从选择里移出。 */
    data class ConversationsMissing(val count: Int) : ComparisonEvent
}

/**
 * 只读地加载选中的会话并建图。
 *
 * 会话读完一次就缓存在 [loaded] 里，增删会话只重建图，不再读数据库；附件哈希交给 [ComparisonFileHasher]
 * 按 (路径, 大小, 修改时间) 缓存。视图模式放在 VM 里，旋转屏幕后不会丢。
 */
class ComparisonVM(
    ids: List<String>,
    private val conversationRepository: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val fileHasher: FileHasher = ComparisonFileHasher(),
) : ViewModel() {
    private val order: MutableList<Uuid> = ids
        .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
        .distinct()
        .toMutableList()
    private val loaded = HashMap<Uuid, Conversation>()

    private var buildJob: Job? = null

    private val _state = MutableStateFlow(ComparisonUiState())
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
            conversations.map { conversation ->
                ComparisonCandidate(id = conversation.id, title = conversation.title)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        rebuild()
    }

    fun addConversation(id: Uuid) {
        if (id in order) return
        order += id
        rebuild()
    }

    fun removeConversation(id: Uuid) {
        if (!order.remove(id)) return
        loaded.remove(id)
        rebuild()
    }

    fun setViewMode(mode: ComparisonViewMode) {
        _state.update { if (it.viewMode == mode) it else it.copy(viewMode = mode) }
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
        rebuild()
    }

    /**
     * 跳转前确认会话还在。停留期间被删掉的会话从选择里移出并通知界面。
     *
     * @return 会话仍存在时为 true，可以安全跳转
     */
    suspend fun verifyConversation(id: Uuid): Boolean {
        // 只查删除标记，不把整条会话读回内存；读库异常时按存在处理，避免误删选择
        val deletedAt = runCatching { conversationRepository.getDeletedAtById(id) }
            .getOrElse { return true }
        if (deletedAt == 0L) return true
        // 已经在别处移出过就不再重复提示
        if (!order.remove(id)) return false
        loaded.remove(id)
        rebuild()
        _events.trySend(ComparisonEvent.ConversationsMissing(1))
        return false
    }

    private fun rebuild() {
        buildJob?.cancel()
        buildJob = viewModelScope.launch {
            val requested = order.toList()
            val conversations = ArrayList<Conversation>(requested.size)
            val missing = ArrayList<Uuid>()

            for (id in requested) {
                val cached = loaded[id]
                if (cached != null) {
                    conversations += cached
                    continue
                }
                val conversation = runCatching { conversationRepository.getConversationById(id) }.getOrNull()
                if (conversation == null) {
                    missing += id
                } else {
                    loaded[id] = conversation
                    conversations += conversation
                }
            }
            missing.forEach { order.remove(it) }

            _state.update {
                it.copy(
                    loading = true,
                    hashing = conversations.any { conversation -> conversation.hasLocalFiles() },
                    missingCount = missing.size,
                    failed = false,
                )
            }
            if (missing.isNotEmpty()) {
                _events.trySend(ComparisonEvent.ConversationsMissing(missing.size))
            }

            // 建图要算哈希和指纹，放到后台；hash 自己再切 IO，避免主线程卡住
            val graph = withContext(Dispatchers.Default) {
                runCatching { buildComparisonGraph(conversations, fileHasher) }.getOrNull()
            }

            _state.update {
                it.copy(
                    loading = false,
                    hashing = false,
                    graph = graph,
                    conversations = conversations.map { conversation ->
                        ComparisonConversationSummary(
                            id = conversation.id,
                            title = conversation.title,
                            messageCount = conversation.messageNodes.size,
                        )
                    },
                    failed = graph == null,
                )
            }
        }
    }
}

private fun Conversation.hasLocalFiles(): Boolean = messageNodes.any { node ->
    node.messages.getOrNull(node.selectIndex)?.parts?.localFileUrls()?.isNotEmpty() == true
}
