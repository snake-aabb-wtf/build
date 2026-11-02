package me.rerere.rikkahub.ui.pages.archive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.toLocalDate
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.Instant
import kotlin.uuid.Uuid

@Composable
fun ArchivePage(assistantId: String) {
    val vm: ArchiveVM = koinViewModel(
        parameters = {
            parametersOf(assistantId)
        }
    )
    ArchivePage(vm = vm)
}

@Composable
private fun ArchivePage(vm: ArchiveVM) {
    val navController = LocalNavController.current
    val conversations by vm.conversations.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.chat_page_archived_title))
                },
                navigationIcon = {
                    BackButton()
                },
            )
        }
    ) { contentPadding ->
        if (conversations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.chat_page_archive_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = contentPadding + PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(conversations, key = { it.id }) { conversation ->
                    ArchivedConversationItem(
                        conversation = conversation,
                        onClick = {
                            // 归档会话从归档页打开时保持归档状态，不在打开时自动恢复
                            navigateToChatPage(navController, conversation.id)
                        },
                        onRestore = { vm.restore(conversation.id) },
                        onDelete = { vm.delete(conversation.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                    )
                }
            }
        }
    }
}

@Composable
private fun ArchivedConversationItem(
    conversation: Conversation,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onRestore: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }
    val archivedAt = conversation.archivedAt
        .takeIf { it > 0L }
        ?.let { Instant.ofEpochMilli(it).toLocalDate() }

    Surface(
        onClick = onClick,
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(25),
        modifier = modifier,
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_message) }.trim(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            supportingContent = {
                if (archivedAt != null) {
                    Text(archivedAt)
                }
            },
            trailingContent = {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            HugeIcons.MoreVertical,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.trash_page_restore)) },
                            onClick = {
                                showMenu = false
                                onRestore()
                            },
                            leadingIcon = {
                                Icon(HugeIcons.ArrowTurnBackward, null)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_page_delete)) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            },
                            leadingIcon = {
                                Icon(HugeIcons.Delete01, null)
                            },
                        )
                    }
                }
            },
        )
    }
}
