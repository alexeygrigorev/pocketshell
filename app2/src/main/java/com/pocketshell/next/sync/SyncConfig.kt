package com.pocketshell.next.sync

/**
 * Constants for the optional Google-login settings sync (issue #2633).
 *
 * The Android half of the feature documented in `pocketshell-electron`'s
 * `docs/SYNC.md` and specified by the backend's
 * `aws-infra/sandbox/pocketshell-sync/docs/CLIENT-INTEGRATION.md`. This file
 * mirrors the desktop app's `src/shared/syncConfig.ts`: same API URL, same
 * slot, same payload shape — so a phone and a laptop signed into the same
 * Google account see the same blob.
 *
 * ## The one value that is NOT the desktop app's
 *
 * [GOOGLE_ANDROID_CLIENT_ID]. The desktop credential is a Google "Desktop app"
 * OAuth client, which drives a loopback listener on `127.0.0.1` and needs a
 * client SECRET at the token endpoint. Neither works here:
 *
 * - A mobile app cannot run a loopback redirect the way an Electron main
 *   process can, and Google rejects `http://127.0.0.1` redirects for Android
 *   clients.
 * - A client secret embedded in an APK is not a secret; it ships to every
 *   device and falls out of a decompile. Android OAuth clients are PUBLIC
 *   clients: with PKCE they take no secret at all, which is why nothing in
 *   this package ever sends `client_secret`.
 *
 * So the Android side needs its OWN OAuth client, of type "Android", in the
 * SAME Google Cloud project as the desktop client (so the server-side email
 * allowlist and the deployed API keep working unchanged). Registering it is a
 * Google Cloud Console action on the maintainer's account — it cannot be done
 * from code, which is why the value below is a placeholder and
 * [isGoogleClientConfigured] exists.
 *
 * Registration inputs (already gathered, see issue #2633):
 * - package name `com.pocketshell.app`
 * - debug signing SHA-1 `A0:4C:74:33:93:AD:23:1C:54:9E:CB:81:E7:43:FA:D7:D9:63:C4:17`
 *
 * ## Filling it in
 *
 * Replace [GOOGLE_ANDROID_CLIENT_ID] with the issued client ID **and** update
 * the `android:scheme` of the redirect `<intent-filter>` in
 * `app2/src/main/AndroidManifest.xml` to the matching reversed form. Those two
 * must agree; `SyncConfigTest` fails if they drift, because a mismatch is
 * silent at build time and shows up only as a sign-in that never returns.
 */
object SyncConfig {

    /**
     * The Google OAuth **Android** client ID for PocketShell.
     *
     * Placeholder until the maintainer registers the client (issue #2633).
     * The value is deliberately shaped like a real client ID so the reversed
     * redirect scheme derived from it is a legal URI scheme and the whole flow
     * can be built and tested end-to-end against it — everything except the
     * two live calls to Google, which need the real registration.
     */
    const val GOOGLE_ANDROID_CLIENT_ID: String =
        "unconfigured-android-client-id.apps.googleusercontent.com"

    /** The placeholder [GOOGLE_ANDROID_CLIENT_ID] ships with. */
    const val UNCONFIGURED_CLIENT_ID_MARKER: String = "unconfigured-android-client-id"

    /**
     * Base URL of the sync API (`$default` stage) — the `ApiUrl` output of the
     * `pocketshell-sync` CloudFormation stack. Identical to the desktop app's
     * `SYNC_API_URL`; that is what makes one account work from both.
     */
    const val SYNC_API_URL: String = "https://a7sota2qic.execute-api.eu-west-1.amazonaws.com"

    /** The one settings blob the app syncs. Same slot the desktop app writes. */
    const val SYNC_SLOT: String = "main"

    /** Google's authorization endpoint (the URL the Custom Tab opens). */
    const val AUTH_ENDPOINT: String = "https://accounts.google.com/o/oauth2/v2/auth"

    /** Google's token endpoint (code exchange and refresh). */
    const val TOKEN_ENDPOINT: String = "https://oauth2.googleapis.com/token"

    /** Google's revocation endpoint — best-effort on sign-out. */
    const val REVOKE_ENDPOINT: String = "https://oauth2.googleapis.com/revoke"

    /** Same scopes as the desktop app: identity only, no Google API access. */
    const val SCOPE: String = "openid email profile"

    /** Path component of the redirect URI, after the reversed-client-ID scheme. */
    const val REDIRECT_PATH: String = "/oauth2redirect"

    /** True once [GOOGLE_ANDROID_CLIENT_ID] has been replaced with a real one. */
    val isGoogleClientConfigured: Boolean
        get() = !GOOGLE_ANDROID_CLIENT_ID.contains(UNCONFIGURED_CLIENT_ID_MARKER)

    /**
     * The redirect scheme Google requires for an Android OAuth client: the
     * client ID with its dot-separated labels reversed. `abc.apps.google
     * usercontent.com` becomes `com.googleusercontent.apps.abc`.
     *
     * Kept as a derivation rather than a second literal so the manifest and
     * the client ID have exactly one source of truth between them (the
     * manifest's copy is checked against this in `SyncConfigTest`).
     */
    fun redirectScheme(clientId: String = GOOGLE_ANDROID_CLIENT_ID): String =
        clientId.split('.').asReversed().joinToString(".")

    /** The full redirect URI sent as `redirect_uri` and captured by the manifest. */
    fun redirectUri(clientId: String = GOOGLE_ANDROID_CLIENT_ID): String =
        "${redirectScheme(clientId)}:$REDIRECT_PATH"
}
