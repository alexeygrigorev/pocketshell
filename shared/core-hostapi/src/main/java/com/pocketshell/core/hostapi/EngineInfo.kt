package com.pocketshell.core.hostapi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * One entry of the host's engine registry (`pocketshell engines list --json`).
 *
 * This is the subset the create picker needs, not the whole host payload. The
 * host also emits a `launch` block (argv, env set/unset, profile env var), and
 * it is deliberately dropped here: since schema 2 the HOST starts the agent
 * (`sessions create --engine` sends the launch line server-side), so a phone
 * that modelled `launch` would be holding a second, drifting copy of a
 * decision it no longer makes.
 *
 * [availableForCreate] is the only field the picker may gate on. It is the
 * host's own `enabled && available` verdict; recomputing it phone-side from
 * [enabled]/[available] would re-derive a policy the host may change.
 * [unavailableReason] is the host's user-facing explanation for a `false`
 * verdict, and is `null` exactly when the engine is createable.
 */
data class EngineInfo(
    val id: String,
    val label: String,
    val family: String,
    val harness: String,
    val providerMark: String,
    val usageProvider: String?,
    val enabled: Boolean,
    val available: Boolean,
    val availableForCreate: Boolean,
    val unavailableReason: String?,
)

/**
 * Parser for `pocketshell engines list --json`.
 *
 * The envelope is `{"engines": [ … ]}` with NO `schema` field — unlike the
 * sessions listing, this command predates the schema-2 work and versions
 * itself by field presence alone. There is therefore no [HostCliError.TooOld]
 * gate here; a host too old to emit a field this parser requires fails as
 * [HostCliError.Malformed].
 *
 * Required per row: `id`, `label`, `available_for_create` — a row missing any
 * of them either has no identity or would make the picker lie about whether
 * an engine can be started, so it fails the whole listing rather than being
 * skipped. Everything else is descriptive and defaults.
 *
 * A missing top-level `engines` key is an error, NOT an empty list: "the host
 * has no engines" and "the host answered with something else entirely" must
 * not look identical (the same rule the sessions parser applies to `errors`).
 */
object EnginesJson {

    private val json = Json { ignoreUnknownKeys = true }

    private const val FIELD_ENGINES = "engines"

    /** Parses [raw] stdout. Never throws: bad input comes back as a failure. */
    fun parseEnginesList(raw: String): Result<List<EngineInfo>> {
        val obj = jsonObjectOrFailure(json, raw).getOrElse { return Result.failure(it) }

        if (obj[FIELD_ENGINES] == null) {
            return Result.failure(
                HostCliError.Malformed("missing the `$FIELD_ENGINES` field"),
            )
        }

        val wire = try {
            json.decodeFromJsonElement<EnginesWire>(obj)
        } catch (e: Exception) {
            return Result.failure(
                HostCliError.Malformed(
                    "engines payload did not match the expected shape " +
                        "(${e.message ?: e::class.simpleName})",
                    e,
                ),
            )
        }

        return Result.success(wire.engines.map { it.toModel() })
    }

    @Serializable
    private data class EnginesWire(
        val engines: List<EngineWire>,
    )

    @Serializable
    private data class EngineWire(
        val id: String,
        val label: String,
        @SerialName("available_for_create") val availableForCreate: Boolean,
        val family: String = "",
        val harness: String = "",
        @SerialName("provider_mark") val providerMark: String = "",
        @SerialName("usage_provider") val usageProvider: String? = null,
        val enabled: Boolean = true,
        val available: Boolean = true,
        @SerialName("unavailable_reason") val unavailableReason: String? = null,
    ) {
        fun toModel(): EngineInfo = EngineInfo(
            id = id,
            label = label,
            family = family,
            harness = harness,
            providerMark = providerMark,
            usageProvider = usageProvider,
            enabled = enabled,
            available = available,
            availableForCreate = availableForCreate,
            unavailableReason = unavailableReason,
        )
    }
}

/**
 * Shared "is this even a JSON object?" front door for the envelope parsers.
 *
 * Lives here rather than in each parser so the two failure messages a user can
 * see for "the host said something unreadable" are worded once. [SessionsJson]
 * keeps its own copy of this step because it must gate on `schema` before
 * decoding, which these envelopes have no equivalent of.
 */
internal fun jsonObjectOrFailure(json: Json, raw: String): Result<JsonObject> {
    val root = try {
        json.parseToJsonElement(raw)
    } catch (e: Exception) {
        return Result.failure(HostCliError.Malformed("response was not valid JSON", e))
    }
    val obj = root as? JsonObject
        ?: return Result.failure(
            HostCliError.Malformed("expected a JSON object at the top level"),
        )
    return Result.success(obj)
}
