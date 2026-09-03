package com.pocketshell.core.hostapi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

/**
 * Parser for `pocketshell sessions list --json` (schema 2).
 *
 * Contract, in one place:
 * - Unknown keys are ignored, so a newer host CLI adding a field never breaks
 *   an older phone.
 * - `schema < 2` is rejected with [HostCliError.TooOld]; there is no schema-1
 *   compatibility path (D22 hard cut).
 * - An unrecognised `manager` keeps the row with [Backend.UNKNOWN]; an
 *   unrecognised `agent_state` / `agent_state_source` keeps the row with a
 *   `null` state. Forward compatibility never costs a row.
 * - `errors[]` is mapped verbatim onto [SessionsListing.errors] and never
 *   dropped, even when it is the only thing in the document.
 * - Anything genuinely unreadable — non-JSON, a non-object root, a missing
 *   `schema`, a row missing `name`/`manager`/`attached` or with a mistyped
 *   field — fails the whole parse with [HostCliError.Malformed]. Failure is
 *   returned in a [Result], not thrown.
 */
object SessionsJson {

    /** The lowest `schema` this parser understands. */
    const val REQUIRED_SCHEMA: Int = 2

    private val json = Json {
        ignoreUnknownKeys = true
        // The host emits a fixed record per row in schema 2; a missing
        // required key is a real defect, so no `coerceInputValues` /
        // `explicitNulls` leniency that would paper over it.
    }

    /**
     * Parses [raw] stdout into a [SessionsListing].
     *
     * Never throws for bad input: every failure comes back as
     * `Result.failure(HostCliError)`.
     */
    fun parseSessionsList(raw: String): Result<SessionsListing> {
        val root = try {
            json.parseToJsonElement(raw)
        } catch (e: Exception) {
            return Result.failure(
                HostCliError.Malformed("response was not valid JSON", e),
            )
        }

        val obj = root as? JsonObject
            ?: return Result.failure(
                HostCliError.Malformed("expected a JSON object at the top level"),
            )

        // The schema gate runs BEFORE the typed decode: a schema-1 document has
        // a different row shape, so decoding first would report a confusing
        // "missing field" instead of the actionable "update the host CLI".
        val schemaField = obj[FIELD_SCHEMA]
        val schema = (schemaField as? JsonPrimitive)?.intOrNull
            ?: return Result.failure(
                HostCliError.Malformed(
                    if (schemaField == null) {
                        "missing the `schema` field"
                    } else {
                        "`schema` was not an integer"
                    },
                ),
            )
        if (schema < REQUIRED_SCHEMA) {
            return Result.failure(HostCliError.TooOld(schema, REQUIRED_SCHEMA))
        }

        val wire = try {
            json.decodeFromJsonElement<SessionsListingWire>(obj)
        } catch (e: Exception) {
            return Result.failure(
                HostCliError.Malformed(
                    "schema $schema payload did not match the expected shape " +
                        "(${e.message ?: e::class.simpleName})",
                    e,
                ),
            )
        }

        return Result.success(
            SessionsListing(
                sessions = wire.sessions.map { it.toModel() },
                errors = wire.errors.map { BackendError(manager = it.manager, message = it.message) },
            ),
        )
    }

    private const val FIELD_SCHEMA = "schema"

    /**
     * `managers` is read but not surfaced: it is derivable from the rows, and
     * [SessionsListing] is deliberately the two-field shape the UI needs
     * (sessions + errors). It stays in the wire type so `ignoreUnknownKeys`
     * isn't the only thing keeping it out.
     */
    @Serializable
    private data class SessionsListingWire(
        val schema: Int,
        val managers: List<String> = emptyList(),
        val sessions: List<SessionWire> = emptyList(),
        val errors: List<BackendErrorWire> = emptyList(),
    )

    @Serializable
    private data class SessionWire(
        val name: String,
        val manager: String,
        val attached: Boolean,
        val id: String? = null,
        val workspace: String? = null,
        val tag: String? = null,
        val engine: String? = null,
        val profile: String? = null,
        @SerialName("agent_state") val agentState: String? = null,
        @SerialName("agent_state_source") val agentStateSource: String? = null,
        @SerialName("created_epoch") val createdEpoch: Long? = null,
        @SerialName("activity_epoch") val activityEpoch: Long? = null,
    ) {
        fun toModel(): SessionRow = SessionRow(
            name = name,
            backend = Backend.fromWire(manager),
            id = id,
            workspace = workspace,
            tag = tag,
            engine = engine,
            profile = profile,
            agentState = AgentState.fromWire(agentState),
            agentStateSource = AgentStateSource.fromWire(agentStateSource),
            attached = attached,
            createdEpoch = createdEpoch,
            activityEpoch = activityEpoch,
        )
    }

    @Serializable
    private data class BackendErrorWire(
        val manager: String,
        val message: String,
    )
}
