package com.pocketshell.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #1155 (Part A) — durable wiring guard for the process-start folder-tree
 * cache warm (D31/G1/G9, non-waivable — this is a REOPEN of #867 / #1109).
 *
 * The Part-A production fix is the `App.onCreate` cache warm:
 *
 * ```
 * sshLifecycleScope.launch {
 *     runCatching { treeClientCache.warmAll() }
 * }
 * ```
 *
 * That single line is the SOLE warm path (the old racy per-VM warm was removed
 * in blocker 1). It is what makes the FIRST host opened after a genuinely cold
 * PROCESS start paint the persisted tree instantly instead of flashing "Loading
 * workspace tree". The exact regression that reopened #867 / #1109 twice is
 * "the startup warm got removed" — so the durable gate is: deleting that warm
 * call from `App.onCreate` must turn a test RED.
 *
 * Why a source-structure guard (not a Robolectric `App.onCreate()` run):
 *   - `App` is `@HiltAndroidApp` and `treeClientCache` is an injected
 *     `lateinit var`. `App.onCreate()` runs `super.onCreate()` (Hilt field
 *     injection) plus StrictMode/CrashReporter/scheduler wiring; a plain-JVM
 *     unit test cannot drive that full `onCreate` without the androidTest Hilt
 *     rule. This mirrors the accepted precedent `AppStrictModeArmOrderTest`,
 *     which guards the SAME class of "a load-bearing line moved / was removed
 *     from `App.onCreate`" regression at the source/structure level so it runs
 *     for free in the Unit job and fails deterministically on the regression.
 *   - The property under test IS the wiring itself: the warm call present, in
 *     `App.onCreate`, dispatched off Main via `sshLifecycleScope.launch`, and
 *     placed AFTER `super.onCreate()` (so Hilt has populated the `lateinit`
 *     `treeClientCache` — a warm before injection would crash on the lateinit).
 *     This is not a proxy for the behaviour; the presence-of-wiring is the exact
 *     thing #867 / #1109 lost.
 *
 * RED→GREEN: reverting `App.kt` line 372 (`runCatching { treeClientCache.warmAll() }`)
 * removes `treeClientCache.warmAll()` from the source, so `warmAllIdx` becomes
 * -1 and every assertion below fails. Restoring it makes them pass.
 */
class AppTreeCacheWarmWiringTest {

    // Code only — comment lines are stripped so a prose mention of
    // `treeClientCache.warmAll()` in this file's sibling comments (or App.kt's
    // own explanatory comment) can never be matched as the call site.
    private val source: String = locate("App.kt")
        .lineSequence()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")

    @Test
    fun onCreateWarmsTreeClientCacheAtProcessStart() {
        val warmAllIdx = source.indexOf(WARM_ALL_CALL)
        assertTrue(
            "App.onCreate must warm the persisted folder-tree cache at process start via " +
                "`$WARM_ALL_CALL` (issue #1155 Part A; the SOLE warm path). Its removal is the " +
                "exact regression that reopened #867 / #1109 — a cold-launch \"Loading workspace " +
                "tree\" flash returns without it. warmAllIdx=$warmAllIdx",
            warmAllIdx >= 0,
        )
    }

    @Test
    fun warmRunsAfterSuperOnCreateSoInjectedCacheIsAvailable() {
        // `treeClientCache` is an injected `lateinit var`; Hilt populates it in
        // `super.onCreate()`. The warm MUST run after `super.onCreate()` or it
        // would touch the uninitialised lateinit. This ordering guard fails if
        // the warm line is deleted (warmAllIdx == -1) AND if a future edit hoists
        // it above injection.
        val superIdx = source.indexOf(SUPER_ON_CREATE_CALL)
        assertTrue("App.onCreate must call $SUPER_ON_CREATE_CALL", superIdx >= 0)
        val warmAllIdx = source.indexOf(WARM_ALL_CALL)
        assertTrue(
            "The tree-cache warm must run AFTER super.onCreate() (Hilt injects the lateinit " +
                "treeClientCache there). warmAllIdx=$warmAllIdx superIdx=$superIdx",
            warmAllIdx in (superIdx + 1) until source.length,
        )
    }

    @Test
    fun warmIsDispatchedOffMainOnTheSshLifecycleScope() {
        // The warm reads the persisted tree from disk, so it must be dispatched
        // OFF Main (StrictMode disk-read tripwire + the #965 ANR budget). Assert
        // the warm call sits inside an `sshLifecycleScope.launch { ... }` block:
        // the nearest `sshLifecycleScope.launch` before the warm must be closer
        // than any prior `super.onCreate()`, and there is a launch opening before
        // the warm. Deleting the warm makes warmAllIdx == -1 and fails here too.
        val warmAllIdx = source.indexOf(WARM_ALL_CALL)
        assertTrue(
            "App.onCreate must warm via `$WARM_ALL_CALL`. warmAllIdx=$warmAllIdx",
            warmAllIdx >= 0,
        )
        val launchIdx = source.lastIndexOf(SSH_SCOPE_LAUNCH, warmAllIdx)
        assertTrue(
            "The tree-cache warm must be dispatched off Main via `$SSH_SCOPE_LAUNCH { ... }` " +
                "so the disk read does not run on the Main thread (StrictMode / #965 ANR budget). " +
                "launchIdx=$launchIdx warmAllIdx=$warmAllIdx",
            launchIdx in 0 until warmAllIdx,
        )
    }

    private fun locate(name: String): String {
        val candidates = listOf(
            File("app/src/main/java/com/pocketshell/app/$name"),
            File("src/main/java/com/pocketshell/app/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("Could not locate $name from ${File(".").absolutePath}")
        return file.readText()
    }

    private companion object {
        const val WARM_ALL_CALL = "treeClientCache.warmAll()"
        const val SUPER_ON_CREATE_CALL = "super.onCreate()"
        const val SSH_SCOPE_LAUNCH = "sshLifecycleScope.launch"
    }
}
