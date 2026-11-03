package me.rerere.rikkahub.ui.pages.comparison.views

import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.comparison.FileHasher
import me.rerere.rikkahub.data.model.comparison.buildComparisonGraph
import me.rerere.rikkahub.data.model.comparison.subtreeSizes
import me.rerere.rikkahub.data.model.comparison.tidyLayout
import me.rerere.rikkahub.data.model.comparison.timelineLayout
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun conversation(title: String, vararg texts: String) = Conversation(
        id = Uuid.random(),
        assistantId = Uuid.random(),
        title = title,
        messageNodes = texts.map { text -> text.toMessage().toMessageNode() },
    )

    private fun String.toMessage(): UIMessage = UIMessage.user(this)
}
