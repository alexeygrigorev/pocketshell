package com.pocketshell.app.share

/**
 * Filename sanitiser for the Android share-target upload path (issue
 * #138).
 *
 * The Android share intent gives us a display name supplied by the
 * source app; it can contain path traversal segments (`../`), null
 * bytes, control characters, leading dots, or be excessively long.
 * Before we land the file at `~/inbox/pocketshell/<timestamp>-<name>`
 * we need to massage that name into something safe-on-disk on a Unix
 * remote and reasonable to display.
 *
 * The rules below are intentionally narrow — the goal is not a perfect
 * portable-POSIX filename, only that the result cannot escape the
 * `~/inbox/pocketshell/` directory and stays under common filename
 * length limits.
 *
 * Rules:
 *
 * 1. Strip any directory components — keep only the basename (anything
 *    after the last `/` or `\`). This removes `../` traversal attempts
 *    at the structural level; the next rule covers leftover dot-runs.
 * 2. Strip null bytes and control characters (codepoints < 0x20 and
 *    0x7F). These have no legitimate place in a filename and trip a
 *    variety of shell tools.
 * 3. Replace whitespace runs with a single `_`. The issue spec calls
 *    out "spaces -> _" explicitly; we extend that to tabs and other
 *    whitespace for consistency.
 * 4. Replace remaining shell-unsafe characters with `_`. The allow-list
 *    is `[A-Za-z0-9._-]` plus a few non-ASCII letters that are common
 *    in real filenames (Unicode letters/digits). Everything else
 *    becomes `_`.
 * 5. Collapse runs of `_` to a single `_` and trim leading/trailing
 *    `_`, `.`, `-`.
 * 6. Reject names that have collapsed to empty or to a pure dot-run
 *    (`.`, `..`, `...`) — return [DEFAULT_NAME] instead so the
 *    timestamp prefix still has something to suffix.
 * 7. Cap the total length at [MAX_LENGTH] characters. The cap is
 *    applied to the sanitised stem+extension as a single string, with
 *    the extension preserved when possible (the prefix is what gets
 *    trimmed).
 *
 * Output is split into a stem ("base") and an optional extension. The
 * caller composes the final filename as `<timestamp>-<base>.<ext>` (or
 * `<timestamp>-<base>` when ext is empty).
 */
internal object FilenameSanitiser {

    /**
     * Maximum sanitised filename length. Most Linux filesystems allow
     * 255 bytes for a filename; we leave headroom for the 16-char
     * timestamp prefix the caller prepends.
     */
    internal const val MAX_LENGTH: Int = 200

    /** Fallback stem when the input sanitises to empty. */
    internal const val DEFAULT_NAME: String = "shared"

    /**
     * Sanitised representation of a filename. [base] is the stem (no
     * dot), [ext] is the extension without its leading dot (may be
     * empty).
     */
    internal data class Sanitised(val base: String, val ext: String) {
        /** Render as a single filename string, dotted only when [ext] is non-empty. */
        fun render(): String = if (ext.isEmpty()) base else "$base.$ext"
    }

    /**
     * Sanitise [input] for use as a filename suffix in the
     * `<timestamp>-<name>` pattern. Defensive against null bytes, path
     * traversal, control characters, and absurd lengths.
     *
     * When [defaultExtension] is non-null it is applied when [input]
     * has no extension of its own. This lets the share dispatcher pass
     * `txt` for `text/plain` shares that arrive without a `.txt`
     * suffix from the source app.
     */
    internal fun sanitise(input: String?, defaultExtension: String? = null): Sanitised {
        val raw = input.orEmpty()

        // 1. Keep only the basename — strip everything up to the last
        //    path separator (forward or back slash). This neutralises
        //    `../etc/passwd` style payloads at the structural level.
        val basename = raw
            .substringAfterLast('/')
            .substringAfterLast('\\')

        // 2. Normalise whitespace runs (including tab / newline / CR
        //    which are control characters but carry semantic
        //    separation we want to preserve as `_`) to a single
        //    underscore BEFORE stripping the remaining control bytes.
        //    Otherwise a `foo\nbar` input would collapse to `foobar`
        //    instead of the more readable `foo_bar`.
        val whitespaceCollapsed = basename.replace(Regex("\\s+"), "_")

        // 3. Strip null bytes + ASCII control characters that weren't
        //    consumed by the whitespace pass. Range covers 0x00..0x1F
        //    and 0x7F (DEL).
        val noControl = whitespaceCollapsed.filter { it.code >= 0x20 && it.code != 0x7F }

        // 4. Split into stem / extension before character substitution
        //    so dots inside the stem don't get treated as extension
        //    separators. We use the LAST dot as the extension marker;
        //    a leading-dot filename like `.bashrc` therefore has stem
        //    `bashrc` and empty extension (the leading dot is dropped
        //    by the trim step below).
        val dotIndex = noControl.lastIndexOf('.')
        val rawStem: String
        val rawExt: String
        if (dotIndex <= 0 || dotIndex == noControl.length - 1) {
            // No dot, or dot only at position 0 (leading-dot filename),
            // or dot at the end (no real extension).
            rawStem = noControl.trimStart('.').trimEnd('.')
            rawExt = ""
        } else {
            rawStem = noControl.substring(0, dotIndex)
            rawExt = noControl.substring(dotIndex + 1)
        }

        val cleanStem = sanitiseSegment(rawStem)
        val cleanExt = sanitiseSegment(rawExt).take(MAX_EXT_LENGTH)

        // 5. Resolve dot-run / empty edge cases.
        val safeStem = if (cleanStem.isEmpty() || cleanStem.all { it == '.' }) {
            DEFAULT_NAME
        } else {
            cleanStem
        }

        val resolvedExt = when {
            cleanExt.isNotEmpty() -> cleanExt
            defaultExtension != null -> sanitiseSegment(defaultExtension).take(MAX_EXT_LENGTH)
            else -> ""
        }

        // 6. Length cap. Keep the extension and trim the stem.
        val extOverhead = if (resolvedExt.isEmpty()) 0 else resolvedExt.length + 1
        val maxStem = (MAX_LENGTH - extOverhead).coerceAtLeast(1)
        val cappedStem = if (safeStem.length > maxStem) safeStem.take(maxStem) else safeStem

        return Sanitised(base = cappedStem, ext = resolvedExt)
    }

    /**
     * Apply the character allow-list + underscore-collapse + trim to a
     * single path segment (stem or extension). Pulled out so the same
     * rules apply on both sides of the extension split.
     */
    private fun sanitiseSegment(segment: String): String {
        val mapped = segment.map { ch ->
            when {
                ch in 'A'..'Z' -> ch
                ch in 'a'..'z' -> ch
                ch in '0'..'9' -> ch
                ch == '.' || ch == '_' || ch == '-' -> ch
                // Allow non-ASCII letters and digits so Unicode names
                // (Cyrillic, CJK, accented Latin) survive the
                // sanitiser instead of collapsing to a row of
                // underscores.
                ch.isLetterOrDigit() && ch.code > 0x7F -> ch
                else -> '_'
            }
        }.joinToString("")

        // Collapse `_+` to a single `_`. Avoids "report (final).docx"
        // turning into `report__final__.docx`.
        val collapsed = mapped.replace(Regex("_+"), "_")

        // Trim leading + trailing separators so we never emit
        // `_foo_.txt` or `-foo-.txt`. Dots are also trimmed because a
        // leading dot would make the file hidden on the remote and a
        // trailing dot is awkward to chain with the extension marker.
        return collapsed.trim('_', '.', '-')
    }

    /**
     * Compose `<timestamp>-<sanitised>` from a [Sanitised] result. The
     * timestamp portion is supplied by the caller (production uses
     * `yyyyMMdd-HHmmss`; tests inject a fixed value for determinism).
     */
    internal fun composeRemoteName(timestamp: String, sanitised: Sanitised): String {
        val stemPart = sanitised.base
        val suffix = if (sanitised.ext.isEmpty()) stemPart else "$stemPart.${sanitised.ext}"
        return "$timestamp-$suffix"
    }

    /**
     * Extension length cap. SCP/SFTP doesn't care, but absurdly long
     * extensions (the kind a malicious sender might craft to push the
     * file past readable lengths) are noise.
     */
    private const val MAX_EXT_LENGTH: Int = 16
}
