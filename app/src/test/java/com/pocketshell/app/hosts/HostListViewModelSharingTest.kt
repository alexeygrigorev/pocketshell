package com.pocketshell.app.hosts

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.bootstrap.HostBootstrapper
import com.pocketshell.app.release.ReleaseCheckResult
import com.pocketshell.app.release.ReleaseChecker
import com.pocketshell.app.release.ReleaseInfo
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.settings.SettingsRepository
import com.pocketshell.app.usage.UsageRemoteSource
import com.pocketshell.app.usage.UsageScheduler
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class HostListViewModelSharingTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private val viewModelStore = ViewModelStore()
    private var nextViewModelKey = 0
    private val createdViewModels = mutableListOf<HostListViewModel>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                packageName = context.packageName
                versionName = "0.2.0"
                applicationInfo = ApplicationInfo().apply {
                    packageName = context.packageName
                }
            },
        )
        db = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java,
        )
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        runBlocking {
            createdViewModels.forEach { vm ->
                val job = vm.viewModelScope.coroutineContext[Job] ?: return@forEach
                withTimeoutOrNull(10_000L) { job.cancelAndJoin() }
            }
        }
        viewModelStore.clear()
        db.close()
    }

    private fun newViewModel(
        applicationContext: Context = context,
        hostDao: HostDao = db.hostDao(),
        sshKeyDao: SshKeyDao = db.sshKeyDao(),
        releaseChecker: ReleaseChecker = FakeReleaseChecker(result = null),
        bootstrapper: HostBootstrapper = HostBootstrapper(),
        usageScheduler: UsageScheduler = newUsageScheduler(),
        activeClients: ActiveTmuxClients = ActiveTmuxClients(),
        settingsRepository: SettingsRepository = newSettingsRepository(),
    ): HostListViewModel =
        HostListViewModel(
            applicationContext = applicationContext,
            hostDao = hostDao,
            sshKeyDao = sshKeyDao,
            releaseChecker = releaseChecker,
            bootstrapper = bootstrapper,
            usageScheduler = usageScheduler,
            activeClients = activeClients,
            settingsRepository = settingsRepository,
        ).also {
            viewModelStore.put("HostListViewModel-${nextViewModelKey++}", it)
            createdViewModels += it
        }

    private fun newUsageScheduler(): UsageScheduler =
        UsageScheduler(db.hostDao(), db.sshKeyDao(), UsageRemoteSource())

    private fun newSettingsRepository(): SettingsRepository = SettingsRepository(context)

    private class FakeReleaseChecker(
        private val result: ReleaseInfo?,
    ) : ReleaseChecker() {
        override suspend fun checkForUpdate(currentVersion: String): ReleaseCheckResult =
            if (result != null) {
                ReleaseCheckResult.UpdateAvailable(result)
            } else {
                ReleaseCheckResult.UpToDate
            }
    }

    @Test
    fun createSharePayload_wrapsKeyReferenceImportInQrEnvelope() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "shared-key", privateKeyPath = "/tmp/shared-key"),
        )
        val host = HostEntity(
            name = "shared",
            hostname = "shared.example.com",
            port = 2222,
            username = "ubuntu",
            keyId = keyId,
        )
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )

        viewModel.createSharePayload(host)

        val share = viewModel.sharePayload.value
        assertNotNull(share)
        // A normal-sized host fits in a single QR envelope part (AC2).
        assertEquals(1, share!!.payloads.size)
        val envelope = share.payloads.single()
        assertTrue(QrChunkCodec.isEnvelope(envelope))
        val part = QrChunkCodec.decodePart(envelope).getOrThrow()
        assertEquals(1, part.total)
        val decoded = SshImportPayloadCodec.decode(
            String(part.chunk, Charsets.UTF_8),
        ).getOrThrow()
        assertEquals("shared", decoded.name)
        assertEquals("shared.example.com", decoded.host)
        assertEquals(2222, decoded.port)
        assertEquals("ubuntu", decoded.username)
        assertEquals(SshImportAuth.KeyReference("shared-key"), decoded.auth)
    }

    /**
     * Issue #1230 regression (reproduce-first, D33/G10). The export used to do
     * `QrChunkCodec.encode(importPayload).single()`; `.single()` throws
     * `IllegalArgumentException` on a >1-element list, and the uncaught throw
     * inside the bare `viewModelScope.launch` crashed the app for ANY payload
     * that split into more than one QR chunk (a host with a long
     * name/hostname/username or a long key name).
     *
     * The fixture below has a name long enough to push the SSH-import JSON well
     * past [QrChunkCodec.ChunkSize] (1500 bytes), so `encode` returns multiple
     * parts — the exact crash trigger.
     *
     * RED (revert the fix — restore `.single()` in `createSharePayload`): the
     * coroutine throws before assigning `_sharePayload`, so `share` is null and
     * `assertNotNull` fails (and the uncaught crash surfaces via `runTest`).
     * GREEN (with the fix): every part is produced and reassembles through the
     * live scanner's [QrChunkAssembler] back into the original import payload.
     */
    @Test
    fun createSharePayload_producesAllParts_forOversizedPayload_andRoundTrips() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "shared-key", privateKeyPath = "/tmp/shared-key"),
        )
        // ~4 KiB name → SSH-import JSON far exceeds the 1500-byte chunk size, so
        // the QR envelope MUST split into multiple parts.
        val longName = "long-host-".repeat(400)
        val host = HostEntity(
            name = longName,
            hostname = "shared.example.com",
            port = 2222,
            username = "ubuntu",
            keyId = keyId,
        )
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )

        viewModel.createSharePayload(host)

        val share = viewModel.sharePayload.value
        assertNotNull("multi-chunk share must not crash (issue #1230)", share)
        // The crash trigger: the payload genuinely splits into >1 part.
        assertTrue(
            "oversized payload should produce more than one QR part",
            share!!.payloads.size > 1,
        )
        // Every part is a well-formed envelope sharing one id, parts 1..N, and
        // the whole set reassembles the way the in-app scanner combines them.
        val assembler = QrChunkAssembler()
        var assembled: String? = null
        share.payloads.forEach { envelope ->
            assertTrue(QrChunkCodec.isEnvelope(envelope))
            val part = QrChunkCodec.decodePart(envelope).getOrThrow()
            assertEquals(share.payloads.size, part.total)
            when (val outcome = assembler.accept(part)) {
                is QrChunkAssembler.Outcome.Complete -> assembled = outcome.payload
                else -> Unit
            }
        }
        assertNotNull("all parts should reassemble into the payload", assembled)
        val decoded = SshImportPayloadCodec.decode(assembled!!).getOrThrow()
        assertEquals(longName, decoded.name)
        assertEquals("shared.example.com", decoded.host)
        assertEquals(2222, decoded.port)
        assertEquals("ubuntu", decoded.username)
        assertEquals(SshImportAuth.KeyReference("shared-key"), decoded.auth)
    }

    @Test
    fun importSharedHostPayload_insertsHost_whenMatchingKeyExists() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "shared-key", privateKeyPath = "/tmp/shared-key"),
        )
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig(
                name = "shared",
                host = "shared.example.com",
                port = 2222,
                username = "ubuntu",
                auth = SshImportAuth.KeyReference("shared-key"),
            ),
        )

        viewModel.importSharedHostPayload(payload).join()

        val rows = db.hostDao().getAll().first()
        assertEquals(1, rows.size)
        assertEquals("shared", rows[0].name)
        assertEquals("shared.example.com", rows[0].hostname)
        assertEquals(2222, rows[0].port)
        assertEquals("ubuntu", rows[0].username)
        assertEquals(keyId, rows[0].keyId)
        assertEquals("Imported shared", viewModel.shareMessage.value)
    }

    @Test
    fun importSharedHostPayload_reportsMissingKey() = runTest {
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig(
                name = "shared",
                host = "shared.example.com",
                port = 22,
                username = "ubuntu",
                auth = SshImportAuth.KeyReference("missing-key"),
            ),
        )

        viewModel.importSharedHostPayload(payload).join()

        assertEquals(0, db.hostDao().getAll().first().size)
        assertEquals(
            "Import the SSH key named missing-key before importing this host",
            viewModel.shareMessage.value,
        )
    }

    @Test
    fun importSharedHostPayload_reportsExpectedPocketShellHostPayloadForUnsupportedPayload() = runTest {
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )

        viewModel.importSharedHostPayload("""{"type":"pocketshell.settings.v1","version":1}""").join()

        assertEquals(0, db.hostDao().getAll().first().size)
        val message = viewModel.shareMessage.value
        assertNotNull(message)
        assertTrue(message!!.contains("PocketShell SSH host payload"))
        assertTrue(message.contains("pocketshell.ssh-import.v1"))
    }

    @Test
    fun importSharedHostPayload_insertsHostAndKey_forSshImportPrivateKeyPayload() = runTest {
        File(context.filesDir, "ssh-keys").deleteRecursively()
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig(
                name = "qr-prod",
                host = "qr.example.com",
                port = 2200,
                username = "ubuntu",
                auth = SshImportAuth.PrivateKey(
                    name = "qr-key",
                    privateKeyPem = """
                        -----BEGIN OPENSSH PRIVATE KEY-----
                        abc
                        -----END OPENSSH PRIVATE KEY-----
                    """.trimIndent(),
                    passphraseRequired = false,
                ),
            ),
        )

        viewModel.importSharedHostPayload(payload).join()

        val keys = db.sshKeyDao().getAll().first()
        val hosts = db.hostDao().getAll().first()
        assertEquals(1, keys.size)
        assertEquals("qr-key", keys[0].name)
        assertEquals(false, keys[0].hasPassphrase)
        assertTrue(File(keys[0].privateKeyPath).exists())
        assertEquals(1, hosts.size)
        assertEquals("qr-prod", hosts[0].name)
        assertEquals("qr.example.com", hosts[0].hostname)
        assertEquals(2200, hosts[0].port)
        assertEquals("ubuntu", hosts[0].username)
        assertEquals(keys[0].id, hosts[0].keyId)
        assertEquals("Imported qr-prod", viewModel.shareMessage.value)
    }

    @Test
    fun importSharedHostPayload_reusesKey_whenSamePrivateKeyPayloadIsScannedAgain() = runTest {
        File(context.filesDir, "ssh-keys").deleteRecursively()
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig(
                name = "qr-prod",
                host = "qr.example.com",
                port = 2200,
                username = "ubuntu",
                auth = SshImportAuth.PrivateKey(
                    name = "qr-key",
                    privateKeyPem = """
                        -----BEGIN OPENSSH PRIVATE KEY-----
                        abc
                        -----END OPENSSH PRIVATE KEY-----
                    """.trimIndent(),
                    passphraseRequired = false,
                ),
            ),
        )

        viewModel.importSharedHostPayload(payload).join()
        viewModel.importSharedHostPayload(payload).join()

        val keys = db.sshKeyDao().getAll().first()
        val hosts = db.hostDao().getAll().first()
        val keyFiles = File(context.filesDir, "ssh-keys").listFiles().orEmpty()
        assertEquals(1, keys.size)
        assertEquals(1, hosts.size)
        assertEquals(keys[0].id, hosts[0].keyId)
        assertEquals(1, keyFiles.size)
        assertNotNull(viewModel.importConflict.value)
    }

    @Test
    fun importSharedHostPayload_derivesPassphraseRequirementFromEncryptedOpenSshKey() = runTest {
        File(context.filesDir, "ssh-keys").deleteRecursively()
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig(
                name = "encrypted-prod",
                host = "encrypted.example.com",
                port = 22,
                username = "ubuntu",
                auth = SshImportAuth.PrivateKey(
                    name = "encrypted-key",
                    privateKeyPem = EncryptedOpenSshPrivateKey,
                    passphraseRequired = false,
                ),
            ),
        )

        viewModel.importSharedHostPayload(payload).join()

        val keys = db.sshKeyDao().getAll().first()
        assertEquals(1, keys.size)
        assertEquals("encrypted-key", keys[0].name)
        assertEquals(true, keys[0].hasPassphrase)
        assertEquals("Imported encrypted-prod", viewModel.shareMessage.value)
    }

    @Test
    fun importSharedHostPayload_usesExistingKey_forSshImportKeyReferencePayload() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "existing-key", privateKeyPath = "/tmp/existing-key"),
        )
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig(
                name = "qr-ref",
                host = "ref.example.com",
                port = 22,
                username = "alexey",
                auth = SshImportAuth.KeyReference("existing-key"),
            ),
        )

        viewModel.importSharedHostPayload(payload).join()

        val hosts = db.hostDao().getAll().first()
        assertEquals(1, hosts.size)
        assertEquals("qr-ref", hosts[0].name)
        assertEquals(keyId, hosts[0].keyId)
        assertEquals(1, db.sshKeyDao().getAll().first().size)
    }

    @Test
    fun importSharedHostPayload_acceptsSinglePartEnvelopeWrappingSshImportPayload() = runTest {
        // Issue #129: a single-part envelope wraps the existing
        // ssh-import JSON. The view model should unwrap and import
        // the host the same as if the JSON had arrived raw.
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "existing-key", privateKeyPath = "/tmp/existing-key"),
        )
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        val inner = SshImportPayloadCodec.encode(
            SshImportConfig(
                name = "envelope-ref",
                host = "env.example.com",
                port = 22,
                username = "alexey",
                auth = SshImportAuth.KeyReference("existing-key"),
            ),
        )
        val envelopes = QrChunkCodec.encode(inner, id = "deadbeef")
        assertEquals(1, envelopes.size)

        viewModel.importSharedHostPayload(envelopes[0]).join()

        val hosts = db.hostDao().getAll().first()
        assertEquals(1, hosts.size)
        assertEquals("envelope-ref", hosts[0].name)
        assertEquals(keyId, hosts[0].keyId)
    }

    @Test
    fun importSharedHostPayload_rejectsMultiPartEnvelope_withGuidance() = runTest {
        // Issue #129: a single envelope from a multi-part transmission
        // is not a complete payload. The view model surfaces guidance
        // pointing the user to the QR scanner rather than silently
        // failing.
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        val payload = "X".repeat(QrChunkCodec.ChunkSize * 2 + 1)
        val envelopes = QrChunkCodec.encode(payload, id = "deadbeef")
        assertTrue(envelopes.size > 1)

        viewModel.importSharedHostPayload(envelopes[0]).join()

        assertEquals(0, db.hostDao().getAll().first().size)
        val message = viewModel.shareMessage.value
        assertNotNull(message)
        assertTrue(
            "expected guidance message to mention the scanner, got: $message",
            message!!.contains("scanner", ignoreCase = true),
        )
    }

    // -- Issue #157 polish item 2: import-conflict prompt -------------------

    /**
     * Re-importing a host whose `(hostname, port)` already exists must
     * surface a conflict dialog instead of silently writing. The
     * ViewModel pauses the write until the user resolves with
     * [ImportConflictResolution].
     */
    @Test
    fun importSharedHostPayload_pausesAndExposesConflict_whenEndpointAlreadyExists() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "shared-key", privateKeyPath = "/tmp/shared-key"),
        )
        db.hostDao().insert(
            HostEntity(
                name = "existing",
                hostname = "shared.example.com",
                port = 2222,
                username = "ubuntu",
                keyId = keyId,
            ),
        )
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig(
                name = "shared",
                host = "shared.example.com",
                port = 2222,
                username = "ubuntu",
                auth = SshImportAuth.KeyReference("shared-key"),
            ),
        )

        viewModel.importSharedHostPayload(payload).join()

        // The pre-existing row is still the only one.
        val rowsAfter = db.hostDao().getAll().first()
        assertEquals(1, rowsAfter.size)
        assertEquals("existing", rowsAfter[0].name)
        // The conflict is exposed for the dialog to render.
        val conflict = viewModel.importConflict.value
        assertNotNull(conflict)
        assertEquals("existing", conflict!!.existing.name)
        assertEquals("shared", conflict.incoming.name)
        assertEquals("shared.example.com", conflict.incoming.hostname)
        assertEquals(2222, conflict.incoming.port)
    }

    @Test
    fun resolveImportConflict_overwrite_updatesExistingRowInPlace() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "shared-key", privateKeyPath = "/tmp/shared-key"),
        )
        val existingId = db.hostDao().insert(
            HostEntity(
                name = "existing",
                hostname = "shared.example.com",
                port = 2222,
                username = "old-user",
                keyId = keyId,
                tmuxInstalled = true,
                pocketshellInstalled = true,
                lastBootstrapAt = 1234L,
                pocketshellLastDetectedAt = 1234L,
            ),
        )
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig(
                name = "shared-new-label",
                host = "shared.example.com",
                port = 2222,
                username = "new-user",
                auth = SshImportAuth.KeyReference("shared-key"),
            ),
        )
        viewModel.importSharedHostPayload(payload).join()

        viewModel.resolveImportConflict(ImportConflictResolution.Overwrite).join()

        val rows = db.hostDao().getAll().first()
        assertEquals(1, rows.size)
        assertEquals(existingId, rows[0].id)
        assertEquals("shared-new-label", rows[0].name)
        assertEquals("new-user", rows[0].username)
        // Bootstrap cache is invalidated so the next connect re-probes.
        assertNull(rows[0].tmuxInstalled)
        assertNull(rows[0].pocketshellInstalled)
        assertNull(rows[0].lastBootstrapAt)
        // Conflict state is cleared.
        assertNull(viewModel.importConflict.value)
        assertEquals("Overwrote existing", viewModel.shareMessage.value)
    }

    @Test
    fun resolveImportConflict_skip_leavesDatabaseUntouched() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "shared-key", privateKeyPath = "/tmp/shared-key"),
        )
        db.hostDao().insert(
            HostEntity(
                name = "existing",
                hostname = "shared.example.com",
                port = 2222,
                username = "ubuntu",
                keyId = keyId,
            ),
        )
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig(
                name = "incoming",
                host = "shared.example.com",
                port = 2222,
                username = "ubuntu",
                auth = SshImportAuth.KeyReference("shared-key"),
            ),
        )
        viewModel.importSharedHostPayload(payload).join()

        viewModel.resolveImportConflict(ImportConflictResolution.Skip).join()

        val rows = db.hostDao().getAll().first()
        assertEquals(1, rows.size)
        assertEquals("existing", rows[0].name)
        assertNull(viewModel.importConflict.value)
        assertEquals("Already added: existing", viewModel.shareMessage.value)
    }

    @Test
    fun resolveImportConflict_addAsNew_appendsSecondRow() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "shared-key", privateKeyPath = "/tmp/shared-key"),
        )
        db.hostDao().insert(
            HostEntity(
                name = "existing",
                hostname = "shared.example.com",
                port = 2222,
                username = "ubuntu",
                keyId = keyId,
            ),
        )
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig(
                name = "incoming",
                host = "shared.example.com",
                port = 2222,
                username = "ubuntu",
                auth = SshImportAuth.KeyReference("shared-key"),
            ),
        )
        viewModel.importSharedHostPayload(payload).join()

        viewModel.resolveImportConflict(ImportConflictResolution.AddAsNew).join()

        val rows = db.hostDao().getAll().first()
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.name == "existing" })
        assertTrue(rows.any { it.name == "incoming" })
        assertNull(viewModel.importConflict.value)
        assertEquals("Imported incoming", viewModel.shareMessage.value)
    }

    @Test
    fun importSharedHostPayload_skipsConflictCheck_whenEndpointIsUnique() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "shared-key", privateKeyPath = "/tmp/shared-key"),
        )
        db.hostDao().insert(
            HostEntity(
                name = "existing",
                hostname = "first.example.com",
                port = 22,
                username = "ubuntu",
                keyId = keyId,
            ),
        )
        val viewModel = newViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = FakeReleaseChecker(result = null),
            bootstrapper = HostBootstrapper(),
            usageScheduler = newUsageScheduler(),
            activeClients = ActiveTmuxClients(),
            settingsRepository = newSettingsRepository(),
        )
        val payload = SshImportPayloadCodec.encode(
            SshImportConfig(
                name = "fresh",
                host = "fresh.example.com",
                port = 22,
                username = "ubuntu",
                auth = SshImportAuth.KeyReference("shared-key"),
            ),
        )
        viewModel.importSharedHostPayload(payload).join()

        // No conflict — direct insert, two rows.
        assertNull(viewModel.importConflict.value)
        val rows = db.hostDao().getAll().first()
        assertEquals(2, rows.size)
        assertEquals("Imported fresh", viewModel.shareMessage.value)
    }

    private companion object {
        val EncryptedOpenSshPrivateKey = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jdHIAAAAGYmNyeXB0AAAAGAAAABDy65Wy4J
            GIiPmAlfzxEptmAAAAGAAAAAEAAAAzAAAAC3NzaC1lZDI1NTE5AAAAIFz+4rPsOPrrK7I/
            hz3T8H4UpgIdLal/ADv4OhvewZ+xAAAAkPodwX8olqflsAful+M/4T4BtLAaULs9Oc3GVb
            uy664Ebtmwo+/HhJmloTIoVs0STzeFeHAK4xkEq6Ut303sIER2av1O0qHUjyOMGPPZop1V
            4Sd/bBQJu/Q14nSsiSRJaHBN2SBpyFSul2/ZLU5xYhs7dzmCTbXH+BM1cP6ZE5byxA8jeW
            gH19i7Bcv1CK6+Pg==
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent()
    }
}
