package me.rerere.rikkahub.data.model.comparison

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.UIMessage
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
    message.parts.forEach { part ->
        builder.segment(partFingerprint(part, fileHasher))
    }
    return builder.toString().sha256Hex()
}

/** 每段先写长度，避免不同切分拼出同一个字符串。 */
private fun StringBuilder.segment(value: String): StringBuilder {
    append(value.length).append('\u0000').append(value).append('\u0000')
}

@Suppress("DEPRECATION")
private suspend fun partFingerprint(part: UIMessagePart, fileHasher: FileHasher): String {
    return when (part) {
        is UIMessagePart.Text -> "T" + part.text
        is UIMessagePart.Reasoning -> "R" + part.reasoning
        is UIMessagePart.Search -> "S"
        is UIMessagePart.Image -> "FImage" + fileKey(part.url, fileHasher)
        is UIMessagePart.Video -> "FVideo" + fileKey(part.url, fileHasher)
        is UIMessagePart.Audio -> "FAudio" + fileKey(part.url, fileHasher)
        is UIMessagePart.Document -> "FDocument" + fileKey(part.url, fileHasher)
        // toolCallId 在 fork 时会换新，不能参与比较
        is UIMessagePart.ToolCall -> "TC" + part.toolName + canonicalJson(part.arguments, fileHasher)
        is UIMessagePart.ToolResult ->
            "TR" + part.toolName + canonicalJsonElement(part.arguments, fileHasher) +
                canonicalJsonElement(part.content, fileHasher)

        is UIMessagePart.Tool ->
            "TO" + part.toolName + canonicalJson(part.input, fileHasher) +
                partsFingerprint(part.output, fileHasher)

        is UIMessagePart.ServerTool ->
            "ST" + part.toolName + part.status.name +
                (part.input?.let { canonicalJsonElement(it, fileHasher) } ?: "") +
                (part.output?.let { canonicalJsonElement(it, fileHasher) } ?: "")

        else -> part::class.simpleName ?: "?"
    }
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

private suspend fun fileKey(url: String, fileHasher: FileHasher): String {
    return fileHasher.hash(url)?.let { "sha256:$it" } ?: url
}

/** 按键名排序的 JSON；解析失败（流式中的半截参数）时退回原始字符串。 */
private suspend fun canonicalJson(raw: String, fileHasher: FileHasher): String {
    val element = runCatching { lenientJson.parseToJsonElement(raw) }.getOrNull()
        ?: return "@raw:$raw"
    return element.canonicalString { url -> fileKey(url, fileHasher) }
}

private suspend fun canonicalJsonElement(element: JsonElement, fileHasher: FileHasher): String {
    return element.canonicalString { url -> fileKey(url, fileHasher) }
}

private suspend fun JsonElement.canonicalString(hashUrl: suspend (String) -> String): String {
    return when (this) {
        is JsonObject -> entries
            .sortedBy { it.key }
            .joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
                JsonPrimitive(key).toString() + ":" + value.canonicalString(hashUrl)
            }

        is JsonArray -> joinToString(separator = ",", prefix = "[", postfix = "]") {
            it.canonicalString(hashUrl)
        }

        is JsonPrimitive -> if (isString && content.startsWith("file://")) {
            JsonPrimitive(hashUrl(content)).toString()
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
