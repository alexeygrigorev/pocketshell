package com.pocketshell.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pocketshell.core.storage.entity.PortUsageEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [PortUsageEntity] (issue #203, port from
 * `ssh-auto-forward-android`).
 *
 * The verbs intentionally split into "create-if-missing" and "increment"
 * pairs rather than a single upsert. SQLite's `INSERT OR REPLACE` with a
 * composite primary key would overwrite the existing row's counters with
 * the new row's values — exactly the wrong behaviour for a running
 * total. The two-step pattern (`insertIfMissing` then `incrementClick` /
 * `addBytes`) guarantees we never lose data even under concurrent
 * writes: the second step is an atomic `UPDATE ... SET col = col + N` so
 * multiple in-flight tunnels for the same port accumulate correctly.
 *
 * `getByHostId` returns a Flow because the panel renders bytes-in /
 * bytes-out live as forwards push data, so a reactive Room query is
 * cleaner than a periodic poll.
 */
@Dao
interface PortUsageDao {
    @Query("SELECT * FROM port_usage WHERE hostId = :hostId")
    fun getByHostId(hostId: Long): Flow<List<PortUsageEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(usage: PortUsageEntity)

    @Query(
        "UPDATE port_usage SET clickCount = clickCount + 1, lastUsedAt = :now " +
            "WHERE hostId = :hostId AND remotePort = :remotePort"
    )
    suspend fun incrementClick(hostId: Long, remotePort: Int, now: Long)

    @Query(
        "UPDATE port_usage SET totalBytes = totalBytes + :bytes, lastUsedAt = :now " +
            "WHERE hostId = :hostId AND remotePort = :remotePort"
    )
    suspend fun addBytes(hostId: Long, remotePort: Int, bytes: Long, now: Long)
}
