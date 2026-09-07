package com.pocketshell.core.hostapi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

/**
 * Parser for `pocketshell sessions list --json` (schema 3).
 *
 * Contract, in one place:
 * - Unknown keys are ignored, so a newer host CLI adding a field never breaks
 *   an older phone.
 * - `schema < 3` is rejected with [HostCliError.TooOld]. The host exposes one
 *   session implementation, so there is no backend compatibility path.
 * - An unrecognised `agent_state` / `agent_state_source` keeps the row with a
 *   `null` state. Forward compatibility never costs a row.
 * - `agent` is carried through verbatim (issue #2579) — no enum, no
 *   normalisation beyond trimming — because the vocabulary belongs to
 *   aplexer's detector on the host. Missing, null or blank all become
 *   `null`, so a host CLI that predates the key parses exactly as before.
 * - `errors[]` is mapped verbatim onto [SessionsListing.errors] and never
 *   dropped, even when it is the only thing in the document.
 * - Anything genuinely unreadable — non-JSON, a non-object root, a missing
 *   `schema`, a row missing `name`/`attached` or with a mistyped
 *   field — fails the whole parse with [HostCliError.Malformed]. Failure is
 *   returned in a [Result], not thrown.
 */
object SessionsJson {

    /** The lowest `schema` this parser understands. */
    const val REQUIRED_SCHEMA: Int = 3

    private val json = Json {
        ignoreUnknownKeys = true
        // The host emits a fixed record per row in schema 3; a missing
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

        // The schema gate runs BEFORE the typed decode so an old document gets
        // the actionable "update the host CLI" error.
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
                errors = wire.errors.map { SessionListError(message = it.message) },
            ),
        )
    }

    private const val FIELD_SCHEMA = "schema"

    @Serializable
    private data class SessionsListingWire(
        val schema: Int,
        val sessions: List<SessionWire> = emptyList(),
        val errors: List<SessionListErrorWire> = emptyList(),
    )

    @Serializable
    private data class SessionWire(
        val name: String,
        val attached: Boolean,
        val id: String? = null,
        val workspace: String? = null,
        val tag: String? = null,
        val engine: String? = null,
        val profile: String? = null,
        /**
         * `agent` (issue #2579). Absent OR explicitly null both decode to
         * null, which is what keeps a host CLI predating the key working —
         * the client's only reaction to null is "no agent focus".
         */
        val agent: String? = null,
        @SerialName("agent_state") val agentState: String? = null,
        @SerialName("agent_state_source") val agentStateSource: String? = null,
        @SerialName("created_epoch") val createdEpoch: Long? = null,
        @SerialName("activity_epoch") val activityEpoch: Long? = null,
    ) {
        fun toModel(): SessionRow = SessionRow(
            name = name,
            id = id,
            workspace = workspace,
            tag = tag,
            engine = engine,
            profile = profile,
            // Verbatim, only trimmed/blank-collapsed: the vocabulary is the
            // HOST's (aplexer's detector), so an unrecognised value must reach
            // the caller intact rather than be mapped to an enum here and lost.
            agent = agent?.trim()?.takeIf { it.isNotEmpty() },
            agentState = AgentState.fromWire(agentState),
            agentStateSource = AgentStateSource.fromWire(agentStateSource),
            attached = attached,
            createdEpoch = createdEpoch,
            activityEpoch = activityEpoch,
        )
    }

    @Serializable
    private data class SessionListErrorWire(
        val message: String,
    )
}
