package com.dragosfotache.gymtracka.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.equipmentFixture
import com.dragosfotache.gymtracka.data.gym.exerciseFixture
import com.dragosfotache.gymtracka.data.gym.muscleGroupFixture
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryStop
import com.dragosfotache.gymtracka.data.sync.engine.SnapshotDownloadStop
import com.dragosfotache.gymtracka.data.sync.model.EquipmentRefModel
import com.dragosfotache.gymtracka.data.sync.model.MuscleGroupRefModel
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationLease
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationStatus
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R08c independent baseline acceptance: complete-snapshot canonical
 * establishment, absence semantics and protected publication recovery.
 *
 * Real Room, no mocked DAOs/stores/admission, no sleeps. Every expectation is
 * a literal authored from docs/archive/remaining-work/R08c-task.md and the
 * coordinator-confirmed API contracts; no production encoder output is used as
 * an oracle and no production source was inspected. Existing strict-codec,
 * budget, staging, driver and publisher matrices are reused, not duplicated.
 * Structured child contexts are inherited; no Gradle execution occurs here.
 */
class SnapshotBaselineTest : GymDatabaseTest() {

    @Test
    fun terminalCompletion_installsBaselineWithoutVisiblePublication() = runTest {
        database.stageSeedBaseline(
            cursor = STAGE_SYNC_CURSOR,
            lastSuccessMillis = BASE_LAST_SUCCESS,
            account = CLAIM_ACCOUNT_A,
            cacheEpoch = BASE_EPOCH,
            baselineComplete = false,
        )
        // Other scope and separate local rows must survive the selected baseline untouched.
        database.stageSeedSecondScope(
            cursor = STAGE_SYNC_CURSOR_2,
            lastSuccessMillis = null,
            account = CLAIM_ACCOUNT_B,
            cacheEpoch = BASE_EPOCH,
        )
        database.claimSeedWorkout(BASE_WORKOUT_2, CLAIM_ACCOUNT_B, "Foreign visible")
        database.claimSeedWorkout(BASE_LOCAL_NEW, null, "Separate local")
        // Global lookup rows the frozen graph must resolve against but never rewrite.
        insertMuscleGroups(muscleGroupFixture(id = BASE_MUSCLE_SEED, name = "Chest"))
        insertEquipment(equipmentFixture(id = BASE_EQUIPMENT_SEED, name = "Dumbbell"))
        insertExercises(exerciseFixture(id = BASE_BUNDLED_SEED, name = "Bench Press"))
        // Whole visible domain: no partial custom/routine/child publication may escape this oracle.
        val domainBefore = database.baseDomainSnapshot()
        val owner = claimOwner()

        // Page one carries a workout whose custom reference arrives only on a
        // later page, plus a saved routine; page two supplies the dependency.
        val pageOne = stagePage(
            listOf(
                stageWorkoutUpsert(
                    id = BASE_WORKOUT_1,
                    name = "Base Workout",
                    revision = 7L,
                    exercises = listOf(baseWorkoutEntry()),
                ),
                stageRoutineUpsert(id = BASE_ROUTINE_1, name = "Base Routine", revision = 3L),
            ),
            BASE_BASELINE_1, BASE_TOKEN_1, true,
        )
        val pageTwo = stagePage(
            listOf(stageCustomUpsert(id = BASE_CUSTOM_1, name = "Base Custom", revision = 5L)),
            BASE_BASELINE_1, null, false,
        )
        val api = SnapshotDeliveryScriptApi(
            database,
            mutableListOf(),
            mutableListOf(SnapshotScriptResponse.Page(pageOne), SnapshotScriptResponse.Page(pageTwo)),
        )
        val (download, baseline) = database.baseWithLoop(owner, api) { run, loop ->
            val started = loop.start(run, BASE_STAGE_1, pageLimit = BASE_PAGE_LIMIT)
            assertTrue(started.push.stop is PushDeliveryStop.Empty)
            val terminal = checkNotNull(started.download) { "fenced push Empty must authorize download" }
            terminal to loop.complete(run, terminal)
        }
        assertTrue(download.stop is SnapshotDownloadStop.Downloaded)
        assertEquals(2, download.committedPages)
        assertEquals(3, download.committedRecords)
        assertBaselineResult(baseline, 1L, BASE_BASELINE_1, 2L, 3L, false)
        baseAssertRedacted(download.toString(), "download")

        // Canonical baseline installed at epoch+1; source last-success exact;
        // materialized epoch preserved; stage deleted.
        val cache = database.r05cReadCache(CLAIM_ACCOUNT_A)!!
        assertTrue(cache.baselineComplete)
        assertEquals(1L, cache.cacheEpoch)
        assertTrue(cache.projectionDirty)
        assertEquals(0L, cache.materializedEpoch)
        val state = database.syncDao().getSyncState(CLAIM_ACCOUNT_A)!!
        assertEquals(BASE_BASELINE_1, state.cursor)
        assertEquals(BASE_LAST_SUCCESS, state.lastSuccessMillis)
        assertEquals("upsert", database.baseCanonicalState(CLAIM_ACCOUNT_A, R05B_TYPE_WORKOUT, BASE_WORKOUT_1))
        assertEquals(7L, database.baseCanonicalRevision(CLAIM_ACCOUNT_A, R05B_TYPE_WORKOUT, BASE_WORKOUT_1))
        assertNotNull(database.baseCanonicalJson(CLAIM_ACCOUNT_A, R05B_TYPE_WORKOUT, BASE_WORKOUT_1))
        assertEquals("upsert", database.baseCanonicalState(CLAIM_ACCOUNT_A, R05B_TYPE_ROUTINE, BASE_ROUTINE_1))
        assertEquals(3L, database.baseCanonicalRevision(CLAIM_ACCOUNT_A, R05B_TYPE_ROUTINE, BASE_ROUTINE_1))
        assertEquals("upsert", database.baseCanonicalState(CLAIM_ACCOUNT_A, R05B_TYPE_CUSTOM, BASE_CUSTOM_1))
        assertEquals(5L, database.baseCanonicalRevision(CLAIM_ACCOUNT_A, R05B_TYPE_CUSTOM, BASE_CUSTOM_1))
        // Previously unknown staged roots carry a zero local-generation watermark.
        assertEquals(0L, database.baseWatermark(CLAIM_ACCOUNT_A, R05B_TYPE_WORKOUT, BASE_WORKOUT_1))
        assertEquals(0L, database.baseWatermark(CLAIM_ACCOUNT_A, R05B_TYPE_CUSTOM, BASE_CUSTOM_1))
        assertTrue(database.stageRowsFor(CLAIM_ACCOUNT_A).isEmpty())

        // No visible domain publication: the old/local graph is byte-identical across every domain table.
        assertEquals(domainBefore, database.baseDomainSnapshot())
        // Other scope preserved exactly.
        assertEquals(STAGE_SYNC_CURSOR_2, database.syncDao().getSyncState(CLAIM_ACCOUNT_B)?.cursor)
        assertEquals(BASE_EPOCH, database.r05cReadCache(CLAIM_ACCOUNT_B)!!.cacheEpoch)

        // A second snapshot carrying the old unchanged root plus a new draft
        // rebases without any retained pull history.
        val pageThree = stagePage(
            listOf(
                stageWorkoutUpsert(
                    id = BASE_WORKOUT_1,
                    name = "Base Workout",
                    revision = 7L,
                    exercises = listOf(baseWorkoutEntry()),
                ),
                stageCustomUpsert(id = BASE_CUSTOM_1, name = "Base Custom", revision = 5L),
                baseDraftRoutineUpsert(),
            ),
            BASE_BASELINE_2, null, false,
        )
        val secondApi = SnapshotDeliveryScriptApi(
            database,
            mutableListOf(),
            mutableListOf(SnapshotScriptResponse.Page(pageThree)),
        )
        val second = database.baseWithLoop(owner, secondApi, 1L) { run, loop ->
            val started = loop.start(run, BASE_STAGE_2, pageLimit = BASE_PAGE_LIMIT)
            val terminal = checkNotNull(started.download) { "second download must be authorized" }
            loop.complete(run, terminal)
        }
        assertBaselineResult(second, 2L, BASE_BASELINE_2, 1L, 3L, false)
        assertEquals(7L, database.baseCanonicalRevision(CLAIM_ACCOUNT_A, R05B_TYPE_WORKOUT, BASE_WORKOUT_1))
        assertEquals("upsert", database.baseCanonicalState(CLAIM_ACCOUNT_A, R05B_TYPE_CUSTOM, BASE_CUSTOM_1))
        assertEquals("upsert", database.baseCanonicalState(CLAIM_ACCOUNT_A, R05B_TYPE_ROUTINE, BASE_ROUTINE_2))
        assertEquals(BASE_BASELINE_2, database.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.cursor)
        assertEquals(BASE_LAST_SUCCESS, database.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.lastSuccessMillis)
        assertEquals(domainBefore, database.baseDomainSnapshot())
    }

    @Test
    fun equalNameOnlyReplacement_succeeds_staleAndConflictingEqual_rejected() = runTest {
        database.stageSeedBaseline(
            cursor = STAGE_SYNC_CURSOR,
            lastSuccessMillis = BASE_LAST_SUCCESS,
            account = CLAIM_ACCOUNT_A,
            cacheEpoch = BASE_EPOCH,
            baselineComplete = false,
        )
        val owner = claimOwner()
        val firstPage = stagePage(
            listOf(
                stageWorkoutUpsert(
                    id = BASE_WORKOUT_1,
                    name = "Base Workout",
                    revision = 7L,
                    exercises = listOf(baseWorkoutEntry(exerciseName = "Base Press")),
                ),
                stageCustomUpsert(id = BASE_CUSTOM_1, name = "Base Custom", revision = 5L),
            ),
            BASE_BASELINE_1, null, false,
        )
        val first = database.baseDownloadAndComplete(
            owner, BASE_STAGE_1, listOf(SnapshotScriptResponse.Page(firstPage)),
        )
        assertBaselineResult(first.second, 1L, BASE_BASELINE_1, 1L, 2L, false)

        // Equal revision with only a convenience-label difference is ignored,
        // and the validated staged frozen body refreshes the retained labels.
        val renamedPage = stagePage(
            listOf(
                stageWorkoutUpsert(
                    id = BASE_WORKOUT_1,
                    name = "Base Workout",
                    revision = 7L,
                    exercises = listOf(baseWorkoutEntry(exerciseName = "Renamed Press")),
                ),
                stageCustomUpsert(id = BASE_CUSTOM_1, name = "Base Custom", revision = 5L),
            ),
            BASE_BASELINE_2, null, false,
        )
        val renamed = database.baseDownloadAndComplete(
            owner, BASE_STAGE_2, listOf(SnapshotScriptResponse.Page(renamedPage)), epoch = 1L,
        )
        assertBaselineResult(renamed.second, 2L, BASE_BASELINE_2, 1L, 2L, false)
        assertEquals(7L, database.baseCanonicalRevision(CLAIM_ACCOUNT_A, R05B_TYPE_WORKOUT, BASE_WORKOUT_1))
        val retainedJson = checkNotNull(database.baseCanonicalJson(CLAIM_ACCOUNT_A, R05B_TYPE_WORKOUT, BASE_WORKOUT_1))
        val retainedEntries = JSONObject(retainedJson).getJSONArray("exercises")
        assertEquals(1, retainedEntries.length())
        assertEquals("Renamed Press", retainedEntries.getJSONObject(0).getString("exerciseName"))

        // A lower incoming frozen revision fails closed; the oracle starts
        // AFTER the terminal download (start legitimately stages) so only the
        // completion attempt itself must change nothing.
        val stalePage = stagePage(
            listOf(stageWorkoutUpsert(id = BASE_WORKOUT_1, name = "Base Workout", revision = 6L)),
            BASE_BASELINE_2, null, false,
        )
        val staleApi = SnapshotDeliveryScriptApi(
            database, mutableListOf(), mutableListOf(SnapshotScriptResponse.Page(stalePage)),
        )
        database.baseWithLoop(owner, staleApi, 2L) { run, loop ->
            val terminal = checkNotNull(loop.start(run, BASE_STAGE_3, pageLimit = BASE_PAGE_LIMIT).download)
            val beforeStale = database.claimSnapshot()
            expectBaselineReject("lower frozen revision") { loop.complete(run, terminal) }
            assertEquals(beforeStale, database.claimSnapshot())
        }
        assertEquals(7L, database.baseCanonicalRevision(CLAIM_ACCOUNT_A, R05B_TYPE_WORKOUT, BASE_WORKOUT_1))
        assertEquals(2L, database.r05cReadCache(CLAIM_ACCOUNT_A)!!.cacheEpoch)
        assertEquals(1, database.stageRowsFor(CLAIM_ACCOUNT_A).size)
        database.baseAbandonObserved(owner, 2L)

        // An equal revision disagreeing on authoritative fields also fails closed.
        // The matching custom is staged, keeping only the set-identity conflict invalid.
        val conflictPage = stagePage(
            listOf(
                stageWorkoutUpsert(
                    id = BASE_WORKOUT_1,
                    name = "Base Workout",
                    revision = 7L,
                    exercises = listOf(baseWorkoutEntry(setId = BASE_SET_2)),
                ),
                stageCustomUpsert(id = BASE_CUSTOM_1, name = "Base Custom", revision = 5L),
            ),
            BASE_BASELINE_2, null, false,
        )
        val conflictApi = SnapshotDeliveryScriptApi(
            database, mutableListOf(), mutableListOf(SnapshotScriptResponse.Page(conflictPage)),
        )
        database.baseWithLoop(owner, conflictApi, 2L) { run, loop ->
            val terminal = checkNotNull(loop.start(run, BASE_STAGE_4, pageLimit = BASE_PAGE_LIMIT).download)
            val beforeConflict = database.claimSnapshot()
            expectBaselineReject("conflicting equal revision") { loop.complete(run, terminal) }
            assertEquals(beforeConflict, database.claimSnapshot())
        }
        assertEquals(7L, database.baseCanonicalRevision(CLAIM_ACCOUNT_A, R05B_TYPE_WORKOUT, BASE_WORKOUT_1))
    }

    @Test
    fun emptySnapshot_marksKnownAbsent_retainsUnobserved_protectsProjection() = runTest {
        database.stageSeedBaseline(
            cursor = STAGE_SYNC_CURSOR,
            lastSuccessMillis = BASE_LAST_SUCCESS,
            account = CLAIM_ACCOUNT_A,
            cacheEpoch = BASE_EPOCH,
            baselineComplete = false,
        )
        val owner = claimOwner()
        // Snapshots carry upserts only; the prior known delete is ledger
        // evidence seeded through the raw contract, never a delete snapshot.
        val seedPage = stagePage(
            listOf(
                stageWorkoutUpsert(id = BASE_WORKOUT_1, name = "Base Workout", revision = 7L),
                stageRoutineUpsert(id = BASE_ROUTINE_1, name = "Base Routine", revision = 3L),
                stageCustomUpsert(id = BASE_CUSTOM_1, name = "Base Custom", revision = 5L),
            ),
            BASE_BASELINE_1, null, false,
        )
        val seeded = database.baseDownloadAndComplete(
            owner, BASE_STAGE_1, listOf(SnapshotScriptResponse.Page(seedPage)),
        )
        assertBaselineResult(seeded.second, 1L, BASE_BASELINE_1, 1L, 3L, false)
        database.r05bSeedCanonicalRow(
            CLAIM_ACCOUNT_A, R05B_TYPE_WORKOUT, BASE_UNKNOWN, 11L, "delete", null, 1L, 1L,
        )

        // The empty download starts with an empty outbox; the unsent local
        // creation is captured AFTER begin/terminal but BEFORE complete in the
        // SAME run, so push gating never authorizes a bypass.
        val db = database
        val emptyApi = SnapshotDeliveryScriptApi(
            db, mutableListOf(), mutableListOf(SnapshotScriptResponse.Page(baseEmptyTerminalPage(BASE_BASELINE_2))),
        )
        db.baseWithLoop(owner, emptyApi, 1L) { run, loop ->
            val terminal = checkNotNull(loop.start(run, BASE_STAGE_2, pageLimit = BASE_PAGE_LIMIT).download)
            assertTrue(terminal.stop is SnapshotDownloadStop.Downloaded)
            db.claimSeedWorkout(BASE_LOCAL_NEW, CLAIM_ACCOUNT_A, "Unsent local")
            val captureStore = db.claimCaptureStore(claimSession(), mutableListOf(BASE_MUTATION_1))
            val captured = db.claimCaptureStart(captureStore, CLAIM_ACCOUNT_A, BASE_LOCAL_NEW)
            val watermarkBefore = db.baseWatermark(CLAIM_ACCOUNT_A, R05B_TYPE_WORKOUT, BASE_LOCAL_NEW)
            assertTrue((watermarkBefore ?: 0L) > 0L)
            // Whole-domain oracle starts AFTER the legitimate capture, BEFORE complete.
            val domainAfterCapture = db.baseDomainSnapshot()

            val baseline = loop.complete(run, terminal)
            assertBaselineResult(baseline, 2L, BASE_BASELINE_2, 1L, 0L, true)

            // Every previously observed root missing from the complete empty
            // graph is absent with known revisions retained; the unsent
            // reservation stays unobserved with its watermark intact.
            assertEquals("snapshot_absent", db.baseCanonicalState(CLAIM_ACCOUNT_A, R05B_TYPE_WORKOUT, BASE_WORKOUT_1))
            assertEquals(7L, db.baseCanonicalRevision(CLAIM_ACCOUNT_A, R05B_TYPE_WORKOUT, BASE_WORKOUT_1))
            assertNull(db.baseCanonicalJson(CLAIM_ACCOUNT_A, R05B_TYPE_WORKOUT, BASE_WORKOUT_1))
            assertEquals("snapshot_absent", db.baseCanonicalState(CLAIM_ACCOUNT_A, R05B_TYPE_ROUTINE, BASE_ROUTINE_1))
            assertEquals(3L, db.baseCanonicalRevision(CLAIM_ACCOUNT_A, R05B_TYPE_ROUTINE, BASE_ROUTINE_1))
            assertEquals("snapshot_absent", db.baseCanonicalState(CLAIM_ACCOUNT_A, R05B_TYPE_CUSTOM, BASE_CUSTOM_1))
            assertEquals(5L, db.baseCanonicalRevision(CLAIM_ACCOUNT_A, R05B_TYPE_CUSTOM, BASE_CUSTOM_1))
            assertEquals("snapshot_absent", db.baseCanonicalState(CLAIM_ACCOUNT_A, R05B_TYPE_WORKOUT, BASE_UNKNOWN))
            assertEquals(11L, db.baseCanonicalRevision(CLAIM_ACCOUNT_A, R05B_TYPE_WORKOUT, BASE_UNKNOWN))
            assertEquals(1L, db.baseWatermark(CLAIM_ACCOUNT_A, R05B_TYPE_WORKOUT, BASE_UNKNOWN))
            assertEquals("unobserved", db.baseCanonicalState(CLAIM_ACCOUNT_A, R05B_TYPE_WORKOUT, BASE_LOCAL_NEW))
            assertEquals(watermarkBefore, db.baseWatermark(CLAIM_ACCOUNT_A, R05B_TYPE_WORKOUT, BASE_LOCAL_NEW))

            // Newer captured intent is preserved byte-exact and protects the whole projection.
            val outbox = db.syncDao().getOutboxForAccount(CLAIM_ACCOUNT_A)
            assertEquals(1, outbox.size)
            assertEquals(captured.mutationId, outbox.single().mutationId)
            val lease = R05cTestLease()
            val publication = run.publishProjection(db.r05cStore(barrier = r05cGrantBarrier(lease)))
            assertEquals(ProjectionPublicationStatus.PROTECTED, publication.status)
            assertEquals(1, lease.closeCount)
            assertEquals(domainAfterCapture, db.baseDomainSnapshot())
        }
        assertEquals(2L, database.r05cReadCache(CLAIM_ACCOUNT_A)!!.cacheEpoch)
        assertTrue(database.r05cReadCache(CLAIM_ACCOUNT_A)!!.projectionDirty)
        assertNotNull(database.syncDao().getOutboxMutation(BASE_MUTATION_1, CLAIM_ACCOUNT_A))
    }

    @Test
    fun emptyBaseline_cleanPublication_removesOnlySelectedVisibleGraph() = runTest {
        // Old visible account graph plus rows that must never be touched.
        database.claimSeedWorkout(BASE_WORKOUT_1, CLAIM_ACCOUNT_A, "Old visible")
        database.claimSeedWorkout(BASE_LOCAL_NEW, null, "Separate local")
        database.claimSeedWorkout(BASE_WORKOUT_2, CLAIM_ACCOUNT_B, "Foreign visible")
        database.stageSeedBaseline(
            cursor = STAGE_SYNC_CURSOR,
            lastSuccessMillis = BASE_LAST_SUCCESS,
            account = CLAIM_ACCOUNT_A,
            cacheEpoch = BASE_EPOCH,
            baselineComplete = false,
        )
        val owner = claimOwner()
        val emptyApi = SnapshotDeliveryScriptApi(
            database, mutableListOf(), mutableListOf(SnapshotScriptResponse.Page(baseEmptyTerminalPage(BASE_BASELINE_1))),
        )
        val baseline = database.baseWithLoop(owner, emptyApi) { run, loop ->
            val terminal = checkNotNull(loop.start(run, BASE_STAGE_1, pageLimit = BASE_PAGE_LIMIT).download)
            loop.complete(run, terminal)
        }
        assertBaselineResult(baseline, 1L, BASE_BASELINE_1, 1L, 0L, false)

        // The old visible account graph disappears ONLY on clean publication.
        assertEquals(1, database.r05cQuery("SELECT * FROM workouts WHERE id = ?", arrayOf(BASE_WORKOUT_1)).size)
        val lease = R05cTestLease()
        val publication = database.baseWithLoop(owner, emptyApi, 1L) { run, _ ->
            run.publishProjection(database.r05cStore(barrier = r05cGrantBarrier(lease)))
        }
        assertEquals(ProjectionPublicationStatus.PUBLISHED, publication.status)
        assertEquals(2L, publication.cacheEpoch)
        assertEquals(1, lease.closeCount)
        val cache = database.r05cReadCache(CLAIM_ACCOUNT_A)!!
        assertEquals(2L, cache.cacheEpoch)
        assertEquals(2L, cache.materializedEpoch)
        assertFalse(cache.projectionDirty)
        assertTrue(database.r05cQuery("SELECT * FROM workouts WHERE id = ?", arrayOf(BASE_WORKOUT_1)).isEmpty())
        assertEquals(1, database.r05cQuery("SELECT * FROM workouts WHERE id = ?", arrayOf(BASE_LOCAL_NEW)).size)
        assertEquals(1, database.r05cQuery("SELECT * FROM workouts WHERE id = ?", arrayOf(BASE_WORKOUT_2)).size)

        // A clean equal-epoch retry is an unchanged no-op with no new signal.
        val retryLease = R05cTestLease()
        val retry = database.baseWithLoop(owner, emptyApi, 2L) { run, _ ->
            run.publishProjection(database.r05cStore(barrier = r05cGrantBarrier(retryLease)))
        }
        assertEquals(ProjectionPublicationStatus.UNCHANGED, retry.status)
        assertEquals(2L, retry.cacheEpoch)
        assertEquals(1, retryLease.closeCount)
    }

    @Test
    fun missingSourceBootstrap_createsExactBaselineWithNullLastSuccess() = runTest {
        database.claimSeedDevice(CLAIM_ACCOUNT_A, CLAIM_GENERATION)
        val owner = claimOwner()
        assertNull(database.syncDao().getSyncState(CLAIM_ACCOUNT_A))
        assertNull(database.r05cReadCache(CLAIM_ACCOUNT_A))
        val page = stagePage(
            listOf(stageCustomUpsert(id = BASE_CUSTOM_1, name = "Base Custom", revision = 5L)),
            BASE_BASELINE_1, null, false,
        )
        val baseline = database.baseWithLoop(owner, baseApi(page)) { run, loop ->
            val terminal = checkNotNull(loop.start(run, BASE_STAGE_1, pageLimit = BASE_PAGE_LIMIT).download)
            loop.complete(run, terminal)
        }
        assertBaselineResult(baseline, 1L, BASE_BASELINE_1, 1L, 1L, false)
        val state = database.syncDao().getSyncState(CLAIM_ACCOUNT_A)!!
        assertEquals(BASE_BASELINE_1, state.cursor)
        assertNull(state.lastSuccessMillis)
        val cache = database.r05cReadCache(CLAIM_ACCOUNT_A)!!
        assertTrue(cache.baselineComplete)
        assertEquals(1L, cache.cacheEpoch)
        assertTrue(cache.projectionDirty)
        assertEquals("upsert", database.baseCanonicalState(CLAIM_ACCOUNT_A, R05B_TYPE_CUSTOM, BASE_CUSTOM_1))
    }

    @Test
    fun completeGraphViolations_rollBackEntireTransaction_stageRetained() = runTest {
        database.stageSeedBaseline(
            cursor = STAGE_SYNC_CURSOR,
            lastSuccessMillis = BASE_LAST_SUCCESS,
            account = CLAIM_ACCOUNT_A,
            cacheEpoch = BASE_EPOCH,
            baselineComplete = false,
        )
        val owner = claimOwner()
        val db = database

        // Unresolved custom reference: the entry points at a global/custom row
        // that no page supplies and the catalogue does not contain.
        val danglingPage = stagePage(
            listOf(
                stageWorkoutUpsert(
                    id = BASE_WORKOUT_1,
                    name = "Base Workout",
                    revision = 7L,
                    exercises = listOf(baseWorkoutEntry(exerciseId = BASE_UNKNOWN)),
                ),
            ),
            BASE_BASELINE_1, null, false,
        )
        db.baseWithLoop(owner, baseApi(danglingPage)) { run, loop ->
            val terminal = checkNotNull(loop.start(run, BASE_STAGE_1, pageLimit = BASE_PAGE_LIMIT).download)
            val beforeDangling = db.claimSnapshot()
            expectBaselineReject("unresolved reference") { loop.complete(run, terminal) }
            assertEquals(beforeDangling, db.claimSnapshot())
        }
        assertEquals(1, db.stageRowsFor(CLAIM_ACCOUNT_A).size)
        db.baseAbandonObserved(owner)

        // Two active workouts in one complete frozen graph.
        val twoActivePage = stagePage(
            listOf(
                stageWorkoutUpsert(id = BASE_WORKOUT_1, name = "First", revision = 7L),
                stageWorkoutUpsert(id = BASE_WORKOUT_2, name = "Second", revision = 9L),
            ),
            BASE_BASELINE_1, null, false,
        )
        db.baseWithLoop(owner, baseApi(twoActivePage)) { run, loop ->
            val terminal = checkNotNull(loop.start(run, BASE_STAGE_2, pageLimit = BASE_PAGE_LIMIT).download)
            val beforeActive = db.claimSnapshot()
            expectBaselineReject("multiple active workouts") { loop.complete(run, terminal) }
            assertEquals(beforeActive, db.claimSnapshot())
        }
        assertEquals(1, db.stageRowsFor(CLAIM_ACCOUNT_A).size)
        db.baseAbandonObserved(owner)

        // One entry UUID bound in two different workout roots. The second
        // root is finished, isolating the wrong-parent invariant from the
        // multiple-active rule above.
        val sharedEntryPage = stagePage(
            listOf(
                stageWorkoutUpsert(
                    id = BASE_WORKOUT_1,
                    name = "First",
                    revision = 7L,
                    exercises = listOf(baseWorkoutEntry(id = BASE_ENTRY_1, exerciseId = BASE_CUSTOM_1)),
                ),
                baseFinishedWorkoutUpsert(
                    id = BASE_WORKOUT_2,
                    name = "Second",
                    revision = 9L,
                    exercises = listOf(baseWorkoutEntry(id = BASE_ENTRY_1, exerciseId = BASE_CUSTOM_1)),
                ),
                stageCustomUpsert(id = BASE_CUSTOM_1, name = "Base Custom", revision = 5L),
            ),
            BASE_BASELINE_1, null, false,
        )
        db.baseWithLoop(owner, baseApi(sharedEntryPage)) { run, loop ->
            val terminal = checkNotNull(loop.start(run, BASE_STAGE_3, pageLimit = BASE_PAGE_LIMIT).download)
            val beforeShared = db.claimSnapshot()
            expectBaselineReject("shared entry binding") { loop.complete(run, terminal) }
            assertEquals(beforeShared, db.claimSnapshot())
        }
        assertEquals(1, db.stageRowsFor(CLAIM_ACCOUNT_A).size)
        db.baseAbandonObserved(owner)

        // Missing global muscle/equipment lookups reject the complete custom graph.
        val ghostRelPage = stagePage(
            listOf(
                baseCustomUpsertWithRels(
                    id = BASE_CUSTOM_2,
                    name = "Ghost Custom",
                    revision = 2L,
                    muscles = listOf(MuscleGroupRefModel(BASE_MUSCLE_UNKNOWN, "Ghost Muscle", "primary")),
                    equipment = listOf(EquipmentRefModel(BASE_EQUIPMENT_UNKNOWN, "Ghost Gear", 1)),
                ),
            ),
            BASE_BASELINE_1, null, false,
        )
        db.baseWithLoop(owner, baseApi(ghostRelPage)) { run, loop ->
            val terminal = checkNotNull(loop.start(run, BASE_STAGE_4, pageLimit = BASE_PAGE_LIMIT).download)
            val beforeGhost = db.claimSnapshot()
            expectBaselineReject("missing global lookups") { loop.complete(run, terminal) }
            assertEquals(beforeGhost, db.claimSnapshot())
        }
        assertEquals(1, db.stageRowsFor(CLAIM_ACCOUNT_A).size)

        // Nothing was ever established: no canonical rows, incomplete baseline, original cursor.
        assertTrue(db.r05cQuery("SELECT * FROM sync_canonical_roots").isEmpty())
        assertEquals(false, db.r05cReadCache(CLAIM_ACCOUNT_A)!!.baselineComplete)
        assertEquals(STAGE_SYNC_CURSOR, db.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.cursor)
        assertEquals(BASE_LAST_SUCCESS, db.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.lastSuccessMillis)
    }

    @Test
    fun capturedEditDuringDownload_baselineSucceeds_publicationProtected() = runTest {
        database.stageSeedBaseline(
            cursor = STAGE_SYNC_CURSOR,
            lastSuccessMillis = BASE_LAST_SUCCESS,
            account = CLAIM_ACCOUNT_A,
            cacheEpoch = BASE_EPOCH,
            baselineComplete = false,
        )
        database.claimSeedWorkout(BASE_WORKOUT_2, CLAIM_ACCOUNT_A, "Old visible")
        val owner = claimOwner()
        val pageOne = stagePage(
            listOf(
                stageWorkoutUpsert(
                    id = BASE_WORKOUT_1,
                    name = "Base Workout",
                    revision = 7L,
                    exercises = listOf(baseWorkoutEntry()),
                ),
            ),
            BASE_BASELINE_1, BASE_TOKEN_1, true,
        )
        val pageTwo = stagePage(
            listOf(stageCustomUpsert(id = BASE_CUSTOM_1, name = "Base Custom", revision = 5L)),
            BASE_BASELINE_1, null, false,
        )
        val api = SnapshotDeliveryScriptApi(
            database,
            mutableListOf(),
            mutableListOf(SnapshotScriptResponse.Page(pageOne), SnapshotScriptResponse.Page(pageTwo)),
        )
        val db = database
        db.baseWithLoop(owner, api) { run, loop ->
            val limited = checkNotNull(loop.start(run, BASE_STAGE_1, maxPages = 1, pageLimit = BASE_PAGE_LIMIT).download)
            assertTrue(limited.stop is SnapshotDownloadStop.LimitReached)

            // Newer intent captured through the real capture path AFTER begin.
            db.claimSeedWorkout(BASE_LOCAL_NEW, CLAIM_ACCOUNT_A, "Racing edit")
            val captureStore = db.claimCaptureStore(claimSession(), mutableListOf(BASE_MUTATION_1))
            val captured = db.claimCaptureStart(captureStore, CLAIM_ACCOUNT_A, BASE_LOCAL_NEW)
            val watermarkBefore = db.baseWatermark(CLAIM_ACCOUNT_A, R05B_TYPE_WORKOUT, BASE_LOCAL_NEW)

            val downloaded = loop.continueDownload(run, limited, maxPages = 5)
            assertTrue(downloaded.stop is SnapshotDownloadStop.Downloaded)
            val baseline = loop.complete(run, downloaded)
            assertBaselineResult(baseline, 1L, BASE_BASELINE_1, 2L, 2L, true)
            // The racing capture is legitimate domain state, not publication:
            // the whole-domain oracle starts here, before any publication attempt.
            val domainAfterCapture = db.baseDomainSnapshot()

            // Exact uncertain identity/bytes/order survive; the watermark is not lowered.
            val outbox = db.syncDao().getOutboxForAccount(CLAIM_ACCOUNT_A)
            assertEquals(1, outbox.size)
            assertEquals(captured.mutationId, outbox.single().mutationId)
            assertEquals(captured.payloadJson, outbox.single().payloadJson)
            assertEquals(watermarkBefore, db.baseWatermark(CLAIM_ACCOUNT_A, R05B_TYPE_WORKOUT, BASE_LOCAL_NEW))

            // ANY selected outbox row protects the ENTIRE old/local graph.
            val lease = R05cTestLease()
            val publication = run.publishProjection(db.r05cStore(barrier = r05cGrantBarrier(lease)))
            assertEquals(ProjectionPublicationStatus.PROTECTED, publication.status)
            assertEquals(1L, publication.cacheEpoch)
            assertEquals(1, lease.closeCount)
            assertEquals(domainAfterCapture, db.baseDomainSnapshot())
        }
        assertEquals(1, database.syncDao().getOutboxForAccount(CLAIM_ACCOUNT_A).size)
        assertEquals(1, database.r05cQuery("SELECT * FROM workouts WHERE id = ?", arrayOf(BASE_WORKOUT_2)).size)
        assertEquals(1, database.r05cQuery("SELECT * FROM workouts WHERE id = ?", arrayOf(BASE_LOCAL_NEW)).size)
        assertTrue(database.r05cReadCache(CLAIM_ACCOUNT_A)!!.projectionDirty)
    }

    @Test
    fun baselineThenLocalCollision_publicationFails_fileReopenRecoveryPublishes() = runTest {
        // A surviving signed-out row collides with the staged canonical root:
        // baseline is retained while visible publication stays blocked.
        database.stageSeedBaseline(
            cursor = STAGE_SYNC_CURSOR,
            lastSuccessMillis = BASE_LAST_SUCCESS,
            account = CLAIM_ACCOUNT_A,
            cacheEpoch = BASE_EPOCH,
            baselineComplete = false,
        )
        database.claimSeedWorkout(BASE_WORKOUT_1, null, "Local same id")
        val owner = claimOwner()
        val page = stagePage(
            listOf(
                stageWorkoutUpsert(
                    id = BASE_WORKOUT_1,
                    name = "Base Workout",
                    revision = 7L,
                    exercises = listOf(baseWorkoutEntry()),
                ),
                stageCustomUpsert(id = BASE_CUSTOM_1, name = "Base Custom", revision = 5L),
            ),
            BASE_BASELINE_1, null, false,
        )
        val baseline = database.baseWithLoop(owner, baseApi(page)) { run, loop ->
            val terminal = checkNotNull(loop.start(run, BASE_STAGE_1, pageLimit = BASE_PAGE_LIMIT).download)
            loop.complete(run, terminal)
        }
        assertBaselineResult(baseline, 1L, BASE_BASELINE_1, 1L, 2L, false)

        val lease = R05cTestLease()
        val api = baseApi(page)
        database.baseWithLoop(owner, api, 1L) { run, _ ->
            expectBaselineReject("physical collision") {
                run.publishProjection(database.r05cStore(barrier = r05cGrantBarrier(lease)))
            }
        }
        assertEquals(1, lease.closeCount)
        // The committed dirty baseline, cursor and old visible graph survive the failed publication.
        assertEquals("upsert", database.baseCanonicalState(CLAIM_ACCOUNT_A, R05B_TYPE_WORKOUT, BASE_WORKOUT_1))
        assertEquals(7L, database.baseCanonicalRevision(CLAIM_ACCOUNT_A, R05B_TYPE_WORKOUT, BASE_WORKOUT_1))
        val cache = database.r05cReadCache(CLAIM_ACCOUNT_A)!!
        assertTrue(cache.baselineComplete)
        assertEquals(1L, cache.cacheEpoch)
        assertTrue(cache.projectionDirty)
        assertEquals(BASE_BASELINE_1, database.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.cursor)
        assertEquals(1, database.r05cQuery("SELECT * FROM workouts WHERE id = ?", arrayOf(BASE_WORKOUT_1)).size)

        // Crash/uncertain publication recovery on a fresh file-backed reopen:
        // a new run at the durable epoch publishes without another snapshot.
        // ONE shared owner spans the close/reopen on this physical database.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = claimUniqueDbName("base")
        val fileOwner = PushRunOwner()
        val fileDatabase = claimFileDatabase(context, name)
        try {
            fileDatabase.stageSeedBaseline(
                cursor = STAGE_SYNC_CURSOR,
                lastSuccessMillis = BASE_LAST_SUCCESS,
                account = CLAIM_ACCOUNT_A,
                cacheEpoch = BASE_EPOCH,
                baselineComplete = false,
            )
            val filePage = stagePage(
                listOf(
                    stageWorkoutUpsert(
                        id = BASE_WORKOUT_1,
                        name = "Base Workout",
                        revision = 7L,
                        exercises = listOf(baseWorkoutEntry()),
                    ),
                    stageRoutineUpsert(id = BASE_ROUTINE_1, name = "Base Routine", revision = 3L),
                    stageCustomUpsert(id = BASE_CUSTOM_1, name = "Base Custom", revision = 5L),
                ),
                BASE_BASELINE_1, null, false,
            )
            val fileBaseline = fileDatabase.baseDownloadAndComplete(
                fileOwner, BASE_STAGE_1, listOf(SnapshotScriptResponse.Page(filePage)),
            )
            assertBaselineResult(fileBaseline.second, 1L, BASE_BASELINE_1, 1L, 3L, false)
            fileDatabase.close()

            val reopened = claimFileDatabase(context, name)
            try {
                val reopenLease = R05cTestLease()
                val reopenApi = SnapshotDeliveryScriptApi(reopened, mutableListOf(), mutableListOf())
                val publication = reopened.baseWithLoop(fileOwner, reopenApi, 1L) { run, _ ->
                    run.publishProjection(reopened.r05cStore(barrier = r05cGrantBarrier(reopenLease)))
                }
                assertEquals(ProjectionPublicationStatus.PUBLISHED, publication.status)
                assertEquals(2L, publication.cacheEpoch)
                assertEquals(1, reopenLease.closeCount)
                val reopenedCache = reopened.r05cReadCache(CLAIM_ACCOUNT_A)!!
                assertEquals(2L, reopenedCache.cacheEpoch)
                assertEquals(2L, reopenedCache.materializedEpoch)
                assertFalse(reopenedCache.projectionDirty)
                assertEquals(BASE_BASELINE_1, reopened.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.cursor)
                assertEquals(BASE_LAST_SUCCESS, reopened.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.lastSuccessMillis)
                assertEquals(
                    1,
                    reopened.r05cQuery("SELECT * FROM workouts WHERE id = ?", arrayOf(BASE_WORKOUT_1)).size,
                )
            } finally {
                reopened.close()
            }
        } finally {
            fileDatabase.close()
        }
    }

    @Test
    fun baselinePlusOne_publishPlusTwo_sameRunCheckpointAndStaleEpochFences() = runTest {
        database.stageSeedBaseline(
            cursor = STAGE_SYNC_CURSOR,
            lastSuccessMillis = BASE_LAST_SUCCESS,
            account = CLAIM_ACCOUNT_A,
            cacheEpoch = BASE_EPOCH,
            baselineComplete = false,
        )
        val owner = claimOwner()
        val page = stagePage(
            listOf(
                stageWorkoutUpsert(id = BASE_WORKOUT_1, name = "Base Workout", revision = 7L),
                stageCustomUpsert(id = BASE_CUSTOM_1, name = "Base Custom", revision = 5L),
            ),
            BASE_BASELINE_1, null, false,
        )
        val db = database
        db.baseWithLoop(owner, baseApi(page)) { run, loop ->
            val terminal = checkNotNull(loop.start(run, BASE_STAGE_1, pageLimit = BASE_PAGE_LIMIT).download)
            val baseline = loop.complete(run, terminal)
            assertBaselineResult(baseline, 1L, BASE_BASELINE_1, 1L, 2L, false)

            // The returned baseline epoch is live on this run: a checkpoint
            // reads and checks at epoch 1.
            val firstCheckpoint = checkNotNull(run.readPullCheckpoint()) { "baseline plus cursor must admit a checkpoint" }
            run.checkPullCheckpoint(firstCheckpoint)

            // Publication increments AGAIN on the same run; the old pull
            // token is retired by the attempt while the fresh read works.
            val lease = R05cTestLease()
            val publication = run.publishProjection(db.r05cStore(barrier = r05cGrantBarrier(lease)))
            assertEquals(ProjectionPublicationStatus.PUBLISHED, publication.status)
            assertEquals(2L, publication.cacheEpoch)
            expectBaselineReject("old epoch-1 pull token") { run.checkPullCheckpoint(firstCheckpoint) }
            val secondCheckpoint = checkNotNull(run.readPullCheckpoint()) { "epoch-2 checkpoint must read" }
            run.checkPullCheckpoint(secondCheckpoint)

            // Same-run newer work is admitted at the published epoch.
            db.claimSeedWorkout(BASE_LOCAL_NEW, CLAIM_ACCOUNT_A, "Post-publish work")
            val captureStore = db.claimCaptureStore(claimSession(), mutableListOf(BASE_MUTATION_2))
            db.claimCaptureStart(captureStore, CLAIM_ACCOUNT_A, BASE_LOCAL_NEW)
            assertEquals(1, db.syncDao().getOutboxForAccount(CLAIM_ACCOUNT_A).size)
        }
        assertEquals(2L, database.r05cReadCache(CLAIM_ACCOUNT_A)!!.cacheEpoch)

        // Older epoch callers fail closed: stale run admission and stale direct publication.
        expectBaselineReject("old-epoch run") {
            database.baseWithLoop(owner, baseApi(page), BASE_EPOCH) { _, _ -> }
        }
        expectBaselineReject("old-epoch direct publication") {
            database.r05cStore(barrier = r05cGrantBarrier(R05cTestLease())).publish(BASE_EPOCH)
        }
        assertEquals(2L, database.r05cReadCache(CLAIM_ACCOUNT_A)!!.cacheEpoch)
        assertEquals(BASE_BASELINE_1, database.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.cursor)
    }

    @Test
    fun publicationCloseFailure_lostResult_freshRunReturnsUnchanged() = runTest {
        database.stageSeedBaseline(
            cursor = STAGE_SYNC_CURSOR,
            lastSuccessMillis = BASE_LAST_SUCCESS,
            account = CLAIM_ACCOUNT_A,
            cacheEpoch = BASE_EPOCH,
            baselineComplete = false,
        )
        val owner = claimOwner()
        val page = stagePage(
            listOf(stageCustomUpsert(id = BASE_CUSTOM_1, name = "Base Custom", revision = 5L)),
            BASE_BASELINE_1, null, false,
        )
        val baseline = database.baseDownloadAndComplete(owner, BASE_STAGE_1, listOf(SnapshotScriptResponse.Page(page)))
        assertBaselineResult(baseline.second, 1L, BASE_BASELINE_1, 1L, 1L, false)

        // The lease close throws a literal test exception AFTER the graph
        // commit, simulating a lost publication result: the caller observes
        // failure while the durable publication stands.
        val failingLease = CloseFailingLease()
        val api = baseApi(page)
        try {
            database.baseWithLoop(owner, api, 1L) { run, _ ->
                run.publishProjection(database.r05cStore(barrier = r05cGrantBarrier(failingLease)))
            }
            fail("close failure must propagate, never read as success")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (expected: Exception) {
            assertTrue("close failure must surface [${expected.message}]", expected.message!!.contains("base-test postcommit"))
        }
        assertEquals(1, failingLease.closeCount)
        val committed = database.r05cReadCache(CLAIM_ACCOUNT_A)!!
        assertEquals(2L, committed.cacheEpoch)
        assertEquals(2L, committed.materializedEpoch)
        assertFalse(committed.projectionDirty)
        assertEquals(1, database.r05cQuery("SELECT * FROM exercises WHERE id = ?", arrayOf(BASE_CUSTOM_1)).size)

        // A fresh durable-epoch run observes the commit without any HTTP:
        // clean retry is UNCHANGED with no new signal.
        val retryLease = R05cTestLease()
        val retry = database.baseWithLoop(owner, api, 2L) { run, _ ->
            run.publishProjection(database.r05cStore(barrier = r05cGrantBarrier(retryLease)))
        }
        assertEquals(ProjectionPublicationStatus.UNCHANGED, retry.status)
        assertEquals(2L, retry.cacheEpoch)
        assertEquals(1, retryLease.closeCount)
    }

    private class CloseFailingLease : ProjectionPublicationLease {
        var closeCount = 0
        override fun checkValid() = Unit
        override fun close() {
            closeCount++
            throw IllegalStateException("base-test postcommit close failure")
        }
    }

    private fun baseApi(page: SnapshotResultModel) =
        SnapshotDeliveryScriptApi(
            database,
            mutableListOf(),
            mutableListOf(SnapshotScriptResponse.Page(page)),
        )

    private fun fail(message: String): Nothing = throw AssertionError(message)
}
