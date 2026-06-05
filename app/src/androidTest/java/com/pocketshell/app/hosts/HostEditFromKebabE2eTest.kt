package com.pocketshell.app.hosts

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #519 — end-to-end coverage that a host can be edited from the
 * host-card kebab. Before this issue the `onEditHost` route was orphaned
 * (kebab had no Edit item, long-press opened the kebab), so a user could
 * only delete-and-re-add a host to change it.
 *
 * Journey: seed a host → land on the host list → open the kebab → tap
 * "Edit" → confirm the edit form is pre-filled with that host's values
 * (`AddEditHostViewModel.loadHost`) → change the name → Save → confirm
 * the host row reflects the change, and the DB row was updated.
 *
 * No Docker is required: the edit-host path is pure UI + Room (no SSH
 * connect), so this runs against the deterministic emulator alone.
 */
@RunWith(AndroidJUnit4::class)
class HostEditFromKebabE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    // Issue #470 blocker #1: grant runtime permissions before the activity
    // launches so the system GrantPermissionsActivity never steals focus
    // from the Compose hierarchy ("No compose hierarchies found").
    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private var hostId: Long? = null
    private var keyId: Long? = null

    @After
    fun tearDown() {
        launchedActivity?.close()
        launchedActivity = null
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = openDb(appContext)
        try {
            runBlocking {
                hostId?.let { db.hostDao().deleteById(it) }
                keyId?.let { db.sshKeyDao().deleteById(it) }
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun editHostFromKebab_prefillsForm_andSaveRoundTrips() {
        val originalName = "Edit me ${System.nanoTime()}"
        val updatedName = "Edited ${System.nanoTime()}"
        seedHost(originalName)
        val id = requireNotNull(hostId)

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        launchedActivity!!.moveToState(Lifecycle.State.RESUMED)

        // Land on the host list — the seeded row is present.
        val rowTag = HOST_ROW_TAG_PREFIX + id
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(rowTag, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(originalName, useUnmergedTree = true).assertExists()

        // Open the kebab and tap the new "Edit" item.
        compose.onNodeWithTag(HOST_OVERFLOW_BUTTON_TAG, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 3_000) {
            compose.onAllNodesWithTag(HOST_EDIT_ITEM_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        capture("01-kebab-with-edit")
        compose.onNodeWithTag(HOST_EDIT_ITEM_TAG, useUnmergedTree = true).performClick()

        // The edit form opens pre-filled with the host's current values.
        // The text-input action lives on the merged field node, so the
        // name field is located via the merged tree (no useUnmergedTree).
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(ADD_HOST_NAME_FIELD_TAG)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(originalName, useUnmergedTree = true).assertExists()
        capture("02-edit-screen-prefilled")

        // Replace the name with a new value (atomic clear+type avoids
        // cursor/commit races) and save.
        compose.onNodeWithTag(ADD_HOST_NAME_FIELD_TAG)
            .performTextReplacement(updatedName)
        compose.waitForIdle()
        // The bottom CTA can sit below the fold (and under the soft
        // keyboard) in the scrollable form; scroll it into view before
        // clicking so the tap lands on the (enabled) Save button.
        compose.onNodeWithTag(ADD_HOST_CTA_TAG).performScrollTo()
        compose.onNodeWithTag(ADD_HOST_CTA_TAG).performClick()

        // Back on the host list, the row reflects the new name.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(rowTag, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(updatedName, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(updatedName, useUnmergedTree = true).assertExists()
        capture("03-host-row-updated")

        // And the persisted row was updated, not duplicated.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = openDb(appContext)
        try {
            runBlocking {
                val persisted = db.hostDao().getById(id)
                assertEquals(updatedName, persisted?.name)
            }
        } finally {
            db.close()
        }
    }

    private fun seedHost(hostName: String) {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val key = InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        val db = openDb(appContext)
        try {
            runBlocking {
                val storedKey = SshKeyStorage.persistKey(
                    context = appContext,
                    sshKeyDao = db.sshKeyDao(),
                    name = "edit-kebab-key-${System.nanoTime()}",
                    content = key,
                )
                keyId = storedKey.id
                hostId = db.hostDao().insert(
                    HostEntity(
                        name = hostName,
                        hostname = "10.0.2.2",
                        port = 2222,
                        username = "testuser",
                        keyId = storedKey.id,
                        tmuxInstalled = true,
                        pocketshellInstalled = true,
                        lastBootstrapAt = System.currentTimeMillis(),
                        pocketshellLastDetectedAt = System.currentTimeMillis(),
                    ),
                )
            }
        } finally {
            db.close()
        }
    }

    private fun openDb(context: android.content.Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = artifactDir()
        val file = File(dir, "$name.png")
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write screenshot: ${file.absolutePath}"
                }
            }
            println("HOST_EDIT_KEBAB_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun artifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/host-edit-from-kebab")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create screenshot directory: ${dir.absolutePath}"
        }
        return dir
    }

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
    }
}
