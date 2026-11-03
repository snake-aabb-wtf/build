package me.rerere.rikkahub.ui.pages.comparison.views

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 三个对比视图共用的度量。
 *
 * 页面创建一份传给布局算法、卡片和连线，避免每个视图各写一套数字（之前布局的列宽和卡片宽度就对不上）。
 */
@Immutable
data class ComparisonMetrics(
    /** 画布视图每一层的横向间距。 */
    val columnWidth: Dp = 300.dp,
    /** 画布视图相邻叶子的纵向间距，必须大于卡片高度。 */
    val rowHeight: Dp = 160.dp,
    val cardWidth: Dp = 220.dp,
    val cardHeight: Dp = 96.dp,
    /** 缩进树每层缩进。 */
    val indentStep: Dp = 20.dp,
    val indentCardWidth: Dp = 320.dp,
    /** 时间线每一层的横向间距。 */
    val timelineColumnWidth: Dp = 320.dp,
    val timelineCardWidth: Dp = 260.dp,
    /** 时间线 lane 的高度（也是 layout 的 rowHeight）。 */
    val timelineLaneHeight: Dp = 160.dp,
    /** 时间线左侧固定泳道标题列宽度。 */
    val laneHeaderWidth: Dp = 128.dp,
)
