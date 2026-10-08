package com.dragosfotache.gymtracka.data.sync.identity

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse

/**
 * SDK launch seam for foreground Google identity calls.
 *
 * Production delegates to the real [CredentialManager]; tests inject a fake
 * that records the actual SDK request and returns actual SDK credential
 * objects. No Google SDK type escapes this seam except through the
 * allowlisted request/response types below.
 */
internal interface GoogleCredentialClient {
    suspend fun getCredential(
        activity: Activity,
        request: GetCredentialRequest,
    ): GetCredentialResponse

    suspend fun clearCredentialState(request: ClearCredentialStateRequest)
}

/**
 * Production [GoogleCredentialClient] backed by the real [CredentialManager].
 *
 * The manager is created from the application context, while each get call
 * uses the supplied foreground [Activity] so provider UI launches on the
 * current activity stack. Merely constructing this client contacts nothing
 * and launches no UI.
 */
internal class CredentialManagerGoogleCredentialClient(
    context: Context,
) : GoogleCredentialClient {
    private val applicationContext: Context = context.applicationContext

    override suspend fun getCredential(
        activity: Activity,
        request: GetCredentialRequest,
    ): GetCredentialResponse {
        val manager = CredentialManager.create(applicationContext)
        return manager.getCredential(activity, request)
    }

    override suspend fun clearCredentialState(request: ClearCredentialStateRequest) {
        val manager = CredentialManager.create(applicationContext)
        manager.clearCredentialState(request)
    }
}
