package com.pocketshell.core.terminal.ui

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.terminal.selection.UrlRegion
import com.pocketshell.core.terminal.selection.ViewportRowsSnapshot
import com.pocketshell.core.terminal.selection.VisualRow
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Issue #558: stale painted regions must never route in another viewport. */
@RunWith(RobolectricTestRunner::class)
class PublishedTapAffordancesTest {

    @Test
    fun sameViewSessionRebindRequiresFreshPublicationEvenForIdenticalViewport() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = TerminalSurfaceState()
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val firstJob = first.attachExternalProducer(
            firstScope,
            MutableSharedFlow<ByteArray>(extraBufferCapacity = 1),
            null,
        )
        val second = TerminalSurfaceState()
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val secondJob = second.attachExternalProducer(
            secondScope,
            MutableSharedFlow<ByteArray>(extraBufferCapacity = 1),
            null,
        )
        try {
            val view = laidOutView(context, first)
            val viewport = ViewportRowsSnapshot(
                rows = listOf(VisualRow(row = 0, text = "https://example.com/same", wrapsToNext = false)),
                columns = 24,
            )
            val urls = listOf(UrlRegion("https://example.com/same", 0, 0, 24))
            val holder = PublishedTapAffordances().apply {
                publish(view, requireNotNull(first.session), viewport, urls, emptyList(), emptyList())
            }

            assertNotNull(holder.currentFor(view, viewport))

            view.attachSession(null)
            assertNull(
                "session A publication must not survive the same view detaching",
                holder.currentFor(view, viewport),
            )

            view.attachSession(requireNotNull(second.session))
            assertNull(
                "session A publication must not route in byte-identical session B viewport",
                holder.currentFor(view, viewport),
            )

            holder.publish(view, requireNotNull(second.session), viewport, urls, emptyList(), emptyList())
            assertNotNull(
                "session B becomes tappable only after earning its own publication",
                holder.currentFor(view, viewport),
            )
        } finally {
            firstJob.cancel()
            secondJob.cancel()
            firstScope.cancel()
            secondScope.cancel()
            first.detachExternalProducer()
            second.detachExternalProducer()
        }
    }

    @Test
    fun publicationIsBoundToExactViewAndViewportGeneration() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = TerminalSurfaceState()
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val firstJob = first.attachExternalProducer(
            firstScope,
            MutableSharedFlow<ByteArray>(extraBufferCapacity = 1),
            null,
        )
        val second = TerminalSurfaceState()
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val secondJob = second.attachExternalProducer(
            secondScope,
            MutableSharedFlow<ByteArray>(extraBufferCapacity = 1),
            null,
        )
        try {
            val firstView = laidOutView(context, first)
            val viewport = ViewportRowsSnapshot(
                rows = listOf(
                    VisualRow(row = 0, text = "https://example.com/", wrapsToNext = true),
                    VisualRow(row = 1, text = "one", wrapsToNext = false),
                ),
                columns = 24,
            )
            val holder = PublishedTapAffordances().apply {
                publish(
                    view = firstView,
                    session = requireNotNull(first.session),
                    viewport = viewport,
                    urls = listOf(UrlRegion("https://example.com/one", 0, 0, 23)),
                    filePaths = emptyList(),
                    engineCommands = emptyList(),
                )
            }

            assertNotNull(
                "the exact published view+viewport must be tappable",
                holder.currentFor(firstView, viewport),
            )

            val secondView = laidOutView(context, second)
            assertNull(
                "a replacement TerminalView must not inherit old coordinates",
                holder.currentFor(secondView, viewport),
            )

            val scrolledViewport = viewport.copy(
                rows = viewport.rows.map { it.copy(row = it.row - 1) },
            )
            assertNull(
                "a scroll generation must invalidate old painted coordinates",
                holder.currentFor(firstView, scrolledViewport),
            )
            val changedViewport = viewport.copy(
                rows = viewport.rows.mapIndexed { index, row ->
                    if (index == viewport.rows.lastIndex) row.copy(text = row.text + "changed") else row
                },
            )
            assertNull(
                "new terminal output must invalidate the old viewport generation",
                holder.currentFor(firstView, changedViewport),
            )
        } finally {
            firstJob.cancel()
            secondJob.cancel()
            firstScope.cancel()
            secondScope.cancel()
            first.detachExternalProducer()
            second.detachExternalProducer()
        }
    }

    private fun laidOutView(context: Context, state: TerminalSurfaceState): TerminalView =
        TerminalView(context, null).also { view ->
            view.applyPocketShellDefaults(PocketShellTerminalViewClient())
            view.attachSession(requireNotNull(state.session))
            view.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        }
}
