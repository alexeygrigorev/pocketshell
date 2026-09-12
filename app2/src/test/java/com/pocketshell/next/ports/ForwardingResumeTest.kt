package com.pocketshell.next.ports

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
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
    fun secondObserveWhileAlreadyStarted_doesNotStartServiceAgain() = runTest(dispatcher) {
        seedHost(enabled = true)
        val started = AtomicInteger(0)
        val resume = resume { started.incrementAndGet() }
        val owner = FakeLifecycleOwner()
        owner.registry.currentState = Lifecycle.State.STARTED

        resume.observeProcessLifecycle(owner)
        advanceUntilIdle()
        val afterFirst = started.get()
        assertEquals("addObserver on STARTED dispatches ON_START", 1, afterFirst)

        resume.observeProcessLifecycle(owner)
        advanceUntilIdle()
        assertEquals(
            "second observe is attach-only; process stays STARTED so there is no new ON_START",
            afterFirst,
            started.get(),
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

        assertEquals("addObserver on STARTED dispatches ON_START", 1, started.get())
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

    @Test
    fun resumeNow_withEnabledHost_startsService() = runTest(dispatcher) {
        seedHost(enabled = true)
        val started = AtomicInteger(0)
        val resume = resume { started.incrementAndGet() }

        resume.resumeNow()
        advanceUntilIdle()

        assertEquals(1, started.get())
    }

    @Test
    fun resumeNow_withNoEnabledHost_doesNotStartService() = runTest(dispatcher) {
        seedHost(enabled = false)
        val started = AtomicInteger(0)
        val resume = resume { started.incrementAndGet() }

        resume.resumeNow()
        advanceUntilIdle()

        assertEquals(0, started.get())
    }

    @Test
    fun resumeNow_passphraseProtected_isSkipped() = runTest(dispatcher) {
        seedHost(enabled = true, hasPassphrase = true)
        val started = AtomicInteger(0)
        val resume = resume { started.incrementAndGet() }

        resume.resumeNow()
        advanceUntilIdle()

        assertEquals(0, started.get())
    }

    @Test
    fun resumeNow_afterLeftoverAttachWhileProcessStarted_startsServiceWithoutNewOnStart() =
        runTest(dispatcher) {
            seedHost(enabled = true)
            val started = AtomicInteger(0)
            val resume = resume { started.incrementAndGet() }
            val owner = FakeLifecycleOwner()
            owner.registry.currentState = Lifecycle.State.STARTED

            resume.observeProcessLifecycle(owner)
            advanceUntilIdle()
            assertEquals("leftover attach consumed ON_START", 1, started.get())

            started.set(0)
            resume.resumeNow()
            advanceUntilIdle()
            assertEquals(
                "MainActivity.onStart must resume after leftover attach with no new process ON_START",
                1,
                started.get(),
            )
        }

    @Test
    fun resumeSweep_withFailingDb_doesNotCrash_andStillCountsSweep() = runTest(dispatcher) {
        val uncaught = mutableListOf<Throwable>()
        val started = AtomicInteger(0)
        val resume = ForwardingResume(
            applicationContext = context,
            hostDao = ThrowingHostDao(db.hostDao()),
            sshKeyDao = db.sshKeyDao(),
        )
        resume.scope = CoroutineScope(
            SupervisorJob() + dispatcher +
                CoroutineExceptionHandler { _, t -> uncaught.add(t) },
        )
        resume.startService = { started.incrementAndGet() }

        resume.resumeNow()
        advanceUntilIdle()

        assertEquals("failed sweep must not start the service", 0, started.get())
        assertEquals(
            "failed sweep still counts toward resumeSweepCount",
            1,
            resume.resumeSweepCount.get(),
        )
        assertEquals(
            "sweep failure must be caught inside resumeIfNeeded, never reach the scope handler",
            emptyList<Throwable>(),
            uncaught,
        )
    }

    /** Room reopens a closed in-memory DB as empty, so simulate the DB failure at the DAO. */
    private class ThrowingHostDao(real: HostDao) : HostDao by real {
        override fun getEnabled(): Flow<List<HostEntity>> =
            flow { throw IllegalStateException("cannot perform this operation because the connection pool has been closed") }
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
