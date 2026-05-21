package com.pocketshell.core.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stub: agent-detection state for a single tmux pane. Lets the conversation
 * tab survive app restarts and avoid re-running detection on every focus
 * change. Populated in a later Phase 2 issue once `core-agents` lands; see
 * docs/agent-awareness.md.
 *
 * - [paneRef] is the canonical "host:session:window:pane" identifier from
 *   `core-tmux`. Indexed (unique) because there's at most one agent per
 *   pane at a time — re-detection updates the row in place.
 * - [agent] is the detected agent kind (`"claude"`, `"codex"`,
 *   `"opencode"`). String for the same migration-friendliness reason as
 *   [SnippetEntity.kind].
 * - [jsonlPath] is the absolute path on the remote host to the live JSONL
 *   file (Claude Code / Codex). Null for OpenCode (which uses SQLite).
 * - [detectedAt] is when the heuristic last succeeded (epoch millis).
 *
 * No foreign key to [HostEntity] today: pane lifecycle is independent of
 * the host row (panes outlive host re-saves; host deletion should not
 * cascade into stale-but-not-yet-cleaned agent rows). A later issue can
 * add the cascade explicitly once the cleanup semantics are decided.
 */
@Entity(
    tableName = "agent_sessions",
    indices = [Index(value = ["paneRef"], unique = true)],
)
data class AgentSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val paneRef: String,
    val agent: String,
    val jsonlPath: String? = null,
    val detectedAt: Long = 0L,
)
