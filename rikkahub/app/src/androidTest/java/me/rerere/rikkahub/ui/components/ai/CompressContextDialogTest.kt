package me.rerere.rikkahub.ui.components.ai

import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Job
import me.rerere.rikkahub.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompressContextDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val messageCount = mutableIntStateOf(21)
    private val visible = mutableStateOf(true)
    private val submissions = mutableListOf<Pair<String, Int>>()
    private val jobs = mutableListOf<Job>()
    private var dismissals = 0

    @After
    fun cancelOutstandingJobs() {
        compose.runOnIdle { jobs.forEach { it.cancel() } }
    }

    @Test
    fun submitsExactNormalizedKeepCount() {
        showDialog()
        keepCount().performTextReplacement("００１２")
        compose.onNode(
            hasSetTextAction() and hasText(context.getString(R.string.chat_page_compress_additional_prompt))
        ).performTextReplacement("Preserve the original wording.")
        confirm().performClick()

        compose.runOnIdle {
            assertEquals(listOf("Preserve the original wording." to 12), submissions)
        }
    }

    @Test
    fun submitsDefaultZero() {
        showDialog()
        confirm().performClick()

        compose.runOnIdle { assertEquals(listOf("" to 0), submissions) }
    }

    @Test
    fun invalidTextCannotStartCompression() {
        showDialog()
        listOf("", "-1", "1.5", "1a2", "9".repeat(100), "21").forEach { text ->
            keepCount().performTextReplacement(text)
            confirm().assertIsNotEnabled().performClick()
            decrease().assertIsNotEnabled()
            increase().assertIsNotEnabled()
            compose.runOnIdle { assertTrue(submissions.isEmpty()) }
        }

        keepCount().performTextReplacement("12")
        confirm().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("" to 12), submissions) }
    }

    @Test
    fun buttonsRespectBoundsAndNoMessages() {
        messageCount.intValue = 1
        showDialog()
        confirm().assertIsEnabled()
        decrease().assertIsNotEnabled()
        increase().assertIsNotEnabled()

        compose.runOnIdle { messageCount.intValue = 0 }
        confirm().assertIsNotEnabled()
        decrease().assertIsNotEnabled()
        increase().assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.chat_page_compress_nothing_to_compress))
            .assertIsDisplayed()

        compose.runOnIdle { messageCount.intValue = 21 }
        decrease().assertIsNotEnabled()
        increase().assertIsEnabled().performClick()
        keepCount().assert(hasText("1"))
        decrease().assertIsEnabled().performClick()
        keepCount().assert(hasText("0"))
        keepCount().performTextReplacement("20")
        increase().assertIsNotEnabled()
        decrease().assertIsEnabled().performClick()
        keepCount().assert(hasText("19"))
        increase().assertIsEnabled().performClick()
        keepCount().assert(hasText("20"))
        compose.runOnIdle { assertTrue(submissions.isEmpty()) }
    }

    @Test
    fun messageCountChangeInvalidatesWithoutClamping() {
        showDialog()
        keepCount().performTextReplacement("20")
        confirm().assertIsEnabled()

        compose.runOnIdle { messageCount.intValue = 20 }

        keepCount().assert(hasText("20"))
        confirm().assertIsNotEnabled().performClick()
        compose.onNodeWithText(context.getString(R.string.chat_page_compress_keep_recent_out_of_range, 19))
            .assertIsDisplayed()
        compose.runOnIdle { assertTrue(submissions.isEmpty()) }
    }

    @Test
    fun cancelsOnlyTheStartedJob() {
        showDialog()
        confirm().performClick()
        compose.onNodeWithText(context.getString(R.string.chat_page_compressing)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()

        compose.runOnIdle {
            assertEquals(1, jobs.size)
            assertTrue(jobs.single().isCancelled)
            assertEquals(0, dismissals)
        }
        keepCount().assert(hasText("0"))
    }

    @Test
    fun cancelBeforeStartingOnlyDismissesAndReopenResetsInput() {
        showDialog()
        keepCount().performTextReplacement("12")
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.runOnIdle {
            assertEquals(1, dismissals)
            assertTrue(submissions.isEmpty())
            assertTrue(jobs.isEmpty())
            visible.value = true
        }
        keepCount().assert(hasText("0"))
    }

    private fun showDialog() {
        compose.setContent {
            MaterialExpressiveTheme {
                if (visible.value) {
                    CompressContextDialog(
                        messagesAfterLastCheckpoint = messageCount.intValue,
                        onDismiss = {
                            dismissals++
                            visible.value = false
                        },
                        onConfirm = { prompt, count ->
                            submissions.add(prompt to count)
                            Job().also { jobs.add(it) }
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun keepCount() = compose.onNode(
        hasSetTextAction() and hasText(context.getString(R.string.chat_page_compress_keep_recent))
    )

    private fun confirm() = compose.onNodeWithText(context.getString(R.string.confirm))

    private fun decrease() = compose.onNodeWithContentDescription(
        context.getString(R.string.chat_page_compress_keep_recent_decrease)
    )

    private fun increase() = compose.onNodeWithContentDescription(
        context.getString(R.string.chat_page_compress_keep_recent_increase)
    )
}
