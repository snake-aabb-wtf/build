package me.rerere.rikkahub.data.model.comparison

import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.uuid.Uuid

class ComparisonEndpointsTest {
    private val noFiles = FileHasher { null }

    private fun conversation(title: String, vararg texts: String): Conversation = Conversation(
        assistantId = Uuid.random(),
        title = title,
        messageNodes = texts.map { UIMessage.user(it).toMessageNode() },
    )

    @Test
    fun `every conversation ends at its own deepest node`() = runTest {
        val graph = buildComparisonGraph(
            listOf(
                conversation("A", "hi", "from a"),
                conversation("B", "hi", "from b", "more b"),
            ),
            noFiles,
        )

        val endpoints = conversationEndpoints(graph)

        assertEquals(2, endpoints.size)
        assertFalse(graph.rootIds.single() in endpoints)
        // 两个分叉后的终点各算一次；共享前缀不算终点
        assertEquals(2, graph.nodes.values.count { it.occurrences.size == 1 && it.childIds.isEmpty() })
    }

    @Test
    fun `shared tail is the endpoint for both conversations`() = runTest {
        val graph = buildComparisonGraph(
            listOf(
                conversation("A", "hi", "bye"),
                conversation("B", "hi", "bye"),
            ),
            noFiles,
        )

        val endpoints = conversationEndpoints(graph)

        assertEquals(1, endpoints.size)
        assertEquals(graph.nodes.values.maxBy { it.depth }.id, endpoints.single())
    }
}
