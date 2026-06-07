package com.pocketshell.app.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.pocketshell.app.MainActivity
import com.pocketshell.app.share.ShareUploader.Companion.extensionForMimeType
import com.pocketshell.app.share.ShareUploader.Companion.queryUriDisplayName
import com.pocketshell.uikit.theme.PocketShellTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * System share-target entry point (issue #138). Receives an
 * `ACTION_SEND` / `ACTION_SEND_MULTIPLE` intent from the Android share
 * sheet, decodes the payload into a [ShareableItem], and surfaces the
 * host-picker UI so the user can route the file to a configured host.
 *
 * Distinct from [com.pocketshell.app.MainActivity]: the share intent
 * launches into PocketShell as a one-shot transactional surface — the
 * user picks a host, the upload runs in the ViewModel, the activity
 * finishes itself when the user dismisses the result. The host list +
 * session navigation continue to live in MainActivity for the normal
 * launcher flow.
 */
@AndroidEntryPoint
class ShareActivity : FragmentActivity() {

    private val viewModel: ShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val staged = decodeShareIntent(intent)
        if (staged.isEmpty()) {
            Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        viewModel.setItems(staged)
        setContent {
            PocketShellTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    HostPickerScreen(
                        viewModel = viewModel,
                        onUploadComplete = { finish() },
                        onCancel = { finish() },
                    )
                }
            }
        }

        // Keep failure feedback independent of Compose recomposition.
        // Successful file uploads stay quiet and finish the one-shot
        // share surface; the share action was user-initiated and does
        // not need an extra Android toast.
        watchUploadStateForFailures()

        // Issue #560: when the user picked an active SESSION as the
        // destination, the ViewModel stages the file into that session's
        // attachment scope and emits a one-shot launch event. Hand off to
        // MainActivity into that tmux session with the staged path
        // pre-loaded as a composer chip, then finish this one-shot surface.
        watchSessionLaunch()
    }

    private fun watchSessionLaunch() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sessionLaunch.collect { launch ->
                    startActivity(buildSessionLaunchIntent(launch))
                    finish()
                }
            }
        }
    }

    private fun buildSessionLaunchIntent(launch: SessionLaunch): Intent =
        Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_OPEN_SESSION_HOST_ID, launch.hostId)
            putExtra(MainActivity.EXTRA_OPEN_SESSION_HOST_NAME, launch.hostName)
            putExtra(MainActivity.EXTRA_OPEN_SESSION_HOSTNAME, launch.hostname)
            putExtra(MainActivity.EXTRA_OPEN_SESSION_PORT, launch.port)
            putExtra(MainActivity.EXTRA_OPEN_SESSION_USERNAME, launch.username)
            putExtra(MainActivity.EXTRA_OPEN_SESSION_KEY_PATH, launch.keyPath)
            putExtra(MainActivity.EXTRA_OPEN_SESSION_NAME, launch.sessionName)
            putExtra(
                MainActivity.EXTRA_OPEN_SESSION_ATTACHMENTS,
                launch.attachmentPaths.toTypedArray(),
            )
        }

    private fun watchUploadStateForFailures() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uploadState.collect { state ->
                    when (state) {
                        is UploadState.Failed ->
                            Toast.makeText(
                                this@ShareActivity,
                                state.message,
                                Toast.LENGTH_LONG,
                            ).show()
                        else -> Unit
                    }
                }
            }
        }
    }

    /**
     * Convert the inbound share `Intent` into the staged item list,
     * resolving display names against this activity's
     * [ContentResolver]. Thin wrapper over the pure
     * [decodeShareIntent] free function so the parsing logic is unit
     * testable without instantiating a Hilt activity.
     */
    internal fun decodeShareIntent(intent: Intent?): List<ShareableItem> =
        decodeShareIntent(intent, contentResolver)
}

/**
 * Convert an inbound Android share `Intent` into a list of internal
 * [ShareableItem]s. Returns an empty list when the intent doesn't carry
 * anything we can route.
 *
 * Pure function (no `Activity` state) — display names are resolved via
 * the supplied [resolver] so this can be exercised in a Robolectric
 * unit test without a live activity. The [ShareActivity] member of the
 * same name simply forwards its `contentResolver`.
 *
 * Supported shapes:
 *
 * - `ACTION_SEND` + `text/plain` + `EXTRA_TEXT` -> single
 *   [ShareableItem.TextItem]
 * - `ACTION_SEND` + `EXTRA_STREAM` -> single [ShareableItem.UriItem]
 *   (the common share-image / share-audio / share-file shape)
 * - `ACTION_SEND_MULTIPLE` + `EXTRA_STREAM` (parcelable list) -> one
 *   [ShareableItem.UriItem] per URI in the list. Issue #258: previously
 *   this took only the first URI and silently dropped the rest —
 *   selecting N screenshots uploaded just one. Now every selected file
 *   is staged and uploaded.
 */
internal fun decodeShareIntent(
    intent: Intent?,
    resolver: android.content.ContentResolver,
): List<ShareableItem> {
    if (intent == null) return emptyList()
    val mime = intent.type
    return when (intent.action) {
        Intent.ACTION_SEND -> {
            val stream = extractStream(intent)
            if (stream != null) {
                listOf(buildUriItem(stream, intent, mime, resolver))
            } else {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                if (!text.isNullOrEmpty()) {
                    listOf(
                        ShareableItem.TextItem(
                            text = text,
                            displayName = subject?.takeIf { it.isNotBlank() } ?: "shared-text",
                        ),
                    )
                } else {
                    emptyList()
                }
            }
        }
        Intent.ACTION_SEND_MULTIPLE -> {
            @Suppress("DEPRECATION")
            val list: ArrayList<Uri>? =
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(
                        Intent.EXTRA_STREAM,
                        Uri::class.java,
                    )
                } else {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
            list.orEmpty().map { buildUriItem(it, intent, mime, resolver) }
        }
        else -> emptyList()
    }
}

private fun extractStream(intent: Intent): Uri? {
    @Suppress("DEPRECATION")
    return if (android.os.Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }
}

private fun buildUriItem(
    uri: Uri,
    intent: Intent,
    mime: String?,
    resolver: android.content.ContentResolver,
): ShareableItem.UriItem {
    val resolverName = queryUriDisplayName(resolver, uri)
    val intentName = intent.getStringExtra(Intent.EXTRA_TITLE)
    val displayName = resolverName ?: intentName ?: uri.lastPathSegment
    val fallback = extensionForMimeType(mime)
    return ShareableItem.UriItem(
        uri = uri,
        displayName = displayName,
        size = null,
        mimeType = mime,
        fallbackExtension = fallback,
    )
}
