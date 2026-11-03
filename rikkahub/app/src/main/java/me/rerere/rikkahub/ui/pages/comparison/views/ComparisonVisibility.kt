package me.rerere.rikkahub.ui.pages.comparison.views

import me.rerere.rikkahub.data.model.comparison.ComparisonGraph
import me.rerere.rikkahub.data.model.comparison.LayoutPoint
import me.rerere.rikkahub.ui.pages.comparison.ComparisonViewport
import kotlin.math.max
import kotlin.math.min

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

/** 一帧最多组合的卡片数：0.2× 下宽浅树整屏能相交上百个节点，没上限会卡在组合上。 */
internal const val MAX_DRAWN_NODES = 80

/** 裁剪后的可见节点，以及因上限没画出来的数量（边缘提示用）。 */
internal data class VisibleNodes(
    val ids: Set<String>,
    val hiddenCount: Int,
)

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
 * 与视口相交的节点，超过 [limit] 时按 (y, x) 取最靠上的前 [limit] 个。
 *
 * 排序只在超限时做，半边接触的非法值已经被 [intersectsViewport] 排掉了。
 */
internal fun visibleInViewportCapped(
    points: Map<String, LayoutPoint>,
    candidates: Set<String>,
    world: WorldRect,
    cardWidthDp: Float,
    cardHeightDp: Float,
    limit: Int = MAX_DRAWN_NODES,
): VisibleNodes {
    val hits = ArrayList<LayoutPoint>(minOf(candidates.size, 256))
    for (id in candidates) {
        val point = points[id] ?: continue
        if (intersectsViewport(point, world, cardWidthDp, cardHeightDp)) hits += point
    }
    if (hits.size <= limit) {
        return VisibleNodes(hits.mapTo(HashSet(hits.size)) { it.id }, 0)
    }
    val kept = hits.sortedWith(compareBy({ it.y }, { it.x })).take(limit)
    return VisibleNodes(kept.mapTo(HashSet(limit)) { it.id }, hits.size - kept.size)
}

/** 可见节点的世界包围盒（dp），包含卡片自身尺寸；空集合返回 null。 */
internal fun contentBounds(
    points: Map<String, LayoutPoint>,
    ids: Set<String>,
    cardWidthDp: Float,
    cardHeightDp: Float,
): WorldRect? {
    var left = Float.MAX_VALUE
    var top = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    var bottom = -Float.MAX_VALUE
    var found = false
    for (id in ids) {
        val point = points[id] ?: continue
        found = true
        left = min(left, point.x)
        top = min(top, point.y)
        right = max(right, point.x + cardWidthDp)
        bottom = max(bottom, point.y + cardHeightDp)
    }
    return if (found) WorldRect(left, top, right, bottom) else null
}

/**
 * 平移轴的限位，带内容起点偏移：内容占 `[start + pan, end + pan]`。
 *
 * 内容小于视口时居中；否则允许半屏的过滚，但内容边缘不能离开视口中心太远。
 */
internal fun clampPan(value: Float, startPx: Float, endPx: Float, viewportPx: Float): Float {
    val contentPx = endPx - startPx
    if (contentPx <= viewportPx) return (viewportPx - contentPx) / 2f - startPx
    val slack = viewportPx * 0.5f
    return value.coerceIn(viewportPx - slack - endPx, slack - startPx)
}

/** 按内容包围盒计算首次进入的视口：完整容纳且不放大超过 [maxScale]，居中对齐。 */
internal fun fitViewport(
    contentWidthPx: Float,
    contentHeightPx: Float,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    bounds: WorldRect? = null,
    minScale: Float = MinCanvasScale,
    maxScale: Float = 1f,
): ComparisonViewport {
    if (contentWidthPx <= 0f || contentHeightPx <= 0f || viewportWidthPx <= 0f || viewportHeightPx <= 0f) {
        return ComparisonViewport()
    }
    val scale = min(maxScale, min(viewportWidthPx / contentWidthPx, viewportHeightPx / contentHeightPx))
        .coerceAtLeast(minScale)
    val left = bounds?.left ?: 0f
    val top = bounds?.top ?: 0f
    val visibleWidth = contentWidthPx * scale
    val visibleHeight = contentHeightPx * scale
    val panX = if (visibleWidth <= viewportWidthPx) {
        (viewportWidthPx - visibleWidth) / 2f - left * scale
    } else {
        0f
    }
    val panY = if (visibleHeight <= viewportHeightPx) {
        (viewportHeightPx - visibleHeight) / 2f - top * scale
    } else {
        0f
    }
    return ComparisonViewport(scale = scale, panX = panX, panY = panY)
}

/** 单轴 fit（时间线不平滑缩放）：内容小于视口时居中，否则从起点开始。 */
internal fun fitPan(contentPx: Float, viewportPx: Float, startPx: Float = 0f): Float =
    if (contentPx <= viewportPx) (viewportPx - contentPx) / 2f - startPx else -startPx

/**
 * 平移轴的限位：内容大于视口时允许半屏的过滚，内容小于视口时居中。
 *
 * pan 是内容平移量：内容占 `[pan, pan + contentPx]`。
 */
internal fun clampPanAxis(value: Float, contentPx: Float, viewportPx: Float): Float =
    clampPan(value = value, startPx = 0f, endPx = contentPx, viewportPx = viewportPx)

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
