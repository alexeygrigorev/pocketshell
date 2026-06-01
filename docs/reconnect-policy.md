# Reconnect policy

PocketShell automatically reconnects only for visible, active SSH and tmux
session screens after an unexpected transport drop. The retry schedule is
bounded: immediate retry, then 1s, 2s, and 5s. During this window the session
status is `Reconnecting`, prompt sending remains disabled, and the user can
cancel or wait for the session to return to `Connected`.

If all retries fail, the screen moves to `Failed` and keeps the manual
Reconnect action available for the last known host/session target.

Background behavior follows D21: normal SSH/tmux sessions do not keep retrying
while the app is stopped. The tmux lifecycle hook detaches the phone-side
control client on background and reattaches on foreground. The only background
exception is port forwarding, which owns its foreground-service carve-out and
its own reconnect loop while forwarding is actively enabled.
