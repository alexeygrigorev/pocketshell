package com.pocketshell.app.notifications

import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.core.storage.AppDatabase
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader

/**
 * Issue #1509 (reported symptom, gate-wired regression) — on app open the app
 * MUST NOT pop the Android-13+ POST_NOTIFICATIONS system permission dialog.
 *
 * ## The reported symptom
 *
 * The maintainer's v0.4.28 dogfood: opening the app fired TWO sequential
 * prompts — the host-version-mismatch update banner AND, separately, the
 * "Allow PocketShell to send you notifications?" system dialog. The dialog came
 * from `MainActivity.onCreate` → `maybeRequestNotificationPermission()`, an
 * app-open trigger. The fix (D22 hard-cut) DELETES that app-open trigger and
 * folds the notifications request into the single session-tree setup
 * coordinator, so it is only ever offered lazily from a host's session tree —
 * never on app open.
 *
 * ## Why the existing connected suite could not catch this
 *
 * All other connected tests declare [com.pocketshell.app.proof.PreGrantPermissionsRule],
 * which pre-grants POST_NOTIFICATIONS before launch. With the grant already in
 * place, a re-added `onCreate` trigger early-returns
 * (`ContextCompat.checkSelfPermission(...) == GRANTED`) and NO dialog pops — so
 * every one of those tests stays green while the reported bug returns. This
 * regression MUST therefore run with POST_NOTIFICATIONS explicitly REVOKED so
 * the app-open trigger, if it ever comes back, actually pops the dialog and
 * fails this test.
 *
 * ## The load-bearing assertion (red→green)
 *
 * With POST_NOTIFICATIONS revoked and ZERO hosts configured, launch
 * MainActivity fresh and settle. The authoritative signal is the system's
 * CURRENTLY-FOCUSED window (`dumpsys window`):
 *  - GREEN (fix): focus is the app's own `MainActivity` — the host list is
 *    reachable, no permission dialog on app open.
 *  - RED (base: `MainActivity.onCreate` app-open trigger restored): focus is the
 *    permission controller's `GrantPermissionsActivity` — the "Allow …
 *    notifications?" dialog is on top of the host list on app open (the reported
 *    second sequential prompt). The reviewer confirmed this red→green on-device.
 *
 * No Docker fixture is needed (a pure app-open journey). Wired into
 * `scripts/ci-journey-suite.sh` so the per-push emulator-journey gate runs it.
 * It lives OUTSIDE the `proof` package deliberately: the app-open focus check
 * needs a hand-rolled `ActivityScenario.launch` (no seeded remote state, no
 * `createAndroidComposeRule<MainActivity>()` pre-grant harness — the whole point
 * is to launch with the grant REVOKED).
 */
@RunWith(AndroidJUnit4::class)
class NoNotificationPromptOnAppOpenE2eTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetPackage = instrumentation.targetContext.packageName
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        // Deterministic empty state: zero hosts so app open lands on the host
        // list (never an auto-resolved default host / session), and REVOKE
        // POST_NOTIFICATIONS so a re-added app-open trigger would actually pop
        // the system dialog (the reported symptom) instead of early-returning on
        // an already-granted permission (the #1509 masking gap).
        val db = Room.databaseBuilder(
            instrumentation.targetContext,
            AppDatabase::class.java,
            DATABASE_NAME,
        ).fallbackToDestructiveMigration(dropAllTables = true).build()
        runCatching { db.clearAllTables() }
        db.close()
        runShell("pm revoke $targetPackage android.permission.POST_NOTIFICATIONS")
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
        // Hygiene: re-grant so a subsequent journey class on the shared emulator
        // is not surprised by the revoked runtime grant (most declare
        // PreGrantPermissionsRule which re-grants anyway, but leave it clean).
        runShell("pm grant $targetPackage android.permission.POST_NOTIFICATIONS")
    }

    @Test
    fun appOpenDoesNotPopNotificationPermissionDialog() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        // Let onCreate + first composition + any (unwanted) permission request
        // settle. A revoked-grant app-open trigger pops GrantPermissionsActivity
        // essentially immediately; the settle window makes the focus read stable.
        instrumentation.waitForIdleSync()
        SystemClock.sleep(2_500)

        // Confirm the app itself is alive/foreground (not crashed, not exited),
        // so a "no dialog" reading can only mean the app-open trigger is gone —
        // not that the app never came up.
        var finishing = true
        scenario?.onActivity { activity ->
            finishing = activity.isFinishing || activity.isDestroyed
        }
        assertFalse("MainActivity must be alive/foregrounded on app open", finishing)

        val focus = currentFocus()

        // Load-bearing: the notifications-permission dialog must NOT be on top on
        // app open. GrantPermissionsActivity lives in the permission-controller
        // process; if it holds focus, the reported second sequential prompt is
        // back.
        assertFalse(
            "the notifications-permission dialog must NOT appear on app open — " +
                "focused window was the system permission controller " +
                "(GrantPermissionsActivity): [$focus]",
            focus.contains("GrantPermissionsActivity") ||
                focus.contains("permissioncontroller"),
        )

        // Corroboration: the app's OWN MainActivity holds focus, i.e. the host
        // list is reachable and not occluded by a permission dialog.
        assertTrue(
            "the app's MainActivity must hold window focus on app open (host list " +
                "reachable, no permission dialog) — focused window was [$focus]",
            focus.contains(targetPackage) && focus.contains("MainActivity"),
        )
    }

    /**
     * The system's currently-focused window(s) from `dumpsys window`, joined to a
     * single string. We read the focus AFTER settling and never interleave a
     * shell dump with Compose-test interactions (this test does no `performClick`),
     * so the #520 UiAutomation-desync caveat does not apply.
     */
    private fun currentFocus(): String {
        val dump = runShell("dumpsys window")
        return dump.lineSequence()
            .filter { it.contains("mCurrentFocus") || it.contains("mFocusedApp") }
            .joinToString(separator = " | ")
            .ifBlank {
                // Fallback for OEM/dumpsys shape drift: the whole focused-app dump.
                runShell("dumpsys activity activities")
                    .lineSequence()
                    .filter { it.contains("mResumedActivity") || it.contains("topResumedActivity") }
                    .joinToString(separator = " | ")
            }
    }

    private fun runShell(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return FileInputStream(pfd.fileDescriptor).use { input ->
            BufferedReader(InputStreamReader(input)).use { it.readText() }
        }.also { pfd.close() }
    }

    private companion object {
        const val DATABASE_NAME = "pocketshell.db"
    }
}
