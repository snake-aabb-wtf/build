package me.rerere.rikkahub.data.model.comparison

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import java.security.MessageDigest

/**
 * 读取一个本地附件的内容哈希。
 *
 * 返回值只用于比较：文件不存在、不是本地文件或读取失败时返回 null，调用方退回使用 URL。
 */
fun interface FileHasher {
    suspend fun hash(url: String): String?
}

/**
 * 消息的内容指纹。
 *
 * 只包含角色与可见内容，忽略 id、时间戳、模型、usage、翻译、注解等元数据；本地附件按文件内容哈希比较，
 * 因此 fork 出来的会话（消息 id 与附件 URL 都变了）能合并到同一条链上。
 */
internal suspend fun messageFingerprint(message: UIMessage, fileHasher: FileHasher): String {
    val builder = StringBuilder()
    builder.segment(message.role.name)
    if (message.isContextCheckpoint) {
        builder.segment("CP")
    }
    message.annotations.forEach { annotation ->
        when (annotation) {
            is UIMessageAnnotation.UrlCitation ->
                builder.segment("A").segment(annotation.title).segment(annotation.url)
        }
    }
    message.parts.forEach { part ->
        builder.segment(partFingerprint(part, fileHasher))
    }
    return builder.toString().sha256Hex()
}

/** 每段先写长度，避免不同切分拼出同一个字符串。 */
private fun StringBuilder.segment(value: String): StringBuilder =
    append(value.length).append('\u0000').append(value).append('\u0000')

@Suppress("DEPRECATION")
private suspend fun partFingerprint(part: UIMessagePart, fileHasher: FileHasher): String {
    val b = StringBuilder()
    when (part) {
        is UIMessagePart.Text -> b.segment("T").segment(part.text)
        is UIMessagePart.Reasoning -> b.segment("R").segment(part.reasoning)
        is UIMessagePart.Search -> b.segment("S")
        is UIMessagePart.Image -> b.segment("FImage").segment(fileKey(part.url, fileHasher))
        is UIMessagePart.Video -> b.segment("FVideo").segment(fileKey(part.url, fileHasher))
        is UIMessagePart.Audio -> b.segment("FAudio").segment(fileKey(part.url, fileHasher))
        is UIMessagePart.Document -> b.segment("FDocument").segment(fileKey(part.url, fileHasher))
            .segment(part.fileName).segment(part.mime)
        // toolCallId 在 fork 时会换新，不能参与比较
        is UIMessagePart.ToolCall ->
            b.segment("TC").segment(part.toolName).segment(canonicalJson(part.arguments, fileHasher, false))
                .segment(approvalKey(part.approvalState))

        is UIMessagePart.ToolResult ->
            b.segment("TR")
                .segment(part.toolName)
                .segment(canonicalJsonElement(part.arguments, fileHasher, false))
                .segment(canonicalJsonElement(part.content, fileHasher, false))

        is UIMessagePart.Tool ->
            b.segment("TO")
                .segment(part.toolName)
                .segment(canonicalJson(part.input, fileHasher, false))
                .segment(approvalKey(part.approvalState))
                .segment(partsFingerprint(part.output, fileHasher))

        is UIMessagePart.ServerTool ->
            b.segment("ST")
                .segment(part.toolName)
                .segment(part.status.name)
                .segment(canonicalJsonElementOrEmpty(part.input, fileHasher, false))
                .segment(canonicalJsonElementOrEmpty(part.output, fileHasher, false))
    }
    return b.toString()
}

/** 工具输出里嵌套的附件同样按内容比较，与 `Conversation.fork()` 的递归拷贝范围保持一致。 */
private suspend fun partsFingerprint(
    parts: List<UIMessagePart>,
    fileHasher: FileHasher,
): String {
    val builder = StringBuilder()
    parts.forEach { part ->
        builder.segment(partFingerprint(part, fileHasher))
    }
    return builder.toString()
}

private fun approvalKey(state: ToolApprovalState): String = when (state) {
    ToolApprovalState.Auto -> "auto"
    ToolApprovalState.Pending -> "pending"
    ToolApprovalState.Approved -> "approved"
    is ToolApprovalState.Denied -> "denied:" + state.reason
    is ToolApprovalState.Answered -> "answered:" + state.answer
}

private suspend fun fileKey(url: String, fileHasher: FileHasher): String {
    return fileHasher.hash(url)?.let { "sha256:$it" } ?: url
}

/** 按键名排序的 JSON；解析失败（流式中的半截参数）时退回原始字符串。 */
private suspend fun canonicalJson(raw: String, fileHasher: FileHasher, hashUrls: Boolean): String {
    // 空参数在 UI 里和 {} 等价（Tool.inputAsJson 同样归一），这里必须一致，否则整条尾巴假分叉
    val source = if (raw.isBlank()) "{}" else raw
    val element = runCatching { lenientJson.parseToJsonElement(source) }.getOrNull()
        ?: return "@raw:$raw"
    return element.canonicalString(fileHasher, hashUrls)
}

private suspend fun canonicalJsonElement(
    element: JsonElement,
    fileHasher: FileHasher,
    hashUrls: Boolean,
): String = element.canonicalString(fileHasher, hashUrls)

/** null 与 JsonNull 都是「没有」，统一成空串，避免同一语义两种写法造成假分叉。 */
private suspend fun canonicalJsonElementOrEmpty(
    element: JsonElement?,
    fileHasher: FileHasher,
    hashUrls: Boolean,
): String {
    if (element == null || element is JsonNull) return ""
    return element.canonicalString(fileHasher, hashUrls)
}

private suspend fun JsonElement.canonicalString(
    fileHasher: FileHasher,
    hashUrls: Boolean,
): String {
    return when (this) {
        is JsonObject -> {
            val builder = StringBuilder("{")
            val sorted = entries.sortedBy { it.key }
            for (index in sorted.indices) {
                if (index > 0) builder.append(',')
                val (key, value) = sorted[index]
                builder.append(JsonPrimitive(key).toString()).append(':')
                builder.append(value.canonicalString(fileHasher, hashUrls))
            }
            builder.append('}').toString()
        }

        is JsonArray -> {
            val builder = StringBuilder("[")
            for (index in indices) {
                if (index > 0) builder.append(',')
                builder.append(this[index].canonicalString(fileHasher, hashUrls))
            }
            builder.append(']').toString()
        }

        is JsonPrimitive -> if (hashUrls && isString && content.startsWith("file://")) {
            JsonPrimitive(fileKey(content, fileHasher)).toString()
        } else {
            toString()
        }

        else -> toString()
    }
}

/** 宽松解析：流式生成中的参数可能是半截 JSON，解析失败时退回原始字符串比较。 */
private val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

internal fun String.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    val hex = CharArray(digest.size * 2)
    digest.forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xFF
        hex[index * 2] = HEX_DIGITS[value ushr 4]
        hex[index * 2 + 1] = HEX_DIGITS[value and 0xF]
    }
    return String(hex)
}

private const val HEX_DIGITS = "0123456789abcdef"
