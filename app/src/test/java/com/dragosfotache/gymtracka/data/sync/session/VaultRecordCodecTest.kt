package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.session.VaultTestFixtures.ACCOUNT_A
import com.dragosfotache.gymtracka.data.sync.session.VaultTestFixtures.MAX_WIRE_MILLIS
import com.dragosfotache.gymtracka.data.sync.session.VaultTestFixtures.MIN_WIRE_MILLIS
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R04a independent QA: version-1 binary record codec.
 *
 * Expectations derived from the R04a-task independent fixture grammar only.
 * Cross-checks production VaultRecordCodec against the independent builder in
 * VaultTestFixtures; malformed inputs must fail closed, never repair or fall back.
 * All persisted-decode negatives are built by splicing otherwise-valid encodings, so no
 * invalid model constructor ever preempts the decode path under test.
 */
class VaultRecordCodecTest {

    private fun assertDecodeFailure(expected: VaultFailure, bytes: ByteArray): SessionVaultException {
        try {
            VaultRecordCodec.decode(bytes)
            fail("expected SessionVaultException($expected)")
        } catch (e: SessionVaultException) {
            assertEquals(expected, e.failure)
            assertNull("cause must stay closed", e.cause)
            assertFalse(
                "error must not leak account material",
                e.message.orEmpty().contains(ACCOUNT_A),
            )
            return e
        }
        throw AssertionError("unreachable")
    }

    // ---- Grammar-derived layout helpers (published field order, no production code) ----
    // Layout: magic(4) version(4) revision(8) recordPresent(1)
    //   [originLen(4) originBytes] [accountLen(4) accountBytes] generation(8)
    //   [opLen(4) opBytes] phase(1) namePresent(1) [nameLen(4) nameBytes]?
    //   [accessLen(4) accessBytes] accessExpires(8) [refreshLen(4) refreshBytes] refreshExpires(8)
    //   pendingPresent(1) ([pOpLen(4) pOpBytes] [keyLen(4) keyBytes] [bodyLen(4) bodyBytes])?

    private data class FieldLayout(
        val recordPresentAt: Int = 16,
        val originLenAt: Int = 17,
        val originBytesAt: Int = 21,
        val originLen: Int,
        val accountLenAt: Int,
        val accountBytesAt: Int,
        val accountLen: Int,
        val generationAt: Int,
        val opLenAt: Int,
        val opBytesAt: Int,
        val opLen: Int,
        val phaseAt: Int,
        val namePresentAt: Int,
        val nameLenAt: Int,
        val nameBytesAt: Int,
        val nameLen: Int,
        val accessLenAt: Int,
        val accessBytesAt: Int,
        val accessLen: Int,
        val accessExpiresAt: Int,
        val refreshLenAt: Int,
        val refreshBytesAt: Int,
        val refreshLen: Int,
        val refreshExpiresAt: Int,
        val pendingPresentAt: Int,
    )

    private fun layoutOf(
        origin: String,
        accountId: String,
        operationId: String,
        displayName: String?,
        accessToken: String,
        refreshToken: String,
    ): FieldLayout {
        fun utf8(s: String): Int = s.toByteArray(StandardCharsets.UTF_8).size
        val oLen = utf8(origin)
        val aLen = utf8(accountId)
        val opLen = utf8(operationId)
        val nLen = if (displayName == null) 0 else utf8(displayName)
        val accLen = utf8(accessToken)
        val refLen = utf8(refreshToken)
        val accountLenAt = 21 + oLen
        val accountBytesAt = accountLenAt + 4
        val generationAt = accountBytesAt + aLen
        val opLenAt = generationAt + 8
        val opBytesAt = opLenAt + 4
        val phaseAt = opBytesAt + opLen
        val namePresentAt = phaseAt + 1
        val nameLenAt = namePresentAt + 1
        val nameBytesAt = nameLenAt + 4
        val accessLenAt = if (displayName == null) namePresentAt + 1 else nameBytesAt + nLen
        val accessBytesAt = accessLenAt + 4
        val accessExpiresAt = accessBytesAt + accLen
        val refreshLenAt = accessExpiresAt + 8
        val refreshBytesAt = refreshLenAt + 4
        val refreshExpiresAt = refreshBytesAt + refLen
        val pendingPresentAt = refreshExpiresAt + 8
        return FieldLayout(
            originLen = oLen,
            accountLenAt = accountLenAt,
            accountBytesAt = accountBytesAt,
            accountLen = aLen,
            generationAt = generationAt,
            opLenAt = opLenAt,
            opBytesAt = opBytesAt,
            opLen = opLen,
            phaseAt = phaseAt,
            namePresentAt = namePresentAt,
            nameLenAt = nameLenAt,
            nameBytesAt = nameBytesAt,
            nameLen = nLen,
            accessLenAt = accessLenAt,
            accessBytesAt = accessBytesAt,
            accessLen = accLen,
            accessExpiresAt = accessExpiresAt,
            refreshLenAt = refreshLenAt,
            refreshBytesAt = refreshBytesAt,
            refreshLen = refLen,
            refreshExpiresAt = refreshExpiresAt,
            pendingPresentAt = pendingPresentAt,
        )
    }

    private fun utf8(s: String): ByteArray = s.toByteArray(StandardCharsets.UTF_8)

    /** Splice a length-prefixed string field (length at [lenAt], bytes [lenAt+4, lenAt+4+oldLen)). */
    private fun spliceString(bytes: ByteArray, lenAt: Int, oldLen: Int, newPayload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(bytes, 0, lenAt)
        out.write(ByteBuffer.allocate(4).putInt(newPayload.size).array())
        out.write(newPayload)
        out.write(bytes, lenAt + 4 + oldLen, bytes.size - (lenAt + 4 + oldLen))
        return out.toByteArray()
    }

    private fun patchLong(bytes: ByteArray, at: Int, value: Long): ByteArray {
        val copy = bytes.copyOf()
        ByteBuffer.allocate(8).putLong(value).array().copyInto(copy, at)
        return copy
    }

    /** Reference fixture: revision 4, canonical origin A, ACCOUNT_A, gen 7, ACTIVE, name "Example". */
    private fun referenceSnapshot(): VaultSnapshot = VaultTestFixtures.snapshot(
        revision = 4L,
        record = VaultTestFixtures.recordA(
            binding = VaultTestFixtures.bindingA(7L),
            operationId = VaultTestFixtures.OP_ACTIVE,
            phase = VaultSessionPhase.ACTIVE,
            session = VaultTestFixtures.sessionA(
                displayName = "Example",
                access = "opaque-access-001",
                refresh = "opaque-refresh-001",
            ),
            pendingRefresh = null,
        ),
    )

    private fun referenceLayout(): FieldLayout = layoutOf(
        origin = VaultTestFixtures.ORIGIN_CANONICAL_A,
        accountId = ACCOUNT_A,
        operationId = VaultTestFixtures.OP_ACTIVE,
        displayName = "Example",
        accessToken = "opaque-access-001",
        refreshToken = "opaque-refresh-001",
    )

    // ---- Valid roundtrips against the independent grammar ----

    @Test
    fun emptySnapshotRoundtripsAndMatchesIndependentBytes() {
        val snapshot = VaultTestFixtures.emptySnapshot()
        val produced = VaultRecordCodec.encode(snapshot)
        assertArrayEquals(VaultTestFixtures.independentEncode(snapshot), produced)
        assertEquals(snapshot, VaultRecordCodec.decode(produced))
    }

    @Test
    fun activeAndPreparedPhasesRoundtrip() {
        for (phase in listOf(VaultSessionPhase.PREPARED, VaultSessionPhase.ACTIVE)) {
            val snapshot = VaultTestFixtures.snapshot(
                revision = 4L,
                record = VaultTestFixtures.recordA(phase = phase),
            )
            val produced = VaultRecordCodec.encode(snapshot)
            assertArrayEquals(VaultTestFixtures.independentEncode(snapshot), produced)
            val decoded = VaultRecordCodec.decode(produced)
            assertEquals(snapshot, decoded)
            assertEquals(phase, decoded.record?.phase)
        }
    }

    @Test
    fun nullVersusEmptyDisplayNameDistinguished() {
        val nullName = VaultTestFixtures.snapshot(
            record = VaultTestFixtures.recordA(
                session = VaultTestFixtures.sessionA(displayName = null),
            ),
        )
        val emptyName = VaultTestFixtures.snapshot(
            record = VaultTestFixtures.recordA(
                session = VaultTestFixtures.sessionA(displayName = ""),
            ),
        )
        val decodedNull = VaultRecordCodec.decode(VaultRecordCodec.encode(nullName))
        val decodedEmpty = VaultRecordCodec.decode(VaultRecordCodec.encode(emptyName))
        assertNull(decodedNull.record?.session?.account?.displayName)
        assertEquals("", decodedEmpty.record?.session?.account?.displayName)
        assertFalse(
            VaultRecordCodec.encode(nullName).contentEquals(VaultRecordCodec.encode(emptyName)),
        )
    }

    @Test
    fun tokensAndNonAsciiNameRetainedVerbatim() {
        val snapshot = VaultTestFixtures.snapshot(
            record = VaultTestFixtures.recordA(
                session = VaultTestFixtures.sessionA(
                    displayName = "Tëst ☃ 健身",
                    access = "opaque-ACCESS-ünïcode-☃-001",
                    refresh = "opaque-refresh-ünïcode-☃-002",
                ),
            ),
        )
        val decoded = VaultRecordCodec.decode(VaultRecordCodec.encode(snapshot))
        assertEquals("Tëst ☃ 健身", decoded.record?.session?.account?.displayName)
        assertEquals("opaque-ACCESS-ünïcode-☃-001", decoded.record?.session?.accessToken?.value)
        assertEquals("opaque-refresh-ünïcode-☃-002", decoded.record?.session?.refreshToken?.value)
    }

    @Test
    fun expiryBoundsPreservedExactly() {
        val snapshot = VaultTestFixtures.snapshot(
            record = VaultTestFixtures.recordA(
                session = VaultTestFixtures.sessionA(
                    accessExpires = MIN_WIRE_MILLIS,
                    refreshExpires = MAX_WIRE_MILLIS,
                ),
            ),
        )
        val decoded = VaultRecordCodec.decode(VaultRecordCodec.encode(snapshot))
        assertEquals(MIN_WIRE_MILLIS, decoded.record?.session?.accessExpiresAtMillis)
        assertEquals(MAX_WIRE_MILLIS, decoded.record?.session?.refreshExpiresAtMillis)

        val interior = VaultTestFixtures.snapshot(
            record = VaultTestFixtures.recordA(
                session = VaultTestFixtures.sessionA(
                    accessExpires = 0L,
                    refreshExpires = 1_700_000_000_123L,
                ),
            ),
        )
        val decodedInterior = VaultRecordCodec.decode(VaultRecordCodec.encode(interior))
        assertEquals(0L, decodedInterior.record?.session?.accessExpiresAtMillis)
        assertEquals(1_700_000_000_123L, decodedInterior.record?.session?.refreshExpiresAtMillis)
    }

    @Test
    fun exactRequestBodiesSurviveVerbatim() {
        val bodies = listOf(
            VaultTestFixtures.refreshBody("opaque-refresh-001"),
            VaultTestFixtures.refreshBodyWithWhitespace("opaque-refresh-001"),
            "{\"refreshToken\":\"a\\u0041b\"}",
            "{\"refreshToken\":\"t\\u00ebst-\\u2603\"}",
            "{\"refreshToken\":\"line\\nbreak\\ttab\"}",
            "{\"refreshToken\":\"quote\\\"back\\\\slash\"}",
            "{\"refreshToken\":\"\\u0000null-escape\"}",
            "{\"refreshToken\":\"1e400\"}",
            "{\"refreshToken\":\"1e999999\"}",
        )
        for (body in bodies) {
            val attempt = VaultTestFixtures.attemptFromBody(VaultTestFixtures.KEY_A, body)
            assertEquals("original requestJson must be retained exactly", body, attempt.requestJson)
            val snapshot = VaultTestFixtures.snapshot(
                record = VaultTestFixtures.recordA(
                    session = VaultTestFixtures.sessionA(refresh = attempt.refreshToken.value),
                    pendingRefresh = VaultTestFixtures.pendingRefresh(attempt = attempt),
                ),
            )
            val decoded = VaultRecordCodec.decode(VaultRecordCodec.encode(snapshot))
            assertEquals(body, decoded.record?.pendingRefresh?.attempt?.requestJson)
            assertEquals(
                body,
                VaultRecordCodec.decode(VaultTestFixtures.independentEncode(snapshot))
                    .record?.pendingRefresh?.attempt?.requestJson,
            )
        }
    }

    @Test
    fun captureBodySurvivesCodecRoundtrip() {
        val attempt = VaultTestFixtures.attemptFor()
        val snapshot = VaultTestFixtures.snapshot(
            record = VaultTestFixtures.recordA(
                pendingRefresh = VaultTestFixtures.pendingRefresh(attempt = attempt),
            ),
        )
        val decoded = VaultRecordCodec.decode(VaultRecordCodec.encode(snapshot))
        assertEquals(
            attempt.idempotencyKey,
            decoded.record?.pendingRefresh?.attempt?.idempotencyKey,
        )
        assertEquals(attempt.requestJson, decoded.record?.pendingRefresh?.attempt?.requestJson)
        assertEquals(
            attempt.refreshToken.value,
            decoded.record?.pendingRefresh?.attempt?.refreshToken?.value,
        )
    }

    @Test
    fun highPortOriginRoundtripsCanonically() {
        val snapshot = VaultTestFixtures.snapshot(
            record = VaultTestFixtures.recordA(
                binding = ServiceSessionBinding(
                    VaultTestFixtures.highPortOrigin(),
                    ACCOUNT_A,
                    11L,
                ),
            ),
        )
        val decoded = VaultRecordCodec.decode(VaultRecordCodec.encode(snapshot))
        assertEquals(
            VaultTestFixtures.ORIGIN_CANONICAL_HIGH_PORT,
            VaultTestFixtures.canonicalOriginString(decoded.record!!.binding.origin),
        )
        assertEquals(snapshot, decoded)
    }

    // ---- Explicit per-tag rejection from an otherwise-valid fixture ----

    @Test
    fun everyPresenceAndPhaseTagRejectsUnknownValues() {
        val valid = VaultTestFixtures.independentEncode(referenceSnapshot())
        val layout = referenceLayout()
        // Sanity: the reference itself decodes.
        assertEquals(referenceSnapshot(), VaultRecordCodec.decode(valid))

        val tagOffsets = mapOf(
            "recordPresent" to layout.recordPresentAt,
            "phase" to layout.phaseAt,
            "displayNamePresent" to layout.namePresentAt,
            "pendingPresent" to layout.pendingPresentAt,
        )
        for ((name, at) in tagOffsets) {
            for (bad in listOf(2, 0x7F, 0xFF)) {
                val copy = valid.copyOf()
                copy[at] = bad.toByte()
                try {
                    VaultRecordCodec.decode(copy)
                    fail("expected UNREADABLE for $name=$bad")
                } catch (e: SessionVaultException) {
                    assertEquals("$name=$bad", VaultFailure.UNREADABLE, e.failure)
                    assertNull(e.cause)
                }
            }
        }
        // Empty snapshot: flipping its recordPresent 0->1 leaves no record bytes (EOF).
        val emptyValid = VaultTestFixtures.independentEncode(VaultTestFixtures.emptySnapshot())
        val flipped = emptyValid.copyOf()
        flipped[16] = 1
        assertDecodeFailure(VaultFailure.UNREADABLE, flipped)
    }

    // ---- Origin spelling: valid suffix, canonical positive control ----

    @Test
    fun nonCanonicalOriginSpellingIsUnreadable() {
        val valid = VaultTestFixtures.independentEncode(referenceSnapshot())
        val layout = referenceLayout()
        // Positive control: re-splicing the identical canonical origin decodes fine.
        val control = spliceString(valid, layout.originLenAt, layout.originLen, utf8(VaultTestFixtures.ORIGIN_CANONICAL_A))
        assertEquals(referenceSnapshot(), VaultRecordCodec.decode(control))

        // https://sync.example.test:444 is a VALID canonical DIFFERENT origin (explicit
        // non-default port, like the :8443 high-port fixture), not a malformed spelling:
        // it is covered by the positive binding-preservation case below.
        val nonCanonical = listOf(
            "https://sync.example.test",
            "https://SYNC.EXAMPLE.TEST:443",
            "HTTPS://sync.example.test:443",
            "https://sync.example.test:443/",
            "http://sync.example.test:80",
        )
        for (literal in nonCanonical) {
            val spliced = spliceString(valid, layout.originLenAt, layout.originLen, utf8(literal))
            try {
                VaultRecordCodec.decode(spliced)
                fail("expected UNREADABLE for origin <$literal>")
            } catch (e: SessionVaultException) {
                assertEquals("origin <$literal>", VaultFailure.UNREADABLE, e.failure)
                assertNull(e.cause)
            }
        }
    }

    @Test
    fun differentValidOriginPreservedAsBinding() {
        // Explicit non-default ports are canonical DIFFERENT origins: splicing :444 over
        // the reference :443 encoding must decode successfully with the new binding.
        val valid = VaultTestFixtures.independentEncode(referenceSnapshot())
        val layout = referenceLayout()
        val spliced = spliceString(
            valid,
            layout.originLenAt,
            layout.originLen,
            utf8("https://sync.example.test:444"),
        )
        val decoded = VaultRecordCodec.decode(spliced)
        assertEquals(4L, decoded.revision)
        val record = requireNotNull(decoded.record)
        assertEquals(
            "https://sync.example.test:444",
            VaultTestFixtures.canonicalOriginString(record.binding.origin),
        )
        assertEquals(ACCOUNT_A, record.binding.accountId)
        assertEquals(7L, record.binding.sessionGeneration)
        assertEquals(VaultTestFixtures.OP_ACTIVE, record.operationId)
        assertEquals("opaque-access-001", record.session.accessToken.value)
    }

    // ---- UTF-8: otherwise-complete records with malformed string bytes ----

    @Test
    fun malformedUtf8InCompleteRecordIsUnreadable() {
        val valid = VaultTestFixtures.independentEncode(referenceSnapshot())
        val layout = referenceLayout()
        // Sanity: this strict decoder configuration is what the test assumes.
        val strict = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        try {
            strict.decode(java.nio.ByteBuffer.wrap(byteArrayOf(0xC0.toByte(), 0xAF.toByte())))
            fail("test decoder must be strict")
        } catch (_: CharacterCodingException) {
        }

        val malformedPayloads = listOf(
            byteArrayOf(0x80.toByte()),
            byteArrayOf(0xC0.toByte(), 0xAF.toByte()),
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
            byteArrayOf(0xF0.toByte(), 0x28.toByte(), 0x8C.toByte(), 0x28.toByte()),
        )
        for (payload in malformedPayloads) {
            // Malformed displayName, everything else valid and complete.
            val splicedName = spliceString(valid, layout.nameLenAt, layout.nameLen, payload)
            assertDecodeFailure(VaultFailure.UNREADABLE, splicedName)
            // Malformed access token, everything else valid and complete.
            val splicedToken = spliceString(valid, layout.accessLenAt, layout.accessLen, payload)
            assertDecodeFailure(VaultFailure.UNREADABLE, splicedToken)
        }
        // Paired valid controls with identical layout operations decode fine.
        val validUnicode = "☃健身".toByteArray(StandardCharsets.UTF_8)
        assertEquals(
            "☃健身",
            VaultRecordCodec.decode(spliceString(valid, layout.nameLenAt, layout.nameLen, validUnicode))
                .record?.session?.account?.displayName,
        )
        assertEquals(
            "☃",
            VaultRecordCodec.decode(
                spliceString(
                    VaultTestFixtures.independentEncode(
                        VaultTestFixtures.snapshot(
                            record = VaultTestFixtures.recordA(
                                session = VaultTestFixtures.sessionA(access = "☃"),
                            ),
                        ),
                    ),
                    layoutOf(
                        origin = VaultTestFixtures.ORIGIN_CANONICAL_A,
                        accountId = ACCOUNT_A,
                        operationId = VaultTestFixtures.OP_ACTIVE,
                        displayName = "Example",
                        accessToken = "☃",
                        refreshToken = "opaque-refresh-001",
                    ).accessLenAt,
                    "☃".toByteArray(StandardCharsets.UTF_8).size,
                    validUnicode.copyOfRange(0, 3),
                ),
            ).record?.session?.accessToken?.value,
        )
    }

    // ---- Complete persisted negative tables (spliced, never constructed invalid) ----

    @Test
    fun persistedInvalidAccountIdsAreUnreadable() {
        val valid = VaultTestFixtures.independentEncode(referenceSnapshot())
        val layout = referenceLayout()
        for (badAccount in listOf("not-a-uuid", "", "B5827485-C9D6-49AA-9578-7A2F637CB8BC", "x".repeat(36))) {
            assertDecodeFailure(
                VaultFailure.UNREADABLE,
                spliceString(valid, layout.accountLenAt, layout.accountLen, utf8(badAccount)),
            )
        }
    }

    @Test
    fun persistedInvalidGenerationsAreUnreadable() {
        val valid = VaultTestFixtures.independentEncode(referenceSnapshot())
        val layout = referenceLayout()
        assertDecodeFailure(VaultFailure.UNREADABLE, patchLong(valid, layout.generationAt, 0L))
        assertDecodeFailure(VaultFailure.UNREADABLE, patchLong(valid, layout.generationAt, -5L))
        assertDecodeFailure(VaultFailure.UNREADABLE, patchLong(valid, layout.generationAt, Long.MIN_VALUE))
    }

    @Test
    fun persistedInvalidOperationIdsAreUnreadable() {
        val valid = VaultTestFixtures.independentEncode(referenceSnapshot())
        val layout = referenceLayout()
        for (badOp in listOf("not-a-uuid", "")) {
            assertDecodeFailure(
                VaultFailure.UNREADABLE,
                spliceString(valid, layout.opLenAt, layout.opLen, utf8(badOp)),
            )
        }
    }

    @Test
    fun persistedEmptyTokensAreUnreadable() {
        val valid = VaultTestFixtures.independentEncode(referenceSnapshot())
        val layout = referenceLayout()
        assertDecodeFailure(
            VaultFailure.UNREADABLE,
            spliceString(valid, layout.accessLenAt, layout.accessLen, byteArrayOf()),
        )
        assertDecodeFailure(
            VaultFailure.UNREADABLE,
            spliceString(valid, layout.refreshLenAt, layout.refreshLen, byteArrayOf()),
        )
    }

    @Test
    fun persistedOutOfRangeExpiriesAreUnreadable() {
        val valid = VaultTestFixtures.independentEncode(referenceSnapshot())
        val layout = referenceLayout()
        assertDecodeFailure(
            VaultFailure.UNREADABLE,
            patchLong(valid, layout.accessExpiresAt, MIN_WIRE_MILLIS - 1),
        )
        assertDecodeFailure(
            VaultFailure.UNREADABLE,
            patchLong(valid, layout.refreshExpiresAt, MAX_WIRE_MILLIS + 1),
        )
        assertDecodeFailure(
            VaultFailure.UNREADABLE,
            patchLong(valid, layout.accessExpiresAt, Long.MIN_VALUE),
        )
        assertDecodeFailure(
            VaultFailure.UNREADABLE,
            patchLong(valid, layout.refreshExpiresAt, Long.MAX_VALUE),
        )
    }

    @Test
    fun persistedMalformedPendingIsUnreadable() {
        // Build a valid pending-bearing encoding, then splice pending fields.
        val pendingSnapshot = VaultTestFixtures.snapshot(
            revision = 2L,
            record = VaultTestFixtures.recordA(
                binding = VaultTestFixtures.bindingA(7L),
                operationId = VaultTestFixtures.OP_ACTIVE,
                phase = VaultSessionPhase.PREPARED,
                session = VaultTestFixtures.sessionA(
                    displayName = "Example",
                    access = "opaque-access-001",
                    refresh = "opaque-refresh-001",
                ),
                pendingRefresh = VaultTestFixtures.pendingRefresh(
                    operationId = VaultTestFixtures.OP_PENDING,
                    attempt = VaultTestFixtures.attemptFor(
                        key = VaultTestFixtures.KEY_A,
                        refresh = "opaque-refresh-001",
                    ),
                ),
            ),
        )
        val valid = VaultTestFixtures.independentEncode(pendingSnapshot)
        // Field-by-field: whole-snapshot equality would couple this codec test to the
        // approved ServiceRefreshAttempt identity semantics, so the valid-encoding
        // sanity compares ALL binding/grant/phase/op/revision/pending fields explicitly.
        val decodedValid = VaultRecordCodec.decode(valid)
        assertEquals(2L, decodedValid.revision)
        val decodedRecord = requireNotNull(decodedValid.record)
        assertEquals(VaultTestFixtures.OP_ACTIVE, decodedRecord.operationId)
        assertEquals(VaultSessionPhase.PREPARED, decodedRecord.phase)
        assertEquals(7L, decodedRecord.binding.sessionGeneration)
        assertEquals(ACCOUNT_A, decodedRecord.binding.accountId)
        assertEquals(
            VaultTestFixtures.ORIGIN_CANONICAL_A,
            VaultTestFixtures.canonicalOriginString(decodedRecord.binding.origin),
        )
        assertEquals("opaque-access-001", decodedRecord.session.accessToken.value)
        assertEquals("opaque-refresh-001", decodedRecord.session.refreshToken.value)
        assertEquals("Example", decodedRecord.session.account.displayName)
        val decodedPending = requireNotNull(decodedRecord.pendingRefresh)
        assertEquals(VaultTestFixtures.OP_PENDING, decodedPending.operationId)
        assertEquals(VaultTestFixtures.KEY_A, decodedPending.attempt.idempotencyKey)
        assertEquals(
            VaultTestFixtures.attemptFor(refresh = "opaque-refresh-001").requestJson,
            decodedPending.attempt.requestJson,
        )
        assertEquals("opaque-refresh-001", decodedPending.attempt.refreshToken.value)

        // Locate pending region: layout of the non-pending prefix, then pendingPresent.
        val prefix = layoutOf(
            origin = VaultTestFixtures.ORIGIN_CANONICAL_A,
            accountId = ACCOUNT_A,
            operationId = VaultTestFixtures.OP_ACTIVE,
            displayName = "Example",
            accessToken = "opaque-access-001",
            refreshToken = "opaque-refresh-001",
        )
        var pos = prefix.pendingPresentAt + 1
        fun readField(data: ByteArray, at: Int): Pair<String, Int> {
            val len = ByteBuffer.wrap(data, at, 4).int
            val bytes = data.copyOfRange(at + 4, at + 4 + len)
            return String(bytes, StandardCharsets.UTF_8) to (at + 4 + len)
        }
        val (pendingOp, afterOp) = readField(valid, pos)
        assertEquals(VaultTestFixtures.OP_PENDING, pendingOp)
        val (_, afterKey) = readField(valid, afterOp)
        val (body, _) = readField(valid, afterKey)

        // Malformed pending operation id.
        run {
            val bad = valid.copyOf()
            val opLenAt = pos
            val opLen = ByteBuffer.wrap(valid, pos, 4).int
            assertDecodeFailure(
                VaultFailure.UNREADABLE,
                spliceString(bad, opLenAt, opLen, utf8("not-a-uuid")),
            )
        }
        // Malformed pending idempotency key.
        run {
            val keyLenAt = afterOp
            val keyLen = ByteBuffer.wrap(valid, afterOp, 4).int
            assertDecodeFailure(
                VaultFailure.UNREADABLE,
                spliceString(valid, keyLenAt, keyLen, utf8("not-a-uuid")),
            )
        }
        // Malformed pending body (not a refresh body).
        run {
            val bodyLenAt = afterKey
            val bodyLen = ByteBuffer.wrap(valid, afterKey, 4).int
            assertDecodeFailure(
                VaultFailure.UNREADABLE,
                spliceString(valid, bodyLenAt, bodyLen, utf8("definitely not json")),
            )
            assertDecodeFailure(
                VaultFailure.UNREADABLE,
                spliceString(valid, bodyLenAt, bodyLen, utf8("")),
            )
            // Unquoted numeric-overflow scalars are malformed refresh bodies, even
            // though their quoted spellings are valid opaque strings (see positive
            // controls in exactRequestBodiesSurviveVerbatim).
            for (numericBody in listOf(
                "{\"refreshToken\":1e400}",
                "{\"refreshToken\":1e999999}",
                "{\"refreshToken\":123}",
                "{\"refreshToken\":true}",
                "{\"refreshToken\":null}",
            )) {
                assertDecodeFailure(
                    VaultFailure.UNREADABLE,
                    spliceString(valid, bodyLenAt, bodyLen, utf8(numericBody)),
                )
            }
        }
        // Pending token mismatch: body is a WELL-FORMED refresh body for another token.
        run {
            val otherBody = VaultTestFixtures.refreshBody("opaque-refresh-DIFFERENT")
            // The spliced body parses, but its token differs from the stored grant.
            val spliced = spliceString(
                valid,
                afterKey,
                ByteBuffer.wrap(valid, afterKey, 4).int,
                utf8(otherBody),
            )
            assertDecodeFailure(VaultFailure.UNREADABLE, spliced)
            assertTrue(body != otherBody)
        }
        // EOF inside pending region.
        assertDecodeFailure(VaultFailure.UNREADABLE, valid.copyOfRange(0, afterKey + 2))
        assertDecodeFailure(VaultFailure.UNREADABLE, valid.copyOfRange(0, valid.size - 1))
    }

    @Test
    fun persistedScalarFidelityOnMatchingGrant() {
        // The stored grant refresh is EXACTLY the scalar text: unquoted negatives then
        // reject solely via the scalar-kind fidelity gate, never via token mismatch.
        for (scalar in listOf("1e400", "1e999999")) {
            val quotedBody = "{\"refreshToken\":\"$scalar\"}"
            val attempt = VaultTestFixtures.attemptFromBody(VaultTestFixtures.KEY_A, quotedBody)
            assertEquals(scalar, attempt.refreshToken.value)
            val snapshot = VaultTestFixtures.snapshot(
                revision = 3L,
                record = VaultTestFixtures.recordA(
                    session = VaultTestFixtures.sessionA(refresh = scalar),
                    pendingRefresh = VaultTestFixtures.pendingRefresh(attempt = attempt),
                ),
            )
            val valid = VaultTestFixtures.independentEncode(snapshot)
            val decoded = VaultRecordCodec.decode(valid)
            assertEquals(quotedBody, decoded.record?.pendingRefresh?.attempt?.requestJson)
            assertEquals(scalar, decoded.record?.session?.refreshToken?.value)

            val prefix = layoutOf(
                origin = VaultTestFixtures.ORIGIN_CANONICAL_A,
                accountId = ACCOUNT_A,
                operationId = VaultTestFixtures.OP_ACTIVE,
                displayName = "Example",
                accessToken = "opaque-access-001",
                refreshToken = scalar,
            )
            var pos = prefix.pendingPresentAt + 1
            fun readLen(at: Int): Int = ByteBuffer.wrap(valid, at, 4).int
            pos += 4 + readLen(pos) // pending operation id
            pos += 4 + readLen(pos) // idempotency key
            val bodyLenAt = pos
            val unquoted = spliceString(
                valid,
                bodyLenAt,
                readLen(bodyLenAt),
                utf8("{\"refreshToken\":$scalar}"),
            )
            assertDecodeFailure(VaultFailure.UNREADABLE, unquoted)
        }
    }

    @Test
    fun refreshBodyScalarKindsRejectedAtAttemptBoundary() {
        // The pending constructor boundary rejects malformed original scalar kinds even
        // though approved attempt objects restore exactly: unquoted scalars never form
        // a restorable attempt, while the same quoted text is a valid opaque string.
        for (badBody in listOf(
            "{\"refreshToken\":1e400}",
            "{\"refreshToken\":1e999999}",
            "{\"refreshToken\":123}",
            "{\"refreshToken\":true}",
            "{\"refreshToken\":null}",
            "not json at all",
            "",
        )) {
            try {
                VaultTestFixtures.attemptFromBody(VaultTestFixtures.KEY_A, badBody)
                fail("expected safe rejection for body <$badBody>")
            } catch (e: SyncModelException) {
                assertNull("cause must stay closed", e.cause)
            }
        }
        for (quotedBody in listOf(
            "{\"refreshToken\":\"1e400\"}",
            "{\"refreshToken\":\"1e999999\"}",
        )) {
            val attempt = VaultTestFixtures.attemptFromBody(VaultTestFixtures.KEY_A, quotedBody)
            assertEquals(quotedBody, attempt.requestJson)
        }
        val numericString = VaultTestFixtures.attemptFromBody(
            VaultTestFixtures.KEY_A,
            "{\"refreshToken\":\"1e400\"}",
        )
        assertEquals("1e400", numericString.refreshToken.value)
    }

    // ---- Structural rejections ----

    @Test
    fun badMagicIsUnreadable() {
        val snapshot = VaultTestFixtures.snapshot()
        assertDecodeFailure(
            VaultFailure.UNREADABLE,
            VaultTestFixtures.encodeWithMagic(snapshot, 0x44454144),
        )
        assertDecodeFailure(VaultFailure.UNREADABLE, byteArrayOf())
        assertDecodeFailure(VaultFailure.UNREADABLE, byteArrayOf(0x47))
    }

    @Test
    fun unsupportedVersionIsDistinctAndNondestructive() {
        val snapshot = VaultTestFixtures.snapshot()
        // R09g reserves version 2 for archive-bearing records; true unknown
        // versions stay distinct (3, 0, MAX) with no assertion weakening.
        val v3 = VaultTestFixtures.encodeWithVersion(snapshot, 3)
        val v0 = VaultTestFixtures.encodeWithVersion(snapshot, 0)
        val intMax = VaultTestFixtures.encodeWithVersion(snapshot, Int.MAX_VALUE)
        assertDecodeFailure(VaultFailure.UNSUPPORTED_VERSION, v3)
        assertDecodeFailure(VaultFailure.UNSUPPORTED_VERSION, v0)
        assertDecodeFailure(VaultFailure.UNSUPPORTED_VERSION, intMax)
        // Original valid bytes still decode afterwards: never a destructive upgrade.
        assertEquals(snapshot, VaultRecordCodec.decode(VaultTestFixtures.independentEncode(snapshot)))
    }

    @Test
    fun truncationAtEveryPrefixIsUnreadable() {
        val full = VaultTestFixtures.independentEncode(VaultTestFixtures.snapshot())
        assertTrue(full.size > 16)
        val cuts = setOf(0, 1, 4, 5, 8, 9, 12, 16, 17, full.size / 2, full.size - 1)
        for (cut in cuts) {
            assertDecodeFailure(VaultFailure.UNREADABLE, full.copyOfRange(0, cut))
        }
    }

    @Test
    fun trailingBytesAreUnreadable() {
        val full = VaultTestFixtures.independentEncode(VaultTestFixtures.snapshot())
        assertDecodeFailure(VaultFailure.UNREADABLE, full + byteArrayOf(0x00))
        assertDecodeFailure(VaultFailure.UNREADABLE, full + byteArrayOf(0x01, 0x02, 0x03))
        val empty = VaultTestFixtures.independentEncode(VaultTestFixtures.emptySnapshot())
        assertDecodeFailure(VaultFailure.UNREADABLE, empty + byteArrayOf(0x00))
    }

    @Test
    fun negativeAndOversizedLengthsAreUnreadable() {
        fun patchFirstStringLength(bytes: ByteArray, length: Int): ByteArray {
            // Layout: magic(4) version(4) revision(8) present(1) then Int length.
            val copy = bytes.copyOf()
            val offset = 4 + 4 + 8 + 1
            copy[offset] = ((length ushr 24) and 0xFF).toByte()
            copy[offset + 1] = ((length ushr 16) and 0xFF).toByte()
            copy[offset + 2] = ((length ushr 8) and 0xFF).toByte()
            copy[offset + 3] = (length and 0xFF).toByte()
            return copy
        }
        val full = VaultTestFixtures.independentEncode(VaultTestFixtures.snapshot())
        assertDecodeFailure(VaultFailure.UNREADABLE, patchFirstStringLength(full, -1))
        assertDecodeFailure(VaultFailure.UNREADABLE, patchFirstStringLength(full, Int.MIN_VALUE))
        // Declared length exceeds remaining bytes: must be rejected before allocation.
        assertDecodeFailure(VaultFailure.UNREADABLE, patchFirstStringLength(full, full.size * 4))
        assertDecodeFailure(VaultFailure.UNREADABLE, patchFirstStringLength(full, Int.MAX_VALUE))
    }

    @Test
    fun modelInvalidInputsAreInvalidRecordOnEncode() {
        try {
            VaultRecordCodec.encode(VaultSnapshot(0L, VaultTestFixtures.recordA()))
            fail("expected INVALID_RECORD")
        } catch (e: SessionVaultException) {
            assertEquals(VaultFailure.INVALID_RECORD, e.failure)
            assertNull(e.cause)
        }
        try {
            VaultRecordCodec.encode(VaultSnapshot(-1L, null))
            fail("expected INVALID_RECORD")
        } catch (e: SessionVaultException) {
            assertEquals(VaultFailure.INVALID_RECORD, e.failure)
        }
    }

    @Test
    fun unpairedSurrogatesRejectedOnEncode() {
        val loneHigh = VaultTestFixtures.snapshot(
            record = VaultTestFixtures.recordA(
                session = VaultTestFixtures.sessionA(displayName = "\uD800lone"),
            ),
        )
        try {
            VaultRecordCodec.encode(loneHigh)
            fail("expected INVALID_RECORD for unpaired surrogate")
        } catch (e: SessionVaultException) {
            assertEquals(VaultFailure.INVALID_RECORD, e.failure)
            assertNull(e.cause)
        }
        val loneLow = VaultTestFixtures.snapshot(
            record = VaultTestFixtures.recordA(
                session = VaultTestFixtures.sessionA(access = "abc\uDFFF"),
            ),
        )
        try {
            VaultRecordCodec.encode(loneLow)
            fail("expected INVALID_RECORD for unpaired surrogate")
        } catch (e: SessionVaultException) {
            assertEquals(VaultFailure.INVALID_RECORD, e.failure)
        }
    }

    @Test
    fun copyValidationRefusesInvalidMutations() {
        val snapshot = VaultTestFixtures.snapshot()
        try {
            snapshot.copy(revision = -1L)
            fail("expected INVALID_RECORD copy")
        } catch (e: SessionVaultException) {
            assertEquals(VaultFailure.INVALID_RECORD, e.failure)
        }
        try {
            VaultTestFixtures.emptySnapshot().copy(record = VaultTestFixtures.recordA())
            fail("expected INVALID_RECORD copy")
        } catch (e: SessionVaultException) {
            assertEquals(VaultFailure.INVALID_RECORD, e.failure)
        }
        val record = VaultTestFixtures.recordA()
        try {
            record.copy(operationId = "bad")
            fail("expected INVALID_RECORD copy")
        } catch (e: SessionVaultException) {
            assertEquals(VaultFailure.INVALID_RECORD, e.failure)
        }
        try {
            record.copy(
                phase = VaultSessionPhase.ACTIVE,
                pendingRefresh = VaultTestFixtures.pendingRefresh(
                    attempt = VaultTestFixtures.attemptFor(refresh = "different-refresh"),
                ),
            )
            fail("expected INVALID_RECORD copy for token mismatch")
        } catch (e: SessionVaultException) {
            assertEquals(VaultFailure.INVALID_RECORD, e.failure)
        }
    }

    @Test
    fun dataClassEqualityAndRedactedDisplay() {
        val a = VaultTestFixtures.recordA()
        val b = VaultTestFixtures.recordA()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        val snapshotText = VaultTestFixtures.snapshot().toString()
        assertFalse(snapshotText.contains("opaque-access-001"))
        assertFalse(snapshotText.contains("opaque-refresh-001"))
        assertFalse(snapshotText.contains(ACCOUNT_A))
    }

    @Test
    fun ipv6OriginAndSupplementaryUnicodeRoundtrip() {
        // Bracketed IPv6 canonical origin plus paired (valid) supplementary characters.
        val snapshot = VaultTestFixtures.snapshot(
            revision = 6L,
            record = VaultTestFixtures.recordA(
                binding = ServiceSessionBinding(
                    VaultTestFixtures.ipv6Origin(),
                    ACCOUNT_A,
                    7L,
                ),
                session = VaultTestFixtures.sessionA(displayName = "A\uD83D\uDE00B \u2603"),
            ),
        )
        val produced = VaultRecordCodec.encode(snapshot)
        assertArrayEquals(VaultTestFixtures.independentEncode(snapshot), produced)
        val decoded = VaultRecordCodec.decode(produced)
        assertEquals(snapshot, decoded)
        assertEquals(
            VaultTestFixtures.ORIGIN_CANONICAL_IPV6,
            VaultTestFixtures.canonicalOriginString(decoded.record!!.binding.origin),
        )
        assertEquals("A\uD83D\uDE00B \u2603", decoded.record?.session?.account?.displayName)
    }

    @Test
    fun stringsAbove64KiBHaveNoWriteUtfCap() {
        // Strict Int-length UTF-8 framing (never writeUTF) imposes no 64KiB ceiling on
        // names, tokens or original request bodies.
        val bigName = "n".repeat(70_000)
        val bigToken = "t".repeat(70_000)
        val attempt = VaultTestFixtures.attemptFor(refresh = bigToken)
        val snapshot = VaultTestFixtures.snapshot(
            revision = 9L,
            record = VaultTestFixtures.recordA(
                session = VaultTestFixtures.sessionA(
                    displayName = bigName,
                    refresh = bigToken,
                ),
                pendingRefresh = VaultTestFixtures.pendingRefresh(attempt = attempt),
            ),
        )
        val produced = VaultRecordCodec.encode(snapshot)
        assertArrayEquals(VaultTestFixtures.independentEncode(snapshot), produced)
        val decoded = VaultRecordCodec.decode(produced)
        assertEquals(70_000, decoded.record?.session?.account?.displayName?.length)
        assertEquals(bigName, decoded.record?.session?.account?.displayName)
        assertEquals(bigToken, decoded.record?.session?.refreshToken?.value)
        assertEquals(attempt.requestJson, decoded.record?.pendingRefresh?.attempt?.requestJson)
        assertTrue((decoded.record?.pendingRefresh?.attempt?.requestJson?.length ?: 0) > 65_536)
    }
}
