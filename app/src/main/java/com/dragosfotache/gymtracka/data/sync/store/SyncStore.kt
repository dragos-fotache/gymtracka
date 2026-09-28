package com.dragosfotache.gymtracka.data.sync.store

import androidx.room.withTransaction
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.SyncOutboxEntity
import com.dragosfotache.gymtracka.data.sync.codec.captureDurableRequest
import com.dragosfotache.gymtracka.data.sync.codec.encodeDurableRequest
import com.dragosfotache.gymtracka.data.sync.model.AffectedRootModel
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.DURABLE_PAYLOAD_VERSION
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.MutationEnvelopeModel
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.UnsupportedDurableVersionException
import com.dragosfotache.gymtracka.data.sync.model.validateMutationEnvelope
import java.util.UUID

/** An additional affected root whose metadata advances with the captured edit. */
data class ExtraAffectedRoot(val type: AggregateType, val id: String)

/** Outcome of one capture call. Null request means no durable work was captured. */
data class CaptureOutcome(
    val request: DurableRequestModel?,
    val coalesced: Boolean,
    val sequence: Long?,
)

/**
 * Application sync entry point for fenced capture. Owns the database transaction and
 * checks the device identity INSIDE it before any scoped reads or writes; a mismatch
 * throws [StaleSessionException] and changes nothing. No network runs in transactions.
 *
 * Reference capture sequence per call: the caller edits the domain first and calls
 * capture only for a real change; metadata is loaded and verified; signed-out edits
 * persist local generations without touching UUID/payload factories; account edits
 * inspect the global tail, reuse a qualifying tail mutation ID or mint a fresh one,
 * build the typed envelope with that exact ID, capture the durable v1 wrapper with
 * all computed effects, and replace the qualifying tail wrapper or append a new row.
 * Any failure rolls back the domain edit, all metadata, and outbox together.
 *
 * @param newMutationId stable ID supplier for appended outbox rows. Defaults to
 * production random UUIDs; tests inject deterministic values (still valid UUIDs).
 * It is never invoked for no-ops, signed-out edits, or coalesced replacements.
 */
class SyncStore(
    private val database: GymDatabase,
    private val session: RepositorySession = RepositorySession.SIGNED_OUT,
    private val newMutationId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun <T> withFencedTransaction(block: suspend (SyncTransaction) -> T): T =
        database.withTransaction {
            val device = database.syncDao().getDeviceState()
            val actualAccountId = device?.activeAccountId
            val actualGeneration = device?.sessionGeneration ?: 0L
            if (actualAccountId != session.accountId ||
                actualGeneration != session.sessionGeneration
            ) {
                throw StaleSessionException(
                    session.accountId,
                    session.sessionGeneration,
                    actualAccountId,
                    actualGeneration,
                )
            }
            val transaction = SyncTransaction(database, session.accountId, newMutationId)
            try {
                block(transaction)
            } finally {
                transaction.close()
            }
        }
}

private data class RootState(
    val type: AggregateType,
    val id: String,
    val accountId: String?,
    val serverRevision: Long?,
    val generation: Long,
    val mutable: Boolean,
    val deleted: Boolean,
)

/**
 * Capture operations available inside one fenced transaction. Instances are created
 * only by [SyncStore.withFencedTransaction]; use outside an active database
 * transaction, or after the transaction closes, is rejected, so capture can never
 * run outside its fence.
 */
class SyncTransaction internal constructor(
    private val database: GymDatabase,
    val accountId: String?,
    private val newMutationId: () -> String,
) {
    private var open = true

    internal fun close() {
        open = false
    }

    private fun requireActive() {
        if (!open) throw IllegalStateException("SyncTransaction used outside its fenced transaction")
        if (!database.inTransaction()) {
            throw IllegalStateException("Capture requires an active database transaction")
        }
    }

    private suspend fun loadRoot(type: AggregateType, id: String): RootState {
        val capture = database.syncCaptureDao()
        return when (type) {
            AggregateType.WORKOUT -> {
                val metadata = capture.getWorkoutMetadata(id)
                    ?: throw SyncModelException("Workout $id not found for capture")
                RootState(type, id, metadata.accountId, metadata.serverRevision, metadata.localGeneration, true, metadata.deletedAtMillis != null)
            }
            AggregateType.ROUTINE -> {
                val metadata = capture.getRoutineMetadata(id)
                    ?: throw SyncModelException("Routine $id not found for capture")
                RootState(type, id, metadata.accountId, metadata.serverRevision, metadata.localGeneration, true, metadata.deletedAtMillis != null)
            }
            AggregateType.CUSTOM_EXERCISE -> {
                val metadata = capture.getExerciseMetadata(id)
                    ?: throw SyncModelException("Exercise $id not found for capture")
                RootState(type, id, metadata.accountId, metadata.serverRevision, metadata.localGeneration, metadata.isUserCreated, metadata.deletedAtMillis != null)
            }
        }
    }

    /**
     * Domain tombstones are allowed for an aggregate.delete target and for old-active
     * deletion effects; ordinary autosaves of tombstoned roots stay forbidden. Bundled
     * rows, wrong owners, negative generations, and overflows always reject.
     */
    private fun checkCapturable(root: RootState, allowTombstone: Boolean) {
        if (root.deleted && !allowTombstone) {
            throw SyncModelException("Deleted aggregate ${root.id} cannot be captured")
        }
        if (!root.mutable) throw SyncModelException("Bundled exercise ${root.id} is immutable")
        if (root.accountId != accountId) {
            throw SyncModelException("Aggregate ${root.id} belongs to a different account")
        }
        if (root.generation < 0) throw SyncModelException("Aggregate ${root.id} has a negative generation")
        if (root.generation == Long.MAX_VALUE) {
            throw SyncModelException("Aggregate ${root.id} generation overflow")
        }
    }

    private suspend fun advanceMetadata(root: RootState, generation: Long) {
        val state = if (accountId == null) "local_only" else "pending"
        val capture = database.syncCaptureDao()
        when (root.type) {
            AggregateType.WORKOUT -> capture.updateWorkoutMetadata(root.id, generation, state)
            AggregateType.ROUTINE -> capture.updateRoutineMetadata(root.id, generation, state)
            AggregateType.CUSTOM_EXERCISE -> capture.updateExerciseMetadata(root.id, generation, state)
        }
    }

    /**
     * Captures the caller's domain edit, which must already have run inside this same
     * transaction. When [changed] is false nothing happens and null is returned. A
     * changed root advances its generation exactly once and is marked pending (or
     * local_only when signed out); signed-out captures create no outbox work and never
     * evaluate [newPayload], so legacy arbitrary IDs need no DTO or UUID validation.
     * Account captures reuse a qualifying tail mutation ID or mint a fresh one, build
     * the typed envelope with that exact ID, and persist the durable v1 wrapper JSON
     * (never the bare mutation) by tail replacement or fresh append.
     */
    suspend fun captureEdit(
        rootType: AggregateType,
        rootId: String,
        kind: MutationKind,
        changed: Boolean,
        extraRoots: List<ExtraAffectedRoot> = emptyList(),
        newPayload: () -> MutationPayload,
    ): CaptureOutcome? {
        requireActive()
        if (!changed) return null
        if (kind == MutationKind.WORKOUT_UPSERT && rootType != AggregateType.WORKOUT ||
            kind == MutationKind.ROUTINE_UPSERT && rootType != AggregateType.ROUTINE ||
            kind == MutationKind.CUSTOM_EXERCISE_UPSERT && rootType != AggregateType.CUSTOM_EXERCISE
        ) {
            throw SyncModelException("Autosave kind does not match captured root type")
        }

        val main = loadRoot(rootType, rootId)
        checkCapturable(main, allowTombstone = kind == MutationKind.AGGREGATE_DELETE)
        val extras = extraRoots.map { loadRoot(it.type, it.id) }
        extras.forEach { checkCapturable(it, allowTombstone = true) }
        val keys = listOf(rootType to rootId) + extras.map { it.type to it.id }
        if (keys.toSet().size != keys.size) {
            throw SyncModelException("Capture roots contain a duplicate aggregate")
        }

        val newGeneration = main.generation + 1
        advanceMetadata(main, newGeneration)
        val extraGenerations = extras.map { extra ->
            val generation = extra.generation + 1
            advanceMetadata(extra, generation)
            AffectedRootModel(extra.type, extra.id, generation)
        }

        val account = accountId ?: return CaptureOutcome(null, coalesced = false, sequence = null)

        val capture = database.syncCaptureDao()
        val coalescibleKind = kind == MutationKind.WORKOUT_UPSERT ||
            kind == MutationKind.ROUTINE_UPSERT ||
            kind == MutationKind.CUSTOM_EXERCISE_UPSERT
        var mutationId: String? = null
        var tailSequence = -1L
        if (coalescibleKind && extraRoots.isEmpty()) {
            val tail = capture.getOutboxTail(account)
            if (tail != null &&
                tail.state == "pending" &&
                tail.attemptCount == 0 &&
                tail.aggregateType == rootType.wireValue &&
                tail.aggregateId == rootId &&
                tail.mutationType == kind.wireValue &&
                tail.localGeneration < newGeneration
            ) {
                val inspected = try {
                    readDurableOutboxRow(tail)
                } catch (_: SyncModelException) {
                    null
                } catch (_: UnsupportedDurableVersionException) {
                    null
                }
                val effects = inspected?.request?.affectedRoots.orEmpty()
                if (effects.size == 1 &&
                    effects[0].aggregateType == rootType &&
                    effects[0].aggregateId == rootId &&
                    effects[0].localGeneration == tail.localGeneration
                ) {
                    mutationId = tail.mutationId
                    tailSequence = tail.sequence
                }
            }
        }

        val coalesced = mutationId != null
        val envelope = validateMutationEnvelope(
            MutationEnvelopeModel(
                mutationId = mutationId ?: newMutationId(),
                kind = kind,
                aggregateType = rootType,
                aggregateId = rootId,
                localGeneration = newGeneration,
                knownServerRevision = main.serverRevision,
                payload = newPayload(),
            ),
        )
        val affectedRoots =
            listOf(AffectedRootModel(rootType, rootId, newGeneration)) + extraGenerations
        val request = captureDurableRequest(envelope, affectedRoots)
        val payloadJson = encodeDurableRequest(request).toString()

        val sequence = if (coalesced) {
            val updated = capture.replaceTailPayload(
                request.envelope.mutationId,
                account,
                tailSequence,
                payloadJson,
                DURABLE_PAYLOAD_VERSION,
                newGeneration,
            )
            if (updated != 1) throw IllegalStateException("Outbox tail changed during capture")
            tailSequence
        } else {
            database.syncDao().insertOutbox(
                SyncOutboxEntity(
                    mutationId = request.envelope.mutationId,
                    accountId = account,
                    aggregateType = rootType.wireValue,
                    aggregateId = rootId,
                    mutationType = kind.wireValue,
                    localGeneration = newGeneration,
                    payloadVersion = DURABLE_PAYLOAD_VERSION,
                    payloadJson = payloadJson,
                    state = "pending",
                ),
            )
        }
        return CaptureOutcome(request, coalesced, sequence)
    }
}
