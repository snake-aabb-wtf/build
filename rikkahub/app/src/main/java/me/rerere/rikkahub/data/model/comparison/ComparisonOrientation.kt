package me.rerere.rikkahub.data.model.comparison

/** 消息层级的展开方向；节点文字和卡片始终保持正向。 */
enum class ComparisonOrientation {
    VERTICAL,
    HORIZONTAL;

    companion object {
        fun fromPreference(value: String?): ComparisonOrientation =
            entries.firstOrNull { it.name == value } ?: VERTICAL
    }
}
