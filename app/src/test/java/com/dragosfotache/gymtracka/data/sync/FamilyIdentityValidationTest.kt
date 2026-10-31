package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.codec.captureDurableRequest
import com.dragosfotache.gymtracka.data.sync.codec.decodeDurableRequest
import com.dragosfotache.gymtracka.data.sync.codec.decodeMutationEnvelope
import com.dragosfotache.gymtracka.data.sync.codec.decodePullResult
import com.dragosfotache.gymtracka.data.sync.codec.decodePushResult
import com.dragosfotache.gymtracka.data.sync.codec.decodeRoutineInput
import com.dragosfotache.gymtracka.data.sync.codec.decodeRoutineRecord
import com.dragosfotache.gymtracka.data.sync.codec.decodeSnapshotResult
import com.dragosfotache.gymtracka.data.sync.codec.decodeWorkoutInput
import com.dragosfotache.gymtracka.data.sync.codec.decodeWorkoutRecord
import com.dragosfotache.gymtracka.data.sync.codec.encodeMutationEnvelope
import com.dragosfotache.gymtracka.data.sync.codec.encodePushMutationsBody
import com.dragosfotache.gymtracka.data.sync.codec.encodeRoutineInput
import com.dragosfotache.gymtracka.data.sync.codec.encodeRoutineRecord
import com.dragosfotache.gymtracka.data.sync.codec.encodeWorkoutInput
import com.dragosfotache.gymtracka.data.sync.codec.encodeWorkoutRecord
import com.dragosfotache.gymtracka.data.sync.model.AffectedRootModel
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.ImportRecord
import com.dragosfotache.gymtracka.data.sync.model.MutationEnvelopeModel
import com.dragosfotache.gymtracka.data.sync.model.MutationKind
import com.dragosfotache.gymtracka.data.sync.model.MutationPayload
import com.dragosfotache.gymtracka.data.sync.model.RoutineExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineInputModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineRecordModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineSetModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.UnsupportedDurableVersionException
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutImportModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutInputModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutRecordModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutSetModel
import com.dragosfotache.gymtracka.data.sync.model.validateMutationEnvelope
import com.dragosfotache.gymtracka.data.sync.model.validateMutationPayload
import com.dragosfotache.gymtracka.data.sync.model.validateRoutineInputModel
import com.dragosfotache.gymtracka.data.sync.model.validateRoutineRecordModel
import com.dragosfotache.gymtracka.data.sync.model.validateWorkoutImportModel
import com.dragosfotache.gymtracka.data.sync.model.validateWorkoutInputModel
import com.dragosfotache.gymtracka.data.sync.model.validateWorkoutRecordModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * workout/routine family identity reservations at pure validation boundaries.
 *
 * Family set = root id + every entry id + every set id across ALL entries. Every member
 * must be distinct across all roles and parents. `exerciseId` (catalogue reference) and
 * `mutationId` stay independent. Fixtures vary ONLY identities; all other scalars are
 * otherwise-valid per coordinator facts.
 */
class FamilyIdentityValidationTest {

    private fun uid(n: Int): String = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')

    private data class FamIds(
        val root: String,
        val e1: String,
        val e2: String,
        val s1: String,
        val s2: String,
        val s3: String,
        val exRef: String,
    )

    private fun baseIds(): FamIds = FamIds(
        root = uid(10),
        e1 = uid(11),
        e2 = uid(12),
        s1 = uid(13),
        s2 = uid(14),
        s3 = uid(15),
        exRef = uid(20),
    )

    private fun famSecrets(f: FamIds): List<String> =
        listOf(f.root, f.e1, f.e2, f.s1, f.s2, f.s3, f.exRef)

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

    // ---- Model fixtures (otherwise-valid scalars) ----

    private fun wSet(id: String, position: Int): WorkoutSetModel = WorkoutSetModel(
        id = id,
        position = position,
        repetitions = 8,
        weightKg = 20.0,
        isCompleted = true,
        restSeconds = 3,
    )

    private fun wEntry(id: String, position: Int, sets: List<WorkoutSetModel>, exRef: String) =
        WorkoutExerciseInputModel(
            id = id,
            exerciseId = exRef,
            position = position,
            notes = "",
            restDurationSeconds = 30,
            sets = sets,
        )

    private fun wRecordEntry(id: String, position: Int, sets: List<WorkoutSetModel>, exRef: String) =
        WorkoutExerciseModel(
            id = id,
            exerciseId = exRef,
            position = position,
            notes = "",
            restDurationSeconds = 30,
            sets = sets,
            exerciseName = "Exercise",
        )

    private fun workoutInputOf(f: FamIds): WorkoutInputModel = WorkoutInputModel(
        id = f.root,
        name = "",
        status = "active",
        startedAtMillis = 1000L,
        exercises = listOf(
            wEntry(f.e1, 0, listOf(wSet(f.s1, 0), wSet(f.s2, 1)), f.exRef),
            wEntry(f.e2, 1, listOf(wSet(f.s3, 0)), f.exRef),
        ),
    )

    private fun workoutRecordOf(f: FamIds, status: String = "finished", finishedAtMillis: Long? = 2000L): WorkoutRecordModel = WorkoutRecordModel(
        id = f.root,
        name = "",
        status = status,
        startedAtMillis = 1000L,
        finishedAtMillis = finishedAtMillis,
        revision = 1L,
        exercises = listOf(
            wRecordEntry(f.e1, 0, listOf(wSet(f.s1, 0), wSet(f.s2, 1)), f.exRef),
            wRecordEntry(f.e2, 1, listOf(wSet(f.s3, 0)), f.exRef),
        ),
    )

    private fun workoutImportOf(f: FamIds, status: String = "finished", finishedAtMillis: Long? = 2000L): WorkoutImportModel = WorkoutImportModel(
        id = f.root,
        name = "",
        status = status,
        startedAtMillis = 1000L,
        finishedAtMillis = finishedAtMillis,
        exercises = listOf(
            wEntry(f.e1, 0, listOf(wSet(f.s1, 0), wSet(f.s2, 1)), f.exRef),
            wEntry(f.e2, 1, listOf(wSet(f.s3, 0)), f.exRef),
        ),
    )

    private fun rSet(id: String, position: Int): RoutineSetModel = RoutineSetModel(
        id = id,
        position = position,
        repetitions = 8,
        weightKg = 20.0,
    )

    private fun rEntry(id: String, position: Int, sets: List<RoutineSetModel>, exRef: String) =
        RoutineExerciseInputModel(
            id = id,
            exerciseId = exRef,
            position = position,
            restDurationSeconds = 30,
            sets = sets,
        )

    private fun rRecordEntry(id: String, position: Int, sets: List<RoutineSetModel>, exRef: String) =
        RoutineExerciseModel(
            id = id,
            exerciseId = exRef,
            position = position,
            restDurationSeconds = 30,
            sets = sets,
            exerciseName = "Exercise",
        )

    private fun routineInputOf(f: FamIds, isDraft: Boolean = false): RoutineInputModel = RoutineInputModel(
        id = f.root,
        name = "",
        isDraft = isDraft,
        exercises = listOf(
            rEntry(f.e1, 0, listOf(rSet(f.s1, 0), rSet(f.s2, 1)), f.exRef),
            rEntry(f.e2, 1, listOf(rSet(f.s3, 0)), f.exRef),
        ),
    )

    private fun routineRecordOf(f: FamIds, isDraft: Boolean = false): RoutineRecordModel = RoutineRecordModel(
        id = f.root,
        name = "",
        isDraft = isDraft,
        revision = 1L,
        exercises = listOf(
            rRecordEntry(f.e1, 0, listOf(rSet(f.s1, 0), rSet(f.s2, 1)), f.exRef),
            rRecordEntry(f.e2, 1, listOf(rSet(f.s3, 0)), f.exRef),
        ),
    )

    private fun collisionCases(): List<Pair<String, (FamIds) -> FamIds>> = listOf(
        "root=entry" to { f -> f.copy(root = f.e1) },
        "root=set" to { f -> f.copy(root = f.s1) },
        "entry=set-same-entry" to { f -> f.copy(s1 = f.e1) },
        "entry=set-cross-entry" to { f -> f.copy(s1 = f.e2) },
        "duplicate-entries" to { f -> f.copy(e2 = f.e1) },
        "duplicate-sets-same-entry" to { f -> f.copy(s2 = f.s1) },
        "duplicate-sets-cross-entry" to { f -> f.copy(s3 = f.s1) },
    )

    // ---- Raw JSON builders (wire names, RFC3339 timestamps) ----

    private fun wSetJson(id: String, position: Int): String =
        """{"id":"$id","position":$position,"repetitions":8,"weightKg":20.0,"isCompleted":true,"restSeconds":3}"""

    private fun wSetsJson(vararg ids: String): String =
        ids.mapIndexed { i, id -> wSetJson(id, i) }.joinToString(",", "[", "]")

    private fun wEntryJson(id: String, exRef: String, position: Int, setsJson: String): String =
        """{"id":"$id","exerciseId":"$exRef","position":$position,"notes":"","restDurationSeconds":30,"sets":$setsJson}"""

    private fun wRecordEntryJson(id: String, exRef: String, position: Int, setsJson: String): String =
        """{"id":"$id","exerciseId":"$exRef","position":$position,"notes":"","restDurationSeconds":30,"exerciseName":"Exercise","sets":$setsJson}"""

    private fun workoutInputJson(f: FamIds): String {
        val e1 = wEntryJson(f.e1, f.exRef, 0, wSetsJson(f.s1, f.s2))
        val e2 = wEntryJson(f.e2, f.exRef, 1, wSetsJson(f.s3))
        return """{"id":"${f.root}","name":"","status":"active","startedAt":"1970-01-01T00:00:01Z","exercises":[$e1,$e2]}"""
    }

    private fun workoutRecordJson(f: FamIds, status: String = "finished", finishedAt: String? = "1970-01-01T00:00:02Z"): String {
        val e1 = wRecordEntryJson(f.e1, f.exRef, 0, wSetsJson(f.s1, f.s2))
        val e2 = wRecordEntryJson(f.e2, f.exRef, 1, wSetsJson(f.s3))
        val finishedAtJson = if (finishedAt == null) "null" else "\"$finishedAt\""
        return """{"id":"${f.root}","name":"","status":"$status","startedAt":"1970-01-01T00:00:01Z","finishedAt":$finishedAtJson,"revision":1,"exercises":[$e1,$e2]}"""
    }

    private fun workoutImportJson(f: FamIds, status: String = "finished", finishedAt: String? = "1970-01-01T00:00:02Z"): String {
        val e1 = wEntryJson(f.e1, f.exRef, 0, wSetsJson(f.s1, f.s2))
        val e2 = wEntryJson(f.e2, f.exRef, 1, wSetsJson(f.s3))
        val finishedAtJson = if (finishedAt == null) "null" else "\"$finishedAt\""
        return """{"id":"${f.root}","name":"","status":"$status","startedAt":"1970-01-01T00:00:01Z","finishedAt":$finishedAtJson,"exercises":[$e1,$e2]}"""
    }

    private fun rSetJson(id: String, position: Int): String =
        """{"id":"$id","position":$position,"repetitions":8,"weightKg":20.0}"""

    private fun rSetsJson(vararg ids: String): String =
        ids.mapIndexed { i, id -> rSetJson(id, i) }.joinToString(",", "[", "]")

    private fun rEntryJson(id: String, exRef: String, position: Int, setsJson: String): String =
        """{"id":"$id","exerciseId":"$exRef","position":$position,"restDurationSeconds":30,"sets":$setsJson}"""

    private fun rRecordEntryJson(id: String, exRef: String, position: Int, setsJson: String): String =
        """{"id":"$id","exerciseId":"$exRef","position":$position,"restDurationSeconds":30,"exerciseName":"Exercise","sets":$setsJson}"""

    private fun routineInputJson(f: FamIds, isDraft: Boolean = false): String {
        val e1 = rEntryJson(f.e1, f.exRef, 0, rSetsJson(f.s1, f.s2))
        val e2 = rEntryJson(f.e2, f.exRef, 1, rSetsJson(f.s3))
        return """{"id":"${f.root}","name":"","isDraft":$isDraft,"exercises":[$e1,$e2]}"""
    }

    private fun routineRecordJson(f: FamIds, isDraft: Boolean = false): String {
        val e1 = rRecordEntryJson(f.e1, f.exRef, 0, rSetsJson(f.s1, f.s2))
        val e2 = rRecordEntryJson(f.e2, f.exRef, 1, rSetsJson(f.s3))
        return """{"id":"${f.root}","name":"","isDraft":$isDraft,"revision":1,"exercises":[$e1,$e2]}"""
    }

    private fun envelopeJson(type: String, aggType: String, aggId: String, mutId: String, payloadJson: String): String =
        """{"mutationId":"$mutId","type":"$type","aggregateType":"$aggType","aggregateId":"$aggId","localGeneration":1,"knownServerRevision":null,"payload":$payloadJson}"""

    private fun durableWrapperJson(innerExact: String, aggType: String, aggId: String): String =
        """{"payloadVersion":1,"requestJson":${JSONObject.quote(innerExact)},"affectedRoots":[{"aggregateType":"$aggType","aggregateId":"$aggId","localGeneration":1}]}"""

    private fun upsertChangeJson(recordJson: String, aggType: String, aggId: String): String =
        """{"aggregateType":"$aggType","aggregateId":"$aggId","operation":"upsert","revision":1,"record":$recordJson}"""

    // ---- Tests ----

    @Test
    fun aggregateRoleCollisionsRejectEveryRepresentation() {
        val reps: List<Pair<String, (FamIds) -> Unit>> = listOf(
            "workout-input" to { f -> validateWorkoutInputModel(workoutInputOf(f)) },
            "workout-record-finished" to { f -> validateWorkoutRecordModel(workoutRecordOf(f)) },
            "workout-record-active" to { f -> validateWorkoutRecordModel(workoutRecordOf(f, "active", null)) },
            "workout-import-finished" to { f -> validateWorkoutImportModel(workoutImportOf(f)) },
            "workout-import-active" to { f -> validateWorkoutImportModel(workoutImportOf(f, "active", null)) },
            "routine-input-saved" to { f -> validateRoutineInputModel(routineInputOf(f)) },
            "routine-input-draft" to { f -> validateRoutineInputModel(routineInputOf(f, true)) },
            "routine-record-saved" to { f -> validateRoutineRecordModel(routineRecordOf(f)) },
            "routine-record-draft" to { f -> validateRoutineRecordModel(routineRecordOf(f, true)) },
        )
        for ((repLabel, run) in reps) {
            for ((caseLabel, collide) in collisionCases()) {
                val f = collide(baseIds())
                assertIdentityRejected("$repLabel/$caseLabel", famSecrets(f)) { run(f) }
            }
        }
    }

    @Test
    fun distinctIdsAcceptedAndPositionsSorted() {
        val f = baseIds()
        assertEquals(workoutInputOf(f), validateWorkoutInputModel(workoutInputOf(f)))
        assertEquals(workoutRecordOf(f), validateWorkoutRecordModel(workoutRecordOf(f)))
        assertEquals(workoutRecordOf(f, "active", null), validateWorkoutRecordModel(workoutRecordOf(f, "active", null)))
        assertEquals(workoutImportOf(f), validateWorkoutImportModel(workoutImportOf(f)))
        assertEquals(workoutImportOf(f, "active", null), validateWorkoutImportModel(workoutImportOf(f, "active", null)))
        assertEquals(routineInputOf(f), validateRoutineInputModel(routineInputOf(f)))
        assertEquals(routineInputOf(f, true), validateRoutineInputModel(routineInputOf(f, true)))
        assertEquals(routineRecordOf(f), validateRoutineRecordModel(routineRecordOf(f)))
        assertEquals(routineRecordOf(f, true), validateRoutineRecordModel(routineRecordOf(f, true)))

        // Reordered arrays with valid consecutive positions still sort to canonical order.
        val reorderedWorkout = workoutInputOf(f).copy(
            exercises = listOf(
                wEntry(f.e2, 1, listOf(wSet(f.s3, 0)), f.exRef),
                wEntry(f.e1, 0, listOf(wSet(f.s2, 1), wSet(f.s1, 0)), f.exRef),
            ),
        )
        assertEquals(workoutInputOf(f), validateWorkoutInputModel(reorderedWorkout))
        val reorderedRoutine = routineInputOf(f).copy(
            exercises = listOf(
                rEntry(f.e2, 1, listOf(rSet(f.s3, 0)), f.exRef),
                rEntry(f.e1, 0, listOf(rSet(f.s2, 1), rSet(f.s1, 0)), f.exRef),
            ),
        )
        assertEquals(routineInputOf(f), validateRoutineInputModel(reorderedRoutine))

        // Explicit null set values are preserved, not treated as identity collisions.
        val nullSets = routineInputOf(f).copy(
            exercises = listOf(rEntry(f.e1, 0, listOf(RoutineSetModel(id = f.s1, position = 0, repetitions = null, weightKg = null)), f.exRef)),
        )
        assertEquals(nullSets, validateRoutineInputModel(nullSets))
    }

    @Test
    fun blankDraftAndEmptySavedShapesAccepted() {
        val draftEmpty = RoutineInputModel(id = uid(70), name = "", isDraft = true, exercises = emptyList())
        assertEquals(draftEmpty, validateRoutineInputModel(draftEmpty))
        val savedEmpty = RoutineInputModel(id = uid(71), name = "", isDraft = false, exercises = emptyList())
        assertEquals(savedEmpty, validateRoutineInputModel(savedEmpty))
        val draftRecord = RoutineRecordModel(id = uid(73), name = "", isDraft = true, revision = 1L, exercises = emptyList())
        assertEquals(draftRecord, validateRoutineRecordModel(draftRecord))
        val activeImport = WorkoutImportModel(
            id = uid(72),
            name = "",
            status = "active",
            startedAtMillis = 1000L,
            finishedAtMillis = null,
            exercises = listOf(wEntry(uid(74), 0, listOf(wSet(uid(76), 0)), uid(75))),
        )
        assertEquals(activeImport, validateWorkoutImportModel(activeImport))
    }

    @Test
    fun exerciseReferenceAndMutationIdAreIndependent() {
        val f = baseIds()
        // Catalogue reference text equal to family UUIDs stays a valid shape in every role, both families.
        val refRoles: List<Pair<String, (FamIds) -> String>> = listOf(
            "exRef=root" to { ids -> ids.root },
            "exRef=entry" to { ids -> ids.e1 },
            "exRef=set" to { ids -> ids.s1 },
        )
        for ((label, pick) in refRoles) {
            val workoutAliased = workoutInputOf(f.copy(exRef = pick(f)))
            assertEquals(label, workoutAliased, validateWorkoutInputModel(workoutAliased))
            val routineAliased = routineInputOf(f.copy(exRef = pick(f)))
            assertEquals(label, routineAliased, validateRoutineInputModel(routineAliased))
        }
        // Same UUID in separate workout/routine families remains representable.
        val shared = uid(77)
        assertEquals(
            workoutInputOf(f).copy(id = shared),
            validateWorkoutInputModel(workoutInputOf(f).copy(id = shared)),
        )
        assertEquals(
            routineInputOf(f).copy(id = shared),
            validateRoutineInputModel(routineInputOf(f).copy(id = shared)),
        )
        // Mutation id equal to the aggregate root is an independent identity.
        val input = workoutInputOf(f)
        val envelope = MutationEnvelopeModel(
            mutationId = f.root,
            kind = MutationKind.WORKOUT_UPSERT,
            aggregateType = AggregateType.WORKOUT,
            aggregateId = f.root,
            localGeneration = 1L,
            knownServerRevision = null,
            payload = MutationPayload.WorkoutUpsert(input),
        )
        assertEquals(envelope, validateMutationEnvelope(envelope))
    }

    @Test
    fun rawDecodersAndEncodersEnforceAggregateRule() {
        val f = baseIds()
        // Valid raw shapes round-trip to the same models (proves negative fixtures differ ONLY in IDs).
        assertEquals(workoutInputOf(f), decodeWorkoutInput(workoutInputJson(f)))
        assertEquals(workoutRecordOf(f), decodeWorkoutRecord(workoutRecordJson(f)))
        assertEquals(workoutRecordOf(f, "active", null), decodeWorkoutRecord(workoutRecordJson(f, "active", null)))
        assertEquals(routineInputOf(f), decodeRoutineInput(routineInputJson(f)))
        assertEquals(routineInputOf(f, true), decodeRoutineInput(routineInputJson(f, true)))
        assertEquals(routineRecordOf(f), decodeRoutineRecord(routineRecordJson(f)))

        for ((caseLabel, collide) in collisionCases()) {
            val c = collide(baseIds())
            val secrets = famSecrets(c)
            assertIdentityRejected("decodeWorkoutInput/$caseLabel", secrets) { decodeWorkoutInput(workoutInputJson(c)) }
            assertIdentityRejected("decodeWorkoutRecord/$caseLabel", secrets) { decodeWorkoutRecord(workoutRecordJson(c)) }
            assertIdentityRejected("decodeWorkoutRecord-active/$caseLabel", secrets) { decodeWorkoutRecord(workoutRecordJson(c, "active", null)) }
            assertIdentityRejected("decodeRoutineInput/$caseLabel", secrets) { decodeRoutineInput(routineInputJson(c)) }
            assertIdentityRejected("decodeRoutineInput-draft/$caseLabel", secrets) { decodeRoutineInput(routineInputJson(c, true)) }
            assertIdentityRejected("decodeRoutineRecord/$caseLabel", secrets) { decodeRoutineRecord(routineRecordJson(c)) }
        }

        // Envelope/raw routes for upserts and both imports.
        val bad = baseIds().copy(root = baseIds().e1)
        val badSecrets = famSecrets(bad)
        val workoutUpsertBad = envelopeJson("workout.upsert", "workout", bad.root, uid(60), workoutInputJson(bad))
        assertIdentityRejected("envelope/workout-upsert-alias", badSecrets) { decodeMutationEnvelope(workoutUpsertBad) }
        val routineUpsertBad = envelopeJson("routine.upsert", "routine", bad.root, uid(61), routineInputJson(bad))
        assertIdentityRejected("envelope/routine-upsert-alias", badSecrets) { decodeMutationEnvelope(routineUpsertBad) }
        val workoutImportBad = envelopeJson("aggregate.import", "workout", bad.root, uid(62), """{"record":${workoutImportJson(bad)}}""")
        assertIdentityRejected("envelope/workout-import-alias", badSecrets) { decodeMutationEnvelope(workoutImportBad) }
        val routineImportBad = envelopeJson("aggregate.import", "routine", bad.root, uid(63), """{"record":${routineInputJson(bad)}}""")
        assertIdentityRejected("envelope/routine-import-alias", badSecrets) { decodeMutationEnvelope(routineImportBad) }
        val activeImportBad = envelopeJson("aggregate.import", "workout", bad.root, uid(55), """{"record":${workoutImportJson(bad, "active", null)}}""")
        assertIdentityRejected("envelope/workout-import-active-alias", badSecrets) { decodeMutationEnvelope(activeImportBad) }
        val draftRoutineImportBad = envelopeJson("aggregate.import", "routine", bad.root, uid(56), """{"record":${routineInputJson(bad, true)}}""")
        assertIdentityRejected("envelope/routine-import-draft-alias", badSecrets) { decodeMutationEnvelope(draftRoutineImportBad) }

        val goodEnvelope = envelopeJson("workout.upsert", "workout", f.root, uid(64), workoutInputJson(f))
        val decoded = decodeMutationEnvelope(goodEnvelope)
        assertEquals(f.root, decoded.aggregateId)

        // Encoder paths reject the same aliases.
        assertIdentityRejected("encodeWorkoutInput/root=entry", badSecrets) { encodeWorkoutInput(workoutInputOf(bad)) }
        assertIdentityRejected("encodeWorkoutRecord/root=entry", badSecrets) { encodeWorkoutRecord(workoutRecordOf(bad)) }
        assertIdentityRejected("encodeRoutineInput/root=entry", badSecrets) { encodeRoutineInput(routineInputOf(bad)) }
        assertIdentityRejected("encodeRoutineRecord/root=entry", badSecrets) { encodeRoutineRecord(routineRecordOf(bad)) }
        val badModelEnvelope = MutationEnvelopeModel(
            mutationId = uid(65),
            kind = MutationKind.WORKOUT_UPSERT,
            aggregateType = AggregateType.WORKOUT,
            aggregateId = bad.root,
            localGeneration = 1L,
            knownServerRevision = null,
            payload = MutationPayload.WorkoutUpsert(workoutInputOf(bad)),
        )
        assertIdentityRejected("encodeMutationEnvelope/upsert-alias", badSecrets) { encodeMutationEnvelope(badModelEnvelope) }
    }

    @Test
    fun upsertAndImportAliasesRejectThroughPayloadEnvelopeAndDurable() {
        val bad = baseIds().copy(e2 = baseIds().e1)
        val secrets = famSecrets(bad)
        val payloads: List<Pair<String, MutationPayload>> = listOf(
            "workout-upsert" to MutationPayload.WorkoutUpsert(workoutInputOf(bad)),
            "routine-upsert" to MutationPayload.RoutineUpsert(routineInputOf(bad)),
            "workout-import" to MutationPayload.AggregateImport(ImportRecord.Workout(workoutImportOf(bad))),
            "routine-import-saved" to MutationPayload.AggregateImport(ImportRecord.Routine(routineInputOf(bad))),
            "routine-import-draft" to MutationPayload.AggregateImport(ImportRecord.Routine(routineInputOf(bad, true))),
        )
        for ((label, payload) in payloads) {
            assertIdentityRejected("payload/$label", secrets) { validateMutationPayload(payload) }
        }
        val envelopes: List<MutationEnvelopeModel> = listOf(
            MutationEnvelopeModel(uid(66), MutationKind.WORKOUT_UPSERT, AggregateType.WORKOUT, bad.root, 1L, null, MutationPayload.WorkoutUpsert(workoutInputOf(bad))),
            MutationEnvelopeModel(uid(67), MutationKind.ROUTINE_UPSERT, AggregateType.ROUTINE, bad.root, 1L, null, MutationPayload.RoutineUpsert(routineInputOf(bad))),
            MutationEnvelopeModel(uid(68), MutationKind.AGGREGATE_IMPORT, AggregateType.WORKOUT, bad.root, 1L, null, MutationPayload.AggregateImport(ImportRecord.Workout(workoutImportOf(bad)))),
            MutationEnvelopeModel(uid(69), MutationKind.AGGREGATE_IMPORT, AggregateType.ROUTINE, bad.root, 1L, null, MutationPayload.AggregateImport(ImportRecord.Routine(routineInputOf(bad)))),
            MutationEnvelopeModel(uid(84), MutationKind.AGGREGATE_IMPORT, AggregateType.ROUTINE, bad.root, 1L, null, MutationPayload.AggregateImport(ImportRecord.Routine(routineInputOf(bad, true)))),
        )
        for (envelope in envelopes) {
            assertIdentityRejected("envelope/${envelope.kind}", secrets) { validateMutationEnvelope(envelope) }
            assertIdentityRejected("capture/${envelope.kind}", secrets) {
                captureDurableRequest(envelope, listOf(AffectedRootModel(envelope.aggregateType, envelope.aggregateId, 1L)))
            }
        }
        // Supported wrapper carrying forbidden IDs rejects as corruption without rewriting.
        val innerBad = envelopeJson("workout.upsert", "workout", bad.root, uid(78), workoutInputJson(bad))
        assertIdentityRejected("durable/corrupt-wrapper", secrets + uid(78)) {
            decodeDurableRequest(durableWrapperJson(innerBad, "workout", bad.root))
        }
        // Unsupported wrapper versions stay a distinct failure.
        val f = baseIds()
        val innerGood = envelopeJson("workout.upsert", "workout", f.root, uid(79), workoutInputJson(f))
        val unsupported =
            """{"payloadVersion":999,"requestJson":${JSONObject.quote(innerGood)},"affectedRoots":[{"aggregateType":"workout","aggregateId":"${f.root}","localGeneration":1}]}"""
        try {
            decodeDurableRequest(unsupported)
            fail("expected distinct unsupported-version failure")
        } catch (e: UnsupportedDurableVersionException) {
            // Catching the exact supplied type is the contract assertion.
        }
    }

    @Test
    fun canonicalProtocolRecordsRejectRoleAliases() {
        val f = baseIds()
        val wAliasSecrets = famSecrets(f.copy(e1 = f.root))
        val rAliasSecrets = famSecrets(f.copy(e1 = f.root))
        val wGood = upsertChangeJson(workoutRecordJson(f), "workout", f.root)
        val wBad = upsertChangeJson(workoutRecordJson(f.copy(e1 = f.root)), "workout", f.root)
        val rGood = upsertChangeJson(routineRecordJson(f), "routine", f.root)
        val rBad = upsertChangeJson(routineRecordJson(f.copy(e1 = f.root)), "routine", f.root)

        val pushOf: (String) -> String = { change ->
            """{"results":[{"mutationId":"${uid(81)}","status":"accepted","batch":{"batchId":"${uid(80)}","cursor":"opaque","changes":[$change]}}]}"""
        }
        val pullOf: (String) -> String = { change ->
            """{"batches":[{"batchId":"${uid(82)}","cursor":"opaque","changes":[$change]}],"nextCursor":"opaque","hasMore":false}"""
        }
        val snapOf: (String) -> String = { change ->
            """{"records":[$change],"baselineCursor":"opaque","hasMore":false}"""
        }
        val ops: List<Triple<String, (String) -> String, (String) -> Unit>> = listOf(
            Triple("push", pushOf, { json -> decodePushResult(json) }),
            Triple("pull", pullOf, { json -> decodePullResult(json) }),
            Triple("snapshot", snapOf, { json -> decodeSnapshotResult(json) }),
        )
        for ((op, wrap, decode) in ops) {
            decode(wrap(wGood))
            decode(wrap(rGood))
            assertIdentityRejected("$op/workout-canonical-alias", wAliasSecrets) { decode(wrap(wBad)) }
            assertIdentityRejected("$op/routine-canonical-alias", rAliasSecrets) { decode(wrap(rBad)) }
        }
    }

    @Test
    fun durableBytesPreservedForValidV1() {
        val f = baseIds()
        val input = workoutInputOf(f)
        val envelope = MutationEnvelopeModel(
            mutationId = uid(82),
            kind = MutationKind.WORKOUT_UPSERT,
            aggregateType = AggregateType.WORKOUT,
            aggregateId = f.root,
            localGeneration = 1L,
            knownServerRevision = null,
            payload = MutationPayload.WorkoutUpsert(input),
        )
        val roots = listOf(AffectedRootModel(AggregateType.WORKOUT, f.root, 1L))
        val captured = captureDurableRequest(envelope, roots)
        assertEquals(1, captured.payloadVersion)
        assertEquals("{\"mutations\":[" + captured.requestJson + "]}", encodePushMutationsBody(listOf(captured)))
        val reread = decodeDurableRequest(durableWrapperJson(captured.requestJson, "workout", f.root))
        assertEquals(captured.requestJson, reread.requestJson)
        assertEquals(1, reread.payloadVersion)
        assertEquals(f.root, reread.envelope.aggregateId)

        // Unusual whitespace/key spacing inside a hand-authored v1 request stays byte-for-byte.
        val inner = "{\n" +
            "  \"mutationId\" : \"${uid(90)}\" ,\n" +
            "  \"type\" : \"workout.upsert\" ,\n" +
            "  \"aggregateType\" : \"workout\" ,\n" +
            "  \"aggregateId\" : \"${f.root}\" ,\n" +
            "  \"localGeneration\" : 1 ,\n" +
            "  \"knownServerRevision\" : null ,\n" +
            "  \"payload\" : ${workoutInputJson(f)}\n" +
            "}"
        val decoded = decodeDurableRequest(durableWrapperJson(inner, "workout", f.root))
        assertEquals(inner, decoded.requestJson)
        assertEquals("{\"mutations\":[$inner]}", encodePushMutationsBody(listOf(decoded)))
    }
}
