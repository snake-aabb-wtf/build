package me.rerere.rikkahub.ui.pages.comparison.views

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.comparison.ComparisonGraph
import me.rerere.rikkahub.data.model.comparison.ComparisonNode
import kotlin.math.min
import kotlin.math.roundToInt

/** 可视缩进上限：再深的层级用「深度 N」徽标表达，避免 4300 层把卡片推出屏幕。 */
internal const val MaxVisibleIndent = 60

/** 横向滚动内容上限，`60000.dp * 4(density)` 仍在 Compose 的 18 bit Constraints 内。 */
private val MaxIndentedContentWidth = 60000.dp

private class TreeRow(
    val node: ComparisonNode,
    val depth: Int,
    /** chain[i] = 第 i+1 层节点还有后续兄弟，决定第 i 列竖线是否继续 */
    val chain: List<Boolean>,
    val isLast: Boolean,
)

/**
 * 视图 A：缩进树。DFS 顺序逐行排布，永不折叠（上千条消息也要能一条条读完）。
 *
 * 整个列表只有一个横向 ScrollState（之前每行各自 horizontalScroll 同一个 state，互相覆盖 maxValue）；
 * 缩进用 offset 而不是 Spacer.width，`depth * 20dp` 再深也不会撞 Constraints；连接线用统一步长画满整行，
 * 父子竖干在行与行之间连续。
 */
@Composable
fun IndentedTreeView(
    graph: ComparisonGraph,
    endpointIds: Set<String>,
    metrics: ComparisonMetrics,
    onNodeClick: (ComparisonNode) -> Unit,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") expanded: Set<String> = emptySet(),
    @Suppress("UNUSED_PARAMETER") onExpand: (String) -> Unit = {},
) {
    val rows = remember(graph) { buildRows(graph) }
    val scrollState = rememberScrollState()
    val connectorColor = MaterialTheme.colorScheme.outlineVariant
    val maxVisualDepth = remember(rows) {
        rows.maxOfOrNull { min(it.depth, MaxVisibleIndent) } ?: 0
    }
    val contentWidth = remember(maxVisualDepth, metrics) {
        (metrics.indentStep * maxVisualDepth + metrics.indentCardWidth + 16.dp)
            .coerceAtMost(MaxIndentedContentWidth)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .horizontalScroll(scrollState)
    ) {
        LazyColumn(
            modifier = Modifier
                .width(contentWidth)
                .fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(rows, key = { it.node.id }) { row ->
                TreeRowItem(
                    row = row,
                    metrics = metrics,
                    endpoint = row.node.id in endpointIds,
                    connectorColor = connectorColor,
                    onClick = { onNodeClick(row.node) },
                )
            }
        }
    }
}

@Composable
private fun TreeRowItem(
    row: TreeRow,
    metrics: ComparisonMetrics,
    endpoint: Boolean,
    connectorColor: Color,
    onClick: () -> Unit,
) {
    val density = LocalDensity.current
    val visualDepth = min(row.depth, MaxVisibleIndent)
    val indentPx = with(density) { (metrics.indentStep * visualDepth).roundToPx() }
    val stepPx = with(density) { metrics.indentStep.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawConnectors(
                    depth = visualDepth,
                    chain = row.chain,
                    isLast = row.isLast,
                    color = connectorColor,
                    step = stepPx,
                )
            }
    ) {
        ComparisonNodeCard(
            node = row.node,
            endpoint = endpoint,
            maxLines = 40,
            expandable = true,
            depth = if (row.depth > MaxVisibleIndent) row.depth else null,
            minHeight = metrics.cardHeight,
            modifier = Modifier
                .offset { IntOffset(indentPx, 0) }
                .padding(vertical = 4.dp)
                .width(metrics.indentCardWidth),
            onClick = onClick,
        )
    }
}

private fun buildRows(graph: ComparisonGraph): List<TreeRow> {
    val rows = ArrayList<TreeRow>(graph.nodes.size)
    val stack = ArrayDeque<TreeRow>()

    for (index in graph.rootIds.indices.reversed()) {
        val node = graph.nodes[graph.rootIds[index]] ?: continue
        stack.addLast(TreeRow(node, 0, emptyList(), index == graph.rootIds.lastIndex))
    }

    while (stack.isNotEmpty()) {
        val row = stack.removeLast()
        rows += row

        val children = row.node.childIds
        for (index in children.indices.reversed()) {
            val child = graph.nodes[children[index]] ?: continue
            val isLast = index == children.lastIndex
            stack.addLast(
                TreeRow(
                    node = child,
                    depth = row.depth + 1,
                    chain = row.chain + !isLast,
                    isLast = isLast,
                )
            )
        }
    }

    return rows
}

/**
 * 连接线：每层竖线在整行高度上连续，最内层在行中线折向卡片左缘。
 *
 * @param step 缩进步长（px），与卡片的 offset 使用同一个 [ComparisonMetrics.indentStep]
 */
private fun DrawScope.drawConnectors(
    depth: Int,
    chain: List<Boolean>,
    isLast: Boolean,
    color: Color,
    step: Float,
) {
    if (depth <= 0) return
    val stroke = 1.5.dp.toPx()
    val midY = size.height / 2f
    val cardLeft = depth * step

    for (level in 0 until depth) {
        val x = level * step + step / 2f
        if (level < depth - 1) {
            if (chain.getOrElse(level) { false }) {
                drawLine(color, Offset(x, 0f), Offset(x, size.height), stroke)
            }
        } else {
            // 中线以上接父节点，横线进卡片，中线以下接后续兄弟
            drawLine(color, Offset(x, 0f), Offset(x, midY), stroke)
            drawLine(color, Offset(x, midY), Offset(cardLeft, midY), stroke)
            if (!isLast) {
                drawLine(color, Offset(x, midY), Offset(x, size.height), stroke)
            }
        }
    }
}
