package me.rerere.rikkahub.ui.components.ai

import me.rerere.rikkahub.data.model.Conversation

internal enum class CompressionInputError { EMPTY, INVALID_INTEGER, OUT_OF_RANGE, NOTHING_TO_COMPRESS }

internal data class CompressionKeepRecentInput(
    val value: Int?,
    val maximum: Int?,
    val error: CompressionInputError?,
)

internal fun validateCompressionKeepRecent(
    text: String,
    messagesAfterLastCheckpoint: Int,
): CompressionKeepRecentInput {
    if (messagesAfterLastCheckpoint <= 0) {
        return CompressionKeepRecentInput(null, null, CompressionInputError.NOTHING_TO_COMPRESS)
    }
    val maximum = messagesAfterLastCheckpoint - 1
    val normalized = text.trim().map { char ->
        if (char in '０'..'９') '0' + (char - '０') else char
    }.joinToString("")
    if (normalized.isEmpty()) {
        return CompressionKeepRecentInput(null, maximum, CompressionInputError.EMPTY)
    }
    if (normalized.any { it !in '0'..'9' }) {
        return CompressionKeepRecentInput(null, maximum, CompressionInputError.INVALID_INTEGER)
    }
    val value = normalized.trimStart('0').ifEmpty { "0" }.toIntOrNull()
        ?: return CompressionKeepRecentInput(null, maximum, CompressionInputError.INVALID_INTEGER)
    if (value > maximum) {
        return CompressionKeepRecentInput(null, maximum, CompressionInputError.OUT_OF_RANGE)
    }
    return CompressionKeepRecentInput(value, maximum, null)
}

internal fun stepCompressionKeepRecent(
    text: String,
    messagesAfterLastCheckpoint: Int,
    increase: Boolean,
): String {
    val input = validateCompressionKeepRecent(text, messagesAfterLastCheckpoint)
    val value = input.value ?: return text
    val maximum = input.maximum ?: return text
    return if (increase) {
        if (value < maximum) (value + 1).toString() else text
    } else {
        if (value > 0) (value - 1).toString() else text
    }
}

internal fun Conversation.messagesAfterLastContextCheckpoint(): Int =
    messageNodes.size - messageNodes.indexOfLast { it.currentMessage.isContextCheckpoint } - 1
