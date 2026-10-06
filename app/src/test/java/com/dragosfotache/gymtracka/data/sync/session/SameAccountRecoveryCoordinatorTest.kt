package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.http.HttpServiceAuthApi
import com.dragosfotache.gymtracka.data.sync.store.PushRunOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R09g independent QA: inert same-account recovery coordinator (JVM).
 *
 * Derived ONLY from docs/archive/remaining-work/R09g-task.md normative prepare/resume/
 * recover contracts plus QA-owned fixtures/doubles (SameAccountRecoveryTestFixtures,
 * FakeSameAccountAuthority, FakeSessionVault, Refresh/Vault fixtures,
 * SessionReauthenticationTestFixtures.ProbeCancellationException,
 * HttpServiceAuthApi + FakeServiceHttpTransport strict-replay helper).
 * No production declarations, diffs, Worker work, generated sources or
 * decompiled classes inspected. Literal independent oracles throughout;
 * deterministic gates only, no sleeps, no DAO mocks.
 *
 * Ordering facts: prepare performs TWO Room revisions (reserve + finalize):
 * ACTIVE 11 -> TRANSITIONING 12 -> PREPARING 13; SIGNED_OUT 10 -> 11 -> 12.
 * Archives append ONLY for uncertain (pending-bearing) sources; settled
 * replacement carries prior archives forward without appending.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SameAccountRecoveryCoordinatorTest {
    private val F = SameAccountRecoveryTestFixtures

    private data class Setup(
        val coordinator: SameAccountRecoveryCoordinator,
        val authority: FakeSameAccountAuthority,
        val vault: FakeSessionVault,
        val runOwner: PushRunOwner,
        val credentialMutex: Mutex,
    )

    private fun setup(
        authoritySnapshot: RecoveryRoomSnapshot = F.signedOut(),
        vaultRecord: VaultSessionRecord? = null,
        vaultRevision: Long = 0L,
        root: String = F.ORIGIN_ROOT,
        runOwner: PushRunOwner = PushRunOwner(),
        credentialMutex: Mutex = Mutex(),
    ): Setup {
        val authority = FakeSameAccountAuthority(authoritySnapshot)
        val vault = F.vaultWith(record = vaultRecord, revision = vaultRevision)
        val coordinator = F.coordinator(
            origin = F.origin(root),
            authority = authority,
            vault = vault,
            runOwner = runOwner,
            credentialMutex = credentialMutex,
        )
        return Setup(coordinator, authority, vault, runOwner, credentialMutex)
    }

    /** Uncertain predecessor: exact pending body/token, generation below the target. */
    private fun uncertainSource(
        generation: Long = 6L,
        operationId: String = F.OP_OLD,
        refreshValue: String = "refresh-old",
        key: String = F.KEY_A,
    ): VaultSessionRecord = F.activeRecord(
        generation = generation,
        operationId = operationId,
        session = F.session(refreshValue = refreshValue),
        pendingRefresh = F.pending(operationId = operationId, key = key, refreshValue = refreshValue),
    )

    private class CommitThenThrowVault(
        private val delegate: FakeSessionVault,
    ) : SessionVault {
        var wrapperCommits = 0
            private set
        override suspend fun load(): VaultSnapshot = delegate.load()
        override suspend fun compareAndSet(expectedRevision: Long, record: VaultSessionRecord?): VaultSnapshot {
            val committed = delegate.compareAndSet(expectedRevision, record)
            wrapperCommits += 1
            throw SessionVaultException(VaultFailure.UNAVAILABLE)
        }
    }

    private class CommitHoldAtEntryVault(
        private val delegate: FakeSessionVault,
    ) : SessionVault {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var releasedCommits = 0
            private set
        override suspend fun load(): VaultSnapshot = delegate.load()
        override suspend fun compareAndSet(expectedRevision: Long, record: VaultSessionRecord?): VaultSnapshot {
            entered.complete(Unit)
            release.await()
            releasedCommits += 1
            return delegate.compareAndSet(expectedRevision, record)
        }
    }

    // ---- Construction + happy paths (two Room revisions) ----

    @Test
    fun constructionPerformsNoIo() = runTest {
        val s = setup()
        assertEquals(0, s.authority.reads)
        assertEquals(0, s.vault.loads)
        assertEquals(0, s.vault.commits)
        assertEquals(0, s.authority.beginSames)
    }

    @Test
    fun activeMissingVaultReachesPrivatePreparationWithFreshGrant() = runTest {
        val expected = F.activeSnapshot(revision = 11L, generation = 7L, baselineComplete = false)
        val s = setup(authoritySnapshot = expected, vaultRecord = null, vaultRevision = 0L)
        val observation = s.coordinator.prepare(expected, F.OP_FRESH, F.freshGrant())
        // Reserve 11->12 TRANSITIONING then finalize 12->13 PREPARING.
        assertEquals(13L, observation.room.revision)
        assertEquals(8L, observation.room.sessionGeneration)
        assertEquals(RecoveryRoomPhase.PREPARING, observation.room.phase)
        assertEquals(1L, observation.vaultRevision)
        val stored = s.vault.current()
        assertEquals(1L, stored.revision)
        assertEquals(VaultSessionPhase.PREPARED, stored.record?.phase)
        assertEquals(F.freshGrant(), stored.record?.session)
        assertEquals(F.binding(generation = 8L), stored.record?.binding)
        assertTrue(stored.record?.supersededSessions?.entries.isNullOrEmpty())
        assertEquals(13L, s.authority.durableSnapshot().revision)
    }

    @Test
    fun retainedSignedOutUncertainSourceArchivesExactlyOnce() = runTest {
        val expected = F.signedOut(revision = 10L, cachedAccountId = F.ACCOUNT_ID)
        // Retained SIGNED_OUT at generation 7 admits only strictly lower generations.
        val source = uncertainSource(generation = 6L)
        val s = setup(authoritySnapshot = expected, vaultRecord = source, vaultRevision = 5L)
        val observation = s.coordinator.prepare(expected, F.OP_FRESH, F.freshGrant())
        // Reserve 10->11 then finalize 11->12.
        assertEquals(12L, observation.room.revision)
        assertEquals(8L, observation.room.sessionGeneration)
        assertEquals(RecoveryRoomPhase.PREPARING, observation.room.phase)
        assertEquals(6L, s.vault.current().revision)
        val stored = s.vault.current().record
        requireNotNull(stored)
        assertEquals(F.freshGrant(), stored.session)
        val entries = stored.supersededSessions.entries
        assertEquals(1, entries.size)
        assertEquals(5L, entries.single().sourceRevision)
        F.assertSemanticRecordEqual(source, entries.single().record)
        assertTrue(entries.single().record.supersededSessions.entries.isEmpty())
    }

    @Test
    fun settledSourceReplacementCarriesPriorArchiveWithoutAppending() = runTest {
        val expected = F.activeSnapshot(revision = 11L, generation = 7L, baselineComplete = false)
        // Settled (no pending) source already carrying one valid prior archive.
        val prior = uncertainSource(generation = 5L, operationId = "44444444-4444-4444-4444-444444444444")
        val settled = F.activeRecord(
            generation = 7L,
            operationId = F.OP_OLD,
            session = F.session(refreshValue = "refresh-settled-7"),
        ).copy(
            supersededSessions = VaultSupersededSessions(
                listOf(VaultSupersededSession(sourceRevision = 4L, record = prior)),
            ),
        )
        val s = setup(authoritySnapshot = expected, vaultRecord = settled, vaultRevision = 5L)
        val observation = s.coordinator.prepare(expected, F.OP_FRESH, F.freshGrant())
        assertEquals(13L, observation.room.revision)
        val stored = s.vault.current().record
        requireNotNull(stored)
        assertEquals(settled.supersededSessions, stored.supersededSessions)
        assertEquals(1, stored.supersededSessions.entries.size)
        assertEquals(4L, stored.supersededSessions.entries.single().sourceRevision)
    }

    @Test
    fun failedPendingSourceRequiresDifferentSuppliedTokenAndArchives() = runTest {
        val expected = F.activeSnapshot(revision = 11L, generation = 7L, baselineComplete = false)
        val source = uncertainSource(generation = 7L)
        val s = setup(authoritySnapshot = expected, vaultRecord = source, vaultRevision = 5L)
        try {
            s.coordinator.prepare(expected, F.OP_FRESH, F.session(refreshValue = "refresh-old"))
            fail("same refresh token must not supersede an uncertain source")
        } catch (e: SessionReauthenticationException) {
            assertEquals(SessionReauthenticationFailure.INVALID_INPUT, e.failure)
        }
        assertEquals(5L, s.vault.current().revision)
        assertEquals(0, s.vault.commits)
        val observation = s.coordinator.prepare(expected, F.OP_FRESH, F.freshGrant())
        assertEquals(13L, observation.room.revision)
        assertEquals(6L, observation.vaultRevision)
        assertEquals(1, s.vault.current().record?.supersededSessions?.entries?.size)
    }

    @Test
    fun strictLegacyReplayStaysMalformedThenFreshGrantSupersedesOnSameStores() = runTest {
        // SAME stores/owners throughout: the uncertain source, vault,
        // authority and shared owners are constructed first. Actual strict
        // replay runs through the QA-owned R09f path — shared refresh
        // coordinator plus the real HttpServiceAuthApi over scripted
        // transport — against an ACTIVE baseline-complete snapshot.
        val expected = F.activeSnapshot(revision = 11L, generation = 7L, baselineComplete = true)
        val source = uncertainSource(generation = 7L)
        val vault = F.vaultWith(record = source, revision = 5L)
        val authority = FakeSameAccountAuthority(expected)
        val runOwner = PushRunOwner()
        val credentialMutex = Mutex()
        val transport = FakeServiceHttpTransport()
        transport.queueOk(RefreshTestFixtures.legacyGrantBody())
        val authApi = HttpServiceAuthApi(origin = F.origin(), transport = transport)
        val refresh = SessionRefreshCoordinator(
            origin = F.origin(),
            vault = vault,
            authApi = authApi,
            clockMillis = { throw AssertionError("R09g replay must not read the clock") },
            uuidProvider = { throw AssertionError("R09g replay must not mint UUIDs") },
            mutex = credentialMutex,
        )
        val reauth = SessionReauthenticationTestFixtures.coordinator(
            origin = F.origin(),
            authority = authority,
            vault = vault,
            runOwner = runOwner,
            credentialMutex = credentialMutex,
        )
        try {
            reauth.reconcilePendingRefresh(expected, refresh)
            fail("legacy precision tail must stay MalformedResponse")
        } catch (e: SyncApiException.MalformedResponse) {
            assertNull(e.cause)
        }
        // Exactly ONE exact HTTP body plus the exact idempotency key; the
        // original vault record and Room tuple stand unchanged.
        assertEquals(1, transport.capturedRequests.size)
        assertEquals(source.pendingRefresh?.attempt?.requestJson, transport.capturedRequests.single().body)
        assertTrue(transport.capturedRequests.single().headers.values.contains(F.KEY_A))
        assertEquals(5L, vault.current().revision)
        F.assertSemanticRecordEqual(source, vault.current().record!!)
        assertEquals(expected, authority.durableSnapshot())
        assertEquals(0, authority.beginSames)
        assertEquals(0, vault.commits)
        // Explicit fresh-grant supersession on those SAME stores/owners:
        // no permissive decode, no old-token rekey, exact evidence archived.
        val recovery = F.coordinator(
            origin = F.origin(),
            authority = authority,
            vault = vault,
            runOwner = runOwner,
            credentialMutex = credentialMutex,
        )
        val observation = recovery.prepare(expected, F.OP_FRESH, F.freshGrant())
        assertEquals(13L, observation.room.revision)
        assertEquals(6L, observation.vaultRevision)
        val stored = vault.current().record
        requireNotNull(stored)
        assertEquals(F.freshGrant(), stored.session)
        val entries = stored.supersededSessions.entries
        assertEquals(1, entries.size)
        F.assertSemanticRecordEqual(source, entries.single().record)
    }

    @Test
    fun transitioningAdjacentUncertainPredecessorResumesWithArchive() = runTest {
        val transitioning = F.transitioningSnapshot(revision = 12L, generation = 8L)
        val predecessor = uncertainSource(generation = 7L)
        val s = setup(authoritySnapshot = transitioning, vaultRecord = predecessor, vaultRevision = 5L)
        val observation = s.coordinator.resume(transitioning, F.freshGrant())
        assertEquals(13L, observation.room.revision)
        assertEquals(RecoveryRoomPhase.PREPARING, observation.room.phase)
        assertEquals(6L, observation.vaultRevision)
        assertEquals(1, s.vault.current().record?.supersededSessions?.entries?.size)
    }

    @Test
    fun olderNonadjacentSourceIsEligibleEvidenceWithSingleArchive() = runTest {
        // Strictly older same-origin/same-account different-operation sources
        // are evidence only: recover needs the fresh grant, resume installs it
        // at the exact existing op/gen with one appended archive.
        val transitioning = F.transitioningSnapshot(revision = 12L, generation = 8L)
        val older = uncertainSource(generation = 5L, operationId = F.OP_OLD)
        val s = setup(authoritySnapshot = transitioning, vaultRecord = older, vaultRevision = 5L)
        val recovery = s.coordinator.recover()
        assertTrue(recovery is SameAccountRecovery.NeedsGrant)
        assertEquals(transitioning, (recovery as SameAccountRecovery.NeedsGrant).room)
        val observation = s.coordinator.resume(transitioning, F.freshGrant())
        assertEquals(13L, observation.room.revision)
        assertEquals(RecoveryRoomPhase.PREPARING, observation.room.phase)
        assertEquals(6L, observation.vaultRevision)
        val stored = s.vault.current().record
        requireNotNull(stored)
        assertEquals(F.freshGrant(), stored.session)
        assertEquals(F.binding(generation = 8L), stored.binding)
        assertEquals(F.OP_FRESH, stored.operationId)
        val entries = stored.supersededSessions.entries
        assertEquals(1, entries.size)
        assertEquals(5L, entries.single().sourceRevision)
        F.assertSemanticRecordEqual(older, entries.single().record)
        val commitsAfterFirst = s.vault.commits
        val second = s.coordinator.resume(observation.room, F.freshGrant())
        assertEquals(6L, second.vaultRevision)
        assertEquals(1, s.vault.current().record?.supersededSessions?.entries?.size)
        assertEquals(commitsAfterFirst, s.vault.commits)
    }

    @Test
    fun olderSettledSourceTokenReuseRefusesButDistinctGrantResumes() = runTest {
        // Explicit older-evidence guard independent of any archived-token
        // case: gen5 settled source (no pending, empty archive) under a
        // private gen8 reservation. Reusing the older settled token refuses;
        // a distinct fresh grant resumes cleanly with no archive fabricated.
        val transitioning = F.transitioningSnapshot(revision = 12L, generation = 8L)
        val settledOlder = F.activeRecord(
            generation = 5L,
            operationId = F.OP_OLD,
            session = F.session(),
            pendingRefresh = null,
        )
        val s = setup(authoritySnapshot = transitioning, vaultRecord = settledOlder, vaultRevision = 5L)
        F.assertRecoveryFailure(SessionReauthenticationFailure.IDENTITY_MISMATCH) {
            s.coordinator.resume(transitioning, F.session())
        }
        assertEquals(5L, s.vault.current().revision)
        assertEquals(0, s.vault.commits)
        val observation = s.coordinator.resume(transitioning, F.freshGrant())
        assertEquals(13L, observation.room.revision)
        assertEquals(6L, observation.vaultRevision)
        val stored = s.vault.current().record
        requireNotNull(stored)
        assertEquals(F.freshGrant(), stored.session)
        assertTrue(stored.supersededSessions.entries.isEmpty())
    }

    @Test
    fun installedTargetFinalizesTransitioningAndIdempotentPreparingNeedsNoCas() = runTest {
        val transitioning = F.transitioningSnapshot(revision = 12L, generation = 8L)
        val target = F.preparedTargetRecord()
        val s = setup(authoritySnapshot = transitioning, vaultRecord = target, vaultRevision = 6L)
        val commitsBefore = s.vault.commits
        val observation = s.coordinator.resume(transitioning, F.freshGrant())
        assertEquals(13L, observation.room.revision)
        assertEquals(6L, observation.vaultRevision)
        assertEquals(commitsBefore, s.vault.commits)

        val preparing = F.preparingSnapshot(revision = 13L, generation = 8L)
        s.authority.seedDurable(preparing)
        val loadsBefore = s.vault.loads
        val second = s.coordinator.resume(preparing, F.freshGrant())
        assertEquals(13L, second.room.revision)
        assertEquals(6L, second.vaultRevision)
        assertEquals(commitsBefore, s.vault.commits)
        assertTrue(s.vault.loads >= loadsBefore)
    }

    @Test
    fun resumePreflightsVaultAndFinalizationOverflowBeforeAnyWrite() = runTest {
        val transitioning = F.transitioningSnapshot(revision = 12L, generation = 8L)
        val predecessor = uncertainSource(generation = 7L)
        val fullVault = F.vaultWith(record = predecessor, revision = Long.MAX_VALUE)
        val authority = FakeSameAccountAuthority(transitioning)
        val overflowCoordinator = F.coordinator(authority = authority, vault = fullVault)
        try {
            overflowCoordinator.resume(transitioning, F.freshGrant())
            fail("vault increment overflow must refuse before CAS")
        } catch (e: SessionReauthenticationException) {
            assertEquals(SessionReauthenticationFailure.COUNTER_OVERFLOW, e.failure)
        }
        assertEquals(0, fullVault.commits)

        val maxRoom = transitioning.copy(revision = Long.MAX_VALUE)
        val s2 = setup(authoritySnapshot = maxRoom, vaultRecord = predecessor, vaultRevision = 5L)
        try {
            s2.coordinator.resume(maxRoom, F.freshGrant())
            fail("finalization overflow must refuse without target write")
        } catch (e: SessionReauthenticationException) {
            assertEquals(SessionReauthenticationFailure.COUNTER_OVERFLOW, e.failure)
        }
        assertEquals(0, s2.vault.commits)
    }

    @Test
    fun nonPrivateResumeInputRefusesAsInvalidInput() = runTest {
        val s = setup(authoritySnapshot = F.signedOut(), vaultRecord = null, vaultRevision = 0L)
        F.assertRecoveryFailure(SessionReauthenticationFailure.INVALID_INPUT) {
            s.coordinator.resume(F.signedOut(), F.freshGrant())
        }
        val sActive = setup(
            authoritySnapshot = F.activeSnapshot(baselineComplete = true),
            vaultRecord = F.activeRecord(),
            vaultRevision = 5L,
        )
        F.assertRecoveryFailure(SessionReauthenticationFailure.INVALID_INPUT) {
            sActive.coordinator.resume(F.activeSnapshot(baselineComplete = true), F.freshGrant())
        }
        assertEquals(0, s.vault.commits)
        assertEquals(0, sActive.vault.commits)
    }

    @Test
    fun archivedTokenReuseRefusesAsIdentityMismatch() = runTest {
        val expected = F.activeSnapshot(revision = 11L, generation = 7L, baselineComplete = false)
        // Settled source carrying an archive of refresh-old; the supplied
        // grant reuses that archived token under a fresh operation.
        val archived = uncertainSource(generation = 5L, operationId = "44444444-4444-4444-4444-444444444444")
        val settled = F.activeRecord(
            generation = 7L,
            operationId = F.OP_OLD,
            session = F.session(refreshValue = "refresh-settled-7"),
        ).copy(
            supersededSessions = VaultSupersededSessions(
                listOf(VaultSupersededSession(sourceRevision = 4L, record = archived)),
            ),
        )
        val s = setup(authoritySnapshot = expected, vaultRecord = settled, vaultRevision = 5L)
        F.assertRecoveryFailure(SessionReauthenticationFailure.IDENTITY_MISMATCH) {
            s.coordinator.prepare(expected, F.OP_FRESH, F.session(refreshValue = "refresh-old"))
        }
        assertEquals(5L, s.vault.current().revision)
        assertEquals(0, s.vault.commits)
    }

    @Test
    fun fullSourceVaultDriftMapsVaultConflict() = runTest {
        val expected = F.activeSnapshot(revision = 11L, generation = 7L, baselineComplete = false)
        val inner = F.vaultWith(record = uncertainSource(generation = 7L), revision = 5L)
        // Hand the coordinator a stale-but-stable vault view: the first load
        // advances the durable vault underneath, every load returns the stale
        // snapshot, and the coordinator's single CAS then misses the revision.
        var advanced = false
        val staleVault = object : SessionVault {
            var stale: VaultSnapshot? = null
            override suspend fun load(): VaultSnapshot {
                val fresh = inner.load()
                if (!advanced) {
                    advanced = true
                    inner.compareAndSet(
                        fresh.revision,
                        uncertainSource(
                            generation = 7L,
                            operationId = "66666666-6666-6666-6666-666666666666",
                        ),
                    )
                    stale = fresh
                }
                return stale!!
            }
            override suspend fun compareAndSet(expectedRevision: Long, record: VaultSessionRecord?): VaultSnapshot =
                inner.compareAndSet(expectedRevision, record)
        }
        val authority = FakeSameAccountAuthority(expected)
        val coordinator = F.coordinator(authority = authority, vault = staleVault)
        F.assertRecoveryFailure(SessionReauthenticationFailure.VAULT_CONFLICT) {
            coordinator.prepare(expected, F.OP_FRESH, F.freshGrant())
        }
        assertTrue(advanced)
        // Reservation happened; no compensation, no finalization.
        assertEquals(RecoveryRoomPhase.TRANSITIONING, authority.durableSnapshot().phase)
        assertEquals(0, authority.finishes)
        assertEquals(6L, inner.current().revision)
    }

    @Test
    fun wrongGrantForeignNonadjacentOrphanRefuse() = runTest {
        val transitioning = F.transitioningSnapshot()
        val target = F.preparedTargetRecord()
        val s = setup(authoritySnapshot = transitioning, vaultRecord = target, vaultRevision = 6L)
        F.assertRecoveryFailure(SessionReauthenticationFailure.IDENTITY_MISMATCH) {
            s.coordinator.resume(transitioning, F.otherGrant())
        }
        val foreignSession = F.session(accountId = F.OTHER_ACCOUNT_ID)
        val foreign = F.preparedTargetRecord(
            root = F.OTHER_ORIGIN_ROOT,
            accountId = F.OTHER_ACCOUNT_ID,
            session = foreignSession,
        )
        val sf = setup(authoritySnapshot = transitioning, vaultRecord = foreign, vaultRevision = 6L)
        F.assertRecoveryFailure(SessionReauthenticationFailure.IDENTITY_MISMATCH) {
            sf.coordinator.resume(transitioning, F.freshGrant())
        }
        // Same-generation different-operation and newer-generation sources
        // are incompatible: neither the installed target nor an older
        // predecessor. Strictly older sources are eligible evidence instead.
        val sameGen = uncertainSource(generation = 8L, operationId = F.OP_OLD)
        val sg = setup(authoritySnapshot = transitioning, vaultRecord = sameGen, vaultRevision = 5L)
        F.assertRecoveryFailure(SessionReauthenticationFailure.IDENTITY_MISMATCH) {
            sg.coordinator.resume(transitioning, F.freshGrant())
        }
        assertEquals(0, sg.vault.commits)
        val newer = uncertainSource(generation = 9L, operationId = F.OP_OLD)
        val sn = setup(authoritySnapshot = transitioning, vaultRecord = newer, vaultRevision = 5L)
        F.assertRecoveryFailure(SessionReauthenticationFailure.IDENTITY_MISMATCH) {
            sn.coordinator.resume(transitioning, F.freshGrant())
        }
        assertEquals(0, sn.vault.commits)
        val orphan = F.activeRecord(
            root = F.OTHER_ORIGIN_ROOT,
            accountId = F.OTHER_ACCOUNT_ID,
            session = F.session(accountId = F.OTHER_ACCOUNT_ID),
        )
        val so = setup(authoritySnapshot = transitioning, vaultRecord = orphan, vaultRevision = 5L)
        F.assertRecoveryFailure(SessionReauthenticationFailure.IDENTITY_MISMATCH) {
            so.coordinator.resume(transitioning, F.freshGrant())
        }
        assertEquals(6L, s.vault.current().revision)
        assertEquals(0, s.vault.commits)
    }

    @Test
    fun noCacheUnknownProvenanceLocalRootsAndOverflowRefuseBeforeReservation() = runTest {
        // No cached account: not retained-account recovery (distinct input class).
        val noCache = F.signedOut(revision = 10L, cachedAccountId = null)
        val s = setup(authoritySnapshot = noCache, vaultRecord = null, vaultRevision = 0L)
        F.assertRecoveryFailure(SessionReauthenticationFailure.INVALID_INPUT) {
            s.coordinator.prepare(noCache, F.OP_FRESH, F.freshGrant())
        }
        assertEquals(0, s.vault.commits)
        assertEquals(0, s.authority.beginSames)

        // Unknown provenance (foreign configured origin) refuses closed.
        val retained = F.signedOut()
        val su = setup(authoritySnapshot = retained, vaultRecord = null, vaultRevision = 0L, root = F.OTHER_ORIGIN_ROOT)
        F.assertRecoveryFailure(SessionReauthenticationFailure.IDENTITY_MISMATCH) {
            su.coordinator.prepare(retained, F.OP_FRESH, F.freshGrant())
        }
        assertEquals(0, su.vault.commits)
        assertEquals(0, su.authority.beginSames)

        // Missing retained provenance refuses even at the matching origin:
        // provenance is stored state, never inferred from the vault.
        val sMissing = setup(authoritySnapshot = retained, vaultRecord = null, vaultRevision = 0L)
        sMissing.authority.unknownProvenance = true
        F.assertRecoveryFailure(SessionReauthenticationFailure.IDENTITY_MISMATCH) {
            sMissing.coordinator.prepare(retained, F.OP_FRESH, F.freshGrant())
        }
        assertEquals(0, sMissing.vault.commits)

        // Different stored provenance refuses at any supplied origin.
        val sd = setup(authoritySnapshot = retained, vaultRecord = null, vaultRevision = 0L)
        sd.authority.retainedOrigin = F.origin(F.OTHER_ORIGIN_ROOT)
        F.assertRecoveryFailure(SessionReauthenticationFailure.IDENTITY_MISMATCH) {
            sd.coordinator.prepare(retained, F.OP_FRESH, F.freshGrant())
        }
        assertEquals(0, sd.vault.commits)

        // Local-only roots map Room LOCAL_DATA_REQUIRES_DECISION to the
        // same-named reauthentication category (R09e closed mapping).
        val sl = setup(authoritySnapshot = retained, vaultRecord = null, vaultRevision = 0L)
        sl.authority.hasLocalRoots = true
        F.assertRecoveryFailure(SessionReauthenticationFailure.LOCAL_DATA_REQUIRES_DECISION) {
            sl.coordinator.prepare(retained, F.OP_FRESH, F.freshGrant())
        }
        assertEquals(0, sl.vault.commits)

        val maxed = F.signedOut(revision = Long.MAX_VALUE - 1)
        val sm = setup(authoritySnapshot = maxed, vaultRecord = null, vaultRevision = 0L)
        F.assertRecoveryFailure(SessionReauthenticationFailure.COUNTER_OVERFLOW) {
            sm.coordinator.prepare(maxed, F.OP_FRESH, F.freshGrant())
        }
        assertEquals(0, sm.vault.commits)
    }

    @Test
    fun invalidInputDoesNotSupersedeValidInflightWork() = runTest {
        val expected = F.activeSnapshot(revision = 11L, generation = 7L, baselineComplete = false)
        val runOwner = PushRunOwner()
        val credentialMutex = Mutex()
        val authority = FakeSameAccountAuthority(expected)
        val vault = F.vaultWith(record = null, revision = 0L)
        val coordinator = F.coordinator(authority = authority, vault = vault, runOwner = runOwner, credentialMutex = credentialMutex)
        // Hold the valid invocation at the deterministic reservation seam.
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        authority.beginSameGate = { args ->
            if (args.operationId == F.OP_FRESH) {
                entered.complete(Unit)
                release.await()
            }
        }
        supervisorScope {
            val valid = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.prepare(expected, F.OP_FRESH, F.freshGrant())
            }
            entered.await()
            // Invalid input while valid work is in flight refuses immediately
            // without disturbing the held reservation path.
            F.assertRecoveryFailure(SessionReauthenticationFailure.INVALID_INPUT) {
                coordinator.prepare(expected, "not-a-uuid", F.freshGrant())
            }
            assertEquals(0, vault.commits)
            release.complete(Unit)
            val observation = valid.await()
            assertEquals(13L, observation.room.revision)
            assertEquals(1L, observation.vaultRevision)
            assertEquals(F.freshGrant(), vault.current().record?.session)
        }
    }

    @Test
    fun postReservationRoomDriftAfterVaultLoadRefusesWithoutCas() = runTest {
        val expected = F.activeSnapshot(revision = 11L, generation = 7L, baselineComplete = false)
        val s = setup(authoritySnapshot = expected, vaultRecord = null, vaultRevision = 0L)
        var vaultLoadsObserved = 0
        val delegate = s.vault
        var driftArmed = false
        val observingVault = object : SessionVault {
            override suspend fun load(): VaultSnapshot {
                val snapshot = delegate.load()
                vaultLoadsObserved += 1
                // After the post-reservation vault load, drift Room before the
                // next check: the reservation happened but the CAS must not.
                if (!driftArmed && s.authority.durableSnapshot().phase == RecoveryRoomPhase.TRANSITIONING) {
                    driftArmed = true
                    s.authority.seedDurable(expected.copy(revision = 99L))
                }
                return snapshot
            }
            override suspend fun compareAndSet(expectedRevision: Long, record: VaultSessionRecord?): VaultSnapshot =
                delegate.compareAndSet(expectedRevision, record)
        }
        val coordinator = F.coordinator(
            authority = s.authority, vault = observingVault,
            runOwner = s.runOwner, credentialMutex = s.credentialMutex,
        )
        F.assertRecoveryFailure(SessionReauthenticationFailure.STALE_AUTHORITY) {
            coordinator.prepare(expected, F.OP_FRESH, F.freshGrant())
        }
        assertTrue("reservation must have happened before the drift", s.authority.beginSames == 1)
        assertTrue("drift must have been injected after a vault load", driftArmed && vaultLoadsObserved >= 1)
        assertEquals(0, delegate.commits)
        assertEquals(0L, delegate.current().revision)
    }

    @Test
    fun lostReturnCommitsTargetThenRecoverNeedsFinalizationAndResumesWithoutSecondCas() = runTest {
        val expected = F.activeSnapshot(revision = 11L, generation = 7L, baselineComplete = false)
        val inner = F.vaultWith(record = null, revision = 0L)
        val throwing = CommitThenThrowVault(inner)
        val authority = FakeSameAccountAuthority(expected)
        val coordinator = F.coordinator(authority = authority, vault = throwing)
        try {
            coordinator.prepare(expected, F.OP_FRESH, F.freshGrant())
            fail("lost return must report failure")
        } catch (e: SessionReauthenticationException) {
            assertEquals(SessionReauthenticationFailure.VAULT_UNAVAILABLE, e.failure)
        }
        // CAS-only commit landed; Room reservation is durable TRANSITIONING.
        assertEquals(1L, inner.current().revision)
        assertEquals(F.freshGrant(), inner.current().record?.session)
        assertEquals(RecoveryRoomPhase.TRANSITIONING, authority.durableSnapshot().phase)
        val retry = F.coordinator(authority = authority, vault = inner)
        val reread = retry.recover()
        assertTrue("lost-return restart must be exact NeedsFinalization", reread is SameAccountRecovery.NeedsFinalization)
        assertEquals(1L, (reread as SameAccountRecovery.NeedsFinalization).vaultRevision)
        val commitsBeforeResume = inner.commits
        val observation = retry.resume(authority.durableSnapshot(), F.freshGrant())
        assertEquals(13L, observation.room.revision)
        assertEquals(1L, observation.vaultRevision)
        assertEquals(commitsBeforeResume, inner.commits)
        assertTrue(inner.current().record?.supersededSessions?.entries.isNullOrEmpty())
    }

    @Test
    fun newestInvalidationFencesLateResultWithSharedInstances() = runTest {
        val expected = F.activeSnapshot(revision = 11L, generation = 7L, baselineComplete = false)
        val authority = FakeSameAccountAuthority(expected)
        val inner = F.vaultWith(record = null, revision = 0L)
        val holding = CommitHoldAtEntryVault(inner)
        val runOwner = PushRunOwner()
        val credentialMutex = Mutex()
        // Shared actual instances: the coordinator under test operates on the
        // same authority/vault/owners the assertions observe.
        val coordinator = F.coordinator(
            authority = authority, vault = holding, runOwner = runOwner, credentialMutex = credentialMutex,
        )
        supervisorScope {
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    coordinator.prepare(expected, F.OP_FRESH, F.freshGrant())
                    "no-throw"
                } catch (e: Throwable) {
                    e
                }
            }
            holding.entered.await()
            coordinator.invalidate()
            holding.release.complete(Unit)
            val outcome = job.await()
            assertTrue(outcome is SessionReauthenticationException)
            assertEquals(
                SessionReauthenticationFailure.STALE_OPERATION,
                (outcome as SessionReauthenticationException).failure,
            )
        }
        // The already-started CAS may have committed (allowed durable effect);
        // invalidation guarantees no finalization and no reported success.
        assertEquals(0, authority.finishes)
        assertEquals(RecoveryRoomPhase.TRANSITIONING, authority.durableSnapshot().phase)
        assertTrue(inner.current().revision == 0L || inner.current().revision == 1L)
        if (inner.current().revision == 1L) {
            assertEquals(F.freshGrant(), inner.current().record?.session)
        }
    }

    @Test
    fun callerCancellationDominatesInvalidationAndDriftWithExactIdentity() = runTest {
        val expected = F.activeSnapshot(revision = 11L, generation = 7L, baselineComplete = false)
        val s = setup(authoritySnapshot = expected, vaultRecord = null, vaultRevision = 0L)
        // Actual entered/release seam in the authority read: UNDISPATCHED
        // alone is not a seam, and runCurrent proves nothing here.
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        s.authority.readGate = {
            entered.complete(Unit)
            release.await()
        }
        val probe = SessionReauthenticationTestFixtures.ProbeCancellationException("cancel-r09g")
        val done = CompletableDeferred<Throwable>()
        supervisorScope {
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    s.coordinator.prepare(expected, F.OP_FRESH, F.freshGrant())
                    done.complete(AssertionError("expected cancellation"))
                } catch (e: Throwable) {
                    done.complete(e)
                }
            }
            entered.await()
            assertFalse("child must be parked at the read seam", job.isCompleted)
            job.cancel(probe)
            s.coordinator.invalidate()
            s.authority.seedDurable(expected.copy(revision = 99L))
            release.complete(Unit)
            assertSame(probe, done.await())
            job.join()
        }
        assertEquals(0, s.vault.commits)
        assertEquals(0, s.authority.beginSames)
    }

    @Test
    fun queuedOwnerAndCredentialContentionBlockThenReleaseReadmits() = runTest {
        val expected = F.activeSnapshot(revision = 11L, generation = 7L, baselineComplete = false)
        val runOwner = PushRunOwner()
        val credentialMutex = Mutex()
        val s = setup(
            authoritySnapshot = expected, vaultRecord = null, vaultRevision = 0L,
            runOwner = runOwner, credentialMutex = credentialMutex,
        )
        for (held in listOf(runOwner.mutex, credentialMutex)) {
            held.lock()
            try {
                supervisorScope {
                    val done = CompletableDeferred<Throwable>()
                    val job = async(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            s.coordinator.prepare(expected, F.OP_FRESH, F.freshGrant())
                            done.complete(AssertionError("queued call must not complete"))
                        } catch (e: Throwable) {
                            done.complete(e)
                        }
                    }
                    runCurrent()
                    runCurrent()
                    assertFalse(job.isCompleted)
                    // Non-copying probe: exact identity survives stack recovery.
                    val probe = SessionReauthenticationTestFixtures.ProbeCancellationException("queued-cancel-r09g")
                    job.cancel(probe)
                    assertSame(probe, done.await())
                    job.join()
                    assertEquals(0, s.vault.commits)
                }
            } finally {
                held.unlock()
            }
        }
        val observation = s.coordinator.prepare(s.authority.durableSnapshot(), F.OP_FRESH, F.freshGrant())
        assertEquals(13L, observation.room.revision)
        assertEquals(1L, observation.vaultRevision)
    }

    @Test
    fun resumeSameOperationNeverArchivesTwice() = runTest {
        val transitioning = F.transitioningSnapshot()
        val predecessor = uncertainSource(generation = 7L, operationId = F.OP_OLD)
        val s = setup(authoritySnapshot = transitioning, vaultRecord = predecessor, vaultRevision = 5L)
        val first = s.coordinator.resume(transitioning, F.freshGrant())
        assertEquals(13L, first.room.revision)
        assertEquals(1, s.vault.current().record?.supersededSessions?.entries?.size)
        val commitsAfterFirst = s.vault.commits
        val preparing = first.room
        val second = s.coordinator.resume(preparing, F.freshGrant())
        assertEquals(1, s.vault.current().record?.supersededSessions?.entries?.size)
        assertEquals(first.vaultRevision, second.vaultRevision)
        assertEquals(commitsAfterFirst, s.vault.commits)
    }

    // ---- Read-only recover ----

    @Test
    fun recoverClassifiesWithoutWriting() = runTest {
        val bare = setup(authoritySnapshot = F.signedOut(revision = 0L, cachedAccountId = null))
        assertTrue(bare.coordinator.recover() is SameAccountRecovery.NoRetainedAccount)
        assertEquals(0, bare.vault.commits)

        val eligible = setup(
            authoritySnapshot = F.signedOut(),
            vaultRecord = uncertainSource(generation = 6L),
            vaultRevision = 5L,
        )
        val eligibleResult = eligible.coordinator.recover()
        assertTrue(eligibleResult is SameAccountRecovery.EligibleForReplacement)
        assertEquals(0, eligible.vault.commits)

        val missing = setup(
            authoritySnapshot = F.transitioningSnapshot(),
            vaultRecord = null,
            vaultRevision = 0L,
        )
        assertTrue(missing.coordinator.recover() is SameAccountRecovery.NeedsGrant)

        val target = F.preparedTargetRecord()
        val needsFinal = setup(
            authoritySnapshot = F.transitioningSnapshot(),
            vaultRecord = target,
            vaultRevision = 6L,
        )
        val finalResult = needsFinal.coordinator.recover()
        assertTrue(finalResult is SameAccountRecovery.NeedsFinalization)
        assertEquals(6L, (finalResult as SameAccountRecovery.NeedsFinalization).vaultRevision)

        val prepared = setup(
            authoritySnapshot = F.preparingSnapshot(),
            vaultRecord = target,
            vaultRevision = 6L,
        )
        assertTrue(prepared.coordinator.recover() is SameAccountRecovery.Prepared)

        val foreign = setup(
            authoritySnapshot = F.activeSnapshot(root = F.OTHER_ORIGIN_ROOT),
            vaultRecord = F.activeRecord(),
            vaultRevision = 5L,
            root = F.ORIGIN_ROOT,
        )
        val blocked = foreign.coordinator.recover()
        assertTrue(blocked is SameAccountRecovery.Blocked)
        val rendered = blocked.toString()
        F.assertRedacted(rendered, F.defaultSecrets())
    }

    @Test
    fun selectedActiveStillRequiresStartupAdmission() = runTest {
        val active = F.activeSnapshot(baselineComplete = false)
        val s = setup(authoritySnapshot = active, vaultRecord = null, vaultRevision = 0L)
        val result = s.coordinator.recover()
        assertTrue(result is SameAccountRecovery.EligibleForReplacement)
        assertEquals(0, s.vault.commits)
    }

    @Test
    fun ordinarySelectedRefreshPreservesArchiveWithExactTransportEvidence() = runTest {
        // Installed archive-bearing target whose grant is forced expired so the
        // selected-session refresh path actually rotates it.
        val archivedSource = uncertainSource(generation = 6L)
        val expiredTarget = F.preparedTargetRecord(
            session = F.session(
                accessValue = "access-target",
                accessExpiresAtMillis = F.NOW - 1_000L,
                refreshValue = "refresh-target",
                refreshExpiresAtMillis = F.NOW + RefreshTestFixtures.THIRTY_DAYS,
            ),
        ).copy(
            supersededSessions = VaultSupersededSessions(
                listOf(VaultSupersededSession(sourceRevision = 5L, record = archivedSource)),
            ),
        )
        val vault = F.vaultWith(record = expiredTarget, revision = 6L)
        val authApi = FakeServiceAuthApi()
        val rotated = F.session(accessValue = "access-rotated", refreshValue = "refresh-rotated")
        authApi.answer(rotated)
        val lease = RefreshTestFixtures.sharedLease(
            binding = expiredTarget.binding,
            operationId = expiredTarget.operationId,
            phase = expiredTarget.phase,
        )
        val refreshCoordinator = RefreshTestFixtures.coordinator(
            vault = vault, authApi = authApi,
            clockMillis = { F.NOW },
        )
        val token = refreshCoordinator.accessToken(lease)
        assertEquals("access-rotated", token.token.value)
        assertEquals(1, authApi.refreshCalls)
        val sent = authApi.seenAttempts.single()
        assertEquals("refresh-target", sent.refreshToken.value)
        assertEquals(expiredTarget.pendingRefresh?.attempt?.idempotencyKey ?: sent.idempotencyKey, sent.idempotencyKey)
        // Archived evidence is byte-identical; the archived predecessor token
        // is never selected: a lease bound to its operation is rejected.
        assertEquals(expiredTarget.supersededSessions, vault.current().record?.supersededSessions)
        val staleLease = RefreshTestFixtures.sharedLease(
            binding = archivedSource.binding,
            operationId = archivedSource.operationId,
            phase = archivedSource.phase,
        )
        try {
            refreshCoordinator.accessToken(staleLease)
            fail("archived predecessor lease must be rejected")
        } catch (e: SessionRefreshException) {
            assertEquals(SessionRefreshFailure.STALE_SESSION, e.failure)
        }
        assertEquals(expiredTarget.supersededSessions, vault.current().record?.supersededSessions)
    }
}

internal object SameAccountRecoveryTestFixturesHelper {
    fun archiveOf(vararg sources: VaultSessionRecord, baseRevision: Long = 5L): VaultSupersededSessions {
        var revision = baseRevision
        val entries = sources.map { source ->
            VaultSupersededSession(sourceRevision = revision++, record = source)
        }
        return VaultSupersededSessions(entries)
    }
}
