# Reconnect policy

PocketShell automatically reconnects only for visible, active SSH terminal
screens after an unexpected transport drop. The remote session is owned by
aplexer and remains independent of the phone's SSH transport. The retry
schedule is bounded: immediate retry, then 1s, 2s, and 5s. During this window
the session status is `Reconnecting`, prompt sending remains disabled, and the
user can cancel or wait for the session to return to `Connected`.

If all retries fail, the screen moves to `Failed` and keeps the manual
Reconnect action available for the last known host and aplexer session target.

Background behavior follows D21: an ordinary SSH terminal does not keep
retrying while the app is stopped. The bounded grace coordinator keeps the
visible transport available for the configured window; after that window the
transport closes and the next foreground visit reconnects and reattaches to the
same host session. Port forwarding is the separate foreground-service
exception and owns its own reconnect loop while forwarding is enabled.
