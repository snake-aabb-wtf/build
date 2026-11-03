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
    fun `tool result json paths stay literal`() = runTest {
        val a = toolResultMessage("file:///a.png")
        val b = toolResultMessage("file:///b.png")

        // fork 不会重写 ToolResult 里的 URL，按内容哈希会把「不同路径」擦掉
        assertNotEquals(messageFingerprint(a, FileHasher { "aaa" }), messageFingerprint(b, FileHasher { "aaa" }))
    }

    @Test
    fun `blank tool input equals empty object`() = runTest {
        fun call(input: String) = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.ToolCall("c", "t", input)),
        )
        fun tool(input: String) = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool("c", "t", input)),
        )

        assertEquals(messageFingerprint(call(""), noFiles), messageFingerprint(call("{}"), noFiles))
        assertEquals(messageFingerprint(call("   "), noFiles), messageFingerprint(call("{}"), noFiles))
        assertEquals(messageFingerprint(tool(""), noFiles), messageFingerprint(tool("{}"), noFiles))
    }

    @Test
    fun `document name and mime are compared`() = runTest {
        fun doc(name: String, mime: String) = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Document(url = "file:///x", fileName = name, mime = mime)),
        )

        assertNotEquals(
            messageFingerprint(doc("a.pdf", "application/pdf"), noFiles),
            messageFingerprint(doc("b.pdf", "application/pdf"), noFiles),
        )
        assertNotEquals(
            messageFingerprint(doc("a.pdf", "application/pdf"), noFiles),
            messageFingerprint(doc("a.pdf", "text/plain"), noFiles),
        )
    }

    @Test
    fun `tool approval reason is compared`() = runTest {
        fun tool(state: me.rerere.ai.ui.ToolApprovalState) = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool("c", "t", "{}", approvalState = state)),
        )

        assertNotEquals(
            messageFingerprint(tool(me.rerere.ai.ui.ToolApprovalState.Approved), noFiles),
            messageFingerprint(tool(me.rerere.ai.ui.ToolApprovalState.Denied("unsafe")), noFiles),
        )
        assertNotEquals(
            messageFingerprint(tool(me.rerere.ai.ui.ToolApprovalState.Denied("unsafe")), noFiles),
            messageFingerprint(tool(me.rerere.ai.ui.ToolApprovalState.Denied("wrong path")), noFiles),
        )
    }

    @Test
    fun `annotations are compared`() = runTest {
        fun message(title: String, url: String) = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("answer")),
            annotations = listOf(me.rerere.ai.ui.UIMessageAnnotation.UrlCitation(title, url)),
        )

        assertNotEquals(
            messageFingerprint(message("A", "https://a"), noFiles),
            messageFingerprint(message("B", "https://b"), noFiles),
        )
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

    @Test
    fun `fields inside the same part type are compared`() = runTest {
        suspend fun fp(part: UIMessagePart) = messageFingerprint(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part)),
            noFiles,
        )

        assertNotEquals(
            fp(UIMessagePart.ToolCall("c", "read", "{}")),
            fp(UIMessagePart.ToolCall("c", "write", "{}")),
        )
        assertNotEquals(fp(UIMessagePart.Reasoning("a")), fp(UIMessagePart.Reasoning("b")))
        assertNotEquals(
            fp(UIMessagePart.Tool("c", "t", """{"q":1}""")),
            fp(UIMessagePart.Tool("c", "t", """{"q":2}""")),
        )
        assertNotEquals(
            fp(UIMessagePart.Tool("c", "t", "{}", output = listOf(UIMessagePart.Text("a")))),
            fp(UIMessagePart.Tool("c", "t", "{}", output = listOf(UIMessagePart.Text("b")))),
        )
        assertNotEquals(
            fp(UIMessagePart.ServerTool("c", "t", status = me.rerere.ai.ui.ServerToolStatus.COMPLETED)),
            fp(UIMessagePart.ServerTool("c", "t", status = me.rerere.ai.ui.ServerToolStatus.FAILED)),
        )
        assertNotEquals(
            fp(UIMessagePart.ToolResult("c", "t", json.parseToJsonElement("""{"ok":true}"""), json.parseToJsonElement("{}"))),
            fp(UIMessagePart.ToolResult("c", "t", json.parseToJsonElement("""{"ok":false}"""), json.parseToJsonElement("{}"))),
        )
    }

    @Test
    fun `json key names and array order are compared`() = runTest {
        assertNotEquals(
            messageFingerprint(toolCallMessage("""{"a":1}"""), noFiles),
            messageFingerprint(toolCallMessage("""{"b":1}"""), noFiles),
        )
        assertNotEquals(
            messageFingerprint(toolCallMessage("""{"a":[1,2]}"""), noFiles),
            messageFingerprint(toolCallMessage("""{"a":[2,1]}"""), noFiles),
        )
    }

    @Test
    fun `file urls in tool inputs stay literal`() = runTest {
        val hasher = FileHasher { "same" }
        fun call(path: String) = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.ToolCall("c", "write", """{"path":"$path"}""")),
        )
        fun tool(path: String) = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool("c", "write", """{"path":"$path"}""")),
        )
        fun server(path: String) = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.ServerTool(
                    "c", "write",
                    input = json.parseToJsonElement("""{"path":"$path"}"""),
                    status = me.rerere.ai.ui.ServerToolStatus.COMPLETED,
                )
            ),
        )

        assertNotEquals(messageFingerprint(call("file:///a"), hasher), messageFingerprint(call("file:///b"), hasher))
        assertNotEquals(messageFingerprint(tool("file:///a"), hasher), messageFingerprint(tool("file:///b"), hasher))
        assertNotEquals(messageFingerprint(server("file:///a"), hasher), messageFingerprint(server("file:///b"), hasher))
        assertNotEquals(
            messageFingerprint(toolResultMessage("file:///a"), hasher),
            messageFingerprint(toolResultMessage("file:///b"), hasher),
        )
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
