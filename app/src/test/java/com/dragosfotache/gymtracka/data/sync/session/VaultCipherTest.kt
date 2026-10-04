package com.dragosfotache.gymtracka.data.sync.session

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R04a independent QA: AES/GCM vault cipher over real JCA.
 *
 * Derived from R04a-task encryption rules only. Uses a fake VaultKeyProvider backed by a
 * real AES-256 key so sealed-format, IV, tag, AAD and failure-mapping behavior is verified
 * against actual crypto, without Android Keystore or network. An independent JCA
 * seal/open pair (literal spec AAD + header + GCMParameterSpec(128, 12-byte IV)) proves
 * AES/GCM/128-bit-tag/header binding rather than production-only symmetry.
 */
class VaultCipherTest {

    private fun cipherWithTracking(): Pair<AesGcmVaultCipher, VaultTestFixtures.TrackingKeyProvider> {
        val tracking = VaultTestFixtures.TrackingKeyProvider(VaultTestFixtures.generateAesKey())
        return AesGcmVaultCipher(tracking) to tracking
    }

    private fun assertClosed(failure: VaultFailure, block: () -> Unit): SessionVaultException {
        try {
            block()
            fail("expected SessionVaultException($failure)")
        } catch (e: SessionVaultException) {
            assertEquals(failure, e.failure)
            assertNull("no raw crypto cause", e.cause)
            return e
        }
        throw AssertionError("unreachable")
    }

    // ---- Independent JCA reference (literal spec values, no production code) ----

    private object IndependentJca {
        const val MAGIC: Int = 0x47545631 // GTV1
        const val VERSION: Int = 1
        const val TAG_BITS: Int = 128
        const val IV_BYTES: Int = 12
        val AAD_SUFFIX: ByteArray = "GymTracka.SessionVault".toByteArray(Charsets.UTF_8)

        fun header(): ByteArray = ByteBuffer.allocate(8).putInt(MAGIC).putInt(VERSION).array()

        fun sealIndependent(key: javax.crypto.SecretKey, plain: ByteArray): ByteArray {
            val header = header()
            val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(header + AAD_SUFFIX)
            val ciphertextAndTag = cipher.doFinal(plain.copyOf())
            return header + iv + ciphertextAndTag
        }

        fun openIndependent(key: javax.crypto.SecretKey, sealed: ByteArray): ByteArray {
            require(sealed.size >= 36) { "sealed too short" }
            val header = sealed.copyOfRange(0, 8)
            require(header.contentEquals(header())) { "header mismatch" }
            val iv = sealed.copyOfRange(8, 20)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(header + AAD_SUFFIX)
            return cipher.doFinal(sealed.copyOfRange(20, sealed.size))
        }
    }

    @Test
    fun roundtripPreservesExactBytes() {
        val (cipher, _) = cipherWithTracking()
        val vectors = listOf(
            byteArrayOf(0x01),
            "hello vault".toByteArray(Charsets.UTF_8),
            VaultTestFixtures.independentEncode(VaultTestFixtures.snapshot()),
            ByteArray(1024) { it.toByte() },
            "ünïcode ☃ \u0000 control".toByteArray(Charsets.UTF_8),
        )
        for (plain in vectors) {
            val sealed = cipher.seal(plain.copyOf())
            assertEquals(plain.toList(), cipher.open(sealed).toList())
        }
    }

    @Test
    fun independentJcaSealOpensViaProduction() {
        // Proves production open implements AES/GCM/128-bit tag with the spec AAD,
        // not merely symmetry with its own seal.
        val key = VaultTestFixtures.generateAesKey()
        val tracking = VaultTestFixtures.TrackingKeyProvider(key)
        val production = AesGcmVaultCipher(tracking)
        val plain = VaultTestFixtures.independentEncode(VaultTestFixtures.snapshot())
        val independentSealed = IndependentJca.sealIndependent(key, plain)
        assertArrayEquals(plain, production.open(independentSealed))
        assertTrue("open must call key(false), never key(true)", tracking.calls.all { !it })
    }

    @Test
    fun productionSealOpensViaIndependentJca() {
        // Proves production seal emits real AES/GCM with 12-byte IV, 128-bit tag and
        // the spec AAD: independent JCA open must succeed and header tamper must fail.
        val key = VaultTestFixtures.generateAesKey()
        val production = AesGcmVaultCipher(VaultTestFixtures.TrackingKeyProvider(key))
        val plain = "independent-open probe ☃".toByteArray(Charsets.UTF_8)
        val sealed = production.seal(plain.copyOf())
        assertArrayEquals(plain, IndependentJca.openIndependent(key, sealed))

        // Header binding: flipping an AAD-covered header byte breaks independent open.
        val tamperedHeader = sealed.copyOf().apply { this[2] = (this[2] + 1).toByte() }
        try {
            IndependentJca.openIndependent(key, tamperedHeader)
            fail("expected independent JCA failure on tampered header")
        } catch (_: Exception) {
        }
        // Production must likewise fail closed on the same tamper.
        assertClosed(VaultFailure.UNREADABLE) { production.open(tamperedHeader) }
    }

    @Test
    fun sealIsRandomizedWithFreshTwelveByteIvAndNoPlaintext() {
        val (cipher, _) = cipherWithTracking()
        val plain = VaultTestFixtures.independentEncode(VaultTestFixtures.snapshot())
        val first = cipher.seal(plain.copyOf())
        val second = cipher.seal(plain.copyOf())
        assertFalse("randomized encryption required", first.contentEquals(second))
        // Both open to the same plaintext.
        assertArrayEquals(plain, cipher.open(first))
        assertArrayEquals(plain, cipher.open(second))

        // Sealed layout: magic(4) version(4) IV(12) ciphertext+tag(>=17 for nonempty plain).
        assertTrue(first.size >= 36)
        assertEquals(0x47.toByte(), first[0])
        assertEquals(0x54.toByte(), first[1])
        assertEquals(0x56.toByte(), first[2])
        assertEquals(0x31.toByte(), first[3])
        // IV halves differ across seals with overwhelming probability.
        assertFalse(first.copyOfRange(8, 20).contentEquals(second.copyOfRange(8, 20)))
        // Ciphertext must not contain the raw record magic or token bytes.
        assertFalse(
            VaultTestFixtures.containsBytes(
                first,
                "opaque-access-001".toByteArray(Charsets.UTF_8),
            ),
        )
        assertFalse(
            VaultTestFixtures.containsBytes(
                first,
                "opaque-refresh-001".toByteArray(Charsets.UTF_8),
            ),
        )
    }

    @Test
    fun tamperedMagicIvCiphertextTagTruncationFailUnreadable() {
        val (cipher, _) = cipherWithTracking()
        val plain = "tamper-evident plaintext".toByteArray(Charsets.UTF_8)
        val sealed = cipher.seal(plain.copyOf())

        fun tampered(mutator: (ByteArray) -> ByteArray): ByteArray = mutator(sealed.copyOf())

        // Bad magic stays UNREADABLE (same-magic other-version is UNSUPPORTED, covered below).
        assertClosed(VaultFailure.UNREADABLE) {
            cipher.open(tampered { it.apply { this[0] = (this[0] + 1).toByte() } })
        }
        // Flipped IV byte.
        assertClosed(VaultFailure.UNREADABLE) {
            cipher.open(tampered { it.apply { this[9] = (this[9] + 1).toByte() } })
        }
        // Flipped ciphertext byte (first byte after 20-byte header).
        assertClosed(VaultFailure.UNREADABLE) {
            cipher.open(tampered { it.apply { this[20] = (this[20] + 1).toByte() } })
        }
        // Flipped tag byte (last byte).
        assertClosed(VaultFailure.UNREADABLE) {
            cipher.open(tampered { it.apply { this[this.size - 1] = (this[this.size - 1] + 1).toByte() } })
        }
        // Truncations, including tag cut and minimum-length violation.
        assertClosed(VaultFailure.UNREADABLE) {
            cipher.open(sealed.copyOfRange(0, sealed.size - 1))
        }
        assertClosed(VaultFailure.UNREADABLE) {
            cipher.open(sealed.copyOfRange(0, 35))
        }
        assertClosed(VaultFailure.UNREADABLE) { cipher.open(byteArrayOf()) }
        assertClosed(VaultFailure.UNREADABLE) { cipher.open(ByteArray(20)) }
        // Trailing garbage after a valid sealed value.
        assertClosed(VaultFailure.UNREADABLE) { cipher.open(sealed + byteArrayOf(0x00)) }
    }

    @Test
    fun supportedMagicOtherVersionIsUnsupported() {
        val (cipher, _) = cipherWithTracking()
        val sealed = cipher.seal("version probe".toByteArray(Charsets.UTF_8))
        // Same magic, version field (bytes 4..8 big-endian) set to 2: per spec this MUST
        // report UNSUPPORTED_VERSION distinctly, so production checks version before tag.
        val v2 = sealed.copyOf()
        v2[4] = 0
        v2[5] = 0
        v2[6] = 0
        v2[7] = 2
        try {
            cipher.open(v2)
            fail("expected UNSUPPORTED_VERSION")
        } catch (e: SessionVaultException) {
            assertEquals(VaultFailure.UNSUPPORTED_VERSION, e.failure)
            assertNull(e.cause)
        }
        val v0 = sealed.copyOf()
        v0[4] = 0
        v0[5] = 0
        v0[6] = 0
        v0[7] = 0
        assertClosed(VaultFailure.UNSUPPORTED_VERSION) { cipher.open(v0) }
    }

    @Test
    fun wrongKeyAndMissingKeyFailClosed() {
        val keyA = VaultTestFixtures.generateAesKey()
        val keyB = VaultTestFixtures.generateAesKey()
        val sealCipher = AesGcmVaultCipher(VaultTestFixtures.TrackingKeyProvider(keyA))
        val sealed = sealCipher.seal("wrong-key probe".toByteArray(Charsets.UTF_8))

        val openWithWrong = AesGcmVaultCipher(VaultTestFixtures.TrackingKeyProvider(keyB))
        assertClosed(VaultFailure.UNREADABLE) { openWithWrong.open(sealed) }

        // Missing/unreadable key retrieval maps to UNAVAILABLE, never silent empty.
        val failingProvider = VaultTestFixtures.TrackingKeyProvider(keyA).apply {
            failure = RuntimeException("simulated-keystore-failure")
        }
        val failingCipher = AesGcmVaultCipher(failingProvider)
        assertClosed(VaultFailure.UNAVAILABLE) {
            failingCipher.open(sealed.copyOf())
        }
        assertClosed(VaultFailure.UNAVAILABLE) {
            failingCipher.seal("x".toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun createFlagsSealCreatesButOpenNeverCreates() {
        val tracking = VaultTestFixtures.TrackingKeyProvider(VaultTestFixtures.generateAesKey())
        val cipher = AesGcmVaultCipher(tracking)
        cipher.seal("flag probe".toByteArray(Charsets.UTF_8))
        assertEquals(listOf(true), tracking.calls)

        tracking.calls.clear()
        val sealed = cipher.seal("second".toByteArray(Charsets.UTF_8))
        tracking.calls.clear()
        cipher.open(sealed)
        assertTrue("open must call key(false), never key(true)", tracking.calls.isNotEmpty())
        assertTrue(tracking.calls.all { !it })

        // Structurally invalid sealed input must not create a key either.
        tracking.calls.clear()
        assertClosed(VaultFailure.UNREADABLE) { cipher.open(byteArrayOf(1, 2, 3)) }
        assertTrue(tracking.calls.none { it })
    }

    @Test
    fun cipherOwnsInputsDefensively() {
        val (cipher, _) = cipherWithTracking()
        val plain = "ownership probe".toByteArray(Charsets.UTF_8)
        val plainCopy = plain.copyOf()
        val sealed = cipher.seal(plain)
        // Mutating caller plaintext afterwards must not affect sealed output.
        plain.fill(0x00)
        assertArrayEquals(plainCopy, cipher.open(sealed))

        // Mutating the sealed input array handed to open must not corrupt a retained copy.
        val sealedCopy = sealed.copyOf()
        val opened = cipher.open(sealedCopy)
        sealedCopy.fill(0x7F)
        assertArrayEquals(plainCopy, opened)
        assertArrayEquals(plainCopy, cipher.open(sealed))
    }

    @Test
    fun errorsCarryNoSecretsOrCauses() {
        val (cipher, tracking) = cipherWithTracking()
        val secret = "top-secret-token-☃".toByteArray(Charsets.UTF_8)
        val sealed = cipher.seal(secret.copyOf())
        val tampered = sealed.copyOf().apply { this[size - 1] = (last() + 1).toByte() }
        val openError = try {
            cipher.open(tampered)
            fail("expected UNREADABLE")
            throw AssertionError("unreachable")
        } catch (caught: SessionVaultException) {
            caught
        }
        assertNull(openError.cause)
        val message = openError.message.orEmpty()
        assertFalse(message.contains("top-secret-token"))
        assertFalse(
            VaultTestFixtures.containsBytes(message.toByteArray(Charsets.UTF_8), secret),
        )
        // Key bytes never appear in messages.
        val keyBytes = VaultTestFixtures.aesKeyBytes(tracking.key)
        assertFalse(
            VaultTestFixtures.containsBytes(message.toByteArray(Charsets.UTF_8), keyBytes),
        )
    }

    @Test
    fun keystoreAliasInvalidShapeRejectedAsSafeSessionVaultError() {
        for (badAlias in listOf(
            "",
            "   ",
            "UNRELATED.KEY",
            "../escape",
            "com.other.app.key",
            "com.dragosfotache.gymtracka.session." + "x".repeat(256),
        )) {
            try {
                AndroidKeystoreVaultKeyProvider(badAlias)
                fail("expected SessionVaultException for alias <$badAlias>")
            } catch (e: SessionVaultException) {
                assertEquals(VaultFailure.INVALID_RECORD, e.failure)
                assertNull("cause must stay closed", e.cause)
                // Guard the exact nonempty original alias: trim() can produce an empty
                // needle whose contains() is always true and would fail vacuously.
                if (badAlias.isNotEmpty()) {
                    assertFalse(
                        "error must not echo alias material",
                        e.message.orEmpty().contains(badAlias),
                    )
                }
            }
        }
    }
}
