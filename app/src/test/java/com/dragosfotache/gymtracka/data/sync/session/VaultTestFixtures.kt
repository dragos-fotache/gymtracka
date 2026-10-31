package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.ServiceAccountRef
import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.Key
import javax.crypto.SecretKey

/**
 * fixtures.
 */
internal object VaultTestFixtures {
    const val RECORD_MAGIC: Int = 0x47545331 // GTS1
    const val RECORD_VERSION: Int = 1
    const val SEALED_MAGIC: Int = 0x47545631 // GTV1
    const val SEALED_VERSION: Int = 1
    const val SEALED_AAD_SUFFIX: String = "GymTracka.SessionVault"

    const val MIN_WIRE_MILLIS: Long = -62167219200000L
    const val MAX_WIRE_MILLIS: Long = 253402300799999L

    const val ACCOUNT_A: String = "b5827485-c9d6-49aa-9578-7a2f637cb8bc"
    const val ACCOUNT_B: String = "123e4567-e89b-12d3-a456-426614174000"
    const val OP_ACTIVE: String = "11111111-1111-1111-1111-111111111111"
    const val OP_PREPARED: String = "22222222-2222-2222-2222-222222222222"
    const val OP_PENDING: String = "33333333-3333-3333-3333-333333333333"
    const val KEY_A: String = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    const val KEY_B: String = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"

    const val ORIGIN_CANONICAL_A: String = "https://sync.example.test:443"
    const val ORIGIN_CANONICAL_HIGH_PORT: String = "https://sync.example.test:8443"
    const val ORIGIN_CANONICAL_IPV6: String = "https://[2001:db8::1]:443"

    fun ipv6Origin(): ServiceOrigin = ServiceOrigin.parse(ORIGIN_CANONICAL_IPV6)

    fun originA(): ServiceOrigin = ServiceOrigin.parse(ORIGIN_CANONICAL_A)

    fun highPortOrigin(): ServiceOrigin = ServiceOrigin.parse(ORIGIN_CANONICAL_HIGH_PORT)

    fun accountA(displayName: String? = "Example"): ServiceAccountRef =
        ServiceAccountRef(ACCOUNT_A, displayName)

    fun accessToken(value: String = "opaque-access-001"): OpaqueToken = OpaqueToken(value)

    fun refreshToken(value: String = "opaque-refresh-001"): OpaqueToken = OpaqueToken(value)

    fun sessionA(
        displayName: String? = "Example",
        access: String = "opaque-access-001",
        accessExpires: Long = 1_700_000_000_000L,
        refresh: String = "opaque-refresh-001",
        refreshExpires: Long = 1_700_000_000_000L + 30L * 24 * 60 * 60 * 1000,
    ): ServiceSession = ServiceSession(
        accountA(displayName),
        accessToken(access),
        accessExpires,
        refreshToken(refresh),
        refreshExpires,
    )

    fun bindingA(generation: Long = 7L): ServiceSessionBinding =
        ServiceSessionBinding(originA(), ACCOUNT_A, generation)

    fun attemptFor(
        key: String = KEY_A,
        refresh: String = "opaque-refresh-001",
    ): ServiceRefreshAttempt = ServiceRefreshAttempt.capture(key, refreshToken(refresh))

    fun attemptFromBody(
        key: String = KEY_A,
        body: String,
    ): ServiceRefreshAttempt = ServiceRefreshAttempt.fromJson(key, body)

    fun pendingRefresh(
        operationId: String = OP_PENDING,
        attempt: ServiceRefreshAttempt = attemptFor(),
    ): VaultPendingRefresh = VaultPendingRefresh(operationId, attempt)

    fun recordA(
        binding: ServiceSessionBinding = bindingA(),
        operationId: String = OP_ACTIVE,
        phase: VaultSessionPhase = VaultSessionPhase.ACTIVE,
        session: ServiceSession = sessionA(),
        pendingRefresh: VaultPendingRefresh? = null,
    ): VaultSessionRecord = VaultSessionRecord(
        binding,
        operationId,
        phase,
        session,
        pendingRefresh,
    )

    fun snapshot(
        revision: Long = 1L,
        record: VaultSessionRecord? = recordA(),
    ): VaultSnapshot = VaultSnapshot(revision, record)

    fun emptySnapshot(): VaultSnapshot = VaultSnapshot(0L, null)

    /** Canonical stored origin spelling derived independently from origin properties. */
    fun canonicalOriginString(origin: ServiceOrigin): String {
        val host = origin.host
        val bracketed = if (host.contains(":")) "[$host]" else host
        return "${origin.scheme}://$bracketed:${origin.port}"
    }

    // ---- Independent version-1 binary record builder (spec grammar only) ----

    fun independentEncode(snapshot: VaultSnapshot): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeInt(RECORD_MAGIC)
            out.writeInt(RECORD_VERSION)
            out.writeLong(snapshot.revision)
            val record = snapshot.record
            if (record == null) {
                out.writeByte(0)
            } else {
                out.writeByte(1)
                writeString(out, canonicalOriginString(record.binding.origin))
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
        }
        return bos.toByteArray()
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    fun encodeWithVersion(snapshot: VaultSnapshot, version: Int): ByteArray {
        val bytes = independentEncode(snapshot)
        // Patch version field (offset 4..8) big-endian.
        bytes[4] = ((version ushr 24) and 0xFF).toByte()
        bytes[5] = ((version ushr 16) and 0xFF).toByte()
        bytes[6] = ((version ushr 8) and 0xFF).toByte()
        bytes[7] = (version and 0xFF).toByte()
        return bytes
    }

    fun encodeWithMagic(snapshot: VaultSnapshot, magic: Int): ByteArray {
        val bytes = independentEncode(snapshot)
        bytes[0] = ((magic ushr 24) and 0xFF).toByte()
        bytes[1] = ((magic ushr 16) and 0xFF).toByte()
        bytes[2] = ((magic ushr 8) and 0xFF).toByte()
        bytes[3] = (magic and 0xFF).toByte()
        return bytes
    }

    // ---- Fakes for vault logic tests ----

    class FakeStorage(
        initial: ByteArray? = null,
    ) : VaultFileStorage {
        var committed: ByteArray? = initial?.copyOf()
            private set
        var reads: Int = 0
            private set
        var writes: Int = 0
            private set
        var readFailure: RuntimeException? = null
        var writeFailure: RuntimeException? = null
        val writtenSizes: MutableList<Int> = mutableListOf()

        override fun read(): ByteArray? {
            reads += 1
            readFailure?.let { throw it }
            return committed?.copyOf()
        }

        override fun replace(bytes: ByteArray) {
            writes += 1
            writtenSizes.add(bytes.size)
            writeFailure?.let { throw it }
            committed = bytes.copyOf()
        }

        fun committedCopy(): ByteArray? = committed?.copyOf()
    }

    class FakeCipher(
        private val prefix: Byte = 0x5A,
    ) : VaultCipher {
        var seals: Int = 0
            private set
        var opens: Int = 0
            private set
        var sealFailure: RuntimeException? = null
        var openFailure: RuntimeException? = null

        override fun seal(plaintext: ByteArray): ByteArray {
            seals += 1
            sealFailure?.let { throw it }
            val input = plaintext.copyOf()
            val out = ByteArray(input.size + 1)
            out[0] = prefix
            input.copyInto(out, 1)
            return out
        }

        override fun open(sealed: ByteArray): ByteArray {
            opens += 1
            openFailure?.let { throw it }
            val input = sealed.copyOf()
            if (input.isEmpty() || input[0] != prefix) {
                throw SessionVaultException(VaultFailure.UNREADABLE)
            }
            return input.copyOfRange(1, input.size)
        }
    }

    class TrackingKeyProvider(
        val key: SecretKey,
    ) : VaultKeyProvider {
        val calls: MutableList<Boolean> = mutableListOf()
        var failure: RuntimeException? = null

        override fun key(createIfMissing: Boolean): SecretKey {
            calls.add(createIfMissing)
            failure?.let { throw it }
            return key
        }
    }

    fun generateAesKey(): SecretKey {
        val gen = javax.crypto.KeyGenerator.getInstance("AES")
        gen.init(256)
        return gen.generateKey()
    }

    fun aesKeyBytes(key: Key): ByteArray = key.encoded
        ?: throw AssertionError("AES key must expose encoded bytes on JVM")

    // ---- Small independent JSON body helpers ----

    /** Minimal valid refresh body for the given opaque refresh value (independent spelling). */
    fun refreshBody(refresh: String): String = "{\"refreshToken\":\"$refresh\"}"

    fun refreshBodyWithWhitespace(refresh: String): String =
        " { \"refreshToken\" : \"$refresh\" } "

    fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
