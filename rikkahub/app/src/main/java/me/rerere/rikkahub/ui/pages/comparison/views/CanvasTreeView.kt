package me.rerere.rikkahub.ui.pages.comparison.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.comparison.ComparisonGraph
import me.rerere.rikkahub.data.model.comparison.ComparisonLayout
import me.rerere.rikkahub.data.model.comparison.ComparisonNode
import me.rerere.rikkahub.data.model.comparison.LayoutPoint
import me.rerere.rikkahub.ui.pages.comparison.ComparisonViewport
import kotlin.math.max
import kotlin.math.roundToInt

internal const val MinCanvasScale = 0.2f
internal const val MaxCanvasScale = 3f

/**
 * 视图 B：自由画布。tidy-tree 布局 + 双指缩放/拖动，贝塞尔连线。
 *
 * 视口是容器尺寸，节点按世界坐标换算成屏幕偏移后再用 graphicsLayer 缩放；整图尺寸从不进
 * `Modifier.size()`，所以 2000 节点的长会话不会撞上 Compose 的 18 bit Constraints 上限。
 * 每帧只组合与视口相交（外扩一个卡片）的节点，首屏卡片数远小于 60。
 */
@Composable
fun CanvasTreeView(
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
    val edgeColor = MaterialTheme.colorScheme.outlineVariant
    val density = LocalDensity.current
    val latestViewport by rememberUpdatedState(viewport)
    val latestOnChange by rememberUpdatedState(onViewportChange)
    val cardWidth = metrics.cardWidth
    val cardHeight = metrics.cardHeight
    val maxX = remember(points) { points.values.maxOf { it.x } }
    val maxY = remember(points) { points.values.maxOf { it.y } }
    val contentWidthDp = maxX + cardWidth.value + 16f
    val contentHeightDp = maxY + cardHeight.value + 16f

    BoxWithConstraints(modifier = modifier.fillMaxSize().clipToBounds()) {
        val viewportWidthPx = constraints.maxWidth.toFloat()
        val viewportHeightPx = constraints.maxHeight.toFloat()
        val densityValue = density.density
        val scale = viewport.scale
        val contentWidthPx = contentWidthDp * densityValue
        val contentHeightPx = contentHeightDp * densityValue
        // pointerInput(Unit) 的 lambda 只建一次，尺寸用最新值，避免分屏/旋转后限位用旧尺寸
        val latestContentSize by rememberUpdatedState(contentWidthPx to contentHeightPx)
        val latestViewSize by rememberUpdatedState(viewportWidthPx to viewportHeightPx)
        val world = visibleWorldRect(
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
            density = densityValue,
            scale = scale,
            panX = viewport.panX,
            panY = viewport.panY,
            marginDp = max(cardWidth.value, cardHeight.value),
        )
        // 先按折叠过滤，再按视口裁剪；两步都是纯函数，单测覆盖。
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
                    detectTransformGestures { centroid, panChange, zoomChange, _ ->
                        val old = latestViewport
                        val newScale = (old.scale * zoomChange).coerceIn(MinCanvasScale, MaxCanvasScale)
                        val ratio = newScale / old.scale
                        // 以 centroid 为锚：该点下的世界坐标在缩放前后保持不动
                        val anchoredX = centroid.x - (centroid.x - old.panX) * ratio
                        val anchoredY = centroid.y - (centroid.y - old.panY) * ratio
                        latestOnChange(
                            ComparisonViewport(
                                scale = newScale,
                                panX = clampPanAxis(
                                    value = anchoredX + panChange.x,
                                    contentPx = latestContentSize.first * newScale,
                                    viewportPx = latestViewSize.first,
                                ),
                                panY = clampPanAxis(
                                    value = anchoredY + panChange.y,
                                    contentPx = latestContentSize.second * newScale,
                                    viewportPx = latestViewSize.second,
                                ),
                            )
                        )
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { latestOnChange(ComparisonViewport()) }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCanvasEdges(
                    graph = graph,
                    points = points,
                    drawnIds = drawnIds,
                    color = edgeColor,
                    density = densityValue,
                    scale = scale,
                    panX = viewport.panX,
                    panY = viewport.panY,
                    cardWidthDp = cardWidth.value,
                    cardHeightDp = cardHeight.value,
                )
            }

            points.values.forEach { point ->
                if (point.id !in drawnIds) return@forEach
                val node = graph.nodes[point.id] ?: return@forEach
                val screenX = (point.x * densityValue * scale + viewport.panX).roundToInt()
                val screenY = (point.y * densityValue * scale + viewport.panY).roundToInt()

                ComparisonNodeCard(
                    node = node,
                    endpoint = point.id in endpointIds,
                    maxLines = 3,
                    minHeight = cardHeight,
                    modifier = Modifier
                        .offset { IntOffset(screenX, screenY) }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                        .width(cardWidth),
                    onClick = { onNodeClick(node) },
                )

                // 折叠的是子树，不是这个节点：卡片照常画，chip 落在子节点列
                if (point.id in folded) {
                    val anchor = node.childIds.firstOrNull()?.let { points[it] } ?: point
                    if (!intersectsViewport(anchor, world, cardWidth.value, cardHeight.value)) {
                        return@forEach
                    }
                    val chipX = (anchor.x * densityValue * scale + viewport.panX).roundToInt()
                    val chipY = (anchor.y * densityValue * scale + viewport.panY).roundToInt()
                    ComparisonFoldChip(
                        hiddenCount = foldHiddenCount(sizes, point.id),
                        endpoint = point.id in endpointIds,
                        fork = node.childIds.size > 1,
                        modifier = Modifier
                            .offset { IntOffset(chipX, chipY) }
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin(0f, 0f)
                            },
                        onClick = { onExpand(point.id) },
                    )
                }
            }

            FilledTonalIconButton(
                onClick = { onViewportChange(ComparisonViewport()) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Refresh01,
                    contentDescription = stringResource(R.string.comparison_reset_view),
                )
            }
        }
    }
}

private fun DrawScope.drawCanvasEdges(
    graph: ComparisonGraph,
    points: Map<String, LayoutPoint>,
    drawnIds: Set<String>,
    color: Color,
    density: Float,
    scale: Float,
    panX: Float,
    panY: Float,
    cardWidthDp: Float,
    cardHeightDp: Float,
) {
    val cardWidthPx = cardWidthDp * density * scale
    val cardHeightPx = cardHeightDp * density * scale
    val stroke = 1.5.dp.toPx()
    val unit = density * scale

    fun screenX(worldX: Float): Float = worldX * unit + panX
    fun screenY(worldY: Float): Float = worldY * unit + panY

    fun outside(x: Float, y: Float): Boolean =
        x + cardWidthPx < 0f || x > size.width || y + cardHeightPx < 0f || y > size.height

    graph.nodes.values.forEach { node ->
        val parentId = node.parentId ?: return@forEach
        if (node.id !in drawnIds || parentId !in drawnIds) return@forEach
        val start = points[parentId] ?: return@forEach
        val end = points[node.id] ?: return@forEach

        // y 是卡片顶边，连线接在竖直中线
        val startX = screenX(start.x) + cardWidthPx
        val startY = screenY(start.y) + cardHeightPx / 2f
        val endX = screenX(end.x)
        val endY = screenY(end.y) + cardHeightPx / 2f

        if (outside(startX, startY) && outside(endX, endY)) return@forEach

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
