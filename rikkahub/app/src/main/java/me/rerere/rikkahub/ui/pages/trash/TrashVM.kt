package me.rerere.rikkahub.ui.pages.trash

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

private const val TAG = "TrashVM"

class TrashVM(
    private val conversationRepo: ConversationRepository,
    settingsStore: SettingsStore,
    private val chatService: ChatService,
) : ViewModel() {
    val conversations: StateFlow<List<Conversation>> =
        conversationRepo.getTrashedConversations()
            .catch {
                Log.e(TAG, "Error: ${it.message}")
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val count: StateFlow<Int> =
        conversationRepo.countTrashed()
            .catch {
                Log.e(TAG, "Error: ${it.message}")
                emit(0)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val assistants: StateFlow<List<Assistant>> =
        settingsStore.settingsFlow
            .map { it.assistants }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 找不到对应助手（已删除或 id 不匹配）时返回 null，由页面显示「未知助手」 */
    fun assistantName(assistantId: Uuid, assistants: List<Assistant>): String? {
        return assistants.firstOrNull { it.id == assistantId }
            ?.name
            ?.takeIf { it.isNotBlank() }
    }

    fun restore(conversation: Conversation) {
        viewModelScope.launch {
            chatService.restoreFromTrash(conversation)
        }
    }

    fun deleteForever(id: Uuid) {
        viewModelScope.launch {
            chatService.permanentlyDelete(id)
        }
    }

    fun empty() {
        viewModelScope.launch {
            chatService.emptyTrash()
        }
    }
}
