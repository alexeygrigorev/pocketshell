package com.pocketshell.app.release

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the semver comparison semantics surfaced by [ReleaseChecker.isNewer].
 *
 * The acceptance criteria for issue #40 enumerate these cases verbatim:
 *
 * - `("v0.0.1", "v0.0.2")` -> true  (patch bump)
 * - `("v0.0.1", "v0.0.1")` -> false (equal)
 * - `("v0.0.1", "v0.1.0")` -> true  (minor bump dominates patch)
 * - `("v0.1.0", "v0.0.9")` -> false (minor regression — not "newer")
 *
 * We also lock down the `v`-prefix tolerance and the `-debug` suffix
 * stripping on the local side, since both come up in PocketShell's
 * actual `versionName` strings.
 */
class ReleaseCheckerTest {

    private val checker = ReleaseChecker()

    @Test
    fun isNewer_returnsTrue_forPatchBump() {
        assertTrue(checker.isNewer("v0.0.1", "v0.0.2"))
    }

    @Test
    fun isNewer_returnsFalse_forEqualVersions() {
        assertFalse(checker.isNewer("v0.0.1", "v0.0.1"))
    }

    @Test
    fun isNewer_returnsTrue_forMinorBump() {
        assertTrue(checker.isNewer("v0.0.1", "v0.1.0"))
    }

    @Test
    fun isNewer_returnsFalse_forMinorRegression() {
        assertFalse(checker.isNewer("v0.1.0", "v0.0.9"))
    }

    @Test
    fun isNewer_toleratesMissingVPrefix_onLocalSide() {
        // PackageInfo.versionName ships without a `v` prefix — make sure
        // the comparison still works.
        assertTrue(checker.isNewer("0.1.0", "v0.2.0"))
        assertFalse(checker.isNewer("0.2.0", "v0.1.0"))
    }

    @Test
    fun isNewer_stripsLocalQualifierSuffix() {
        // Debug builds sometimes carry a `-debug` qualifier; the remote
        // tag is plain semver. The comparison should drop the qualifier
        // before splitting.
        assertTrue(checker.isNewer("0.1.0-debug", "v0.2.0"))
        assertFalse(checker.isNewer("0.2.0-debug", "v0.1.0"))
    }

    @Test
    fun isNewer_returnsTrue_forMajorBump() {
        assertTrue(checker.isNewer("v0.9.9", "v1.0.0"))
    }

    @Test
    fun isNewer_returnsFalse_forMajorRegression() {
        assertFalse(checker.isNewer("v1.0.0", "v0.9.9"))
    }
}
