package com.pocketshell.next.hosts

/** Key types PocketShell can create locally. */
enum class SshKeyGenerationType(
    val label: String,
    val description: String,
    internal val javaAlgorithm: String,
) {
    ED25519(
        label = "ED25519",
        description = "Modern default for new servers",
        javaAlgorithm = "Ed25519",
    ),
    RSA(
        label = "RSA 3072",
        description = "Broad compatibility with older servers",
        javaAlgorithm = "RSA",
    ),
}

/** The protection selected for a newly generated private key. */
enum class SshKeyProtection(
    val label: String,
    val description: String,
) {
    NONE(
        label = "No passphrase",
        description = "The key can be used without an unlock prompt",
    ),
    PASSPHRASE(
        label = "Passphrase protected",
        description = "Ask for a passphrase before the key is used",
    ),
}

/**
 * One generate request. The passphrase is transient input, never UI state or
 * persisted metadata; callers copy and scrub it at the hand-off boundary.
 */
class SshKeyGenerationRequest(
    val name: String,
    val type: SshKeyGenerationType = SshKeyGenerationType.ED25519,
    val protection: SshKeyProtection = SshKeyProtection.NONE,
    val passphrase: CharArray? = null,
)
