package com.pocketshell.next.hosts

import org.json.JSONException
import org.json.JSONObject

/** One host's connection details, as carried by a `pocketshell.ssh-import.v1` payload. */
data class SshImportConfig(
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val auth: SshImportAuth,
)

/** How the imported host authenticates. */
sealed interface SshImportAuth {

    /**
     * The payload carries key material. Produced by the desktop emitter
     * (`pocketshell qr-share`), never by this app's export — see
     * [SshImportPayloadCodec] for why.
     */
    data class PrivateKey(val name: String, val privateKeyPem: String) : SshImportAuth

    /** The payload names a key the importing device is expected to already have. */
    data class KeyReference(val name: String) : SshImportAuth
}

/**
 * Codec for the `pocketshell.ssh-import.v1` payload (`docs/ssh-qr-import.md`),
 * ported verbatim in wire terms from the old client so QRs produced by
 * `pocketshell qr-share`, by the shipping app, and by app2 all interoperate.
 *
 * ## Security stance (preserved from the old client, deliberately)
 *
 * Decoding accepts both auth shapes, because a desktop emitter's whole job is
 * to hand over a key the phone does not have yet. **Encoding for share-out uses
 * [SshImportAuth.KeyReference] only** ([HostQrShareViewModel]): a QR displayed
 * on a phone screen is a visible secret, and there is no reason for
 * PocketShell's own export to put a private key on it — the receiving device
 * needs the key imported by a path the user controls.
 *
 * ## Difference from the old codec
 *
 * `SshImportAuth.PrivateKey` no longer carries a `passphraseRequired` flag. The
 * documented format never had the field on the wire (it was always derived from
 * the key bytes), and app2 rejects encrypted keys outright at
 * [SshKeyStore.importKey] rather than recording a flag nothing can act on.
 */
object SshImportPayloadCodec {

    const val TYPE: String = "pocketshell.ssh-import.v1"
    const val VERSION: Int = 1

    /**
     * Upper bound on an accepted payload. A QR cannot carry anything close to
     * this, so the limit exists to stop a hostile deep link / file from being
     * parsed as a multi-megabyte JSON document.
     */
    const val MAX_PAYLOAD_BYTES: Int = 12 * 1024

    /**
     * Parse [payload]. Every rejection — wrong type, wrong version, missing
     * field, bad port, non-key "key" — comes back as a failed [Result] carrying
     * a user-readable message, never as a thrown exception at the call site.
     */
    fun decode(payload: String): Result<SshImportConfig> = runCatching {
        val trimmed = payload.trim()
        require(trimmed.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "PocketShell SSH host payload ($TYPE) is too large"
        }
        val json = JSONObject(trimmed)
        require(json.optString("type") == TYPE) {
            "Expected a PocketShell SSH host payload ($TYPE)"
        }
        require(json.optInt("version", -1) == VERSION) {
            "Unsupported SSH import payload version"
        }
        val config = SshImportConfig(
            name = readName(json),
            host = readHost(json),
            port = readPort(json),
            username = json.getString("username").trim(),
            auth = readAuth(json.getJSONObject("auth")),
        )
        validate(config)
        config
    }.recoverCatching { error ->
        // A malformed/foreign JSON document surfaces as a JSONException from
        // deep inside org.json; translate it once, here, so no caller has to
        // decide what "No value for auth" means to a user.
        if (error is JSONException) {
            throw IllegalArgumentException("Expected a PocketShell SSH host payload ($TYPE) in JSON", error)
        }
        throw error
    }

    /** Serialise [config]. Throws if the config is not valid to begin with. */
    fun encode(config: SshImportConfig): String {
        validate(config)
        val auth = when (val value = config.auth) {
            is SshImportAuth.PrivateKey -> JSONObject()
                .put("type", "privateKey")
                .put("name", value.name)
                .put("privateKeyPem", value.privateKeyPem)

            is SshImportAuth.KeyReference -> JSONObject()
                .put("type", "keyRef")
                .put("name", value.name)
        }
        return JSONObject()
            .put("type", TYPE)
            .put("version", VERSION)
            .put("name", config.name)
            .put("host", config.host)
            .put("port", config.port)
            .put("username", config.username)
            .put("auth", auth)
            .toString()
    }

    private fun readName(json: JSONObject): String =
        json.optString("name").trim().ifBlank { readHost(json) }

    /** `hostname` is accepted as an alias for `host`, per the documented format. */
    private fun readHost(json: JSONObject): String =
        (if (json.has("host")) json.getString("host") else json.getString("hostname")).trim()

    private fun readPort(json: JSONObject): Int {
        if (!json.has("port")) return DEFAULT_PORT
        val value = json.get("port")
        val port = when (value) {
            is Int -> value
            // `22.0` is a valid JSON number but not a valid port; only accept a
            // fractionless one rather than silently truncating.
            is Number -> value.toInt().takeIf { value.toDouble() == it.toDouble() }
            else -> null
        }
        return port ?: throw IllegalArgumentException("SSH import payload has an invalid port")
    }

    private fun readAuth(json: JSONObject): SshImportAuth = when (json.getString("type")) {
        "privateKey" -> SshImportAuth.PrivateKey(
            name = json.optString("name").trim().ifBlank { "imported-key" },
            privateKeyPem = json.getString("privateKeyPem").trim(),
        )

        "keyRef" -> SshImportAuth.KeyReference(name = json.getString("name").trim())

        else -> throw IllegalArgumentException("Unsupported SSH import auth type")
    }

    private fun validate(config: SshImportConfig) {
        require(
            config.name.isNotBlank() && config.host.isNotBlank() && config.username.isNotBlank(),
        ) { "SSH import payload is missing required fields" }
        require(config.port in MIN_PORT..MAX_PORT) { "SSH import payload has an invalid port" }
        require(config.host.none { it.isWhitespace() || it.isISOControl() }) {
            "SSH import host is invalid"
        }
        when (val auth = config.auth) {
            is SshImportAuth.PrivateKey -> {
                require(auth.name.isNotBlank()) { "SSH import key name is required" }
                require(SshKeyMaterial.looksLikePrivateKey(auth.privateKeyPem)) {
                    "SSH import private key is invalid"
                }
            }

            is SshImportAuth.KeyReference ->
                require(auth.name.isNotBlank()) { "SSH import key reference is required" }
        }
    }

    private const val DEFAULT_PORT = 22
    private const val MIN_PORT = 1
    private const val MAX_PORT = 65535
}
