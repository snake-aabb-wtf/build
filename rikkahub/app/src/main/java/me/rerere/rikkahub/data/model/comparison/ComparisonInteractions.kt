package me.rerere.rikkahub.data.model.comparison

import me.rerere.rikkahub.data.model.Conversation
import java.time.Instant
import kotlin.uuid.Uuid

/** 少于两个会话的对比没有意义。 */
const val COMPARISON_MIN_CONVERSATIONS = 2

/** 一次对比最多容纳的会话数，再多屏幕和选择器都排不下。 */
const val COMPARISON_MAX_CONVERSATIONS = 8

/** 超过这个节点数时提示用户缩进树视图仍会显示全部内容。 */
const val LARGE_COMPARISON_NODE_THRESHOLD = 2000

/** 选择器里的一条候选会话；[updateAt] 用来判断从聊天页返回后是否需要重建。 */
data class ComparisonCandidate(
    val id: Uuid,
    val title: String,
    val updateAt: Instant,
)

/** 多选开关：已选则取消，未选则加入；到上限后不再加入。 */
fun toggleComparisonSelection(
    selected: Set<Uuid>,
    id: Uuid,
    limit: Int = COMPARISON_MAX_CONVERSATIONS,
): Set<Uuid> = when {
    id in selected -> selected - id
    selected.size >= limit -> selected
    else -> selected + id
}

/**
 * 从保存状态恢复选择：区分「没存过」和「存的是空列表」。
 *
 * `saved == null` 才退回路由参数；空列表是用户删到 0 个的有效状态，必须保持空，
 * 否则删干净后重建页面会把路由里的原始会话又「复活」。
 */
fun restoreComparisonIds(
    saved: List<String>?,
    fallback: List<String>,
    limit: Int = COMPARISON_MAX_CONVERSATIONS,
): List<Uuid> {
    val source = saved ?: fallback
    return source
        .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
        .distinct()
        .take(limit)
}

/** 选中数量够不够开始对比。 */
fun canCompare(selected: Set<Uuid>): Boolean = selected.size >= COMPARISON_MIN_CONVERSATIONS

/** 还能不能再加一个会话（已在选择里、或已到上限时都不行）。 */
fun canAddConversation(
    selected: Set<Uuid>,
    id: Uuid,
    limit: Int = COMPARISON_MAX_CONVERSATIONS,
): Boolean = id !in selected && selected.size < limit

/**
 * 选择器实际列出的会话：只列还没进对比的全部候选。
 *
 * 已在对比里的会话在上方 chip 里显示，不在弹层里重复出现。上限只在「加入时」由
 * [canAddConversation] 判定：若在这里按剩余名额截断，已选 7 个时就只剩 1 条候选可选。
 */
fun availableCandidates(
    candidates: List<ComparisonCandidate>,
    selected: Set<Uuid>,
): List<ComparisonCandidate> =
    candidates
        .asSequence()
        .filter { it.id !in selected }
        .distinctBy { it.id }
        .toList()

/** 图大到画布/时间线一次铺不开，但仍允许查看（缩进树不折叠）。 */
fun isLargeComparison(graph: ComparisonGraph): Boolean =
    graph.nodeCount > LARGE_COMPARISON_NODE_THRESHOLD

/**
 * 读会话的结果分类。
 *
 * 只有「查询成功但返回 null」才是 [ConversationLoad.Missing]；读库抛异常是
 * [ConversationLoad.Unavailable]，不能把选择当成不存在删掉。
 */
sealed interface ConversationLoad {
    data class Loaded(val conversation: Conversation) : ConversationLoad
    data object Missing : ConversationLoad
    data object Unavailable : ConversationLoad
}

fun conversationLoad(conversation: Conversation?): ConversationLoad =
    if (conversation == null) ConversationLoad.Missing else ConversationLoad.Loaded(conversation)

/** 需要从对比里移出：不存在、已在回收站或已归档；读库失败要保留选择。 */
fun ConversationLoad.shouldDrop(): Boolean = when (this) {
    ConversationLoad.Missing -> true
    ConversationLoad.Unavailable -> false
    is ConversationLoad.Loaded -> conversation.deletedAt > 0 || conversation.archivedAt > 0
}

/**
 * 多条会话是否完全没有共同开头。
 *
 * 只看非空会话（有可选消息节点的）的首个节点：少于两条、首节点重合、或至少两条共用同一个根时都不提示；
 * 空会话不参与分母，避免「2 条无关会话 + 1 条空会话」被误判成没有问题。
 */
fun hasNoCommonStart(
    conversations: List<Conversation>,
    graph: ComparisonGraph,
): Boolean {
    val starts = conversations.mapNotNull { conversation ->
        conversation.messageNodes
            .firstOrNull { node -> node.messages.getOrNull(node.selectIndex) != null }
            ?.id
    }
    if (starts.size < COMPARISON_MIN_CONVERSATIONS) return false
    // 同一个 messageNode 被重复传进来时不算分叉
    if (starts.toSet().size != starts.size) return false
    return graph.rootIds.size == starts.size
}

/**
 * 会话当前选中的分支签名：节点数 + 每个节点的 (id, selectIndex) + 最后一条消息 id。
 *
 * 切换分支、编辑消息都只改这些字段而不动 `updateAt`，所以从聊天页返回时不能只看时间戳。
 */
fun branchSignature(conversation: Conversation): String {
    val builder = StringBuilder(conversation.messageNodes.size * 24)
    builder.append(conversation.messageNodes.size).append('\u0000')
    conversation.messageNodes.forEach { node ->
        builder.append(node.id).append(':').append(node.selectIndex).append('\u0000')
    }
    val last = conversation.messageNodes.lastOrNull()
    builder.append(last?.messages?.getOrNull(last.selectIndex)?.id ?: "-")
    return builder.toString().sha256Hex()
}

/**
 * 展开一个折叠节点时把整棵子树一起标记为已展开。
 *
 * 画布/时间线按「子树节点数 > 阈值」逐层折叠，长会话链需要一层层点开；整棵展开后 3000 条消息的
 * 单链也能一次看全。
 */
fun expandedWithSubtree(
    graph: ComparisonGraph,
    expanded: Set<String>,
    id: String,
): Set<String> {
    if (!graph.nodes.containsKey(id)) return expanded
    val result = expanded.toMutableSet()
    val visited = HashSet<String>()
    val stack = ArrayDeque<String>()
    stack.addLast(id)
    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        if (!visited.add(current)) continue
        result += current
        graph.nodes[current]?.childIds?.forEach { childId -> stack.addLast(childId) }
    }
    return result
}

/**
 * 多来源节点在选择器里每个会话只列一条。
 *
 * 建图时同一会话对同一节点最多贡献一条 occurrence，这里仍去重，避免将来图结构变化后出现重复标题。
 */
fun sourceOccurrences(node: ComparisonNode): List<NodeOccurrence> =
    node.occurrences.distinctBy { it.conversationId }
