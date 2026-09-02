package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.FIRST_HOST_TEST_CONNECT_SCREEN_TAG
import com.pocketshell.app.hosts.FIRST_HOST_TEST_CONNECT_TRUST_TAG
import com.pocketshell.app.hosts.HOST_LIST_CONTENT_TAG
import com.pocketshell.app.hosts.HOST_OVERFLOW_BUTTON_TAG
import com.pocketshell.app.hosts.HOST_RESUME_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.HOST_WATCHED_FOLDERS_ITEM_TAG
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.projects.WATCHED_FOLDERS_DISCOVER_TAG
import com.pocketshell.app.testaccess.TestAccessEntryPoint
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.uikit.components.HOST_STATUS_DESCRIPTION_ERROR
import dagger.hilt.android.EntryPointAccessors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #2463 — "the app throws me off the Hosts list onto a Trust/Test-connect
 * screen about a second after launch, with no tap".
 *
 * ## The mechanism this journey reproduces
 *
 * `4b5be0d8 Enforce SSH host key trust and rekey flows` added
 * `HostKeyTrustPromptRouter`, a process-wide `@Singleton` holding a
 * `MutableStateFlow<Long?>`, and made `MainActivity.AppNavigator` navigate to
 * `AppDestination.FirstHostTestConnect` on ANY emission — no guard on whether
 * the failure came from a foreground user action or a background probe, none on
 * what the user is currently doing, and (being a retained `StateFlow` on a
 * process singleton) replaying into any freshly created Activity.
 *
 * The same commit's Room v19 migration leaves `trustedHostKeySha256` NULL for
 * every pre-existing host. So on the first launch after the update, the
 * host-list cold-launch reprobe (`hostlist-reprobe-kicked`) dials, fails host-key
 * verification, and the app yanks the user off the Hosts list onto a trust
 * screen for whichever host lost the race.
 *
 * ## The class, not the one instance (G2)
 *
 * The seeded list covers every background-reprobe host-key state at once
 * against the deterministic `agents` fixture:
 *
 *  * **unconfirmed** — `trustedHostKeySha256 = null`: exactly what the v19
 *    migration produces for an existing user's hosts (`UnknownHostKeyException`).
 *  * **mismatched** — a wrong persisted fingerprint (`ChangedHostKeyException`),
 *    the other half of the verification-failure family, and the one a rekeyed
 *    server produces.
 *  * **trusted control** — the real fingerprint, also never probed. It proves
 *    the reprobe machinery actually ran, so a green verdict here cannot be the
 *    vacuous "nothing happened at all".
 *
 * Acceptance: the user stays on the Hosts list for the whole cold-launch window,
 * every observed navigator destination is `HostList`, and the two failing hosts
 * are ANNOTATED on their cards instead — the existing failed-connect status
 * indicator, which the user can act on when they choose to.
 *
 * The remaining three tests pin the other side of the hard cut: a genuinely
 * user-initiated connect that hits the same failure MUST still reach the
 * Trust/Test-connect screen and offer the fingerprint decision. The fix must not
 * have silently deleted the intentional foreground UX — and "user-initiated" is
 * not just the host-card tap. All three of the app's user-initiated host-open
 * entry points are covered:
 *
 *  * the host-card **tap** (`HostListViewModel.bootstrapHost`);
 *  * **"Resume last session"** (#1239), which skips `bootstrapHost` entirely and
 *    navigates straight to the persisted tmux destination — the exact affordance
 *    an upgrading user sees at cold launch, because the `LastSessionStore`
 *    snapshot survives the v19 migration while the trusted fingerprint does not;
 *  * the kebab's **"Watched folders"** (#206) → **"Discover from remote"** probe,
 *    which is that screen's only SSH.
 *
 * MUST FAIL on `release/v0.4.47` @ `97d561f3` (the navigator leaves the host list
 * unasked, and no card annotation exists) and PASS after the #2463 fix.
 */
@RunWith(AndroidJUnit4::class)
class Issue2463BackgroundHostKeyTrustNoNavJourneyE2eTest {

    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: org.junit.rules.RuleChain = org.junit.rules.RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private var unconfirmedHostId: Long = 0L
    private lateinit var unconfirmedRowTag: String
    private lateinit var mismatchedRowTag: String
    private lateinit var trustedRowTag: String
    private lateinit var resumeRowTag: String
    private val hostNames = mutableListOf<String>()
    private val trail = mutableListOf<String>()

    private suspend fun seedBeforeLaunch() {
        val key = readFixtureKey()
        val realFingerprint = waitForSshFixtureReady(SshKey.Pem(key))
        clearLastSessionPrefs()
        // The router is a process singleton whose annotation set outlives an
        // Activity and therefore a test class; Room ids repeat after
        // `clearAllTables()`, so a sibling class's leftover annotation would
        // otherwise put a red dot on a host this class just seeded (and make the
        // liveness assertion below pass without our own reprobe ever running).
        EntryPointAccessors
            .fromApplication(
                InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
                TestAccessEntryPoint::class.java,
            )
            .hostKeyTrustPromptRouter()
            .resetForTest()

        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue2463-key-${System.currentTimeMillis()}",
                content = key,
            )
            // Every host is seeded with the bootstrap columns NULL, which is what
            // makes `HostListViewModel.reprobeUnknownHostsOnce` dial them on the
            // host list's first composition — the exact cold-launch reprobe from
            // the report. No tap is ever needed for the first test.
            unconfirmedHostId = db.hostDao().insert(
                newHost(
                    name = "Z2463 migrated",
                    keyId = storedKey.id,
                    // The Room v19 migration's own value for every existing host.
                    trustedHostKeySha256 = null,
                ),
            )
            unconfirmedRowTag = HOST_ROW_TAG_PREFIX + unconfirmedHostId
            resumeRowTag = HOST_RESUME_ROW_TAG_PREFIX + unconfirmedHostId
            mismatchedRowTag = HOST_ROW_TAG_PREFIX + db.hostDao().insert(
                newHost(
                    name = "Z2463 rekeyed",
                    keyId = storedKey.id,
                    trustedHostKeySha256 = MISMATCHED_FINGERPRINT,
                ),
            )
            trustedRowTag = HOST_ROW_TAG_PREFIX + db.hostDao().insert(
                newHost(
                    name = "Z2463 trusted",
                    keyId = storedKey.id,
                    trustedHostKeySha256 = realFingerprint,
                ),
            )
            // Issue #2463 round 2: the surviving `LastSessionStore` snapshot an
            // upgrading user carries across the v19 migration. It renders the
            // one-tap "Resume last session" row on the migrated host's card at
            // cold launch, which is the SECOND user-initiated host-open entry
            // point (it bypasses `bootstrapHost` entirely and navigates straight
            // to the tmux session destination).
            seedLastSessionSnapshot(
                hostId = unconfirmedHostId,
                keyPath = storedKey.privateKeyPath,
            )
        } finally {
            db.close()
        }
    }

    /**
     * Write the `last_session` prefs blob [com.pocketshell.app.session.LastSessionStore]
     * reads, directly and before launch. Field names/shape mirror that store's
     * private key constants; a drift there breaks this seed loudly (the resume
     * row never renders and the test fails on its own precondition) rather than
     * silently.
     */
    private fun seedLastSessionSnapshot(hostId: Long, keyPath: String) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val ok = ctx.getSharedPreferences("last_session", android.content.Context.MODE_PRIVATE)
            .edit()
            .putLong("host_id", hostId)
            .putString("host_name", "Z2463 migrated")
            .putString("hostname", DEFAULT_HOST)
            .putInt("port", DEFAULT_PORT)
            .putString("username", DEFAULT_USER)
            .putString("key_path", keyPath)
            .putString("session_name", RESUME_SESSION_NAME)
            .putString("start_dir", null)
            .putLong("saved_at", System.currentTimeMillis())
            .commit()
        check(ok) { "could not seed the last_session snapshot" }
    }

    private fun newHost(name: String, keyId: Long, trustedHostKeySha256: String?): HostEntity {
        hostNames += name
        return HostEntity(
            name = name,
            hostname = DEFAULT_HOST,
            port = DEFAULT_PORT,
            username = DEFAULT_USER,
            keyId = keyId,
            tmuxInstalled = null,
            pocketshellInstalled = null,
            trustedHostKeySha256 = trustedHostKeySha256,
        )
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun coldLaunchReprobeHostKeyFailuresAnnotateTheCardsAndNeverLeaveTheHostsList() {
        waitForAllHostRows()
        record("00-host-list-composed")

        // Watch the REAL navigator for the whole cold-launch window instead of
        // sampling the Compose tree at the end: the reported symptom is a
        // navigation that happens ~1 s after launch and is easy to miss.
        val observed = observeDestinations(HOST_LIST_WATCH_MS)
        record("01-after-reprobe-window")

        // Liveness first (G3/G6): if no host-key failure was ever reported, this
        // journey proves nothing, so hard-fail rather than green vacuously.
        val annotated = failedConnectIndicatorCount()
        writeTrail()
        assertEquals(
            "the cold-launch reprobe must have reported a host-key failure for BOTH the " +
                "migrated (unconfirmed) and the rekeyed (mismatched) host and annotated their " +
                "cards; observed indicators=$annotated, trail=${trail.joinToString(" | ")}",
            2,
            annotated,
        )

        // The acceptance itself: no background failure may move the user.
        assertEquals(
            "ISSUE 2463: a BACKGROUND host-key verification failure must never navigate. " +
                "Observed navigator destinations: $observed",
            setOf("HostList"),
            observed,
        )
        assertTrue(
            "ISSUE 2463: the Hosts list must still be on screen after the cold-launch reprobe. " +
                "Trail: ${trail.joinToString(" | ")}",
            onHostList(),
        )
        assertTrue(
            "ISSUE 2463: the Trust/Test-connect screen must NOT have opened unasked. " +
                "Trail: ${trail.joinToString(" | ")}",
            !onTrustScreen(),
        )
        capture("01-hosts-list-after-background-reprobe")
    }

    /**
     * The other side of the hard cut: the foreground path the trust screen
     * exists for must still work. Tapping a host whose key cannot be verified
     * is an explicit ask, and it must reach the Trust/Test-connect screen.
     */
    @Test
    fun anExplicitHostTapWithAnUnverifiableKeyStillReachesTheTrustScreen() {
        waitForAllHostRows()
        // Let the background reprobe settle first so the tap is unambiguously
        // the thing that produced the navigation.
        observeDestinations(REPROBE_SETTLE_MS)
        record("00-before-tap")
        assertTrue("precondition: still on the hosts list before the tap", onHostList())

        compose.onNodeWithTag(unconfirmedRowTag, useUnmergedTree = true).performClick()

        val reachedTrustScreen = awaitTrustDestination(TRUST_SCREEN_WAIT_MS)
        record("01-after-tap")
        assertTrue(
            "ISSUE 2463: an explicit tap on a host with an unverifiable key must still open the " +
                "Trust/Test-connect screen. Trail: ${trail.joinToString(" | ")}",
            reachedTrustScreen,
        )

        // ...and it must STAY. The tap's failed bootstrap probe used to carry on
        // and open the workspace tree anyway, burying the only screen that can
        // fix the host behind a folder list that can never load.
        val settled = observeDestinations(TRUST_SCREEN_SETTLE_MS)
        record("02-trust-screen-settled")
        writeTrail()
        capture("02-trust-screen-after-explicit-tap")
        assertEquals(
            "ISSUE 2463: the Trust/Test-connect screen must not be buried by a navigation the " +
                "same failed tap triggered. Observed after it opened: $settled",
            setOf("FirstHostTestConnect"),
            settled,
        )
        assertTrue(
            "ISSUE 2463: the Trust/Test-connect screen must still be the screen on display. " +
                "Trail: ${trail.joinToString(" | ")}",
            onTrustScreen(),
        )
    }

    /**
     * Round-2 reviewer finding 1, entry point A: **"Resume last session"**
     * (#1239). It is a genuine one-tap user-initiated host open that never goes
     * through `bootstrapHost` — the card row navigates straight to the tmux
     * session destination, whose connect reports through the same lease
     * connector. On the upgrade path this issue is about, the snapshot survives
     * the v19 migration while `trustedHostKeySha256` does not, so this row is
     * rendered on a host whose key cannot be verified and one tap on it must
     * NOT dead-end in a terminal showing a bare connect failure with no
     * fingerprint and no "Trust and connect".
     */
    @Test
    fun aResumeLastSessionTapWithAnUnverifiableKeyReachesTheTrustScreen() {
        waitForAllHostRows()
        observeDestinations(REPROBE_SETTLE_MS)
        record("00-before-resume-tap")
        assertTrue("precondition: still on the hosts list before the tap", onHostList())
        // Liveness: if the Resume affordance never rendered, the tap below would
        // no-op and a "green" verdict would prove nothing (G3/G6).
        compose.waitUntil(timeoutMillis = HOST_ROW_WAIT_MS) { nodesWithTag(resumeRowTag) }

        compose.onNodeWithTag(resumeRowTag, useUnmergedTree = true).performClick()

        val reachedTrustScreen = awaitTrustDestination(TRUST_SCREEN_WAIT_MS)
        // The destination alone is not the user-visible outcome: what makes this
        // path recoverable is the fingerprint + "Trust and connect" affordance,
        // so wait for and assert THAT (G6 — the load-bearing assertion is the
        // green one).
        val offeredTrustAffordance = awaitTrustAffordance(TRUST_SCREEN_WAIT_MS)
        record("01-after-resume-tap")
        writeTrail()
        capture("03-trust-screen-after-resume-tap")
        assertTrue(
            "ISSUE 2463: a 'Resume last session' tap is user-initiated — a host-key verification " +
                "failure on it must reach the Trust/Test-connect screen, not be absorbed into a " +
                "card annotation while the user stares at an unexplained connect failure. " +
                "Trail: ${trail.joinToString(" | ")}",
            reachedTrustScreen,
        )
        assertTrue(
            "ISSUE 2463: the resumed host's Trust screen must offer the fingerprint decision " +
                "('Trust and connect' / 'Replace trusted key'). Trail: ${trail.joinToString(" | ")}",
            offeredTrustAffordance,
        )
    }

    /**
     * Round-2 reviewer finding 1, entry point B: the kebab's **"Watched
     * folders"** item (#206). Opening the screen performs no SSH itself — the
     * user-initiated connect is the screen's "Discover from remote" probe, so
     * that probe (not the menu tap) is the thing that has to count as
     * foreground. Same acceptance: an unverifiable key must surface the Trust
     * screen rather than a bare discover error.
     */
    @Test
    fun aWatchedFoldersDiscoverProbeWithAnUnverifiableKeyReachesTheTrustScreen() {
        waitForAllHostRows()
        observeDestinations(REPROBE_SETTLE_MS)
        record("00-before-watched-folders")
        assertTrue("precondition: still on the hosts list before the kebab", onHostList())

        compose.onNode(
            hasTestTag(HOST_OVERFLOW_BUTTON_TAG) and hasAnyAncestor(hasTestTag(unconfirmedRowTag)),
            useUnmergedTree = true,
        ).performClick()
        compose.waitUntil(timeoutMillis = MENU_WAIT_MS) {
            nodesWithTag(HOST_WATCHED_FOLDERS_ITEM_TAG)
        }
        compose.onNodeWithTag(HOST_WATCHED_FOLDERS_ITEM_TAG, useUnmergedTree = true).performClick()

        // The discover button only renders with SSH credentials attached, so its
        // presence also proves we arrived through the per-host kebab route.
        compose.waitUntil(timeoutMillis = MENU_WAIT_MS) {
            nodesWithTag(WATCHED_FOLDERS_DISCOVER_TAG)
        }
        record("01-watched-folders-open")
        compose.onNodeWithTag(WATCHED_FOLDERS_DISCOVER_TAG, useUnmergedTree = true).performClick()

        val reachedTrustScreen = awaitTrustDestination(TRUST_SCREEN_WAIT_MS)
        val offeredTrustAffordance = awaitTrustAffordance(TRUST_SCREEN_WAIT_MS)
        record("02-after-discover")
        writeTrail()
        capture("04-trust-screen-after-watched-folders-discover")
        assertTrue(
            "ISSUE 2463: the watched-folders discover probe is user-initiated — a host-key " +
                "verification failure on it must reach the Trust/Test-connect screen. " +
                "Trail: ${trail.joinToString(" | ")}",
            reachedTrustScreen,
        )
        assertTrue(
            "ISSUE 2463: the discover probe's Trust screen must offer the fingerprint decision " +
                "('Trust and connect' / 'Replace trusted key'). Trail: ${trail.joinToString(" | ")}",
            offeredTrustAffordance,
        )
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Poll the activity's own navigator destination from the main thread for
     * [windowMs] and return the distinct destination names seen. Reading the
     * navigator directly (rather than the Compose tree) catches a transient
     * navigation that a single end-of-test assertion would miss.
     */
    private fun observeDestinations(windowMs: Long): Set<String> {
        val seen = linkedSetOf<String>()
        val deadline = SystemClock.elapsedRealtime() + windowMs
        while (SystemClock.elapsedRealtime() < deadline) {
            seen += currentDestinationName()
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        seen += currentDestinationName()
        trail += "destinations=$seen"
        return seen
    }

    private fun awaitTrustDestination(windowMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + windowMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (currentDestinationName() == "FirstHostTestConnect" || onTrustScreen()) return true
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    /**
     * The user-visible payoff of reaching the trust screen: the button carrying
     * the fingerprint decision ("Trust and connect" for an unknown key,
     * "Replace trusted key" for a changed one). Rendered only once the screen's
     * own test-connect has completed and produced a
     * [com.pocketshell.core.ssh.HostKeyVerificationException].
     */
    private fun awaitTrustAffordance(windowMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + windowMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (nodesWithTag(FIRST_HOST_TEST_CONNECT_TRUST_TAG)) return true
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    private fun currentDestinationName(): String {
        var name = "unknown"
        compose.activityRule.scenario.onActivity { activity ->
            name = activity.currentDestinationForTest()::class.java.simpleName
        }
        return name
    }

    private fun failedConnectIndicatorCount(): Int = runCatching {
        compose.onAllNodesWithContentDescription(HOST_STATUS_DESCRIPTION_ERROR, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size
    }.getOrDefault(0)

    private fun waitForAllHostRows() {
        compose.waitUntil(timeoutMillis = HOST_ROW_WAIT_MS) {
            runCatching {
                hostNames.all { name ->
                    compose.onAllNodesWithText(name, useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
            }.getOrDefault(false)
        }
    }

    private fun onHostList(): Boolean = nodesWithTag(HOST_LIST_CONTENT_TAG)

    private fun onTrustScreen(): Boolean = nodesWithTag(FIRST_HOST_TEST_CONNECT_SCREEN_TAG)

    private fun nodesWithTag(tag: String): Boolean = runCatching {
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)

    private fun record(label: String) {
        val line = "$label => destination=${currentDestinationName()} hostList=${onHostList()} " +
            "trustScreen=${onTrustScreen()} failedConnectDots=${failedConnectIndicatorCount()}"
        trail += line
        Log.i(LOG_TAG, "ISSUE2463_TRAIL $line")
        println("ISSUE2463_TRAIL $line")
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private fun artifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) { "could not create ${dir.absolutePath}" }
        return dir
    }

    private fun writeTrail() {
        val file = File(artifactDir(), "issue2463-trail.txt")
        file.writeText(trail.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE2463_TRAIL_ARTIFACT ${file.absolutePath}")
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val file = File(artifactDir(), "$name.png")
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "could not write ${file.absolutePath}"
            }
        }
        bitmap.recycle()
        println("ISSUE2463_SCREENSHOT ${file.absolutePath}")
    }

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue2463TrustNav"
        const val DEVICE_DIR_NAME: String = "issue2463-host-key-trust-no-nav"
        const val MISMATCHED_FINGERPRINT: String =
            "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val HOST_ROW_WAIT_MS: Long = 30_000L
        // The reported navigation landed ~1 s after `hostlist-reprobe-kicked`;
        // this window covers three failing dials plus CI-AVD slack.
        const val HOST_LIST_WATCH_MS: Long = 20_000L
        const val REPROBE_SETTLE_MS: Long = 12_000L
        const val TRUST_SCREEN_WAIT_MS: Long = 30_000L
        const val TRUST_SCREEN_SETTLE_MS: Long = 8_000L
        const val MENU_WAIT_MS: Long = 15_000L
        const val POLL_INTERVAL_MS: Long = 50L
        const val RESUME_SESSION_NAME: String = "issue2463-resume"
    }
}
