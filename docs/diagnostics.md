# Diagnostics Flight Recorder

PocketShell keeps a bounded app-local diagnostics log for reconnect, freeze,
and crash follow-up. The recorder is enabled by default and can be disabled in
Settings -> Diagnostics.

The exported file is newline-delimited JSON.

Each line is one event with these common fields:

- `sequence`: monotonically increasing event number for the current retained
  log window.
- `wallClockTime`: UTC timestamp for human review.
- `monotonicTimestampNanos`: Android elapsed-time clock for ordering events
  across sleep and wall-clock changes.
- `category` and `name`: stable event family and event name.
- `metadata`: compact, redacted fields for the event.

## Recorded Events

The recorder focuses on metadata needed to reconstruct the cases behind issue
#548:

- app lifecycle: app created, foreground, background, grace-window start,
  grace-window elapsed, and foreground return within or after the grace window.
- connection lifecycle: SSH open attempts, tmux connect and reconnect starts,
  successes, and failures. Disconnect markers, latency milestones, and
  reconnect cause-trail breadcrumbs are also recorded.
- network lifecycle: validated default-network changes, deferred changes while
  backgrounded, and suppressed changes when a short app-switch preserved a live
  terminal runtime.
- user actions: route changes, tab switches, reconnect taps, keyboard panel
  opens, composer recording state changes, attachment counts, and
  port-forward actions.

## Privacy Rules

Diagnostics are intended to be shared after a bad phone session. They must not
include terminal contents or command-like input. That covers commands, prompts,
and keystrokes. Attachment contents and conversation text are also excluded.
Secrets such as API keys and passphrases are excluded, along with tokens,
cookies, and private-key paths.

Hostnames, usernames, tmux session names, and filesystem paths are exported as
stable short SHA-256 fingerprints when they appear in metadata. This preserves
correlation across events without exposing the original text.

Safe coarse fields remain readable, so fields such as `hostKind` and `port` can
be inspected directly. Counts and byte sizes are also preserved, along with
durations, trigger names, and screen routes.

## Export

Use Settings -> Diagnostics -> Share diagnostics JSONL. The share action copies
the retained log to `cacheDir/diagnostics-export/` and opens Android's standard
share sheet with a `.jsonl` file.

Use Start fresh diagnostics capture before reproducing a reconnect or app-switch
bug. That clears the retained window, re-enables recording, and writes a
`diagnostics/capture_started` marker so the export starts at the attempted
reproduction.
