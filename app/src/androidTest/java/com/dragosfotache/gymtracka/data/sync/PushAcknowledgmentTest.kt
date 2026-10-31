package com.dragosfotache.gymtracka.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dragosfotache.gymtracka.data.gym.ExerciseEntity
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.RoutineEntity
import com.dragosfotache.gymtracka.data.gym.SyncStateEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutExerciseEntity
import com.dragosfotache.gymtracka.data.gym.WorkoutSetEntity
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
import com.dragosfotache.gymtracka.data.sync.model.SyncChangeModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import com.dragosfotache.gymtracka.data.sync.store.CanonicalConflictException
import com.dragosfotache.gymtracka.data.sync.store.CanonicalStore
import com.dragosfotache.gymtracka.data.sync.store.ExtraAffectedRoot
import com.dragosfotache.gymtracka.data.sync.store.PushAcknowledgmentResult
import com.dragosfotache.gymtracka.data.sync.store.PushBlockReason
import com.dragosfotache.gymtracka.data.sync.store.PushHeadResult
import com.dragosfotache.gymtracka.data.sync.store.PushOutcomeResult
import com.dragosfotache.gymtracka.data.sync.store.StalePushClaimException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Real Room checks for atomic canonical application, exact frozen-head removal, generation-scoped
 * metadata settlement and dirty marking. Expected values are literals rather than regenerated
 * output.
 */
class PushAcknowledgmentTest : GymDatabaseTest() {

    private companion object {
        const val ACK_CHILD_ENTRY = "e1111111-1111-1111-1111-111111111111"
        const val ACK_CHILD_SET = "e2222222-2222-2222-2222-222222222222"
        const val ACK_CHILD_CUSTOM = "e3333333-3333-3333-3333-333333333333"
        const val ACK_TOMB_WORKOUT = "e4444444-4444-4444-4444-444444444444"
        const val ACK_FOREIGN_WORKOUT = "e5555555-5555-5555-5555-555555555555"
        const val ACK_LOCAL_ROUTINE = "e6666666-6666-6666-6666-666666666666"
        const val ACK_LOCAL_CUSTOM = "e7777777-7777-7777-7777-777777777777"
    }

    @Test
    fun completeMultiRootReplacement_workoutTargetAndUnknownDelete() = runTest {
        database.ackSeedFenced()
        // All physical roots exist before capture: capture requires them.
        database.claimSeedWorkout(CLAIM_WORKOUT_A, CLAIM_ACCOUNT_A, "Ack Workout")
        database.claimSeedWorkout(CLAIM_WORKOUT_B, CLAIM_ACCOUNT_A, "Due Later")
        // Representative business data: children under the old affected root
        // seeded together with it, before capture advances its generation.
        database.exerciseDao().insertExercise(
            ExerciseEntity(
                id = ACK_CHILD_CUSTOM,
                name = "Child Ref",
                type = "reps",
                difficultyLevel = null,
                forceType = null,
                mechanics = null,
                category = null,
                instructions = null,
                isUserCreated = true,
                accountId = CLAIM_ACCOUNT_A,
            ),
        )
        insertWorkoutAggregate(
            WorkoutEntity(
                id = CLAIM_WORKOUT_OLD,
                name = "Old Local",
                startedAtMillis = CLAIM_STARTED_MILLIS,
                accountId = CLAIM_ACCOUNT_A,
                serverRevision = 5L,
                localGeneration = 0L,
            ),
            listOf(WorkoutExerciseEntity(ACK_CHILD_ENTRY, CLAIM_WORKOUT_OLD, ACK_CHILD_CUSTOM, 0)),
            listOf(WorkoutSetEntity(ACK_CHILD_SET, ACK_CHILD_ENTRY, 0, 8, 80.0, true, 90)),
        )
        val store = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_1, CLAIM_MUTATION_2))
        val head = database.claimCaptureStart(
            store,
            CLAIM_ACCOUNT_A,
            CLAIM_WORKOUT_A,
            "Ack Workout",
            extraRoots = listOf(ExtraAffectedRoot(AggregateType.WORKOUT, CLAIM_WORKOUT_OLD)),
        )
        val tail = database.claimCaptureStart(store, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_B, "Due Later")
        // A local tombstone, foreign scope rows and untouched local aggregates.
        database.claimSeedWorkout(ACK_TOMB_WORKOUT, CLAIM_ACCOUNT_A, "Tomb")
        database.claimExec(
            "UPDATE workouts SET deleted_at_millis = 99, sync_state = 'deleted' WHERE id = '$ACK_TOMB_WORKOUT'",
        )
        database.claimSeedWorkout(ACK_FOREIGN_WORKOUT, CLAIM_ACCOUNT_B, "Foreign")
        database.claimSeedDevice(CLAIM_ACCOUNT_B, CLAIM_GENERATION)
        val foreignStore = database.claimCaptureStore(claimSession(CLAIM_ACCOUNT_B), mutableListOf(CLAIM_MUTATION_5))
        val foreign = database.claimCaptureStart(
            foreignStore,
            CLAIM_ACCOUNT_B,
            ACK_FOREIGN_WORKOUT,
            "Foreign",
        )
        database.claimSeedDevice()
        database.routineDao().insertRoutine(
            RoutineEntity(id = ACK_LOCAL_ROUTINE, name = "Local Routine", isDraft = false, accountId = CLAIM_ACCOUNT_A),
        )
        database.syncDao().upsertSyncState(SyncStateEntity(CLAIM_ACCOUNT_A, "cursor-9", 12345L))
        val cacheBefore = database.ackCacheRow(CLAIM_ACCOUNT_A)
        val batch = ackBatch(
            target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L),
            extraDeletes = listOf(ackDeleteChange(AggregateType.WORKOUT, ACK_UNKNOWN_CLOUD, 11L)),
        )

        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
            assertEquals(head.mutationId, claim.row.mutationId)
            val frozen = database.ackRow(head.mutationId)
            // Complete row snapshots of untouched tables, taken admitted.
            assertEquals(listOf(ACK_CHILD_ENTRY), database.ackIds("workout_exercises"))
            assertEquals(listOf(ACK_CHILD_SET), database.ackIds("workout_sets"))
            val childExBefore = database.projectionDump("workout_exercises")
            val childSetBefore = database.projectionDump("workout_sets")
            val routinesBefore = database.projectionDump("routines")
            val exercisesBefore = database.projectionDump("exercises")
            val accepted = run.handleResult(
                claim,
                ackAcceptedResult(claim.row.mutationId, batch),
            ) as PushOutcomeResult.Accepted
            // Read-only snapshot: the admitted freeze is untouched by handling.
            assertEquals(frozen, database.ackRow(head.mutationId))
            val result = run.acknowledgeAccepted(claim, accepted) as PushAcknowledgmentResult.Acknowledged
            assertEquals(2, result.canonical.appliedRoots)
            assertEquals(0, result.canonical.ignoredRoots)
            assertEquals(2, result.acknowledgedRoots)

            // Target canonical evidence replaced wholesale with independent literals.
            val stored = JSONObject(database.ackCanonicalJson(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A)!!)
            assertEquals("W", stored.getString("name"))
            assertEquals(7L, stored.getLong("revision"))
            assertEquals(7L, database.ackCanonicalRevision(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
            // Unknown cloud-old-active DELETE is stored but never acknowledgment context.
            assertEquals(11L, database.ackCanonicalRevision(CLAIM_ACCOUNT_A, "workout", ACK_UNKNOWN_CLOUD))
            assertNull(database.ackCanonicalJson(CLAIM_ACCOUNT_A, "workout", ACK_UNKNOWN_CLOUD))

            // Both locally affected roots settle at exactly the frozen generation:
            // target takes max(physical 5, canonical 7); absent-from-batch old root
            // keeps its revision and still flips to synced.
            assertEquals(7L, database.ackMeta("workouts", CLAIM_WORKOUT_A)["server_revision"])
            assertEquals("synced", database.ackMeta("workouts", CLAIM_WORKOUT_A)["sync_state"])
            assertEquals(1L, database.ackMeta("workouts", CLAIM_WORKOUT_A)["local_generation"])
            assertEquals(5L, database.ackMeta("workouts", CLAIM_WORKOUT_OLD)["server_revision"])
            assertEquals("synced", database.ackMeta("workouts", CLAIM_WORKOUT_OLD)["sync_state"])
            // Generation watermarks never move under acknowledgment.
            assertEquals(1L, database.ackWatermark(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
            assertEquals(1L, database.ackWatermark(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_OLD))

            // Exact frozen row removed; tail row byte-identical.
            assertNull(database.syncDao().getOutboxMutation(head.mutationId, CLAIM_ACCOUNT_A))
            assertEquals(tail, database.syncDao().getOutboxMutation(tail.mutationId, CLAIM_ACCOUNT_A))
            // Complete business/table preservation: children, tombstone, foreign
            // and local aggregates, cursor and cache epochs. Child and
            // unrelated tables survive byte-for-byte, not just by ID.
            assertEquals(childExBefore, database.projectionDump("workout_exercises"))
            assertEquals(childSetBefore, database.projectionDump("workout_sets"))
            assertEquals(routinesBefore, database.projectionDump("routines"))
            assertEquals(exercisesBefore, database.projectionDump("exercises"))
            assertEquals(
                mapOf(
                    "account_id" to CLAIM_ACCOUNT_A,
                    "server_revision" to 5L,
                    "local_generation" to 0L,
                    "sync_state" to "deleted",
                    "deleted_at_millis" to 99L,
                ),
                database.ackMeta("workouts", ACK_TOMB_WORKOUT),
            )
            assertEquals(
                "Foreign",
                database.ackCount("SELECT COUNT(*) FROM workouts WHERE id = '$ACK_FOREIGN_WORKOUT' AND name = 'Foreign'"),
                1,
            )
            assertEquals(foreign, database.syncDao().getOutboxMutation(foreign.mutationId, CLAIM_ACCOUNT_B))
            assertEquals(
                1,
                database.ackCount(
                    "SELECT COUNT(*) FROM routines WHERE id = '$ACK_LOCAL_ROUTINE' AND name = 'Local Routine'",
                ),
            )
            assertEquals("cursor-9", database.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.cursor)
            assertEquals(12345L, database.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.lastSuccessMillis)
            val cache = database.syncCanonicalDao().getAccountCache(CLAIM_ACCOUNT_A)!!
            assertEquals(true, cache.projectionDirty)
            assertEquals(cacheBefore.split("|")[0], if (cache.baselineComplete) "1" else "0")
            assertEquals(cacheBefore.split("|")[1], cache.cacheEpoch.toString())
            assertEquals(cacheBefore.split("|")[3], cache.materializedEpoch.toString())

            val text = result.toString()
            assertFalse(text.contains(head.mutationId))
            assertFalse(text.contains(ACK_BATCH_1))
            assertFalse(text.contains(CLAIM_WORKOUT_A))
            assertTrue(text.isNotBlank())
        }

        assertEquals(1, database.claimOutboxFor(CLAIM_ACCOUNT_A).size)
        assertEquals("pending", database.ackRow(tail.mutationId).state)
    }

    @Test
    fun routineTarget_acknowledgesMetadata() = runTest {
        database.ackSeedFenced()
        val head = database.ackCaptureRoutineHead(CLAIM_MUTATION_3)
        val batch = ackBatch(target = ackRoutineChange(ACK_ROUTINE_A, 7L))

        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
            val accepted = run.handleResult(
                claim,
                ackAcceptedResult(claim.row.mutationId, batch),
            ) as PushOutcomeResult.Accepted
            val result = run.acknowledgeAccepted(claim, accepted) as PushAcknowledgmentResult.Acknowledged
            assertEquals(1, result.canonical.appliedRoots)
            assertEquals(1, result.acknowledgedRoots)
            assertEquals(
                mapOf(
                    "account_id" to CLAIM_ACCOUNT_A,
                    "server_revision" to 7L,
                    "local_generation" to 1L,
                    "sync_state" to "synced",
                    "deleted_at_millis" to null,
                ),
                database.ackMeta("routines", ACK_ROUTINE_A),
            )
            assertEquals(1L, database.ackWatermark(CLAIM_ACCOUNT_A, "routine", ACK_ROUTINE_A))
            assertNull(database.syncDao().getOutboxMutation(head.mutationId, CLAIM_ACCOUNT_A))
            assertEquals(true, database.ackProjectionDirty(CLAIM_ACCOUNT_A))
        }
    }

    @Test
    fun customTarget_acknowledgesMetadata() = runTest {
        database.ackSeedFenced()
        val head = database.ackCaptureCustomHead(CLAIM_MUTATION_4)
        val batch = ackBatch(target = ackCustomChange(ACK_CUSTOM_A, 7L))

        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
            val accepted = run.handleResult(
                claim,
                ackAcceptedResult(claim.row.mutationId, batch),
            ) as PushOutcomeResult.Accepted
            val result = run.acknowledgeAccepted(claim, accepted) as PushAcknowledgmentResult.Acknowledged
            assertEquals(1, result.canonical.appliedRoots)
            assertEquals(1, result.acknowledgedRoots)
            assertEquals(
                mapOf(
                    "account_id" to CLAIM_ACCOUNT_A,
                    "server_revision" to 7L,
                    "local_generation" to 1L,
                    "sync_state" to "synced",
                    "deleted_at_millis" to null,
                ),
                database.ackMeta("exercises", ACK_CUSTOM_A),
            )
            assertEquals(1L, database.ackWatermark(CLAIM_ACCOUNT_A, "customExercise", ACK_CUSTOM_A))
            // Custom ownership flag survives acknowledgment untouched.
            assertEquals(
                1,
                database.ackCount("SELECT COUNT(*) FROM exercises WHERE id = '$ACK_CUSTOM_A' AND is_user_created = 1"),
            )
            assertNull(database.syncDao().getOutboxMutation(head.mutationId, CLAIM_ACCOUNT_A))
            assertEquals(true, database.ackProjectionDirty(CLAIM_ACCOUNT_A))
        }
    }

    @Test
    fun newerSameRootEdit_preservesNewerMetadata() = runTest {
        database.ackSeedFenced()
        database.claimSeedWorkout(CLAIM_WORKOUT_A, CLAIM_ACCOUNT_A, "Ack Workout")
        val headStore = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_1))
        val head = database.claimCaptureStart(headStore, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_A, "Ack Workout")
        var saved: PushOutcomeResult.Accepted? = null
        // Admit and snapshot first; the newer edit lands only afterwards.
        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
            saved = run.handleResult(
                claim,
                ackAcceptedResult(claim.row.mutationId, ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L))),
            ) as PushOutcomeResult.Accepted
        }
        // Newer same-root upsert tail lands while the accepted result is pending.
        val tailStore = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_2))
        val tail = database.claimCaptureUpsert(tailStore, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_A, "Edited Later")
        database.claimExec("UPDATE workouts SET name = 'Edited Later' WHERE id = '$CLAIM_WORKOUT_A'")
        assertEquals(2L, tail.localGeneration)

        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
            assertEquals(2, claim.row.attemptCount)
            assertEquals(head.payloadJson, claim.row.payloadJson)
            val result = run.acknowledgeAccepted(claim, saved!!) as PushAcknowledgmentResult.Acknowledged
            assertEquals(1, result.canonical.appliedRoots)
            // Newer generation is never acknowledged.
            assertEquals(0, result.acknowledgedRoots)
            // Lower admitted generation settles canonically, but newer physical
            // metadata, business fields and watermarks remain unchanged.
            assertEquals(7L, database.ackCanonicalRevision(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
            assertEquals(
                mapOf(
                    "account_id" to CLAIM_ACCOUNT_A,
                    "server_revision" to 5L,
                    "local_generation" to 2L,
                    "sync_state" to "pending",
                    "deleted_at_millis" to null,
                ),
                database.ackMeta("workouts", CLAIM_WORKOUT_A),
            )
            assertEquals(2L, database.ackWatermark(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
            assertEquals(
                1,
                database.ackCount(
                    "SELECT COUNT(*) FROM workouts WHERE id = '$CLAIM_WORKOUT_A' AND name = 'Edited Later'",
                ),
            )
            assertNull(database.syncDao().getOutboxMutation(head.mutationId, CLAIM_ACCOUNT_A))
            // Complete tail row equality: the later replacement effect survives.
            assertEquals(tail, database.ackRow(tail.mutationId))
            assertEquals("pending", database.ackRow(tail.mutationId).state)
            assertEquals(0, database.ackRow(tail.mutationId).attemptCount)
            assertEquals(2L, database.ackRow(tail.mutationId).localGeneration)
            assertEquals(true, database.ackProjectionDirty(CLAIM_ACCOUNT_A))
        }
    }

    @Test
    fun laterStartWithExtraRoot_preservesTombstonedTarget() = runTest {
        database.ackSeedFenced()
        database.claimSeedWorkout(CLAIM_WORKOUT_A, CLAIM_ACCOUNT_A, "Ack Workout")
        database.claimSeedWorkout(CLAIM_WORKOUT_B, CLAIM_ACCOUNT_A, "Later")
        val headStore = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_1))
        val head = database.claimCaptureStart(headStore, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_A, "Ack Workout")
        var saved: PushOutcomeResult.Accepted? = null
        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
            saved = run.handleResult(
                claim,
                ackAcceptedResult(claim.row.mutationId, ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L))),
            ) as PushOutcomeResult.Accepted
        }
        // A later start of B with extraRoot A lands while the accepted result
        // is still pending, and tombstones the earlier target A in the same
        // capture transaction: multi-root later replacement effects.
        val laterStore = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_2))
        val later = laterStore.withFencedTransaction { transaction ->
            database.claimExec(
                "UPDATE workouts SET deleted_at_millis = 77 WHERE id = '$CLAIM_WORKOUT_A'",
            )
            transaction.captureEdit(
                AggregateType.WORKOUT,
                CLAIM_WORKOUT_B,
                com.dragosfotache.gymtracka.data.sync.model.MutationKind.WORKOUT_START,
                changed = true,
                extraRoots = listOf(ExtraAffectedRoot(AggregateType.WORKOUT, CLAIM_WORKOUT_A)),
            ) {
                com.dragosfotache.gymtracka.data.sync.model.MutationPayload.WorkoutStart(
                    CLAIM_WORKOUT_B,
                    "Later",
                    CLAIM_STARTED_MILLIS,
                )
            }
        }
        checkNotNull(later) { "test later capture must append" }
        assertEquals(CLAIM_MUTATION_2, later.request?.envelope?.mutationId)
        val tail = database.ackRow(CLAIM_MUTATION_2)
        val tombstoned = database.ackMeta("workouts", CLAIM_WORKOUT_A)
        assertEquals(2L, tombstoned["local_generation"])
        assertEquals("pending", tombstoned["sync_state"])
        assertEquals(77L, tombstoned["deleted_at_millis"])
        assertEquals(2L, database.ackWatermark(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))

        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
            assertEquals(head.payloadJson, claim.row.payloadJson)
            val result = run.acknowledgeAccepted(claim, saved!!) as PushAcknowledgmentResult.Acknowledged
            assertEquals(1, result.canonical.appliedRoots)
            // The newer tombstoned generation is never resurrected to synced;
            // the new root B is outside this acknowledgment's context.
            assertEquals(0, result.acknowledgedRoots)
            assertEquals(7L, database.ackCanonicalRevision(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
            assertEquals(tombstoned, database.ackMeta("workouts", CLAIM_WORKOUT_A))
            assertEquals(
                mapOf(
                    "account_id" to CLAIM_ACCOUNT_A,
                    "server_revision" to 5L,
                    "local_generation" to 1L,
                    "sync_state" to "pending",
                    "deleted_at_millis" to null,
                ),
                database.ackMeta("workouts", CLAIM_WORKOUT_B),
            )
            assertEquals(1L, database.ackWatermark(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_B))
            assertEquals(2L, database.ackWatermark(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
            assertNull(database.syncDao().getOutboxMutation(head.mutationId, CLAIM_ACCOUNT_A))
            assertEquals(tail, database.ackRow(CLAIM_MUTATION_2))
            assertEquals(true, database.ackProjectionDirty(CLAIM_ACCOUNT_A))
        }
    }

    @Test
    fun invalidRetainedCanonicalEvidence_rollsBackAndRecovers() = runTest {
        database.ackSeedFenced()
        database.ackCaptureWorkoutHead()
        // Corrupt the retained reservation body the writer must revalidate.
        database.claimExec(
            "UPDATE sync_canonical_roots SET canonical_state = 'upsert', canonical_json = '{broken' " +
                "WHERE account_id = '$CLAIM_ACCOUNT_A'" +
                " AND aggregate_type = 'workout' AND aggregate_id = '$CLAIM_WORKOUT_A'",
        )
        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
            val admitted = database.claimSnapshot()
            val accepted = run.handleResult(
                claim,
                ackAcceptedResult(claim.row.mutationId, ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L))),
            ) as PushOutcomeResult.Accepted
            try {
                run.acknowledgeAccepted(claim, accepted)
                fail("Invalid retained evidence must abort acknowledgment")
            } catch (_: com.dragosfotache.gymtracka.data.sync.store.CanonicalStorageException) {
            }
            assertEquals(claim.row, database.ackRow(claim.row.mutationId))
            assertEquals(admitted, database.claimSnapshot())
            try {
                run.acknowledgeAccepted(claim, accepted)
                fail("Rolled-back evidence write still consumes the token")
            } catch (_: StalePushClaimException) {
            }
        }
        // The frozen head is reclaimable with identical bytes after the fault.
        val head = database.ackRow()
        assertEquals("frozen", head.state)
        assertEquals(1, head.attemptCount)
        database.claimExec(
            "UPDATE sync_canonical_roots SET canonical_state = 'unobserved', canonical_json = NULL " +
                "WHERE account_id = '$CLAIM_ACCOUNT_A'" +
                " AND aggregate_type = 'workout' AND aggregate_id = '$CLAIM_WORKOUT_A'",
        )
        database.claimPushStore().withRun(0L) { run ->
            val next = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
            assertEquals(2, next.row.attemptCount)
            assertEquals(head.payloadJson, next.row.payloadJson)
            val accepted = run.handleResult(
                next,
                ackAcceptedResult(next.row.mutationId, ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L))),
            ) as PushOutcomeResult.Accepted
            val result = run.acknowledgeAccepted(next, accepted) as PushAcknowledgmentResult.Acknowledged
            assertEquals(1, result.acknowledgedRoots)
        }
    }

    @Test
    fun missingPhysicalRoot_succeedsWithoutRecreation() = runTest {
        database.ackSeedFenced()
        val head = database.ackCaptureWorkoutHead()
        // Physical root vanishes; independent ledger reservation keeps generation evidence.
        database.claimExec("DELETE FROM workouts WHERE id = '$CLAIM_WORKOUT_A'")
        assertEquals(1L, database.ackWatermark(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))

        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
            val accepted = run.handleResult(
                claim,
                ackAcceptedResult(claim.row.mutationId, ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L))),
            ) as PushOutcomeResult.Accepted
            val result = run.acknowledgeAccepted(claim, accepted) as PushAcknowledgmentResult.Acknowledged
            assertEquals(1, result.canonical.appliedRoots)
            assertEquals(0, result.acknowledgedRoots)
            // No fabrication: the root is not reinserted.
            assertEquals(0, database.ackCount("SELECT COUNT(*) FROM workouts WHERE id = '$CLAIM_WORKOUT_A'"))
            // Reservation watermark unchanged by acknowledgment.
            assertEquals(1L, database.ackWatermark(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
            assertEquals(7L, database.ackCanonicalRevision(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
            assertNull(database.syncDao().getOutboxMutation(head.mutationId, CLAIM_ACCOUNT_A))
            assertEquals(true, database.ackProjectionDirty(CLAIM_ACCOUNT_A))
        }
    }

    @Test
    fun exactMatch_receivesMonotonicHighestRevision() = runTest {
        val cases = listOf(
            Triple("below-canonical", 5L, 7L),
            Triple("above-canonical", 9L, 9L),
        )
        cases.forEach { (label, physicalRevision, expected) ->
            database.claimExec("DELETE FROM sync_outbox")
            database.claimExec("DELETE FROM workouts")
            database.claimExec("DELETE FROM sync_canonical_roots")
            database.claimExec("DELETE FROM sync_account_cache")
            database.ackSeedFenced()
            database.ackCaptureWorkoutHead()
            database.claimExec(
                "UPDATE workouts SET server_revision = $physicalRevision WHERE id = '$CLAIM_WORKOUT_A'",
            )
            database.claimPushStore().withRun(0L) { run ->
                val claim = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
                val accepted = run.handleResult(
                    claim,
                    ackAcceptedResult(claim.row.mutationId, ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L))),
                ) as PushOutcomeResult.Accepted
                val result = run.acknowledgeAccepted(claim, accepted) as PushAcknowledgmentResult.Acknowledged
                assertEquals("$label ack", 1, result.acknowledgedRoots)
                assertEquals(
                    "$label meta",
                    mapOf(
                        "account_id" to CLAIM_ACCOUNT_A,
                        "server_revision" to expected,
                        "local_generation" to 1L,
                        "sync_state" to "synced",
                        "deleted_at_millis" to null,
                    ),
                    database.ackMeta("workouts", CLAIM_WORKOUT_A),
                )
                // Watermark pinned at the frozen generation, never incremented.
                assertEquals("$label watermark", 1L, database.ackWatermark(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
            }
        }

        // Null physical revision treats canonical as the only evidence.
        database.claimExec("DELETE FROM sync_outbox")
        database.claimExec("DELETE FROM workouts")
        database.claimExec("DELETE FROM sync_canonical_roots")
        database.claimExec("DELETE FROM sync_account_cache")
        database.ackSeedFenced()
        database.ackCaptureWorkoutHead()
        database.claimExec("UPDATE workouts SET server_revision = NULL WHERE id = '$CLAIM_WORKOUT_A'")
        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
            val accepted = run.handleResult(
                claim,
                ackAcceptedResult(claim.row.mutationId, ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L))),
            ) as PushOutcomeResult.Accepted
            val result = run.acknowledgeAccepted(claim, accepted) as PushAcknowledgmentResult.Acknowledged
            assertEquals(1, result.acknowledgedRoots)
            assertEquals(7L, database.ackMeta("workouts", CLAIM_WORKOUT_A)["server_revision"])
            assertEquals("synced", database.ackMeta("workouts", CLAIM_WORKOUT_A)["sync_state"])
        }
    }

    @Test
    fun duplicateAfterLaterWinner_removesHeadWithoutRestoring() = runTest {
        database.ackSeedFenced()
        database.ackCaptureWorkoutHead()
        // Later canonical winner arrives first through the owned writer, with a
        // distinct business name so restoration would be visible.
        val winner = ackBatch(
            target = SyncChangeModel(
                AggregateType.WORKOUT,
                CLAIM_WORKOUT_A,
                ChangeOperation.UPSERT,
                9L,
                com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord.Workout(
                    WorkoutRecordModel(CLAIM_WORKOUT_A, "Newer", "active", CLAIM_STARTED_MILLIS, null, 9L, emptyList()),
                ),
            ),
            batchId = ACK_BATCH_2,
            cursor = ACK_CURSOR_2,
        )
        val applied = CanonicalStore(database, claimSession()).applyBatch(winner, 0L)
        assertEquals(1, applied.appliedRoots)

        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
            val oldDuplicate = ackBatch(
                target = SyncChangeModel(
                    AggregateType.WORKOUT,
                    CLAIM_WORKOUT_A,
                    ChangeOperation.UPSERT,
                    1L,
                    CanonicalRecord.Workout(
                        WorkoutRecordModel(CLAIM_WORKOUT_A, "Older", "active", CLAIM_STARTED_MILLIS, null, 1L, emptyList()),
                    ),
                ),
            )
            val accepted = run.handleResult(
                claim,
                ackAcceptedResult(claim.row.mutationId, oldDuplicate, MutationStatus.DUPLICATE),
            ) as PushOutcomeResult.Accepted
            // Prove the all-no-op path still marks dirty: reset the flag that
            // capture and the canonical apply already set.
            database.claimExec(
                "UPDATE sync_account_cache SET projection_dirty = 0 WHERE account_id = '$CLAIM_ACCOUNT_A'",
            )
            assertEquals(false, database.ackProjectionDirty(CLAIM_ACCOUNT_A))
            val result = run.acknowledgeAccepted(claim, accepted) as PushAcknowledgmentResult.Acknowledged
            // Whole batch obsolete, yet the exact request is still removed and dirty set.
            assertEquals(0, result.canonical.appliedRoots)
            assertEquals(1, result.canonical.ignoredRoots)
            assertEquals(
                "Newer",
                JSONObject(database.ackCanonicalJson(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A)!!)
                    .getString("name"),
            )
            assertEquals(9L, database.ackCanonicalRevision(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
            assertNull(database.syncDao().getOutboxMutation(claim.row.mutationId, CLAIM_ACCOUNT_A))
            assertEquals(true, database.ackProjectionDirty(CLAIM_ACCOUNT_A))
            assertEquals(1L, database.ackWatermark(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
        }
        assertEquals(0, database.claimOutboxFor(CLAIM_ACCOUNT_A).size)
    }

    @Test
    fun equalConflict_rollsBackAndLeavesHeadRecoverable() = runTest {
        database.ackSeedFenced()
        database.ackCaptureWorkoutHead()
        CanonicalStore(database, claimSession()).applyBatch(
            ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 5L)),
            0L,
        )

        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
            assertEquals(1, claim.row.attemptCount)
            // Full-table oracle taken after the admission freeze, so the
            // rollback proof is not polluted by the claim increment itself.
            val admitted = database.claimSnapshot()
            val conflicting = ackBatch(
                target = SyncChangeModel(
                    AggregateType.WORKOUT,
                    CLAIM_WORKOUT_A,
                    ChangeOperation.UPSERT,
                    5L,
                    outcomeWorkoutRecord(id = CLAIM_WORKOUT_A, revision = 5L).let {
                        it.copy(record = it.record.copy(name = "Changed"))
                    },
                ),
                batchId = ACK_BATCH_2,
                cursor = ACK_CURSOR_2,
            )
            val accepted = run.handleResult(
                claim,
                ackAcceptedResult(claim.row.mutationId, conflicting),
            ) as PushOutcomeResult.Accepted
            try {
                run.acknowledgeAccepted(claim, accepted)
                fail("Equal authoritative conflict must reject the complete transaction")
            } catch (_: CanonicalConflictException) {
            }
            // Nothing committed; the frozen head is reclaimable with identical bytes.
            assertEquals(claim.row, database.ackRow(claim.row.mutationId))
            assertEquals(admitted, database.claimSnapshot())
            try {
                run.acknowledgeAccepted(claim, accepted)
                fail("Rolled-back acknowledgment still consumes the claim token")
            } catch (_: StalePushClaimException) {
            }
        }

        val head = database.ackRow()
        assertEquals("frozen", head.state)
        assertEquals(1, head.attemptCount)
        var reclaimed = false
        database.claimPushStore().withRun(0L) { run ->
            val next = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
            assertEquals(2, next.row.attemptCount)
            assertEquals(head.payloadJson, next.row.payloadJson)
            reclaimed = true
        }
        assertTrue(reclaimed)
    }

    @Test
    fun corruptContext_tableDriven() = runTest {
        val cases = listOf(
            "missing-ledger" to { _: String ->
                database.claimExec(
                    "DELETE FROM sync_canonical_roots WHERE account_id = '$CLAIM_ACCOUNT_A'" +
                        " AND aggregate_type = 'workout' AND aggregate_id = '$CLAIM_WORKOUT_A'",
                )
            },
            "low-ledger" to { _: String ->
                database.claimExec(
                    "UPDATE sync_canonical_roots SET local_generation_watermark = 0" +
                        " WHERE account_id = '$CLAIM_ACCOUNT_A'" +
                        " AND aggregate_type = 'workout' AND aggregate_id = '$CLAIM_WORKOUT_A'",
                )
            },
            "foreign-root" to { _: String ->
                database.claimExec("UPDATE workouts SET account_id = '$CLAIM_ACCOUNT_B' WHERE id = '$CLAIM_WORKOUT_A'")
            },
            "lowered-generation" to { _: String ->
                database.claimExec("UPDATE workouts SET local_generation = 0 WHERE id = '$CLAIM_WORKOUT_A'")
            },
            "invalid-state" to { _: String ->
                database.claimExec("UPDATE workouts SET sync_state = 'synced' WHERE id = '$CLAIM_WORKOUT_A'")
            },
        )
        cases.forEach { (label, tamper) ->
            database.claimExec("DELETE FROM sync_outbox")
            database.claimExec("DELETE FROM workouts")
            database.claimExec("DELETE FROM sync_canonical_roots")
            database.claimExec("DELETE FROM sync_account_cache")
            database.ackSeedFenced()
            database.claimSeedWorkout(CLAIM_WORKOUT_A, CLAIM_ACCOUNT_A, "Ack Workout")
            database.claimSeedWorkout(CLAIM_WORKOUT_B, CLAIM_ACCOUNT_A, "Due Later")
            val seeder = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_1, CLAIM_MUTATION_2))
            database.claimCaptureStart(seeder, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_A, "Ack Workout")
            // A real pending successor: the retained head must block it.
            val successor = database.claimCaptureStart(seeder, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_B, "Due Later")
            tamper(label)
            val stored = database.ackRow(CLAIM_MUTATION_1)
            var admitted = ""
            database.claimPushStore().withRun(0L) { run ->
                val claim = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
                assertEquals(CLAIM_MUTATION_1, claim.row.mutationId)
                val accepted = run.handleResult(
                    claim,
                    ackAcceptedResult(claim.row.mutationId, ackBatch(target = ackWorkoutChange())),
                ) as PushOutcomeResult.Accepted
                // Oracle taken after the admission freeze, not before it.
                admitted = database.claimSnapshot()
                val result = run.acknowledgeAccepted(claim, accepted)
                assertEquals("$label result", PushAcknowledgmentResult.RetainedCorruption, result)
                val text = result.toString()
                assertFalse("$label display", text.contains(stored.mutationId))
                assertTrue("$label display blank", text.isNotBlank())
                try {
                    val head = database.ackRow(CLAIM_MUTATION_1)
                    assertEquals("$label state", "frozen", head.state)
                    assertEquals("$label code", ACK_CONTEXT_CORRUPT_CODE, head.errorCode)
                    assertEquals("$label message", ACK_CONTEXT_CORRUPT_MESSAGE, head.errorMessage)
                    assertNull("$label retry", head.retryAfterMillis)
                    assertEquals("$label bytes", stored.payloadJson, head.payloadJson)
                    assertEquals("$label id", stored.mutationId, head.mutationId)
                    // No partial canonical/root effects beyond the retained bookkeeping.
                    assertNull("$label canonical", database.ackCanonicalJson(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
                } catch (error: AssertionError) {
                    throw AssertionError("$label: ${error.message}", error)
                }
            }
            // The retained head blocks its real successor while both stand;
            // the blocked selection itself is read-only.
            database.claimPushStore().withRun(0L) { run ->
                val blocked = run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Blocked
                assertEquals("$label block", PushBlockReason.RETAINED_ERROR, blocked.reason)
                assertEquals("$label block code", ACK_CONTEXT_CORRUPT_CODE, blocked.errorCode)
            }
            assertEquals("$label successor", successor, database.ackRow(CLAIM_MUTATION_2))
            assertEquals("$label successor state", "pending", database.ackRow(CLAIM_MUTATION_2).state)
            // Full-table proof of no stray writes: restore only the retained
            // bookkeeping columns and the snapshot must equal the admitted one.
            database.claimExec(
                "UPDATE sync_outbox SET error_code = NULL, error_message = NULL" +
                    " WHERE sequence = ${stored.sequence}",
            )
            assertEquals("$label isolation", admitted, database.claimSnapshot())
        }
    }

    @Test
    fun bundledCustomContext_retainedCorruption() = runTest {
        database.ackSeedFenced()
        // Start from a valid custom upsert capture, then flip the physical row
        // to a bundled identity: bundled roots are never valid context.
        database.ackCaptureCustomHead(CLAIM_MUTATION_4)
        database.claimExec(
            "UPDATE exercises SET is_user_created = 0, account_id = NULL WHERE id = '$ACK_CUSTOM_A'",
        )

        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
            val accepted = run.handleResult(
                claim,
                ackAcceptedResult(claim.row.mutationId, ackBatch(target = ackCustomChange())),
            ) as PushOutcomeResult.Accepted
            assertEquals(PushAcknowledgmentResult.RetainedCorruption, run.acknowledgeAccepted(claim, accepted))
        }
        val head = database.ackRow(CLAIM_MUTATION_4)
        assertEquals("frozen", head.state)
        assertEquals(ACK_CONTEXT_CORRUPT_CODE, head.errorCode)
        assertEquals(ACK_CONTEXT_CORRUPT_MESSAGE, head.errorMessage)
        assertNull(head.retryAfterMillis)
        assertNull(database.ackCanonicalJson(CLAIM_ACCOUNT_A, "customExercise", ACK_CUSTOM_A))
    }

    @Test
    fun wrongMutationSnapshot_rejectsWithoutConsuming() = runTest {
        database.ackSeedFenced()
        database.ackCaptureWorkoutHead(mutationId = CLAIM_MUTATION_1, rootId = CLAIM_WORKOUT_A)
        // A genuinely bound snapshot from an independent database with a
        // different mutation identity must not apply here.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val foreign = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java).build()
        var foreignAccepted: PushOutcomeResult.Accepted? = null
        try {
            foreign.claimSeedDevice()
            foreign.claimExec("DELETE FROM sync_account_cache")
            foreign.claimSeedCache(CLAIM_ACCOUNT_A, 0L)
            foreign.claimSeedWorkout(CLAIM_WORKOUT_B, CLAIM_ACCOUNT_A, "Foreign Workout")
            val foreignStore = foreign.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_2))
            foreign.claimCaptureStart(foreignStore, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_B, "Foreign Workout")
            foreign.claimPushStore().withRun(0L) { run ->
                val foreignClaim = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
                foreignAccepted = run.handleResult(
                    foreignClaim,
                    outcomeResult(
                        foreignClaim.row.mutationId,
                        MutationStatus.ACCEPTED,
                        null,
                        outcomeBatch(targetId = CLAIM_WORKOUT_B),
                    ),
                ) as PushOutcomeResult.Accepted
            }
        } finally {
            foreign.close()
        }

        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
            val live = database.ackRow(claim.row.mutationId)
            // Oracle taken after the admission freeze.
            val admitted = database.claimSnapshot()
            try {
                run.acknowledgeAccepted(claim, foreignAccepted!!)
                fail("Wrong-mutation snapshot must be rejected before writes")
            } catch (error: IllegalArgumentException) {
                assertEquals(ACK_MISMATCH_MESSAGE, error.message)
            }
            // Not consumed: the live claim still validates and storage is untouched.
            run.checkClaim(claim)
            assertEquals(live, database.ackRow(claim.row.mutationId))
            assertEquals(admitted, database.claimSnapshot())
        }
    }

    @Test
    fun sameIdCrossAccountSnapshot_rejectsProvenance() = runTest {
        database.ackSeedFenced()
        database.ackCaptureWorkoutHead(mutationId = CLAIM_MUTATION_1, rootId = CLAIM_WORKOUT_A, name = "Local Workout")
        // Same mutation and target IDs and byte-identical request: only the
        // account differs, so only the account binding can reject.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val foreign = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java).build()
        var foreignAccepted: PushOutcomeResult.Accepted? = null
        var foreignBytes = ""
        var foreignSequence = -1L
        try {
            foreign.claimSeedDevice(CLAIM_ACCOUNT_B, CLAIM_GENERATION)
            foreign.claimExec("DELETE FROM sync_account_cache")
            foreign.claimSeedCache(CLAIM_ACCOUNT_B, 0L)
            foreign.claimSeedWorkout(CLAIM_WORKOUT_A, CLAIM_ACCOUNT_B, "Local Workout")
            val sessionB = claimSession(CLAIM_ACCOUNT_B)
            val foreignStore = foreign.claimCaptureStore(sessionB, mutableListOf(CLAIM_MUTATION_1))
            foreign.claimCaptureStart(foreignStore, CLAIM_ACCOUNT_B, CLAIM_WORKOUT_A, "Local Workout")
            foreign.claimPushStore(sessionB).withRun(0L) { run ->
                val foreignClaim = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
                assertEquals(CLAIM_MUTATION_1, foreignClaim.row.mutationId)
                foreignBytes = foreignClaim.row.payloadJson
                foreignSequence = foreignClaim.row.sequence
                foreignAccepted = run.handleResult(
                    foreignClaim,
                    outcomeResult(
                        foreignClaim.row.mutationId,
                        MutationStatus.ACCEPTED,
                        null,
                        outcomeBatch(targetId = CLAIM_WORKOUT_A),
                    ),
                ) as PushOutcomeResult.Accepted
            }
        } finally {
            foreign.close()
        }

        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
            assertEquals(CLAIM_MUTATION_1, claim.row.mutationId)
            // Immutable request identity is byte-identical across accounts,
            // down to the row sequence: only the account binding can reject.
            assertEquals(foreignBytes, claim.row.payloadJson)
            assertEquals(foreignSequence, claim.row.sequence)
            // Oracle taken after the admission freeze.
            val admitted = database.claimSnapshot()
            try {
                run.acknowledgeAccepted(claim, foreignAccepted!!)
                fail("Cross-account snapshot must be rejected despite identical request bytes")
            } catch (error: IllegalArgumentException) {
                assertEquals(ACK_MISMATCH_MESSAGE, error.message)
            }
            run.checkClaim(claim)
            assertEquals(admitted, database.claimSnapshot())
        }
    }

    @Test
    fun sameAccountChangedRequest_rejectsProvenance() = runTest {
        database.ackSeedFenced()
        database.ackCaptureWorkoutHead(mutationId = CLAIM_MUTATION_1, rootId = CLAIM_WORKOUT_A, name = "Local Workout")
        // Same account, same mutation and target IDs, but different request
        // bytes: the changed request must still reject on its own, so an
        // account defect cannot mask a request defect or vice versa.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val foreign = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java).build()
        var foreignAccepted: PushOutcomeResult.Accepted? = null
        var foreignBytes = ""
        try {
            foreign.claimSeedDevice()
            foreign.claimExec("DELETE FROM sync_account_cache")
            foreign.claimSeedCache(CLAIM_ACCOUNT_A, 0L)
            foreign.claimSeedWorkout(CLAIM_WORKOUT_A, CLAIM_ACCOUNT_A, "Changed Workout")
            val foreignStore = foreign.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_1))
            foreign.claimCaptureStart(foreignStore, CLAIM_ACCOUNT_A, CLAIM_WORKOUT_A, "Changed Workout")
            foreign.claimPushStore().withRun(0L) { run ->
                val foreignClaim = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
                assertEquals(CLAIM_MUTATION_1, foreignClaim.row.mutationId)
                foreignBytes = foreignClaim.row.payloadJson
                foreignAccepted = run.handleResult(
                    foreignClaim,
                    outcomeResult(
                        foreignClaim.row.mutationId,
                        MutationStatus.ACCEPTED,
                        null,
                        outcomeBatch(targetId = CLAIM_WORKOUT_A),
                    ),
                ) as PushOutcomeResult.Accepted
            }
        } finally {
            foreign.close()
        }

        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
            assertFalse("fixtures must differ in request bytes", foreignBytes == claim.row.payloadJson)
            // Oracle taken after the admission freeze.
            val admitted = database.claimSnapshot()
            try {
                run.acknowledgeAccepted(claim, foreignAccepted!!)
                fail("Changed-request snapshot must be rejected despite matching account and IDs")
            } catch (error: IllegalArgumentException) {
                assertEquals(ACK_MISMATCH_MESSAGE, error.message)
            }
            run.checkClaim(claim)
            assertEquals(admitted, database.claimSnapshot())
        }
    }

    @Test
    fun priorSnapshot_reusedWithFreshAdmission() = runTest {
        database.ackSeedFenced()
        database.ackCaptureWorkoutHead()
        val batch = ackBatch(target = ackWorkoutChange(CLAIM_WORKOUT_A, 7L))
        var saved: PushOutcomeResult.Accepted? = null
        // Originating block: admit, snapshot, then close without acknowledging.
        database.claimPushStore().withRun(0L) { run ->
            val first = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
            saved = run.handleResult(
                first,
                ackAcceptedResult(first.row.mutationId, batch),
            ) as PushOutcomeResult.Accepted
        }
        // Fresh admission of the exact unchanged request reuses the earlier snapshot.
        database.claimPushStore().withRun(0L) { run ->
            val second = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
            assertEquals(2, second.row.attemptCount)
            val result = run.acknowledgeAccepted(second, saved!!) as PushAcknowledgmentResult.Acknowledged
            assertEquals(1, result.canonical.appliedRoots)
            assertEquals(1, result.acknowledgedRoots)
            assertEquals("synced", database.ackMeta("workouts", CLAIM_WORKOUT_A)["sync_state"])
            assertNull(database.syncDao().getOutboxMutation(second.row.mutationId, CLAIM_ACCOUNT_A))
        }
        assertEquals(0, database.claimOutboxFor(CLAIM_ACCOUNT_A).size)
    }

    @Test
    fun deleteTargetWithWatermarkOnlyNewer_preservesTombstone() = runTest {
        database.ackSeedFenced()
        // Real pending physical tombstone BEFORE capture: capture advances
        // the generation and keeps pending, per the domain convention.
        database.claimSeedWorkout(CLAIM_WORKOUT_A, CLAIM_ACCOUNT_A, "Doomed")
        database.claimExec(
            "UPDATE workouts SET deleted_at_millis = 77 WHERE id = '$CLAIM_WORKOUT_A'",
        )
        val deleteStore = database.claimCaptureStore(claimSession(), mutableListOf(CLAIM_MUTATION_1))
        val captured = deleteStore.withFencedTransaction { transaction ->
            transaction.captureEdit(
                AggregateType.WORKOUT,
                CLAIM_WORKOUT_A,
                com.dragosfotache.gymtracka.data.sync.model.MutationKind.AGGREGATE_DELETE,
                changed = true,
            ) { com.dragosfotache.gymtracka.data.sync.model.MutationPayload.AggregateDelete }
        }
        checkNotNull(captured) { "test delete capture must append" }
        assertEquals(CLAIM_MUTATION_1, captured.request?.envelope?.mutationId)
        // Watermark-only newer: ledger advances above frozen/physical
        // generation while the physical root itself stays at generation 1.
        database.claimExec(
            "UPDATE sync_canonical_roots SET local_generation_watermark = 2 " +
                "WHERE account_id = '$CLAIM_ACCOUNT_A'" +
                " AND aggregate_type = 'workout' AND aggregate_id = '$CLAIM_WORKOUT_A'",
        )
        val tombstoned = database.ackMeta("workouts", CLAIM_WORKOUT_A)
        assertEquals(1L, tombstoned["local_generation"])
        assertEquals("pending", tombstoned["sync_state"])
        assertEquals(77L, tombstoned["deleted_at_millis"])
        assertEquals(2L, database.ackWatermark(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))

        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
            assertEquals("aggregate.delete", claim.row.mutationType)
            // Accepted batch targets DELETE, not UPSERT, for aggregate.delete.
            val batch = ackBatch(
                target = ackDeleteChange(AggregateType.WORKOUT, CLAIM_WORKOUT_A, 7L),
            )
            val accepted = run.handleResult(
                claim,
                ackAcceptedResult(claim.row.mutationId, batch),
            ) as PushOutcomeResult.Accepted
            database.claimExec(
                "UPDATE sync_account_cache SET projection_dirty = 0 WHERE account_id = '$CLAIM_ACCOUNT_A'",
            )
            val result = run.acknowledgeAccepted(claim, accepted) as PushAcknowledgmentResult.Acknowledged
            assertEquals(1, result.canonical.appliedRoots)
            assertEquals(0, result.canonical.ignoredRoots)
            // Watermark-only newer protection: nothing acknowledged.
            assertEquals(0, result.acknowledgedRoots)
            // Exact head removed; revisioned DELETE persisted with null body.
            assertNull(database.syncDao().getOutboxMutation(claim.row.mutationId, CLAIM_ACCOUNT_A))
            assertEquals(7L, database.ackCanonicalRevision(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
            assertNull(database.ackCanonicalJson(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
            // Physical tombstone, metadata and business fields unchanged;
            // watermark pinned; dirty set; nothing fabricated or published.
            assertEquals(tombstoned, database.ackMeta("workouts", CLAIM_WORKOUT_A))
            assertEquals(2L, database.ackWatermark(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
            assertEquals(true, database.ackProjectionDirty(CLAIM_ACCOUNT_A))
            assertEquals(0, database.ackCount("SELECT COUNT(*) FROM workout_exercises"))
            assertEquals(0, database.ackCount("SELECT COUNT(*) FROM workout_sets"))
            assertEquals(
                1,
                database.ackCount(
                    "SELECT COUNT(*) FROM workouts WHERE id = '$CLAIM_WORKOUT_A' AND name = 'Doomed'",
                ),
            )
        }
        assertEquals(0, database.claimOutboxFor(CLAIM_ACCOUNT_A).size)
    }

    @Test
    fun fileBackedReopen_persistsAcknowledgment() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = claimUniqueDbName("ack-persist")
        var preCloseBytes = ""
        var preCloseInner = ""
        val fileDatabase = claimFileDatabase(context, name)
        try {
            fileDatabase.ackSeedFenced()
            fileDatabase.ackCaptureWorkoutHead()
            // Record wrapper bytes and inner request text independently while live.
            fileDatabase.claimPushStore().withRun(0L) { run ->
                val claim = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
                preCloseBytes = claim.row.payloadJson
                preCloseInner = JSONObject(claim.row.payloadJson).getString("requestJson")
                assertEquals(preCloseInner, claim.request.requestJson)
            }
        } finally {
            fileDatabase.close()
        }

        // Reopen before acknowledgment keeps the exact frozen identity on both levels.
        val reopened = claimFileDatabase(context, name)
        try {
            reopened.claimSeedDevice()
            reopened.claimPushStore().withRun(0L) { run ->
                val claim = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
                assertEquals(CLAIM_MUTATION_1, claim.row.mutationId)
                assertEquals(preCloseBytes, claim.row.payloadJson)
                assertEquals(preCloseInner, JSONObject(claim.row.payloadJson).getString("requestJson"))
                assertEquals(preCloseInner, claim.request.requestJson)
            }
        } finally {
            reopened.close()
        }

        // Acknowledge, close, reopen: removal, canonical, metadata and dirty persist.
        val working = claimFileDatabase(context, name)
        try {
            working.claimSeedDevice()
            working.claimPushStore().withRun(0L) { run ->
                val claim = (run.claimHead(ACK_NOW_MILLIS) as PushHeadResult.Claimed).claim
                assertEquals(preCloseBytes, claim.row.payloadJson)
                val accepted = run.handleResult(
                    claim,
                    ackAcceptedResult(claim.row.mutationId, ackBatch(target = ackWorkoutChange())),
                ) as PushOutcomeResult.Accepted
                val result = run.acknowledgeAccepted(claim, accepted) as PushAcknowledgmentResult.Acknowledged
                assertEquals(1, result.acknowledgedRoots)
            }
        } finally {
            working.close()
        }
        val reread = claimFileDatabase(context, name)
        try {
            reread.claimSeedDevice()
            assertNull(reread.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A))
            assertEquals(7L, reread.ackCanonicalRevision(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
            assertEquals("synced", reread.ackMeta("workouts", CLAIM_WORKOUT_A)["sync_state"])
            assertEquals(1L, reread.ackWatermark(CLAIM_ACCOUNT_A, "workout", CLAIM_WORKOUT_A))
            assertEquals(true, reread.ackProjectionDirty(CLAIM_ACCOUNT_A))
        } finally {
            reread.close()
            context.deleteDatabase(name)
        }
    }
}
