package me.rerere.rikkahub.ui.components.ui.permission

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.rikkahub.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalNetworkPermissionCardTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var state: PermissionState
    private lateinit var permissionContext: LocalNetworkPermissionContext
    private lateinit var activity: ComponentActivity
    private val singleLaunches = mutableListOf<String>()
    private val multipleLaunches = mutableListOf<List<String>>()

    @Test
    fun cardRequestsPermissionOnlyAfterClick() {
        showCard()

        compose.runOnIdle {
            assertEquals(0, singleLaunches.size)
            assertEquals(0, multipleLaunches.size)
        }
        compose.onNodeWithTag(CARD_TAG).performClick()

        compose.runOnIdle {
            assertEquals(listOf(Manifest.permission.ACCESS_LOCAL_NETWORK), singleLaunches)
            assertEquals(0, multipleLaunches.size)
        }
    }

    @Test
    fun refreshUpdatesGrantedState() {
        showCard()

        compose.runOnIdle {
            assertFalse(state.allRequiredPermissionsGranted)
        }
        compose.onNodeWithTag(CARD_TAG).assertIsDisplayed()

        compose.runOnIdle {
            permissionContext.granted = true
            state.refreshPermissionStates()
            assertTrue(state.allRequiredPermissionsGranted)
        }

        compose.onNodeWithTag(CARD_TAG).assertDoesNotExist()
    }

    @Test
    fun permanentDenialUsesExistingRationale() {
        showCard()
        compose.onNodeWithTag(CARD_TAG).performClick()
        compose.runOnIdle {
            assertEquals(listOf(Manifest.permission.ACCESS_LOCAL_NETWORK), singleLaunches)
            state.handleSinglePermissionResult(Manifest.permission.ACCESS_LOCAL_NETWORK, false)
        }

        compose.onNodeWithTag(CARD_TAG).performClick()

        compose.runOnIdle {
            assertEquals(1, singleLaunches.size)
            assertEquals(0, multipleLaunches.size)
            assertTrue(state.showRationaleDialog)
        }
        compose.onNodeWithText(activity.getString(R.string.permission_go_to_settings)).assertIsDisplayed()
        compose.onNodeWithText(activity.getString(R.string.cancel)).performClick()
        compose.runOnIdle {
            assertFalse(state.showRationaleDialog)
        }
        compose.onNodeWithTag(CARD_TAG).assertIsDisplayed().performClick()
        compose.onNodeWithText(activity.getString(R.string.permission_go_to_settings)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, singleLaunches.size)
        }
    }

    @Test
    fun stateFactoryRespectsDeviceSdk() {
        var factoryState: PermissionState? = null
        compose.setContent {
            MaterialExpressiveTheme {
                factoryState = rememberLocalNetworkPermissionState()
            }
        }

        compose.runOnIdle {
            if (Build.VERSION.SDK_INT < 37) {
                assertNull(factoryState)
            } else {
                assertNotNull(factoryState)
                assertTrue(factoryState!!.permissionStates.containsKey(Manifest.permission.ACCESS_LOCAL_NETWORK))
            }
        }
    }

    private fun showCard() {
        compose.setContent {
            activity = LocalContext.current as ComponentActivity
            state = remember {
                permissionContext = LocalNetworkPermissionContext(activity)
                PermissionState(
                    permissions = setOf(
                        PermissionInfo(
                            permission = Manifest.permission.ACCESS_LOCAL_NETWORK,
                            displayName = { Text(stringResource(R.string.permission_local_network)) },
                            usage = { Text(stringResource(R.string.permission_local_network_desc)) },
                            required = true,
                        )
                    ),
                    context = permissionContext,
                    activity = activity,
                ).apply {
                    setPermissionLaunchers(
                        multiplePermissionLauncher = RecordingPermissionLauncher(
                            ActivityResultContracts.RequestMultiplePermissions()
                        ) { multipleLaunches.add(it.toList()) },
                        singlePermissionLauncher = RecordingPermissionLauncher(
                            ActivityResultContracts.RequestPermission()
                        ) { singleLaunches.add(it) },
                    )
                }
            }
            MaterialExpressiveTheme {
                if (!state.allRequiredPermissionsGranted) {
                    LocalNetworkPermissionCard(
                        permissionState = state,
                        modifier = Modifier.testTag(CARD_TAG),
                    )
                }
            }
        }
    }

    private class LocalNetworkPermissionContext(base: Context) : ContextWrapper(base) {
        var granted = false

        override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
            if (permission == Manifest.permission.ACCESS_LOCAL_NETWORK) {
                return if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
            }
            return super.checkPermission(permission, pid, uid)
        }
    }

    private class RecordingPermissionLauncher<I>(
        override val contract: ActivityResultContract<I, *>,
        private val record: (I) -> Unit,
    ) : ActivityResultLauncher<I>() {
        override fun launch(input: I, options: ActivityOptionsCompat?) {
            record(input)
        }

        override fun unregister() = Unit
    }

    private companion object {
        const val CARD_TAG = "local-network-permission-card"
    }
}
