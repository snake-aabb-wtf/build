package me.rerere.rikkahub.ui.pages.comparison.views

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.comparison.ComparisonGraph
import me.rerere.rikkahub.data.model.comparison.ComparisonNode

private val IndentStep = 20.dp
private val IndentedCardWidth = 320.dp

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
 * 行内左侧用 Canvas 之外的 drawBehind 画连接线，行内容整体横向滚动，深层缩进不会把正文挤没。
 */
@Composable
fun IndentedTreeView(
    graph: ComparisonGraph,
    endpointIds: Set<String>,
    expanded: Set<String>,
    onExpand: (String) -> Unit,
    onNodeClick: (ComparisonNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember(graph) { buildRows(graph) }
    val scrollState = rememberScrollState()
    val connectorColor = MaterialTheme.colorScheme.outlineVariant

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(rows, key = { it.node.id }) { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
                    .drawBehind {
                        drawConnectors(row.depth, row.chain, row.isLast, connectorColor)
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Spacer(Modifier.width(IndentStep * row.depth + 12.dp))
                ComparisonNodeCard(
                    node = row.node,
                    endpoint = row.node.id in endpointIds,
                    maxLines = Int.MAX_VALUE,
                    modifier = Modifier.width(IndentedCardWidth),
                    onClick = { onNodeClick(row.node) },
                )
                Spacer(Modifier.width(16.dp))
            }
        }
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

private fun DrawScope.drawConnectors(
    depth: Int,
    chain: List<Boolean>,
    isLast: Boolean,
    color: Color,
) {
    if (depth <= 0) return
    val step = IndentStep.toPx()
    val stroke = 1.5.dp.toPx()
    val midY = size.height / 2f
    val elbowX = depth * step + 12.dp.toPx()

    for (level in 0 until depth) {
        val x = level * step + step / 2f
        if (level < depth - 1) {
            if (chain.getOrElse(level) { false }) {
                drawLine(color, Offset(x, 0f), Offset(x, size.height), stroke)
            }
        } else {
            drawLine(color, Offset(x, 0f), Offset(x, midY), stroke)
            drawLine(color, Offset(x, midY), Offset(elbowX, midY), stroke)
            if (!isLast) {
                drawLine(color, Offset(x, midY), Offset(x, size.height), stroke)
            }
        }
    }
}
