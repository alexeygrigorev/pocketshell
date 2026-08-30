package com.pocketshell.app.fileviewer

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FileViewerImageTransformStateTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun realImagePanelGetsFreshPanAndZoomStateWhenFileIdentityChanges() {
        var file by mutableStateOf(File("/cache/a.png"))
        var visible: ImageTransformState? = null
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val decoder = FileViewerBitmapDecoder { bitmap }

        compose.setContent {
            PocketShellTheme {
                CompositionLocalProvider(LocalFileViewerBitmapDecoder provides decoder) {
                    ImagePanel(
                        cacheFile = file,
                        onTransformStateObserved = { visible = it },
                    )
                }
            }
        }
        compose.waitUntil(5_000) { visible != null }
        val first = requireNotNull(visible)
        compose.runOnIdle {
            first.scale = 4f
            first.offsetX = 120f
            first.offsetY = -80f
            file = File("/cache/b.png")
        }

        compose.waitUntil(5_000) { visible !== first }
        compose.runOnIdle {
            assertNotSame(first, visible)
            assertEquals(1f, visible?.scale)
            assertEquals(0f, visible?.offsetX)
            assertEquals(0f, visible?.offsetY)
        }
    }

    @Test
    fun realImagePanelComposesWhileDecoderIsBlockedOffMain() {
        val decodeStarted = CountDownLatch(1)
        val releaseDecode = CountDownLatch(1)
        val decodeThread = AtomicReference<Thread>()
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val decoder = FileViewerBitmapDecoder {
            decodeThread.set(Thread.currentThread())
            decodeStarted.countDown()
            check(releaseDecode.await(10, TimeUnit.SECONDS))
            bitmap
        }

        try {
            compose.setContent {
                PocketShellTheme {
                    CompositionLocalProvider(LocalFileViewerBitmapDecoder provides decoder) {
                        ImagePanel(cacheFile = File("/cache/blocked.png"))
                    }
                }
            }
            assertTrue("decoder never started", decodeStarted.await(5, TimeUnit.SECONDS))
            compose.onNodeWithTag(FILE_VIEWER_IMAGE_LOADING_TAG).assertIsDisplayed()
            assertNotNull(decodeThread.get())
            assertNotSame(Looper.getMainLooper().thread, decodeThread.get())
        } finally {
            releaseDecode.countDown()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(FILE_VIEWER_IMAGE_TAG)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(FILE_VIEWER_IMAGE_TAG).assertIsDisplayed()
    }
}
