package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.codec.captureDurableRequest
import com.dragosfotache.gymtracka.data.sync.codec.decodeDurableRequest
import com.dragosfotache.gymtracka.data.sync.codec.decodeMutationEnvelope
import com.dragosfotache.gymtracka.data.sync.codec.encodeDurableRequest
import com.dragosfotache.gymtracka.data.sync.codec.encodeMutationEnvelope
import com.dragosfotache.gymtracka.data.sync.codec.encodePushMutationsBody
import com.dragosfotache.gymtracka.data.sync.model.AffectedRootModel
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Remaining semantic commands: child plans, additions, and targeted deletes. */
class SemanticCommandTest {

    private companion object {
        const val SOURCE_WORKOUT = "11111111-1111-1111-1111-111111111111"
        const val NEW_WORKOUT = "22222222-2222-2222-2222-222222222222"
        const val SOURCE_ROUTINE = "33333333-3333-3333-3333-333333333333"
        const val NEW_ROUTINE = "44444444-4444-4444-4444-444444444444"
        const val SOURCE_EXERCISE_A = "55555555-5555-5555-5555-555555555555"
        const val SOURCE_EXERCISE_B = "66666666-6666-6666-6666-666666666666"
        const val TARGET_EXERCISE_A = "77777777-7777-7777-7777-777777777777"
        const val TARGET_EXERCISE_B = "88888888-8888-8888-8888-888888888888"
        const val SOURCE_SET_A = "99999999-9999-9999-9999-999999999999"
        const val SOURCE_SET_B = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val TARGET_SET_A = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val TARGET_SET_B = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        const val CATALOGUE = "dddddddd-dddd-dddd-dddd-dddddddddddd"
        const val MUTATION = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
        const val EXTRA_SET = "ffffffff-ffff-ffff-ffff-ffffffffffff"
    }

    private fun uuid(): String = UUID.randomUUID().toString()

    private fun setBinding(source: String, target: String): JSONObject = JSONObject().apply {
        put("sourceSetId", source)
        put("id", target)
    }

    private fun exerciseBinding(
        source: String,
        target: String,
        sets: List<JSONObject>,
    ): JSONObject = JSONObject().apply {
        put("sourceExerciseId", source)
        put("id", target)
        put("sets", JSONArray(sets))
    }

    private fun childIdsJson(): JSONArray = JSONArray(
        listOf(
            exerciseBinding(
                SOURCE_EXERCISE_A, TARGET_EXERCISE_A,
                listOf(
                    setBinding(SOURCE_SET_A, TARGET_SET_A),
                    setBinding(SOURCE_SET_B, TARGET_SET_B),
                ),
            ),
            exerciseBinding(SOURCE_EXERCISE_B, TARGET_EXERCISE_B, emptyList()),
        ),
    )

    private fun envelopeJson(
        kind: String,
        aggregateType: String,
        aggregateId: String,
        payload: JSONObject,
        generation: Long = 3L,
    ): String = JSONObject().apply {
        put("mutationId", MUTATION)
        put("type", kind)
        put("aggregateType", aggregateType)
        put("aggregateId", aggregateId)
        put("localGeneration", generation)
        put("knownServerRevision", JSONObject.NULL)
        put("payload", payload)
    }.toString()

    private fun performAgainPayload(): JSONObject = JSONObject().apply {
        put("sourceWorkoutId", SOURCE_WORKOUT)
        put("id", NEW_WORKOUT)
        put("startedAt", "2026-09-24T14:00:00Z")
        put("childIds", childIdsJson())
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
    fun commands_roundTripEveryAddedKind() {
        val saveAsRoutinePayload = JSONObject().apply {
            put("sourceWorkoutId", SOURCE_WORKOUT)
            put("id", NEW_ROUTINE)
            put("name", "Saved Plan")
            put("childIds", childIdsJson())
        }
        val cases = listOf(
            Triple("workout.performAgain", "workout", NEW_WORKOUT) to performAgainPayload(),
            Triple("routine.startWorkout", "workout", NEW_WORKOUT) to JSONObject().apply {
                put("sourceRoutineId", SOURCE_ROUTINE)
                put("id", NEW_WORKOUT)
                put("startedAt", "2026-09-24T14:00:00Z")
                put("childIds", childIdsJson())
            },
            Triple("workout.saveAsRoutine", "routine", NEW_ROUTINE) to saveAsRoutinePayload,
            Triple("workout.addExercises", "workout", SOURCE_WORKOUT) to JSONObject().apply {
                put(
                    "additions",
                    JSONArray(
                        listOf(
                            JSONObject().apply {
                                put("id", TARGET_EXERCISE_A)
                                put("exerciseId", CATALOGUE)
                                put("setIds", JSONArray(listOf(TARGET_SET_A, TARGET_SET_B)))
                            },
                            JSONObject().apply {
                                put("id", TARGET_EXERCISE_B)
                                put("exerciseId", CATALOGUE)
                                put("setIds", JSONArray(listOf(uuid())))
                            },
                        ),
                    ),
                )
            },
            Triple("workout.replaceExercise", "workout", SOURCE_WORKOUT) to JSONObject().apply {
                put("workoutExerciseId", SOURCE_EXERCISE_A)
                put("exerciseId", CATALOGUE)
            },
            Triple("workout.deleteExercise", "workout", SOURCE_WORKOUT) to JSONObject().apply {
                put("workoutExerciseId", SOURCE_EXERCISE_A)
            },
            Triple("workout.deleteSet", "workout", SOURCE_WORKOUT) to JSONObject().apply {
                put("workoutSetId", SOURCE_SET_A)
            },
            Triple("routine.deleteExercise", "routine", SOURCE_ROUTINE) to JSONObject().apply {
                put("routineExerciseId", SOURCE_EXERCISE_A)
            },
            Triple("routine.deleteSet", "routine", SOURCE_ROUTINE) to JSONObject().apply {
                put("routineSetId", SOURCE_SET_A)
            },
        )
        cases.forEach { (key, payload) ->
            val (kind, aggregateType, aggregateId) = key
            val decoded = decodeMutationEnvelope(
                envelopeJson(kind, aggregateType, aggregateId, payload),
            )
            assertEquals(MutationKind.fromWire(kind), decoded.kind)
            assertEquals(AggregateType.fromWire(aggregateType), decoded.aggregateType)
            assertEquals(aggregateId, decoded.aggregateId)
            assertEquals(decoded, decodeMutationEnvelope(encodeMutationEnvelope(decoded).toString()))
        }
    }

    @Test
    fun commands_preserveOrderedBindingsAndRepeatReferences() {
        val decoded = decodeMutationEnvelope(
            envelopeJson("workout.performAgain", "workout", NEW_WORKOUT, performAgainPayload()),
        )
        val plan = (decoded.payload as MutationPayload.WorkoutPerformAgain).childIds
        assertEquals(listOf(SOURCE_EXERCISE_A, SOURCE_EXERCISE_B), plan.map { it.sourceExerciseId })
        assertEquals(listOf(TARGET_EXERCISE_A, TARGET_EXERCISE_B), plan.map { it.id })
        assertEquals(
            listOf(SOURCE_SET_A, SOURCE_SET_B),
            plan[0].sets.map { it.sourceSetId },
        )
        assertEquals(
            listOf(TARGET_SET_A, TARGET_SET_B),
            plan[0].sets.map { it.id },
        )
        assertTrue(plan[1].sets.isEmpty())

        // The same catalogue exercise may appear twice with distinct child IDs.
        val repeated = decodeMutationEnvelope(
            envelopeJson(
                "workout.addExercises", "workout", SOURCE_WORKOUT,
                JSONObject().apply {
                    put(
                        "additions",
                        JSONArray(
                            listOf(
                                JSONObject().apply {
                                    put("id", TARGET_EXERCISE_A)
                                    put("exerciseId", CATALOGUE)
                                    put("setIds", JSONArray(listOf(TARGET_SET_A)))
                                },
                                JSONObject().apply {
                                    put("id", TARGET_EXERCISE_B)
                                    put("exerciseId", CATALOGUE)
                                    put("setIds", JSONArray(listOf(TARGET_SET_B)))
                                },
                            ),
                        ),
                    )
                },
            ),
        )
        val additions = (repeated.payload as MutationPayload.WorkoutAddExercises).additions
        assertEquals(listOf(CATALOGUE, CATALOGUE), additions.map { it.exerciseId })
    }

    @Test
    fun commands_rejectStructuralViolations() {
        // Kind/target mismatches, including the routine-prefixed workout command.
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(
                    "routine.startWorkout", "routine", NEW_ROUTINE,
                    JSONObject().apply {
                        put("sourceRoutineId", SOURCE_ROUTINE)
                        put("id", NEW_ROUTINE)
                        put("startedAt", "2026-09-24T14:00:00Z")
                        put("childIds", JSONArray())
                    },
                ),
            )
        }
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(
                    "workout.saveAsRoutine", "workout", NEW_WORKOUT,
                    JSONObject().apply {
                        put("sourceWorkoutId", SOURCE_WORKOUT)
                        put("id", NEW_WORKOUT)
                        put("name", "Saved")
                        put("childIds", JSONArray())
                    },
                ),
            )
        }
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(
                    "workout.deleteExercise", "routine", SOURCE_ROUTINE,
                    JSONObject().apply { put("workoutExerciseId", SOURCE_EXERCISE_A) },
                ),
            )
        }
        // Duplicate and aliased IDs in child plans.
        fun planWith(bindings: JSONArray): JSONObject = JSONObject().apply {
            put("sourceWorkoutId", SOURCE_WORKOUT)
            put("id", NEW_WORKOUT)
            put("startedAt", "2026-09-24T14:00:00Z")
            put("childIds", bindings)
        }
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(
                    "workout.performAgain", "workout", NEW_WORKOUT,
                    planWith(
                        JSONArray(
                            listOf(
                                exerciseBinding(SOURCE_EXERCISE_A, TARGET_EXERCISE_A, emptyList()),
                                exerciseBinding(SOURCE_EXERCISE_A, TARGET_EXERCISE_B, emptyList()),
                            ),
                        ),
                    ),
                ),
            )
        }
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(
                    "workout.performAgain", "workout", NEW_WORKOUT,
                    planWith(
                        JSONArray(
                            listOf(
                                exerciseBinding(
                                    SOURCE_EXERCISE_A, TARGET_EXERCISE_A,
                                    listOf(setBinding(SOURCE_SET_A, TARGET_SET_A)),
                                ),
                                exerciseBinding(
                                    SOURCE_EXERCISE_B, TARGET_EXERCISE_B,
                                    listOf(setBinding(SOURCE_SET_A, TARGET_SET_B)),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(
                    "workout.performAgain", "workout", NEW_WORKOUT,
                    planWith(JSONArray(listOf(exerciseBinding(SOURCE_EXERCISE_A, SOURCE_EXERCISE_A, emptyList())))),
                ),
            )
        }
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(
                    "workout.performAgain", "workout", NEW_WORKOUT,
                    planWith(JSONArray(listOf(exerciseBinding(SOURCE_EXERCISE_A, NEW_WORKOUT, emptyList())))),
                ),
            )
        }
        // New root must differ from its source.
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(
                    "workout.performAgain", "workout", SOURCE_WORKOUT,
                    JSONObject().apply {
                        put("sourceWorkoutId", SOURCE_WORKOUT)
                        put("id", SOURCE_WORKOUT)
                        put("startedAt", "2026-09-24T14:00:00Z")
                        put("childIds", JSONArray())
                    },
                ),
            )
        }
        // Additions: empty plan, empty set IDs, duplicates, malformed IDs.
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(
                    "workout.addExercises", "workout", SOURCE_WORKOUT,
                    JSONObject().apply { put("additions", JSONArray()) },
                ),
            )
        }
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(
                    "workout.addExercises", "workout", SOURCE_WORKOUT,
                    JSONObject().apply {
                        put(
                            "additions",
                            JSONArray(
                                listOf(
                                    JSONObject().apply {
                                        put("id", TARGET_EXERCISE_A)
                                        put("exerciseId", CATALOGUE)
                                        put("setIds", JSONArray())
                                    },
                                ),
                            ),
                        )
                    },
                ),
            )
        }
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(
                    "workout.addExercises", "workout", SOURCE_WORKOUT,
                    JSONObject().apply {
                        put(
                            "additions",
                            JSONArray(
                                listOf(
                                    JSONObject().apply {
                                        put("id", TARGET_EXERCISE_A)
                                        put("exerciseId", CATALOGUE)
                                        put("setIds", JSONArray(listOf(TARGET_SET_A)))
                                    },
                                    JSONObject().apply {
                                        put("id", TARGET_EXERCISE_A)
                                        put("exerciseId", CATALOGUE)
                                        put("setIds", JSONArray(listOf(TARGET_SET_B)))
                                    },
                                ),
                            ),
                        )
                    },
                ),
            )
        }
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(
                    "workout.replaceExercise", "workout", SOURCE_WORKOUT,
                    JSONObject().apply {
                        put("workoutExerciseId", "nope")
                        put("exerciseId", CATALOGUE)
                    },
                ),
            )
        }
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(
                    "routine.deleteSet", "routine", SOURCE_ROUTINE,
                    JSONObject().apply { put("routineSetId", TARGET_SET_A.uppercase()) },
                ),
            )
        }
        // Missing fields, wrong types, unknown kinds, strict bodies.
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(
                    "workout.deleteSet", "workout", SOURCE_WORKOUT,
                    JSONObject(),
                ),
            )
        }
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(
                    "workout.replaceExercise", "workout", SOURCE_WORKOUT,
                    JSONObject().apply {
                        put("workoutExerciseId", SOURCE_EXERCISE_A)
                        put("exerciseId", 42)
                    },
                ),
            )
        }
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(
                    "workout.addExercises", "workout", SOURCE_WORKOUT,
                    JSONObject().apply {
                        put(
                            "additions",
                            JSONArray(
                                listOf(
                                    JSONObject().apply {
                                        put("id", TARGET_EXERCISE_A)
                                        put("exerciseId", CATALOGUE)
                                        put(
                                            "setIds",
                                            JSONArray(
                                                listOf(
                                                    JSONObject().apply { put("id", TARGET_SET_A) },
                                                ),
                                            ),
                                        )
                                    },
                                ),
                            ),
                        )
                    },
                ),
            )
        }
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(
                    "workout.fly", "workout", SOURCE_WORKOUT,
                    JSONObject().apply { put("workoutExerciseId", SOURCE_EXERCISE_A) },
                ),
            )
        }
        assertRejects {
            decodeMutationEnvelope(
                envelopeJson(
                    "workout.deleteExercise", "workout", SOURCE_WORKOUT,
                    JSONObject().apply { put("workoutExerciseId", SOURCE_EXERCISE_A) },
                ) + " junk",
            )
        }
    }

    @Test
    fun commands_representServerInvalidShapes() {
        // Empty child plans are valid shapes; source eligibility is server-side.
        val emptyPlan = decodeMutationEnvelope(
            envelopeJson(
                "workout.performAgain", "workout", NEW_WORKOUT,
                JSONObject().apply {
                    put("sourceWorkoutId", SOURCE_WORKOUT)
                    put("id", NEW_WORKOUT)
                    put("startedAt", "2026-09-24T14:00:00Z")
                    put("childIds", JSONArray())
                },
            ),
        )
        assertTrue(
            (emptyPlan.payload as MutationPayload.WorkoutPerformAgain).childIds.isEmpty(),
        )
        // Targeted deletes stay representable for unknown IDs.
        val deleteSet = decodeMutationEnvelope(
            envelopeJson(
                "workout.deleteSet", "workout", SOURCE_WORKOUT,
                JSONObject().apply { put("workoutSetId", uuid()) },
            ),
        )
        assertTrue(deleteSet.payload is MutationPayload.WorkoutDeleteSet)
    }

    @Test
    fun commands_keepStableIdentitiesThroughDurableAndPush() {
        val envelope = decodeMutationEnvelope(
            envelopeJson("workout.performAgain", "workout", NEW_WORKOUT, performAgainPayload()),
        )
        val captured = captureDurableRequest(
            envelope,
            listOf(AffectedRootModel(AggregateType.WORKOUT, NEW_WORKOUT, 3L)),
        )
        assertTrue(captured.requestJson.contains(TARGET_EXERCISE_A))
        assertTrue(captured.requestJson.contains(TARGET_SET_A))
        assertEquals(
            captured,
            decodeDurableRequest(encodeDurableRequest(captured).toString()),
        )
        val body = encodePushMutationsBody(listOf(captured))
        assertTrue(body.contains(captured.requestJson))
        val embedded = decodeMutationEnvelope(
            JSONObject(body).getJSONArray("mutations").getJSONObject(0).toString(),
        )
        assertEquals(envelope, embedded)
    }

    @Test
    fun commands_encodeToIndependentlyExpectedEnvelopes() {
        fun check(kind: String, aggregateType: String, aggregateId: String, payload: JSONObject) {
            val decoded = decodeMutationEnvelope(
                envelopeJson(kind, aggregateType, aggregateId, payload),
            )
            val expected = JSONObject(
                """
                {"mutationId":"$MUTATION","type":"$kind",
                 "aggregateType":"$aggregateType","aggregateId":"$aggregateId",
                 "localGeneration":3,"knownServerRevision":null,
                 "payload":${payload.toString()}}
                """.trimIndent(),
            )
            assertTrue(jsonValueEquals(expected, encodeMutationEnvelope(decoded)))
            assertEquals(decoded, decodeMutationEnvelope(encodeMutationEnvelope(decoded).toString()))
        }
        check(
            "workout.performAgain", "workout", NEW_WORKOUT,
            JSONObject(
                """
                {"sourceWorkoutId":"$SOURCE_WORKOUT","id":"$NEW_WORKOUT",
                 "startedAt":"2026-09-24T14:00:00Z",
                 "childIds":[
                  {"sourceExerciseId":"$SOURCE_EXERCISE_A","id":"$TARGET_EXERCISE_A",
                   "sets":[{"sourceSetId":"$SOURCE_SET_A","id":"$TARGET_SET_A"}]},
                  {"sourceExerciseId":"$SOURCE_EXERCISE_B","id":"$TARGET_EXERCISE_B",
                   "sets":[]}]}
                """.trimIndent(),
            ),
        )
        check(
            "routine.startWorkout", "workout", NEW_WORKOUT,
            JSONObject(
                """
                {"sourceRoutineId":"$SOURCE_ROUTINE","id":"$NEW_WORKOUT",
                 "startedAt":"2026-09-24T14:00:00Z","childIds":[]}
                """.trimIndent(),
            ),
        )
        check(
            "workout.saveAsRoutine", "routine", NEW_ROUTINE,
            JSONObject(
                """
                {"sourceWorkoutId":"$SOURCE_WORKOUT","id":"$NEW_ROUTINE",
                 "name":"Saved Plan","childIds":[]}
                """.trimIndent(),
            ),
        )
        check(
            "workout.addExercises", "workout", SOURCE_WORKOUT,
            JSONObject(
                """
                {"additions":[
                  {"id":"$TARGET_EXERCISE_A","exerciseId":"$CATALOGUE",
                   "setIds":["$TARGET_SET_A","$TARGET_SET_B"]},
                  {"id":"$TARGET_EXERCISE_B","exerciseId":"$CATALOGUE",
                   "setIds":["$EXTRA_SET"]}]}
                """.trimIndent(),
            ),
        )
        check(
            "workout.replaceExercise", "workout", SOURCE_WORKOUT,
            JSONObject(
                """{"workoutExerciseId":"$SOURCE_EXERCISE_A","exerciseId":"$CATALOGUE"}""",
            ),
        )
        check(
            "workout.deleteExercise", "workout", SOURCE_WORKOUT,
            JSONObject("""{"workoutExerciseId":"$SOURCE_EXERCISE_A"}"""),
        )
        check(
            "workout.deleteSet", "workout", SOURCE_WORKOUT,
            JSONObject("""{"workoutSetId":"$SOURCE_SET_A"}"""),
        )
        check(
            "routine.deleteExercise", "routine", SOURCE_ROUTINE,
            JSONObject("""{"routineExerciseId":"$SOURCE_EXERCISE_A"}"""),
        )
        check(
            "routine.deleteSet", "routine", SOURCE_ROUTINE,
            JSONObject("""{"routineSetId":"$SOURCE_SET_A"}"""),
        )
    }

    @Test
    fun commands_rejectCrossLevelAndRootIdReuse() {
        fun additionsEnvelope(additions: JSONArray): String =
            envelopeJson(
                "workout.addExercises", "workout", SOURCE_WORKOUT,
                JSONObject().apply { put("additions", additions) },
            )
        // Entry ID reused as a set ID across levels.
        assertRejects {
            decodeMutationEnvelope(
                additionsEnvelope(
                    JSONArray(
                        listOf(
                            JSONObject().apply {
                                put("id", TARGET_EXERCISE_A)
                                put("exerciseId", CATALOGUE)
                                put("setIds", JSONArray(listOf(TARGET_EXERCISE_A)))
                            },
                        ),
                    ),
                ),
            )
        }
        // Set ID reuses the envelope root ID.
        assertRejects {
            decodeMutationEnvelope(
                additionsEnvelope(
                    JSONArray(
                        listOf(
                            JSONObject().apply {
                                put("id", TARGET_EXERCISE_A)
                                put("exerciseId", CATALOGUE)
                                put("setIds", JSONArray(listOf(SOURCE_WORKOUT)))
                            },
                        ),
                    ),
                ),
            )
        }
        // Entry ID reuses the envelope root ID.
        assertRejects {
            decodeMutationEnvelope(
                additionsEnvelope(
                    JSONArray(
                        listOf(
                            JSONObject().apply {
                                put("id", SOURCE_WORKOUT)
                                put("exerciseId", CATALOGUE)
                                put("setIds", JSONArray(listOf(TARGET_SET_A)))
                            },
                        ),
                    ),
                ),
            )
        }
    }
}
