package com.pocketshell.app.proof

import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.composer.COMPOSER_MIC_TAG
import com.pocketshell.app.composer.COMPOSER_CLOSE_TAG
import com.pocketshell.app.composer.COMPOSER_TIMER_TAG
import com.pocketshell.app.composer.COMPOSER_WAVEFORM_TAG
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.app.proof.signals.repokeSessionPickerFromHostRow
import com.pocketshell.app.proof.signals.waitForSessionInPicker as waitForSessionInPickerSignal
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_COMPACT_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_FULL_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.voice.ComposerLauncherIcon
import com.pocketshell.app.voice.ComposerLauncherIconSemanticsKey
import com.pocketshell.app.voice.SESSION_COMPOSER_LAUNCHER_ICON_TAG
import com.pocketshell.app.voice.SESSION_COMPOSER_LAUNCHER_TAG
import com.pocketshell.app.voice.SESSION_COMPOSER_LAUNCHER_CONTENT_DESCRIPTION
import com.pocketshell.app.voice.VOICE_GESTURE_COACHMARK_COPY
import com.pocketshell.app.voice.VOICE_GESTURE_COACHMARK_TAG
import com.pocketshell.app.voice.VOICE_GESTURE_DICTATION_ACTION_LABEL
import com.pocketshell.app.voice.VOICE_GESTURE_NATIVE_DICTATION_ACTION_ID
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.voice.AndroidKeystoreApiKeyStorage
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.RuleChain

/**
 * Issue #1753 production-route backstop.
 *
 * Unlike [VoiceGestureCoachmarkLauncherProofTest], this class never calls
 * `setContent`, never mounts a ComponentActivity, and never supplies a fake
 * composer ViewModel. [SeedBeforeLaunchRule] seeds the real Docker host and
 * tmux session before [MainActivity] starts; the test then drives the actual
 * host-list -> session-picker -> [com.pocketshell.app.tmux.TmuxSessionScreen]
 * route and opens the production Hilt-backed composer.
 *
 * The test drives the real pointer path into the production composer, then
 * separately proves tap-only activation, navigation/session switching,
 * recreation durability, and the platform accessibility node. The connected
 * fixture deliberately has no Whisper key, so both dictation paths must reach
 * the production OpenAI API-key gate; the component proof owns the recording
 * surface assertion with a deterministic microphone seam. It does not claim
 * that Compose semantics invocation is equivalent to a TalkBack service
 * gesture; the node artifact is retained for that separate reviewer check.
 */
@RunWith(AndroidJUnit4::class)
class VoiceGestureCoachmarkMainActivityDockerTest {

    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private lateinit var fixtureKey: String
    private lateinit var hostRowTag: String

    @Before
    fun clearEducationLedger() {
        InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getSharedPreferences(EDUCATION_PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun cleanupFixture() {
        if (::fixtureKey.isInitialized) {
            runCatching { runBlocking { cleanupTmuxSession(fixtureKey) } }
        }
        clearLastSessionPrefs()
        clearVoiceApiKey()
    }

    @Test
    fun realMainActivityDockerSessionMountsCoachmarkAndOpensProductionComposer() {
        runBlocking {
            compose.waitUntil(timeoutMillis = 20_000) {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()

            waitForSessionInPickerSignal(
                rule = compose,
                sessionName = SESSION_A,
                timeoutMs = 30_000,
                onRepoke = {
                    repokeSessionPickerFromHostRow(
                        rule = compose,
                        hostRowTag = hostRowTag,
                    )
                },
            )
            compose.onNodeWithText(SESSION_A, useUnmergedTree = true).performClick()
            compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()

            waitForCoachmarkAndLauncherWithDiagnostics()
            compose.assertNodeFullyWithinRoot(VOICE_GESTURE_COACHMARK_TAG)
            compose.assertNodeFullyWithinRoot(SESSION_COMPOSER_LAUNCHER_TAG)
            assertLauncherIconIdentity()
            assertCoachmarkEndAnchor()
            assertPlatformAccessibilityContract()
            WalkthroughScreenshotArtifacts.capture("issue-1753-mainactivity-docker-coachmark-pre-action")
            invokeNativeDictationAction()
            WalkthroughScreenshotArtifacts.capture("issue-1753-mainactivity-docker-coachmark")
            waitForTerminalMarker(SESSION_A_MARKER)

            // This is the load-bearing device proof: a real continuous pointer
            // sequence leaves the mounted production launcher, crosses the
            // 40dp threshold, and must reach the production dictation branch.
            // With no stored key, that branch is observed at the real key gate,
            // which distinguishes swipe-up from the compose-only tap path.
            compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).performTouchInput {
                down(center)
                advanceEventTime(80L)
                repeat(8) {
                    moveBy(Offset(0f, -28f))
                    advanceEventTime(16L)
                }
                up()
            }
            waitForApiKeyGate()
            WalkthroughScreenshotArtifacts.capture("issue-1753-mainactivity-docker-swipe-key-gate")
            dismissApiKeyGateAndComposer()

            // A separate fresh activation on the same real session remains
            // compose-only: the launcher tap must show Idle without a timer or
            // waveform. This is intentionally distinct from the swipe proof.
            compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG).performClick()
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithTag(COMPOSER_MIC_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(COMPOSER_MIC_TAG).assertExists()
            compose.onNodeWithTag(COMPOSER_TIMER_TAG).assertDoesNotExist()
            compose.onNodeWithTag(COMPOSER_WAVEFORM_TAG).assertDoesNotExist()
            WalkthroughScreenshotArtifacts.capture("issue-1753-mainactivity-docker-tap-idle")
            compose.onNodeWithTag(COMPOSER_CLOSE_TAG).performClick()

            // Navigation away/back plus A→B→A switching must retain the
            // launcher while the already-presented coachmark never returns.
            clickTmuxBack()
            waitForSessionInPickerWithRepoke(SESSION_B)
            compose.onNodeWithText(SESSION_B, useUnmergedTree = true).performClick()
            compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
            waitForTerminalMarker(SESSION_B_MARKER)
            assertLauncherPresentWithoutCoachmark("session B")
            WalkthroughScreenshotArtifacts.capture("issue-1753-mainactivity-docker-session-b")

            clickTmuxBack()
            waitForSessionInPickerWithRepoke(SESSION_A)
            compose.onNodeWithText(SESSION_A, useUnmergedTree = true).performClick()
            compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
            waitForTerminalMarker(SESSION_A_MARKER)
            assertLauncherPresentWithoutCoachmark("session A after switch back")

            // Activity recreation is the app-restart boundary available inside
            // this single connected test. The persisted ledger must suppress
            // the coachmark while the real route and launcher return.
            compose.activityRule.scenario.recreate()
            compose.waitUntil(timeoutMillis = 30_000) {
                compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            waitForTerminalMarker(SESSION_A_MARKER)
            assertLauncherPresentWithoutCoachmark("session A after activity recreation")
            WalkthroughScreenshotArtifacts.capture("issue-1753-mainactivity-docker-after-recreate")
        }
    }

    private suspend fun seedBeforeLaunch() {
        fixtureKey = readFixtureKey()
        clearLastSessionPrefs()
        clearEducationPreference()
        clearVoiceApiKey()
        waitForSshFixtureReady(SshKey.Pem(fixtureKey))
        seedTmuxSession(fixtureKey)
        hostRowTag = seedDockerHost(fixtureKey)
        forceFlatHostDetailViewMode()
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private suspend fun seedDockerHost(key: String): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            database.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = context,
                sshKeyDao = database.sshKeyDao(),
                name = "issue1753-mainactivity-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = database.hostDao().insert(
                HostEntity(
                    name = HOST_NAME,
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
            database.close()
        }
    }

    private suspend fun seedTmuxSession(key: String) {
        val script = buildString {
            appendLine("set -eu")
            listOf(SESSION_A, SESSION_B).forEach { sessionName ->
                appendLine("tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true")
                appendLine(
                    "tmux new-session -d -s ${shellQuote(sessionName)} " +
                        shellQuote("printf '${markerFor(sessionName)}\\n'; exec sleep 600"),
                )
                appendLine("tmux has-session -t ${shellQuote(sessionName)}")
            }
        }
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(script) } }
        assertTrue(
            "real Docker tmux seed failed: exception=${result.exceptionOrNull()} " +
                "stderr=${result.getOrNull()?.stderr}",
            result.getOrNull()?.exitCode == 0,
        )
    }

    private suspend fun cleanupTmuxSession(key: String) {
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                it.exec(
                    SESSION_NAMES.joinToString("; ") { name ->
                        "tmux kill-session -t ${shellQuote(name)} 2>/dev/null || true"
                    },
                )
            }
        }
    }

    private fun waitForApiKeyGate() {
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText(
                OPENAI_API_KEY_TITLE,
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(OPENAI_API_KEY_TITLE, useUnmergedTree = true).assertExists()
    }

    private fun dismissApiKeyGateAndComposer() {
        compose.onNodeWithText(API_KEY_CANCEL_LABEL, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText(
                OPENAI_API_KEY_TITLE,
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag(COMPOSER_CLOSE_TAG).performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(COMPOSER_CLOSE_TAG).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun waitForCoachmarkAndLauncherWithDiagnostics() {
        try {
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithTag(SESSION_COMPOSER_LAUNCHER_TAG)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        } catch (error: Throwable) {
            val launcherCount = compose.onAllNodesWithTag(
                SESSION_COMPOSER_LAUNCHER_TAG,
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size
            Log.e(
                "Issue1753Coachmark",
                "launcher-timeout launcher=$launcherCount",
                error,
            )
            throw error
        }

        try {
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithTag(
                    VOICE_GESTURE_COACHMARK_TAG,
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText(
                        VOICE_GESTURE_COACHMARK_COPY,
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: Throwable) {
            val launcherCount = compose.onAllNodesWithTag(
                SESSION_COMPOSER_LAUNCHER_TAG,
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size
            val coachmarkCount = compose.onAllNodesWithTag(
                VOICE_GESTURE_COACHMARK_TAG,
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size
            val coachmarkTextCount = compose.onAllNodesWithText(
                VOICE_GESTURE_COACHMARK_COPY,
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size
            Log.e(
                "Issue1753Coachmark",
                "coachmark-timeout launcher=$launcherCount coachmarkTag=$coachmarkCount " +
                    "coachmarkText=$coachmarkTextCount",
                error,
            )
            Log.e(
                "Issue1753Coachmark",
                "semanticsRoot=" + compose.onRoot(useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .toString(),
            )
            throw error
        }
    }

    private fun waitForTerminalMarker(marker: String) {
        waitForTerminalViewAttached()
        compose.waitUntil(timeoutMillis = 20_000) {
            val terminal = currentTerminalText()
            terminal.contains(marker)
        }
    }

    private fun currentTerminalText(): String {
        var transcript = ""
        compose.activityRule.scenario.onActivity { activity ->
            transcript = activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.screen
                ?.transcriptText
                .orEmpty()
        }
        return transcript
    }

    private fun waitForSessionInPickerWithRepoke(sessionName: String) {
        waitForSessionInPickerSignal(
            rule = compose,
            sessionName = sessionName,
            timeoutMs = 30_000,
            onRepoke = {
                repokeSessionPickerFromHostRow(
                    rule = compose,
                    hostRowTag = hostRowTag,
                )
            },
        )
    }

    private fun clickTmuxBack() {
        val tag = listOf(
            TMUX_COMPACT_CHROME_BACK_BUTTON_TAG,
            TMUX_FULL_CHROME_BACK_BUTTON_TAG,
        ).firstOrNull { candidate ->
            compose.onAllNodesWithTag(candidate, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        } ?: error("real session screen did not expose a back control")
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
    }

    private fun assertLauncherPresentWithoutCoachmark(label: String) {
        compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG, useUnmergedTree = true).assertExists()
        compose.onAllNodesWithTag(VOICE_GESTURE_COACHMARK_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .also { nodes ->
                assertTrue(
                    "$label must retain the launcher without re-showing the coachmark",
                    nodes.isEmpty(),
                )
            }
    }

    private fun markerFor(sessionName: String): String = when (sessionName) {
        SESSION_A -> SESSION_A_MARKER
        SESSION_B -> SESSION_B_MARKER
        else -> error("unknown #1753 session $sessionName")
    }

    private fun waitForTerminalViewAttached() {
        compose.waitUntil(timeoutMillis = 30_000) {
            var attached = false
            compose.activityRule.scenario.onActivity { activity ->
                val terminal = activity.window.decorView.findTerminalView()
                attached = terminal?.currentSession?.emulator != null
            }
            attached
        }
    }

    private fun assertLauncherIconIdentity() {
        val icon = compose
            .onNodeWithTag(SESSION_COMPOSER_LAUNCHER_ICON_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
        assertSame(
            "the real MainActivity route must render ComposerLauncherIcon",
            ComposerLauncherIcon,
            icon.config[ComposerLauncherIconSemanticsKey],
        )
        assertEquals("ComposerLauncher", icon.config[ComposerLauncherIconSemanticsKey]?.name)
    }

    private fun assertCoachmarkEndAnchor() {
        val coachmark = compose.onNodeWithTag(VOICE_GESTURE_COACHMARK_TAG)
            .getUnclippedBoundsInRoot()
        val launcher = compose.onNodeWithTag(SESSION_COMPOSER_LAUNCHER_TAG)
            .getUnclippedBoundsInRoot()
        assertEquals(
            "the real coachmark must share the launcher's end anchor",
            launcher.right,
            coachmark.right,
        )
        assertTrue(
            "the real coachmark must sit above the real launcher: " +
                "coachmark=$coachmark launcher=$launcher",
            coachmark.bottom <= launcher.top,
        )
    }

    private fun assertPlatformAccessibilityContract() {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val root = checkNotNull(
            InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow,
        ) { "MainActivity did not expose an accessibility root" }
        val matchingNodes = buildList {
            collectAccessibilityNodesWithDescription(
                node = root,
                description = SESSION_COMPOSER_LAUNCHER_CONTENT_DESCRIPTION,
                path = "root",
                output = this,
            )
        }
        val discoverableNodes = matchingNodes.filter { it.node.isDiscoverableForAccessibility() }
        val validNodes = discoverableNodes.filter { it.node.isValidPlatformLauncher() }
        val launcher = validNodes.singleOrNull()?.node
        val actions = launcher?.actionList.orEmpty()
        // Refresh the node returned by Android's accessibility bridge before
        // recording the contract. The action must survive the bridge's
        // invalidation/refresh boundary, not merely appear in one stale tree.
        val nativeNodeRefreshAccepted = launcher?.refresh() == true
        val refreshedActions = launcher?.actionList.orEmpty()
        val refreshedValidNode = launcher?.isValidPlatformLauncher() == true
        val composeSemantics = buildString {
            appendComposeSemantics(
                node = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(),
                indent = "",
                output = this,
            )
        }
        val composeMergedSemantics = buildString {
            appendComposeSemantics(
                node = compose.onRoot().fetchSemanticsNode(),
                indent = "",
                output = this,
            )
        }
        val report = buildString {
            appendLine("content_description=$SESSION_COMPOSER_LAUNCHER_CONTENT_DESCRIPTION")
            appendLine("node_found=${launcher != null}")
            val accessibilityManager = InstrumentationRegistry.getInstrumentation()
                .targetContext.getSystemService(AccessibilityManager::class.java)
            appendLine("accessibility_enabled=${accessibilityManager?.isEnabled}")
            appendLine("touch_exploration_enabled=${accessibilityManager?.isTouchExplorationEnabled}")
            appendLine("enabled_service_count=${accessibilityManager?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)?.size}")
            appendLine("native_node=$launcher")
            appendLine("native_action_id=$VOICE_GESTURE_NATIVE_DICTATION_ACTION_ID")
            appendLine("native_action_id_type=${VOICE_GESTURE_NATIVE_DICTATION_ACTION_ID and 0xFF000000.toInt()}")
            appendLine("actions=${actions.joinToString { it.label?.toString().orEmpty() }}")
            appendLine("dictation_action_present=${actions.any { it.label?.toString() == VOICE_GESTURE_DICTATION_ACTION_LABEL }}")
            appendLine("native_node_refresh=$nativeNodeRefreshAccepted")
            appendLine("refreshed_actions=${refreshedActions.joinToString { it.label?.toString().orEmpty() }}")
            appendLine("refreshed_dictation_action_present=${refreshedActions.any { it.id == VOICE_GESTURE_NATIVE_DICTATION_ACTION_ID && it.label?.toString() == VOICE_GESTURE_DICTATION_ACTION_LABEL }}")
            appendLine("refreshed_valid_platform_launcher=$refreshedValidNode")
            appendLine("discoverable_platform_nodes=${discoverableNodes.size}")
            appendLine("valid_platform_nodes=${validNodes.size}")
            appendLine("matching_accessibility_nodes=${matchingNodes.size}")
            matchingNodes.forEach { appendLine(describeAccessibilityNode(it)) }
            appendLine("compose_unmerged_semantics_begin")
            append(composeSemantics)
            appendLine("compose_unmerged_semantics_end")
            appendLine("compose_merged_semantics_begin")
            append(composeMergedSemantics)
            appendLine("compose_merged_semantics_end")
        }
        artifactFile("issue-1753-mainactivity-accessibility.txt").writeText(report)
        assertTrue(
            "exactly one real Android node must be clickable and expose ACTION_CLICK plus " +
                "'$VOICE_GESTURE_DICTATION_ACTION_LABEL'; " +
                "report=$report",
            validNodes.size == 1 &&
                nativeNodeRefreshAccepted &&
                refreshedValidNode,
        )
    }

    private fun invokeNativeDictationAction() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val root = checkNotNull(
            InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow,
        ) { "MainActivity did not expose an accessibility root for action dispatch" }
        val launcher = buildList {
            collectAccessibilityNodesWithDescription(
                node = root,
                description = SESSION_COMPOSER_LAUNCHER_CONTENT_DESCRIPTION,
                path = "root",
                output = this,
            )
        }.single { it.node.isValidPlatformLauncher() }.node
        assertTrue(
            "the fresh native launcher node must refresh before dispatch",
            launcher.refresh(),
        )
        assertTrue(
            "the refreshed native launcher must retain the accepted dictation action",
            launcher.actionList.any {
                it.id == VOICE_GESTURE_NATIVE_DICTATION_ACTION_ID &&
                    it.label?.toString() == VOICE_GESTURE_DICTATION_ACTION_LABEL
            },
        )
        assertTrue(
            "the real Android dictation action must dispatch through the launcher node",
            launcher.performAction(VOICE_GESTURE_NATIVE_DICTATION_ACTION_ID),
        )
        // The same native action must enter the production dictation branch.
        // With no configured key, its first observable production boundary is
        // the real OpenAI key dialog rather than a fake recording surface.
        waitForApiKeyGate()
        WalkthroughScreenshotArtifacts.capture("issue-1753-mainactivity-docker-accessibility-key-gate")
        dismissApiKeyGateAndComposer()
    }

    private fun clearVoiceApiKey() {
        AndroidKeystoreApiKeyStorage(
            InstrumentationRegistry.getInstrumentation().targetContext,
        ).clear()
    }

    private data class AccessibilityNodeRecord(
        val path: String,
        val node: AccessibilityNodeInfo,
    )

    private fun collectAccessibilityNodesWithDescription(
        node: AccessibilityNodeInfo,
        description: String,
        path: String,
        output: MutableList<AccessibilityNodeRecord>,
    ) {
        if (node.contentDescription?.toString() == description) {
            output += AccessibilityNodeRecord(path = path, node = node)
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                collectAccessibilityNodesWithDescription(
                    node = child,
                    description = description,
                    path = "$path/$index",
                    output = output,
                )
            }
        }
    }

    private fun describeAccessibilityNode(record: AccessibilityNodeRecord): String {
        val node = record.node
        val actions = node.actionList.joinToString { action ->
            "${action.id}:${action.label?.toString().orEmpty()}"
        }
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        return "path=${record.path} class=${node.className} " +
            "bounds=$bounds clickable=${node.isClickable} " +
            "enabled=${node.isEnabled} visibleToUser=${node.isVisibleToUser} " +
            "importantForAccessibility=${node.isImportantForAccessibility} " +
            "childCount=${node.childCount} actions=[$actions]"
    }

    private fun AccessibilityNodeInfo.isDiscoverableForAccessibility(): Boolean =
        isVisibleToUser && isImportantForAccessibility

    private fun AccessibilityNodeInfo.isValidPlatformLauncher(): Boolean =
        isDiscoverableForAccessibility() &&
            className == android.widget.Button::class.java.name &&
            isEnabled &&
            isClickable &&
            actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } &&
            actionList.any { it.label?.toString() == VOICE_GESTURE_DICTATION_ACTION_LABEL }

    private fun appendComposeSemantics(
        node: androidx.compose.ui.semantics.SemanticsNode,
        indent: String,
        output: StringBuilder,
    ) {
        output.append(indent)
            .append("id=")
            .append(node.id)
            .append(" config=")
            .append(node.config)
            .appendLine()
        node.children.forEach { child ->
            appendComposeSemantics(child, "$indent  ", output)
        }
    }

    private fun clearEducationPreference() {
        InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getSharedPreferences(EDUCATION_PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun forceFlatHostDetailViewMode() {
        InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("host_detail_view_mode", "Flat")
            .commit()
    }

    private fun artifactFile(name: String) =
        com.pocketshell.app.test.testArtifactsRoot(
            InstrumentationRegistry.getInstrumentation().targetContext,
        ).resolve("additional_test_output/issue-1753/$name").also {
            check(it.parentFile?.exists() == true || it.parentFile?.mkdirs() == true) {
                "could not create #1753 artifact directory"
            }
        }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun View.findTerminalView(): TerminalView? {
        if (this is TerminalView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            findTerminalViewAt(getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun findTerminalViewAt(view: View): TerminalView? {
        if (view is TerminalView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findTerminalViewAt(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private companion object {
        const val DATABASE_NAME = "pocketshell.db"
        const val EDUCATION_PREFS_NAME = "voice_education"
        const val HOST_NAME = "Issue1753 MainActivity Docker"
        const val SESSION_A = "issue1753-main-a"
        const val SESSION_B = "issue1753-main-b"
        val SESSION_NAMES = listOf(SESSION_A, SESSION_B)
        const val SESSION_A_MARKER = "ISSUE1753-MAIN-A-READY"
        const val SESSION_B_MARKER = "ISSUE1753-MAIN-B-READY"
        const val OPENAI_API_KEY_TITLE = "OpenAI API key"
        const val API_KEY_CANCEL_LABEL = "Cancel"
    }
}
