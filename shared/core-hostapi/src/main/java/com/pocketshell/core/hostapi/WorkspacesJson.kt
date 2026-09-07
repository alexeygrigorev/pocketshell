package com.pocketshell.core.hostapi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

/** Parser for `pocketshell workspaces list --json` (schema 1). */
object WorkspacesJson {

    const val REQUIRED_SCHEMA: Int = 1

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parses the host's durable workspace envelope without inventing entries.
     * Invalid workspace records fail the complete read so a partial membership
     * list can never silently hide a user's workspace.
     */
    fun parseWorkspacesList(raw: String): Result<WorkspacesListing> {
        val root = try {
            json.parseToJsonElement(raw)
        } catch (error: Exception) {
            return Result.failure(
                HostCliError.Malformed("response was not valid JSON", error),
            )
        }
        val obj = root as? JsonObject
            ?: return Result.failure(
                HostCliError.Malformed("expected a JSON object at the top level"),
            )
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
        if (obj[FIELD_WORKSPACES] == null) {
            return Result.failure(HostCliError.Malformed("missing the `workspaces` field"))
        }

        val wire = try {
            json.decodeFromJsonElement<WorkspacesWire>(obj)
        } catch (error: Exception) {
            return Result.failure(
                HostCliError.Malformed(
                    "schema $schema payload did not match the expected shape " +
                        "(${error.message ?: error::class.simpleName})",
                    error,
                ),
            )
        }

        val entries = wire.workspaces.mapIndexed { index, workspace ->
            val path = workspace.path.trim()
            if (path.isEmpty()) {
                return Result.failure(
                    HostCliError.Malformed("workspace[$index] has an empty `path`"),
                )
            }
            WorkspaceMembership(
                path = path,
                displayPath = workspace.displayPath.trim().ifEmpty { path },
            )
        }
        return Result.success(WorkspacesListing(entries))
    }

    private const val FIELD_SCHEMA = "schema"
    private const val FIELD_WORKSPACES = "workspaces"

    @Serializable
    private data class WorkspacesWire(
        val schema: Int,
        val workspaces: List<WorkspaceWire> = emptyList(),
    )

    @Serializable
    private data class WorkspaceWire(
        val path: String,
        @SerialName("display_path") val displayPath: String = "",
    )
}
