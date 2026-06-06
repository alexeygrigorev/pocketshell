package com.pocketshell.app.hosts

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
import androidx.compose.ui.test.onNodeWithTag
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
 * Issue #515: connected coverage for the two new update affordances.
 *
 * 1. [AppUpdateWarningBanner] — when the host probe proved the app is
 *    behind the remote pocketshell CLI AND a downloadable GitHub release
 *    has been resolved, the #514 soft banner offers an OPTIONAL "Update"
 *    that fires the same `ACTION_VIEW` → APK path as the standalone
 *    [UpdateBanner]. The host stays fully usable; the action never blocks.
 *
 * 2. [UpdateCheckFailedBanner] — when the GitHub poll genuinely failed
 *    (vs found no newer release), a visible "couldn't check — Retry" note
 *    appears instead of a silent no-op, and Retry re-runs the check.
 *
 * Both render the real production composables against the real
 * [HostListViewModel] wiring so the test stays honest about shipped
 * behavior. Screenshots are captured for the reviewer.
 */
@RunWith(AndroidJUnit4::class)
class AppUpdateActionBannerTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: AppDatabase

    private val releaseInfo = ReleaseInfo(
        tagName = "v9999.0.0",
        htmlUrl = "https://github.com/alexeygrigorev/pocketshell/releases/tag/v9999.0.0",
        apkUrl = "https://example.com/pocketshell-9999.0.0-debug.apk",
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

    private fun newViewModel(releaseChecker: ReleaseChecker): HostListViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return HostListViewModel(
            applicationContext = context,
            hostDao = db.hostDao(),
            sshKeyDao = db.sshKeyDao(),
            releaseChecker = releaseChecker,
            bootstrapper = HostBootstrapper(),
            usageScheduler = UsageScheduler(db.hostDao(), db.sshKeyDao(), UsageRemoteSource()),
            activeClients = ActiveTmuxClients(),
            settingsRepository = SettingsRepository(context),
            sessionOpener = { _, _, _ -> null },
        )
    }

    @Test
    fun appUpdateBanner_withResolvedRelease_firesApkDownload() {
        val viewModel = newViewModel(StubReleaseChecker(releaseInfo))
        val warning = HostListViewModel.AppUpdateWarning(
            hostId = 7L,
            remoteVersion = "9999.0.0",
            appVersion = "0.3.22",
            releaseInfo = releaseInfo,
        )
        val launched = mutableListOf<Uri>()
        val context = RecordingContext(
            ApplicationProvider.getApplicationContext(),
            onStart = { intent -> launched.add(intent.data!!); true },
        )

        compose.setContent {
            AppUpdateNoticesUnderTest(context = context, viewModel = viewModel, warning = warning)
        }

        // The OPTIONAL "Update" action is present because a release resolved.
        compose.onNodeWithTag(HOST_LIST_APP_UPDATE_ACTION_TAG).assertExists()
        capture("app-update-banner-with-action.png")

        compose.onNodeWithTag(HOST_LIST_APP_UPDATE_ACTION_TAG).performClick()
        compose.waitForIdle()

        // It fires the SAME ACTION_VIEW → APK path as the standalone banner.
        assertTrue(launched.any { it.toString() == releaseInfo.apkUrl })
        compose.onNodeWithText("Downloading v9999.0.0", substring = true).assertExists()
        capture("app-update-banner-download-started.png")
    }

    @Test
    fun appUpdateBanner_withoutResolvedRelease_isPassiveDismissibleNote() {
        val viewModel = newViewModel(StubReleaseChecker(null))
        val warning = HostListViewModel.AppUpdateWarning(
            hostId = 7L,
            remoteVersion = "9999.0.0",
            appVersion = "0.3.22",
            releaseInfo = null,
        )
        val context = RecordingContext(
            ApplicationProvider.getApplicationContext(),
            onStart = { true },
        )

        compose.setContent {
            AppUpdateNoticesUnderTest(context = context, viewModel = viewModel, warning = warning)
        }

        // No resolved release → no Update action, just the passive note.
        compose.onNodeWithTag(HOST_LIST_APP_UPDATE_ACTION_TAG).assertDoesNotExist()
        compose.onNodeWithText("consider updating the app", substring = true).assertExists()
        capture("app-update-banner-passive.png")
    }

    @Test
    fun updateCheckFailedBanner_showsReason_andRetryReRunsCheck() {
        val checker = StubReleaseChecker(null, failureReason = "GitHub returned HTTP 403")
        val viewModel = newViewModel(checker)
        // init {} already ran one failed check.
        val context = RecordingContext(
            ApplicationProvider.getApplicationContext(),
            onStart = { true },
        )

        compose.setContent {
            FailureNoticeUnderTest(viewModel = viewModel)
        }

        compose.waitForIdle()
        // Visible, reasoned failure — NOT a silent no-op.
        compose.onNodeWithTag(HOST_LIST_UPDATE_CHECK_FAILED_TAG).assertExists()
        compose.onNodeWithText("Couldn't check for updates", substring = true).assertExists()
        capture("update-check-failed-banner.png")

        val before = checker.callCount
        compose.onNodeWithTag(HOST_LIST_UPDATE_CHECK_RETRY_TAG).performClick()
        compose.waitForIdle()
        // Retry re-runs the check.
        assertTrue(checker.callCount > before)
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val directory = File(mediaRoot, "additional_test_output/app-update-action")
        check(directory.exists() || directory.mkdirs()) {
            "Could not create app-update artifact directory: ${directory.absolutePath}"
        }
        val file = File(directory, name)
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Could not write app-update screenshot: ${file.absolutePath}"
            }
        }
        bitmap.recycle()
        println("APP_UPDATE_SCREENSHOT ${file.absolutePath}")
    }

    /**
     * Renders the real [AppUpdateWarningBanner] with the production
     * `launchApkDownload` wiring + the result [ShareMessageBanner], exactly
     * as the host-list notices block does.
     */
    @androidx.compose.runtime.Composable
    private fun AppUpdateNoticesUnderTest(
        context: Context,
        viewModel: HostListViewModel,
        warning: HostListViewModel.AppUpdateWarning,
    ) {
        val updateMessage by viewModel.updateMessage.collectAsStateWithLifecycle()
        Column(modifier = Modifier.fillMaxWidth().testTag("app-update-notices-under-test")) {
            Column(modifier = Modifier.testTag(HOST_LIST_APP_UPDATE_WARNING_TAG)) {
                AppUpdateWarningBanner(
                    warning = warning,
                    onUpdate = warning.releaseInfo?.let { info ->
                        { launchApkDownload(context, info, viewModel) }
                    },
                    onDismiss = viewModel::dismissAppUpdateWarning,
                )
            }
            updateMessage?.let { msg ->
                ShareMessageBanner(message = msg, onDismiss = viewModel::clearUpdateMessage)
            }
        }
    }

    /** Renders the real [UpdateCheckFailedBanner] driven by the ViewModel. */
    @androidx.compose.runtime.Composable
    private fun FailureNoticeUnderTest(viewModel: HostListViewModel) {
        val failure by viewModel.updateCheckFailed.collectAsStateWithLifecycle()
        Column(modifier = Modifier.fillMaxWidth().testTag("failure-notice-under-test")) {
            failure?.let { f ->
                Column(modifier = Modifier.testTag(HOST_LIST_UPDATE_CHECK_FAILED_TAG)) {
                    UpdateCheckFailedBanner(
                        reason = f.reason,
                        onRetry = viewModel::checkForUpdates,
                        onDismiss = viewModel::dismissUpdateCheckFailure,
                    )
                }
            }
        }
    }

    private class RecordingContext(
        base: Context,
        private val onStart: (Intent) -> Boolean,
    ) : ContextWrapper(base) {
        override fun startActivity(intent: Intent) {
            onStart(intent)
        }
    }

    private class StubReleaseChecker(
        private val info: ReleaseInfo?,
        private val failureReason: String? = null,
    ) : ReleaseChecker() {
        var callCount: Int = 0
            private set

        override suspend fun checkForUpdate(currentVersion: String): ReleaseCheckResult {
            callCount += 1
            return when {
                failureReason != null -> ReleaseCheckResult.Failed(failureReason)
                info != null -> ReleaseCheckResult.UpdateAvailable(info)
                else -> ReleaseCheckResult.UpToDate
            }
        }
    }
}
