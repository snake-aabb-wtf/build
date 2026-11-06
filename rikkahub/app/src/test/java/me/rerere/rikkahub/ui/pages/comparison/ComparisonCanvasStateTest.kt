package me.rerere.rikkahub.ui.pages.comparison

import me.rerere.rikkahub.data.model.comparison.ComparisonOrientation.HORIZONTAL
import me.rerere.rikkahub.data.model.comparison.ComparisonOrientation.VERTICAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComparisonCanvasStateTest {
    @Test
    fun `switching direction preserves each viewport and its first fit status independently`() {
        val horizontal = CanvasViewportState(ComparisonViewport(2f, -300f, 40f), fitted = true)
        val vertical = CanvasViewportState(ComparisonViewport(0.5f, 80f, -700f), fitted = true)

        val first = ComparisonCanvasState().updated(HORIZONTAL, horizontal)
        assertEquals(horizontal, first[HORIZONTAL])
        assertEquals(CanvasViewportState(), first[VERTICAL])

        val both = first.updated(VERTICAL, vertical)
        assertEquals(horizontal, both[HORIZONTAL])
        assertEquals(vertical, both[VERTICAL])
        assertTrue(both[HORIZONTAL].fitted)
        assertTrue(both[VERTICAL].fitted)
    }

    @Test
    fun `graph rebuild invalidates fit in both directions while retaining stored viewports`() {
        val horizontal = CanvasViewportState(ComparisonViewport(2f, -300f, 40f), fitted = true)
        val vertical = CanvasViewportState(ComparisonViewport(0.5f, 80f, -700f), fitted = true)
        val state = ComparisonCanvasState()
            .updated(HORIZONTAL, horizontal)
            .updated(VERTICAL, vertical)
            .invalidateFits()

        assertFalse(state[HORIZONTAL].fitted)
        assertFalse(state[VERTICAL].fitted)
        assertEquals(horizontal.viewport, state[HORIZONTAL].viewport)
        assertEquals(vertical.viewport, state[VERTICAL].viewport)
    }
}
