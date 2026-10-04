package com.dragosfotache.gymtracka.data.sync.session

import javax.crypto.SecretKey

/**
 * Storage-only seams for the inert encrypted session vault. No HTTP, refresh
 * orchestration, access provider, Room, engine, background, UI, or repository
 * behavior lives behind these boundaries.
 */

/** Revision-checked session store: whole-snapshot atomic replacement only. */
internal interface SessionVault {
    /** Reads, decrypts, and validates the committed snapshot; empty store reads as revision zero. */
    suspend fun load(): VaultSnapshot

    /**
     * Atomically replaces the whole snapshot when the committed revision
     * exactly equals [expectedRevision]. A null record writes a
     * positive-revision encrypted tombstone, never a reset to zero.
     */
    suspend fun compareAndSet(expectedRevision: Long, record: VaultSessionRecord?): VaultSnapshot
}

/**
 * Durable ciphertext file seam. [read] returns null only for a genuinely
 * absent committed file and never stores plaintext; [replace] performs one
 * complete durable replacement or throws, with an uncertain outcome after the
 * commit point. Supplied arrays are copied defensively, never retained.
 */
internal interface VaultFileStorage {
    fun read(): ByteArray?

    fun replace(bytes: ByteArray)
}

/** Authenticated-encryption seam: randomized seal, verifying open. */
internal interface VaultCipher {
    fun seal(plaintext: ByteArray): ByteArray

    fun open(sealed: ByteArray): ByteArray
}

/**
 * Key seam. Retrieval with `createIfMissing = false` never generates or
 * replaces a missing key; creation happens lazily on seal only.
 */
internal fun interface VaultKeyProvider {
    fun key(createIfMissing: Boolean): SecretKey
}
