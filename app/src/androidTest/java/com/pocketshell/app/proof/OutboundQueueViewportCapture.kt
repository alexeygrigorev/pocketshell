package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.composer.OutboundItem
import com.pocketshell.app.composer.composerOutboundQueueItemRowTestTag
import com.pocketshell.app.proof.signals.assertNodeFullyWithinOwningRoot
import org.junit.Assert.assertEquals

/** Captures reviewer-visible stable-id queue rows in exact FIFO order. */
internal class OutboundQueueViewportCapture(
    private val compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>,
    private val artifacts: OutboundAcceptanceArtifacts,
    private val visibleTerminalText: () -> String,
) {
    fun capture(checkpoint: String, rows: List<OutboundItem>) {
        assertEquals("checkpoint requires both stable FIFO rows", 2, rows.size)
        artifacts.writeText(
            "$checkpoint-fifo.txt",
            rows.mapIndexed { index, row ->
                "${index + 1}|${row.id}|${composerOutboundQueueItemRowTestTag(row.id)}|${row.cleanText}"
            }.joinToString(separator = "\n", postfix = "\n"),
        )
        var previousCrop: Bitmap? = null
        try {
            rows.forEachIndexed { index, row ->
                val tag = composerOutboundQueueItemRowTestTag(row.id)
                compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()
                val current = captureRow(
                    name = "$checkpoint-fifo-${index + 1}-${row.id.take(8)}",
                    tag = tag,
                    payload = row.cleanText,
                    distinctFrom = previousCrop,
                )
                previousCrop?.recycle()
                previousCrop = current
            }
        } finally {
            previousCrop?.recycle()
        }
    }

    private fun captureRow(name: String, tag: String, payload: String, distinctFrom: Bitmap?): Bitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (true) {
            compose.waitForIdle()
            val row = compose.onNodeWithTag(tag, useUnmergedTree = true)
            row.assertIsDisplayed()
            compose.assertNodeFullyWithinOwningRoot(tag, useUnmergedTree = true)
            compose.onNode(
                hasText(payload, substring = true) and hasAnyAncestor(hasTestTag(tag)),
                useUnmergedTree = true,
            ).assertIsDisplayed()
            instrumentation.waitForIdleSync()
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            check(bitmap.width == 1080 && bitmap.height == 2400)
            val bounds = row.fetchSemanticsNode().boundsInWindow
            val left = kotlin.math.floor(bounds.left).toInt().coerceIn(0, bitmap.width - 1)
            val top = kotlin.math.floor(bounds.top).toInt().coerceIn(0, bitmap.height - 1)
            val right = kotlin.math.ceil(bounds.right).toInt().coerceIn(left + 1, bitmap.width)
            val bottom = kotlin.math.ceil(bounds.bottom).toInt().coerceIn(top + 1, bitmap.height)
            val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
            if (distinctFrom == null || !crop.sameAs(distinctFrom)) {
                artifacts.writeViewport(name, bitmap)
                artifacts.writeText("$name-visible-terminal.txt", visibleTerminalText())
                bitmap.recycle()
                return crop
            }
            crop.recycle()
            bitmap.recycle()
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw AssertionError("$name never rendered a Surface frame distinct from the preceding FIFO row")
            }
            SystemClock.sleep(16L)
        }
    }
}
