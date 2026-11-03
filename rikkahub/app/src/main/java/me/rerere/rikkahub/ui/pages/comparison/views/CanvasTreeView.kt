package me.rerere.rikkahub.ui.pages.comparison.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import me.rerere.rikkahub.data.model.comparison.ComparisonGraph
import me.rerere.rikkahub.data.model.comparison.ComparisonLayout
import me.rerere.rikkahub.data.model.comparison.ComparisonNode
import me.rerere.rikkahub.data.model.comparison.LayoutPoint

private const val CanvasCardWidthDp = 220f
private const val CanvasCardHeightDp = 96f
private const val MinScale = 0.4f
private const val MaxScale = 2.5f

/**
 * 视图 B：自由画布。tidy-tree 布局 + 双指缩放/拖动，贝塞尔连线。
 *
 * 子树超过 [FOLD_SUBTREE_THRESHOLD] 且未展开的节点画成 `+N` 占位，避免一次铺开上千个节点。
 */
@Composable
fun CanvasTreeView(
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
    val edgeColor = MaterialTheme.colorScheme.outlineVariant
    val density = LocalDensity.current

    val points = layout.points
    if (layout.points.isEmpty()) return

    val maxX = points.values.maxOf { it.x }
    val maxY = points.values.maxOf { it.y }
    val contentWidthDp = maxX + CanvasCardWidthDp + 32f
    val contentHeightDp = maxY + CanvasCardHeightDp + 32f

    BoxWithConstraints(modifier = modifier.fillMaxSize().clipToBounds()) {
        val fitScale = (maxWidth.value / contentWidthDp).coerceIn(MinScale, 1f)
        // state 只建一次，换图时用 effect 重置，避免 transformable 的 lambda 抓到旧的 state 对象
        var scale by remember { mutableFloatStateOf(fitScale) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        LaunchedEffect(layout, fitScale) {
            scale = fitScale
            offset = Offset.Zero
        }

        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(MinScale, MaxScale)
            offset += panChange
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .transformable(transformState)
        ) {
            Box(
                modifier = Modifier
                    .size(contentWidthDp.dp, contentHeightDp.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
            ) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    drawEdges(graph, points, visible, edgeColor)
                }

                val halfCard = CanvasCardHeightDp / 2f
                points.values.forEach { point ->
                    val node = graph.nodes[point.id] ?: return@forEach
                    if (point.id !in visible) return@forEach
                    val x = with(density) { point.x.dp.roundToPx() }
                    val y = with(density) { (point.y - halfCard).dp.roundToPx() }
                    val chipOffset = with(density) { 24.dp.roundToPx() }
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
                            maxLines = 3,
                            modifier = Modifier
                                .offset { IntOffset(x, y) }
                                .width(CanvasCardWidthDp.dp)
                                .height(CanvasCardHeightDp.dp),
                            onClick = { onNodeClick(node) },
                        )
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawEdges(
    graph: ComparisonGraph,
    points: Map<String, LayoutPoint>,
    visible: Set<String>,
    color: Color,
) {
    val cardWidth = CanvasCardWidthDp.dp.toPx()
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
