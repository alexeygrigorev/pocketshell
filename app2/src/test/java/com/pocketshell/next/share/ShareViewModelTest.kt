package com.pocketshell.next.share

import android.app.NotificationManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.robolectric.Shadows.shadowOf

/**
 * [ShareViewModel] over the same real stack [ShareUploaderTest] uses: real Room,
 * real registry, real uploader, real notifier. Only the sshj dial and the source
 * app's provider are substituted.
 *
 * The assertions this file exists for are the ones a mocked uploader could not
 * make: that the picker is SKIPPED exactly when the destination is unambiguous,
 * that a multi-file share produces one remote file per item, and that one bad
 * item does not swallow the good ones.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ShareViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var stack: TestShareStack

    @Before
    fun setUp() {
        // viewModelScope is hard-wired to Dispatchers.Main.
        Dispatchers.setMain(dispatcher)
        stack = TestShareStack()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        stack.close()
    }

    private fun viewModel(): ShareViewModel = ShareViewModel(
        hostDao = stack.db.hostDao(),
        registry = stack.registry,
        uploader = stack.uploader,
        notifier = stack.notifier,
        dispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `one configured host uploads without asking which host`() = runTest(dispatcher) {
        val hostId = stack.seedHost(name = "devbox")
        val model = viewModel()

        model.stage(listOf(stack.uriItem("content://doc/1", "hello".toByteArray(), providerName = "note.txt")))
        advanceUntilIdle()

        val upload = model.state.value.upload
        assertTrue("expected a completed upload, got $upload", upload is ShareUploadState.Success)
        val path = (upload as ShareUploadState.Success).paths.single()
        assertEquals("${stack.inbox}/${TestShareStack.fixedTimestamp}-note.txt", path)
        assertEquals("hello", stack.sftp.textAt(path))
        assertEquals(hostId, stack.factory.targets.single().hostId)
    }

    @Test
    fun `two hosts and no live connection shows the picker instead of guessing`() =
        runTest(dispatcher) {
            stack.seedHost(name = "devbox")
            stack.seedHost(name = "laptop")
            val model = viewModel()

            model.stage(listOf(stack.uriItem("content://doc/1", "x".toByteArray())))
            advanceUntilIdle()

            val state = model.state.value
            assertEquals(ShareUploadState.Idle, state.upload)
            assertEquals(listOf("devbox", "laptop"), state.hosts.map { it.name })
            // Nothing was dialled and nothing was written: an ambiguous share
            // must not touch anybody's machine before the user says which one.
            assertEquals(0, stack.factory.dialCount)
        }

    @Test
    fun `the single host with a live connection is chosen without asking`() = runTest(dispatcher) {
        stack.seedHost(name = "devbox")
        val liveHostId = stack.seedHost(name = "laptop")
        // The user is attached to `laptop` right now — the registry holding a
        // live connection for it is what "the host I'm working on" means here.
        stack.registry.getOrConnect(liveHostId)

        val model = viewModel()
        model.stage(listOf(stack.uriItem("content://doc/1", "x".toByteArray(), providerName = "a.txt")))
        advanceUntilIdle()

        val upload = model.state.value.upload
        assertTrue("expected an upload to the connected host, got $upload", upload is ShareUploadState.Success)
        assertEquals("laptop", (upload as ShareUploadState.Success).hostName)
        // The same connection was reused rather than a second one dialled.
        assertEquals(1, stack.factory.dialCount)
    }

    @Test
    fun `tapping a host uploads every staged file`() = runTest(dispatcher) {
        stack.seedHost(name = "devbox")
        val target = stack.seedHost(name = "laptop")
        val model = viewModel()
        model.stage(
            listOf(
                stack.uriItem("content://doc/1", "one".toByteArray(), providerName = "one.txt"),
                stack.uriItem("content://doc/2", "two".toByteArray(), providerName = "two.txt"),
                stack.uriItem("content://doc/3", "three".toByteArray(), providerName = "three.txt"),
            ),
        )
        advanceUntilIdle()

        model.uploadTo(target)
        advanceUntilIdle()

        val upload = model.state.value.upload as ShareUploadState.Success
        assertEquals(3, upload.paths.size)
        assertEquals(listOf("one", "two", "three"), upload.paths.map { stack.sftp.textAt(it) })
        assertEquals("Sent 3 files to laptop", upload.message)
    }

    @Test
    fun `one unreadable file does not lose the ones that did upload`() = runTest(dispatcher) {
        val hostId = stack.seedHost(name = "devbox")
        val good = stack.uriItem("content://doc/1", "good".toByteArray(), providerName = "good.txt")
        // A URI the provider no longer serves — the second of three.
        val broken = ShareableItem.UriItem(
            uri = android.net.Uri.parse("content://doc/gone"),
            displayName = "gone.txt",
            mimeType = null,
            fallbackExtension = null,
        )
        val alsoGood = stack.uriItem("content://doc/3", "also".toByteArray(), providerName = "also.txt")

        val model = viewModel()
        model.stage(listOf(good, broken, alsoGood))
        advanceUntilIdle()
        model.uploadTo(hostId)
        advanceUntilIdle()

        val upload = model.state.value.upload
        assertTrue("expected a partial failure, got $upload", upload is ShareUploadState.Failed)
        upload as ShareUploadState.Failed
        assertEquals(listOf("gone.txt"), upload.failedNames)
        assertEquals(2, upload.uploaded.size)
        assertEquals(listOf("good", "also"), upload.uploaded.map { stack.sftp.textAt(it) })
        assertTrue(
            "the user must be told what did land: `${upload.message}`",
            upload.message.contains("2 of 3 uploaded") && upload.message.contains("gone.txt"),
        )
    }

    @Test
    fun `a failed share can be retried onto the same host and then succeeds`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost(name = "devbox")
            stack.factory.failWith = "No route to host"
            val model = viewModel()
            model.stage(listOf(stack.uriItem("content://doc/1", "x".toByteArray(), providerName = "a.txt")))
            advanceUntilIdle()

            assertTrue(model.state.value.upload is ShareUploadState.Failed)

            stack.factory.failWith = null
            model.retry(hostId)
            advanceUntilIdle()

            val upload = model.state.value.upload as ShareUploadState.Success
            assertEquals("x", stack.sftp.textAt(upload.paths.single()))
        }

    @Test
    fun `a share with no hosts configured says so instead of spinning forever`() =
        runTest(dispatcher) {
            val model = viewModel()

            model.stage(listOf(stack.uriItem("content://doc/1", "x".toByteArray())))
            advanceUntilIdle()

            val state = model.state.value
            assertTrue("hosts must have loaded", state.hostsLoaded)
            assertTrue("no hosts to offer", state.hosts.isEmpty())
            assertEquals(ShareUploadState.Idle, state.upload)
        }

    @Test
    fun `the status bar carries the result when the user has left the screen`() =
        runTest(dispatcher) {
            stack.seedHost(name = "devbox")
            val model = viewModel()

            model.stage(listOf(stack.uriItem("content://doc/1", "x".toByteArray(), providerName = "a.txt")))
            advanceUntilIdle()

            val posted = shadowOf(notificationManager()).allNotifications
            assertEquals(1, posted.size)
            val text = posted.single().extras.getCharSequence("android.text").toString()
            assertTrue("expected the landed path in `$text`", text.contains("-a.txt"))
        }

    @Test
    fun `a failure is reported in the status bar too`() = runTest(dispatcher) {
        stack.seedHost(name = "devbox")
        stack.factory.failWith = "No route to host"
        val model = viewModel()

        model.stage(listOf(stack.uriItem("content://doc/1", "x".toByteArray(), providerName = "a.txt")))
        advanceUntilIdle()

        val posted = shadowOf(notificationManager()).allNotifications
        val title = posted.last().extras.getCharSequence("android.title").toString()
        assertTrue("expected a failure title, got `$title`", title.contains("failed"))
    }

    @Test
    fun `closing the share takes its notification with it`() = runTest(dispatcher) {
        stack.seedHost(name = "devbox")
        val model = viewModel()
        model.stage(listOf(stack.uriItem("content://doc/1", "x".toByteArray(), providerName = "a.txt")))
        advanceUntilIdle()
        assertEquals(1, shadowOf(notificationManager()).allNotifications.size)

        // The activity finished, so the ViewModel is cleared. An ONGOING
        // "uploading…" row here would advertise work that died with the scope,
        // and it cannot be swiped away.
        clearViewModel(model)

        assertTrue(
            "the status bar must be empty once the share surface is gone",
            shadowOf(notificationManager()).allNotifications.isEmpty(),
        )
    }

    @Test
    fun `the picker is skipped for one host and shown for several`() {
        val devbox = ShareHostRow(1, "devbox", "a@b", connected = false)
        val laptop = ShareHostRow(2, "laptop", "a@c", connected = false)

        assertEquals(null, defaultShareHost(emptyList()))
        assertEquals(devbox, defaultShareHost(listOf(devbox)))
        assertEquals(null, defaultShareHost(listOf(devbox, laptop)))
        assertEquals(
            laptop.copy(connected = true),
            defaultShareHost(listOf(devbox, laptop.copy(connected = true))),
        )
        // Two live connections is genuinely ambiguous — ask.
        assertEquals(
            null,
            defaultShareHost(listOf(devbox.copy(connected = true), laptop.copy(connected = true))),
        )
    }

    /**
     * Ends [model]'s lifecycle the way a finishing Activity does.
     *
     * `ViewModel.clear()` is internal to androidx.lifecycle, so the real owner
     * is used: a [ViewModelStore] holding this instance, cleared. That runs the
     * production `onCleared()` rather than a test-only stand-in for it.
     */
    private fun clearViewModel(model: ShareViewModel) {
        val store = ViewModelStore()
        ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = model as T
            },
        )[ShareViewModel::class.java]
        store.clear()
    }

    private fun notificationManager(): NotificationManager =
        ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(NotificationManager::class.java)
}
