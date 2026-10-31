package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.api.GoogleChallengePurpose
import com.dragosfotache.gymtracka.data.sync.api.GoogleExchangeAttempt
import com.dragosfotache.gymtracka.data.sync.api.GoogleIdentityException
import com.dragosfotache.gymtracka.data.sync.api.GoogleIdentityProvider
import com.dragosfotache.gymtracka.data.sync.api.ServiceAuthApi
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.http.HttpServiceAuthApi
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import java.util.UUID
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

private const val RETIREMENT_MESSAGE = "Foreground sign-in was retired"
private const val SEAM_CANCEL_MESSAGE = "Foreground sign-in seam cancelled"

/**
 * explicitly constructed, SDK-independent foreground sign-in and
 * reauthentication orchestrator. Obtains one SIGN_IN challenge, supplies its
 * exact nonce to the Google provider, captures one exact exchange attempt,
 * retries that attempt within a finite foreground budget, and consumes the
 * service grant through the guarded private handoff. The invocation returns
 * private preparation, never a freely reusable grant or ACTIVE authority.
 *
 * Construction is inert and performs no I/O. One nonqueueing admission slot
 * spans challenge through final handoff acceptance; a second simultaneous
 * call throws BUSY without effects. The whole invocation (challenge, Google,
 * retries, delays and handoff) runs under a monotonic [attemptTimeoutMillis]
 * budget mapping to TIMED_OUT; caller cancellation propagates and dominates
 * racing faults. Owned seam work runs detached from the caller scope on the
 * caller dispatcher, so cancellation-resistant injected seams release the
 * caller promptly while their late results can never initiate the next phase,
 * consume the handoff or report success. [close] permanently retires this
 * orchestrator and cancels only its owned work, never the caller scope or
 * the borrowed provider/facade. No monitor is ever held across suspensions.
 * Displays are fixed and redacted; no tokens, nonces, bodies, keys or raw
 * dependency details escape.
 */
internal class ForegroundSignInCoordinator(
    private val origin: ServiceOrigin?,
    private val identityProvider: GoogleIdentityProvider,
    private val handoff: ForegroundGrantHandoff,
    private val authApi: ServiceAuthApi? = origin?.let { HttpServiceAuthApi(it) },
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val uuidProvider: () -> String = { UUID.randomUUID().toString() },
    private val attemptTimeoutMillis: Long = 180_000L,
) {
    private val lock = Any()

    // Guarded by lock; synchronous fencing only, never held across suspends.
    private var retired = false
    private var occupied = false
    private var currentSlot: Any? = null
    private var currentWork: Job? = null

    // Owned detached work runs here so close() can cancel in-flight seams
    // and delays without cancelling the caller scope.
    private val supervisor = SupervisorJob()
    private val ownedScope = CoroutineScope(supervisor + Dispatchers.Default)

    init {
        require(attemptTimeoutMillis > 0) { "attemptTimeoutMillis must be positive" }
    }

    /**
     * Runs one bounded foreground sign-in for [request]. See the class
     * documentation for ordering, retry, lifetime and failure semantics.
     */
    suspend fun signIn(request: ForegroundSignInRequest): AppSessionState {
        // Capture the actual outer caller context at entry: child-scope stack
        // recovery can replace cancellation at the timeout boundary, while an
        // originally cancelled caller must dominate. Outer catches below
        // recheck this original context, never the recovered child context.
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val fence = synchronized(lock) {
            if (retired) {
                throw ForegroundSignInException(ForegroundSignInFailure.RETIRED)
            }
            if (occupied) {
                throw ForegroundSignInException(ForegroundSignInFailure.BUSY)
            }
            occupied = true
            val fresh = Any()
            currentSlot = fresh
            fresh
        }
        try {
            return try {
                withTimeout(attemptTimeoutMillis) {
                    runSignIn(request, fence)
                }
            } catch (e: TimeoutCancellationException) {
                // Caller cancellation dominates the racing timeout.
                caller.ensureActive()
                throw ForegroundSignInException(ForegroundSignInFailure.TIMED_OUT)
            } catch (e: CancellationException) {
                // Generic scoped cancellation exits the timeout boundary
                // without its cause: re-establish the original caller cause
                // before propagating scoped cancellation.
                caller.ensureActive()
                throw e
            }
        } finally {
            // Only the current slot holder releases admission: a detached
            // late predecessor never clears a newer slot.
            synchronized(lock) {
                if (currentSlot === fence) {
                    currentSlot = null
                    occupied = false
                    currentWork = null
                }
            }
        }
    }

    /**
     * Synchronously, idempotently and permanently retires this orchestrator.
     * Cancels only its owned invocation work, never the caller scope or the
     * borrowed provider/facade. In-flight retirement yields fixed
     * cancellation; later calls throw RETIRED. Performs no provider
     * clear/logout, authority invalidation or cleanup.
     */
    fun close() {
        val work: Job?
        synchronized(lock) {
            if (retired) {
                return
            }
            retired = true
            work = currentWork
            currentWork = null
        }
        work?.cancel(CancellationException(SEAM_CANCEL_MESSAGE))
        supervisor.cancel(CancellationException(RETIREMENT_MESSAGE))
    }

    override fun toString(): String = "ForegroundSignInCoordinator(redacted)"

    private suspend fun runSignIn(
        request: ForegroundSignInRequest,
        fence: Any,
    ): AppSessionState {
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor] as? CoroutineDispatcher
            ?: Dispatchers.Default
        val configured = origin
            ?: throw ForegroundSignInException(ForegroundSignInFailure.CONFIGURATION_UNAVAILABLE)
        val api = authApi
            ?: throw ForegroundSignInException(ForegroundSignInFailure.CONFIGURATION_UNAVAILABLE)
        if (request.origin != configured) {
            throw ForegroundSignInException(ForegroundSignInFailure.INVALID_REQUEST)
        }
        try {
            canonicalForegroundOperation(configured, request.intent, request.expected, request.operationId)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: ForegroundSignInContextInvalidException) {
            currentCoroutineContext().ensureActive()
            ensureSlotCurrent(fence)
            throw ForegroundSignInException(ForegroundSignInFailure.INVALID_REQUEST)
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureSlotCurrent(fence)
            throw ForegroundSignInException(ForegroundSignInFailure.UNAVAILABLE)
        }

        // One explicit SIGN_IN challenge only; challenge failures never
        // reacquire automatically.
        val challenge = try {
            runOwned(fence, dispatcher) { api.challenge(GoogleChallengePurpose.SIGN_IN) }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: SyncApiException.Transient) {
            currentCoroutineContext().ensureActive()
            ensureSlotCurrent(fence)
            throw ForegroundSignInException(ForegroundSignInFailure.TRANSIENT_FAILURE)
        } catch (_: SyncApiException.RequestRejected) {
            currentCoroutineContext().ensureActive()
            ensureSlotCurrent(fence)
            throw ForegroundSignInException(ForegroundSignInFailure.REQUEST_REJECTED)
        } catch (_: SyncApiException.RequestTooLarge) {
            currentCoroutineContext().ensureActive()
            ensureSlotCurrent(fence)
            throw ForegroundSignInException(ForegroundSignInFailure.REQUEST_REJECTED)
        } catch (_: SyncApiException.ReauthenticationRequired) {
            currentCoroutineContext().ensureActive()
            ensureSlotCurrent(fence)
            throw ForegroundSignInException(ForegroundSignInFailure.REQUEST_REJECTED)
        } catch (_: SyncApiException.MalformedResponse) {
            currentCoroutineContext().ensureActive()
            ensureSlotCurrent(fence)
            throw ForegroundSignInException(ForegroundSignInFailure.MALFORMED_RESPONSE)
        } catch (_: SyncApiException) {
            currentCoroutineContext().ensureActive()
            ensureSlotCurrent(fence)
            throw ForegroundSignInException(ForegroundSignInFailure.UNAVAILABLE)
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureSlotCurrent(fence)
            throw ForegroundSignInException(ForegroundSignInFailure.UNAVAILABLE)
        }

        // Freshness is required before Google acquisition and again
        // immediately before the first exchange; equality refuses.
        if (!isChallengeLive(challenge.expiresAtMillis)) {
            throw ForegroundSignInException(ForegroundSignInFailure.CHALLENGE_EXPIRED)
        }

        // Exactly one Google acquisition with the exact challenge nonce; the
        // opaque token is used unchanged.
        val token = try {
            runOwned(fence, dispatcher) {
                // Provider admission freshness: the caller-side check can
                // precede a dispatcher stall past challenge expiry, so refuse
                // an already-expired challenge inside the owned block before
                // acquisition, as for first exchange admission.
                if (!isChallengeLive(challenge.expiresAtMillis)) {
                    throw ForegroundSignInException(ForegroundSignInFailure.CHALLENGE_EXPIRED)
                }
                identityProvider.credentialFor(challenge.nonce)
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: ForegroundSignInException) {
            currentCoroutineContext().ensureActive()
            ensureSlotCurrent(fence)
            throw e
        } catch (e: GoogleIdentityException) {
            currentCoroutineContext().ensureActive()
            ensureSlotCurrent(fence)
            throw ForegroundSignInException(ForegroundSignInFailure.IDENTITY_FAILED, e.reason)
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureSlotCurrent(fence)
            throw ForegroundSignInException(ForegroundSignInFailure.UNAVAILABLE)
        }

        // Exactly one canonical exchange key and one immutable attempt,
        // retained only within this invocation.
        val key = try {
            uuidProvider()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureSlotCurrent(fence)
            throw ForegroundSignInException(ForegroundSignInFailure.UNAVAILABLE)
        }
        val attempt = try {
            GoogleExchangeAttempt.capture(key, challenge.challengeId, token)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureSlotCurrent(fence)
            throw ForegroundSignInException(ForegroundSignInFailure.UNAVAILABLE)
        }

        if (!isChallengeLive(challenge.expiresAtMillis)) {
            throw ForegroundSignInException(ForegroundSignInFailure.CHALLENGE_EXPIRED)
        }

        // At most three total sends of the same attempt object. Only
        // transient faults retry, with the larger of the bounded schedule
        // and the service hint; the whole-invocation timeout ends a longer
        // delay. No expiry refusal applies to uncertain replays: the service
        // validates committed replay and the returned grant is preserved
        // unchanged, including expired access.
        var retryAfterMillis: Long? = null
        var grant: ServiceSession? = null
        for (sendIndex in 0..2) {
            if (sendIndex > 0) {
                val requiredDelay = if (sendIndex == 1) {
                    maxOf(1_000L, retryAfterMillis ?: 0L)
                } else {
                    maxOf(2_000L, retryAfterMillis ?: 0L)
                }
                try {
                    runOwned(fence, dispatcher) { delay(requiredDelay) }
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw e
                }
            }
            currentCoroutineContext().ensureActive()
            ensureSlotCurrent(fence)
            try {
                grant = runOwned(fence, dispatcher) {
                    // First-send admission freshness: an expired challenge at
                    // actual first-send admission refuses here, inside the
                    // owned block immediately before the exchange. Sent
                    // uncertain retries never recheck expiry.
                    if (sendIndex == 0 && !isChallengeLive(challenge.expiresAtMillis)) {
                        throw ForegroundSignInException(ForegroundSignInFailure.CHALLENGE_EXPIRED)
                    }
                    api.exchange(attempt)
                }
                break
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            } catch (e: ForegroundSignInException) {
                currentCoroutineContext().ensureActive()
                ensureSlotCurrent(fence)
                throw e
            } catch (e: SyncApiException.Transient) {
                currentCoroutineContext().ensureActive()
                ensureSlotCurrent(fence)
                if (sendIndex == 2) {
                    throw ForegroundSignInException(ForegroundSignInFailure.RETRY_EXHAUSTED)
                }
                retryAfterMillis = e.retryAfterMillis
            } catch (_: SyncApiException.RequestRejected) {
                currentCoroutineContext().ensureActive()
                ensureSlotCurrent(fence)
                throw ForegroundSignInException(ForegroundSignInFailure.REQUEST_REJECTED)
            } catch (_: SyncApiException.RequestTooLarge) {
                currentCoroutineContext().ensureActive()
                ensureSlotCurrent(fence)
                throw ForegroundSignInException(ForegroundSignInFailure.REQUEST_REJECTED)
            } catch (_: SyncApiException.ReauthenticationRequired) {
                currentCoroutineContext().ensureActive()
                ensureSlotCurrent(fence)
                throw ForegroundSignInException(ForegroundSignInFailure.REQUEST_REJECTED)
            } catch (_: SyncApiException.MalformedResponse) {
                currentCoroutineContext().ensureActive()
                ensureSlotCurrent(fence)
                throw ForegroundSignInException(ForegroundSignInFailure.MALFORMED_RESPONSE)
            } catch (_: SyncApiException) {
                currentCoroutineContext().ensureActive()
                ensureSlotCurrent(fence)
                throw ForegroundSignInException(ForegroundSignInFailure.UNAVAILABLE)
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                ensureSlotCurrent(fence)
                throw ForegroundSignInException(ForegroundSignInFailure.UNAVAILABLE)
            }
        }
        val session = grant
            ?: throw ForegroundSignInException(ForegroundSignInFailure.UNAVAILABLE)

        // Exactly one handoff after service success.
        currentCoroutineContext().ensureActive()
        ensureSlotCurrent(fence)
        val result = try {
            runOwned(fence, dispatcher) { handoff.acceptForegroundGrant(request, session) }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: AppSessionException) {
            currentCoroutineContext().ensureActive()
            ensureSlotCurrent(fence)
            throw e
        } catch (e: AppSessionStaleException) {
            currentCoroutineContext().ensureActive()
            ensureSlotCurrent(fence)
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ensureSlotCurrent(fence)
            throw ForegroundSignInException(ForegroundSignInFailure.UNAVAILABLE)
        }

        currentCoroutineContext().ensureActive()
        ensureSlotCurrent(fence)
        if (result !is AppSessionState.Private) {
            throw ForegroundSignInException(ForegroundSignInFailure.UNAVAILABLE)
        }
        return result
    }

    private fun readClockMillis(): Long {
        try {
            return clockMillis()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw ForegroundSignInException(ForegroundSignInFailure.UNAVAILABLE)
        }
    }

    private suspend fun isChallengeLive(expiresAtMillis: Long): Boolean {
        val now = readClockMillis()
        currentCoroutineContext().ensureActive()
        return now < expiresAtMillis
    }

    /**
     * Runs one owned seam lazily detached from the caller scope on
     * [dispatcher]. The work is published and caller/slot-rechecked under
     * the monitor before start, and rechecks caller activity plus the exact
     * invocation fence inside the owned block immediately before invoking
     * the effect, so queued late work never even starts a seam after caller
     * cancellation, retirement or slot turnover. The caller wait stays
     * cancellable, so resistant seams release the caller promptly; a
     * detached late result is never consumed because every return rechecks
     * caller activity and the exact fence before the result is used, and
     * cleanup never clears a newer slot. A seam-owned
     * TimeoutCancellationException is a dependency fault, never this
     * invocation's budget; caller cancellation always propagates and
     * dominates racing faults.
     */
    private suspend fun <T> runOwned(
        fence: Any,
        dispatcher: CoroutineDispatcher,
        block: suspend () -> T,
    ): T {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        ensureSlotCurrent(fence)
        val callerJob = caller[Job]
        val deferred = ownedScope.async(dispatcher, start = CoroutineStart.LAZY) {
            callerJob?.ensureActive()
            ensureSlotCurrent(fence)
            block()
        }
        try {
            synchronized(lock) {
                caller.ensureActive()
                if (retired || currentSlot !== fence) {
                    throw CancellationException(RETIREMENT_MESSAGE)
                }
                currentWork = deferred
            }
            deferred.start()
            try {
                val result = deferred.await()
                currentCoroutineContext().ensureActive()
                ensureSlotCurrent(fence)
                return result
            } catch (e: TimeoutCancellationException) {
                // A completed deferred failure is seam-owned, never this
                // invocation's budget; an incomplete one is this invocation's
                // own timeout racing the seam.
                val seamTimedOut = deferred.isCompleted
                deferred.cancel(CancellationException(SEAM_CANCEL_MESSAGE))
                // Caller cancellation dominates either timeout.
                currentCoroutineContext().ensureActive()
                ensureSlotCurrent(fence)
                if (seamTimedOut) {
                    throw ForegroundSignInException(ForegroundSignInFailure.UNAVAILABLE)
                }
                throw e
            } catch (e: CancellationException) {
                deferred.cancel(CancellationException(SEAM_CANCEL_MESSAGE))
                // Caller cancellation dominates a racing fault or retirement.
                currentCoroutineContext().ensureActive()
                ensureSlotCurrent(fence)
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                ensureSlotCurrent(fence)
                throw e
            }
        } finally {
            // Every created deferred is cancelled on all exits, including
            // pre-start registration failure, so an unstarted lazy child
            // never lingers on the long-lived supervisor retaining closures.
            // currentWork clears only by exact identity, never a newer slot.
            deferred.cancel(CancellationException(SEAM_CANCEL_MESSAGE))
            synchronized(lock) {
                if (currentWork === deferred) {
                    currentWork = null
                }
            }
        }
    }

    /**
     * Invocation liveness: caller activity plus the exact slot fence. Throws
     * fixed cancellation when retired or superseded, so detached late work
     * can never advance. Never held across suspensions by callers.
     */
    private suspend fun ensureSlotCurrent(fence: Any) {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        synchronized(lock) {
            caller.ensureActive()
            if (retired || currentSlot !== fence) {
                throw CancellationException(RETIREMENT_MESSAGE)
            }
        }
    }
}
