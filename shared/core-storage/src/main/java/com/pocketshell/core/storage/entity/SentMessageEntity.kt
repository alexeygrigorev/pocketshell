package com.pocketshell.core.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One message that left the composer, kept so the user never has to retype it
 * (rewrite task P-1, maintainer request 2026-09-03).
 *
 * ## This is a LOG, not a queue
 *
 * The row is written when the composer dispatches a message, and nothing ever
 * reads it back to re-send it automatically. There is no state column, no
 * attempt counter, no "pending" flag driving a drain loop — the deleted
 * outbound queue had all four and that is precisely why it is gone. The only
 * consumer is a list the user scrolls to tap an entry back into the draft, the
 * same relationship a shell has with its history file.
 *
 * [delivered] records what happened at dispatch time and is presentation only:
 * a message the session was offline for reads "not delivered" in the list, so
 * the user can tell at a glance which one to re-send by hand. Nothing acts on
 * it.
 *
 * ## Keying
 *
 * [sessionKey] is `"<hostId>/<sessionName>"` — the same composite the composer's
 * draft store uses. Deliberately NOT a foreign key on `hosts`: a session name is
 * a server-side identity the client does not own, and a history row outliving a
 * deleted host row is harmless (it is unreachable text, not state anything
 * depends on). Making it a cascade would also mean the history disappears when
 * a host is edited hard enough to be recreated, which is the opposite of the
 * point.
 *
 * [sentAtMs] is epoch millis at dispatch; the list is newest-first and the store
 * trims each session's log to a bounded number of rows by this column.
 */
@Entity(
    tableName = "sent_messages",
    indices = [Index(value = ["sessionKey", "sentAtMs"])],
)
data class SentMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionKey: String,
    /** The exact text handed to the session, minus the trailing carriage return. */
    val body: String,
    val sentAtMs: Long,
    /** True when the session was attached at dispatch. Presentation only. */
    val delivered: Boolean,
)
