package me.rerere.rikkahub.ui.pages.comparison.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.comparison.ComparisonNode

/**
 * 对比画布的节点卡：角色、来源、正文、分叉描边、会话终点标记。
 *
 * 卡片不再写死高度（大字号下会切正文），由 [minHeight] 撑住画布的排布间距；
 * 头部标签数量有上限，超出的会话收成 `+N`，220dp 宽也不会溢出。
 */
@Composable
fun ComparisonNodeCard(
    node: ComparisonNode,
    endpoint: Boolean,
    maxLines: Int,
    modifier: Modifier = Modifier,
    minHeight: Dp = 0.dp,
    endpointTitles: List<String> = emptyList(),
    onEndpointClick: (() -> Unit)? = null,
    onClick: () -> Unit = {},
) {
    if (node.message.isContextCheckpoint) {
        ComparisonCheckpointCard(
            node = node,
            endpoint = endpoint,
            maxLines = maxLines,
            modifier = modifier,
            minHeight = minHeight,
            endpointTitles = endpointTitles,
            onEndpointClick = onEndpointClick,
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
        modifier = modifier.heightIn(min = minHeight),
        shape = RoundedCornerShape(14.dp),
        color = container,
        contentColor = content,
        border = if (isFork) BorderStroke(1.dp, colors.primary) else null,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            CardHeader(
                node = node,
                endpoint = endpoint,
                isFork = isFork,
                endpointTitles = endpointTitles,
                onEndpointClick = onEndpointClick,
                content = content,
            )
            NodeBody(
                node = node,
                maxLines = maxLines,
                content = content,
            )
        }
    }
}

@Composable
private fun CardHeader(
    node: ComparisonNode,
    endpoint: Boolean,
    isFork: Boolean,
    endpointTitles: List<String>,
    onEndpointClick: (() -> Unit)?,
    content: Color,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = comparisonRoleLabel(node.message.role),
            style = MaterialTheme.typography.labelSmall,
            color = content.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (node.occurrences.size > 1) {
            Text(
                text = stringResource(R.string.comparison_shared_by, node.occurrences.size),
                style = MaterialTheme.typography.labelSmall,
                color = content.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
            EndpointBadge(titles = endpointTitles, onClick = onEndpointClick)
        }
    }

    SourceTitles(node = node, content = content)
}

/** 卡片来源标签：最多三条，其余收成 `+N`，避免会话标题把卡片挤爆。 */
@Composable
private fun SourceTitles(
    node: ComparisonNode,
    content: Color,
) {
    val sourceTitles = node.occurrences
        .map { occurrence ->
            occurrence.conversationTitle.ifBlank { stringResource(R.string.comparison_untitled) }
        }
        .distinct()
    if (sourceTitles.isEmpty()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        sourceTitles.take(3).forEach { title ->
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = content.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (sourceTitles.size > 3) {
            Text(
                text = "+" + (sourceTitles.size - 3),
                style = MaterialTheme.typography.labelSmall,
                color = content.copy(alpha = 0.55f),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun NodeBody(
    node: ComparisonNode,
    maxLines: Int,
    content: Color,
) {
    val body = comparisonNodeText(node)

    Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        color = content,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 压缩检查点单独成形，不按普通气泡画。 */
@Composable
private fun ComparisonCheckpointCard(
    node: ComparisonNode,
    endpoint: Boolean,
    maxLines: Int,
    modifier: Modifier = Modifier,
    minHeight: Dp = 0.dp,
    endpointTitles: List<String> = emptyList(),
    onEndpointClick: (() -> Unit)? = null,
    onClick: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val content = colors.onTertiaryContainer
    val isFork = node.childIds.size > 1
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = minHeight),
        shape = RoundedCornerShape(14.dp),
        color = colors.tertiaryContainer,
        contentColor = content,
        // 检查点也可能是分叉点：同样描边，别让它看起来像普通正文
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
                Text(
                    text = stringResource(R.string.comparison_checkpoint_title),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
                    EndpointBadge(titles = endpointTitles, onClick = onEndpointClick)
                }
            }
            SourceTitles(node = node, content = content)
            NodeBody(
                node = node,
                maxLines = maxLines,
                content = content,
            )
        }
    }
}

/**
 * 子树被折叠时的占位：卡片本身照常画，chip 放在子节点列，点一次展开整棵子树。
 *
 * @param hiddenCount 被藏起来的后代数量（子树大小 - 1）
 */
@Composable
fun ComparisonFoldChip(
    hiddenCount: Int,
    modifier: Modifier = Modifier,
    endpoint: Boolean = false,
    fork: Boolean = false,
    /** 自定义文案（线性长链的中段压缩用）；null 时显示「+N 条后续」。 */
    label: String? = null,
    onClick: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = colors.surfaceVariant,
        contentColor = colors.onSurfaceVariant,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label ?: stringResource(R.string.comparison_fold_more, hiddenCount),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            if (fork) {
                Text(
                    text = stringResource(R.string.comparison_fork),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary,
                    maxLines = 1,
                )
            }
            if (endpoint) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(colors.tertiary, CircleShape)
                )
            }
        }
    }
}

/**
 * 终点徽标要说清「谁在此结束」：单条显示「<会话名> 在此结束」，多条只显示数量，
 * 点一下由页面弹出名字列表。
 */
@Composable
private fun EndpointBadge(
    titles: List<String>,
    onClick: (() -> Unit)? = null,
) {
    val color = MaterialTheme.colorScheme.tertiary
    val untitled = stringResource(R.string.comparison_untitled)
    val label = when {
        titles.isEmpty() -> stringResource(R.string.comparison_endpoint)
        titles.size == 1 -> stringResource(
            R.string.comparison_endpoint_here,
            titles.single().ifBlank { untitled },
        )

        else -> stringResource(R.string.comparison_endpoint_many, titles.size)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .widthIn(max = 150.dp)
            .then(
                if (onClick != null && titles.size > 1) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            ),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color, CircleShape)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
    // toText() 会拼接全部 part；视口每帧重组时不能在组合里重算
    val text = remember(message) { message.toText().trim() }
    if (text.isNotEmpty()) return text

    val reasoning = remember(message) {
        message.parts
            .filterIsInstance<UIMessagePart.Reasoning>()
            .map { it.reasoning.trim() }
            .firstOrNull { it.isNotEmpty() }
    }
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
