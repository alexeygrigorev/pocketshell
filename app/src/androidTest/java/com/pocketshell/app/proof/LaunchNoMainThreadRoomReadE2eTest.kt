package com.pocketshell.app.proof

import android.os.StrictMode
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.diagnostics.StrictModeInstaller
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.projects.FOLDER_LIST_SCREEN_TAG
import com.pocketshell.app.testaccess.TestAccessEntryPoint
import com.pocketshell.core.storage.entity.HostEntity
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Issue #951 (#928 D2) — reproduce-first end-to-end for the launch ANR /
 * crash-loop risk: `MainActivity.onCreate` used to do an unguarded
 * `runBlocking { resolveDefaultHostLaunchDestination(...) }` — two Room reads +
 * a key-file stat — ON the Main thread on every default-host cold launch.
 *
 * This is the EXACT class the #933 process-wide StrictMode tripwire exists to
 * catch: a main-thread disk read. The test drives the **real launch path**
 * (`ActivityScenario.launch(MainActivity)` with a default host seeded in Room),
 * with the production StrictMode policy active on the Main thread, and asserts:
 *
 *  - **RED on base** — the launch-time default-host resolution trips a
 *    `StrictMode` **disk-read** violation whose stack trace is attributable to
 *    `MainActivity` (the `runBlocking` Room read on the Main thread).
 *  - **GREEN with the fix** — the same launch trips NO `MainActivity`-attributed
 *    main-thread disk-read violation (the resolution runs on `Dispatchers.IO`),
 *    AND the default host still auto-opens its FolderList (the #305 contract).
 *
 * The violation is captured directly off the production
 * [StrictModeInstaller.buildThreadPolicy] listener (not via DiagnosticEvents)
 * so the FULL stack trace is available for attribution — the recorded
 * `strictmode.violation` event only carries the top frame, which for a disk
 * read is the SQLite/IO site, not the `MainActivity` caller.
 *
 * No Docker / SSH / tmux — a pure on-device launch exercise, deterministic on
 * the CI swiftshader AVD. Wired into the per-push emulator journey gate via
 * `scripts/ci-journey-suite.sh`. No `assumeTrue` / `assumeFalse(isRunningOnCi())`
 * on the load-bearing assertion (process.md F3 / D33).
 */
@RunWith(AndroidJUnit4::class)
class LaunchNoMainThreadRoomReadE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private var hostId: Long? = null
    private var keyId: Long? = null

    @After
    fun tearDown() {
        launchedActivity?.close()
        launchedActivity = null
        val entry = testAccess()
        entry.settingsRepository().setDefaultHostId(null)
        val db = entry.appDatabase()
        runBlocking {
            hostId?.let { db.hostDao().deleteById(it) }
            keyId?.let { db.sshKeyDao().deleteById(it) }
        }
    }

    @Test
    fun defaultHostLaunchDoesNotReadRoomOnMainThread_andStillAutoOpensFolderList() {
        seedDefaultHost("Issue951 default ${System.nanoTime()}")

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mainThreadDiskReads = CopyOnWriteArrayList<Throwable>()
        val savedPolicy = arrayOfNulls<StrictMode.ThreadPolicy>(1)

        // Install a main-thread policy that detects disk reads (the exact
        // detector union the production [StrictModeInstaller] uses — see its
        // buildThreadPolicy) with a listener that retains the FULL Throwable so
        // we can attribute a disk read to MainActivity. The androidTest APK is
        // debuggable so App.onCreate already installed an equivalent production
        // policy; we replace it with this capturing one for the launch window
        // and restore it after. The executor runs the listener synchronously on
        // the violating (Main) thread, exactly as the production wiring does.
        instrumentation.runOnMainSync {
            savedPolicy[0] = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .penaltyListener({ it.run() }) { violation ->
                        if (StrictModeInstaller.violationKind(violation) == "disk_read") {
                            mainThreadDiskReads += violation
                        }
                    }
                    .build(),
            )
        }

        try {
            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            launchedActivity!!.moveToState(Lifecycle.State.RESUMED)

            // The default host must still auto-open its FolderList (the #305
            // contract preserved): the host list may flash first, then the
            // off-Main resolve routes here.
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithTag(FOLDER_LIST_SCREEN_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag(FOLDER_LIST_SCREEN_TAG, useUnmergedTree = true).assertExists()

            // Let any in-flight main-thread violation listener land.
            instrumentation.waitForIdleSync()

            // LOAD-BEARING (G6): the DEFAULT-HOST RESOLUTION specifically must
            // NOT have read Room on the Main thread. Attribute precisely to the
            // resolution call chain, not to any MainActivity-rooted disk access
            // (SharedPreferences / keystore startup reads are unrelated and
            // present on both base and fixed code). On base the
            // `runBlocking { resolveDefaultHostLaunchDestination(...) }` runs the
            // Room `getById` synchronously on the Main thread, so its violation
            // stack carries BOTH the `resolveDefaultHostLaunchDestination` frame
            // AND a Room generated DAO impl frame (`*Dao_Impl` / SQLite). With
            // the fix the read runs on Dispatchers.IO → never on Main → no such
            // violation is captured at all.
            val resolutionReads = mainThreadDiskReads.filter { violation ->
                val frames = violation.stackTrace
                val viaResolution = frames.any { frame ->
                    frame.className.contains("resolveDefaultHostLaunchDestination") ||
                        frame.methodName.contains("resolveDefaultHostLaunchDestination")
                }
                val viaRoomRead = frames.any { frame ->
                    frame.className.contains("Dao_Impl") ||
                        frame.className.contains("androidx.room") ||
                        frame.className.contains("android.database.sqlite")
                }
                viaResolution || viaRoomRead
            }
            assertTrue(
                "MainActivity.onCreate must NOT read Room on the Main thread " +
                    "during default-host launch (issue #951 / #928 D2 ANR + " +
                    "crash-loop risk). Captured ${resolutionReads.size} " +
                    "main-thread disk-read violation(s) attributable to the " +
                    "default-host Room resolution:\n" +
                    resolutionReads.joinToString("\n\n") { violation ->
                        violation.toString() + "\n" +
                            violation.stackTrace.take(15).joinToString("\n") { "    at $it" }
                    },
                resolutionReads.isEmpty(),
            )
        } finally {
            instrumentation.runOnMainSync {
                savedPolicy[0]?.let { StrictMode.setThreadPolicy(it) }
            }
        }
    }

    /**
     * Seed the default host through the SAME Hilt singletons MainActivity reads
     * (DB via [TestAccessEntryPoint.appDatabase], default-host preference via
     * the singleton [com.pocketshell.app.settings.SettingsRepository]). A
     * throwaway `SettingsRepository(context)` would write SharedPreferences but
     * leave the singleton's construction-time snapshot stale, so the activity's
     * off-Main resolve would read a null default host. Going through the
     * singleton guarantees the activity sees the seeded value (the documented
     * ColdInstall seeding pattern).
     */
    private fun seedDefaultHost(hostName: String) {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val key = InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        val entry = testAccess()
        val db = entry.appDatabase()
        runBlocking {
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue951-key-${System.nanoTime()}",
                content = key,
            )
            keyId = storedKey.id
            hostId = db.hostDao().insert(
                HostEntity(
                    name = hostName,
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    pocketshellInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                    pocketshellLastDetectedAt = System.currentTimeMillis(),
                ),
            )
        }
        entry.settingsRepository().setDefaultHostId(requireNotNull(hostId))
    }

    private fun testAccess(): TestAccessEntryPoint =
        EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            TestAccessEntryPoint::class.java,
        )

    private companion object {
        const val DEFAULT_HOST: String = "10.0.2.2"
        const val DEFAULT_PORT: Int = 2222
        const val DEFAULT_USER: String = "testuser"
    }
}
