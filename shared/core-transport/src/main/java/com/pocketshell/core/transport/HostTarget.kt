package com.pocketshell.core.transport

/**
 * Everything needed to dial one SSH host.
 *
 * [hostId] is the Room host row id in `shared/core-storage`; it is the identity
 * the layers above use to key the one-connection-per-host registry, so two
 * targets for the same row are the same connection even if the hostname string
 * differs.
 */
data class HostTarget(
    val hostId: Long,
    val hostname: String,
    val port: Int,
    val username: String,
    val auth: AuthMaterial,
)

/**
 * A *reference* to credential material, never the material itself.
 *
 * Resolving a reference to a private key or a password is the job of the layer
 * that owns the secret store (core-storage's SSH key table / the encrypted
 * preference store); this module only carries the handle so a [HostTarget] can
 * be logged, compared, and held in memory without holding a secret.
 */
sealed interface AuthMaterial {
    /** Room `ssh_keys` row id of the private key to authenticate with. */
    data class KeyRef(val keyId: Long) : AuthMaterial

    /**
     * Opaque handle the credential store resolves to a password. It is a
     * lookup key (e.g. an encrypted-preferences entry name), not the password.
     */
    data class Password(val secretRef: String) : AuthMaterial
}
