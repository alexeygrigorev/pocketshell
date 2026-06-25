package com.pocketshell.app.portfwd

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.portfwd.TunnelInfo
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private fun forwardingTunnel(remotePort: Int, localPort: Int): TunnelInfo =
    TunnelInfo(
        remotePort = remotePort,
        localPort = localPort,
        process = "sshd",
        status = TunnelInfo.Status.FORWARDING,
    )

/** The exact reported shape: two remote-22 forwards (the "Key 22 already used" crash). */
private val DUPLICATE_REMOTE_PORT = listOf(
    forwardingTunnel(remotePort = 22, localPort = 10022),
    forwardingTunnel(remotePort = 22, localPort = 10023),
)

/**
 * Issue #931 — reproduce-first (D33 / G10) RED proof for the port-forward panel
 * crash `IllegalArgumentException: Key "22" already used`.
 *
 * The maintainer's captured crash (`20260601-085515-PocketShell_crash_report.txt`)
 * was a **real Compose `LazyColumn` duplicate-key crash**: the port-forward table
 * keyed every row on `it.remotePort`, and the forwarding list can contain two
 * rows that share a remote port (e.g. the same remote `22` listening on two
 * interfaces, or a remapped/duplicate forward). When two LazyColumn items share a
 * key, `SubcomposeLayout.subcompose` throws `IllegalArgumentException: Key "<n>"
 * was already used` during the measure pass and the whole app crashes (the
 * "always restarting" class, #928 D2/D7).
 *
 * Reproducing the crash as a *live* composition is intentionally NOT done here:
 * `SubcomposeLayout` throws the duplicate-key `IllegalArgumentException` on the
 * main looper during the measure pass, OUTSIDE the JUnit `Statement.evaluate`
 * call stack, so it is an uncaught UI-thread crash that neither a `try/catch`
 * nor `@Test(expected = ...)` can catch — it kills the whole instrumentation
 * process (which is exactly the on-device "always restarting" symptom). Instead,
 * this RED proof asserts the EXACT precondition `SubcomposeLayout.subcompose`
 * rejects: the OLD `key = { it.remotePort }` extractor produces a DUPLICATE key
 * for the reported duplicate-22 list — i.e. it reproduces the
 * `Key "22" already used` collision deterministically — while the production
 * [tunnelRowKeys] does not. The GREEN side that the fix actually *renders* the
 * duplicate list in a real `LazyColumn` lives in
 * [PortForwardDuplicateKeyRenderTest], which composes the production keying for
 * real.
 *
 * PURE assertion + (for the render twin) Compose composition — NO Docker
 * fixture, NO SSH/tmux, NO port, NO `assumeTrue` /
 * `assumeFalse(isRunningOnCi())` self-skip — so it runs on CI as a gate (wired
 * into `scripts/ci-journey-suite.sh`).
 */
@RunWith(AndroidJUnit4::class)
class PortForwardDuplicateKeyCrashTest {

    /** The OLD shipped key extractor, reproduced verbatim for the RED proof. */
    private fun legacyRemotePortKeys(tunnels: List<TunnelInfo>): List<Int> =
        tunnels.map { it.remotePort }

    @Test
    fun oldRemotePortKeyingCollidesOnDuplicateRemotePort() {
        // RED: the OLD keying reuses key 22 for the two remote-22 rows — the
        // exact `IllegalArgumentException: Key "22" already used` precondition
        // that crashed the app.
        val legacy = legacyRemotePortKeys(DUPLICATE_REMOTE_PORT)
        assertEquals("the OLD keying reuses 22", listOf(22, 22), legacy)
        assertEquals(
            "the OLD keying has a duplicate key — the crash precondition",
            DUPLICATE_REMOTE_PORT.size - 1,
            legacy.size - legacy.toSet().size,
        )
    }

    @Test
    fun productionKeyingHasNoCollisionOnDuplicateRemotePort() {
        // GREEN (key level): the production keying keeps the two remote-22 rows
        // distinct, so the LazyColumn duplicate-key check cannot fire.
        val keys = tunnelRowKeys(DUPLICATE_REMOTE_PORT)
        assertEquals(DUPLICATE_REMOTE_PORT.size, keys.toSet().size)
        assertNotEquals(keys[0], keys[1])
    }
}

/**
 * Issue #931 — GREEN proofs that the production [tunnelRowKeys] (the #931 fix)
 * renders the duplicate-bearing lists with NO crash. Each test composes a real
 * Compose `LazyColumn` keyed exactly as the production `PortForwardPanelScreen`
 * now keys its rows (`itemsIndexed` over `tunnelRowKeys(...)`).
 *
 * Class coverage: duplicate remote port (the reported case), duplicate LOCAL
 * port, remote+local+status all identical (the occurrence-counter worst case),
 * and the empty list. PURE Compose composition — NO Docker fixture, NO SSH/tmux,
 * NO port, NO self-skip — wired into `scripts/ci-journey-suite.sh`.
 */
@RunWith(AndroidJUnit4::class)
class PortForwardDuplicateKeyRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun productionKeyingRendersDuplicateRemotePortWithoutCrash() {
        val keys = tunnelRowKeys(DUPLICATE_REMOTE_PORT)
        compose.setContent {
            PocketShellTheme {
                LazyColumn(contentPadding = PaddingValues(0.dp)) {
                    itemsIndexed(
                        DUPLICATE_REMOTE_PORT,
                        key = { index, _ -> keys[index] },
                    ) { _, tunnel ->
                        Text("row ${tunnel.remotePort}->${tunnel.localPort}")
                    }
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("row 22->10022").assertExists()
        compose.onNodeWithText("row 22->10023").assertExists()
    }

    @Test
    fun productionKeyingRendersDuplicateLocalPortWithoutCrash() {
        // Class coverage: two distinct remote ports remapped onto the same local
        // port. The production keying still produces unique keys → no crash.
        val tunnels = listOf(
            forwardingTunnel(remotePort = 3000, localPort = 18080),
            forwardingTunnel(remotePort = 8080, localPort = 18080),
        )
        val keys = tunnelRowKeys(tunnels)
        compose.setContent {
            PocketShellTheme {
                LazyColumn(contentPadding = PaddingValues(0.dp)) {
                    itemsIndexed(tunnels, key = { index, _ -> keys[index] }) { _, tunnel ->
                        Text("local ${tunnel.remotePort}->${tunnel.localPort}")
                    }
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("local 3000->18080").assertExists()
        compose.onNodeWithText("local 8080->18080").assertExists()
    }

    @Test
    fun productionKeyingRendersFullyIdenticalRowsWithoutCrash() {
        // Worst case: remote AND local port AND status all identical. The
        // occurrence counter in tunnelRowKeys must still keep them apart.
        val tunnels = listOf(
            forwardingTunnel(remotePort = 22, localPort = 22),
            forwardingTunnel(remotePort = 22, localPort = 22),
            forwardingTunnel(remotePort = 22, localPort = 22),
        )
        val keys = tunnelRowKeys(tunnels)
        assertTrue("keys must be unique", keys.size == keys.toSet().size)
        compose.setContent {
            PocketShellTheme {
                LazyColumn(contentPadding = PaddingValues(0.dp)) {
                    itemsIndexed(tunnels, key = { index, _ -> keys[index] }) { index, _ ->
                        Text("identical row $index")
                    }
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("identical row 0").assertExists()
        compose.onNodeWithText("identical row 1").assertExists()
        compose.onNodeWithText("identical row 2").assertExists()
    }

    @Test
    fun productionKeyingRendersEmptyListWithoutCrash() {
        // Class coverage: the empty list must not blow up.
        val empty = emptyList<TunnelInfo>()
        val keys = tunnelRowKeys(empty)
        assertTrue(keys.isEmpty())
        compose.setContent {
            PocketShellTheme {
                LazyColumn(contentPadding = PaddingValues(0.dp)) {
                    itemsIndexed(empty, key = { index, _ -> keys[index] }) { _, _ -> }
                }
            }
        }
        compose.waitForIdle()
    }
}
