package com.pocketshell.app.usage

import com.pocketshell.app.messaging.FcmTokenRegistrar

/**
 * The slice of [FcmTokenRegistrar] the Usage screen needs to deliver the FCM
 * device token over its live foreground SSH session (issue #690 R3).
 *
 * Factored to an interface so [UsageViewModel] can be unit-tested with a fake
 * registrar (no Android `SharedPreferences` / `Context`), while production
 * delegates to the real [FcmTokenRegistrar] via [Fcm].
 *
 * D21 (foreground-only): the token is NOT uploaded from a background service.
 * The Usage refresh — already a live foreground SSH path — is the carrier.
 */
public interface PushTokenRegistrar {
    /** The freshest cached device token, or null if FCM hasn't minted one. */
    public fun pendingToken(): String?

    /** True when a cached token has not yet been delivered to the host. */
    public fun needsRegistration(): Boolean

    /** Mark the cached token as successfully delivered to the host. */
    public fun markRegistered()

    /** Build the host command that registers [token] (PATH-robust wrapper). */
    public fun registerCommand(token: String): String

    /** Production adapter delegating to the real [FcmTokenRegistrar]. */
    public class Fcm(private val delegate: FcmTokenRegistrar) : PushTokenRegistrar {
        override fun pendingToken(): String? = delegate.pendingToken()
        override fun needsRegistration(): Boolean = delegate.needsRegistration()
        override fun markRegistered(): Unit = delegate.markRegistered()
        override fun registerCommand(token: String): String =
            FcmTokenRegistrar.registerCommand(token)
    }
}
