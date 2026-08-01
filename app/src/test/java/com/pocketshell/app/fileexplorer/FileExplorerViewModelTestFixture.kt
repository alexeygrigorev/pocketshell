package com.pocketshell.app.fileexplorer

import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.ssh.SshLeaseManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking

/**
 * Owns the complete JVM-test lifecycle of File Explorer ViewModels.
 *
 * A cached directory is painted synchronously while its live reconcile keeps
 * running on [kotlinx.coroutines.Dispatchers.IO]. A state-only Ready predicate
 * therefore cannot prove that the ViewModel's real-worker child has returned to
 * Main. Cancel and join every owned job before [MainDispatcherRule] resets the
 * process-global Main dispatcher (#1880, second recurrence).
 */
internal class FileExplorerViewModelTestFixture(
    mainDispatcherRule: MainDispatcherRule,
) {
    private data class Owned(
        val viewModel: FileExplorerViewModel,
        val leaseManager: SshLeaseManager,
    )

    private val owned = mutableListOf<Owned>()
    private var closed = false

    init {
        mainDispatcherRule.beforeResetMain(::closeAndJoin)
    }

    fun create(leaseManager: SshLeaseManager): FileExplorerViewModel =
        FileExplorerViewModel(leaseManager).also { viewModel ->
            check(!closed) { "FileExplorerViewModelTestFixture is already closed" }
            owned += Owned(viewModel, leaseManager)
        }

    fun closeAndJoin() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        owned.asReversed().forEach { owner ->
            try {
                owner.viewModel.clearAndJoinOwnedJobs()
            } catch (candidate: Throwable) {
                failure = failure.combine(candidate)
            }
            try {
                owner.leaseManager.close()
            } catch (candidate: Throwable) {
                failure = failure.combine(candidate)
            }
        }
        owned.clear()
        failure?.let { throw it }
    }

    fun clearAndJoin(viewModel: FileExplorerViewModel) {
        check(owned.any { it.viewModel === viewModel }) {
            "FileExplorerViewModel is not owned by this fixture"
        }
        viewModel.clearAndJoinOwnedJobs()
    }

    private fun FileExplorerViewModel.clearAndJoinOwnedJobs() {
        val jobs = listOfNotNull(jobField("loadJob"), jobField("transferJob"))
        callOnCleared()
        runBlocking { jobs.joinAll() }
        check(jobs.all(Job::isCompleted)) {
            "File Explorer ViewModel still owns an incomplete coroutine after clear-and-join"
        }
    }

    private fun FileExplorerViewModel.jobField(name: String): Job? {
        val field = FileExplorerViewModel::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(this) as? Job
    }

    private fun FileExplorerViewModel.callOnCleared() {
        val method = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
        method.isAccessible = true
        method.invoke(this)
    }

    private fun Throwable?.combine(candidate: Throwable): Throwable =
        this?.also { first ->
            if (first !== candidate) first.addSuppressed(candidate)
        } ?: candidate
}
