package com.pocketshell.testsupport

/**
 * Issue #1994 — the pure DECISION behind "did PocketShell leave an orphan tmux
 * client behind?".
 *
 * ## Why this decision is not simply `list-clients | wc -l`
 *
 * The detach/orphan journeys ran their acceptance against the *shared* Docker
 * fixture session (`claude-main`) and asserted the TOTAL client count reached
 * zero. That count answers a different question than the one the maintainer
 * cares about. `tmux list-clients -t <session>` reports **every** client on the
 * session, including clients this journey never created:
 *
 *  * the nightly runs ~300 connected tests in ONE instrumentation process
 *    against ONE tmux server, and several of those tests attach their own
 *    sidecar clients (a "desktop" `tmux attach`, a second-client input probe);
 *  * when a session that owns a client is killed, tmux does **not** kill the
 *    client — it moves it to another session. So one test killing its own
 *    session can migrate a stray client onto the very session a later test is
 *    about to assert on.
 *
 * A total-count assertion therefore reports `expected:<0> but was:<1>` for a
 * *foreign* client with the identical text it uses for a genuine PocketShell
 * leak — which is exactly the ambiguity issue #1994 asks to end ("distinguish a
 * product detach regression from a leaked prior-test client"). Worse, the
 * ambiguity is one-directional in the dangerous way: it manufactures a red that
 * points at the product.
 *
 * ## The decision
 *
 * Ownership is established by *identity* **and by kind**, not by counting. The
 * journey snapshots the client names present BEFORE it attaches (the foreign
 * baseline), snapshots again once its own attach is live (the difference is the
 * app-owned set), and then asks [evaluateOwnedClientDetach] whether the
 * app-owned clients are gone.
 *
 *  * app-owned client still present  -> FAIL (the real orphan regression);
 *  * **a `-CC` control-mode client in the pre-attach baseline -> FAIL**, never
 *    laundered into "foreign". See "the laundering hole" below;
 *  * a client that is neither foreign-baseline nor app-owned appeared
 *    afterwards -> FAIL (a post-teardown re-dial / duplicate attach, which is a
 *    D21 violation and must never be silently tolerated);
 *  * foreign-baseline **plain** client still present -> IGNORED for pass/fail,
 *    but always reported in the diagnosis so a reviewer sees it. A plain client
 *    is the only shape that may ever be excused.
 *
 * ## The laundering hole this closes (issue #1994, round 2)
 *
 * A baseline-only definition of "foreign" is unsafe on a 300-class shard: a
 * PocketShell `-CC` client **leaked by an EARLIER class in the same
 * instrumentation process** is already attached when this journey takes its
 * baseline, so a purely time-based rule would file it under "foreign" and pass.
 * That is strictly worse than the total-count oracle it replaced — the old
 * oracle at least went red.
 *
 * Nothing else in this suite opens a control-mode client: `-CC` on the fixture
 * is PocketShell, by construction. So **control-mode implies app-owned**,
 * regardless of when it attached, and a control-mode client in the baseline is
 * reported as [LEAKED_CONTROL_MODE_CLIENT_SIGNATURE] and is fatal.
 *
 * ## Attribution is never amnesty (the port-forward pin)
 *
 * While a port-forward pins the connection always-on, `App.kt`'s
 * `dispatchGraceElapsedIfNeeded` deliberately SUPPRESSES the bounded-grace
 * teardown (#1159 Part 3, the D21 carve-out), so the app's `-CC` client stays
 * attached by design and never converges. On nightly shard 2 of run
 * 30961154855 a forwarding pin leaked from an earlier class and produced exactly
 * that state — reported as a plain orphan, pointing at the product.
 * [describeOwnedDetachFailure] NAMES the pin as the cause when it is active, but
 * the verdict is still a FAILURE: a surviving client is never excused.
 *
 * ## Why it lives here rather than in `androidTest`
 *
 * Same reason as [parseImeServiceState]: the IO (an SSH exec against the Docker
 * fixture) is emulator/Docker-only, but the *discrimination* it drives is the
 * part that was wrong, and it must be pinned in the required per-PR `Unit tests`
 * job rather than only in the nightly. `app/src/test/.../TmuxClientOwnershipTest`
 * is that pin; the connected journeys only perform the IO.
 */

/**
 * Field separator for [TMUX_CLIENT_OWNERSHIP_FORMAT].
 *
 * It MUST be printable ASCII. The first cut of #1994 used a literal TAB and it
 * came back as `_` on the Docker fixture's tmux (3.6b), observed directly:
 * `list-sessions -F '#{session_name}<TAB>X'` printed `fmtprobe2_X_$0`. tmux
 * routes some `list-*` output through a message path that sanitises control
 * characters, and whether a given separator survives depends on the tmux build
 * and the output path — a reviewer could NOT reproduce the substitution on tmux
 * 3.4 over an isolated socket, so treat the sanitisation as
 * build/path-specific rather than universal. The consequence when it does bite
 * is not: the row never splits, every field collapses into the client NAME,
 * ownership matching silently never matches, and the proof degrades to nonsense
 * while still looking structured. A printable separator is safe on every build,
 * so it is the one to use. [TmuxClientOwnershipTest] pins it.
 *
 * `~|~` is safe against the actual field contents: `client_name` is a device
 * path, `client_control_mode` is `0`/`1`, and `client_flags` is a comma-separated
 * word list.
 */
const val TMUX_CLIENT_FIELD_SEPARATOR: String = "~|~"

/**
 * The `-F` format the journeys must pass to `tmux list-clients` so this parser
 * has a stable, non-localised input.
 */
const val TMUX_CLIENT_OWNERSHIP_FORMAT: String =
    "#{client_name}$TMUX_CLIENT_FIELD_SEPARATOR#{client_control_mode}" +
        "$TMUX_CLIENT_FIELD_SEPARATOR#{client_flags}"

/** One row of `tmux list-clients -F [TMUX_CLIENT_OWNERSHIP_FORMAT]`. */
data class TmuxClientRecord(
    /** `#{client_name}` — the client's tty path, e.g. `/dev/pts/24`. */
    val name: String,
    /** True when tmux reports the client as a `-CC` control-mode client. */
    val controlMode: Boolean,
    /** Raw `#{client_flags}`, e.g. `attached,focused,control-mode,UTF-8`. */
    val flags: String,
) {
    fun describe(): String = "$name[${if (controlMode) "control-mode" else "plain"}|$flags]"
}

/**
 * Emitted when the acceptance is decided while a foreign client sits on the
 * session. Kept as a constant so a reviewer can grep one phrase to see that a
 * green verdict knowingly ignored a client it did not create.
 */
const val FOREIGN_TMUX_CLIENT_IGNORED_SIGNATURE: String =
    "foreign (not created by this journey) tmux client(s) ignored by the owned-detach oracle"

/** Emitted when a client appeared that is neither foreign-baseline nor owned. */
const val UNEXPECTED_NEW_TMUX_CLIENT_SIGNATURE: String =
    "a tmux client that this journey never created appeared after the app attached"

/**
 * Emitted when a `-CC` control-mode client sits in the pre-attach baseline.
 *
 * Nothing but PocketShell opens a control-mode client against this fixture, so
 * such a client was leaked by an EARLIER class in the same instrumentation
 * process. It is app-owned by kind and must never be laundered into "foreign".
 */
const val LEAKED_CONTROL_MODE_CLIENT_SIGNATURE: String =
    "a PocketShell -CC (control-mode) tmux client was already attached before this journey " +
        "started — control-mode is NEVER foreign on this fixture, so it is an app client " +
        "leaked by an EARLIER class in this instrumentation process, not a stray sidecar"

/**
 * Emitted when the acceptance is decided while a port-forward pins the
 * connection always-on. Names the cause; never converts a failure into a pass.
 */
const val GRACE_HELD_BY_PORT_FORWARD_PIN_SIGNATURE: String =
    "grace held by port-forward pin (#1159 Part 3): a port-forward was active at verdict time, " +
        "so App.dispatchGraceElapsedIfNeeded SUPPRESSED the bounded teardown and the app's -CC " +
        "client stayed attached BY DESIGN — this is the named cause, not a product orphan, and " +
        "it is still a hard failure because the journey's precondition (no pin) was violated"

/** Verdict of [evaluateOwnedClientDetach]. */
data class OwnedClientDetachVerdict(
    /** App-owned clients still attached — the genuine orphan regression. */
    val ownedRemaining: List<TmuxClientRecord>,
    /**
     * Clients that are neither foreign-baseline nor app-owned — i.e. they
     * appeared *after* the app attached. A control-mode one is a post-teardown
     * re-dial (a D21 violation); a plain one is a sidecar the journey never
     * created. Both are fatal.
     */
    val unexpectedNew: List<TmuxClientRecord>,
    /** Foreign-baseline PLAIN clients still attached; reported, never fatal. */
    val foreignRemaining: List<TmuxClientRecord>,
    /**
     * Control-mode clients present in the pre-attach baseline — PocketShell
     * clients leaked by an earlier class. Fatal; never laundered as foreign.
     */
    val leakedControlRemaining: List<TmuxClientRecord> = emptyList(),
) {
    /**
     * True only when every app-owned client is gone, no leaked control-mode
     * client is attached, and nothing new appeared.
     */
    val detached: Boolean
        get() = ownedRemaining.isEmpty() &&
            unexpectedNew.isEmpty() &&
            leakedControlRemaining.isEmpty()

    fun diagnosis(): String = buildString {
        append("owned_remaining=")
        append(ownedRemaining.joinToString(",") { it.describe() }.ifEmpty { "<none>" })
        append(" leaked_control_remaining=")
        append(leakedControlRemaining.joinToString(",") { it.describe() }.ifEmpty { "<none>" })
        append(" unexpected_new=")
        append(unexpectedNew.joinToString(",") { it.describe() }.ifEmpty { "<none>" })
        append(" foreign_remaining=")
        append(foreignRemaining.joinToString(",") { it.describe() }.ifEmpty { "<none>" })
        if (leakedControlRemaining.isNotEmpty()) {
            append(" — ")
            append(LEAKED_CONTROL_MODE_CLIENT_SIGNATURE)
        }
        if (unexpectedNew.isNotEmpty()) {
            append(" — ")
            append(UNEXPECTED_NEW_TMUX_CLIENT_SIGNATURE)
        }
        if (foreignRemaining.isNotEmpty()) {
            append(" — ")
            append(FOREIGN_TMUX_CLIENT_IGNORED_SIGNATURE)
        }
    }
}

/**
 * The failure text a detach journey must assert with.
 *
 * When [portForwardPinActive] is true the message NAMES the pin (#1159 Part 3)
 * as the cause instead of blaming the product for an orphan — but the caller
 * still fails, because [OwnedClientDetachVerdict.detached] is unchanged. This is
 * the same "attribution is never amnesty" rule the capture oracle uses for a
 * foreign window: naming the owner explains a red, it never manufactures a
 * green.
 *
 * Returns `null` when [verdict] is clean, so a caller cannot accidentally
 * fabricate a failure out of a healthy verdict.
 */
fun describeOwnedDetachFailure(
    verdict: OwnedClientDetachVerdict,
    portForwardPinActive: Boolean,
    sessionName: String,
    rawClients: String,
): String? {
    if (verdict.detached) return null
    val cause = if (portForwardPinActive) {
        GRACE_HELD_BY_PORT_FORWARD_PIN_SIGNATURE
    } else {
        "PocketShell left its own tmux client attached to $sessionName after the bounded " +
            "grace elapsed (no port-forward pin was active, so the teardown was NOT suppressed " +
            "— this is a genuine detach regression)"
    }
    return "$cause; port_forward_pin_active=$portForwardPinActive " +
        "${verdict.diagnosis()}; raw=`$rawClients`"
}

/**
 * Parse `tmux list-clients -F [TMUX_CLIENT_OWNERSHIP_FORMAT]` output.
 *
 * Blank lines are dropped (tmux emits a trailing newline, and `|| true` in the
 * journeys' shell wrapper can add an empty line when no server is running).
 * A row with fewer fields than the format promises is kept with the fields it
 * does have rather than dropped: silently discarding an unparsable client would
 * turn a real orphan into a green.
 */
fun parseTmuxClients(listClientsStdout: String): List<TmuxClientRecord> =
    listClientsStdout.lines()
        .map { it.trimEnd('\r') }
        .filter { it.isNotBlank() }
        .map { line ->
            val fields = line.split(TMUX_CLIENT_FIELD_SEPARATOR)
            val name = fields.getOrNull(0)?.trim().orEmpty()
            val controlModeField = fields.getOrNull(1)?.trim().orEmpty()
            val flags = fields.getOrNull(2)?.trim().orEmpty()
            TmuxClientRecord(
                name = name.ifEmpty { UNNAMED_TMUX_CLIENT },
                // tmux renders a boolean format variable as "1"/"0"; fall back to
                // the flag list so a tmux build that renders it differently still
                // classifies a control client correctly.
                controlMode = controlModeField == "1" || flags.contains("control-mode"),
                flags = flags,
            )
        }

/** Placeholder name for a client row tmux emitted without a name. */
const val UNNAMED_TMUX_CLIENT: String = "<unnamed-client>"

/**
 * Decide whether the clients THIS journey created are gone.
 *
 * @param foreignBaseline client names observed before the app attached.
 * @param ownedNames client names attributed to the app's own attach.
 * @param current the clients tmux reports right now.
 */
fun evaluateOwnedClientDetach(
    foreignBaseline: Set<String>,
    ownedNames: Set<String>,
    current: List<TmuxClientRecord>,
): OwnedClientDetachVerdict {
    val owned = current.filter { it.name in ownedNames }
    // Control-mode is app-owned BY KIND. A `-CC` client sitting in the pre-attach
    // baseline was leaked by an EARLIER class in the same instrumentation
    // process; classifying it as foreign because it predates our baseline is the
    // laundering hole (#1994 round 2) and would hide the exact symptom the
    // nightly reported.
    val leakedControl = current.filter {
        it.name !in ownedNames && it.controlMode && it.name in foreignBaseline
    }
    // Only a PLAIN client may ever be excused. A control-mode client is never
    // foreign on this fixture.
    val foreign = current.filter {
        it.name in foreignBaseline && it.name !in ownedNames && !it.controlMode
    }
    val unexpected = current.filter {
        it.name !in ownedNames && it.name !in foreignBaseline
    }
    return OwnedClientDetachVerdict(
        ownedRemaining = owned,
        unexpectedNew = unexpected,
        foreignRemaining = foreign,
        leakedControlRemaining = leakedControl,
    )
}

/**
 * The app-owned set: whatever appeared between the pre-attach baseline and the
 * attached snapshot. Returned as names so a later poll can match by identity
 * even after the count changes.
 *
 * The name is the client's tty path and the kernel reuses pts numbers, so a
 * foreign pts that closes and is reallocated to the app inside one journey would
 * be classified foreign by identity alone. That is why kind is the second axis:
 * a reallocated pts carrying a control-mode client is still caught by
 * [evaluateOwnedClientDetach]'s control-mode rule.
 */
fun resolveOwnedClientNames(
    foreignBaseline: Set<String>,
    attachedSnapshot: List<TmuxClientRecord>,
): Set<String> = attachedSnapshot.map { it.name }.filterNot { it in foreignBaseline }.toSet()
