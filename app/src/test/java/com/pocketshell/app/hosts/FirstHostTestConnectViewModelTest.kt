package com.pocketshell.app.hosts

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.core.ssh.ChangedHostKeyException
import com.pocketshell.core.ssh.UnknownHostKeyException
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FirstHostTestConnectViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun start_reportsSuccess_whenTesterConnects() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "lab", privateKeyPath = "/tmp/lab"))
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "Lab",
                hostname = "127.0.0.1",
                port = 2222,
                username = "test",
                keyId = keyId,
            ),
        )
        val tester = RecordingTester(Result.success(Unit))
        val vm = FirstHostTestConnectViewModel(db.hostDao(), db.sshKeyDao(), tester)

        vm.start(hostId)

        val state = vm.state.value
        assertEquals(FirstHostTestStatus.Success, state.status)
        assertEquals("Lab", state.host?.name)
        assertEquals("lab", state.key?.name)
        assertEquals(1, tester.calls)
    }

    @Test
    fun unknownHostKey_requiresExplicitConfirmation_thenPersistsAndReconnects() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "lab", privateKeyPath = "/tmp/lab"))
        val hostId = db.hostDao().insert(
            HostEntity(name = "Lab", hostname = "127.0.0.1", port = 2222, username = "test", keyId = keyId),
        )
        val unknown = UnknownHostKeyException("127.0.0.1", 2222, "ssh-ed25519", "SHA256:first")
        val tester = QueueTester(listOf(Result.failure(unknown), Result.success(Unit)))
        val vm = FirstHostTestConnectViewModel(db.hostDao(), db.sshKeyDao(), tester)

        vm.start(hostId)
        assertEquals(unknown, (vm.state.value.status as FirstHostTestStatus.ConfirmHostKey).failure)

        vm.confirmPresentedHostKey()
        advanceUntilIdle()

        assertEquals(FirstHostTestStatus.Success, vm.state.value.status)
        val persisted = requireNotNull(db.hostDao().getById(hostId))
        assertEquals("ssh-ed25519", persisted.trustedHostKeyAlgorithm)
        assertEquals("SHA256:first", persisted.trustedHostKeySha256)
        assertEquals("SHA256:first", tester.seenHosts.last().trustedHostKeySha256)
        assertEquals(2, tester.calls)
    }

    @Test
    fun knownHostKeyReconnectsWithoutConfirmation() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "lab", privateKeyPath = "/tmp/lab"))
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "Lab",
                hostname = "known.example",
                port = 22,
                username = "test",
                keyId = keyId,
                trustedHostKeyAlgorithm = "ssh-ed25519",
                trustedHostKeySha256 = "SHA256:known",
            ),
        )
        val tester = RecordingTester(Result.success(Unit))
        val vm = FirstHostTestConnectViewModel(db.hostDao(), db.sshKeyDao(), tester)

        vm.start(hostId)

        assertEquals(FirstHostTestStatus.Success, vm.state.value.status)
        assertEquals("SHA256:known", tester.lastHost?.trustedHostKeySha256)
        assertEquals(1, tester.calls)
    }

    @Test
    fun changedHostKeyIsRejectedUntilReplacementIsExplicitlyConfirmed() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "lab", privateKeyPath = "/tmp/lab"))
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "Lab",
                hostname = "changed.example",
                port = 22,
                username = "test",
                keyId = keyId,
                trustedHostKeyAlgorithm = "ssh-ed25519",
                trustedHostKeySha256 = "SHA256:old",
            ),
        )
        val changed = ChangedHostKeyException(
            "changed.example", 22, "ssh-ed25519", "SHA256:old", "SHA256:new",
        )
        val tester = QueueTester(listOf(Result.failure(changed), Result.success(Unit)))
        val vm = FirstHostTestConnectViewModel(db.hostDao(), db.sshKeyDao(), tester)

        vm.start(hostId)
        val confirmation = vm.state.value.status as FirstHostTestStatus.ConfirmHostKey
        assertEquals(changed, confirmation.failure)
        assertEquals("SHA256:old", db.hostDao().getById(hostId)?.trustedHostKeySha256)

        vm.confirmPresentedHostKey()
        advanceUntilIdle()

        assertEquals(FirstHostTestStatus.Success, vm.state.value.status)
        assertEquals("SHA256:new", db.hostDao().getById(hostId)?.trustedHostKeySha256)
    }

    @Test
    fun start_reportsActionableFailure_whenTesterFails() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "lab", privateKeyPath = "/tmp/lab"))
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "Lab",
                hostname = "127.0.0.1",
                port = 1,
                username = "test",
                keyId = keyId,
            ),
        )
        val tester = RecordingTester(Result.failure(IllegalStateException("Connection refused")))
        val vm = FirstHostTestConnectViewModel(db.hostDao(), db.sshKeyDao(), tester)

        vm.start(hostId)

        val failed = vm.state.value.status as FirstHostTestStatus.Failed
        assertTrue(failed.message.contains("Check the hostname, port, username, and SSH key"))
        assertTrue(failed.message.contains("Connection refused"))
    }

    @Test
    fun start_retestsSameHost_afterPriorFailure() = runTest {
        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "lab", privateKeyPath = "/tmp/lab"))
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "Lab",
                hostname = "127.0.0.1",
                port = 1,
                username = "test",
                keyId = keyId,
            ),
        )
        val tester = QueueTester(
            listOf(
                Result.failure(IllegalStateException("Connection refused")),
                Result.success(Unit),
            ),
        )
        val vm = FirstHostTestConnectViewModel(db.hostDao(), db.sshKeyDao(), tester)

        vm.start(hostId)
        assertTrue(vm.state.value.status is FirstHostTestStatus.Failed)

        vm.start(hostId)

        assertEquals(FirstHostTestStatus.Success, vm.state.value.status)
        assertEquals(2, tester.calls)
    }

    @Test
    fun start_reportsNeedsPassphrase_withoutCallingTester_whenKeyIsEncrypted() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "lab", privateKeyPath = "/tmp/lab", hasPassphrase = true),
        )
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "Lab",
                hostname = "127.0.0.1",
                port = 2222,
                username = "test",
                keyId = keyId,
            ),
        )
        val tester = RecordingTester(Result.success(Unit))
        val vm = FirstHostTestConnectViewModel(db.hostDao(), db.sshKeyDao(), tester)

        vm.start(hostId)

        assertEquals(FirstHostTestStatus.NeedsPassphrase, vm.state.value.status)
        assertEquals("Lab", vm.state.value.host?.name)
        assertEquals("lab", vm.state.value.key?.name)
        assertEquals(0, tester.calls)
    }

    @Test
    fun start_reportsMissingHost_withoutCallingTester() = runTest {
        val tester = RecordingTester(Result.success(Unit))
        val vm = FirstHostTestConnectViewModel(db.hostDao(), db.sshKeyDao(), tester)

        vm.start(999L)

        val failed = vm.state.value.status as FirstHostTestStatus.Failed
        assertEquals("Host was not saved. Go back and add it again.", failed.message)
        assertEquals(0, tester.calls)
    }

    private class RecordingTester(
        private val result: Result<Unit>,
    ) : FirstHostConnectionTester() {
        var calls: Int = 0
            private set
        var lastHost: HostEntity? = null
            private set

        override suspend fun test(host: HostEntity, key: SshKeyEntity): Result<Unit> {
            calls++
            lastHost = host
            return result
        }
    }

    private class QueueTester(
        results: List<Result<Unit>>,
    ) : FirstHostConnectionTester() {
        private val remaining = ArrayDeque(results)
        var calls: Int = 0
            private set
        val seenHosts = mutableListOf<HostEntity>()

        override suspend fun test(host: HostEntity, key: SshKeyEntity): Result<Unit> {
            calls++
            seenHosts += host
            return remaining.removeFirst()
        }
    }
}
