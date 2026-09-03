package com.pocketshell.next.connect

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.next.MainActivity
import com.pocketshell.next.hosts.hostRowTag
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Journey J01 — tap a host, answer the host-key prompt, land on its tree
 * (rewrite task U-2).
 *
 * This is the first instrumented test of the rewrite's app module, and its job
 * is to prove the whole new stack works on a device against a REAL server:
 * Room host row → Hilt graph → [ConnectionsRegistry] → core-transport's sshj
 * dial → [RoomTrustStore] → the trust sheet → navigation. None of that is
 * observable from the host JVM: the JVM suites all stop at a scripted
 * `HostConnectionFactory`, so a missing `INTERNET` permission, a BouncyCastle
 * gap on Android, an unreadable key file or a sheet that never reaches the
 * window would all be invisible there and fatal here.
 *
 * ## What the assertions are anchored to
 *
 * Every check is on the RENDERED screen or on the persisted database row —
 * never on `ConnectionsRegistry` / ViewModel state (D29: internal state can be
 * green while the screen is broken). The fingerprint shown in the sheet is
 * compared against the key the fixture sshd ACTUALLY presents, obtained by an
 * independent sshj probe in the seed step, so a sheet rendering a placeholder
 * (or the app trusting a key it never saw) fails rather than passes.
 *
 * ## Fixture
 *
 * Needs the Docker SSH fixture on `10.0.2.2:2222` — see [AgentsFixture] for the
 * exact bring-up command. The suite fails with an explicit "bring the fixture
 * up" message rather than a confusing UI timeout when it is absent.
 *
 * Host rows are inserted with EXPLICIT, per-test ids. SQLite reuses
 * `max(id) + 1` after a delete, so wiping and re-inserting would hand the next
 * test the same id — and the registry's one-connection-per-host cache would
 * then serve test 2 the connection test 1 opened, letting a "reject" test pass
 * without ever raising a prompt. Distinct ids (plus a registry `closeAll`)
 * remove both halves of that trap.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class J01ConnectAndTrustJourney {

    private val compose = createAndroidComposeRule<MainActivity>()

    /**
     * Order is load-bearing. Hilt first (it owns the component the seed reads
     * from), then the seed (MainActivity must launch into a populated database
     * — the compose rule launches in its own `before()`, ahead of any
     * `@Before`), then the activity.
     */
    @get:Rule
    val chain: RuleChain = RuleChain
        .outerRule(HiltAndroidRule(this))
        .around(SeedBeforeLaunchRule { description -> seed(description) })
        .around(compose)

    /** The fingerprint the fixture sshd actually presents, captured in [seed]. */
    private lateinit var presentedFingerprint: String

    private var hostId: Long = 0

    private suspend fun seed(description: Description) {
        val graph = appGraph()
        // A connection cached from an earlier test in this same process would
        // let a later tap skip the dial entirely.
        graph.connectionsRegistry().closeAll()

        // The app's data survives between tests AND between runs on the same
        // install, so a host trusted by a previous run would make the
        // first-contact test connect with no prompt at all — a pass that proves
        // nothing. Start every test from an empty hosts/keys table.
        graph.hostDao().getAll().first().forEach { graph.hostDao().deleteById(it.id) }
        graph.sshKeyDao().getAll().first().forEach { graph.sshKeyDao().deleteById(it.id) }

        presentedFingerprint = AgentsFixture.probeHostKeyFingerprint()
        println("J01_FIXTURE ${AgentsFixture.host}:${AgentsFixture.port} $presentedFingerprint")

        val keyPath = AgentsFixture.installPrivateKey()
        val keyId = graph.sshKeyDao().insert(
            SshKeyEntity(name = "j01-${description.methodName}", privateKeyPath = keyPath),
        )

        // A pre-stored fingerprint for the mismatch case only; every other test
        // starts from an untrusted host.
        val preTrusted = if (description.methodName.contains("changed", ignoreCase = true)) {
            STALE_FINGERPRINT
        } else {
            null
        }

        hostId = HOST_IDS.getValue(description.methodName)
        graph.hostDao().insert(
            HostEntity(
                id = hostId,
                name = "docker-fixture",
                hostname = AgentsFixture.host,
                port = AgentsFixture.port,
                username = AgentsFixture.USER,
                keyId = keyId,
                trustedHostKeyAlgorithm = preTrusted?.let { "SHA256" },
                trustedHostKeySha256 = preTrusted,
            ),
        )
    }

    /**
     * The headline journey: an untrusted host raises the prompt with the real
     * fingerprint, trusting it persists that fingerprint AND reconnects, and
     * the tree route renders.
     */
    @Test
    fun trustingAnUnknownHostKeyConnectsAndLandsOnTheTree() {
        awaitTag(hostRowTag(hostId))
        JourneyScreenshots.capture("01-host-list")

        compose.onNodeWithTag(hostRowTag(hostId)).performClick()

        // The dial reached a real server and came back "this key needs a
        // decision" — with the key that server actually presented.
        awaitTag(TRUST_SHEET_FINGERPRINT_TAG)
        JourneyScreenshots.capture("02-trust-prompt-unknown")
        compose.onNodeWithText(UNKNOWN_TITLE).assertIsDisplayed()
        compose.onNodeWithText(presentedFingerprint).assertIsDisplayed()
        compose.onNodeWithTag(TRUST_SHEET_PREVIOUS_FINGERPRINT_TAG).assertDoesNotExist()
        // Raising the prompt is not consent.
        assertNull("prompt must not store a key", storedFingerprint())
        // And it must not have navigated.
        compose.onNodeWithText(treeLabel()).assertDoesNotExist()

        compose.onNodeWithTag(TRUST_SHEET_TRUST_TAG).performClick()

        // Trust -> record -> full re-dial -> authenticated -> tree.
        awaitText(treeLabel())
        JourneyScreenshots.capture("03-tree-after-trust")
        compose.onNodeWithText(treeLabel()).assertIsDisplayed()
        assertEquals(
            "the trusted key must be the one the server presented",
            presentedFingerprint,
            storedFingerprint(),
        )
    }

    /** The acceptance criterion: rejecting stores nothing. */
    @Test
    fun rejectingTheTrustPromptStoresNoKeyAndStaysOnTheHostList() {
        awaitTag(hostRowTag(hostId))

        compose.onNodeWithTag(hostRowTag(hostId)).performClick()
        awaitTag(TRUST_SHEET_REJECT_TAG)

        compose.onNodeWithTag(TRUST_SHEET_REJECT_TAG).performClick()

        awaitGone(TRUST_SHEET_FINGERPRINT_TAG)
        JourneyScreenshots.capture("04-after-reject")

        // Room-level assertion: no key was stored.
        assertNull("reject must not store a host key", storedFingerprint())
        // Screen-level: still on the host list, never on the tree.
        compose.onNodeWithTag(hostRowTag(hostId)).assertIsDisplayed()
        compose.onNodeWithText(treeLabel()).assertDoesNotExist()
    }

    /**
     * The non-happy fixture state: the host row already trusts a DIFFERENT key.
     * The prompt must escalate (both fingerprints on screen, key-changed copy)
     * and rejecting must leave the previously trusted key exactly as it was —
     * a mismatch that silently overwrote, or silently cleared, the stored key
     * would be a security regression no happy-path test can see.
     */
    @Test
    fun aChangedHostKeyRaisesTheMismatchPromptAndRejectingKeepsTheOldKey() {
        awaitTag(hostRowTag(hostId))
        assertEquals(STALE_FINGERPRINT, storedFingerprint())

        compose.onNodeWithTag(hostRowTag(hostId)).performClick()

        awaitTag(TRUST_SHEET_PREVIOUS_FINGERPRINT_TAG)
        JourneyScreenshots.capture("05-trust-prompt-mismatch")

        compose.onNodeWithText(MISMATCH_TITLE).assertIsDisplayed()
        compose.onNodeWithText(MISMATCH_TRUST_LABEL).assertIsDisplayed()
        compose.onNodeWithText(UNKNOWN_TITLE).assertDoesNotExist()
        // Both keys are on screen, and they are genuinely different.
        compose.onNodeWithText(presentedFingerprint).assertIsDisplayed()
        compose.onNodeWithText(STALE_FINGERPRINT).assertIsDisplayed()
        assertNotEquals(STALE_FINGERPRINT, presentedFingerprint)
        assertTrue(presentedFingerprint.startsWith("SHA256:"))

        compose.onNodeWithTag(TRUST_SHEET_REJECT_TAG).performClick()
        awaitGone(TRUST_SHEET_PREVIOUS_FINGERPRINT_TAG)

        assertEquals(
            "rejecting a changed key must leave the old key untouched",
            STALE_FINGERPRINT,
            storedFingerprint(),
        )
        compose.onNodeWithText(treeLabel()).assertDoesNotExist()
    }

    private fun treeLabel(): String = "Tree(hostId=$hostId)"

    private fun storedFingerprint(): String? =
        runBlocking { appGraph().hostDao().getById(hostId)?.trustedHostKeySha256 }

    private fun awaitTag(tag: String) {
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitGone(tag: String) {
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L

        /** A plausible-looking fingerprint that is NOT the fixture's. */
        const val STALE_FINGERPRINT =
            "SHA256:0000000000000000000000000000000000000000000"

        /**
         * One distinct host-row id per test — see the class doc for why an
         * autoincrement id reused across tests would let a cached connection
         * make a later test pass vacuously.
         */
        val HOST_IDS: Map<String, Long> = mapOf(
            "trustingAnUnknownHostKeyConnectsAndLandsOnTheTree" to 9_101L,
            "rejectingTheTrustPromptStoresNoKeyAndStaysOnTheHostList" to 9_102L,
            "aChangedHostKeyRaisesTheMismatchPromptAndRejectingKeepsTheOldKey" to 9_103L,
        )
    }
}
