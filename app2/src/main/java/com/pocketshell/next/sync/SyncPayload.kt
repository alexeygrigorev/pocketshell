package com.pocketshell.next.sync

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The plaintext the envelope encrypts, and the selection rules that decide
 * what goes into it — the Android port of the desktop app's
 * `src/shared/syncMerge.ts` (issue #2633).
 *
 * The wire shape is fixed by the desktop app and must not drift: one JSON
 * object, one key, `{"hosts":[ ... ]}`, one slot (`main`), whole list per sync.
 *
 * ## Sync is SELECTIVE
 *
 * The payload is the ticked aliases and nothing else — a host the user has not
 * ticked never leaves the device, encrypted or otherwise. That is the privacy
 * property, and it is why the payload is ASSEMBLED rather than merged: pushing
 * replaces the account's content with the ticked set.
 *
 * The account is part of the selection rather than a rival to it: aliases
 * pulled from the account tick themselves on ([aliasesToAutoCheck]) — but only
 * ones the local host list lacks, so an alias this device can see is one the
 * user has decided about and their untick stands. Together those give the two
 * flows that matter: a fresh device pulls and auto-ticks everything, so its
 * next push re-uploads the account instead of wiping it; and removing a host
 * from the account is untick + sync, nowhere else.
 */

/**
 * One host entry as it travels in the payload.
 *
 * [extras] is the deliberate part. The desktop app's entries carry fields this
 * client has no concept of (`proxyJump`, `localForwards`, `identityFile`, …).
 * If the phone re-uploaded an account entry as only the four fields it
 * understands, a single "Sync now" from a phone would quietly strip the
 * laptop's jump hosts and forwards. So every field that is not one of the four
 * below is carried through verbatim and re-emitted on the next push.
 *
 * Values are plain Kotlin (`String`/`Number`/`Boolean`/`List`/`Map`/null)
 * rather than `JSONObject`, which has identity equality — a data class holding
 * one could never compare equal to itself after a round-trip.
 */
data class SyncHostEntry(
    val name: String,
    val hostname: String,
    val port: Int = DEFAULT_PORT,
    val user: String = "",
    val extras: Map<String, Any?> = emptyMap(),
) {
    companion object {
        const val DEFAULT_PORT: Int = 22
    }
}

/** Serialize the payload the envelope encrypts — the envelope's plaintext. */
fun serializeSyncPayload(hosts: List<SyncHostEntry>): String {
    val array = JSONArray()
    hosts.forEach { array.put(hostToJson(it)) }
    return JSONObject().put("hosts", array).toString()
}

/**
 * Parse a pulled plaintext, degraded: anything that is not a payload with a
 * plausible host array parses to an EMPTY list, not an error — a blob is user
 * data from possibly an older build, and an empty list is the safe outcome (no
 * aliases to auto-tick, no entries to restore).
 */
fun parseSyncPayload(plaintext: String): List<SyncHostEntry> {
    val root = try {
        JSONObject(plaintext)
    } catch (_: JSONException) {
        return emptyList()
    }
    val hosts = root.optJSONArray("hosts") ?: return emptyList()
    val out = mutableListOf<SyncHostEntry>()
    for (i in 0 until hosts.length()) {
        val entry = hosts.optJSONObject(i) ?: continue
        // Same minimum a `Host` directive needs on the desktop side: an entry
        // without both is not addressable and is dropped rather than repaired.
        val name = entry.optString("name").trim()
        val hostname = entry.optString("hostname").trim()
        if (name.isEmpty() || hostname.isEmpty()) continue
        out += SyncHostEntry(
            name = name,
            hostname = hostname,
            port = entry.optInt("port", SyncHostEntry.DEFAULT_PORT)
                .takeIf { it in 1..65535 } ?: SyncHostEntry.DEFAULT_PORT,
            user = entry.optString("user"),
            extras = entry.keys().asSequence()
                .filter { it !in TYPED_FIELDS }
                .associateWith { key -> unwrapJson(entry.opt(key)) },
        )
    }
    return out
}

/** The ticked aliases assembled into the list the envelope encrypts. */
fun assembleSyncSet(
    local: List<SyncHostEntry>,
    remote: List<SyncHostEntry>,
    checked: List<String>,
): List<SyncHostEntry> {
    val localByName = local.associateBy { it.name }
    val remoteByName = remote.associateBy { it.name }
    val out = mutableListOf<SyncHostEntry>()
    val seen = mutableSetOf<String>()
    for (alias in checked) {
        if (!seen.add(alias)) continue
        // The LOCAL entry when this device has it — the device you are holding
        // is authoritative for the hosts it has; else the ACCOUNT's entry, so a
        // ticked alias this device has lost keeps its backup instead of
        // silently vanishing from the account too. A tick with no entry on
        // either side contributes nothing; the tick stays so a re-added host
        // syncs again.
        val entry = localByName[alias] ?: remoteByName[alias] ?: continue
        out += mergeExtras(entry, remoteByName[alias])
    }
    return out
}

/**
 * Account aliases the selection does not have yet AND the local list lacks —
 * the auto-tick. The local clause is what keeps an untick meaningful.
 */
fun aliasesToAutoCheck(
    remote: List<SyncHostEntry>,
    checked: List<String>,
    localAliases: List<String>,
): List<String> {
    val known = checked.toSet()
    val local = localAliases.toSet()
    return remote.map { it.name }.filter { it !in known && it !in local }
}

/**
 * A local entry carries only what this client models; the account's copy of
 * the same alias may carry desktop-only directives. Re-emitting the local
 * entry alone would strip them, so the account's extras are carried forward
 * under the local entry's connection fields.
 */
private fun mergeExtras(local: SyncHostEntry, remote: SyncHostEntry?): SyncHostEntry {
    if (remote == null || remote === local || remote.extras.isEmpty()) return local
    if (local.extras.isEmpty()) return local.copy(extras = remote.extras)
    return local.copy(extras = remote.extras + local.extras)
}

private val TYPED_FIELDS = setOf("name", "hostname", "port", "user")

private fun hostToJson(host: SyncHostEntry): JSONObject {
    val json = JSONObject()
    // Extras first: a stale extra can never shadow the four fields this client
    // owns, whatever the account happened to contain.
    host.extras.forEach { (key, value) ->
        if (key !in TYPED_FIELDS) json.put(key, wrapJson(value))
    }
    return json
        .put("name", host.name)
        .put("hostname", host.hostname)
        .put("port", host.port)
        .put("user", host.user)
}

/** `JSONObject`/`JSONArray`/`JSONObject.NULL` → plain Kotlin values. */
private fun unwrapJson(value: Any?): Any? = when (value) {
    null, JSONObject.NULL -> null
    is JSONObject -> value.keys().asSequence().associateWith { unwrapJson(value.opt(it)) }
    is JSONArray -> (0 until value.length()).map { unwrapJson(value.opt(it)) }
    else -> value
}

/** The inverse of [unwrapJson]. */
private fun wrapJson(value: Any?): Any = when (value) {
    null -> JSONObject.NULL
    is Map<*, *> -> JSONObject().also { obj ->
        value.forEach { (key, nested) -> obj.put(key.toString(), wrapJson(nested)) }
    }
    is List<*> -> JSONArray().also { array -> value.forEach { array.put(wrapJson(it)) } }
    else -> value
}
