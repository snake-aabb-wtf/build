package me.rerere.rikkahub.ui.pages.comparison.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.layout.onSizeChanged
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
import me.rerere.rikkahub.data.model.comparison.ComparisonOrientation
import me.rerere.rikkahub.data.model.comparison.LayoutPoint
import me.rerere.rikkahub.ui.pages.comparison.ComparisonViewport
import kotlin.math.max
import kotlin.math.roundToInt

internal const val MinCanvasScale = 0.2f
internal const val MaxCanvasScale = 3f

private data class CanvasExpansion(
    val point: LayoutPoint,
    val nodeId: String,
    val hiddenCount: Int,
    val linear: Boolean,
)

/**
 * 自由画布：tidy-tree 布局 + 双指缩放/拖动，贝塞尔连线。
 *
 * 视口是容器尺寸，节点按世界坐标换算成屏幕偏移后再用 graphicsLayer 缩放；整图尺寸从不进
 * `Modifier.size()`，所以 2000 节点的长会话不会撞上 Compose 的 18 bit Constraints 上限。
 * 每帧只组合与视口相交（外扩一个卡片）的节点，并按 (y, x) 截到 [MAX_DRAWN_NODES] 张。
 */
@Composable
fun CanvasTreeView(
    graph: ComparisonGraph,
    layout: ComparisonLayout,
    orientation: ComparisonOrientation,
    sizes: Map<String, Int>,
    endpointIds: Set<String>,
    expanded: Set<String>,
    viewport: ComparisonViewport,
    onViewportChange: (ComparisonViewport) -> Unit,
    onExpand: (String) -> Unit,
    onNodeClick: (ComparisonNode) -> Unit,
    metrics: ComparisonMetrics,
    modifier: Modifier = Modifier,
    endpointTitles: Map<String, List<String>> = emptyMap(),
    onEndpointClick: (ComparisonNode) -> Unit = {},
    /** 是否已完成首次 fit：放在 VM 里，返回页面时不再重置用户的缩放/平移。 */
    fitted: Boolean = false,
    onFitted: () -> Unit = {},
    onExpandSegment: (String) -> Unit = {},
) {
    val points = layout.points
    if (points.isEmpty()) return

    val folded = remember(graph, sizes, expanded) { foldedNodeIds(graph, sizes, expanded) }
    val linear = remember(graph, expanded) { linearCompression(graph, expanded) }
    val visible = remember(graph, folded, linear) {
        visibleNodeIds(graph, folded, linear.hidden)
    }
    val edgeColor = MaterialTheme.colorScheme.outlineVariant
    val density = LocalDensity.current
    val latestViewport by rememberUpdatedState(viewport)
    val latestOnChange by rememberUpdatedState(onViewportChange)
    val cardWidth = metrics.cardWidth
    val cardHeight = metrics.cardHeight
    // 实测未缩放的卡高：竖向连线必须从卡片底部出发，大字号不能用最小高度代替。
    val cardHeights = remember(graph, metrics, density.fontScale) { mutableStateMapOf<String, Float>() }
    // 纵向按整行高度算：卡片实际能到 120~150dp，按 96 裁剪会在行间留缝
    val rowHeight = metrics.rowHeight
    val expansions = remember(graph, points, folded, linear, visible, orientation, metrics, sizes) {
        buildList {
            folded.forEach { id ->
                if (id !in visible) return@forEach
                val parent = points[id] ?: return@forEach
                add(
                    CanvasExpansion(
                        canvasFoldPoint(parent, orientation, metrics.columnWidth.value, rowHeight.value),
                        nodeId = id, hiddenCount = foldHiddenCount(sizes, id), linear = false,
                    )
                )
            }
            linear.anchors.forEach { (id, count) ->
                if (graph.nodes[id]?.parentId !in visible) return@forEach
                val point = points[id] ?: return@forEach
                add(CanvasExpansion(point.copy(id = "segment:$id"), id, count, linear = true))
            }
        }
    }
    val visualPoints = remember(points, expansions) {
        points + expansions.associate { it.point.id to it.point }
    }
    val visualIds = remember(visible, expansions) { visible + expansions.map { it.point.id } }
    // 展开入口也参与适配、限位和裁剪，避免宽分叉的按钮落在视口之外。
    val bounds = remember(visualPoints, visualIds, cardWidth, rowHeight) {
        contentBounds(visualPoints, visualIds, cardWidth.value, rowHeight.value)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().clipToBounds()) {
        val viewportWidthPx = constraints.maxWidth.toFloat()
        val viewportHeightPx = constraints.maxHeight.toFloat()
        val densityValue = density.density
        val scale = viewport.scale
        val latestBounds by rememberUpdatedState(bounds)
        val latestDensity by rememberUpdatedState(densityValue)
        // 首次进入 / 换图时 fit 一次；旋转或缩放后只重新限位，不重置用户的视图

        fun fit(): ComparisonViewport =
            fitCanvasViewport(bounds, densityValue, viewportWidthPx, viewportHeightPx)

        val latestFit by rememberUpdatedState(fit())

        LaunchedEffect(bounds, scale, viewportWidthPx, viewportHeightPx, fitted) {
            if (!fitted) {
                if (bounds != null && viewportWidthPx > 0f && viewportHeightPx > 0f) {
                    latestOnChange(fit())
                    onFitted()
                }
                return@LaunchedEffect
            }
            // 换图/旋转沿用旧 pan 会首帧空白：按当前包围盒重新限位
            val box = latestBounds ?: return@LaunchedEffect
            val unit = latestDensity * scale
            val current = latestViewport
            val panX = clampPan(
                value = current.panX,
                startPx = box.left * unit,
                endPx = box.right * unit,
                viewportPx = viewportWidthPx,
            )
            val panY = clampPan(
                value = current.panY,
                startPx = box.top * unit,
                endPx = box.bottom * unit,
                viewportPx = viewportHeightPx,
            )
            if (panX != current.panX || panY != current.panY) {
                latestOnChange(current.copy(panX = panX, panY = panY))
            }
        }

        val world = visibleWorldRect(
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
            density = densityValue,
            scale = scale,
            panX = viewport.panX,
            panY = viewport.panY,
            marginDp = max(cardWidth.value, rowHeight.value),
        )
        // 先按折叠过滤，再按视口裁剪并截断到上限；都是纯函数，单测覆盖。
        val drawn = remember(visualPoints, visualIds, world, cardWidth, rowHeight) {
            visibleInViewportCapped(
                points = visualPoints,
                candidates = visualIds,
                world = world,
                cardWidthDp = cardWidth.value,
                cardHeightDp = rowHeight.value,
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
                        val box = latestBounds
                        val unit = latestDensity * newScale
                        latestOnChange(
                            ComparisonViewport(
                                scale = newScale,
                                panX = if (box == null) {
                                    anchoredX + panChange.x
                                } else {
                                    clampPan(
                                        value = anchoredX + panChange.x,
                                        startPx = box.left * unit,
                                        endPx = box.right * unit,
                                        viewportPx = size.width.toFloat(),
                                    )
                                },
                                panY = if (box == null) {
                                    anchoredY + panChange.y
                                } else {
                                    clampPan(
                                        value = anchoredY + panChange.y,
                                        startPx = box.top * unit,
                                        endPx = box.bottom * unit,
                                        viewportPx = size.height.toFloat(),
                                    )
                                },
                            )
                        )
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        // 双击回到 fit，而不是把大图缩成 1:1 只看得到第一列
                        onDoubleTap = { latestOnChange(latestFit) }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCanvasEdges(
                    graph = graph,
                    points = points,
                    drawnIds = drawn.ids,
                    color = edgeColor,
                    density = densityValue,
                    scale = scale,
                    panX = viewport.panX,
                    panY = viewport.panY,
                    cardWidthDp = cardWidth.value,
                    cardHeightDp = cardHeight.value,
                    cardHeights = cardHeights,
                    orientation = orientation,
                )
            }

            points.values.forEach { point ->
                if (point.id !in drawn.ids) return@forEach
                val node = graph.nodes[point.id] ?: return@forEach
                key(point.id) {
                    val screenX = (point.x * densityValue * scale + viewport.panX).roundToInt()
                    val screenY = (point.y * densityValue * scale + viewport.panY).roundToInt()

                    ComparisonNodeCard(
                        node = node,
                        endpoint = point.id in endpointIds,
                        endpointTitles = endpointTitles[point.id].orEmpty(),
                        onEndpointClick = {
                            if (endpointTitles[point.id].orEmpty().size > 1) onEndpointClick(node)
                        },
                        maxLines = 3,
                        minHeight = cardHeight,
                        modifier = Modifier
                            .offset { IntOffset(screenX, screenY) }
                            .onSizeChanged { cardHeights[point.id] = it.height / densityValue }
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin(0f, 0f)
                            }
                            .width(cardWidth),
                        onClick = { onNodeClick(node) },
                    )

                }
            }

            // 入口独立裁剪：平移后即使父卡片不在当前屏幕内，仍可点开可见的 chip。
            expansions.forEach { expansion ->
                val anchor = expansion.point
                if (anchor.id !in drawn.ids) return@forEach
                key(anchor.id) {
                    ComparisonFoldChip(
                        hiddenCount = expansion.hiddenCount,
                        label = if (expansion.linear) {
                            stringResource(R.string.comparison_linear_more, expansion.hiddenCount)
                        } else null,
                        endpoint = !expansion.linear && expansion.nodeId in endpointIds,
                        fork = !expansion.linear,
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (anchor.x * densityValue * scale + viewport.panX).roundToInt(),
                                    (anchor.y * densityValue * scale + viewport.panY).roundToInt(),
                                )
                            }
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin(0f, 0f)
                            }
                            .widthIn(max = cardWidth),
                        onClick = {
                            if (expansion.linear) onExpandSegment(expansion.nodeId)
                            else onExpand(expansion.nodeId)
                        },
                    )
                }
            }

            if (drawn.hiddenCount > 0) {
                // 画不下就明确告诉用户还有多少，放大后会出现
                Text(
                    text = stringResource(R.string.comparison_hidden_nodes, drawn.hiddenCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            FilledTonalIconButton(
                onClick = { onViewportChange(fit()) },
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
    cardHeights: Map<String, Float>,
    orientation: ComparisonOrientation,
) {
    val stroke = 1.5.dp.toPx()
    val unit = density * scale

    fun screenX(worldX: Float): Float = worldX * unit + panX
    fun screenY(worldY: Float): Float = worldY * unit + panY

    graph.nodes.values.forEach { node ->
        val parentId = node.parentId ?: return@forEach
        if (node.id !in drawnIds || parentId !in drawnIds) return@forEach
        val start = points[parentId] ?: return@forEach
        val end = points[node.id] ?: return@forEach

        val edge = canvasEdge(
            start, end, cardWidthDp,
            parentHeight = cardHeights[parentId] ?: cardHeightDp,
            childHeight = cardHeights[node.id] ?: cardHeightDp,
            orientation = orientation,
        )
        val path = Path().apply {
            moveTo(screenX(edge.startX), screenY(edge.startY))
            cubicTo(
                screenX(edge.control1X), screenY(edge.control1Y),
                screenX(edge.control2X), screenY(edge.control2Y),
                screenX(edge.endX), screenY(edge.endY),
            )
        }
        drawPath(path, color, style = Stroke(width = stroke))
    }
}
