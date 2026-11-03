package me.rerere.rikkahub.data.model.comparison

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ComparisonFingerprintTest {
    private val noFiles = FileHasher { null }
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ignores ids timestamps model and translation`() = runTest {
        val a = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi")))
        val b = a.copy(
            id = Uuid.random(),
            createdAt = LocalDateTime(2020, 1, 1, 0, 0),
            modelId = Uuid.random(),
            translation = "你好",
            isSynthetic = true,
            stepCount = 3,
        )

        assertEquals(messageFingerprint(a, noFiles), messageFingerprint(b, noFiles))
    }

    @Test
    fun `distinguishes role and text`() = runTest {
        val user = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi")))
        val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("hi")))
        val other = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi!")))

        assertNotEquals(messageFingerprint(user, noFiles), messageFingerprint(assistant, noFiles))
        assertNotEquals(messageFingerprint(user, noFiles), messageFingerprint(other, noFiles))
    }

    @Test
    fun `same content different local urls merge when bytes match`() = runTest {
        val hasher = FileHasher { "aaa" }
        val a = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image("file:///a.png")))
        val b = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image("file:///b.png")))

        assertEquals(messageFingerprint(a, hasher), messageFingerprint(b, hasher))
        // 读不到内容时退回 URL，两个不同的本地文件仍要区分开
        assertNotEquals(messageFingerprint(a, noFiles), messageFingerprint(b, noFiles))
    }

    @Test
    fun `hashes nested attachments inside tool output`() = runTest {
        assertEquals(
            messageFingerprint(toolMessage("file:///a.png"), FileHasher { "aaa" }),
            messageFingerprint(toolMessage("file:///b.png"), FileHasher { "aaa" }),
        )
        assertNotEquals(
            messageFingerprint(toolMessage("file:///a.png"), FileHasher { "x" }),
            messageFingerprint(toolMessage("file:///b.png"), FileHasher { "y" }),
        )
    }

    @Test
    fun `hashes attachments nested in tool result json`() = runTest {
        val a = toolResultMessage("file:///a.png")
        val b = toolResultMessage("file:///b.png")

        assertEquals(messageFingerprint(a, FileHasher { "aaa" }), messageFingerprint(b, FileHasher { "aaa" }))
        assertNotEquals(messageFingerprint(a, noFiles), messageFingerprint(b, noFiles))
    }

    @Test
    fun `json object key order does not matter`() = runTest {
        assertEquals(
            messageFingerprint(toolCallMessage("""{"a":1,"b":2}"""), noFiles),
            messageFingerprint(toolCallMessage("""{"b":2,"a":1}"""), noFiles),
        )
        assertNotEquals(
            messageFingerprint(toolCallMessage("""{"a":1,"b":2}"""), noFiles),
            messageFingerprint(toolCallMessage("""{"a":1,"b":3}"""), noFiles),
        )
    }

    @Test
    fun `context checkpoint is part of the fingerprint`() = runTest {
        val plain = UIMessage.user("summary")
        val checkpoint = plain.copy(isContextCheckpoint = true)

        assertNotEquals(messageFingerprint(plain, noFiles), messageFingerprint(checkpoint, noFiles))
    }

    @Test
    fun `fingerprint is a 64 char lowercase hex digest`() = runTest {
        val fp = messageFingerprint(UIMessage.user("hi"), noFiles)

        assertEquals(64, fp.length)
        assertTrue(Regex("[0-9a-f]{64}").matches(fp))
    }

    @Test
    fun `part boundaries matter`() = runTest {
        val together = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("ab")))
        val split = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("a"), UIMessagePart.Text("b")),
        )

        assertNotEquals(messageFingerprint(together, noFiles), messageFingerprint(split, noFiles))
    }

    @Test
    fun `no parts differs from empty text`() = runTest {
        val empty = UIMessage(role = MessageRole.USER, parts = emptyList())
        val blank = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("")))

        assertNotEquals(messageFingerprint(empty, noFiles), messageFingerprint(blank, noFiles))
    }

    @Test
    fun `every part type has its own fingerprint`() = runTest {
        val parts = listOf(
            UIMessagePart.Text("x"),
            UIMessagePart.Reasoning("x"),
            UIMessagePart.Search,
            UIMessagePart.Image("file:///x.png"),
            UIMessagePart.Video("file:///x.mp4"),
            UIMessagePart.Audio("file:///x.mp3"),
            UIMessagePart.Document(url = "file:///x.pdf", fileName = "x.pdf"),
            UIMessagePart.ToolCall(toolCallId = "c", toolName = "t", arguments = """{"a":1}"""),
            UIMessagePart.ToolResult(
                toolCallId = "c",
                toolName = "t",
                content = json.parseToJsonElement("""{"a":1}"""),
                arguments = json.parseToJsonElement("""{"a":1}"""),
            ),
            UIMessagePart.Tool(
                toolCallId = "c",
                toolName = "t",
                input = """{"a":1}""",
                output = emptyList(),
            ),
            UIMessagePart.ServerTool(
                toolCallId = "c",
                toolName = "t",
                input = json.parseToJsonElement("""{"a":1}"""),
                output = json.parseToJsonElement("""{"b":2}"""),
                status = me.rerere.ai.ui.ServerToolStatus.COMPLETED,
            ),
        )

        val fingerprints = parts.map { part ->
            messageFingerprint(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part)), noFiles)
        }

        assertEquals(parts.size, fingerprints.toSet().size)
    }

    @Test
    fun `tool input paths are compared literally not by file content`() = runTest {
        val a = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.ToolCall(toolCallId = "c", toolName = "write", arguments = """{"path":"file:///a.txt"}""")),
        )
        val b = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.ToolCall(toolCallId = "c", toolName = "write", arguments = """{"path":"file:///b.txt"}""")),
        )

        assertNotEquals(messageFingerprint(a, FileHasher { "same" }), messageFingerprint(b, FileHasher { "same" }))
    }

    private fun toolMessage(url: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = "call-$url",
                toolName = "render",
                input = """{"path":"/tmp/out.png"}""",
                output = listOf(UIMessagePart.Image(url)),
            )
        ),
    )

    private fun toolResultMessage(url: String) = UIMessage(
        role = MessageRole.TOOL,
        parts = listOf(
            UIMessagePart.ToolResult(
                toolCallId = "call-$url",
                toolName = "render",
                content = json.parseToJsonElement("""{"file":"$url","ok":true}"""),
                arguments = json.parseToJsonElement("""{"path":"x"}"""),
            )
        ),
    )

    private fun toolCallMessage(arguments: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.ToolCall(
                toolCallId = "call",
                toolName = "calc",
                arguments = arguments,
            )
        ),
    )
}
