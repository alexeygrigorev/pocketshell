package com.pocketshell.core.hostapi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * One host-side agent profile (`pocketshell profiles list --json`).
 *
 * Profiles are defined ONCE on the host — auto-discovered from conventional
 * config dirs (`~/.claude`, `~/.zlaude`, `~/.codex`, …) plus an optional
 * `~/.config/pocketshell/profiles.yaml` — so the phone fetches them instead of
 * storing a per-host copy that can drift.
 *
 * [configDir] is `null` for an engine's built-in default profile (the one that
 * needs no config-dir override); the host never emits anything from INSIDE a
 * config dir, so nothing here is secret. [isDefault] marks the profile the
 * host would pick for [engine] when the user does not choose — the picker
 * pre-selects it.
 */
data class ProfileInfo(
    val name: String,
    val engine: String,
    val configDir: String?,
    val isDefault: Boolean,
)

/**
 * Parser for `pocketshell profiles list --json`.
 *
 * Envelope: `{"profiles": [{"name","engine","config_dir","default"}]}`, with
 * no `schema` field (see [EnginesJson] for why there is no version gate).
 *
 * Required per row: `name` and `engine` — a profile without both cannot be
 * passed back to `sessions create --profile`, so the whole listing fails
 * rather than showing an unusable row. `config_dir` and `default` describe the
 * row and default to `null` / `false`.
 *
 * A missing top-level `profiles` key is an error, not an empty list.
 */
object ProfilesJson {

    private val json = Json { ignoreUnknownKeys = true }

    private const val FIELD_PROFILES = "profiles"

    /** Parses [raw] stdout. Never throws: bad input comes back as a failure. */
    fun parseProfilesList(raw: String): Result<List<ProfileInfo>> {
        val obj = jsonObjectOrFailure(json, raw).getOrElse { return Result.failure(it) }

        if (obj[FIELD_PROFILES] == null) {
            return Result.failure(
                HostCliError.Malformed("missing the `$FIELD_PROFILES` field"),
            )
        }

        val wire = try {
            json.decodeFromJsonElement<ProfilesWire>(obj)
        } catch (e: Exception) {
            return Result.failure(
                HostCliError.Malformed(
                    "profiles payload did not match the expected shape " +
                        "(${e.message ?: e::class.simpleName})",
                    e,
                ),
            )
        }

        return Result.success(wire.profiles.map { it.toModel() })
    }

    @Serializable
    private data class ProfilesWire(
        val profiles: List<ProfileWire>,
    )

    @Serializable
    private data class ProfileWire(
        val name: String,
        val engine: String,
        @SerialName("config_dir") val configDir: String? = null,
        // `default` is the wire name; the model calls it `isDefault` because
        // `default` reads as a Java keyword at every call site.
        @SerialName("default") val isDefault: Boolean = false,
    ) {
        fun toModel(): ProfileInfo = ProfileInfo(
            name = name,
            engine = engine,
            configDir = configDir,
            isDefault = isDefault,
        )
    }
}
