package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrawerDeletionUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() {
        operationScope.cancel()
    }

    @Test
    fun deletionWaitsForTheRealDrawerClosingAnimation() {
        lateinit var drawerState: DrawerState
        var deletedAfterClosing = false
        compose.setContent {
            MaterialTheme {
                drawerState = rememberDrawerState(DrawerValue.Open)
                val uiScope = rememberCoroutineScope()
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet {
                            TextButton(
                                onClick = {
                                    launchDrawerDeletion(
                                        uiScope = uiScope,
                                        operationScope = operationScope,
                                        closeDrawer = { drawerState.close() },
                                    ) {
                                        deletedAfterClosing = drawerState.isClosed
                                    }
                                },
                            ) {
                                Text("Delete conversation")
                            }
                        }
                    },
                ) {
                    Text("Chat")
                }
            }
        }

        compose.onNodeWithText("Delete conversation").performClick()

        compose.runOnIdle {
            assertTrue(drawerState.isClosed)
            assertTrue(deletedAfterClosing)
        }
    }
}
