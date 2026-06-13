package com.pocketshell.app.projects

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.DefaultSshLeaseConnector
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * End-to-end host PROFILE DISCOVERY journey (issue #732, Finding B).
 *
 * `SessionTypeProfilePickerUiTest` proves the picker UI with HARD-CODED
 * profile lists, so it can never catch a real discovery break: if the host
 * `pocketshell profiles list` were silently missing, the gateway would
 * return [ProfilesResult.ToolUnavailable] and the picker would false-green
 * on the default-only fallback. This test closes that gap by exercising the
 * REAL host-discovery path against the deterministic Docker `agents` fixture
 * on `10.0.2.2:2222`:
 *
 *  1. [SshProfilesGateway] runs `pocketshell profiles list --json` over the
 *     warm SSH lease and parses the fixture's seeded profiles. The fixture
 *     entrypoint seeds `~/.claude` (the default "Claude" profile) and a
 *     sibling `~/.zlaude` (the non-default "Claude (Z.AI)" profile), each
 *     with a real marker file, so discovery is genuine — NOT a canned blob.
 *  2. The result is asserted to be [ProfilesResult.Profiles] (NOT
 *     ToolUnavailable / Failed) and to contain both seeded Claude profiles.
 *  3. The discovered rows are projected onto the picker's [ClaudeProfile]
 *     flow exactly as [FolderListViewModel] does, then the non-default
 *     "Claude (Z.AI)" profile is SELECTED and [AgentCli.launchCommand]
 *     threads its name into `--profile '<name>'`.
 *  4. That launch command is finally EXEC'd over SSH against the fixture's
 *     `pocketshell agent` wrapper, proving the discovered + selected profile
 *     routes end-to-end (the fixture agent prints its ready banner so we see
 *     the launch actually reached the agent, not just that a string was
 *     typed).
 *
 * CI: the `agents` fixture on host port `2222` is already wired into the
 * emulator job (no new Docker service), so this runs at PR time, not only in
 * the release gate.
 */
@RunWith(AndroidJUnit4::class)
class ProfileDiscoveryPickerDockerTest {

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File

    @Before
    fun setUp(): Unit = runBlocking {
        val keyText = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("test_key").bufferedReader().use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        keyFile = File(cacheDir, "issue732-profile-discovery-key").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }
        waitForSshFixtureReady(sshKey)
    }

    @Test
    fun discoversSeededHostProfilesAndSelectsOneEndToEnd(): Unit = runBlocking {
        val leaseManager = SshLeaseManager(connector = DefaultSshLeaseConnector())
        val gateway = SshProfilesGateway(leaseManager)
        val host = HostEntity(
            id = 1L,
            name = "profile-discovery-test",
            hostname = DEFAULT_HOST,
            port = DEFAULT_PORT,
            username = DEFAULT_USER,
            keyId = 1L,
        )

        // --- 1 + 2: REAL host discovery (must NOT false-green) ---
        val result = withTimeout(30_000) {
            gateway.listProfiles(
                host = host,
                keyPath = keyFile.absolutePath,
                passphrase = null,
                engine = RemoteProfile.ENGINE_CLAUDE,
            )
        }
        assertTrue(
            "expected ProfilesResult.Profiles from real host discovery, got $result " +
                "(a ToolUnavailable here is the false-green this test exists to catch)",
            result is ProfilesResult.Profiles,
        )
        val discovered = (result as ProfilesResult.Profiles).profiles
        // The fixture seeds exactly the default Claude + the Z.AI sibling.
        val defaultProfile = discovered.firstOrNull { it.default }
        val zaiProfile = discovered.firstOrNull { it.name == "Claude (Z.AI)" }
        assertEquals(
            "expected the default 'Claude' profile to be discovered, got " +
                discovered.map { it.name },
            "Claude",
            defaultProfile?.name,
        )
        assertTrue(
            "expected the non-default 'Claude (Z.AI)' profile to be discovered, got " +
                discovered.map { it.name },
            zaiProfile != null,
        )
        // The non-default profile carries its absolute host config_dir; the
        // default carries none (engine built-in).
        assertTrue(
            "Z.AI profile must point at a ~/.zlaude config dir, got ${zaiProfile?.configDir}",
            zaiProfile?.configDir?.endsWith("/.zlaude") == true,
        )
        assertEquals(
            "default profile must have no config_dir override",
            null,
            defaultProfile?.configDir,
        )

        // --- 3: project onto the picker flow exactly like FolderListViewModel ---
        val claudeProfiles = discovered
            .filter { it.engine == RemoteProfile.ENGINE_CLAUDE }
            .map { ClaudeProfile(name = it.name, default = it.default) }
        assertTrue(
            "picker needs >1 claude profile to even show the toggle, got $claudeProfiles",
            claudeProfiles.size > 1,
        )

        // Select the NON-default discovered profile.
        val selected = "Claude (Z.AI)"
        val workdir = "/tmp/issue732-discovery-${System.currentTimeMillis().toString().takeLast(6)}"
        val launchCommand = AgentCli.Claude.launchCommand(
            directory = workdir,
            skipPermissions = true,
            claudeProfileName = selected,
            claudeProfiles = claudeProfiles,
        )
        // The selected non-default profile name is threaded into --profile.
        assertTrue(
            "launch command must thread --profile for the selected non-default profile, got: $launchCommand",
            launchCommand.contains("--profile '$selected'"),
        )
        assertTrue(launchCommand.startsWith("pocketshell agent claude --dir '$workdir'"))

        // --- 4: actually exec the discovered+selected launch over SSH ---
        val banner = withTimeout(30_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = sshKey,
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 10_000,
            ).getOrThrow().use { session ->
                session.exec("mkdir -p $workdir")
                // The fixture `pocketshell agent claude ... --profile '<name>'`
                // wrapper accepts the profile flag and exec's the fake claude
                // agent, which prints its ready banner — proving the selected
                // profile routed all the way through to a launched agent and
                // the wrapper did not choke on the discovered profile name.
                session.exec(launchCommand)
            }
        }
        assertEquals(
            "fixture agent launch should exit 0 (stderr=${banner.stderr})",
            0,
            banner.exitCode,
        )
        assertFalse(
            "fixture should not report an unsupported profiles/agent path, got: " +
                "${banner.stdout}\n${banner.stderr}",
            banner.stderr.contains("fixture supports"),
        )
        assertTrue(
            "expected the launched fake claude agent's ready output, got stdout=" +
                "${banner.stdout} stderr=${banner.stderr}",
            (banner.stdout + banner.stderr).contains("claude", ignoreCase = true),
        )
    }
}
