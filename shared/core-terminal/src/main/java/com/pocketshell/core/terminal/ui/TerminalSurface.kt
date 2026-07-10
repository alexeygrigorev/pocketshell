package com.pocketshell.core.terminal.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.pocketshell.core.terminal.selection.AgentPaneAffordanceOverlay
import com.pocketshell.core.terminal.selection.EngineCommandRegion
import com.pocketshell.core.terminal.selection.FilePathRegion
import com.pocketshell.core.terminal.selection.SelectionOverlay
import com.pocketshell.core.terminal.selection.ShellPaneAffordanceOverlay
import com.pocketshell.core.terminal.selection.safeLayoutDimension
import com.pocketshell.core.terminal.selection.TerminalMatch
import com.pocketshell.core.terminal.selection.TerminalMatchRegion
import com.pocketshell.core.terminal.selection.UrlRegion
import com.pocketshell.core.terminal.selection.hitTestEngineCommand
import com.pocketshell.core.terminal.selection.hitTestFilePath
import com.pocketshell.core.terminal.selection.hitTestUrl
import com.termux.terminal.TextStyle
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background colour applied to the [TerminalView]'s parent surface. This is
 * the terminal-specific near-black token rather than the app chrome colour;
 * keeping terminal canvas and default VT background aligned avoids navy
 * gutters and makes the surface read as a real SSH terminal.
 */
val DefaultTerminalBackground: Color = Color(TERMINAL_BACKGROUND_ARGB)

internal const val TERMINAL_BACKGROUND_ARGB: Int = -0x00FEFBF7 // 0xFF010409
internal const val TERMINAL_FOREGROUND_ARGB: Int = -0x0019120D // 0xFFE6EDF3
internal const val TERMINAL_CURSOR_ARGB: Int = -0x00DD2C12 // 0xFF22D3EE

/**
 * Default text size used by the embedded [TerminalView], expressed in **raw
 * device pixels** (not sp, not dp, not CSS px).
 *
 * Why raw pixels: the vendored [TerminalView.setTextSize] accepts an `int`
 * and passes it straight to [android.graphics.Paint.setTextSize], whose
 * `textSize` parameter is documented in pixels. There is no
 * `TypedValue.applyDimension` density conversion anywhere in the upstream
 * path, so whatever number we pass in here is the number of physical pixels
 * the renderer hands to the [android.graphics.Paint] used to draw each
 * glyph. Upstream Termux's javadoc on `setTextSize` says "density-independent
 * pixels" but that is inaccurate for the code as it actually runs — confirmed
 * by reading [com.termux.view.TerminalRenderer]'s constructor.
 *
 * Why 28: issue #98's phone reference needs larger, more deliberate terminal
 * typography than the previous 24 px default, but 30+ px drops the phone
 * viewport to a cramped column count for real agent CLIs. 28 px keeps command
 * output readable while preserving a usable grid on 1080 px wide devices.
 *
 * Suffixed `_RAW_PX` (not just `_PX`) to make the unit unambiguous in IDE
 * autocomplete and search results.
 */
internal const val DEFAULT_TEXT_SIZE_RAW_PX: Int = 28

/**
 * Asset path (relative to the APK's `assets/` root) for the bundled
 * JetBrainsMono Regular TTF used by the terminal renderer. Vendored under
 * this module's own `src/main/assets/fonts/` so a host that depends on
 * `core-terminal` always has the font available without needing
 * `:shared:ui-kit` on its classpath — and so emulator instrumentation
 * tests run inside `:shared:core-terminal` itself (e.g.
 * [TerminalRendererBoldCellPinningInstrumentedTest]) can load the same
 * face the production app uses.
 *
 * See issue #241 — switching the terminal typeface from the system
 * default monospace to JetBrainsMono Regular gives a tighter, more
 * deliberate glyph that pairs with the new cell-height metric in
 * [com.termux.view.TerminalRenderer].
 */
internal const val TERMINAL_FONT_ASSET_PATH: String = "fonts/JetBrainsMono-Regular.ttf"

/**
 * Loads the bundled JetBrainsMono Regular [Typeface] for the embedded
 * terminal. Falls back to the system monospace if the asset is somehow
 * missing (e.g. a stripped APK in a misconfigured build) — the fallback
 * preserves a usable terminal rather than crashing on the IO error a
 * direct [Typeface.createFromAsset] would throw.
 *
 * Cached on [Context.getApplicationContext] so all [TerminalView]
 * instances share one face; [Typeface.createFromAsset] parses the TTF on
 * every call otherwise.
 */
private var cachedTerminalTypeface: Typeface? = null
internal fun terminalTypeface(context: Context): Typeface {
    val cached = cachedTerminalTypeface
    if (cached != null) return cached
    val loaded = runCatching {
        Typeface.createFromAsset(context.applicationContext.assets, TERMINAL_FONT_ASSET_PATH)
    }.getOrNull() ?: Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    cachedTerminalTypeface = loaded
    return loaded
}

/**
 * Clipboard label used when the terminal selection-action mode's COPY action
 * pushes text into the system clipboard. Not shown to the user on modern
 * Android but surfaced to accessibility services. Kept short and identifiable
 * so a clipboard-manager surface (e.g. Gboard's clipboard history) can label
 * the entry as "from PocketShell terminal".
 */
internal const val CLIPBOARD_LABEL_TERMINAL: String = "PocketShell terminal"

/**
 * Maximum length of the text included in the "Copied: …" toast after a COPY
 * action. A multi-line selection of a stack trace can be hundreds of bytes;
 * truncating to a sane prefix keeps the toast on-screen for a single line.
 */
internal const val TOAST_PREVIEW_CHARS: Int = 60

/**
 * Fire `Intent.ACTION_VIEW` for [url] from [context]. We add
 * `FLAG_ACTIVITY_NEW_TASK` so the call is safe from a non-activity context
 * (the LocalContext in a Compose surface is typically an
 * `androidx.activity.ComponentActivity`, but a configuration-change
 * recomposition can briefly hand us the application context). If no
 * activity can handle the URL — the user has no browser installed, or
 * Android's verified-link policy denies access — fall back to copying the
 * URL to the system clipboard so the user can paste it elsewhere.
 *
 * We deliberately catch [android.content.ActivityNotFoundException] rather
 * than pre-checking with `resolveActivity`, because the pre-check requires
 * the `QUERY_ALL_PACKAGES` permission on API 30+. The exception path is
 * cheap on the common case where a browser is present.
 */
public fun openUrlWithFallback(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: android.content.ActivityNotFoundException) {
        val clipboard = context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(CLIPBOARD_LABEL_TERMINAL, url))
        Toast.makeText(context.applicationContext, "Copied URL: $url", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Hosts the vendored [TerminalView] inside a Compose tree via
 * [AndroidView] interop.
 *
 * The surface is a thin Compose wrapper — it does not own the session,
 * does not parse output, and does not render any UI chrome (breadcrumbs,
 * keybar, chips are all Phase 1). Its only jobs are:
 *
 * 1. Construct one [TerminalView] per composition slot and reuse it across
 *    recompositions.
 * 2. Apply the design-language background colour and Android's deterministic
 *    monospace typeface.
 * 3. Bind the [TerminalView] to whatever [TerminalSession] the [state]
 *    currently holds, attaching / detaching as the state changes.
 * 4. Wire the [TerminalView]'s required [TerminalViewClient] to a sane
 *    no-op default so the view does not NPE during input or measurement.
 *
 * @param state Compose-friendly state holder for the session and its I/O.
 *   Get one via [rememberTerminalSurfaceState].
 * @param modifier Standard Compose modifier (size, padding, etc.). The
 *   surface defaults to filling its parent; the caller controls layout
 *   via this modifier.
 * @param onKeyEvent Optional Compose-level key-event forwarder. When
 *   non-`null`, this callback is invoked for every key event that the
 *   Compose focus system routes to the surface (typically the design-
 *   language key bar dispatching modifier-bar codes). Return `true` to
 *   mark the event consumed; `false` lets it propagate to the underlying
 *   [TerminalView]. The vendored [TerminalView] has its own
 *   [android.view.View.dispatchKeyEvent] path; this slot exists so callers
 *   can intercept at the Compose layer without reaching the View directly.
 *   Defaults to `null`, which is equivalent to "don't consume — fall
 *   through to the View".
 *
 * ## JNI deferral
 *
 * Per `VENDORED.md` — this issue does NOT compile `libtermux.so`. The
 * [TerminalView] reaches into JNI only via
 * [TerminalSession.updateSize] → [TerminalSession.initializeEmulator],
 * which is gated on `state.session != null` and on the view receiving a
 * non-zero size. Until #9 wires a real session source, callers should
 * leave [TerminalSurfaceState] unattached; the surface still composes and
 * lays out cleanly, rendering as the default solid-black [TerminalView]
 * canvas.
 *
 * @see TerminalSurfaceState
 * @see rememberTerminalSurfaceState
 */
@Composable
fun TerminalSurface(
    state: TerminalSurfaceState,
    modifier: Modifier = Modifier,
    terminalKeyboardMode: TerminalKeyboardMode = TerminalKeyboardMode.RawCommand,
    matchListener: ((TerminalMatch) -> Unit)? = null,
    onTerminalSizeChanged: ((columns: Int, rows: Int) -> Unit)? = null,
    onLocalTerminalError: ((Throwable) -> Unit)? = null,
    onKeyEvent: ((ComposeKeyEvent) -> Boolean)? = null,
    onUrlTap: ((String) -> Unit)? = null,
    urlsEnabled: Boolean = true,
    // Issue #500: when non-null, file paths detected on the visible viewport
    // become tappable and the tapped path (verbatim, project-relative paths
    // resolved by the host against the session cwd) is delivered here. When
    // null, file-path detection is off and only URLs are tappable.
    onFilePathTap: ((String) -> Unit)? = null,
    // Issue #770: the set of valid slash-commands for the pane's detected engine
    // (from `AgentCommandCatalog`). When non-empty AND [onEngineCommandTap] is
    // non-null, any of these the agent rendered in the terminal become tappable;
    // a tap delivers the command verbatim (e.g. `/clear`) here so the host can
    // open the composer pre-filled with it. An empty set or a null callback
    // disables engine-command detection entirely.
    engineCommands: Set<String> = emptySet(),
    onEngineCommandTap: ((String) -> Unit)? = null,
    // Issue #796 (REOPENED): when `false`, the four per-frame full-viewport
    // overlay scanners (URL / smart-selection-match / file-path / engine-command)
    // are NOT wired at all for this surface — only the cheap #803-paced repaint
    // runs. This is set `false` for an interactive-agent pane (the #679 session
    // tree's agentKind/cgroup signal), whose tappable affordances now live in the
    // Conversation view (#809/#818, the default agent view). The four scanners
    // re-extract every visible row via `screen.getSelectedText(...)` and run
    // several regex passes per row on the MAIN thread every frame; during a live
    // Codex `%output` burst with the keyboard up that per-frame regex cost — not
    // the #803-bounded VT-append — is what stalled the main thread into a real
    // ANR. Dropping it for an agent pane removes the dominant per-frame cost at
    // the root. Defaults to `true` so shell / non-agent panes (and every other
    // caller / test) keep full URL/path/command tappability — UNCHANGED. Hard cut
    // per D22: no settings flag, no per-pane "legacy scanner" fallback.
    affordanceScannersEnabled: Boolean = true,
    // Issue #871: an interactive-agent pane (Codex/Claude) keeps tappable file
    // paths and URLs even though [affordanceScannersEnabled] is `false` for it —
    // BUT via an OFF-main, debounced scan ([AgentPaneAffordanceOverlay]), never
    // the per-frame on-main scanners that caused the #803/#866/#796 ANR. Set
    // `true` for an agent pane (the #679 agentKind/cgroup signal); the host wires
    // `onFilePathTap` / `onUrlTap` as usual and a tap routes to the file viewer /
    // browser exactly like a shell pane. The match + engine-command scanners stay
    // off for an agent pane (the conversation view, #818, is its richer surface);
    // only path + URL are restored. Has no effect when [affordanceScannersEnabled]
    // is `true` (a shell pane already runs the full on-main scanners). Defaults to
    // `false` so non-agent callers / tests are UNCHANGED.
    agentPaneLinkAffordancesEnabled: Boolean = false,
) {
    // Hoist the bridge so the same instance survives recompositions and we
    // do not leak listeners across configuration changes. AndroidView's
    // factory runs once; update runs every recomposition.
    val viewClient = remember { PocketShellTerminalViewClient() }
    viewClient.terminalKeyboardMode = terminalKeyboardMode
    viewClient.onTerminalSizeChanged = onTerminalSizeChanged
    viewClient.onTerminalSurfaceError = onLocalTerminalError
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    var viewportTick by remember { mutableStateOf(0L) }
    val desiredSession = state.session

    val context = LocalContext.current

    // Issue #796 (Slice B of #792): frame-gate the redraw signal that drives the
    // main-thread render consumers (the TerminalView repaint AND the per-render
    // full-viewport URL / file-path / engine-command scans). `state.renderRequests`
    // fires once per emulator tick; a Codex `%output` burst makes that O(N)
    // repaints + O(N) viewport scans back-to-back on the UI thread — the
    // keyboard-up ANR (#796). [coalescePerFrame] collapses a burst to ≤1 emission
    // per frame while NEVER dropping the settled final frame (the cursor/spinner
    // state after the burst still paints — its last-frame-after-idle guarantee
    // mirrors LayoutChangeCoalescer). The same coalesced flow feeds the repaint,
    // the URL scan, and the SmartSelection/FilePath/EngineCommand overlays, so
    // every per-render scan is gated, not just the repaint. Remembered per
    // `state` so the operator (and its conflating channel) survives recomposition.
    //
    // Issue #1286: give the frame-budgeted VT-append drain main-thread priority
    // while a `%output` burst is draining. When the drain queue is backlogged
    // (`renderDrainBacklogged()`), the coalescer WIDENS its window to
    // [DRAIN_PRIORITY_WINDOW_MS] so the per-frame repaint (and the affordance
    // extraction the same signal drives) fires ~4× less often — handing the append
    // drain more contiguous main-thread frames to finish the burst instead of the
    // on-main repaint/onDraw starving it into the ANR. It does NOT suppress the
    // repaint: the screen keeps updating (~15fps) during the burst and tappable
    // affordances keep refreshing, so this fixes the freeze AND avoids a
    // frozen/blank-during-burst regression; the settled frame still paints once the
    // drain catches up (the #1286 black-screen face). Reverts to the base ~60fps
    // window the instant the drain drains. Inert without a bridge (plain SSH): the
    // predicate is `false`, so the base window is always used and behaviour is
    // unchanged.
    val coalescedRenderRequests = remember(state) {
        state.renderRequests.coalescePerFrame(
            backlogWindowMs = {
                if (state.renderDrainBacklogged()) DRAIN_PRIORITY_WINDOW_MS else RENDER_FRAME_WINDOW_MS
            },
        )
    }

    LaunchedEffect(terminalKeyboardMode, terminalView) {
        val view = terminalView ?: return@LaunchedEffect
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.restartInput(view)
    }

    // Issue #175 — install the system-clipboard sink for the vendored
    // selection action mode's COPY button. When the user long-presses, drags,
    // and taps Copy, the vendored TextSelectionCursorController calls
    // `session.onCopyTextToClipboard(selectedText)` which routes through the
    // TerminalSessionClient set at session-construction time. By default that
    // client (TerminalSurfaceState.sessionClient) drops the text on the floor;
    // wiring a real sink here is what makes Copy actually copy.
    //
    // We use a DisposableEffect tied to the context (and the app context
    // alone, since the activity context would re-create the closure on
    // every recomposition) so the sink is installed once per composition
    // and detached cleanly when the surface leaves the tree. Toast feedback
    // gives users visual confirmation the copy succeeded.
    DisposableEffect(state, context.applicationContext) {
        val appContext = context.applicationContext
        state.setOnCopySelection { selectedText ->
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText(CLIPBOARD_LABEL_TERMINAL, selectedText))
            // Truncate the toast preview so a multi-line selection does not
            // produce a giant toast — the clipboard still has the full text.
            val preview = if (selectedText.length > TOAST_PREVIEW_CHARS) {
                selectedText.substring(0, TOAST_PREVIEW_CHARS) + "…"
            } else {
                selectedText
            }
            Toast.makeText(appContext, "Copied: $preview", Toast.LENGTH_SHORT).show()
        }
        onDispose { state.setOnCopySelection(null) }
    }

    DisposableEffect(viewClient) {
        viewClient.onViewportChanged = {
            viewportTick += 1
        }
        onDispose { viewClient.onViewportChanged = null }
    }

    DisposableEffect(state, terminalView) {
        val view = terminalView
        state.setSmartTextStagingBridge(
            if (view == null) {
                null
            } else {
                { policy -> view.prepareForRawTerminalInput(policy.toTerminalViewPolicy()) }
            },
        )
        // Issue #1443: bind the DIAGNOSTIC pixel-truth sampler to the live View's
        // bounded PixelCopy. The tmux watchdog's suspicion tick calls
        // state.probePixelBlackWhileModelHasContent() (rate-bounded, off the render
        // path) which invokes this to detect a genuine pixel/GPU-layer black the
        // MODEL-derived detectors cannot see. Diagnostics only — no heal is wired.
        state.setSurfacePixelSampler(
            if (view == null) {
                null
            } else {
                TerminalSurfaceState.SurfacePixelSampler { view.sampleSurfaceNearUniformBlack() }
            },
        )
        onDispose {
            state.setSmartTextStagingBridge(null)
            state.setSurfacePixelSampler(null)
        }
    }

    LaunchedEffect(state, terminalView, coalescedRenderRequests) {
        val view = terminalView ?: return@LaunchedEffect
        // Issue #796: drive the repaint off the FRAME-COALESCED redraw signal
        // (≤1 repaint per frame), not the raw per-emulator-tick `renderRequests`.
        // A Codex `%output` burst no longer turns into O(N) `onScreenUpdated()`
        // repaints on the UI thread. The coalescer never drops the final frame,
        // so the settled cursor/spinner state still paints after the burst.
        //
        // Issue #1260: collect on the Handler-based [Dispatchers.Main.immediate],
        // NOT the LaunchedEffect's default Compose `AndroidUiDispatcher.Main`. The
        // coalescer's `delay(16ms)` window is a timer, and AndroidUiDispatcher
        // batches its dispatch to Choreographer frame boundaries — during a
        // `%output` burst on a surface that is not otherwise invalidating, those
        // frames stop, so the coalescer's delay never resumes and the repaint
        // stalls for the whole burst (only the settled frame paints, at the end).
        // #1216's `renderRequests` `replay = 1` exposed this: the priming
        // emission that used to arrive DURING the burst (and kick the frame loop)
        // now fires at composition on empty content, so nothing re-pumps the
        // frame-gated dispatcher mid-burst. The main-thread Handler dispatcher is
        // not frame-gated, so its `postDelayed` timer fires regardless — the
        // emulator read still runs on the main thread, so this is thread-safe.
        withContext(Dispatchers.Main.immediate) {
            coalescedRenderRequests.collect {
                runCatching { view.onScreenUpdated() }
                    .onFailure { onLocalTerminalError?.invoke(it) }
            }
        }
    }

    // Issue #721: a force-full-repaint signal (emitted on tmux reattach re-seed)
    // means the EXISTING screen content must be redrawn from the buffer, not just
    // the newly-changed rows the #469 dirty-region path would pick. This resets
    // the renderer's dirty cache and issues a full invalidate(), so a reattach
    // RESEED repaints every row regardless of cache state.
    LaunchedEffect(state, terminalView) {
        val view = terminalView ?: return@LaunchedEffect
        // Issue #1286 (black-screen face): collect the reattach/reseed force-repaint
        // on the Handler-based [Dispatchers.Main.immediate], NOT the LaunchedEffect's
        // default frame-gated AndroidUiDispatcher.Main — same rationale as the #1260
        // repaint collector. On a beyond-grace reattach the pane is re-seeded WHILE a
        // `%output` burst may still be draining; the Choreographer frames the
        // frame-gated dispatcher batches to can stall for the whole burst, so the seed
        // repaint would never fire and the freshly seeded rows stay black (the
        // blank-pane-on-attach the maintainer reported, exercised by
        // PreExistingMultiWindowSeedE2eTest). The main-looper immediate dispatcher is
        // not frame-gated, so the seed repaint lands regardless of the burst.
        withContext(Dispatchers.Main.immediate) {
            state.fullRepaintRequests.collect {
                runCatching { view.forceFullRepaint() }
                    .onFailure { onLocalTerminalError?.invoke(it) }
            }
        }
    }

    // Issue #1203: a SURFACE force-repaint signal (the model grid is intact but the
    // on-screen surface is black — the #1192 sixth class the model-vs-tmux oracle
    // cannot see) re-binds the View's emulator and forces a full-clip repaint of
    // what the model already holds. Unlike fullRepaintRequests (which follows a
    // MODEL reseed), this recovers the surface WITHOUT a capture-pane round-trip —
    // the recovery the model reseed is architecturally incapable of providing.
    LaunchedEffect(state, terminalView) {
        val view = terminalView ?: return@LaunchedEffect
        // Issue #1286 (black-screen face): as with [fullRepaintRequests] above, collect
        // the surface force-repaint on the non-frame-gated [Dispatchers.Main.immediate]
        // so a surface-only-black recovery still fires while a `%output` burst has the
        // Choreographer frame loop stalled.
        withContext(Dispatchers.Main.immediate) {
            state.surfaceRepaintRequests.collect {
                runCatching { view.forceSurfaceRepaint() }
                    .onFailure { onLocalTerminalError?.invoke(it) }
            }
        }
    }

    // If the caller installed an onKeyEvent slot, chain it onto the
    // user-supplied modifier so the Compose focus system routes key events
    // through it before the embedded TerminalView gets a chance. We tack
    // the slot on at the end (after the caller's chain) so caller-supplied
    // focus / pointer modifiers run first. The slot defaults to `null`,
    // in which case we leave the modifier untouched — no allocation, no
    // KeyInputModifier added to the tree.
    val keyAwareModifier = if (onKeyEvent != null) {
        modifier.onKeyEvent { event -> onKeyEvent(event) }
    } else {
        modifier
    }

    // Resolve the URL-tap callback once. If the caller did not supply one and
    // URL overlay is enabled, default to firing Intent.ACTION_VIEW from the
    // app context — which is the behaviour every reasonable host would want.
    // Suppressing the overlay entirely (urlsEnabled = false) is what tests
    // and code paths that explicitly do not want URL detection should use.
    val effectiveUrlTap: ((String) -> Unit)? = remember(
        onUrlTap,
        urlsEnabled,
        affordanceScannersEnabled,
        agentPaneLinkAffordancesEnabled,
        context,
    ) {
        if (!affordanceScannersEnabled && !agentPaneLinkAffordancesEnabled) {
            // Issue #796: an agent pane with NO link affordances does not run the
            // URL scanner at all. A null tap disables both the per-frame
            // `findVisibleUrls` scan and the URL hit-test. Issue #871: an agent
            // pane with link affordances enabled (the default agent path now)
            // falls through to the normal resolution below — the URL scan it
            // drives is the OFF-main `AgentPaneAffordanceOverlay`, not the
            // per-frame on-main scan, so the ANR is not reintroduced.
            null
        } else if (!urlsEnabled) {
            null
        } else if (onUrlTap != null) {
            onUrlTap
        } else {
            { url -> openUrlWithFallback(context, url) }
        }
    }

    // The list of URL regions currently visible on the embedded TerminalView.
    // Updated on each render request. The state holder lives in
    // this composable (and not in TerminalSurfaceState) because URLs are a
    // view-coordinate concept, not a transcript-bytes concept — the same
    // session can render different URL sets at different sizes.
    var visibleUrls by remember { mutableStateOf<List<UrlRegion>>(emptyList()) }
    var visibleFilePaths by remember { mutableStateOf<List<FilePathRegion>>(emptyList()) }
    var visibleEngineCommands by remember { mutableStateOf<List<EngineCommandRegion>>(emptyList()) }
    var visibleMatchRegions by remember { mutableStateOf<List<TerminalMatchRegion>>(emptyList()) }
    // Issue #770: engine-command detection is active only when the host both
    // supplies a tap sink and a non-empty command set for the detected engine.
    // Issue #796: an agent pane runs NO per-frame viewport scanner, so the
    // engine-command overlay is off there too (its affordance lives in the
    // Conversation view, #809/#818).
    val engineCommandsEnabled =
        affordanceScannersEnabled && onEngineCommandTap != null && engineCommands.isNotEmpty()
    // Issue #796: the smart-selection match overlay and the file-path overlay are
    // the other two per-frame full-viewport scanners. Gate BOTH on
    // [affordanceScannersEnabled] so an agent pane wires none of the four
    // scanners — only the cheap #803-paced repaint runs. Shell / non-agent panes
    // (default `true`) are unchanged.
    val matchScannerEnabled = affordanceScannersEnabled && matchListener != null
    // The per-frame ON-MAIN file-path overlay is only for a shell / non-agent
    // pane (`affordanceScannersEnabled`). An agent pane never wires it — its
    // file-path scan runs OFF-main via [AgentPaneAffordanceOverlay] below.
    val filePathScannerEnabled = affordanceScannersEnabled && onFilePathTap != null
    // Issue #871: the OFF-main, debounced agent-pane path+URL overlay. Active ONLY
    // for an agent pane (`agentPaneLinkAffordancesEnabled`) when scanners are off.
    // Wires the file-path tap hit-test and drives the URL hit-test snapshot too,
    // restoring tappable paths/URLs without the per-frame on-main ANR cost.
    val agentLinkOverlayEnabled =
        !affordanceScannersEnabled &&
            agentPaneLinkAffordancesEnabled &&
            (onFilePathTap != null || effectiveUrlTap != null)
    // The file-path tap is live when EITHER the shell-pane single-snapshot overlay
    // OR the agent-pane off-main overlay is feeding `visibleFilePaths`.
    val filePathTapActive =
        onFilePathTap != null && (filePathScannerEnabled || agentLinkOverlayEnabled)
    // Issue #1233: the ONE consolidated shell / non-agent affordance overlay
    // ([ShellPaneAffordanceOverlay] below) is wired whenever a shell pane has ANY
    // of the four affordance passes active. It extracts the visible viewport ONCE
    // per coalesced frame and runs the enabled URL / smart-selection / file-path /
    // engine-command regex passes off the main thread — replacing the four
    // independent per-frame on-main scanners that each re-extracted the whole
    // viewport (~4× redundant extraction + regex on Main every frame).
    val shellAffordanceOverlayEnabled =
        affordanceScannersEnabled &&
            (effectiveUrlTap != null || matchScannerEnabled || filePathScannerEnabled || engineCommandsEnabled)

    // Issue #1233: the URL / smart-selection / file-path / engine-command hit-test
    // snapshots (`visibleUrls` / `visibleMatchRegions` / `visibleFilePaths` /
    // `visibleEngineCommands`) are all fed by [ShellPaneAffordanceOverlay] (shell
    // pane) or [AgentPaneAffordanceOverlay] (agent pane) below, each from a SINGLE
    // per-frame viewport extraction — no standalone per-frame URL scan here.

    // Install the tap-hook on the view client every time `visibleUrls`,
    // `terminalView`, or `effectiveUrlTap` changes. The hook receives a tap
    // in view-local pixels and returns true if the tap landed on a URL —
    // PocketShellTerminalViewClient.onSingleTapUp then lets the host handle
    // that gesture and suppresses the plain terminal tap fall-through.
    val engineCommandTap = if (engineCommandsEnabled) onEngineCommandTap else null
    DisposableEffect(
        viewClient,
        terminalView,
        visibleUrls,
        visibleFilePaths,
        visibleEngineCommands,
        effectiveUrlTap,
        onFilePathTap,
        filePathTapActive,
        engineCommandTap,
    ) {
        val view = terminalView
        val tap = effectiveUrlTap
        // The file-path tap is live when EITHER the shell-pane on-main overlay
        // (#500) OR the agent-pane off-main overlay (#871) is feeding
        // `visibleFilePaths`. When neither is, the tap stays inert (its snapshot
        // is empty and the affordance lives in Conversation, #809/#818).
        val pathTapMaybe = if (filePathTapActive) onFilePathTap else null
        if (view == null || (tap == null && pathTapMaybe == null && engineCommandTap == null)) {
            viewClient.onTapMaybeUrl = null
        } else {
            val urlsSnapshot = visibleUrls
            val pathsSnapshot = visibleFilePaths
            val commandsSnapshot = visibleEngineCommands
            val pathTap = pathTapMaybe
            val cmdTap = engineCommandTap
            viewClient.onTapMaybeUrl = { x, y ->
                // URLs first: a URL's `/path` tail is already excluded from
                // file-path detection, but keeping URL precedence here is
                // belt-and-braces and matches the established browser route.
                val urlHit = if (tap != null) hitTestUrl(view, urlsSnapshot, x, y) else null
                if (urlHit != null) {
                    tap?.invoke(urlHit.url)
                    true
                } else {
                    val pathHit = if (pathTap != null) hitTestFilePath(view, pathsSnapshot, x, y) else null
                    if (pathHit != null) {
                        pathTap?.invoke(pathHit.path)
                        true
                    } else {
                        // Issue #770: an engine command (`/clear`) the agent
                        // rendered → open the composer pre-filled with it.
                        val cmdHit = if (cmdTap != null) {
                            hitTestEngineCommand(view, commandsSnapshot, x, y)
                        } else {
                            null
                        }
                        if (cmdHit != null) {
                            cmdTap?.invoke(cmdHit.command)
                            true
                        } else {
                            false
                        }
                    }
                }
            }
        }
        onDispose { viewClient.onTapMaybeUrl = null }
    }

    // Layer the vendored TerminalView under the optional SelectionOverlay
    // and smart-selection affordance overlay. We use
    // `androidx.compose.ui.layout.Layout` rather than
    // `Box` because this module does not depend on `compose-foundation`. The
    // Layout measures each child with the incoming constraints and places
    // them at origin so they perfectly overlap. Painting order matches
    // source order: AndroidView paints first (background), then any
    // SelectionOverlay (above the View), then the affordance overlay on top
    // so token hairlines are visible above other overlays. URL tap-routing
    // happens inside the View's gesture pipeline via
    // [PocketShellTerminalViewClient.onTapMaybeUrl], not in the overlay —
    // see [ShellPaneAffordanceOverlay]'s KDoc for the rationale.
    Layout(
        modifier = keyAwareModifier,
        content = {
            AndroidView(
                factory = { ctx ->
                    TerminalView(ctx, /* attributes = */ null)
                        .applyPocketShellDefaults(viewClient)
                        .also { view ->
                            terminalView = view
                            // Issue #1192: forward each painted frame's outcome (content
                            // vs black fallback) into the state's surface-paint seam so
                            // the tmux watchdog can fingerprint a surface-only-black.
                            view.setFramePaintObserver { paintedContent, atMs ->
                                state.onSurfaceFramePainted(paintedContent, atMs)
                            }
                        }
                },
                update = { view ->
                    terminalView = view
                    val current = view.currentSession
                    // Attach / detach as the state's session reference
                    // changes. `attachSession` early-returns when given the
                    // same instance, so this is idempotent across
                    // recompositions.
                    if (desiredSession != null) {
                        if (desiredSession !== current) {
                            runCatching {
                                view.attachSession(desiredSession)
                                // Issue #721: attaching a session that already
                                // holds buffer content (a tmux session switch via
                                // pager dispose, or any attach with no fresh bytes
                                // pending) updates the emulator the View points at
                                // but does not, on its own, repaint the existing
                                // screen — the #469 dirty cache still assumes the
                                // previous surface pixels. Force a full repaint so
                                // the whole buffer is redrawn on attach, not just
                                // newly-written cells.
                                view.forceFullRepaint()
                            }.onFailure { onLocalTerminalError?.invoke(it) }
                        }
                    } else if (desiredSession !== current) {
                        // No public detach on TerminalView; clear the
                        // field via an attach of an empty marker is not
                        // possible because TerminalSession is `final`
                        // and we cannot construct a sentinel without
                        // driving JNI. The view simply keeps its last
                        // session reference until the next attach. This
                        // is acceptable for #8 — #9 will always have a
                        // fresh session on hand when changing sessions.
                    }
                },
            )
            if (matchScannerEnabled) {
                SelectionOverlay(
                    view = terminalView,
                    regions = visibleMatchRegions,
                    onTap = matchListener,
                )
            }
            // Issue #1233: ONE consolidated shell / non-agent affordance overlay
            // replaces the four independent per-frame on-main scanners (the URL
            // scan + SmartSelectionAffordanceOverlay + FilePathOverlay +
            // EngineCommandOverlay). It extracts the visible viewport ONCE per
            // coalesced frame and runs the (enabled) URL / smart-selection /
            // file-path / engine-command regex passes OFF the main thread against
            // that single snapshot, publishing all four hit-test snapshots. It
            // draws the match + file-path + engine-command hairlines; the URL
            // underline comes from the matcher's TerminalMatch.Url matches (the URL
            // pass feeds tap hit-testing only), exactly as the four overlays did.
            // The matcher pass runs whenever the smart-selection consumer is active
            // OR URLs are tappable (so the URL underline is still drawn), matching
            // the deleted SmartSelectionAffordanceOverlay gate.
            if (shellAffordanceOverlayEnabled) {
                ShellPaneAffordanceOverlay(
                    view = terminalView,
                    renderRequests = coalescedRenderRequests,
                    viewportChangeKey = viewportTick,
                    matcher = if (matchScannerEnabled || effectiveUrlTap != null) {
                        state.currentMatcher()
                    } else {
                        null
                    },
                    knownCommands = if (engineCommandsEnabled) engineCommands else emptySet(),
                    scanUrls = effectiveUrlTap != null,
                    scanFilePaths = filePathScannerEnabled,
                    onUrlsChanged = { visibleUrls = it },
                    onFilePathsChanged = { visibleFilePaths = it },
                    onMatchesChanged = { visibleMatchRegions = it },
                    onEngineCommandsChanged = { visibleEngineCommands = it },
                )
            }
            // Issue #871: an agent pane (Codex/Claude) gets tappable file paths +
            // URLs via the OFF-main, debounced overlay — never the per-frame
            // on-main scan. It feeds BOTH `visibleFilePaths` and `visibleUrls` for
            // the tap hit-test and paints the affordance hairlines. The match +
            // engine-command scanners stay off for an agent pane (Conversation,
            // #818, is the richer surface).
            if (agentLinkOverlayEnabled) {
                AgentPaneAffordanceOverlay(
                    view = terminalView,
                    renderRequests = coalescedRenderRequests,
                    viewportChangeKey = viewportTick,
                    onFilePathsChanged = { visibleFilePaths = it },
                    onUrlsChanged = { visibleUrls = it },
                )
            }
        },
    ) { measurables, constraints ->
        // Measure each child with the full incoming constraints and stack
        // them at (0, 0). The first child is the AndroidView; subsequent
        // children (the overlay, if present) are placed on top in source
        // order, exactly as `Box` would do for centred / aligned children.
        val placeables = measurables.map { it.measure(constraints) }
        // Issue #966/#967: under the pager's intermittent UNBOUNDED-dimension
        // lookahead pass a child can report a huge measured size; forwarding it
        // straight into `layout(...)` would throw the `Size(W x Int.MAX_VALUE)`
        // crash (#958 class). Clamp each axis to Compose's layout ceiling so the
        // outer stack is robust to that transient pass — the real bounded pass is
        // unchanged.
        val width = safeLayoutDimension(placeables.maxOfOrNull { it.width } ?: constraints.minWidth)
        val height = safeLayoutDimension(placeables.maxOfOrNull { it.height } ?: constraints.minHeight)
        layout(width, height) {
            placeables.forEach { it.place(0, 0) }
        }
    }

    // Intentionally no `DisposableEffect` here. Resource ownership is
    // delegated cleanly:
    //
    // - The vendored `TerminalView` is owned by `AndroidView`, which
    //   detaches it from its parent on disposal (the standard interop
    //   contract).
    // - The optional external producer is launched in the *caller's*
    //   scope via [TerminalSurfaceState.attachExternalProducer]; cancelling
    //   that scope (e.g. when the host composable leaves the composition)
    //   triggers the bridge's `finally { detachExternalProducer() }` path,
    //   which stops the drainer thread and releases the session.
    // - The `NoOpTerminalViewClient` is a plain object with no listeners
    //   registered anywhere; nothing to detach.
    //
    // An earlier revision had an empty `DisposableEffect(state) { onDispose
    // { } }` here as a TODO marker. It allocated a Compose slot per
    // composition without doing any work and was removed in #31 once the
    // ownership story above was verified.
}

/**
 * Convenience factory for creating a [TerminalSurfaceState] that survives
 * recompositions. The returned instance is bound to the current
 * composition's lifecycle — when the composable leaves, the state is
 * garbage-collected like any other `remember`-ed object.
 *
 * Usage:
 *
 * ```kotlin
 * val terminal = rememberTerminalSurfaceState()
 * TerminalSurface(terminal, modifier = Modifier.fillMaxSize())
 * ```
 *
 * Issue #8 ships this without saving across configuration changes; once
 * #9 lands and sessions carry meaningful per-pane state, we will revisit
 * with `rememberSaveable` and a custom `Saver`.
 */
@Composable
fun rememberTerminalSurfaceState(): TerminalSurfaceState =
    remember { TerminalSurfaceState() }

private fun TerminalRawInputPolicy.toTerminalViewPolicy(): TerminalView.SmartTextStagingPolicy =
    when (this) {
        TerminalRawInputPolicy.FlushSmartText -> TerminalView.SmartTextStagingPolicy.FLUSH
        TerminalRawInputPolicy.ClearSmartText -> TerminalView.SmartTextStagingPolicy.CLEAR
    }

/**
 * Keyboard policy for TerminalView IME input.
 *
 * [RawCommand] is the default and advertises password-like/no-suggestions
 * input flags so keyboards do not autocorrect shell syntax. [SmartText]
 * requests a normal autocorrect-capable text keyboard, but the vendored
 * input connection stages committed text and writes it only when Enter
 * confirms the buffer. Prompt Composer remains the preferred surface for
 * prose and longer agent prompts.
 */
enum class TerminalKeyboardMode {
    RawCommand,
    SmartText,
}

internal fun TerminalView.applyPocketShellDefaults(viewClient: TerminalViewClient): TerminalView {
    setTerminalViewClient(viewClient)
    if (viewClient is PocketShellTerminalViewClient) {
        viewClient.bind(this)
    }
    // Termux's setTypeface reads mRenderer.mTextSize, so text size must create
    // the renderer before we swap in the app typeface.
    setTextSize(DEFAULT_TEXT_SIZE_RAW_PX)
    // Issue #241 — switch to the bundled JetBrainsMono Regular face. The
    // renderer applies bold / italic / underline per-cell from terminal
    // attributes, so we load the plain regular face here; do NOT pre-bake a
    // bold style into the Typeface or every cell would render bold.
    setTypeface(terminalTypeface(context))
    setDefaultBackgroundColor(DefaultTerminalBackground.toArgb())
    isFocusable = true
    isFocusableInTouchMode = true
    return this
}

private fun TerminalView.applyPocketShellDefaultColors() {
    val emulator = currentSession?.emulator ?: return
    emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = TERMINAL_BACKGROUND_ARGB
    emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND] = TERMINAL_FOREGROUND_ARGB
    emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] = TERMINAL_CURSOR_ARGB
}

/**
 * [TerminalViewClient] used by PocketShell's embedded terminal.
 *
 * The vendored [TerminalView] owns text and hardware-key routing once the IME
 * is open, but it delegates tap handling to its client. PocketShell keeps
 * plain terminal taps independent from IME display and focus: the explicit
 * show-keyboard accessory calls [showTerminalSoftKeyboard], while this client
 * uses the tap hook for URL/file routing.
 */
internal class PocketShellTerminalViewClient : TerminalViewClient, TerminalSessionClient {
    private var terminalView: TerminalView? = null
    var terminalKeyboardMode: TerminalKeyboardMode = TerminalKeyboardMode.RawCommand
    var onTerminalSizeChanged: ((columns: Int, rows: Int) -> Unit)? = null
    var onTerminalSurfaceError: ((Throwable) -> Unit)? = null
    var onViewportChanged: (() -> Unit)? = null

    /**
     * Hook installed by [TerminalSurface] when URL detection is enabled.
     * Called from [onSingleTapUp] for every confirmed single tap; given the
     * tap coordinates in view-local pixels, the host returns `true` if the
     * tap landed on a URL and `false` otherwise.
     *
     * Splitting the URL hit-test out of this class (which lives in
     * `core-terminal`) means the host can swap in a stub for tests and lets
     * the design-system color used to underline URLs live alongside the
     * matching overlay code rather than being hard-coded in this client.
     */
    var onTapMaybeUrl: ((tapX: Float, tapY: Float) -> Boolean)? = null

    fun bind(view: TerminalView) {
        terminalView = view
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        runCatching { terminalView?.onScreenUpdated() }
            .onFailure { onTerminalSurfaceError?.invoke(it) }
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession) = Unit
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) = Unit
    override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) {
        runCatching {
            terminalView?.apply {
                applyPocketShellDefaultColors()
                onScreenUpdated()
            }
        }.onFailure { onTerminalSurfaceError?.invoke(it) }
    }

    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit
    override fun getTerminalCursorStyle(): Int? = null
    override fun onScale(scale: Float): Float = 1f
    override fun onSingleTapUp(e: MotionEvent?) {
        if (terminalView == null) return
        // Issue #175/#500 — give URL/file hosts first crack at the gesture.
        // The vendored TerminalView has already handled selection state before
        // calling us; do not request focus or summon IME from a terminal tap.
        runCatching {
            if (e != null) onTapMaybeUrl?.invoke(e.x, e.y)
        }.onFailure { onTerminalSurfaceError?.invoke(it) }
    }
    override fun onScrollChanged() {
        onViewportChanged?.invoke()
    }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseSmartTextInput(): Boolean = terminalKeyboardMode == TerminalKeyboardMode.SmartText
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
    override fun onEmulatorSet() {
        runCatching {
            terminalView?.applyPocketShellDefaultColors()
            val emulator = terminalView?.currentSession?.emulator ?: return
            if (emulator.mColumns > 0 && emulator.mRows > 0) {
                onTerminalSizeChanged?.invoke(emulator.mColumns, emulator.mRows)
            }
            onViewportChanged?.invoke()
        }.onFailure { onTerminalSurfaceError?.invoke(it) }
    }
    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        e?.let { onTerminalSurfaceError?.invoke(it) }
    }
    override fun logStackTrace(tag: String?, e: Exception?) {
        e?.let { onTerminalSurfaceError?.invoke(it) }
    }

    // Issues #966/#967: a render/resize failure — including an Error (OOM /
    // StackOverflow) the onDraw catch now swallows instead of crashing — is
    // surfaced to the host's terminal-surface recovery path. A silent render
    // death on a LIVE transport becomes an observable, recoverable event.
    override fun onTerminalRenderFailure(message: String?, t: Throwable?) {
        t?.let { onTerminalSurfaceError?.invoke(it) }
    }
}
