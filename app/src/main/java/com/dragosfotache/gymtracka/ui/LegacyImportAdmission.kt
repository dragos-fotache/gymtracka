package com.dragosfotache.gymtracka.ui

import com.dragosfotache.gymtracka.data.sync.session.AppSessionTransitionBarrier
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationLease

/**
 * Real legacy-import transition adapter over one live local app state.
 * Composes the owner-dispatched checked seam: editor/process checks run on
 * the existing app-state owner dispatcher before pause/drain and after the
 * terminal drains before lease dispatch. An open explicit-save custom editor
 * refuses without autosaving, discarding or hiding its draft; the caller
 * resolves Save/Discard/Cancel through the editor UI and retries. Terminal
 * failure evidence is preserved. Never uses the
 * dormant fallback to drain an existing live app.
 */
internal fun GymAppState.legacyImportTransitionBarrier(
    processCheck: () -> Unit,
): AppSessionTransitionBarrier {
    val refuseEditor: () -> Unit = {
        if (destination == Destination.CreateExercise ||
            destination == Destination.EditCustomExercise
        ) {
            throw IllegalStateException(
                "Explicit-save custom editor is open; refusing legacy transition",
            )
        }
    }
    val delegate = transitionBarrierWithChecks(
        beforeDrain = {
            processCheck()
            refuseEditor()
        },
        afterDrain = {
            processCheck()
            refuseEditor()
        },
    )
    return AppSessionTransitionBarrier { previous ->
        LegacyImportTrackingLease(delegate.acquire(previous), processCheck)
    }
}

private class LegacyImportTrackingLease(
    private val delegate: ProjectionPublicationLease,
    private val processCheck: () -> Unit,
) : ProjectionPublicationLease {
    private val lock = Any()
    private var closed = false

    override fun checkValid() {
        synchronized(lock) {
            if (closed) {
                throw com.dragosfotache.gymtracka.data.sync.store.ProjectionLeaseInvalidException()
            }
        }
        processCheck()
        delegate.checkValid()
    }

    override fun close() {
        val runClose: Boolean = synchronized(lock) {
            if (closed) return
            closed = true
            true
        }
        if (runClose) {
            delegate.close()
        }
    }

    override fun toString(): String = "LegacyImportTrackingLease(redacted)"
}
