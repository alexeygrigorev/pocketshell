package com.pocketshell.next.sync

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The bridge between the settings screen, the browser, and the redirect
 * activity (issue #2633).
 *
 * A process-scoped singleton because the three of them do not share a
 * lifetime: the screen starts the sign-in, the Custom Tab takes over, and the
 * redirect arrives at a DIFFERENT activity — possibly after the settings
 * screen's ViewModel was destroyed and rebuilt. Anything scoped narrower than
 * the process would lose the PKCE verifier in between, which is exactly the
 * failure mode "sign-in silently does nothing" is made of.
 *
 * Nothing that passes through here is a token. [state] carries a phase and, on
 * failure, a message; the tokens go straight from [GoogleAuth] to the
 * Keystore-backed store.
 */
class SyncSignInCoordinator(
    private val auth: GoogleAuth,
    private val launcher: AuthorizationLauncher,
    private val scope: CoroutineScope,
) {

    sealed interface State {
        data object Idle : State
        /** The Custom Tab is open; we are waiting for the redirect. */
        data object AwaitingRedirect : State
        /** The code came back and is being exchanged for tokens. */
        data object Exchanging : State
        data class Failed(val message: String) : State
        data class SignedIn(val email: String?) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Open the browser at Google's authorization endpoint. */
    fun startSignIn(context: Context) {
        try {
            val pending = auth.beginAuthorization()
            _state.value = State.AwaitingRedirect
            launcher.open(context, pending.authorizationUrl)
        } catch (e: SyncAuthError) {
            _state.value = State.Failed(e.message ?: "sign-in could not be started")
        }
    }

    /** Called by [SyncOAuthRedirectActivity] with the captured redirect URI. */
    fun onRedirect(callbackUri: String) {
        _state.value = State.Exchanging
        scope.launch {
            _state.value = try {
                State.SignedIn(auth.completeAuthorization(callbackUri).email)
            } catch (e: Exception) {
                when (e) {
                    is SyncAuthError, is NotSignedInError ->
                        State.Failed(e.message ?: "sign-in failed")
                    else -> throw e
                }
            }
        }
    }

    /** Drop a finished/failed sign-in so the screen stops showing its banner. */
    fun acknowledge() {
        if (_state.value is State.Failed || _state.value is State.SignedIn) {
            _state.value = State.Idle
        }
    }
}
