package me.rerere.rikkahub.data.repository

import android.content.Context
import android.content.ContextWrapper
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ConversationTrashTest {
    private lateinit var directory: File
    private lateinit var database: AppDatabase
    private lateinit var appScope: AppScope
    private lateinit var filesManager: FilesManager
    private lateinit var repository: ConversationRepository
    private val assistantId = Uuid.random()

    @Before
    fun setUp() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        directory = Files.createTempDirectory(app.cacheDir.toPath(), "conversation-trash-test-").toFile()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
        }
        database = AppDatabaseFactory.create(context, File(directory, "db").path)
        appScope = AppScope()
        filesManager = FilesManager(context, FilesRepository(database.managedFileDao()), appScope)
        repository = ConversationRepository(
            database.conversationDao(),
            database.messageNodeDao(),
            database.favoriteDao(),
            database,
            filesManager,
            MessageFtsManager(database),
        )
    }

    @After
    fun tearDown() = runBlocking {
        appScope.coroutineContext[Job]!!.cancelAndJoin()
        database.close()
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun movingToTrashPreservesMessagesAndFilesAndRestoringRebuildsSearch() = runBlocking {
        val saved = insert("apple")
        assertEquals(listOf(saved.conversation.id), visibleIds())
        assertEquals(listOf(saved.conversation.id.toString()), searchIds("apple"))
        assertIndexCount(saved.conversation.id, 1)

        repository.moveToTrash(saved.conversation.id)

        val trashed = repository.getTrashedConversations().first().single()
        assertEquals(saved.conversation.id, trashed.id)
        assertTrue(trashed.deletedAt > 0L)
        assertTrue(trashed.messageNodes.isEmpty())
        assertEquals(1, repository.countTrashed().first())
        assertTrue(visibleIds().isEmpty())
        assertTrue(repository.getConversationsOfAssistantPage(assistantId, 0, 20).items.isEmpty())
        assertTrue(repository.searchConversations("apple").first().isEmpty())
        assertTrue(searchIds("apple").isEmpty())
        assertIndexCount(saved.conversation.id, 0)
        assertRetained(saved)

        // 回收站实际传入的是没有消息节点的列表摘要。
        repository.restoreFromTrash(trashed)

        val restored = requireNotNull(repository.getConversationById(saved.conversation.id))
        assertEquals(0L, restored.deletedAt)
        assertEquals(0L, restored.archivedAt)
        assertTrue(repository.getTrashedConversations().first().isEmpty())
        assertEquals(listOf(saved.conversation.id), visibleIds())
        assertEquals(listOf(saved.conversation.id), repository.searchConversations("apple").first().map { it.id })
        assertEquals(listOf(saved.conversation.id.toString()), searchIds("apple"))
        assertIndexCount(saved.conversation.id, 1)
        assertRetained(saved)
    }

    @Test
    fun permanentlyDeletingATrashSummaryRemovesItsDataAndKeepsOtherConversations() = runBlocking {
        val deleted = insert("apple")
        val kept = insert("banana")
        repository.moveToTrash(deleted.conversation.id)
        val summary = repository.getTrashedConversations().first().single()
        assertTrue(summary.messageNodes.isEmpty())

        repository.permanentlyDeleteConversation(summary)
        awaitFileCleanup()

        assertDeleted(deleted)
        assertTrue(repository.getTrashedConversations().first().isEmpty())
        assertEquals(listOf(kept.conversation.id), visibleIds())
        assertEquals(listOf(kept.conversation.id.toString()), searchIds("regression"))
        assertIndexCount(kept.conversation.id, 1)
        assertRetained(kept)
    }

    @Test
    fun permanentlyDeletingAVisibleConversationIsRejectedWithoutRemovingData() = runBlocking {
        val saved = insert("apple")

        val failure = runCatching {
            repository.permanentlyDeleteConversation(saved.conversation)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(0L, repository.getDeletedAtById(saved.conversation.id))
        assertEquals(listOf(saved.conversation.id), visibleIds())
        assertEquals(listOf(saved.conversation.id.toString()), searchIds("apple"))
        assertIndexCount(saved.conversation.id, 1)
        assertRetained(saved)
    }

    @Test
    fun emptyingTrashDeletesAllAssistantsTrashAndKeepsVisibleAndArchivedConversations() = runBlocking {
        val first = insert("apple")
        val second = insert("banana", assistantId = Uuid.random())
        val visible = insert("cherry")
        val archived = insert("durian")
        repository.archiveConversation(archived.conversation.id)
        repository.moveToTrash(first.conversation.id)
        repository.moveToTrash(second.conversation.id)
        assertEquals(2, repository.countTrashed().first())

        repository.emptyTrash()
        awaitFileCleanup()

        assertDeleted(first)
        assertDeleted(second)
        assertEquals(0, repository.countTrashed().first())
        assertTrue(repository.getTrashedConversations().first().isEmpty())
        assertEquals(listOf(visible.conversation.id), visibleIds())
        assertEquals(listOf(visible.conversation.id.toString()), searchIds("regression"))
        assertEquals(listOf(archived.conversation.id), repository.getArchivedConversations(assistantId).first().map { it.id })
        assertIndexCount(visible.conversation.id, 1)
        assertIndexCount(archived.conversation.id, 1)
        assertRetained(visible)
        assertRetained(archived)
    }

    private suspend fun insert(word: String, assistantId: Uuid = this.assistantId): SavedConversation {
        val attachment = filesManager.saveManagedText(
            folder = FileFolders.UPLOAD,
            text = "attachment $word",
            displayName = "$word.txt",
        )
        val file = filesManager.getFile(attachment)
        val message = UIMessage.user("regression $word").let {
            it.copy(
                parts = it.parts + UIMessagePart.Document(
                    url = file.toUri().toString(),
                    fileName = attachment.displayName,
                    mime = attachment.mimeType,
                ),
            )
        }
        val conversation = Conversation(
            assistantId = assistantId,
            title = word,
            messageNodes = listOf(MessageNode.of(message)),
        )
        repository.insertConversation(conversation)
        return SavedConversation(conversation, attachment, file)
    }

    private suspend fun visibleIds() = repository.getConversationsOfAssistant(assistantId).first().map { it.id }

    private suspend fun searchIds(keyword: String) = repository.searchMessages(keyword).map { it.conversationId }

    private suspend fun assertRetained(saved: SavedConversation) {
        val loaded = requireNotNull(repository.getConversationById(saved.conversation.id))
        assertEquals(saved.conversation.messageNodes, loaded.messageNodes)
        assertEquals(1, database.messageNodeDao().getNodesOfConversation(saved.conversation.id.toString()).size)
        assertEquals(saved.attachment, filesManager.get(saved.attachment.id))
        assertEquals("attachment ${saved.conversation.title}", saved.file.readText())
    }

    private suspend fun assertDeleted(saved: SavedConversation) {
        assertNull(repository.getConversationById(saved.conversation.id))
        assertTrue(database.messageNodeDao().getNodesOfConversation(saved.conversation.id.toString()).isEmpty())
        assertIndexCount(saved.conversation.id, 0)
        assertNull(filesManager.get(saved.attachment.id))
        assertFalse(saved.file.exists())
    }

    private fun assertIndexCount(id: Uuid, expected: Int) {
        // 搜索本身也过滤 deleted_at，直接检查两张表，避免残留索引被过滤条件掩盖。
        listOf("message_fts", "message_fts_rowid").forEach { table ->
            database.openHelper.writableDatabase.query(
                "SELECT COUNT(*) FROM $table WHERE conversation_id = ?",
                arrayOf(id.toString()),
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals(table, expected, it.getInt(0))
            }
        }
    }

    private suspend fun awaitFileCleanup() {
        appScope.coroutineContext[Job]!!.children.toList().joinAll()
    }

    private data class SavedConversation(
        val conversation: Conversation,
        val attachment: ManagedFileEntity,
        val file: File,
    )
}
