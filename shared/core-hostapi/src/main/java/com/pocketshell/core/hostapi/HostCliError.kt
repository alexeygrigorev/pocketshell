package com.pocketshell.core.hostapi

/**
 * Why a host-CLI response could not be turned into a model.
 *
 * Parsers in this module return `Result<T>`, and the failure is always one of
 * these — never a raw [kotlinx.serialization.SerializationException] the caller
 * has to catch blind and stringify. Callers `when`-branch on the subtype:
 * [TooOld] drives the "update the host CLI" bootstrap flow, [Malformed] is a
 * bug/corruption report.
 *
 * It extends [Exception] only so it can travel inside `Result.failure`; nothing
 * in this module throws it.
 */
sealed class HostCliError(
    /** Message safe to show a user as-is. */
    val userMessage: String,
    cause: Throwable? = null,
) : Exception(userMessage, cause) {

    /**
     * The host CLI answered with an older schema than this app can read.
     *
     * Hard cut, per the repo's no-backwards-compatibility rule (D22): there is
     * no schema-1 fallback parser. The caller's job is to tell the user to
     * update the host CLI (`pipx upgrade pocketshell` / the in-app bootstrap
     * upgrade flow), not to degrade quietly.
     */
    class TooOld(
        val foundSchema: Int,
        val requiredSchema: Int,
    ) : HostCliError(
        "The pocketshell CLI on the host is too old (reports schema $foundSchema, " +
            "this app needs schema $requiredSchema or newer). Update it on the host " +
            "and try again.",
    )

    /**
     * The payload was not a readable schema-2 document: not JSON at all, not an
     * object, missing `schema`, or a session row with a missing/mistyped
     * required field.
     *
     * A single bad row fails the WHOLE listing on purpose. Skipping it would
     * hand the UI a list that is silently short — the same class of lie as a
     * dropped backend error.
     */
    class Malformed(
        val detail: String,
        cause: Throwable? = null,
    ) : HostCliError("Could not read the host's response: $detail", cause)
}
