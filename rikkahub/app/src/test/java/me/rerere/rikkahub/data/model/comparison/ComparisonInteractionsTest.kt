package me.rerere.rikkahub.data.model.comparison

import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun `branch signature follows the selected branch`() {
        val node = MessageNode(
            messages = listOf(UIMessage.user("branch a"), UIMessage.user("branch b")),
            selectIndex = 0,
        )
        val base = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(UIMessage.user("hi").toMessageNode(), node),
        )

        val switched = base.copy(
            messageNodes = listOf(base.messageNodes[0], node.copy(selectIndex = 1)),
        )

        assertNotEquals(branchSignature(base), branchSignature(switched))
    }

    @Test
    fun `branch signature ignores title and timestamps`() {
        val base = conversation("A", "hi", "hello")

        val renamed = base.copy(
            title = "renamed",
            updateAt = Instant.parse("2030-01-01T00:00:00Z"),
        )

        assertEquals(branchSignature(base), branchSignature(renamed))
    }

    @Test
    fun `branch signature tracks node count and last message id`() {
        val base = conversation("A", "hi", "hello")
        val longer = conversation("A", "hi", "hello", "again")

        assertNotEquals(branchSignature(base), branchSignature(longer))

        val last = base.messageNodes.last()
        val replacedLast = base.copy(
            messageNodes = base.messageNodes.dropLast(1) +
                last.copy(messages = listOf(UIMessage.user("regenerated"))),
        )
        assertNotEquals(branchSignature(base), branchSignature(replacedLast))
    }

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
    fun `picker lists every unselected conversation`() {
        val candidates = (1..10).map { index ->
            ComparisonCandidate(Uuid.random(), "c$index", Instant.EPOCH)
        }
        val selected = candidates.take(6).map { it.id }.toSet()

        val available = availableCandidates(candidates, selected)

        // 已选 6 个时仍要看到全部 4 条候选，不能按剩余名额截断
        assertEquals(4, available.size)
        assertTrue(available.none { it.id in selected })
        assertEquals(candidates.drop(6).map { it.id }, available.map { it.id })

        // 已选 7 个时剩 1 个名额，但选择器仍列出全部 3 条，是否加得进由 canAddConversation 判定
        val seven = candidates.take(7).map { it.id }.toSet()
        assertEquals(3, availableCandidates(candidates, seven).size)
        assertTrue(canAddConversation(seven, candidates[7].id))

        // 已选满 8 个：选择器为空，再点任何候选都不能加入
        val eight = candidates.take(8).map { it.id }.toSet()
        assertFalse(canAddConversation(eight, candidates[8].id))
        assertEquals(
            emptyList<ComparisonCandidate>(),
            availableCandidates(candidates, eight),
        )
    }

    @Test
    fun `duplicate candidate rows are collapsed`() {
        val id = Uuid.random()
        val candidates = listOf(
            ComparisonCandidate(id, "first", Instant.EPOCH),
            ComparisonCandidate(id, "duplicate", Instant.EPOCH),
        )

        val available = availableCandidates(candidates, emptySet())

        assertEquals(1, available.size)
        assertEquals("first", available.single().title)
    }

    @Test
    fun `empty saved ids stay empty instead of resurrecting route ids`() {
        val first = Uuid.random()
        val second = Uuid.random()
        val fallback = listOf(first.toString(), second.toString())

        // 删到 0 个：存的是空列表，必须保持空
        assertEquals(emptyList<Uuid>(), restoreComparisonIds(saved = emptyList(), fallback = fallback))
        // 没存过才用路由参数
        assertEquals(listOf(first, second), restoreComparisonIds(saved = null, fallback = fallback))
        // 超出上限时只取前 8 个
        val many = (1..12).map { Uuid.random().toString() }
        assertEquals(
            COMPARISON_MAX_CONVERSATIONS,
            restoreComparisonIds(saved = many, fallback = emptyList()).size,
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
