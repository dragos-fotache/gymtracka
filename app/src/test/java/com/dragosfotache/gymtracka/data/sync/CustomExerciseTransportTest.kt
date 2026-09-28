package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.gym.ExerciseDetails
import com.dragosfotache.gymtracka.data.gym.ExerciseEntity
import com.dragosfotache.gymtracka.data.gym.ExerciseEquipment
import com.dragosfotache.gymtracka.data.gym.ExerciseMuscleGroup
import com.dragosfotache.gymtracka.data.sync.codec.decodeCustomExerciseInput
import com.dragosfotache.gymtracka.data.sync.codec.decodeCustomExerciseRecord
import com.dragosfotache.gymtracka.data.sync.codec.encodeCustomExerciseInput
import com.dragosfotache.gymtracka.data.sync.codec.encodeCustomExerciseRecord
import com.dragosfotache.gymtracka.data.sync.mapping.toCustomExerciseInput
import com.dragosfotache.gymtracka.data.sync.mapping.toCustomExerciseRecord
import com.dragosfotache.gymtracka.data.sync.mapping.toRoomAggregate
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Custom-exercise transport: metadata/quantity/instruction fidelity and guards. */
class CustomExerciseTransportTest {

    private companion object {
        const val ROOT = "88888888-8888-8888-8888-888888888888"
        const val PRIMARY = "99999999-9999-9999-9999-999999999999"
        const val SECONDARY_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val SECONDARY_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val EQUIPMENT = "cccccccc-cccc-cccc-cccc-cccccccccccc"
    }

    private fun uuid(): String = UUID.randomUUID().toString()

    private fun inputJson(): JSONObject = JSONObject(
        """
        {
          "id": "$ROOT",
          "name": "Cable \"Fly\" \u00dcnit",
          "primaryMuscleGroupId": "$PRIMARY",
          "secondaryMuscleGroupIds": ["$SECONDARY_B", "$SECONDARY_A"],
          "equipmentId": "$EQUIPMENT",
          "equipmentNumItems": 2,
          "category": "strength",
          "difficultyLevel": null,
          "forceType": "pull",
          "mechanics": null,
          "instructions": ["Step one", ""]
        }
        """.trimIndent(),
    )

    private fun recordJson(): JSONObject {
        val json = inputJson()
        json.put("type", "reps")
        json.put("isUserCreated", true)
        json.put("revision", 5)
        json.put(
            "muscleGroups",
            org.json.JSONArray(
                """
                [
                  {"id": "$SECONDARY_B", "name": "Rear Delts", "type": "secondary"},
                  {"id": "$PRIMARY", "name": "Chest", "type": "primary"},
                  {"id": "$SECONDARY_A", "name": "Front Delts", "type": "secondary"}
                ]
                """.trimIndent(),
            ),
        )
        json.put(
            "equipment",
            org.json.JSONArray(
                """[{"id": "$EQUIPMENT", "name": "Cable", "numItems": 2}]""",
            ),
        )
        json.remove("primaryMuscleGroupId")
        json.remove("secondaryMuscleGroupIds")
        json.remove("equipmentId")
        json.remove("equipmentNumItems")
        return json
    }

    private fun details(
        isUserCreated: Boolean = true,
        type: String = "reps",
        name: String = "Cable Fly",
        muscles: List<ExerciseMuscleGroup> = listOf(
            ExerciseMuscleGroup(SECONDARY_B, "rear-delts", "Rear Delts", "secondary"),
            ExerciseMuscleGroup(PRIMARY, "chest", "Chest", "primary"),
            ExerciseMuscleGroup(SECONDARY_A, "front-delts", "Front Delts", "secondary"),
        ),
        equipment: List<ExerciseEquipment> = listOf(
            ExerciseEquipment(EQUIPMENT, "Cable", "cable", null, 2),
        ),
    ) = ExerciseDetails(
        ExerciseEntity(
            id = ROOT,
            name = name,
            type = type,
            difficultyLevel = null,
            forceType = "pull",
            mechanics = null,
            category = "strength",
            instructions = listOf("Step one", ""),
            isUserCreated = isUserCreated,
        ),
        muscles,
        equipment,
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
    fun customExercise_roundTripsWithExactRelationshipsAndEscaping() {
        val input = decodeCustomExerciseInput(inputJson())
        assertEquals(ROOT, input.id)
        assertEquals("Cable \"Fly\" Ünit", input.name)
        assertEquals(PRIMARY, input.primaryMuscleGroupId)
        assertEquals(listOf(SECONDARY_A, SECONDARY_B), input.secondaryMuscleGroupIds)
        assertEquals(EQUIPMENT, input.equipmentId)
        assertEquals(2, input.equipmentNumItems)
        assertEquals(listOf("Step one", ""), input.instructions)
        assertEquals(input, decodeCustomExerciseInput(encodeCustomExerciseInput(input).toString()))

        val record = decodeCustomExerciseRecord(recordJson())
        assertEquals(5L, record.revision)
        assertEquals(true, record.isUserCreated)
        assertEquals("reps", record.type)
        // Stabilized primary-first, then UUID — not display-name order.
        assertEquals(
            listOf("$PRIMARY:primary", "$SECONDARY_A:secondary", "$SECONDARY_B:secondary"),
            record.muscleGroups.map { "${it.id}:${it.type}" },
        )
        assertEquals(listOf("Chest", "Front Delts", "Rear Delts"), record.muscleGroups.map { it.name })
        assertEquals(2, record.equipment.single().numItems)
        assertEquals(record, decodeCustomExerciseRecord(encodeCustomExerciseRecord(record).toString()))

        val encodedInput = encodeCustomExerciseInput(input)
        assertTrue(!encodedInput.has("revision"))
        assertTrue(!encodedInput.has("isUserCreated"))
        assertTrue(!encodedInput.has("owner"))
        assertTrue(!encodedInput.has("type"))
        val encodedRecord = encodeCustomExerciseRecord(record)
        assertTrue(!encodedRecord.has("owner"))
    }

    @Test
    fun customExerciseInput_matchesFullExpectedShape() {
        // Input secondaries arrive unsorted; the stabilized wire order is UUID-sorted.
        val input = decodeCustomExerciseInput(inputJson())
        assertEquals(listOf(SECONDARY_A, SECONDARY_B), input.secondaryMuscleGroupIds)

        val expected = JSONObject(
            """
            {"id":"$ROOT","name":"Cable \"Fly\" Ünit",
             "primaryMuscleGroupId":"$PRIMARY",
             "secondaryMuscleGroupIds":["$SECONDARY_A","$SECONDARY_B"],
             "equipmentId":"$EQUIPMENT","equipmentNumItems":2,
             "category":"strength","difficultyLevel":null,"forceType":"pull",
             "mechanics":null,"instructions":["Step one",""]}
            """.trimIndent(),
        )
        val encoded = encodeCustomExerciseInput(input)
        assertTrue(jsonValueEquals(expected, encoded))
        // Forbidden upload fields are omitted, not just ignored on decode.
        assertTrue(!encoded.has("type"))
        assertTrue(!encoded.has("isUserCreated"))
        assertTrue(!encoded.has("revision"))
        assertTrue(!encoded.has("owner"))
        assertTrue(!encoded.has("accountId"))
        assertTrue(!encoded.has("muscleGroups"))
        assertTrue(!encoded.has("equipment"))
        assertEquals(input, decodeCustomExerciseInput(encoded.toString()))
    }

    @Test
    fun customExercise_preservesNullEmptyAndQuantityDistinctions() {
        val nullInstructions = decodeCustomExerciseInput(
            inputJson().apply { put("instructions", JSONObject.NULL) },
        )
        assertNull(nullInstructions.instructions)

        val emptyInstructions = decodeCustomExerciseInput(
            inputJson().apply { put("instructions", org.json.JSONArray()) },
        )
        assertEquals(emptyList<String>(), emptyInstructions.instructions)

        val missingInstructions = decodeCustomExerciseInput(
            inputJson().apply { remove("instructions") },
        )
        assertNull(missingInstructions.instructions)

        val zeroQuantity = decodeCustomExerciseInput(
            inputJson().apply { put("equipmentNumItems", 0) },
        )
        assertEquals(0, zeroQuantity.equipmentNumItems)
        assertEquals(EQUIPMENT, zeroQuantity.equipmentId)

        val nullQuantity = decodeCustomExerciseInput(
            inputJson().apply { put("equipmentNumItems", JSONObject.NULL) },
        )
        assertNull(nullQuantity.equipmentNumItems)

        val missingSecondaries = decodeCustomExerciseInput(
            inputJson().apply { remove("secondaryMuscleGroupIds") },
        )
        assertEquals(emptyList<String>(), missingSecondaries.secondaryMuscleGroupIds)

        // Re-encoded forms preserve the distinctions.
        assertTrue(encodeCustomExerciseInput(nullInstructions).isNull("instructions"))
        assertEquals(
            listOf(SECONDARY_A, SECONDARY_B),
            decodeCustomExerciseInput(encodeCustomExerciseInput(nullInstructions).toString())
                .secondaryMuscleGroupIds,
        )
        assertEquals(0, encodeCustomExerciseInput(zeroQuantity).getInt("equipmentNumItems"))
        assertTrue(encodeCustomExerciseInput(nullQuantity).isNull("equipmentNumItems"))
        assertEquals(0, encodeCustomExerciseInput(missingSecondaries).getJSONArray("secondaryMuscleGroupIds").length())
    }

    @Test
    fun customExercise_rejectsRelationshipQuantityAndNameViolations() {
        // Quantity without equipment; negative quantity.
        assertRejects {
            decodeCustomExerciseInput(
                inputJson().apply { put("equipmentId", JSONObject.NULL) },
            )
        }
        assertRejects {
            decodeCustomExerciseInput(inputJson().apply { put("equipmentNumItems", -1) })
        }
        // Primary/secondary overlap and duplicate secondaries.
        assertRejects {
            decodeCustomExerciseInput(
                inputJson().apply {
                    put(
                        "secondaryMuscleGroupIds",
                        org.json.JSONArray(listOf(SECONDARY_A, PRIMARY)),
                    )
                },
            )
        }
        assertRejects {
            decodeCustomExerciseInput(
                inputJson().apply {
                    put(
                        "secondaryMuscleGroupIds",
                        org.json.JSONArray(listOf(SECONDARY_A, SECONDARY_A)),
                    )
                },
            )
        }
        // Explicit null secondaries are invalid; missing means empty.
        assertRejects {
            decodeCustomExerciseInput(
                inputJson().apply { put("secondaryMuscleGroupIds", JSONObject.NULL) },
            )
        }
        // Blank names rejected; valid names pass through unchanged.
        assertRejects { decodeCustomExerciseInput(inputJson().apply { put("name", "") }) }
        assertRejects { decodeCustomExerciseInput(inputJson().apply { put("name", "   ") }) }
        assertEquals(
            "  Keep  ",
            decodeCustomExerciseInput(inputJson().apply { put("name", "  Keep  ") }).name,
        )
        // Tertiary, multiple primaries, and multiple equipment rejected.
        assertRejects {
            decodeCustomExerciseRecord(
                recordJson().apply {
                    getJSONArray("muscleGroups").getJSONObject(2).put("type", "tertiary")
                },
            )
        }
        assertRejects {
            decodeCustomExerciseRecord(
                recordJson().apply {
                    getJSONArray("muscleGroups").getJSONObject(0).put("type", "primary")
                },
            )
        }
        assertRejects {
            decodeCustomExerciseRecord(
                recordJson().apply {
                    getJSONArray("equipment").put(
                        JSONObject("""{"id": "${uuid()}", "name": "Other", "numItems": null}"""),
                    )
                },
            )
        }
        // Bundled rows and non-reps types are never custom records.
        assertRejects {
            decodeCustomExerciseRecord(recordJson().apply { put("isUserCreated", false) })
        }
        assertRejects {
            decodeCustomExerciseRecord(recordJson().apply { put("type", "cardio") })
        }
        assertRejects {
            decodeCustomExerciseRecord(recordJson().apply { put("revision", 0) })
        }
        // Uppercase aliases rejected.
        assertRejects {
            decodeCustomExerciseInput(
                inputJson().apply { put("primaryMuscleGroupId", SECONDARY_A.uppercase()) },
            )
        }
    }

    @Test
    fun customExercise_rejectsWrongJsonTypesMissingFieldsAndBodies() {
        assertRejects { decodeCustomExerciseInput(inputJson().apply { put("name", 42) }) }
        assertRejects {
            decodeCustomExerciseInput(
                inputJson().apply {
                    put("secondaryMuscleGroupIds", SECONDARY_A)
                },
            )
        }
        assertRejects {
            decodeCustomExerciseInput(
                inputJson().apply { put("instructions", org.json.JSONArray(listOf(7))) },
            )
        }
        assertRejects {
            decodeCustomExerciseInput(inputJson().apply { put("equipmentNumItems", "2") })
        }
        assertRejects { decodeCustomExerciseInput(inputJson().apply { remove("id") }) }
        assertRejects { decodeCustomExerciseInput(JSONObject("""{"id": "${uuid()}"}""")) }
        assertRejects { decodeCustomExerciseInput(inputJson().toString() + " junk") }
        assertRejects { decodeCustomExerciseInput("[]") }
    }

    @Test
    fun customExercise_mappingGuardsAndFlattens() {
        val input = details().toCustomExerciseInput()
        assertEquals(PRIMARY, input.primaryMuscleGroupId)
        assertEquals(listOf(SECONDARY_A, SECONDARY_B), input.secondaryMuscleGroupIds)
        assertEquals(2, input.equipmentNumItems)

        val record = details().toCustomExerciseRecord(9L)
        val aggregate = record.toRoomAggregate("account-c", 6L)
        assertEquals(ROOT, aggregate.exercise.id)
        assertEquals(true, aggregate.exercise.isUserCreated)
        assertEquals("reps", aggregate.exercise.type)
        assertEquals("account-c", aggregate.exercise.accountId)
        assertEquals(9L, aggregate.exercise.serverRevision)
        assertEquals(6L, aggregate.exercise.localGeneration)
        assertEquals("synced", aggregate.exercise.syncState)
        assertEquals(listOf("Step one", ""), aggregate.exercise.instructions)
        assertEquals(
            setOf("$PRIMARY:primary", "$SECONDARY_A:secondary", "$SECONDARY_B:secondary"),
            aggregate.muscleGroups.map { "${it.muscleGroupId}:${it.type}" }.toSet(),
        )
        assertTrue(aggregate.muscleGroups.all { it.exerciseId == ROOT })
        assertEquals(EQUIPMENT, aggregate.equipment.single().equipmentId)
        assertEquals(2, aggregate.equipment.single().numberOfItems)
        assertRejects { record.toRoomAggregate("", 0L) }
        assertRejects { record.toRoomAggregate("account-c", -1L) }

        // Bundled, non-reps, blank-name, tertiary, and multi-equipment rows rejected.
        assertRejects { details(isUserCreated = false).toCustomExerciseInput() }
        assertRejects { details(isUserCreated = false).toCustomExerciseRecord(1L) }
        assertRejects { details(type = "cardio").toCustomExerciseInput() }
        assertRejects { details(name = "  ").toCustomExerciseInput() }
        assertRejects {
            details(
                muscles = listOf(
                    ExerciseMuscleGroup(PRIMARY, "chest", "Chest", "primary"),
                    ExerciseMuscleGroup(SECONDARY_A, "front", "Front", "tertiary"),
                ),
            ).toCustomExerciseInput()
        }
        assertRejects {
            details(
                equipment = listOf(
                    ExerciseEquipment(EQUIPMENT, "Cable", "cable", null, null),
                    ExerciseEquipment(uuid(), "Other", "cable", null, null),
                ),
            ).toCustomExerciseInput()
        }
        assertRejects { details().toCustomExerciseRecord(0L) }
    }

    @Test
    fun customExercise_metadataSurvivesDetailsToRoomAndDeletedRejects() {
        val source = ExerciseDetails(
            ExerciseEntity(
                id = ROOT,
                name = "Cable Fly",
                type = "reps",
                difficultyLevel = "intermediate",
                forceType = "pull",
                mechanics = "compound",
                category = "strength",
                instructions = listOf("Step one"),
                isUserCreated = true,
            ),
            listOf(
                ExerciseMuscleGroup(PRIMARY, "chest", "Chest", "primary"),
            ),
            emptyList(),
        )
        val input = source.toCustomExerciseInput()
        assertEquals("intermediate", input.difficultyLevel)
        assertEquals("pull", input.forceType)
        assertEquals("compound", input.mechanics)

        val record = source.toCustomExerciseRecord(2L)
        assertEquals("intermediate", record.difficultyLevel)
        assertEquals("pull", record.forceType)
        assertEquals("compound", record.mechanics)

        val aggregate = record.toRoomAggregate("account-c", 0L)
        assertEquals("intermediate", aggregate.exercise.difficultyLevel)
        assertEquals("pull", aggregate.exercise.forceType)
        assertEquals("compound", aggregate.exercise.mechanics)

        val deleted = source.copy(exercise = source.exercise.copy(deletedAtMillis = 5_000L))
        assertRejects { deleted.toCustomExerciseInput() }
        assertRejects { deleted.toCustomExerciseRecord(1L) }
    }
}
