package com.pocketshell.core.terminal.selection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #1233 — LOAD-BEARING unit proof: a shell / non-agent terminal pane must
 * extract the visible viewport AT MOST ONCE per coalesced frame for its affordance
 * scanners, instead of the FOUR independent full-viewport extractions the four
 * per-frame on-main scanners (URL scan + SmartSelection + FilePath +
 * EngineCommand overlays) each performed before the fix.
 *
 * ## The before/after this pins (RenderFrameCoalescerTest precedent)
 *
 * - [preFixShellPaneExtractsViewportFourTimesPerFrame] models the DELETED shell-pane
 *   wiring: each of the four scanners ran its own `findVisible*` = its own viewport
 *   extraction, so ONE render frame drove FOUR full-viewport extractions on the
 *   main thread. This pins the redundant per-frame cost (the milder-#796 root).
 * - [consolidatedShellScannerExtractsViewportOncePerFrame] drives the PRODUCTION
 *   [collectShellPaneAffordances] loop (the body of [ShellPaneAffordanceOverlay])
 *   with a counting extractor and asserts ONE render frame drives exactly ONE
 *   extraction — while STILL producing all four affordance region lists (URL /
 *   file-path / smart-selection / engine-command), so detection is unchanged.
 * - [consolidatedShellScannerExtractsOncePerFrameAcrossManyFrames] extends that to
 *   N frames -> N extractions (1 per frame), never 4N.
 *
 * The counting fake is the injected `extractSnapshot: () -> ViewportRowsSnapshot`
 * seam — the exact thing #1233's acceptance calls a "fake that counts viewport-text
 * extractions per frame".
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ShellPaneAffordanceScanCountTest {

    /**
     * A realistic one-row viewport carrying all four affordance token kinds so
     * every pass detects something (proving the single-snapshot path feeds each
     * scanner, not just that it runs once):
     *  - a schemed URL, a rooted file path (`.txt`), and an engine command
     *    (`/clear`) — the URL/path also register as smart-selection matches.
     */
    private fun sampleSnapshot(): ViewportRowsSnapshot =
        ViewportRowsSnapshot(
            rows = listOf(
                VisualRow(
                    row = 0,
                    text = "open https://example.com/foo see /home/me/out/report.txt run /clear",
                    wrapsToNext = false,
                ),
            ),
            columns = 80,
        )

    private val knownCommands = setOf("/clear")

    @Test
    fun preFixShellPaneExtractsViewportFourTimesPerFrame() {
        // Model the pre-#1233 shell-pane wiring: the URL scan + SmartSelection +
        // FilePath + EngineCommand overlays EACH re-extracted the full viewport
        // themselves (each `findVisible*` = one `extractVisibleViewportRows`) on the
        // SAME render frame. So ONE frame drove FOUR independent extractions.
        val extractCount = AtomicInteger(0)
        val snapshot = sampleSnapshot()
        val extract: () -> ViewportRowsSnapshot = {
            extractCount.incrementAndGet()
            snapshot
        }

        // The four scanners, exactly as the four deleted overlays ran them — each
        // from its OWN extraction.
        val urls = urlRegionsForRows(extract().rows, snapshot.columns)
        val paths = filePathRegionsForRows(extract().rows, snapshot.columns)
        val matches = terminalMatchRegionsForRows(extract().rows, snapshot.columns, DefaultTerminalMatcher())
        val commands = engineCommandRegionsForRows(extract().rows, snapshot.columns, knownCommands)

        assertEquals(
            "pre-#1233 shell pane: the four independent scanners each re-extract the " +
                "viewport, so ONE frame drives FOUR full-viewport extractions on Main",
            4,
            extractCount.get(),
        )
        // Sanity: the sample viewport genuinely carries all four token kinds, so the
        // green single-snapshot test below is not vacuous.
        assertTrue("sample must contain a URL", urls.any { it.url == "https://example.com/foo" })
        assertTrue("sample must contain a file path", paths.any { it.path == "/home/me/out/report.txt" })
        assertTrue("sample must contain smart-selection matches", matches.isNotEmpty())
        assertTrue("sample must contain the /clear engine command", commands.any { it.command == "/clear" })
    }

    @Test
    fun consolidatedShellScannerExtractsViewportOncePerFrame() = runTest {
        val extractCount = AtomicInteger(0)
        val snapshot = sampleSnapshot()
        val results = mutableListOf<ShellPaneAffordanceRegions>()
        val frames = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
        val dispatcher = StandardTestDispatcher(testScheduler)

        val job = launch(dispatcher) {
            collectShellPaneAffordances(
                renderRequests = frames,
                extractSnapshot = {
                    extractCount.incrementAndGet()
                    snapshot
                },
                matcher = DefaultTerminalMatcher(),
                knownCommands = knownCommands,
                scanUrls = true,
                scanFilePaths = true,
                mainContext = dispatcher,
                scanContext = dispatcher,
                onResult = { results += it },
            )
        }

        // The `onStart { emit(Unit) }` initial frame: ONE frame -> ONE extraction.
        advanceUntilIdle()

        assertEquals(
            "#1233: ONE coalesced frame must drive exactly ONE viewport extraction " +
                "for ALL four affordance scanners (was 4 pre-fix)",
            1,
            extractCount.get(),
        )
        assertEquals("one frame publishes one affordance result", 1, results.size)

        // Behavior UNCHANGED: the single snapshot fed all four passes — every
        // affordance kind is still detected.
        val regions = results.single()
        assertTrue(
            "URL still detected from the single snapshot",
            regions.urls.any { it.url == "https://example.com/foo" },
        )
        assertTrue(
            "file path still detected from the single snapshot",
            regions.filePaths.any { it.path == "/home/me/out/report.txt" },
        )
        assertTrue(
            "smart-selection matches still detected from the single snapshot",
            regions.matches.isNotEmpty(),
        )
        assertTrue(
            "engine command still detected from the single snapshot",
            regions.engineCommands.any { it.command == "/clear" },
        )

        job.cancel()
    }

    @Test
    fun consolidatedShellScannerExtractsOncePerFrameAcrossManyFrames() = runTest {
        val extractCount = AtomicInteger(0)
        val snapshot = sampleSnapshot()
        val results = mutableListOf<ShellPaneAffordanceRegions>()
        val frames = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
        val dispatcher = StandardTestDispatcher(testScheduler)

        val job = launch(dispatcher) {
            collectShellPaneAffordances(
                renderRequests = frames,
                extractSnapshot = {
                    extractCount.incrementAndGet()
                    snapshot
                },
                matcher = DefaultTerminalMatcher(),
                knownCommands = knownCommands,
                scanUrls = true,
                scanFilePaths = true,
                mainContext = dispatcher,
                scanContext = dispatcher,
                onResult = { results += it },
            )
        }
        advanceUntilIdle() // initial onStart frame (extraction 1)

        // Emit each render frame with a settle between them so `conflate` does not
        // collapse them — one distinct frame at a time.
        val extraFrames = 5
        repeat(extraFrames) {
            frames.emit(Unit)
            advanceUntilIdle()
        }

        // 1 (initial) + extraFrames distinct frames = one extraction PER frame,
        // never 4 per frame.
        assertEquals(
            "#1233: each coalesced frame drives exactly ONE extraction (N frames -> N " +
                "extractions), never 4N",
            1 + extraFrames,
            extractCount.get(),
        )
        assertTrue("each frame published a result", results.size >= 1 + extraFrames)

        job.cancel()
    }
}
