package com.dragosfotache.gymtracka.data.sync.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.sync.engine.PushDeliveryStop
import com.dragosfotache.gymtracka.data.sync.engine.SnapshotDownloadStop
import com.dragosfotache.gymtracka.data.sync.store.PushBlockReason
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * retained-stage and durable-baseline recovery.
 */
@RunWith(AndroidJUnit4::class)
class PreparedSessionEngineRecoveryTest {

    private val harness = PreparedEngineHarness("recover")
    private val F = PreparedEngineFixtures

    @After
    fun cleanOnlyOwnTestArtifacts() {
        harness.clean()
    }

    @Test
    fun budgetExhaustionRetainsNonterminalStageWithDurablePages() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            // Two-page account but an invocation budget of one: the latest
            // snapshot stop is LimitReached and the nonterminal stage stays.
            val transport = PreparedEngineTransport()
            transport.queueOk(F.snapshotPage1())
            transport.queueOk(F.snapshotPage2())
            transport.queueOk(F.pullRevision2Terminal())
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())

            val limited = engine.bootstrap(stageId = F.STAGE_RETAINED, maxSnapshotPages = 1)
            assertFalse(limited.retainedStage)
            val snapshot = checkNotNull(limited.snapshot)
            assertNull(limited.baseline)
            assertNull(limited.delivery)
            val download = checkNotNull(snapshot.download)
            assertEquals(1, download.committedPages)
            assertEquals(1, download.committedRecords)
            assertTrue(download.stop is SnapshotDownloadStop.LimitReached)
            // Only the first page was ever requested; no terminal/pull HTTP.
            assertEquals(1, transport.capturedRequests.size)
            F.assertSnapshotInitial(transport.capturedRequests[0])

            // Durable header plus the committed first-page child survive.
            val header = harness.stageHeader(database, F.STAGE_RETAINED)
            assertNotNull(header)
            assertEquals(F.ACCOUNT, header!!.accountId)
            assertEquals(1L, header.pageCount)
            assertEquals(1L, header.recordCount)
            assertEquals(1L, harness.stageRecordCount(database, F.STAGE_RETAINED))
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun corruptRetainedStageFailsClosedWithoutBypassOrCleanup() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            // Corrupt retained stage: garbage phase, lying counts, foreign token.
            // Seeded through the vetted Room authority column contract, never production code.
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO sync_snapshot_stages (stage_id, account_id, session_generation, cache_epoch," +
                    " page_limit, source_state_present, source_cursor, source_last_success_millis," +
                    " source_baseline_complete, baseline_cursor, next_page_token, phase," +
                    " page_count, record_count)" +
                    " VALUES (?, ?, 999, 0, 200, 0, NULL, NULL, 0, NULL, 'bootstrap-foreign-token', 'bootstrap-garbage', 3, 7)",
                arrayOf<Any?>(F.STAGE_CORRUPT, F.ACCOUNT),
            )
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO sync_snapshot_records (stage_id, aggregate_type, aggregate_id," +
                    " server_revision, canonical_json, page_number, record_position)" +
                    " VALUES (?, 'workout', 'bootstrap-garbage-root', 1, '{\"id\":\"garbage\"}', 0, 0)",
                arrayOf<Any?>(F.STAGE_CORRUPT),
            )
            val corruptBefore = harness.stageFullRow(database, F.STAGE_CORRUPT)
            assertNotNull(corruptBefore)
            assertEquals(1L, harness.stageRecordCount(database, F.STAGE_CORRUPT))

            // The spec forbids bypass and cleanup around a corrupt row: the
            // call fails AUTHORITY_UNAVAILABLE with ZERO HTTP and the corrupt
            // header/children unchanged.
            val transport = PreparedEngineTransport()
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            try {
                engine.bootstrap(stageId = F.STAGE_NEW)
                fail("expected corrupt stage to fail closed")
            } catch (e: PreparedEngineException) {
                assertEquals(PreparedEngineFailure.AUTHORITY_UNAVAILABLE, e.failure)
                F.assertClosed(e, listOf(F.ACCOUNT, F.OP, F.ACCESS, F.REFRESH))
            }
            assertTrue(transport.capturedRequests.isEmpty())
            assertEquals(corruptBefore, harness.stageFullRow(database, F.STAGE_CORRUPT))
            assertEquals(1L, harness.stageRecordCount(database, F.STAGE_CORRUPT))

            // Explicit restart cannot delete the corruption either.
            try {
                engine.bootstrap(stageId = F.STAGE_FRESH, restartRetainedStage = true)
                fail("expected corrupt stage restart to fail closed")
            } catch (e: PreparedEngineException) {
                assertEquals(PreparedEngineFailure.AUTHORITY_UNAVAILABLE, e.failure)
                F.assertClosed(e, listOf(F.ACCOUNT, F.OP, F.STAGE_CORRUPT, F.STAGE_FRESH))
            }
            assertTrue(transport.capturedRequests.isEmpty())
            assertEquals(corruptBefore, harness.stageFullRow(database, F.STAGE_CORRUPT))
            assertEquals(1L, harness.stageRecordCount(database, F.STAGE_CORRUPT))
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun retainedStagePreservesRejectedFrozenOutboxAndNewerIntent() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val owner = F.owner()
            // Establish the retained nonterminal stage with an EMPTY outbox
            // first: a rejected head would correctly block snapshot START,
            // so no retained stage could exist yet.
            val firstTransport = PreparedEngineTransport()
            firstTransport.queueOk(F.snapshotPage1())
            firstTransport.queueOk(F.snapshotPage2())
            val first = F.engine(database, vault, PreparedEngineAuthApi(), firstTransport, owner)
            val limited = first.bootstrap(stageId = F.STAGE_RETAINED, maxSnapshotPages = 1)
            assertNotNull(limited.snapshot)
            first.invalidate()

            // THEN seed the rejected head plus a frozen sibling and a newer
            // pending intent: retained policy must neither bypass, rekey nor
            // drop any row.
            harness.seedOutboxRow(
                database,
                sequence = 10L,
                mutationId = F.OUTBOX_REJECTED_MUT,
                aggregateId = F.OUTBOX_WORKOUT,
                mutationType = "workout.start",
                localGeneration = 4L,
                state = "rejected",
                errorCode = "gone",
            )
            harness.seedOutboxRow(
                database,
                sequence = 11L,
                mutationId = F.OUTBOX_NEWER_MUT,
                aggregateId = F.OUTBOX_WORKOUT,
                mutationType = "workout.upsert",
                localGeneration = 5L,
                state = "pending",
            )
            // A frozen sibling is preserved exactly as well.
            harness.seedOutboxRow(
                database,
                sequence = 12L,
                mutationId = F.OUTBOX_FROZEN_MUT,
                aggregateId = F.OUTBOX_WORKOUT,
                mutationType = "workout.upsert",
                localGeneration = 6L,
                state = "frozen",
            )
            val outboxBefore = harness.outboxSnapshot(database, F.ACCOUNT)
            assertEquals(3, outboxBefore.size)

            // Retained stage refuses HTTP: the outbox is untouched, the newer
            // intent still pending at its exact generation.
            val retainedTransport = PreparedEngineTransport()
            val second = F.engine(database, vault, PreparedEngineAuthApi(), retainedTransport, owner)
            val retained = second.bootstrap(stageId = F.STAGE_OTHER)
            assertTrue(retained.retainedStage)
            assertTrue(retainedTransport.capturedRequests.isEmpty())
            assertEquals(outboxBefore, harness.outboxSnapshot(database, F.ACCOUNT))
            second.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun retainedStageReturnsWithoutHttpAndRestartsOnlyWithNewUuid() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val owner = F.owner()
            val firstTransport = PreparedEngineTransport()
            firstTransport.queueOk(F.snapshotPage1())
            firstTransport.queueOk(F.snapshotPage2())
            val first = F.engine(database, vault, PreparedEngineAuthApi(), firstTransport, owner)
            val limited = first.bootstrap(stageId = F.STAGE_RETAINED, maxSnapshotPages = 1)
            assertNotNull(limited.snapshot)
            first.invalidate()

            // A selected stage refuses HTTP unless explicitly restarted.
            val retainedTransport = PreparedEngineTransport()
            val second = F.engine(database, vault, PreparedEngineAuthApi(), retainedTransport, owner)
            val retained = second.bootstrap(stageId = F.STAGE_OTHER)
            assertTrue(retained.retainedStage)
            assertNull(retained.snapshot)
            assertNull(retained.baseline)
            assertNull(retained.delivery)
            assertTrue(retainedTransport.capturedRequests.isEmpty())
            second.invalidate()

            // Explicit restart with the EQUAL retained UUID rejects; stage preserved.
            val equalTransport = PreparedEngineTransport()
            val third = F.engine(database, vault, PreparedEngineAuthApi(), equalTransport, owner)
            try {
                third.bootstrap(stageId = F.STAGE_RETAINED, restartRetainedStage = true)
                fail("expected equal-UUID restart to reject")
            } catch (e: PreparedEngineException) {
                assertEquals(PreparedEngineFailure.INVALID_INPUT, e.failure)
                F.assertClosed(e, listOf(F.STAGE_RETAINED, F.ACCOUNT, F.OP))
            }
            assertTrue(equalTransport.capturedRequests.isEmpty())
            assertNotNull(harness.stageHeader(database, F.STAGE_RETAINED))
            assertEquals(1L, harness.stageRecordCount(database, F.STAGE_RETAINED))
            third.invalidate()

            // A NEW UUID abandons exactly that stage and completes fresh.
            val restartTransport = PreparedEngineTransport()
            restartTransport.queueOk(F.snapshotPage1())
            restartTransport.queueOk(F.snapshotPage2())
            restartTransport.queueOk(F.pullRevision2Terminal())
            val fourth = F.engine(database, vault, PreparedEngineAuthApi(), restartTransport, owner)
            val restarted = fourth.bootstrap(stageId = F.STAGE_NEW, restartRetainedStage = true)
            assertFalse(restarted.retainedStage)
            val baseline = checkNotNull(restarted.baseline)
            assertEquals(2L, baseline.pageCount)
            assertEquals(2L, baseline.recordCount)
            assertNull(harness.stageHeader(database, F.STAGE_RETAINED))
            assertEquals(0L, harness.stageRecordCount(database, F.STAGE_RETAINED))
            fourth.invalidate()

            val device = harness.deviceRow(database)
            assertNull(device[0])
            assertEquals(F.ACCOUNT, device[1])
            assertEquals("preparing", device[3])
            assertEquals(VaultSessionPhase.PREPARED, vault.load().record?.phase)
        } finally {
            database.close()
        }
    }

    @Test
    fun rejectedHeadWithoutStageReturnsPushOnlyBlocked() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            // An ALREADY-rejected head blocks before wrapper parsing: zero HTTP.
            harness.seedOutboxRow(
                database,
                sequence = 10L,
                mutationId = F.OUTBOX_REJECTED_MUT,
                aggregateId = F.OUTBOX_WORKOUT,
                mutationType = "workout.start",
                localGeneration = 4L,
                state = "rejected",
                errorCode = "gone",
            )
            val transport = PreparedEngineTransport()
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            val result = engine.bootstrap(stageId = F.STAGE_NEW)
            assertFalse(result.retainedStage)
            val snapshot = checkNotNull(result.snapshot)
            val blocked = snapshot.push.stop as PushDeliveryStop.Blocked
            assertEquals(PushBlockReason.REJECTED, blocked.reason)
            assertEquals(0, snapshot.push.acknowledgedMutations)
            assertNull(snapshot.download)
            assertNull(result.baseline)
            assertNull(result.delivery)
            assertTrue(transport.capturedRequests.isEmpty())
            // The rejected row stays rejected; no stage was created.
            val rows = harness.outboxSnapshot(database, F.ACCOUNT)
            assertEquals(1, rows.size)
            assertEquals(F.OUTBOX_REJECTED_MUT, rows[0][1])
            assertEquals("rejected", rows[0][9])
            assertEquals("gone", rows[0][12])
            assertNull(harness.stageHeader(database, F.STAGE_NEW))
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun pendingHeadReceivingRejectionRetainsItWithoutSnapshot() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            // A NEW pending head with a valid exact wrapper IS transmitted;
            // the rejection response is then durably retained.
            harness.seedOutboxRow(
                database,
                sequence = 10L,
                mutationId = F.OUTBOX_NEWER_MUT,
                aggregateId = F.OUTBOX_WORKOUT,
                mutationType = "workout.start",
                localGeneration = 4L,
                state = "pending",
                payloadJson = F.durablePayloadJson(F.OUTBOX_NEWER_MUT, F.OUTBOX_WORKOUT, 4L),
            )
            val transport = PreparedEngineTransport()
            transport.queueOk(
                """{"results":[{"mutationId":"${F.OUTBOX_NEWER_MUT}","status":"rejected","errorCode":"gone","batch":null}]}""",
            )
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            val result = engine.bootstrap(stageId = F.STAGE_NEW)
            assertFalse(result.retainedStage)
            val snapshot = checkNotNull(result.snapshot)
            val blocked = snapshot.push.stop as PushDeliveryStop.Blocked
            assertEquals(PushBlockReason.REJECTED, blocked.reason)
            assertNull(snapshot.download)
            assertNull(result.baseline)
            assertNull(result.delivery)
            // Exactly one push transmission with the full hand-written body
            // (mutations envelope over the exact inner request) and an empty
            // query; no snapshot HTTP whatsoever.
            assertEquals(1, transport.capturedRequests.size)
            val push = transport.capturedRequests.single()
            assertEquals(com.dragosfotache.gymtracka.data.sync.http.ServiceHttpMethod.POST, push.method)
            assertEquals("/v1/sync/push", push.path)
            assertTrue(push.query.isEmpty())
            assertEquals(mapOf("Authorization" to "Bearer ${F.ACCESS}"), push.headers)
            val expectedInner = F.durableInnerJson(F.OUTBOX_NEWER_MUT, F.OUTBOX_WORKOUT, 4L)
            assertEquals("{\"mutations\":[$expectedInner]}", push.body)
            // The rejection is durably retained on the head row.
            val rows = harness.outboxSnapshot(database, F.ACCOUNT)
            assertEquals(1, rows.size)
            assertEquals(F.OUTBOX_NEWER_MUT, rows[0][1])
            assertEquals("rejected", rows[0][9])
            assertEquals("gone", rows[0][12])
            assertNull(harness.stageHeader(database, F.STAGE_NEW))
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun explicitRestartForcesFreshSnapshotDespiteOlderBaseline() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val owner = F.owner()
            // An older complete baseline + checkpoint exists (stage removed).
            val fullTransport = PreparedEngineTransport()
            fullTransport.queueOk(F.EMPTY_SNAPSHOT_BODY)
            fullTransport.queueOk(F.EMPTY_PULL_BODY)
            val full = F.engine(database, vault, PreparedEngineAuthApi(), fullTransport, owner)
            val established = full.bootstrap(stageId = F.STAGE_NEW)
            assertNotNull(established.baseline)
            full.invalidate()

            // Force a missing cursor so the next bootstrap must stage instead
            // of skipping via the older baseline/checkpoint.
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM sync_state WHERE account_id = ?",
                arrayOf<Any?>(F.ACCOUNT),
            )
            assertNull(harness.syncState(database, F.ACCOUNT))
            val stageTransport = PreparedEngineTransport()
            stageTransport.queueOk(F.snapshotPage1())
            stageTransport.queueOk(F.snapshotPage2())
            val staged = F.engine(database, vault, PreparedEngineAuthApi(), stageTransport, owner)
            val limited = staged.bootstrap(stageId = F.STAGE_RETAINED, maxSnapshotPages = 1)
            assertNotNull(limited.snapshot)
            assertNotNull(harness.stageHeader(database, F.STAGE_RETAINED))
            staged.invalidate()

            // Restore the valid older checkpoint: a retained nonterminal stage
            // now stands alongside a complete baseline + valid checkpoint.
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO sync_state (account_id, cursor, last_success_millis) VALUES (?, ?, ?)",
                arrayOf<Any?>(F.ACCOUNT, F.BASELINE, F.FIXED_CLOCK),
            )
            assertEquals(1L, harness.accountCache(database, F.ACCOUNT)?.baselineComplete)
            assertEquals(F.BASELINE, harness.syncState(database, F.ACCOUNT)?.first)

            // Explicit abandonment forces a FRESH replacement snapshot: the
            // older baseline/checkpoint must not satisfy the restart as a skip.
            val restartTransport = PreparedEngineTransport()
            restartTransport.queueOk(F.snapshotPage1())
            restartTransport.queueOk(F.snapshotPage2())
            restartTransport.queueOk(F.pullRevision2Terminal())
            val restart = F.engine(database, vault, PreparedEngineAuthApi(), restartTransport, owner)
            val replaced = restart.bootstrap(stageId = F.STAGE_FRESH, restartRetainedStage = true)
            assertFalse(replaced.retainedStage)
            val baseline = checkNotNull(replaced.baseline)
            assertNotNull(replaced.delivery)
            val snapshotPaths = restartTransport.snapshotPaths()
            assertTrue(
                "explicit restart must transmit a fresh snapshot, got $snapshotPaths",
                snapshotPaths.contains("/v1/sync/snapshot"),
            )
            F.assertSnapshotInitial(restartTransport.capturedRequests[0])
            assertEquals(F.BASELINE, baseline.baselineCursor)
            assertEquals(2L, baseline.recordCount)
            restart.invalidate()
        } finally {
            database.close()
        }
    }

    @Test
    fun fileReopenPreservesPairAndRecoversRetainedStage() = runTest {
        val dbName = "bootstrap-recover.db"
        var journalBefore: List<String?> = emptyList()
        var headerBefore: PreparedEngineHarness.StageHeader? = null
        var childrenBefore = -1L
        val database = harness.createFileDb(dbName)
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            val setupTransport = PreparedEngineTransport()
            setupTransport.queueOk(F.snapshotPage1())
            setupTransport.queueOk(F.snapshotPage2())
            val setup = F.engine(database, vault, PreparedEngineAuthApi(), setupTransport, F.owner())
            val limited = setup.bootstrap(stageId = F.STAGE_RETAINED, maxSnapshotPages = 1)
            assertNotNull(limited.snapshot)
            journalBefore = harness.journalRow(database)
            headerBefore = harness.stageHeader(database, F.STAGE_RETAINED)
            assertNotNull(headerBefore)
            childrenBefore = harness.stageRecordCount(database, F.STAGE_RETAINED)
            setup.invalidate()
        } finally {
            database.close()
        }

        val reopened = harness.reopenFileDb(dbName)
        try {
            // Pre-close journal/header survive byte-identical (read BEFORE close above).
            assertEquals(journalBefore, harness.journalRow(reopened))
            val vault = harness.reopenVault()
            assertEquals(1L, vault.load().revision)
            assertEquals(VaultSessionPhase.PREPARED, vault.load().record?.phase)
            val header = harness.stageHeader(reopened, F.STAGE_RETAINED)
            assertEquals(headerBefore, header)
            assertNotNull(header)
            assertEquals(childrenBefore, harness.stageRecordCount(reopened, F.STAGE_RETAINED))
            assertEquals(1L, header!!.pageCount)
            assertEquals(1L, harness.stageRecordCount(reopened, F.STAGE_RETAINED))

            // Retained stage returns without HTTP on the reopened pair...
            val owner = F.owner()
            val retainedTransport = PreparedEngineTransport()
            val retainedEngine = F.engine(reopened, vault, PreparedEngineAuthApi(), retainedTransport, owner)
            val retained = retainedEngine.bootstrap(stageId = F.STAGE_OTHER)
            assertTrue(retained.retainedStage)
            assertTrue(retainedTransport.capturedRequests.isEmpty())
            retainedEngine.invalidate()

            // ...and explicit restart with a new UUID completes on it.
            val restartTransport = PreparedEngineTransport()
            restartTransport.queueOk(F.snapshotPage1())
            restartTransport.queueOk(F.snapshotPage2())
            restartTransport.queueOk(F.pullRevision2Terminal())
            val restartEngine = F.engine(reopened, vault, PreparedEngineAuthApi(), restartTransport, owner)
            val restarted = restartEngine.bootstrap(stageId = F.STAGE_NEW, restartRetainedStage = true)
            assertFalse(restarted.retainedStage)
            assertNotNull(restarted.baseline)
            assertNotNull(restarted.delivery)
            restartEngine.invalidate()
        } finally {
            reopened.close()
        }
    }

    @Test
    fun continuationCrossesHundredPageChunkWithoutPermanentCap() = runTest {
        val database = harness.openInMemoryDb()
        try {
            val vault = harness.newVault()
            harness.reservePreparedPair(database, vault)
            // 102 single-record pages: crosses the <=100-page driver chunk.
            // Frames are generated from the fixed record template (own IDs).
            val total = 102
            val transport = PreparedEngineTransport()
            transport.serve = { index, _ ->
                if (index <= total) {
                    val id = bulkId(index)
                    val token = "bootstrap-bulk-$index"
                    val body = if (index < total) {
                        """{"records":[${F.routineUpsertJson(id, "Bulk draft $index", 1)}],""" +
                            """"baselineCursor":"${F.BASELINE}","nextPageToken":"$token","hasMore":true}"""
                    } else {
                        """{"records":[${F.routineUpsertJson(id, "Bulk draft $index", 1)}],""" +
                            """"baselineCursor":"${F.BASELINE}","nextPageToken":null,"hasMore":false}"""
                    }
                    PreparedEngineTransport.Frame(200, body)
                } else {
                    PreparedEngineTransport.Frame(200, F.EMPTY_PULL_BODY)
                }
            }
            val engine = F.engine(database, vault, PreparedEngineAuthApi(), transport, F.owner())
            val result = engine.bootstrap(stageId = F.STAGE_NEW, maxSnapshotPages = total)
            val baseline = checkNotNull(result.baseline)
            assertNotNull(result.delivery)
            assertEquals(total.toLong(), baseline.pageCount)
            assertEquals(total.toLong(), baseline.recordCount)
            assertEquals(total + 1, transport.capturedRequests.size)

            // Every continuation echoes the exact prior token at the fixed limit.
            for (k in 1 until total) {
                F.assertSnapshotContinuation(
                    transport.capturedRequests[k],
                    "bootstrap-bulk-$k",
                )
            }
            F.assertPull(transport.capturedRequests[total], F.BASELINE)
            assertEquals(total.toLong(), harness.countRows(database, "sync_canonical_roots"))
            engine.invalidate()
        } finally {
            database.close()
        }
    }

    private fun bulkId(index: Int): String =
        "c" + "%07x".format(index) + "-1111-4111-8111-111111111111"
}
