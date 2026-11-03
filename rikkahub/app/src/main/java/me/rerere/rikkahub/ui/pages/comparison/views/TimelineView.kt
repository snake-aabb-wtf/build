package me.rerere.rikkahub.ui.pages.comparison.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.comparison.ComparisonGraph
import me.rerere.rikkahub.data.model.comparison.ComparisonLayout
import me.rerere.rikkahub.data.model.comparison.ComparisonNode
import me.rerere.rikkahub.data.model.comparison.LayoutPoint
import kotlin.math.roundToInt

private const val TimelineCardWidthDp = 260f
private const val TimelineCardHeightDp = 120f
private const val TimelineLaneHeightDp = 140f
private const val TimelineColumnWidthDp = 320f

/**
 * 视图 C：横向时间线。每一列是一个深度，每一 lane 是一条分支，lane 用不同色相区分。
 *
 * 与画布一样，超大树先折叠成 `+N` 占位；整块内容横向 + 纵向滚动。
 */
@Composable
fun TimelineView(
    graph: ComparisonGraph,
    layout: ComparisonLayout,
    sizes: Map<String, Int>,
    endpointIds: Set<String>,
    expanded: Set<String>,
    onExpand: (String) -> Unit,
    onNodeClick: (ComparisonNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val folded = remember(graph, sizes, expanded) { foldedNodeIds(graph, sizes, expanded) }
    val visible = remember(graph, folded) { visibleNodeIds(graph, folded) }
    val colorScheme = MaterialTheme.colorScheme
    val laneColors = remember(colorScheme) {
        listOf(
            colorScheme.primary,
            colorScheme.secondary,
            colorScheme.tertiary,
            colorScheme.error,
        )
    }
    val edgeColor = colorScheme.outlineVariant
    val density = LocalDensity.current
    val points = layout.points
    if (points.isEmpty()) return

    val horizontalScroll = rememberScrollState()
    val verticalScroll = rememberScrollState()

    val maxX = points.values.maxOf { it.x }
    val maxY = points.values.maxOf { it.y }
    val contentWidthDp = maxX + TimelineCardWidthDp + 32f
    val contentHeightDp = maxOf(
        layout.leafCount * TimelineLaneHeightDp,
        maxY + TimelineCardHeightDp,
    ) + 32f
    val laneHeight = TimelineLaneHeightDp

    fun laneColor(y: Float): Color =
        laneColors[((y / laneHeight).roundToInt().coerceAtLeast(0)) % laneColors.size]

    Box(
        modifier = modifier
            .fillMaxSize()
            .horizontalScroll(horizontalScroll)
            .verticalScroll(verticalScroll)
    ) {
        Box(
            modifier = Modifier
                .size(contentWidthDp.dp, contentHeightDp.dp)
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                drawLanes(layout, laneColors, edgeColor)
                drawTimelineEdges(graph, points, visible, edgeColor)
            }

            val halfCard = TimelineCardHeightDp / 2f
            val chipOffset = with(density) { 28.dp.roundToPx() }
            points.values.forEach { point ->
                val node = graph.nodes[point.id] ?: return@forEach
                if (point.id !in visible) return@forEach
                val x = with(density) { point.x.dp.roundToPx() }
                val y = with(density) { (point.y - halfCard).dp.roundToPx() }
                if (point.id in folded) {
                    ComparisonFoldChip(
                        hiddenCount = (sizes[point.id] ?: 1) - 1,
                        modifier = Modifier.offset { IntOffset(x, y + chipOffset) },
                        onClick = { onExpand(point.id) },
                    )
                } else {
                    ComparisonNodeCard(
                        node = node,
                        endpoint = point.id in endpointIds,
                        maxLines = 4,
                        accent = laneColor(point.y),
                        modifier = Modifier
                            .offset { IntOffset(x, y) }
                            .width(TimelineCardWidthDp.dp)
                            .height(TimelineCardHeightDp.dp),
                        onClick = { onNodeClick(node) },
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawLanes(
    layout: ComparisonLayout,
    laneColors: List<Color>,
    guideColor: Color,
) {
    val laneHeight = TimelineLaneHeightDp.dp.toPx()
    for (lane in 0 until layout.leafCount) {
        val y = lane * laneHeight
        drawLine(
            color = laneColors[lane % laneColors.size].copy(alpha = 0.18f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = laneHeight * 0.5f,
        )
    }
    // 列分隔线：一眼看出深度
    var x = TimelineColumnWidthDp.dp.toPx()
    while (x < size.width) {
        drawLine(
            color = guideColor.copy(alpha = 0.4f),
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 1.dp.toPx(),
        )
        x += TimelineColumnWidthDp.dp.toPx()
    }
}

private fun DrawScope.drawTimelineEdges(
    graph: ComparisonGraph,
    points: Map<String, LayoutPoint>,
    visible: Set<String>,
    color: Color,
) {
    val cardWidth = TimelineCardWidthDp.dp.toPx()
    val stroke = 1.5.dp.toPx()

    graph.nodes.values.forEach { node ->
        val parentId = node.parentId ?: return@forEach
        if (node.id !in visible || parentId !in visible) return@forEach
        val start = points[parentId] ?: return@forEach
        val end = points[node.id] ?: return@forEach

        val startX = start.x.dp.toPx() + cardWidth
        val startY = start.y.dp.toPx()
        val endX = end.x.dp.toPx()
        val endY = end.y.dp.toPx()
        val gap = (endX - startX).coerceAtLeast(1f)

        val path = Path().apply {
            moveTo(startX, startY)
            cubicTo(
                startX + gap * 0.5f, startY,
                endX - gap * 0.5f, endY,
                endX, endY,
            )
        }
        drawPath(path, color, style = Stroke(width = stroke))
    }
}
