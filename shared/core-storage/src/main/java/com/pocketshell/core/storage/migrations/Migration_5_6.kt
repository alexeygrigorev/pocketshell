package com.pocketshell.core.storage.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room migration from schema v5 → v6.
 *
 * Issue #41 (false-negative `quse not installed` on Hetzner-style hosts):
 * the bootstrap probe runs `/bin/sh -lc`, which sources `~/.profile` but
 * NOT `~/.bashrc`. Maintainers who install Python tools as cloned repos
 * with venvs (`export PATH="~/git/quse/.venv/bin:$PATH"` in `.bashrc`)
 * have those paths invisible to the probe. The fix is a per-host PATH
 * override that the user enters once on the Add/Edit Host screen; the
 * probe prepends it ahead of the existing augmentation
 * (`$HOME/.local/bin:$HOME/bin:$HOME/.cargo/bin:$PATH`).
 *
 * - `pathOverride TEXT` (nullable): optional colon-separated list of
 *   extra directories (e.g.
 *   `/home/alex/git/quse/.venv/bin:/home/alex/git/tmuxcli/.venv/bin`).
 *   `NULL` and the empty string both mean "no override". The value is
 *   forwarded verbatim into the shell wrapper — no sanitisation beyond
 *   the standard single-quote escape — because the user is intentionally
 *   choosing what their shell sees, mirroring [HostEntity.usageCommandOverride].
 *
 * The column is nullable with a default of `NULL`, so existing rows keep
 * the previous probe behaviour and the next bootstrap pass will continue
 * to use only the built-in PATH augmentation.
 */
public val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE hosts ADD COLUMN pathOverride TEXT")
    }
}
