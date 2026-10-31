package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.ServiceAccountRef
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Checks superseded-evidence validation, v1/v2 encoding and CAS fences against independently built
 * binary bytes. Entries have increasing source revisions/generations, matching origin/account,
 * lower generation than the current record, distinct refresh tokens and source revisions below the
 * snapshot revision.
 */
class VaultSupersededEvidenceTest {

    private fun assertModelInvalid(block: () -> Unit): SessionVaultException {
        try {
            block()
            fail("expected SessionVaultException(INVALID_RECORD)")
        } catch (e: SessionVaultException) {
            assertEquals(VaultFailure.INVALID_RECORD, e.failure)
            assertNull(e.cause)
            return e
        }
        throw AssertionError("unreachable")
    }

    private fun assertDecodeFailure(expected: VaultFailure, bytes: ByteArray): SessionVaultException {
        try {
            VaultRecordCodec.decode(bytes)
            fail("expected SessionVaultException($expected)")
        } catch (e: SessionVaultException) {
            assertEquals(expected, e.failure)
            assertNull("cause must stay closed", e.cause)
            assertFalse(e.message.orEmpty().contains(VaultTestFixtures.ACCOUNT_A))
            return e
        }
        throw AssertionError("unreachable")
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun writeRecordBody(out: DataOutputStream, record: VaultSessionRecord) {
        writeString(out, VaultTestFixtures.canonicalOriginString(record.binding.origin))
        writeString(out, record.binding.accountId)
        out.writeLong(record.binding.sessionGeneration)
        writeString(out, record.operationId)
        out.writeByte(if (record.phase == VaultSessionPhase.PREPARED) 0 else 1)
        val displayName = record.session.account.displayName
        if (displayName == null) {
            out.writeByte(0)
        } else {
            out.writeByte(1)
            writeString(out, displayName)
        }
        writeString(out, record.session.accessToken.value)
        out.writeLong(record.session.accessExpiresAtMillis)
        writeString(out, record.session.refreshToken.value)
        out.writeLong(record.session.refreshExpiresAtMillis)
        val pending = record.pendingRefresh
        if (pending == null) {
            out.writeByte(0)
        } else {
            out.writeByte(1)
            writeString(out, pending.operationId)
            writeString(out, pending.attempt.idempotencyKey)
            writeString(out, pending.attempt.requestJson)
        }
    }

    /** Independent v2 builder straight from the spec grammar (no production help). */
    private fun independentEncodeV2(snapshot: VaultSnapshot): ByteArray {
        val record = requireNotNull(snapshot.record) { "v2 requires an occupied record" }
        require(record.supersededSessions.entries.isNotEmpty()) { "v2 requires a nonempty archive" }
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeInt(VaultTestFixtures.RECORD_MAGIC)
            out.writeInt(2)
            out.writeLong(snapshot.revision)
            out.writeByte(1)
            writeRecordBody(out, record)
            val entries = record.supersededSessions.entries
            out.writeInt(entries.size)
            for (entry in entries) {
                out.writeLong(entry.sourceRevision)
                writeRecordBody(out, entry.record)
            }
        }
        return bos.toByteArray()
    }

    private fun patchVersion(bytes: ByteArray, version: Int): ByteArray {
        val copy = bytes.copyOf()
        copy[4] = ((version ushr 24) and 0xFF).toByte()
        copy[5] = ((version ushr 16) and 0xFF).toByte()
        copy[6] = ((version ushr 8) and 0xFF).toByte()
        copy[7] = (version and 0xFF).toByte()
        return copy
    }

    private fun foreignSession(refresh: String): ServiceSession = ServiceSession(
        ServiceAccountRef(VaultTestFixtures.ACCOUNT_B, null),
        OpaqueToken("opaque-access-foreign"),
        1_700_000_000_000L,
        OpaqueToken(refresh),
        1_700_000_000_000L + 30L * 24 * 60 * 60 * 1000,
    )

    private fun foreignRecord(
        generation: Long = 7L,
        refresh: String = "opaque-refresh-foreign-001",
    ): VaultSessionRecord = VaultTestFixtures.recordA(
        binding = ServiceSessionBinding(
            VaultTestFixtures.originA(), VaultTestFixtures.ACCOUNT_B, generation,
        ),
        session = foreignSession(refresh),
    )

    private fun oldRecord(
        generation: Long = 7L,
        operationId: String = VaultTestFixtures.OP_ACTIVE,
        refresh: String = "opaque-refresh-001",
    ): VaultSessionRecord = VaultTestFixtures.recordA(
        binding = VaultTestFixtures.bindingA(generation),
        operationId = operationId,
        phase = VaultSessionPhase.ACTIVE,
        session = VaultTestFixtures.sessionA(refresh = refresh),
        pendingRefresh = null,
    )

    private fun archivedCurrent(
        currentGeneration: Long = 8L,
        currentRefresh: String = "opaque-refresh-002",
        archiveRevision: Long = 5L,
        sourceGeneration: Long = 7L,
        sourceRefresh: String = "opaque-refresh-001",
    ): VaultSessionRecord = VaultTestFixtures.recordA(
        binding = VaultTestFixtures.bindingA(currentGeneration),
        operationId = VaultTestFixtures.OP_PREPARED,
        phase = VaultSessionPhase.PREPARED,
        session = VaultTestFixtures.sessionA(refresh = currentRefresh),
        pendingRefresh = null,
    ).copy(
        supersededSessions = VaultSupersededSessions(
            listOf(
                VaultSupersededSession(
                    sourceRevision = archiveRevision,
                    record = oldRecord(generation = sourceGeneration, refresh = sourceRefresh),
                ),
            ),
        ),
    )

    /** Seed a real encrypted vault at the exact valid rev6 snapshot (independent bytes). */
    private fun seededRev6Vault(
        snapshot: VaultSnapshot = VaultSnapshot(6L, archivedCurrent()),
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ): Pair<EncryptedSessionVault, VaultTestFixtures.FakeStorage> {
        val cipher = VaultTestFixtures.FakeCipher()
        val sealed = cipher.seal(independentEncodeV2(snapshot))
        val storage = VaultTestFixtures.FakeStorage(sealed)
        val vault = EncryptedSessionVault(storage, VaultTestFixtures.FakeCipher(), dispatcher, Mutex())
        return vault to storage
    }

    // ---- Model validation (all fixtures otherwise valid) ----

    @Test
    fun supersededEntryRequiresPositiveRevisionAndEmptyNestedArchive() {
        assertModelInvalid { VaultSupersededSession(sourceRevision = 0L, record = oldRecord()) }
        assertModelInvalid { VaultSupersededSession(sourceRevision = -3L, record = oldRecord()) }
        val nested = oldRecord(generation = 7L, refresh = "opaque-refresh-007").copy(
            supersededSessions = VaultSupersededSessions(
                listOf(VaultSupersededSession(1L, oldRecord(generation = 6L, refresh = "opaque-refresh-006"))),
            ),
        )
        assertModelInvalid { VaultSupersededSession(sourceRevision = 2L, record = nested) }
    }

    @Test
    fun nestedArchiveSourcesAreAlwaysForbidden() {
        // A middle record carrying a valid lower archive is itself a valid
        // fixture; archiving IT (nonempty archive) is always refused.
        val inner = VaultSupersededSession(
            2L, oldRecord(generation = 5L, refresh = "opaque-refresh-005"),
        )
        val middle = oldRecord(generation = 7L, refresh = "opaque-refresh-007").copy(
            supersededSessions = VaultSupersededSessions(listOf(inner)),
        )
        assertEquals(1, middle.supersededSessions.entries.size)
        assertModelInvalid { VaultSupersededSession(5L, middle) }
    }

    @Test
    fun archiveRequiresIncreasingRevisionsGenerationsAndConsistentAccount() {
        val first = VaultSupersededSession(3L, oldRecord(generation = 6L, refresh = "opaque-refresh-006"))
        val sameRevision = VaultSupersededSession(3L, oldRecord(generation = 7L, refresh = "opaque-refresh-007"))
        assertModelInvalid { VaultSupersededSessions(listOf(first, sameRevision)) }
        val decreasing = VaultSupersededSession(2L, oldRecord(generation = 7L, refresh = "opaque-refresh-007"))
        assertModelInvalid { VaultSupersededSessions(listOf(first, decreasing)) }
        val decreasingGen = VaultSupersededSession(4L, oldRecord(generation = 6L, refresh = "opaque-refresh-008"))
        assertModelInvalid { VaultSupersededSessions(listOf(first, decreasingGen)) }
        // Foreign binding with a MATCHING foreign session account is valid on
        // its own terms; mixing it with an account-A entry is inconsistent.
        val foreignValid = VaultSupersededSession(4L, foreignRecord(generation = 7L))
        assertModelInvalid { VaultSupersededSessions(listOf(first, foreignValid)) }
        val foreignArchive = VaultSupersededSessions(
            listOf(
                VaultSupersededSession(3L, foreignRecord(generation = 6L, refresh = "opaque-refresh-foreign-001")),
                VaultSupersededSession(4L, foreignRecord(generation = 7L, refresh = "opaque-refresh-foreign-002")),
            ),
        )
        assertEquals(2, foreignArchive.entries.size)
        assertTrue(VaultSupersededSessions(emptyList()).entries.isEmpty())
    }

    @Test
    fun archiveEntriesDefensivelyOwnedAndValueEqual() {
        // Multi-entry input: the ArrayList escape risk is owned-copy rejection.
        val first = VaultSupersededSession(3L, oldRecord(generation = 6L, refresh = "opaque-refresh-006"))
        val second = VaultSupersededSession(4L, oldRecord(generation = 7L, refresh = "opaque-refresh-007"))
        val mutable = mutableListOf(first, second)
        val archive = VaultSupersededSessions(mutable)
        mutable.clear()
        mutable.add(VaultSupersededSession(9L, oldRecord(generation = 6L, refresh = "opaque-refresh-009")))
        assertEquals(listOf(first, second), archive.entries)
        try {
            (archive.entries as MutableList<VaultSupersededSession>).clear()
            fail("entries must not escape as mutable")
        } catch (_: UnsupportedOperationException) {
        } catch (_: ClassCastException) {
        }
        assertEquals(listOf(first, second), archive.entries)
        val again = VaultSupersededSessions(
            listOf(
                VaultSupersededSession(3L, oldRecord(generation = 6L, refresh = "opaque-refresh-006")),
                VaultSupersededSession(4L, oldRecord(generation = 7L, refresh = "opaque-refresh-007")),
            ),
        )
        assertEquals(archive, again)
        assertEquals(archive.hashCode(), again.hashCode())
        val rendered = archive.toString()
        assertFalse(rendered.contains("opaque-refresh-006"))
        assertFalse(rendered.contains("opaque-refresh-007"))
        assertFalse(rendered.contains(VaultTestFixtures.ACCOUNT_A))
        val entryRendered = VaultSupersededSession(3L, oldRecord()).toString()
        assertFalse(entryRendered.contains("opaque-refresh-001"))
    }

    @Test
    fun recordArchiveMustMatchOriginAccountLowerGenerationAndDistinctToken() {
        val source = oldRecord(generation = 7L, refresh = "opaque-refresh-001")
        val archive = VaultSupersededSessions(listOf(VaultSupersededSession(3L, source)))
        assertModelInvalid {
            VaultTestFixtures.recordA(
                binding = VaultTestFixtures.bindingA(6L),
                session = VaultTestFixtures.sessionA(refresh = "opaque-refresh-002"),
            ).copy(supersededSessions = archive)
        }
        assertModelInvalid {
            VaultTestFixtures.recordA(
                binding = VaultTestFixtures.bindingA(8L),
                session = VaultTestFixtures.sessionA(refresh = "opaque-refresh-001"),
            ).copy(supersededSessions = archive)
        }
        // sourceRevision must sit below the snapshot revision.
        assertModelInvalid { VaultSnapshot(3L, archivedCurrent(archiveRevision = 5L)) }
        assertModelInvalid { VaultSnapshot(5L, archivedCurrent(archiveRevision = 5L)) }
        assertEquals(6L, VaultSnapshot(6L, archivedCurrent(archiveRevision = 5L)).revision)
    }

    // ---- Codec: v1 stability with storage no-write oracle + independent v2 ----

    @Test
    fun v1NoArchiveEncodingIsByteExactAndLoadPerformsNoWrite() = runTest {
        val snapshot = VaultTestFixtures.snapshot(
            revision = 4L,
            record = VaultTestFixtures.recordA(),
        )
        val produced = VaultRecordCodec.encode(snapshot)
        assertArrayEquals(VaultTestFixtures.independentEncode(snapshot), produced)
        assertEquals(snapshot, VaultRecordCodec.decode(produced))
        // A v1 load performs no write and creates no key: storage write count
        // stays zero across the encrypted load path.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cipher = VaultTestFixtures.FakeCipher()
        val storage = VaultTestFixtures.FakeStorage(cipher.seal(VaultTestFixtures.independentEncode(snapshot)))
        val vault = EncryptedSessionVault(storage, VaultTestFixtures.FakeCipher(), dispatcher, Mutex())
        assertEquals(snapshot, vault.load())
        assertEquals(0, storage.writes)
        assertTrue(storage.reads >= 1)
    }

    @Test
    fun v2RoundtripMatchesIndependentBytesWithWhitespaceAndUnicode() {
        val body = " { \"refreshToken\" : \"opaque-refresh-ünïcode-☃-001\" } "
        val sourceAttempt = VaultTestFixtures.attemptFromBody(VaultTestFixtures.KEY_A, body)
        val source = VaultTestFixtures.recordA(
            binding = VaultTestFixtures.bindingA(7L),
            operationId = VaultTestFixtures.OP_ACTIVE,
            phase = VaultSessionPhase.ACTIVE,
            session = VaultTestFixtures.sessionA(
                displayName = "Tëst ☃ 健身",
                access = "opaque-access-ünïcode-☃-001",
                accessExpires = 1_700_000_000_123L,
                refresh = sourceAttempt.refreshToken.value,
                refreshExpires = 1_700_000_000_000L + 30L * 24 * 60 * 60 * 1000,
            ),
            pendingRefresh = VaultTestFixtures.pendingRefresh(
                operationId = VaultTestFixtures.OP_PENDING,
                attempt = sourceAttempt,
            ),
        )
        val current = VaultTestFixtures.recordA(
            binding = VaultTestFixtures.bindingA(8L),
            operationId = VaultTestFixtures.OP_PREPARED,
            phase = VaultSessionPhase.PREPARED,
            session = VaultTestFixtures.sessionA(
                displayName = "New ☃",
                access = "opaque-access-002",
                refresh = "opaque-refresh-002",
            ),
            pendingRefresh = null,
        ).copy(
            supersededSessions = VaultSupersededSessions(
                listOf(VaultSupersededSession(5L, source)),
            ),
        )
        val snapshot = VaultSnapshot(6L, current)
        val produced = VaultRecordCodec.encode(snapshot)
        assertArrayEquals(independentEncodeV2(snapshot), produced)
        val decoded = VaultRecordCodec.decode(produced)
        assertEquals(snapshot, decoded)
        assertEquals(body, decoded.record?.supersededSessions?.entries?.single()?.record?.pendingRefresh?.attempt?.requestJson)
        assertEquals("Tëst ☃ 健身", decoded.record?.supersededSessions?.entries?.single()?.record?.session?.account?.displayName)
        assertEquals(1_700_000_000_123L, decoded.record?.supersededSessions?.entries?.single()?.record?.session?.accessExpiresAtMillis)
    }

    @Test
    fun v2StrictRefusals() {
        val snapshot = VaultSnapshot(6L, archivedCurrent())
        val valid = independentEncodeV2(snapshot)
        assertEquals(snapshot, VaultRecordCodec.decode(valid))
        assertDecodeFailure(VaultFailure.UNREADABLE, valid + byteArrayOf(0x00))
        assertDecodeFailure(VaultFailure.UNREADABLE, valid.copyOfRange(0, valid.size - 1))
        assertDecodeFailure(VaultFailure.UNSUPPORTED_VERSION, patchVersion(valid, 3))
        assertDecodeFailure(VaultFailure.UNSUPPORTED_VERSION, patchVersion(valid, 0))
        assertDecodeFailure(VaultFailure.UNSUPPORTED_VERSION, patchVersion(valid, Int.MAX_VALUE))
        val v1bytes = VaultTestFixtures.independentEncode(VaultTestFixtures.snapshot())
        assertDecodeFailure(VaultFailure.UNSUPPORTED_VERSION, patchVersion(v1bytes, 3))
    }

    @Test
    fun v2RejectsEmptyArchiveAndRecursiveOrInconsistentEntries() {
        val bare = VaultTestFixtures.recordA(
            binding = VaultTestFixtures.bindingA(8L),
            session = VaultTestFixtures.sessionA(refresh = "opaque-refresh-002"),
        )
        val emptyBos = ByteArrayOutputStream()
        DataOutputStream(emptyBos).use { out ->
            out.writeInt(VaultTestFixtures.RECORD_MAGIC)
            out.writeInt(2)
            out.writeLong(6L)
            out.writeByte(1)
            writeRecordBody(out, bare)
            out.writeInt(0)
        }
        assertDecodeFailure(VaultFailure.UNREADABLE, emptyBos.toByteArray())

        // Recursive evidence: a nested archive suffix smuggled after an entry's
        // record body is trailing grammar and must be refused.
        val valid = independentEncodeV2(VaultSnapshot(6L, archivedCurrent()))
        val nestedSuffixBos = ByteArrayOutputStream()
        nestedSuffixBos.write(valid)
        DataOutputStream(nestedSuffixBos).use { out ->
            out.writeInt(1)
            out.writeLong(9L)
            writeRecordBody(out, oldRecord(generation = 6L, refresh = "opaque-refresh-009"))
        }
        assertDecodeFailure(VaultFailure.UNREADABLE, nestedSuffixBos.toByteArray())

        // Declared two entries but only one present: truncation, not allocation.
        val truncated = valid.copyOf()
        val bareLen = VaultTestFixtures.independentEncode(
            VaultSnapshot(6L, bare.copy(supersededSessions = VaultSupersededSessions(emptyList()))),
        ).size
        ByteBuffer.allocate(4).putInt(2).array().copyInto(truncated, bareLen)
        assertDecodeFailure(VaultFailure.UNREADABLE, truncated)

        // Foreign entry (account-B binding AND matching account-B session)
        // mixed under an account-A current record is inconsistent.
        val foreignBos = ByteArrayOutputStream()
        DataOutputStream(foreignBos).use { out ->
            out.writeInt(VaultTestFixtures.RECORD_MAGIC)
            out.writeInt(2)
            out.writeLong(6L)
            out.writeByte(1)
            writeRecordBody(out, bare)
            out.writeInt(1)
            out.writeLong(5L)
            writeRecordBody(out, foreignRecord(generation = 7L))
        }
        assertDecodeFailure(VaultFailure.UNREADABLE, foreignBos.toByteArray())
    }

    @Test
    fun v2CountValidatedBeforeAllocation() {
        val current = archivedCurrent()
        val snapshot = VaultSnapshot(6L, current)
        val valid = independentEncodeV2(snapshot)
        val bareCurrent = current.copy(supersededSessions = VaultSupersededSessions(emptyList()))
        val countAt = VaultTestFixtures.independentEncode(VaultSnapshot(6L, bareCurrent)).size
        val huge = valid.copyOf()
        ByteBuffer.allocate(4).putInt(Int.MAX_VALUE).array().copyInto(huge, countAt)
        assertDecodeFailure(VaultFailure.UNREADABLE, huge)
        val negative = valid.copyOf()
        ByteBuffer.allocate(4).putInt(-1).array().copyInto(negative, countAt)
        assertDecodeFailure(VaultFailure.UNREADABLE, negative)
    }

    // ---- CAS fences on a seeded valid rev6 snapshot (CAS 6 -> 7) ----

    @Test
    fun casRejectsArchiveRemovalAndValidEntrySubstitutionAsInvalidRecord() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (vault, _) = seededRev6Vault(dispatcher = dispatcher)
        assertEquals(6L, vault.load().revision)
        val stored = vault.load().record
        requireNotNull(stored)

        // Removal of the stored archive via an otherwise VALID bare record is a
        // primitive archive-preservation violation: INVALID_RECORD, not a
        // revision conflict.
        val stripped = stored.copy(supersededSessions = VaultSupersededSessions(emptyList()))
        try {
            vault.compareAndSet(6L, stripped)
            fail("expected INVALID_RECORD for archive removal")
        } catch (e: SessionVaultException) {
            assertEquals(VaultFailure.INVALID_RECORD, e.failure)
        }
        // Substitution with a DIFFERENT otherwise-valid archive still modifies
        // retained evidence: INVALID_RECORD.
        val substituted = stored.copy(
            supersededSessions = VaultSupersededSessions(
                listOf(
                    VaultSupersededSession(
                        4L, oldRecord(generation = 6L, refresh = "opaque-refresh-006"),
                    ),
                ),
            ),
        )
        try {
            vault.compareAndSet(6L, substituted)
            fail("expected INVALID_RECORD for archive substitution")
        } catch (e: SessionVaultException) {
            assertEquals(VaultFailure.INVALID_RECORD, e.failure)
        }
        assertEquals(6L, vault.load().revision)
    }

    @Test
    fun pendingCurrentReplacementWithoutExactEvidenceRefusesButExactAppendSucceeds() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // Stored rev6: uncertain CURRENT record (pending token == grant
        // refresh) already carrying the rev5 archive.
        val currentPending = VaultTestFixtures.pendingRefresh(
            operationId = VaultTestFixtures.OP_PREPARED,
            attempt = VaultTestFixtures.attemptFor(
                key = VaultTestFixtures.KEY_B,
                refresh = "opaque-refresh-002",
            ),
        )
        val stored = VaultTestFixtures.recordA(
            binding = VaultTestFixtures.bindingA(8L),
            operationId = VaultTestFixtures.OP_PREPARED,
            phase = VaultSessionPhase.PREPARED,
            session = VaultTestFixtures.sessionA(refresh = "opaque-refresh-002"),
            pendingRefresh = currentPending,
        ).copy(
            supersededSessions = VaultSupersededSessions(
                listOf(
                    VaultSupersededSession(
                        5L, oldRecord(generation = 7L, refresh = "opaque-refresh-001"),
                    ),
                ),
            ),
        )
        val cipher = VaultTestFixtures.FakeCipher()
        val storage = VaultTestFixtures.FakeStorage(cipher.seal(independentEncodeV2(VaultSnapshot(6L, stored))))
        val vault = EncryptedSessionVault(storage, VaultTestFixtures.FakeCipher(), dispatcher, Mutex())
        assertEquals(6L, vault.load().revision)

        // Binding/operation-changing replacement WITHOUT the exact appended
        // evidence: standalone model-valid, but the CAS write guard refuses
        // with INVALID_RECORD and writes nothing.
        val bareReplacement = VaultTestFixtures.recordA(
            binding = VaultTestFixtures.bindingA(9L),
            operationId = VaultTestFixtures.OP_PENDING,
            phase = VaultSessionPhase.PREPARED,
            session = VaultTestFixtures.sessionA(refresh = "opaque-refresh-003"),
            pendingRefresh = null,
        )
        try {
            vault.compareAndSet(6L, bareReplacement)
            fail("expected INVALID_RECORD for replacement without appended evidence")
        } catch (e: SessionVaultException) {
            assertEquals(VaultFailure.INVALID_RECORD, e.failure)
        }
        assertEquals(6L, vault.load().revision)

        // Exact append plus the new grant commits atomically in one CAS.
        val exactTarget = bareReplacement.copy(
            supersededSessions = VaultSupersededSessions(
                stored.supersededSessions.entries + VaultSupersededSession(
                    6L,
                    stored.copy(supersededSessions = VaultSupersededSessions(emptyList())),
                ),
            ),
        )
        val committed = vault.compareAndSet(6L, exactTarget)
        assertEquals(7L, committed.revision)
        assertEquals(exactTarget, committed.record)
        assertEquals(2, committed.record?.supersededSessions?.entries?.size)
        assertEquals("opaque-refresh-003", committed.record?.session?.refreshToken?.value)
    }

    @Test
    fun staleExpectedRevisionConflictsWithoutTouchingEvidence() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (vault, _) = seededRev6Vault(dispatcher = dispatcher)
        val stored = vault.load().record
        requireNotNull(stored)
        // Unknown actual revision: CONFLICT, retained evidence untouched.
        try {
            vault.compareAndSet(5L, stored)
            fail("expected CONFLICT for stale revision")
        } catch (e: SessionVaultException) {
            assertEquals(VaultFailure.CONFLICT, e.failure)
        }
        try {
            vault.compareAndSet(7L, stored)
            fail("expected CONFLICT for future revision")
        } catch (e: SessionVaultException) {
            assertEquals(VaultFailure.CONFLICT, e.failure)
        }
        assertEquals(6L, vault.load().revision)
        assertEquals(stored.supersededSessions, vault.load().record?.supersededSessions)
    }

    @Test
    fun immutableViolationsFailModelValidationBeforeAnyCas() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (vault, _) = seededRev6Vault(dispatcher = dispatcher)
        val stored = vault.load().record
        requireNotNull(stored)
        val single = stored.supersededSessions.entries.single()
        // Duplicate: model boundary, never a CAS conflict.
        assertModelInvalid {
            stored.copy(
                supersededSessions = VaultSupersededSessions(listOf(single, single)),
            )
        }
        // Otherwise-valid two-entry fixture (gen7/rev5 + gen8/rev6, distinct
        // tokens) under a gen9 current: reordering it is the model violation.
        val entryA = VaultSupersededSession(
            5L, oldRecord(generation = 7L, refresh = "opaque-refresh-007a"),
        )
        val entryB = VaultSupersededSession(
            6L, oldRecord(generation = 8L, refresh = "opaque-refresh-008b"),
        )
        val current9 = VaultTestFixtures.recordA(
            binding = VaultTestFixtures.bindingA(9L),
            session = VaultTestFixtures.sessionA(refresh = "opaque-refresh-009"),
        ).copy(supersededSessions = VaultSupersededSessions(listOf(entryA, entryB)))
        assertEquals(2, current9.supersededSessions.entries.size)
        assertModelInvalid {
            current9.copy(
                supersededSessions = VaultSupersededSessions(listOf(entryB, entryA)),
            )
        }
        // Reinstallation of archived credentials: model boundary.
        assertModelInvalid {
            stored.copy(session = VaultTestFixtures.sessionA(refresh = "opaque-refresh-001"))
        }
        assertEquals(6L, vault.load().revision)
    }

    @Test
    fun nullCasKeepsLegacyEmptyArchiveButRefusesNonemptyArchive() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val plain = EncryptedSessionVault(
            VaultTestFixtures.FakeStorage(null), VaultTestFixtures.FakeCipher(), dispatcher, Mutex(),
        )
        plain.compareAndSet(0L, VaultTestFixtures.recordA())
        val tombstone = plain.compareAndSet(1L, null)
        assertEquals(2L, tombstone.revision)
        assertNull(tombstone.record)

        val (archived, _) = seededRev6Vault(dispatcher = dispatcher)
        try {
            archived.compareAndSet(6L, null)
            fail("archive-bearing null CAS must refuse")
        } catch (e: SessionVaultException) {
            assertEquals(VaultFailure.INVALID_RECORD, e.failure)
        }
        assertEquals(6L, archived.load().revision)
    }

    @Test
    fun ordinaryRefreshCarriesArchiveUnchanged() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val (vault, _) = seededRev6Vault(dispatcher = dispatcher)
        val stored = vault.load().record
        requireNotNull(stored)
        val rotated = stored.copy(
            session = VaultTestFixtures.sessionA(refresh = "opaque-refresh-003"),
        )
        val next = vault.compareAndSet(6L, rotated)
        assertEquals(7L, next.revision)
        assertEquals(stored.supersededSessions, next.record?.supersededSessions)
    }
}
