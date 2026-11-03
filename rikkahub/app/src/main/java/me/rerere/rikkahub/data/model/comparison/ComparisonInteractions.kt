package me.rerere.rikkahub.data.model.comparison

import kotlin.uuid.Uuid

/** 少于两个会话的对比没有意义。 */
const val COMPARISON_MIN_CONVERSATIONS = 2

/** 超过这个节点数时提示用户缩进树视图仍会显示全部内容。 */
const val LARGE_COMPARISON_NODE_THRESHOLD = 2000

/** 多选开关：已选则取消，未选则加入。 */
fun toggleComparisonSelection(selected: Set<Uuid>, id: Uuid): Set<Uuid> =
    if (id in selected) selected - id else selected + id

/** 选中数量够不够开始对比。 */
fun canCompare(selected: Set<Uuid>): Boolean = selected.size >= COMPARISON_MIN_CONVERSATIONS

/** 图大到画布/时间线一次铺不开，但仍允许查看（缩进树不折叠）。 */
fun isLargeComparison(graph: ComparisonGraph): Boolean =
    graph.nodeCount > LARGE_COMPARISON_NODE_THRESHOLD

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
