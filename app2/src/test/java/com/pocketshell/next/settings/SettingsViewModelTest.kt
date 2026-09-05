package com.pocketshell.next.settings

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SettingsViewModel] forwards to a real [SettingsRepository] and projects a
 * real `hosts` table — no fakes for either collaborator, since both are cheap
 * to stand up for real (Robolectric's `SharedPreferences`, an in-memory Room
 * database) and a fake risks drifting from what the repository actually does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SettingsViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = SettingsRepository(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(): SettingsViewModel =
        SettingsViewModel(repository, db.hostDao(), UnconfinedTestDispatcher())

    @Test
    fun `state mirrors the repository's live snapshot`() = runTest {
        val vm = viewModel()

        assertEquals(AppSettings(), vm.state.value)

        vm.setTerminalTextSizePx(32)

        assertEquals(32, vm.state.value.terminalTextSizePx)
        assertEquals(32, repository.settings.value.terminalTextSizePx)
    }

    @Test
    fun `every setter forwards to the repository`() = runTest {
        val vm = viewModel()

        vm.setVoiceLanguage("de")
        vm.setVoiceSilenceThresholdSeconds(10f)
        vm.setUsageWarnThresholdPercent(90)
        vm.setBackgroundGraceMillis(AppSettings.BACKGROUND_GRACE_30_SECONDS_MS)
        vm.setAgentSubmitEnterDelayMs(300)

        val snapshot = repository.settings.value
        assertEquals("de", snapshot.voiceLanguage)
        assertEquals(10f, snapshot.voiceSilenceThresholdSeconds)
        assertEquals(90, snapshot.usageWarnThresholdPercent)
        assertEquals(AppSettings.BACKGROUND_GRACE_30_SECONDS_MS, snapshot.backgroundGraceMillis)
        assertEquals(300, snapshot.agentSubmitEnterDelayMs)
    }

    @Test
    fun `the host list is the picker for the workspace-roots section`() = runTest {
        runBlocking {
            db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k")).let { keyId ->
                db.hostDao().insert(
                    HostEntity(name = "hetzner", hostname = "10.0.0.1", username = "alexey", keyId = keyId),
                )
            }
        }

        val vm = viewModel()
        val hosts = vm.hosts.first { it.isNotEmpty() }

        assertEquals("hetzner", hosts.single().name)
        assertEquals("alexey@10.0.0.1:22", hosts.single().subtitle)
    }

    @Test
    fun `a blank host name falls back to the hostname`() = runTest {
        runBlocking {
            db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k")).let { keyId ->
                db.hostDao().insert(
                    HostEntity(name = "", hostname = "10.0.0.5", username = "root", keyId = keyId),
                )
            }
        }

        val vm = viewModel()
        val hosts = vm.hosts.first { it.isNotEmpty() }

        assertEquals("10.0.0.5", hosts.single().name)
    }
}
