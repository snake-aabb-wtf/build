package me.rerere.rikkahub.ui.pages.comparison.views

import me.rerere.rikkahub.data.model.comparison.ComparisonGraph
import me.rerere.rikkahub.data.model.comparison.LayoutPoint

/**
 * 折叠阈值：子树大于这个节点数、且本身是分叉点的节点，在画布/时间线里先收成 `+N` 占位。
 *
 * 线性链永不折叠：1000 条消息的单链没有可省的视觉分支，折叠只会把整棵树藏起来。
 */
internal const val FOLD_SUBTREE_THRESHOLD = 300

/** 只折叠「分叉点（有多个子节点）且子树超过阈值、尚未展开」的节点。 */
internal fun foldedNodeIds(
    graph: ComparisonGraph,
    sizes: Map<String, Int>,
    expanded: Set<String>,
): Set<String> = sizes
    .filter { (id, size) ->
        size > FOLD_SUBTREE_THRESHOLD &&
            id !in expanded &&
            (graph.nodes[id]?.childIds?.size ?: 0) > 1
    }
    .keys

/** 折叠 chip 上显示的隐藏数量：子树节点数减去自己。 */
internal fun foldHiddenCount(sizes: Map<String, Int>, id: String): Int =
    ((sizes[id] ?: 1) - 1).coerceAtLeast(0)

/**
 * 折叠后仍然可见的节点：折叠节点自身照常绘制，它的后代不画。
 *
 * 折叠的只是「子树」，不是这个节点本身——之前的实现把节点一起藏了，首屏只剩一个 `+999`。
 */
internal fun visibleNodeIds(graph: ComparisonGraph, folded: Set<String>): Set<String> {
    val visible = HashSet<String>(graph.nodes.size)
    val stack = ArrayDeque<String>()
    graph.rootIds.forEach { stack.addLast(it) }
    while (stack.isNotEmpty()) {
        val id = stack.removeLast()
        if (!visible.add(id)) continue
        if (id in folded) continue
        graph.nodes[id]?.childIds?.forEach { stack.addLast(it) }
    }
    return visible
}

/** 世界坐标（dp）里的一块矩形，视图裁剪用。 */
internal data class WorldRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * 屏幕像素坐标反推世界坐标（dp）：`screen = world * density * scale + pan`（pan 是内容平移）。
 *
 * [marginDp] 外扩一个卡片尺寸，保证滑到边缘时卡片不会突然出现/消失。
 */
internal fun visibleWorldRect(
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    density: Float,
    scale: Float,
    panX: Float,
    panY: Float,
    marginDp: Float,
): WorldRect {
    val unit = (density * scale).coerceAtLeast(0.0001f)
    return WorldRect(
        left = -panX / unit - marginDp,
        top = -panY / unit - marginDp,
        right = (viewportWidthPx - panX) / unit + marginDp,
        bottom = (viewportHeightPx - panY) / unit + marginDp,
    )
}

/**
 * 与视口相交的节点。
 *
 * @param candidates 已经过折叠过滤的节点 id（[visibleNodeIds]）
 * @param world 世界坐标下的视口矩形（[visibleWorldRect]）
 */
internal fun visibleInViewport(
    points: Map<String, LayoutPoint>,
    candidates: Set<String>,
    world: WorldRect,
    cardWidthDp: Float,
    cardHeightDp: Float,
): Set<String> {
    val result = HashSet<String>(minOf(candidates.size, 256))
    for (id in candidates) {
        val point = points[id] ?: continue
        if (point.x + cardWidthDp < world.left || point.x > world.right) continue
        if (point.y + cardHeightDp < world.top || point.y > world.bottom) continue
        result += id
    }
    return result
}

/** 单个节点是否与视口相交（折叠 chip 也用它）。 */
internal fun intersectsViewport(
    point: LayoutPoint?,
    world: WorldRect,
    cardWidthDp: Float,
    cardHeightDp: Float,
): Boolean {
    if (point == null) return false
    if (point.x + cardWidthDp < world.left || point.x > world.right) return false
    if (point.y + cardHeightDp < world.top || point.y > world.bottom) return false
    return true
}

/**
 * 平移轴的限位：内容大于视口时允许半屏的过滚，内容小于视口时居中。
 *
 * pan 是内容平移量：内容占 `[pan, pan + contentPx]`。
 */
internal fun clampPanAxis(value: Float, contentPx: Float, viewportPx: Float): Float {
    if (contentPx <= viewportPx) return (viewportPx - contentPx) / 2f
    val slack = viewportPx * 0.5f
    return value.coerceIn(viewportPx - contentPx - slack, slack)
}

/** 时间线泳道的短标题：该 lane 的叶子节点所属会话。 */
internal fun laneTitles(
    graph: ComparisonGraph,
    points: Map<String, LayoutPoint>,
    laneHeight: Float,
    laneCount: Int,
): List<String> {
    if (laneCount <= 0 || laneHeight <= 0f) return emptyList()
    val titles = MutableList(laneCount) { "" }
    graph.nodes.values.forEach { node ->
        if (node.childIds.isNotEmpty()) return@forEach
        val point = points[node.id] ?: return@forEach
        val lane = (point.y / laneHeight).toInt()
        if (lane !in titles.indices || titles[lane].isNotEmpty()) return@forEach
        titles[lane] = node.occurrences.firstOrNull()?.conversationTitle.orEmpty()
    }
    return titles
}
