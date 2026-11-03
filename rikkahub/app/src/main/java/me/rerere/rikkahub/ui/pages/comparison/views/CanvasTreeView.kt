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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
 * 每帧只组合与视口相交（外扩一个卡片）的节点，并按 (y, x) 截到 [MAX_DRAWN_NODES] 张。
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
    endpointTitles: Map<String, List<String>> = emptyMap(),
    onEndpointClick: (ComparisonNode) -> Unit = {},
    /** 是否已完成首次 fit：放在 VM 里，切视图回来不再重置用户的缩放/平移。 */
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
    // 纵向按整行高度算：卡片实际能到 120~150dp，按 96 裁剪会在行间留缝
    val rowHeight = metrics.rowHeight
    // 包围盒取折叠后的可见节点：折叠掉的子树不该继续把画布拉长
    val bounds = remember(points, visible, cardWidth, rowHeight) {
        contentBounds(points, visible, cardWidth.value, rowHeight.value)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().clipToBounds()) {
        val viewportWidthPx = constraints.maxWidth.toFloat()
        val viewportHeightPx = constraints.maxHeight.toFloat()
        val densityValue = density.density
        val scale = viewport.scale
        val latestBounds by rememberUpdatedState(bounds)
        val latestDensity by rememberUpdatedState(densityValue)
        // 首次进入 / 换图时 fit 一次；旋转或缩放后只重新限位，不重置用户的视图

        fun fit(): ComparisonViewport = bounds?.let { box ->
            fitViewport(
                contentWidthPx = (box.right - box.left) * densityValue,
                contentHeightPx = (box.bottom - box.top) * densityValue,
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                bounds = box,
            )
        } ?: ComparisonViewport()

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
        val drawn = remember(points, visible, world, cardWidth, rowHeight) {
            visibleInViewportCapped(
                points = points,
                candidates = visible,
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
                        if (intersectsViewport(anchor, world, cardWidth.value, rowHeight.value)) {
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
                }
            }

            // 线性长链的中段：chip 挂在第一颗被藏起来的节点位置，点一下恢复整段
            linear.anchors.forEach { (anchorId, count) ->
                if (anchorId in drawn.ids) return@forEach
                val anchor = points[anchorId] ?: return@forEach
                val parentId = graph.nodes[anchorId]?.parentId ?: return@forEach
                if (parentId !in drawn.ids) return@forEach
                if (!intersectsViewport(anchor, world, cardWidth.value, rowHeight.value)) {
                    return@forEach
                }
                ComparisonFoldChip(
                    hiddenCount = count,
                    label = stringResource(R.string.comparison_linear_more, count),
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
                        },
                    onClick = { onExpandSegment(anchorId) },
                )
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
