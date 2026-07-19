package com.pocketshell.core.ssh

/**
 * Issue #1693 — the #780-model SYNTHETIC self-inflicted-close bridge.
 *
 * Exposes [RealSshSession.forceTransportDeathForTest] (a raw, synchronous,
 * anonymous-peer-style transport kill) to app-module test code WITHOUT adding a
 * test-only method to the production [SshSession] interface. Mirrors the existing
 * process-global `KeepAliveTestOverride` test-seam-object pattern in this module:
 * the production interface stays clean, [RealSshSession]'s kill stays `internal`,
 * and the bridge is the single documented entry point.
 *
 * The whole reason this exists: the reconnect-storm reproduction
 * (`MobileLatencyStormSelfInflictedCloseE2eTest`) needs to force the shared `-CC`
 * lease's transport DOWN deterministically on the `agent_kind_classify` bounded
 * exec's real timeout — reproducing the pre-#1641 v0.4.38 self-close storm every
 * RED run. The historical "restore the `close()` shim" RED is flaky (~1/3) because
 * the modern async/refcount-aware [SshSession.close] no longer reliably reaches the
 * reader (issue #1693). [forceTransportDeath] bypasses that machinery.
 *
 * TEST-ONLY. Production never calls this — the app's bounded-exec seam
 * (`BoundedSessionExec`) invokes it only when a test has armed the caller site.
 * On a non-[RealSshSession] (e.g. a per-test fake) it is a no-op, so it is safe to
 * call unconditionally.
 */
public object SshSessionTestControl {

    /**
     * Raw, synchronous, anonymous transport kill (issue #1693). Delegates to
     * [RealSshSession.forceTransportDeathForTest]; a no-op on any other
     * [SshSession] implementation.
     */
    public fun forceTransportDeath(session: SshSession) {
        (session as? RealSshSession)?.forceTransportDeathForTest()
    }
}
