package com.pocketshell.app.proof

import com.pocketshell.testsupport.IME_SERVICE_UNAVAILABLE_SIGNATURE
import com.pocketshell.testsupport.ImeServiceHealth
import com.pocketshell.testsupport.parseImeServiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1879 — the acceptance test for the issue's "captured IME-service-state
 * signature" item.
 *
 * ## Why this test exists on the JVM rather than on the emulator
 *
 * The state it discriminates — the input-method SERVICE itself unable to raise
 * a window — cannot be produced on the shared AVD without disabling the
 * emulator's IME, which would corrupt every sibling journey lane running on the
 * same device. So the *decision* is a pure function over the real
 * `dumpsys input_method` text (fixtures below are verbatim excerpts from a real
 * API-35 emulator dump), pinned here in the required per-PR `Unit tests` job,
 * and the androidTest side only performs the IO.
 *
 * ## The property under test
 *
 * `ime_visible_after_tap=false` is emitted by three different worlds (see
 * `ImeServiceState.kt`). This decides ONLY the third — "the service could not
 * have raised a keyboard for anyone" — and it must be **conservative in the
 * direction of blaming the product**: an unreadable, truncated, or merely
 * client-side-negative dump must NOT excuse a failing chip, because a wrong
 * environmental label on a genuine defect is exactly the misattribution #1879
 * is about, pointed the other way.
 */
class ImeServiceStateSignatureTest {

    @Test
    fun healthyServiceDoesNotExcuseAFailingChip() {
        val state = parseImeServiceState(
            dumpsysInputMethod = HEALTHY_DUMP,
            defaultInputMethod = LATIN_IME,
            enabledInputMethodIds = listOf(LATIN_IME),
        )

        assertEquals(
            "a bound, ready service with an enabled default IME is AVAILABLE; " +
                "anything else would hand a real chip failure an environmental excuse. " +
                state.describe(),
            ImeServiceHealth.AVAILABLE,
            state.health,
        )
        assertFalse(state.describe(), state.serviceUnavailable)
        assertEquals(LATIN_IME, state.curMethodId)
        assertTrue(state.describe(), state.curMethodBound == true)
        assertTrue(state.describe(), state.systemReady == true)
    }

    /**
     * The #1879 state-2 guard. `mBoundToMethod` / `mInputShown` also read false
     * when a foreign window owns input focus and no view is served, so they must
     * NOT decide. If they did, a focus-loss — or a genuine chip failure that
     * never established an input connection — would be relabelled "the IME
     * service is broken".
     */
    @Test
    fun clientSideNegativesAloneDoNotBlameTheService() {
        val dump = HEALTHY_DUMP.replace("mBoundToMethod=true", "mBoundToMethod=false")
        val state = parseImeServiceState(
            dumpsysInputMethod = dump,
            defaultInputMethod = LATIN_IME,
            enabledInputMethodIds = listOf(LATIN_IME),
        )

        assertEquals(
            "mBoundToMethod is a CLIENT-side fact: it reads false whenever no view " +
                "is served, which is also true when a foreign window stole focus and " +
                "when the chip itself is broken. " + state.describe(),
            ImeServiceHealth.AVAILABLE,
            state.health,
        )
        assertFalse(state.describe(), state.serviceUnavailable)
        assertTrue(state.describe(), state.boundToMethod == false)
        assertTrue(state.describe(), state.inputShown == false)
    }

    @Test
    fun noEnabledInputMethodIsTheServiceSignature() {
        val state = parseImeServiceState(
            dumpsysInputMethod = HEALTHY_DUMP,
            defaultInputMethod = null,
            enabledInputMethodIds = emptyList(),
        )

        assertEquals(state.describe(), ImeServiceHealth.UNAVAILABLE, state.health)
        assertTrue(state.describe(), state.serviceUnavailable)
        assertTrue(state.describe(), state.reasons.contains("no_enabled_input_methods"))
        assertTrue(state.describe(), state.reasons.contains("no_default_input_method"))
    }

    @Test
    fun serviceNotReadyIsTheServiceSignature() {
        val state = parseImeServiceState(
            dumpsysInputMethod = HEALTHY_DUMP.replace("mSystemReady=true", "mSystemReady=false"),
            defaultInputMethod = LATIN_IME,
            enabledInputMethodIds = listOf(LATIN_IME),
        )

        assertTrue(state.describe(), state.serviceUnavailable)
        assertTrue(state.describe(), state.reasons.contains("input_method_service_not_ready"))
    }

    /** The crash-looping / restarting IME: selected, but nothing bound to it. */
    @Test
    fun unboundServiceIsTheServiceSignature() {
        val dump = HEALTHY_DUMP
            .replace("mHaveConnection=true", "mHaveConnection=false")
            .replace(
                "mCurMethod=com.android.server.inputmethod.IInputMethodInvoker@1c0ac1d",
                "mCurMethod=null",
            )
        val state = parseImeServiceState(
            dumpsysInputMethod = dump,
            defaultInputMethod = LATIN_IME,
            enabledInputMethodIds = listOf(LATIN_IME),
        )

        assertTrue(state.describe(), state.serviceUnavailable)
        assertTrue(state.describe(), state.reasons.contains("input_method_service_not_bound"))
    }

    /**
     * Asymmetry guard (the conservative direction): IMMS legitimately drops the
     * binding when no client needs it, so ONE negative binding signal while the
     * other is positive must NOT be called broken.
     */
    @Test
    fun oneNegativeBindingSignalAloneIsNotEnoughToBlameTheService() {
        val state = parseImeServiceState(
            dumpsysInputMethod = HEALTHY_DUMP.replace("mHaveConnection=true", "mHaveConnection=false"),
            defaultInputMethod = LATIN_IME,
            enabledInputMethodIds = listOf(LATIN_IME),
        )

        assertEquals(state.describe(), ImeServiceHealth.AVAILABLE, state.health)
        assertFalse(state.describe(), state.serviceUnavailable)
    }

    @Test
    fun noCurrentInputMethodSelectedIsTheServiceSignature() {
        val state = parseImeServiceState(
            dumpsysInputMethod = HEALTHY_DUMP.replace(
                "mCurMethodId=com.android.inputmethod.latin/.LatinIME",
                "mCurMethodId=null",
            ),
            defaultInputMethod = LATIN_IME,
            enabledInputMethodIds = listOf(LATIN_IME),
        )

        assertTrue(state.describe(), state.serviceUnavailable)
        assertTrue(state.describe(), state.reasons.contains("no_current_input_method_selected"))
    }

    /**
     * Fail-safe toward RED. Missing, truncated, or format-drifted evidence is
     * UNKNOWN — never UNAVAILABLE — so the caller keeps the ordinary
     * chip-blaming wording rather than inventing an environmental excuse.
     */
    @Test
    fun missingOrUnreadableEvidenceIsUnknownAndNeverAnExcuse() {
        val noEvidence = parseImeServiceState(null, null, null)
        assertEquals(noEvidence.describe(), ImeServiceHealth.UNKNOWN, noEvidence.health)
        assertFalse(noEvidence.describe(), noEvidence.serviceUnavailable)
        assertTrue(noEvidence.describe(), noEvidence.reasons.contains("no_ime_service_evidence"))

        val driftedFormat = parseImeServiceState(
            dumpsysInputMethod = "Current Input Method Manager state:\n  someFutureKey=42\n",
            defaultInputMethod = null,
            enabledInputMethodIds = null,
        )
        assertEquals(driftedFormat.describe(), ImeServiceHealth.UNKNOWN, driftedFormat.health)
        assertFalse(driftedFormat.describe(), driftedFormat.serviceUnavailable)
        assertTrue(
            driftedFormat.describe(),
            driftedFormat.reasons.contains("ime_service_dump_unreadable"),
        )
    }

    /**
     * The signature phrase must be stable and self-describing: it is what a
     * future CI red is grepped for, and it must read as "not a chip failure"
     * without needing this file open.
     */
    @Test
    fun theSignaturePhraseNamesTheServiceAndClearsTheChip() {
        assertTrue(
            IME_SERVICE_UNAVAILABLE_SIGNATURE,
            IME_SERVICE_UNAVAILABLE_SIGNATURE.contains("input-method SERVICE"),
        )
        assertTrue(
            IME_SERVICE_UNAVAILABLE_SIGNATURE,
            IME_SERVICE_UNAVAILABLE_SIGNATURE.contains("NOT a show-keyboard-chip failure"),
        )
    }

    /** The one-line rendering must carry every field an artifact review needs. */
    @Test
    fun describeCarriesEveryDiscriminatingField() {
        val state = parseImeServiceState(HEALTHY_DUMP, LATIN_IME, listOf(LATIN_IME))
        val described = state.describe()
        listOf(
            "ime_service_health=",
            "cur_method_id=",
            "cur_method_bound=",
            "system_ready=",
            "bound_to_method=",
            "input_shown=",
            "default_ime=",
            "enabled_ime_count=",
        ).forEach { key ->
            assertTrue("summary line must carry $key; got: $described", described.contains(key))
        }
    }

    private companion object {
        const val LATIN_IME = "com.android.inputmethod.latin/.LatinIME"

        /**
         * Verbatim excerpt of a real `dumpsys input_method` state block from the
         * API-35 emulator this suite runs on (`emulator-5554`, 2026-07-29). Using
         * the real text — not a hand-written approximation — is what makes the
         * parser's key names load-bearing: a format drift breaks the fixture
         * rather than silently returning "healthy".
         */
        val HEALTHY_DUMP: String = """
              mCurrentUserId=0
              mCurMethodId=com.android.inputmethod.latin/.LatinIME
              mCurClient=ClientState{399e510 mUid=10121 mPid=1229 mSelfReportedDisplayId=0} mCurSeq=9627
              mFocusedWindow()=android.os.BinderProxy@7702f31
              softInputMode=STATE_UNSPECIFIED|ADJUST_NOTHING
              mCurId=com.android.inputmethod.latin/.LatinIME mHaveConnection=true mBoundToMethod=true mVisibleBound=false
              mCurToken=android.os.Binder@e9ad5ca
              mCurTokenDisplayId=0
              mCurMethod=com.android.server.inputmethod.IInputMethodInvoker@1c0ac1d
              mRequestedShowExplicitly=false mShowForced=false
              mImeHiddenByDisplayPolicy=false
              mInputShown=false
              mInFullscreenMode=false
              mSystemReady=true mInteractive=true
        """.trimIndent()
    }
}
