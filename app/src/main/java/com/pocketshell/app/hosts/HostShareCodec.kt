package com.pocketshell.app.hosts

import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import org.json.JSONException
import org.json.JSONObject

data class SharedHostConfig(
    val name: String,
    val hostname: String,
    val port: Int,
    val username: String,
    val keyName: String,
)

object HostShareCodec {
    private const val Type = "pocketshell.host.v1"

    fun encode(host: HostEntity, key: SshKeyEntity): String = JSONObject()
        .put("type", Type)
        .put("name", host.name)
        .put("hostname", host.hostname)
        .put("port", host.port)
        .put("username", host.username)
        .put("keyName", key.name)
        .toString()

    fun decode(payload: String): Result<SharedHostConfig> = runCatching {
        val json = JSONObject(payload.trim())
        if (json.optString("type") != Type) {
            throw IllegalArgumentException("Not a PocketShell host share")
        }
        val config = SharedHostConfig(
            name = json.getString("name").trim(),
            hostname = json.getString("hostname").trim(),
            port = readPort(json),
            username = json.getString("username").trim(),
            keyName = json.getString("keyName").trim(),
        )
        if (config.name.isBlank() || config.hostname.isBlank() ||
            config.username.isBlank() || config.keyName.isBlank()
        ) {
            throw IllegalArgumentException("Shared host is missing required fields")
        }
        if (config.port !in 1..65535) {
            throw IllegalArgumentException("Shared host has an invalid port")
        }
        config
    }.recoverCatching { error ->
        if (error is JSONException) {
            throw IllegalArgumentException("Shared host payload is not valid JSON", error)
        }
        throw error
    }

    private fun readPort(json: JSONObject): Int {
        if (!json.has("port")) return 22
        val value = json.get("port")
        return when (value) {
            is Int -> value
            is Number -> value.toInt().takeIf { value.toDouble() == it.toDouble() }
            else -> null
        } ?: throw IllegalArgumentException("Shared host has an invalid port")
    }
}
