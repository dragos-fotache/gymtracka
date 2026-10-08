package com.dragosfotache.gymtracka.data.sync.identity

import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.ClearCredentialProviderConfigurationException
import androidx.credentials.exceptions.ClearCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.dragosfotache.gymtracka.data.sync.api.GoogleIdentityException
import com.dragosfotache.gymtracka.data.sync.api.GoogleIdentityFailureReason
import com.dragosfotache.gymtracka.data.sync.api.GoogleIdentityProvider
import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException

private const val RETIRED_MESSAGE = "Google sign-in is no longer available"
private const val CLOSE_MESSAGE = "Google sign-in was closed"
private const val TIMEOUT_MESSAGE = "Google sign-in timed out"
private const val FENCED_MESSAGE = "Google sign-in cannot complete"

/**
 * Foreground Android [GoogleIdentityProvider] built on Credential Manager's
 * explicit Google button request.
 *
 * Each [credentialFor] call builds exactly one [GetCredentialRequest]
 * containing exactly one [GetSignInWithGoogleOption] with the configured
 * web/server audience and the exact supplied service nonce, launches it on
 * the current [ComponentActivity], and returns only the opaque ID token for
 * later server verification. No token decoding, account inference, service
 * exchange, HTTP, persistence writes, sign-out workflow or automatic cleanup
 * happens here. Merely constructing this adapter contacts nothing.
 *
 * Lifetime: one adapter per Activity. Construction registers a lifecycle
 * observer and therefore must happen on the main thread; [close] is safe
 * from any thread and moves observer removal onto the main thread when
 * needed. [close] permanently retires the instance, cancels adapter-owned
 * in-flight work, removes the lifecycle observer and releases the Activity
 * reference. `ON_DESTROY` (including
 * recreation) retires automatically. Constructing with an already-destroyed
 * Activity retires immediately without registering an observer. A new
 * Activity needs a new adapter.
 *
 * Concurrency: one shared nonqueueing admission slot per adapter; a second
 * concurrent get/clear fails with [GoogleIdentityFailureReason.BUSY].
 */
internal class AndroidGoogleIdentityProvider(
    activity: ComponentActivity,
    private val serverClientId: String?,
    private val client: GoogleCredentialClient =
        CredentialManagerGoogleCredentialClient(activity.applicationContext),
    private val requestTimeoutMillis: Long = 120_000L,
) : GoogleIdentityProvider, DefaultLifecycleObserver {

    private val lock = Any()

    // Guarded by lock; synchronous fencing only, never held across suspends.
    private var retired = false
    private var occupied = false
    private var currentWork: Job? = null
    private var host: ComponentActivity? = activity
    private var observerAttached = false

    // Adapter-owned work runs here so close() can cancel in-flight SDK calls
    // without cancelling the caller's scope.
    private val supervisor = SupervisorJob()
    private val adapterScope = CoroutineScope(supervisor + Dispatchers.Main.immediate)

    init {
        require(requestTimeoutMillis > 0) { "requestTimeoutMillis must be positive" }
        // Main-thread registration: LifecycleRegistry enforces the main
        // thread here, so construction must happen on Main.
        if (activity.isDestroyed || activity.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            // Already destroyed: LifecycleRegistry will not dispatch
            // ON_DESTROY to a late observer, so retire immediately without
            // retaining observer/host references.
            synchronized(lock) {
                retired = true
                host = null
            }
        } else {
            activity.lifecycle.addObserver(this)
            synchronized(lock) { observerAttached = true }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        close()
    }

    /**
     * Idempotently retires this adapter and cancels adapter-owned in-flight
     * work. Safe from any thread and against concurrent completion; a retired
     * call never publishes a result, and retirement during a call surfaces as
     * safe cancellation. Lifecycle observer removal always runs on the real
     * main thread (posted there when close is called elsewhere) and is never
     * silently swallowed: a genuine removal failure propagates instead of
     * leaving the observer attached.
     */
    fun close() {
        val work: Job?
        val observed: ComponentActivity?
        val needsRemoval: Boolean
        synchronized(lock) {
            if (retired && !observerAttached) return
            retired = true
            work = currentWork
            currentWork = null
            observed = host
            host = null
            needsRemoval = observerAttached
        }
        work?.cancel(CancellationException(CLOSE_MESSAGE))
        supervisor.cancel(CancellationException(CLOSE_MESSAGE))
        if (needsRemoval && observed != null) {
            removeObserverOnMain(observed)
        }
    }

    override suspend fun credentialFor(nonce: String): OpaqueToken {
        // Already-cancelled callers must not acquire the slot or observe
        // BUSY/RETIRED: fail fast with the caller's own cancellation.
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            if (retired) throw GoogleIdentityException(GoogleIdentityFailureReason.RETIRED)
            if (occupied) throw GoogleIdentityException(GoogleIdentityFailureReason.BUSY)
            occupied = true
        }
        try {
            // Exact pass-through: null/blank or whitespace-padded audience is
            // rejected, never repaired; blank nonce is rejected, otherwise it
            // is preserved exactly, including any whitespace.
            val audience = serverClientId
            if (audience == null || audience.isBlank() || audience.trim() != audience) {
                throw GoogleIdentityException(GoogleIdentityFailureReason.UNCONFIGURED)
            }
            if (nonce.isBlank()) {
                throw GoogleIdentityException(GoogleIdentityFailureReason.INVALID_CREDENTIAL)
            }
            val result = withContext(Dispatchers.Main.immediate) {
                executeGet(audience, nonce)
            }
            // Final acceptance linearization with close(): a result computed
            // before a racing close must never be published after retirement.
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (retired) throw CancellationException(RETIRED_MESSAGE)
            }
            return result
        } catch (e: GoogleIdentityException) {
            // Caller cancellation takes precedence over a racing failure; a
            // classified failure that lost a racing retirement becomes safe
            // cancellation after the dispatcher handoff. (Admission RETIRED/
            // BUSY is thrown before the try block and is unaffected.)
            currentCoroutineContext().ensureActive()
            if (isRetired()) throw CancellationException(RETIRED_MESSAGE)
            throw e
        } finally {
            synchronized(lock) {
                occupied = false
                currentWork = null
            }
        }
    }

    override suspend fun clearCredentialState() {
        // Already-cancelled callers must not acquire the slot or observe
        // BUSY/RETIRED: fail fast with the caller's own cancellation.
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            if (retired) throw GoogleIdentityException(GoogleIdentityFailureReason.RETIRED)
            if (occupied) throw GoogleIdentityException(GoogleIdentityFailureReason.BUSY)
            occupied = true
        }
        try {
            withContext(Dispatchers.Main.immediate) {
                executeClear()
            }
            // Final acceptance linearization with close(): clear must not
            // report success after a racing retirement.
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (retired) throw CancellationException(RETIRED_MESSAGE)
            }
        } catch (e: GoogleIdentityException) {
            // Caller cancellation takes precedence over a racing failure; a
            // classified failure that lost a racing retirement becomes safe
            // cancellation after the dispatcher handoff.
            currentCoroutineContext().ensureActive()
            if (isRetired()) throw CancellationException(RETIRED_MESSAGE)
            throw e
        } finally {
            synchronized(lock) {
                occupied = false
                currentWork = null
            }
        }
    }

    // Display must not reveal configuration inputs.
    override fun toString(): String {
        val isRetired = synchronized(lock) { retired }
        return "AndroidGoogleIdentityProvider(retired=$isRetired)"
    }

    private fun isRetired(): Boolean = synchronized(lock) { retired }

    private fun currentHost(): ComponentActivity? = synchronized(lock) { host }

    private fun removeObserverOnMain(observed: ComponentActivity) {
        if (isMainThread()) {
            observed.lifecycle.removeObserver(this)
            synchronized(lock) { observerAttached = false }
        } else {
            val mainLooper = Looper.getMainLooper()
            if (mainLooper != null) {
                Handler(mainLooper).post {
                    observed.lifecycle.removeObserver(this)
                    synchronized(lock) { observerAttached = false }
                }
            } else {
                // No main looper in this process (never the case on device):
                // attempt direct removal so a failure surfaces instead of a
                // silently retained observer.
                observed.lifecycle.removeObserver(this)
                synchronized(lock) { observerAttached = false }
            }
        }
    }

    private fun isMainThread(): Boolean {
        val mainLooper = Looper.getMainLooper()
        return mainLooper != null && Looper.myLooper() === mainLooper
    }

    private fun isForeground(host: ComponentActivity): Boolean =
        host.lifecycle.currentState == Lifecycle.State.RESUMED &&
            !host.isFinishing &&
            !host.isDestroyed

    private suspend fun executeGet(audience: String, nonce: String): OpaqueToken {
        if (isRetired()) throw CancellationException(RETIRED_MESSAGE)
        currentCoroutineContext().ensureActive()
        var snapshot = currentHost()
        if (snapshot == null || isRetired()) throw CancellationException(RETIRED_MESSAGE)
        if (!isForeground(snapshot)) {
            throw GoogleIdentityException(GoogleIdentityFailureReason.NOT_FOREGROUND)
        }

        val option = try {
            GetSignInWithGoogleOption.Builder(audience).setNonce(nonce).build()
        } catch (_: IllegalArgumentException) {
            throw GoogleIdentityException(GoogleIdentityFailureReason.PROVIDER_FAILURE)
        }
        val request = try {
            GetCredentialRequest.Builder().addCredentialOption(option).build()
        } catch (_: IllegalArgumentException) {
            throw GoogleIdentityException(GoogleIdentityFailureReason.PROVIDER_FAILURE)
        }

        // Recheck immediately before launching: admission fencing, not a
        // demand that the Activity stay resumed while provider UI is up.
        if (isRetired()) throw CancellationException(RETIRED_MESSAGE)
        currentCoroutineContext().ensureActive()
        snapshot = currentHost()
        if (snapshot == null || isRetired()) throw CancellationException(RETIRED_MESSAGE)
        if (!isForeground(snapshot)) {
            throw GoogleIdentityException(GoogleIdentityFailureReason.NOT_FOREGROUND)
        }
        val launchHost = snapshot

        val attempt = adapterScope.async {
            client.getCredential(launchHost, request)
        }
        synchronized(lock) { currentWork = attempt }
        try {
            val response = try {
                withTimeout(requestTimeoutMillis) { attempt.await() }
            } catch (e: TimeoutCancellationException) {
                attempt.cancel(CancellationException(TIMEOUT_MESSAGE))
                // A parent timeout or external cancellation takes precedence
                // over this adapter-owned timeout.
                currentCoroutineContext().ensureActive()
                if (isRetired()) throw CancellationException(RETIRED_MESSAGE)
                throw GoogleIdentityException(GoogleIdentityFailureReason.PROVIDER_FAILURE)
            } catch (e: CancellationException) {
                attempt.cancel(CancellationException(CLOSE_MESSAGE))
                throw e
            }
            // Result fencing: disposal/destroy/finishing/caller liveness. The
            // Activity may pause/stop while provider UI is up, so RESUMED is
            // not re-required here. Cancellation-resistant SDK work that
            // returns late must never publish its token.
            currentCoroutineContext().ensureActive()
            if (isRetired()) throw CancellationException(RETIRED_MESSAGE)
            val live = currentHost()
            if (live == null || isRetired()) throw CancellationException(RETIRED_MESSAGE)
            if (live.isDestroyed || live.isFinishing) throw CancellationException(FENCED_MESSAGE)
            return parseToken(response)
        } catch (e: GetCredentialCancellationException) {
            // User cancellation only when the caller is still live; caller
            // coroutine cancellation propagates instead.
            currentCoroutineContext().ensureActive()
            if (isRetired()) throw CancellationException(RETIRED_MESSAGE)
            throw GoogleIdentityException(GoogleIdentityFailureReason.USER_CANCELLED)
        } catch (e: NoCredentialException) {
            throw classifiedGetFailure(GoogleIdentityFailureReason.UNAVAILABLE)
        } catch (e: GetCredentialUnsupportedException) {
            throw classifiedGetFailure(GoogleIdentityFailureReason.UNAVAILABLE)
        } catch (e: GetCredentialProviderConfigurationException) {
            throw classifiedGetFailure(GoogleIdentityFailureReason.UNAVAILABLE)
        } catch (e: GetCredentialException) {
            throw classifiedGetFailure(GoogleIdentityFailureReason.PROVIDER_FAILURE)
        } catch (e: GoogleIdentityException) {
            // Already classified (e.g. INVALID_CREDENTIAL from response
            // parsing): preserve it, with caller/retirement precedence over
            // the racing fault.
            currentCoroutineContext().ensureActive()
            if (isRetired()) throw CancellationException(RETIRED_MESSAGE)
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw classifiedGetFailure(GoogleIdentityFailureReason.PROVIDER_FAILURE)
        } finally {
            synchronized(lock) {
                if (currentWork === attempt) currentWork = null
            }
            attempt.cancel()
        }
    }

    private suspend fun classifiedGetFailure(
        reason: GoogleIdentityFailureReason,
    ): Nothing {
        currentCoroutineContext().ensureActive()
        if (isRetired()) throw CancellationException(RETIRED_MESSAGE)
        throw GoogleIdentityException(reason)
    }

    private suspend fun parseToken(response: GetCredentialResponse): OpaqueToken {
        currentCoroutineContext().ensureActive()
        if (isRetired()) throw CancellationException(RETIRED_MESSAGE)
        val credential = response.credential
        if (credential !is CustomCredential) {
            throw GoogleIdentityException(GoogleIdentityFailureReason.INVALID_CREDENTIAL)
        }
        if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw GoogleIdentityException(GoogleIdentityFailureReason.INVALID_CREDENTIAL)
        }
        val parsed = try {
            GoogleIdTokenCredential.createFrom(credential.data)
        } catch (_: GoogleIdTokenParsingException) {
            throw GoogleIdentityException(GoogleIdentityFailureReason.INVALID_CREDENTIAL)
        } catch (_: IllegalArgumentException) {
            throw GoogleIdentityException(GoogleIdentityFailureReason.INVALID_CREDENTIAL)
        } catch (_: NullPointerException) {
            throw GoogleIdentityException(GoogleIdentityFailureReason.INVALID_CREDENTIAL)
        }
        val token = parsed.idToken
        if (token.isBlank()) {
            throw GoogleIdentityException(GoogleIdentityFailureReason.INVALID_CREDENTIAL)
        }
        // Final fences before accepting: a late token from
        // cancellation-resistant work must never be published.
        currentCoroutineContext().ensureActive()
        if (isRetired()) throw CancellationException(RETIRED_MESSAGE)
        return OpaqueToken(token)
    }

    private suspend fun executeClear() {
        // Explicit primitive only: needs a live, not-destroyed adapter, but
        // not necessarily a resumed Activity, and no configured audience.
        if (isRetired()) throw CancellationException(RETIRED_MESSAGE)
        currentCoroutineContext().ensureActive()
        val snapshot = currentHost()
        if (snapshot == null || isRetired()) throw CancellationException(RETIRED_MESSAGE)
        if (snapshot.isDestroyed) {
            if (isRetired()) throw CancellationException(RETIRED_MESSAGE)
            throw GoogleIdentityException(GoogleIdentityFailureReason.PROVIDER_FAILURE)
        }

        val attempt = adapterScope.async {
            client.clearCredentialState(ClearCredentialStateRequest())
        }
        synchronized(lock) { currentWork = attempt }
        try {
            try {
                withTimeout(requestTimeoutMillis) { attempt.await() }
            } catch (e: TimeoutCancellationException) {
                attempt.cancel(CancellationException(TIMEOUT_MESSAGE))
                currentCoroutineContext().ensureActive()
                if (isRetired()) throw CancellationException(RETIRED_MESSAGE)
                throw GoogleIdentityException(GoogleIdentityFailureReason.PROVIDER_FAILURE)
            } catch (e: CancellationException) {
                attempt.cancel(CancellationException(CLOSE_MESSAGE))
                throw e
            }
            currentCoroutineContext().ensureActive()
            if (isRetired()) throw CancellationException(RETIRED_MESSAGE)
            val live = currentHost()
            if (live == null || isRetired()) throw CancellationException(RETIRED_MESSAGE)
            if (live.isDestroyed) throw CancellationException(FENCED_MESSAGE)
        } catch (e: ClearCredentialUnsupportedException) {
            throw classifiedClearFailure(GoogleIdentityFailureReason.UNAVAILABLE)
        } catch (e: ClearCredentialProviderConfigurationException) {
            throw classifiedClearFailure(GoogleIdentityFailureReason.UNAVAILABLE)
        } catch (e: ClearCredentialException) {
            throw classifiedClearFailure(GoogleIdentityFailureReason.PROVIDER_FAILURE)
        } catch (e: GoogleIdentityException) {
            // Already classified: preserve it, with caller/retirement
            // precedence over the racing fault.
            currentCoroutineContext().ensureActive()
            if (isRetired()) throw CancellationException(RETIRED_MESSAGE)
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw classifiedClearFailure(GoogleIdentityFailureReason.PROVIDER_FAILURE)
        } finally {
            synchronized(lock) {
                if (currentWork === attempt) currentWork = null
            }
            attempt.cancel()
        }
    }

    private suspend fun classifiedClearFailure(
        reason: GoogleIdentityFailureReason,
    ): Nothing {
        currentCoroutineContext().ensureActive()
        if (isRetired()) throw CancellationException(RETIRED_MESSAGE)
        throw GoogleIdentityException(reason)
    }
}
