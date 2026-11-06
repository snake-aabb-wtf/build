package me.rerere.rikkahub.data.model.comparison

import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComparisonCanvasLayoutTest {
    @Test
    fun `default canvas chain runs from top to bottom with upright card spacing`() {
        val layout = tidyLayout(chain(), columnWidth = 300f, rowHeight = 200f)

        assertEquals(LayoutPoint("root", 0f, 0f), layout.points.getValue("root"))
        assertEquals(LayoutPoint("middle", 0f, 200f), layout.points.getValue("middle"))
        assertEquals(LayoutPoint("end", 0f, 400f), layout.points.getValue("end"))
    }

    @Test
    fun `horizontal chain uses depth for x and preserves top left coordinates`() {
        val graph = chain()

        val layout = tidyLayout(
            graph, columnWidth = 300f, rowHeight = 200f,
            orientation = ComparisonOrientation.HORIZONTAL,
        )

        assertEquals(LayoutPoint("root", 0f, 0f), layout.points.getValue("root"))
        assertEquals(LayoutPoint("middle", 300f, 0f), layout.points.getValue("middle"))
        assertEquals(LayoutPoint("end", 600f, 0f), layout.points.getValue("end"))
        assertEquals(1, layout.leafCount)
        assertEquals(2, layout.maxDepth)
    }

    @Test
    fun `vertical siblings have room for upright cards and parent centers over them`() {
        val layout = tidyLayout(
            graph(
                node("root", null, 0, "left", "middle", "right"),
                node("left", "root", 1), node("middle", "root", 1), node("right", "root", 1),
            ),
            columnWidth = 300f, rowHeight = 200f,
        )

        assertEquals(LayoutPoint("root", 300f, 0f), layout.points.getValue("root"))
        assertEquals(LayoutPoint("left", 0f, 200f), layout.points.getValue("left"))
        assertEquals(LayoutPoint("middle", 300f, 200f), layout.points.getValue("middle"))
        assertEquals(LayoutPoint("right", 600f, 200f), layout.points.getValue("right"))
        assertTrue(layout.points.getValue("left").x + 220f < layout.points.getValue("middle").x)
    }

    @Test
    fun `asymmetric vertical fork centers each parent over its immediate children`() {
        val layout = tidyLayout(
            graph(
                node("root", null, 0, "left", "right"),
                node("left", "root", 1), node("right", "root", 1, "a", "b"),
                node("a", "right", 2), node("b", "right", 2),
            ),
            columnWidth = 300f, rowHeight = 200f,
        )

        assertEquals(LayoutPoint("root", 225f, 0f), layout.points.getValue("root"))
        assertEquals(LayoutPoint("right", 450f, 200f), layout.points.getValue("right"))
        assertEquals(LayoutPoint("a", 300f, 400f), layout.points.getValue("a"))
        assertEquals(LayoutPoint("b", 600f, 400f), layout.points.getValue("b"))
    }

    @Test
    fun `unrelated vertical roots stay side by side in selection order`() {
        val layout = tidyLayout(graph(node("a", null, 0), node("b", null, 0)), 300f, 200f)

        assertEquals(LayoutPoint("a", 0f, 0f), layout.points.getValue("a"))
        assertEquals(LayoutPoint("b", 300f, 0f), layout.points.getValue("b"))
        assertEquals(2, layout.leafCount)
    }

    @Test
    fun `two thousand vertical levels retain every node without recursive stack growth`() {
        val nodes = (0 until 2000).map { index ->
            node(
                "n$index", if (index == 0) null else "n${index - 1}", index,
                *if (index < 1999) arrayOf("n${index + 1}") else emptyArray(),
            )
        }
        val layout = tidyLayout(graph(*nodes.toTypedArray()), 300f, 200f)

        assertEquals(2000, layout.points.size)
        assertEquals(1999, layout.maxDepth)
        assertEquals(1, layout.leafCount)
        assertEquals(LayoutPoint("n1999", 0f, 399800f), layout.points.getValue("n1999"))
    }

    @Test
    fun `empty graph stays empty in either direction`() {
        ComparisonOrientation.entries.forEach { orientation ->
            val layout = tidyLayout(graph(), 300f, 200f, orientation)
            assertTrue(layout.points.isEmpty())
            assertEquals(0, layout.leafCount)
            assertEquals(0, layout.maxDepth)
        }
    }

    @Test
    fun `saved direction restores horizontal choice and safely defaults unknown values to vertical`() {
        assertEquals(ComparisonOrientation.HORIZONTAL, ComparisonOrientation.fromPreference("HORIZONTAL"))
        assertEquals(ComparisonOrientation.VERTICAL, ComparisonOrientation.fromPreference("VERTICAL"))
        assertEquals(ComparisonOrientation.VERTICAL, ComparisonOrientation.fromPreference(null))
        assertEquals(ComparisonOrientation.VERTICAL, ComparisonOrientation.fromPreference("obsolete-view"))
    }

    private fun chain(): ComparisonGraph = graph(
        node("root", null, 0, "middle"),
        node("middle", "root", 1, "end"),
        node("end", "middle", 2),
    )

    private fun graph(vararg nodes: ComparisonNode): ComparisonGraph = ComparisonGraph(
        rootIds = nodes.filter { it.parentId == null }.map { it.id },
        nodes = nodes.associateBy { it.id },
    )

    private fun node(id: String, parent: String?, depth: Int, vararg children: String): ComparisonNode =
        ComparisonNode(
            id = id,
            parentId = parent,
            depth = depth,
            message = UIMessage.user(id),
            occurrences = emptyList(),
            childIds = children.toList(),
            fingerprint = id,
        )
}
