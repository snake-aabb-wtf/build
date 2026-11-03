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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.comparison.COMPARISON_MIN_CONVERSATIONS
import me.rerere.rikkahub.data.model.comparison.ComparisonNode
import me.rerere.rikkahub.data.model.comparison.NodeOccurrence
import me.rerere.rikkahub.data.model.comparison.conversationEndpoints
import me.rerere.rikkahub.data.model.comparison.isLargeComparison
import me.rerere.rikkahub.data.model.comparison.sourceOccurrences
import me.rerere.rikkahub.data.model.comparison.subtreeSizes
import me.rerere.rikkahub.data.model.comparison.tidyLayout
import me.rerere.rikkahub.data.model.comparison.timelineLayout
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.comparison.views.CanvasTreeView
import me.rerere.rikkahub.ui.pages.comparison.views.ComparisonMetrics
import me.rerere.rikkahub.ui.pages.comparison.views.IndentedTreeView
import me.rerere.rikkahub.ui.pages.comparison.views.TimelineView
import me.rerere.rikkahub.ui.pages.comparison.views.comparisonNodeText
import me.rerere.rikkahub.ui.pages.comparison.views.comparisonRoleLabel
import me.rerere.rikkahub.utils.pushToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

// 布局用的列宽/行高由 ComparisonMetrics 统一提供，三个视图共用一份数字

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
    val metrics = remember { ComparisonMetrics() }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is ComparisonEvent.ConversationsMissing -> snackbarHostState.showSnackbar(
                    context.getString(R.string.comparison_missing, event.count)
                )
            }
        }
    }

    fun openOccurrence(occurrence: NodeOccurrence) {
        scope.launch {
            if (vm.verifyConversation(occurrence.conversationId)) {
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

    val selectedIds = remember(state.conversations) {
        state.conversations.map { it.id }.toSet()
    }

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
            ComparisonToolbar(
                conversations = state.conversations,
                viewMode = state.viewMode,
                onViewModeChange = vm::setViewMode,
                onRemove = vm::removeConversation,
                onAdd = onAddConversations ?: { showPicker = true },
            )
            val graph = state.graph
            if (graph != null &&
                state.conversations.size > 1 &&
                graph.rootIds.size == state.conversations.size
            ) {
                // 各自从不同的第一条消息开始：不合并，提示一下免得看起来像画错了
                Text(
                    text = stringResource(R.string.comparison_no_common_prefix),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (graph != null && isLargeComparison(graph)) {
                // 只提示不拦：A 视图永不折叠，几千条消息也能逐条读
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
                onExpand = vm::expand,
                onNodeClick = handleNodeClick,
                onCanvasViewportChange = vm::setCanvasViewport,
                onTimelineViewportChange = vm::setTimelineViewport,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        }
    }

    if (showPicker) {
        ComparisonPickerSheet(
            conversations = candidates,
            selected = selectedIds,
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
    viewMode: ComparisonViewMode,
    onViewModeChange: (ComparisonViewMode) -> Unit,
    onRemove: (Uuid) -> Unit,
    onAdd: (() -> Unit)?,
) {
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
            conversations.forEach { summary ->
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
                                .clickable { onRemove(summary.id) },
                        )
                    },
                )
            }
            if (onAdd != null) {
                AssistChip(
                    onClick = onAdd,
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

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val modes = ComparisonViewMode.entries
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = viewMode == mode,
                    onClick = { onViewModeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                ) {
                    Text(mode.label())
                }
            }
        }
    }
}

@Composable
private fun ComparisonContent(
    state: ComparisonUiState,
    metrics: ComparisonMetrics,
    onExpand: (String) -> Unit,
    onNodeClick: (ComparisonNode) -> Unit,
    onCanvasViewportChange: (ComparisonViewport) -> Unit,
    onTimelineViewportChange: (ComparisonViewport) -> Unit,
    modifier: Modifier = Modifier,
) {
    val graph = state.graph
    when {
        graph == null && state.loading -> ComparisonLoading(
            hashing = state.hashing,
            modifier = modifier,
        )

        graph == null -> ComparisonPlaceholder(
            text = stringResource(
                if (state.failed) R.string.comparison_failed else R.string.comparison_empty
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
                val endpoints = remember(graph) { conversationEndpoints(graph) }
                when (state.viewMode) {
                    ComparisonViewMode.INDENTED -> IndentedTreeView(
                        graph = graph,
                        endpointIds = endpoints,
                        metrics = metrics,
                        expanded = state.expanded,
                        onExpand = onExpand,
                        onNodeClick = onNodeClick,
                        modifier = modifier,
                    )

                    ComparisonViewMode.CANVAS -> CanvasTreeView(
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
                    )

                    ComparisonViewMode.TIMELINE -> {
                        val timeline = remember(graph, metrics) {
                            timelineLayout(
                                graph,
                                metrics.timelineColumnWidth.value,
                                metrics.timelineLaneHeight.value,
                            )
                        }
                        TimelineView(
                            graph = graph,
                            layout = timeline,
                            sizes = sizes,
                            endpointIds = endpoints,
                            expanded = state.expanded,
                            viewport = state.timelineViewport,
                            onViewportChange = onTimelineViewportChange,
                            onExpand = onExpand,
                            onNodeClick = onNodeClick,
                            metrics = metrics,
                            modifier = modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparisonLoading(
    hashing: Boolean,
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
                text = stringResource(
                    if (hashing) R.string.comparison_hashing else R.string.comparison_loading
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

@Composable
private fun ComparisonViewMode.label(): String = when (this) {
    ComparisonViewMode.INDENTED -> stringResource(R.string.comparison_view_indented)
    ComparisonViewMode.CANVAS -> stringResource(R.string.comparison_view_canvas)
    ComparisonViewMode.TIMELINE -> stringResource(R.string.comparison_view_timeline)
}
