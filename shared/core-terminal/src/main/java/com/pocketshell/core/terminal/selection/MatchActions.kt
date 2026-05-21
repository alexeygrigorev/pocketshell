package com.pocketshell.core.terminal.selection

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Strategy interface for handling a tap on a [TerminalMatch]. Production code
 * uses [DefaultMatchActions] which wires Android system services (clipboard,
 * `Intent.ACTION_VIEW`, toast). Tests substitute a recording fake.
 *
 * Keeping the interface narrow (one method, no return value) is intentional:
 * each match kind has exactly one default action, and there is no useful
 * result for the caller to consume — feedback is the toast / launched
 * activity, not a return value.
 */
interface MatchActions {
    /** Perform the default action for [match]. */
    fun onMatchTapped(match: TerminalMatch)
}

/**
 * Default [MatchActions] implementation that wires the actions called out in
 * `docs/vision.md` §4:
 *
 * - [TerminalMatch.Path] → copy `value` to the clipboard, show a short toast
 *   `"Copied: <value>"` so the user has visual confirmation. Most terminal
 *   sessions are short-lived so the clipboard is the simplest hand-off.
 * - [TerminalMatch.Url] → fire `Intent.ACTION_VIEW` with the parsed URI. We
 *   set `FLAG_ACTIVITY_NEW_TASK` because this class may be called from a
 *   non-activity context (e.g. an `ApplicationContext` injected via Hilt).
 *   If no activity can handle the URL (the user has no browser installed),
 *   the action falls back to copying.
 * - [TerminalMatch.Error] → copy `value` to the clipboard with a toast. Same
 *   shape as [TerminalMatch.Path] — the user almost always wants to paste
 *   into a bug report or web search.
 *
 * Toast text is intentionally not localised in this layer; `feature-terminal`
 * (downstream) can substitute a custom [MatchActions] when localisation lands.
 */
class DefaultMatchActions(
    private val context: Context,
) : MatchActions {

    override fun onMatchTapped(match: TerminalMatch) {
        when (match) {
            is TerminalMatch.Path -> copyToClipboard(match.value, label = "path")
            is TerminalMatch.Url -> openUrl(match.value)
            is TerminalMatch.Error -> copyToClipboard(match.value, label = "error")
        }
    }

    /**
     * Copy [text] to the system clipboard and show a brief confirmation
     * toast. [label] is the clipboard's [ClipData.newPlainText] label — not
     * shown to the user on modern Android, but used by accessibility services.
     */
    private fun copyToClipboard(text: String, label: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        }
        Toast.makeText(context, "Copied: $text", Toast.LENGTH_SHORT).show()
    }

    /**
     * Launch an external viewer for [url]. Falls back to clipboard copy if no
     * activity can handle the intent — this can happen on stripped-down
     * Android images that ship without a browser. We deliberately catch the
     * resulting `ActivityNotFoundException` rather than pre-checking with
     * `resolveActivity` because the pre-check requires the
     * `QUERY_ALL_PACKAGES` permission on API 30+.
     */
    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            copyToClipboard(url, label = "url")
        }
    }
}
