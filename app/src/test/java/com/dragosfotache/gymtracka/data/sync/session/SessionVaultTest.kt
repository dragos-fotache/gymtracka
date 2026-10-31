package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.session.VaultTestFixtures.FakeCipher
import com.dragosfotache.gymtracka.data.sync.session.VaultTestFixtures.FakeStorage
import com.dragosfotache.gymtracka.data.sync.session.VaultTestFixtures.MAX_WIRE_MILLIS
import com.dragosfotache.gymtracka.data.sync.session.VaultTestFixtures.MIN_WIRE_MILLIS
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CancellationException as CoroutinesCancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * EncryptedSessionVault behavior.
 */
class SessionVaultTest {

    private fun vault(
        storage: FakeStorage,
        cipher: FakeCipher = FakeCipher(),
        dispatcher: kotlinx.coroutines.CoroutineDispatcher? = null,
        mutex: Mutex = Mutex(),
    ): EncryptedSessionVault {
        val io = dispatcher ?: StandardTestDispatcher()
        return EncryptedSessionVault(storage, cipher, io, mutex)
    }

    private fun assertVaultFailure(
        expected: VaultFailure,
        block: () -> Unit,
    ): SessionVaultException {
        try {
            block()
            fail("expected SessionVaultException($expected)")
        } catch (e: SessionVaultException) {
            assertEquals(expected, e.failure)
            assertNull("cause must stay closed", e.cause)
            return e
        }
        throw AssertionError("unreachable")
    }

    private suspend fun assertVaultFailureSuspend(
        expected: VaultFailure,
        block: suspend () -> Unit,
    ): SessionVaultException {
        try {
            block()
            fail("expected SessionVaultException($expected)")
        } catch (e: SessionVaultException) {
            assertEquals(expected, e.failure)
            assertNull("cause must stay closed", e.cause)
            return e
        }
        throw AssertionError("unreachable")
    }

    // ---- Empty / tombstone / ABA ----

    @Test
    fun emptyStoreLoadsRevisionZeroNull() = runTest {
        val storage = FakeStorage(null)
        val v = vault(storage, dispatcher = testScheduler.let { StandardTestDispatcher(testScheduler) })
        val snapshot = v.load()
        assertEquals(0L, snapshot.revision)
        assertNull(snapshot.record)
    }

    @Test
    fun clearingWritesPositiveRevisionTombstoneNotReset() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val storage = FakeStorage(null)
        val cipher = FakeCipher()
        val v = EncryptedSessionVault(storage, cipher, dispatcher, Mutex())
        val first = v.compareAndSet(0L, VaultTestFixtures.recordA())
        assertEquals(1L, first.revision)
        assertNotNull(first.record)

        val tombstone = v.compareAndSet(1L, null)
        assertEquals(2L, tombstone.revision)
        assertNull(tombstone.record)

        // Tombstone is durable: reload still revision 2, not zero.
        val reloaded = v.load()
        assertEquals(2L, reloaded.revision)
        assertNull(reloaded.record)

        // Delayed old revision must not reinstall credentials (ABA guard).
        assertVaultFailureSuspend(VaultFailure.CONFLICT) {
            v.compareAndSet(0L, VaultTestFixtures.recordA())
        }
        assertVaultFailureSuspend(VaultFailure.CONFLICT) {
            v.compareAndSet(1L, VaultTestFixtures.recordA())
        }
        val after = v.load()
        assertEquals(2L, after.revision)
        assertNull(after.record)
    }

    @Test
    fun clearingFromEmptyWritesRevisionOneTombstone() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val storage = FakeStorage(null)
        val v = EncryptedSessionVault(storage, FakeCipher(), dispatcher, Mutex())
        val tombstone = v.compareAndSet(0L, null)
        assertEquals(1L, tombstone.revision)
        assertNull(tombstone.record)
        val reloaded = v.load()
        assertEquals(1L, reloaded.revision)
        assertNull(reloaded.record)
    }

    @Test
    fun persistedRevisionZeroWithRecordIsUnreadable() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // VaultSnapshot(0, record) cannot be constructed (constructor refuses), so seed
        // independently valid bytes and patch the Long revision at offset 8..15 to zero.
        val validPlain = VaultTestFixtures.independentEncode(VaultTestFixtures.snapshot(revision = 1L))
        val patched = validPlain.copyOf()
        for (i in 8..15) patched[i] = 0
        val cipher = FakeCipher()
        val storage = FakeStorage(cipher.seal(patched))
        val v = EncryptedSessionVault(storage, cipher, dispatcher, Mutex())
        // Read of invalid committed state must fail closed, never silent empty.
        assertVaultFailureSuspend(VaultFailure.UNREADABLE) { v.load() }
        assertVaultFailureSuspend(VaultFailure.UNREADABLE) {
            v.compareAndSet(0L, VaultTestFixtures.recordA())
        }
    }

    // ---- CAS happy path / conflict / overflow / negative ----

    @Test
    fun casIncrementsOnceAndPersistsWholeGrantPlusAttempt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val storage = FakeStorage(null)
        val cipher = FakeCipher()
        val v = EncryptedSessionVault(storage, cipher, dispatcher, Mutex())

        val pending = VaultTestFixtures.pendingRefresh()
        val withPending = VaultTestFixtures.recordA(
            phase = VaultSessionPhase.PREPARED,
            pendingRefresh = pending,
        )
        val s1 = v.compareAndSet(0L, withPending)
        assertEquals(1L, s1.revision)
        assertEquals(VaultSessionPhase.PREPARED, s1.record?.phase)
        assertNotNull(s1.record?.pendingRefresh)

        // Rotation completing in ONE commit: grant changes and pending clears atomically.
        val rotated = VaultTestFixtures.recordA(
            phase = VaultSessionPhase.ACTIVE,
            session = VaultTestFixtures.sessionA(
                access = "opaque-access-002",
                refresh = "opaque-refresh-002",
            ),
            pendingRefresh = null,
        )
        val s2 = v.compareAndSet(1L, rotated)
        assertEquals(2L, s2.revision)
        assertEquals("opaque-access-002", s2.record?.session?.accessToken?.value)
        assertNull(s2.record?.pendingRefresh)

        val reloaded = v.load()
        assertEquals(2L, reloaded.revision)
        assertEquals("opaque-refresh-002", reloaded.record?.session?.refreshToken?.value)
        assertNull(reloaded.record?.pendingRefresh)
    }

    @Test
    fun casMismatchIsConflictWithoutWriteOrSeal() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val storage = FakeStorage(null)
        val cipher = FakeCipher()
        val v = EncryptedSessionVault(storage, cipher, dispatcher, Mutex())
        v.compareAndSet(0L, VaultTestFixtures.recordA())
        val sealsBefore = cipher.seals
        val writesBefore = storage.writes
        assertVaultFailureSuspend(VaultFailure.CONFLICT) {
            v.compareAndSet(0L, VaultTestFixtures.recordA())
        }
        assertVaultFailureSuspend(VaultFailure.CONFLICT) {
            v.compareAndSet(99L, VaultTestFixtures.recordA())
        }
        assertEquals(sealsBefore, cipher.seals)
        assertEquals(writesBefore, storage.writes)
        val reloaded = v.load()
        assertEquals(1L, reloaded.revision)
    }

    @Test
    fun casNegativeExpectedRevisionIsInvalidWithoutWriteOrKeyCreation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val key = VaultTestFixtures.generateAesKey()
        val tracking = VaultTestFixtures.TrackingKeyProvider(key)
        val countingCipher = object : VaultCipher {
            var seals = 0
            override fun seal(plaintext: ByteArray): ByteArray {
                seals += 1
                return AesGcmVaultCipher(tracking).seal(plaintext)
            }
            override fun open(sealed: ByteArray): ByteArray =
                AesGcmVaultCipher(tracking).open(sealed)
        }
        // Seed empty via fake storage (no crypto needed for refusal path).
        val storage = FakeStorage(null)
        val v = EncryptedSessionVault(storage, countingCipher, dispatcher, Mutex())
        assertVaultFailureSuspend(VaultFailure.INVALID_RECORD) {
            v.compareAndSet(-1L, VaultTestFixtures.recordA())
        }
        assertEquals(0, countingCipher.seals)
        assertEquals(0, storage.writes)
        assertTrue(tracking.calls.isEmpty())
    }

    @Test
    fun casOverflowIsExhaustedWithoutWrite() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cipher = FakeCipher()
        // Seed committed state at Long.MAX_VALUE through fake seal path.
        val maxRecord = VaultTestFixtures.recordA()
        val plain = VaultRecordCodec.encode(VaultSnapshot(Long.MAX_VALUE, maxRecord))
        val storage = FakeStorage(cipher.seal(plain))
        // Reset counters after seeding so refusal-path counts are exact.
        val opensBefore = cipher.opens
        val writesBefore = storage.writes
        val v = EncryptedSessionVault(storage, cipher, dispatcher, Mutex())

        val loaded = v.load()
        assertEquals(Long.MAX_VALUE, loaded.revision)

        assertVaultFailureSuspend(VaultFailure.REVISION_EXHAUSTED) {
            v.compareAndSet(Long.MAX_VALUE, VaultTestFixtures.recordA())
        }
        assertVaultFailureSuspend(VaultFailure.REVISION_EXHAUSTED) {
            v.compareAndSet(Long.MAX_VALUE, null)
        }
        assertEquals(writesBefore, storage.writes)
        // Committed max state is preserved, never reset to zero.
        val after = v.load()
        assertEquals(Long.MAX_VALUE, after.revision)
        assertNotNull(after.record)
        assertTrue(cipher.opens > opensBefore)
    }

    @Test
    fun rereadsDiskPerOperationWithoutSuccessCache() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val storage = FakeStorage(null)
        val cipher = FakeCipher()
        val v = EncryptedSessionVault(storage, cipher, dispatcher, Mutex())
        v.compareAndSet(0L, VaultTestFixtures.recordA())
        val readsAfterWrite = storage.reads
        // External replacement on disk (simulating restart/new process view).
        val external = VaultTestFixtures.snapshot(revision = 5L, record = VaultTestFixtures.recordA())
        storage.replace(cipher.seal(VaultRecordCodec.encode(external)))
        val loaded = v.load()
        assertEquals(5L, loaded.revision)
        assertTrue(storage.reads > readsAfterWrite)
        // CAS must fence against the re-read revision, not a cached one.
        assertVaultFailureSuspend(VaultFailure.CONFLICT) {
            v.compareAndSet(1L, VaultTestFixtures.recordA())
        }
        val ok = v.compareAndSet(5L, VaultTestFixtures.recordA())
        assertEquals(6L, ok.revision)
    }

    // ---- Binding: account / origin / generation / operation / pending token ----

    @Test
    fun recordAccountMustEqualGrantAccountAtConstruction() {
        // Constructor validation is REQUIRED: a binding/grant account mismatch must throw
        // INVALID_RECORD at construction, never survive to CAS.
        try {
            VaultSessionRecord(
                ServiceSessionBinding(VaultTestFixtures.originA(), VaultTestFixtures.ACCOUNT_B, 3L),
                VaultTestFixtures.OP_ACTIVE,
                VaultSessionPhase.ACTIVE,
                VaultTestFixtures.sessionA(displayName = "Example"),
                null,
            )
            fail("expected INVALID_RECORD for account mismatch")
        } catch (e: SessionVaultException) {
            assertEquals(VaultFailure.INVALID_RECORD, e.failure)
            assertNull(e.cause)
        }
        // Copy onto a mismatched binding is likewise refused.
        try {
            VaultTestFixtures.recordA().copy(
                binding = ServiceSessionBinding(
                    VaultTestFixtures.originA(),
                    VaultTestFixtures.ACCOUNT_B,
                    3L,
                ),
            )
            fail("expected INVALID_RECORD for mismatched copy")
        } catch (e: SessionVaultException) {
            assertEquals(VaultFailure.INVALID_RECORD, e.failure)
            assertNull(e.cause)
        }
    }

    @Test
    fun bindingCoversOriginAndGenerationAb() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val storage = FakeStorage(null)
        val v = EncryptedSessionVault(storage, FakeCipher(), dispatcher, Mutex())
        val recordHighPort = VaultTestFixtures.recordA(
            binding = ServiceSessionBinding(
                VaultTestFixtures.highPortOrigin(),
                VaultTestFixtures.ACCOUNT_A,
                9L,
            ),
        )
        val s1 = v.compareAndSet(0L, recordHighPort)
        assertEquals(
            VaultTestFixtures.ORIGIN_CANONICAL_HIGH_PORT,
            VaultTestFixtures.canonicalOriginString(s1.record!!.binding.origin),
        )
        assertEquals(9L, s1.record!!.binding.sessionGeneration)

        // Same account, different origin/generation is a different binding: CAS still
        // succeeds as whole-record replacement when revision matches (caller-supplied).
        val recordOtherOrigin = VaultTestFixtures.recordA(binding = VaultTestFixtures.bindingA(10L))
        val s2 = v.compareAndSet(1L, recordOtherOrigin)
        assertEquals(2L, s2.revision)
        assertEquals(10L, s2.record!!.binding.sessionGeneration)
    }

    @Test
    fun bindingGenerationGuardIsPreexistingModelFailure() {
        // Non-positive generation is rejected by the approved ServiceSessionBinding guard
        // before any vault constructor runs: expect the existing SyncModelException.
        try {
            ServiceSessionBinding(VaultTestFixtures.originA(), VaultTestFixtures.ACCOUNT_A, 0L)
            fail("expected SyncModelException for generation 0")
        } catch (e: SyncModelException) {
            assertNull("cause must stay closed", e.cause)
        }
        try {
            ServiceSessionBinding(VaultTestFixtures.originA(), VaultTestFixtures.ACCOUNT_A, -3L)
            fail("expected SyncModelException for negative generation")
        } catch (e: SyncModelException) {
            assertNull("cause must stay closed", e.cause)
        }
    }

    @Test
    fun invalidOperationIdsRefused() {
        assertVaultFailure(VaultFailure.INVALID_RECORD) {
            VaultSessionRecord(
                VaultTestFixtures.bindingA(),
                "not-a-uuid",
                VaultSessionPhase.ACTIVE,
                VaultTestFixtures.sessionA(),
                null,
            )
        }
        assertVaultFailure(VaultFailure.INVALID_RECORD) {
            VaultPendingRefresh("not-a-uuid", VaultTestFixtures.attemptFor())
        }
        assertVaultFailure(VaultFailure.INVALID_RECORD) {
            VaultTestFixtures.pendingRefresh().copy(operationId = "bad")
        }
        assertVaultFailure(VaultFailure.INVALID_RECORD) { VaultSnapshot(-1L, null) }
        assertVaultFailure(VaultFailure.INVALID_RECORD) {
            VaultSnapshot(0L, VaultTestFixtures.recordA())
        }
    }

    @Test
    fun pendingTokenMustEqualStoredGrantRefresh() {
        val matching = VaultTestFixtures.recordA(
            session = VaultTestFixtures.sessionA(refresh = "opaque-refresh-001"),
            pendingRefresh = VaultTestFixtures.pendingRefresh(
                attempt = VaultTestFixtures.attemptFor(refresh = "opaque-refresh-001"),
            ),
        )
        assertNotNull(matching.pendingRefresh)

        assertVaultFailure(VaultFailure.INVALID_RECORD) {
            VaultTestFixtures.recordA(
                session = VaultTestFixtures.sessionA(refresh = "opaque-refresh-001"),
                pendingRefresh = VaultTestFixtures.pendingRefresh(
                    attempt = VaultTestFixtures.attemptFor(refresh = "opaque-refresh-OTHER"),
                ),
            )
        }
    }

    @Test
    fun preparedAndActiveBothRetainUncertainAttemptWithoutPromotion() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val storage = FakeStorage(null)
        val v = EncryptedSessionVault(storage, FakeCipher(), dispatcher, Mutex())
        val prepared = VaultTestFixtures.recordA(
            phase = VaultSessionPhase.PREPARED,
            pendingRefresh = VaultTestFixtures.pendingRefresh(),
        )
        val s1 = v.compareAndSet(0L, prepared)
        assertEquals(VaultSessionPhase.PREPARED, s1.record?.phase)
        assertNotNull(s1.record?.pendingRefresh)

        val active = VaultTestFixtures.recordA(
            phase = VaultSessionPhase.ACTIVE,
            pendingRefresh = VaultTestFixtures.pendingRefresh(),
        )
        val s2 = v.compareAndSet(1L, active)
        assertEquals(VaultSessionPhase.ACTIVE, s2.record?.phase)
        assertNotNull(s2.record?.pendingRefresh)
    }

    // ---- Read / seal / write failures never silent-empty ----

    @Test
    fun corruptedCommittedDataFailsClosedPreservingFile() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cipher = FakeCipher()
        val storage = FakeStorage(byteArrayOf(1, 2, 3, 4))
        val before = storage.committedCopy()
        val v = EncryptedSessionVault(storage, cipher, dispatcher, Mutex())
        assertVaultFailureSuspend(VaultFailure.UNREADABLE) { v.load() }
        assertArrayEquals(before, storage.committedCopy())
        assertVaultFailureSuspend(VaultFailure.UNREADABLE) {
            v.compareAndSet(0L, VaultTestFixtures.recordA())
        }
        // Failed CAS against unreadable base must not install new credentials.
        assertArrayEquals(before, storage.committedCopy())
    }

    @Test
    fun cipherOpenFailureFailsClosedWithoutInferringMissing() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val storage = FakeStorage(null)
        val cipher = FakeCipher()
        val v = EncryptedSessionVault(storage, cipher, dispatcher, Mutex())
        v.compareAndSet(0L, VaultTestFixtures.recordA())
        val before = storage.committedCopy()
        cipher.openFailure = SessionVaultException(VaultFailure.UNREADABLE)
        assertVaultFailureSuspend(VaultFailure.UNREADABLE) { v.load() }
        assertArrayEquals(before, storage.committedCopy())
        // Must not report empty store from an access/tag failure.
        try {
            v.load()
            fail("expected UNREADABLE")
        } catch (e: SessionVaultException) {
            assertEquals(VaultFailure.UNREADABLE, e.failure)
            assertFalse(e.failure == VaultFailure.INVALID_RECORD)
        }
    }

    @Test
    fun sealFailureFailsWithoutWrite() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val storage = FakeStorage(null)
        val cipher = FakeCipher()
        val v = EncryptedSessionVault(storage, cipher, dispatcher, Mutex())
        cipher.sealFailure = SessionVaultException(VaultFailure.UNAVAILABLE)
        val writesBefore = storage.writes
        assertVaultFailureSuspend(VaultFailure.UNAVAILABLE) {
            v.compareAndSet(0L, VaultTestFixtures.recordA())
        }
        assertEquals(writesBefore, storage.writes)
        val loaded = v.load()
        assertEquals(0L, loaded.revision)
        assertNull(loaded.record)
    }

    @Test
    fun writeFailurePropagatesWithoutSilentSuccess() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val storage = FakeStorage(null)
        val cipher = FakeCipher()
        val v = EncryptedSessionVault(storage, cipher, dispatcher, Mutex())
        storage.writeFailure = SessionVaultException(VaultFailure.UNAVAILABLE)
        try {
            v.compareAndSet(0L, VaultTestFixtures.recordA())
            fail("expected write failure")
        } catch (e: SessionVaultException) {
            assertEquals(VaultFailure.UNAVAILABLE, e.failure)
            assertNull(e.cause)
        }
        storage.writeFailure = null
        // Uncertain outcome is resolved by reload/fence, not by assuming success.
        val loaded = v.load()
        assertEquals(0L, loaded.revision)
        assertNull(loaded.record)
    }

    @Test
    fun storageReadFailureNeverReturnsEmpty() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val storage = FakeStorage(null)
        val v = EncryptedSessionVault(storage, FakeCipher(), dispatcher, Mutex())
        storage.readFailure = SessionVaultException(VaultFailure.UNAVAILABLE)
        assertVaultFailureSuspend(VaultFailure.UNAVAILABLE) { v.load() }
        assertVaultFailureSuspend(VaultFailure.UNAVAILABLE) {
            v.compareAndSet(0L, VaultTestFixtures.recordA())
        }
    }

    // ---- Cancellation matrix (no sleeps) ----

    @Test
    fun cancelledBeforeOperationPerformsNoIo() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val storage = FakeStorage(null)
        val cipher = FakeCipher()
        val v = EncryptedSessionVault(storage, cipher, dispatcher, Mutex())
        // Cancel the child caller before invoking the vault API.
        var loadOutcome: Throwable? = null
        val loadChild = launch {
            // Simulate pre-cancelled caller by cancelling this child first.
            this.coroutineContext[kotlinx.coroutines.Job]?.cancel(
                CancellationException("pre-cancel-load"),
            )
            try {
                v.load()
                loadOutcome = AssertionError("expected CancellationException")
            } catch (e: CancellationException) {
                loadOutcome = e
            } catch (e: CoroutinesCancellationException) {
                loadOutcome = e
            }
        }
        loadChild.join()
        assertTrue(loadOutcome is CancellationException || loadOutcome is CoroutinesCancellationException)
        assertEquals(0, storage.reads)
        assertEquals(0, storage.writes)
        assertEquals(0, cipher.seals)
        assertEquals(0, cipher.opens)

        var casOutcome: Throwable? = null
        val casChild = launch {
            this.coroutineContext[kotlinx.coroutines.Job]?.cancel(
                CancellationException("pre-cancel-cas"),
            )
            try {
                v.compareAndSet(0L, VaultTestFixtures.recordA())
                casOutcome = AssertionError("expected CancellationException")
            } catch (e: CancellationException) {
                casOutcome = e
            } catch (e: CoroutinesCancellationException) {
                casOutcome = e
            }
        }
        casChild.join()
        assertTrue(casOutcome is CancellationException || casOutcome is CoroutinesCancellationException)
        assertEquals(0, storage.reads)
        assertEquals(0, storage.writes)
    }

    @Test
    fun cancelledWhileWaitingForMutexPerformsNoIo() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val storage = FakeStorage(null)
        val cipher = FakeCipher()
        val held = Mutex(locked = true)
        val v = EncryptedSessionVault(storage, cipher, dispatcher, held)
        // The caller child blocks on the held vault mutex, then is cancelled while waiting:
        // no storage or crypto I/O may have happened.
        var caughtInsideChild: Throwable? = null
        val child: Deferred<Unit> = async {
            try {
                v.load()
            } catch (e: Throwable) {
                caughtInsideChild = e
                throw e
            }
        }
        testScheduler.runCurrent()
        child.cancel(CancellationException("cancel-while-waiting"))
        try {
            child.await()
        } catch (_: Throwable) {
        }
        assertTrue(
            caughtInsideChild is CancellationException ||
                caughtInsideChild is CoroutinesCancellationException,
        )
        assertEquals(0, storage.reads)
        assertEquals(0, storage.writes)
        assertEquals(0, cipher.seals)
        assertEquals(0, cipher.opens)
        held.unlock()
    }

    @Test
    fun activeInjectedCancellationIdentityRetainedAcrossReadOpenSealReplace() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        suspend fun captureInsideChild(block: suspend () -> Unit): Throwable? {
            var caught: Throwable? = null
            val child: Deferred<Unit> = async {
                try {
                    block()
                } catch (e: Throwable) {
                    caught = e
                    throw e
                }
            }
            try {
                child.await()
            } catch (_: Throwable) {
            }
            return caught
        }
        // Read stage.
        run {
            val injected = CancellationException("injected-read-cancellation")
            val storage = FakeStorage(null).apply { readFailure = injected }
            val v = EncryptedSessionVault(storage, FakeCipher(), dispatcher, Mutex())
            assertSame(injected, captureInsideChild { v.load() })
            assertNull(storage.committedCopy())
        }
        // Open stage: valid sealed bytes present, cipher open throws active identity.
        run {
            val injected = CancellationException("injected-open-cancellation")
            val cipher = FakeCipher().apply { openFailure = injected }
            val sealer = FakeCipher()
            val storage = FakeStorage(
                sealer.seal(VaultRecordCodec.encode(VaultTestFixtures.snapshot())),
            )
            val v = EncryptedSessionVault(storage, cipher, dispatcher, Mutex())
            assertSame(injected, captureInsideChild { v.load() })
        }
        // Seal stage.
        run {
            val injected = CancellationException("injected-seal-cancellation")
            val cipher = FakeCipher().apply { sealFailure = injected }
            val storage = FakeStorage(null)
            val v = EncryptedSessionVault(storage, cipher, dispatcher, Mutex())
            assertSame(injected, captureInsideChild {
                v.compareAndSet(0L, VaultTestFixtures.recordA())
            })
            assertEquals(0, storage.writes)
        }
        // Replace stage.
        run {
            val injected = CancellationException("injected-replace-cancellation")
            val storage = FakeStorage(null).apply { writeFailure = injected }
            val v = EncryptedSessionVault(storage, FakeCipher(), dispatcher, Mutex())
            assertSame(injected, captureInsideChild {
                v.compareAndSet(0L, VaultTestFixtures.recordA())
            })
            assertNull(storage.committedCopy())
        }
    }

    @Test
    fun callerCancellationDominatesRacingSeamError() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // The seam cancels the ACTUAL caller inside read, then throws its own error:
        // the ORIGINAL caller cause must win over a different CancellationException,
        // a generic error, or a safe vault error alike.
        val seamErrors: List<() -> RuntimeException> = listOf(
            { CancellationException("different-seam-cancellation") },
            { RuntimeException("generic-seam-boom") },
            { SessionVaultException(VaultFailure.UNAVAILABLE) },
        )
        for (seamError in seamErrors) {
            val callerCancel = CancellationException("caller-dominates")
            var callerJob: kotlinx.coroutines.Job? = null
            val storage = object : VaultFileStorage {
                override fun read(): ByteArray? {
                    callerJob?.cancel(callerCancel)
                    throw seamError()
                }
                override fun replace(bytes: ByteArray) = throw AssertionError("no write expected")
            }
            val v = EncryptedSessionVault(storage, FakeCipher(), dispatcher, Mutex())
            var caughtInsideChild: Throwable? = null
            val child: Deferred<Unit> = async {
                callerJob = coroutineContext[kotlinx.coroutines.Job]
                try {
                    v.load()
                } catch (e: Throwable) {
                    caughtInsideChild = e
                    throw e
                }
            }
            try {
                child.await()
            } catch (_: Throwable) {
            }
            assertSame(callerCancel, caughtInsideChild)
            assertFalse(caughtInsideChild is SessionVaultException)
        }
    }

    @Test
    fun coreCopiesReadBytesBeforeMutatingCipherOpen() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val snapshot = VaultTestFixtures.snapshot()
        val sealer = FakeCipher()
        // Hostile storage hands out its RETAINED committed array on read (no copy).
        val retaining = object : VaultFileStorage {
            var committed: ByteArray? = sealer.seal(VaultRecordCodec.encode(snapshot))
            override fun read(): ByteArray? = committed
            override fun replace(bytes: ByteArray) {
                committed = bytes.copyOf()
            }
        }
        // Hostile cipher decodes from a private copy but mutates the handed array.
        val delegate = FakeCipher()
        val seenInputs = mutableListOf<ByteArray>()
        val mutating = object : VaultCipher {
            override fun seal(plaintext: ByteArray): ByteArray = delegate.seal(plaintext)
            override fun open(sealed: ByteArray): ByteArray {
                seenInputs.add(sealed)
                val result = delegate.open(sealed.copyOf())
                sealed.fill(0x00)
                return result
            }
        }
        val v = EncryptedSessionVault(retaining, mutating, dispatcher, Mutex())
        val first = v.load()
        assertEquals(snapshot, first)
        // The core handed the cipher a defensive copy: the cipher never observed the
        // retained storage array, and the original storage view is unmutated.
        assertEquals(1, seenInputs.size)
        assertNotSame(retaining.committed, seenInputs.single())
        val second = v.load()
        assertEquals(snapshot, second)
        assertEquals(first, second)
    }

    @Test
    fun cancellationAfterSynchronousCommitMayLeaveCompleteNewState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val afterCommit = CancellationException("race-after-commit")
        var callerJob: kotlinx.coroutines.Job? = null
        var committedBytes: ByteArray? = null
        // Deterministic synchronous seam: durably saves the complete replacement,
        // then cancels the ACTUAL caller. Cancellation is NOT rollback proof.
        val storage = object : VaultFileStorage {
            override fun read(): ByteArray? = committedBytes?.copyOf()
            override fun replace(bytes: ByteArray) {
                committedBytes = bytes.copyOf()
                callerJob?.cancel(afterCommit)
                throw afterCommit
            }
        }
        val cipher = FakeCipher()
        val v = EncryptedSessionVault(storage, cipher, dispatcher, Mutex())
        val expected = VaultTestFixtures.recordA()
        var caughtInsideChild: Throwable? = null
        val child: Deferred<Unit> = async {
            callerJob = coroutineContext[kotlinx.coroutines.Job]
            try {
                v.compareAndSet(0L, expected)
            } catch (e: Throwable) {
                caughtInsideChild = e
                throw e
            }
        }
        try {
            child.await()
        } catch (_: Throwable) {
        }
        assertSame(afterCommit, caughtInsideChild)
        // The synchronous commit stands despite caller cancellation: reload fences
        // to the complete new snapshot and the caller must reload before transmitting.
        val reloaded = v.load()
        assertEquals(1L, reloaded.revision)
        assertEquals(expected, reloaded.record)
    }

    // ---- Concurrency: exactly one CAS winner ----

    @Test
    fun concurrentCasHasExactlyOneWinner() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val storage = FakeStorage(null)
        val v = EncryptedSessionVault(storage, FakeCipher(), dispatcher, Mutex())
        val contenders = 8
        val winners = mutableListOf<VaultSnapshot>()
        val conflicts = mutableListOf<SessionVaultException>()
        coroutineContext[kotlinx.coroutines.Job]?.let { _ ->
            val jobs = (0 until contenders).map { index ->
                async(dispatcher) {
                    try {
                        val record = VaultTestFixtures.recordA(
                            operationId = listOf(
                                "11111111-1111-1111-1111-111111111111",
                                "22222222-2222-2222-2222-222222222222",
                                "33333333-3333-3333-3333-333333333333",
                                "44444444-4444-4444-4444-444444444444",
                                "55555555-5555-5555-5555-555555555555",
                                "66666666-6666-6666-6666-666666666666",
                                "77777777-7777-7777-7777-777777777777",
                                "88888888-8888-8888-8888-888888888888",
                            )[index],
                        )
                        v.compareAndSet(0L, record)
                    } catch (e: SessionVaultException) {
                        e
                    }
                }
            }
            jobs.forEach { deferred ->
                when (val outcome = deferred.await()) {
                    is VaultSnapshot -> winners.add(outcome)
                    is SessionVaultException -> {
                        assertEquals(VaultFailure.CONFLICT, outcome.failure)
                        conflicts.add(outcome)
                    }
                    else -> fail("unexpected outcome $outcome")
                }
            }
        }
        assertEquals(1, winners.size)
        assertEquals(contenders - 1, conflicts.size)
        assertEquals(1L, winners.single().revision)
        val reloaded = v.load()
        assertEquals(1L, reloaded.revision)
        assertEquals(winners.single().record, reloaded.record)
    }

    // ---- Redaction / closed causes / defensive ownership ----

    @Test
    fun displaysAreRedactedAndCausesClosed() = runTest {
        val record = VaultTestFixtures.recordA(
            session = VaultTestFixtures.sessionA(
                displayName = "Secret Name",
                access = "super-secret-access",
                refresh = "super-secret-refresh",
            ),
            pendingRefresh = VaultTestFixtures.pendingRefresh(
                attempt = VaultTestFixtures.attemptFor(refresh = "super-secret-refresh"),
            ),
        )
        val snapshot = VaultSnapshot(3L, record)
        val recordText = record.toString()
        val pendingText = record.pendingRefresh.toString()
        val snapshotText = snapshot.toString()
        val exceptionText = SessionVaultException(VaultFailure.CONFLICT).toString()
        for (text in listOf(recordText, pendingText, snapshotText, exceptionText)) {
            assertFalse(text.contains("super-secret-access"))
            assertFalse(text.contains("super-secret-refresh"))
            assertFalse(text.contains(VaultTestFixtures.ACCOUNT_A))
            assertFalse(text.contains("Secret Name"))
        }
        // Attempt body is sensitive and must never appear in displays.
        assertFalse(snapshotText.contains(record.pendingRefresh?.attempt?.requestJson ?: "IMPOSSIBLE-NEEDLE"))
        val e = SessionVaultException(VaultFailure.UNREADABLE)
        assertNull(e.cause)
        assertFalse(e.message.orEmpty().contains("super-secret"))
        // Closed cause rejects late attachment of a raw failure: initCause must fail
        // rather than smuggling a secret-bearing cause into the safe exception.
        try {
            e.initCause(RuntimeException("raw-secret-cause"))
            fail("initCause must be rejected on closed SessionVaultException")
        } catch (expected: IllegalStateException) {
        }
        assertNull(e.cause)
    }

    @Test
    fun codecAndVaultOwnBytesDefensively() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val snapshot = VaultTestFixtures.snapshot()
        val encoded = VaultRecordCodec.encode(snapshot)
        val encodedCopy = encoded.copyOf()
        // Mutating the encoded output must not affect a fresh encode/decode.
        encoded[encoded.size - 1] = (encoded.last() + 1).toByte()
        val fresh = VaultRecordCodec.encode(snapshot)
        assertArrayEquals(encodedCopy, fresh)
        assertEquals(snapshot, VaultRecordCodec.decode(fresh))

        // Vault boundary ownership: capture the exact arrays handed to seal/replace.
        // The fake returns copies on read, so mutating a read copy would prove nothing;
        // instead prove the core never mutates its retained storage view or cache.
        var sealedHandedToReplace: ByteArray? = null
        val observingStorage = object : VaultFileStorage {
            var committed: ByteArray? = null
            var expectedAtReplace: ByteArray? = null
            override fun read(): ByteArray? = committed?.copyOf()
            override fun replace(bytes: ByteArray) {
                sealedHandedToReplace = bytes
                expectedAtReplace = bytes.copyOf()
                committed = bytes.copyOf()
            }
        }
        var plainHandedToSeal: ByteArray? = null
        val observingCipher = object : VaultCipher {
            val delegate = FakeCipher()
            override fun seal(plaintext: ByteArray): ByteArray {
                plainHandedToSeal = plaintext
                return delegate.seal(plaintext.copyOf())
            }
            override fun open(sealed: ByteArray): ByteArray = delegate.open(sealed)
        }
        val v = EncryptedSessionVault(observingStorage, observingCipher, dispatcher, Mutex())
        v.compareAndSet(0L, snapshot.record)
        // Ownership proof: the expected bytes are captured INSIDE storage.replace, the
        // passed reference and the retained committed copy must be different objects,
        // and later mutation of handed buffers must leave committed state intact.
        // (The core may legitimately zero its OWN passed plaintext temporary after the
        // copy was taken, so handed-buffer content is never asserted, only stability.)
        val handedPlain = requireNotNull(plainHandedToSeal)
        val handedSealed = requireNotNull(sealedHandedToReplace)
        val retainedCommitted = requireNotNull(observingStorage.committed)
        assertNotSame(handedSealed, retainedCommitted)
        val expectedCommitted = requireNotNull(observingStorage.expectedAtReplace)
        handedPlain.fill(0x7F)
        handedSealed.fill(0x00)
        assertEquals(expectedCommitted.toList(), observingStorage.read()?.toList())
        val reloaded = v.load()
        assertEquals(1L, reloaded.revision)
        assertEquals(snapshot.record, reloaded.record)
    }

    @Test
    fun cancellationDuringSuccessfulSealPreventsReplacement() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val callerCancel = CancellationException("cancel-during-seal")
        var callerJob: kotlinx.coroutines.Job? = null
        val delegate = FakeCipher()
        // Seam cancels the ACTUAL caller, then returns valid sealed bytes: the core's
        // pre-write cancellation check must still refuse to start the replacement.
        val cancellingCipher = object : VaultCipher {
            override fun seal(plaintext: ByteArray): ByteArray {
                callerJob?.cancel(callerCancel)
                return delegate.seal(plaintext)
            }
            override fun open(sealed: ByteArray): ByteArray = delegate.open(sealed)
        }
        val storage = FakeStorage(null)
        val v = EncryptedSessionVault(storage, cancellingCipher, dispatcher, Mutex())
        var caughtInsideChild: Throwable? = null
        val child: Deferred<Unit> = async {
            callerJob = coroutineContext[kotlinx.coroutines.Job]
            try {
                v.compareAndSet(0L, VaultTestFixtures.recordA())
            } catch (e: Throwable) {
                caughtInsideChild = e
                throw e
            }
        }
        try {
            child.await()
        } catch (_: Throwable) {
        }
        val sealMessage = (caughtInsideChild as? CancellationException)?.message
            ?: (caughtInsideChild as? CoroutinesCancellationException)?.message
        assertTrue(
            "seal-time caller cancellation must abort CAS, got $caughtInsideChild",
            sealMessage == "cancel-during-seal",
        )
        assertFalse(caughtInsideChild is SessionVaultException)
        assertEquals(0, storage.writes)
        val loaded = v.load()
        assertEquals(0L, loaded.revision)
        assertNull(loaded.record)
    }

    @Test
    fun expiryBoundsPreservedExactly() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val storage = FakeStorage(null)
        val v = EncryptedSessionVault(storage, FakeCipher(), dispatcher, Mutex())
        val bounds = VaultTestFixtures.recordA(
            session = VaultTestFixtures.sessionA(
                accessExpires = MIN_WIRE_MILLIS,
                refreshExpires = MAX_WIRE_MILLIS,
            ),
        )
        val committed = v.compareAndSet(0L, bounds)
        assertEquals(MIN_WIRE_MILLIS, committed.record?.session?.accessExpiresAtMillis)
        assertEquals(MAX_WIRE_MILLIS, committed.record?.session?.refreshExpiresAtMillis)
        val reloaded = v.load()
        assertEquals(MIN_WIRE_MILLIS, reloaded.record?.session?.accessExpiresAtMillis)
        assertEquals(MAX_WIRE_MILLIS, reloaded.record?.session?.refreshExpiresAtMillis)
    }

    @Test
    fun invalidModelEncodeIsInvalidRecord() {
        // revision0 with record and negative revision are model-invalid; encode refuses.
        assertVaultFailure(VaultFailure.INVALID_RECORD) {
            VaultRecordCodec.encode(VaultSnapshot(0L, VaultTestFixtures.recordA()))
        }
        assertVaultFailure(VaultFailure.INVALID_RECORD) {
            VaultRecordCodec.encode(VaultSnapshot(-4L, null))
        }
    }
}
