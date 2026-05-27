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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.pocketshell.app.share.ShareUploader.Companion.extensionForMimeType
import com.pocketshell.app.share.ShareUploader.Companion.queryUriDisplayName
import com.pocketshell.app.settings.SettingsRepository
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellThemeMode
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * System share-target entry point (issue #138). Receives an
 * `ACTION_SEND` / `ACTION_SEND_MULTIPLE` intent from the Android share
 * sheet, decodes the payload into a [ShareableItem], and surfaces the
 * host-picker UI so the user can route the file to a configured host.
 *
 * Distinct from [com.pocketshell.app.MainActivity]: the share intent
 * launches into PocketShell as a one-shot transactional surface — the
 * user picks a host, the upload runs in the ViewModel, the activity
 * finishes itself when the result toast resolves. The host list +
 * session navigation continue to live in MainActivity for the normal
 * launcher flow.
 */
@AndroidEntryPoint
class ShareActivity : FragmentActivity() {

    private val viewModel: ShareViewModel by viewModels()

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val staged = decodeShareIntent(intent)
        if (staged == null) {
            Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        viewModel.setItem(staged)
        setContent {
            val settings by settingsRepository.settings.collectAsState()
            PocketShellTheme(mode = settings.theme.toThemeMode()) {
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

        // Bridge upload state -> Toast so users see a quick
        // acknowledgement on the source app's surface in case they
        // dismiss the result screen before tapping Done. Observe the
        // flow imperatively on the activity's lifecycle scope so the
        // toast is independent of Compose recomposition.
        watchUploadStateForToasts()
    }

    private fun watchUploadStateForToasts() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uploadState.collect { state ->
                    when (state) {
                        is UploadState.Success ->
                            Toast.makeText(
                                this@ShareActivity,
                                "Uploaded to ${state.hostName}: ${state.remotePath}",
                                Toast.LENGTH_LONG,
                            ).show()
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
     * Convert an inbound Android share `Intent` into our internal
     * [ShareableItem] union. Returns null when the intent doesn't
     * carry anything we can route.
     *
     * Supported shapes:
     *
     * - `ACTION_SEND` + `text/plain` + `EXTRA_TEXT` -> [ShareableItem.TextItem]
     * - `ACTION_SEND` + `EXTRA_STREAM` -> [ShareableItem.UriItem]
     *   (the common share-image / share-audio / share-file shape)
     * - `ACTION_SEND_MULTIPLE` + `EXTRA_STREAM` (parcelable list) ->
     *   takes the **first** URI from the list and treats it as a
     *   single-file upload. The first-cut spec defers multi-file
     *   upload as a non-goal.
     */
    internal fun decodeShareIntent(intent: Intent?): ShareableItem? {
        if (intent == null) return null
        val mime = intent.type
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val stream = extractStream(intent)
                if (stream != null) {
                    buildUriItem(stream, intent, mime)
                } else {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                    if (!text.isNullOrEmpty()) {
                        ShareableItem.TextItem(
                            text = text,
                            displayName = subject?.takeIf { it.isNotBlank() } ?: "shared-text",
                        )
                    } else {
                        null
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
                list?.firstOrNull()?.let { buildUriItem(it, intent, mime) }
            }
            else -> null
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

    private fun buildUriItem(uri: Uri, intent: Intent, mime: String?): ShareableItem.UriItem {
        val resolverName = queryUriDisplayName(contentResolver, uri)
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
}

private fun com.pocketshell.app.settings.ThemePreference.toThemeMode(): PocketShellThemeMode =
    when (this) {
        com.pocketshell.app.settings.ThemePreference.System -> PocketShellThemeMode.System
        com.pocketshell.app.settings.ThemePreference.Light -> PocketShellThemeMode.Light
        com.pocketshell.app.settings.ThemePreference.Dark -> PocketShellThemeMode.Dark
    }
