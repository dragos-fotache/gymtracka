package com.dragosfotache.gymtracka.data.sync.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Encrypted, revision-checked session vault over caller-supplied storage and
 * cipher seams. Unused storage boundary: no network, rotation, Room, or
 * activation logic lives here.
 *
 * Every operation re-reads committed disk state under the mutex; there is no
 * success cache and no implicit repair. All codec, crypto, and file work runs
 * on the injected IO dispatcher. Compare-and-set decrypts and validates the
 * committed snapshot, compares the exact expected revision, increments once
 * with a checked Long overflow, then commits one full snapshot (encode,
 * encrypt, durable replace) inside a single IO-scoped block, returning only
 * after the durable write succeeds. Clearing writes a positive-revision
 * encrypted tombstone so a delayed old revision can never reinstall
 * credentials.
 *
 * Refusals (invalid expected revision, revision mismatch, revision overflow)
 * perform no write and no crypto-key creation, and activity is checked before
 * the refusals as well as before starting a write, so cancellation during a
 * successful seal never starts the replacement. Read failures stay closed and
 * preserve the committed file: corrupted or invalid contents fail, and a
 * missing key never triggers regeneration. Arbitrary storage/key seam errors
 * map safe to unavailable; explicit cipher failures stay as reported while
 * malformed decode stays unreadable.
 *
 * Failures are captured as results inside the IO context and the original
 * failure is thrown outside it, so cancellation identity never crosses the
 * context recovery: every IO invocation additionally rechecks the actual
 * caller context in an outer catch, so an originally canceled caller
 * dominates even when the context call itself throws a recovery copy, and an
 * injected cancellation otherwise stays the identical object. No
 * cause-unwrapping, message-only oracles, recovery disabling, or internal
 * APIs are used. A cancellation racing a synchronous commit may still leave
 * the durable new state behind, which the caller must reload rather than
 * assume rolled back. Owned plaintext and ciphertext copies never leave the
 * IO block and are cleared around all exits, including the pre-write activity
 * check; seam-returned buffers are copied before passing onward so no
 * storage/cipher retained view is ever mutated or cleared. Immutable strings,
 * GC behavior, and flash secure erasure are not claimed.
 */
internal class EncryptedSessionVault(
    private val storage: VaultFileStorage,
    private val cipher: VaultCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mutex: Mutex = Mutex(),
) : SessionVault {
    override suspend fun load(): VaultSnapshot =
        mutex.withLock {
            ioRun { readCommitted() }.orThrowClosed(VaultFailure.UNREADABLE)
        }

    override suspend fun compareAndSet(expectedRevision: Long, record: VaultSessionRecord?): VaultSnapshot {
        currentCoroutineContext().ensureActive()
        if (expectedRevision < 0L) {
            throw SessionVaultException(VaultFailure.INVALID_RECORD)
        }
        return mutex.withLock {
            val current = ioRun { readCommitted() }.orThrowClosed(VaultFailure.UNREADABLE)
            if (current.revision != expectedRevision) {
                throw SessionVaultException(VaultFailure.CONFLICT)
            }
            if (expectedRevision == Long.MAX_VALUE) {
                throw SessionVaultException(VaultFailure.REVISION_EXHAUSTED)
            }
            validateArchiveTransition(current, record)
            val next = VaultSnapshot(expectedRevision + 1L, record)
            ioRun { commitSnapshot(next) }.orThrowClosed(VaultFailure.UNAVAILABLE)
            next
        }
    }

    /**
     * Archive preservation fence for every CAS write. Rejects removal,
     * reordering or modification of already archived entries, clearing a
     * nonempty archive via a null record, and reinstalling archived-session
     * credentials (enforced by record/snapshot validation). A single archive
     * growth step is allowed only when archiving the exact uncertain source
     * being replaced: the current record must carry pending, the new binding
     * or operation must differ, and the appended entry must equal the
     * current revision plus the current record stripped of its archive.
     * Legacy primitive null CAS over an archive-empty record remains allowed
     * as established clearing behavior. All refusals perform no write and no
     * crypto-key creation; stale/overflow callers already returned above.
     */
    private fun validateArchiveTransition(current: VaultSnapshot, next: VaultSessionRecord?) {
        val currentRecord = current.record ?: return
        if (next == null) {
            if (currentRecord.supersededSessions.entries.isNotEmpty()) {
                throw SessionVaultException(VaultFailure.INVALID_RECORD)
            }
            return
        }
        val oldArchive = currentRecord.supersededSessions.entries
        val newArchive = next.supersededSessions.entries
        if (newArchive.size < oldArchive.size) {
            throw SessionVaultException(VaultFailure.INVALID_RECORD)
        }
        for (index in oldArchive.indices) {
            if (newArchive[index] != oldArchive[index]) {
                throw SessionVaultException(VaultFailure.INVALID_RECORD)
            }
        }
        // A binding/operation-changing replacement of an uncertain source
        // must append its exact evidence: same-size archives would silently
        // discard it. An ordinary same-binding refresh legitimately carries
        // the archive unchanged while clearing pending.
        val replacesBinding = next.binding != currentRecord.binding ||
            next.operationId != currentRecord.operationId
        if (currentRecord.pendingRefresh != null && replacesBinding) {
            if (newArchive.size != oldArchive.size + 1) {
                throw SessionVaultException(VaultFailure.INVALID_RECORD)
            }
        } else if (newArchive.size == oldArchive.size) {
            return
        }
        if (newArchive.size != oldArchive.size + 1) {
            throw SessionVaultException(VaultFailure.INVALID_RECORD)
        }
        if (currentRecord.pendingRefresh == null) {
            throw SessionVaultException(VaultFailure.INVALID_RECORD)
        }
        if (next.binding == currentRecord.binding && next.operationId == currentRecord.operationId) {
            throw SessionVaultException(VaultFailure.INVALID_RECORD)
        }
        val appended = newArchive[oldArchive.size]
        if (appended.sourceRevision != current.revision) {
            throw SessionVaultException(VaultFailure.INVALID_RECORD)
        }
        val stripped = try {
            currentRecord.copy(supersededSessions = VaultSupersededSessions(emptyList()))
        } catch (e: Exception) {
            throw SessionVaultException(VaultFailure.INVALID_RECORD)
        }
        if (!vaultRecordsSemanticEqual(appended.record, stripped)) {
            throw SessionVaultException(VaultFailure.INVALID_RECORD)
        }
    }

    private suspend fun <T> ioRun(block: suspend () -> T): Result<T> {
        try {
            return withContext(ioDispatcher) {
                try {
                    Result.success(block())
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (e is CancellationException) {
                throw e
            }
            throw (e as? SessionVaultException) ?: SessionVaultException(VaultFailure.UNAVAILABLE)
        }
    }

    private suspend fun <T> Result<T>.orThrowClosed(failure: VaultFailure): T {
        currentCoroutineContext().ensureActive()
        val error = exceptionOrNull()
        if (error != null) {
            if (error is CancellationException) {
                throw error
            }
            throw (error as? SessionVaultException) ?: SessionVaultException(failure)
        }
        return getOrThrow()
    }

    private suspend fun commitSnapshot(next: VaultSnapshot) {
        val plaintext = try {
            VaultRecordCodec.encode(next)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw (e as? SessionVaultException) ?: SessionVaultException(VaultFailure.INVALID_RECORD)
        }
        try {
            val sealedBytes = try {
                cipher.seal(plaintext).copyOf()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw (e as? SessionVaultException) ?: SessionVaultException(VaultFailure.UNAVAILABLE)
            }
            try {
                // The block context mirrors caller cancellation; the outer catch
                // re-establishes the actual caller cause at the boundary.
                currentCoroutineContext().ensureActive()
                try {
                    storage.replace(sealedBytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw (e as? SessionVaultException) ?: SessionVaultException(VaultFailure.UNAVAILABLE)
                }
            } finally {
                sealedBytes.fill(0)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    private fun readCommitted(): VaultSnapshot {
        val committed = try {
            storage.read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw (e as? SessionVaultException) ?: SessionVaultException(VaultFailure.UNAVAILABLE)
        }
        if (committed == null) {
            return VaultSnapshot(0L, null)
        }
        val owned = committed.copyOf()
        try {
            return openCommitted(owned)
        } finally {
            owned.fill(0)
        }
    }

    private fun openCommitted(ownedSealed: ByteArray): VaultSnapshot {
        val owned = try {
            cipher.open(ownedSealed).copyOf()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw (e as? SessionVaultException) ?: SessionVaultException(VaultFailure.UNAVAILABLE)
        }
        try {
            return VaultRecordCodec.decode(owned)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw (e as? SessionVaultException) ?: SessionVaultException(VaultFailure.UNREADABLE)
        } finally {
            owned.fill(0)
        }
    }
}
