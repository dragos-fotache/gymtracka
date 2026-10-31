package com.dragosfotache.gymtracka.ui.account

import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportProgress
import com.dragosfotache.gymtracka.data.sync.legacy.LegacyImportProgressPhase
import com.dragosfotache.gymtracka.data.sync.model.requireWorkoutUuid
import com.dragosfotache.gymtracka.data.sync.session.AccountSyncStatus
import com.dragosfotache.gymtracka.data.sync.session.AppAccountSession
import com.dragosfotache.gymtracka.data.sync.session.AppSessionException
import com.dragosfotache.gymtracka.data.sync.session.AppSessionState
import com.dragosfotache.gymtracka.data.sync.session.AppSessionReason
import com.dragosfotache.gymtracka.data.sync.session.AppSessionStaleException
import com.dragosfotache.gymtracka.data.sync.session.ForegroundSignInException
import com.dragosfotache.gymtracka.data.sync.session.ForegroundSignInIntent
import com.dragosfotache.gymtracka.data.sync.session.RecoveryRoomPhase
import com.dragosfotache.gymtracka.data.sync.session.RecoveryRoomSnapshot
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Closed startup presentation errors. Fixed safe text only. */
internal enum class AccountStartupError {
    UNAVAILABLE,
    SIGN_IN_FAILED,
    PREPARATION_PAUSED,
    IDENTITY_MISMATCH,
    EDITOR_OPEN,
    CANCELLED,
}

/** Startup presentation snapshot. Positive visibility stays with exact-handle binding. */
internal data class AccountStartupUiState(
    val busy: Boolean = false,
    val error: AccountStartupError? = null,
    val importProgress: LegacyImportProgress? = null,
    val syncStatus: AccountSyncStatus? = null,
)

/** Narrow startup operation seam over the session facade. */
internal interface AccountStartupOperations {
    val state: StateFlow<AppSessionState>
    suspend fun recover(): AppSessionState
    suspend fun signIn(
        intent: ForegroundSignInIntent,
        expected: RecoveryRoomSnapshot,
        operationId: String,
    ): AppSessionState
    suspend fun completeActivation(expected: RecoveryRoomSnapshot, stageId: String): AppSessionState
    suspend fun completeActivationAfterReconnect(expected: RecoveryRoomSnapshot, stageId: String): AppSessionState =
        completeActivation(expected, stageId)
    suspend fun observeLegacyImport(): LegacyImportProgress?
    fun observeSyncStatus(session: AppAccountSession): Flow<AccountSyncStatus>
}

/**
 * mandatory startup controller. Serializes one nonqueueing owned operation
 * across start/signIn/continuePreparation with synchronous occupation/generation
 * fencing before launch/dispatch. Owned jobs are created lazy and registered
 * before start so cancellation before the body still releases occupation; a lost
 * return never releases a newer slot. Generation is rechecked before every seam.
 * Old operation and status collectors never replace newer presentation: the
 * authority watcher clears retired/new-handle status before any new collector
 * emits. Fresh recover prevents stale advisory snapshots from authorizing work.
 * Blocked or corrupt import observation maps closed UNAVAILABLE, never a
 * fabricated no-migration. Failed post-sign-in fresh recover maps UNAVAILABLE
 * with no stale fallback. Cancellation settles synchronously in the owned job
 * with no untracked follow-up work. Null operations means configuration
 * unavailable with no effects and an initial UNAVAILABLE presentation.
 */
internal class AccountStartupState(
    private val operations: AccountStartupOperations?,
    val signInAvailable: Boolean,
    private val scope: CoroutineScope,
    private val uuidProvider: () -> String = { UUID.randomUUID().toString() },
) {
    /** False when configuration is unavailable (null operations). */
    val configurationAvailable: Boolean get() = operations != null

    private val lock = Any()
    private var generation: Long = 0L
    private var occupied: Boolean = false
    private var currentJob: Job? = null
    private var authorityJob: Job? = null
    private var statusJob: Job? = null
    private var statusGeneration: Long = 0L
    private var statusHandle: AppAccountSession? = null
    private var started: Boolean = false
    private var disposed: Boolean = false

    private val _uiState = MutableStateFlow(
        AccountStartupUiState(error = if (operations == null) AccountStartupError.UNAVAILABLE else null),
    )
    val uiState: StateFlow<AccountStartupUiState> = _uiState.asStateFlow()

    /** Actual later sync supersedes a failed sign-in attempt, but not editor/identity/metadata failures. */
    fun onSyncSucceeded() {
        synchronized(lock) {
            if (disposed || occupied || _uiState.value.error != AccountStartupError.SIGN_IN_FAILED) return
            _uiState.value = _uiState.value.copy(error = null)
        }
    }

    /** Idempotent single startup recovery plus authority watching. No HTTP. */
    fun start() {
        val prepared = synchronized(lock) {
            if (disposed || started) return
            started = true
            val ops = operations ?: return
            if (occupied || !isScopeAlive()) return
            occupied = true
            generation += 1
            Pair(ops, generation)
        }
        val (target, gen) = prepared
        setBusyIfCurrent(gen, true, clearError = true)
        ensureAuthorityWatcher(target)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val recovered = try {
                    callRecover(target)
                } catch (e: CancellationException) {
                    settleCancelled(gen)
                    throw e
                } catch (e: AccountStartupException) {
                    finishIfCurrent(gen, e.error)
                    return@launch
                } catch (_: Exception) {
                    finishIfCurrent(gen, AccountStartupError.UNAVAILABLE)
                    return@launch
                }
                if (!checkCurrent(gen)) return@launch
                when (recovered) {
                    is AppSessionState.Active -> {
                        ensureStatusCollector(gen, recovered.session)
                        if (!checkCurrent(gen)) return@launch
                        when (val observation = observeImport(target)) {
                            is ImportObservation.Observed -> {
                                updateImportIfCurrent(gen, observation.progress)
                                finishIfCurrent(gen, null)
                            }
                            is ImportObservation.Failed -> finishIfCurrent(gen, observation.error)
                        }
                    }
                    is AppSessionState.Private -> {
                        if (!checkCurrent(gen)) return@launch
                        when (val observation = observeImport(target)) {
                            is ImportObservation.Observed -> {
                                updateImportIfCurrent(gen, observation.progress)
                                finishIfCurrent(gen, null)
                            }
                            is ImportObservation.Failed -> finishIfCurrent(gen, observation.error)
                        }
                    }
                    is AppSessionState.PrivacyLocked -> finishIfCurrent(gen, null)
                }
            } catch (e: CancellationException) {
                settleCancelled(gen)
                throw e
            } finally {
                releaseIfCurrent(gen)
            }
        }
        if (!registerOwned(gen, job)) {
            releaseIfCurrent(gen)
            return
        }
        if (!job.start()) {
            releaseIfCurrent(gen)
        }
    }

    /**
     * Nonqueueing fresh-recover sign-in followed by at most one activation.
     * An already Active recovery derives its expectation from the live handle
     * and still performs real same-account reauthentication through the
     * foreground adapter; the editor/drain path stays reachable.
     */
    fun signIn() {
        val prepared = synchronized(lock) {
            if (disposed) return
            val ops = operations ?: return
            if (occupied || !isScopeAlive()) return
            occupied = true
            generation += 1
            Pair(ops, generation)
        }
        val (target, gen) = prepared
        setBusyIfCurrent(gen, true, clearError = true)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!signInAvailable) {
                    finishIfCurrent(gen, AccountStartupError.SIGN_IN_FAILED)
                    return@launch
                }
                val recovered = try {
                    callRecover(target)
                } catch (e: CancellationException) {
                    settleCancelled(gen)
                    throw e
                } catch (e: AccountStartupException) {
                    finishIfCurrent(gen, e.error)
                    return@launch
                } catch (_: Exception) {
                    finishIfCurrent(gen, AccountStartupError.UNAVAILABLE)
                    return@launch
                }
                if (!checkCurrent(gen)) return@launch
                val expected: RecoveryRoomSnapshot = when (recovered) {
                    is AppSessionState.Active -> recovered.session.room
                    is AppSessionState.Private ->
                        recovered.room ?: run {
                            finishIfCurrent(gen, AccountStartupError.UNAVAILABLE)
                            return@launch
                        }
                    is AppSessionState.PrivacyLocked -> {
                        finishIfCurrent(gen, null)
                        return@launch
                    }
                }
                if (!checkCurrent(gen)) return@launch
                val importProgress = when (val observation = observeImport(target)) {
                    is ImportObservation.Failed -> {
                        finishIfCurrent(gen, observation.error)
                        return@launch
                    }
                    is ImportObservation.Observed -> observation.progress
                }
                if (!checkCurrent(gen)) return@launch
                updateImportIfCurrent(gen, importProgress)
                if (importProgress != null && importProgress.phase == LegacyImportProgressPhase.BLOCKED) {
                    finishIfCurrent(gen, AccountStartupError.UNAVAILABLE)
                    return@launch
                }
                val selection = try {
                    selectSignInIntent(expected, importProgress)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AccountStartupException) {
                    finishIfCurrent(gen, e.error)
                    return@launch
                } catch (_: Exception) {
                    finishIfCurrent(gen, AccountStartupError.UNAVAILABLE)
                    return@launch
                }
                if (!checkCurrent(gen)) return@launch
                val afterSignIn = try {
                    callSignIn(target, selection.first, expected, selection.second)
                } catch (e: CancellationException) {
                    settleCancelled(gen)
                    throw e
                } catch (e: AccountStartupException) {
                    finishIfCurrent(gen, e.error)
                    return@launch
                } catch (_: Exception) {
                    finishIfCurrent(gen, AccountStartupError.UNAVAILABLE)
                    return@launch
                }
                if (!checkCurrent(gen)) return@launch
                if (afterSignIn is AppSessionState.Active) {
                    ensureStatusCollector(gen, afterSignIn.session)
                    finishIfCurrent(gen, null)
                    return@launch
                }
                if (afterSignIn !is AppSessionState.Private) {
                    finishIfCurrent(gen, null)
                    return@launch
                }
                // Authoritative fresh recover for activation. Any failure maps
                // closed UNAVAILABLE; the stale sign-in observation is never
                // reused as activation authority.
                val fresh = try {
                    callRecover(target)
                } catch (e: CancellationException) {
                    settleCancelled(gen)
                    throw e
                } catch (e: AccountStartupException) {
                    finishIfCurrent(gen, e.error)
                    return@launch
                } catch (_: Exception) {
                    finishIfCurrent(gen, AccountStartupError.UNAVAILABLE)
                    return@launch
                }
                if (!checkCurrent(gen)) return@launch
                val activationExpected = (fresh as? AppSessionState.Private)?.room
                if (fresh is AppSessionState.Active) {
                    ensureStatusCollector(gen, fresh.session)
                    finishIfCurrent(gen, null)
                    return@launch
                }
                if (activationExpected == null || activationExpected.phase != RecoveryRoomPhase.PREPARING) {
                    finishIfCurrent(gen, AccountStartupError.PREPARATION_PAUSED)
                    return@launch
                }
                if (!checkCurrent(gen)) return@launch
                when (val preActivation = observeImport(target)) {
                    is ImportObservation.Failed -> {
                        finishIfCurrent(gen, preActivation.error)
                        return@launch
                    }
                    is ImportObservation.Observed -> {
                        updateImportIfCurrent(gen, preActivation.progress)
                        if (preActivation.progress != null &&
                            preActivation.progress.phase == LegacyImportProgressPhase.BLOCKED
                        ) {
                            finishIfCurrent(gen, AccountStartupError.UNAVAILABLE)
                            return@launch
                        }
                    }
                }
                // One fresh stage UUID per activation attempt, validated once.
                val stageId = try {
                    freshUuid()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AccountStartupException) {
                    finishIfCurrent(gen, e.error)
                    return@launch
                } catch (_: Exception) {
                    finishIfCurrent(gen, AccountStartupError.UNAVAILABLE)
                    return@launch
                }
                if (!checkCurrent(gen)) return@launch
                val afterActivation = try {
                    callActivate(target, activationExpected, stageId)
                } catch (e: CancellationException) {
                    settleCancelled(gen)
                    throw e
                } catch (e: AccountStartupException) {
                    finishIfCurrent(gen, e.error)
                    return@launch
                } catch (_: Exception) {
                    finishIfCurrent(gen, AccountStartupError.UNAVAILABLE)
                    return@launch
                }
                if (!checkCurrent(gen)) return@launch
                if (afterActivation is AppSessionState.Active) {
                    ensureStatusCollector(gen, afterActivation.session)
                    finishIfCurrent(gen, null)
                } else {
                    when (val refresh = observeImport(target)) {
                        is ImportObservation.Failed -> finishIfCurrent(gen, refresh.error)
                        is ImportObservation.Observed -> {
                            updateImportIfCurrent(gen, refresh.progress)
                            finishIfCurrent(gen, AccountStartupError.PREPARATION_PAUSED)
                        }
                    }
                }
            } catch (e: CancellationException) {
                settleCancelled(gen)
                throw e
            } finally {
                releaseIfCurrent(gen)
            }
        }
        if (!registerOwned(gen, job)) {
            releaseIfCurrent(gen)
            return
        }
        if (!job.start()) {
            releaseIfCurrent(gen)
        }
    }

    /** Explicit single activation over a fresh exact PREPARING observation. */
    fun continuePreparation(connectionRestored: Boolean = false) {
        val prepared = synchronized(lock) {
            if (disposed) return
            val ops = operations ?: return
            if (occupied || !isScopeAlive()) return
            occupied = true
            generation += 1
            Pair(ops, generation)
        }
        val (target, gen) = prepared
        setBusyIfCurrent(gen, true, clearError = true)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val recovered = try {
                    callRecover(target)
                } catch (e: CancellationException) {
                    settleCancelled(gen)
                    throw e
                } catch (e: AccountStartupException) {
                    finishIfCurrent(gen, e.error)
                    return@launch
                } catch (_: Exception) {
                    finishIfCurrent(gen, AccountStartupError.UNAVAILABLE)
                    return@launch
                }
                if (!checkCurrent(gen)) return@launch
                if (recovered is AppSessionState.Active) {
                    ensureStatusCollector(gen, recovered.session)
                    finishIfCurrent(gen, null)
                    return@launch
                }
                if (recovered !is AppSessionState.Private) {
                    finishIfCurrent(gen, null)
                    return@launch
                }
                val expected = recovered.room ?: run {
                    finishIfCurrent(gen, AccountStartupError.UNAVAILABLE)
                    return@launch
                }
                if (expected.phase != RecoveryRoomPhase.PREPARING) {
                    finishIfCurrent(gen, AccountStartupError.PREPARATION_PAUSED)
                    return@launch
                }
                if (!checkCurrent(gen)) return@launch
                when (val observation = observeImport(target)) {
                    is ImportObservation.Failed -> {
                        finishIfCurrent(gen, observation.error)
                        return@launch
                    }
                    is ImportObservation.Observed -> {
                        updateImportIfCurrent(gen, observation.progress)
                        if (observation.progress != null &&
                            observation.progress.phase == LegacyImportProgressPhase.BLOCKED
                        ) {
                            finishIfCurrent(gen, AccountStartupError.UNAVAILABLE)
                            return@launch
                        }
                    }
                }
                val stageId = try {
                    freshUuid()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AccountStartupException) {
                    finishIfCurrent(gen, e.error)
                    return@launch
                } catch (_: Exception) {
                    finishIfCurrent(gen, AccountStartupError.UNAVAILABLE)
                    return@launch
                }
                if (!checkCurrent(gen)) return@launch
                val after = try {
                    callActivate(target, expected, stageId, connectionRestored)
                } catch (e: CancellationException) {
                    settleCancelled(gen)
                    throw e
                } catch (e: AccountStartupException) {
                    finishIfCurrent(gen, e.error)
                    return@launch
                } catch (_: Exception) {
                    finishIfCurrent(gen, AccountStartupError.UNAVAILABLE)
                    return@launch
                }
                if (!checkCurrent(gen)) return@launch
                if (after is AppSessionState.Active) {
                    ensureStatusCollector(gen, after.session)
                    finishIfCurrent(gen, null)
                } else {
                    when (val refresh = observeImport(target)) {
                        is ImportObservation.Failed -> finishIfCurrent(gen, refresh.error)
                        is ImportObservation.Observed -> {
                            updateImportIfCurrent(gen, refresh.progress)
                            finishIfCurrent(gen, AccountStartupError.PREPARATION_PAUSED)
                        }
                    }
                }
            } catch (e: CancellationException) {
                settleCancelled(gen)
                throw e
            } finally {
                releaseIfCurrent(gen)
            }
        }
        if (!registerOwned(gen, job)) {
            releaseIfCurrent(gen)
            return
        }
        if (!job.start()) {
            releaseIfCurrent(gen)
        }
    }

    /**
     * Cancels only the owned current invocation and settles privately with one
     * fresh read-only recovery. Never auto-activates, never clears
     * credential/vault/import/outbox state, and never claims remote rollback.
     * Settles CANCELLED; corrupt import evidence is retained, never cleared to
     * a fabricated no-migration. Idle cancel disarms a deferred activation retry, otherwise is a no-op.
     */
    fun cancel() {
        val prepared = synchronized(lock) {
            if (disposed) return
            if (currentJob?.isActive != true) {
                // An idle deferred activation owns no job. Cancel still disarms its foreground retry.
                if (_uiState.value.error == AccountStartupError.PREPARATION_PAUSED &&
                    (operations?.state?.value as? AppSessionState.Private)?.retryAtMillis != null
                ) {
                    _uiState.value = _uiState.value.copy(error = AccountStartupError.CANCELLED)
                }
                return
            }
            if (!isScopeAlive()) {
                currentJob?.cancel()
                currentJob = null
                occupied = false
                return
            }
            val ops = operations ?: return
            generation += 1
            currentJob?.cancel()
            currentJob = null
            occupied = false
            occupied = true
            generation += 1
            Pair(ops, generation)
        }
        val (target, gen) = prepared
        setBusyIfCurrent(gen, true, clearError = false)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val settled = try {
                    callRecover(target)
                } catch (e: CancellationException) {
                    settleCancelled(gen)
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (!checkCurrent(gen)) return@launch
                if (settled is AppSessionState.Active) {
                    ensureStatusCollector(gen, settled.session)
                }
                if (!checkCurrent(gen)) return@launch
                // Retain corrupt evidence: failures leave the last import
                // observation untouched instead of fabricating no migration.
                when (val observation = observeImport(target)) {
                    is ImportObservation.Observed ->
                        updateImportIfCurrent(gen, observation.progress)
                    is ImportObservation.Failed -> Unit
                }
                finishIfCurrent(gen, AccountStartupError.CANCELLED)
            } catch (e: CancellationException) {
                settleCancelled(gen)
                throw e
            } finally {
                releaseIfCurrent(gen)
            }
        }
        if (!registerOwned(gen, job)) {
            releaseIfCurrent(gen)
            return
        }
        if (!job.start()) {
            releaseIfCurrent(gen)
        }
    }

    /** Fences presentation and cancels owned jobs. Never cancels the borrowed scope. */
    fun dispose() {
        val job: Job?
        val watcher: Job?
        val status: Job?
        synchronized(lock) {
            if (disposed) return
            disposed = true
            generation += 1
            statusGeneration += 1
            job = currentJob
            watcher = authorityJob
            status = statusJob
            currentJob = null
            authorityJob = null
            statusJob = null
            statusHandle = null
            occupied = false
            _uiState.value = AccountStartupUiState()
        }
        try {
            job?.cancel()
        } catch (_: Exception) {
        }
        try {
            watcher?.cancel()
        } catch (_: Exception) {
        }
        try {
            status?.cancel()
        } catch (_: Exception) {
        }
    }

    private fun selectSignInIntent(
        expected: RecoveryRoomSnapshot,
        progress: LegacyImportProgress?,
    ): Pair<ForegroundSignInIntent, String> {
        if (expected.phase == RecoveryRoomPhase.SIGNED_OUT &&
            expected.cachedAccountId == null &&
            expected.identity == null
        ) {
            if (progress != null) {
                throw AccountStartupException(AccountStartupError.IDENTITY_MISMATCH)
            }
            return ForegroundSignInIntent.INITIAL_SIGN_IN to freshUuid()
        }
        if (expected.phase == RecoveryRoomPhase.ACTIVE) {
            val current = expected.identity?.operationId
                ?: throw AccountStartupException(AccountStartupError.IDENTITY_MISMATCH)
            if (expected.cachedAccountId == null) {
                throw AccountStartupException(AccountStartupError.IDENTITY_MISMATCH)
            }
            val fresh = freshUuid()
            if (fresh == current) {
                throw AccountStartupException(AccountStartupError.UNAVAILABLE)
            }
            return ForegroundSignInIntent.SAME_ACCOUNT_REAUTHENTICATION to fresh
        }
        if (expected.phase == RecoveryRoomPhase.PREPARING || expected.phase == RecoveryRoomPhase.TRANSITIONING) {
            val reserved = expected.identity?.operationId
                ?: throw AccountStartupException(AccountStartupError.IDENTITY_MISMATCH)
            if (expected.cachedAccountId == null) {
                throw AccountStartupException(AccountStartupError.IDENTITY_MISMATCH)
            }
            val validPrivateImport = expected.phase == RecoveryRoomPhase.PREPARING &&
                progress != null &&
                (progress.phase == LegacyImportProgressPhase.CAPTURED ||
                    progress.phase == LegacyImportProgressPhase.SETTLED)
            val fresh = freshUuid()
            if (fresh == reserved) {
                throw AccountStartupException(AccountStartupError.UNAVAILABLE)
            }
            if (validPrivateImport) {
                return ForegroundSignInIntent.PRIVATE_IMPORT_REAUTHENTICATION to fresh
            }
            return ForegroundSignInIntent.SAME_ACCOUNT_REAUTHENTICATION to fresh
        }
        if (expected.phase == RecoveryRoomPhase.SIGNED_OUT && expected.cachedAccountId != null) {
            if (expected.identity != null) {
                throw AccountStartupException(AccountStartupError.IDENTITY_MISMATCH)
            }
            return ForegroundSignInIntent.SAME_ACCOUNT_REAUTHENTICATION to freshUuid()
        }
        throw AccountStartupException(AccountStartupError.IDENTITY_MISMATCH)
    }

    /**
     * Single validated UUID supply. Provider failures map closed UNAVAILABLE;
     * non-canonical values map closed IDENTITY_MISMATCH. Never retried.
     */
    private fun freshUuid(): String {
        val raw = try {
            uuidProvider()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw AccountStartupException(AccountStartupError.UNAVAILABLE)
        }
        try {
            requireWorkoutUuid(raw, "id")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw AccountStartupException(AccountStartupError.IDENTITY_MISMATCH)
        }
        return raw
    }

    private sealed interface ImportObservation {
        data class Observed(val progress: LegacyImportProgress?) : ImportObservation
        data class Failed(val error: AccountStartupError) : ImportObservation
    }

    /**
     * Closed import reread. Null means genuinely no migration. Blocked, corrupt
     * or failed observation maps closed UNAVAILABLE; foreign evidence maps
     * IDENTITY_MISMATCH; lost authority maps CANCELLED. Caller cancellation is
     * rethrown. Never fabricates no-migration from failure.
     */
    private suspend fun observeImport(target: AccountStartupOperations): ImportObservation {
        currentCoroutineContext().ensureActive()
        return try {
            ImportObservation.Observed(target.observeLegacyImport())
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw e
        } catch (e: AppSessionStaleException) {
            currentCoroutineContext().ensureActive()
            ImportObservation.Failed(AccountStartupError.CANCELLED)
        } catch (e: AppSessionException) {
            currentCoroutineContext().ensureActive()
            if (e.reason == AppSessionReason.IDENTITY_MISMATCH) {
                ImportObservation.Failed(AccountStartupError.IDENTITY_MISMATCH)
            } else {
                ImportObservation.Failed(AccountStartupError.UNAVAILABLE)
            }
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            ImportObservation.Failed(AccountStartupError.UNAVAILABLE)
        }
    }

    private suspend fun callRecover(target: AccountStartupOperations): AppSessionState {
        try {
            return target.recover()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccountStartupException) {
            throw e
        } catch (e: AppSessionStaleException) {
            throw AccountStartupException(AccountStartupError.CANCELLED)
        } catch (e: AppSessionException) {
            throw AccountStartupException(mapReason(e.reason))
        } catch (_: Exception) {
            throw AccountStartupException(AccountStartupError.UNAVAILABLE)
        }
    }

    private suspend fun callSignIn(
        target: AccountStartupOperations,
        intent: ForegroundSignInIntent,
        expected: RecoveryRoomSnapshot,
        operationId: String,
    ): AppSessionState {
        try {
            return target.signIn(intent, expected, operationId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccountStartupException) {
            throw e
        } catch (e: ForegroundSignInException) {
            throw AccountStartupException(AccountStartupError.SIGN_IN_FAILED)
        } catch (e: AppSessionStaleException) {
            throw AccountStartupException(AccountStartupError.CANCELLED)
        } catch (e: AppSessionException) {
            throw AccountStartupException(mapReason(e.reason))
        } catch (_: Exception) {
            throw AccountStartupException(AccountStartupError.UNAVAILABLE)
        }
    }

    private suspend fun callActivate(
        target: AccountStartupOperations,
        expected: RecoveryRoomSnapshot,
        stageId: String,
        connectionRestored: Boolean = false,
    ): AppSessionState {
        try {
            return if (connectionRestored) target.completeActivationAfterReconnect(expected, stageId)
            else target.completeActivation(expected, stageId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccountStartupException) {
            throw e
        } catch (e: AppSessionStaleException) {
            throw AccountStartupException(AccountStartupError.CANCELLED)
        } catch (e: AppSessionException) {
            throw AccountStartupException(mapReason(e.reason))
        } catch (_: Exception) {
            throw AccountStartupException(AccountStartupError.UNAVAILABLE)
        }
    }

    private fun mapReason(reason: AppSessionReason): AccountStartupError =
        when (reason) {
            AppSessionReason.CONFIGURATION_UNAVAILABLE -> AccountStartupError.UNAVAILABLE
            AppSessionReason.IDENTITY_MISMATCH -> AccountStartupError.IDENTITY_MISMATCH
            AppSessionReason.SIGN_IN_REQUIRED -> AccountStartupError.SIGN_IN_FAILED
            AppSessionReason.PREPARATION_REQUIRED,
            AppSessionReason.LOCAL_DATA_REQUIRES_DECISION,
            AppSessionReason.UNAVAILABLE,
            -> AccountStartupError.PREPARATION_PAUSED
        }

    private fun isScopeAlive(): Boolean {
        val job = scope.coroutineContext[Job] ?: return true
        return job.isActive
    }

    private fun isCurrent(gen: Long): Boolean =
        synchronized(lock) { !disposed && gen == generation }

    private suspend fun checkCurrent(gen: Long): Boolean {
        currentCoroutineContext().ensureActive()
        return isCurrent(gen)
    }

    /**
     * Registers an already-created lazy owned job before start, replacing any
     * live predecessor. Returns false when a newer generation or disposal owns
     * the slot; the caller releases occupation and never starts the job.
     */
    private fun registerOwned(gen: Long, job: Job): Boolean {
        synchronized(lock) {
            if (disposed || gen != generation) {
                job.cancel()
                return false
            }
            currentJob?.cancel()
            currentJob = job
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                if (currentJob === job) {
                    currentJob = null
                }
            }
            // A job cancelled before its lazy body runs never reaches any
            // terminal presentation path, so owned busy would stick. Settle it
            // here, fenced to the owning generation: ran bodies already
            // cleared busy themselves, and a stale generation (newer slot or
            // disposal) cannot overwrite newer presentation.
            synchronized(lock) {
                if (!disposed && gen == generation && _uiState.value.busy) {
                    _uiState.value =
                        _uiState.value.copy(busy = false, error = AccountStartupError.CANCELLED)
                }
            }
            // Covers cancellation before the body runs, where the body
            // finally never executes. Idempotent with that finally.
            releaseIfCurrent(gen)
        }
        return true
    }

    private fun releaseIfCurrent(gen: Long) {
        synchronized(lock) {
            if (disposed || gen != generation) return
            occupied = false
        }
    }

    private fun setBusyIfCurrent(gen: Long, busy: Boolean, clearError: Boolean) {
        synchronized(lock) {
            if (disposed || gen != generation) return
            val current = _uiState.value
            _uiState.value = current.copy(busy = busy, error = if (clearError) null else current.error)
        }
    }

    private fun finishIfCurrent(gen: Long, error: AccountStartupError?) {
        synchronized(lock) {
            if (disposed || gen != generation) return
            _uiState.value = _uiState.value.copy(busy = false, error = error)
        }
    }

    private fun updateImportIfCurrent(gen: Long, progress: LegacyImportProgress?) {
        synchronized(lock) {
            if (disposed || gen != generation) return
            _uiState.value = _uiState.value.copy(importProgress = progress)
        }
    }

    /**
     * Owned cancellation settling: fences to the owning generation only, marks
     * the presentation cancelled, and starts no follow-up work.
     */
    private fun settleCancelled(gen: Long) {
        synchronized(lock) {
            if (disposed || gen != generation) return
            _uiState.value = _uiState.value.copy(busy = false, error = AccountStartupError.CANCELLED)
        }
    }

    /**
     * Single authority supervisor. Follows facade state for the process
     * lifetime: clears retired status on private authority, and clears the old
     * DTO before any new-handle collector can emit. Cancelled with disposal.
     */
    private fun ensureAuthorityWatcher(target: AccountStartupOperations) {
        val job = synchronized(lock) {
            if (disposed || !isScopeAlive()) return
            if (authorityJob?.isActive == true) return
            authorityJob?.cancel()
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    target.state.collect { snapshot ->
                        currentCoroutineContext().ensureActive()
                        if (snapshot is AppSessionState.Active) {
                            val handle = snapshot.session
                            val adopted = synchronized(lock) {
                                if (disposed) return@collect
                                if (!handle.isAlive()) return@collect
                                if (statusHandle === handle && statusJob?.isActive == true) return@collect
                                _uiState.value = _uiState.value.copy(syncStatus = null)
                                statusJob?.cancel()
                                statusGeneration += 1
                                statusHandle = handle
                                statusGeneration
                            }
                            launchStatusCollector(target, handle, adopted)
                        } else {
                            synchronized(lock) {
                                if (disposed) return@collect
                                statusJob?.cancel()
                                statusJob = null
                                statusHandle = null
                                statusGeneration += 1
                                _uiState.value = _uiState.value.copy(syncStatus = null)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
        synchronized(lock) {
            if (disposed) {
                job.cancel()
                return
            }
            authorityJob = job
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                if (authorityJob === job) {
                    authorityJob = null
                }
            }
        }
        job.start()
    }

    private fun ensureStatusCollector(gen: Long, handle: AppAccountSession) {
        val prepared = synchronized(lock) {
            if (disposed || gen != generation) return
            if (operations == null || !isScopeAlive()) return
            if (statusHandle === handle && statusJob?.isActive == true) return
            _uiState.value = _uiState.value.copy(syncStatus = null)
            statusJob?.cancel()
            statusGeneration += 1
            statusHandle = handle
            statusGeneration
        }
        val target = synchronized(lock) {
            if (disposed || gen != generation) return
            operations
        } ?: return
        launchStatusCollector(target, handle, prepared)
    }

    private fun launchStatusCollector(
        target: AccountStartupOperations,
        handle: AppAccountSession,
        statusGen: Long,
    ) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                target.observeSyncStatus(handle).collect { status ->
                    currentCoroutineContext().ensureActive()
                    synchronized(lock) {
                        if (disposed || statusGen != statusGeneration || statusHandle !== handle) return@collect
                        val live = target.state.value
                        if (live !is AppSessionState.Active || live.session !== handle) return@collect
                        if (!handle.isAlive()) return@collect
                        _uiState.value = _uiState.value.copy(syncStatus = status)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                synchronized(lock) {
                    if (disposed || statusGen != statusGeneration || statusHandle !== handle) return@launch
                    _uiState.value = _uiState.value.copy(syncStatus = null)
                }
            }
        }
        synchronized(lock) {
            if (disposed || statusGen != statusGeneration || statusHandle !== handle) {
                job.cancel()
                return
            }
            statusJob?.cancel()
            statusJob = job
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                if (statusJob === job) {
                    statusJob = null
                }
            }
        }
        job.start()
    }
}
