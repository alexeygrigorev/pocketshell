package com.pocketshell.app.hosts

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.SshKeyEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Backs [SshKeysManagementPane]. Owns the live list of registered keys + the
 * "add key" flow.
 *
 * Two add paths in scope for #18:
 *
 * - **Import**: the user picks a file via SAF; we read its content,
 *   sanity-check the PEM header, and copy the bytes into the app's
 *   `filesDir/ssh-keys/<name>` so the on-disk reference is stable even
 *   if the source URI later disappears.
 * - **Generate**: the issue's scope mentions "generate new on-device
 *   (sshj has key generation utilities)". The JVM's
 *   `java.security.KeyPairGenerator` ships RSA generation in every
 *   Android image; we use it to produce a 3072-bit RSA key (the
 *   default for `ssh-keygen` on most distros today), write the private
 *   half in PKCS#8 PEM format, and skip the public key — sshj derives
 *   it from the private one for the formats we hand it. RSA was picked
 *   over Ed25519 because Android's `KeyPairGenerator` exposes RSA out
 *   of the box; Ed25519 requires BouncyCastle's provider, which is
 *   shaded by sshj but not on the default Android security stack.
 *
 * Both paths flow through [SshKeyStorage.persistKey] so the on-disk layout and DB row
 * shape stay symmetrical.
 */
@HiltViewModel
class SshKeysViewModel @Inject constructor(
    private val sshKeyDao: SshKeyDao,
) : ViewModel() {

    /** Live list of registered keys, sorted by name (DAO query). */
    val keys: StateFlow<List<SshKeyEntity>> = sshKeyDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private val _error: MutableStateFlow<String?> = MutableStateFlow(null)

    /** Last user-facing error, or `null` if the screen is clean. */
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Clear the error label — the screen calls this when the user dismisses. */
    fun clearError() {
        _error.value = null
    }

    /**
     * Import a private key from a SAF [Uri]. Reads the content into memory,
     * verifies the PEM header looks like a private key, persists to disk +
     * DB. Errors surface via [error]; a successful import clears any
     * lingering error from a previous failed attempt (issue #38).
     */
    fun importKey(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader(Charsets.UTF_8).readText()
                    }
                } ?: run {
                    _error.value = "Could not open the selected file"
                    return@launch
                }

                val trimmed = content.trim()
                if (!SshKeyStorage.looksLikePrivateKey(trimmed)) {
                    _error.value = "File does not look like an SSH private key " +
                        "(missing the -----BEGIN ... PRIVATE KEY----- header)"
                    return@launch
                }

                val fileName = resolveDisplayName(context, uri) ?: "imported-key"
                SshKeyStorage.persistKey(
                    context = context,
                    sshKeyDao = sshKeyDao,
                    name = fileName,
                    content = trimmed,
                    hasPassphrase = SshKeyStorage.hasPrivateKeyPassphrase(trimmed),
                )
                // Successful add: explicitly drop any stale error the
                // banner might still be carrying from a prior failure
                // (issue #38 item 4).
                _error.value = null
            } catch (t: Throwable) {
                _error.value = "Failed to import key: ${t.message}"
            }
        }
    }

    /**
     * Generate a fresh RSA-3072 key pair on-device, write the private half
     * to app storage in PKCS#8 PEM, and register it in the DB.
     *
     * Naming: `generated-<timestamp>` keeps the list readable when the user
     * generates several in a row. Renaming will land with a future polish
     * pass — the entity has a `name` field so swapping is trivial.
     *
     * On success any lingering [error] from a previous failed operation
     * is cleared (issue #38 item 4).
     */
    fun generateKey(context: Context) {
        viewModelScope.launch {
            try {
                val pem = withContext(Dispatchers.IO) { generateRsaPrivateKeyPem() }
                val name = "generated-${System.currentTimeMillis()}"
                SshKeyStorage.persistKey(
                    context = context,
                    sshKeyDao = sshKeyDao,
                    name = name,
                    content = pem,
                    hasPassphrase = false,
                )
                _error.value = null
            } catch (t: Throwable) {
                _error.value = "Failed to generate key: ${t.message}"
            }
        }
    }

    /**
     * Delete the given key. Hosts that reference it cascade-delete via the
     * FK constraint declared on [com.pocketshell.core.storage.entity.HostEntity];
     * the user is warned in [SshKeysManagementPane]'s confirmation dialog.
     *
     * Order matters (issue #38 item 5): the on-disk file is removed FIRST,
     * then the DB row. If the file delete fails the row stays so the user
     * can retry from the same UI surface — the alternative (DB-first) would
     * orphan the file silently and lose the row pointer to it. A successful
     * delete also clears any stale [error] banner.
     */
    fun deleteKey(key: SshKeyEntity) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Best-effort: a missing file is fine (already-deleted
                    // race), a permission-denied is not — `runCatching`
                    // would mask the latter, so we let `delete()` return
                    // its boolean and only fail on the existence-check.
                    val file = java.io.File(key.privateKeyPath)
                    if (file.exists() && !file.delete()) {
                        throw java.io.IOException(
                            "Could not delete key file: ${file.absolutePath}",
                        )
                    }
                }
                sshKeyDao.delete(key)
                _error.value = null
            } catch (t: Throwable) {
                _error.value = "Failed to delete key: ${t.message}"
            }
        }
    }

    /** Read the display name for a SAF URI; fall back to the last path segment. */
    private fun resolveDisplayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
        }
        return uri.lastPathSegment?.substringAfterLast("/")
    }

    /**
     * Generate an RSA-3072 key and return the private half in PKCS#8 PEM.
     *
     * sshj's `loadKeys(String, ...)` autodetects format; PKCS#8 PEM is one
     * of the formats it handles via the `PKCS8KeyFile` reader. The output
     * is wrapped in `-----BEGIN PRIVATE KEY-----` / `-----END PRIVATE KEY-----`
     * with the Base64 body chunked at 64 chars per line — same shape that
     * `ssh-keygen -m PKCS8` emits.
     */
    private fun generateRsaPrivateKeyPem(): String {
        val gen = java.security.KeyPairGenerator.getInstance("RSA")
        gen.initialize(3072)
        val keyPair = gen.generateKeyPair()
        val encoded = keyPair.private.encoded // already PKCS#8 for RSA
        val base64 = java.util.Base64.getEncoder().encodeToString(encoded)
        val chunked = base64.chunked(64).joinToString("\n")
        return buildString {
            appendLine("-----BEGIN PRIVATE KEY-----")
            append(chunked)
            appendLine()
            appendLine("-----END PRIVATE KEY-----")
        }
    }

    internal fun hasPrivateKeyPassphrase(content: String): Boolean =
        SshKeyStorage.hasPrivateKeyPassphrase(content)
}
