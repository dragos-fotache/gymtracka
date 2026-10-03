package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.codec.captureDurableRequest
import com.dragosfotache.gymtracka.data.sync.codec.decodeDurableRequest
import com.dragosfotache.gymtracka.data.sync.codec.decodeMutationEnvelope
import com.dragosfotache.gymtracka.data.sync.codec.encodeMutationEnvelope
import com.dragosfotache.gymtracka.data.sync.codec.encodePushMutationsBody
import com.dragosfotache.gymtracka.data.sync.model.AffectedRootModel
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.ChildExerciseBinding
import com.dragosfotache.gymtracka.data.sync.model.ChildSetBinding
import com.dragosfotache.gymtracka.data.sync.model.ExerciseAddition
import com.dragosfotache.gymtracka.data.sync.model.MutationEnvelopeModel
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.UnsupportedDurableVersionException
import com.dragosfotache.gymtracka.data.sync.model.validateMutationEnvelope
import com.dragosfotache.gymtracka.data.sync.model.validateMutationPayload
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R02b QA: clone/copy family identity rules for the three copy commands.
 *
 * source = source root + sourceExerciseIds + sourceSetIds; target = new root +
 * target entry ids + target set ids. Each side must be internally unique for ALL
 * commands. Source/target overlap rejects ONLY for same-family workout.performAgain;
 * routine.startWorkout and workout.saveAsRoutine allow cross-family overlap.
 */
class CopyIdentityValidationTest {

    private fun uid(n: Int): String = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')

    private data class CopyIds(
        val sourceRoot: String,
        val se1: String,
        val se2: String,
        val ss1: String,
        val ss2: String,
        val ss3: String,
        val targetRoot: String,
        val te1: String,
        val te2: String,
        val ts1: String,
        val ts2: String,
        val ts3: String,
    )

    private fun baseCopy(): CopyIds = CopyIds(
        sourceRoot = uid(30),
        se1 = uid(31),
        se2 = uid(32),
        ss1 = uid(33),
        ss2 = uid(34),
        ss3 = uid(35),
        targetRoot = uid(40),
        te1 = uid(41),
        te2 = uid(42),
        ts1 = uid(43),
        ts2 = uid(44),
        ts3 = uid(45),
    )

    private fun copySecrets(c: CopyIds): List<String> = listOf(
        c.sourceRoot, c.se1, c.se2, c.ss1, c.ss2, c.ss3,
        c.targetRoot, c.te1, c.te2, c.ts1, c.ts2, c.ts3,
    )

    private fun assertIdentityRejected(label: String, secrets: List<String>, block: () -> Unit) {
        try {
            block()
            fail("expected SyncModelException for $label")
        } catch (e: SyncModelException) {
            val message = e.message ?: ""
            assertTrue("empty safe context for $label", message.isNotBlank())
            assertNull("cause must be absent for $label", e.cause)
            for (secret in secrets.distinct()) {
                assertFalse("leaked UUID text for $label", message.contains(secret))
            }
        }
    }

    private fun childIdsOf(c: CopyIds): List<ChildExerciseBinding> = listOf(
        ChildExerciseBinding(
            sourceExerciseId = c.se1,
            id = c.te1,
            sets = listOf(ChildSetBinding(c.ss1, c.ts1), ChildSetBinding(c.ss2, c.ts2)),
        ),
        ChildExerciseBinding(
            sourceExerciseId = c.se2,
            id = c.te2,
            sets = listOf(ChildSetBinding(c.ss3, c.ts3)),
        ),
    )

    private fun performAgainOf(c: CopyIds): MutationPayload.WorkoutPerformAgain =
        MutationPayload.WorkoutPerformAgain(
            sourceWorkoutId = c.sourceRoot,
            id = c.targetRoot,
            startedAtMillis = 1000L,
            childIds = childIdsOf(c),
        )

    private fun startWorkoutOf(c: CopyIds): MutationPayload.RoutineStartWorkout =
        MutationPayload.RoutineStartWorkout(
            sourceRoutineId = c.sourceRoot,
            id = c.targetRoot,
            startedAtMillis = 1000L,
            childIds = childIdsOf(c),
        )

    private fun saveAsRoutineOf(c: CopyIds): MutationPayload.WorkoutSaveAsRoutine =
        MutationPayload.WorkoutSaveAsRoutine(
            sourceWorkoutId = c.sourceRoot,
            id = c.targetRoot,
            name = "Copy",
            childIds = childIdsOf(c),
        )

    private fun payloadsOf(c: CopyIds): List<Pair<String, MutationPayload>> = listOf(
        "performAgain" to performAgainOf(c),
        "startWorkout" to startWorkoutOf(c),
        "saveAsRoutine" to saveAsRoutineOf(c),
    )

    private fun envelopesOf(c: CopyIds, mutBase: Int): List<Pair<String, MutationEnvelopeModel>> = listOf(
        "performAgain" to MutationEnvelopeModel(
            mutationId = uid(mutBase),
            kind = MutationKind.WORKOUT_PERFORM_AGAIN,
            aggregateType = AggregateType.WORKOUT,
            aggregateId = c.targetRoot,
            localGeneration = 1L,
            knownServerRevision = null,
            payload = performAgainOf(c),
        ),
        "startWorkout" to MutationEnvelopeModel(
            mutationId = uid(mutBase + 1),
            kind = MutationKind.ROUTINE_START_WORKOUT,
            aggregateType = AggregateType.WORKOUT,
            aggregateId = c.targetRoot,
            localGeneration = 1L,
            knownServerRevision = null,
            payload = startWorkoutOf(c),
        ),
        "saveAsRoutine" to MutationEnvelopeModel(
            mutationId = uid(mutBase + 2),
            kind = MutationKind.WORKOUT_SAVE_AS_ROUTINE,
            aggregateType = AggregateType.ROUTINE,
            aggregateId = c.targetRoot,
            localGeneration = 1L,
            knownServerRevision = null,
            payload = saveAsRoutineOf(c),
        ),
    )

    private fun sourceCollisionCases(): List<Pair<String, (CopyIds) -> CopyIds>> = listOf(
        "sourceRoot=sourceEntry" to { c -> c.copy(se1 = c.sourceRoot) },
        "sourceRoot=sourceSet" to { c -> c.copy(ss1 = c.sourceRoot) },
        "sourceEntry=sourceSet-same-binding" to { c -> c.copy(ss1 = c.se1) },
        "sourceEntry=sourceSet-cross-binding" to { c -> c.copy(ss1 = c.se2) },
        "duplicate-sourceEntries" to { c -> c.copy(se2 = c.se1) },
        "duplicate-sourceSets-same-binding" to { c -> c.copy(ss2 = c.ss1) },
        "duplicate-sourceSets-cross-binding" to { c -> c.copy(ss3 = c.ss1) },
    )

    private fun targetCollisionCases(): List<Pair<String, (CopyIds) -> CopyIds>> = listOf(
        "targetRoot=targetEntry" to { c -> c.copy(te1 = c.targetRoot) },
        "targetRoot=targetSet" to { c -> c.copy(ts1 = c.targetRoot) },
        "targetEntry=targetSet-same-binding" to { c -> c.copy(ts1 = c.te1) },
        "targetEntry=targetSet-cross-binding" to { c -> c.copy(ts1 = c.te2) },
        "duplicate-targetEntries" to { c -> c.copy(te2 = c.te1) },
        "duplicate-targetSets-same-binding" to { c -> c.copy(ts2 = c.ts1) },
        "duplicate-targetSets-cross-binding" to { c -> c.copy(ts3 = c.ts1) },
    )

    private fun crossOverlapCases(): List<Pair<String, (CopyIds) -> CopyIds>> = listOf(
        "targetRoot=sourceRoot" to { c -> c.copy(targetRoot = c.sourceRoot) },
        "targetEntry=sourceEntry" to { c -> c.copy(te1 = c.se1) },
        "targetSet=sourceSet" to { c -> c.copy(ts1 = c.ss1) },
        "targetRoot=sourceEntry" to { c -> c.copy(targetRoot = c.se1) },
        "targetRoot=sourceSet" to { c -> c.copy(targetRoot = c.ss1) },
        "targetEntry=sourceRoot" to { c -> c.copy(te1 = c.sourceRoot) },
        "targetEntry=sourceSet" to { c -> c.copy(te1 = c.ss1) },
        "targetSet=sourceRoot" to { c -> c.copy(ts1 = c.sourceRoot) },
        "targetSet=sourceEntry" to { c -> c.copy(ts1 = c.se1) },
        "targetEntry-cross-binding=sourceEntry" to { c -> c.copy(te2 = c.se1) },
        "targetEntry-cross-binding=sourceSet" to { c -> c.copy(te2 = c.ss1) },
        "targetSet-cross-binding=sourceSet" to { c -> c.copy(ts3 = c.ss1) },
        "targetSet-cross-binding=sourceEntry" to { c -> c.copy(ts3 = c.se1) },
        "full-corresponding-overlap" to { c ->
            c.copy(targetRoot = c.sourceRoot, te1 = c.se1, te2 = c.se2, ts1 = c.ss1, ts2 = c.ss2, ts3 = c.ss3)
        },
    )

    // ---- Raw JSON builders ----

    private fun bindingJson(srcEx: String, tgtEx: String, sets: List<Pair<String, String>>): String {
        val setsJson = sets.joinToString(",") { (s, t) -> """{"sourceSetId":"$s","id":"$t"}""" }
        return """{"sourceExerciseId":"$srcEx","id":"$tgtEx","sets":[$setsJson]}"""
    }

    private fun bindingsJson(c: CopyIds): String =
        bindingJson(c.se1, c.te1, listOf(c.ss1 to c.ts1, c.ss2 to c.ts2)) + "," +
            bindingJson(c.se2, c.te2, listOf(c.ss3 to c.ts3))

    private fun performPayloadJson(c: CopyIds): String =
        """{"sourceWorkoutId":"${c.sourceRoot}","id":"${c.targetRoot}","startedAt":"1970-01-01T00:00:01Z","childIds":[${bindingsJson(c)}]}"""

    private fun startPayloadJson(c: CopyIds): String =
        """{"sourceRoutineId":"${c.sourceRoot}","id":"${c.targetRoot}","startedAt":"1970-01-01T00:00:01Z","childIds":[${bindingsJson(c)}]}"""

    private fun savePayloadJson(c: CopyIds): String =
        """{"sourceWorkoutId":"${c.sourceRoot}","id":"${c.targetRoot}","name":"Copy","childIds":[${bindingsJson(c)}]}"""

    private fun envelopeJson(type: String, aggType: String, aggId: String, mutId: String, payloadJson: String): String =
        """{"mutationId":"$mutId","type":"$type","aggregateType":"$aggType","aggregateId":"$aggId","localGeneration":1,"knownServerRevision":null,"payload":$payloadJson}"""

    // ---- Tests ----

    @Test
    fun sourceSideCollisionsRejectAllCommands() {
        for ((caseLabel, collide) in sourceCollisionCases()) {
            val c = collide(baseCopy())
            val secrets = copySecrets(c)
            for ((cmd, payload) in payloadsOf(c)) {
                assertIdentityRejected("source/$cmd/$caseLabel payload", secrets) { validateMutationPayload(payload) }
            }
            for ((cmd, envelope) in envelopesOf(c, 100)) {
                assertIdentityRejected("source/$cmd/$caseLabel envelope", secrets) { validateMutationEnvelope(envelope) }
            }
        }
    }

    @Test
    fun targetSideCollisionsRejectAllCommands() {
        for ((caseLabel, collide) in targetCollisionCases()) {
            val c = collide(baseCopy())
            val secrets = copySecrets(c)
            for ((cmd, payload) in payloadsOf(c)) {
                assertIdentityRejected("target/$cmd/$caseLabel payload", secrets) { validateMutationPayload(payload) }
            }
            for ((cmd, envelope) in envelopesOf(c, 110)) {
                assertIdentityRejected("target/$cmd/$caseLabel envelope", secrets) { validateMutationEnvelope(envelope) }
            }
        }
    }

    @Test
    fun performAgainSourceTargetOverlapAlwaysRejects() {
        for ((caseLabel, overlap) in crossOverlapCases()) {
            val c = overlap(baseCopy())
            val secrets = copySecrets(c)
            assertIdentityRejected("performAgain/$caseLabel payload", secrets) { validateMutationPayload(performAgainOf(c)) }
            val envelope = envelopesOf(c, 120).first { it.first == "performAgain" }.second
            assertIdentityRejected("performAgain/$caseLabel envelope", secrets) { validateMutationEnvelope(envelope) }
            val raw = envelopeJson("workout.performAgain", "workout", c.targetRoot, uid(129), performPayloadJson(c))
            assertIdentityRejected("performAgain/$caseLabel raw", secrets + uid(129)) { decodeMutationEnvelope(raw) }
        }
        // Same root/source UUID with an empty plan still rejects for the same-family command.
        val c = baseCopy().copy(targetRoot = baseCopy().sourceRoot)
        val empty = MutationPayload.WorkoutPerformAgain(
            sourceWorkoutId = c.sourceRoot,
            id = c.targetRoot,
            startedAtMillis = 1000L,
            childIds = emptyList(),
        )
        assertIdentityRejected("performAgain/empty-plan-equal-roots", copySecrets(c)) { validateMutationPayload(empty) }
    }

    @Test
    fun crossFamilyOverlapAcceptedForStartAndSave() {
        for ((caseLabel, overlap) in crossOverlapCases()) {
            val c = overlap(baseCopy())
            assertEquals(startWorkoutOf(c), validateMutationPayload(startWorkoutOf(c)))
            assertEquals(saveAsRoutineOf(c), validateMutationPayload(saveAsRoutineOf(c)))
            val envelopes = envelopesOf(c, 130)
            val start = envelopes.first { it.first == "startWorkout" }.second
            val save = envelopes.first { it.first == "saveAsRoutine" }.second
            assertEquals(start, validateMutationEnvelope(start))
            assertEquals(save, validateMutationEnvelope(save))
        }
        // Raw decode accepts overlapping cross-family plans; empty equal-root plans accept too.
        val full = baseCopy().copy(targetRoot = baseCopy().sourceRoot).let { c ->
            c.copy(te1 = c.se1, te2 = c.se2, ts1 = c.ss1, ts2 = c.ss2, ts3 = c.ss3)
        }
        val startRaw = envelopeJson("routine.startWorkout", "workout", full.targetRoot, uid(139), startPayloadJson(full))
        assertEquals(full.targetRoot, decodeMutationEnvelope(startRaw).aggregateId)
        val saveRaw = envelopeJson("workout.saveAsRoutine", "routine", full.targetRoot, uid(140), savePayloadJson(full))
        assertEquals(full.targetRoot, decodeMutationEnvelope(saveRaw).aggregateId)
        for ((cmd, payload) in listOf(
            "startWorkout" to MutationPayload.RoutineStartWorkout(full.sourceRoot, full.targetRoot, 1000L, emptyList()),
            "saveAsRoutine" to MutationPayload.WorkoutSaveAsRoutine(full.sourceRoot, full.targetRoot, "Copy", emptyList()),
        )) {
            assertEquals(payload, validateMutationPayload(payload))
        }
    }

    @Test
    fun emptyPlanAndEmptySetsPreserveExactOrder() {
        val c = baseCopy()
        // Empty plan with distinct roots is a valid structural shape for every command.
        for ((cmd, payload) in listOf(
            "performAgain" to MutationPayload.WorkoutPerformAgain(c.sourceRoot, c.targetRoot, 1000L, emptyList()),
            "startWorkout" to MutationPayload.RoutineStartWorkout(c.sourceRoot, c.targetRoot, 1000L, emptyList()),
            "saveAsRoutine" to MutationPayload.WorkoutSaveAsRoutine(c.sourceRoot, c.targetRoot, "Copy", emptyList()),
        )) {
            assertEquals("$cmd/empty-plan", payload, validateMutationPayload(payload))
        }
        // Entry bindings with no sets are valid shapes; cross-family overlap still must not excuse side duplication.
        val emptySets = c.copy()
        for ((cmd, payload) in listOf(
            "performAgain" to MutationPayload.WorkoutPerformAgain(
                emptySets.sourceRoot, emptySets.targetRoot, 1000L,
                listOf(ChildExerciseBinding(emptySets.se1, emptySets.te1, emptyList())),
            ),
            "startWorkout" to MutationPayload.RoutineStartWorkout(
                emptySets.sourceRoot, emptySets.targetRoot, 1000L,
                listOf(ChildExerciseBinding(emptySets.se1, emptySets.te1, emptyList())),
            ),
            "saveAsRoutine" to MutationPayload.WorkoutSaveAsRoutine(
                emptySets.sourceRoot, emptySets.targetRoot, "Copy",
                listOf(ChildExerciseBinding(emptySets.se1, emptySets.te1, emptyList())),
            ),
        )) {
            assertEquals("$cmd/empty-sets", payload, validateMutationPayload(payload))
        }
        // Arbitrary binding order is preserved exactly: no lookup, no sorting.
        val reversed = listOf(
            ChildExerciseBinding(c.se2, c.te2, listOf(ChildSetBinding(c.ss3, c.ts3))),
            ChildExerciseBinding(c.se1, c.te1, listOf(ChildSetBinding(c.ss2, c.ts2), ChildSetBinding(c.ss1, c.ts1))),
        )
        val payload = MutationPayload.RoutineStartWorkout(c.sourceRoot, c.targetRoot, 1000L, reversed)
        val validated = validateMutationPayload(payload)
        assertTrue(validated is MutationPayload.RoutineStartWorkout)
        assertEquals(reversed, (validated as MutationPayload.RoutineStartWorkout).childIds)
        assertEquals(reversed, validateMutationPayload(MutationPayload.WorkoutSaveAsRoutine(c.sourceRoot, c.targetRoot, "Copy", reversed)).let {
            (it as MutationPayload.WorkoutSaveAsRoutine).childIds
        })
    }

    @Test
    fun encoderAndDurableParityForCopyRules() {
        // Representative rejections hold on encode/capture/raw-durable paths too.
        val srcBad = baseCopy().copy(ss1 = baseCopy().se1)
        val srcSecrets = copySecrets(srcBad)
        val srcEnvelope = envelopesOf(srcBad, 140).first { it.first == "performAgain" }.second
        assertIdentityRejected("encode/source-collision", srcSecrets) { encodeMutationEnvelope(srcEnvelope) }
        assertIdentityRejected("capture/source-collision", srcSecrets) {
            captureDurableRequest(srcEnvelope, listOf(AffectedRootModel(srcEnvelope.aggregateType, srcEnvelope.aggregateId, 1L)))
        }
        val srcInner = envelopeJson("workout.performAgain", "workout", srcBad.targetRoot, uid(149), performPayloadJson(srcBad))
        val srcWrapper =
            """{"payloadVersion":1,"requestJson":${JSONObject.quote(srcInner)},"affectedRoots":[{"aggregateType":"workout","aggregateId":"${srcBad.targetRoot}","localGeneration":1}]}"""
        assertIdentityRejected("durable/source-collision", srcSecrets + uid(149)) { decodeDurableRequest(srcWrapper) }

        val overlap = baseCopy().copy(te1 = baseCopy().se1)
        val overlapSecrets = copySecrets(overlap)
        val overlapEnvelope = envelopesOf(overlap, 150).first { it.first == "performAgain" }.second
        assertIdentityRejected("encode/performAgain-overlap", overlapSecrets) { encodeMutationEnvelope(overlapEnvelope) }

        // Representative cross-family acceptance holds through raw decode, encode and capture.
        val ok = baseCopy().copy(targetRoot = baseCopy().sourceRoot)
        val saveEnvelope = envelopesOf(ok, 160).first { it.first == "saveAsRoutine" }.second
        assertEquals(saveEnvelope, validateMutationEnvelope(saveEnvelope))
        encodeMutationEnvelope(saveEnvelope)
        val captured = captureDurableRequest(saveEnvelope, listOf(AffectedRootModel(AggregateType.ROUTINE, ok.targetRoot, 1L)))
        assertEquals(1, captured.payloadVersion)
        assertEquals("{\"mutations\":[" + captured.requestJson + "]}", encodePushMutationsBody(listOf(captured)))
    }

    @Test
    fun durableCrossFamilyBytesCorruptAndUnsupported() {
        // Equal cross-family UUIDs are valid v1 with exact byte preservation, not a new version.
        val c = baseCopy().copy(targetRoot = baseCopy().sourceRoot).let {
            it.copy(te1 = it.se1, te2 = it.se2, ts1 = it.ss1, ts2 = it.ss2, ts3 = it.ss3)
        }
        val payload = savePayloadJson(c)
        val inner = "{\n" +
            "  \"mutationId\" : \"${uid(170)}\" ,\n" +
            "  \"type\" : \"workout.saveAsRoutine\" ,\n" +
            "  \"aggregateType\" : \"routine\" ,\n" +
            "  \"aggregateId\" : \"${c.targetRoot}\" ,\n" +
            "  \"localGeneration\" : 1 ,\n" +
            "  \"knownServerRevision\" : null ,\n" +
            "  \"payload\" : $payload\n" +
            "}"
        val wrapper =
            """{"payloadVersion":1,"requestJson":${JSONObject.quote(inner)},"affectedRoots":[{"aggregateType":"routine","aggregateId":"${c.targetRoot}","localGeneration":1}]}"""
        val decoded = decodeDurableRequest(wrapper)
        assertEquals(inner, decoded.requestJson)
        assertEquals(1, decoded.payloadVersion)
        assertEquals("{\"mutations\":[$inner]}", encodePushMutationsBody(listOf(decoded)))

        // Supported wrapper with forbidden same-family aliases rejects without rewriting.
        val bad = baseCopy().copy(targetRoot = baseCopy().sourceRoot)
        val badInner = envelopeJson("workout.performAgain", "workout", bad.targetRoot, uid(171), performPayloadJson(bad))
        val badWrapper =
            """{"payloadVersion":1,"requestJson":${JSONObject.quote(badInner)},"affectedRoots":[{"aggregateType":"workout","aggregateId":"${bad.targetRoot}","localGeneration":1}]}"""
        assertIdentityRejected("durable/corrupt-same-family", copySecrets(bad) + uid(171)) {
            decodeDurableRequest(badWrapper)
        }

        // Unsupported wrapper versions stay a distinct failure.
        val unsupported =
            """{"payloadVersion":999,"requestJson":${JSONObject.quote(inner)},"affectedRoots":[{"aggregateType":"routine","aggregateId":"${c.targetRoot}","localGeneration":1}]}"""
        try {
            decodeDurableRequest(unsupported)
            fail("expected distinct unsupported-version failure")
        } catch (e: UnsupportedDurableVersionException) {
            // Catching the exact supplied type is the contract assertion.
        }
    }

    @Test
    fun mutationIdIndependentAndAdditionGuardsRetained() {
        val c = baseCopy()
        // Mutation id equal to the new target root is an independent identity for copy commands.
        for ((cmd, kind, aggType) in listOf(
            Triple("performAgain", MutationKind.WORKOUT_PERFORM_AGAIN, AggregateType.WORKOUT),
            Triple("saveAsRoutine", MutationKind.WORKOUT_SAVE_AS_ROUTINE, AggregateType.ROUTINE),
        )) {
            val payload = if (cmd == "performAgain") performAgainOf(c) else saveAsRoutineOf(c)
            val envelope = MutationEnvelopeModel(
                mutationId = c.targetRoot,
                kind = kind,
                aggregateType = aggType,
                aggregateId = c.targetRoot,
                localGeneration = 1L,
                knownServerRevision = null,
                payload = payload,
            )
            assertEquals("$cmd/mutationId=root", envelope, validateMutationEnvelope(envelope))
        }
        // Existing addition target guards: planned ids must stay distinct from the target root.
        val target = uid(180)
        val validAdditions = listOf(
            ExerciseAddition(id = uid(181), exerciseId = uid(20), setIds = listOf(uid(182))),
            ExerciseAddition(id = uid(183), exerciseId = uid(20), setIds = listOf(uid(184))),
        )
        val validEnvelope = MutationEnvelopeModel(
            mutationId = uid(185),
            kind = MutationKind.WORKOUT_ADD_EXERCISES,
            aggregateType = AggregateType.WORKOUT,
            aggregateId = target,
            localGeneration = 1L,
            knownServerRevision = null,
            payload = MutationPayload.WorkoutAddExercises(validAdditions),
        )
        assertEquals(validEnvelope, validateMutationEnvelope(validEnvelope))
        val aliasSecrets = listOf(target, uid(20), uid(186), uid(187))
        val entryAlias = MutationPayload.WorkoutAddExercises(
            listOf(ExerciseAddition(id = target, exerciseId = uid(20), setIds = listOf(uid(186)))),
        )
        assertIdentityRejected("addExercises/entry-alias", aliasSecrets) {
            validateMutationEnvelope(validEnvelope.copy(payload = entryAlias))
        }
        val setAlias = MutationPayload.WorkoutAddExercises(
            listOf(ExerciseAddition(id = uid(187), exerciseId = uid(20), setIds = listOf(target))),
        )
        assertIdentityRejected("addExercises/set-alias", aliasSecrets) {
            validateMutationEnvelope(validEnvelope.copy(payload = setAlias))
        }
    }
}
