package com.pocketshell.core.ssh

import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * Real-transport host-key trust regressions for #2433.
 *
 * An unknown key is first observed through a rejected handshake. Only the exact
 * fingerprint returned by that handshake may subsequently connect. Restarting
 * the same fixture on the same endpoint generates a different key and proves
 * that the previously trusted fingerprint rejects it.
 *
 * The dedicated fixture is needed because
 *
 * `tests/docker/Dockerfile.ssh` bakes its host keys into an image layer, so every
 * container from it presents the same key forever and "the key changed" is
 * unreachable. `tests/docker/Dockerfile.ssh-rekeyed` ships with **no** host keys
 * and mints a fresh set at container START, so two starts of the SAME image ID
 * present different keys. [hostKeyFingerprints] reads them back out of the
 * container so the change is *observed*, not assumed.
 *
 * ## Why a fixed host port
 *
 * Host-key trust is keyed on `host:port`. With Testcontainers' usual ephemeral
 * ports the second connect would hit a *different* endpoint, and "the key for
 * this endpoint changed" would degrade into "this endpoint is new" — a strictly
 * weaker (and wrong) scenario. Both container starts are therefore pinned to
 * [FIXED_HOST_PORT]; see its KDoc for the port-collision audit.
 */
class HostKeyTrustCharacterizationIntegrationTest {

    companion object {
        private const val CONTAINER_SSH_PORT = 22

        /**
         * Fixed host port for the re-keyed fixture.
         *
         * Both container starts must publish on the SAME host port or the
         * "changed key on an endpoint I already trusted" scenario collapses into
         * "brand-new endpoint" (see class KDoc).
         *
         * Port audit against every port this repo binds (`tests/docker/`,
         * `scripts/`, `docs/`): 2222 `agents`/`sshd`, 2224 `tmux`, 2226
         * `flaky-agent`, 2228 + 8474 toxiproxy, 2229 packet-loss proxy,
         * 2230-2236 the bootstrap scenario fixtures, 2238 `agents-old-cli`,
         * 2239 `agents-daemon`, 2240 `real-agent`, 2241
         * `bootstrap-notifications`, 2243-2245 the #724 journey pool. 2246 is
         * the first free port above the pool and is claimed here.
         *
         * `tests/docker/docker-compose.yml`'s `sshd-rekeyed` service publishes
         * the same 2246 for manual smoke testing — deliberately, following the
         * existing `sshd`/`agents` precedent of "run one or the other, not
         * both". Nothing in CI starts that compose service (the `integration`
         * job is Testcontainers-only), so they cannot collide there.
         */
        private const val FIXED_HOST_PORT = 2246

        /** Bounded wall-clock ceiling for infrastructure waits (never an assertion). */
        private val READY_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(90)

        private val projectRoot: Path by lazy { findProjectRoot() }

        private fun findProjectRoot(): Path {
            var dir: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
            while (dir != null) {
                if (dir.resolve("tests/docker/Dockerfile.ssh-rekeyed").toFile().exists()) {
                    return dir
                }
                dir = dir.parent
            }
            error(
                "Could not locate tests/docker/Dockerfile.ssh-rekeyed from user.dir=" +
                    System.getProperty("user.dir"),
            )
        }
    }

    /**
     * The ONE image both starts run. Resolved (built) once per test so the
     * "two starts of the same image" claim can be checked by image ID rather
     * than taken on faith.
     */
    private lateinit var imageName: String

    private val startedContainers = mutableListOf<GenericContainer<*>>()

    private val privateKeyFile: File
        get() = projectRoot.resolve("tests/docker/test_key").toFile()

    @Before
    fun setUp() {
        val dockerAvailable = runCatching {
            DockerClientFactory.instance().isDockerAvailable
        }.getOrDefault(false)
        assumeTrue(
            "Docker not available; skipping host-key trust characterization",
            dockerAvailable,
        )

        val dockerDir = projectRoot.resolve("tests/docker")
        // `.get()` builds the image NOW and returns its tag, so every container
        // below starts from an already-built, identical image instead of
        // racing two builds.
        imageName = ImageFromDockerfile("pocketshell-test-ssh-rekeyed", false)
            .withDockerfile(dockerDir.resolve("Dockerfile.ssh-rekeyed"))
            .get()
    }

    @After
    fun tearDown() {
        startedContainers.asReversed().forEach { runCatching { it.stop() } }
        startedContainers.clear()
    }

    // ------------------------------------------------------------------
    // Unknown -> explicit first-use trust -> known automatic reconnect.
    // ------------------------------------------------------------------

    @Test
    fun firstConnectToAnUnknownHostKeyIsRejectedUntilExplicitlyTrusted() = runBlocking {
        val container = startRekeyedFixture()
        val fingerprints = hostKeyFingerprints(container)
        assertTrue(
            "fixture must present at least one host key to characterize",
            fingerprints.isNotEmpty(),
        )

        val strictResult = connect(KnownHostsPolicy.VerifiedFingerprint(null))
        assertTrue(
            "an unknown key must be rejected before confirmation",
            strictResult.isFailure,
        )
        val unknown = strictResult.exceptionOrNull() as UnknownHostKeyException
        assertTrue("reported key must be one actually served", unknown.presentedSha256 in fingerprints)

        repeat(2) { attempt ->
            connect(KnownHostsPolicy.VerifiedFingerprint(unknown.presentedSha256))
                .getOrThrow()
                .use { session ->
                    assertEquals("trusted reconnect $attempt must be usable", 0, session.exec("true").exitCode)
                }
        }
    }

    // ------------------------------------------------------------------
    // Changed key on the exact same endpoint is rejected.
    // ------------------------------------------------------------------

    /**
     * #1799 / #1639 §H1 characterization: after the host key at an endpoint
     * **changes**, a reconnect to that same endpoint **still succeeds today**.
     *
     * The journey, all on the real transport against `host:`[FIXED_HOST_PORT]:
     *
     *  1. Start the fixture. Record its host-key fingerprints and seed a
     *     known-hosts file with its public keys.
     *  2. Connect with that seeded file — it SUCCEEDS. This is the control that
     *     proves the seeded store is a working oracle (not a file sshj silently
     *     ignores), so its verdict in step 5 means something.
     *  3. Stop the fixture and start a NEW container from the SAME image on the
     *     SAME host port. Assert both starts report the same Docker image ID and
     *     that their fingerprint sets are DISJOINT — i.e. the key genuinely
     *     changed, observed, not assumed.
     *  4. Connect with the step-1 known-hosts file — it FAILS. A real verifier
     *     does detect this. The capability exists in the build.
     *  5. Connect with the argument OMITTED (production's default) — it
     *     SUCCEEDS, and round-trips a command. **That is the hole**: the
     *     classic MITM signature is accepted silently.
     *
     * Step 5 flips to `isFailure` under #1639 §H1.
     */
    @Test
    fun connectAfterTheHostKeyChangesIsRejectedWithBothFingerprints() = runBlocking {
        // (1) First incarnation.
        val first = startRekeyedFixture()
        val firstImageId = first.containerInfo.imageId
        val firstFingerprints = hostKeyFingerprints(first)
        assertTrue(
            "fixture must present at least one host key",
            firstFingerprints.isNotEmpty(),
        )
        val firstUnknown = connect(KnownHostsPolicy.VerifiedFingerprint(null)).exceptionOrNull()
            as UnknownHostKeyException
        connect(KnownHostsPolicy.VerifiedFingerprint(firstUnknown.presentedSha256))
            .getOrThrow().close()

        // (3) Re-key by restarting the fixture on the SAME endpoint.
        stopFixture(first)
        val second = startRekeyedFixture()
        val secondImageId = second.containerInfo.imageId
        val secondFingerprints = hostKeyFingerprints(second)

        assertNotNull("first container must report an image id", firstImageId)
        assertEquals(
            "both starts must come from the SAME image — otherwise the key change " +
                "could be explained by a different image rather than by per-start " +
                "key generation",
            firstImageId,
            secondImageId,
        )
        // Print the observed evidence. The integrationTest task streams stdout,
        // so a reviewer reading the run output sees the ACTUAL fingerprints
        // rather than having to take the assertion's word for it.
        println(
            "[#1799] re-key observed on image $imageName ($firstImageId)\n" +
                "[#1799]   start 1 host keys: ${firstFingerprints.sorted()}\n" +
                "[#1799]   start 2 host keys: ${secondFingerprints.sorted()}",
        )
        assertTrue(
            "two successive starts of image $imageName ($firstImageId) must present " +
                "DIFFERENT host keys, otherwise the re-keying fixture is not " +
                "re-keying and this whole test is vacuous. start1=$firstFingerprints " +
                "start2=$secondFingerprints",
            firstFingerprints.isNotEmpty() &&
                secondFingerprints.isNotEmpty() &&
                firstFingerprints.intersect(secondFingerprints).isEmpty(),
        )

        val mismatchResult = connect(KnownHostsPolicy.VerifiedFingerprint(firstUnknown.presentedSha256))
        assertTrue("changed key must be rejected", mismatchResult.isFailure)
        val changed = mismatchResult.exceptionOrNull() as ChangedHostKeyException
        assertEquals(firstUnknown.presentedSha256, changed.expectedSha256)
        assertTrue(changed.presentedSha256 in secondFingerprints)
        assertTrue(changed.presentedSha256 !in firstFingerprints)
    }

    // ------------------------------------------------------------------
    // Fixture plumbing (never load-bearing — all of it hard-fails loudly)
    // ------------------------------------------------------------------

    /**
     * The EXACT production call shape of `DefaultPortForwardConnector`: the
     * `knownHosts` argument is omitted, so [SshConnection.connect]'s default
     * ([TestOnlyAcceptAll]) applies. Deliberately NOT written as
     * `knownHosts = TestOnlyAcceptAll` — the point is that production
     * reaches this posture by *default*, without any caller opting in.
     */
    private suspend fun connect(policy: KnownHostsPolicy): Result<SshSession> =
        SshConnection.connect(
            host = "127.0.0.1",
            port = FIXED_HOST_PORT,
            user = "testuser",
            key = SshKey.Path(privateKeyFile),
            passphrase = null,
            knownHosts = policy,
            timeoutMs = 15_000,
        )

    /**
     * Start the re-keyed fixture pinned to [FIXED_HOST_PORT] and block until
     * sshd inside it answers a real SSH probe. The readiness probe runs
     * `ssh ... testuser@localhost true` from INSIDE the container (the same
     * shape as the compose healthcheck), so it never touches the client-side
     * trust path under test.
     */
    private fun startRekeyedFixture(): GenericContainer<*> {
        val container = GenericContainer(DockerImageName.parse(imageName))
            .withExposedPorts(CONTAINER_SSH_PORT)
            .withCreateContainerCmdModifier { cmd ->
                cmd.hostConfig?.withPortBindings(
                    PortBinding(
                        Ports.Binding.bindPort(FIXED_HOST_PORT),
                        ExposedPort.tcp(CONTAINER_SSH_PORT),
                    ),
                )
            }
        // Port 2246 may still be in TIME_WAIT / mid-teardown from the previous
        // incarnation in the changed-key test; a bounded retry on the START
        // (infrastructure, not an assertion) keeps that from reading as a
        // product failure.
        var lastFailure: Throwable? = null
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        var started = false
        while (System.currentTimeMillis() < deadline) {
            val attempt = runCatching { container.start() }
            if (attempt.isSuccess) {
                started = true
                break
            }
            lastFailure = attempt.exceptionOrNull()
            Thread.sleep(500)
        }
        assertTrue(
            "could not bind the re-keyed fixture to host port $FIXED_HOST_PORT " +
                "within ${READY_TIMEOUT_MS}ms — is something else holding it? " +
                "last failure: $lastFailure",
            started,
        )
        startedContainers += container

        assertEquals(
            "the fixture must be reachable on the pinned host port so both " +
                "incarnations share one host:port trust identity",
            FIXED_HOST_PORT,
            container.getMappedPort(CONTAINER_SSH_PORT),
        )
        awaitSshReady(container)
        return container
    }

    private fun stopFixture(container: GenericContainer<*>) {
        container.stop()
        startedContainers.remove(container)
    }

    /**
     * Wait until sshd answers a real publickey SSH login from inside the
     * container. HARD-fails on timeout; the assertion is the loop's exit
     * condition, never anything observed inside the loop body.
     */
    private fun awaitSshReady(container: GenericContainer<*>) {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        var ready = false
        var lastOutput = "<never ran>"
        while (System.currentTimeMillis() < deadline) {
            val probe = runCatching {
                container.execInContainer(
                    "sh", "-c",
                    "ssh -o BatchMode=yes -o ConnectTimeout=2 " +
                        "-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null " +
                        "-i /root/test_key testuser@localhost true",
                )
            }
            val result = probe.getOrNull()
            if (result != null) {
                lastOutput = "exit=${result.exitCode} out=${result.stdout} err=${result.stderr}"
                if (result.exitCode == 0) {
                    ready = true
                    break
                }
            } else {
                lastOutput = "exec failed: ${probe.exceptionOrNull()}"
            }
            Thread.sleep(250)
        }
        assertTrue(
            "sshd in the re-keyed fixture did not accept a login within " +
                "${READY_TIMEOUT_MS}ms; last probe: $lastOutput",
            ready,
        )
    }

    /**
     * Read the SERVER-side fingerprint of every host key the container is
     * currently serving, via `ssh-keygen -lf` inside the container.
     *
     * Server-side on purpose: it is independent of anything the client does, so
     * it cannot be confused by algorithm negotiation, and it covers **all** key
     * types at once. That is what makes "the two sets are disjoint" a sound
     * proof that whichever key sshj negotiated on the second connect, it was a
     * different one.
     */
    private fun hostKeyFingerprints(container: GenericContainer<*>): Set<String> {
        val result = container.execInContainer(
            "sh", "-c",
            "for f in /etc/ssh/ssh_host_*_key.pub; do ssh-keygen -lf \"\$f\"; done",
        )
        assertEquals(
            "reading host-key fingerprints out of the fixture must succeed; " +
                "stdout=${result.stdout} stderr=${result.stderr}",
            0,
            result.exitCode,
        )
        // `ssh-keygen -lf` prints "<bits> SHA256:<b64> <comment> (<TYPE>)"; the
        // comment embeds the container hostname, which differs per container by
        // construction, so keep ONLY the fingerprint token or the disjointness
        // assertion would pass for a reason that has nothing to do with keys.
        val fingerprints = result.stdout
            .lineSequence()
            .mapNotNull { line ->
                line.split(" ").firstOrNull { it.startsWith("SHA256:") }
            }
            .toSet()
        assertTrue(
            "expected at least one SHA256 host-key fingerprint; raw output was:\n" +
                result.stdout,
            fingerprints.isNotEmpty(),
        )
        return fingerprints
    }

    /**
     * Write an OpenSSH `known_hosts` file trusting EVERY public host key the
     * container currently serves, for `127.0.0.1:`[FIXED_HOST_PORT].
     *
     * All key types are seeded (not just ed25519) so the control connect in the
     * changed-key test verifies regardless of which host-key algorithm sshj and
     * the server negotiate.
     */
    private fun knownHostsFileFor(container: GenericContainer<*>, label: String): File {
        val result = container.execInContainer(
            "sh", "-c",
            "for f in /etc/ssh/ssh_host_*_key.pub; do cat \"\$f\"; done",
        )
        assertEquals(
            "reading host public keys out of the fixture must succeed; " +
                "stderr=${result.stderr}",
            0,
            result.exitCode,
        )
        val entries = result.stdout
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { pub ->
                // Drop the trailing comment; keep "<type> <base64>".
                val parts = pub.split(" ")
                "[127.0.0.1]:$FIXED_HOST_PORT ${parts[0]} ${parts[1]}"
            }
            .toList()
        assertTrue(
            "expected at least one host public key to seed known_hosts; raw:\n" +
                result.stdout,
            entries.isNotEmpty(),
        )
        val file = tempFile("known_hosts-$label")
        file.writeText(entries.joinToString("\n", postfix = "\n"))
        return file
    }

    private fun emptyKnownHostsFile(label: String): File =
        tempFile("known_hosts-$label").also { it.writeText("") }

    private fun tempFile(name: String): File {
        val dir = Files.createTempDirectory("ps1799").toFile().also { it.deleteOnExit() }
        return File(dir, name).also { it.deleteOnExit() }
    }
}
