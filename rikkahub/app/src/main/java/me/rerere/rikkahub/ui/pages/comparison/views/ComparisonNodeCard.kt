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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * 三种视图共用的节点卡：角色、来源、正文、分叉描边、会话终点标记。
 *
 * 卡片不再写死高度（大字号下会切正文），由 [minHeight] 撑住画布/时间线的排布间距；
 * 头部标签数量有上限，超出的会话收成 `+N`，220dp 宽也不会溢出。
 */
@Composable
fun ComparisonNodeCard(
    node: ComparisonNode,
    endpoint: Boolean,
    maxLines: Int,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    minHeight: Dp = 0.dp,
    expandable: Boolean = false,
    depth: Int? = null,
    onClick: () -> Unit = {},
) {
    if (node.message.isContextCheckpoint) {
        ComparisonCheckpointCard(
            node = node,
            endpoint = endpoint,
            maxLines = maxLines,
            modifier = modifier,
            minHeight = minHeight,
            expandable = expandable,
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
                accent = accent,
                depth = depth,
                content = content,
            )
            NodeBody(
                node = node,
                maxLines = maxLines,
                expandable = expandable,
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
    accent: Color?,
    depth: Int?,
    content: Color,
) {
    val colors = MaterialTheme.colorScheme
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
        if (depth != null && depth > 0) {
            Text(
                text = stringResource(R.string.comparison_depth, depth),
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

    val sourceTitles = node.occurrences
        .map { occurrence ->
            occurrence.conversationTitle.ifBlank { stringResource(R.string.comparison_untitled) }
        }
        .distinct()
    if (sourceTitles.isNotEmpty()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 来源标签最多两个，剩下收成 +N，避免标题把卡片挤爆
            sourceTitles.take(2).forEach { title ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = content.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (sourceTitles.size > 2) {
                Text(
                    text = "+" + (sourceTitles.size - 2),
                    style = MaterialTheme.typography.labelSmall,
                    color = content.copy(alpha = 0.55f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun NodeBody(
    node: ComparisonNode,
    maxLines: Int,
    expandable: Boolean,
    content: Color,
) {
    var expanded by remember(node.id) { mutableStateOf(false) }
    var overflowed by remember(node.id) { mutableStateOf(false) }
    val body = comparisonNodeText(node)

    Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        color = content,
        maxLines = if (expanded) Int.MAX_VALUE else maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout ->
            if (!expanded) overflowed = layout.hasVisualOverflow
        },
    )
    if (expandable && (overflowed || expanded)) {
        Text(
            text = stringResource(
                if (expanded) R.string.comparison_collapse_text else R.string.comparison_expand_text
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { expanded = !expanded },
        )
    }
}

/** 压缩检查点单独成形，不按普通气泡画。 */
@Composable
private fun ComparisonCheckpointCard(
    node: ComparisonNode,
    endpoint: Boolean,
    maxLines: Int,
    modifier: Modifier = Modifier,
    minHeight: Dp = 0.dp,
    expandable: Boolean = false,
    onClick: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = minHeight),
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
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                if (endpoint) EndpointBadge()
            }
            NodeBody(
                node = node,
                maxLines = maxLines,
                expandable = expandable,
                content = colors.onTertiaryContainer,
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
                text = stringResource(R.string.comparison_fold_more, hiddenCount),
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
