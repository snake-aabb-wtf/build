package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationStatusMapperTest {
    @Test
    fun `entity round trip keeps archive and trash flags`() {
        assertRoundTrip(archivedAt = 111L, deletedAt = 0L)
        assertRoundTrip(archivedAt = 0L, deletedAt = 333L)
    }

    @Test
    fun `summary mapping keeps flags`() {
        val summary = ConversationMapper.toSummary(
            LightConversationEntity(
                id = "00000000-0000-0000-0000-000000000001",
                assistantId = "00000000-0000-0000-0000-000000000002",
                title = "t", isPinned = false, createAt = 0, updateAt = 0,
                folderId = "", archivedAt = 444L, deletedAt = 0L,
            )
        )
        assertEquals(444L, summary.archivedAt)
        assertEquals(0L, summary.deletedAt)
    }

    private fun assertRoundTrip(archivedAt: Long, deletedAt: Long) {
        val conversation = sampleConversation(archivedAt = archivedAt, deletedAt = deletedAt)
        val entity = ConversationMapper.toEntity(conversation)
        assertEquals(archivedAt, entity.archivedAt)
        assertEquals(deletedAt, entity.deletedAt)

        val back = ConversationMapper.toConversation(entity, emptyList())
        assertEquals(archivedAt, back.archivedAt)
        assertEquals(deletedAt, back.deletedAt)
    }

    private fun sampleConversation(archivedAt: Long, deletedAt: Long) = Conversation(
        id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
        assistantId = Uuid.parse("00000000-0000-0000-0000-000000000002"),
        messageNodes = emptyList(),
        archivedAt = archivedAt,
        deletedAt = deletedAt,
    )
}
