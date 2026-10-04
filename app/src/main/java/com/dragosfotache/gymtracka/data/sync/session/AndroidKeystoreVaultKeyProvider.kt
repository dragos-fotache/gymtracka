package com.dragosfotache.gymtracka.data.sync.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.CancellationException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

private const val ANDROID_KEYSTORE: String = "AndroidKeyStore"
private const val VAULT_KEY_SIZE_BITS: Int = 256

/**
 * Android Keystore AES-256 key provider for the session vault. The key is
 * nonexportable, restricted to encrypt/decrypt with GCM and no padding,
 * requires randomized encryption, and carries no user-authentication or
 * StrongBox/hardware mandate. Creation is lazy: retrieval with
 * `createIfMissing = false` never generates or replaces a missing key, so a
 * deleted key fails closed instead of silently reseeding. Retrieval or
 * generation failures report unavailable with no raw cause. The alias permits
 * only the safe app prefix with the production suffix or a test-namespace
 * suffix, so tests cannot touch unrelated keys.
 */
internal class AndroidKeystoreVaultKeyProvider(alias: String) : VaultKeyProvider {
    private val keyAlias: String = validateKeyAlias(alias)

    override fun key(createIfMissing: Boolean): SecretKey {
        try {
            val store = KeyStore.getInstance(ANDROID_KEYSTORE)
            store.load(null)
            val existing = store.getKey(keyAlias, null)
            if (existing is SecretKey) {
                return existing
            }
            if (existing != null) {
                // Wrong-kind key material under our alias: never overwrite it.
                throw SessionVaultException(VaultFailure.UNAVAILABLE)
            }
            if (store.containsAlias(keyAlias)) {
                // Alias occupied without retrievable key material (for example a
                // certificate entry): never regenerate over it.
                throw SessionVaultException(VaultFailure.UNAVAILABLE)
            }
            if (!createIfMissing) {
                throw SessionVaultException(VaultFailure.UNAVAILABLE)
            }
            generateKey()
            val created = store.getKey(keyAlias, null)
            if (created is SecretKey) {
                return created
            }
            throw SessionVaultException(VaultFailure.UNAVAILABLE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionVaultException) {
            throw e
        } catch (e: Exception) {
            throw SessionVaultException(VaultFailure.UNAVAILABLE)
        }
    }

    private fun generateKey() {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .setKeySize(VAULT_KEY_SIZE_BITS)
            .build()
        generator.init(spec)
        generator.generateKey()
    }
}

private val KEY_ALIAS_SUFFIX_SHAPE = Regex("^[a-z][a-z0-9_-]{0,63}$")

private fun validateKeyAlias(alias: String): String {
    if (!alias.startsWith(SESSION_VAULT_KEY_ALIAS_PREFIX)) {
        throw SessionVaultException(VaultFailure.INVALID_RECORD)
    }
    val suffix = alias.removePrefix(SESSION_VAULT_KEY_ALIAS_PREFIX)
    if (!KEY_ALIAS_SUFFIX_SHAPE.matches(suffix)) {
        throw SessionVaultException(VaultFailure.INVALID_RECORD)
    }
    if (suffix != SESSION_VAULT_PRODUCTION_NAMESPACE && !suffix.startsWith(SESSION_VAULT_TEST_NAMESPACE_PREFIX)) {
        throw SessionVaultException(VaultFailure.INVALID_RECORD)
    }
    return alias
}
