package com.pocketshell.next.connect

/**
 * Process-local handoff for a private-key passphrase.
 *
 * The unlock UI gives the resolver a [CharArray] only for the next connection
 * attempt. Implementations must copy it into volatile memory, never persist it,
 * and wipe the copy when [clearPassphrase] is called. Keeping this as a small
 * interface prevents the connection ViewModel from owning or logging secret
 * material while still letting the native unlock surface hand it to sshj.
 */
interface SshKeyUnlocker {

    /** Replace the current transient passphrase with a scrubbed copy of [value]. */
    fun rememberPassphrase(keyId: Long, value: CharArray)

    /** Return a copy for one dial. The caller owns and must scrub that copy. */
    fun copyPassphrase(keyId: Long): CharArray?

    /** Wipe and forget the transient value for [keyId]. */
    fun clearPassphrase(keyId: Long)
}
