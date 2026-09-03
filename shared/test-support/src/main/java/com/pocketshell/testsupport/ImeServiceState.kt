package com.pocketshell.testsupport

/**
 * Issue #1879: the **captured IME-service-state signature**.
 *
 * ## The state this exists to name
 *
 * A journey that asserts "one tap on the show-keyboard chip raises the REAL
 * soft keyboard" can fail with the byte-identical message
 * `ime_visible_after_tap=false raisedMs=45052` in at least three different
 * worlds, only one of which is a product defect:
 *
 *  1. **The chip is broken.** The app window holds input focus, the system
 *     input-method service is healthy and bound, `showSoftInput` is accepted —
 *     and the keyboard still does not come up. This is the contract failing,
 *     and it must stay RED.
 *  2. **No app window holds input focus** (a foreign error dialog owns it), so
 *     the framework *refuses* `showSoftInput` outright with
 *     `Ignoring showSoftInput() as view=… is not served`. Named by
 *     `FOREIGN_WINDOW_FOCUS_SIGNATURE` on the androidTest side.
 *  3. **The IME service itself never raises.** The window IS focused, the
 *     request IS accepted client-side — but the input-method service is
 *     unbound, still starting, crash-looping, or there is no enabled/default
 *     IME at all. Nothing about the chip is observable in that world either.
 *
 * State 3 is live on the CI AVD fleet, not hypothetical: the failing attempt of
 * run 30454838433 carried repeated
 * `ApkAssets: Deleting an ApkAssets object … LatinIMEGooglePrebuilt.apk` churn,
 * and #1800 exists precisely because that emulator sometimes cannot raise a
 * physical IME window at all.
 *
 * This file is the pure, dependency-free half of the discriminator so it can be
 * pinned by a fast JVM unit test in the required per-PR `Unit tests` job. The
 * androidTest side (`ImeServiceStateSignals.kt`) only does the IO —
 * `dumpsys input_method`, `Settings.Secure.DEFAULT_INPUT_METHOD`, and the
 * enabled-IME list — and hands the text here.
 *
 * ## Deliberately NOT an auto-INFRA signature
 *
 * [IME_SERVICE_UNAVAILABLE_SIGNATURE] is a *diagnosis carried in the failure
 * message and the summary artifact*. It is NOT registered in
 * `the CI journey infra-signature classifier (deleted with the per-class lane)`, so it can never downgrade a shard to
 * INFRA. Every additional auto-INFRA net is a masking risk; this one would be
 * keyed on state the app itself can influence, so it stays loud. Its job is to
 * make the next red *readable*, not to make it disappear.
 *
 * ## Which fields decide "unavailable", and which are diagnostics only
 *
 * Only **service-side** facts decide: `mSystemReady`, the enabled-IME list, the
 * configured default IME, and whether the input-method manager service is
 * currently bound to an IME (`mCurMethodId` / `mCurMethod` / `mHaveConnection`).
 *
 * `mBoundToMethod` and `mInputShown` are recorded but deliberately do NOT
 * decide, because they are **client**-side: they also read false in state 2,
 * where no window is served at all. Letting them decide would relabel a
 * focus-loss (or a genuine chip failure that never established an input
 * connection) as "the IME service is broken", i.e. exactly the misattribution
 * #1879 is about, pointed the other way.
 */

/**
 * Stable, greppable prefix emitted when the input-method SERVICE could not have
 * raised a keyboard for anyone. Kept as a constant so a CI failure carries one
 * phrase distinguishing "this AVD has no working IME" from "the control under
 * test did not work".
 */
const val IME_SERVICE_UNAVAILABLE_SIGNATURE: String =
    "The system input-method SERVICE could not have raised a keyboard for any " +
        "app (no enabled/default IME, or the service is not bound or not ready), " +
        "so this is NOT a show-keyboard-chip failure."

/** Verdict on whether the IME service was in a position to raise a window. */
enum class ImeServiceHealth {
    /** The service is ready and bound to an enabled IME. */
    AVAILABLE,

    /** The service provably could not have raised a window. */
    UNAVAILABLE,

    /**
     * The evidence was missing or unreadable. Treated as "not an excuse": the
     * caller keeps the ordinary product-failure wording. Fail-safe is toward
     * RED, never toward a green-looking excuse.
     */
    UNKNOWN,
}

/** Parsed, presentation-ready view of `dumpsys input_method` + IME settings. */
data class ImeServiceState(
    val health: ImeServiceHealth,
    val reasons: List<String>,
    val curMethodId: String?,
    val curMethodBound: Boolean?,
    val systemReady: Boolean?,
    val boundToMethod: Boolean?,
    val inputShown: Boolean?,
    val defaultInputMethod: String?,
    val enabledInputMethodIds: List<String>,
) {
    /** True only when the service provably could not have raised a window. */
    val serviceUnavailable: Boolean get() = health == ImeServiceHealth.UNAVAILABLE

    /** One-line, artifact-friendly rendering for a summary file or a message. */
    fun describe(): String = buildString {
        append("ime_service_health=").append(health.name.lowercase())
        append(" cur_method_id=").append(curMethodId ?: "<absent>")
        append(" cur_method_bound=").append(curMethodBound?.toString() ?: "<absent>")
        append(" system_ready=").append(systemReady?.toString() ?: "<absent>")
        append(" bound_to_method=").append(boundToMethod?.toString() ?: "<absent>")
        append(" input_shown=").append(inputShown?.toString() ?: "<absent>")
        append(" default_ime=").append(defaultInputMethod ?: "<absent>")
        append(" enabled_ime_count=").append(enabledInputMethodIds.size)
        if (reasons.isNotEmpty()) {
            append(" reasons=").append(reasons.joinToString(separator = ","))
        }
    }
}

private val BOOLEAN_KEYS = listOf("mSystemReady", "mBoundToMethod", "mInputShown", "mHaveConnection")

/**
 * Parse the service-side IME state.
 *
 * @param dumpsysInputMethod raw `dumpsys input_method` text, or null when the
 *   dump could not be taken.
 * @param defaultInputMethod `Settings.Secure.DEFAULT_INPUT_METHOD`, or null.
 * @param enabledInputMethodIds ids from `InputMethodManager.getEnabledInputMethodList()`,
 *   or null when the list could not be read (which is *unknown*, not empty).
 */
fun parseImeServiceState(
    dumpsysInputMethod: String?,
    defaultInputMethod: String?,
    enabledInputMethodIds: List<String>?,
): ImeServiceState {
    val dump = dumpsysInputMethod?.takeIf { it.isNotBlank() }
    val curMethodId = dump?.let { readValue(it, "mCurMethodId") }
    val curMethod = dump?.let { readValue(it, "mCurMethod") }
    val booleans = BOOLEAN_KEYS.associateWith { key -> dump?.let { readBoolean(it, key) } }
    val systemReady = booleans["mSystemReady"]
    val boundToMethod = booleans["mBoundToMethod"]
    val inputShown = booleans["mInputShown"]
    val haveConnection = booleans["mHaveConnection"]

    // `mCurMethod` is the live binder to the IME process; `mHaveConnection` is
    // the service connection to it. Deliberately asymmetric: EITHER reading
    // positive counts as bound, and only BOTH reading known-negative counts as
    // unbound. A transiently-unbound-but-healthy service (IMMS drops the
    // binding when no client needs it) must not be reported as broken — a
    // wrong environmental label on a genuine chip failure would be this
    // issue's own misattribution pointed the other way.
    val curMethodBound: Boolean? = when {
        haveConnection == null && curMethod == null -> null
        else -> (haveConnection == true) || curMethod.isPresent()
    }

    val normalisedDefault = defaultInputMethod?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    val reasons = mutableListOf<String>()

    // Evidence sufficiency first: with no dump AND no enabled-IME list there is
    // nothing to reason from, and inventing an excuse would be worse than
    // saying so (the caller then keeps the ordinary product-failure wording).
    val haveEvidence = dump != null || enabledInputMethodIds != null
    if (!haveEvidence) {
        return ImeServiceState(
            health = ImeServiceHealth.UNKNOWN,
            reasons = listOf("no_ime_service_evidence"),
            curMethodId = null,
            curMethodBound = null,
            systemReady = null,
            boundToMethod = null,
            inputShown = null,
            defaultInputMethod = normalisedDefault,
            enabledInputMethodIds = emptyList(),
        )
    }

    if (enabledInputMethodIds != null && enabledInputMethodIds.isEmpty()) {
        reasons += "no_enabled_input_methods"
    }
    if (enabledInputMethodIds != null && normalisedDefault == null) {
        reasons += "no_default_input_method"
    }
    if (systemReady == false) {
        reasons += "input_method_service_not_ready"
    }
    // Only meaningful when the key is actually PRESENT and reads null/none. A
    // dump whose format no longer carries the key at all is unreadable
    // evidence, not proof that no IME is selected.
    if (dump != null && dump.contains("mCurMethodId=") && !curMethodId.isPresent()) {
        reasons += "no_current_input_method_selected"
    }
    if (curMethodBound == false) {
        reasons += "input_method_service_not_bound"
    }

    val health = when {
        reasons.isNotEmpty() -> ImeServiceHealth.UNAVAILABLE
        // A readable dump with none of the service-side keys present is not
        // evidence of health — the format changed or the dump was truncated.
        dump != null && systemReady == null && curMethodId == null && curMethodBound == null ->
            ImeServiceHealth.UNKNOWN
        else -> ImeServiceHealth.AVAILABLE
    }
    val finalReasons = if (health == ImeServiceHealth.UNKNOWN && reasons.isEmpty()) {
        listOf("ime_service_dump_unreadable")
    } else {
        reasons.toList()
    }

    return ImeServiceState(
        health = health,
        reasons = finalReasons,
        curMethodId = curMethodId,
        curMethodBound = curMethodBound,
        systemReady = systemReady,
        boundToMethod = boundToMethod,
        inputShown = inputShown,
        defaultInputMethod = normalisedDefault,
        enabledInputMethodIds = enabledInputMethodIds.orEmpty(),
    )
}

private fun String?.isPresent(): Boolean =
    this != null && isNotBlank() && this != "null" && this != "none"

private fun readValue(dump: String, key: String): String? {
    val regex = Regex("""\b${Regex.escape(key)}=(\S+)""")
    return regex.find(dump)?.groupValues?.get(1)
}

private fun readBoolean(dump: String, key: String): Boolean? =
    when (readValue(dump, key)?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
