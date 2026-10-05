package com.dragosfotache.gymtracka.data.sync.store

import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.codec.decodeDurableRequest
import com.dragosfotache.gymtracka.data.sync.codec.parseStrictJsonObject
import com.dragosfotache.gymtracka.data.sync.model.AffectedRootModel
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationOutcomeModel
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
import com.dragosfotache.gymtracka.data.sync.model.SyncBatchModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import org.json.JSONException

/**
 * Owns and revalidates one accepted snapshot against its claim before any
 * suspension or write. Requires private origin provenance (account plus exact
 * original frozen row identity, excluding attempt/retry bookkeeping and live
 * run/session/epoch), the exact claimed mutation ID, accepted or
 * duplicate-accepted status, no error, a full valid batch, and the correct
 * envelope target operation (DELETE for aggregate.delete, UPSERT otherwise).
 * Any mismatch throws the fixed binding error without consuming the claim.
 */
internal fun validatedAcceptedBatch(
    claim: PushClaim,
    accepted: PushOutcomeResult.Accepted,
): SyncBatchModel {
    if (!accepted.matchesClaim(claim.accountId, claim.row)) {
        throw IllegalArgumentException(ACCEPTED_CLAIM_MISMATCH_MESSAGE)
    }
    val owned = try {
        accepted.outcome
    } catch (_: SyncModelException) {
        throw IllegalArgumentException(ACCEPTED_CLAIM_MISMATCH_MESSAGE)
    } catch (_: JSONException) {
        throw IllegalArgumentException(ACCEPTED_CLAIM_MISMATCH_MESSAGE)
    }
    if (owned.mutationId != claim.row.mutationId) {
        throw IllegalArgumentException(ACCEPTED_CLAIM_MISMATCH_MESSAGE)
    }
    when (owned.status) {
        MutationStatus.ACCEPTED, MutationStatus.DUPLICATE -> Unit
        MutationStatus.REJECTED -> throw IllegalArgumentException(ACCEPTED_CLAIM_MISMATCH_MESSAGE)
    }
    if (owned.errorCode != null) {
        throw IllegalArgumentException(ACCEPTED_CLAIM_MISMATCH_MESSAGE)
    }
    val batch = owned.batch
        ?: throw IllegalArgumentException(ACCEPTED_CLAIM_MISMATCH_MESSAGE)
    val ownedBatch = try {
        ownedCanonicalBatch(batch)
    } catch (_: SyncModelException) {
        throw IllegalArgumentException(ACCEPTED_CLAIM_MISMATCH_MESSAGE)
    }
    if (!acknowledgeTargetValid(claim, owned)) {
        throw IllegalArgumentException(ACCEPTED_CLAIM_MISMATCH_MESSAGE)
    }
    return ownedBatch
}

/**
 * Pure envelope-target check for acknowledgment binding. The batch must contain
 * the claimed envelope target; operation is DELETE for aggregate.delete,
 * otherwise UPSERT. Unreadable frozen bytes fail closed as a binding mismatch.
 */
private fun acknowledgeTargetValid(claim: PushClaim, owned: MutationOutcomeModel): Boolean {
    val batch = owned.batch ?: return false
    if (owned.errorCode != null) {
        return false
    }
    val envelope = try {
        decodeDurableRequest(parseStrictJsonObject(claim.row.payloadJson)).envelope
    } catch (_: SyncModelException) {
        return false
    } catch (_: JSONException) {
        return false
    }
    val target = batch.changes.firstOrNull {
        it.aggregateType == envelope.aggregateType && it.aggregateId == envelope.aggregateId
    } ?: return false
    val expected = if (envelope.kind == MutationKind.AGGREGATE_DELETE) {
        ChangeOperation.DELETE
    } else {
        ChangeOperation.UPSERT
    }
    return target.operation == expected
}

/** Physical metadata snapshot retained for post-write tamper rechecks. */
internal data class AckPhysicalSnapshot(
    val accountId: String?,
    val serverRevision: Long?,
    val localGeneration: Long,
    val syncState: String,
    val deletedAtMillis: Long?,
    val isUserCreated: Boolean?,
)

/** Validated per-root acknowledgment evidence for one frozen affected root. */
internal data class AckRootEvidence(
    val root: AffectedRootModel,
    val watermark: Long,
    val before: AckPhysicalSnapshot?,
    val atFrozenGeneration: Boolean,
)

/** Validated local acknowledgment context for all frozen affected roots. */
internal sealed class AckContext {
    data class Valid(val roots: List<AckRootEvidence>) : AckContext()
    data object Invalid : AckContext()
}

/**
 * Validates every frozen local affected root inside the caller's active fenced
 * transaction. Each account/type/ID ledger reservation must exist with a
 * nonnegative watermark at or above the frozen generation. An existing physical
 * root must belong to the selected account (customs must be user-created), carry
 * a local generation at or above frozen, a positive-or-null server revision and
 * `sync_state='pending'`, with its ledger watermark also at or above the
 * physical generation. A vanished physical root stays valid on independent
 * reservation evidence; nothing is fabricated or reinserted. Reads only.
 */
internal suspend fun validateAckContext(
    database: GymDatabase,
    accountId: String,
    roots: List<AffectedRootModel>,
): AckContext {
    val canonicalDao = database.syncCanonicalDao()
    val captureDao = database.syncCaptureDao()
    val evidences = ArrayList<AckRootEvidence>(roots.size)
    for (root in roots) {
        val frozen = root.localGeneration
        if (frozen < 0) {
            return AckContext.Invalid
        }
        val reservation = canonicalDao.getRoot(
            accountId,
            root.aggregateType.wireValue,
            root.aggregateId,
        ) ?: return AckContext.Invalid
        val watermark = reservation.localGenerationWatermark
        if (watermark < 0 || watermark < frozen) {
            return AckContext.Invalid
        }
        val before = when (root.aggregateType) {
            AggregateType.WORKOUT -> captureDao.getWorkoutMetadata(root.aggregateId)?.let {
                AckPhysicalSnapshot(
                    accountId = it.accountId,
                    serverRevision = it.serverRevision,
                    localGeneration = it.localGeneration,
                    syncState = it.syncState,
                    deletedAtMillis = it.deletedAtMillis,
                    isUserCreated = null,
                )
            }
            AggregateType.ROUTINE -> captureDao.getRoutineMetadata(root.aggregateId)?.let {
                AckPhysicalSnapshot(
                    accountId = it.accountId,
                    serverRevision = it.serverRevision,
                    localGeneration = it.localGeneration,
                    syncState = it.syncState,
                    deletedAtMillis = it.deletedAtMillis,
                    isUserCreated = null,
                )
            }
            AggregateType.CUSTOM_EXERCISE -> captureDao.getExerciseMetadata(root.aggregateId)?.let {
                AckPhysicalSnapshot(
                    accountId = it.accountId,
                    serverRevision = it.serverRevision,
                    localGeneration = it.localGeneration,
                    syncState = it.syncState,
                    deletedAtMillis = it.deletedAtMillis,
                    isUserCreated = it.isUserCreated,
                )
            }
        }
        if (before != null) {
            if (before.accountId != accountId) {
                return AckContext.Invalid
            }
            if (root.aggregateType == AggregateType.CUSTOM_EXERCISE && before.isUserCreated != true) {
                return AckContext.Invalid
            }
            if (before.localGeneration < frozen) {
                return AckContext.Invalid
            }
            val revision = before.serverRevision
            if (revision != null && revision <= 0) {
                return AckContext.Invalid
            }
            if (before.syncState != "pending") {
                return AckContext.Invalid
            }
            if (watermark < before.localGeneration) {
                return AckContext.Invalid
            }
        }
        val atFrozen = before != null &&
            before.localGeneration == frozen &&
            watermark == frozen
        evidences.add(AckRootEvidence(root, watermark, before, atFrozen))
    }
    return AckContext.Valid(evidences)
}

/** Post-write expectation for one affected root's physical metadata. */
internal data class AckMetadataExpectation(
    val evidence: AckRootEvidence,
    val updated: Boolean,
    val expectedRevision: Long?,
)

/**
 * Updates exactly the affected physical roots at their frozen generation,
 * setting only `server_revision` and `sync_state='synced'`. The revision is the
 * maximum of the existing physical and the retained canonical positive
 * revisions; null counts as no evidence and absence from the server batch never
 * invents one. Newer or vanished roots stay untouched. Must run inside the
 * caller's active fenced transaction after canonical application.
 *
 * @return the updated-root count plus per-root expectations for [recheckAckMetadata].
 */
internal suspend fun applyAckMetadataUpdates(
    database: GymDatabase,
    accountId: String,
    roots: List<AckRootEvidence>,
): Pair<Int, List<AckMetadataExpectation>> {
    val canonicalDao = database.syncCanonicalDao()
    val captureDao = database.syncCaptureDao()
    var acknowledgedRoots = 0
    val expectations = ArrayList<AckMetadataExpectation>(roots.size)
    for (evidence in roots) {
        val before = evidence.before
        if (before == null || !evidence.atFrozenGeneration) {
            expectations.add(AckMetadataExpectation(evidence, updated = false, expectedRevision = null))
            continue
        }
        val canonical = canonicalDao.getRoot(
            accountId,
            evidence.root.aggregateType.wireValue,
            evidence.root.aggregateId,
        ) ?: throw IllegalStateException("Acknowledged root changed during transaction")
        val canonicalRevision = canonical.serverRevision?.takeIf { it > 0 }
        val physicalRevision = before.serverRevision?.takeIf { it > 0 }
        val newRevision = when {
            canonicalRevision != null && physicalRevision != null ->
                maxOf(canonicalRevision, physicalRevision)
            canonicalRevision != null -> canonicalRevision
            else -> physicalRevision
        }
        val updated = when (evidence.root.aggregateType) {
            AggregateType.WORKOUT -> captureDao.acknowledgeWorkoutMetadata(
                evidence.root.aggregateId,
                newRevision,
                "synced",
            )
            AggregateType.ROUTINE -> captureDao.acknowledgeRoutineMetadata(
                evidence.root.aggregateId,
                newRevision,
                "synced",
            )
            AggregateType.CUSTOM_EXERCISE -> captureDao.acknowledgeExerciseMetadata(
                evidence.root.aggregateId,
                newRevision,
                "synced",
            )
        }
        if (updated != 1) {
            throw IllegalStateException("Acknowledged root changed during transaction")
        }
        acknowledgedRoots += 1
        expectations.add(AckMetadataExpectation(evidence, updated = true, expectedRevision = newRevision))
    }
    return acknowledgedRoots to expectations
}

/** Reads one affected root's current physical metadata as a recheck snapshot. */
private suspend fun readAckSnapshot(
    database: GymDatabase,
    root: AffectedRootModel,
): AckPhysicalSnapshot? {
    val captureDao = database.syncCaptureDao()
    return when (root.aggregateType) {
        AggregateType.WORKOUT -> captureDao.getWorkoutMetadata(root.aggregateId)?.let {
            AckPhysicalSnapshot(
                accountId = it.accountId,
                serverRevision = it.serverRevision,
                localGeneration = it.localGeneration,
                syncState = it.syncState,
                deletedAtMillis = it.deletedAtMillis,
                isUserCreated = null,
            )
        }
        AggregateType.ROUTINE -> captureDao.getRoutineMetadata(root.aggregateId)?.let {
            AckPhysicalSnapshot(
                accountId = it.accountId,
                serverRevision = it.serverRevision,
                localGeneration = it.localGeneration,
                syncState = it.syncState,
                deletedAtMillis = it.deletedAtMillis,
                isUserCreated = null,
            )
        }
        AggregateType.CUSTOM_EXERCISE -> captureDao.getExerciseMetadata(root.aggregateId)?.let {
            AckPhysicalSnapshot(
                accountId = it.accountId,
                serverRevision = it.serverRevision,
                localGeneration = it.localGeneration,
                syncState = it.syncState,
                deletedAtMillis = it.deletedAtMillis,
                isUserCreated = it.isUserCreated,
            )
        }
    }
}

/**
 * Rechecks every affected ledger reservation watermark before commit against its
 * pre-write evidence, including vanished and newer roots. The canonical writer
 * never alters watermarks, so a missing reservation or any changed watermark
 * (raised, lowered or lost) is trigger-induced tampering and fails the whole
 * transaction; the conservative consumed token is retained and storage rolls
 * back. Reads only.
 */
internal suspend fun recheckAckWatermarks(
    database: GymDatabase,
    accountId: String,
    roots: List<AckRootEvidence>,
) {
    val canonicalDao = database.syncCanonicalDao()
    for (evidence in roots) {
        val current = canonicalDao.getRoot(
            accountId,
            evidence.root.aggregateType.wireValue,
            evidence.root.aggregateId,
        ) ?: throw IllegalStateException("Acknowledged root changed during transaction")
        if (current.localGenerationWatermark != evidence.watermark) {
            throw IllegalStateException("Acknowledged root changed during transaction")
        }
    }
}

/**
 * Rechecks affected metadata writes before commit: updated roots must carry the
 * expected revision with `synced` state and otherwise unchanged snapshots, while
 * newer or vanished roots must match their pre-write snapshots exactly. Any
 * trigger-induced domain tampering fails the transaction. Reads only.
 */
internal suspend fun recheckAckMetadata(
    database: GymDatabase,
    expectations: List<AckMetadataExpectation>,
) {
    for (expectation in expectations) {
        val current = readAckSnapshot(database, expectation.evidence.root)
        if (!expectation.updated) {
            if (current != expectation.evidence.before) {
                throw IllegalStateException("Acknowledged root changed during transaction")
            }
            continue
        }
        val before = expectation.evidence.before
            ?: throw IllegalStateException("Acknowledged root changed during transaction")
        if (current == null ||
            current.serverRevision != expectation.expectedRevision ||
            current.syncState != "synced" ||
            current != before.copy(
                serverRevision = expectation.expectedRevision,
                syncState = "synced",
            )
        ) {
            throw IllegalStateException("Acknowledged root changed during transaction")
        }
    }
}
