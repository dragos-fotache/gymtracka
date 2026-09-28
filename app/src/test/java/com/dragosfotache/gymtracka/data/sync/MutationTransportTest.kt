package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.codec.decodeMutationEnvelope
import com.dragosfotache.gymtracka.data.sync.codec.encodeMutationEnvelope
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CustomExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.ImportRecord
import com.dragosfotache.gymtracka.data.sync.model.MAX_WIRE_MILLIS
import com.dragosfotache.gymtracka.data.sync.model.MIN_WIRE_MILLIS
import com.dragosfotache.gymtracka.data.sync.model.MutationEnvelopeModel
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.model.RoutineInputModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutImportModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutInputModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutSetModel
import com.dragosfotache.gymtracka.data.sync.model.validateMutationEnvelope
import com.dragosfotache.gymtracka.data.sync.model.validateWorkoutImportModel
import com.dragosfotache.gymtracka.data.sync.model.validateWorkoutInputModel
import com.dragosfotache.gymtracka.data.sync.model.validateWorkoutRecordModel
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Typed mutation envelopes: every supported kind, imports, and violations. */
class MutationTransportTest {

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
        const val MUTATION = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    }

    private fun uuid(): String = UUID.randomUUID().toString()

    private fun workoutInputJson(): JSONObject = JSONObject(
        """
        {"id":"$WORKOUT","name":"W","status":"active",
         "startedAt":"2026-09-24T14:00:00Z",
         "exercises":[{"id":"$WORKOUT_EXERCISE","exerciseId":"$CATALOGUE",
          "position":0,"notes":"","restDurationSeconds":60,
          "sets":[{"id":"$WORKOUT_SET","position":0,"repetitions":null,"weightKg":null,
           "isCompleted":false,"restSeconds":null}]}]}
        """.trimIndent(),
    )

    private fun finishedImportJson(): JSONObject = JSONObject(
        """
        {"id":"$WORKOUT","name":"W","status":"finished",
         "startedAt":"2026-09-24T14:00:00Z","finishedAt":"2026-09-24T15:00:00Z",
         "exercises":[{"id":"$WORKOUT_EXERCISE","exerciseId":"$CATALOGUE",
          "position":0,"notes":" Hard ","restDurationSeconds":90,
          "sets":[{"id":"$WORKOUT_SET","position":0,"repetitions":8,"weightKg":100.0,
           "isCompleted":true,"restSeconds":87}]}]}
        """.trimIndent(),
    )

    private fun routineInputJson(draft: Boolean = false, name: String = "R"): JSONObject =
        JSONObject().apply {
            put("id", ROUTINE)
            put("name", name)
            put("isDraft", draft)
            put(
                "exercises",
                JSONArray(
                    listOf(
                        JSONObject().apply {
                            put("id", ROUTINE_EXERCISE)
                            put("exerciseId", CATALOGUE)
                            put("position", 0)
                            put("restDurationSeconds", 30)
                            put(
                                "sets",
                                JSONArray(
                                    listOf(
                                        JSONObject().apply {
                                            put("id", ROUTINE_SET)
                                            put("position", 0)
                                            put("repetitions", 5)
                                            put("weightKg", 50.0)
                                        },
                                    ),
                                ),
                            )
                        },
                    ),
                ),
            )
        }

    private fun customInputJson(): JSONObject = JSONObject(
        """
        {"id":"$CUSTOM","name":"Custom","primaryMuscleGroupId":"$MUSCLE",
         "secondaryMuscleGroupIds":[],"equipmentId":null,"equipmentNumItems":null,
         "category":null,"difficultyLevel":null,"forceType":null,"mechanics":null,
         "instructions":null}
        """.trimIndent(),
    )

    private fun envelopeJson(
        mutationId: String = MUTATION,
        kind: String,
        aggregateType: String,
        aggregateId: String,
        localGeneration: Long = 2L,
        knownServerRevision: Long? = null,
        payload: JSONObject,
    ): String = JSONObject().apply {
        put("mutationId", mutationId)
        put("type", kind)
        put("aggregateType", aggregateType)
        put("aggregateId", aggregateId)
        put("localGeneration", localGeneration)
        if (knownServerRevision == null) put("knownServerRevision", JSONObject.NULL)
        else put("knownServerRevision", knownServerRevision)
        put("payload", payload)
    }.toString()

    private fun assertRejects(block: () -> Unit) {
        try {
            block()
        } catch (_: SyncModelException) {
            return
        }
        fail("Expected SyncModelException")
    }

    @Test
    fun mutations_roundTripEverySupportedKind() {
        val cases = listOf(
            Triple(MutationKind.WORKOUT_START, AggregateType.WORKOUT, WORKOUT) to
                JSONObject().apply {
                    put("id", WORKOUT)
                    put("name", "Morning Workout")
                    put("startedAt", "2026-09-24T14:00:00Z")
                },
            Triple(MutationKind.WORKOUT_UPSERT, AggregateType.WORKOUT, WORKOUT) to workoutInputJson(),
            Triple(MutationKind.WORKOUT_FINISH, AggregateType.WORKOUT, WORKOUT) to
                JSONObject().apply { put("finishedAt", "2026-09-24T15:00:00Z") },
            Triple(MutationKind.ROUTINE_UPSERT, AggregateType.ROUTINE, ROUTINE) to
                routineInputJson(draft = true, name = ""),
            Triple(MutationKind.ROUTINE_SAVE, AggregateType.ROUTINE, ROUTINE) to
                JSONObject().apply { put("name", "Saved") },
            Triple(MutationKind.CUSTOM_EXERCISE_UPSERT, AggregateType.CUSTOM_EXERCISE, CUSTOM) to
                customInputJson(),
            Triple(MutationKind.AGGREGATE_DELETE, AggregateType.WORKOUT, WORKOUT) to JSONObject(),
        )
        cases.forEachIndexed { index, (key, payload) ->
            val (kind, aggregateType, aggregateId) = key
            val known = if (index == 0) 9L else null
            val decoded = decodeMutationEnvelope(
                envelopeJson(
                    mutationId = uuid(),
                    kind = kind.wireValue,
                    aggregateType = aggregateType.wireValue,
                    aggregateId = aggregateId,
                    knownServerRevision = known,
                    payload = payload,
                ),
            )
            assertEquals(kind, decoded.kind)
            assertEquals(aggregateType, decoded.aggregateType)
            assertEquals(aggregateId, decoded.aggregateId)
            assertEquals(2L, decoded.localGeneration)
            assertEquals(known, decoded.knownServerRevision)
            assertEquals(decoded, decodeMutationEnvelope(encodeMutationEnvelope(decoded).toString()))
        }

        // Aggregate delete accepts every supported target type.
        listOf(AggregateType.WORKOUT to WORKOUT, AggregateType.ROUTINE to ROUTINE, AggregateType.CUSTOM_EXERCISE to CUSTOM)
            .forEach { (aggregateType, aggregateId) ->
                val decoded = decodeMutationEnvelope(
                    envelopeJson(
                        kind = MutationKind.AGGREGATE_DELETE.wireValue,
                        aggregateType = aggregateType.wireValue,
                        aggregateId = aggregateId,
                        payload = JSONObject(),
                    ),
                )
                assertTrue(decoded.payload is MutationPayload.AggregateDelete)
            }
    }

    @Test
    fun imports_preserveActiveFinishedFidelity() {
        val active = decodeMutationEnvelope(
            envelopeJson(
                kind = "aggregate.import",
                aggregateType = "workout",
                aggregateId = WORKOUT,
                payload = JSONObject().apply { put("record", workoutInputJson()) },
            ),
        )
        val activeRecord = (active.payload as MutationPayload.AggregateImport).record
        assertTrue(activeRecord is ImportRecord.Workout)
        assertNull((activeRecord as ImportRecord.Workout).input.finishedAtMillis)
        assertEquals(active, decodeMutationEnvelope(encodeMutationEnvelope(active).toString()))

        val finished = decodeMutationEnvelope(
            envelopeJson(
                kind = "aggregate.import",
                aggregateType = "workout",
                aggregateId = WORKOUT,
                payload = JSONObject().apply { put("record", finishedImportJson()) },
            ),
        )
        val finishedInput = (
            finished.payload as MutationPayload.AggregateImport
            ).record as ImportRecord.Workout
        assertEquals("finished", finishedInput.input.status)
        assertEquals(1_790_262_000_000L, finishedInput.input.finishedAtMillis)
        assertEquals(" Hard ", finishedInput.input.exercises.single().notes)
        assertEquals(87, finishedInput.input.exercises.single().sets.single().restSeconds)
        assertEquals(listOf(0), finishedInput.input.exercises.map { it.position })
        assertEquals(finished, decodeMutationEnvelope(encodeMutationEnvelope(finished).toString()))

        // Import payloads carry no revision, display names, or derived volume.
        val payloadJson = encodeMutationEnvelope(finished).getJSONObject("payload")
            .getJSONObject("record")
        assertTrue(!payloadJson.has("revision"))
        assertTrue(!payloadJson.has("totalVolumeKg"))
        assertTrue(!payloadJson.getJSONArray("exercises").getJSONObject(0).has("exerciseName"))

        // Routine and custom imports reuse their input structures.
        val routineImport = decodeMutationEnvelope(
            envelopeJson(
                kind = "aggregate.import",
                aggregateType = "routine",
                aggregateId = ROUTINE,
                payload = JSONObject().apply { put("record", routineInputJson()) },
            ),
        )
        assertEquals(ROUTINE, (routineImport.payload as MutationPayload.AggregateImport).record.aggregateId)
        val customImport = decodeMutationEnvelope(
            envelopeJson(
                kind = "aggregate.import",
                aggregateType = "customExercise",
                aggregateId = CUSTOM,
                payload = JSONObject().apply { put("record", customInputJson()) },
            ),
        )
        assertEquals(CUSTOM, (customImport.payload as MutationPayload.AggregateImport).record.aggregateId)
    }

    @Test
    fun mutations_rejectEnvelopeViolations() {
        val startPayload = JSONObject().apply {
            put("id", WORKOUT)
            put("name", "W")
            put("startedAt", "2026-09-24T14:00:00Z")
        }
        // Unknown kind.
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(kind = "workout.fly", aggregateType = "workout", aggregateId = WORKOUT, payload = startPayload),
            )
        }
        // Kind/target mismatch.
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(kind = "workout.start", aggregateType = "routine", aggregateId = ROUTINE, payload = startPayload),
            )
        }
        // Payload root ID mismatch.
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(
                    kind = "workout.start",
                    aggregateType = "workout",
                    aggregateId = uuid(),
                    payload = startPayload,
                ),
            )
        }
        // Malformed IDs and nonpositive generations/revisions.
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(
                    kind = "workout.finish",
                    aggregateType = "workout",
                    aggregateId = "nope",
                    payload = JSONObject().apply { put("finishedAt", "2026-09-24T15:00:00Z") },
                ),
            )
        }
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(
                    kind = "workout.finish",
                    aggregateType = "workout",
                    aggregateId = WORKOUT,
                    localGeneration = 0L,
                    payload = JSONObject().apply { put("finishedAt", "2026-09-24T15:00:00Z") },
                ),
            )
        }
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(
                    kind = "workout.finish",
                    aggregateType = "workout",
                    aggregateId = WORKOUT,
                    knownServerRevision = 0L,
                    payload = JSONObject().apply { put("finishedAt", "2026-09-24T15:00:00Z") },
                ),
            )
        }
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(
                    kind = "workout.upsert",
                    aggregateType = "workout",
                    aggregateId = WORKOUT,
                    payload = workoutInputJson(),
                ).let { JSONObject(it).apply { remove("payload") } }.toString(),
            )
        }
        // Kind/payload shape mismatch and strict bodies.
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(
                    kind = "workout.upsert",
                    aggregateType = "workout",
                    aggregateId = WORKOUT,
                    payload = JSONObject().apply { put("finishedAt", "2026-09-24T15:00:00Z") },
                ),
            )
        }
        // Unrepresented envelope fields never travel verbatim.
        assertRejects {
            decodeMutationEnvelope(
                JSONObject(
                    envelopeJson(
                        kind = "workout.finish",
                        aggregateType = "workout",
                        aggregateId = WORKOUT,
                        payload = JSONObject().apply { put("finishedAt", "2026-09-24T15:00:00Z") },
                    ),
                ).apply {
                    put("ownerId", WORKOUT)
                    put("accessToken", "secret")
                }.toString(),
            )
        }
        // Unrepresented nested input fields never travel verbatim.
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(
                    kind = "workout.upsert",
                    aggregateType = "workout",
                    aggregateId = WORKOUT,
                    payload = workoutInputJson().apply {
                        put("revision", 3)
                        getJSONArray("exercises").getJSONObject(0).put("exerciseName", "Squat")
                    },
                ),
            )
        }
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(
                    kind = "customExercise.upsert",
                    aggregateType = "customExercise",
                    aggregateId = CUSTOM,
                    payload = customInputJson().apply { put("isUserCreated", true) },
                ),
            )
        }
        // Delete payloads must actually be empty.
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(
                    kind = "aggregate.delete",
                    aggregateType = "workout",
                    aggregateId = WORKOUT,
                    payload = JSONObject().apply { put("reason", "cleanup") },
                ),
            )
        }
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(
                    kind = "workout.start",
                    aggregateType = "workout",
                    aggregateId = WORKOUT,
                    payload = startPayload,
                ) + " trailing",
            )
        }
        // Server-state-invalid commands stay representable for later service rejection.
        val blankSave = decodeMutationEnvelope(
            envelopeJson(
                kind = "routine.save",
                aggregateType = "routine",
                aggregateId = ROUTINE,
                payload = JSONObject().apply { put("name", "") },
            ),
        )
        assertEquals("", (blankSave.payload as MutationPayload.RoutineSave).name)
        val deleteMissing = decodeMutationEnvelope(
            envelopeJson(
                kind = "aggregate.delete",
                aggregateType = "workout",
                aggregateId = uuid(),
                payload = JSONObject(),
            ),
        )
        assertTrue(deleteMissing.payload is MutationPayload.AggregateDelete)
    }

    @Test
    fun mutations_enforceTimestampRangeOnDirectModels() {
        fun startEnvelope(startedAt: Long): MutationEnvelopeModel =
            MutationEnvelopeModel(
                MUTATION,
                MutationKind.WORKOUT_START,
                AggregateType.WORKOUT,
                WORKOUT,
                1L,
                null,
                MutationPayload.WorkoutStart(WORKOUT, "W", startedAt),
            )
        assertRejects {
            validateMutationEnvelope(
                startEnvelope(Long.MAX_VALUE),
            )
        }
        assertRejects {
            validateMutationEnvelope(
                startEnvelope(Long.MIN_VALUE),
            )
        }
        val boundary = validateMutationEnvelope(
            startEnvelope(MAX_WIRE_MILLIS),
        )
        assertEquals(MAX_WIRE_MILLIS, (boundary.payload as MutationPayload.WorkoutStart).startedAtMillis)
        validateMutationEnvelope(startEnvelope(MIN_WIRE_MILLIS))

        assertRejects {
            validateMutationEnvelope(
                MutationEnvelopeModel(
                    MUTATION,
                    MutationKind.WORKOUT_FINISH,
                    AggregateType.WORKOUT,
                    WORKOUT,
                    1L,
                    null,
                    MutationPayload.WorkoutFinish(Long.MAX_VALUE),
                ),
            )
        }
    }

    @Test
    fun mutations_normalizeDirectModelChildrenOnValidateAndEncode() {
        val entry = uuid()
        val firstSet = uuid()
        val secondSet = uuid()
        val shuffled = MutationEnvelopeModel(
            MUTATION,
            MutationKind.WORKOUT_UPSERT,
            AggregateType.WORKOUT,
            WORKOUT,
            1L,
            null,
            MutationPayload.WorkoutUpsert(
                WorkoutInputModel(
                    WORKOUT, "W", "active", 1_790_258_400_000L,
                    listOf(
                        WorkoutExerciseInputModel(
                            entry, CATALOGUE, 0, "", 30,
                            listOf(
                                WorkoutSetModel(secondSet, 1, 6, 60.0, false, null),
                                WorkoutSetModel(firstSet, 0, 5, 50.0, true, null),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val validated = validateMutationEnvelope(shuffled)
        val validatedSets = (validated.payload as MutationPayload.WorkoutUpsert)
            .input.exercises.single().sets
        assertEquals(listOf(0, 1), validatedSets.map { it.position })
        assertEquals(listOf(firstSet, secondSet), validatedSets.map { it.id })

        val decoded = decodeMutationEnvelope(encodeMutationEnvelope(shuffled).toString())
        val decodedSets = (decoded.payload as MutationPayload.WorkoutUpsert)
            .input.exercises.single().sets
        assertEquals(listOf(0, 1), decodedSets.map { it.position })
        assertEquals(listOf(firstSet, secondSet), decodedSets.map { it.id })
    }

    @Test
    fun mutations_matchReorderedChildrenByIdForBoundaryChecks() {
        // Validators sort children, so the boundary check must match array elements
        // by preserved ID: reversed exercise order with different set counts is valid.
        val firstEntry = uuid()
        val secondEntry = uuid()
        val payload = JSONObject(
            """
            {"id":"$WORKOUT","name":"W","status":"active",
             "startedAt":"2026-09-24T14:00:00Z",
             "exercises":[
              {"id":"$secondEntry","exerciseId":"$CATALOGUE",
               "position":1,"notes":"","restDurationSeconds":30,
               "sets":[{"id":"${uuid()}","position":0,"repetitions":null,"weightKg":null,
                "isCompleted":false,"restSeconds":null}]},
              {"id":"$firstEntry","exerciseId":"$CATALOGUE",
               "position":0,"notes":"","restDurationSeconds":60,
               "sets":[{"id":"${uuid()}","position":1,"repetitions":6,"weightKg":60.0,
                "isCompleted":false,"restSeconds":null},
               {"id":"${uuid()}","position":0,"repetitions":5,"weightKg":50.0,
                "isCompleted":true,"restSeconds":null}]}]}
            """.trimIndent(),
        )
        val decoded = decodeMutationEnvelope(
            envelopeJson(
                kind = "workout.upsert",
                aggregateType = "workout",
                aggregateId = WORKOUT,
                payload = payload,
            ),
        )
        val exercises = (decoded.payload as MutationPayload.WorkoutUpsert).input.exercises
        assertEquals(listOf(0, 1), exercises.map { it.position })
        assertEquals(listOf(firstEntry, secondEntry), exercises.map { it.id })
        assertEquals(2, exercises[0].sets.size)
        assertEquals(1, exercises[1].sets.size)
    }

    @Test
    fun mutations_checkDirectModelUtcRangeAndMalformedImports() {
        val entry = uuid()
        fun inputAt(startedAt: Long) = WorkoutInputModel(
            WORKOUT, "W", "active", startedAt,
            listOf(
                WorkoutExerciseInputModel(
                    entry, CATALOGUE, 0, "", 30,
                    listOf(WorkoutSetModel(uuid(), 0, null, null, false, null)),
                ),
            ),
        )
        assertRejects {
            validateWorkoutInputModel(inputAt(Long.MAX_VALUE))
        }
        assertRejects {
            validateWorkoutInputModel(inputAt(Long.MIN_VALUE))
        }
        assertEquals(
            MAX_WIRE_MILLIS,
            validateWorkoutInputModel(inputAt(MAX_WIRE_MILLIS)).startedAtMillis,
        )
        assertEquals(
            MIN_WIRE_MILLIS,
            validateWorkoutInputModel(inputAt(MIN_WIRE_MILLIS)).startedAtMillis,
        )

        val recordAt = { startedAt: Long, finishedAt: Long? ->
            validateWorkoutRecordModel(
                WorkoutRecordModel(
                    WORKOUT, "W", "finished", startedAt, finishedAt, 3L,
                    listOf(
                        WorkoutExerciseModel(
                            entry, CATALOGUE, 0, "", 60,
                            listOf(WorkoutSetModel(uuid(), 0, 5, 50.0, true, null)),
                            "Squat",
                        ),
                    ),
                ),
            )
        }
        assertRejects { recordAt(Long.MAX_VALUE, 2_000L) }
        assertRejects { recordAt(1_000L, Long.MAX_VALUE) }
        assertEquals(MAX_WIRE_MILLIS, recordAt(MAX_WIRE_MILLIS, MAX_WIRE_MILLIS).startedAtMillis)

        val importAt = { startedAt: Long, finishedAt: Long?, status: String, complete: Boolean ->
            validateWorkoutImportModel(
                WorkoutImportModel(
                    WORKOUT, "W", status, startedAt, finishedAt,
                    listOf(
                        WorkoutExerciseInputModel(
                            entry, CATALOGUE, 0, "", 30,
                            listOf(
                                WorkoutSetModel(
                                    uuid(), 0,
                                    if (complete) 5 else null,
                                    if (complete) 50.0 else null,
                                    complete, null,
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
        assertRejects { importAt(Long.MAX_VALUE, null, "active", false) }
        assertRejects { importAt(1_000L, Long.MAX_VALUE, "finished", true) }
        assertEquals(1_000L, importAt(1_000L, 2_000L, "finished", true).startedAtMillis)
        // Malformed imports reject: finished without completed survivors, active with finishedAt.
        assertRejects { importAt(1_000L, 2_000L, "finished", false) }
        assertRejects { importAt(1_000L, 2_000L, "active", false) }
        assertRejects {
            validateWorkoutImportModel(
                WorkoutImportModel(WORKOUT, "W", "archived", 1_000L, null, emptyList()),
            )
        }
    }

    @Test
    fun mutations_encodeDirectModelsToExpectedEnvelopes() {
        fun check(envelope: MutationEnvelopeModel, expected: JSONObject) {
            val encoded = encodeMutationEnvelope(envelope)
            assertTrue(jsonValueEquals(expected, encoded))
            assertEquals(
                validateMutationEnvelope(envelope),
                decodeMutationEnvelope(encoded.toString()),
            )
        }
        fun envelope(
            kind: MutationKind,
            aggregateType: AggregateType,
            aggregateId: String,
            payload: MutationPayload,
        ) = MutationEnvelopeModel(MUTATION, kind, aggregateType, aggregateId, 1L, null, payload)

        fun base(expectedType: String, expectedAggregateType: String, expectedId: String): JSONObject =
            JSONObject().apply {
                put("mutationId", MUTATION)
                put("type", expectedType)
                put("aggregateType", expectedAggregateType)
                put("aggregateId", expectedId)
                put("localGeneration", 1)
                put("knownServerRevision", JSONObject.NULL)
            }

        check(
            envelope(
                MutationKind.WORKOUT_START, AggregateType.WORKOUT, WORKOUT,
                MutationPayload.WorkoutStart(WORKOUT, "W", 1_790_258_400_000L),
            ),
            base("workout.start", "workout", WORKOUT).apply {
                put(
                    "payload",
                    JSONObject(
                        """{"id":"$WORKOUT","name":"W","startedAt":"2026-09-24T14:00:00Z"}""",
                    ),
                )
            },
        )
        check(
            envelope(
                MutationKind.WORKOUT_FINISH, AggregateType.WORKOUT, WORKOUT,
                MutationPayload.WorkoutFinish(1_790_262_000_000L),
            ),
            base("workout.finish", "workout", WORKOUT).apply {
                put("payload", JSONObject("""{"finishedAt":"2026-09-24T15:00:00Z"}"""))
            },
        )
        check(
            envelope(
                MutationKind.ROUTINE_SAVE, AggregateType.ROUTINE, ROUTINE,
                MutationPayload.RoutineSave("Saved"),
            ),
            base("routine.save", "routine", ROUTINE).apply {
                put("payload", JSONObject("""{"name":"Saved"}"""))
            },
        )
        check(
            envelope(
                MutationKind.AGGREGATE_DELETE, AggregateType.CUSTOM_EXERCISE, CUSTOM,
                MutationPayload.AggregateDelete,
            ),
            base("aggregate.delete", "customExercise", CUSTOM).apply {
                put("payload", JSONObject())
            },
        )
        check(
            envelope(
                MutationKind.ROUTINE_UPSERT, AggregateType.ROUTINE, ROUTINE,
                MutationPayload.RoutineUpsert(RoutineInputModel(ROUTINE, "", true, emptyList())),
            ),
            base("routine.upsert", "routine", ROUTINE).apply {
                put(
                    "payload",
                    JSONObject(
                        """{"id":"$ROUTINE","name":"","isDraft":true,"exercises":[]}""",
                    ),
                )
            },
        )
        check(
            envelope(
                MutationKind.CUSTOM_EXERCISE_UPSERT, AggregateType.CUSTOM_EXERCISE, CUSTOM,
                MutationPayload.CustomExerciseUpsert(
                    CustomExerciseInputModel(
                        CUSTOM, "Custom", null, emptyList(), null, null,
                        null, null, null, null, null,
                    ),
                ),
            ),
            base("customExercise.upsert", "customExercise", CUSTOM).apply {
                put(
                    "payload",
                    JSONObject(
                        """
                        {"id":"$CUSTOM","name":"Custom","primaryMuscleGroupId":null,
                         "secondaryMuscleGroupIds":[],"equipmentId":null,"equipmentNumItems":null,
                         "category":null,"difficultyLevel":null,"forceType":null,
                         "mechanics":null,"instructions":null}
                        """.trimIndent(),
                    ),
                )
            },
        )

        // Routine and custom imports round-trip fully through encode and decode.
        val routineImport = envelope(
            MutationKind.AGGREGATE_IMPORT, AggregateType.ROUTINE, ROUTINE,
            MutationPayload.AggregateImport(
                ImportRecord.Routine(RoutineInputModel(ROUTINE, "R", false, emptyList())),
            ),
        )
        assertEquals(
            routineImport,
            decodeMutationEnvelope(encodeMutationEnvelope(routineImport).toString()),
        )
        val customImport = envelope(
            MutationKind.AGGREGATE_IMPORT, AggregateType.CUSTOM_EXERCISE, CUSTOM,
            MutationPayload.AggregateImport(
                ImportRecord.CustomExercise(
                    CustomExerciseInputModel(
                        CUSTOM, "Custom", MUSCLE, emptyList(),
                        null, null, null, null, null, null, listOf("Step"),
                    ),
                ),
            ),
        )
        assertEquals(
            customImport,
            decodeMutationEnvelope(encodeMutationEnvelope(customImport).toString()),
        )
    }
}
