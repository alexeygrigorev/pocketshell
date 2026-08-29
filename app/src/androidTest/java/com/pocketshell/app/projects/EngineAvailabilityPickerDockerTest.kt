package com.pocketshell.app.projects

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.DefaultSshLeaseConnector
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Host-backed issue #2276 journey.
 *
 * Unlike [EngineAvailabilityPickerUiTest], this test does not construct the
 * RemoteEngine rows itself. It connects to the Docker agents fixture,
 * executes the real [SshEnginesGateway] command through its shared SSH lease,
 * and passes that exact result to the real picker content. The fixture's
 * `engines list --json` arm delegates to the repository's REAL
 * `pocketshell.engines` registry, so the resolution under test is the
 * production one running over a real non-interactive SSH `exec` channel.
 *
 * The whole contract is asserted against that one real manifest:
 *  - an installed, enabled `codex` row IS offered and can actually be picked
 *    (the maintainer's reported symptom was an installed Codex that never
 *    appeared in New session at all);
 *  - `nvm-only-engine` and `login-only-engine` are installed but NOT on this
 *    exec channel's PATH — the state the maintainer actually hit, where
 *    `codex`/`opencode` live in an nvm node bin directory the exec channel
 *    never sees. Both must be offered, because the pane's login shell (where
 *    the harness is really launched) resolves them fine;
 *  - `forced-engine` has no binary anywhere and is offered only because
 *    `force_available: true` pins it — the manual escape hatch;
 *  - a missing harness (`fixture-missing-engine`), an absent built-in
 *    (`grok`) and a present-but-disabled harness (`codex-disabled`) are
 *    absent from the create choices entirely, not merely greyed out.
 */
@RunWith(AndroidJUnit4::class)
class EngineAvailabilityPickerDockerTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var keyFile: File
    private lateinit var sshKey: SshKey.Pem

    @Test
    fun realHostManifestHidesUnavailableAndDisabledEnginesFromPicker(): Unit {
        val keyText = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("test_key").bufferedReader().use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        keyFile = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "issue2276-engine-availability-key",
        ).apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }

        val engines = runBlocking {
            waitForSshFixtureReady(sshKey)
            val leaseManager = SshLeaseManager(connector = DefaultSshLeaseConnector())
            try {
                val host = HostEntity(
                    id = 2276L,
                    name = "issue-2276-agents",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = 2276L,
                )
                val result = withTimeout(30_000) {
                    SshEnginesGateway(leaseManager).listEngines(
                        host = host,
                        keyPath = keyFile.absolutePath,
                        passphrase = null,
                    )
                }
                assertTrue(
                    "expected a real engines manifest, got $result; ToolUnavailable " +
                        "would mean the fixture command is missing",
                    result is EnginesResult.Engines,
                )
                (result as EnginesResult.Engines).engines
            } finally {
                leaseManager.close()
            }
        }

        val available = engines.singleOrNull { it.id == "claude" }
        val installedCodex = engines.singleOrNull { it.id == "codex" }
        val nvmOnly = engines.singleOrNull { it.id == "nvm-only-engine" }
        val loginOnly = engines.singleOrNull { it.id == "login-only-engine" }
        val forced = engines.singleOrNull { it.id == "forced-engine" }
        val missing = engines.singleOrNull { it.id == "fixture-missing-engine" }
        val absentBuiltIn = engines.singleOrNull { it.id == "grok" }
        val disabled = engines.singleOrNull { it.id == "codex-disabled" }
        assertNotNull("fixture manifest must contain the available row", available)
        assertNotNull("fixture manifest must contain the installed codex row", installedCodex)
        assertNotNull("fixture manifest must contain the nvm-only row", nvmOnly)
        assertNotNull("fixture manifest must contain the login-only row", loginOnly)
        assertNotNull("fixture manifest must contain the forced row", forced)
        assertNotNull("fixture manifest must contain the missing row", missing)
        assertNotNull("fixture manifest must contain the absent built-in row", absentBuiltIn)
        assertNotNull("fixture manifest must contain the disabled row", disabled)
        assertTrue("fixture claude harness must be present", available!!.available)
        assertTrue("fixture claude must be createable", available.availableForCreate)
        assertTrue("installed codex harness must be present", installedCodex!!.available)
        assertTrue("installed codex must be createable", installedCodex.availableForCreate)

        // The #2276 round-4 state: installed on disk, launchable from the
        // pane's login shell, invisible to the PATH this manifest command
        // itself runs with. A bare `which` probe reports these two as "not
        // installed on this host" and the picker hides an installed engine —
        // exactly what happened to the maintainer's nvm-installed codex.
        assertTrue(
            "nvm-installed harness must resolve; reason was ${nvmOnly!!.unavailableReason}",
            nvmOnly.available,
        )
        assertTrue("nvm-installed harness must be createable", nvmOnly.availableForCreate)
        assertTrue(
            "login-shell-only harness must resolve; reason was " +
                "${loginOnly!!.unavailableReason}",
            loginOnly.available,
        )
        assertTrue("login-shell-only harness must be createable", loginOnly.availableForCreate)
        // The manual escape hatch: no binary anywhere, pinned by config.
        assertTrue("force_available row must be available", forced!!.available)
        assertTrue("force_available row must be createable", forced.availableForCreate)

        assertFalse("fixture missing harness must be unavailable", missing!!.available)
        assertFalse("missing harness must not be createable", missing.availableForCreate)
        assertTrue(
            "missing harness reason must come from the host manifest",
            missing.unavailableReason?.contains("not installed") == true,
        )
        assertFalse("uninstalled built-in must be unavailable", absentBuiltIn!!.available)
        assertFalse("uninstalled built-in must not be createable", absentBuiltIn.availableForCreate)
        assertTrue("disabled harness remains present", disabled!!.available)
        assertFalse("disabled harness must not be createable", disabled.availableForCreate)
        assertEquals("disabled in the host registry", disabled.unavailableReason)

        var choice: SessionTypeChoice? = null
        compose.setContent {
            PocketShellTheme {
                SessionTypePickerContent(
                    folderPath = "/srv/issue-2276",
                    folderLabel = "issue-2276",
                    onCancel = {},
                    onCreate = { choice = it },
                    engines = engines,
                )
            }
        }

        // The rows below are the SshEnginesGateway result above, not an
        // injected projection fixture. Visible Claude AND Codex rows prove the
        // real picker received the host registry and offers every installed,
        // enabled engine; the two unsafe rows must never be offered as create
        // choices.
        compose.onNodeWithTag(sessionTypePickerAgentEngineTag("claude"))
            .assertIsDisplayed()
        compose.onNodeWithTag(sessionTypePickerAgentEngineTag("codex"))
            .assertIsDisplayed()
        compose.onNodeWithTag(sessionTypePickerAgentEngineTag("nvm-only-engine"))
            .assertExists()
        compose.onNodeWithTag(sessionTypePickerAgentEngineTag("login-only-engine"))
            .assertExists()
        compose.onNodeWithTag(sessionTypePickerAgentEngineTag("forced-engine"))
            .assertExists()
        compose.onNodeWithTag(sessionTypePickerAgentEngineTag("fixture-missing-engine"))
            .assertDoesNotExist()
        compose.onNodeWithTag(sessionTypePickerAgentEngineTag("grok"))
            .assertDoesNotExist()
        compose.onNodeWithTag(sessionTypePickerAgentEngineTag("codex-disabled"))
            .assertDoesNotExist()
        compose.onNodeWithText("Claude").assertIsDisplayed()
        compose.onNodeWithText("Codex").assertIsDisplayed()
        compose.onNodeWithText("Missing fixture engine").assertDoesNotExist()
        compose.onNodeWithText("Disabled Codex fixture").assertDoesNotExist()
        compose.onNodeWithText("Grok").assertDoesNotExist()

        captureScreenshot("engine-picker-from-real-host-manifest")

        // The installed Codex engine must be genuinely pickable, not just
        // rendered: select it and create with it.
        compose.onNodeWithTag(sessionTypePickerAgentEngineTag("codex")).performClick()
        compose.waitForIdle()
        captureScreenshot("engine-picker-codex-selected")
        compose.onNodeWithTag(SESSION_TYPE_PICKER_CREATE_TAG).performClick()
        compose.waitForIdle()
        assertEquals("codex", choice?.engineId)
    }

    /**
     * Best-effort visual evidence for the review record. The load-bearing
     * proof is the semantics assertions above, so a capture failure on an
     * emulator image without a PixelCopy-able decor window must not redden the
     * journey.
     */
    private fun captureScreenshot(name: String) {
        runCatching {
            compose.waitForIdle()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
                ?: compose.onRoot().captureToImage().asAndroidBitmap()
            val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(
                instrumentation.targetContext,
            )
            val dir = File(mediaRoot, "additional_test_output/issue2276-engine-picker")
                .apply { mkdirs() }
            FileOutputStream(File(dir, "$name.png")).use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
