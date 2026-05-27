package com.pocketshell.core.ssh

import net.schmizz.sshj.common.SSHException
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Process-wide [Thread.UncaughtExceptionHandler] that swallows
 * `TransportException` / `SSHException` / `IOException` thrown from sshj's
 * own internal JVM threads (`Reader`, `Writer`, `KeepAlive`, ...).
 *
 * Issue #173 round-2 — the original fix wrapped the *coroutine* layer that
 * consumes data from sshj (see `TmuxClient.readerLoop` and
 * `SessionViewModel.runConnect`'s `producerJob.invokeOnCompletion`). That
 * shields the coroutine boundary, but on CI's swiftshader emulator the
 * sshj `Reader` JVM thread can crash *before* the coroutine boundary sees
 * the broken transport: when `transport.die()` invokes its disconnect
 * listener chain, any unchecked exception thrown from those listeners
 * propagates straight out of `Reader.run`, hits the JVM default uncaught
 * exception handler, and kills the entire process. 13 consecutive red CI
 * runs of `BackgroundResumeSocketDeathE2eTest` proved that route is
 * reachable.
 *
 * The fix is to install a chained `Thread.setDefaultUncaughtExceptionHandler`
 * that recognises sshj's internal threads by name (sshj names them
 * `sshj-Reader`, `sshj-KeepAliveRunner-...`, etc. via either explicit
 * `setName` in the [net.schmizz.sshj.transport.Reader] constructor or
 * via `com.hierynomus.sshj.common.ThreadNameProvider`). When such a
 * thread dies with an SSH/IO transport-related exception we log it via
 * the diagnostic tag and swallow — the existing Kotlin coroutine
 * boundary in `TmuxClient.readerLoop` / `SessionViewModel` will observe
 * the same socket drop through the next `read()` call on the channel
 * stream (which throws after `transport.die()` propagates the error via
 * `notifyError` to each channel) and route through the existing
 * `_disconnected` StateFlow / `producerJob.invokeOnCompletion`
 * machinery.
 *
 * Anything we do NOT recognise as an sshj-thread transport crash is
 * delegated to the previous default handler (typically the platform's
 * crash reporter). This keeps non-SSH crashes loud — debugging
 * unrelated bugs would be hellish if every uncaught exception were
 * swallowed.
 *
 * Idempotent install: the chained handler will only wrap the *real*
 * previous handler once, no matter how many SSH connections are opened
 * concurrently. Subsequent calls to [installIfNecessary] return without
 * touching `Thread.setDefaultUncaughtExceptionHandler` — important
 * because some tests reset the default handler between cases and we
 * must not stack ourselves on top of ourselves.
 *
 * Why process-wide instead of per-thread:
 *  - sshj does not expose any extension point to set an
 *    UncaughtExceptionHandler on its Reader/Writer/KeepAlive threads
 *    (no thread factory hook), so we cannot install on a per-thread
 *    basis without reflecting into sshj internals (the brief flagged
 *    that as fragile).
 *  - The default handler is the natural integration point for "an
 *    sshj background thread crashed unexpectedly" — sshj's own thread
 *    naming convention gives us a precise filter so we do not over-
 *    swallow.
 *
 * Why not just `Thread.setDefaultUncaughtExceptionHandler(null)`:
 *  - Drops the platform's crash reporter for the rest of the process.
 *    Unacceptable: we need real, non-SSH crashes to still report.
 *
 * Why not catch *every* exception from sshj threads (regardless of
 * type):
 *  - We want to surface genuine programming errors (NPE, IAE, ...) on
 *    sshj threads loudly. Only known transport-layer exception types
 *    are silently absorbed.
 */
internal object SshjTransportThreadGuard {

    /**
     * Diagnostic tag for logcat searches when triaging a swallowed
     * sshj thread crash. We log through `java.util.logging` (always
     * available on Android and on the host JVM, no extra dependency
     * required) rather than `android.util.Log` so the same code path
     * is exercisable from the host JVM unit tests in `src/test/`.
     * Android routes `java.util.logging` records through `logcat` —
     * search for the tag literal to filter, e.g.
     * `adb logcat | grep issue173-sshj-guard`.
     */
    private const val LOG_TAG: String = "issue173-sshj-guard"

    private val logger: Logger =
        Logger.getLogger(SshjTransportThreadGuard::class.java.name)

    /**
     * Marker on the [Thread.UncaughtExceptionHandler] object that
     * indicates this guard has already been installed. We check the
     * runtime type (not identity against [chainedHandlerRef]) so that
     * a test which calls [installIfNecessary] from a fresh classloader
     * still treats the previous install as canonical.
     */
    private class ChainedHandler(
        val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            if (isSshjTransportCrash(t, e)) {
                // Logged, then swallowed. The Kotlin coroutine that
                // reads from the affected channel will observe the
                // same disconnect on its next read() because sshj's
                // transport.die() already propagated `notifyError` to
                // each channel's input stream before this exception
                // escaped. The downstream `TmuxClient.disconnected`
                // StateFlow / `SessionViewModel.producerJob.invokeOn-
                // Completion` machinery then routes the disconnect to
                // the UI as a `ConnectionStatus.Failed` state.
                logger.log(
                    Level.WARNING,
                    "$LOG_TAG swallowed sshj-thread crash on '${t.name}': " +
                        "${e.javaClass.name}: ${e.message}",
                    e,
                )
                return
            }
            // Not an sshj transport crash — delegate to the previous
            // handler so genuine non-SSH crashes still report (and
            // the test runner / Crashlytics / etc. still see them).
            val delegate = previous
            if (delegate != null) {
                delegate.uncaughtException(t, e)
            } else {
                // No previous handler — fall back to the JVM's
                // default behavior of logging to stderr and the
                // thread terminating. We intentionally do not
                // rethrow: the thread is already terminating.
                logger.log(
                    Level.SEVERE,
                    "$LOG_TAG uncaught exception on '${t.name}' with no chained handler: " +
                        "${e.javaClass.name}: ${e.message}",
                    e,
                )
            }
        }
    }

    private val installedHandlerRef: AtomicReference<ChainedHandler?> =
        AtomicReference(null)

    /**
     * Install the guard if it has not been installed already. Safe to
     * call from any thread; the first caller wins via CAS and
     * subsequent callers no-op.
     *
     * Called from [SshConnection.connect] and [SshConnection.createClient]
     * so the guard is in place before any sshj `Reader` thread starts
     * — sshj's `SSHClient.connect()` is what spawns the Reader, and
     * both entry points either build the client themselves
     * ([SshConnection.connect]) or are how production callers get a
     * client ([SshConnection.createClient]).
     */
    fun installIfNecessary() {
        // Fast path: already installed by us. Use the AtomicReference
        // rather than the JVM-global handler getter so a sibling test
        // that nulled the default handler in @After cannot accidentally
        // make us reinstall and re-wrap ourselves recursively.
        if (installedHandlerRef.get() != null) {
            return
        }
        synchronized(this) {
            if (installedHandlerRef.get() != null) {
                return
            }
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            // Defensive: if the current default already IS one of our
            // chained handlers (e.g. installed by a previous classloader
            // instance), do not stack a second copy on top of it.
            if (previous is ChainedHandler) {
                installedHandlerRef.set(previous)
                return
            }
            val chained = ChainedHandler(previous = previous)
            Thread.setDefaultUncaughtExceptionHandler(chained)
            installedHandlerRef.set(chained)
            logger.log(
                Level.INFO,
                "$LOG_TAG installed chained UncaughtExceptionHandler over " +
                    (previous?.javaClass?.name ?: "<none>"),
            )
        }
    }

    /**
     * Visible for tests: tell the guard to forget its install state.
     * Production callers never use this — it exists so unit tests can
     * verify the install-once contract.
     */
    @Suppress("unused")
    internal fun resetForTests() {
        installedHandlerRef.set(null)
    }

    /**
     * Visible for tests: classification predicate. A crash counts as a
     * "swallowable sshj transport crash" only if BOTH:
     *  - the thread's name starts with `sshj-` (matches the Reader's
     *    explicit `setName("sshj-Reader")` and ThreadNameProvider's
     *    `sshj-<class>-<addr>-<ts>` format used for KeepAlive etc.), AND
     *  - the exception is or is caused by `SSHException` /
     *    `TransportException` / `IOException` — the family of
     *    transport-layer failures sshj surfaces when the underlying
     *    socket dies.
     *
     * Both must hold so that a `NullPointerException` raised by an
     * unrelated bug on an sshj thread (or any exception on a
     * non-sshj thread) still propagates to the platform default
     * handler and surfaces loudly.
     */
    internal fun isSshjTransportCrash(t: Thread, e: Throwable): Boolean {
        if (!t.name.startsWith("sshj-")) return false
        // Walk the cause chain; sshj wraps SocketException as
        // SSHException(cause=SocketException), and ChannelInputStream's
        // notifyError preserves the original SSHException as the
        // throwable that propagates back out of the affected read.
        var cursor: Throwable? = e
        var depth = 0
        while (cursor != null && depth < CAUSE_CHAIN_DEPTH_LIMIT) {
            if (cursor is SSHException || cursor is IOException) {
                return true
            }
            cursor = cursor.cause
            depth++
        }
        return false
    }

    /**
     * Cap walking the cause chain so a pathologically nested chain
     * (or a cycle some buggy wrapper introduced) cannot loop forever.
     * sshj's wrap depth in practice is ~3 (SocketException ->
     * SSHException -> Cancelled coroutine diagnostic), so 16 is well
     * above the real ceiling.
     */
    private const val CAUSE_CHAIN_DEPTH_LIMIT: Int = 16
}
