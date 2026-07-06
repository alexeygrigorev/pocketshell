#!/bin/sh
# Busy-agent burst fixture (issues #1302 / #1208 / #1297).
#
# ## Why a busy fixture is needed
#
# Every heal net funnels its recovery through ONE per-host `sendMutex`-guarded
# `capture-pane` round-trip (the #1297 SPOF). A Claude burst that saturates the
# `-CC` output channel is exactly the state that wedges that capture: the heal's
# `capture-pane` queues behind the flood and times out at the 2.5s ceiling, so a
# lost grid stays black while the channel is busy. The #1297 wedge-proof lane
# lets the heal capture through under load; this fixture is what exercises it and
# proves "reconcile under load" on the real connected path.
#
# ## Behavior (deterministic on-device shape)
#
# 1. Paint the FULL markered banner (tmux's authoritative grid).
# 2. Flood the `-CC` channel with heavy alt-screen repaint BURSTS for a bounded
#    ~12s — this is the capture-wedge state, saturating the channel while the
#    client attaches, seeds, and the reconciler makes its first heal attempts.
# 3. REPAINT the banner and then idle-repaint ONLY a spinner line forever.
#
# The bounded burst makes the fixture DETERMINISTIC: the pane always settles to a
# stable authoritative banner, so a journey can drive a grid loss on a pane that
# HAS BEEN under heavy `-CC` load and assert the reconciler converges it back to
# tmux's banner — without racing one exact live flood frame (the full continuous
# multi-session mutex-contention run is the batched-lane acceptance per #1208).
#
# The banner rows are `"<MARKER> row NN ..."` (same shape as the idle fixture) so
# the journey's `bannerRowCount` regex counts restored rows; the burst rows use a
# distinct `BURST` prefix and no `row NN`, so they never inflate the banner count.
#
# Usage (as the tmux new-session payload):
#   sh /opt/pocketshell-agent-fixtures/busy-agent-burst.sh <MARKER> <ROWS>
set -eu

marker="${1:-PS1302-BUSY}"
rows="${2:-40}"
burst_seconds="${3:-12}"

paint_banner() {
  printf '\033[2J\033[H'
  i=1
  while [ "$i" -le "$rows" ]; do
    printf '%s row %02d assistant transcript line abcdefghijklmnopqrstuvwxyz\n' "$marker" "$i"
    i=$((i + 1))
  done
}

# (1) Authoritative banner.
paint_banner

# (2) Bounded heavy flood: repaint the whole alt-screen faster than a client can
# drain, saturating the `-CC` channel so a heal `capture-pane` has to contend for
# the send mutex. Bounded by wall-clock so the pane deterministically settles.
start=$(date +%s)
frame=0
while [ "$(( $(date +%s) - start ))" -lt "$burst_seconds" ]; do
  printf '\033[2J\033[H'
  row=0
  while [ "$row" -lt "$rows" ]; do
    printf '\033[3%dmBURST frame %d row %02d %s\033[0m\n' \
      "$(( row % 7 ))" "$frame" "$row" 'xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx'
    row=$((row + 1))
  done
  frame=$((frame + 1))
done

# (3) Settle to the authoritative banner, then idle-repaint only a spinner line
# in place (like the idle fixture) so a lost grid can never self-heal.
paint_banner
spin='|/-\'
n=0
while true; do
  c=$(printf '%s' "$spin" | cut -c "$(( (n % 4) + 1 ))")
  printf '\r[busy] %s settled frame %d ' "$c" "$n"
  n=$((n + 1))
  sleep 1
done
