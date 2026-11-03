package me.rerere.rikkahub.ui.pages.history;

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Cancel01
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.comparison.COMPARISON_MAX_CONVERSATIONS
import me.rerere.rikkahub.data.model.comparison.canCompare
import me.rerere.rikkahub.data.model.comparison.toggleComparisonSelection
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.SwipeToDelete
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.navigateToComparison
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun HistoryPage(vm: HistoryVM = koinViewModel()) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable(
        stateSaver = listSaver<Set<Uuid>, String>(
            save = { selected -> selected.map(Uuid::toString) },
            restore = { saved -> saved.mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }.toSet() },
        )
    ) { mutableStateOf(emptySet<Uuid>()) }

    val conversations by vm.conversations.collectAsStateWithLifecycle()

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    fun toggleSelection(id: Uuid) {
        val next = toggleComparisonSelection(selectedIds, id)
        // 到上限时选择不变：明确告诉用户为什么点不动
        if (next == selectedIds && id !in selectedIds) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.comparison_limit, COMPARISON_MAX_CONVERSATIONS)
                )
            }
            return
        }
        selectedIds = next
    }

    BackHandler(enabled = selectionMode) {
        exitSelection()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (selectionMode) {
                        Text(stringResource(R.string.comparison_selected_count, selectedIds.size))
                    } else {
                        Text(stringResource(R.string.history_page_title))
                    }
                },
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = { exitSelection() }) {
                            Icon(
                                HugeIcons.Cancel01,
                                contentDescription = stringResource(R.string.history_page_cancel),
                            )
                        }
                    } else {
                        BackButton()
                    }
                },
                actions = {
                    if (!selectionMode) {
                        IconButton(
                            onClick = {
                                navController.navigate(Screen.MessageSearch)
                            }
                        ) {
                            Icon(
                                HugeIcons.GlobalSearch,
                                contentDescription = stringResource(R.string.history_page_search_messages)
                            )
                        }
                        IconButton(
                            onClick = {
                                showDeleteAllDialog = true
                            }
                        ) {
                            Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.history_page_delete_all))
                        }
                    }
                }
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        bottomBar = {
            if (selectionMode) {
                SelectionBottomBar(
                    selectedCount = selectedIds.size,
                    canCompare = canCompare(selectedIds),
                    onCompare = {
                        val ids = conversations.filter { it.id in selectedIds }.map { it.id }
                        if (ids.size >= 2) {
                            navigateToComparison(navController, ids)
                            exitSelection()
                        }
                    },
                )
            }
        }
    ) { contentPadding ->
        val snackMessageDeleted = stringResource(R.string.history_page_conversation_deleted)
        val snackMessageUndo = stringResource(R.string.history_page_undo)
        LazyColumn(
            contentPadding = contentPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(conversations, key = { it.id }) { conversation ->
                val selected = conversation.id in selectedIds
                val itemModifier = Modifier
                    .fillMaxWidth()
                    .animateItem()
                if (selectionMode) {
                    ConversationItem(
                        conversation = conversation,
                        selected = selected,
                        selectionMode = true,
                        onClick = { toggleSelection(conversation.id) },
                        onLongClick = { toggleSelection(conversation.id) },
                        modifier = itemModifier,
                    )
                } else {
                    SwipeableConversationItem(
                        conversation = conversation,
                        onClick = {
                            navigateToChatPage(navController, conversation.id)
                        },
                        onDelete = {
                            scope.launch {
                                // 先获取完整的对话数据（包含 messageNodes），用于撤销恢复
                                val fullConversation = vm.getFullConversation(conversation.id) ?: conversation
                                vm.deleteConversation(conversation)
                                val result = snackbarHostState.showSnackbar(
                                    message = snackMessageDeleted,
                                    actionLabel = snackMessageUndo,
                                    withDismissAction = true,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    vm.restoreConversation(fullConversation)
                                }
                            }
                        },
                        onTogglePin = { vm.togglePinStatus(conversation.id) },
                        onLongClick = {
                            selectionMode = true
                            selectedIds = setOf(conversation.id)
                        },
                        modifier = itemModifier,
                    )
                }
            }
        }
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text(stringResource(R.string.history_page_delete_all_conversations)) },
            text = { Text(stringResource(R.string.history_page_delete_all_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteAllConversations()
                        showDeleteAllDialog = false
                    }
                ) {
                    Text(stringResource(R.string.history_page_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteAllDialog = false }
                ) {
                    Text(stringResource(R.string.history_page_cancel))
                }
            }
        )
    }
}

@Composable
private fun SelectionBottomBar(
    selectedCount: Int,
    canCompare: Boolean,
    onCompare: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (canCompare) {
                    stringResource(R.string.comparison_selected_count, selectedCount)
                } else {
                    stringResource(R.string.comparison_select_hint)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = onCompare,
                enabled = canCompare,
            ) {
                Text(stringResource(R.string.comparison_compare_action))
            }
        }
    }
}

@Composable
private fun SwipeableConversationItem(
    conversation: Conversation,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    SwipeToDelete(
        onDelete = onDelete,
        modifier = modifier,
        shape = RoundedCornerShape(25),
    ) {
        ConversationItem(
            conversation = conversation,
            onTogglePin = onTogglePin,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(25),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
            onLongClickLabel = stringResource(R.string.comparison_select_mode),
        )
    ) {
        ListItem(
            headlineContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (conversation.isPinned) {
                        Icon(
                            imageVector = HugeIcons.Pin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.history_page_new_conversation) }
                            .trim(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            },
            supportingContent = {
                Text(conversation.createAt.toLocalDateTime())
            },
            trailingContent = {
                if (selectionMode) {
                    Checkbox(checked = selected, onCheckedChange = null)
                } else {
                    IconButton(
                        onClick = onTogglePin
                    ) {
                        Icon(
                            if (conversation.isPinned) HugeIcons.PinOff else HugeIcons.Pin,
                            contentDescription = if (conversation.isPinned) stringResource(R.string.history_page_unpin) else stringResource(
                                R.string.history_page_pin
                            )
                        )
                    }
                }
            }
        )
    }
}
