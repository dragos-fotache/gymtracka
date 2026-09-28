package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.gym.RoutineDetails
import com.dragosfotache.gymtracka.data.gym.RoutineEntity
import com.dragosfotache.gymtracka.data.gym.RoutineExerciseDetails
import com.dragosfotache.gymtracka.data.gym.RoutineExerciseWithName
import com.dragosfotache.gymtracka.data.gym.RoutineSetEntity
import com.dragosfotache.gymtracka.data.sync.codec.decodeRoutineInput
import com.dragosfotache.gymtracka.data.sync.codec.decodeRoutineRecord
import com.dragosfotache.gymtracka.data.sync.codec.encodeRoutineInput
import com.dragosfotache.gymtracka.data.sync.codec.encodeRoutineRecord
import com.dragosfotache.gymtracka.data.sync.mapping.toRoomAggregate
import com.dragosfotache.gymtracka.data.sync.mapping.toRoutineInput
import com.dragosfotache.gymtracka.data.sync.mapping.toRoutineRecord
import com.dragosfotache.gymtracka.data.sync.model.RoutineExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineInputModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineSetModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Routine transport: shapes, drafts/autosave empties, ordering, and strictness. */
class RoutineTransportTest {

    private companion object {
        const val ROOT = "11111111-1111-1111-1111-111111111111"
        const val ENTRY_A = "22222222-2222-2222-2222-222222222222"
        const val ENTRY_B = "33333333-3333-3333-3333-333333333333"
        const val CATALOGUE = "44444444-4444-4444-4444-444444444444"
        const val SET_A1 = "55555555-5555-5555-5555-555555555555"
        const val SET_A2 = "66666666-6666-6666-6666-666666666666"
        const val SET_B1 = "77777777-7777-7777-7777-777777777777"
    }

    private fun uuid(): String = UUID.randomUUID().toString()

    private fun inputJson(): JSONObject = JSONObject(
        """
        {
          "id": "$ROOT",
          "name": "Strength \"A\"\nPlan",
          "isDraft": false,
          "exercises": [
            {
              "id": "$ENTRY_A",
              "exerciseId": "$CATALOGUE",
              "position": 0,
              "restDurationSeconds": 90,
              "sets": [
                {"id": "$SET_A1", "position": 0, "repetitions": 8, "weightKg": 100.0},
                {"id": "$SET_A2", "position": 1, "repetitions": null, "weightKg": null}
              ]
            },
            {
              "id": "$ENTRY_B",
              "exerciseId": "$CATALOGUE",
              "position": 1,
              "restDurationSeconds": 0,
              "sets": [
                {"id": "$SET_B1", "position": 0, "repetitions": 0, "weightKg": 0.0}
              ]
            }
          ]
        }
        """.trimIndent(),
    )

    private fun assertRejects(block: () -> Unit) {
        try {
            block()
        } catch (_: SyncModelException) {
            return
        }
        fail("Expected SyncModelException")
    }

    @Test
    fun routineInput_roundTripsWithExactExpectedJson() {
        val input = decodeRoutineInput(inputJson())
        assertEquals(ROOT, input.id)
        assertEquals("Strength \"A\"\nPlan", input.name)
        assertEquals(false, input.isDraft)
        assertNull(input.exercises[0].sets[1].repetitions)
        assertNull(input.exercises[0].sets[1].weightKg)
        assertEquals(0, input.exercises[1].sets[0].repetitions)

        // Exact expected document (order-independent: JVM org.json uses hash key order).
        val expected = JSONObject(
            """
            {"id":"$ROOT","name":"Strength \"A\"\nPlan",
             "isDraft":false,"exercises":[
              {"id":"$ENTRY_A","exerciseId":"$CATALOGUE","position":0,
               "restDurationSeconds":90,"sets":[
                {"id":"$SET_A1","position":0,"repetitions":8,"weightKg":100.0},
                {"id":"$SET_A2","position":1,"repetitions":null,"weightKg":null}]},
              {"id":"$ENTRY_B","exerciseId":"$CATALOGUE","position":1,
               "restDurationSeconds":0,"sets":[
                {"id":"$SET_B1","position":0,"repetitions":0,"weightKg":0.0}]}]}
            """.trimIndent(),
        )
        val encoded = encodeRoutineInput(input)
        assertTrue(jsonValueEquals(expected, encoded))
        // Spot-check raw rendering: escaping, explicit nulls, and Double formatting.
        val raw = encoded.toString()
        assertTrue(raw.contains("Strength \\\"A\\\"\\nPlan"))
        assertTrue(raw.contains("\"weightKg\":100"))
        assertTrue(raw.contains("\"repetitions\":null"))
        assertEquals(input, decodeRoutineInput(raw))
    }

    @Test
    fun routineInput_emitsNoRevisionNamesOrSetState() {
        val encoded = encodeRoutineInput(decodeRoutineInput(inputJson()))
        assertTrue(!encoded.has("revision"))
        val exercise = encoded.getJSONArray("exercises").getJSONObject(0)
        assertTrue(!exercise.has("exerciseName"))
        val set = exercise.getJSONArray("sets").getJSONObject(0)
        assertTrue(!set.has("isCompleted"))
        assertTrue(!set.has("restSeconds"))
    }

    @Test
    fun routineInput_acceptsDraftAndAutosaveEmpties() {
        val emptyDraft = decodeRoutineInput(
            JSONObject(
                """{"id": "${uuid()}", "name": "", "isDraft": true, "exercises": []}""",
            ),
        )
        assertEquals("", emptyDraft.name)
        assertEquals(true, emptyDraft.isDraft)
        assertTrue(emptyDraft.exercises.isEmpty())

        // Saved-routine autosave may temporarily carry a blank name and no children.
        val blankSaved = decodeRoutineInput(
            JSONObject(
                """{"id": "${uuid()}", "name": "", "isDraft": false, "exercises": []}""",
            ),
        )
        assertEquals(false, blankSaved.isDraft)
        assertEquals(blankSaved, decodeRoutineInput(encodeRoutineInput(blankSaved).toString()))
    }

    @Test
    fun routineRecord_carriesRevisionAndNames() {
        val json = inputJson()
        json.put("revision", 7)
        json.getJSONArray("exercises").getJSONObject(0).put("exerciseName", "Squat")
        json.getJSONArray("exercises").getJSONObject(1).put("exerciseName", "Bench")
        val record = decodeRoutineRecord(json)
        assertEquals(7L, record.revision)
        assertEquals(listOf("Squat", "Bench"), record.exercises.map { it.exerciseName })
        assertEquals(record, decodeRoutineRecord(encodeRoutineRecord(record).toString()))

        val aggregate = record.toRoomAggregate("account-r", 4L)
        assertEquals("account-r", aggregate.routine.accountId)
        assertEquals(7L, aggregate.routine.serverRevision)
        assertEquals(4L, aggregate.routine.localGeneration)
        assertEquals("synced", aggregate.routine.syncState)
        assertEquals(false, aggregate.routine.isDraft)
        assertEquals(2, aggregate.exercises.size)
        assertEquals(3, aggregate.sets.size)
        // Valid canonical and flat rows keep stable parent IDs.
        assertEquals(listOf(ENTRY_A, ENTRY_B), record.exercises.map { it.id })
        assertTrue(aggregate.exercises.all { it.routineId == ROOT })
        val exerciseIds = aggregate.exercises.map { it.id }.toSet()
        assertEquals(setOf(ENTRY_A, ENTRY_B), exerciseIds)
        assertTrue(aggregate.sets.all { exerciseIds.contains(it.routineExerciseId) })
    }

    @Test
    fun routineMapping_sortsShuffledChildrenAndVerifiesParents() {
        val rootId = uuid()
        val firstEntry = uuid()
        val secondEntry = uuid()
        val details = RoutineDetails(
            RoutineEntity(rootId, "R", false),
            listOf(
                RoutineExerciseDetails(
                    RoutineExerciseWithName(secondEntry, rootId, uuid(), 1, 30, "Beta"),
                    listOf(RoutineSetEntity(uuid(), secondEntry, 0, 3, 20.0)),
                ),
                RoutineExerciseDetails(
                    RoutineExerciseWithName(firstEntry, rootId, uuid(), 0, 60, "Alpha"),
                    listOf(
                        RoutineSetEntity(uuid(), firstEntry, 1, null, null),
                        RoutineSetEntity(uuid(), firstEntry, 0, 5, 50.0),
                    ),
                ),
            ),
        )
        val input = details.toRoutineInput()
        assertEquals(listOf(0, 1), input.exercises.map { it.position })
        assertEquals(firstEntry, input.exercises[0].id)
        assertEquals(listOf(0, 1), input.exercises[0].sets.map { it.position })

        val foreign = details.copy(
            exercises = listOf(
                details.exercises[0].copy(
                    routineExercise = details.exercises[0].routineExercise.copy(routineId = uuid()),
                ),
                details.exercises[1],
            ),
        )
        assertRejects { foreign.toRoutineInput() }
        assertRejects { foreign.toRoutineRecord(1L) }

        val foreignSet = RoutineDetails(
            details.routine,
            listOf(
                details.exercises[0].copy(
                    sets = listOf(
                        details.exercises[0].sets[0].copy(routineExerciseId = uuid()),
                    ),
                ),
                details.exercises[1],
            ),
        )
        assertRejects { foreignSet.toRoutineInput() }
        assertRejects { foreignSet.toRoutineRecord(1L) }
    }

    @Test
    fun routineMapping_rejectsDeletedRoots() {
        val deleted = RoutineDetails(
            RoutineEntity(uuid(), "R", false, deletedAtMillis = 5_000L),
            emptyList(),
        )
        assertRejects { deleted.toRoutineInput() }
        assertRejects { deleted.toRoutineRecord(1L) }
    }

    @Test
    fun routineValidation_rejectsStructuralAndValueViolations() {
        // Gap and duplicate positions.
        assertRejects {
            decodeRoutineInput(
                inputJson().apply { getJSONArray("exercises").getJSONObject(1).put("position", 5) },
            )
        }
        assertRejects {
            decodeRoutineInput(
                inputJson().apply { getJSONArray("exercises").getJSONObject(1).put("position", 0) },
            )
        }
        // Duplicate exercise and cross-exercise set IDs.
        assertRejects {
            decodeRoutineInput(
                inputJson().apply { getJSONArray("exercises").getJSONObject(1).put("id", ENTRY_A) },
            )
        }
        assertRejects {
            decodeRoutineInput(
                inputJson().apply {
                    getJSONArray("exercises").getJSONObject(1)
                        .getJSONArray("sets").getJSONObject(0).put("id", SET_A1)
                },
            )
        }
        // Invalid numerics, UUIDs, revision, and metadata guards.
        assertRejects {
            decodeRoutineInput(
                inputJson().apply {
                    getJSONArray("exercises").getJSONObject(0)
                        .getJSONArray("sets").getJSONObject(0).put("weightKg", -1.0)
                },
            )
        }
        assertRejects {
            decodeRoutineInput(
                inputJson().apply {
                    getJSONArray("exercises").getJSONObject(0).put("restDurationSeconds", -10)
                },
            )
        }
        assertRejects { decodeRoutineInput(inputJson().apply { put("id", "ABCDEF") }) }
        val recordJson = inputJson()
        recordJson.put("revision", 1)
        recordJson.getJSONArray("exercises").getJSONObject(0).put("exerciseName", "Squat")
        recordJson.getJSONArray("exercises").getJSONObject(1).put("exerciseName", "Bench")
        assertRejects {
            decodeRoutineRecord(
                JSONObject(recordJson.toString()).apply { put("revision", 0) },
            )
        }
        val record = decodeRoutineRecord(recordJson)
        assertRejects { record.toRoomAggregate("", 0L) }
        assertRejects { record.toRoomAggregate("account-r", -1L) }
        // Wrong JSON types, missing required fields, strict bodies.
        assertRejects {
            decodeRoutineInput(inputJson().apply { put("isDraft", "false") })
        }
        assertRejects {
            decodeRoutineInput(
                inputJson().apply {
                    getJSONArray("exercises").getJSONObject(0)
                        .getJSONArray("sets").getJSONObject(0).put("repetitions", "8")
                },
            )
        }
        assertRejects { decodeRoutineInput(inputJson().apply { remove("exercises") }) }
        assertRejects { decodeRoutineInput(JSONObject("""{"id": "${uuid()}"}""")) }
        assertRejects { decodeRoutineInput(inputJson().toString() + " junk") }
        assertRejects { decodeRoutineInput("[]") }
    }

    @Test
    fun routineModel_helpersStayHonest() {
        // Direct model construction round-trips through validation unchanged.
        val model = RoutineInputModel(
            uuid(), "Draft", true,
            listOf(
                RoutineExerciseInputModel(
                    uuid(), uuid(), 0, 30,
                    listOf(RoutineSetModel(uuid(), 0, null, 12.5)),
                ),
            ),
        )
        assertEquals(model, decodeRoutineInput(encodeRoutineInput(model).toString()))
    }
}
