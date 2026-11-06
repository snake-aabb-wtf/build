package me.rerere.rikkahub.ui.pages.comparison.views

import me.rerere.rikkahub.data.model.comparison.LayoutPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerticalCanvasVisibilityTest {
    private val points = (0 until 2000).associate { index ->
        val id = "n$index"
        id to LayoutPoint(id, 0f, index * 200f)
    }

    @Test
    fun `vertical pan reveals later levels without composing the entire long chain`() {
        val world = visibleWorldRect(220f, 600f, 1f, 1f, 0f, -1000f, marginDp = 0f)
        val drawn = visibleInViewportCapped(points, points.keys, world, 220f, 200f)

        assertEquals(setOf("n4", "n5", "n6", "n7", "n8"), drawn.ids)
        assertEquals(0, drawn.hiddenCount)
    }

    @Test
    fun `zoomed out vertical canvas caps drawn items while retaining the full point map`() {
        val world = WorldRect(0f, 0f, 220f, 400000f)
        val drawn = visibleInViewportCapped(points, points.keys, world, 220f, 200f)

        assertEquals(80, drawn.ids.size)
        assertEquals(1920, drawn.hiddenCount)
        assertTrue("n79" in drawn.ids)
        assertFalse("n80" in drawn.ids)
        assertEquals(2000, points.size)
    }
}
