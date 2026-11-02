package me.rerere.rikkahub.ui.pages.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.toLocalDate
import org.koin.androidx.compose.koinViewModel
import java.time.Instant

@Composable
fun TrashPage(vm: TrashVM = koinViewModel()) {
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val count by vm.count.collectAsStateWithLifecycle()
    val assistants by vm.assistants.collectAsStateWithLifecycle()

    var showEmptyDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.trash_page_title_plain))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(
                        onClick = { showEmptyDialog = true },
                        enabled = count > 0,
                    ) {
                        Icon(
                            HugeIcons.Delete02,
                            contentDescription = stringResource(R.string.history_page_delete_all),
                        )
                    }
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
                    text = stringResource(R.string.trash_page_empty),
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
                    TrashedConversationItem(
                        conversation = conversation,
                        assistantName = vm.assistantName(conversation.assistantId, assistants),
                        onRestore = { vm.restore(conversation) },
                        onDeleteForever = { pendingDelete = conversation },
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                    )
                }
            }
        }
    }

    if (showEmptyDialog) {
        AlertDialog(
            onDismissRequest = { showEmptyDialog = false },
            title = { Text(stringResource(R.string.trash_page_title_plain)) },
            text = { Text(stringResource(R.string.trash_page_empty_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.empty()
                        showEmptyDialog = false
                    }
                ) {
                    Text(stringResource(R.string.trash_page_delete_forever))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    pendingDelete?.let { conversation ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = {
                Text(
                    conversation.title
                        .ifBlank { stringResource(R.string.chat_page_new_message) }
                        .trim()
                )
            },
            text = { Text(stringResource(R.string.trash_page_delete_forever_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteForever(conversation.id)
                        pendingDelete = null
                    }
                ) {
                    Text(stringResource(R.string.trash_page_delete_forever))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun TrashedConversationItem(
    conversation: Conversation,
    assistantName: String?,
    modifier: Modifier = Modifier,
    onRestore: () -> Unit = {},
    onDeleteForever: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }
    val deletedAt = conversation.deletedAt
        .takeIf { it > 0L }
        ?.let { Instant.ofEpochMilli(it).toLocalDate() }

    // 回收站里的会话不响应点击打开，只能恢复或永久删除
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(25),
        modifier = modifier,
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = conversation.title
                        .ifBlank { stringResource(R.string.chat_page_new_message) }
                        .trim(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            supportingContent = {
                Column {
                    Text(
                        text = assistantName
                            ?: stringResource(R.string.trash_page_unknown_assistant),
                    )
                    if (deletedAt != null) {
                        Text(
                            text = deletedAt,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                            text = { Text(stringResource(R.string.trash_page_delete_forever)) },
                            onClick = {
                                showMenu = false
                                onDeleteForever()
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
