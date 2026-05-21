package com.pocketshell.core.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import com.pocketshell.core.storage.dao.AgentSessionDao
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.PortRemappingDao
import com.pocketshell.core.storage.dao.SessionDao
import com.pocketshell.core.storage.dao.SnippetDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.AgentSessionEntity
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.PortRemappingEntity
import com.pocketshell.core.storage.entity.SessionEntity
import com.pocketshell.core.storage.entity.SnippetEntity
import com.pocketshell.core.storage.entity.SshKeyEntity

/**
 * The PocketShell Room database.
 *
 * Version 1 was the initial schema: extracted entities from
 * `ssh-auto-forward-android` ([HostEntity], [SshKeyEntity],
 * [PortRemappingEntity]) plus the Phase 1+ stubs ([SessionEntity],
 * [SnippetEntity], [AgentSessionEntity]). PocketShell is a new app — the
 * version number resets to 1 here, no migration from the
 * `ssh-auto-forward-android` v2 schema is needed (different package, no
 * shared DB file).
 *
 * Version 2 (issue #49) added the host-bootstrap cache columns to
 * [HostEntity]:
 *
 * - `tmuxInstalled` (`Boolean?`) — last-known tmux presence on the host
 * - `lastBootstrapAt` (`Long?`) — epoch-millis of the last probe
 *
 * The migration SQL lives in
 * [com.pocketshell.core.storage.migrations.MIGRATION_1_2].
 *
 * `exportSchema = false` matches the reference module. When the schema
 * starts evolving in real users' hands, flip this on and check generated
 * schemas into `schemas/` so migrations are reviewable.
 */
@Database(
    entities = [
        HostEntity::class,
        SshKeyEntity::class,
        PortRemappingEntity::class,
        SessionEntity::class,
        SnippetEntity::class,
        AgentSessionEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao
    abstract fun sshKeyDao(): SshKeyDao
    abstract fun portRemappingDao(): PortRemappingDao
    abstract fun sessionDao(): SessionDao
    abstract fun snippetDao(): SnippetDao
    abstract fun agentSessionDao(): AgentSessionDao
}
