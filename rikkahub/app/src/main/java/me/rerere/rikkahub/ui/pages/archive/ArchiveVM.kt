package me.rerere.rikkahub.ui.pages.archive

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

private const val TAG = "ArchiveVM"

class ArchiveVM(
    assistantId: String,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
) : ViewModel() {
    private val assistantUuid = Uuid.parse(assistantId)

    val conversations: StateFlow<List<Conversation>> =
        conversationRepo.getArchivedConversations(assistantUuid)
            .catch {
                Log.e(TAG, "Error: ${it.message}")
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun restore(id: Uuid) {
        viewModelScope.launch {
            chatService.unarchiveConversation(id)
        }
    }

    fun delete(id: Uuid) {
        viewModelScope.launch {
            chatService.moveToTrash(id)
        }
    }
}
