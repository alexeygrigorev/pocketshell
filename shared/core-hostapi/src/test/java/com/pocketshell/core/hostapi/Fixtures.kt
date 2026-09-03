package com.pocketshell.core.hostapi

/**
 * Loads a `sessions list --json` fixture from `src/test/resources/fixtures`.
 *
 * Fails loudly on a missing file instead of returning an empty string: a
 * silently-empty fixture would make an assertion pass vacuously.
 */
internal fun fixture(name: String): String {
    val path = "/fixtures/$name"
    val stream = SessionsJson::class.java.getResourceAsStream(path)
        ?: error("missing test fixture $path")
    return stream.bufferedReader().use { it.readText() }
        .also { check(it.isNotBlank()) { "fixture $path is empty" } }
}
