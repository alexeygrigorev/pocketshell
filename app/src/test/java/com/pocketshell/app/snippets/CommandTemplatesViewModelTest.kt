package com.pocketshell.app.snippets

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CommandTemplatesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private var hostId: Long = 0L
    private var otherHostId: Long = 0L

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .allowMainThreadQueries()
            .build()

        val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "test-key", privateKeyPath = "/tmp/k"))
        hostId = db.hostDao().insert(
            HostEntity(name = "prod", hostname = "prod.example.com", username = "deploy", keyId = keyId),
        )
        otherHostId = db.hostDao().insert(
            HostEntity(name = "stage", hostname = "stage.example.com", username = "deploy", keyId = keyId),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun addTemplate_writesNormalisedCommandSequenceForBoundHost() = runTest {
        val vm = CommandTemplatesViewModel(db.commandTemplateDao())
        vm.bindHost(hostId)

        vm.addTemplate(
            label = "  Deploy api  ",
            commands = " git add . \r\ngit commit -m '{{message}}'\n\n git push ",
        )

        val row = db.commandTemplateDao().getByHostId(hostId).first().single()
        assertEquals("Deploy api", row.label)
        assertEquals("git add .\ngit commit -m '{{message}}'\ngit push", row.commands)
        assertEquals(hostId, row.hostId)
        assertNull(vm.error.value)
    }

    @Test
    fun addTemplate_rejectsMissingHostLabelOrCommands() = runTest {
        val vm = CommandTemplatesViewModel(db.commandTemplateDao())

        vm.addTemplate(label = "x", commands = "echo x")
        assertNotNull(vm.error.value)
        assertEquals(0, db.commandTemplateDao().getByHostId(hostId).first().size)

        vm.clearError()
        vm.bindHost(hostId)
        vm.addTemplate(label = " ", commands = "echo x")
        assertNotNull(vm.error.value)
        assertEquals(0, db.commandTemplateDao().getByHostId(hostId).first().size)

        vm.clearError()
        vm.addTemplate(label = "x", commands = " \n ")
        assertNotNull(vm.error.value)
        assertEquals(0, db.commandTemplateDao().getByHostId(hostId).first().size)
    }

    @Test
    fun perHostFiltering_updateAndDeleteRoundTrip() = runTest {
        val vm = CommandTemplatesViewModel(db.commandTemplateDao())

        vm.bindHost(hostId)
        vm.addTemplate(label = "prod macro", commands = "echo prod")
        vm.bindHost(otherHostId)
        vm.addTemplate(label = "stage macro", commands = "echo stage")

        val prodRow = db.commandTemplateDao().getByHostId(hostId).first().single()
        assertEquals("prod macro", prodRow.label)
        assertEquals("stage macro", db.commandTemplateDao().getByHostId(otherHostId).first().single().label)

        vm.updateTemplate(prodRow.copy(label = "  prod deploy  ", commands = " echo one \n echo two "))
        val updated = db.commandTemplateDao().getByHostId(hostId).first().single()
        assertEquals("prod deploy", updated.label)
        assertEquals("echo one\necho two", updated.commands)

        vm.deleteTemplate(updated)
        assertEquals(0, db.commandTemplateDao().getByHostId(hostId).first().size)
        assertEquals(1, db.commandTemplateDao().getByHostId(otherHostId).first().size)
    }
}
