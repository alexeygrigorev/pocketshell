package android.content

/**
 * Test-only [ClipboardManager] subclass for the app module's connected
 * Compose tests. Lives in package `android.content` so it can call
 * ClipboardManager's package-private no-arg constructor — the public class
 * exposes no `public` constructor, but a class declared in the same package
 * can still see and invoke the package-private one.
 *
 * This mirrors the established
 * `shared/core-terminal/.../android/content/RecordingClipboardManager.kt`
 * helper (it cannot be shared across module test source sets, so the app
 * module carries its own copy).
 *
 * Records every [setPrimaryClip] call in [recordedClips]. Production copy
 * code (`ConversationCopyAction.copyConversationTextToClipboard`,
 * `FileViewerScreen.copyTextToClipboard`) resolves
 * `context.getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager` and
 * calls `setPrimaryClip(...)`; routing that lookup through this recording
 * subclass (via [com.pocketshell.app.test.ClipboardOverrideContext]) lets
 * the test observe the call deterministically.
 *
 * Why this exists: the Android emulator (API 29+) enforces a foreground-
 * focus policy on `ClipboardManager` reads/writes — the system service
 * silently returns `null`/no-ops for windows that do not hold focus, which
 * is exactly the AOSP API 35 swiftshader AVD's default state for a
 * `createAndroidComposeRule<ComponentActivity>()` activity. Reading the
 * system clipboard back is therefore flaky; reading [recordedClips] is not.
 */
class RecordingClipboardManager : ClipboardManager() {

    private val internalClips = mutableListOf<ClipData>()

    val recordedClips: List<ClipData>
        get() = synchronized(internalClips) { internalClips.toList() }

    /** The plain text of the most recently recorded primary clip, or `null`. */
    val lastText: String?
        get() = synchronized(internalClips) {
            internalClips.lastOrNull()?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.text?.toString()
        }

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
