package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.codec.decodeWorkoutInput
import com.dragosfotache.gymtracka.data.sync.codec.decodeWorkoutRecord
import com.dragosfotache.gymtracka.data.sync.codec.encodeWorkoutInput
import com.dragosfotache.gymtracka.data.sync.codec.formatUtcMillis
import com.dragosfotache.gymtracka.data.sync.codec.parseUtcMillis
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutInputModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutSetModel
import com.dragosfotache.gymtracka.data.sync.model.validateWorkoutInputModel
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Strictness coverage: wrong JSON types, invalid values, timestamps, upload rules. */
class WorkoutValidationTest {

    private fun uuid(): String = UUID.randomUUID().toString()

    private fun validInputJson(): JSONObject = JSONObject(
        """
        {
          "id": "${uuid()}",
          "name": "Active",
          "status": "active",
          "startedAt": "2026-09-24T14:00:00Z",
          "exercises": [
            {
              "id": "${uuid()}",
              "exerciseId": "${uuid()}",
              "position": 0,
              "notes": "",
              "restDurationSeconds": 30,
              "sets": [
                {"id": "${uuid()}", "position": 0, "repetitions": 5, "weightKg": 50.0,
                 "isCompleted": true, "restSeconds": null}
              ]
            }
          ]
        }
        """.trimIndent(),
    )

    private fun validRecordJson(): JSONObject {
        val input = validInputJson()
        input.put("status", "finished")
        input.put("finishedAt", "2026-09-24T15:00:00Z")
        input.put("revision", 4)
        input.getJSONArray("exercises").getJSONObject(0).put("exerciseName", "Squat")
        return input
    }

    private fun assertRejects(block: () -> Unit) {
        try {
            block()
        } catch (_: SyncModelException) {
            return
        } catch (_: IllegalArgumentException) {
            return
        }
        fail("Expected explicit rejection")
    }

    @Test
    fun codec_rejectsMissingRequiredAndWrongJsonTypes() {
        assertRejects { decodeWorkoutInput(JSONObject("{}")) }
        assertRejects { decodeWorkoutInput(validInputJson().apply { remove("exercises") }) }
        assertRejects { decodeWorkoutInput(validInputJson().apply { put("name", JSONObject.NULL) }) }

        // org.json coercion traps: numeric/bool strings, fractional integers.
        assertRejects { decodeWorkoutInput(validInputJson().apply { put("name", 42) }) }
        assertRejects {
            decodeWorkoutInput(
                validInputJson().apply {
                    getJSONArray("exercises").getJSONObject(0)
                        .getJSONArray("sets").getJSONObject(0)
                        .put("repetitions", "5")
                },
            )
        }
        assertRejects {
            decodeWorkoutInput(
                validInputJson().apply {
                    getJSONArray("exercises").getJSONObject(0).put("position", 1.5)
                },
            )
        }
        assertRejects {
            decodeWorkoutInput(
                validInputJson().apply {
                    getJSONArray("exercises").getJSONObject(0)
                        .getJSONArray("sets").getJSONObject(0)
                        .put("isCompleted", "true")
                },
            )
        }
        assertRejects {
            decodeWorkoutRecord(validRecordJson().apply { put("revision", "4") })
        }
        assertRejects {
            decodeWorkoutRecord(validRecordJson().apply { put("revision", 4.5) })
        }
    }

    @Test
    fun codec_rejectsInvalidRevisionStatusCompletionAndNumerics() {
        assertRejects { decodeWorkoutRecord(validRecordJson().apply { put("revision", 0) }) }
        assertRejects { decodeWorkoutRecord(validRecordJson().apply { put("revision", -2) }) }
        assertRejects { decodeWorkoutRecord(validRecordJson().apply { put("status", "archived") }) }
        assertRejects { decodeWorkoutInput(validInputJson().apply { put("status", "finished") }) }
        assertRejects { decodeWorkoutInput(validInputJson().apply { put("status", "ACTIVE") }) }

        // Completed sets require parsed reps and weight.
        assertRejects {
            decodeWorkoutInput(
                validInputJson().apply {
                    getJSONArray("exercises").getJSONObject(0)
                        .getJSONArray("sets").getJSONObject(0)
                        .put("repetitions", JSONObject.NULL)
                },
            )
        }
        // Negative domain values.
        assertRejects {
            decodeWorkoutInput(
                validInputJson().apply {
                    getJSONArray("exercises").getJSONObject(0).put("restDurationSeconds", -5)
                },
            )
        }
        assertRejects {
            decodeWorkoutInput(
                validInputJson().apply {
                    getJSONArray("exercises").getJSONObject(0)
                        .getJSONArray("sets").getJSONObject(0)
                        .put("weightKg", -1.0)
                },
            )
        }
        assertRejects {
            decodeWorkoutRecord(
                validRecordJson().apply {
                    getJSONArray("exercises").getJSONObject(0)
                        .getJSONArray("sets").getJSONObject(0)
                        .put("isCompleted", false)
                },
            )
        }
        // Malformed UUIDs.
        assertRejects { decodeWorkoutInput(validInputJson().apply { put("id", "not-a-uuid") }) }
        // Non-finite weight cannot be constructed through JSON, but model validation rejects it.
        assertRejects {
            validateWorkoutInputModel(
                WorkoutInputModel(
                    uuid(), "Active", "active", 1_000L,
                    listOf(
                        WorkoutExerciseInputModel(
                            uuid(), uuid(), 0, "", 30,
                            listOf(WorkoutSetModel(uuid(), 0, 5, Double.POSITIVE_INFINITY, false, null)),
                        ),
                    ),
                ),
            )
        }
        // Encoding a non-finite weight is rejected instead of emitting invalid JSON.
        assertRejects {
            encodeWorkoutInput(
                WorkoutInputModel(
                    uuid(), "Active", "active", 1_000L,
                    listOf(
                        WorkoutExerciseInputModel(
                            uuid(), uuid(), 0, "", 30,
                            listOf(WorkoutSetModel(uuid(), 0, 5, Double.NaN, false, null)),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun timestamps_formatUtcParseStrictlyAndKeepMillisecondPrecision() {
        assertEquals("2026-09-24T14:00:00Z", formatUtcMillis(1_790_258_400_000L))
        assertEquals(1_790_258_400_000L, parseUtcMillis("2026-09-24T14:00:00Z"))
        assertEquals(1_790_258_400_120L, parseUtcMillis("2026-09-24T14:00:00.120Z"))
        assertEquals(1_790_258_400_000L, parseUtcMillis("2026-09-24T14:00:00.000Z"))

        assertRejects { parseUtcMillis("2026-09-24T14:00:00") }
        assertRejects { parseUtcMillis("2026-09-24T14:00:00+02:00") }
        assertRejects { parseUtcMillis("2026-09-24T14:00:00+00:00") }
        assertRejects { parseUtcMillis("2026-09-24T14:00:00Z ") }
        assertRejects { parseUtcMillis("2026-13-24T14:00:00Z") }
        assertRejects { parseUtcMillis("2026-09-24T25:00:00Z") }
        assertRejects { parseUtcMillis("2026-09-24T14:00:00.123456789Z") }
        // Hour 24 and leap second 60 must not silently normalize into another timestamp.
        assertRejects { parseUtcMillis("2026-09-24T24:00:00Z") }
        assertRejects { parseUtcMillis("2026-09-24T23:59:60Z") }
        assertRejects { parseUtcMillis("2026-02-30T14:00:00Z") }
        // Extended years outside RFC 3339 must not be emitted.
        assertRejects { formatUtcMillis(253_402_300_800_000L) }
        assertRejects { formatUtcMillis(Long.MAX_VALUE) }
        assertRejects { formatUtcMillis(Long.MIN_VALUE) }
        assertRejects { parseUtcMillis("not-a-timestamp") }

        assertRejects { decodeWorkoutInput(validInputJson().apply { put("startedAt", "2026-09-24 14:00:00") }) }
    }

    @Test
    fun timestamps_areTimezoneLocaleIndependentAndCoverBoundaries() {
        val defaultZone = java.util.TimeZone.getDefault()
        val defaultLocale = java.util.Locale.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Kiritimati"))
            java.util.Locale.setDefault(
                java.util.Locale.Builder().setLanguage("tr").setRegion("TR").build(),
            )
            assertEquals("2026-09-24T14:00:00Z", formatUtcMillis(1_790_258_400_000L))
            assertEquals(1_790_258_400_000L, parseUtcMillis("2026-09-24T14:00:00Z"))
        } finally {
            java.util.TimeZone.setDefault(defaultZone)
            java.util.Locale.setDefault(defaultLocale)
        }

        // Four-digit year boundaries round-trip; trailing fractional zeroes are fine.
        assertEquals(-62_167_219_200_000L, parseUtcMillis("0000-01-01T00:00:00Z"))
        assertEquals("0000-01-01T00:00:00Z", formatUtcMillis(-62_167_219_200_000L))
        assertEquals(253_402_300_799_999L, parseUtcMillis("9999-12-31T23:59:59.999Z"))
        assertEquals("9999-12-31T23:59:59.999Z", formatUtcMillis(253_402_300_799_999L))
        assertEquals(1_790_258_400_120L, parseUtcMillis("2026-09-24T14:00:00.120000Z"))
    }

    @Test
    fun identifiers_rejectAbbreviatedUuidsAndDuplicateSetsAcrossExercises() {
        // UUID.fromString accepts abbreviated groups on the JVM; the contract needs full shape.
        assertRejects { decodeWorkoutInput(validInputJson().apply { put("id", "e9cd4a3c-4cce-4b7d-8b7b") }) }
        // Uppercase aliases are rejected so they can never become separate Room rows.
        assertRejects {
            decodeWorkoutInput(
                validInputJson().apply { put("id", "E9CD4A3C-4CCE-4B7D-8B7B-8763956A2F15") },
            )
        }
        assertRejects {
            decodeWorkoutInput(
                validInputJson().apply {
                    getJSONArray("exercises").getJSONObject(0)
                        .put("exerciseId", "A52AF213-B0B8-4D7A-A43F-7758F838DC41")
                },
            )
        }
        assertRejects {
            decodeWorkoutInput(
                validInputJson().apply {
                    getJSONArray("exercises").getJSONObject(0).put("exerciseId", "not-a-uuid")
                },
            )
        }
        assertRejects {
            decodeWorkoutInput(
                validInputJson().apply {
                    getJSONArray("exercises").getJSONObject(0)
                        .getJSONArray("sets").getJSONObject(0)
                        .put("id", "10dfcb31-4588-421e-9800-0d40e3c7b86")
                },
            )
        }

        // workout_sets has one global primary key: duplicates across parents are rejected.
        val sharedSetId = uuid()
        val firstExercise = WorkoutExerciseInputModel(
            uuid(), uuid(), 0, "", 30,
            listOf(WorkoutSetModel(sharedSetId, 0, 5, 50.0, true, null)),
        )
        val secondExercise = WorkoutExerciseInputModel(
            uuid(), uuid(), 1, "", 30,
            listOf(WorkoutSetModel(sharedSetId, 0, 6, 60.0, true, null)),
        )
        assertRejects {
            validateWorkoutInputModel(
                WorkoutInputModel(uuid(), "Active", "active", 1_000L, listOf(firstExercise, secondExercise)),
            )
        }
    }

    @Test
    fun codec_sortsShuffledSetsByPosition() {
        val json = validInputJson()
        val sets = json.getJSONArray("exercises").getJSONObject(0).getJSONArray("sets")
        val extra = JSONObject(
            """{"id": "${uuid()}", "position": 1, "repetitions": 6, "weightKg": 55.0,
               "isCompleted": false, "restSeconds": null}""",
        )
        sets.put(extra)
        // Stored array order is 0, 1 while positions read 1, 0 after the swap.
        val first = sets.getJSONObject(0)
        val second = sets.getJSONObject(1)
        sets.put(0, second)
        sets.put(1, first)
        val decoded = decodeWorkoutInput(json)
        assertEquals(listOf(0, 1), decoded.exercises[0].sets.map { it.position })
        assertEquals(listOf(5, 6), decoded.exercises[0].sets.map { it.repetitions })
    }
}
