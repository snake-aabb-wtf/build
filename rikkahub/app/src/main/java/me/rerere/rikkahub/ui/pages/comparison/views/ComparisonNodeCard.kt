package me.rerere.rikkahub.ui.pages.comparison.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.comparison.ComparisonGraph
import me.rerere.rikkahub.data.model.comparison.ComparisonNode

/** 三种视图共用的节点卡：角色、正文、分叉描边、会话终点标记。 */
@Composable
fun ComparisonNodeCard(
    node: ComparisonNode,
    endpoint: Boolean,
    maxLines: Int,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    onClick: () -> Unit = {},
) {
    if (node.message.isContextCheckpoint) {
        ComparisonCheckpointCard(
            node = node,
            endpoint = endpoint,
            maxLines = maxLines,
            modifier = modifier,
            onClick = onClick,
        )
        return
    }

    val colors = MaterialTheme.colorScheme
    val isUser = node.message.role == MessageRole.USER
    val isFork = node.childIds.size > 1
    val container = if (isUser) colors.primaryContainer else colors.surfaceContainerHigh
    val content = if (isUser) colors.onPrimaryContainer else colors.onSurface

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = container,
        contentColor = content,
        border = if (isFork) BorderStroke(1.dp, colors.primary) else null,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (accent != null) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(accent, CircleShape)
                    )
                }
                Text(
                    text = comparisonRoleLabel(node.message.role),
                    style = MaterialTheme.typography.labelSmall,
                    color = content.copy(alpha = 0.7f),
                    maxLines = 1,
                )
                if (node.occurrences.size > 1) {
                    Text(
                        text = stringResource(R.string.comparison_shared_by, node.occurrences.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = content.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (isFork) {
                    Text(
                        text = stringResource(R.string.comparison_fork),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.primary,
                        maxLines = 1,
                    )
                }
                if (endpoint) {
                    EndpointBadge()
                }
            }
            Text(
                text = comparisonNodeText(node),
                style = MaterialTheme.typography.bodyMedium,
                color = content,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 压缩检查点单独成形，不按普通气泡画。 */
@Composable
private fun ComparisonCheckpointCard(
    node: ComparisonNode,
    endpoint: Boolean,
    maxLines: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = colors.tertiaryContainer,
        contentColor = colors.onTertiaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.comparison_checkpoint_title),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                if (endpoint) EndpointBadge()
            }
            Text(
                text = comparisonNodeText(node),
                style = MaterialTheme.typography.bodySmall,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 子树被折叠时的占位：点开后再画子树。 */
@Composable
fun ComparisonFoldChip(
    hiddenCount: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = stringResource(R.string.comparison_fold_more, hiddenCount),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun EndpointBadge() {
    val color = MaterialTheme.colorScheme.tertiary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color, CircleShape)
        )
        Text(
            text = stringResource(R.string.comparison_endpoint),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
fun comparisonRoleLabel(role: MessageRole): String = when (role) {
    MessageRole.USER -> stringResource(R.string.comparison_role_user)
    MessageRole.ASSISTANT -> stringResource(R.string.comparison_role_assistant)
    MessageRole.SYSTEM -> stringResource(R.string.comparison_role_system)
    MessageRole.TOOL -> stringResource(R.string.comparison_role_tool)
}

/** 卡片上显示的正文：优先可见文本，其次思考内容，最后按附件/工具类型给个占位。 */
@Composable
fun comparisonNodeText(node: ComparisonNode): String {
    val message = node.message
    val text = message.toText().trim()
    if (text.isNotEmpty()) return text

    val reasoning = message.parts
        .filterIsInstance<UIMessagePart.Reasoning>()
        .map { it.reasoning.trim() }
        .firstOrNull { it.isNotEmpty() }
    if (reasoning != null) return reasoning

    val part = message.parts.firstOrNull { it !is UIMessagePart.Text }
    return when (part) {
        is UIMessagePart.Image -> stringResource(R.string.comparison_part_image)
        is UIMessagePart.Video -> stringResource(R.string.comparison_part_video)
        is UIMessagePart.Audio -> stringResource(R.string.comparison_part_audio)
        is UIMessagePart.Document -> stringResource(R.string.comparison_part_document, part.fileName)
        is UIMessagePart.Tool -> stringResource(R.string.comparison_part_tool, part.toolName)
        is UIMessagePart.ServerTool -> stringResource(R.string.comparison_part_tool, part.toolName)
        is UIMessagePart.ToolCall -> stringResource(R.string.comparison_part_tool, part.toolName)
        is UIMessagePart.ToolResult -> stringResource(R.string.comparison_part_tool, part.toolName)
        is UIMessagePart.Reasoning -> stringResource(R.string.comparison_part_reasoning)
        else -> stringResource(R.string.comparison_part_empty)
    }
}

/** 折叠阈值：子树大于这个节点数的节点在画布/时间线里先收成 `+N` 占位。 */
internal const val FOLD_SUBTREE_THRESHOLD = 300

/** 子树超过阈值且没被展开的节点。 */
internal fun foldedNodeIds(
    graph: ComparisonGraph,
    sizes: Map<String, Int>,
    expanded: Set<String>,
): Set<String> = sizes
    .filter { (id, size) -> size > FOLD_SUBTREE_THRESHOLD && id !in expanded && graph.nodes.containsKey(id) }
    .keys

/** 折叠后仍然可见的节点：折叠节点的后代不参与绘制。 */
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
