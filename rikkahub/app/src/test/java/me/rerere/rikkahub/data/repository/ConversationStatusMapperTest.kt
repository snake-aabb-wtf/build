package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationStatusMapperTest {
    @Test
    fun `entity round trip keeps archive and trash flags`() {
        val conversation = sampleConversation(archivedAt = 111L, deletedAt = 0L)
        val entity = ConversationMapper.toEntity(conversation)
        assertEquals(111L, entity.archivedAt)
        assertEquals(0L, entity.deletedAt)

        val back = ConversationMapper.toConversation(entity, emptyList())
        assertEquals(111L, back.archivedAt)
        assertEquals(0L, back.deletedAt)
    }

    @Test
    fun `summary mapping keeps flags`() {
        val summary = ConversationMapper.toSummary(
            LightConversationEntity(
                id = "00000000-0000-0000-0000-000000000001",
                assistantId = "00000000-0000-0000-0000-000000000002",
                title = "t", isPinned = false, createAt = 0, updateAt = 0,
                folderId = "", archivedAt = 0, deletedAt = 222L,
            )
        )
        assertEquals(222L, summary.deletedAt)
    }

    private fun sampleConversation(archivedAt: Long, deletedAt: Long) = Conversation(
        id = Uuid.parse("00000000-0000-0000-0000-000000000001"),
        assistantId = Uuid.parse("00000000-0000-0000-0000-000000000002"),
        messageNodes = emptyList(),
        archivedAt = archivedAt,
        deletedAt = deletedAt,
    )
}
