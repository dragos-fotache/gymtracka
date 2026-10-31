package com.dragosfotache.gymtracka.data.sync.session

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex

internal const val SESSION_VAULT_PRODUCTION_NAMESPACE: String = "gymtracka-session-vault"
internal const val SESSION_VAULT_KEY_ALIAS_PREFIX: String = "com.dragosfotache.gymtracka.session."
internal const val SESSION_VAULT_TEST_NAMESPACE_PREFIX: String = "gymtracka-test-"

/**
 * Inert production construction path for the encrypted session vault:
 * Keystore AES/GCM cipher over app-private `noBackupFilesDir` ciphertext.
 * Construction performs no I/O and activates nothing; the app owns one
 * process singleton. The default and test factories share one process-wide
 * mutex, and the injectable mutex on [EncryptedSessionVault] exists only for
 * independent storage tests. No multiprocess support is claimed. The test
 * factory requires a `gymtracka-test-` namespace prefix and a matching key
 * alias, so test runs cannot touch production or unrelated keys.
 */
internal object AndroidSessionVault {
    private val sharedMutex = Mutex()

    fun create(context: Context): EncryptedSessionVault =
        build(context, SESSION_VAULT_PRODUCTION_NAMESPACE)

    fun createForTesting(context: Context, namespace: String): EncryptedSessionVault {
        if (!namespace.startsWith(SESSION_VAULT_TEST_NAMESPACE_PREFIX)) {
            throw SessionVaultException(VaultFailure.INVALID_RECORD)
        }
        return build(context, namespace)
    }

    private fun build(context: Context, namespace: String): EncryptedSessionVault {
        val storage = AndroidVaultFileStorage(context = context, namespace = namespace)
        val keys = AndroidKeystoreVaultKeyProvider(alias = SESSION_VAULT_KEY_ALIAS_PREFIX + namespace)
        val cipher = AesGcmVaultCipher(keyProvider = keys)
        return EncryptedSessionVault(
            storage = storage,
            cipher = cipher,
            ioDispatcher = Dispatchers.IO,
            mutex = sharedMutex,
        )
    }
}
