package com.pocketshell.app.hosts

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.bootstrap.HostBootstrapper
import com.pocketshell.app.release.ReleaseCheckResult
import com.pocketshell.app.release.ReleaseChecker
import com.pocketshell.app.release.ReleaseInfo
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.settings.SettingsRepository
import com.pocketshell.app.usage.UsageRemoteSource
import com.pocketshell.app.usage.UsageScheduler
import com.pocketshell.core.storage.AppDatabase
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #476: connected coverage for the update-banner tap feedback.
 *
 * The maintainer's complaint was that tapping "Update" gave no
 * confirmation — a working `ACTION_VIEW` download looked like a silent
 * no-op. The fix routes every tap through [HostListViewModel]'s
 * [HostListViewModel.onUpdateDownloadStarted] /
 * [HostListViewModel.onUpdateDownloadFailed], which surface a
 * [ShareMessageBanner]. This test renders the real production
 * [UpdateBanner] + result banner wiring against a real
 * [HostListViewModel] and exercises both paths:
 *
 * - Success: a [Context] whose `startActivity` accepts the APK intent →
 *   the "Downloading <tag>" confirmation appears.
 * - Failure: a [Context] that throws [ActivityNotFoundException] for the
 *   APK URL but accepts the release-page URL → the "Couldn't start the
 *   download … opened the release page" message appears AND the fallback
 *   release-page intent is recorded.
 *
 * Screenshots of both states are captured for the reviewer.
 */
@RunWith(AndroidJUnit4::class)
class UpdateBannerFeedbackTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: AppDatabase

    private val releaseInfo = ReleaseInfo(
        tagName = "v0.4.0",
        htmlUrl = "https://github.com/alexeygrigorev/pocketshell/releases/tag/v0.4.0",
        apkUrl = "https://example.com/pocketshell-0.4.0-debug.apk",
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun newViewModel(): HostListViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return HostListViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = NoOpReleaseChecker(),
            bootstrapper = HostBootstrapper(),
            usageScheduler = UsageScheduler(db.hostDao(), db.sshKeyDao(), UsageRemoteSource()),
            activeClients = ActiveTmuxClients(),
            settingsRepository = SettingsRepository(context),
            sessionOpener = { _, _, _ -> null },
        )
    }

    @Test
    fun tappingUpdate_showsDownloadStartedConfirmation() {
        val viewModel = newViewModel()
        // A context that accepts every ACTION_VIEW — the maintainer's
        // device, where the download works.
        val launched = mutableListOf<Uri>()
        val context = RecordingContext(
            ApplicationProvider.getApplicationContext(),
            onStart = { intent -> launched.add(intent.data!!); true },
        )

        compose.setContent {
            UpdateNoticesUnderTest(context = context, viewModel = viewModel)
        }

        compose.onNodeWithText("Update").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Downloading v0.4.0", substring = true).assertExists()
        // The successful path launches the APK intent and never the
        // release-page fallback.
        assertTrue(launched.any { it.toString() == releaseInfo.apkUrl })
        assertTrue(launched.none { it.toString() == releaseInfo.htmlUrl })

        capture("update-download-started.png")
    }

    @Test
    fun tappingUpdate_onNoHandler_showsFailureAndOpensReleasePage() {
        val viewModel = newViewModel()
        // A context that has no handler for the APK URL (the documented
        // failure mode) but can open the release page.
        val launched = mutableListOf<Uri>()
        val context = RecordingContext(
            ApplicationProvider.getApplicationContext(),
            onStart = { intent ->
                val uri = intent.data!!
                if (uri.toString() == releaseInfo.apkUrl) {
                    throw ActivityNotFoundException("no activity for apk")
                }
                launched.add(uri)
                true
            },
        )

        compose.setContent {
            UpdateNoticesUnderTest(context = context, viewModel = viewModel)
        }

        compose.onNodeWithText("Update").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Couldn't start the download", substring = true).assertExists()
        compose.onNodeWithText("release page", substring = true).assertExists()
        // The fallback opened the release page in a browser.
        assertTrue(launched.any { it.toString() == releaseInfo.htmlUrl })

        capture("update-download-failed-fallback.png")
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val directory = File(mediaRoot, "additional_test_output/update-banner-feedback")
        check(directory.exists() || directory.mkdirs()) {
            "Could not create update-banner artifact directory: ${directory.absolutePath}"
        }
        val file = File(directory, name)
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Could not write update-banner screenshot: ${file.absolutePath}"
            }
        }
        bitmap.recycle()
        println("UPDATE_BANNER_SCREENSHOT ${file.absolutePath}")
    }

    /**
     * Mirrors the exact notices-block wiring from [HostListScreen]: the
     * real [UpdateBanner] with the production try/catch + fallback, and
     * the [ShareMessageBanner] that surfaces the result. Driving the same
     * code path (not a copy) keeps the test honest about the shipped
     * behavior.
     */
    @androidx.compose.runtime.Composable
    private fun UpdateNoticesUnderTest(context: Context, viewModel: HostListViewModel) {
        val updateMessage by viewModel.updateMessage.collectAsStateWithLifecycle()
        Column(modifier = Modifier.fillMaxWidth().testTag("update-notices-under-test")) {
            UpdateBanner(
                info = releaseInfo,
                // Issue #515: drive the same shared APK-launch helper the
                // production screen uses, so the test stays honest about the
                // shipped behavior.
                onUpdate = { launchApkDownload(context, releaseInfo, viewModel) },
            )
            updateMessage?.let { msg ->
                ShareMessageBanner(message = msg, onDismiss = viewModel::clearUpdateMessage)
            }
        }
    }

    /** Records the intents passed to [startActivity] and lets the test
     *  decide success/failure per URL. */
    private class RecordingContext(
        base: Context,
        private val onStart: (Intent) -> Boolean,
    ) : ContextWrapper(base) {
        override fun startActivity(intent: Intent) {
            onStart(intent)
        }
    }

    private class NoOpReleaseChecker : ReleaseChecker() {
        override suspend fun checkForUpdate(currentVersion: String): ReleaseCheckResult =
            ReleaseCheckResult.UpToDate
    }
}
