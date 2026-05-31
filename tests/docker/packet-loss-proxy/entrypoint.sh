#!/bin/sh
set -eu

listen_port="${PACKET_LOSS_LISTEN_PORT:-2229}"
target_host="${PACKET_LOSS_TARGET_HOST:-agents}"
target_port="${PACKET_LOSS_TARGET_PORT:-22}"
loss_rate="${PACKET_LOSS_RATE:-5%}"

echo "packet-loss-proxy listen=0.0.0.0:${listen_port} target=${target_host}:${target_port} loss=${loss_rate}"
tc qdisc replace dev eth0 root netem loss "${loss_rate}"
tc qdisc show dev eth0

exec socat -d -d "TCP-LISTEN:${listen_port},fork,reuseaddr" "TCP:${target_host}:${target_port}"
