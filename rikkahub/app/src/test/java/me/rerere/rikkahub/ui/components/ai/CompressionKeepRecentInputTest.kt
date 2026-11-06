package me.rerere.rikkahub.ui.components.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class CompressionKeepRecentInputTest {
    @Test
    fun acceptsNormalizedDecimalInput() {
        listOf(
            "0" to 0,
            "20" to 20,
            "000012" to 12,
            "００１２" to 12,
            " 12 " to 12,
            "0".repeat(100) to 0,
            "0".repeat(100) + "12" to 12,
        ).forEach { (text, expected) ->
            val result = validateCompressionKeepRecent(text, 21)

            assertEquals(text, expected, result.value)
            assertEquals(text, 20, result.maximum)
            assertNull(text, result.error)
        }
    }

    @Test
    fun rejectsEmptyAndNonIntegerInput() {
        listOf("", "   ").forEach { text ->
            val result = validateCompressionKeepRecent(text, 21)

            assertNull(text, result.value)
            assertEquals(text, CompressionInputError.EMPTY, result.error)
        }
        listOf("-1", "+1", "1.5", "1a2", "1 2", "1e2", "١٢", "2147483648", "9".repeat(100))
            .forEach { text ->
                val result = validateCompressionKeepRecent(text, 21)

                assertNull(text, result.value)
                assertEquals(text, CompressionInputError.INVALID_INTEGER, result.error)
            }
    }

    @Test
    fun usesLiveMessageCountBounds() {
        assertEquals(20, validateCompressionKeepRecent("20", 21).value)
        assertEquals(CompressionInputError.OUT_OF_RANGE, validateCompressionKeepRecent("21", 21).error)
        assertEquals(CompressionInputError.OUT_OF_RANGE, validateCompressionKeepRecent("20", 20).error)
        assertEquals(0, validateCompressionKeepRecent("0", 1).value)
        val outOfRange = validateCompressionKeepRecent("21", 21)
        assertNull(outOfRange.value)
        assertEquals(20, outOfRange.maximum)

        listOf(0, -1, Int.MIN_VALUE).forEach { count ->
            listOf("0", "", "invalid").forEach { text ->
                val result = validateCompressionKeepRecent(text, count)

                assertNull(result.value)
                assertNull(result.maximum)
                assertEquals(CompressionInputError.NOTHING_TO_COMPRESS, result.error)
            }
        }
        val tooLarge = validateCompressionKeepRecent("2147483647", Int.MAX_VALUE)
        assertNull(tooLarge.value)
        assertEquals(2147483646, tooLarge.maximum)
        assertEquals(CompressionInputError.OUT_OF_RANGE, tooLarge.error)
    }

    @Test
    fun stepsOnlyWithinTheSameValidRange() {
        assertEquals("0", stepCompressionKeepRecent("0", 21, increase = false))
        assertEquals("20", stepCompressionKeepRecent("20", 21, increase = true))
        assertEquals("13", stepCompressionKeepRecent("００１２", 21, increase = true))
        assertEquals("11", stepCompressionKeepRecent(" 00012 ", 21, increase = false))
        assertEquals("1", stepCompressionKeepRecent("0", 21, increase = true))
        assertEquals("19", stepCompressionKeepRecent("20", 21, increase = false))
        assertEquals("０００", stepCompressionKeepRecent("０００", 21, increase = false))
        assertEquals(" 020 ", stepCompressionKeepRecent(" 020 ", 21, increase = true))

        listOf("", "   ", "-1", "+1", "1.5", "1a2", "1 2", "1e2", "١٢", "21", "2147483648")
            .forEach { text ->
                listOf(false, true).forEach { increase ->
                    assertEquals(text, text, stepCompressionKeepRecent(text, 21, increase))
                }
            }
        listOf(false, true).forEach { increase ->
            assertEquals("0", stepCompressionKeepRecent("0", 0, increase))
            assertEquals("0", stepCompressionKeepRecent("0", 1, increase))
        }
        assertEquals(
            "2147483646",
            stepCompressionKeepRecent("2147483646", Int.MAX_VALUE, increase = true),
        )
    }

    @Test
    fun countsOnlyNodesAfterTheLastSelectedCheckpoint() {
        val plain = List(4) { UIMessage.user("message $it").toMessageNode() }
        assertEquals(4, conversation(plain).messagesAfterLastContextCheckpoint())
        val checkpoint = UIMessage.user("summary").copy(isContextCheckpoint = true).toMessageNode()
        assertEquals(
            1,
            conversation(listOf(plain[0], checkpoint, plain[1], checkpoint, plain[2]))
                .messagesAfterLastContextCheckpoint(),
        )
        assertEquals(0, conversation(plain + checkpoint).messagesAfterLastContextCheckpoint())
        assertEquals(1, conversation(plain.take(1)).messagesAfterLastContextCheckpoint())
        assertEquals(0, conversation(emptyList()).messagesAfterLastContextCheckpoint())

        val alternatives = MessageNode(
            messages = listOf(UIMessage.user("regular"), UIMessage.user("summary").copy(isContextCheckpoint = true)),
            selectIndex = 0,
        )
        val original = conversation(listOf(plain[0], alternatives, plain[1], plain[2]))
        assertEquals(4, original.messagesAfterLastContextCheckpoint())
        val switched = original.copy(
            messageNodes = original.messageNodes.toMutableList().also {
                it[1] = alternatives.copy(selectIndex = 1)
            },
        )
        assertEquals(2, switched.messagesAfterLastContextCheckpoint())
        assertEquals(0, original.messageNodes[1].selectIndex)
        assertEquals(4, original.messageNodes.size)
    }

    private fun conversation(nodes: List<MessageNode>) = Conversation(
        assistantId = Uuid.random(),
        messageNodes = nodes,
    )
}
