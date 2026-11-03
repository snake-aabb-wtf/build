package me.rerere.rikkahub.ui.pages.comparison.views

import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.comparison.FileHasher
import me.rerere.rikkahub.data.model.comparison.LayoutPoint
import me.rerere.rikkahub.data.model.comparison.buildComparisonGraph
import me.rerere.rikkahub.data.model.comparison.subtreeSizes
import me.rerere.rikkahub.data.model.comparison.tidyLayout
import me.rerere.rikkahub.data.model.comparison.timelineLayout
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ComparisonVisibilityTest {
    private val noFiles = FileHasher { null }

    @Test
    fun `linear chain is never folded`() = runTest {
        val graph = buildComparisonGraph(
            listOf(conversation("A", *Array(1000) { index -> "message $index" })),
            noFiles,
        )
        val sizes = subtreeSizes(graph)

        val folded = foldedNodeIds(graph, sizes, expanded = emptySet())
        val visible = visibleNodeIds(graph, folded)

        assertTrue(folded.isEmpty())
        assertEquals(graph.nodes.size, visible.size)
    }

    @Test
    fun `linear compression keeps the first and last five nodes`() = runTest {
        val graph = buildComparisonGraph(
            listOf(conversation("A", *Array(400) { index -> "message $index" })),
            noFiles,
        )

        val compression = linearCompression(graph, expanded = emptySet())

        assertEquals(1, compression.anchors.size)
        assertEquals(390, compression.hidden.size)
        val visible = visibleNodeIds(graph, emptySet(), compression.hidden)
        assertEquals(10, visible.size)
        assertTrue(compression.hidden.none { it in visible })

        val byDepth = graph.nodes.values.sortedBy { it.depth }
        byDepth.take(5).forEach { node -> assertTrue(node.id in visible) }
        byDepth.takeLast(5).forEach { node -> assertTrue(node.id in visible) }
        byDepth.subList(5, 395).forEach { node -> assertFalse(node.id in visible) }

        // chip 锚点是中段第一颗节点
        assertEquals(byDepth[5].id, compression.anchors.keys.single())
        assertEquals(390, compression.anchors.values.single())
    }

    @Test
    fun `linear compression threshold is strict at two hundred`() = runTest {
        val atThresholdGraph = buildComparisonGraph(
            listOf(conversation("A", *Array(200) { index -> "m$index" })),
            noFiles,
        )
        val atThreshold = linearCompression(atThresholdGraph, expanded = emptySet())
        assertTrue(atThreshold.hidden.isEmpty())
        assertTrue(atThreshold.anchors.isEmpty())

        val over200 = buildComparisonGraph(
            listOf(conversation("A", *Array(201) { index -> "m$index" })),
            noFiles,
        )
        val over = linearCompression(over200, expanded = emptySet())
        assertEquals(191, over.hidden.size)
        assertEquals(
            10,
            visibleNodeIds(graph = over200, folded = emptySet(), hidden = over.hidden).size,
        )
    }

    @Test
    fun `expanding a segment restores the whole run`() = runTest {
        val graph = buildComparisonGraph(
            listOf(conversation("A", *Array(400) { index -> "message $index" })),
            noFiles,
        )
        val anchor = linearCompression(graph, emptySet()).anchors.keys.single()

        val compression = linearCompression(graph, expanded = setOf(linearSegmentKey(anchor)))

        assertTrue(compression.hidden.isEmpty())
        assertEquals(graph.nodes.size, visibleNodeIds(graph, emptySet(), compression.hidden).size)
    }

    @Test
    fun `only forks above threshold are folded`() = runTest {
        val graph = buildComparisonGraph(
            listOf(
                conversation("A", "hi", *Array(400) { index -> "a $index" }),
                conversation("B", "hi", *Array(400) { index -> "b $index" }),
            ),
            noFiles,
        )
        val sizes = subtreeSizes(graph)

        val folded = foldedNodeIds(graph, sizes, expanded = emptySet())

        assertEquals(setOf(graph.rootIds.single()), folded)
        val root = graph.nodes.getValue(graph.rootIds.single())
        assertEquals(2, root.childIds.size)
        // 分叉下面每条 400 节点的线性链子树也超过阈值，但不是分叉点，不折
        root.childIds.forEach { childId ->
            assertTrue(sizes.getValue(childId) > FOLD_SUBTREE_THRESHOLD)
            assertFalse(childId in folded)
        }
    }

    @Test
    fun `folded node itself stays visible and hides only descendants`() = runTest {
        val graph = buildComparisonGraph(
            listOf(
                conversation("A", "hi", *Array(400) { index -> "a $index" }),
                conversation("B", "hi", *Array(400) { index -> "b $index" }),
            ),
            noFiles,
        )
        val sizes = subtreeSizes(graph)
        val rootId = graph.rootIds.single()

        val folded = foldedNodeIds(graph, sizes, expanded = emptySet())
        val visible = visibleNodeIds(graph, folded)

        assertTrue(rootId in visible)
        assertEquals(1, visible.size)
        assertEquals(800, foldHiddenCount(sizes, rootId))
    }

    @Test
    fun `fold chip hides every descendant but the node itself`() = runTest {
        val graph = buildComparisonGraph(
            listOf(
                conversation("A", "hi", *Array(400) { index -> "a $index" }),
                conversation("B", "hi", *Array(400) { index -> "b $index" }),
            ),
            noFiles,
        )
        val sizes = subtreeSizes(graph)
        val rootId = graph.rootIds.single()
        val folded = foldedNodeIds(graph, sizes, expanded = emptySet())
        val visible = visibleNodeIds(graph, folded)

        assertEquals(801, sizes.getValue(rootId))
        assertEquals(800, foldHiddenCount(sizes, rootId))
        assertEquals(graph.nodes.size - 1, (graph.nodes.keys - visible).size)
        // 线性子链子树再大也不折，所以不会出现它的 chip
        assertTrue(graph.nodes.getValue(rootId).childIds.none { it in folded })
    }

    @Test
    fun `expanding clears the fold`() = runTest {
        val graph = buildComparisonGraph(
            listOf(
                conversation("A", "hi", *Array(400) { index -> "a $index" }),
                conversation("B", "hi", *Array(400) { index -> "b $index" }),
            ),
            noFiles,
        )
        val sizes = subtreeSizes(graph)
        val rootId = graph.rootIds.single()

        val folded = foldedNodeIds(graph, sizes, expanded = setOf(rootId))

        assertTrue(folded.isEmpty())
        assertEquals(graph.nodes.size, visibleNodeIds(graph, folded).size)
    }

    @Test
    fun `small fork is not folded`() = runTest {
        val graph = buildComparisonGraph(
            listOf(conversation("A", "hi", "a"), conversation("B", "hi", "b")),
            noFiles,
        )

        val folded = foldedNodeIds(graph, subtreeSizes(graph), expanded = emptySet())

        assertTrue(folded.isEmpty())
    }

    @Test
    fun `first screen of a long chain stays under sixty cards`() = runTest {
        val graph = buildComparisonGraph(
            listOf(conversation("A", *Array(2000) { index -> "message $index" })),
            noFiles,
        )
        val layout = tidyLayout(graph, columnWidth = 300f, rowHeight = 160f)
        val folded = foldedNodeIds(graph, subtreeSizes(graph), expanded = emptySet())
        val candidates = visibleNodeIds(graph, folded)

        val world = visibleWorldRect(
            viewportWidthPx = 1080f,
            viewportHeightPx = 1920f,
            density = 3f,
            scale = MinCanvasScale,
            panX = 0f,
            panY = 0f,
            marginDp = 220f,
        )
        val visible = visibleInViewport(layout.points, candidates, world, 220f, 96f)

        assertTrue(visible.isNotEmpty())
        assertTrue("expected < 60 cards, got ${visible.size}", visible.size < 60)
        visible.forEach { id ->
            assertTrue(
                "node $id must intersect viewport",
                intersectsViewport(layout.points[id], world, 220f, 96f),
            )
        }
    }

    @Test
    fun `culling follows pan`() = runTest {
        val graph = buildComparisonGraph(
            listOf(conversation("A", *Array(500) { index -> "message $index" })),
            noFiles,
        )
        val layout = tidyLayout(graph, columnWidth = 300f, rowHeight = 160f)
        val candidates = layout.points.keys

        val world = visibleWorldRect(1080f, 1920f, 3f, 1f, panX = 1_000_000f, panY = 0f, marginDp = 220f)
        val visible = visibleInViewport(layout.points, candidates, world, 220f, 96f)

        val firstHundred = layout.points.values.sortedBy { it.x }.take(100).map { it.id }.toSet()
        assertTrue(visible.intersect(firstHundred).isEmpty())
    }

    @Test
    fun `lane titles follow leaf conversations`() = runTest {
        val graph = buildComparisonGraph(
            listOf(
                conversation("Alpha", "hi", "a"),
                conversation("Beta", "hi", "b"),
            ),
            noFiles,
        )
        val layout = timelineLayout(graph, columnWidth = 320f, laneHeight = 160f)

        val titles = laneTitles(graph, layout.points, laneHeight = 160f, laneCount = layout.leafCount)

        assertEquals(setOf("Alpha", "Beta"), titles.toSet())
    }

    @Test
    fun `pan clamp centers small content and bounds large content`() {
        assertEquals(100f, clampPanAxis(value = 999f, contentPx = 300f, viewportPx = 500f), 0.001f)
        val large = clampPanAxis(value = 999_999f, contentPx = 3000f, viewportPx = 500f)
        assertTrue(large <= 250f)
    }

    @Test
    fun `visible nodes are capped and report how many were dropped`() {
        val points = (0 until 200).associate { index ->
            val id = "n$index"
            id to LayoutPoint(id = id, x = (index % 20) * 300f, y = (index / 20) * 200f)
        }
        val world = WorldRect(-1_000_000f, -1_000_000f, 1_000_000f, 1_000_000f)

        val capped = visibleInViewportCapped(points, points.keys, world, 220f, 200f, limit = 80)

        assertEquals(80, capped.ids.size)
        assertEquals(120, capped.hiddenCount)
        // 按 (y, x) 取最靠上的：前四行整行都在，最后一行不该出现
        assertTrue("n79" in capped.ids)
        assertFalse("n199" in capped.ids)

        val uncapped = visibleInViewportCapped(points, points.keys.take(10).toSet(), world, 220f, 200f, limit = 80)
        assertEquals(10, uncapped.ids.size)
        assertEquals(0, uncapped.hiddenCount)
    }

    @Test
    fun `cap keeps every node when the screen already fits`() = runTest {
        val graph = buildComparisonGraph(
            listOf(conversation("A", *Array(2000) { index -> "message $index" })),
            noFiles,
        )
        val layout = tidyLayout(graph, columnWidth = 300f, rowHeight = 200f)
        val world = visibleWorldRect(
            viewportWidthPx = 1080f,
            viewportHeightPx = 1920f,
            density = 3f,
            scale = MinCanvasScale,
            panX = 0f,
            panY = 0f,
            marginDp = 220f,
        )

        val capped = visibleInViewportCapped(layout.points, layout.points.keys, world, 220f, 200f)
        val plain = visibleInViewport(layout.points, layout.points.keys, world, 220f, 200f)

        assertEquals(plain, capped.ids)
        assertEquals(0, capped.hiddenCount)
        assertTrue(capped.ids.size <= MAX_DRAWN_NODES)
    }

    @Test
    fun `pan clamp accounts for content offset and centers small content`() {
        // 内容占世界坐标 [100, 500]，视口 300：允许半屏过滚
        assertEquals(50f, clampPan(9999f, startPx = 100f, endPx = 500f, viewportPx = 300f), 0.001f)
        assertEquals(-350f, clampPan(-9999f, startPx = 100f, endPx = 500f, viewportPx = 300f), 0.001f)
        // 内容比视口小：居中，并且减掉内容起点偏移
        assertEquals(50f, clampPan(0f, startPx = 100f, endPx = 300f, viewportPx = 500f), 0.001f)
        // 与原 clampPanAxis 语义一致
        assertEquals(clampPanAxis(9999f, 300f, 500f), clampPan(9999f, 0f, 300f, 500f), 0.001f)
    }

    @Test
    fun `content bounds only cover visible nodes`() {
        val points = mapOf(
            "a" to LayoutPoint("a", x = 0f, y = 0f),
            "b" to LayoutPoint("b", x = 300f, y = 400f),
            "hidden" to LayoutPoint("hidden", x = 9000f, y = 9000f),
        )

        val box = contentBounds(points, setOf("a", "b"), cardWidthDp = 220f, cardHeightDp = 200f)

        assertEquals(WorldRect(left = 0f, top = 0f, right = 520f, bottom = 600f), box)
        assertNull(contentBounds(points, emptySet(), 220f, 200f))
    }

    @Test
    fun `fit viewport centers content and never magnifies past one`() {
        val fitted = fitViewport(
            contentWidthPx = 1000f,
            contentHeightPx = 500f,
            viewportWidthPx = 500f,
            viewportHeightPx = 500f,
            bounds = WorldRect(left = 0f, top = 0f, right = 1000f, bottom = 500f),
        )

        assertEquals(0.5f, fitted.scale, 0.001f)
        assertEquals(0f, fitted.panX, 0.001f)
        assertEquals(125f, fitted.panY, 0.001f)

        val small = fitViewport(
            contentWidthPx = 100f,
            contentHeightPx = 100f,
            viewportWidthPx = 1000f,
            viewportHeightPx = 1000f,
            bounds = WorldRect(left = 0f, top = 0f, right = 100f, bottom = 100f),
        )
        assertEquals(1f, small.scale, 0.001f)
        assertEquals(450f, small.panX, 0.001f)

        // 内容起点不在 0：pan 要把起点偏移抵掉，否则首帧整块偏到一边
        val offset = fitViewport(
            contentWidthPx = 400f,
            contentHeightPx = 400f,
            viewportWidthPx = 1000f,
            viewportHeightPx = 1000f,
            bounds = WorldRect(left = 200f, top = 200f, right = 600f, bottom = 600f),
        )
        assertEquals(100f, offset.panX, 0.001f)
        assertEquals(100f, offset.panY, 0.001f)
    }

    @Test
    fun `fit pan starts at the content edge when it is wider than the viewport`() {
        assertEquals(0f, fitPan(contentPx = 400f, viewportPx = 300f, startPx = 0f), 0.001f)
        assertEquals(50f, fitPan(contentPx = 200f, viewportPx = 300f), 0.001f)
        assertEquals(-50f, fitPan(contentPx = 200f, viewportPx = 300f, startPx = 100f), 0.001f)
    }

    @Test
    fun `chain flags stop at the visible indent depth`() {
        var chain = emptyList<Boolean>()
        repeat(200) { index -> chain = appendChain(chain, continuesBelow = index % 2 == 0) }

        assertEquals(MAX_CHAIN_DEPTH, chain.size)
        assertEquals(listOf(true, false, true), appendChain(listOf(true, false), continuesBelow = true))
        // 截断保留的是最外层的前缀，连线不错位
        assertEquals(true, chain.first())
        assertEquals(false, chain[1])
    }

    private fun conversation(title: String, vararg texts: String) = Conversation(
        id = Uuid.random(),
        assistantId = Uuid.random(),
        title = title,
        messageNodes = texts.map { text -> text.toMessage().toMessageNode() },
    )

    private fun String.toMessage(): UIMessage = UIMessage.user(this)
}
