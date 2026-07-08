package com.pocketshell.app.proof

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Modifier

/**
 * Issue #1362 (v0.4.25 RELEASE BLOCKER) — durable regression proof for the
 * `TmuxSessionScreen` ART dex-verification failure.
 *
 * ## The bug this pins
 * On the API-33 CI AVD the app threw, on EVERY session-screen open:
 * ```
 * java.lang.VerifyError: Verifier rejected class
 *   com.pocketshell.app.tmux.TmuxSessionScreenKt:
 *   ... TmuxSessionScreen(...) failed to verify:
 *   [0x39D8] copy1 v19<-v300 type=Reference: androidx.compose.runtime.MutableState
 *     at com.pocketshell.app.MainActivityKt.AppNavigator(MainActivity.kt:1574)
 * ```
 * The ART bytecode verifier REJECTS the `TmuxSessionScreen` composable because
 * D8 allocated a `MutableState` reference into a wide (v256+) register and then
 * emitted a `move-object/16`/`move-object/from16` of it that the verifier's
 * register-type merge rejects. `AppNavigator` therefore crashed the instant any
 * journey opened a session screen (all three emulator shards, both cold boots).
 *
 * The #1344 band-wording fix (68ac75b7) added the nested
 * `disconnectEndpointLabel(user, host, port)` -> `failureReasonSentence(...)`
 * computation INLINE inside the ~2800-line `TmuxSessionScreen` method; that
 * changed D8's register allocation of the mega-method into the fatal wide-register
 * MutableState-move shape. The #1362 fix hoists that computation into its own
 * [com.pocketshell.app.tmux.SessionFailureBand] composable frame (keeping the
 * #1344 "Disconnected from <endpoint>" wording verbatim), restoring an allocation
 * that the verifier accepts.
 *
 * ## Why THIS test reproduces it (no proxy — process.md F2/G10)
 * ART verifies a class's method bodies when the class is resolved on-device. This
 * test forces that resolution+verification of the REAL production class that
 * declares the crashing composable (`TmuxSessionScreenKt`), exactly the class
 * `AppNavigator` resolves when it opens a session screen. On the broken build the
 * class fails verification and the load throws `VerifyError` (RED); with the fix
 * the class resolves + verifies clean (GREEN). No stand-in, no convenient proxy:
 * it is the exact production class that crashed on-device.
 *
 * Deliberately NOT a MainActivity/Docker journey: the VerifyError fires at class
 * resolution, BEFORE any SSH/tmux connection, so a full connected journey is not
 * needed to trigger it — this is the cheapest deterministic reproduction. The
 * broad emulator gate (every session-opening `*E2eTest`) is the on-device backstop
 * for the full user path; this dedicated class-verify test is the focused,
 * fast RED->GREEN pin. Wired into the per-push emulator journey gate via
 * `scripts/ci-journey-suite.sh`. No `assumeTrue` / `assumeFalse(isRunningOnCi())`
 * on the load-bearing assertion (process.md F3 / D33).
 */
@RunWith(AndroidJUnit4::class)
class TmuxSessionScreenArtVerifyE2eTest {

    @Test
    fun tmuxSessionScreenComposableClassResolvesAndVerifiesOnDevice() {
        val loader = requireNotNull(javaClass.classLoader) {
            "no classloader available to resolve the production composable class"
        }

        // Class.forName(initialize = true) resolves AND (on ART) verifies the
        // class that declares the crashing composable. On the #1362 broken build
        // this throws java.lang.VerifyError here; with the fix it returns cleanly.
        val cls = Class.forName(
            "com.pocketshell.app.tmux.TmuxSessionScreenKt",
            /* initialize = */ true,
            loader,
        )

        // Touch the declared methods so the verifier must resolve the mega-method
        // whose body was rejected on-device (its parameter/return descriptors are
        // resolved here). getDeclaredMethods()/getParameterTypes() force the
        // class's method table to fully resolve rather than staying lazy.
        val composable = cls.declaredMethods.firstOrNull { method ->
            method.name == "TmuxSessionScreen" && Modifier.isStatic(method.modifiers)
        }
        assertTrue(
            "TmuxSessionScreenKt.TmuxSessionScreen must be declared and its class " +
                "must verify on-device (issue #1362 VerifyError regression). If the " +
                "class failed ART verification this line is never reached — the " +
                "Class.forName above throws VerifyError first.",
            composable != null,
        )
        // Resolve the parameter descriptors too — a second nudge to make ART touch
        // the method fully. (No-op on a verified class; on a rejected class the
        // load already failed above.)
        composable?.parameterTypes
    }
}
