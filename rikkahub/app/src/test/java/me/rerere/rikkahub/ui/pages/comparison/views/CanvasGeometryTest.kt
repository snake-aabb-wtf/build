package me.rerere.rikkahub.ui.pages.comparison.views

import me.rerere.rikkahub.data.model.comparison.ComparisonOrientation
import me.rerere.rikkahub.data.model.comparison.LayoutPoint
import me.rerere.rikkahub.ui.pages.comparison.ComparisonViewport
import org.junit.Assert.assertEquals
import org.junit.Test

class CanvasGeometryTest {
    @Test
    fun `first fit converts offset world bounds to pixels at device density`() {
        val viewport = fitCanvasViewport(WorldRect(300f, 200f, 520f, 600f), 3f, 1080f, 1200f)

        assertEquals(ComparisonViewport(1f, -690f, -600f), viewport)
    }

    @Test
    fun `minimum zoom still aligns a large offset graph with the viewport origin`() {
        val viewport = fitCanvasViewport(WorldRect(15000f, 0f, 35000f, 400f), 3f, 1080f, 1200f)

        assertEquals(0.2f, viewport.scale, 0.001f)
        assertEquals(-9000f, viewport.panX, 0.001f)
    }

    @Test
    fun `folded vertical fork keeps its expansion control below the centered parent`() {
        val parent = LayoutPoint("parent", 15000f, 0f)
        val anchor = canvasFoldPoint(parent, ComparisonOrientation.VERTICAL, 300f, 200f)

        assertEquals(LayoutPoint("fold:parent", 15000f, 200f), anchor)
        val points = mapOf(parent.id to parent, anchor.id to anchor)
        val bounds = contentBounds(points, points.keys, 220f, 200f)
        assertEquals(WorldRect(15000f, 0f, 15220f, 400f), bounds)
    }

    @Test
    fun `horizontal edges connect upright cards at their actual side centers`() {
        val edge = canvasEdge(
            LayoutPoint("parent", 0f, 100f), LayoutPoint("child", 300f, 400f),
            cardWidth = 220f, parentHeight = 140f, childHeight = 100f,
            orientation = ComparisonOrientation.HORIZONTAL,
        )

        assertEquals(CanvasEdge(220f, 170f, 260f, 170f, 260f, 450f, 300f, 450f), edge)
    }

    @Test
    fun `vertical edges start below the whole parent and enter the child top center`() {
        val edge = canvasEdge(
            LayoutPoint("parent", 300f, 0f), LayoutPoint("child", 0f, 200f),
            cardWidth = 220f, parentHeight = 150f, childHeight = 120f,
            orientation = ComparisonOrientation.VERTICAL,
        )

        assertEquals(CanvasEdge(410f, 150f, 410f, 175f, 110f, 175f, 110f, 200f), edge)
    }
}
