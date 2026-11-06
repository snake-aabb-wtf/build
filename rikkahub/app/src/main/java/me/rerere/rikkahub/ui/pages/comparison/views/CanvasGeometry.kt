package me.rerere.rikkahub.ui.pages.comparison.views

import me.rerere.rikkahub.data.model.comparison.ComparisonOrientation
import me.rerere.rikkahub.data.model.comparison.LayoutPoint
import me.rerere.rikkahub.ui.pages.comparison.ComparisonViewport

internal fun fitCanvasViewport(
    bounds: WorldRect?,
    density: Float,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
): ComparisonViewport = bounds?.let { box ->
    fitViewport(
        contentWidthPx = (box.right - box.left) * density,
        contentHeightPx = (box.bottom - box.top) * density,
        viewportWidthPx = viewportWidthPx,
        viewportHeightPx = viewportHeightPx,
        bounds = WorldRect(
            box.left * density, box.top * density,
            box.right * density, box.bottom * density,
        ),
    )
} ?: ComparisonViewport()

/** 卡片间贝塞尔曲线的世界坐标（dp）。 */
internal data class CanvasEdge(
    val startX: Float,
    val startY: Float,
    val control1X: Float,
    val control1Y: Float,
    val control2X: Float,
    val control2Y: Float,
    val endX: Float,
    val endY: Float,
)

internal fun canvasFoldPoint(
    parent: LayoutPoint,
    orientation: ComparisonOrientation,
    columnWidth: Float,
    rowHeight: Float,
): LayoutPoint = when (orientation) {
    ComparisonOrientation.VERTICAL -> parent.copy(id = "fold:${parent.id}", y = parent.y + rowHeight)
    ComparisonOrientation.HORIZONTAL -> parent.copy(id = "fold:${parent.id}", x = parent.x + columnWidth)
}

internal fun canvasEdge(
    start: LayoutPoint,
    end: LayoutPoint,
    cardWidth: Float,
    parentHeight: Float,
    childHeight: Float,
    orientation: ComparisonOrientation,
): CanvasEdge {
    if (orientation == ComparisonOrientation.VERTICAL) {
        val startX = start.x + cardWidth / 2f
        val startY = start.y + parentHeight
        val endX = end.x + cardWidth / 2f
        val middleY = startY + (end.y - startY) / 2f
        return CanvasEdge(startX, startY, startX, middleY, endX, middleY, endX, end.y)
    }
    val startX = start.x + cardWidth
    val startY = start.y + parentHeight / 2f
    val endX = end.x
    val endY = end.y + childHeight / 2f
    val middleX = startX + (endX - startX) / 2f
    return CanvasEdge(startX, startY, middleX, startY, middleX, endY, endX, endY)
}
