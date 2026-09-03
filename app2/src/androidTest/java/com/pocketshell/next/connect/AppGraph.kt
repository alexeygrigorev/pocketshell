package com.pocketshell.next.connect

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.SshKeyDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Reaches into the RUNNING app's Hilt graph from an instrumented test.
 *
 * The alternative — opening a second Room instance over the same
 * `pocketshell.db` file — would give the test its own connection pool and its
 * own invalidation tracker, so a row the test wrote and a row the screen reads
 * would come from two different places. Everything here is the app's real
 * singleton: the same [HostDao] the host list renders from, and the same
 * [ConnectionsRegistry] the connect gate dials through.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppGraph {
    fun hostDao(): HostDao
    fun sshKeyDao(): SshKeyDao
    fun connectionsRegistry(): ConnectionsRegistry
}

/** The app-under-test's Hilt graph. */
fun appGraph(): AppGraph = EntryPointAccessors.fromApplication(
    InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application,
    AppGraph::class.java,
)
