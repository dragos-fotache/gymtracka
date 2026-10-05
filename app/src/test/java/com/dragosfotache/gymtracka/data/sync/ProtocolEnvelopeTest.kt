package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.codec.decodePullResult
import com.dragosfotache.gymtracka.data.sync.codec.decodePushResult
import com.dragosfotache.gymtracka.data.sync.codec.decodeSnapshotResult
import com.dragosfotache.gymtracka.data.sync.codec.encodePullResult
import com.dragosfotache.gymtracka.data.sync.codec.encodePushResult
import com.dragosfotache.gymtracka.data.sync.codec.encodeSnapshotResult
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CanonicalRecord
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.CustomExerciseRecordModel
import com.dragosfotache.gymtracka.data.sync.model.EquipmentRefModel
import com.dragosfotache.gymtracka.data.sync.model.MuscleGroupRefModel
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
import com.dragosfotache.gymtracka.data.sync.model.RoutineExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineRecordModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineSetModel
import com.dragosfotache.gymtracka.data.sync.model.SyncBatchModel
import com.dragosfotache.gymtracka.data.sync.model.SyncChangeModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutSetModel
import com.dragosfotache.gymtracka.data.sync.model.validateSyncBatch
import com.dragosfotache.gymtracka.data.sync.model.validateSyncChange
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Protocol envelopes: typed changes, batches, and push/pull/snapshot results. */
class ProtocolEnvelopeTest {

    private companion object {
        const val WORKOUT = "11111111-1111-1111-1111-111111111111"
        const val WORKOUT_EXERCISE = "22222222-2222-2222-2222-222222222222"
        const val WORKOUT_SET = "33333333-3333-3333-3333-333333333333"
        const val CATALOGUE = "44444444-4444-4444-4444-444444444444"
        const val ROUTINE = "55555555-5555-5555-5555-555555555555"
        const val ROUTINE_EXERCISE = "66666666-6666-6666-6666-666666666666"
        const val ROUTINE_SET = "77777777-7777-7777-7777-777777777777"
        const val CUSTOM = "88888888-8888-8888-8888-888888888888"
        const val MUSCLE = "99999999-9999-9999-9999-999999999999"
        const val OLD_WORKOUT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val BATCH = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val MUTATION = "cccccccc-cccc-cccc-cccc-cccccccccccc"
    }

    private fun workoutRecordJson(): JSONObject = JSONObject(
        """
        {"id":"$WORKOUT","name":"W","status":"finished",
         "startedAt":"2026-09-24T14:00:00Z","finishedAt":"2026-09-24T15:00:00Z","revision":3,
         "exercises":[{"id":"$WORKOUT_EXERCISE","exerciseId":"$CATALOGUE","exerciseName":"Squat",
          "position":0,"notes":"","restDurationSeconds":60,
          "sets":[{"id":"$WORKOUT_SET","position":0,"repetitions":5,"weightKg":50.0,
           "isCompleted":true,"restSeconds":null}]}]}
        """.trimIndent(),
    )

    private fun routineRecordJson(): JSONObject = JSONObject(
        """
        {"id":"$ROUTINE","name":"R","isDraft":false,"revision":2,
         "exercises":[{"id":"$ROUTINE_EXERCISE","exerciseId":"$CATALOGUE","exerciseName":"Squat",
          "position":0,"restDurationSeconds":30,
          "sets":[{"id":"$ROUTINE_SET","position":0,"repetitions":5,"weightKg":50.0}]}]}
        """.trimIndent(),
    )

    private fun customRecordJson(): JSONObject = JSONObject(
        """
        {"id":"$CUSTOM","name":"Custom","type":"reps",
         "category":null,"difficultyLevel":null,"forceType":null,"mechanics":null,
         "instructions":null,"isUserCreated":true,"revision":4,
         "muscleGroups":[{"id":"$MUSCLE","name":"Chest","type":"primary"}],
         "equipment":[]}
        """.trimIndent(),
    )

    private fun activeWorkoutRecordJson(): JSONObject = JSONObject(
        """
        {"id":"$WORKOUT","name":"W","status":"active",
         "startedAt":"2026-09-24T14:00:00Z","revision":12,
         "exercises":[{"id":"$WORKOUT_EXERCISE","exerciseId":"$CATALOGUE","exerciseName":"Squat",
          "position":0,"notes":"","restDurationSeconds":60,
          "sets":[{"id":"$WORKOUT_SET","position":0,"repetitions":null,"weightKg":null,
           "isCompleted":false,"restSeconds":null}]}]}
        """.trimIndent(),
    )

    private fun upsertJson(type: String, id: String, revision: Long, record: JSONObject): JSONObject =
        JSONObject().apply {
            put("aggregateType", type)
            put("aggregateId", id)
            put("operation", "upsert")
            put("revision", revision)
            put("record", record)
        }

    private fun deleteJson(type: String, id: String, revision: Long, explicitNull: Boolean): JSONObject =
        JSONObject().apply {
            put("aggregateType", type)
            put("aggregateId", id)
            put("operation", "delete")
            put("revision", revision)
            if (explicitNull) put("record", JSONObject.NULL)
        }

    private fun batchJson(cursor: String, vararg changes: JSONObject, batchId: String = BATCH): JSONObject =
        JSONObject().apply {
            put("batchId", batchId)
            put("cursor", cursor)
            put("changes", JSONArray(changes.toList()))
        }

    private fun assertRejects(block: () -> Unit) {
        try {
            block()
        } catch (_: SyncModelException) {
            return
        }
        fail("Expected SyncModelException")
    }

    @Test
    fun changes_roundTripEveryAggregateUpsertAndDelete() {
        val cases = listOf(
            Triple("workout", WORKOUT, 3L) to workoutRecordJson(),
            Triple("routine", ROUTINE, 2L) to routineRecordJson(),
            Triple("customExercise", CUSTOM, 4L) to customRecordJson(),
        )
        cases.forEach { (key, record) ->
            val (type, id, revision) = key
            val batch = batchJson(
                "cursor-1",
                upsertJson(type, id, revision, record),
                batchId = UUID.randomUUID().toString(),
            )
            val decoded = decodePullResult(
                JSONObject().apply {
                    put("batches", JSONArray(listOf(batch)))
                    put("nextCursor", "cursor-1")
                    put("hasMore", false)
                }.toString(),
            )
            val change = decoded.batches.single().changes.single()
            assertEquals(AggregateType.fromWire(type), change.aggregateType)
            assertEquals(type, change.aggregateType.wireValue)
            assertEquals(id, change.aggregateId)
            assertEquals(revision, change.revision)
            val decodedRecord = change.record
            assertTrue(decodedRecord is CanonicalRecord)
            assertEquals(id, decodedRecord!!.aggregateId)
            assertEquals(revision, decodedRecord.revision)
            assertEquals(decoded, decodePullResult(encodePullResult(decoded).toString()))
        }

        listOf(
            Triple("workout", WORKOUT, 3L),
            Triple("routine", ROUTINE, 2L),
            Triple("customExercise", CUSTOM, 4L),
        ).forEach { (type, id, revision) ->
            val withAbsent = deleteJson(type, id, revision, explicitNull = false)
            val withNull = deleteJson(type, id, revision, explicitNull = true)
            val batch = batchJson(
                "cursor-1",
                withAbsent,
                batchId = UUID.randomUUID().toString(),
            )
            val decoded = decodePullResult(
                JSONObject().apply {
                    put("batches", JSONArray(listOf(batch)))
                    put("nextCursor", "cursor-1")
                    put("hasMore", false)
                }.toString(),
            )
            assertNull(decoded.batches.single().changes.single().record)
            val batchNull = batchJson(
                "cursor-1",
                withNull,
                batchId = UUID.randomUUID().toString(),
            )
            val decodedNull = decodePullResult(
                JSONObject().apply {
                    put("batches", JSONArray(listOf(batchNull)))
                    put("nextCursor", "cursor-1")
                    put("hasMore", false)
                }.toString(),
            )
            assertNull(decodedNull.batches.single().changes.single().record)
        }
    }

    @Test
    fun batch_preservesMultiRootDeletePlusUpsertOrderAndShapes() {
        val batch = batchJson(
            "cursor-opaque-9",
            deleteJson("workout", OLD_WORKOUT, 11L, explicitNull = true),
            upsertJson("workout", WORKOUT, 12L, activeWorkoutRecordJson()),
        )
        val result = decodePushResult(
            JSONObject().apply {
                put(
                    "results",
                    JSONArray(
                        listOf(
                            JSONObject().apply {
                                put("mutationId", MUTATION)
                                put("status", "accepted")
                                put("errorCode", JSONObject.NULL)
                                put("batch", batch)
                            },
                        ),
                    ),
                )
            }.toString(),
        )
        val outcome = result.results.single()
        assertEquals(MUTATION, outcome.mutationId)
        assertEquals(MutationStatus.ACCEPTED, outcome.status)
        assertNull(outcome.errorCode)
        val changes = outcome.batch!!.changes
        assertEquals(
            listOf(ChangeOperation.DELETE, ChangeOperation.UPSERT),
            changes.map { it.operation },
        )
        assertEquals(listOf(OLD_WORKOUT, WORKOUT), changes.map { it.aggregateId })
        val newActive = changes[1].record as CanonicalRecord.Workout
        assertEquals("active", newActive.record.status)
        assertNull(newActive.record.finishedAtMillis)
        assertEquals(false, newActive.record.exercises.single().sets.single().isCompleted)
        assertNull(newActive.record.exercises.single().sets.single().restSeconds)
        assertEquals(result, decodePushResult(encodePushResult(result).toString()))

        // Encoder emits explicit nulls for absent delete records and accepted error codes.
        val encoded = encodePushResult(result).toString()
        val reencoded = JSONObject(encoded)
            .getJSONArray("results").getJSONObject(0)
            .getJSONObject("batch")
        assertTrue(reencoded.getJSONArray("changes").getJSONObject(0).isNull("record"))
        assertTrue(
            JSONObject(encoded).getJSONArray("results").getJSONObject(0).isNull("errorCode"),
        )
        assertEquals(
            "accepted",
            JSONObject(encoded).getJSONArray("results").getJSONObject(0).getString("status"),
        )
        assertEquals(
            "delete",
            reencoded.getJSONArray("changes").getJSONObject(0).getString("operation"),
        )
        assertEquals(
            "workout",
            reencoded.getJSONArray("changes").getJSONObject(0).getString("aggregateType"),
        )
    }

    @Test
    fun discriminators_mapExplicitWireValues() {
        assertEquals(AggregateType.WORKOUT, AggregateType.fromWire("workout"))
        assertEquals(AggregateType.ROUTINE, AggregateType.fromWire("routine"))
        assertEquals(AggregateType.CUSTOM_EXERCISE, AggregateType.fromWire("customExercise"))
        assertEquals(ChangeOperation.UPSERT, ChangeOperation.fromWire("upsert"))
        assertEquals(ChangeOperation.DELETE, ChangeOperation.fromWire("delete"))
        assertEquals(MutationStatus.ACCEPTED, MutationStatus.fromWire("accepted"))
        assertEquals(MutationStatus.DUPLICATE, MutationStatus.fromWire("duplicate"))
        assertEquals(MutationStatus.REJECTED, MutationStatus.fromWire("rejected"))
        assertRejects { AggregateType.fromWire("WORKOUT") }
        assertRejects { ChangeOperation.fromWire("Upsert") }
        assertRejects { MutationStatus.fromWire("ACCEPTED") }
    }

    @Test
    fun envelopes_rejectMismatchedAndUnknownShapes() {
        // Upsert without a record; delete carrying one.
        assertRejects {
            decodePullResult(page(batchJson("c", upsertJson("workout", WORKOUT, 3L, workoutRecordJson()).apply { remove("record") })))
        }
        val deleteWithRecord = deleteJson("workout", WORKOUT, 3L, explicitNull = false)
        deleteWithRecord.put("record", workoutRecordJson())
        assertRejects { decodePullResult(page(batchJson("c", deleteWithRecord))) }
        // Identity, revision, and record-type mismatches.
        assertRejects {
            decodePullResult(
                page(
                    batchJson(
                        "c",
                        upsertJson("workout", UUID.randomUUID().toString(), 3L, workoutRecordJson()),
                    ),
                ),
            )
        }
        assertRejects {
            decodePullResult(
                page(batchJson("c", upsertJson("workout", WORKOUT, 9L, workoutRecordJson()))),
            )
        }
        assertRejects {
            decodePullResult(
                page(batchJson("c", upsertJson("routine", ROUTINE, 2L, workoutRecordJson()))),
            )
        }
        // Unknown discriminators and malformed IDs/revisions.
        assertRejects {
            decodePullResult(page(batchJson("c", upsertJson("cat", WORKOUT, 3L, workoutRecordJson()))))
        }
        assertRejects {
            decodePullResult(
                page(
                    batchJson(
                        "c",
                        upsertJson("workout", WORKOUT, 3L, workoutRecordJson()).apply {
                            put("operation", "merge")
                        },
                    ),
                ),
            )
        }
        assertRejects {
            decodePullResult(page(batchJson("c", upsertJson("workout", "nope", 3L, workoutRecordJson()))))
        }
        assertRejects {
            decodePullResult(page(batchJson("", upsertJson("workout", WORKOUT, 3L, workoutRecordJson()))))
        }
        assertRejects {
            decodePullResult(
                page(
                    batchJson(
                        "c",
                        upsertJson("workout", WORKOUT, 3L, workoutRecordJson()),
                        upsertJson("workout", WORKOUT, 3L, workoutRecordJson()),
                    ),
                ),
            )
        }
        assertRejects { decodePullResult(page(JSONObject().apply {
            put("batchId", UUID.randomUUID().toString())
            put("cursor", "c")
            put("changes", JSONArray())
        })) }
    }

    @Test
    fun pushOutcomes_enforceExactlyOneResultShape() {
        fun outcome(status: String, errorCode: Any?, batch: JSONObject?): JSONObject =
            JSONObject().apply {
                put("mutationId", MUTATION)
                put("status", status)
                if (errorCode == null) put("errorCode", JSONObject.NULL) else put("errorCode", errorCode)
                if (batch == null) put("batch", JSONObject.NULL) else put("batch", batch)
            }
        val acceptedBatch = batchJson("c", upsertJson("workout", WORKOUT, 3L, workoutRecordJson()))

        assertRejects {
            decodePushResult(pushOf(outcome("accepted", null, null)))
        }
        assertRejects {
            decodePushResult(pushOf(outcome("accepted", "boom", acceptedBatch)))
        }
        assertRejects {
            decodePushResult(pushOf(outcome("rejected", null, null)))
        }
        assertRejects {
            decodePushResult(pushOf(outcome("rejected", "boom", acceptedBatch)))
        }
        assertRejects {
            decodePushResult(pushOf(outcome("duplicate", "boom", acceptedBatch)))
        }
        assertRejects {
            decodePushResult(pushOf(outcome("duplicate", null, null)))
        }
        assertRejects {
            decodePushResult(pushOf(outcome("pending", null, null)))
        }
        // Both duplicate replays are valid, as is an empty result list.
        val duplicateAccepted = decodePushResult(pushOf(outcome("duplicate", null, acceptedBatch)))
        assertEquals(
            duplicateAccepted,
            decodePushResult(encodePushResult(duplicateAccepted).toString()),
        )
        val duplicateRejected = decodePushResult(pushOf(outcome("duplicate", "gone", null)))
        assertEquals(
            duplicateRejected,
            decodePushResult(encodePushResult(duplicateRejected).toString()),
        )
        val rejected = decodePushResult(pushOf(outcome("rejected", "gone", null)))
        assertEquals(rejected, decodePushResult(encodePushResult(rejected).toString()))
        decodePushResult(JSONObject().apply { put("results", JSONArray()) }.toString())
        // Duplicate outcome IDs rejected.
        assertRejects {
            decodePushResult(
                JSONObject().apply {
                    put(
                        "results",
                        JSONArray(
                            listOf(
                                outcome("rejected", "gone", null),
                                outcome("rejected", "gone", null),
                            ),
                        ),
                    )
                }.toString(),
            )
        }
    }

    @Test
    fun pullPages_keepOpaqueOrderAndRejectBadPages() {
        val first = batchJson("cursor-B", upsertJson("workout", WORKOUT, 3L, workoutRecordJson()), batchId = UUID.randomUUID().toString())
        val second = batchJson("cursor-A", upsertJson("routine", ROUTINE, 2L, routineRecordJson()), batchId = UUID.randomUUID().toString())
        val decoded = decodePullResult(
            JSONObject().apply {
                put("batches", JSONArray(listOf(first, second)))
                put("nextCursor", "cursor-A")
                put("hasMore", true)
            }.toString(),
        )
        assertEquals(listOf("cursor-B", "cursor-A"), decoded.batches.map { it.cursor })
        assertEquals(decoded, decodePullResult(encodePullResult(decoded).toString()))

        // Empty terminal page is valid.
        val terminal = decodePullResult(
            JSONObject().apply {
                put("batches", JSONArray())
                put("nextCursor", "cursor-A")
                put("hasMore", false)
            }.toString(),
        )
        assertTrue(terminal.batches.isEmpty())

        assertRejects {
            decodePullResult(
                JSONObject().apply {
                    put("batches", JSONArray(listOf(first, first)))
                    put("nextCursor", "cursor-B")
                    put("hasMore", false)
                }.toString(),
            )
        }
        assertRejects {
            decodePullResult(
                JSONObject().apply {
                    put("batches", JSONArray())
                    put("nextCursor", "cursor-A")
                    put("hasMore", true)
                }.toString(),
            )
        }
        assertRejects {
            decodePullResult(
                JSONObject().apply {
                    put("batches", JSONArray(listOf(first, second)))
                    put("nextCursor", "cursor-B")
                    put("hasMore", false)
                }.toString(),
            )
        }
        assertRejects { decodePullResult("[]") }
        assertRejects {
            decodePullResult(
                JSONObject().apply {
                    put("batches", JSONArray(listOf(first)))
                    put("nextCursor", "cursor-A")
                    put("hasMore", false)
                }.toString() + " junk",
            )
        }
    }

    @Test
    fun snapshotPages_acceptOnlyUniqueUpsertsWithTokenRules() {
        val records = JSONArray(
            listOf(
                upsertJson("workout", WORKOUT, 3L, workoutRecordJson()),
                upsertJson("customExercise", CUSTOM, 4L, customRecordJson()),
            ),
        )
        val decoded = decodeSnapshotResult(
            JSONObject().apply {
                put("records", records)
                put("baselineCursor", "baseline-1")
                put("nextPageToken", "page-2")
                put("hasMore", true)
            }.toString(),
        )
        assertEquals(2, decoded.records.size)
        assertEquals("page-2", decoded.nextPageToken)
        assertEquals(decoded, decodeSnapshotResult(encodeSnapshotResult(decoded).toString()))

        // Empty terminal snapshot is valid.
        val terminal = decodeSnapshotResult(
            JSONObject().apply {
                put("records", JSONArray())
                put("baselineCursor", "baseline-1")
                put("hasMore", false)
            }.toString(),
        )
        assertTrue(terminal.records.isEmpty())
        assertNull(terminal.nextPageToken)

        assertRejects {
            decodeSnapshotResult(
                JSONObject().apply {
                    put(
                        "records",
                        JSONArray(listOf(deleteJson("workout", WORKOUT, 3L, explicitNull = true))),
                    )
                    put("baselineCursor", "baseline-1")
                    put("hasMore", false)
                }.toString(),
            )
        }
        assertRejects {
            decodeSnapshotResult(
                JSONObject().apply {
                    put(
                        "records",
                        JSONArray(
                            listOf(
                                upsertJson("workout", WORKOUT, 3L, workoutRecordJson()),
                                upsertJson("workout", WORKOUT, 3L, workoutRecordJson()),
                            ),
                        ),
                    )
                    put("baselineCursor", "baseline-1")
                    put("hasMore", false)
                }.toString(),
            )
        }
        assertRejects {
            decodeSnapshotResult(
                JSONObject().apply {
                    put("records", records)
                    put("baselineCursor", "")
                    put("hasMore", false)
                }.toString(),
            )
        }
        assertRejects {
            decodeSnapshotResult(
                JSONObject().apply {
                    put("records", records)
                    put("baselineCursor", "baseline-1")
                    put("hasMore", true)
                }.toString(),
            )
        }
        assertRejects {
            decodeSnapshotResult(
                JSONObject().apply {
                    put("records", JSONArray())
                    put("baselineCursor", "baseline-1")
                    put("nextPageToken", "page-2")
                    put("hasMore", false)
                }.toString(),
            )
        }
    }

    @Test
    fun snapshotWhitespaceToken_nonemptyPreservedExactlyAndEmptyRejected() {
        // Owner resolution (R08a): minimal nonblank→nonempty correction.
        // Whitespace-only NONEMPTY opaque tokens are valid and preserved
        // exactly; absent/empty continuations stay rejected. Representative
        // raw decode/encode exact-string coverage only; the existing
        // snapshotPages_acceptOnlyUniqueUpsertsWithTokenRules matrix stands.
        val wsToken = "  \t "
        val records = JSONArray(
            listOf(upsertJson("workout", WORKOUT, 3L, workoutRecordJson())),
        )
        val decoded = decodeSnapshotResult(
            JSONObject().apply {
                put("records", records)
                put("baselineCursor", "baseline-1")
                put("nextPageToken", wsToken)
                put("hasMore", true)
            }.toString(),
        )
        assertEquals(wsToken, decoded.nextPageToken)
        assertEquals(decoded, decodeSnapshotResult(encodeSnapshotResult(decoded).toString()))
        assertEquals(
            wsToken,
            decodeSnapshotResult(encodeSnapshotResult(decoded).toString()).nextPageToken,
        )

        // Empty-string continuation on a nonterminal page stays rejected.
        assertRejects {
            decodeSnapshotResult(
                JSONObject().apply {
                    put("records", records)
                    put("baselineCursor", "baseline-1")
                    put("nextPageToken", "")
                    put("hasMore", true)
                }.toString(),
            )
        }
        // Whitespace continuation on a terminal page stays rejected.
        assertRejects {
            decodeSnapshotResult(
                JSONObject().apply {
                    put("records", records)
                    put("baselineCursor", "baseline-1")
                    put("nextPageToken", wsToken)
                    put("hasMore", false)
                }.toString(),
            )
        }
    }

    @Test
    fun directModels_validateFullAggregateShapeAndNormalize() {
        // Invalid status with otherwise matching identity/revision.
        val badStatus = WorkoutRecordModel(
            WORKOUT, "W", "archived", 1_000L, null, 3L,
            listOf(
                WorkoutExerciseModel(
                    WORKOUT_EXERCISE, CATALOGUE, 0, "", 60,
                    listOf(WorkoutSetModel(WORKOUT_SET, 0, 5, 50.0, true, null)),
                    "Squat",
                ),
            ),
        )
        assertRejects {
            validateSyncChange(
                SyncChangeModel(
                    AggregateType.WORKOUT, WORKOUT, ChangeOperation.UPSERT, 3L,
                    CanonicalRecord.Workout(badStatus),
                ),
            )
        }

        // Duplicate child IDs across routine exercises with matching identity/revision.
        val sharedSet = RoutineSetModel(ROUTINE_SET, 0, 5, 50.0)
        val dupSets = RoutineRecordModel(
            ROUTINE, "R", false, 2L,
            listOf(
                RoutineExerciseModel(ROUTINE_EXERCISE, CATALOGUE, 0, 30, listOf(sharedSet), "A"),
                RoutineExerciseModel(UUID.randomUUID().toString(), CATALOGUE, 1, 30, listOf(sharedSet), "B"),
            ),
        )
        assertRejects {
            validateSyncChange(
                SyncChangeModel(
                    AggregateType.ROUTINE, ROUTINE, ChangeOperation.UPSERT, 2L,
                    CanonicalRecord.Routine(dupSets),
                ),
            )
        }

        // Negative equipment quantity with matching identity/revision.
        val badQuantity = CustomExerciseRecordModel(
            CUSTOM, "Custom", "reps", null, null, null, null, null, true, 4L,
            listOf(MuscleGroupRefModel(MUSCLE, "Chest", "primary")),
            listOf(EquipmentRefModel(UUID.randomUUID().toString(), "Cable", -1)),
        )
        assertRejects {
            validateSyncChange(
                SyncChangeModel(
                    AggregateType.CUSTOM_EXERCISE, CUSTOM, ChangeOperation.UPSERT, 4L,
                    CanonicalRecord.CustomExercise(badQuantity),
                ),
            )
        }

        // Normalization sorts shuffled children while batch order is preserved.
        val shuffled = WorkoutRecordModel(
            WORKOUT, "W", "finished", 1_000L, 2_000L, 3L,
            listOf(
                WorkoutExerciseModel(
                    UUID.randomUUID().toString(), CATALOGUE, 1, "", 30,
                    listOf(WorkoutSetModel(UUID.randomUUID().toString(), 0, 5, 50.0, true, null)),
                    "Beta",
                ),
                WorkoutExerciseModel(
                    WORKOUT_EXERCISE, CATALOGUE, 0, "", 60,
                    listOf(WorkoutSetModel(WORKOUT_SET, 0, 5, 50.0, true, null)),
                    "Alpha",
                ),
            ),
        )
        val batch = validateSyncBatch(
            SyncBatchModel(
                BATCH, "cursor-1",
                listOf(
                    SyncChangeModel(
                        AggregateType.WORKOUT, WORKOUT, ChangeOperation.UPSERT, 3L,
                        CanonicalRecord.Workout(shuffled),
                    ),
                    SyncChangeModel(AggregateType.ROUTINE, ROUTINE, ChangeOperation.DELETE, 2L, null),
                ),
            ),
        )
        assertEquals(
            listOf(ChangeOperation.UPSERT, ChangeOperation.DELETE),
            batch.changes.map { it.operation },
        )
        val normalized = (batch.changes[0].record as CanonicalRecord.Workout).record
        assertEquals(listOf(0, 1), normalized.exercises.map { it.position })
        assertEquals(listOf("Alpha", "Beta"), normalized.exercises.map { it.exerciseName })
    }

    private fun page(batch: JSONObject): String =
        JSONObject().apply {
            put("batches", JSONArray(listOf(batch)))
            put("nextCursor", batch.getString("cursor"))
            put("hasMore", false)
        }.toString()

    private fun pushOf(vararg outcomes: JSONObject): String =
        JSONObject().apply {
            put("results", JSONArray(outcomes.toList()))
        }.toString()
}
