package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.bootstrap.HOST_BOOTSTRAP_SKIP_TAG
import com.pocketshell.app.composer.COMPOSER_DRAFT_TAG
import com.pocketshell.app.composer.COMPOSER_OUTBOUND_QUEUE_BANNER_TAG
import com.pocketshell.app.composer.COMPOSER_OUTBOUND_QUEUE_TOGGLE_TAG
import com.pocketshell.app.composer.OUTBOUND_STALE_HOLD_MS
import com.pocketshell.app.composer.OutboundRoute
import com.pocketshell.app.composer.OutboundState
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.composer.SharedPrefsOutboundQueueStore
import com.pocketshell.app.composer.composerOutboundQueueItemRowTestTag
import com.pocketshell.app.composer.composerOutboundQueueRetryTestTag
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.app.voice.SESSION_COMPOSER_LAUNCHER_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestName
import org.junit.runner.RunWith

/**
 * Issue #1700 — the ON-DEVICE stale-flush journey.
 *
 * The maintainer's report: after the queue clogged, reconnect flushed
 * minutes-old prompts into a context that had moved on. #1686 fixed the clog
 * drain; this class is the staleness half.
 *
 * Clock injection: rows are seeded with a real-epoch [createdAtMs] already at
 * or over [OUTBOUND_STALE_HOLD_MS]. There is no five-minute sleep.
 *
 * G6 mutation: remove the atomic age gate in [com.pocketshell.app.composer.OutboundQueueStore.claim]
 * / [com.pocketshell.app.composer.firstComposerAutoFlushable]. Stale marker A
 * then appears in the real pane and this test fails.
 */
@RunWith(AndroidJUnit4::class)
class Issue1700StaleFlushJourneyE2eTest {

    val compose = createAndroidComposeRule<MainActivity>()

    val testName: TestName = TestName()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(testName)
        .around(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private lateinit var fixtureKey: String
    private lateinit var hostRowTag: String

    private suspend fun seedBeforeLaunch() {
        clearLastSessionPrefs()
        fixtureKey = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(fixtureKey))
        seedInteractiveSession(fixtureKey)
        hostRowTag = seedDockerHost(fixtureKey)
    }

    @After
    fun tearDown() {
        runBlocking { runCatching { cleanupRemoteTmuxSession(fixtureKey) } }
        runCatching {
            SharedPrefsOutboundQueueStore(
                InstrumentationRegistry.getInstrumentation().targetContext,
            ).clearSession(currentQueueSessionKeyOrEmpty())
        }
    }

    @Test
    fun staleQueuedPromptIsHeldAndFreshTailDrainsThenSendNowDeliversTheSameId() {
        val paneId = attachAndResolvePane()
        val sessionKey = currentQueueSessionKey()
        val store = SharedPrefsOutboundQueueStore(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        val composer = currentComposerViewModel()
        val now = System.currentTimeMillis()
        val staleMarker = "PS1700A${now.toString(36).takeLast(6)}"
        val freshMarker = "PS1700B${now.toString(36).takeLast(6)}"

        val stale = store.enqueue(
            sessionKey = sessionKey,
            cleanText = "# $staleMarker",
            createdAtMs = now - OUTBOUND_STALE_HOLD_MS - 1_000L,
            paneId = paneId,
            route = OutboundRoute.RawBytes,
            sendKey = "sk-1700-stale-$now",
        )
        val fresh = store.enqueue(
            sessionKey = sessionKey,
            cleanText = "# $freshMarker",
            createdAtMs = now,
            paneId = paneId,
            route = OutboundRoute.RawBytes,
            sendKey = "sk-1700-fresh-$now",
        )
        // Refreshes the durable snapshot only; production's live-screen drain
        // owns the retry/claim. No ViewModel retry method is injected here.
        compose.runOnUiThread { composer.refreshOutboundQueueItemsFor(sessionKey) }
        compose.waitUntil(timeoutMillis = DRAIN_TIMEOUT_MS) {
            store.item(fresh.id) == null &&
                store.item(stale.id)?.state == OutboundState.HeldForReview
        }
        assertEquals(OutboundState.HeldForReview, store.item(stale.id)?.state)
        assertEquals(stale.id, store.item(stale.id)?.id)
        assertEquals("# $staleMarker", store.item(stale.id)?.cleanText)

        val afterFresh = runBlocking { remoteCapture(fixtureKey, paneId) }
        writeText("issue1700-01-after-fresh-drain.txt", afterFresh)
        writeText(
            "issue1700-01-queue-after-fresh.txt",
            "stale=${store.item(stale.id)}\nfresh=${store.item(fresh.id)}\n",
        )
        assertTrue("fresh tail must reach the real pane:\n$afterFresh", freshMarker in afterFresh)
        assertFalse(
            "stale head must NOT silently flush into the live pane:\n$afterFresh",
            staleMarker in afterFresh,
        )

        openComposerQueue(stale.id)
        compose.waitUntil(timeoutMillis = COMPOSER_TIMEOUT_MS) {
            hasText("Needs review") && hasText("Send now")
        }
        val retryTag = composerOutboundQueueRetryTestTag(stale.id)
        val retryAction = compose.onNode(
            hasTestTag(retryTag) and hasClickAction(),
            // Match the merged clickable semantics node. The unmerged
            // interaction was a vacuous red: it found the outer tag but
            // invoked no callback.
            useUnmergedTree = false,
        )
        retryAction.performScrollTo().assertIsDisplayed().assertIsEnabled()
        val retryBounds = retryAction.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Send now must have non-empty visible bounds after scrolling: $retryBounds",
            retryBounds.width > 0f && retryBounds.height > 0f,
        )
        compose.assertNodeFullyWithinRoot(retryTag, useUnmergedTree = false)
        captureScreenshot("issue1700-01-needs-review-send-now.png")
        retryAction.performClick()

        try {
            compose.waitUntil(timeoutMillis = DRAIN_TIMEOUT_MS) {
                val remaining = store.item(stale.id)
                remaining == null
            }
        } catch (failure: Throwable) {
            // Preserve the product state at the failed click. This is diagnostic
            // evidence only; the strict row-removal oracle above remains intact.
            writeText(
                "issue1700-02-after-send-now-timeout.txt",
                buildString {
                    appendLine("failure=${failure::class.java.name}: ${failure.message}")
                    appendLine("storeRow=${store.item(stale.id)}")
                    appendLine("composerRows=${composer.outboundQueueItems.value}")
                    appendLine("composerUi=${composer.uiState.value}")
                    appendLine("transportWritable=${composer.isSendTransportWritable()}")
                },
            )
            throw failure
        }
        val afterSendNow = runBlocking { remoteCapture(fixtureKey, paneId) }
        writeText("issue1700-02-after-send-now.txt", afterSendNow)
        assertEquals(
            "Send now must deliver the SAME row exactly once:\n$afterSendNow",
            1,
            Regex(Regex.escape(staleMarker)).findAll(afterSendNow).count(),
        )
        assertEquals(
            "fresh tail stays exactly-once:\n$afterSendNow",
            1,
            Regex(Regex.escape(freshMarker)).findAll(afterSendNow).count(),
        )
    }

    private fun openComposerQueue(itemId: String) {
        compose.waitUntil(timeoutMillis = COMPOSER_TIMEOUT_MS) {
            hasTag(SESSION_COMPOSER_LAUNCHER_TAG) || hasTag(COMPOSER_DRAFT_TAG)
        }
        if (!hasTag(COMPOSER_DRAFT_TAG)) {
            compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG, useUnmergedTree = true)
                .performClick()
            compose.waitUntil(timeoutMillis = COMPOSER_TIMEOUT_MS) { hasTag(COMPOSER_DRAFT_TAG) }
        }
        compose.waitUntil(timeoutMillis = COMPOSER_TIMEOUT_MS) {
            hasTag(COMPOSER_OUTBOUND_QUEUE_BANNER_TAG)
        }
        // The header always owns COMPOSER_OUTBOUND_QUEUE_TOGGLE_TAG, in both
        // collapsed and expanded states. Do not use its mere presence as an
        // expansion oracle: the collapsed header also contains a nested Send
        // now button, and a wait on its text can succeed before the toggle's
        // recomposition has settled. Require the exact row node instead, then
        // click the header only when the row is not mounted yet.
        val rowTag = composerOutboundQueueItemRowTestTag(itemId)
        if (!hasTag(rowTag)) {
            compose.onNodeWithTag(COMPOSER_OUTBOUND_QUEUE_TOGGLE_TAG, useUnmergedTree = true)
                .performClick()
        }
        compose.waitUntil(timeoutMillis = COMPOSER_TIMEOUT_MS) {
            hasTag(rowTag)
        }
        compose.onNodeWithTag(rowTag, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun attachAndResolvePane(): String {
        compose.waitUntil(timeoutMillis = HOST_ROW_TIMEOUT_MS) {
            runCatching {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        runCatching {
            compose.waitUntil(timeoutMillis = BOOTSTRAP_SHEET_PROBE_MS) {
                compose.onAllNodesWithTag(HOST_BOOTSTRAP_SKIP_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(HOST_BOOTSTRAP_SKIP_TAG, useUnmergedTree = true).performClick()
        }
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        compose.waitUntil(timeoutMillis = 30_000) {
            var attached = false
            compose.activityRule.scenario.onActivity { activity ->
                attached = activity.window.decorView.findTerminalView()
                    ?.currentSession?.emulator != null
            }
            attached
        }
        val vm = currentTmuxViewModel()
        return requireNotNull(vm.panes.value.firstOrNull()?.paneId) {
            "expected at least one attached pane to send into"
        }
    }

    private fun currentQueueSessionKey(): String =
        requireNotNull(currentTmuxViewModel().currentTargetSessionKeyForTest()) {
            "a live session target is required to read the composer queue"
        }

    private fun currentQueueSessionKeyOrEmpty(): String =
        runCatching { currentQueueSessionKey() }.getOrDefault("")

    private fun currentTmuxViewModel(): TmuxSessionViewModel {
        var vm: TmuxSessionViewModel? = null
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.activityRule.scenario.onActivity { activity ->
                vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
            }
            if ((vm?.panes?.value?.isNotEmpty()) == true) break
            SystemClock.sleep(100)
        }
        return requireNotNull(vm) { "TmuxSessionViewModel not available" }
    }

    private fun currentComposerViewModel(): PromptComposerViewModel {
        var vm: PromptComposerViewModel? = null
        compose.activityRule.scenario.onActivity { activity ->
            vm = ViewModelProvider(activity)[PromptComposerViewModel::class.java]
        }
        return requireNotNull(vm) { "PromptComposerViewModel not available" }
    }

    private fun hasTag(tag: String): Boolean = runCatching {
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)

    private fun hasText(text: String): Boolean = runCatching {
        compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
    }.getOrDefault(false)

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context.assets.open("test_key").bufferedReader().use { it.readText() }

    private suspend fun seedDockerHost(key: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue1700-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue1700 Stale Flush",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                ),
            )
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

    private suspend fun seedInteractiveSession(key: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_NAME)} -x 80 -y 24 " +
                    shellQuote("exec bash --norc --noprofile"),
            )
            appendLine("sleep 1")
            appendLine("tmux list-sessions")
        }
        val result = execRemoteSetupUntilReady(
            key = SshKey.Pem(key),
            command = script,
            description = "issue1700 interactive shell seed",
        )
        assertTrue(
            "expected interactive session seeding to succeed; exit=${result.exitCode} " +
                "stderr='${result.stderr}'",
            result.exitCode == 0,
        )
    }

    private suspend fun remoteExec(key: String, command: String): String =
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(command).stdout } }.getOrElse { "" }

    private suspend fun remoteCapture(key: String, paneId: String): String =
        remoteExec(key, "tmux capture-pane -p -t ${shellQuote(paneId)} 2>&1 || true")

    private suspend fun cleanupRemoteTmuxSession(key: String) {
        remoteExec(key, "tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
    }

    private fun writeText(name: String, text: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) { "could not create ${dir.absolutePath}" }
        val file = File(dir, name)
        file.writeText(text)
        println("ISSUE1700_TEXT ${file.absolutePath}")
        return file
    }

    private fun captureScreenshot(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "UiAutomation returned no screenshot for $name"
        }
        check(bitmap.width == 1080 && bitmap.height == 2400) {
            "Issue #1700 screenshot must be the full emulator viewport: " +
                "${bitmap.width}x${bitmap.height}"
        }
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) { "could not create ${dir.absolutePath}" }
        val file = File(dir, name)
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "could not write screenshot: ${file.absolutePath}"
            }
        }
        bitmap.recycle()
        println("ISSUE1700_SCREENSHOT ${file.absolutePath}")
        return file
    }

    private fun View.findTerminalView(): TerminalView? {
        if (this is TerminalView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            val match = getChildAt(index).findTerminalView()
            if (match != null) return match
        }
        return null
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val DEVICE_DIR_NAME: String = "issue1700-stale-flush"
        const val SESSION_NAME: String = "issue1700-stale"
        const val HOST_ROW_TIMEOUT_MS: Long = 60_000L
        const val BOOTSTRAP_SHEET_PROBE_MS: Long = 20_000L
        const val COMPOSER_TIMEOUT_MS: Long = 30_000L
        const val DRAIN_TIMEOUT_MS: Long = 60_000L
    }
}
