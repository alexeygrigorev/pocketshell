#!/bin/sh
# Idle-incremental Claude-style TUI fixture (issues #1302 / #1208).
#
# ## What the maintainer's black screen actually needs
#
# The default `agents:2222` `claude` binary is a stub that prints one line and
# exits, and `agent-entrypoint.sh` seeds `idle-agent`s that just `sleep 3600` —
# so nothing continuously repaints a TUI. That HAPPY fixture masks the
# maintainer's residual fragments-over-black (the G10 "happy fixture masks
# reality" gap): his real symptom needs an agent pane that
#
#   1. paints a FULL multi-row TUI frame ONCE, then
#   2. idle-repaints ONLY a spinner / status line IN PLACE (carriage-return, no
#      newline, no screen clear, no full re-home).
#
# If the Android client ever LOSES the grid (a pre-pipe drop, empty seed,
# overflow, or surface-null), this idle-incremental repaint style can NEVER
# restore it: the client only receives one-cell spinner deltas over a mostly-
# black grid, so the pane stays fragments-over-black indefinitely. Recovery must
# come from the reconciler reseeding the pane from tmux's authoritative
# `capture-pane` — Claude's own output will not self-heal it. That is exactly
# the state the composite recovery journey drives.
#
# ## Contract for the journey
#
#  * tmux's `capture-pane` for this pane always holds the FULL banner (the rows
#    painted in step 1 stay in the grid; the spinner rewrites its own line
#    BELOW them), so a correct reconciler reseed restores >= MIN banner rows.
#  * The banner rows are `"<MARKER> row NN ..."` so the journey's
#    `bannerRowCount` regex (`<MARKER> row (\d{2})`) counts distinct restored
#    rows. The spinner line deliberately does NOT contain `row NN`, so it never
#    inflates the banner-row count.
#  * The spinner emits real `%output` on a ~1s timer, so a client whose steady
#    watchdog has backed off is WOKEN on a suspect (fragments-over-black) pane —
#    the realistic wake the maintainer's live Claude spinner also produced.
#
# Usage (as the tmux new-session payload):
#   sh /opt/pocketshell-agent-fixtures/idle-incremental-claude.sh <MARKER> <ROWS>
set -eu

marker="${1:-PS1302-CLAUDE}"
rows="${2:-40}"

# (1) Paint the FULL TUI frame ONCE. Distinct, numbered, markered rows so the
# journey can count how many the reconciler restores from tmux's grid.
i=1
while [ "$i" -le "$rows" ]; do
  printf '%s row %02d assistant transcript line abcdefghijklmnopqrstuvwxyz\n' "$marker" "$i"
  i=$((i + 1))
done

# (2) Idle-incremental repaint: forever rewrite ONLY a spinner / status line in
# place. Carriage-return to column 1 of the spinner's own line, no newline, no
# `CSI 2J`, no re-home — so the FULL frame above is never repainted and a client
# that lost the grid receives only spinner fragments. This is the "idle Claude
# never self-heals" property: recovery must come from the reconciler, not here.
spin='|/-\'
n=0
while true; do
  c=$(printf '%s' "$spin" | cut -c "$(( (n % 4) + 1 ))")
  printf '\r[busy] %s reconciling frame %d ' "$c" "$n"
  n=$((n + 1))
  sleep 1
done
