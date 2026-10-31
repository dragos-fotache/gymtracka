package com.dragosfotache.gymtracka.ui.account

import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportReplacementConsent
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportReplacementRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Opaque single-invocation consent identity. Carries no request data. */
internal class AccountImportConsentDecision internal constructor() {
    override fun toString(): String = "AccountImportConsentDecision(redacted)"
}

/**
 * one-current nonqueueing active-import consent bridge. Composable warning
 * callbacks capture the exact opaque decision token. Caller cancellation releases
 * the pending decision; a stale token answer after cancellation or close can never
 * consent a future request. Close permanently fences and cancels pending work.
 * Request data is never displayed or saved.
 */
internal class AccountImportConsent : LegacyImportReplacementConsent {
    private val lock = Any()
    private var closed = false
    private var current: AccountImportConsentDecision? = null
    private var gate: CompletableDeferred<Boolean>? = null

    private val _pending = MutableStateFlow<AccountImportConsentDecision?>(null)
    val pending: StateFlow<AccountImportConsentDecision?> = _pending.asStateFlow()

    override suspend fun confirm(request: LegacyImportReplacementRequest): Boolean {
        currentCoroutineContext().ensureActive()
        val callerJob = currentCoroutineContext()[Job]
        val decision: AccountImportConsentDecision
        val waiter: CompletableDeferred<Boolean>
        synchronized(lock) {
            callerJob?.ensureActive()
            if (closed) {
                throw CancellationException("Consent is closed")
            }
            if (current != null) {
                throw IllegalStateException("Consent is already pending")
            }
            decision = AccountImportConsentDecision()
            waiter = CompletableDeferred()
            current = decision
            gate = waiter
            _pending.value = decision
        }
        try {
            val confirmed = try {
                waiter.await()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw e
            }
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (closed || current !== decision) {
                    throw CancellationException("Consent was superseded")
                }
                return confirmed
            }
        } finally {
            synchronized(lock) {
                if (current === decision) {
                    current = null
                    gate = null
                    if (_pending.value === decision) {
                        _pending.value = null
                    }
                }
            }
        }
    }

    /** Resolves the exact pending decision. Stale tokens are ignored. */
    fun answer(decision: AccountImportConsentDecision, confirmed: Boolean) {
        val waiter: CompletableDeferred<Boolean>? = synchronized(lock) {
            if (closed) return
            if (current === null || current !== decision) return
            gate
        }
        try {
            waiter?.complete(confirmed)
        } catch (_: Exception) {
        }
    }

    /** Permanently fences and cancels any pending owned decision. */
    fun close() {
        val waiter: CompletableDeferred<Boolean>? = synchronized(lock) {
            if (closed) return
            closed = true
            val pendingGate = gate
            current = null
            gate = null
            _pending.value = null
            pendingGate
        }
        try {
            waiter?.cancel(CancellationException("Consent is closed"))
        } catch (_: Exception) {
        }
    }
}
