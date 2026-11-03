package me.rerere.rikkahub.ui.pages.comparison

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.comparison.ComparisonGraph
import me.rerere.rikkahub.data.model.comparison.FileHasher
import me.rerere.rikkahub.data.model.comparison.buildComparisonGraph
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

data class ComparisonUiState(
    val loading: Boolean = true,
    val hashing: Boolean = false,
    val graph: ComparisonGraph? = null,
    val conversations: List<ComparisonConversationSummary> = emptyList(),
    val viewMode: ComparisonViewMode = ComparisonViewMode.INDENTED,
    val expanded: Set<String> = emptySet(),
    /** 加载时已经不存在、被移出对比的会话数 */
    val missingCount: Int = 0,
    val failed: Boolean = false,
)

/**
 * 只读地加载选中的会话并建图。
 *
 * 会话读完一次就缓存在 [loaded] 里，增删会话只重建图，不再读数据库；附件哈希交给 [ComparisonFileHasher]
 * 按 (路径, 大小, 修改时间) 缓存。视图模式放在 VM 里，旋转屏幕后不会丢。
 */
class ComparisonVM(
    ids: List<String>,
    private val conversationRepository: ConversationRepository,
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

    fun expand(id: String) {
        _state.update {
            it.copy(expanded = if (id in it.expanded) it.expanded - id else it.expanded + id)
        }
    }

    /** 重新读一遍所有会话：会话可能在外部被改过（编辑、重新生成）。 */
    fun refresh() {
        loaded.clear()
        rebuild()
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
