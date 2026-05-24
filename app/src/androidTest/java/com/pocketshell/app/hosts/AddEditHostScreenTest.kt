package com.pocketshell.app.hosts

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose-UI instrumentation tests for the Add Host form (issue #111).
 *
 * The screen renders against an in-memory Room database so we exercise
 * the real [AddEditHostViewModel] including the per-field error and
 * focus-routing behaviour the issue calls for.
 *
 * Covered acceptance criteria:
 *
 * - Empty submit highlights every required field with per-field error
 *   text and moves focus to the first invalid field.
 * - The CTA is disabled until every required field is non-empty AND port
 *   is in range AND a key is selected.
 * - Invalid port shows a per-field error and blocks save.
 * - A fully valid form saves and writes a row.
 *
 * The screen uses [androidx.compose.ui.platform.testTag] tags exposed as
 * top-level constants in [AddEditHostScreen] so the assertions don't
 * depend on label wording. Material 3's `OutlinedTextField` sets
 * `mergeDescendants = true` on its inner editable node, which lifts the
 * testTag onto that inner node rather than the merged parent — hence
 * every `onNodeWithTag` call below passes `useUnmergedTree = true`.
 */
@RunWith(AndroidJUnit4::class)
class AddEditHostScreenTest {

    // `createAndroidComposeRule` hosts the screen inside a real
    // `ComponentActivity`, which provides the `LocalOnBackPressedDispatcherOwner`
    // that `BackHandler` (used by AddEditHostScreen for the discard-dialog
    // gate) demands at composition time. The bare `createComposeRule()`
    // host lacks that owner and would crash on first composition.
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * Helper for rendering the screen with a hand-built [AddEditHostViewModel]
     * pointed at the in-memory database. We bypass `hiltViewModel()` so
     * the test does not need Hilt's test runtime.
     */
    private fun renderScreen(
        onDone: () -> Unit = {},
        onManageKeys: () -> Unit = {},
    ): AddEditHostViewModel {
        val vm = AddEditHostViewModel(db.hostDao(), db.sshKeyDao())
        compose.setContent {
            PocketShellTheme {
                AddEditHostScreen(
                    hostId = null,
                    onDone = onDone,
                    onManageKeys = onManageKeys,
                    viewModel = vm,
                )
            }
        }
        return vm
    }

    @Test
    fun ctaIsDisabled_onEmptyForm_andEnabledWhenAllFieldsValid() {
        // Seed a key so the form CAN reach a valid state.
        val keyId = runBlocking {
            db.sshKeyDao().insert(SshKeyEntity(name = "lab", privateKeyPath = "/tmp/lab"))
        }
        renderScreen()

        // Empty Name/Hostname/Username and no selected key — CTA off.
        compose.onNodeWithTag(ADD_HOST_CTA_TAG, useUnmergedTree = true)
            .assertIsNotEnabled()

        // Type the required text and pick the key. The CTA should flip on.
        compose.onNodeWithTag(ADD_HOST_NAME_FIELD_TAG, useUnmergedTree = true)
            .performTextInput("homelab")
        compose.onNodeWithTag(ADD_HOST_HOSTNAME_FIELD_TAG, useUnmergedTree = true)
            .performTextInput("h.example")
        compose.onNodeWithTag(ADD_HOST_USERNAME_FIELD_TAG, useUnmergedTree = true)
            .performTextInput("deploy")
        // Open the key dropdown and pick the seeded key by its label.
        // The KeySelector overlays a click-catcher Box on top of the
        // read-only OutlinedTextField; tapping the field's testTag opens
        // the dropdown.
        compose.onNodeWithTag(ADD_HOST_KEY_FIELD_TAG, useUnmergedTree = true)
            .performClick()
        compose.onNodeWithText("lab").performClick()

        compose.onNodeWithTag(ADD_HOST_CTA_TAG, useUnmergedTree = true)
            .assertIsEnabled()
        // Keep the keyId referenced so static analysers don't flag it
        // as unused (we use it transitively via the seeded SSH key row).
        assertEquals(keyId, keyId)
    }

    @Test
    fun emptySubmit_highlightsFirstInvalidField_andCtaStaysDisabled() {
        val vm = renderScreen()

        // Drive save() with a blank form. The CTA is disabled in this
        // state (correct per the AC), so we go through the ViewModel to
        // simulate the rejection path that the UI's CTA blocks. The
        // resulting per-field errors and focus routing are still real
        // screen behaviour.
        compose.runOnIdle { vm.save() }
        compose.waitForIdle()

        // The first invalid required field is Name — its OutlinedTextField
        // should now be focused.
        compose.onNodeWithTag(ADD_HOST_NAME_FIELD_TAG, useUnmergedTree = true)
            .assertIsFocused()

        // Per-field error labels are visible under each blank required
        // field. The word "Required" is the supporting text we attach
        // for any blank mandatory field — Name, Hostname / IP and
        // Username are all blank here, so we expect 3 matching nodes.
        compose.onAllNodesWithText("Required").assertCountEquals(3)

        // CTA stays disabled.
        compose.onNodeWithTag(ADD_HOST_CTA_TAG, useUnmergedTree = true)
            .assertIsNotEnabled()
    }

    @Test
    fun invalidPort_showsFieldError_andBlocksCta() {
        val keyId = runBlocking {
            db.sshKeyDao().insert(SshKeyEntity(name = "lab", privateKeyPath = "/tmp/lab"))
        }
        val vm = renderScreen()
        assertEquals(keyId, keyId)

        // Fill text fields directly through the UI so the test exercises
        // the actual input flow, then pick the seeded key.
        compose.onNodeWithTag(ADD_HOST_NAME_FIELD_TAG, useUnmergedTree = true)
            .performTextInput("homelab")
        compose.onNodeWithTag(ADD_HOST_HOSTNAME_FIELD_TAG, useUnmergedTree = true)
            .performTextInput("h.example")
        compose.onNodeWithTag(ADD_HOST_USERNAME_FIELD_TAG, useUnmergedTree = true)
            .performTextInput("deploy")
        compose.onNodeWithTag(ADD_HOST_KEY_FIELD_TAG, useUnmergedTree = true)
            .performClick()
        compose.onNodeWithText("lab").performClick()

        // Force an out-of-range port via the ViewModel.
        // `performTextInput` appends to the existing "22", which would
        // still be a valid numeric prefix; mutating the model is the
        // cleanest way to simulate a deliberate user edit to a bad value.
        compose.runOnIdle { vm.updateState { it.copy(port = "99999") } }
        compose.runOnIdle { vm.save() }
        compose.waitForIdle()

        compose.onNodeWithText("Invalid port (1-65535)").assertExists()
        compose.onNodeWithTag(ADD_HOST_PORT_FIELD_TAG, useUnmergedTree = true)
            .assertIsFocused()
        runBlocking {
            assertEquals(0, db.hostDao().getAll().first().size)
        }
    }

    @Test
    fun validForm_pressingCta_savesRow() {
        val keyId = runBlocking {
            db.sshKeyDao().insert(SshKeyEntity(name = "lab", privateKeyPath = "/tmp/lab"))
        }
        renderScreen()

        compose.onNodeWithTag(ADD_HOST_NAME_FIELD_TAG, useUnmergedTree = true)
            .performTextInput("homelab")
        compose.onNodeWithTag(ADD_HOST_HOSTNAME_FIELD_TAG, useUnmergedTree = true)
            .performTextInput("h.example")
        compose.onNodeWithTag(ADD_HOST_USERNAME_FIELD_TAG, useUnmergedTree = true)
            .performTextInput("deploy")
        compose.onNodeWithTag(ADD_HOST_KEY_FIELD_TAG, useUnmergedTree = true)
            .performClick()
        compose.onNodeWithText("lab").performClick()

        compose.onNodeWithTag(ADD_HOST_CTA_TAG, useUnmergedTree = true)
            .assertIsEnabled()
        compose.onNodeWithTag(ADD_HOST_CTA_TAG, useUnmergedTree = true)
            .performClick()
        compose.waitForIdle()

        runBlocking {
            val hosts = db.hostDao().getAll().first()
            assertEquals(1, hosts.size)
            assertEquals("homelab", hosts[0].name)
            assertEquals("h.example", hosts[0].hostname)
            assertEquals("deploy", hosts[0].username)
            assertEquals(keyId, hosts[0].keyId)
        }
    }
}
