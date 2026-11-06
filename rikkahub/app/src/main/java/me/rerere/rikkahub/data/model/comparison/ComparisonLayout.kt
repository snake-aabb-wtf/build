package me.rerere.rikkahub.data.model.comparison

/** 一个节点在画布上的左上角位置（dp）；文字保持正向，深度沿所选方向展开。 */
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

/**
 * tidy tree：叶子按 DFS 顺序排布，内部节点居中于首末子节点之间。
 *
 * [columnWidth] 和 [rowHeight] 始终表示物理横向、纵向间距。竖版的叶子沿 x 排列，
 * 层级沿 y 展开，避免交换横版坐标后卡片宽度大于左右间距。显式栈保证长链不会打爆调用栈。
 */
fun tidyLayout(
    graph: ComparisonGraph,
    columnWidth: Float,
    rowHeight: Float,
    orientation: ComparisonOrientation = ComparisonOrientation.VERTICAL,
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

        val crossAxis = if (node.childIds.isEmpty()) {
            val value = leaves * when (orientation) {
                ComparisonOrientation.HORIZONTAL -> rowHeight
                ComparisonOrientation.VERTICAL -> columnWidth
            }
            leaves += 1
            value
        } else {
            fun childPosition(id: String): Float = points.getValue(id).let {
                if (orientation == ComparisonOrientation.HORIZONTAL) it.y else it.x
            }
            (childPosition(node.childIds.first()) + childPosition(node.childIds.last())) / 2f
        }

        if (node.depth > maxDepth) maxDepth = node.depth
        points[node.id] = when (orientation) {
            ComparisonOrientation.HORIZONTAL -> LayoutPoint(node.id, node.depth * columnWidth, crossAxis)
            ComparisonOrientation.VERTICAL -> LayoutPoint(node.id, crossAxis, node.depth * rowHeight)
        }
        stack.removeLast()
    }

    return ComparisonLayout(points = points, maxDepth = maxDepth, leafCount = leaves)
}

private class Frame(val id: String) {
    var childIndex: Int = 0
}
