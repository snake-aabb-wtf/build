package me.rerere.rikkahub.ui.pages.comparison.views

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 对比画布使用的度量。
 *
 * 页面创建一份传给布局算法、卡片和连线，保持节点间距与卡片尺寸一致。
 */
@Immutable
data class ComparisonMetrics(
    /** 画布视图每一层的横向间距。 */
    val columnWidth: Dp = 300.dp,
    /** 画布视图相邻叶子的纵向间距，必须大于卡片高度（大字号下卡片能到 150dp）。 */
    val rowHeight: Dp = 200.dp,
    val cardWidth: Dp = 220.dp,
    val cardHeight: Dp = 96.dp,
)
