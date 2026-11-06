package me.rerere.rikkahub.ui.pages.comparison

import me.rerere.rikkahub.data.model.comparison.ComparisonOrientation

/** 画布的视图状态；平移单位为屏幕像素。 */
data class ComparisonViewport(
    val scale: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
)

data class CanvasViewportState(
    val viewport: ComparisonViewport = ComparisonViewport(),
    val fitted: Boolean = false,
)

data class ComparisonCanvasState(
    val horizontal: CanvasViewportState = CanvasViewportState(),
    val vertical: CanvasViewportState = CanvasViewportState(),
) {
    operator fun get(orientation: ComparisonOrientation): CanvasViewportState = when (orientation) {
        ComparisonOrientation.HORIZONTAL -> horizontal
        ComparisonOrientation.VERTICAL -> vertical
    }

    fun updated(orientation: ComparisonOrientation, value: CanvasViewportState): ComparisonCanvasState =
        when (orientation) {
            ComparisonOrientation.HORIZONTAL -> copy(horizontal = value)
            ComparisonOrientation.VERTICAL -> copy(vertical = value)
        }

    fun invalidateFits(): ComparisonCanvasState = copy(
        horizontal = horizontal.copy(fitted = false),
        vertical = vertical.copy(fitted = false),
    )
}
