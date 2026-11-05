package me.rerere.rikkahub.ui.pages.comparison

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.comparison.ComparisonCandidate
import kotlin.uuid.Uuid

/**
 * 追加会话的底部弹层：列出当前助手的可见会话，已在对比里的不再重复列出。
 *
 * 数据源与会话列表页一致（当前助手、不含归档与回收站）。
 */
@Composable
fun ComparisonPickerSheet(
    conversations: List<ComparisonCandidate>,
    selected: Set<Uuid>,
    enabled: Boolean = true,
    onToggle: (Uuid) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.comparison_picker_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            if (conversations.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.comparison_picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(conversations, key = { it.id }) { conversation ->
                        val checked = conversation.id in selected
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = conversation.title.ifBlank {
                                        stringResource(R.string.comparison_untitled)
                                    },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                Checkbox(checked = checked, onCheckedChange = null)
                            },
                            modifier = Modifier.clickable(enabled = enabled) {
                                onToggle(conversation.id)
                            },
                        )
                    }
                }
            }
        }
    }
}
