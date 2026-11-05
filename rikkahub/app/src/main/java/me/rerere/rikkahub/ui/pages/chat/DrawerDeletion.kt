package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Close with Compose's frame clock; deletion and undo must survive leaving the chat page. */
internal fun launchDrawerDeletion(
    uiScope: CoroutineScope,
    operationScope: CoroutineScope,
    closeDrawer: suspend () -> Unit,
    afterClose: suspend () -> Unit,
) {
    uiScope.launch {
        closeDrawer()
        operationScope.launch { afterClose() }
    }
}
