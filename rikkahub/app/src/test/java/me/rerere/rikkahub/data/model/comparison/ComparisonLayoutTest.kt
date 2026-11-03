package me.rerere.rikkahub.data.model.comparison

import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ComparisonLayoutTest {
    private val noFiles = FileHasher { null }

    @Test
    fun `single chain has one leaf and no overlaps`() = runTest {
        val graph = buildComparisonGraph(listOf(conversation("A", "m0", "m1", "m2", "m3")), noFiles)

        val layout = tidyLayout(graph, columnWidth = 100f, rowHeight = 50f)

        assertEquals(4, layout.points.size)
        assertEquals(1, layout.leafCount)
        assertEquals(3, layout.maxDepth)
        assertEquals(listOf(0f, 100f, 200f, 300f), layout.points.values.sortedBy { it.x }.map { it.x })
        assertTrue(layout.points.values.all { it.y == 0f })
        assertEquals(layout.points.size, layout.points.values.map { it.id }.toSet().size)
    }

    @Test
    fun `two branches get distinct rows`() = runTest {
        val graph = buildComparisonGraph(
            listOf(
                conversation("A", "hi", "from a"),
                conversation("B", "hi", "from b"),
            ),
            noFiles,
        )

        val layout = tidyLayout(graph, columnWidth = 100f, rowHeight = 50f)

        assertEquals(2, layout.leafCount)
        val leaves = graph.nodes.values.filter { it.childIds.isEmpty() }.map { layout.points.getValue(it.id) }
        assertEquals(2, leaves.size)
        assertEquals(leaves.size, leaves.map { it.y }.toSet().size)
        assertEquals(1, leaves.map { it.x }.toSet().size)
        assertEquals(50f, leaves.map { it.y }.sorted()[1], 0.001f)
    }

    @Test
    fun `parent is centered over children`() = runTest {
        val graph = buildComparisonGraph(
            listOf(
                conversation("A", "hi", "a"),
                conversation("B", "hi", "b"),
                conversation("C", "hi", "c"),
            ),
            noFiles,
        )

        val layout = tidyLayout(graph, columnWidth = 100f, rowHeight = 50f)

        val root = layout.points.getValue(graph.rootIds.single())
        val children = graph.nodes.getValue(graph.rootIds.single()).childIds
            .map { layout.points.getValue(it) }
            .sortedBy { it.y }
        assertEquals(3, children.size)
        assertEquals(0f, children.first().y, 0.001f)
        assertEquals(100f, children.last().y, 0.001f)
        assertEquals(50f, root.y, 0.001f)
        assertTrue(root.y > children.first().y && root.y < children.last().y)
    }

    @Test
    fun `subtree size counts descendants`() = runTest {
        val graph = buildComparisonGraph(
            listOf(
                conversation("A", "hi", "a"),
                conversation("B", "hi", "b"),
            ),
            noFiles,
        )

        val sizes = subtreeSizes(graph)

        assertEquals(graph.nodes.size, sizes.getValue(graph.rootIds.single()))
        val leaves = graph.nodes.values.filter { it.childIds.isEmpty() }
        assertEquals(2, leaves.size)
        assertTrue(leaves.all { sizes.getValue(it.id) == 1 })
    }

    @Test
    fun `timeline lanes equal leaf count`() = runTest {
        val graph = buildComparisonGraph(
            listOf(
                conversation("A", "hi", "a"),
                conversation("B", "hi", "b"),
                conversation("C", "hi", "c"),
            ),
            noFiles,
        )

        val layout = timelineLayout(graph, columnWidth = 200f, laneHeight = 80f)

        assertEquals(3, layout.leafCount)
        val leaves = graph.nodes.values.filter { it.childIds.isEmpty() }.map { layout.points.getValue(it.id) }
        assertEquals(setOf(0f, 80f, 160f), leaves.map { it.y }.toSet())
        val root = layout.points.getValue(graph.rootIds.single())
        assertEquals(80f, root.y, 0.001f)
    }

    @Test
    fun `empty graph lays out nothing`() = runTest {
        val graph = buildComparisonGraph(emptyList(), noFiles)

        val tidy = tidyLayout(graph, columnWidth = 100f, rowHeight = 50f)
        val timeline = timelineLayout(graph, columnWidth = 100f, laneHeight = 50f)

        assertTrue(tidy.points.isEmpty())
        assertEquals(0, tidy.leafCount)
        assertTrue(timeline.points.isEmpty())
        assertEquals(0, timeline.leafCount)
    }

    @Test
    fun `long chain does not blow the stack`() = runTest {
        val graph = buildComparisonGraph(
            listOf(conversation("A", *Array(2000) { index -> "message $index" })),
            noFiles,
        )

        val layout = tidyLayout(graph, columnWidth = 10f, rowHeight = 10f)

        assertEquals(2000, layout.points.size)
        assertEquals(1, layout.leafCount)
        assertEquals(1999, layout.maxDepth)
    }

    private fun conversation(title: String, vararg texts: String) = Conversation(
        id = Uuid.random(),
        assistantId = Uuid.random(),
        title = title,
        messageNodes = texts.map { text -> text.toMessage() },
    )

    private fun String.toMessage(): UIMessage = UIMessage.user(this)
}
