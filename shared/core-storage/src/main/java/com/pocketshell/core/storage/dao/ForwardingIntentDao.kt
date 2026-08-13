package com.pocketshell.core.storage.dao

import androidx.room.Dao
import androidx.room.Query

/**
 * Durable authority for the aggregate port-forwarding notification control.
 *
 * The notification says "Port forwarding running" and its Stop action tears
 * down every active forwarding host. Its persisted scope must therefore match:
 * clear every enabled host in one Room statement. Updating only
 * [com.pocketshell.core.storage.entity.HostEntity.enabled] preserves the host's
 * connection and presentation fields, unlike rewriting a stale entity
 * snapshot row by row.
 */
@Dao
interface ForwardingIntentDao {

    @Query("UPDATE hosts SET enabled = 0 WHERE enabled = 1")
    suspend fun disableAll(): Int
}
