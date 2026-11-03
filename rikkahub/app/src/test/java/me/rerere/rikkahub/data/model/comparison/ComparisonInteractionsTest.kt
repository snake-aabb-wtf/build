package me.rerere.rikkahub.data.model.comparison

import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class ComparisonInteractionsTest {
    private val noFiles = FileHasher { null }

    private fun conversation(title: String, vararg texts: String): Conversation = Conversation(
        assistantId = Uuid.random(),
        title = title,
        messageNodes = texts.map { UIMessage.user(it).toMessageNode() },
    )

    @Test
    fun `toggle adds then removes an id`() {
        val id = Uuid.random()

        val added = toggleComparisonSelection(emptySet(), id)
        assertEquals(setOf(id), added)
        assertFalse(canCompare(added))

        val removed = toggleComparisonSelection(added, id)
        assertTrue(removed.isEmpty())
    }

    @Test
    fun `selection stops at the cap and frees a slot when removed`() {
        val full = (1..COMPARISON_MAX_CONVERSATIONS).map { Uuid.random() }.toSet()
        val extra = Uuid.random()

        assertEquals(full, toggleComparisonSelection(full, extra))
        assertFalse(canAddConversation(full, extra))
        assertTrue(canAddConversation(toggleComparisonSelection(full, full.first()), extra))
        assertFalse(canAddConversation(setOf(Uuid.random()), Uuid.random(), limit = 1))
    }

    @Test
    fun `picker lists only unselected conversations up to the remaining slots`() {
        val candidates = (1..10).map { index ->
            ComparisonCandidate(Uuid.random(), "c$index", Instant.EPOCH)
        }
        val selected = candidates.take(6).map { it.id }.toSet()

        val available = availableCandidates(candidates, selected)

        assertEquals(2, available.size)
        assertTrue(available.none { it.id in selected })
        assertEquals(candidates.drop(6).take(2).map { it.id }, available.map { it.id })
        assertEquals(
            emptyList<ComparisonCandidate>(),
            availableCandidates(candidates, candidates.take(8).map { it.id }.toSet()),
        )
    }

    @Test
    fun `only a successful null lookup counts as missing`() {
        assertTrue(conversationLoad(null).shouldDrop())
        assertTrue(conversationLoad(conversation("trashed", "hi").copy(deletedAt = 1L)).shouldDrop())
        assertFalse(conversationLoad(conversation("alive", "hi")).shouldDrop())
        // 读库失败不能把用户的选择当成已删除
        assertFalse(ConversationLoad.Unavailable.shouldDrop())
    }

    @Test
    fun `no common start ignores empty conversations in the denominator`() = runTest {
        val a = conversation("A", "from a")
        val b = conversation("B", "from b")
        val empty = Conversation(
            assistantId = Uuid.random(),
            title = "empty",
            messageNodes = emptyList(),
        )

        val graph = buildComparisonGraph(listOf(a, b, empty), noFiles)

        assertTrue(hasNoCommonStart(listOf(a, b, empty), graph))
        assertFalse(hasNoCommonStart(listOf(a, a.copy()), graph))
        assertFalse(hasNoCommonStart(listOf(a, empty), graph))
    }

    @Test
    fun `shared first message is not reported as no common start`() = runTest {
        val a = conversation("A", "hi", "from a")
        val b = conversation("B", "hi", "from b")

        val graph = buildComparisonGraph(listOf(a, b), noFiles)

        assertEquals(1, graph.rootIds.size)
        assertFalse(hasNoCommonStart(listOf(a, b), graph))
    }

    @Test
    fun `compare needs two conversations`() {
        val a = Uuid.random()
        val b = Uuid.random()

        assertFalse(canCompare(setOf(a)))
        assertTrue(canCompare(setOf(a, b)))
        assertTrue(canCompare(setOf(a, b, Uuid.random())))
    }

    @Test
    fun `expanding a node expands its whole subtree but not siblings`() = runTest {
        val graph = buildComparisonGraph(
            listOf(
                conversation("A", "root", "left", "left-1", "left-2"),
                conversation("B", "root", "right", "right-1"),
            ),
            noFiles,
        )
        val root = graph.nodes.getValue(graph.rootIds.single())
        val left = graph.nodes.getValue(root.childIds.first())
        val right = graph.nodes.getValue(root.childIds.last())

        val expanded = expandedWithSubtree(graph, emptySet(), left.id)

        val leftChild = graph.nodes.getValue(left.childIds.single())
        val leftGrandChild = graph.nodes.getValue(leftChild.childIds.single())
        assertTrue(left.id in expanded)
        assertTrue(leftChild.id in expanded)
        assertTrue(leftGrandChild.id in expanded)
        assertFalse(right.id in expanded)
        assertFalse(root.id in expanded)
    }

    @Test
    fun `expanding a deep chain marks every node`() = runTest {
        val graph = buildComparisonGraph(
            listOf(conversation("A", *Array(50) { "m$it" })),
            noFiles,
        )

        val expanded = expandedWithSubtree(graph, emptySet(), graph.rootIds.single())

        assertEquals(graph.nodes.size, expanded.size)
    }

    @Test
    fun `unknown id leaves selection untouched`() {
        val graph = ComparisonGraph(rootIds = emptyList(), nodes = emptyMap())
        val expanded = setOf("x")

        assertEquals(expanded, expandedWithSubtree(graph, expanded, "missing"))
    }

    @Test
    fun `source occurrences keep one row per conversation`() {
        val first = Uuid.random()
        val second = Uuid.random()
        val node = ComparisonNode(
            id = "n",
            parentId = null,
            depth = 0,
            message = UIMessage.user("hi"),
            occurrences = listOf(
                occurrence(first, "A"),
                occurrence(first, "A again"),
                occurrence(second, "B"),
            ),
            childIds = emptyList(),
            fingerprint = "f",
        )

        val sources = sourceOccurrences(node)

        assertEquals(listOf(first, second), sources.map { it.conversationId })
        assertEquals("A", sources.first().conversationTitle)
    }

    @Test
    fun `large comparison flag uses strict threshold`() {
        assertFalse(isLargeComparison(graphOf(2000)))
        assertTrue(isLargeComparison(graphOf(2001)))
    }

    private fun occurrence(conversationId: Uuid, title: String): NodeOccurrence = NodeOccurrence(
        conversationId = conversationId,
        conversationTitle = title,
        messageId = Uuid.random(),
        messageNodeId = Uuid.random(),
    )

    private fun graphOf(count: Int): ComparisonGraph = ComparisonGraph(
        rootIds = if (count == 0) emptyList() else listOf("n0"),
        nodes = (0 until count).associate { index ->
            val id = "n$index"
            id to ComparisonNode(
                id = id,
                parentId = if (index == 0) null else "n${index - 1}",
                depth = index,
                message = UIMessage.user("m$index"),
                occurrences = emptyList(),
                childIds = if (index + 1 < count) listOf("n${index + 1}") else emptyList(),
                fingerprint = "f$index",
            )
        },
    )
}
