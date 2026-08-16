package com.pocketshell.app.proof

import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.settings.AppSettings
import com.pocketshell.app.settings.OutboundDeliveryAuthority
import com.pocketshell.app.settings.SettingsRepository
import com.pocketshell.app.testaccess.TestAccessEntryPoint
import dagger.hilt.android.EntryPointAccessors
import org.junit.Assert.assertEquals

/**
 * Issue #2124: select the outbound delivery authority for a connected journey —
 * on the SINGLETON the running app actually reads.
 *
 * ## Why the obvious implementation is a silent no-op (round-1 defect)
 *
 * The first version of this helper wrote `app_settings` SharedPreferences
 * directly and read correctly at every line — and constrained nothing.
 * [SettingsRepository] is a `@Singleton` built during `App.onCreate` Hilt field
 * injection, which in a self-instrumenting androidTest happens at process start,
 * **before any `@Rule`/`@Before` body runs**. Its constructor kicks off an eager
 * `Dispatchers.IO` preload of the persisted snapshot into `warmSnapshot`, and it
 * never re-reads the prefs file afterwards. So a prefs write from a seed step
 * cannot change the authority the ViewModel observes: the two journeys that
 * exist to prove the legacy escape hatch silently ran the ACKNOWLEDGED path
 * instead (their diagnostic tails carried four `outbound_host_ack_send` events).
 *
 * `TestAccessEntryPoint.settingsRepository()`'s own KDoc had already documented
 * this exact trap for `defaultHostId` (#951). The pin now goes through the same
 * door: the production setter on the production singleton, which writes the
 * prefs file AND the in-memory `StateFlow` that
 * `TmuxSessionViewModel.hostAck.authority` reads on every send.
 *
 * ## The pin proves itself (a pin that cannot be observed is not a pin)
 *
 * [pinOutboundDeliveryAuthority] HARD-ASSERTS, through the exact expression
 * production reads (`settingsRepository.settings.value.outboundDeliveryAuthority`
 * — see `HostAckDeliveryPort`'s `configuredAuthority` lambda), that the
 * selection is in effect before the journey continues. Under the round-1
 * implementation that assertion FAILS (the singleton still answers the shipped
 * default), so this class can never again no-op silently.
 *
 * The behavioural half of the proof lives in the journeys themselves: a class
 * pinned to [OutboundDeliveryAuthority.TerminalInference] asserts that ZERO
 * `outbound_host_ack_send` diagnostics were recorded (see
 * [assertNoAcknowledgedSendsWereRecorded]) — the event only `deliverViaHostAck`
 * emits, and exactly the signal that exposed the round-1 no-op.
 */
internal fun settingsRepositorySingletonForTest(): SettingsRepository =
    EntryPointAccessors.fromApplication(
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
        TestAccessEntryPoint::class.java,
    ).settingsRepository()

/**
 * Select [authority] on the app's singleton settings repository and prove the
 * selection took, reading it back through the production expression.
 */
internal fun pinOutboundDeliveryAuthority(authority: OutboundDeliveryAuthority) {
    val repository = settingsRepositorySingletonForTest()
    repository.setOutboundDeliveryAuthority(authority)
    assertEquals(
        "the outbound-delivery authority pin did not bind to the singleton the app " +
            "reads — a pin that cannot be observed is not a pin (issue #2124)",
        authority,
        repository.settings.value.outboundDeliveryAuthority,
    )
}

/**
 * The BEHAVIOURAL half of the pin's liveness proof: with the legacy authority
 * selected, not one send may have travelled the acknowledged lane.
 *
 * [com.pocketshell.app.tmux.HostAckSendProbe] counts every acknowledged-lane
 * delivery ATTEMPT — incremented at the top of `HostAckDeliveryPort.deliver`,
 * above the transport guard, so a send that entered the lane while the session
 * was down counts too (reviewer round 2: with the increment on the exec, this
 * class's zero had a blind spot in exactly the journey that cuts the link on
 * purpose). Under the round-1 prefs-write pin this count was FOUR in
 * `OutboundExactlyOnceAcrossFlapE2eTest` while the class believed it was
 * exercising the legacy inference lifecycle. Call it after the journey's sends.
 */
internal fun assertNoAcknowledgedSendsWereRecorded(context: String) {
    assertEquals(
        "$context: this class is pinned to the LEGACY inference authority, so no send " +
            "may travel the acknowledged host-CLI lane — a non-zero count means the " +
            "authority pin did not bind (issue #2124)",
        0L,
        com.pocketshell.app.tmux.HostAckSendProbe.count(),
    )
}

/** Restore the shipped default so the next class starts unpinned. */
internal fun clearOutboundDeliveryAuthorityPin() {
    val repository = settingsRepositorySingletonForTest()
    repository.setOutboundDeliveryAuthority(AppSettings.DEFAULT_OUTBOUND_DELIVERY_AUTHORITY)
    assertEquals(
        "clearing the pin must restore the shipped default on the singleton",
        AppSettings.DEFAULT_OUTBOUND_DELIVERY_AUTHORITY,
        repository.settings.value.outboundDeliveryAuthority,
    )
}
