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
    /** 物理横向间距：横版为消息层级，竖版为相邻分支。 */
    val columnWidth: Dp = 300.dp,
    /** 物理纵向间距：竖版为消息层级，横版为相邻分支；随字号增加以容纳卡片。 */
    val rowHeight: Dp = 200.dp,
    val cardWidth: Dp = 220.dp,
    val cardHeight: Dp = 96.dp,
)
