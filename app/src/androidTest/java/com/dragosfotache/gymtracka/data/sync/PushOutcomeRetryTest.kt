package com.dragosfotache.gymtracka.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dragosfotache.gymtracka.data.gym.GymDatabaseTest
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.store.PushFailureResult
import com.dragosfotache.gymtracka.data.sync.store.PushHeadResult
import com.dragosfotache.gymtracka.data.sync.store.StalePushClaimException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * retry acceptance: transient scheduling with deterministic
 * backoff/hints/caps, due-time blocking, exact-byte replay with admission
 * progression, overflow exhaustion and disk reopen. Real Room, no mocked
 * DAOs, no sleeps. Pure arithmetic lives in the JVM [PushRetryPolicyTest].
 */
class PushOutcomeRetryTest : GymDatabaseTest() {

    @Test
    fun reconnectResetsOnlyConnectionBackoff_preservingExactRequestAndAdmissionHistory() = runTest {
        database.outcomeSeedFenced()
        val original = database.outcomeCaptureHead()
        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
            run.handleFailure(claim, SyncApiException.Transient(connectionFailure = true), OUTCOME_NOW_MILLIS)
        }
        val held = database.outcomeRow()
        assertEquals("connection", held.retryCause)
        database.claimPushStore().withRun(0L) { run ->
            assertTrue(run.claimHead(OUTCOME_NOW_MILLIS) is PushHeadResult.Deferred)
        }
        database.claimPushStore().withRun(0L) { run ->
            run.retryConnectionHeadAfterReconnect()
            val retry = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
            assertEquals(original.payloadJson, retry.row.payloadJson)
            assertEquals(original.mutationId, retry.row.mutationId)
            assertEquals(2, retry.row.attemptCount)
            assertNull(retry.row.retryAfterMillis)
            assertEquals("unknown", retry.row.retryCause)
            // A new connection failure is not reset by the already-consumed event.
            run.handleFailure(retry, SyncApiException.Transient(connectionFailure = true), OUTCOME_NOW_MILLIS)
            assertTrue(run.claimHead(OUTCOME_NOW_MILLIS) is PushHeadResult.Deferred)
        }
    }

    @Test
    fun reconnectNeverShortensServiceHintsOrUnknownLegacyDelays() = runTest {
        database.outcomeSeedFenced()
        database.outcomeCaptureHead()
        for (failure in listOf(SyncApiException.Transient(), SyncApiException.Transient(300_000L), SyncApiException.Transient(300_000L, connectionFailure = true))) {
            val now = database.outcomeRow().retryAfterMillis ?: OUTCOME_NOW_MILLIS
            database.claimPushStore().withRun(0L) { run ->
                val claim = (run.claimHead(now) as PushHeadResult.Claimed).claim
                run.handleFailure(claim, failure, now)
            }
            val held = database.outcomeRow()
            assertEquals("unknown", held.retryCause)
            database.claimPushStore().withRun(0L) { run ->
                run.retryConnectionHeadAfterReconnect()
                assertTrue(run.claimHead(now) is PushHeadResult.Deferred)
            }
            assertEquals(held, database.outcomeRow())
        }
    }

    @Test
    fun connectionRetryCause_survivesFileReopen_andAllowsExactImmediateReplay() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = claimUniqueDbName("connection-retry-reopen")
        val first = claimFileDatabase(context, name)
        val held = try {
            first.outcomeSeedFenced()
            first.outcomeCaptureHead()
            first.claimPushStore().withRun(0L) { run ->
                val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
                run.handleFailure(claim, SyncApiException.Transient(connectionFailure = true), OUTCOME_NOW_MILLIS)
            }
            first.outcomeRow()
        } finally {
            first.close()
        }
        val reopened = claimFileDatabase(context, name)
        try {
            assertEquals(held, reopened.outcomeRow())
            reopened.claimPushStore().withRun(0L) { run ->
                run.retryConnectionHeadAfterReconnect()
                val retry = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
                assertEquals(held.payloadJson, retry.row.payloadJson)
                assertEquals(held.mutationId, retry.row.mutationId)
                assertEquals(held.attemptCount + 1, retry.row.attemptCount)
                assertNull(retry.row.retryAfterMillis)
            }
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun transientNullHint_schedulesBackoffAndBlocksUntilDue() = runTest {
        database.outcomeSeedFenced()
        val stored = database.outcomeCaptureHead()

        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
            val scheduled = run.handleFailure(claim, SyncApiException.Transient(), OUTCOME_NOW_MILLIS)
                as PushFailureResult.RetryScheduled
            // Admission 1 backs off exactly 1000 ms.
            assertEquals(OUTCOME_NOW_MILLIS + 1_000L, scheduled.retryAfterMillis)
        }

        val waiting = database.outcomeRow()
        assertEquals("frozen", waiting.state)
        assertEquals(OUTCOME_NOW_MILLIS + 1_000L, waiting.retryAfterMillis)
        assertNull(waiting.errorCode)
        assertNull(waiting.errorMessage)
        assertEquals(stored.payloadJson, waiting.payloadJson)

        // Due-time blocking: the head stays deferred until its absolute time.
        var deferred: PushHeadResult.Deferred? = null
        database.claimPushStore().withRun(0L) { run ->
            deferred = run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Deferred
        }
        assertEquals(OUTCOME_NOW_MILLIS + 1_000L, deferred!!.retryAfterMillis)

        // Replay when due obtains a new claim/count but the same wire identity.
        database.claimPushStore().withRun(0L) { run ->
            val replayed = (run.claimHead(OUTCOME_NOW_MILLIS + 1_000L) as PushHeadResult.Claimed).claim
            assertEquals(stored.mutationId, replayed.row.mutationId)
            assertEquals(2, replayed.row.attemptCount)
            assertEquals(waiting.payloadJson, replayed.row.payloadJson)
            assertEquals(stored.payloadJson, replayed.row.payloadJson)
            assertEquals(
                JSONObject(stored.payloadJson).getString("requestJson"),
                replayed.request.requestJson,
            )
        }
    }

    @Test
    fun transientHints_clampCapAndSchedule() = runTest {
        // (hint, expected absolute retry time) for admission count 1.
        val cases = listOf(
            Triple("null-hint", null, OUTCOME_NOW_MILLIS + 1_000L),
            Triple("negative-hint", -5L, OUTCOME_NOW_MILLIS + 1_000L),
            Triple("below-backoff", 500L, OUTCOME_NOW_MILLIS + 1_000L),
            Triple("above-backoff", 5_000L, OUTCOME_NOW_MILLIS + 5_000L),
            Triple("at-cap", 300_000L, OUTCOME_NOW_MILLIS + 300_000L),
            Triple("above-cap", 999_999L, OUTCOME_NOW_MILLIS + 300_000L),
        )
        cases.forEach { (label, hint, expected) ->
            database.claimExec("DELETE FROM sync_outbox")
            database.claimExec("DELETE FROM workouts")
            database.outcomeSeedFenced()
            database.outcomeCaptureHead()
            database.claimPushStore().withRun(0L) { run ->
                val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
                val scheduled = run.handleFailure(
                    claim,
                    if (hint == null) SyncApiException.Transient() else SyncApiException.Transient(hint),
                    OUTCOME_NOW_MILLIS,
                ) as PushFailureResult.RetryScheduled
                assertEquals("$label return", expected, scheduled.retryAfterMillis)
            }
            try {
                val head = database.outcomeRow()
                assertEquals("$label row", expected, head.retryAfterMillis)
                assertEquals("$label state", "frozen", head.state)
                assertNull("$label code", head.errorCode)
                assertNull("$label message", head.errorMessage)
            } catch (error: AssertionError) {
                throw AssertionError("$label: ${error.message}", error)
            }
        }
    }

    @Test
    fun admissionProgression_increasesBackoffWithUnchangedBytes() = runTest {
        database.outcomeSeedFenced()
        val stored = database.outcomeCaptureHead()

        // First admission schedules 1000 ms; replay admits count 2.
        database.claimPushStore().withRun(0L) { run ->
            val first = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
            val scheduled = run.handleFailure(first, SyncApiException.Transient(), OUTCOME_NOW_MILLIS)
                as PushFailureResult.RetryScheduled
            assertEquals(OUTCOME_NOW_MILLIS + 1_000L, scheduled.retryAfterMillis)
        }
        val due = OUTCOME_NOW_MILLIS + 1_000L
        var secondAttempt = 0
        database.claimPushStore().withRun(0L) { run ->
            val second = (run.claimHead(due) as PushHeadResult.Claimed).claim
            secondAttempt = second.row.attemptCount
            assertEquals(2, secondAttempt)
            assertEquals(stored.payloadJson, second.row.payloadJson)
            // Second admission backs off 2000 ms from its own handling time.
            val rescheduled = run.handleFailure(second, SyncApiException.Transient(), due)
                as PushFailureResult.RetryScheduled
            assertEquals(due + 2_000L, rescheduled.retryAfterMillis)
        }
        assertEquals(2, secondAttempt)
        val waiting = database.outcomeRow()
        assertEquals(due + 2_000L, waiting.retryAfterMillis)
        assertEquals(2, waiting.attemptCount)
        assertEquals(stored.payloadJson, waiting.payloadJson)
        assertTrue(waiting.payloadJson.contains(stored.mutationId))
    }

    @Test
    fun retryTimestampOverflow_retainsExhaustedWithoutWrapping() = runTest {
        database.outcomeSeedFenced()
        val stored = database.outcomeCaptureHead()

        database.claimPushStore().withRun(0L) { run ->
            val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
            // Any positive delay overflows from Long.MAX_VALUE: never wrap to due.
            val exhausted = run.handleFailure(claim, SyncApiException.Transient(), Long.MAX_VALUE)
                as PushFailureResult.Retained
            assertEquals(OUTCOME_RETRY_EXHAUSTED_CODE, exhausted.errorCode)
        }

        val head = database.outcomeRow()
        assertEquals("frozen", head.state)
        assertEquals(OUTCOME_RETRY_EXHAUSTED_CODE, head.errorCode)
        assertEquals(OUTCOME_RETRY_EXHAUSTED_MESSAGE, head.errorMessage)
        assertNull(head.retryAfterMillis)
        assertEquals(stored.payloadJson, head.payloadJson)

        // Exhausted retry blocks via existing selection; the head never becomes due.
        var blocked: PushHeadResult? = null
        database.claimPushStore().withRun(0L) { run ->
            blocked = run.claimHead(Long.MAX_VALUE)
        }
        assertTrue(blocked is PushHeadResult.Blocked)
    }

    @Test
    fun clockRollbackEqualDueTime_stillConsumesClaim() = runTest {
        database.outcomeSeedFenced()
        val stored = database.outcomeCaptureHead()
        // Admission 1 at t=1000 with a null hint schedules retryAt=2000.
        database.claimPushStore().withRun(0L) { run ->
            val first = (run.claimHead(1_000L) as PushHeadResult.Claimed).claim
            val scheduled = run.handleFailure(first, SyncApiException.Transient(), 1_000L)
                as PushFailureResult.RetryScheduled
            assertEquals(2_000L, scheduled.retryAfterMillis)
        }
        // Admitted exactly when due obtains count 2 with retryAt still 2000.
        database.claimPushStore().withRun(0L) { run ->
            val second = (run.claimHead(2_000L) as PushHeadResult.Claimed).claim
            assertEquals(2, second.row.attemptCount)
            assertEquals(2_000L, second.row.retryAfterMillis)
            // Clock rollback: now=0 with admission-2 backoff 2000 recomputes 2000.
            val rescheduled = run.handleFailure(second, SyncApiException.Transient(), 0L)
                as PushFailureResult.RetryScheduled
            assertEquals(2_000L, rescheduled.retryAfterMillis)
            val consumed = database.outcomeRow()
            assertEquals(2, consumed.attemptCount)
            assertEquals(2_000L, consumed.retryAfterMillis)
            assertEquals(stored.payloadJson, consumed.payloadJson)
            // The successful write consumed the claim on every branch.
            try {
                run.handleFailure(second, SyncApiException.Transient(), 0L)
                fail("Reused claim must be stale after a successful write")
            } catch (_: StalePushClaimException) {
            }
            try {
                run.handleResult(second, outcomeRejectedResult(second.row.mutationId, "gone"))
                fail("Reused claim must be stale for results too")
            } catch (_: StalePushClaimException) {
            }
            try {
                run.checkClaim(second)
                fail("Reused claim must be stale for validation too")
            } catch (_: StalePushClaimException) {
            }
            assertEquals(consumed, database.outcomeRow())
        }
        // New claim/replay remains exact identity and stays due at 2000.
        val replayed = database.outcomeFreshClaim(nowMillis = 2_000L)
        assertEquals(3, replayed.row.attemptCount)
        assertEquals(stored.mutationId, replayed.row.mutationId)
        assertEquals(stored.payloadJson, replayed.row.payloadJson)
        assertEquals(2_000L, replayed.row.retryAfterMillis)
    }

    @Test
    fun diskReopen_preservesScheduledRetryForNewOwner() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = claimUniqueDbName("outcome-retry")
        var preCloseBytes = ""
        var preCloseMutation = ""
        val fileDatabase = claimFileDatabase(context, name)
        try {
            fileDatabase.outcomeSeedFenced()
            val captured = fileDatabase.outcomeCaptureHead()
            fileDatabase.claimPushStore().withRun(0L) { run ->
                val claim = (run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Claimed).claim
                val scheduled = run.handleFailure(
                    claim,
                    SyncApiException.Transient(5_000L),
                    OUTCOME_NOW_MILLIS,
                ) as PushFailureResult.RetryScheduled
                assertEquals(OUTCOME_NOW_MILLIS + 5_000L, scheduled.retryAfterMillis)
            }
            assertEquals(
                OUTCOME_NOW_MILLIS + 5_000L,
                fileDatabase.syncDao().getOutboxMutation(captured.mutationId, CLAIM_ACCOUNT_A)?.retryAfterMillis,
            )
            // Pre-close identity: wrapper bytes and mutation ID captured before close.
            preCloseBytes = captured.payloadJson
            preCloseMutation = captured.mutationId
        } finally {
            fileDatabase.close()
        }

        // Simulated restart: the stored absolute due time resumes, unchanged.
        val reopened = claimFileDatabase(context, name)
        try {
            reopened.claimSeedDevice()
            val head = reopened.syncDao().getOutboxMutation(CLAIM_MUTATION_1, CLAIM_ACCOUNT_A)!!
            assertEquals("frozen", head.state)
            assertEquals(OUTCOME_NOW_MILLIS + 5_000L, head.retryAfterMillis)
            reopened.claimPushStore().withRun(0L) { run ->
                run.retryConnectionHeadAfterReconnect()
                val deferred = run.claimHead(OUTCOME_NOW_MILLIS) as PushHeadResult.Deferred
                assertEquals(OUTCOME_NOW_MILLIS + 5_000L, deferred.retryAfterMillis)
                val replayed = (run.claimHead(OUTCOME_NOW_MILLIS + 5_000L) as PushHeadResult.Claimed).claim
                assertEquals(preCloseMutation, replayed.row.mutationId)
                assertEquals(head.mutationId, replayed.row.mutationId)
                assertEquals(2, replayed.row.attemptCount)
                // Replayed identity matches the pre-close captured bytes, not
                // only the reopened row itself.
                assertEquals(preCloseBytes, replayed.row.payloadJson)
                assertEquals(head.payloadJson, replayed.row.payloadJson)
                assertEquals(
                    JSONObject(preCloseBytes).getString("requestJson"),
                    replayed.request.requestJson,
                )
            }
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }
}
