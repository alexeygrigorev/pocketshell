#!/bin/sh
# Issue #1799 (prerequisite fixture for #1639 §H1 — SSH host-key verification).
#
# Regenerate this container's SSH host keys on every START, then hand off to
# sshd. The point of the whole fixture is that `tests/docker/Dockerfile.ssh`
# runs `ssh-keygen -A` at BUILD time, so its host keys live in an image layer
# and EVERY container started from that image presents the SAME key. A
# "the host key changed" scenario is therefore literally unreachable with the
# existing fixture — which is why no test for it can exist today.
#
# Here the image ships with NO host keys at all (the Dockerfile deliberately
# does not run `ssh-keygen -A`), and this entrypoint mints a fresh set per
# container start. Two successive `docker run`s of the SAME image ID present
# DIFFERENT host keys, which is what makes an unknown-key / changed-key
# observation on the real SSH transport possible.
#
# The `rm -f` is belt-and-braces: if a future edit to the Dockerfile
# accidentally reintroduces build-time key generation, this still guarantees a
# fresh key per start rather than silently degrading the fixture back into the
# stable-key behaviour it exists to avoid.
set -eu

rm -f /etc/ssh/ssh_host_*
ssh-keygen -A

exec /usr/sbin/sshd -D -e
