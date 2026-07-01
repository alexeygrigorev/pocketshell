package com.pocketshell.app.composer

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * Connected/Docker E2E for issue #731 (parent audit #657, data-loss path
 * #581).
 *
 * Every existing [PromptAttachmentStager] test drives a `FakeStagingSshSession`
 * (`PromptAttachmentStagerTest`), so the REAL SSH upload path — the proven
 * #581 data-loss path — has never been exercised against a live server. A
 * silent regression in `PromptAttachmentStager.uploadFile`
 * (`PromptAttachmentStager.kt`, the unknown-size branch that drains to a temp
 * file and calls `SshSession.uploadFile`) would lose the user's composer
 * attachment with no test catching it.
 *
 * This test closes that blind spot. It stages a real attachment through the
 * PRODUCTION [PromptAttachmentStager] against the deterministic Docker
 * `agents:2222` fixture and proves the bytes landed on the host by reading
 * them back over a fresh SSH `exec` (an md5 + byte-for-byte base64 read-back),
 * mirroring the capture-pane read-back proof in `SharePasteIntoSessionE2eTest`.
 *
 * The attachment is provided via [Issue731AttachmentProvider] — a test-only
 * [android.content.ContentProvider] registered in the androidTest manifest —
 * whose `query` reports NO size (`OpenableColumns.SIZE` absent). That forces
 * the stager down the unknown-size branch: `drainToTempFile` ->
 * `session.uploadFile(...)`, i.e. the exact production line under guard. The
 * provider's `openFile` streams the real local bytes, so this is an end-to-end
 * real upload over SSH.
 *
 * Wiring: this is a `*DockerTest` connected test under `app/src/androidTest`,
 * so the nightly-extensive suite's phase-1 full connected run
 * (`scripts/nightly-extensive-suite.sh`, `:app:connectedDebugAndroidTest` with
 * only `notClass` exclusions) picks it up automatically. The `agents:2222`
 * fixture it needs is the same one that workflow already starts
 * (`.github/workflows/nightly-extensive.yml` -> "Start Docker fixtures"), so
 * no new fixture is required. The per-push CI journey suite
 * (`scripts/ci-journey-suite.sh`) runs an explicit `JOURNEY_CLASSES` allowlist
 * that does NOT include this class, so it stays nightly-only as the issue asks.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentStagerRealUploadDockerTest {

    private var sshSession: SshSession? = null
    private var remoteScopeDir: String? = null
    private var cacheDir: File? = null

    @After
    fun teardown() {
        // Best-effort: remove the remote attachment dir we created so re-runs
        // against the shared fixture start clean and don't accumulate files.
        val dir = remoteScopeDir
        val key = readTestKeyOrNull()
        if (dir != null && key != null) {
            runCatching {
                runBlocking {
                    SshConnection.connect(
                        host = DEFAULT_HOST,
                        port = DEFAULT_PORT,
                        user = DEFAULT_USER,
                        key = SshKey.Pem(key),
                        knownHosts = KnownHostsPolicy.AcceptAll,
                        timeoutMs = 10_000,
                    ).getOrNull()?.use { session ->
                        session.exec("rm -rf \"\$HOME/$dir\" 2>/dev/null || true")
                    }
                }
            }
        }
        remoteScopeDir = null
        runCatching { sshSession?.close() }
        sshSession = null
        runCatching { cacheDir?.deleteRecursively() }
        cacheDir = null
    }

    @Test
    fun stagesAttachmentThroughRealUploadFileAndBytesLandOnHost() { runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // The size-less test provider is registered in the androidTest
        // manifest, so it lives in the TEST APK process and is only
        // resolvable via the instrumentation (test) context's resolver. The
        // production PromptAttachmentStager is resolver-injected, so feeding
        // it this resolver still exercises the real upload code path — only
        // the ContentResolver instance differs.
        val testContext = instrumentation.context
        val key = instrumentation.context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        waitForSshFixtureReady(SshKey.Pem(key))

        val marker = System.currentTimeMillis().toString()

        // The deterministic, non-trivial binary payload the provider serves
        // (1 KiB, every byte value). The provider lives in its OWN process/UID,
        // so we cannot hand it bytes via static state — instead both sides
        // compute the SAME bytes from the shared formula in
        // [Issue731AttachmentProvider.payloadBytes]. We assert the host received
        // THESE bytes, not a truncation or a charset-mangled copy.
        val payloadBytes = Issue731AttachmentProvider.payloadBytes()
        val displayName = "issue731-$marker.bin"
        val expectedMd5 = md5Hex(payloadBytes)

        // The provider's `query` omits SIZE (forcing the uploadFile branch) and
        // returns the display name from the URI's `name` query param.
        val authority = "${testContext.packageName}.issue731attachments"
        val attachmentUri = Uri.parse(
            "content://$authority/attachment?name=${Uri.encode(displayName)}",
        )

        val tmpCache = File.createTempFile("issue731-cache-", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        cacheDir = tmpCache

        // Stage over a REAL SSH session to the Docker fixture.
        val ssh = withTimeout(20_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).getOrThrow()
        }
        sshSession = ssh

        val scopeKey = "issue731-$marker"
        // Record the remote dir for cleanup BEFORE staging, derived the same
        // way the production stager derives it, so teardown removes exactly
        // what was created.
        val safeScope = PromptAttachmentStager.safeScopeSegment(scopeKey)
        remoteScopeDir = "${PromptAttachmentStager.REMOTE_DIRECTORY}/$safeScope"

        val stager = PromptAttachmentStager(
            resolver = testContext.contentResolver,
            cacheDir = tmpCache,
        )

        val result = stager.stage(
            session = ssh,
            scopeKey = scopeKey,
            uris = listOf(attachmentUri),
        )

        // 1) The stage must succeed and return exactly one display path.
        val displayPaths = result.getOrThrow()
        assertEquals(
            "expected exactly one staged attachment path, got $displayPaths",
            1,
            displayPaths.size,
        )

        // 2) The remote filename must derive from our display name, which only
        //    happens when describe() produced a name and the stage ran the
        //    unknown-size -> drain-to-temp -> uploadFile branch (the SIZE-less
        //    provider guarantees that branch). The byte read-back below is the
        //    decisive proof the real bytes transferred.
        assertTrue(
            "remote display path must carry the sanitised display name, was " +
                displayPaths.single(),
            displayPaths.single().endsWith(".bin"),
        )

        // 3) The temp drain file must be cleaned up (production deletes it in
        //    the finally block around session.uploadFile).
        val drainDir = File(tmpCache, "prompt-attachments")
        assertTrue(
            "drain temp dir must be empty after a successful upload, " +
                "had ${drainDir.listFiles()?.toList()}",
            drainDir.listFiles().orEmpty().isEmpty(),
        )

        // The returned display path is `~/<remoteDir>/<remoteName>`. Convert it
        // to a $HOME-relative path we can read back over SSH.
        val displayPath = displayPaths.single()
        assertTrue(
            "display path should be tilde-rooted, was $displayPath",
            displayPath.startsWith("~/"),
        )
        val remoteRelative = displayPath.removePrefix("~/")

        // 4) THE PROOF: read the bytes back over a fresh exec and assert the
        //    file exists, has the exact size, and md5-matches the payload we
        //    handed the stager. This is the real-bytes-arrived guard #581 lacked.
        val stat = ssh.exec(
            "stat -c '%s' \"\$HOME/$remoteRelative\" 2>/dev/null || echo MISSING",
        )
        val reportedSize = stat.stdout.trim()
        assertTrue(
            "remote attachment $remoteRelative must exist on the host " +
                "(stat said '$reportedSize', stderr='${stat.stderr.trim()}')",
            reportedSize != "MISSING" && reportedSize.isNotEmpty(),
        )
        assertEquals(
            "remote attachment size must equal the uploaded payload size",
            payloadBytes.size.toString(),
            reportedSize,
        )

        val md5 = ssh.exec(
            "md5sum \"\$HOME/$remoteRelative\" | awk '{print \$1}'",
        )
        assertEquals(
            "remote md5 must match the locally-staged payload md5; " +
                "the real-upload bytes must arrive byte-for-byte",
            expectedMd5,
            md5.stdout.trim(),
        )

        // Byte-for-byte read-back via base64 so we never depend on a charset.
        val b64 = ssh.exec(
            "base64 \"\$HOME/$remoteRelative\" | tr -d '\\n'",
        )
        val downloaded = android.util.Base64.decode(
            b64.stdout.trim(),
            android.util.Base64.DEFAULT,
        )
        assertArrayEquals(
            "remote bytes must be byte-for-byte identical to the staged payload",
            payloadBytes,
            downloaded,
        )

        Unit
    } }

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun readTestKeyOrNull(): String? = runCatching {
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
    }.getOrNull()
}
