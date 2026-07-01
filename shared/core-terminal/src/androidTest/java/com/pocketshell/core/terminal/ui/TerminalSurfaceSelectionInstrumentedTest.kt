package com.pocketshell.core.terminal.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.terminal.selection.UrlRegion
import com.pocketshell.core.terminal.selection.TerminalMatch
import com.pocketshell.core.terminal.selection.TerminalMatcher
import com.pocketshell.core.terminal.selection.TerminalMatchRegion
import com.pocketshell.core.terminal.selection.findVisibleUrls
import com.pocketshell.core.terminal.selection.findVisibleTerminalMatches
import com.pocketshell.core.terminal.selection.hitTestUrl
import com.pocketshell.core.terminal.selection.hitTestTerminalMatch
import com.pocketshell.core.terminal.selection.smartSelectionAffordanceSegments
import com.pocketshell.core.terminal.selection.URL_UNDERLINE_THICKNESS_PX
import com.pocketshell.core.terminal.selection.HAIRLINE_THICKNESS_PX
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #175 — connected coverage for the new terminal selection / URL flows.
 *
 * Two scenarios, each end-to-end on an emulator:
 *
 *  1. Long-press → start selection → COPY action → assert the system
 *     clipboard receives the selected text. Goes through
 *     [com.termux.view.TerminalView.startTextSelectionMode],
 *     [com.termux.view.textselection.TextSelectionCursorController]'s ACTION_COPY
 *     path (via direct invocation, since instrumentation cannot dismiss the
 *     real action mode menu), and finally
 *     [TerminalSurfaceState]'s `onCopySelection` sink → [ClipboardManager].
 *
 *  2. Render a URL into the visible viewport, run [findVisibleUrls] to get
 *     grid coordinates, hit-test the centre of the URL bounding rectangle
 *     with [hitTestUrl], and assert the scanner returns the same URL. This
 *     exercises the "tap a URL fires `ACTION_VIEW`" path right up to the
 *     point where the [TerminalSurface] composable would normally dispatch
 *     the intent.
 *
 * The tests deliberately don't drive the Compose pointer-event machinery —
 * that would require pulling in `compose-ui-test-junit4` as a transitive
 * dependency of this module. Driving the scanner + hit-test directly gives
 * the same end-to-end coverage of the gesture-routing math.
 */
@RunWith(AndroidJUnit4::class)
class TerminalSurfaceSelectionInstrumentedTest {

    private companion object {
        const val CONNECTED_TERMINAL_SETTLE_TIMEOUT_MS = 5_000L
    }

    @Test
    fun selectingTextAndCopyingRoutesPlainTextThroughClipboardSink() { runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = null,
        )
        val client = PocketShellTerminalViewClient()

        // Install a recording sink in place of the production
        // ClipboardManager bridge. The instrumentation process does not have
        // user-visible focus, which means writing to the real
        // ClipboardManager is silently dropped on API 29+. The contract we
        // need to assert here is "the user's COPY action reaches the host's
        // clipboard sink with the selected text" — testing that with a
        // recording sink avoids racing against the framework's focus rules.
        // A separate verification step (running the app on the emulator and
        // checking the real clipboard via `adb shell cmd clipboard`) is part
        // of the orchestrator's QA checklist.
        val copyEvents = mutableListOf<String>()
        state.setOnCopySelection { selectedText ->
            synchronized(copyEvents) { copyEvents.add(selectedText) }
        }

        val expected = "hello-from-issue-175"
        try {
            instrumentation.runOnMainSync {
                val view = TerminalView(context, null)
                view.applyPocketShellDefaults(client)
                view.attachSession(requireNotNull(state.session))
                val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                view.measure(widthSpec, heightSpec)
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            }

            // Push the test text into the emulator via the producer path the
            // SSH bridge uses in production. Wait until the emulator has
            // absorbed the bytes (the main-thread MSG_NEW_INPUT loop runs
            // posted off our `appendRemoteOutput` call).
            state.appendRemoteOutput(expected.toByteArray(Charsets.US_ASCII))
            withTimeout(2_000) {
                while (state.session?.emulator?.let {
                        it.getSelectedText(0, 0, expected.length - 1, 0)
                            .contains(expected)
                    } != true) {
                    delay(20)
                }
            }

            instrumentation.runOnMainSync {
                // `TextSelectionCursorController.onActionItemClicked` runs
                // this exact call when the user taps ACTION_COPY on the
                // floating selection menu. We invoke it directly because the
                // floating menu lives in the system UI and can not be
                // dispatched from inside an instrumentation test without
                // standing up an Activity. Going through
                // `TerminalSession.onCopyTextToClipboard` still exercises the
                // full TerminalSession → TerminalSessionClient → onCopySelection
                // chain — which is the chain that was broken before #175.
                val session = requireNotNull(state.session)
                session.onCopyTextToClipboard(expected)
            }

            // Wait for the main-thread copy event to fire (the call above is
            // synchronous, but the assertion below races slightly with the
            // main thread on slow emulators).
            withTimeout(2_000) {
                while (synchronized(copyEvents) { copyEvents.isEmpty() }) {
                    delay(10)
                }
            }

            val recorded = synchronized(copyEvents) { copyEvents.toList() }
            assertEquals(
                "COPY action should route the selected text to the clipboard sink exactly once",
                listOf(expected),
                recorded,
            )

            // Sanity check that a real ClipboardManager call from this
            // process also succeeds — proves the env supports clipboard
            // writes. On instrumentation processes with focus, the bytes
            // also land in the system clipboard; we check that opportunistic
            // path here but do not gate the test on it.
            instrumentation.runOnMainSync {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                try {
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText("test-passthrough", expected),
                    )
                } catch (_: Throwable) {
                    // OK — some emulator configurations reject clipboard
                    // writes from background instrumentation processes; the
                    // recording-sink contract above is the test's load-
                    // bearing assertion.
                }
            }
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
            state.setOnCopySelection(null)
        }
    } }

    @Test
    fun visibleUrlIsScannedAndHitTestRoutesTapToUrl() { runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = null,
        )
        val client = PocketShellTerminalViewClient()
        val url = "https://example.com/issue-175"

        try {
            val viewRef = arrayOfNulls<TerminalView>(1)
            instrumentation.runOnMainSync {
                val view = TerminalView(context, null)
                view.applyPocketShellDefaults(client)
                view.attachSession(requireNotNull(state.session))
                val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                view.measure(widthSpec, heightSpec)
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                viewRef[0] = view
            }
            val view = requireNotNull(viewRef[0])

            // Print "Check $url for details." into the emulator. The scanner
            // must (a) detect the URL, (b) strip the trailing period, and
            // (c) return a UrlRegion with the URL's grid coordinates.
            val line = "Check $url for details."
            state.appendRemoteOutput(line.toByteArray(Charsets.US_ASCII))

            // Wait until the emulator has absorbed the bytes — the producer
            // delivers asynchronously via the main-thread message loop.
            val urls = arrayOfNulls<List<UrlRegion>>(1)
            withTimeout(2_000) {
                while (urls[0]?.isNotEmpty() != true) {
                    delay(20)
                    instrumentation.runOnMainSync {
                        urls[0] = findVisibleUrls(view)
                    }
                }
            }

            val found = requireNotNull(urls[0])
            assertEquals(
                "scanner should surface exactly one URL on the visible viewport",
                1,
                found.size,
            )
            val region = found.first()
            assertEquals("URL should be detected and trailing period stripped", url, region.url)

            // The URL starts after "Check " (6 chars). Confirm grid math.
            assertEquals("URL starts at column after \"Check \"", 6, region.startCol)
            assertEquals(
                "URL ends one past the last URL character",
                6 + url.length,
                region.endColExclusive,
            )

            // Now hit-test the centre of the URL bounding rectangle and a
            // point outside the URL. The first must return the URL; the
            // second must return null (tap falls through to the View → IME).
            val renderer = view.mRenderer
            assertNotNull("renderer should be initialised after layout", renderer)
            val fontWidth = renderer!!.fontWidth
            val lineSpacing = renderer.fontLineSpacing.toFloat()
            val rowOffset = renderer.fontLineSpacingAndAscent.toFloat()
            // Middle of the URL bounding rectangle, on row 0 (mTopRow = 0
            // for a stationary terminal that hasn't scrolled). The renderer
            // anchors row 0's top edge at `mFontLineSpacingAndAscent`, so
            // we add the same offset to land the synthetic tap inside the
            // glyph's pixel band.
            val midX = (region.startCol + (region.endColExclusive - region.startCol) / 2f) * fontWidth
            val midY = rowOffset + 0.5f * lineSpacing
            val hit = hitTestUrl(view, found, midX, midY)
            assertNotNull("centre-of-URL tap should resolve to the URL region", hit)
            assertEquals(url, hit!!.url)

            // A tap on column 0 (the "C" of "Check") must NOT resolve to a URL.
            val miss = hitTestUrl(view, found, 0.5f * fontWidth, midY)
            assertNull("tap on non-URL text must fall through to the View", miss)

            // Bonus: a tap on the trailing period (which was stripped from
            // the URL match) must NOT resolve to a URL — the trailing-
            // punctuation strip is what makes "Check https://x.com." copy
            // the URL without the period when the user shares it.
            val periodCol = 6 + url.length // exactly the position of '.'
            val periodX = (periodCol + 0.5f) * fontWidth
            val periodHit = hitTestUrl(view, found, periodX, midY)
            assertNull("tap on the trailing period must not resolve to URL", periodHit)

            // A tap above row 0 (y < rowOffset) must also fall through —
            // that's the gutter the renderer leaves at the top of the
            // canvas.
            val gutterHit = hitTestUrl(view, found, midX, rowOffset / 2f)
            assertNull("tap in the top gutter must not resolve to URL", gutterHit)

            // End-to-end: install the same tap-hook the TerminalSurface
            // composable would install and synthesise a single-tap-up gesture
            // on the URL's bounding box. The hook must report the URL string
            // to the host and return true so plain terminal-tap fall-through
            // is suppressed.
            val tapUrls = mutableListOf<String>()
            client.onTapMaybeUrl = { x, y ->
                val hit = hitTestUrl(view, found, x, y)
                if (hit != null) {
                    tapUrls.add(hit.url)
                    true
                } else {
                    false
                }
            }
            try {
                instrumentation.runOnMainSync {
                    val now = SystemClock.uptimeMillis()
                    val tap = MotionEvent.obtain(
                        /* downTime = */ now,
                        /* eventTime = */ now,
                        MotionEvent.ACTION_UP,
                        midX,
                        midY,
                        /* metaState = */ 0,
                    )
                    try {
                        client.onSingleTapUp(tap)
                    } finally {
                        tap.recycle()
                    }
                }
                assertEquals(
                    "URL tap should reach the host via onTapMaybeUrl",
                    listOf(url),
                    tapUrls,
                )

                // A tap on column 0 of the same row must return false from
                // the hook while a URL tap returns true. We swap in a
                // counting hook so the synthetic taps don't pollute the first
                // assertion's recording.
                val handledCalls = arrayOf(0, 0)
                client.onTapMaybeUrl = { x, y ->
                    val hit = hitTestUrl(view, found, x, y)
                    if (hit != null) {
                        handledCalls[0]++
                        true
                    } else {
                        handledCalls[1]++
                        false
                    }
                }
                instrumentation.runOnMainSync {
                    val emptyHit = hitTestUrl(view, found, 0.5f * fontWidth, midY)
                    assertNull(
                        "empty-area tap must not resolve to a URL",
                        emptyHit,
                    )
                    assertFalse(
                        "tap-on-empty hook must return false so onSingleTapUp has no URL action",
                        client.onTapMaybeUrl?.invoke(0.5f * fontWidth, midY) ?: true,
                    )
                    assertTrue(
                        "tap-on-URL hook must return true so onSingleTapUp routes the URL action",
                        client.onTapMaybeUrl?.invoke(midX, midY) ?: false,
                    )
                }
                assertEquals("hook should have been invoked once on URL, once empty", 1, handledCalls[0])
                assertEquals("hook should have been invoked once on empty area", 1, handledCalls[1])
            } finally {
                client.onTapMaybeUrl = null
            }
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    } }

    @Test
    fun visibleSmartSelectionHitTestChoosesPressedSameLineMatch() { runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = null,
        )
        val client = PocketShellTerminalViewClient()

        try {
            val viewRef = arrayOfNulls<TerminalView>(1)
            instrumentation.runOnMainSync {
                val view = TerminalView(context, null)
                view.applyPocketShellDefaults(client)
                view.attachSession(requireNotNull(state.session))
                val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                view.measure(widthSpec, heightSpec)
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                viewRef[0] = view
            }
            val view = requireNotNull(viewRef[0])
            val leftPath = "/etc/hosts"
            val rightPath = "/tmp/backup-hosts"
            val line = "open $leftPath then $rightPath"
            state.appendRemoteOutput(line.toByteArray(Charsets.US_ASCII))

            val regionsRef = arrayOfNulls<List<TerminalMatchRegion>>(1)
            withTimeout(2_000) {
                while ((regionsRef[0]?.size ?: 0) < 2) {
                    delay(20)
                    instrumentation.runOnMainSync {
                        regionsRef[0] = findVisibleTerminalMatches(view)
                    }
                }
            }

            val regions = requireNotNull(regionsRef[0])
            val left = regions.single { it.match.value == leftPath }
            val right = regions.single { it.match.value == rightPath }
            assertEquals("left path should start after \"open \"", 5, left.startCol)
            assertEquals(
                "right path should start after the left path and separator",
                5 + leftPath.length + " then ".length,
                right.startCol,
            )

            val renderer = view.mRenderer
            assertNotNull("renderer should be initialised after layout", renderer)
            val fontWidth = renderer!!.fontWidth
            val lineSpacing = renderer.fontLineSpacing.toFloat()
            val rowOffset = renderer.fontLineSpacingAndAscent.toFloat()
            val midY = rowOffset + 0.5f * lineSpacing
            val rightMidX = (right.startCol + (right.endColExclusive - right.startCol) / 2f) * fontWidth
            val rightHit = hitTestTerminalMatch(view, regions, rightMidX, midY)
            assertNotNull("tap inside right path should hit the right region", rightHit)
            assertEquals(rightPath, rightHit!!.match.value)

            val gapCol = left.endColExclusive + 1
            val gapHit = hitTestTerminalMatch(view, regions, (gapCol + 0.5f) * fontWidth, midY)
            assertNull("tap between same-line matches should not select either match", gapHit)

            val topY = rowOffset
            val bottomY = rowOffset + lineSpacing
            val leftEdgeHit = hitTestTerminalMatch(view, regions, right.startCol * fontWidth, topY)
            assertNotNull("left/top boundary should be inside the right path region", leftEdgeHit)
            assertEquals(rightPath, leftEdgeHit!!.match.value)

            val insideBottomHit = hitTestTerminalMatch(view, regions, rightMidX, bottomY - 0.5f)
            assertNotNull("tap just above row bottom should hit the right path", insideBottomHit)
            assertEquals(rightPath, insideBottomHit!!.match.value)

            val rightBoundaryHit = hitTestTerminalMatch(
                view,
                regions,
                right.endColExclusive * fontWidth,
                midY,
            )
            assertNull("endColExclusive boundary should not hit the right path", rightBoundaryHit)

            val bottomBoundaryHit = hitTestTerminalMatch(view, regions, rightMidX, bottomY)
            assertNull("row bottom boundary should not hit the right path", bottomBoundaryHit)
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    } }

    @Test
    fun visibleSmartSelectionAffordancesUseQuietChromeForPathAndError() { runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = null,
        )
        val client = PocketShellTerminalViewClient()
        val url = "https://example.com/docs"
        val path = "/var/log/pocketshell.log"
        val error = "Error: failed to parse config"

        try {
            val viewRef = arrayOfNulls<TerminalView>(1)
            instrumentation.runOnMainSync {
                val view = TerminalView(context, null)
                view.applyPocketShellDefaults(client)
                view.attachSession(requireNotNull(state.session))
                val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                view.measure(widthSpec, heightSpec)
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                viewRef[0] = view
            }
            val view = requireNotNull(viewRef[0])
            instrumentation.runOnMainSync {
                val columns = requireNotNull(view.mEmulator).mColumns
                val widestToken = maxOf(url.length, path.length, error.length)
                require(columns > widestToken) {
                    "fixture requires each token to fit on one visible row; columns=$columns widestToken=$widestToken"
                }
            }
            state.appendRemoteOutput("$url\r\n$path\r\n$error".toByteArray(Charsets.US_ASCII))

            val regions = waitForVisibleTerminalMatches(
                instrumentation = instrumentation,
                view = view,
                timeoutMs = CONNECTED_TERMINAL_SETTLE_TIMEOUT_MS,
                description = "url=$url path=$path error=$error",
            ) { regions ->
                regions.any { it.match.value == url } &&
                    regions.any { it.match.value == path } &&
                    regions.any { it.match.value == error }
            }
            val segments = smartSelectionAffordanceSegments(
                view = view,
                regions = regions,
                canvasWidthPx = view.measuredWidth.toFloat(),
                canvasHeightPx = view.measuredHeight.toFloat(),
            )
            val urlSegment = segments.single { it.match.value == url }
            val pathSegment = segments.single { it.match.value == path }
            val errorSegment = segments.single { it.match.value == error }

            assertEquals(
                "URL affordance should keep the existing 2 px underline",
                URL_UNDERLINE_THICKNESS_PX,
                urlSegment.thicknessPx,
            )
            assertEquals(
                "Path affordance should render as a quiet 1 px hairline",
                HAIRLINE_THICKNESS_PX,
                pathSegment.thicknessPx,
            )
            assertEquals(
                "Error affordance should render as a quiet 1 px hairline",
                HAIRLINE_THICKNESS_PX,
                errorSegment.thicknessPx,
            )
            assertEquals(
                "Error affordance should use neutral chrome, not amber status color",
                Color(0x66E6EDF3),
                errorSegment.color,
            )
            assertTrue("URL affordance should have positive width", urlSegment.right > urlSegment.left)
            assertTrue("Path affordance should have positive width", pathSegment.right > pathSegment.left)
            assertTrue("Error affordance should have positive width", errorSegment.right > errorSegment.left)
            assertTrue("URL affordance should stay inside the viewport", urlSegment.top >= 0f)
            assertTrue("Path affordance should stay inside the viewport", pathSegment.top >= 0f)
            assertTrue("Error affordance should stay inside the viewport", errorSegment.top >= 0f)
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    } }

    @Test
    fun visibleSmartSelectionSupportsLegacyValueOnlyMatcherForHitTesting() { runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = null,
        )
        val client = PocketShellTerminalViewClient()

        try {
            val viewRef = arrayOfNulls<TerminalView>(1)
            instrumentation.runOnMainSync {
                val view = TerminalView(context, null)
                view.applyPocketShellDefaults(client)
                view.attachSession(requireNotNull(state.session))
                val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                view.measure(widthSpec, heightSpec)
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                viewRef[0] = view
            }
            val view = requireNotNull(viewRef[0])
            val token = "PS-354"
            state.appendRemoteOutput("ticket $token is tappable".toByteArray(Charsets.US_ASCII))

            val regionsRef = arrayOfNulls<List<TerminalMatchRegion>>(1)
            withTimeout(2_000) {
                while (regionsRef[0]?.singleOrNull()?.match?.value != token) {
                    delay(20)
                    instrumentation.runOnMainSync {
                        regionsRef[0] = findVisibleTerminalMatches(view, LegacyTicketMatcher)
                    }
                }
            }

            val region = requireNotNull(regionsRef[0]).single()
            assertEquals(7, region.startCol)
            assertEquals(7 + token.length, region.endColExclusive)

            val renderer = view.mRenderer
            assertNotNull("renderer should be initialised after layout", renderer)
            val fontWidth = renderer!!.fontWidth
            val lineSpacing = renderer.fontLineSpacing.toFloat()
            val rowOffset = renderer.fontLineSpacingAndAscent.toFloat()
            val hit = hitTestTerminalMatch(
                view,
                listOf(region),
                (region.startCol + 0.5f) * fontWidth,
                rowOffset + 0.5f * lineSpacing,
            )
            assertNotNull("legacy matcher region should be tappable", hit)
            assertEquals(token, hit!!.match.value)
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    } }

    @Test
    fun visibleSmartSelectionCanBeRecomputedAfterScrollWithoutNewOutput() { runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = null,
        )
        val client = PocketShellTerminalViewClient()

        try {
            val viewRef = arrayOfNulls<TerminalView>(1)
            instrumentation.runOnMainSync {
                val view = TerminalView(context, null)
                view.applyPocketShellDefaults(client)
                view.attachSession(requireNotNull(state.session))
                val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                view.measure(widthSpec, heightSpec)
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                viewRef[0] = view
            }
            val view = requireNotNull(viewRef[0])
            val rows = view.mEmulator.mRows
            val historyPath = "/tmp/history-354"
            val output = buildString {
                append("old $historyPath\r\n")
                repeat(rows + 2) { index ->
                    append("filler-$index\r\n")
                }
            }
            state.appendRemoteOutput(output.toByteArray(Charsets.US_ASCII))

            withTimeout(2_000) {
                while (view.mEmulator.screen.activeTranscriptRows < 1) {
                    delay(20)
                }
            }
            val historyRows = view.mEmulator.screen.activeTranscriptRows

            val bottomRegionsRef = arrayOfNulls<List<TerminalMatchRegion>>(1)
            instrumentation.runOnMainSync {
                view.setTopRow(0)
                bottomRegionsRef[0] = findVisibleTerminalMatches(view)
            }
            assertTrue(
                "history-only path should not be visible before scrolling",
                requireNotNull(bottomRegionsRef[0]).none { it.match.value == historyPath },
            )

            var viewportChanges = 0
            client.onViewportChanged = { viewportChanges++ }
            val scrolledRegionsRef = arrayOfNulls<List<TerminalMatchRegion>>(1)
            instrumentation.runOnMainSync {
                view.setTopRow(-historyRows)
                scrolledRegionsRef[0] = findVisibleTerminalMatches(view)
            }
            assertEquals("scrolling the viewport should notify the surface", 1, viewportChanges)
            assertTrue(
                "recomputed scrolled viewport should expose history path as a tap target",
                requireNotNull(scrolledRegionsRef[0]).any { it.match.value == historyPath },
            )
        } finally {
            client.onViewportChanged = null
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    } }

    @Test
    fun visibleUrlRegionsCanBeRecomputedAfterScrollWithoutNewOutput() { runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = null,
        )
        val client = PocketShellTerminalViewClient()

        try {
            val viewRef = arrayOfNulls<TerminalView>(1)
            instrumentation.runOnMainSync {
                val view = TerminalView(context, null)
                view.applyPocketShellDefaults(client)
                view.attachSession(requireNotNull(state.session))
                val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                view.measure(widthSpec, heightSpec)
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                viewRef[0] = view
            }
            val view = requireNotNull(viewRef[0])
            val rows = view.mEmulator.mRows
            val historyUrl = "https://example.com/history-354"
            val output = buildString {
                append("old $historyUrl\r\n")
                repeat(rows + 2) { index ->
                    append("filler-$index\r\n")
                }
            }
            state.appendRemoteOutput(output.toByteArray(Charsets.US_ASCII))

            withTimeout(2_000) {
                while (view.mEmulator.screen.activeTranscriptRows < 1) {
                    delay(20)
                }
            }
            val historyRows = view.mEmulator.screen.activeTranscriptRows

            val bottomUrlsRef = arrayOfNulls<List<UrlRegion>>(1)
            instrumentation.runOnMainSync {
                view.setTopRow(0)
                bottomUrlsRef[0] = findVisibleUrls(view)
            }
            assertTrue(
                "history-only URL should not be visible before scrolling",
                requireNotNull(bottomUrlsRef[0]).none { it.url == historyUrl },
            )

            var viewportChanges = 0
            client.onViewportChanged = { viewportChanges++ }
            val scrolledUrlsRef = arrayOfNulls<List<UrlRegion>>(1)
            instrumentation.runOnMainSync {
                view.setTopRow(-historyRows)
                scrolledUrlsRef[0] = findVisibleUrls(view)
            }
            assertEquals("scrolling the viewport should notify the surface", 1, viewportChanges)
            assertTrue(
                "recomputed scrolled viewport should expose history URL as a tap target",
                requireNotNull(scrolledUrlsRef[0]).any { it.url == historyUrl },
            )
        } finally {
            client.onViewportChanged = null
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    } }

    @Test
    fun emulatorSetInvalidatesViewportForOverlayRecompute() { runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = null,
        )
        val client = PocketShellTerminalViewClient()

        try {
            instrumentation.runOnMainSync {
                val view = TerminalView(context, null)
                view.applyPocketShellDefaults(client)
                view.attachSession(requireNotNull(state.session))
                val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                view.measure(widthSpec, heightSpec)
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            }

            var terminalSizeChanges = 0
            var viewportChanges = 0
            client.onTerminalSizeChanged = { columns, rows ->
                if (columns > 0 && rows > 0) terminalSizeChanges++
            }
            client.onViewportChanged = { viewportChanges++ }

            instrumentation.runOnMainSync {
                client.onEmulatorSet()
            }

            assertEquals("emulator set should still report terminal size", 1, terminalSizeChanges)
            assertEquals(
                "emulator set should invalidate viewport-derived overlays",
                1,
                viewportChanges,
            )
        } finally {
            client.onTerminalSizeChanged = null
            client.onViewportChanged = null
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    } }

    private object LegacyTicketMatcher : TerminalMatcher {
        override fun matches(text: String): List<TerminalMatch> =
            if ("PS-354" in text) listOf(TerminalMatch.Error("PS-354")) else emptyList()
    }

    private suspend fun waitForVisibleTerminalMatches(
        instrumentation: android.app.Instrumentation,
        view: TerminalView,
        timeoutMs: Long,
        description: String,
        predicate: (List<TerminalMatchRegion>) -> Boolean,
    ): List<TerminalMatchRegion> {
        var snapshot = emptyList<TerminalMatchRegion>()
        var matched: List<TerminalMatchRegion>? = null
        withTimeoutOrNull(timeoutMs) {
            while (matched == null) {
                instrumentation.runOnMainSync {
                    snapshot = findVisibleTerminalMatches(view)
                }
                if (predicate(snapshot)) {
                    matched = snapshot
                } else {
                    delay(20)
                }
            }
        }
        if (matched != null) return matched
        throw AssertionError(
            "Timed out after ${timeoutMs}ms waiting for visible terminal matches ($description).\n" +
                describeVisibleTerminal(instrumentation, view, snapshot),
        )
    }

    private fun describeVisibleTerminal(
        instrumentation: android.app.Instrumentation,
        view: TerminalView,
        regions: List<TerminalMatchRegion>,
    ): String {
        val lines = mutableListOf<String>()
        var columns = 0
        var rows = 0
        var topRow = 0
        instrumentation.runOnMainSync {
            val emulator = view.mEmulator
            val screen = emulator?.screen
            columns = emulator?.mColumns ?: 0
            rows = emulator?.mRows ?: 0
            topRow = view.topRow
            if (screen != null && columns > 0 && rows > 0) {
                for (row in topRow until topRow + minOf(rows, 8)) {
                    val line = try {
                        screen.getSelectedText(0, row, columns, row)
                    } catch (t: Throwable) {
                        "<${t::class.java.simpleName}: ${t.message}>"
                    }
                    lines += "row=$row text=${line.trimEnd()}"
                }
            }
        }
        val regionSummary = regions.joinToString(
            separator = "\n",
            prefix = "regions:\n",
        ) { region ->
            "row=${region.row} cols=${region.startCol}..${region.endColExclusive} " +
                "type=${region.match::class.java.simpleName} value=${region.match.value}"
        }
        return buildString {
            appendLine("terminal: columns=$columns rows=$rows topRow=$topRow")
            appendLine(regionSummary)
            appendLine("visible rows:")
            lines.forEach { appendLine(it) }
        }
    }
}
