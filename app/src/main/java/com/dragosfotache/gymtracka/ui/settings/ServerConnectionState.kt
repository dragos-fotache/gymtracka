package com.dragosfotache.gymtracka.ui.settings

import com.dragosfotache.gymtracka.data.sync.api.ServerChecker
import com.dragosfotache.gymtracka.data.sync.api.ServerCheckResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal enum class ServerConnectionStatus {
    NOT_CHECKED,
    CHECKING,
    CONNECTED,
    UNREACHABLE,
    SERVER_ERROR,
    UNEXPECTED_RESPONSE,
}

/**
 * Composition-owned advisory checks in foreground Settings or deferred private activation. A cancelled job
 * retains the slot until it actually exits; a new entry queues one fresh check after retirement.
 * Generations and cancellation fence late results without cancelling the borrowed scope.
 */
internal class ServerConnectionState(
    private val checker: ServerChecker,
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = 5_000L,
) {
    private val lock = Any()
    private var active = false
    private var disposed = false
    private var generation = 0L
    private var requested = false
    private var job: Job? = null
    private val _status = MutableStateFlow(ServerConnectionStatus.NOT_CHECKED)
    val status: StateFlow<ServerConnectionStatus> = _status.asStateFlow()
    private var observedUnreachable = false
    private val _reconnections = MutableStateFlow(0L)
    val reconnections: StateFlow<Long> = _reconnections.asStateFlow()

    init {
        require(timeoutMillis > 0)
        scope.coroutineContext[Job]?.invokeOnCompletion { dispose() }
    }

    /** True only while a connection section is visible and its lifecycle is at least STARTED. */
    fun setActive(value: Boolean): Unit = synchronized(lock) {
        if (disposed || active == value) return
        active = value
        generation += 1
        requested = value
        _status.value = if (value) ServerConnectionStatus.CHECKING else ServerConnectionStatus.NOT_CHECKED
        if (value) startLocked() else job?.cancel()
    }

    /** Duplicate presses and checks outside foreground Settings are no-ops. */
    fun recheck(): Unit = synchronized(lock) {
        if (disposed || !active || job != null) return
        requested = true
        startLocked()
    }

    /** A retryable sync failure invalidates any older probe and queues one fresh check. */
    fun refreshAfterSyncFailure(): Unit = synchronized(lock) {
        if (disposed || !active) return
        generation += 1
        requested = true
        _status.value = ServerConnectionStatus.CHECKING
        if (job == null) startLocked() else job?.cancel()
    }

    fun dispose(): Unit = synchronized(lock) {
        if (disposed) return
        disposed = true
        active = false
        requested = false
        generation += 1
        _status.value = ServerConnectionStatus.NOT_CHECKED
        observedUnreachable = false
        job?.cancel()
    }

    private fun startLocked() {
        if (disposed || !active || !requested || job != null) return
        if (scope.coroutineContext[Job]?.isActive == false) return
        requested = false
        generation += 1
        val captured = generation
        _status.value = ServerConnectionStatus.CHECKING
        val next = scope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                withTimeoutOrNull(timeoutMillis) { checker.check() } ?: ServerCheckResult.UNREACHABLE
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ServerCheckResult.UNEXPECTED_RESPONSE
            }
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (!disposed && active && generation == captured) {
                    _status.value = when (result) {
                        ServerCheckResult.CONNECTED -> ServerConnectionStatus.CONNECTED
                        ServerCheckResult.UNREACHABLE -> ServerConnectionStatus.UNREACHABLE
                        ServerCheckResult.SERVER_ERROR -> ServerConnectionStatus.SERVER_ERROR
                        ServerCheckResult.UNEXPECTED_RESPONSE -> ServerConnectionStatus.UNEXPECTED_RESPONSE
                    }
                    if (result == ServerCheckResult.UNREACHABLE) observedUnreachable = true
                    if (result == ServerCheckResult.CONNECTED && observedUnreachable) {
                        observedUnreachable = false
                        if (_reconnections.value < Long.MAX_VALUE) _reconnections.value += 1L
                    }
                }
            }
        }
        job = next
        next.invokeOnCompletion {
            synchronized(lock) {
                if (job === next) {
                    job = null
                    if (active && !disposed && generation == captured && next.isCancelled) {
                        _status.value = ServerConnectionStatus.NOT_CHECKED
                    }
                    startLocked()
                }
            }
        }
        next.start()
    }
}
