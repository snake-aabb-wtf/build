package me.rerere.rikkahub.data.model.comparison

import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ComparisonGraphTest {
    private val noFiles = FileHasher { null }

    @Test
    fun `identical conversations merge into one chain`() = runTest {
        val a = conversation("A", user("hi"), assistant("hello"))
        val b = conversation("B", user("hi"), assistant("hello"))

        val graph = buildComparisonGraph(listOf(a, b), noFiles)

        assertEquals(2, graph.nodes.size)
        assertEquals(1, graph.rootIds.size)
        val root = graph.nodes.getValue(graph.rootIds.single())
        assertEquals(setOf(a.id, b.id), root.occurrences.map { it.conversationId }.toSet())
        assertEquals(1, root.childIds.size)
        val reply = graph.nodes.getValue(root.childIds.single())
        assertEquals(2, reply.occurrences.size)
        assertEquals(1, reply.depth)
    }

    @Test
    fun `first different reply splits immediately`() = runTest {
        val a = conversation("A", user("hi"), assistant("from a"))
        val b = conversation("B", user("hi"), assistant("from b"))

        val graph = buildComparisonGraph(listOf(a, b), noFiles)

        assertEquals(3, graph.nodes.size)
        val root = graph.nodes.getValue(graph.rootIds.single())
        assertEquals(2, root.occurrences.size)
        assertEquals(2, root.childIds.size)
        val children = root.childIds.map { graph.nodes.getValue(it) }
        assertEquals(setOf("from a", "from b"), children.map { it.message.toText() }.toSet())
        assertTrue(children.all { it.occurrences.size == 1 })
        assertTrue(children.all { it.depth == 1 })
    }

    @Test
    fun `same text after divergence does not merge back`() = runTest {
        val a = conversation("A", user("hi"), assistant("from a"), user("again"))
        val b = conversation("B", user("hi"), assistant("from b"), user("again"))

        val graph = buildComparisonGraph(listOf(a, b), noFiles)

        assertEquals(5, graph.nodes.size)
        assertEquals(1, graph.rootIds.size)
        val leaves = graph.nodes.values.filter { it.childIds.isEmpty() }
        assertEquals(2, leaves.size)
        assertTrue(leaves.all { it.message.toText() == "again" })
        assertNotEquals(leaves[0].id, leaves[1].id)
        assertNotEquals(leaves[0].parentId, leaves[1].parentId)
        assertTrue(leaves.all { it.occurrences.size == 1 })
    }

    @Test
    fun `empty conversation contributes nothing`() = runTest {
        val empty = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            title = "empty",
            messageNodes = emptyList(),
        )

        assertTrue(buildComparisonGraph(listOf(empty), noFiles).nodes.isEmpty())
        assertEquals(0, buildComparisonGraph(listOf(empty), noFiles).rootIds.size)

        val single = conversation("A", user("hi"))
        val mixed = buildComparisonGraph(listOf(empty, single), noFiles)
        assertEquals(1, mixed.nodes.size)
        assertEquals(1, mixed.rootIds.size)
    }

    @Test
    fun `repeated identical consecutive messages create two nodes`() = runTest {
        val a = conversation("A", user("same"), assistant("same"), user("same"))

        val graph = buildComparisonGraph(listOf(a), noFiles)

        assertEquals(3, graph.nodes.size)
        assertEquals(1, graph.rootIds.size)
        val depths = graph.nodes.values.sortedBy { it.depth }
        assertEquals(listOf(0, 1, 2), depths.map { it.depth })
        assertEquals(listOf(1, 1, 1), depths.map { it.childIds.size })
        assertTrue(graph.nodes.values.all { it.occurrences.size == 1 })
    }

    @Test
    fun `node with out of range select index is skipped`() = runTest {
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            title = "A",
            messageNodes = listOf(
                user("first").toMessageNode(),
                MessageNode(messages = listOf(user("skipped")), selectIndex = 3),
                user("last").toMessageNode(),
            ),
        )

        val graph = buildComparisonGraph(listOf(conversation), noFiles)

        assertEquals(2, graph.nodes.size)
        val root = graph.nodes.getValue(graph.rootIds.single())
        assertEquals("first", root.message.toText())
        assertEquals(1, root.childIds.size)
        assertEquals("last", graph.nodes.getValue(root.childIds.single()).message.toText())
    }

    @Test
    fun `occurrences keep the node id needed to jump back`() = runTest {
        val messageNode = user("hi").toMessageNode()
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            title = "A",
            messageNodes = listOf(messageNode),
        )

        val graph = buildComparisonGraph(listOf(conversation), noFiles)

        val occurrence = graph.nodes.getValue(graph.rootIds.single()).occurrences.single()
        assertEquals(conversation.id, occurrence.conversationId)
        assertEquals("A", occurrence.conversationTitle)
        assertEquals(messageNode.id, occurrence.messageNodeId)
        assertEquals(messageNode.currentMessage.id, occurrence.messageId)
    }

    private fun conversation(title: String, vararg messages: UIMessage) = Conversation(
        id = Uuid.random(),
        assistantId = Uuid.random(),
        title = title,
        messageNodes = messages.map { it.toMessageNode() },
    )

    private fun user(text: String): UIMessage = UIMessage.user(text)

    private fun assistant(text: String): UIMessage = UIMessage.assistant(text)
}
