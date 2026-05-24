package com.pocketshell.app.hosts

import com.pocketshell.uikit.components.HostAvatarColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The avatar colour mapping under [HostAvatarColor] feeds the host card
 * UI (issue #113) — multiple hosts beginning with the same first letter
 * have to be visually distinguishable in the list. These tests live in
 * `:app/src/test` because `:shared:ui-kit` does not yet have its own
 * unit-test source set; the helper is exercised through the public API
 * the way the production code uses it.
 */
class HostAvatarColorTest {

    @Test
    fun hueFor_isDeterministic_acrossInvocations() {
        val first = HostAvatarColor.hueFor("hetzner")
        val second = HostAvatarColor.hueFor("hetzner")
        assertEquals(
            "hueFor must return the same value for identical input",
            first,
            second,
        )
    }

    @Test
    fun hueFor_isStable_acrossKnownInputs() {
        // Pin a few representative names so a future refactor that
        // accidentally swaps the hash function or skews the modulo
        // window trips this test. The values come from
        // `String.hashCode() mod 360` with `abs` applied.
        // `String.hashCode()` is contract-specified by the JLS so we
        // can hard-code the expectations.
        val cases = mapOf(
            "hetzner" to (Math.abs("hetzner".hashCode()) % 360).toFloat(),
            "test" to (Math.abs("test".hashCode()) % 360).toFloat(),
            "prod" to (Math.abs("prod".hashCode()) % 360).toFloat(),
            "gpu-box" to (Math.abs("gpu-box".hashCode()) % 360).toFloat(),
        )
        cases.forEach { (name, expected) ->
            assertEquals("hueFor($name)", expected, HostAvatarColor.hueFor(name))
        }
    }

    @Test
    fun hueFor_distinctNames_produceDistinctHues() {
        // Names beginning with the same letter — the original avatar
        // collapsed these to the same look ("T"). We don't guarantee
        // *every* pair differs (hash collisions modulo 360 are
        // possible) but a reasonable set should hit different hues
        // for everyday names.
        val hues = listOf("test", "tetra", "tunnel", "tinyhost", "trial")
            .map(HostAvatarColor::hueFor)
            .toSet()
        assertTrue(
            "Expected >=4 distinct hues from 5 distinct T-names but got: $hues",
            hues.size >= 4,
        )
    }

    @Test
    fun hueFor_emptyName_returnsNeutralHue() {
        // The avatar must still draw something for a blank label —
        // 180° (cyan-ish) is a sensible neutral that matches the
        // doc on `HostAvatarColor`.
        assertEquals(180f, HostAvatarColor.hueFor(""))
    }

    @Test
    fun colorFor_isDeterministic_acrossInvocations() {
        val a = HostAvatarColor.colorFor("hetzner")
        val b = HostAvatarColor.colorFor("hetzner")
        assertEquals(a, b)
    }

    @Test
    fun colorFor_distinctNames_produceDistinctColors() {
        val a = HostAvatarColor.colorFor("hetzner")
        val b = HostAvatarColor.colorFor("gpu-box")
        // Distinct hues -> distinct colours.
        assertNotEquals(a, b)
    }

    @Test
    fun hueFor_isAlwaysWithinUnitCircle() {
        // The HSL recipe assumes hue ∈ [0, 360). Spot-check the
        // boundary cases — the helper must never emit a negative
        // hue (would happen if `Math.abs(Int.MIN_VALUE)` slipped
        // through) or a value >= 360.
        val names = listOf("", "a", "ZZZZZZZZ", "/etc/hosts", "🚀", "x".repeat(1024))
        names.forEach { n ->
            val h = HostAvatarColor.hueFor(n)
            assertTrue("hue($n) = $h must be >= 0", h >= 0f)
            assertTrue("hue($n) = $h must be < 360", h < 360f)
        }
    }
}
