package com.dragosfotache.gymtracka.data.sync.session

import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.ServiceAccountRef
import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

private const val RECORD_MAGIC: Int = 0x47545331
private const val RECORD_VERSION_V1: Int = 1
private const val RECORD_VERSION_V2: Int = 2

/**
 * Version 1 binary record grammar for vault snapshots without retained
 * evidence, independent of any other serialization in the app; version 2
 * extends it only with an archive suffix for archive-bearing records:
 *
 * ```text
 * Int magic 0x47545331 (GTS1); Int version 1|2; Long revision; byte recordPresent
 * if recordPresent == 1:
 *   string canonical origin (https://host:port; IPv6 bracketed, explicit effective port)
 *   string accountId; Long sessionGeneration; string operationId; byte phase (0 PREPARED, 1 ACTIVE)
 *   byte displayNamePresent; [string displayName]
 *   string accessToken; Long accessExpiresAtMillis; string refreshToken; Long refreshExpiresAtMillis
 *   byte pendingPresent
 *   if pendingPresent == 1: string pending operationId; string idempotencyKey; string ORIGINAL requestJson
 *   if version == 2:
 *     Int archiveCount (positive); repeat archiveCount:
 *       Long sourceRevision; <record grammar above without recursive archive suffix>
 * ```
 *
 * Snapshots with no archive retain EXACT v1 encoding (version 1, no suffix)
 * and all existing v1 records decode unchanged. Version 2 appears only for
 * archive-bearing records. Big-endian signed Java data values, strict UTF-8
 * length-prefixed strings (Int byte length, then bytes), one byte per
 * presence/phase tag with only 0/1 accepted. No Java modified-UTF8,
 * replacement decoding, surplus bytes, or content cap. The exact original
 * `requestJson` (whitespace, order, escapes, non-ASCII, control escapes)
 * survives the round trip; tokens and display names are retained verbatim
 * with nullable name distinct from empty.
 */
internal object VaultRecordCodec {
    fun encode(snapshot: VaultSnapshot): ByteArray {
        val archive = snapshot.record?.supersededSessions?.entries.orEmpty()
        val useV2 = archive.isNotEmpty()
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeInt(RECORD_MAGIC)
            data.writeInt(if (useV2) RECORD_VERSION_V2 else RECORD_VERSION_V1)
            data.writeLong(snapshot.revision)
            val record = snapshot.record
            data.writeByte(if (record == null) 0 else 1)
            if (record != null) {
                writeRecordFields(data, record)
                if (useV2) {
                    data.writeInt(archive.size)
                    for (entry in archive) {
                        data.writeLong(entry.sourceRevision)
                        writeRecordFields(data, entry.record)
                    }
                }
            }
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): VaultSnapshot {
        // Owned input copy: cleared best effort on every exit. Decoded Strings
        // are immutable copies; clearing bytes makes no GC erasure claim.
        val owned = bytes.copyOf()
        try {
            val input = DataInputStream(ByteArrayInputStream(owned))
            try {
                return decodeBody(input)
            } catch (e: SessionVaultException) {
                if (e.failure == VaultFailure.UNSUPPORTED_VERSION) {
                    throw e
                }
                throw SessionVaultException(VaultFailure.UNREADABLE)
            } catch (e: Exception) {
                throw SessionVaultException(VaultFailure.UNREADABLE)
            }
        } finally {
            owned.fill(0)
        }
    }

    private fun decodeBody(input: DataInputStream): VaultSnapshot {
        val magic = input.readInt()
        if (magic != RECORD_MAGIC) {
            throw SessionVaultException(VaultFailure.UNREADABLE)
        }
        val version = input.readInt()
        if (version != RECORD_VERSION_V1 && version != RECORD_VERSION_V2) {
            throw SessionVaultException(VaultFailure.UNSUPPORTED_VERSION)
        }
        val revision = input.readLong()
        val recordPresent = readTag(input)
        if (recordPresent == 0) {
            if (version == RECORD_VERSION_V2) {
                throw SessionVaultException(VaultFailure.UNREADABLE)
            }
            if (input.available() != 0) {
                throw SessionVaultException(VaultFailure.UNREADABLE)
            }
            try {
                return VaultSnapshot(revision, null)
            } catch (e: Exception) {
                throw SessionVaultException(VaultFailure.UNREADABLE)
            }
        }
        val main = decodeRecordFields(input)
        if (version == RECORD_VERSION_V1) {
            if (input.available() != 0) {
                throw SessionVaultException(VaultFailure.UNREADABLE)
            }
            try {
                return VaultSnapshot(revision, main)
            } catch (e: Exception) {
                throw SessionVaultException(VaultFailure.UNREADABLE)
            }
        }
        // Version 2 archive suffix: positive count validated against
        // remaining input before any archive allocation; no arbitrary cap.
        // Each entry needs at least its 8-byte source revision, so a count
        // above remaining/8 is malformed without further reads.
        if (input.available() < 4) {
            throw SessionVaultException(VaultFailure.UNREADABLE)
        }
        val archiveCount = input.readInt()
        if (archiveCount < 1 || archiveCount > input.available() / 8) {
            throw SessionVaultException(VaultFailure.UNREADABLE)
        }
        val archived = ArrayList<VaultSupersededSession>(minOf(archiveCount, 16))
        var index = 0
        while (index < archiveCount) {
            if (input.available() < 8) {
                throw SessionVaultException(VaultFailure.UNREADABLE)
            }
            val sourceRevision = input.readLong()
            if (sourceRevision < 1L) {
                throw SessionVaultException(VaultFailure.UNREADABLE)
            }
            val archivedRecord = decodeRecordFields(input)
            try {
                archived.add(VaultSupersededSession(sourceRevision, archivedRecord))
            } catch (e: Exception) {
                throw SessionVaultException(VaultFailure.UNREADABLE)
            }
            index += 1
        }
        if (input.available() != 0) {
            throw SessionVaultException(VaultFailure.UNREADABLE)
        }
        try {
            val archive = VaultSupersededSessions(archived)
            val combined = main.copy(supersededSessions = archive)
            return VaultSnapshot(revision, combined)
        } catch (e: Exception) {
            throw SessionVaultException(VaultFailure.UNREADABLE)
        }
    }

    private fun writeRecordFields(data: DataOutputStream, record: VaultSessionRecord) {
        writeString(data, canonicalOriginSpelling(record.binding.origin))
        writeString(data, record.binding.accountId)
        data.writeLong(record.binding.sessionGeneration)
        writeString(data, record.operationId)
        data.writeByte(if (record.phase == VaultSessionPhase.PREPARED) 0 else 1)
        val displayName = record.session.account.displayName
        data.writeByte(if (displayName == null) 0 else 1)
        if (displayName != null) {
            writeString(data, displayName)
        }
        writeString(data, record.session.accessToken.value)
        data.writeLong(record.session.accessExpiresAtMillis)
        writeString(data, record.session.refreshToken.value)
        data.writeLong(record.session.refreshExpiresAtMillis)
        val pending = record.pendingRefresh
        data.writeByte(if (pending == null) 0 else 1)
        if (pending != null) {
            writeString(data, pending.operationId)
            writeString(data, pending.attempt.idempotencyKey)
            writeString(data, pending.attempt.requestJson)
        }
    }

    private fun decodeRecordFields(input: DataInputStream): VaultSessionRecord {
        val originText = readString(input)
        val origin = try {
            ServiceOrigin.parse(originText)
        } catch (e: Exception) {
            throw SessionVaultException(VaultFailure.UNREADABLE)
        }
        if (canonicalOriginSpelling(origin) != originText) {
            throw SessionVaultException(VaultFailure.UNREADABLE)
        }
        val accountId = readString(input)
        val generation = input.readLong()
        val operationId = readString(input)
        val phase = if (readTag(input) == 0) VaultSessionPhase.PREPARED else VaultSessionPhase.ACTIVE
        val displayName = if (readTag(input) == 0) null else readString(input)
        val accessToken = readString(input)
        val accessExpires = input.readLong()
        val refreshToken = readString(input)
        val refreshExpires = input.readLong()
        val session = try {
            ServiceSession(
                account = ServiceAccountRef(accountId, displayName),
                accessToken = OpaqueToken(accessToken),
                accessExpiresAtMillis = accessExpires,
                refreshToken = OpaqueToken(refreshToken),
                refreshExpiresAtMillis = refreshExpires,
            )
        } catch (e: Exception) {
            throw SessionVaultException(VaultFailure.UNREADABLE)
        }
        val pending = if (readTag(input) == 0) {
            null
        } else {
            val pendingOperationId = readString(input)
            val idempotencyKey = readString(input)
            val requestJson = readString(input)
            try {
                VaultPendingRefresh(
                    operationId = pendingOperationId,
                    attempt = ServiceRefreshAttempt.fromJson(idempotencyKey, requestJson),
                )
            } catch (e: Exception) {
                throw SessionVaultException(VaultFailure.UNREADABLE)
            }
        }
        val binding = try {
            ServiceSessionBinding(origin, accountId, generation)
        } catch (e: Exception) {
            throw SessionVaultException(VaultFailure.UNREADABLE)
        }
        try {
            return VaultSessionRecord(binding, operationId, phase, session, pending)
        } catch (e: Exception) {
            throw SessionVaultException(VaultFailure.UNREADABLE)
        }
    }

    private fun readTag(input: DataInputStream): Int {
        val tag = input.readByte().toInt()
        if (tag != 0 && tag != 1) {
            throw SessionVaultException(VaultFailure.UNREADABLE)
        }
        return tag
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        if (length < 0 || length > input.available()) {
            throw SessionVaultException(VaultFailure.UNREADABLE)
        }
        val bytes = ByteArray(length)
        try {
            input.readFully(bytes)
            return strictUtf8(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun strictUtf8(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw SessionVaultException(VaultFailure.UNREADABLE)
        }
    }

    private fun writeString(data: DataOutputStream, value: String) {
        rejectUnpairedSurrogates(value)
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        data.writeInt(bytes.size)
        data.write(bytes)
    }

    private fun rejectUnpairedSurrogates(value: String) {
        var index = 0
        while (index < value.length) {
            val unit = value[index]
            if (unit.isHighSurrogate()) {
                if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) {
                    throw SessionVaultException(VaultFailure.INVALID_RECORD)
                }
                index += 2
            } else if (unit.isLowSurrogate()) {
                throw SessionVaultException(VaultFailure.INVALID_RECORD)
            } else {
                index += 1
            }
        }
    }
}

/** Canonical stored origin spelling: `https://host:port` with IPv6 bracketed and the effective port explicit. */
internal fun canonicalOriginSpelling(origin: ServiceOrigin): String {
    val host = origin.host
    val bracketed = if (host.contains(':')) "[$host]" else host
    return "https://$bracketed:${origin.port}"
}
