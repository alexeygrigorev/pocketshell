package com.pocketshell.next.connect

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.SentMessageDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.next.composer.ComposerAttachmentStager
import com.pocketshell.next.composer.ComposerDraftStore
import com.pocketshell.next.terminal.GraceCoordinator
import com.pocketshell.next.voice.PendingTranscriptionStore
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

    /**
     * Task P-1. The composer's sent-message log, so a journey can read what the
     * app really persisted rather than what its own screen claims, and the
     * production attachment stager, so a journey can drive a real SFTP upload
     * to the fixture without going through the system file picker (which an
     * instrumented test cannot operate).
     */
    fun sentMessageDao(): SentMessageDao

    fun composerAttachmentStager(): ComposerAttachmentStager

    /** The durable draft slot, so a journey can start from a known-empty composer. */
    fun composerDraftStore(): ComposerDraftStore

    /**
     * Task U-8. The app's single background-grace policy singleton, so a
     * journey can assert [GraceCoordinator.isHolding] directly rather than
     * inferring the D21 window's state from the notification tray alone.
     */
    fun graceCoordinator(): GraceCoordinator

    /**
     * Task P-2. The offline-dictation queue, so a journey can seed a
     * "recorded while offline" row directly — the same way a real dictation
     * would have parked it — without operating a real microphone (which an
     * instrumented test cannot do).
     */
    fun pendingTranscriptionStore(): PendingTranscriptionStore
}

/** The app-under-test's Hilt graph. */
fun appGraph(): AppGraph = EntryPointAccessors.fromApplication(
    InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application,
    AppGraph::class.java,
)
