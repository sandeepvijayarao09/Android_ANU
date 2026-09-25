package io.github.sandeepvijayarao09.anu.connector.gmail

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/**
 * Gmail access through Google Play services' `AuthorizationClient` (ADR-029). Google matches the
 * Android OAuth client by package name and signing certificate, so no client ID ships in the app. Play
 * services caches and refreshes tokens, and Anu stores none.
 */
class PlayServicesTokens(private val context: Context) : AccessTokens {

    override suspend fun token(scopes: Set<String>): String {
        val result = Identity.getAuthorizationClient(context).authorize(request(scopes)).await()
        if (result.hasResolution()) throw GmailNotConnectedException()
        return result.accessToken ?: throw GmailNotConnectedException()
    }

    /** Whether the user has already granted every Gmail scope. It never shows UI. */
    suspend fun isConnected(): Boolean = runCatching { token(GmailScopes.ALL) }.isSuccess

    /**
     * Starts connecting. Returns the consent screen to launch, or null when access is already granted.
     * Pass the screen's result to [finishConnect].
     */
    suspend fun beginConnect(): IntentSender? {
        val result = Identity.getAuthorizationClient(context).authorize(request(GmailScopes.ALL)).await()
        return if (result.hasResolution()) result.pendingIntent?.intentSender else null
    }

    fun finishConnect(data: Intent?): Boolean {
        val intent = data ?: return false
        return runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(intent).accessToken != null }.getOrDefault(false)
    }

    private fun request(scopes: Set<String>) = AuthorizationRequest.builder().setRequestedScopes(scopes.map(::Scope)).build()
}
