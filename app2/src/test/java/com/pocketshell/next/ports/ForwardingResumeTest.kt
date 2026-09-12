package com.pocketshell.next.ports

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ForwardingResumeTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private val dispatcher = StandardTestDispatcher(TestCoroutineScheduler())

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun enabledHost_onStart_requestsForwardServiceResume() = runTest(dispatcher) {
        seedHost(enabled = true)
        val started = AtomicInteger(0)
        val resume = resume { started.incrementAndGet() }
        val owner = FakeLifecycleOwner()
        owner.registry.currentState = Lifecycle.State.CREATED

        resume.observeProcessLifecycle(owner)
        advanceUntilIdle()
        assertEquals(0, started.get())

        owner.registry.currentState = Lifecycle.State.STARTED
        advanceUntilIdle()

        assertEquals(1, started.get())
    }

    @Test
    fun noEnabledHost_doesNotStartService() = runTest(dispatcher) {
        seedHost(enabled = false)
        val started = AtomicInteger(0)
        val resume = resume { started.incrementAndGet() }
        val owner = FakeLifecycleOwner()
        owner.registry.currentState = Lifecycle.State.STARTED

        resume.observeProcessLifecycle(owner)
        advanceUntilIdle()

        assertEquals(0, started.get())
    }

    @Test
    fun secondObserveProcessLifecycle_isANoOp() = runTest(dispatcher) {
        seedHost(enabled = true)
        val started = AtomicInteger(0)
        val resume = resume { started.incrementAndGet() }
        val owner = FakeLifecycleOwner()
        owner.registry.currentState = Lifecycle.State.CREATED

        resume.observeProcessLifecycle(owner)
        resume.observeProcessLifecycle(owner)
        advanceUntilIdle()
        assertEquals(0, started.get())

        owner.registry.currentState = Lifecycle.State.STARTED
        advanceUntilIdle()

        assertEquals(1, started.get())
    }

    @Test
    fun secondObserveWhileAlreadyStarted_requestsResumeAgain() = runTest(dispatcher) {
        seedHost(enabled = true)
        val started = AtomicInteger(0)
        val resume = resume { started.incrementAndGet() }
        val owner = FakeLifecycleOwner()
        owner.registry.currentState = Lifecycle.State.STARTED

        resume.observeProcessLifecycle(owner)
        advanceUntilIdle()
        val afterFirst = started.get()
        assertTrue("already-STARTED owner must seed an immediate resume", afterFirst >= 1)

        resume.observeProcessLifecycle(owner)
        advanceUntilIdle()
        assertTrue(
            "later MainActivity attach must resume even if ProcessLifecycleOwner stayed STARTED",
            started.get() > afterFirst,
        )
    }

    @Test
    fun alreadyStartedOwner_atAttach_seedsImmediateResume() = runTest(dispatcher) {
        seedHost(enabled = true)
        val started = AtomicInteger(0)
        val resume = resume { started.incrementAndGet() }
        val owner = FakeLifecycleOwner()
        owner.registry.currentState = Lifecycle.State.STARTED

        resume.observeProcessLifecycle(owner)
        advanceUntilIdle()

        assertTrue("already-STARTED owner must seed an immediate resume", started.get() >= 1)
    }

    @Test
    fun passphraseProtectedEnabledHost_isSkipped() = runTest(dispatcher) {
        seedHost(enabled = true, hasPassphrase = true)
        val started = AtomicInteger(0)
        val resume = resume { started.incrementAndGet() }
        val owner = FakeLifecycleOwner()
        owner.registry.currentState = Lifecycle.State.STARTED

        resume.observeProcessLifecycle(owner)
        advanceUntilIdle()

        assertEquals(0, started.get())
    }

    private fun resume(onStart: () -> Unit): ForwardingResume {
        val resume = ForwardingResume(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
        )
        resume.scope = CoroutineScope(SupervisorJob() + dispatcher)
        resume.startService = { onStart() }
        return resume
    }

    private fun seedHost(enabled: Boolean, hasPassphrase: Boolean = false): Long = runBlocking {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(
                name = "fixture-key",
                privateKeyPath = "/dev/null",
                hasPassphrase = hasPassphrase,
            ),
        )
        db.hostDao().insert(
            HostEntity(
                name = "fixture",
                hostname = "10.0.2.2",
                port = 2222,
                username = "testuser",
                keyId = keyId,
                enabled = enabled,
            ),
        )
    }

    private class FakeLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
