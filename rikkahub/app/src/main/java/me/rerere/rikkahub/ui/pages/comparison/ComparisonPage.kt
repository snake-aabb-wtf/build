package me.rerere.rikkahub.ui.pages.comparison

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.comparison.COMPARISON_MIN_CONVERSATIONS
import me.rerere.rikkahub.data.model.comparison.ComparisonGraph
import me.rerere.rikkahub.data.model.comparison.ComparisonNode
import me.rerere.rikkahub.data.model.comparison.NodeOccurrence
import me.rerere.rikkahub.data.model.comparison.availableCandidates
import me.rerere.rikkahub.data.model.comparison.conversationEndpointTitles
import me.rerere.rikkahub.data.model.comparison.isLargeComparison
import me.rerere.rikkahub.data.model.comparison.isLargeSelection
import me.rerere.rikkahub.data.model.comparison.sourceOccurrences
import me.rerere.rikkahub.data.model.comparison.subtreeSizes
import me.rerere.rikkahub.data.model.comparison.tidyLayout
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.comparison.views.CanvasTreeView
import me.rerere.rikkahub.ui.pages.comparison.views.ComparisonMetrics
import me.rerere.rikkahub.ui.pages.comparison.views.comparisonNodeText
import me.rerere.rikkahub.ui.pages.comparison.views.comparisonRoleLabel
import me.rerere.rikkahub.utils.pushToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

// 布局和画布卡片的列宽/行高由 ComparisonMetrics 统一提供

@Composable
fun ComparisonPage(
    ids: List<String>,
    onAddConversations: (() -> Unit)? = null,
    onNodeClick: ((ComparisonNode) -> Unit)? = null,
    vm: ComparisonVM = koinViewModel(parameters = { parametersOf(ids) }),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val candidates by vm.candidates.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showPicker by remember { mutableStateOf(false) }
    var occurrenceNode by remember { mutableStateOf<ComparisonNode?>(null) }
    var endpointSheetTitles by remember { mutableStateOf<List<String>?>(null) }
    val metrics = remember { ComparisonMetrics() }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is ComparisonEvent.ConversationsMissing -> snackbarHostState.showSnackbar(
                    context.getString(R.string.comparison_missing, event.count)
                )

                ComparisonEvent.MessageMissing -> snackbarHostState.showSnackbar(
                    context.getString(R.string.comparison_message_missing)
                )
            }
        }
    }

    // 从聊天页返回（或应用回到前台）时重读会话。切分支/编辑消息不改 updateAt，
    // 真正判断靠 VM 里的分支签名；这里只保证「本页可见」时才触发。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            ) {
                vm.syncWithCandidates(vm.candidates.value)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 返回瞬间候选列表可能还没重新发射，等它到齐再对一次签名
    LaunchedEffect(candidates) {
        vm.syncWithCandidates(candidates)
    }

    fun openOccurrence(occurrence: NodeOccurrence) {
        scope.launch {
            // 会话不在或节点在聊天页被删掉时由 VM 发事件提示，这里不导航
            if (vm.verifyOccurrence(occurrence.conversationId, occurrence.messageNodeId)) {
                pushToChatPage(navController, occurrence.conversationId, occurrence.messageNodeId)
            }
        }
    }

    // 单来源直接跳；多来源先让用户选哪个会话。push 导航，返回时对比页状态还在。
    val handleNodeClick: (ComparisonNode) -> Unit = onNodeClick ?: { node ->
        val sources = sourceOccurrences(node)
        when {
            sources.size == 1 -> openOccurrence(sources.single())
            sources.size > 1 -> occurrenceNode = node
        }
    }

    val selectedIds = state.selectedIds

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.comparison_page_title))
                        val graph = state.graph
                        if (graph != null) {
                            Text(
                                text = stringResource(
                                    R.string.comparison_summary,
                                    graph.nodeCount,
                                    state.conversations.size,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(onClick = vm::refresh, enabled = !state.loading) {
                        Icon(
                            imageVector = HugeIcons.Refresh01,
                            contentDescription = stringResource(R.string.comparison_refresh),
                        )
                    }
                },
            )
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            // 逐字比较的说明放在最顶上：用户需要先知道「不相似度合并」这个前提
            Text(
                text = stringResource(R.string.comparison_explainer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (isLargeSelection(state.selectedIds.size)) {
                Text(
                    text = stringResource(R.string.comparison_many_conversations),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            ComparisonToolbar(
                conversations = state.conversations,
                enabled = !state.loading,
                onRemove = vm::removeConversation,
                onAdd = onAddConversations ?: { showPicker = true },
            )
            val graph = state.graph
            if (graph != null && state.noCommonStart) {
                // 各自从不同的第一条消息开始：不合并，提示一下免得看起来像画错了
                Text(
                    text = stringResource(R.string.comparison_no_common_prefix),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (graph != null && isLargeComparison(graph)) {
                // 只提示不拦：通过画布的缩放、平移和展开查看大图
                Text(
                    text = stringResource(R.string.comparison_large_graph),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            ComparisonContent(
                state = state,
                metrics = metrics,
                onCancelLoading = vm::cancelLoading,
                onExpand = vm::expand,
                onExpandSegment = vm::expandSegment,
                onNodeClick = handleNodeClick,
                onEndpointClick = { node ->
                    endpointSheetTitles = endpointTitlesOf(state.graph, node)
                },
                onCanvasViewportChange = vm::setCanvasViewport,
                onCanvasFitted = vm::markCanvasFitted,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        }
    }

    if (showPicker) {
        // 列出全部未选中的会话
        val options = remember(candidates, selectedIds) {
            availableCandidates(candidates, selectedIds)
        }
        ComparisonPickerSheet(
            conversations = options,
            selected = selectedIds,
            enabled = !state.loading,
            onToggle = { id ->
                if (id in selectedIds) vm.removeConversation(id) else vm.addConversation(id)
            },
            onDismiss = { showPicker = false },
        )
    }

    val sheetNode = occurrenceNode
    if (sheetNode != null) {
        ModalBottomSheet(onDismissRequest = { occurrenceNode = null }) {
            OccurrencePickerSheet(
                node = sheetNode,
                onPick = { occurrence ->
                    occurrenceNode = null
                    openOccurrence(occurrence)
                },
            )
        }
    }

    // 「N 个会话在此结束」点开后列名字
    val endpointTitles = endpointSheetTitles
    if (endpointTitles != null) {
        ModalBottomSheet(onDismissRequest = { endpointSheetTitles = null }) {
            EndpointTitlesSheet(
                titles = endpointTitles,
                onDismiss = { endpointSheetTitles = null },
            )
        }
    }
}

@Composable
private fun EndpointTitlesSheet(
    titles: List<String>,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.comparison_endpoint_list_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp),
        ) {
            // 会话标题可能重名，不能用标题做 key
            items(titles.size) { index ->
                val title = titles[index]
                ListItem(
                    headlineContent = {
                        Text(
                            text = title.ifBlank { stringResource(R.string.comparison_untitled) },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }
        }
    }
}

@Composable
private fun OccurrencePickerSheet(
    node: ComparisonNode,
    onPick: (NodeOccurrence) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.comparison_choose_source),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp),
        ) {
            items(sourceOccurrences(node), key = { it.conversationId.toString() }) { occurrence ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = occurrence.conversationTitle.ifBlank {
                                stringResource(R.string.comparison_untitled)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = comparisonRoleLabel(node.message.role) + " · " + comparisonNodeText(node),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.clickable { onPick(occurrence) },
                )
            }
        }
    }
}

@Composable
private fun ComparisonToolbar(
    conversations: List<ComparisonConversationSummary>,
    enabled: Boolean,
    onRemove: (Uuid) -> Unit,
    onAdd: (() -> Unit)?,
) {
    // 超过 4 个会话时先只露 3 个 chip，避免把图挤到只剩一条缝
    var chipsExpanded by rememberSaveable { mutableStateOf(false) }
    val collapsed = conversations.size > 4 && !chipsExpanded
    val shown = if (collapsed) conversations.take(3) else conversations

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            shown.forEach { summary ->
                val title = if (summary.title.isBlank()) {
                    stringResource(R.string.comparison_untitled)
                } else {
                    summary.title
                }
                val label = if (summary.messageCount == 0) {
                    "$title · ${stringResource(R.string.comparison_no_messages)}"
                } else {
                    title
                }
                InputChip(
                    selected = false,
                    onClick = {},
                    modifier = Modifier.widthIn(max = 240.dp),
                    label = {
                        Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    trailingIcon = {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.comparison_remove),
                            modifier = Modifier
                                .size(16.dp)
                                .clickable(enabled = enabled) { onRemove(summary.id) },
                        )
                    },
                )
            }
            if (conversations.size > 4) {
                AssistChip(
                    onClick = { chipsExpanded = !chipsExpanded },
                    label = {
                        Text(
                            if (collapsed) {
                                stringResource(R.string.comparison_more_chips, conversations.size - 3)
                            } else {
                                stringResource(R.string.comparison_collapse_text)
                            }
                        )
                    },
                )
            }
            if (onAdd != null) {
                AssistChip(
                    onClick = onAdd,
                    enabled = enabled,
                    label = { Text(stringResource(R.string.comparison_add)) },
                    leadingIcon = {
                        Icon(
                            imageVector = HugeIcons.Add01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }
        }
    }
}

/** 只列「真正在这个节点结束」的会话，路过的会话不算。 */
private fun endpointTitlesOf(graph: ComparisonGraph?, node: ComparisonNode): List<String> =
    graph?.let { conversationEndpointTitles(it)[node.id] }.orEmpty()

@Composable
private fun ComparisonContent(
    state: ComparisonUiState,
    metrics: ComparisonMetrics,
    onCancelLoading: () -> Unit,
    onExpand: (String) -> Unit,
    onExpandSegment: (String) -> Unit,
    onNodeClick: (ComparisonNode) -> Unit,
    onEndpointClick: (ComparisonNode) -> Unit,
    onCanvasViewportChange: (ComparisonViewport) -> Unit,
    onCanvasFitted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val graph = state.graph
    when {
        graph == null && state.loading -> ComparisonLoading(
            hashing = state.hashing,
            loadedCount = state.loadedCount,
            totalCount = state.totalCount,
            onCancel = onCancelLoading,
            modifier = modifier,
        )

        graph == null -> ComparisonPlaceholder(
            text = stringResource(
                when {
                    state.failed -> R.string.comparison_failed
                    state.cancelled -> R.string.comparison_cancelled
                    else -> R.string.comparison_empty
                }
            ),
            modifier = modifier,
        )

        state.conversations.size < COMPARISON_MIN_CONVERSATIONS -> ComparisonPlaceholder(
            text = stringResource(R.string.comparison_need_two),
            modifier = modifier,
        )

        else -> {
            val tidy = remember(graph, metrics) {
                tidyLayout(graph, metrics.columnWidth.value, metrics.rowHeight.value)
            }
            if (tidy.points.isEmpty()) {
                ComparisonPlaceholder(
                    text = stringResource(R.string.comparison_empty),
                    modifier = modifier,
                )
            } else {
                val sizes = remember(graph) { subtreeSizes(graph) }
                val endpointTitles = remember(graph) { conversationEndpointTitles(graph) }
                val endpoints = endpointTitles.keys
                // 画布按像素 offset 定位，RTL 会把偏移镜像到屏幕外；内部固定 LTR
                CompositionLocalProvider(
                    LocalLayoutDirection provides LayoutDirection.Ltr
                ) {
                    CanvasTreeView(
                        graph = graph,
                        layout = tidy,
                        sizes = sizes,
                        endpointIds = endpoints,
                        expanded = state.expanded,
                        viewport = state.canvasViewport,
                        onViewportChange = onCanvasViewportChange,
                        onExpand = onExpand,
                        onNodeClick = onNodeClick,
                        metrics = metrics,
                        modifier = modifier,
                        endpointTitles = endpointTitles,
                        onEndpointClick = onEndpointClick,
                        fitted = state.canvasFitted,
                        onFitted = onCanvasFitted,
                        onExpandSegment = onExpandSegment,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComparisonLoading(
    hashing: Boolean,
    loadedCount: Int,
    totalCount: Int,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = when {
                    hashing -> stringResource(R.string.comparison_hashing)
                    totalCount > 0 -> stringResource(
                        R.string.comparison_loading_progress,
                        loadedCount,
                        totalCount,
                    )

                    else -> stringResource(R.string.comparison_loading)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.comparison_cancel))
            }
        }
    }
}

@Composable
private fun ComparisonPlaceholder(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
