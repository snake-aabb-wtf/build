package me.rerere.rikkahub.ui.pages.comparison.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.comparison.ComparisonGraph
import me.rerere.rikkahub.data.model.comparison.ComparisonLayout
import me.rerere.rikkahub.data.model.comparison.ComparisonNode
import me.rerere.rikkahub.data.model.comparison.LayoutPoint
import me.rerere.rikkahub.ui.pages.comparison.ComparisonViewport
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 视图 C：泳道时间线。左侧固定泳道标题列，右侧横竖平移；不平滑缩放（scale 恒为 1）。
 *
 * 与画布的区别是真正的正交折线、泳道区间整块铺底色、lane 标题固定可读；内容尺寸同样不进
 * `Modifier.size()`，平移用 offset + 裁剪，几千节点也不会撞 Constraints。
 */
@Composable
fun TimelineView(
    graph: ComparisonGraph,
    layout: ComparisonLayout,
    sizes: Map<String, Int>,
    endpointIds: Set<String>,
    expanded: Set<String>,
    viewport: ComparisonViewport,
    onViewportChange: (ComparisonViewport) -> Unit,
    onExpand: (String) -> Unit,
    onNodeClick: (ComparisonNode) -> Unit,
    metrics: ComparisonMetrics,
    modifier: Modifier = Modifier,
) {
    val points = layout.points
    if (points.isEmpty()) return

    val folded = remember(graph, sizes, expanded) { foldedNodeIds(graph, sizes, expanded) }
    val visible = remember(graph, folded) { visibleNodeIds(graph, folded) }
    val colors = MaterialTheme.colorScheme
    val laneColors = remember(colors) {
        listOf(
            colors.primary,
            colors.secondary,
            colors.tertiary,
            colors.primaryContainer,
            colors.secondaryContainer,
            colors.tertiaryContainer,
        )
    }
    val edgeColor = colors.outlineVariant
    val density = LocalDensity.current
    val latestViewport by rememberUpdatedState(viewport)
    val latestOnChange by rememberUpdatedState(onViewportChange)
    val cardWidth = metrics.timelineCardWidth
    val cardHeight = metrics.cardHeight
    val laneHeight = metrics.timelineLaneHeight
    val laneHeightPx = with(density) { laneHeight.toPx() }
    val laneCount = max(layout.leafCount, 1)
    val titles = remember(graph, points, laneHeight, laneCount) {
        laneTitles(graph, points, laneHeight.value, laneCount)
    }
    val maxX = remember(points) { points.values.maxOf { it.x } }
    val maxY = remember(points) { points.values.maxOf { it.y } }
    val contentWidthDp = maxX + cardWidth.value + 16f
    val contentHeightDp = max(maxY + cardHeight.value, laneCount * laneHeight.value) + 16f

    fun laneIndex(yDp: Float): Int =
        (yDp / laneHeight.value).roundToInt().coerceIn(0, laneCount - 1)

    fun laneColor(index: Int): Color = laneColors[index % laneColors.size]

    BoxWithConstraints(modifier = modifier.fillMaxSize().clipToBounds()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val densityValue = density.density
        val headerPx = with(density) { metrics.laneHeaderWidth.toPx() }
        val contentViewportPx = (widthPx - headerPx).coerceAtLeast(1f)
        val contentWidthPx = contentWidthDp * densityValue
        val contentHeightPx = contentHeightDp * densityValue
        val latestContentSize by rememberUpdatedState(contentWidthPx to contentHeightPx)
        val latestContentViewport by rememberUpdatedState(contentViewportPx to heightPx)
        val panX = viewport.panX
        val panY = viewport.panY
        val world = visibleWorldRect(
            viewportWidthPx = contentViewportPx,
            viewportHeightPx = heightPx,
            density = densityValue,
            scale = 1f,
            panX = panX,
            panY = panY,
            marginDp = max(cardWidth.value, cardHeight.value),
        )
        val drawnIds = remember(points, visible, world, cardWidth, cardHeight) {
            visibleInViewport(
                points = points,
                candidates = visible,
                world = world,
                cardWidthDp = cardWidth.value,
                cardHeightDp = cardHeight.value,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val old = latestViewport
                        latestOnChange(
                            ComparisonViewport(
                                scale = 1f,
                                panX = clampPanAxis(
                                    value = old.panX + drag.x,
                                    contentPx = latestContentSize.first,
                                    viewportPx = latestContentViewport.first,
                                ),
                                panY = clampPanAxis(
                                    value = old.panY + drag.y,
                                    contentPx = latestContentSize.second,
                                    viewportPx = latestContentViewport.second,
                                ),
                            )
                        )
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                clipRect(left = headerPx) {
                    drawLanes(
                        laneCount = laneCount,
                        laneHeightPx = laneHeightPx,
                        panY = panY,
                        laneColors = laneColors,
                        guideColor = edgeColor,
                        headerPx = headerPx,
                        widthPx = widthPx,
                    )
                    drawTimelineEdges(
                        graph = graph,
                        points = points,
                        drawnIds = drawnIds,
                        color = edgeColor,
                        laneColor = { point -> laneColor(laneIndex(point.y)) },
                        density = densityValue,
                        headerPx = headerPx,
                        panX = panX,
                        panY = panY,
                        cardWidthDp = cardWidth.value,
                        cardHeightDp = cardHeight.value,
                    )
                }
            }

            points.values.forEach { point ->
                if (point.id !in drawnIds) return@forEach
                val node = graph.nodes[point.id] ?: return@forEach
                val screenX = (headerPx + point.x * densityValue + panX).roundToInt()
                val screenY = (point.y * densityValue + panY).roundToInt()
                val accent = laneColor(laneIndex(point.y))

                ComparisonNodeCard(
                    node = node,
                    endpoint = point.id in endpointIds,
                    maxLines = 4,
                    minHeight = cardHeight,
                    accent = accent,
                    modifier = Modifier
                        .offset { IntOffset(screenX, screenY) }
                        .width(cardWidth),
                    onClick = { onNodeClick(node) },
                )

                if (point.id in folded) {
                    val anchor = node.childIds.firstOrNull()?.let { points[it] } ?: point
                    if (!intersectsViewport(anchor, world, cardWidth.value, cardHeight.value)) {
                        return@forEach
                    }
                    ComparisonFoldChip(
                        hiddenCount = foldHiddenCount(sizes, point.id),
                        endpoint = point.id in endpointIds,
                        fork = node.childIds.size > 1,
                        modifier = Modifier.offset {
                            IntOffset(
                                (headerPx + anchor.x * densityValue + panX).roundToInt(),
                                (anchor.y * densityValue + panY).roundToInt(),
                            )
                        },
                        onClick = { onExpand(point.id) },
                    )
                }
            }

            // 固定泳道标题列，盖在卡片之上，横向平移时不跟着走
            LaneHeader(
                titles = titles,
                laneColors = laneColors,
                laneHeightPx = laneHeightPx,
                panY = panY,
                heightPx = heightPx,
                headerWidth = metrics.laneHeaderWidth,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    }
}

@Composable
private fun LaneHeader(
    titles: List<String>,
    laneColors: List<Color>,
    laneHeightPx: Float,
    panY: Float,
    heightPx: Float,
    headerWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .width(headerWidth)
            .fillMaxHeight()
            .background(colors.surface)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(1.dp)
                .fillMaxHeight()
                .background(colors.outlineVariant)
        )
        titles.forEachIndexed { index, title ->
            val top = index * laneHeightPx + panY
            if (top > heightPx || top + laneHeightPx < 0f) return@forEachIndexed
            Row(
                modifier = Modifier
                    .offset { IntOffset(0, top.roundToInt()) }
                    .width(headerWidth)
                    .padding(start = 8.dp, end = 10.dp, top = 6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Spacer(
                    modifier = Modifier
                        .padding(top = 5.dp)
                        .size(6.dp)
                        .background(laneColors[index % laneColors.size], CircleShape)
                )
                Text(
                    text = title.ifBlank { stringResource(R.string.comparison_untitled) },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

private fun DrawScope.drawLanes(
    laneCount: Int,
    laneHeightPx: Float,
    panY: Float,
    laneColors: List<Color>,
    guideColor: Color,
    headerPx: Float,
    widthPx: Float,
) {
    val first = ((-panY) / laneHeightPx).toInt().coerceIn(0, laneCount)
    val last = ((size.height - panY) / laneHeightPx).toInt().coerceAtMost(laneCount - 1)
    for (lane in first..last) {
        val top = lane * laneHeightPx + panY
        val color = laneColors[lane % laneColors.size]
        // 整块 lane 区间铺底色，而不是只画一条分界线
        drawRect(
            color = color.copy(alpha = 0.12f),
            topLeft = Offset(headerPx, top),
            size = Size((widthPx - headerPx).coerceAtLeast(0f), laneHeightPx),
        )
        drawLine(
            color = guideColor.copy(alpha = 0.4f),
            start = Offset(headerPx, top),
            end = Offset(widthPx, top),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

private fun DrawScope.drawTimelineEdges(
    graph: ComparisonGraph,
    points: Map<String, LayoutPoint>,
    drawnIds: Set<String>,
    color: Color,
    laneColor: (LayoutPoint) -> Color,
    density: Float,
    headerPx: Float,
    panX: Float,
    panY: Float,
    cardWidthDp: Float,
    cardHeightDp: Float,
) {
    val cardWidthPx = cardWidthDp * density
    val cardHeightPx = cardHeightDp * density
    val stroke = 1.5.dp.toPx()

    fun screenX(worldX: Float): Float = headerPx + worldX * density + panX
    fun screenY(worldY: Float): Float = worldY * density + panY

    fun outside(x: Float, y: Float): Boolean =
        x + cardWidthPx < headerPx || x > size.width || y + cardHeightPx < 0f || y > size.height

    graph.nodes.values.forEach { node ->
        val parentId = node.parentId ?: return@forEach
        if (node.id !in drawnIds || parentId !in drawnIds) return@forEach
        val start = points[parentId] ?: return@forEach
        val end = points[node.id] ?: return@forEach

        val startX = screenX(start.x) + cardWidthPx
        val startY = screenY(start.y) + cardHeightPx / 2f
        val endX = screenX(end.x)
        val endY = screenY(end.y) + cardHeightPx / 2f
        if (outside(startX, startY) && outside(endX, endY)) return@forEach

        // 正交折线：竖-横-竖，比贝塞尔更像泳道图
        val midX = startX + (endX - startX).coerceAtLeast(1f) * 0.5f
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(midX, startY)
            lineTo(midX, endY)
            lineTo(endX, endY)
        }
        drawPath(path, laneColor(end).copy(alpha = 0.55f), style = Stroke(width = stroke))
    }
}
