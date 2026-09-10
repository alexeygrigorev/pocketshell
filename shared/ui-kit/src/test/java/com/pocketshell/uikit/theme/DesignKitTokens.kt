package com.pocketshell.uikit.theme

import org.json.JSONObject
import java.io.File

/**
 * Reads `docs/design-kit/design-system/tokens.json` — the single machine-readable
 * source of truth for colour, type, spacing, size and radius (issue #2635, T1).
 *
 * Before this existed there were four token sources and two of them declared
 * themselves authoritative: `docs/design-language.md` (20/16/14/11, rows 56/64)
 * and `tokens.json` (28/20/18/16, rows 72/88). `QuietThemeTokenTest` pinned the
 * code to hard-coded constants, i.e. to a *copy* of one of them, so regenerating
 * the kit's `android/PocketShellTheme.kt` from the JSON would have quietly put
 * 28sp back and nothing would have failed. Reading the file makes the pin real:
 * change a number in `tokens.json` without changing `Type.kt`/`Spacing.kt`/
 * `Shape.kt`/`Color.kt` and the ui-kit JVM gate goes red.
 *
 * Resolution walks up from the test's working directory (Gradle runs unit tests
 * with the module directory as `user.dir`) until the file is found. A missing
 * file throws — a token pin that silently skips when it cannot find its source
 * is the "guard reporting perfection over nothing" shape `check-design-tokens.sh`
 * already had to grow an existence check for.
 */
object DesignKitTokens {

    private const val RELATIVE_PATH = "docs/design-kit/design-system/tokens.json"

    val file: File by lazy { locate() }

    private val root: JSONObject by lazy { JSONObject(file.readText()) }

    val color: JSONObject get() = root.getJSONObject("color")
    val space: JSONObject get() = root.getJSONObject("space")
    val size: JSONObject get() = root.getJSONObject("size")
    val radius: JSONObject get() = root.getJSONObject("radius")
    val type: JSONObject get() = root.getJSONObject("type")

    /** `#RRGGBB` / `#RRGGBBAA` from the kit, as the `0xAARRGGBB` Compose spells. */
    fun colorArgb(role: String): Long {
        val raw = color.getString(role).removePrefix("#")
        val argb = when (raw.length) {
            6 -> "FF$raw"
            8 -> raw.substring(6, 8) + raw.substring(0, 6)
            else -> error("tokens.json color.$role is not #RRGGBB or #RRGGBBAA: $raw")
        }
        return argb.uppercase().toLong(16)
    }

    fun spaceDp(rung: String): Int = space.getInt(rung)

    fun sizeDp(rung: String): Int = size.getInt(rung)

    fun radiusDp(role: String): Int = radius.getInt(role)

    fun typeRung(rung: String): TypeRung {
        val json = type.getJSONObject(rung)
        return TypeRung(
            sizeSp = json.getInt("sizeSp"),
            lineHeightSp = json.getInt("lineHeightSp"),
            weight = json.getInt("weight"),
            mono = json.optBoolean("mono", false),
        )
    }

    /** Every type rung the kit declares, so a new rung cannot go unasserted. */
    fun typeRungNames(): List<String> = type.keys().asSequence().toList().sorted()

    /** The kit's generated Android handoff theme, checked against the same JSON. */
    val generatedAndroidTheme: File by lazy {
        File(file.parentFile.parentFile, "android/PocketShellTheme.kt").also {
            check(it.isFile) { "missing generated kit theme at ${it.absolutePath}" }
        }
    }

    private fun locate(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, RELATIVE_PATH)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error(
            "could not find $RELATIVE_PATH above ${System.getProperty("user.dir")}; " +
                "the design-token pin cannot pass without its source of truth",
        )
    }

    data class TypeRung(
        val sizeSp: Int,
        val lineHeightSp: Int,
        val weight: Int,
        val mono: Boolean,
    )
}
