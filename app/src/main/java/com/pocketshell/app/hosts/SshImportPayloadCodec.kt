package com.pocketshell.app.hosts

import org.json.JSONException
import org.json.JSONObject

data class SshImportConfig(
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val auth: SshImportAuth,
)

sealed interface SshImportAuth {
    data class PrivateKey(
        val name: String,
        val privateKeyPem: String,
        val passphraseRequired: Boolean,
    ) : SshImportAuth

    data class KeyReference(
        val name: String,
    ) : SshImportAuth
}

object SshImportPayloadCodec {
    const val Type = "pocketshell.ssh-import.v1"
    const val Version = 1
    const val MaxPayloadBytes = 12 * 1024

    fun decode(payload: String): Result<SshImportConfig> = runCatching {
        val trimmed = payload.trim()
        if (trimmed.toByteArray(Charsets.UTF_8).size > MaxPayloadBytes) {
            throw IllegalArgumentException(
                "PocketShell SSH host payload (pocketshell.ssh-import.v1) is too large",
            )
        }
        val json = JSONObject(trimmed)
        if (json.optString("type") != Type) {
            throw IllegalArgumentException(
                "Expected PocketShell SSH host payload (pocketshell.ssh-import.v1)",
            )
        }
        if (json.optInt("version", -1) != Version) {
            throw IllegalArgumentException("Unsupported SSH import payload version")
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
        if (error is JSONException) {
            throw IllegalArgumentException(
                "Expected PocketShell SSH host payload (pocketshell.ssh-import.v1) JSON",
                error,
            )
        }
        throw error
    }

    fun encode(config: SshImportConfig): String {
        validate(config)
        val auth = when (val value = config.auth) {
            is SshImportAuth.PrivateKey -> JSONObject()
                .put("type", "privateKey")
                .put("name", value.name)
                .put("privateKeyPem", value.privateKeyPem)
                .put("passphraseRequired", value.passphraseRequired)

            is SshImportAuth.KeyReference -> JSONObject()
                .put("type", "keyRef")
                .put("name", value.name)
        }
        return JSONObject()
            .put("type", Type)
            .put("version", Version)
            .put("name", config.name)
            .put("host", config.host)
            .put("port", config.port)
            .put("username", config.username)
            .put("auth", auth)
            .toString()
    }

    private fun readName(json: JSONObject): String =
        json.optString("name").trim().ifBlank { readHost(json) }

    private fun readHost(json: JSONObject): String =
        (if (json.has("host")) json.getString("host") else json.getString("hostname")).trim()

    private fun readAuth(json: JSONObject): SshImportAuth {
        return when (json.getString("type")) {
            "privateKey" -> {
                val pem = json.getString("privateKeyPem").trim()
                SshImportAuth.PrivateKey(
                    name = json.optString("name").trim().ifBlank { "imported-key" },
                    privateKeyPem = pem,
                    passphraseRequired = SshKeyStorage.hasPrivateKeyPassphrase(pem),
                )
            }

            "keyRef" -> SshImportAuth.KeyReference(
                name = json.getString("name").trim(),
            )

            else -> throw IllegalArgumentException("Unsupported SSH import auth type")
        }
    }

    private fun readPort(json: JSONObject): Int {
        if (!json.has("port")) return 22
        val value = json.get("port")
        return when (value) {
            is Int -> value
            is Number -> value.toInt().takeIf { value.toDouble() == it.toDouble() }
            else -> null
        } ?: throw IllegalArgumentException("SSH import payload has an invalid port")
    }

    private fun validate(config: SshImportConfig) {
        if (config.name.isBlank() || config.host.isBlank() || config.username.isBlank()) {
            throw IllegalArgumentException("SSH import payload is missing required fields")
        }
        if (config.port !in 1..65535) {
            throw IllegalArgumentException("SSH import payload has an invalid port")
        }
        if (config.host.any { it.isWhitespace() || it.isISOControl() }) {
            throw IllegalArgumentException("SSH import host is invalid")
        }
        when (val auth = config.auth) {
            is SshImportAuth.PrivateKey -> {
                if (auth.name.isBlank()) {
                    throw IllegalArgumentException("SSH import key name is required")
                }
                if (!SshKeyStorage.looksLikePrivateKey(auth.privateKeyPem)) {
                    throw IllegalArgumentException("SSH import private key is invalid")
                }
            }

            is SshImportAuth.KeyReference -> {
                if (auth.name.isBlank()) {
                    throw IllegalArgumentException("SSH import key reference is required")
                }
            }
        }
    }
}
