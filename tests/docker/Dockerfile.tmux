# Tmux test image for PocketShell (`pocketshell-test:tmux`).
#
# Extends the base SSH image with tmux and `tmuxctl` (the server-side
# scheduler PocketShell delegates to per D7). Used by:
#   - `shared/core-tmux` integration tests (`tmux -CC` control-mode wiring)
#   - Recurring-jobs tests in later issues
#
# Per `docs/testing.md` (`Adding tmux + tmuxctl`).
FROM pocketshell-test:ssh

RUN apk add --no-cache tmux python3 py3-pip \
 && pip install --break-system-packages tmuxctl
