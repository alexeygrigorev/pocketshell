#!/usr/bin/env python3
"""Classify connection-log flap signatures for objective release verification.

Reads the PocketShell connection log (`~/.pocketshell/connection-log.jsonl` by
default) and reports how often each known flap signature occurred. It exists so
"is the connection actually stable now?" is a NUMBER against a baseline, not a
feeling (the D33 "be certain" directive).

Usage:
    scripts/flap-report.py                      # whole log
    scripts/flap-report.py --since 66048        # only events after seq 66048
    scripts/flap-report.py --log /path/to.jsonl
    scripts/flap-report.py --baseline-help      # how to verify a release

Each log line is a JSON object:
    {sequence, wallClockTime, category:"reconnect", name:"cause_trail",
     metadata:{cause, trigger, outcome, stage}}

The SPURIOUS signatures below are teardowns/redials of a transport that was
(or should have been) alive on a good link — the flapping the maintainer
reported. Each notes the release that fixed it, so a post-install run should
show them at ~zero after its `--since` baseline.
"""
import argparse
import collections
import json
import os
import sys

# cause -> (human label, status note). Status is deliberately conservative:
# only signatures with a direct, shipped fix are marked FIXED; the rest say
# "monitor" so a post-install run informs rather than overclaims.
SPURIOUS = {
    "within_grace_foreground_socket_drop": ("within-grace foreground heal teardown", "FIXED v0.4.33 (#1538)"),
    "silent_heal_within_grace": ("silent within-grace heal -> teardown", "FIXED v0.4.33 (#1538)"),
    "stale_lease_attach_eof": ("fast-switch stale-lease redial", "OPEN (#1537 a/b decision)"),
    "reader_exception": ("reader-exception passive disconnect", "MONITOR (real reader EOF; spurious only if link was good)"),
    "auto_reconnect_loop": ("auto-reconnect redial storm", "MONITOR (amortized by #928/#1533; watch the count)"),
}
# Causes that are legitimate lifecycle, not flaps — reported separately for context.
BENIGN = {
    "within_grace", "within_grace_foreground", "process_background", "post_grace",
    "post_grace_foreground", "connect_invoked", "explicit_close", "grace_deadline",
    "background_grace_elapsed", "app_background_grace_elapsed", "ProcessStopped",
    "process-foreground", "foreground_active", "connection_status_reconnecting",
    "grace_deadline_reached",
}


def load(path, since):
    events = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                e = json.loads(line)
            except json.JSONDecodeError:
                continue
            if since is not None and (e.get("sequence") or 0) <= since:
                continue
            events.append(e)
    return events


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--log", default=os.path.expanduser("~/.pocketshell/connection-log.jsonl"))
    ap.add_argument("--since", type=int, default=None, help="only count events with sequence > this (post-install baseline)")
    ap.add_argument("--baseline-help", action="store_true")
    args = ap.parse_args()

    if args.baseline_help:
        print(
            "To verify a release objectively:\n"
            "  1. Note the current last sequence:  tail -1 <log> | python3 -c \"import sys,json;print(json.load(sys.stdin)['sequence'])\"\n"
            "  2. Install the new build, then USE it a while (background/foreground,\n"
            "     switch sessions, run agents) so it writes fresh cause_trail events.\n"
            "  3. Re-run:  scripts/flap-report.py --since <that sequence>\n"
            "  A stable release shows the fixed SPURIOUS signatures at ~0 after the baseline."
        )
        return 0

    if not os.path.exists(args.log):
        print(f"no connection log at {args.log}", file=sys.stderr)
        return 2

    events = load(args.log, args.since)
    if not events:
        scope = f" after seq {args.since}" if args.since is not None else ""
        print(f"no events{scope} in {args.log} "
              f"(if you just installed a build, use the app first so it logs)")
        return 0

    counts = collections.Counter()
    first_seen, last_seen = {}, {}
    for e in events:
        cause = e.get("metadata", {}).get("cause", "?")
        counts[cause] += 1
        t = (e.get("wallClockTime") or "?")[:19]
        first_seen.setdefault(cause, t)
        last_seen[cause] = t

    seqs = [e.get("sequence") for e in events if e.get("sequence") is not None]
    times = [(e.get("wallClockTime") or "")[:19] for e in events if e.get("wallClockTime")]
    scope = f"seq > {args.since}" if args.since is not None else "whole log"
    print(f"== flap-report ({scope}) ==")
    if seqs:
        print(f"events: {len(events)}  seq {min(seqs)}..{max(seqs)}")
    if times:
        print(f"window: {min(times)} .. {max(times)}")

    spurious_total = 0
    print("\nSPURIOUS flap signatures (teardown/redial of an alive-on-good-link transport):")
    any_spurious = False
    for cause, (label, fixed) in SPURIOUS.items():
        n = counts.get(cause, 0)
        if n:
            any_spurious = True
            spurious_total += n
            print(f"  {n:4d}  {cause:38s} {label}  [{fixed}]  last={last_seen[cause]}")
    if not any_spurious:
        print("  (none)")

    other = [(c, n) for c, n in counts.items() if c not in SPURIOUS and c not in BENIGN]
    if other:
        print("\nOther / unclassified causes (review if unexpected):")
        for cause, n in sorted(other, key=lambda x: -x[1]):
            print(f"  {n:4d}  {cause:38s} last={last_seen[cause]}")

    print(f"\nSPURIOUS TOTAL{(' after seq %d' % args.since) if args.since is not None else ''}: {spurious_total}")
    still_open = [c for c in SPURIOUS if counts.get(c) and SPURIOUS[c][1].startswith("OPEN")]
    if still_open:
        print("NOTE: still-open (undecided) signatures present: " + ", ".join(still_open))
    return 0


if __name__ == "__main__":
    sys.exit(main())
