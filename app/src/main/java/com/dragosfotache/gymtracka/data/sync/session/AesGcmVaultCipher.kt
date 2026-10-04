package com.dragosfotache.gymtracka.data.sync.session

import kotlinx.coroutines.CancellationException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

private const val SEALED_MAGIC: Int = 0x47545631
private const val SEALED_VERSION: Int = 1
private const val SEALED_HEADER_BYTES: Int = 8
private const val SEALED_IV_BYTES: Int = 12
private const val SEALED_TAG_BITS: Int = 128
private const val SEALED_TAG_BYTES: Int = 16
private const val SEALED_MIN_BYTES: Int = SEALED_HEADER_BYTES + SEALED_IV_BYTES + SEALED_TAG_BYTES
private const val SEALED_AAD_SUFFIX: String = "GymTracka.SessionVault"

/**
 * AES/GCM/NoPadding vault cipher over a [VaultKeyProvider] key. Each seal
 * uses a fresh provider-generated 12-byte IV with a 128-bit tag; decryption
 * with `createIfMissing = false` never generates or replaces a missing key.
 *
 * Sealed format: Int magic 0x47545631 (GTV1), Int version 1, exactly 12 IV
 * bytes, then ciphertext plus the 16-byte tag. The AAD is the 8-byte
 * big-endian header (magic/version) followed by UTF-8
 * `GymTracka.SessionVault`. Malformed magic, length, or tag fails closed;
 * another version under the supported magic reports unsupported. Errors carry
 * no raw crypto cause and no IV, token, or body material. Supplied arrays are
 * copied defensively and transient copies are cleared best effort.
 */
internal class AesGcmVaultCipher(private val keyProvider: VaultKeyProvider) : VaultCipher {
    override fun seal(plaintext: ByteArray): ByteArray {
        val input = plaintext.copyOf()
        var iv: ByteArray? = null
        try {
            val key = providerKey(createIfMissing = true)
            // The Keystore key requires randomized encryption, so the IV must be
            // provider-generated: init without parameters, then take cipher.iv.
            // A caller-supplied encrypt IV is rejected for such keys.
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            iv = cipher.iv
            if (iv == null || iv.size != SEALED_IV_BYTES) {
                throw SessionVaultException(VaultFailure.UNAVAILABLE)
            }
            cipher.updateAAD(sealedAad())
            val ciphertext = cipher.doFinal(input)
            // Do not assume the provider default: the sealed tail must be exactly
            // the 128-bit tag appended to the plaintext length.
            if (ciphertext.size != input.size + SEALED_TAG_BYTES) {
                ciphertext.fill(0)
                throw SessionVaultException(VaultFailure.UNAVAILABLE)
            }
            val out = ByteBuffer.allocate(SEALED_HEADER_BYTES + SEALED_IV_BYTES + ciphertext.size)
            out.putInt(SEALED_MAGIC)
            out.putInt(SEALED_VERSION)
            out.put(iv)
            out.put(ciphertext)
            return out.array()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionVaultException) {
            throw e
        } catch (e: Exception) {
            throw SessionVaultException(VaultFailure.UNAVAILABLE)
        } finally {
            input.fill(0)
            iv?.fill(0)
        }
    }

    override fun open(sealed: ByteArray): ByteArray {
        val input = sealed.copyOf()
        var iv: ByteArray? = null
        var ciphertext: ByteArray? = null
        try {
            if (input.size < SEALED_MIN_BYTES) {
                throw SessionVaultException(VaultFailure.UNREADABLE)
            }
            val header = ByteBuffer.wrap(input, 0, SEALED_HEADER_BYTES)
            val magic = header.int
            val version = header.int
            if (magic != SEALED_MAGIC) {
                throw SessionVaultException(VaultFailure.UNREADABLE)
            }
            if (version != SEALED_VERSION) {
                throw SessionVaultException(VaultFailure.UNSUPPORTED_VERSION)
            }
            iv = input.copyOfRange(SEALED_HEADER_BYTES, SEALED_HEADER_BYTES + SEALED_IV_BYTES)
            ciphertext = input.copyOfRange(SEALED_HEADER_BYTES + SEALED_IV_BYTES, input.size)
            val key = providerKey(createIfMissing = false)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(SEALED_TAG_BITS, iv))
            cipher.updateAAD(sealedAad())
            return cipher.doFinal(ciphertext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionVaultException) {
            throw e
        } catch (e: Exception) {
            throw SessionVaultException(VaultFailure.UNREADABLE)
        } finally {
            input.fill(0)
            iv?.fill(0)
            ciphertext?.fill(0)
        }
    }

    private fun providerKey(createIfMissing: Boolean) = try {
        keyProvider.key(createIfMissing)
    } catch (e: CancellationException) {
        throw e
    } catch (e: SessionVaultException) {
        throw e
    } catch (e: Exception) {
        throw SessionVaultException(VaultFailure.UNAVAILABLE)
    }

    private fun sealedAad(): ByteArray {
        val header = ByteBuffer.allocate(SEALED_HEADER_BYTES)
        header.putInt(SEALED_MAGIC)
        header.putInt(SEALED_VERSION)
        return header.array() + SEALED_AAD_SUFFIX.toByteArray(StandardCharsets.UTF_8)
    }
}
