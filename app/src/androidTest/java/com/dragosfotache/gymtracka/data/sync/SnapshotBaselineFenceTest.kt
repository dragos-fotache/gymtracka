package com.dragosfotache.gymtracka.data.sync

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.gym.SyncStateEntity
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.engine.SnapshotDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.engine.SnapshotDownloadResult
import com.dragosfotache.gymtracka.data.sync.engine.SnapshotDownloadStop
import com.dragosfotache.gymtracka.data.sync.model.SnapshotResultModel
import com.dragosfotache.gymtracka.data.sync.store.ProjectionPublicationStatus
import com.dragosfotache.gymtracka.data.sync.store.ProjectionStore
import com.dragosfotache.gymtracka.data.sync.store.PushRun
import com.dragosfotache.gymtracka.data.sync.store.SnapshotBaselineResult
import com.dragosfotache.gymtracka.data.sync.store.SnapshotStage
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/**
 * R08c independent baseline fence acceptance: driver issuance authority,
 * source/session/epoch/corruption fences, atomic rollback, publication
 * selection/pairing and capability-retirement recovery.
 *
 * Real Room, no mocked DAOs/stores/admission, no sleeps. New
 * eligibility/pairing failures assert generic safe rejection (cancellation
 * identity never swallowed) paired with full persisted-state and stage
 * oracles, per the coordinator-confirmed contract; no invented exception
 * taxonomy is asserted. Existing staging/driver/publication matrices are
 * reused, not duplicated.
 */
class SnapshotBaselineFenceTest : GymDatabaseTest() {

    @Test
    fun terminalIssuanceAuthority_foreignWrongRunClosedDuplicate_rejected() = runTest {
        database.stageSeedBaseline(
            cursor = STAGE_SYNC_CURSOR,
            lastSuccessMillis = BASE_LAST_SUCCESS,
            account = CLAIM_ACCOUNT_A,
            cacheEpoch = BASE_EPOCH,
            baselineComplete = false,
        )
        val owner = claimOwner()
        val db = database
        val pageOne = stagePage(
            listOf(stageCustomUpsert(id = BASE_CUSTOM_1, name = "Base Custom", revision = 5L)),
            BASE_BASELINE_1, BASE_TOKEN_1, true,
        )
        val pageTwo = stagePage(emptyList(), BASE_BASELINE_1, null, false)
        val singlePage = stagePage(
            listOf(stageCustomUpsert(id = BASE_CUSTOM_1, name = "Base Custom", revision = 5L)),
            BASE_BASELINE_1, null, false,
        )

        // Nonterminal issuance is not completion authority. The no-change
        // oracle starts AFTER the download (start legitimately stages) so
        // only the completion attempt itself must change nothing.
        db.baseWithLoop(owner, pageApi(db, pageOne, pageTwo)) { run, loop ->
            val limited = checkNotNull(loop.start(run, BASE_STAGE_1, maxPages = 1, pageLimit = BASE_PAGE_LIMIT).download)
            assertTrue(limited.stop is SnapshotDownloadStop.LimitReached)
            val beforeNonterminal = db.claimSnapshot()
            expectBaselineReject("nonterminal completion") { loop.complete(run, limited) }
            assertEquals(beforeNonterminal, db.claimSnapshot())
            run.abandonSnapshotStage(checkNotNull(run.readSnapshotStage()))
        }

        // ONE shared owner spans every store on this physical database. The
        // issuing driver is retained for the wrong/closed-run checks so a
        // foreign-driver rejection cannot mask the intended fences.
        val store = db.claimPushStore(claimSession(), owner)
        val twoPageApi = SnapshotDeliveryScriptApi(
            db, mutableListOf(),
            mutableListOf(SnapshotScriptResponse.Page(pageOne), SnapshotScriptResponse.Page(pageTwo)),
        )
        val issuingLoop = SnapshotDeliveryLoop(store, twoPageApi) { BASE_NOW_MILLIS }
        val foreignLoop = SnapshotDeliveryLoop(store, pageApi(db, singlePage)) { BASE_NOW_MILLIS }
        var issued: SnapshotDownloadResult? = null
        var originRun: PushRun? = null
        store.withRun(BASE_EPOCH) { run ->
            val terminal = checkNotNull(issuingLoop.start(run, BASE_STAGE_1, pageLimit = BASE_PAGE_LIMIT).download)
            assertTrue(terminal.stop is SnapshotDownloadStop.Downloaded)
            issued = terminal
            originRun = run
            val beforeForeign = db.claimSnapshot()
            expectBaselineReject("foreign driver") { foreignLoop.complete(run, terminal) }
            assertEquals(beforeForeign, db.claimSnapshot())
            run.abandonSnapshotStage(checkNotNull(run.readSnapshotStage()))
        }
        val terminal = checkNotNull(issued)
        // Same issuing driver, another run: read/restart observations never
        // adopt append or completion authority.
        store.withRun(BASE_EPOCH) { otherRun ->
            val beforeWrongRun = db.claimSnapshot()
            expectBaselineReject("wrong run") { issuingLoop.complete(otherRun, terminal) }
            assertEquals(beforeWrongRun, db.claimSnapshot())
        }
        // Same issuing driver with its actual originating run closed: the
        // retained origin (not a new unrelated run) must fence closed use.
        val beforeClosed = db.claimSnapshot()
        expectBaselineReject("closed run") { issuingLoop.complete(checkNotNull(originRun), terminal) }
        assertEquals(beforeClosed, db.claimSnapshot())

        // The actual terminal issuance completes exactly once; a repeated
        // completion with the retired result cannot establish anything new.
        db.baseWithLoop(owner, pageApi(db, singlePage)) { run, loop ->
            val downloaded = checkNotNull(loop.start(run, BASE_STAGE_1, pageLimit = BASE_PAGE_LIMIT).download)
            val baseline = loop.complete(run, downloaded)
            assertBaselineResult(baseline, 1L, BASE_BASELINE_1, 1L, 1L, false)
            expectBaselineReject("duplicate completion") { loop.complete(run, downloaded) }
        }
        assertEquals(1L, db.r05cReadCache(CLAIM_ACCOUNT_A)!!.cacheEpoch)
        assertTrue(db.stageRowsFor(CLAIM_ACCOUNT_A).isEmpty())
    }

    @Test
    fun transportFailureResults_neverAuthorizeCompletion() = runTest {
        database.stageSeedBaseline(
            cursor = STAGE_SYNC_CURSOR,
            lastSuccessMillis = BASE_LAST_SUCCESS,
            account = CLAIM_ACCOUNT_A,
            cacheEpoch = BASE_EPOCH,
            baselineComplete = false,
        )
        val owner = claimOwner()
        val db = database

        // A mid-download transport failure cannot be completed around.
        val failingApi = SnapshotDeliveryScriptApi(
            db, mutableListOf(),
            mutableListOf(
                SnapshotScriptResponse.Page(
                    stagePage(
                        listOf(stageCustomUpsert(id = BASE_CUSTOM_1, name = "Base Custom", revision = 5L)),
                        BASE_BASELINE_1, BASE_TOKEN_1, true,
                    ),
                ),
                SnapshotScriptResponse.Failure(SyncApiException.Transient()),
            ),
        )
        db.baseWithLoop(owner, failingApi) { run, loop ->
            val failed = checkNotNull(loop.start(run, BASE_STAGE_1, pageLimit = BASE_PAGE_LIMIT).download)
            assertTrue(failed.stop is SnapshotDownloadStop.Failed)
            val beforeFailure = db.claimSnapshot()
            expectBaselineReject("failure result") { loop.complete(run, failed) }
            assertEquals(beforeFailure, db.claimSnapshot())
        }
        assertEquals(false, db.r05cReadCache(CLAIM_ACCOUNT_A)!!.baselineComplete)
        assertEquals(STAGE_SYNC_CURSOR, db.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.cursor)
        db.baseAbandonObserved(owner)

        // Authentic expiry requests explicit restart, never completion.
        val expiredApi = SnapshotDeliveryScriptApi(
            db, mutableListOf(),
            mutableListOf(SnapshotScriptResponse.Failure(SyncApiException.SnapshotExpired())),
        )
        db.baseWithLoop(owner, expiredApi) { run, loop ->
            val restart = checkNotNull(loop.start(run, BASE_STAGE_2, pageLimit = BASE_PAGE_LIMIT).download)
            assertTrue(restart.stop is SnapshotDownloadStop.RestartRequired)
            val beforeExpiry = db.claimSnapshot()
            expectBaselineReject("expiry result") { loop.complete(run, restart) }
            assertEquals(beforeExpiry, db.claimSnapshot())
        }
        assertEquals(1, db.stageRowsFor(CLAIM_ACCOUNT_A).size)
        db.baseAbandonObserved(owner)

        // An invalid token is not expiry and never authorizes recovery either.
        val invalidApi = SnapshotDeliveryScriptApi(
            db, mutableListOf(),
            mutableListOf(SnapshotScriptResponse.Failure(SyncApiException.InvalidSnapshotToken())),
        )
        db.baseWithLoop(owner, invalidApi) { run, loop ->
            val failed = checkNotNull(loop.start(run, BASE_STAGE_3, pageLimit = BASE_PAGE_LIMIT).download)
            assertTrue(failed.stop is SnapshotDownloadStop.Failed)
            expectBaselineReject("invalid token result") { loop.complete(run, failed) }
        }
        db.baseAbandonObserved(owner)
    }

    @Test
    fun storageOnlyTerminalAndSupersededObservation_rejected() = runTest {
        database.stageSeedBaseline(
            cursor = STAGE_SYNC_CURSOR,
            lastSuccessMillis = BASE_LAST_SUCCESS,
            account = CLAIM_ACCOUNT_A,
            cacheEpoch = BASE_EPOCH,
            baselineComplete = false,
        )
        val owner = claimOwner()
        val db = database
        val page = stagePage(
            listOf(stageCustomUpsert(id = BASE_CUSTOM_1, name = "Base Custom", revision = 5L)),
            BASE_BASELINE_1, null, false,
        )

        // A live storage-appended terminal stage that THIS driver never
        // issued is not completion authority, even when shaped Downloaded.
        // The internal result constructor is coordinator-supplied for this
        // NEGATIVE test only; if the test friend module cannot see it, the
        // coordinator relays a compile-only alternate before execution.
        val store = db.claimPushStore(claimSession(), owner)
        val loop = SnapshotDeliveryLoop(store, pageApi(db, page)) { BASE_NOW_MILLIS }
        store.withRun(BASE_EPOCH) { run ->
            val begun = run.beginSnapshotStage(BASE_STAGE_1, BASE_PAGE_LIMIT)
            val stored: SnapshotStage = run.appendSnapshotPage(begun, page)
            assertTrue(stored.downloaded)
            val fabricated = SnapshotDownloadResult(
                stage = stored,
                committedPages = 1,
                committedRecords = 1,
                stop = SnapshotDownloadStop.Downloaded,
                driver = loop,
            )
            val beforeFabricated = db.claimSnapshot()
            expectBaselineReject("storage-only terminal") { loop.complete(run, fabricated) }
            assertEquals(beforeFabricated, db.claimSnapshot())
            run.abandonSnapshotStage(checkNotNull(run.readSnapshotStage()))
        }

        // An actually issued result superseded by a durable observation also
        // rejects, preserving every durable byte for explicit recovery.
        db.baseWithLoop(owner, pageApi(db, page)) { run, loop ->
            val terminal = checkNotNull(loop.start(run, BASE_STAGE_2, pageLimit = BASE_PAGE_LIMIT).download)
            assertNotNull(run.readSnapshotStage())
            val beforeSuperseded = db.claimSnapshot()
            expectBaselineReject("superseded issuance") { loop.complete(run, terminal) }
            assertEquals(beforeSuperseded, db.claimSnapshot())
        }
        db.baseAbandonObserved(owner)
    }

    @Test
    fun eligibleAttemptConsumesIssuance_concurrentDuplicatesCompleteOnce() = runTest {
        database.stageSeedBaseline(
            cursor = STAGE_SYNC_CURSOR,
            lastSuccessMillis = BASE_LAST_SUCCESS,
            account = CLAIM_ACCOUNT_A,
            cacheEpoch = BASE_EPOCH,
            baselineComplete = false,
        )
        val owner = claimOwner()
        val db = database
        val seedPage = stagePage(
            listOf(stageCustomUpsert(id = BASE_CUSTOM_1, name = "Base Custom", revision = 5L)),
            BASE_BASELINE_1, null, false,
        )
        db.baseDownloadAndComplete(owner, BASE_STAGE_1, listOf(SnapshotScriptResponse.Page(seedPage)))

        // The staged lower revision is eligible-issued but fails validation
        // BEFORE any baseline write; the first eligible attempt already
        // consumes the terminal issuance, so an immediate repeat with the same
        // result cannot be retried into completion.
        val stalePage = stagePage(
            listOf(stageCustomUpsert(id = BASE_CUSTOM_1, name = "Stale Custom", revision = 4L)),
            BASE_BASELINE_2, null, false,
        )
        db.baseWithLoop(owner, pageApi(db, stalePage), 1L) { run, loop ->
            val terminal = checkNotNull(loop.start(run, BASE_STAGE_2, pageLimit = BASE_PAGE_LIMIT).download)
            assertTrue(terminal.stop is SnapshotDownloadStop.Downloaded)
            val beforeStale = db.claimSnapshot()
            expectBaselineReject("stale validation") { loop.complete(run, terminal) }
            assertEquals(beforeStale, db.claimSnapshot())
            expectBaselineReject("retired terminal cannot retry") { loop.complete(run, terminal) }
            assertEquals(beforeStale, db.claimSnapshot())
        }
        assertEquals(5L, db.baseCanonicalRevision(CLAIM_ACCOUNT_A, R05B_TYPE_CUSTOM, BASE_CUSTOM_1))
        assertEquals(1, db.stageRowsFor(CLAIM_ACCOUNT_A).size)
        // Recovery is durable observation plus explicit abandon and NEW-stage restart, never readoption.
        db.baseWithLoop(owner, pageApi(db, stalePage), 1L) { run, _ ->
            assertNotNull(run.readSnapshotStage())
        }
        db.baseAbandonObserved(owner, 1L)
        val freshPage = stagePage(
            listOf(stageCustomUpsert(id = BASE_CUSTOM_1, name = "Base Custom", revision = 6L)),
            BASE_BASELINE_2, null, false,
        )
        val recovered = db.baseDownloadAndComplete(
            owner, BASE_STAGE_3, listOf(SnapshotScriptResponse.Page(freshPage)), epoch = 1L,
        )
        assertBaselineResult(recovered.second, 2L, BASE_BASELINE_2, 1L, 1L, false)
        assertEquals(6L, db.baseCanonicalRevision(CLAIM_ACCOUNT_A, R05B_TYPE_CUSTOM, BASE_CUSTOM_1))

        // Concurrent duplicate attempts on one terminal issuance complete exactly once.
        val dupePage = stagePage(
            listOf(stageCustomUpsert(id = BASE_CUSTOM_2, name = "Second Custom", revision = 2L)),
            BASE_BASELINE_2, null, false,
        )
        db.baseWithLoop(owner, pageApi(db, dupePage), 2L) { run, loop ->
            val terminal = checkNotNull(loop.start(run, BASE_STAGE_4, pageLimit = BASE_PAGE_LIMIT).download)
            var successes = 0
            coroutineScope {
                val first = async { completeCounting(run, loop, terminal) { successes++ } }
                val second = async { completeCounting(run, loop, terminal) { successes++ } }
                first.await()
                second.await()
            }
            assertEquals(1, successes)
        }
        assertEquals(3L, db.r05cReadCache(CLAIM_ACCOUNT_A)!!.cacheEpoch)
        assertTrue(db.stageRowsFor(CLAIM_ACCOUNT_A).isEmpty())
        assertEquals("upsert", db.baseCanonicalState(CLAIM_ACCOUNT_A, R05B_TYPE_CUSTOM, BASE_CUSTOM_2))
    }

    private suspend fun completeCounting(
        run: PushRun,
        loop: SnapshotDeliveryLoop,
        terminal: SnapshotDownloadResult,
        onSuccess: () -> Unit,
    ) {
        try {
            loop.complete(run, terminal)
            onSuccess()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (expected: Exception) {
            // Exactly one concurrent duplicate must lose; the single success
            // plus single epoch advance below carry the assertion weight.
        }
    }

    @Test
    fun sourceSessionEpochCorruption_fencesBeforeEffects_redactionHolds() = runTest {
        database.stageSeedBaseline(
            cursor = STAGE_SYNC_CURSOR,
            lastSuccessMillis = BASE_LAST_SUCCESS,
            account = CLAIM_ACCOUNT_A,
            cacheEpoch = BASE_EPOCH,
            baselineComplete = false,
        )
        val owner = claimOwner()
        val db = database
        val page = stagePage(
            listOf(stageCustomUpsert(id = BASE_CUSTOM_1, name = "Base Custom", revision = 5L)),
            BASE_BASELINE_1, null, false,
        )

        // The original full source observation changed after download: reject
        // and recheck the full tampered state afterwards.
        db.baseWithLoop(owner, pageApi(db, page)) { run, loop ->
            val terminal = checkNotNull(loop.start(run, BASE_STAGE_1, pageLimit = BASE_PAGE_LIMIT).download)
            db.syncDao().upsertSyncState(SyncStateEntity(CLAIM_ACCOUNT_A, "tampered-cursor", BASE_LAST_SUCCESS))
            val beforeTamper = db.claimSnapshot()
            expectBaselineReject("changed source") { loop.complete(run, terminal) }
            assertEquals(beforeTamper, db.claimSnapshot())
        }
        assertEquals("tampered-cursor", db.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.cursor)
        assertEquals(1, db.stageRowsFor(CLAIM_ACCOUNT_A).size)
        db.baseAbandonObserved(owner)
        db.syncDao().upsertSyncState(SyncStateEntity(CLAIM_ACCOUNT_A, STAGE_SYNC_CURSOR, BASE_LAST_SUCCESS))

        // The durable session changes after the actual download: the same
        // issued completion now fences on generation instead of succeeding.
        db.baseWithLoop(owner, pageApi(db, page)) { run, loop ->
            val terminal = checkNotNull(loop.start(run, BASE_STAGE_2, pageLimit = BASE_PAGE_LIMIT).download)
            db.claimSeedDevice(CLAIM_ACCOUNT_A, CLAIM_GENERATION + 1)
            // No-change baseline starts AFTER the deliberate tamper, before complete.
            val beforeSession = db.claimSnapshot()
            expectBaselineReject("wrong session") { loop.complete(run, terminal) }
            assertEquals(beforeSession, db.claimSnapshot())
            db.claimSeedDevice(CLAIM_ACCOUNT_A, CLAIM_GENERATION)
        }
        assertEquals(1, db.stageRowsFor(CLAIM_ACCOUNT_A).size)
        db.baseAbandonObserved(owner)

        // The durable run epoch changes after the actual download: completion
        // fences on epoch instead of succeeding. Unrelated withRun admission
        // with a wrong epoch additionally rejects before any block runs.
        db.baseWithLoop(owner, pageApi(db, page)) { run, loop ->
            val terminal = checkNotNull(loop.start(run, BASE_STAGE_2, pageLimit = BASE_PAGE_LIMIT).download)
            db.claimUpdateCache(CLAIM_ACCOUNT_A, 5L)
            // No-change baseline starts AFTER the deliberate tamper, before complete.
            val beforeEpoch = db.claimSnapshot()
            expectBaselineReject("changed durable epoch") { loop.complete(run, terminal) }
            assertEquals(beforeEpoch, db.claimSnapshot())
            db.claimUpdateCache(CLAIM_ACCOUNT_A, 0L)
        }
        expectBaselineReject("wrong epoch admission") {
            db.baseWithLoop(owner, pageApi(db, page), 999L) { _, _ -> }
        }
        db.baseAbandonObserved(owner)

        // Retained-evidence corruption (forged counters) fails closed with the
        // stage preserved. Corrupt read/abandon paths must also preserve, so
        // the exact fixture-owned evidence is restored before valid abandon;
        // that restore is test-only cleanup, never production recovery.
        db.baseWithLoop(owner, pageApi(db, page)) { run, loop ->
            val terminal = checkNotNull(loop.start(run, BASE_STAGE_3, pageLimit = BASE_PAGE_LIMIT).download)
            val originalCount =
                db.r05cQuery(
                    "SELECT record_count AS n FROM sync_snapshot_stages WHERE stage_id = ?",
                    arrayOf(BASE_STAGE_3),
                ).single()["n"] as Long
            db.claimExec("UPDATE sync_snapshot_stages SET record_count = ? WHERE stage_id = ?", arrayOf(999L, BASE_STAGE_3))
            val beforeCorrupt = db.claimSnapshot()
            // Completion must independently reject retained corruption before
            // recovery observation, preserving all durable bytes.
            expectBaselineReject("corrupt completion") { loop.complete(run, terminal) }
            assertEquals(beforeCorrupt, db.claimSnapshot())
            // Strict policy: a corrupt retained stage cannot even be
            // observed; the read itself must throw, preserving everything.
            expectBaselineReject("corrupt read") { run.readSnapshotStage() }
            assertEquals(beforeCorrupt, db.claimSnapshot())
            // Raw evidence proves the stage is still retained, not cleaned up.
            assertEquals(
                1L,
                db.r05cQuery(
                    "SELECT COUNT(*) AS n FROM sync_snapshot_stages WHERE stage_id = ?",
                    arrayOf(BASE_STAGE_3),
                ).single()["n"],
            )
            // Corrupt abandon is unobtainable by construction: no observation
            // can be read off corrupt bytes, so no abandon capability exists
            // to attempt with. Restore the exact fixture-owned evidence
            // before valid abandon; that restore is test-only cleanup,
            // never production recovery.
            db.claimExec(
                "UPDATE sync_snapshot_stages SET record_count = ? WHERE stage_id = ?",
                arrayOf(originalCount, BASE_STAGE_3),
            )
        }
        db.baseAbandonObserved(owner)

        // Fixed redacted displays carry no identities, tokens, baselines or bodies.
        db.baseWithLoop(owner, pageApi(db, page)) { run, loop ->
            val started = loop.start(run, BASE_STAGE_4, pageLimit = BASE_PAGE_LIMIT)
            assertEquals("SnapshotStartResult", started.toString())
            baseAssertRedacted(started.toString(), "start")
            val terminal = checkNotNull(started.download)
            assertEquals("SnapshotDownloadResult", terminal.toString())
            baseAssertRedacted(terminal.toString(), "download")
            val baseline = loop.complete(run, terminal)
            assertBaselineResult(baseline, 1L, BASE_BASELINE_1, 1L, 1L, false)
        }
    }

    @Test
    fun lateSqlFailure_rollsBackCanonicalAbsenceCheckpointCacheAndStage() = runTest {
        val db = database
        // Checked epoch overflow is a fixed safe storage failure changing nothing,
        // and the consumed terminal issuance cannot be retried into completion.
        db.claimSeedDevice(CLAIM_ACCOUNT_A, CLAIM_GENERATION)
        db.claimSeedCache(CLAIM_ACCOUNT_A, Long.MAX_VALUE, baselineComplete = false)
        db.syncDao().upsertSyncState(SyncStateEntity(CLAIM_ACCOUNT_A, STAGE_SYNC_CURSOR, BASE_LAST_SUCCESS))
        val overflowOwner = claimOwner()
        val overflowPage = stagePage(
            listOf(stageCustomUpsert(id = BASE_CUSTOM_1, name = "Base Custom", revision = 5L)),
            BASE_BASELINE_1, null, false,
        )
        db.baseWithLoop(overflowOwner, pageApi(db, overflowPage), Long.MAX_VALUE) { run, loop ->
            val terminal = checkNotNull(loop.start(run, BASE_STAGE_1, pageLimit = BASE_PAGE_LIMIT).download)
            val beforeOverflow = db.claimSnapshot()
            expectBaselineReject("epoch overflow") { loop.complete(run, terminal) }
            assertEquals(beforeOverflow, db.claimSnapshot())
            expectBaselineReject("retired terminal cannot retry") { loop.complete(run, terminal) }
            assertEquals(beforeOverflow, db.claimSnapshot())
            assertNotNull(run.readSnapshotStage())
        }
        assertEquals(Long.MAX_VALUE, db.r05cReadCache(CLAIM_ACCOUNT_A)!!.cacheEpoch)
        assertNull(db.baseCanonicalState(CLAIM_ACCOUNT_A, R05B_TYPE_CUSTOM, BASE_CUSTOM_1))
        assertEquals(STAGE_SYNC_CURSOR, db.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.cursor)
        // Begin fencing is unaffected: a new stage can still start after explicit abandon.
        db.baseAbandonObserved(overflowOwner, Long.MAX_VALUE)
        db.baseWithLoop(overflowOwner, pageApi(db, overflowPage), Long.MAX_VALUE) { run, loop ->
            val restarted = checkNotNull(loop.start(run, BASE_STAGE_2, pageLimit = BASE_PAGE_LIMIT).download)
            assertTrue(restarted.stop is SnapshotDownloadStop.Downloaded)
        }
        db.baseAbandonObserved(overflowOwner, Long.MAX_VALUE)

        // Late SQL failure AFTER earlier canonical/absence writes: the cache
        // write is blocked so installs, cursor, cache and stage deletion roll
        // back together. The mixed scope explicitly admits account B first.
        db.stageSeedSecondScope(
            cursor = STAGE_SYNC_CURSOR_2,
            lastSuccessMillis = null,
            account = CLAIM_ACCOUNT_B,
            cacheEpoch = BASE_EPOCH,
        )
        db.claimSeedDevice(CLAIM_ACCOUNT_B, CLAIM_GENERATION)
        db.r05cSetCache(CLAIM_ACCOUNT_B, baselineComplete = false)
        // One valid prior OBSERVED missing root: the fault download carries
        // only upserts, so completion also performs a snapshot_absent write.
        // Seeded through the vetted canonical contract harness, never a snapshot.
        db.r05cApplyCanonical(
            r05bPull(
                batchId = R05B_BATCH_1,
                cursor = R05B_CURSOR_1,
                changes = r05bJsonArray(
                    r05bChange(
                        R05B_TYPE_ROUTINE, BASE_ROUTINE_1, R05B_OP_UPSERT, 3L,
                        r05bRoutineRecord(id = BASE_ROUTINE_1, name = "Base Routine", revision = 3L),
                    ),
                ),
            ),
            BASE_EPOCH,
            account = CLAIM_ACCOUNT_B,
        )
        val bSession = claimSession(account = CLAIM_ACCOUNT_B)
        val sqlPage = stagePage(
            listOf(
                stageWorkoutUpsert(id = BASE_WORKOUT_1, name = "Base Workout", revision = 7L),
                stageCustomUpsert(id = BASE_CUSTOM_2, name = "Second Custom", revision = 2L),
            ),
            BASE_BASELINE_2, null, false,
        )
        db.baseWithLoop(overflowOwner, pageApi(db, sqlPage), BASE_EPOCH, bSession) { run, loop ->
            val terminal = checkNotNull(loop.start(run, BASE_STAGE_3, pageLimit = BASE_PAGE_LIMIT).download)
            db.claimExec(
                "CREATE TRIGGER base_block_cache BEFORE UPDATE ON sync_account_cache " +
                    "BEGIN SELECT RAISE(ABORT, 'base_blocked'); END",
            )
            try {
                val beforeSql = db.claimSnapshot()
                expectBaselineReject("late SQL failure") { loop.complete(run, terminal) }
                assertEquals(beforeSql, db.claimSnapshot())
                expectBaselineReject("retired terminal cannot retry") { loop.complete(run, terminal) }
                assertEquals(beforeSql, db.claimSnapshot())
            } finally {
                db.claimExec("DROP TRIGGER base_block_cache")
            }
            assertNotNull(run.readSnapshotStage())
        }
        // Earlier upsert writes rolled back with the blocked cache write,
        // and the performed snapshot_absent write rolled back with them.
        assertNull(db.baseCanonicalState(CLAIM_ACCOUNT_B, R05B_TYPE_WORKOUT, BASE_WORKOUT_1))
        assertNull(db.baseCanonicalState(CLAIM_ACCOUNT_B, R05B_TYPE_CUSTOM, BASE_CUSTOM_2))
        assertEquals("upsert", db.baseCanonicalState(CLAIM_ACCOUNT_B, R05B_TYPE_ROUTINE, BASE_ROUTINE_1))
        assertEquals(3L, db.baseCanonicalRevision(CLAIM_ACCOUNT_B, R05B_TYPE_ROUTINE, BASE_ROUTINE_1))
        assertEquals(false, db.r05cReadCache(CLAIM_ACCOUNT_B)!!.baselineComplete)
        assertEquals(BASE_EPOCH, db.r05cReadCache(CLAIM_ACCOUNT_B)!!.cacheEpoch)
        assertEquals(STAGE_SYNC_CURSOR_2, db.syncDao().getSyncState(CLAIM_ACCOUNT_B)?.cursor)
        // Explicit abandon plus NEW-stage restart recovers after the fault clears.
        db.baseAbandonObserved(overflowOwner, BASE_EPOCH, bSession)
        val recovered = db.baseWithLoop(overflowOwner, pageApi(db, sqlPage), BASE_EPOCH, bSession) { run, loop ->
            val terminal = checkNotNull(loop.start(run, BASE_STAGE_4, pageLimit = BASE_PAGE_LIMIT).download)
            loop.complete(run, terminal)
        }
        assertBaselineResult(recovered, 1L, BASE_BASELINE_2, 1L, 2L, false)
        assertEquals("snapshot_absent", db.baseCanonicalState(CLAIM_ACCOUNT_B, R05B_TYPE_ROUTINE, BASE_ROUTINE_1))
        assertEquals(3L, db.baseCanonicalRevision(CLAIM_ACCOUNT_B, R05B_TYPE_ROUTINE, BASE_ROUTINE_1))
        db.claimSeedDevice(CLAIM_ACCOUNT_A, CLAIM_GENERATION)
    }

    @Test
    fun postwriteCancellation_rollsBackAndRetiresResult_freshRecovery() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inCommit = AtomicBoolean(false)
        val installSeen = AtomicBoolean(false)
        val absenceSeen = AtomicBoolean(false)
        val fired = AtomicBoolean(false)
        val triggerCarriesAbsence = AtomicBoolean(true)
        lateinit var runnerJob: Job
        // Test-only real Room query hook: cancel ONLY on a query whose
        // callback entry already finds both branches established by PRIOR
        // statements. Query callbacks run BEFORE SQL, so cancelling inside
        // the absence statement's own callback could never prove that
        // statement executed; the following query is the deterministic proof
        // that both the install and the snapshot_absent writes ran before
        // cancellation, per testing-strategy.
        val callbackDb = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
            .setQueryCallback(
                RoomDatabase.QueryCallback { sql, bindArgs ->
                    if (inCommit.get()) {
                        if (installSeen.get() && absenceSeen.get()) {
                            // Following query after both branches: cancel here.
                            if (fired.compareAndSet(false, true)) {
                                triggerCarriesAbsence.set(bindArgs.any { it == "snapshot_absent" })
                                inCommit.set(false)
                                runnerJob.cancel(CancellationException("base-test postwrite cancel"))
                            }
                        } else {
                            val lower = sql.lowercase()
                            val isWrite = lower.contains("insert") || lower.contains("update") ||
                                lower.contains("delete")
                            if (isWrite && lower.contains("sync_canonical_roots")) {
                                if (lower.contains("insert")) {
                                    installSeen.set(true)
                                }
                                if (lower.contains("update") && bindArgs.any { it == "snapshot_absent" }) {
                                    absenceSeen.set(true)
                                }
                            }
                        }
                    }
                },
                Executor { command -> command.run() },
            )
            .build()
        try {
            callbackDb.stageSeedBaseline(
                cursor = STAGE_SYNC_CURSOR,
                lastSuccessMillis = BASE_LAST_SUCCESS,
                account = CLAIM_ACCOUNT_A,
                cacheEpoch = BASE_EPOCH,
                baselineComplete = false,
            )
            // One valid prior OBSERVED missing root: the fault download
            // carries upserts, so completion performs both install writes and
            // a snapshot_absent write inside the canceled transaction.
            callbackDb.r05cApplyCanonical(
                r05bPull(
                    batchId = R05B_BATCH_1,
                    cursor = R05B_CURSOR_1,
                    changes = r05bJsonArray(
                        r05bChange(
                            R05B_TYPE_ROUTINE, BASE_ROUTINE_1, R05B_OP_UPSERT, 3L,
                            r05bRoutineRecord(id = BASE_ROUTINE_1, name = "Base Routine", revision = 3L),
                        ),
                    ),
                ),
                BASE_EPOCH,
            )
            val owner = claimOwner()
            val page = stagePage(
                listOf(
                    stageWorkoutUpsert(id = BASE_WORKOUT_1, name = "Base Workout", revision = 7L),
                    stageCustomUpsert(id = BASE_CUSTOM_1, name = "Base Custom", revision = 5L),
                ),
                BASE_BASELINE_1, null, false,
            )
            val store = callbackDb.claimPushStore(claimSession(), owner)
            val api = SnapshotDeliveryScriptApi(
                callbackDb, mutableListOf(), mutableListOf(SnapshotScriptResponse.Page(page)),
            )
            val loop = SnapshotDeliveryLoop(store, api) { BASE_NOW_MILLIS }
            store.withRun(BASE_EPOCH) { run ->
                val terminal = checkNotNull(loop.start(run, BASE_STAGE_1, pageLimit = BASE_PAGE_LIMIT).download)
                assertTrue(terminal.stop is SnapshotDownloadStop.Downloaded)
                // Oracle taken after admission, not before it.
                val admitted = callbackDb.claimSnapshot()
                // The commit runs in a child inheriting the live run's
                // coroutine context; the query hook cancels that child after
                // the first observed write while the parent run stays live to
                // prove rollback, retirement and fresh recovery.
                coroutineScope {
                    val child = async {
                        loop.complete(run, terminal)
                    }
                    runnerJob = child
                    inCommit.set(true)
                    try {
                        child.await()
                        fail("Mid-write cancellation must propagate, never a commit")
                    } catch (_: CancellationException) {
                    } finally {
                        inCommit.set(false)
                    }
                }
                assertTrue("postwrite hook must fire", fired.get())
                // The cancel fired on a following query whose own bind args
                // carry no absence mark: both branches executed beforehand.
                assertTrue("cancel must fire after both branches", !triggerCarriesAbsence.get())
                // Both branches executed before the cancel: installs and the
                // snapshot_absent write all roll back together.
                assertTrue("install branch must execute before cancel", installSeen.get())
                assertTrue("absence branch must execute before cancel", absenceSeen.get())
                assertEquals(admitted, callbackDb.claimSnapshot())
                assertNull(callbackDb.baseCanonicalState(CLAIM_ACCOUNT_A, R05B_TYPE_WORKOUT, BASE_WORKOUT_1))
                assertNull(callbackDb.baseCanonicalState(CLAIM_ACCOUNT_A, R05B_TYPE_CUSTOM, BASE_CUSTOM_1))
                assertEquals("upsert", callbackDb.baseCanonicalState(CLAIM_ACCOUNT_A, R05B_TYPE_ROUTINE, BASE_ROUTINE_1))
                assertEquals(3L, callbackDb.baseCanonicalRevision(CLAIM_ACCOUNT_A, R05B_TYPE_ROUTINE, BASE_ROUTINE_1))
                assertEquals(false, callbackDb.r05cReadCache(CLAIM_ACCOUNT_A)!!.baselineComplete)
                assertEquals(STAGE_SYNC_CURSOR, callbackDb.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.cursor)
                assertEquals(1, callbackDb.stageRowsFor(CLAIM_ACCOUNT_A).size)
                // The canceled attempt retires its result in the live run...
                expectBaselineReject("cancel-retired terminal") { loop.complete(run, terminal) }
                // ...while explicit abandon plus NEW-stage restart recovers.
                run.abandonSnapshotStage(checkNotNull(run.readSnapshotStage()))
                val retryApi = SnapshotDeliveryScriptApi(
                    callbackDb, mutableListOf(), mutableListOf(SnapshotScriptResponse.Page(page)),
                )
                val retryLoop = SnapshotDeliveryLoop(store, retryApi) { BASE_NOW_MILLIS }
                val retryTerminal =
                    checkNotNull(retryLoop.start(run, BASE_STAGE_2, pageLimit = BASE_PAGE_LIMIT).download)
                val recovered = retryLoop.complete(run, retryTerminal)
                assertBaselineResult(recovered, 1L, BASE_BASELINE_1, 1L, 2L, false)
                assertEquals("snapshot_absent", callbackDb.baseCanonicalState(CLAIM_ACCOUNT_A, R05B_TYPE_ROUTINE, BASE_ROUTINE_1))
                assertEquals(3L, callbackDb.baseCanonicalRevision(CLAIM_ACCOUNT_A, R05B_TYPE_ROUTINE, BASE_ROUTINE_1))
            }
        } finally {
            callbackDb.close()
        }
    }

    @Test
    fun committedButResumptionCanceled_durableBaselineStands_freshRunPublishes() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val queryExecutor = Executors.newSingleThreadExecutor()
        val backingTx = Executors.newSingleThreadExecutor()
        val armed = AtomicBoolean(false)
        val captured = CompletableDeferred<Runnable>()
        val txEnded = CompletableDeferred<Unit>()
        val queue = QueueDispatcher()
        // Vetted transaction-executor capture: the decorator runs normally
        // until armed, then captures the next transaction Runnable without
        // running it. A separate query executor keeps reads off the barrier.
        // The queued caller dispatcher holds ONLY the complete-call caller
        // continuation; Room still executes the transaction on its own
        // separate executor, so no production behavior changes.
        val hookDb = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java)
            .setQueryExecutor(queryExecutor)
            .setTransactionExecutor(
                Executor { command ->
                    if (armed.getAndSet(false)) {
                        captured.complete(command)
                    } else {
                        backingTx.execute(command)
                    }
                },
            )
            .build()
        try {
            hookDb.stageSeedBaseline(
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
            val store = hookDb.claimPushStore(claimSession(), owner)
            val api = SnapshotDeliveryScriptApi(
                hookDb, mutableListOf(), mutableListOf(SnapshotScriptResponse.Page(page)),
            )
            val loop = SnapshotDeliveryLoop(store, api) { BASE_NOW_MILLIS }
            var observedEpoch = -1L
            store.withRun(BASE_EPOCH) { run ->
                val terminal = checkNotNull(loop.start(run, BASE_STAGE_1, pageLimit = BASE_PAGE_LIMIT).download)
                assertTrue(terminal.stop is SnapshotDownloadStop.Downloaded)
                armed.set(true)
                var delivered: SnapshotBaselineResult? = null
                var cancelObserved: CancellationException? = null
                coroutineScope {
                    val child = async(queue, start = CoroutineStart.UNDISPATCHED) {
                        delivered = loop.complete(run, terminal)
                    }
                    // The captured transaction proves complete ran to
                    // admission and is now suspended on the queued caller
                    // continuation; release it on the owned executor and
                    // signal only when it physically RETURNS.
                    val runnable = captured.await()
                    backingTx.execute {
                        runnable.run()
                        txEnded.complete(Unit)
                    }
                    txEnded.await()
                    // Inspect durable state WITHOUT draining the queued
                    // caller continuation: the commit stands on its own.
                    val cacheAfter = hookDb.r05cReadCache(CLAIM_ACCOUNT_A)!!
                    assertTrue(cacheAfter.baselineComplete)
                    assertEquals(1L, cacheAfter.cacheEpoch)
                    assertTrue(cacheAfter.projectionDirty)
                    assertEquals(BASE_BASELINE_1, hookDb.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.cursor)
                    assertEquals("upsert", hookDb.baseCanonicalState(CLAIM_ACCOUNT_A, R05B_TYPE_CUSTOM, BASE_CUSTOM_1))
                    assertTrue(hookDb.stageRowsFor(CLAIM_ACCOUNT_A).isEmpty())
                    observedEpoch = cacheAfter.cacheEpoch
                    // Now cancel the resumption and drain: no baseline result
                    // may be delivered to the canceled caller.
                    child.cancel(CancellationException("base-test resumption cancel"))
                    queue.drain()
                    try {
                        child.await()
                        fail("Canceled resumption must throw, never deliver a commit")
                    } catch (cancelled: CancellationException) {
                        cancelObserved = cancelled
                    }
                }
                assertNull(delivered)
                assertNotNull(cancelObserved)
            }
            assertEquals(1L, observedEpoch)
            // The old epoch can no longer admit: only the observed durable
            // epoch is success evidence, never the old result or capability.
            expectBaselineReject("old epoch after commit") {
                store.withRun(BASE_EPOCH) { fail("stale epoch must not admit") }
            }
            // A fresh admitted run at the observed durable epoch publishes
            // with an empty scripted HTTP list: no network proves recovery.
            val pubApi = SnapshotDeliveryScriptApi(hookDb, mutableListOf(), mutableListOf())
            assertTrue(pubApi.snapshotCalls.isEmpty())
            val pubStore = hookDb.claimPushStore(claimSession(), owner)
            pubStore.withRun(observedEpoch) { run ->
                val lease = R05cTestLease()
                val publication = run.publishProjection(hookDb.r05cStore(barrier = r05cGrantBarrier(lease)))
                assertEquals(ProjectionPublicationStatus.PUBLISHED, publication.status)
                assertEquals(2L, publication.cacheEpoch)
                assertEquals(1, lease.closeCount)
            }
            assertTrue(pubApi.snapshotCalls.isEmpty())
        } finally {
            hookDb.close()
            queryExecutor.shutdown()
            backingTx.shutdown()
        }
    }

    private class QueueDispatcher : CoroutineDispatcher() {
        private val lock = Any()
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(lock) { queue.add(block) }
        }
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = true
        fun drain() {
            while (true) {
                val next = synchronized(lock) { queue.removeFirstOrNull() } ?: return
                next.run()
            }
        }
    }

    @Test
    fun selectedStageBlocksDirectPublication_foreignStageDoesNot() = runTest {
        val db = database
        db.stageSeedBaseline(
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
        // A selected downloaded stage blocks direct publication before any write or epoch advance.
        db.baseWithLoop(owner, pageApi(db, page)) { run, loop ->
            checkNotNull(loop.start(run, BASE_STAGE_1, pageLimit = BASE_PAGE_LIMIT).download)
        }
        val beforeBlocked = db.claimSnapshot()
        val blockedLease = R05cTestLease()
        expectBaselineReject("downloaded stage blocks publication") {
            db.r05cStore(barrier = r05cGrantBarrier(blockedLease)).publish(BASE_EPOCH)
        }
        assertEquals(1, blockedLease.closeCount)
        assertEquals(beforeBlocked, db.claimSnapshot())

        // A corrupt selected stage also blocks direct publication. The exact
        // fixture-owned counter is restored afterwards for valid abandon;
        // that restore is test-only cleanup, never production recovery.
        val originalCount =
            db.r05cQuery(
                "SELECT record_count AS n FROM sync_snapshot_stages WHERE stage_id = ?",
                arrayOf(BASE_STAGE_1),
            ).single()["n"] as Long
        db.claimExec("UPDATE sync_snapshot_stages SET record_count = ? WHERE stage_id = ?", arrayOf(999L, BASE_STAGE_1))
        val beforeCorrupt = db.claimSnapshot()
        val corruptLease = R05cTestLease()
        expectBaselineReject("corrupt stage blocks publication") {
            db.r05cStore(barrier = r05cGrantBarrier(corruptLease)).publish(BASE_EPOCH)
        }
        assertEquals(beforeCorrupt, db.claimSnapshot())
        db.claimExec(
            "UPDATE sync_snapshot_stages SET record_count = ? WHERE stage_id = ?",
            arrayOf(originalCount, BASE_STAGE_1),
        )
        db.baseAbandonObserved(owner)

        // A foreign stage never blocks selected publication. The selected
        // account reuses its existing seeded cache row (no duplicate insert)
        // plus canonical application through the vetted contract harness.
        db.stageInsertInitialHeader(BASE_STAGE_2, BASE_PAGE_LIMIT, account = CLAIM_ACCOUNT_B)
        db.r05cSetCache(CLAIM_ACCOUNT_A, baselineComplete = true)
        db.r05cApplyCanonical(
            r05bPull(
                batchId = R05B_BATCH_1,
                cursor = R05B_CURSOR_1,
                changes = r05bJsonArray(
                    r05bChange(
                        R05B_TYPE_CUSTOM, R05B_CUSTOM_A, R05B_OP_UPSERT, 1L,
                        r05bCustomRecord(id = R05B_CUSTOM_A, name = "Foreign-proof"),
                    ),
                ),
            ),
            BASE_EPOCH,
        )
        val foreignLease = R05cTestLease()
        val published = db.r05cStore(barrier = r05cGrantBarrier(foreignLease)).publish(BASE_EPOCH)
        assertEquals(ProjectionPublicationStatus.PUBLISHED, published.status)
        assertEquals(1, foreignLease.closeCount)
        assertEquals(1, db.stageRowsFor(CLAIM_ACCOUNT_B).size)
    }

    @Test
    fun publishProjection_pairingRejectsBeforeBarrier_leaseClosesOnce() = runTest {
        val db = database
        db.stageSeedBaseline(
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
        val baseline = db.baseDownloadAndComplete(owner, BASE_STAGE_1, listOf(SnapshotScriptResponse.Page(page)))
        assertBaselineResult(baseline.second, 1L, BASE_BASELINE_1, 1L, 1L, false)

        // A revoked lease fails without durable effects and still closes exactly once.
        val revoked = R05cTestLease()
        revoked.revoke()
        val beforeRevoked = db.claimSnapshot()
        db.baseWithLoop(owner, pageApi(db, page), 1L) { run, _ ->
            expectBaselineReject("revoked lease") {
                run.publishProjection(db.r05cStore(barrier = r05cGrantBarrier(revoked)))
            }
        }
        assertEquals(1, revoked.closeCount)
        assertEquals(beforeRevoked, db.claimSnapshot())

        // Acquisition-race capture protects the whole projection; nothing is partially published.
        db.claimSeedWorkout(BASE_WORKOUT_2, CLAIM_ACCOUNT_A, "Old visible")
        val raceLease = R05cTestLease()
        val raceApi = SnapshotDeliveryScriptApi(db, mutableListOf(), mutableListOf())
        val workoutsBefore = db.r05cDump("workouts").size
        val protected = db.baseWithLoop(owner, raceApi, 1L) { run, _ ->
            val barrier = r05cGrantBarrier(raceLease) {
                db.claimSeedWorkout(BASE_LOCAL_NEW, CLAIM_ACCOUNT_A, "Race capture")
                val captureStore = db.claimCaptureStore(claimSession(), mutableListOf(BASE_MUTATION_1))
                db.claimCaptureStart(captureStore, CLAIM_ACCOUNT_A, BASE_LOCAL_NEW)
            }
            run.publishProjection(db.r05cStore(barrier = barrier))
        }
        assertEquals(ProjectionPublicationStatus.PROTECTED, protected.status)
        assertEquals(1, raceLease.closeCount)
        assertEquals(workoutsBefore + 1, db.r05cDump("workouts").size)
        assertEquals(1, db.r05cQuery("SELECT * FROM workouts WHERE id = ?", arrayOf(BASE_WORKOUT_2)).size)
        assertEquals(1, db.r05cQuery("SELECT * FROM workouts WHERE id = ?", arrayOf(BASE_LOCAL_NEW)).size)
        assertEquals(1, db.syncDao().getOutboxForAccount(CLAIM_ACCOUNT_A).size)
        assertTrue(db.r05cReadCache(CLAIM_ACCOUNT_A)!!.projectionDirty)
    }

    @Test
    fun publishProjection_wrongPairing_rejectsBeforeLeaseAcquisition() = runTest {
        val db = database
        db.stageSeedBaseline(
            cursor = STAGE_SYNC_CURSOR,
            lastSuccessMillis = BASE_LAST_SUCCESS,
            account = CLAIM_ACCOUNT_A,
            cacheEpoch = BASE_EPOCH,
            baselineComplete = false,
        )
        val owner = claimOwner()
        val api = SnapshotDeliveryScriptApi(db, mutableListOf(), mutableListOf())
        val beforePairing = db.claimSnapshot()

        // A publisher bound to another physical database never reaches the barrier.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val otherDatabase = Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java).build()
        try {
            val recording = R05cRecordingBarrier(R05cTestLease())
            db.baseWithLoop(owner, api) { run, _ ->
                expectBaselineReject("wrong physical database") {
                    run.publishProjection(ProjectionStore(otherDatabase, claimSession(), recording))
                }
            }
            assertTrue(recording.requests.isEmpty())
        } finally {
            otherDatabase.close()
        }

        // A publisher bound to another session never reaches the barrier either.
        val sessionRecording = R05cRecordingBarrier(R05cTestLease())
        db.baseWithLoop(owner, api) { run, _ ->
            expectBaselineReject("wrong session") {
                run.publishProjection(ProjectionStore(db, claimSession(account = CLAIM_ACCOUNT_B), sessionRecording))
            }
        }
        assertTrue(sessionRecording.requests.isEmpty())
        assertEquals(beforePairing, db.claimSnapshot())
    }

    @Test
    fun failedPublishProjection_retiresOldPullToken_freshReadRecovers() = runTest {
        val db = database
        db.stageSeedBaseline(
            cursor = STAGE_SYNC_CURSOR,
            lastSuccessMillis = BASE_LAST_SUCCESS,
            account = CLAIM_ACCOUNT_A,
            cacheEpoch = BASE_EPOCH,
            baselineComplete = false,
        )
        // Colliding local row: baseline commits, publication must fail.
        db.claimSeedWorkout(BASE_WORKOUT_1, null, "Local same id")
        val owner = claimOwner()
        val page = stagePage(
            listOf(
                stageWorkoutUpsert(id = BASE_WORKOUT_1, name = "Base Workout", revision = 7L),
                stageCustomUpsert(id = BASE_CUSTOM_1, name = "Base Custom", revision = 5L),
            ),
            BASE_BASELINE_1, null, false,
        )
        db.baseWithLoop(owner, pageApi(db, page)) { run, loop ->
            val terminal = checkNotNull(loop.start(run, BASE_STAGE_1, pageLimit = BASE_PAGE_LIMIT).download)
            val baseline = loop.complete(run, terminal)
            assertBaselineResult(baseline, 1L, BASE_BASELINE_1, 1L, 2L, false)

            // Control: a pull token read on the admitted epoch checks cleanly before the attempt.
            val control = checkNotNull(run.readPullCheckpoint()) { "baseline plus cursor must admit a checkpoint" }
            run.checkPullCheckpoint(control)

            val lease = R05cTestLease()
            expectBaselineReject("colliding publication") {
                run.publishProjection(db.r05cStore(barrier = r05cGrantBarrier(lease)))
            }
            assertEquals(1, lease.closeCount)

            // The old pull token is retired by the attempt even though durable
            // epoch/checkpoint state never moved; a fresh read recovers.
            expectBaselineReject("retired pull token") { run.checkPullCheckpoint(control) }
            assertNotNull(run.readPullCheckpoint())
        }
        assertEquals(1L, db.r05cReadCache(CLAIM_ACCOUNT_A)!!.cacheEpoch)
        assertEquals(BASE_BASELINE_1, db.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.cursor)
        assertTrue(db.r05cReadCache(CLAIM_ACCOUNT_A)!!.projectionDirty)
        assertEquals("upsert", db.baseCanonicalState(CLAIM_ACCOUNT_A, R05B_TYPE_WORKOUT, BASE_WORKOUT_1))
    }

    private fun pageApi(db: GymDatabase, vararg pages: SnapshotResultModel) =
        SnapshotDeliveryScriptApi(db, mutableListOf(), pages.map { SnapshotScriptResponse.Page(it) }.toMutableList())
}
