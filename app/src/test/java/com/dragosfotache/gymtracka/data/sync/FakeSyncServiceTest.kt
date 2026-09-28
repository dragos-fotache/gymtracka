package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.ChangeOperation
import com.dragosfotache.gymtracka.data.sync.model.MutationOutcomeModel
import com.dragosfotache.gymtracka.data.sync.model.MutationStatus
import com.dragosfotache.gymtracka.data.sync.model.SyncChangeModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.testing.service.FakeAccountSyncApi
import com.dragosfotache.gymtracka.testing.service.FakeSyncService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Scaffold tests for the shared fake sync service: explicit catalogue and
 * type-scoped ownership state, revision counters, outcome recording, log
 * scaffolding, and account-bound recording adapters. Read protocol and
 * command execution semantics are later tasks; these tests pin only the
 * scaffold contract.
 */
class FakeSyncServiceTest {
    companion object {
        const val ACCOUNT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val ACCOUNT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val ROOT_1 = "11111111-1111-1111-1111-111111111111"
        const val ROOT_2 = "22222222-2222-2222-2222-222222222222"
        const val CATALOGUE_PUSH_UP = "44444444-4444-4444-4444-444444444444"
        const val BATCH_1 = "55555555-5555-5555-5555-555555555555"
        const val BATCH_2 = "66666666-6666-6666-6666-666666666666"
        const val BATCH_3 = "88888888-8888-8888-8888-888888888888"
        const val BATCH_4 = "99999999-9999-9999-9999-999999999999"
        const val MUTATION_1 = "77777777-7777-7777-7777-777777777777"
    }

    private fun deleteChange(rootId: String, revision: Long = 1L) = SyncChangeModel(
        aggregateType = AggregateType.WORKOUT,
        aggregateId = rootId,
        operation = ChangeOperation.DELETE,
        revision = revision,
        record = null,
    )

    private fun deterministicUuids() = object {
        // Two UUIDs per appended batch (batch ID plus cursor).
        val ids = ArrayDeque(
            listOf(
                BATCH_1, BATCH_2, BATCH_3, BATCH_4,
                BATCH_1, BATCH_2, BATCH_3, BATCH_4,
            ),
        )
    }

    @Test
    fun catalogueReferencesAreExplicit() {
        val service = FakeSyncService()
        service.seedCatalogue(listOf(FakeSyncService.CatalogueEntry(CATALOGUE_PUSH_UP, "Push-Up")))
        assertTrue(service.isKnownReference(CATALOGUE_PUSH_UP))
        assertFalse(service.isKnownReference(ROOT_1))
    }

    @Test
    fun ownershipIsTypeScopedAndNeverOverwritten() {
        val service = FakeSyncService()
        assertNull(service.ownerOf(AggregateType.WORKOUT, ROOT_1))
        service.registerRoot(AggregateType.WORKOUT, ROOT_1, ACCOUNT_A)
        assertEquals(ACCOUNT_A, service.ownerOf(AggregateType.WORKOUT, ROOT_1))
        assertNull(service.ownerOf(AggregateType.WORKOUT, ROOT_2))

        // Same account re-registration is idempotent.
        service.registerRoot(AggregateType.WORKOUT, ROOT_1, ACCOUNT_A)
        assertEquals(ACCOUNT_A, service.ownerOf(AggregateType.WORKOUT, ROOT_1))

        // The same ID under another aggregate type is a different root.
        service.registerRoot(AggregateType.ROUTINE, ROOT_1, ACCOUNT_B)
        assertEquals(ACCOUNT_B, service.ownerOf(AggregateType.ROUTINE, ROOT_1))
        assertEquals(ACCOUNT_A, service.ownerOf(AggregateType.WORKOUT, ROOT_1))

        try {
            service.registerRoot(AggregateType.WORKOUT, ROOT_1, ACCOUNT_B)
            fail("Expected a different-account owner overwrite to be rejected")
        } catch (e: SyncModelException) {
            assertTrue(e.message!!.contains("already owned"))
        }
        assertEquals(ACCOUNT_A, service.ownerOf(AggregateType.WORKOUT, ROOT_1))
    }

    @Test
    fun revisionCountersArePerAccount() {
        val service = FakeSyncService()
        assertEquals(1L, service.nextRevision(ACCOUNT_A))
        assertEquals(2L, service.nextRevision(ACCOUNT_A))
        assertEquals(1L, service.nextRevision(ACCOUNT_B))
    }

    @Test
    fun outcomesAreRecordedPerAccount() {
        val service = FakeSyncService()
        assertNull(service.replayOutcome(ACCOUNT_A, MUTATION_1))
        val outcome = MutationOutcomeModel(
            mutationId = MUTATION_1,
            status = MutationStatus.REJECTED,
            errorCode = "owned_root_missing",
            batch = null,
        )
        service.recordOutcome(ACCOUNT_A, outcome)
        assertEquals(outcome, service.replayOutcome(ACCOUNT_A, MUTATION_1))
        assertNull(service.replayOutcome(ACCOUNT_B, MUTATION_1))
    }

    @Test
    fun appendedBatchesStayInspectableInOrder() {
        val uuids = deterministicUuids()
        val service = FakeSyncService(uuidProvider = { uuids.ids.removeFirst() })
        assertTrue(service.loggedBatches(ACCOUNT_A).isEmpty())
        val first = service.appendChangeBatch(ACCOUNT_A, listOf(deleteChange(ROOT_1)))
        val second = service.appendChangeBatch(ACCOUNT_A, listOf(deleteChange(ROOT_2)))
        assertEquals(listOf(first, second), service.loggedBatches(ACCOUNT_A))
        assertFalse(first.cursor == second.cursor)
        assertTrue(service.loggedBatches(ACCOUNT_B).isEmpty())
    }

    @Test
    fun invalidSeededBatchIsRejected() {
        val service = FakeSyncService()
        try {
            service.appendChangeBatch(ACCOUNT_A, emptyList())
            fail("Expected an empty seeded batch to be rejected")
        } catch (_: SyncModelException) {
        }
    }

    @Test
    fun adaptersBindAccountsAndRecordCallsSeparately() {
        val service = FakeSyncService()
        val apiA = FakeAccountSyncApi(ACCOUNT_A, service)
        val apiB = FakeAccountSyncApi(ACCOUNT_B, service)
        assertEquals(ACCOUNT_A, apiA.accountId)
        assertEquals(ACCOUNT_B, apiB.accountId)
        assertTrue(apiA.pullCalls.isEmpty())
        assertTrue(apiB.snapshotCalls.isEmpty())
    }
}
