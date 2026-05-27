package android.content

/**
 * Test-only [ClipboardManager] subclass used by
 * `TerminalSurfaceComposeIntegrationTest`. Lives in package
 * `android.content` so it can call ClipboardManager's package-private
 * no-arg constructor — the public class has no exposed `public`
 * constructor, but a Kotlin/Java class declared in the same package can
 * still see and invoke the package-private one.
 *
 * Records every [setPrimaryClip] call in [recordedClips]. The
 * production [com.pocketshell.core.terminal.ui.TerminalSurface]
 * `DisposableEffect` does `appContext.getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager`
 * and then `setPrimaryClip(...)` on the result; our subclass IS a
 * ClipboardManager (Kotlin inheritance), so the `as?` cast succeeds and
 * `setPrimaryClip` invokes the override below.
 *
 * Why this exists: the Android emulator (API 29+) enforces a foreground-
 * focus policy on `ClipboardManager.setPrimaryClip` — the system service
 * silently no-ops writes from un-focused windows. The AOSP API 35
 * emulator's launcher (QuickstepLauncher) keeps window focus even when
 * `composeTestRule.activity` is launched, which makes a vanilla
 * system-clipboard assertion flaky. Routing the production code's
 * `setPrimaryClip` call through this recording subclass (via a
 * `ContextWrapper` whose `getSystemService(CLIPBOARD_SERVICE)` returns
 * this instance) lets the test observe the call deterministically
 * without depending on the focus-policy gate.
 *
 * The production `DisposableEffect` also creates `ClipData.newPlainText`
 * before calling `setPrimaryClip`, so [recordedClips] holds the exact
 * ClipData the user's COPY would have placed on the system clipboard.
 */
class RecordingClipboardManager : ClipboardManager() {

    private val internalClips = mutableListOf<ClipData>()

    val recordedClips: List<ClipData>
        get() = synchronized(internalClips) { internalClips.toList() }

    override fun setPrimaryClip(clip: ClipData) {
        synchronized(internalClips) { internalClips += clip }
    }

    override fun getPrimaryClip(): ClipData? {
        return synchronized(internalClips) { internalClips.lastOrNull() }
    }

    override fun clearPrimaryClip() {
        synchronized(internalClips) { internalClips.clear() }
    }

    override fun hasPrimaryClip(): Boolean {
        return synchronized(internalClips) { internalClips.isNotEmpty() }
    }
}
