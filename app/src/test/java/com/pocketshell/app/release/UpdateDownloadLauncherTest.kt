package com.pocketshell.app.release

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shared user-driven update download path (#515). The #657 audit flagged
 * [launchUpdateDownload] as having zero coverage; this pins the exact intent
 * it builds (the symptom the maintainer hit was "tap Update, nothing
 * downloads") and the failure/fallback semantics.
 *
 * Robolectric gives a real [Context], [Intent], and `Uri` so the test asserts
 * the actual `ACTION_VIEW` intent rather than a mock — including the
 * [Intent.FLAG_ACTIVITY_NEW_TASK] that makes the launch survive a
 * non-Activity context (the gap that made the old launcher throw an uncaught
 * `AndroidRuntimeException` from outside an Activity).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class UpdateDownloadLauncherTest {

    private val info = ReleaseInfo(
        tagName = "v0.3.23",
        htmlUrl = "https://github.com/alexeygrigorev/pocketshell/releases/tag/v0.3.23",
        apkUrl = "https://github.com/alexeygrigorev/pocketshell/releases/download/" +
            "v0.3.23/pocketshell-0.3.23-debug.apk",
    )

    @Test
    fun apkViewIntent_isActionViewToApkUrl_withNewTaskFlag() {
        val intent = apkViewIntent(info)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(info.apkUrl, intent.dataString)
        // The flag is what lets the launch work from any context type.
        assertTrue(
            "ACTION_VIEW intent must carry FLAG_ACTIVITY_NEW_TASK",
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
    }

    @Test
    fun launchUpdateDownload_firesApkDownloadIntent_andReportsStarted() {
        val recorder = RecordingContext(ApplicationProvider.getApplicationContext())
        var startedTag: String? = null
        var failedReason: String? = null

        launchUpdateDownload(
            context = recorder,
            info = info,
            onStarted = { startedTag = it },
            onFailed = { failedReason = it },
        )

        val launched = recorder.launched.single()
        assertEquals(Intent.ACTION_VIEW, launched.action)
        assertEquals(info.apkUrl, launched.dataString)
        assertTrue(launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals("v0.3.23", startedTag)
        assertNull("a successful download must not report a failure", failedReason)
    }

    @Test
    fun launchUpdateDownload_fallsBackToReleasePage_whenNoAppCanOpenApk() {
        val recorder = RecordingContext(
            base = ApplicationProvider.getApplicationContext(),
            failApkLaunch = { ActivityNotFoundException("no activity for VIEW apk") },
        )
        var startedTag: String? = null
        var failedReason: String? = null

        launchUpdateDownload(
            context = recorder,
            info = info,
            onStarted = { startedTag = it },
            onFailed = { failedReason = it },
        )

        // The APK launch failed, so it must fall back to the release page and
        // report the concrete failure reason rather than claim success.
        assertNull("a failed download must not report started", startedTag)
        assertNotNull(failedReason)
        val fallback = recorder.launched.single()
        assertEquals(Intent.ACTION_VIEW, fallback.action)
        assertEquals(info.htmlUrl, fallback.dataString)
        assertTrue(fallback.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun launchUpdateDownload_doesNotCrash_whenLaunchedOutsideActivity() {
        // Regression for the silent-crash gap: a non-Activity context used to
        // make startActivity throw an uncaught AndroidRuntimeException (it
        // extends RuntimeException, not ActivityNotFoundException), so the
        // download silently threw and the fallback never ran. The widened
        // catch + the NEW_TASK flag must degrade to the release-page fallback
        // + onFailed instead of letting the exception escape.
        val recorder = RecordingContext(
            base = ApplicationProvider.getApplicationContext(),
            failApkLaunch = {
                android.util.AndroidRuntimeException(
                    "Calling startActivity() from outside of an Activity context " +
                        "requires the FLAG_ACTIVITY_NEW_TASK flag.",
                )
            },
        )
        var startedTag: String? = null
        var failedReason: String? = null

        launchUpdateDownload(
            context = recorder,
            info = info,
            onStarted = { startedTag = it },
            onFailed = { failedReason = it },
        )

        assertNull(startedTag)
        assertNotNull("the exotic launch failure must surface a reason", failedReason)
        // Fallback to the release page still attempted.
        assertEquals(info.htmlUrl, recorder.launched.single().dataString)
    }

    private class RecordingContext(
        base: Context,
        private val failApkLaunch: (() -> RuntimeException)? = null,
    ) : ContextWrapper(base) {
        val launched = mutableListOf<Intent>()

        override fun startActivity(intent: Intent) {
            // Only the primary APK-download launch is configured to fail; the
            // release-page fallback (different data URL) is recorded so the
            // test can assert the fallback target.
            if (failApkLaunch != null && intent.dataString == APK_URL) {
                throw failApkLaunch.invoke()
            }
            launched += intent
        }

        private companion object {
            const val APK_URL =
                "https://github.com/alexeygrigorev/pocketshell/releases/download/" +
                    "v0.3.23/pocketshell-0.3.23-debug.apk"
        }
    }
}
