#!/bin/sh
set -eu

listen_port="${PACKET_LOSS_LISTEN_PORT:-2229}"
target_host="${PACKET_LOSS_TARGET_HOST:-agents}"
target_port="${PACKET_LOSS_TARGET_PORT:-22}"
loss_rate="${PACKET_LOSS_RATE:-5%}"

# Issue #1876: the fixture used to model packet loss ONLY (zero added latency),
# which is a LAN with holes in it — not a mobile link. The reconcile-chain defect
# this fixture now has to reproduce is driven by ROUND-TRIP TIME: a serial chain
# of N SSH exec channels costs N x (several RTT), so it is invisible at 0 ms RTT
# and deterministic at 150-200 ms. These knobs are OPTIONAL and default to empty,
# so every pre-existing caller (the #346 connected packet-loss proof, the nightly
# fault gate, docker-compose) keeps the exact loss-only qdisc it had before.
#
# `netem` here shapes the proxy container's EGRESS, and socat relays both
# directions through that one interface, so each direction is delayed once:
# observed client<->server RTT is ~2 x PACKET_LOSS_DELAY.
delay_ms="${PACKET_LOSS_DELAY_MS:-}"
jitter_ms="${PACKET_LOSS_JITTER_MS:-}"

netem_args="loss ${loss_rate}"
if [ -n "$delay_ms" ]; then
  if [ -n "$jitter_ms" ]; then
    netem_args="delay ${delay_ms}ms ${jitter_ms}ms distribution normal ${netem_args}"
  else
    netem_args="delay ${delay_ms}ms ${netem_args}"
  fi
fi

echo "packet-loss-proxy listen=0.0.0.0:${listen_port} target=${target_host}:${target_port} netem=${netem_args}"
# shellcheck disable=SC2086 # netem_args is a deliberately word-split argument list.
tc qdisc replace dev eth0 root netem $netem_args
tc qdisc show dev eth0

exec socat -d -d "TCP-LISTEN:${listen_port},fork,reuseaddr" "TCP:${target_host}:${target_port}"
