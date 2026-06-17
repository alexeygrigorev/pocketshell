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
import com.pocketshell.core.terminal.selection.EngineCommandOverlay
import com.pocketshell.core.terminal.selection.EngineCommandRegion
import com.pocketshell.core.terminal.selection.FilePathOverlay
import com.pocketshell.core.terminal.selection.FilePathRegion
import com.pocketshell.core.terminal.selection.SelectionOverlay
import com.pocketshell.core.terminal.selection.SmartSelectionAffordanceOverlay
import com.pocketshell.core.terminal.selection.TerminalMatch
import com.pocketshell.core.terminal.selection.TerminalMatchRegion
import com.pocketshell.core.terminal.selection.UrlRegion
import com.pocketshell.core.terminal.selection.findVisibleUrls
import com.pocketshell.core.terminal.selection.hitTestEngineCommand
import com.pocketshell.core.terminal.selection.hitTestFilePath
import com.pocketshell.core.terminal.selection.hitTestUrl
import com.termux.terminal.TextStyle
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

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
    val coalescedRenderRequests = remember(state) { state.renderRequests.coalescePerFrame() }

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
        onDispose { state.setSmartTextStagingBridge(null) }
    }

    LaunchedEffect(state, terminalView, coalescedRenderRequests) {
        val view = terminalView ?: return@LaunchedEffect
        // Issue #796: drive the repaint off the FRAME-COALESCED redraw signal
        // (≤1 repaint per frame), not the raw per-emulator-tick `renderRequests`.
        // A Codex `%output` burst no longer turns into O(N) `onScreenUpdated()`
        // repaints on the UI thread. The coalescer never drops the final frame,
        // so the settled cursor/spinner state still paints after the burst.
        coalescedRenderRequests.collect {
            runCatching { view.onScreenUpdated() }
                .onFailure { onLocalTerminalError?.invoke(it) }
        }
    }

    // Issue #721: a force-full-repaint signal (emitted on tmux reattach re-seed)
    // means the EXISTING screen content must be redrawn from the buffer, not just
    // the newly-changed rows the #469 dirty-region path would pick. This resets
    // the renderer's dirty cache and issues a full invalidate(), so a reattach
    // RESEED repaints every row regardless of cache state.
    LaunchedEffect(state, terminalView) {
        val view = terminalView ?: return@LaunchedEffect
        state.fullRepaintRequests.collect {
            runCatching { view.forceFullRepaint() }
                .onFailure { onLocalTerminalError?.invoke(it) }
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
    val effectiveUrlTap: ((String) -> Unit)? = remember(onUrlTap, urlsEnabled, context) {
        if (!urlsEnabled) {
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
    val engineCommandsEnabled = onEngineCommandTap != null && engineCommands.isNotEmpty()

    LaunchedEffect(state, terminalView, effectiveUrlTap, viewportTick, coalescedRenderRequests) {
        val view = terminalView
        if (view == null || effectiveUrlTap == null) {
            visibleUrls = emptyList()
            return@LaunchedEffect
        }
        visibleUrls = runCatching { findVisibleUrls(view) }
            .getOrElse { cause ->
                onLocalTerminalError?.invoke(cause)
                emptyList()
            }
        // Issue #796: scan the full viewport for URLs at most once per frame, not
        // once per emulator tick. `findVisibleUrls` reads the live TerminalView
        // renderer/emulator, so it must run on the UI thread (the view is not
        // thread-safe); the win here is frame-gating the scan count, which is the
        // dominant per-tick cost during a burst. Only the diff is published.
        coalescedRenderRequests.collect {
            val fresh = runCatching { findVisibleUrls(view) }
                .getOrElse { cause ->
                    onLocalTerminalError?.invoke(cause)
                    emptyList()
                }
            if (fresh != visibleUrls) {
                visibleUrls = fresh
            }
        }
    }

    // Issue #500: file-path detection is driven by the FilePathOverlay below
    // (gated on onFilePathTap != null). The overlay scans the visible viewport
    // and reports its FilePathRegion snapshot via onFilePathsChanged, which we
    // capture into `visibleFilePaths` for the tap hit-test. Keeping a single
    // scan source (the overlay) avoids double-scanning per render tick.

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
        engineCommandTap,
    ) {
        val view = terminalView
        val tap = effectiveUrlTap
        if (view == null || (tap == null && onFilePathTap == null && engineCommandTap == null)) {
            viewClient.onTapMaybeUrl = null
        } else {
            val urlsSnapshot = visibleUrls
            val pathsSnapshot = visibleFilePaths
            val commandsSnapshot = visibleEngineCommands
            val pathTap = onFilePathTap
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
    // see [SmartSelectionAffordanceOverlay]'s KDoc for the rationale.
    Layout(
        modifier = keyAwareModifier,
        content = {
            AndroidView(
                factory = { ctx ->
                    TerminalView(ctx, /* attributes = */ null)
                        .applyPocketShellDefaults(viewClient)
                        .also { terminalView = it }
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
            if (matchListener != null) {
                SelectionOverlay(
                    view = terminalView,
                    regions = visibleMatchRegions,
                    onTap = matchListener,
                )
            }
            if (matchListener != null || effectiveUrlTap != null) {
                SmartSelectionAffordanceOverlay(
                    view = terminalView,
                    // Issue #796: gate the per-render match scan to ≤1/frame.
                    renderRequests = coalescedRenderRequests,
                    viewportChangeKey = viewportTick,
                    matcher = state.currentMatcher(),
                    onMatchesChanged = { visibleMatchRegions = it },
                )
            }
            // Issue #500: tappable file-path affordance + hit-test snapshot.
            if (onFilePathTap != null) {
                FilePathOverlay(
                    view = terminalView,
                    // Issue #796: gate the per-render file-path scan to ≤1/frame.
                    renderRequests = coalescedRenderRequests,
                    viewportChangeKey = viewportTick,
                    onFilePathsChanged = { visibleFilePaths = it },
                )
            }
            // Issue #770: tappable engine-command affordance + hit-test snapshot.
            if (engineCommandsEnabled) {
                EngineCommandOverlay(
                    view = terminalView,
                    // Issue #796: gate the per-render engine-command scan to ≤1/frame.
                    renderRequests = coalescedRenderRequests,
                    knownCommands = engineCommands,
                    viewportChangeKey = viewportTick,
                    onEngineCommandsChanged = { visibleEngineCommands = it },
                )
            }
        },
    ) { measurables, constraints ->
        // Measure each child with the full incoming constraints and stack
        // them at (0, 0). The first child is the AndroidView; subsequent
        // children (the overlay, if present) are placed on top in source
        // order, exactly as `Box` would do for centred / aligned children.
        val placeables = measurables.map { it.measure(constraints) }
        val width = placeables.maxOfOrNull { it.width } ?: constraints.minWidth
        val height = placeables.maxOfOrNull { it.height } ?: constraints.minHeight
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
}
