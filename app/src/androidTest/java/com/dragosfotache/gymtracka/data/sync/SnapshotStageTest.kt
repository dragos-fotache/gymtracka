package com.dragosfotache.gymtracka.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.engine.PullDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.engine.SyncDeliveryLoop
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.store.PushBlockReason
import com.dragosfotache.gymtracka.data.sync.store.PushHeadResult
import com.dragosfotache.gymtracka.data.sync.store.PushOutcomeResult
import com.dragosfotache.gymtracka.data.sync.store.SnapshotStageActiveException
import com.dragosfotache.gymtracka.data.sync.store.SnapshotStageIntentException
import com.dragosfotache.gymtracka.data.sync.store.SnapshotStageStorageException
import com.dragosfotache.gymtracka.data.sync.store.StalePullCheckpointException
import com.dragosfotache.gymtracka.data.sync.store.StaleSnapshotStageException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Real Room checks for initial/terminal stages, complete ordered pages, atomic appends, admission,
 * account isolation, stage exclusion, retained corruption, reopen recovery and redacted displays.
 */
class SnapshotStageTest : GymDatabaseTest() {

    @Test
    fun beginOnEmptyOutbox_stagesInitialEmptyHeader() = runTest {
        database.stageSeedBaseline()
        val prior = database.stagePriorSnapshot()
        val stage = database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            val begun = run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
            assertEquals(STAGE_ID_1, begun.stageId)
            assertEquals(STAGE_LIMIT, begun.pageLimit)
            assertNull(begun.baselineCursor)
            assertNull(begun.nextPageToken)
            assertFalse(begun.downloaded)
            assertEquals(0L, begun.pageCount)
            assertEquals(0L, begun.recordCount)
            assertTrue(begun.canAppend)
            assertEquals("SnapshotStage", begun.toString())
            stageAssertRedacted(begun.toString(), "stage display")
            begun
        }
        // Durable header evidence: one downloading stage, zero child rows.
        val rows = database.stageRowsFor(CLAIM_ACCOUNT_A)
        assertEquals(1, rows.size)
        val header = rows.single()
        assertEquals(STAGE_ID_1, header.stageId)
        assertEquals(CLAIM_ACCOUNT_A, header.accountId)
        assertEquals(STAGE_LIMIT, header.pageLimit)
        assertEquals("downloading", header.phase)
        assertEquals(0L, header.pageCount)
        assertEquals(0L, header.recordCount)
        assertNull(header.baselineCursor)
        assertNull(header.nextPageToken)
        assertEquals(emptyList<Any>(), database.stageRecordRows(STAGE_ID_1))
        assertEquals(emptyList<Any>(), database.stageTokenRows(STAGE_ID_1))
        // Closed-run token cannot be reused outside its live run.
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            try {
                run.appendSnapshotPage(
                    stage,
                    stagePage(emptyList(), STAGE_BASELINE_1, null, false),
                )
                fail("Closed-run token must not append")
            } catch (_: IllegalStateException) {
            }
        }
        assertEquals(prior, database.stagePriorSnapshot())
    }

    @Test
    fun defaultLimit_isBoundedAndExplicitBoundsHold() = runTest {
        database.stageSeedBaseline()
        val defaulted = database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            run.beginSnapshotStage(STAGE_ID_1)
        }
        // DEFAULT_SNAPSHOT_LIMIT is exactly 200: fixed default, never clamped.
        assertEquals(200, defaulted.pageLimit)
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            run.abandonSnapshotStage(
                checkNotNull(run.readSnapshotStage()) { "Must observe defaulted stage" },
            )
        }
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            assertEquals(1, run.beginSnapshotStage(STAGE_ID_2, 1).pageLimit)
        }
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            run.abandonSnapshotStage(
                checkNotNull(run.readSnapshotStage()) { "Must observe limit-1 stage" },
            )
        }
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            assertEquals(500, run.beginSnapshotStage(STAGE_ID_3, 500).pageLimit)
        }
    }

    @Test
    fun invalidStageIdAndLimit_failBeforeStorage() = runTest {
        database.stageSeedBaseline()
        val prior = database.stagePriorSnapshot()
        listOf("not-a-uuid", "", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA").forEach { badId ->
            database.claimPushStore().withRun(STAGE_EPOCH) { run ->
                try {
                    run.beginSnapshotStage(badId, STAGE_LIMIT)
                    fail("Bad stage ID [$badId] must fail before storage")
                } catch (e: Exception) {
                    assertTrue(e is SyncModelException || e is IllegalArgumentException)
                    e.message?.let { stageAssertRedacted(it, "bad-id message") }
                }
            }
        }
        listOf(0, -1, 501, Int.MAX_VALUE).forEach { badLimit ->
            database.claimPushStore().withRun(STAGE_EPOCH) { run ->
                try {
                    run.beginSnapshotStage(STAGE_ID_1, badLimit)
                    fail("Bad limit [$badLimit] must fail before storage")
                } catch (e: Exception) {
                    assertTrue(e is SyncModelException || e is IllegalArgumentException)
                    e.message?.let { stageAssertRedacted(it, "bad-limit message") }
                }
            }
        }
        assertEquals(emptyList<Any>(), database.stageRowsFor(CLAIM_ACCOUNT_A))
        assertEquals(prior, database.stagePriorSnapshot())
    }

    @Test
    fun emptyTerminalStage_commitsDownloadedWithoutRecords() = runTest {
        database.stageSeedBaseline()
        val prior = database.stagePriorSnapshot()
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            val begun = run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
            val terminal = run.appendSnapshotPage(
                begun,
                stagePage(emptyList(), STAGE_BASELINE_1, null, false),
            )
            assertEquals(STAGE_ID_1, terminal.stageId)
            assertEquals(STAGE_BASELINE_1, terminal.baselineCursor)
            assertNull(terminal.nextPageToken)
            assertTrue(terminal.downloaded)
            assertFalse(terminal.canAppend)
            assertEquals(1L, terminal.pageCount)
            assertEquals(0L, terminal.recordCount)
        }
        // Terminal evidence: bound baseline, no continuation, no child rows,
        // and nothing entered canonical or visible domain storage.
        val header = database.stageRowsFor(CLAIM_ACCOUNT_A).single()
        assertEquals(STAGE_BASELINE_1, header.baselineCursor)
        assertNull(header.nextPageToken)
        assertEquals("downloaded", header.phase)
        assertEquals(emptyList<Any>(), database.stageRecordRows(STAGE_ID_1))
        assertEquals(emptyList<Any>(), database.stageTokenRows(STAGE_ID_1))
        assertEquals(prior, database.stagePriorSnapshot())
        assertEquals(0, database.claimCount("SELECT COUNT(*) FROM sync_canonical_roots"))
    }

    @Test
    fun multiPage_allThreeFamilies_preservesWholeRecords() = runTest {
        database.stageSeedBaseline()
        val prior = database.stagePriorSnapshot()
        // Page one carries a large whole workout whose exercise references a
        // custom record that only arrives on a LATER page: staging must
        // preserve the later-page dependency, never resolve it here.
        val workout = stageWorkoutUpsert(
            id = STAGE_WORKOUT_1,
            revision = 7L,
            exercises = listOf(
                stageWorkoutEntry(exerciseId = STAGE_CUSTOM_1, notes = STAGE_LARGE_NOTES),
            ),
        )
        val routine = stageRoutineUpsert(id = STAGE_ROUTINE_1, revision = 3L)
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            var token = run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
            token = run.appendSnapshotPage(
                token,
                stagePage(listOf(workout, routine), STAGE_BASELINE_1, STAGE_TOKEN_1, true),
            )
            assertEquals(STAGE_BASELINE_1, token.baselineCursor)
            assertEquals(STAGE_TOKEN_1, token.nextPageToken)
            assertFalse(token.downloaded)
            assertTrue(token.canAppend)
            assertEquals(1L, token.pageCount)
            assertEquals(2L, token.recordCount)
            // Same-account second family plus the depended-upon custom root.
            token = run.appendSnapshotPage(
                token,
                stagePage(
                    listOf(stageCustomUpsert(id = STAGE_CUSTOM_1, revision = 5L)),
                    STAGE_BASELINE_1, STAGE_TOKEN_2, true,
                ),
            )
            assertEquals(2L, token.pageCount)
            assertEquals(3L, token.recordCount)
            // Overlapping IDs across workout/routine namespaces are
            // independent: the same UUID root may appear under routine.
            token = run.appendSnapshotPage(
                token,
                stagePage(
                    listOf(stageRoutineUpsert(id = STAGE_WORKOUT_1, name = "Namespaced Routine", revision = 9L)),
                    STAGE_BASELINE_1, null, false,
                ),
            )
            assertTrue(token.downloaded)
            assertFalse(token.canAppend)
            assertNull(token.nextPageToken)
            assertEquals(3L, token.pageCount)
            assertEquals(4L, token.recordCount)
        }
        // Exact page/token/root evidence, ordered page then position. Stored
        // aggregate types are normative exact wire strings
        // (workout/routine/customExercise), never Kotlin enum names.
        val records = database.stageRecordRows(STAGE_ID_1)
        assertEquals(4, records.size)
        assertEquals(
            listOf("workout", "routine", "customExercise", "routine"),
            records.map { it.aggregateType },
        )
        assertEquals(
            listOf(STAGE_WORKOUT_1, STAGE_ROUTINE_1, STAGE_CUSTOM_1, STAGE_WORKOUT_1),
            records.map { it.aggregateId },
        )
        assertEquals(listOf(7L, 3L, 5L, 9L), records.map { it.serverRevision })
        assertEquals(listOf(1, 1, 2, 3), records.map { it.pageNumber.toString().toInt() })
        assertEquals(listOf(0, 1, 0, 0), records.map { it.recordPosition.toString().toInt() })
        // Whole-record fidelity: stored bodies decode to the exact represented
        // type/ID/revision, with ordered children and metadata preserved.
        val workoutJson = JSONObject(
            records.first {
                it.aggregateId == STAGE_WORKOUT_1 && it.aggregateType == "workout"
            }.canonicalJson,
        )
        assertEquals("Staged Workout", workoutJson.getString("name"))
        assertEquals(7L, workoutJson.getLong("revision"))
        val exercises = workoutJson.getJSONArray("exercises")
        assertEquals(1, exercises.length())
        // The full 128KiB body is preserved exactly, not just its length.
        assertEquals(STAGE_LARGE_NOTES, exercises.getJSONObject(0).getString("notes"))
        assertEquals(60, exercises.getJSONObject(0).getInt("restDurationSeconds"))
        assertEquals(80.0, exercises.getJSONObject(0).getJSONArray("sets").getJSONObject(0).getDouble("weightKg"), 0.0)
        val tokens = database.stageTokenRows(STAGE_ID_1)
        assertEquals(listOf(STAGE_TOKEN_1, STAGE_TOKEN_2), tokens.map { it.pageToken })
        assertEquals(listOf(1, 2), tokens.map { it.pageNumber.toString().toInt() })
        val header = database.stageRowsFor(CLAIM_ACCOUNT_A).single()
        assertEquals(3L, header.pageCount)
        assertEquals(4L, header.recordCount)
        assertEquals("downloaded", header.phase)
        // Terminal stage remains outside canonical and visible domain storage.
        assertEquals(prior, database.stagePriorSnapshot())
        assertEquals(0, database.claimCount("SELECT COUNT(*) FROM sync_canonical_roots"))
    }

    @Test
    fun wholePageAtomicity_matrixRejectsWithoutPartialWrites() = runTest {
        database.stageSeedBaseline()
        val validFirst = stagePage(
            listOf(stageWorkoutUpsert()),
            STAGE_BASELINE_1, STAGE_TOKEN_1, true,
        )
        val cases = listOf(
            "delete-op" to stagePage(listOf(stageDelete()), STAGE_BASELINE_1, STAGE_TOKEN_2, true),
            "bundled-custom" to stagePage(
                listOf(stageCustomUpsert(userCreated = false)), STAGE_BASELINE_1, STAGE_TOKEN_2, true,
            ),
            "zero-revision" to stagePage(
                listOf(stageWorkoutUpsert(id = STAGE_WORKOUT_2, revision = 0L)),
                STAGE_BASELINE_1, STAGE_TOKEN_2, true,
            ),
            "within-page-duplicate" to stagePage(
                listOf(stageWorkoutUpsert(), stageWorkoutUpsert()),
                STAGE_BASELINE_1, STAGE_TOKEN_2, true,
            ),
            "count-above-limit" to stagePage(
                listOf(
                    stageWorkoutUpsert(id = STAGE_WORKOUT_2),
                    stageRoutineUpsert(id = STAGE_ROUTINE_2),
                    stageCustomUpsert(id = STAGE_CUSTOM_2),
                ),
                STAGE_BASELINE_1, STAGE_TOKEN_2, true,
            ),
            "empty-nonterminal" to stagePage(emptyList(), STAGE_BASELINE_1, STAGE_TOKEN_2, true),
            "nonterminal-null-continuation" to stagePage(
                listOf(stageWorkoutUpsert(id = STAGE_WORKOUT_2)), STAGE_BASELINE_1, null, true,
            ),
            "terminal-nonnull-continuation" to stagePage(
                listOf(stageWorkoutUpsert(id = STAGE_WORKOUT_2)), STAGE_BASELINE_1, STAGE_TOKEN_2, false,
            ),
            "cross-page-duplicate-identical" to stagePage(
                listOf(stageWorkoutUpsert()), STAGE_BASELINE_1, STAGE_TOKEN_2, true,
            ),
            "baseline-mismatch" to stagePage(
                listOf(stageWorkoutUpsert(id = STAGE_WORKOUT_2)), STAGE_BASELINE_2, STAGE_TOKEN_2, true,
            ),
            "token-cycle" to stagePage(
                listOf(stageWorkoutUpsert(id = STAGE_WORKOUT_2)), STAGE_BASELINE_1, STAGE_TOKEN_1, true,
            ),
            // Owner resolution (snapshot staging): token shape is nonempty, not
            // nonblank. Whitespace-only nonempty tokens are covered by the
            // dedicated commit-and-preserve test below; empty-string
            // continuations stay rejected and are covered there too.
            "empty-baseline" to stagePage(
                listOf(stageWorkoutUpsert(id = STAGE_WORKOUT_2)), "", STAGE_TOKEN_2, true,
            ),
        )
        // "first-" cases append the malformed page as the FIRST page of a
        // fresh stage instead of after the valid first page.
        val firstPageCases = mapOf(
            "first-empty-baseline" to stagePage(
                listOf(stageWorkoutUpsert()), "", STAGE_TOKEN_1, true,
            ),
        )
        firstPageCases.forEach { (label, badFirst) ->
            database.claimPushStore().withRun(STAGE_EPOCH) { run ->
                val token = run.beginSnapshotStage(STAGE_ID_1, STAGE_SMALL_LIMIT)
                try {
                    run.appendSnapshotPage(token, badFirst)
                    fail("$label must reject the first page")
                } catch (e: SyncModelException) {
                    e.message?.let { stageAssertRedacted(it, "$label message") }
                    assertNull(e.cause)
                }
                assertEquals(0, database.stageRecordRows(STAGE_ID_1).size)
                assertEquals(0L, database.stageRowsFor(CLAIM_ACCOUNT_A).single().pageCount)
                run.abandonSnapshotStage(
                    checkNotNull(run.readSnapshotStage()) { "$label must observe for abandon" },
                )
            }
        }
        cases.forEach { (label, badPage) ->
            database.claimPushStore().withRun(STAGE_EPOCH) { run ->
                val token = run.appendSnapshotPage(run.beginSnapshotStage(STAGE_ID_1, STAGE_SMALL_LIMIT), validFirst)
                val committedRecords = database.stageRecordRows(STAGE_ID_1).size
                val committedHeader = database.stageRowsFor(CLAIM_ACCOUNT_A).single()
                try {
                    run.appendSnapshotPage(token, badPage)
                    fail("$label must reject the whole new page")
                } catch (e: SyncModelException) {
                    e.message?.let { stageAssertRedacted(it, "$label message") }
                    assertNull(e.cause)
                }
                // Earlier committed pages survive; no partial row/header update.
                assertEquals(label, committedRecords, database.stageRecordRows(STAGE_ID_1).size)
                val after = database.stageRowsFor(CLAIM_ACCOUNT_A).single()
                assertEquals(committedHeader.pageCount, after.pageCount)
                assertEquals(committedHeader.recordCount, after.recordCount)
                assertEquals(
                    committedHeader.nextPageToken,
                    after.nextPageToken,
                )
                run.abandonSnapshotStage(
                    checkNotNull(run.readSnapshotStage()) { "$label must observe for abandon" },
                )
            }
            assertEquals(emptyList<Any>(), database.stageRowsFor(CLAIM_ACCOUNT_A))
        }
    }

    @Test
    fun whitespaceNonemptyToken_commitsPreservedExactlyWhileEmptyRejected() = runTest {
        database.stageSeedBaseline()
        // Owner resolution (snapshot staging): nonempty (not nonblank) opaque tokens are
        // valid and preserved byte-exact; absent/empty continuations reject.
        // The token below is whitespace-ONLY nonempty, so it exercises the
        // authorized correction at the Room boundary (a token containing
        // visible text would already pass the old nonblank validator).
        val wsToken = "  \t "
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            var token = run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
            token = run.appendSnapshotPage(
                token,
                stagePage(listOf(stageWorkoutUpsert()), STAGE_BASELINE_1, wsToken, true),
            )
            assertEquals(wsToken, token.nextPageToken)
            assertEquals(1L, token.pageCount)
            // Empty-string continuation is still rejected; the committed page
            // stands and the live token is unaffected.
            try {
                run.appendSnapshotPage(
                    token,
                    stagePage(
                        listOf(stageWorkoutUpsert(id = STAGE_WORKOUT_2)),
                        STAGE_BASELINE_1, "", true,
                    ),
                )
                fail("Empty continuation must be rejected")
            } catch (e: SyncModelException) {
                e.message?.let { stageAssertRedacted(it, "empty-token message") }
                assertNull(e.cause)
            }
            assertEquals(1, database.stageRecordRows(STAGE_ID_1).size)
            token = run.appendSnapshotPage(
                token,
                stagePage(
                    listOf(stageWorkoutUpsert(id = STAGE_WORKOUT_2)),
                    STAGE_BASELINE_1, STAGE_TOKEN_2, true,
                ),
            )
            assertEquals(STAGE_TOKEN_2, token.nextPageToken)
            assertEquals(2L, token.pageCount)
        }
        // Exact durable bytes: the whitespace token stands untrimmed.
        assertEquals(
            listOf(wsToken, STAGE_TOKEN_2),
            database.stageTokenRows(STAGE_ID_1).map { it.pageToken },
        )
        assertEquals(2L, database.stageRowsFor(CLAIM_ACCOUNT_A).single().pageCount)
    }

    @Test
    fun absentCacheAndCheckpoint_beginReadAppendWithoutFabrication() = runTest {
        // Device-only start: no cache row and no sync-state row. Begin must
        // capture the absent source shape (presence false, nullables null,
        // baseline-complete false) without fabricating cache/checkpoint rows.
        database.claimSeedDevice()
        val prior = database.stagePriorSnapshot()
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            assertNull(run.readSnapshotStage())
            val begun = run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
            assertTrue(begun.canAppend)
            assertNull(begun.baselineCursor)
            assertEquals(
                0,
                database.claimCount(
                    "SELECT COUNT(*) FROM sync_account_cache WHERE account_id = '$CLAIM_ACCOUNT_A'",
                ),
            )
            assertEquals(
                0,
                database.claimCount("SELECT COUNT(*) FROM sync_state WHERE account_id = '$CLAIM_ACCOUNT_A'"),
            )
            val settled = run.appendSnapshotPage(
                begun,
                stagePage(listOf(stageWorkoutUpsert()), STAGE_BASELINE_1, null, false),
            )
            assertTrue(settled.downloaded)
            assertEquals(STAGE_BASELINE_1, settled.baselineCursor)
        }
        // Absent-source evidence retained exactly; nothing else moved.
        assertEquals(
            1,
            database.claimCount(
                "SELECT COUNT(*) FROM sync_snapshot_stages WHERE stage_id = '$STAGE_ID_1'" +
                    " AND source_state_present = 0 AND source_cursor IS NULL" +
                    " AND source_last_success_millis IS NULL AND source_baseline_complete = 0",
            ),
        )
        assertEquals(prior, database.stagePriorSnapshot())
    }

    @Test
    fun beginRejects_anyOutboxState_existingStage_andForeignCollision() = runTest {
        database.stageSeedBaseline()
        // ANY selected-account outbox state blocks begin, including frozen,
        // rejected, corrupt and unsupported-version rows.
        database.stageSeedOutboxPending()
        database.claimExec(
            "UPDATE sync_outbox SET state = 'frozen' WHERE account_id = ?",
            arrayOf<Any?>(CLAIM_ACCOUNT_A),
        )
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            try {
                run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
                fail("Frozen outbox must block begin")
            } catch (e: SnapshotStageIntentException) {
                assertEquals(STAGE_INTENT_MESSAGE, e.message)
                assertNull(e.cause)
            }
        }
        database.claimExec("DELETE FROM sync_outbox")
        database.claimExec(
            "INSERT INTO sync_outbox (sequence, mutation_id, account_id, aggregate_type, aggregate_id," +
                " mutation_type, local_generation, payload_version, payload_json, state," +
                " attempt_count, retry_after_millis, error_code, error_message)" +
                " VALUES (40, '$CLAIM_MUTATION_2', '$CLAIM_ACCOUNT_A', 'workout', '$CLAIM_WORKOUT_B'," +
                " 'workout.upsert', 1, 1, '{broken', 'pending', 0, NULL, NULL, NULL)",
        )
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            try {
                run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
                fail("Corrupt outbox must block begin")
            } catch (e: SnapshotStageIntentException) {
                assertEquals(STAGE_INTENT_MESSAGE, e.message)
            }
        }
        database.claimExec("DELETE FROM sync_outbox")
        // Distinct physical root/mutation: the prior pending workout row
        // still stands (only outbox was cleared), so reseeding the same ID
        // would violate workouts.id uniqueness.
        database.stageSeedOutboxPending(rootId = CLAIM_WORKOUT_B, mutationId = CLAIM_MUTATION_2)
        database.claimExec(
            "UPDATE sync_outbox SET state = 'rejected', error_code = 'gone'" +
                " WHERE account_id = '$CLAIM_ACCOUNT_A'",
        )
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            try {
                run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
                fail("Rejected outbox must block begin")
            } catch (_: SnapshotStageIntentException) {
            }
        }
        database.claimExec("DELETE FROM sync_outbox")
        // Clean begin, then an existing stage blocks a second begin.
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
            try {
                run.beginSnapshotStage(STAGE_ID_2, STAGE_LIMIT)
                fail("Existing stage must block begin")
            } catch (e: SnapshotStageActiveException) {
                assertEquals(STAGE_ACTIVE_MESSAGE, e.message)
                assertNull(e.cause)
            }
        }
        // Foreign stage-ID collision under the other account fails closed
        // without overwriting the original stage row.
        database.stageSeedSecondScope()
        database.claimSeedDevice(CLAIM_ACCOUNT_B)
        database.claimPushStore(claimSession(CLAIM_ACCOUNT_B)).withRun(STAGE_EPOCH) { run ->
            try {
                run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
                fail("Foreign stage-ID collision must fail closed")
            } catch (e: Exception) {
                assertTrue(
                    e is SnapshotStageActiveException ||
                        e is SnapshotStageStorageException ||
                        e is StaleSnapshotStageException,
                )
                e.message?.let { stageAssertRedacted(it, "collision message") }
            }
        }
        assertEquals(1, database.stageRowsFor(CLAIM_ACCOUNT_A).size)
        assertEquals(STAGE_ID_1, database.stageRowsFor(CLAIM_ACCOUNT_A).single().stageId)
        assertEquals(emptyList<Any>(), database.stageRowsFor(CLAIM_ACCOUNT_B))
    }

    @Test
    fun foreignAccount_isolatedFromSelectedStage() = runTest {
        database.stageSeedBaseline()
        database.stageSeedSecondScope()
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
        }
        // The other account still claims and reads pull checkpoints freely.
        database.claimSeedDevice(CLAIM_ACCOUNT_B)
        database.stageSeedOutboxPending(account = CLAIM_ACCOUNT_B, rootId = CLAIM_WORKOUT_B, mutationId = CLAIM_MUTATION_2)
        database.claimPushStore(claimSession(CLAIM_ACCOUNT_B)).withRun(STAGE_EPOCH) { run ->
            val head = run.claimHead(STAGE_NOW_MILLIS)
            assertTrue(head is PushHeadResult.Claimed)
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Foreign scope must read" }
            assertEquals(STAGE_SYNC_CURSOR_2, checkpoint.cursor)
            run.checkPullCheckpoint(checkpoint)
        }
        assertEquals(1, database.stageRowsFor(CLAIM_ACCOUNT_A).size)
    }

    @Test
    fun localCaptureAfterBegin_preservedAndNeverBlocksAppend() = runTest {
        database.stageSeedBaseline()
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            var token = run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
            // Local capture DURING download is allowed: no outbox-empty
            // recheck after begin, and staging never touches newer intent.
            database.stageSeedOutboxPending()
            // Oracle taken AFTER capture and immediately BEFORE append: the
            // capture intentionally changed domain/ledger/outbox, so only the
            // append must prove exact prior-table/newer-intent preservation.
            val admitted = database.stagePriorSnapshot()
            val intentBytes = database.claimOutboxFor(CLAIM_ACCOUNT_A).map { it.payloadJson }
            token = run.appendSnapshotPage(
                token,
                stagePage(listOf(stageWorkoutUpsert()), STAGE_BASELINE_1, STAGE_TOKEN_1, true),
            )
            assertEquals(1L, token.pageCount)
            assertEquals(admitted, database.stagePriorSnapshot())
            assertEquals(intentBytes, database.claimOutboxFor(CLAIM_ACCOUNT_A).map { it.payloadJson })
            val outbox = database.claimOutboxFor(CLAIM_ACCOUNT_A)
            assertEquals(1, outbox.size)
            assertEquals("pending", outbox.single().state)
        }
    }

    @Test
    fun beginRetires_priorPullCheckpoint() = runTest {
        database.stageSeedBaseline()
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Must read before stage" }
            run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
            try {
                run.checkPullCheckpoint(checkpoint)
                fail("Begin must retire the previously admitted pull token")
            } catch (_: StalePullCheckpointException) {
            }
        }
    }

    @Test
    fun selectedStage_blocksPushAndPullThroughExistingDrivers() = runTest {
        database.stageSeedBaseline()
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
        }
        // Newer local intent arrives after begin and is preserved.
        database.stageSeedOutboxPending()
        val outboxBefore = database.claimOutboxFor(CLAIM_ACCOUNT_A).map { it.payloadJson }
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            // claimHead checks stage existence BEFORE selecting/freezing any
            // head and returns unchanged Blocked.
            val blocked = run.claimHead(STAGE_NOW_MILLIS) as PushHeadResult.Blocked
            assertEquals(PushBlockReason.SNAPSHOT_STAGE, blocked.reason)
            assertNull(blocked.errorCode)
            // A consecutive claim stays blocked with no outbox movement;
            // budget exhaustion itself is covered by the established budget
            // regressions, not re-proven here.
            val again = run.claimHead(STAGE_NOW_MILLIS) as PushHeadResult.Blocked
            assertEquals(PushBlockReason.SNAPSHOT_STAGE, again.reason)
            assertEquals(outboxBefore, database.claimOutboxFor(CLAIM_ACCOUNT_A).map { it.payloadJson })
            // Every incremental pull op rejects selected-stage presence.
            val seed = database.stagePriorSnapshot()
            try {
                run.readPullCheckpoint()
                fail("Pull read must reject staged presence")
            } catch (e: SnapshotStageActiveException) {
                assertEquals(STAGE_ACTIVE_MESSAGE, e.message)
            }
            assertEquals(seed, database.stagePriorSnapshot())
        }
        // Shared drivers perform no HTTP and surface the block.
        val api = SyncDeliveryScriptApi(database, mutableListOf(), mutableListOf())
        val driven = SyncDeliveryLoop(database.claimPushStore(claimSession(), claimOwner()), api) { STAGE_NOW_MILLIS }
            .deliver(STAGE_EPOCH)
        assertTrue(api.pushCalls.isEmpty())
        assertTrue(api.pullCalls.isEmpty())
        assertNull(driven.pull)
        val gated = SyncDeliveryScriptApi(database, mutableListOf(), mutableListOf())
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            try {
                PullDeliveryLoop(gated).pull(run, 10, 100)
                fail("Pull driver must throw before HTTP")
            } catch (e: SnapshotStageActiveException) {
                assertEquals(STAGE_ACTIVE_MESSAGE, e.message)
            }
        }
        assertTrue(gated.pullCalls.isEmpty())
        // Explicit abandonment restores eligible behavior without rewriting
        // the request, checkpoint or success rows.
        val cursorBefore = database.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.cursor
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            run.abandonSnapshotStage(
                checkNotNull(run.readSnapshotStage()) { "Must observe for abandon" },
            )
        }
        assertEquals(emptyList<Any>(), database.stageRowsFor(CLAIM_ACCOUNT_A))
        assertEquals(outboxBefore, database.claimOutboxFor(CLAIM_ACCOUNT_A).map { it.payloadJson })
        assertEquals(cursorBefore, database.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.cursor)
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            assertTrue(run.claimHead(STAGE_NOW_MILLIS) is PushHeadResult.Claimed)
            val fresh = checkNotNull(run.readPullCheckpoint()) { "Pull must work after abandon" }
            assertEquals(STAGE_SYNC_CURSOR, fresh.cursor)
        }
    }

    @Test
    fun selectedStage_rejectsClaimSeamsWithStorageUnchanged() = runTest {
        // One bounded claim-group test: check, outcome, failure and ack
        // guards against a live claim coexisting with a selected stage.
        // Coexistence is unreachable via API (begin needs an empty outbox;
        // claimHead blocks when staged), so the stage arrives via direct
        // test-owned SQL with literal begin-equivalent header bytes. Claims
        // are run-bound, so everything shares one live run.
        database.stageSeedBaseline()
        database.stageSeedOutboxPending()
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            val claim = (run.claimHead(STAGE_NOW_MILLIS) as PushHeadResult.Claimed).claim
            val pushResult = ackAcceptedResult(
                claim.row.mutationId,
                ackBatch(target = ackWorkoutChange()),
            )
            database.stageInsertInitialHeader(STAGE_ID_1, STAGE_LIMIT)
            val before = database.claimSnapshot()
            try {
                run.checkClaim(claim)
                fail("checkClaim must reject staged presence")
            } catch (e: SnapshotStageActiveException) {
                assertEquals(STAGE_ACTIVE_MESSAGE, e.message)
                assertNull(e.cause)
            }
            assertEquals(before, database.claimSnapshot())
            try {
                run.handleResult(claim, pushResult)
                fail("handleResult must reject staged presence")
            } catch (e: SnapshotStageActiveException) {
                assertEquals(STAGE_ACTIVE_MESSAGE, e.message)
            }
            assertEquals(before, database.claimSnapshot())
            try {
                run.handleFailure(claim, SyncApiException.Transient(), STAGE_NOW_MILLIS)
                fail("handleFailure must reject staged presence")
            } catch (e: SnapshotStageActiveException) {
                assertEquals(STAGE_ACTIVE_MESSAGE, e.message)
            }
            assertEquals(before, database.claimSnapshot())
            // The rejections left the claim live: abandon the stage, accept
            // the still-pending head, restage, and prove the ack guard too.
            run.abandonSnapshotStage(
                checkNotNull(run.readSnapshotStage()) { "Must observe for abandon" },
            )
            val accepted = run.handleResult(claim, pushResult) as PushOutcomeResult.Accepted
            database.stageInsertInitialHeader(STAGE_ID_1, STAGE_LIMIT)
            val restaged = database.claimSnapshot()
            try {
                run.acknowledgeAccepted(claim, accepted)
                fail("acknowledgeAccepted must reject staged presence")
            } catch (e: SnapshotStageActiveException) {
                assertEquals(STAGE_ACTIVE_MESSAGE, e.message)
                assertNull(e.cause)
            }
            assertEquals(restaged, database.claimSnapshot())
        }
    }

    @Test
    fun selectedStage_rejectsPullSeamsWithStorageUnchanged() = runTest {
        // One bounded pull-group test: checkpoint check, batch commit and
        // tail guards against a live checkpoint coexisting with a selected
        // stage. A standard begin retires the admitted pull token, so the
        // stage again arrives via direct test-owned SQL in the same live run.
        database.stageSeedBaseline()
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            val checkpoint = checkNotNull(run.readPullCheckpoint()) { "Must read before stage" }
            database.stageInsertInitialHeader(STAGE_ID_1, STAGE_LIMIT)
            val before = database.claimSnapshot()
            try {
                run.checkPullCheckpoint(checkpoint)
                fail("Pull check must reject staged presence")
            } catch (e: SnapshotStageActiveException) {
                assertEquals(STAGE_ACTIVE_MESSAGE, e.message)
            }
            assertEquals(before, database.claimSnapshot())
            try {
                run.applyNextPullBatch(
                    checkpoint,
                    pullBatch(
                        changes = listOf(
                            pullUpsert(AggregateType.WORKOUT, PULL_WORKOUT_A, 7L, pullWorkoutRecord()),
                        ),
                        cursor = PULL_CURSOR_2,
                    ),
                )
                fail("Pull batch must reject staged presence")
            } catch (e: SnapshotStageActiveException) {
                assertEquals(STAGE_ACTIVE_MESSAGE, e.message)
            }
            assertEquals(before, database.claimSnapshot())
            try {
                run.recordPullTail(checkpoint, STAGE_NOW_MILLIS)
                fail("Pull tail must reject staged presence")
            } catch (e: SnapshotStageActiveException) {
                assertEquals(STAGE_ACTIVE_MESSAGE, e.message)
            }
            assertEquals(before, database.claimSnapshot())
        }
    }

    @Test
    fun corruptRetained_failsClosedAndPreserved() = runTest {
        database.stageSeedBaseline()
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            val token = run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
            run.appendSnapshotPage(
                token,
                stagePage(listOf(stageWorkoutUpsert()), STAGE_BASELINE_1, STAGE_TOKEN_1, true),
            )
        }
        // Corrupt one retained body behind the committed header.
        database.claimExec(
            "UPDATE sync_snapshot_records SET canonical_json = '{broken' " +
                "WHERE stage_id = '$STAGE_ID_1'",
        )
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            try {
                run.readSnapshotStage()
                fail("Corrupt body must fail the read closed")
            } catch (e: SnapshotStageStorageException) {
                assertEquals(STAGE_STORAGE_MESSAGE, e.message)
                assertNull(e.cause)
                e.message?.let { stageAssertRedacted(it, "corrupt message") }
            }
            // Corrupt retained evidence yields no observation: reads fail
            // closed and keep failing with the bytes preserved. No repair,
            // filtering or cleanup authority is exercised here.
            try {
                run.readSnapshotStage()
                fail("Corrupt body must keep failing without repair")
            } catch (_: SnapshotStageStorageException) {
            }
        }
        // Nothing was repaired or normalized: the corrupt bytes stand.
        assertEquals(
            "{broken",
            database.stageRecordRows(STAGE_ID_1).single().canonicalJson,
        )
        val priorAfterBodyCorrupt = database.stagePriorSnapshot()
        // Corrupt progress evidence likewise fails closed and is preserved.
        database.claimExec(
            "UPDATE sync_snapshot_records SET canonical_json = '{}' WHERE stage_id = '$STAGE_ID_1'",
        )
        database.claimExec("DELETE FROM sync_snapshot_tokens WHERE stage_id = '$STAGE_ID_1'")
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            try {
                run.readSnapshotStage()
                fail("Missing progress evidence must fail closed")
            } catch (_: SnapshotStageStorageException) {
            }
        }
        assertEquals(0, database.stageTokenRows(STAGE_ID_1).size)
        assertEquals(1, database.stageRowsFor(CLAIM_ACCOUNT_A).size)
        // The corrupt bytes stand exactly as written: no repair,
        // normalization, filtering or cleanup authority was exercised.
        assertEquals("{}", database.stageRecordRows(STAGE_ID_1).single().canonicalJson)
        assertEquals(priorAfterBodyCorrupt, database.stagePriorSnapshot())
    }

    @Test
    fun absurdPageCount_failsClosedPromptlyWithoutChanges() = runTest {
        database.stageSeedBaseline()
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            val token = run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
            run.appendSnapshotPage(
                token,
                stagePage(listOf(stageWorkoutUpsert()), STAGE_BASELINE_1, STAGE_TOKEN_1, true),
            )
        }
        // Absurd retained counter behind small real evidence: malformed
        // durable evidence, not a loop target. Validation must fail closed
        // promptly against the real evidence without iterating the counter.
        database.claimExec(
            "UPDATE sync_snapshot_stages SET page_count = 9223372036854775807 " +
                "WHERE stage_id = '$STAGE_ID_1'",
        )
        val tampered = database.claimSnapshot()
        val tamperedPrior = database.stagePriorSnapshot()
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            try {
                run.readSnapshotStage()
                fail("Absurd pageCount must fail the read closed")
            } catch (e: SnapshotStageStorageException) {
                assertEquals(STAGE_STORAGE_MESSAGE, e.message)
                assertNull(e.cause)
                e.message?.let { stageAssertRedacted(it, "absurd-counter message") }
            }
            try {
                run.readSnapshotStage()
                fail("Absurd pageCount must keep failing without repair")
            } catch (_: SnapshotStageStorageException) {
            }
        }
        // No normalization, no repair, no side effects: the absurd value and
        // the small real evidence stand exactly as retained.
        assertEquals(tampered, database.claimSnapshot())
        assertEquals(tamperedPrior, database.stagePriorSnapshot())
        assertEquals(1, database.stageRecordRows(STAGE_ID_1).size)
        assertEquals(1, database.stageTokenRows(STAGE_ID_1).size)
        database.openHelper.writableDatabase.query(
            "SELECT page_count FROM sync_snapshot_stages WHERE stage_id = '$STAGE_ID_1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(Long.MAX_VALUE, cursor.getLong(0))
        }
    }

    @Test
    fun missingNonterminalPage_failsClosedAndPreserved() = runTest {
        database.stageSeedBaseline()
        // Normal three-page downloaded stage: two nonterminal pages plus a
        // terminal page that carries a record. The known terminal-empty case
        // stays valid and is covered separately.
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            var token = run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
            token = run.appendSnapshotPage(
                token,
                stagePage(
                    listOf(stageWorkoutUpsert(), stageRoutineUpsert()),
                    STAGE_BASELINE_1, STAGE_TOKEN_1, true,
                ),
            )
            token = run.appendSnapshotPage(
                token,
                stagePage(listOf(stageCustomUpsert()), STAGE_BASELINE_1, STAGE_TOKEN_2, true),
            )
            val terminal = run.appendSnapshotPage(
                token,
                stagePage(
                    listOf(stageRoutineUpsert(id = STAGE_WORKOUT_1, revision = 9L)),
                    STAGE_BASELINE_1, null, false,
                ),
            )
            assertTrue(terminal.downloaded)
        }
        // Corrupt only completeness: remove the nonterminal page-2 rows and
        // adjust the header counter to match, so the missing page is the sole
        // inconsistency against header, tokens and terminal evidence.
        database.claimExec(
            "DELETE FROM sync_snapshot_records WHERE stage_id = '$STAGE_ID_1' AND page_number = 2",
        )
        database.claimExec(
            "UPDATE sync_snapshot_stages SET record_count = record_count - 1 " +
                "WHERE stage_id = '$STAGE_ID_1'",
        )
        val tampered = database.claimSnapshot()
        val tamperedPrior = database.stagePriorSnapshot()
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            try {
                run.readSnapshotStage()
                fail("Missing nonterminal page must fail the read closed")
            } catch (e: SnapshotStageStorageException) {
                assertEquals(STAGE_STORAGE_MESSAGE, e.message)
                assertNull(e.cause)
                e.message?.let { stageAssertRedacted(it, "missing-page message") }
            }
            try {
                run.readSnapshotStage()
                fail("Missing nonterminal page must keep failing without repair")
            } catch (_: SnapshotStageStorageException) {
            }
        }
        // Preserved exactly as retained: header, surviving rows, both token
        // rows and every prior table untouched by the failed reads.
        assertEquals(tampered, database.claimSnapshot())
        assertEquals(tamperedPrior, database.stagePriorSnapshot())
        assertEquals(3, database.stageRecordRows(STAGE_ID_1).size)
        assertEquals(
            listOf(STAGE_TOKEN_1, STAGE_TOKEN_2),
            database.stageTokenRows(STAGE_ID_1).map { it.pageToken },
        )
        assertEquals(3L, database.stageRowsFor(CLAIM_ACCOUNT_A).single().pageCount)
        assertEquals(3L, database.stageRowsFor(CLAIM_ACCOUNT_A).single().recordCount)
    }

    @Test
    fun diskReopen_partialAndDownloadedStage_haveNoImplicitEffects() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val partialName = claimUniqueDbName("stage-partial")
        val partialDb = claimFileDatabase(context, partialName)
        // Newer intent is captured DURING the live run after begin (seeding
        // outbox before begin must reject); bytes stay in outer scope for
        // exact reopen comparison.
        var outboxBytes = emptyList<String>()
        try {
            partialDb.stageSeedBaseline()
            partialDb.claimPushStore().withRun(STAGE_EPOCH) { run ->
                val token = run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
                partialDb.stageSeedOutboxPending()
                outboxBytes = partialDb.claimOutboxFor(CLAIM_ACCOUNT_A).map { it.payloadJson }
                run.appendSnapshotPage(
                    token,
                    stagePage(listOf(stageWorkoutUpsert()), STAGE_BASELINE_1, STAGE_TOKEN_1, true),
                )
            }
        } finally {
            partialDb.close()
        }
        val reopened = claimFileDatabase(context, partialName)
        try {
            reopened.claimSeedDevice()
            // No implicit resume, publication or cleanup across restart.
            assertEquals(1, reopened.stageRowsFor(CLAIM_ACCOUNT_A).size)
            assertEquals(1, reopened.stageRecordRows(STAGE_ID_1).size)
            reopened.claimPushStore().withRun(STAGE_EPOCH) { run ->
                val observation = checkNotNull(run.readSnapshotStage()) { "Partial stage must survive reopen" }
                assertFalse(observation.canAppend)
                assertEquals(STAGE_BASELINE_1, observation.baselineCursor)
                try {
                    run.appendSnapshotPage(
                        observation,
                        stagePage(listOf(stageWorkoutUpsert(id = STAGE_WORKOUT_2)), STAGE_BASELINE_1, STAGE_TOKEN_2, true),
                    )
                    fail("Restart observation must never resume append authority")
                } catch (_: StaleSnapshotStageException) {
                }
                // Explicit same-account abandon under the CURRENT session works
                // and preserves newer outbox, domain, cache and other scope.
                run.abandonSnapshotStage(observation)
            }
            assertEquals(emptyList<Any>(), reopened.stageRowsFor(CLAIM_ACCOUNT_A))
            assertEquals(
                outboxBytes,
                reopened.claimOutboxFor(CLAIM_ACCOUNT_A).map { it.payloadJson },
            )
            assertEquals(STAGE_SYNC_CURSOR, reopened.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.cursor)
            // A NEW stage UUID with null request token restarts cleanly.
            reopened.claimExec("DELETE FROM sync_outbox")
            reopened.claimPushStore().withRun(STAGE_EPOCH) { run ->
                val fresh = run.beginSnapshotStage(STAGE_ID_2, STAGE_LIMIT)
                assertTrue(fresh.canAppend)
                assertNull(fresh.baselineCursor)
            }
        } finally {
            reopened.close()
            context.deleteDatabase(partialName)
        }
    }

    @Test
    fun downloadedStage_neverEstablishesBaselineAbsenceOrPublication() = runTest {
        database.stageSeedBaseline()
        val cursorBefore = database.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.cursor
        val successBefore = database.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.lastSuccessMillis
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            var token = run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
            token = run.appendSnapshotPage(
                token,
                stagePage(listOf(stageWorkoutUpsert()), STAGE_BASELINE_1, null, false),
            )
            assertTrue(token.downloaded)
        }
        // Downloaded is still NOT a validated graph, canonical baseline,
        // deletion authority, bootstrap success or visible publication.
        assertEquals(cursorBefore, database.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.cursor)
        assertEquals(successBefore, database.syncDao().getSyncState(CLAIM_ACCOUNT_A)?.lastSuccessMillis)
        assertEquals(0, database.claimCount("SELECT COUNT(*) FROM sync_canonical_roots"))
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            val observation = checkNotNull(run.readSnapshotStage()) { "Downloaded stage must be observable" }
            assertTrue(observation.downloaded)
            assertFalse(observation.canAppend)
        }
    }

    @Test
    fun stageDisplayAndMessages_areRedactedWithFixedText() = runTest {
        database.stageSeedBaseline()
        database.claimPushStore().withRun(STAGE_EPOCH) { run ->
            val stage = run.beginSnapshotStage(STAGE_ID_1, STAGE_LIMIT)
            assertEquals("SnapshotStage", stage.toString())
            stageAssertRedacted(stage.toString(), "stage toString")
            try {
                run.beginSnapshotStage(STAGE_ID_2, STAGE_LIMIT)
                fail("Must reject second begin")
            } catch (e: SnapshotStageActiveException) {
                assertEquals(STAGE_ACTIVE_MESSAGE, e.message)
                stageAssertRedacted(e.message!!, "active message")
                assertNull(e.cause)
            }
        }
    }
}
