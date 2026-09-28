package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.codec.captureDurableRequest
import com.dragosfotache.gymtracka.data.sync.codec.decodeDurableRequest
import com.dragosfotache.gymtracka.data.sync.codec.decodeMutationEnvelope
import com.dragosfotache.gymtracka.data.sync.codec.encodeDurableRequest
import com.dragosfotache.gymtracka.data.sync.codec.encodePushMutationsBody
import com.dragosfotache.gymtracka.data.sync.model.AffectedRootModel
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.DurableRequestModel
import com.dragosfotache.gymtracka.data.sync.model.MutationEnvelopeModel
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.UnsupportedDurableVersionException
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Durable v1 wrapper: immutability, push embedding, version dispatch, guards. */
class DurablePayloadTest {

    private companion object {
        const val WORKOUT = "11111111-1111-1111-1111-111111111111"
        const val OLD_WORKOUT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val MUTATION = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    }

    private fun uuid(): String = UUID.randomUUID().toString()

    private fun startEnvelope(
        mutationId: String = MUTATION,
        aggregateId: String = WORKOUT,
        generation: Long = 2L,
    ): MutationEnvelopeModel = MutationEnvelopeModel(
        mutationId = mutationId,
        kind = MutationKind.WORKOUT_START,
        aggregateType = AggregateType.WORKOUT,
        aggregateId = aggregateId,
        localGeneration = generation,
        knownServerRevision = null,
        payload = MutationPayload.WorkoutStart(aggregateId, "Morning Workout", 1_790_258_400_000L),
    )

    private fun targetRoots(generation: Long = 2L): List<AffectedRootModel> =
        listOf(AffectedRootModel(AggregateType.WORKOUT, WORKOUT, generation))

    private fun assertRejects(block: () -> Unit) {
        try {
            block()
        } catch (_: SyncModelException) {
            return
        }
        fail("Expected SyncModelException")
    }

    @Test
    fun durable_preservesExactRequestTextAcrossWrappers() {
        val captured = captureDurableRequest(startEnvelope(), targetRoots())
        assertEquals(1, captured.payloadVersion)
        assertEquals(captured.envelope, decodeMutationEnvelope(captured.requestJson))

        // Whitespace and key order survive wrapper encode/decode untouched.
        val pretty = JSONObject(captured.requestJson).toString(4)
        val wrapper = JSONObject().apply {
            put("affectedRoots", JSONArray())
            put("requestJson", pretty)
            put("payloadVersion", 1)
        }
        val withRoots = JSONObject(wrapper.toString()).apply {
            put(
                "affectedRoots",
                JSONArray(
                    listOf(
                        JSONObject().apply {
                            put("aggregateType", "workout")
                            put("aggregateId", WORKOUT)
                            put("localGeneration", 2)
                        },
                    ),
                ),
            )
        }
        val decoded = decodeDurableRequest(withRoots.toString())
        assertEquals(pretty, decoded.requestJson)
        assertEquals(captured.envelope, decoded.envelope)
        assertEquals(decoded, decodeDurableRequest(encodeDurableRequest(decoded).toString()))
        // Re-encoding the wrapper keeps the frozen bytes identical.
        assertEquals(
            pretty,
            decodeDurableRequest(encodeDurableRequest(decoded).toString()).requestJson,
        )
    }

    @Test
    fun durable_pushEmbeddingIsByteForByte() {
        val first = captureDurableRequest(startEnvelope(), targetRoots())
        val second = captureDurableRequest(
            startEnvelope(mutationId = uuid(), aggregateId = OLD_WORKOUT),
            listOf(AffectedRootModel(AggregateType.WORKOUT, OLD_WORKOUT, 2L)),
        )
        val body = encodePushMutationsBody(listOf(first, second))
        assertTrue(body.contains(first.requestJson))
        assertTrue(body.contains(second.requestJson))
        assertTrue(!body.contains("\"account\""))
        assertTrue(!body.contains("\"session\""))
        assertTrue(!body.contains("\"token\""))
        assertTrue(!body.contains("\"owner\""))
        assertTrue(!body.contains("payloadVersion"))
        assertTrue(!body.contains("affectedRoots"))

        // Embedded requests decode back to their envelopes.
        val parsed = JSONObject(body).getJSONArray("mutations")
        assertEquals(2, parsed.length())
        assertEquals(first.envelope, decodeMutationEnvelope(parsed.getJSONObject(0).toString()))
        assertEquals(second.envelope, decodeMutationEnvelope(parsed.getJSONObject(1).toString()))

        // Empty arrays encode validly; caller lists cannot mutate frozen state.
        assertEquals("{\"mutations\":[]}", encodePushMutationsBody(emptyList()))
        val mutableRoots = mutableListOf(AffectedRootModel(AggregateType.WORKOUT, WORKOUT, 2L))
        val frozen = captureDurableRequest(startEnvelope(), mutableRoots)
        mutableRoots.clear()
        mutableRoots.add(AffectedRootModel(AggregateType.ROUTINE, uuid(), 9L))
        assertEquals(1, frozen.affectedRoots.size)
        assertEquals(WORKOUT, frozen.affectedRoots.single().aggregateId)
    }

    @Test
    fun durable_rejectsViolationsAndDispatchesVersions() {
        // Effects must include the target with its exact generation.
        assertRejects {
            captureDurableRequest(startEnvelope(), emptyList())
        }
        assertRejects {
            captureDurableRequest(
                startEnvelope(),
                listOf(AffectedRootModel(AggregateType.WORKOUT, WORKOUT, 9L)),
            )
        }
        assertRejects {
            captureDurableRequest(
                startEnvelope(),
                listOf(
                    AffectedRootModel(AggregateType.WORKOUT, WORKOUT, 2L),
                    AffectedRootModel(AggregateType.WORKOUT, WORKOUT, 3L),
                ),
            )
        }
        assertRejects {
            captureDurableRequest(
                startEnvelope(),
                listOf(AffectedRootModel(AggregateType.WORKOUT, "nope", 2L)),
            )
        }
        assertRejects {
            captureDurableRequest(
                startEnvelope(),
                listOf(AffectedRootModel(AggregateType.WORKOUT, WORKOUT, 0L)),
            )
        }
        // Extra effects may describe a replaced old active root.
        val withOld = captureDurableRequest(
            startEnvelope(),
            targetRoots() + AffectedRootModel(AggregateType.WORKOUT, OLD_WORKOUT, 11L),
        )
        assertEquals(2, withOld.affectedRoots.size)

        // Unsupported versions dispatch distinctly with version metadata.
        val captured = captureDurableRequest(startEnvelope(), targetRoots())
        val future = JSONObject(encodeDurableRequest(captured).toString()).apply {
            put("payloadVersion", 2)
        }
        try {
            decodeDurableRequest(future.toString())
            fail("Expected UnsupportedDurableVersionException")
        } catch (error: UnsupportedDurableVersionException) {
            assertEquals(2, error.payloadVersion)
        }
        try {
            decodeDurableRequest(
                JSONObject().apply {
                    put("payloadVersion", 2)
                    put("requestJson", "{}")
                    put("affectedRoots", JSONArray())
                }.toString(),
            )
            fail("Expected UnsupportedDurableVersionException")
        } catch (error: UnsupportedDurableVersionException) {
            assertEquals(2, error.payloadVersion)
        }

        // Malformed raw JSON and wrapper bodies.
        assertRejects { decodeDurableRequest("{") }
        assertRejects {
            decodeDurableRequest(
                JSONObject(encodeDurableRequest(captured).toString()).apply {
                    put("requestJson", "{broken")
                }.toString(),
            )
        }
        assertRejects { decodeDurableRequest("[]") }

        // Push guards: over 100 mutations and duplicate IDs.
        assertRejects {
            encodePushMutationsBody(List(101) { captured })
        }
        assertRejects {
            encodePushMutationsBody(listOf(captured, captured))
        }
    }

    @Test
    fun durableRequestModel_staysHonest() {
        val captured = captureDurableRequest(startEnvelope(), targetRoots())
        assertEquals(
            captured,
            DurableRequestModel(
                1,
                captured.requestJson,
                captured.affectedRoots,
                captured.envelope,
            ),
        )
    }

    @Test
    fun durableDirectModels_verifyRawTextBeforeTrustingIds() {
        val captured = captureDurableRequest(startEnvelope(), targetRoots())
        fun tampered(mutator: (JSONObject) -> Unit): DurableRequestModel {
            val text = JSONObject(captured.requestJson).apply(mutator).toString()
            return captured.copy(requestJson = text)
        }

        // Altered raw mutation ID, target, generation, and payload all fail encoding.
        assertRejects {
            encodeDurableRequest(tampered { it.put("mutationId", uuid()) })
        }
        assertRejects {
            encodeDurableRequest(
                tampered {
                    val target = uuid()
                    it.put("aggregateId", target)
                    it.getJSONObject("payload").put("id", target)
                },
            )
        }
        assertRejects {
            encodeDurableRequest(tampered { it.put("localGeneration", 9L) })
        }
        assertRejects {
            encodeDurableRequest(
                tampered { it.getJSONObject("payload").put("name", "Renamed") },
            )
        }
        // Malformed raw text fails instead of falling back.
        assertRejects {
            encodeDurableRequest(captured.copy(requestJson = "{broken"))
        }
        // Push embedding verifies raw text the same way.
        assertRejects {
            encodePushMutationsBody(listOf(tampered { it.put("localGeneration", 9L) }))
        }
        // Untouched models still encode identically.
        assertEquals(
            encodeDurableRequest(captured).toString(),
            encodeDurableRequest(captured.copy()).toString(),
        )
    }

    @Test
    fun durableVersionDispatch_precedesRawTextParsing() {
        val captured = captureDurableRequest(startEnvelope(), targetRoots())
        // A version 2 model with malformed raw content must dispatch on version first.
        val futureMalformed = captured.copy(payloadVersion = 2, requestJson = "{broken")
        try {
            encodeDurableRequest(futureMalformed)
            fail("Expected UnsupportedDurableVersionException")
        } catch (error: UnsupportedDurableVersionException) {
            assertEquals(2, error.payloadVersion)
        }
        try {
            encodePushMutationsBody(listOf(futureMalformed))
            fail("Expected UnsupportedDurableVersionException")
        } catch (error: UnsupportedDurableVersionException) {
            assertEquals(2, error.payloadVersion)
        }
    }
}
