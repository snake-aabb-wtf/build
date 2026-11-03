package me.rerere.rikkahub.data.model.comparison

/** 一个节点在画布上的位置：x 由深度决定，y 由布局算法决定。 */
data class LayoutPoint(val id: String, val x: Float, val y: Float)

/**
 * @property maxDepth 节点深度（0 起）的最大值，空图为 0
 * @property leafCount 叶子节点数
 */
data class ComparisonLayout(
    val points: Map<String, LayoutPoint>,
    val maxDepth: Int,
    val leafCount: Int,
)

/** 子树节点数（含自身）。 */
fun subtreeSizes(graph: ComparisonGraph): Map<String, Int> {
    val sizes = HashMap<String, Int>(graph.nodes.size)
    val stack = ArrayDeque<Pair<String, Boolean>>()
    graph.rootIds.asReversed().forEach { stack.addLast(it to false) }
    while (stack.isNotEmpty()) {
        val (id, visited) = stack.removeLast()
        val node = graph.nodes[id] ?: continue
        if (visited) {
            sizes[id] = 1 + node.childIds.sumOf { childId -> sizes[childId] ?: 0 }
        } else {
            stack.addLast(id to true)
            node.childIds.asReversed().forEach { childId -> stack.addLast(childId to false) }
        }
    }
    return sizes
}

/** tidy tree：叶子按 DFS 顺序逐行排布，内部节点居中于首末子节点之间。 */
fun tidyLayout(graph: ComparisonGraph, columnWidth: Float, rowHeight: Float): ComparisonLayout {
    return layout(graph, columnWidth, rowHeight) { childrenY ->
        (childrenY.first() + childrenY.last()) / 2f
    }
}

/** 横向时间线：叶子占 lane，内部节点取子节点 y 的中位数。 */
fun timelineLayout(graph: ComparisonGraph, columnWidth: Float, laneHeight: Float): ComparisonLayout {
    return layout(graph, columnWidth, laneHeight) { childrenY ->
        val sorted = childrenY.sorted()
        val middle = sorted.size / 2
        if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2f
        }
    }
}

/**
 * 显式栈的 DFS 后序遍历，避免上千层深的会话链把调用栈打爆。
 *
 * @param internalY 由子节点 y（按 childIds 顺序）算出内部节点 y
 */
private fun layout(
    graph: ComparisonGraph,
    columnWidth: Float,
    rowHeight: Float,
    internalY: (List<Float>) -> Float,
): ComparisonLayout {
    val points = LinkedHashMap<String, LayoutPoint>(graph.nodes.size)
    var leaves = 0
    var maxDepth = 0

    val stack = ArrayDeque<Frame>()
    graph.rootIds.asReversed().forEach { stack.addLast(Frame(it)) }

    while (stack.isNotEmpty()) {
        val frame = stack.last()
        val node = graph.nodes[frame.id]
        if (node == null) {
            stack.removeLast()
            continue
        }

        if (frame.childIndex < node.childIds.size) {
            val childId = node.childIds[frame.childIndex]
            frame.childIndex += 1
            stack.addLast(Frame(childId))
            continue
        }

        val y = if (node.childIds.isEmpty()) {
            val value = leaves * rowHeight
            leaves += 1
            value
        } else {
            internalY(node.childIds.map { childId -> points.getValue(childId).y })
        }

        if (node.depth > maxDepth) maxDepth = node.depth
        points[node.id] = LayoutPoint(id = node.id, x = node.depth * columnWidth, y = y)
        stack.removeLast()
    }

    return ComparisonLayout(points = points, maxDepth = maxDepth, leafCount = leaves)
}

private class Frame(val id: String) {
    var childIndex: Int = 0
}
