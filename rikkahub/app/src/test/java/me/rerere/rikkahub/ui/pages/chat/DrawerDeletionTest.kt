package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.coroutineContext

class DrawerDeletionTest {
    @Test
    fun `drawer closes with a frame clock before deletion starts in the operation scope`() = runTest {
        val scopes = DeletionScopes(this)
        try {
            val events = mutableListOf<String>()
            val closed = CompletableDeferred<Unit>()
            launchDrawerDeletion(
                uiScope = scopes.ui,
                operationScope = scopes.operation,
                closeDrawer = {
                    withFrameNanos { events += "frame" }
                    closed.await()
                    events += "closed"
                },
                afterClose = {
                    assertNull(coroutineContext[MonotonicFrameClock])
                    events += "delete"
                },
            )

            runCurrent()
            assertTrue(scopes.errors.toString(), scopes.errors.isEmpty())
            assertEquals(listOf("frame"), events)

            closed.complete(Unit)
            runCurrent()
            assertTrue(scopes.errors.toString(), scopes.errors.isEmpty())
            assertEquals(listOf("frame", "closed", "delete"), events)
        } finally {
            scopes.close()
        }
    }

    @Test
    fun `leaving the chat page does not cancel deletion feedback or undo`() = runTest {
        val scopes = DeletionScopes(this)
        try {
            val events = mutableListOf<String>()
            val undo = CompletableDeferred<Unit>()
            launchDrawerDeletion(
                uiScope = scopes.ui,
                operationScope = scopes.operation,
                closeDrawer = {
                    withFrameNanos { }
                    events += "closed"
                },
                afterClose = {
                    events += "delete"
                    scopes.ui.cancel()
                    events += "snackbar"
                    undo.await()
                    events += "restore"
                },
            )

            runCurrent()
            assertTrue(scopes.errors.toString(), scopes.errors.isEmpty())
            assertEquals(listOf("closed", "delete", "snackbar"), events)

            undo.complete(Unit)
            runCurrent()
            assertTrue(scopes.errors.toString(), scopes.errors.isEmpty())
            assertEquals(listOf("closed", "delete", "snackbar", "restore"), events)
        } finally {
            scopes.close()
        }
    }

    private class DeletionScopes(testScope: TestScope) {
        val errors = mutableListOf<Throwable>()
        private val handler = CoroutineExceptionHandler { _, error -> errors += error }
        private val dispatcher = StandardTestDispatcher(testScope.testScheduler)
        private val clock = object : MonotonicFrameClock {
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(1L)
        }
        val ui = CoroutineScope(SupervisorJob() + dispatcher + handler + clock)
        val operation = CoroutineScope(SupervisorJob() + dispatcher + handler)

        fun close() {
            ui.cancel()
            operation.cancel()
        }
    }
}
