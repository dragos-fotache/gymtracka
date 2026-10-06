package com.dragosfotache.gymtracka.data.sync.session

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.ServiceAccountRef
import com.dragosfotache.gymtracka.data.sync.api.ServiceRefreshAttempt
import com.dragosfotache.gymtracka.data.sync.api.ServiceSession
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import javax.crypto.SecretKey
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/**
 * R04a independent QA: actual Android storage / Keystore / packaging checks.
 *
 * Derived from R04a-task encryption/Android-I/O/backup rules only. androidTest does not
 * compile src/test fixtures, so this file keeps small PRIVATE independent fixtures below
 * (canonical values only, no production symmetry). Uses test-owned isolated
 * namespaces/aliases (gymtracka-test-*) and cleans only own paths/keys/outside targets;
 * the production namespace/alias and app data are never touched. No network, no sleeps.
 */
@RunWith(AndroidJUnit4::class)
class SessionVaultAndroidTest {

    // ---- Private independent Android fixtures (no src/test dependency) ----

    private companion object {
        const val ACCOUNT_A: String = "b5827485-c9d6-49aa-9578-7a2f637cb8bc"
        const val OP_ACTIVE: String = "11111111-1111-1111-1111-111111111111"
        const val OP_PREPARED: String = "22222222-2222-2222-2222-222222222222"
        const val OP_PENDING: String = "33333333-3333-3333-3333-333333333333"
        const val KEY_A: String = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val ORIGIN_CANONICAL_A: String = "https://sync.example.test:443"
        const val PRODUCTION_NAMESPACE: String = "gymtracka-session-vault"
    }

    private fun originA(): ServiceOrigin = ServiceOrigin.parse(ORIGIN_CANONICAL_A)

    private fun sessionA(
        displayName: String? = "Example",
        access: String = "opaque-access-001",
        refresh: String = "opaque-refresh-001",
    ): ServiceSession = ServiceSession(
        ServiceAccountRef(ACCOUNT_A, displayName),
        OpaqueToken(access),
        1_700_000_000_000L,
        OpaqueToken(refresh),
        1_700_000_000_000L + 30L * 24 * 60 * 60 * 1000,
    )

    private fun bindingA(generation: Long = 7L): ServiceSessionBinding =
        ServiceSessionBinding(originA(), ACCOUNT_A, generation)

    private fun attemptFor(
        key: String = KEY_A,
        refresh: String = "opaque-refresh-001",
    ): ServiceRefreshAttempt = ServiceRefreshAttempt.capture(key, OpaqueToken(refresh))

    private fun attemptFromBody(key: String, body: String): ServiceRefreshAttempt =
        ServiceRefreshAttempt.fromJson(key, body)

    private fun pendingRefresh(
        operationId: String = OP_PENDING,
        attempt: ServiceRefreshAttempt = attemptFor(),
    ): VaultPendingRefresh = VaultPendingRefresh(operationId, attempt)

    private fun recordA(
        binding: ServiceSessionBinding = bindingA(),
        operationId: String = OP_ACTIVE,
        phase: VaultSessionPhase = VaultSessionPhase.ACTIVE,
        session: ServiceSession = sessionA(),
        pendingRefresh: VaultPendingRefresh? = null,
    ): VaultSessionRecord = VaultSessionRecord(binding, operationId, phase, session, pendingRefresh)

    private fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    // ---- Harness ----

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val usedNamespaces: MutableList<String> = mutableListOf()
    private val usedOutsideDirs: MutableList<File> = mutableListOf()

    private fun testNamespace(suffix: String): String {
        val namespace = "gymtracka-test-r04a-$suffix"
        usedNamespaces.add(namespace)
        return namespace
    }

    private fun testAlias(namespace: String): String =
        "com.dragosfotache.gymtracka.session.$namespace"

    private fun androidKeyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }

    private fun noBackupDir(): File = context.noBackupFilesDir

    private fun namespaceDir(namespace: String): File = File(noBackupDir(), namespace)

    private fun vaultFile(namespace: String): File = File(namespaceDir(namespace), "session.vault")

    private fun tempFile(namespace: String): File = File(namespaceDir(namespace), "session.vault.new")

    private fun isSymlinkNoFollow(path: String): Boolean {
        return try {
            val stat = Os.lstat(path)
            (stat.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFLNK
        } catch (_: Exception) {
            false
        }
    }

    private fun removeLinkNoFollow(path: String) {
        try {
            if (isSymlinkNoFollow(path)) Os.remove(path)
        } catch (_: Exception) {
        }
    }

    private fun deleteRecursivelyOwned(root: File) {
        if (!root.exists() && !isSymlinkNoFollow(root.absolutePath)) return
        removeLinkNoFollow(root.absolutePath)
        if (root.isDirectory && !isSymlinkNoFollow(root.absolutePath)) {
            root.listFiles()?.forEach { deleteRecursivelyOwned(it) }
        }
        try {
            root.delete()
        } catch (_: Exception) {
        }
    }

    @After
    fun cleanOnlyOwnTestArtifacts() {
        for (namespace in usedNamespaces) {
            // lstat first: never follow a symlink during cleanup, remove the link itself.
            removeLinkNoFollow(namespaceDir(namespace).absolutePath)
            removeLinkNoFollow(tempFile(namespace).absolutePath)
            removeLinkNoFollow(vaultFile(namespace).absolutePath)
            try {
                tempFile(namespace).delete()
                vaultFile(namespace).delete()
            } catch (_: Exception) {
            }
            try {
                val dir = namespaceDir(namespace)
                if (dir.isDirectory) {
                    dir.listFiles()?.forEach { deleteRecursivelyOwned(it) }
                    dir.delete()
                } else {
                    dir.delete()
                }
            } catch (_: Exception) {
            }
            try {
                androidKeyStore().deleteEntry(testAlias(namespace))
            } catch (_: Exception) {
            }
        }
        usedNamespaces.clear()
        for (outside in usedOutsideDirs) {
            deleteRecursivelyOwned(outside)
        }
        usedOutsideDirs.clear()
    }

    private fun deleteNamespacePath(namespace: String) {
        removeLinkNoFollow(namespaceDir(namespace).absolutePath)
        try {
            tempFile(namespace).delete()
            vaultFile(namespace).delete()
            val dir = namespaceDir(namespace)
            if (dir.isDirectory) {
                dir.listFiles()?.forEach { deleteRecursivelyOwned(it) }
                dir.delete()
            }
        } catch (_: Exception) {
        }
        try {
            androidKeyStore().deleteEntry(testAlias(namespace))
        } catch (_: Exception) {
        }
    }

    private fun testVault(namespace: String, faults: VaultWriteFaults = VaultWriteFaults {}): EncryptedSessionVault {
        val storage = AndroidVaultFileStorage(context, namespace, faults)
        return EncryptedSessionVault(
            storage,
            AesGcmVaultCipher(AndroidKeystoreVaultKeyProvider(testAlias(namespace))),
        )
    }

    // ---- Construction performs no I/O until use ----

    @Test
    fun factoryConstructionCreatesNoDirectoryOrKeyUntilIo() = runTest {
        val namespace = testNamespace("noio")
        deleteNamespacePath(namespace)
        assertFalse(namespaceDir(namespace).exists())
        assertFalse(androidKeyStore().containsAlias(testAlias(namespace)))

        // Construction alone (storage + test factory) must not touch disk or Keystore.
        val storage = AndroidVaultFileStorage(context, namespace)
        assertFalse(namespaceDir(namespace).exists())
        assertFalse(androidKeyStore().containsAlias(testAlias(namespace)))
        val vault = AndroidSessionVault.createForTesting(context, namespace)
        assertFalse(namespaceDir(namespace).exists())
        assertFalse(androidKeyStore().containsAlias(testAlias(namespace)))

        // Read-only load of the absent store returns empty WITHOUT creating anything.
        val empty = vault.load()
        assertEquals(0L, empty.revision)
        assertNull(empty.record)
        assertFalse(namespaceDir(namespace).exists())
        assertFalse(vaultFile(namespace).exists())
        assertFalse(androidKeyStore().containsAlias(testAlias(namespace)))
        assertEquals(storage.directory, namespaceDir(namespace))
        assertEquals(storage.file, vaultFile(namespace))
    }

    @Test
    fun productionFactoryInertConstruction() {
        // Inert construction: no I/O on Main, no directory/key creation. Never writes
        // through the production instance; only inspects absence preservation.
        val resort = namespaceDir(PRODUCTION_NAMESPACE)
        val hadDir = resort.exists()
        val productionAlias = "com.dragosfotache.gymtracka.session.$PRODUCTION_NAMESPACE"
        val hadAlias = try {
            androidKeyStore().containsAlias(productionAlias)
        } catch (_: Exception) {
            false
        }
        val production = AndroidSessionVault.create(context)
        assertNotNull(production)
        assertEquals(hadDir, resort.exists())
        if (!hadDir) assertFalse(vaultFile(PRODUCTION_NAMESPACE).exists())
        if (!hadAlias) {
            assertFalse(androidKeyStore().containsAlias(productionAlias))
        }
    }

    @Test
    fun deviceProtectedContextRejected() {
        val namespace = testNamespace("protected")
        val protected = context.createDeviceProtectedStorageContext()
        try {
            AndroidVaultFileStorage(protected, namespace)
            fail("device-protected context must be rejected fail-closed")
        } catch (e: SessionVaultException) {
            assertNull("cause must stay closed", e.cause)
        }
        assertFalse(namespaceDir(namespace).exists())
        // The wrapper check applies to the PASSED context, not applicationContext: a
        // plain credential-protected context still constructs without I/O.
        val plainNamespace = testNamespace("protected-ok")
        val plain = AndroidVaultFileStorage(context.applicationContext, plainNamespace)
        assertEquals(namespaceDir(plainNamespace), plain.directory)
        assertEquals(vaultFile(plainNamespace), plain.file)
    }

    // ---- Roundtrip / reopen / tombstone journey on real Keystore + files ----

    @Test
    fun roundtripReopenAttemptsAndTombstoneJourney() = runTest {
        val namespace = testNamespace("journey")
        deleteNamespacePath(namespace)
        val attemptBody = " { \"refreshToken\" : \"opaque-refresh-001\" } "
        val attempt = attemptFromBody(KEY_A, attemptBody)
        val prepared = recordA(
            binding = bindingA(7L),
            operationId = OP_PREPARED,
            phase = VaultSessionPhase.PREPARED,
            session = sessionA(refresh = attempt.refreshToken.value),
            pendingRefresh = pendingRefresh(operationId = OP_PENDING, attempt = attempt),
        )
        val vault = AndroidSessionVault.createForTesting(context, namespace)
        val s1 = vault.compareAndSet(0L, prepared)
        assertEquals(1L, s1.revision)
        assertEquals(VaultSessionPhase.PREPARED, s1.record?.phase)
        assertEquals(attemptBody, s1.record?.pendingRefresh?.attempt?.requestJson)

        // Reopen through a NEW factory instance over the same namespace.
        val reopened = AndroidSessionVault.createForTesting(context, namespace)
        val reloaded = reopened.load()
        assertEquals(1L, reloaded.revision)
        assertEquals(attemptBody, reloaded.record?.pendingRefresh?.attempt?.requestJson)
        assertEquals(KEY_A, reloaded.record?.pendingRefresh?.attempt?.idempotencyKey)

        // Rotation completing in ONE commit then tombstone; reopen observes each step.
        // Ordinary rotation preserves activation identity (binding + operation)
        // while completing the grant and clearing pending; it fabricates no archive.
        val rotated = recordA(
            binding = prepared.binding,
            operationId = prepared.operationId,
            phase = VaultSessionPhase.ACTIVE,
            session = sessionA(access = "opaque-access-002", refresh = "opaque-refresh-002"),
            pendingRefresh = null,
        )
        assertEquals(prepared.binding, rotated.binding)
        assertEquals(prepared.operationId, rotated.operationId)
        assertTrue(rotated.supersededSessions.entries.isEmpty())
        val s2 = reopened.compareAndSet(1L, rotated)
        assertEquals(2L, s2.revision)
        val tombstone = reopened.compareAndSet(2L, null)
        assertEquals(3L, tombstone.revision)
        assertNull(tombstone.record)

        val reread = AndroidSessionVault.createForTesting(context, namespace).load()
        assertEquals(3L, reread.revision)
        assertNull(reread.record)

        // ABA guard: delayed old revision cannot reinstall credentials.
        try {
            reopened.compareAndSet(0L, prepared)
            fail("expected CONFLICT")
        } catch (e: SessionVaultException) {
            assertEquals(VaultFailure.CONFLICT, e.failure)
            assertNull(e.cause)
        }
    }

    @Test
    fun realKeystoreKeyIsNonexportable() = runTest {
        val namespace = testNamespace("keystore")
        deleteNamespacePath(namespace)
        val vault = AndroidSessionVault.createForTesting(context, namespace)
        vault.compareAndSet(0L, recordA())
        val store = androidKeyStore()
        assertTrue(store.containsAlias(testAlias(namespace)))
        val key = store.getKey(testAlias(namespace), null)
        assertNotNull(key)
        // AndroidKeyStore AES keys are nonexportable: encoded material must be null.
        assertNull((key as SecretKey).encoded)
    }

    @Test
    fun androidKeyHasRequiredSettingsAndRandomizedSeals() = runTest {
        val namespace = testNamespace("keysettings")
        deleteNamespacePath(namespace)
        val vault = AndroidSessionVault.createForTesting(context, namespace)
        vault.compareAndSet(0L, recordA())

        // Actual Keystore settings: AES-256, GCM/NoPadding, encrypt+decrypt, no auth gate.
        val key = androidKeyStore().getKey(testAlias(namespace), null) as SecretKey
        assertEquals("AES", key.algorithm)
        val factory = javax.crypto.SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
        val info = factory.getKeySpec(
            key,
            android.security.keystore.KeyInfo::class.java,
        ) as android.security.keystore.KeyInfo
        assertEquals(256, info.keySize)
        assertTrue(info.blockModes.contains(android.security.keystore.KeyProperties.BLOCK_MODE_GCM))
        assertTrue(
            info.encryptionPaddings.contains(
                android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE,
            ),
        )
        assertTrue(info.purposes and android.security.keystore.KeyProperties.PURPOSE_ENCRYPT != 0)
        assertTrue(info.purposes and android.security.keystore.KeyProperties.PURPOSE_DECRYPT != 0)
        assertFalse(info.isUserAuthenticationRequired)

        // Randomized-required encryption uses provider-generated IVs: two seals of the
        // same plaintext through the real provider differ, and both open.
        val cipher = AesGcmVaultCipher(AndroidKeystoreVaultKeyProvider(testAlias(namespace)))
        val plain = "provider-iv probe ☃".toByteArray(Charsets.UTF_8)
        val first = cipher.seal(plain.copyOf())
        val second = cipher.seal(plain.copyOf())
        assertFalse(first.contentEquals(second))
        assertEquals(plain.toList(), cipher.open(first).toList())
        assertEquals(plain.toList(), cipher.open(second).toList())

        // Real writes are randomized end to end: same record, successive revisions differ.
        val before = vaultFile(namespace).readBytes()
        vault.compareAndSet(1L, recordA())
        val after = vaultFile(namespace).readBytes()
        assertFalse(before.contentEquals(after))
        assertEquals(2L, vault.load().revision)
    }

    @Test
    fun keyDeletionFailsClosedWithoutRegeneration() = runTest {
        val namespace = testNamespace("keydel")
        deleteNamespacePath(namespace)
        val vault = AndroidSessionVault.createForTesting(context, namespace)
        vault.compareAndSet(0L, recordA())
        val sealedBefore = vaultFile(namespace).readBytes()

        androidKeyStore().deleteEntry(testAlias(namespace))
        assertFalse(androidKeyStore().containsAlias(testAlias(namespace)))

        // Missing key fails closed (provider retrieval failure), never silent empty,
        // and must not regenerate a key that would strand the committed ciphertext.
        try {
            vault.load()
            fail("expected fail-closed after key deletion")
        } catch (e: SessionVaultException) {
            assertEquals(VaultFailure.UNAVAILABLE, e.failure)
            assertNull("cause must stay closed", e.cause)
        }
        assertFalse(androidKeyStore().containsAlias(testAlias(namespace)))
        try {
            vault.compareAndSet(1L, null)
            fail("expected fail-closed CAS after key deletion")
        } catch (e: SessionVaultException) {
            assertNull(e.cause)
        }
        assertFalse(androidKeyStore().containsAlias(testAlias(namespace)))
        // Committed ciphertext preserved for recovery/retry under the original key.
        assertEquals(sealedBefore.toList(), vaultFile(namespace).readBytes().toList())
    }

    @Test
    fun ciphertextContainsNoSecrets() = runTest {
        val namespace = testNamespace("secrets")
        deleteNamespacePath(namespace)
        val body = "{\"refreshToken\":\"opaque-refresh-001\"}"
        val attempt = attemptFromBody(KEY_A, body)
        val record = recordA(
            session = sessionA(
                displayName = "Secret Display",
                access = "super-secret-access-xyz",
                refresh = attempt.refreshToken.value,
            ),
            pendingRefresh = pendingRefresh(attempt = attempt),
        )
        val vault = AndroidSessionVault.createForTesting(context, namespace)
        vault.compareAndSet(0L, record)
        val raw = vaultFile(namespace).readBytes()
        for (needle in listOf(
            "super-secret-access-xyz",
            "opaque-refresh-001",
            body,
            ACCOUNT_A,
            "Secret Display",
        )) {
            assertFalse(
                "ciphertext must not contain <$needle>",
                containsBytes(raw, needle.toByteArray(Charsets.UTF_8)),
            )
        }
    }

    // ---- Atomic file behavior: .new residue + injected faults ----

    @Test
    fun staleTempFileIsIgnoredAndReplaced() = runTest {
        val namespace = testNamespace("temp")
        deleteNamespacePath(namespace)
        val vault = AndroidSessionVault.createForTesting(context, namespace)
        vault.compareAndSet(0L, recordA())

        // Simulate a crashed write: owned stale .new residue must never be promoted.
        tempFile(namespace).writeBytes(" ArBr".toByteArray(Charsets.UTF_8))
        val loaded = vault.load()
        assertEquals(1L, loaded.revision)
        assertNotNull(loaded.record)

        // The next write ignores/unlinks the stale temp and succeeds atomically.
        val s2 = vault.compareAndSet(1L, null)
        assertEquals(2L, s2.revision)
        assertFalse(tempFile(namespace).exists())
        assertEquals(2L, vault.load().revision)
    }

    @Test
    fun preRenameFaultsKeepCommittedBaseUnchanged() = runTest {
        val namespace = testNamespace("prerename")
        deleteNamespacePath(namespace)
        val vault = AndroidSessionVault.createForTesting(context, namespace)
        vault.compareAndSet(0L, recordA())
        val committedBefore = vaultFile(namespace).readBytes()

        for (stage in listOf(
            VaultWriteStage.OPEN_TEMP,
            VaultWriteStage.SYNC_TEMP,
            VaultWriteStage.RENAME,
        )) {
            val failingVault = testVault(namespace, VaultWriteFaults { fault ->
                if (fault == stage) throw SessionVaultException(VaultFailure.UNAVAILABLE)
            })
            try {
                failingVault.compareAndSet(1L, null)
                fail("expected fault at $stage: safe error, never reported success")
            } catch (e: SessionVaultException) {
                assertNull(e.cause)
            }
            // File-level failure before rename keeps the old committed state byte-identical;
            // safe errors never report success, so reload fences to the old revision.
            assertEquals(committedBefore.toList(), vaultFile(namespace).readBytes().toList())
            val after = vault.load()
            assertEquals(1L, after.revision)
            assertNotNull(after.record)
        }
    }

    @Test
    fun postRenameFaultIsUncertainNeverSilent() = runTest {
        val namespace = testNamespace("postrename")
        deleteNamespacePath(namespace)
        val vault = AndroidSessionVault.createForTesting(context, namespace)
        vault.compareAndSet(0L, recordA())

        val failingVault = testVault(namespace, VaultWriteFaults { fault ->
            if (fault == VaultWriteStage.SYNC_DIRECTORY) {
                throw SessionVaultException(VaultFailure.UNAVAILABLE)
            }
        })
        try {
            failingVault.compareAndSet(1L, null)
            fail("expected post-rename fault")
        } catch (e: SessionVaultException) {
            assertNull(e.cause)
        }
        // After rename the outcome is uncertain (old or complete new) but never silent
        // empty/corrupt: reload yields a valid snapshot and fences the next CAS.
        val reloaded = vault.load()
        assertTrue(reloaded.revision == 1L || reloaded.revision == 2L)
        if (reloaded.revision == 1L) {
            assertNotNull(reloaded.record)
            assertEquals(2L, vault.compareAndSet(1L, null).revision)
        } else {
            assertNull(reloaded.record)
        }
    }

    @Test
    fun firstWriteFaultsAndDurabilityGates() = runTest {
        // OPEN_TEMP / RENAME faults on first write prove no commit: empty store, no file.
        for (stage in listOf(VaultWriteStage.OPEN_TEMP, VaultWriteStage.RENAME)) {
            val namespace = testNamespace("firstwrite-${stage.name.lowercase()}")
            deleteNamespacePath(namespace)
            val failingVault = testVault(namespace, VaultWriteFaults { fault ->
                if (fault == stage) throw SessionVaultException(VaultFailure.UNAVAILABLE)
            })
            try {
                failingVault.compareAndSet(0L, recordA())
                fail("expected first-write fault at $stage")
            } catch (e: SessionVaultException) {
                assertNull(e.cause)
            }
            val loaded = AndroidSessionVault.createForTesting(context, namespace).load()
            assertEquals(0L, loaded.revision)
            assertNull(loaded.record)
            assertFalse(vaultFile(namespace).exists())
        }

        // SYNC_DIRECTORY faults happen AFTER rename, so a first-write failure there may
        // leave the COMPLETE revision-1 record: reload the exact snapshot (never assume
        // rollback to empty) and retry only at the matching revision.
        run {
            val namespace = testNamespace("firstwrite-syncdir")
            deleteNamespacePath(namespace)
            val expected = recordA()
            val failingVault = testVault(namespace, VaultWriteFaults { fault ->
                if (fault == VaultWriteStage.SYNC_DIRECTORY) {
                    throw SessionVaultException(VaultFailure.UNAVAILABLE)
                }
            })
            try {
                failingVault.compareAndSet(0L, expected)
                fail("expected first-write directory-sync fault")
            } catch (e: SessionVaultException) {
                assertNull(e.cause)
            }
            val plainVault = AndroidSessionVault.createForTesting(context, namespace)
            val loaded = plainVault.load()
            assertEquals(1L, loaded.revision)
            assertEquals(expected, loaded.record)
            assertEquals(2L, plainVault.compareAndSet(1L, null).revision)
        }

        // Clean first write: new namespace mkdir (+ parent fsync gate) succeeds end to end.
        run {
            val namespace = testNamespace("firstwrite-clean")
            deleteNamespacePath(namespace)
            val plainVault = AndroidSessionVault.createForTesting(context, namespace)
            assertEquals(1L, plainVault.compareAndSet(0L, recordA()).revision)
            assertTrue(namespaceDir(namespace).isDirectory)
            assertTrue(vaultFile(namespace).isFile)
        }
    }

    @Test
    fun concurrentCasAcrossFactoryInstancesHasOneWinner() = runTest {
        val namespace = testNamespace("concurrent")
        deleteNamespacePath(namespace)
        // Default factory and repeated opens share ONE process-wide mutex (test factory
        // included): concurrent CAS across two instances still yields exactly one winner.
        val first = AndroidSessionVault.createForTesting(context, namespace)
        val second = AndroidSessionVault.createForTesting(context, namespace)
        val opIds = listOf(
            "11111111-1111-1111-1111-111111111111",
            "22222222-2222-2222-2222-222222222222",
        )
        val a = async {
            try {
                Result.success(first.compareAndSet(0L, recordA(operationId = opIds[0])))
            } catch (e: SessionVaultException) {
                Result.failure<VaultSnapshot>(e)
            }
        }
        val b = async {
            try {
                Result.success(second.compareAndSet(0L, recordA(operationId = opIds[1])))
            } catch (e: SessionVaultException) {
                Result.failure<VaultSnapshot>(e)
            }
        }
        val outcomes = listOf(a.await(), b.await())
        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(1, outcomes.count { it.isFailure })
        val conflict = outcomes.first { it.isFailure }.exceptionOrNull() as SessionVaultException
        assertEquals(VaultFailure.CONFLICT, conflict.failure)
        assertEquals(1L, first.load().revision)
    }

    // ---- Path / privacy: read null ONLY for genuinely absent file ----

    @Test
    fun readNullOnlyForGenuinelyAbsentFile() {
        val namespace = testNamespace("absent")
        deleteNamespacePath(namespace)
        val storage = AndroidVaultFileStorage(context, namespace)
        assertNull(storage.read())
    }

    @Test
    fun namespaceOccupiedByFileFailsClosedNotNull() {
        val namespace = testNamespace("fileblock")
        deleteNamespacePath(namespace)
        noBackupDir().mkdirs()
        namespaceDir(namespace).writeBytes("blocker".toByteArray())
        try {
            val storage = AndroidVaultFileStorage(context, namespace)
            try {
                storage.read()
                fail("expected fail-closed when namespace path is a file")
            } catch (e: SessionVaultException) {
                assertNull("cause must stay closed", e.cause)
            }
        } catch (e: SessionVaultException) {
            assertNull("cause must stay closed", e.cause)
        } finally {
            namespaceDir(namespace).delete()
        }
    }

    @Test
    fun finalPathOccupiedByDirectoryFailsClosedNotNull() {
        val namespace = testNamespace("dirblock")
        deleteNamespacePath(namespace)
        val storage = AndroidVaultFileStorage(context, namespace)
        namespaceDir(namespace).mkdirs()
        assertTrue(vaultFile(namespace).mkdir())
        try {
            storage.read()
            fail("expected fail-closed when final path is a directory")
        } catch (e: SessionVaultException) {
            assertNull("cause must stay closed", e.cause)
        }
    }

    @Test
    fun unreadableFileFailsClosedNotNull() = runTest {
        val namespace = testNamespace("perms")
        deleteNamespacePath(namespace)
        val vault = AndroidSessionVault.createForTesting(context, namespace)
        vault.compareAndSet(0L, recordA())
        val path = vaultFile(namespace).absolutePath
        Os.chmod(path, 0)
        try {
            val storage = AndroidVaultFileStorage(context, namespace)
            try {
                storage.read()
                fail("expected fail-closed on permission error")
            } catch (e: SessionVaultException) {
                assertNull("cause must stay closed", e.cause)
            }
            try {
                vault.load()
                fail("expected fail-closed vault load on permission error")
            } catch (e: SessionVaultException) {
                assertNull(e.cause)
            }
        } finally {
            Os.chmod(path, 384) // 0600 for cleanup
        }
        assertEquals(1L, vault.load().revision)
    }

    @Test
    fun namespaceSymlinkEscapeFailsClosedTargetPreserved() {
        val namespace = testNamespace("nslink")
        deleteNamespacePath(namespace)
        val outside = File(context.cacheDir, "vault-outside-$namespace").also {
            usedOutsideDirs.add(it)
            it.mkdirs()
        }
        val sentinel = File(outside, "sentinel.bin")
        val sentinelBytes = "sentinel-do-not-touch".toByteArray(Charsets.UTF_8)
        sentinel.writeBytes(sentinelBytes)
        try {
            Os.symlink(outside.absolutePath, namespaceDir(namespace).absolutePath)
        } catch (e: Exception) {
            fail("emulator must support symlinks for this privacy gate: $e")
        }
        try {
            val storage = AndroidVaultFileStorage(context, namespace)
            try {
                storage.read()
                fail("expected fail-closed on namespace symlink escape")
            } catch (e: SessionVaultException) {
                assertNull("cause must stay closed", e.cause)
            }
            // The escape target is preserved: nothing was written through the link.
            assertEquals(sentinelBytes.toList(), sentinel.readBytes().toList())
            assertTrue(sentinel.isFile)
        } catch (e: SessionVaultException) {
            assertNull("cause must stay closed", e.cause)
        } finally {
            removeLinkNoFollow(namespaceDir(namespace).absolutePath)
            assertEquals(sentinelBytes.toList(), sentinel.readBytes().toList())
        }
    }

    @Test
    fun finalFileSymlinkEscapeFailsClosedTargetPreserved() = runTest {
        val namespace = testNamespace("filelink")
        deleteNamespacePath(namespace)
        namespaceDir(namespace).mkdirs()
        val outside = File(context.cacheDir, "vault-outside-$namespace").also {
            usedOutsideDirs.add(it)
            it.mkdirs()
        }
        val sentinel = File(outside, "sentinel.bin")
        val sentinelBytes = "final-link-sentinel".toByteArray(Charsets.UTF_8)
        sentinel.writeBytes(sentinelBytes)
        try {
            Os.symlink(sentinel.absolutePath, vaultFile(namespace).absolutePath)
        } catch (e: Exception) {
            fail("emulator must support symlinks for this privacy gate: $e")
        }
        try {
            val storage = AndroidVaultFileStorage(context, namespace)
            try {
                storage.read()
                fail("expected fail-closed on final-file symlink escape")
            } catch (e: SessionVaultException) {
                assertNull("cause must stay closed", e.cause)
            }
            val vault = AndroidSessionVault.createForTesting(context, namespace)
            try {
                vault.compareAndSet(0L, recordA())
                fail("expected fail-closed CAS through final-file symlink")
            } catch (e: SessionVaultException) {
                assertNull(e.cause)
            }
            assertEquals(sentinelBytes.toList(), sentinel.readBytes().toList())
        } finally {
            removeLinkNoFollow(vaultFile(namespace).absolutePath)
            assertEquals(sentinelBytes.toList(), sentinel.readBytes().toList())
        }
    }

    @Test
    fun tempFileSymlinkEscapeFailsClosedTargetPreserved() = runTest {
        val namespace = testNamespace("templink")
        deleteNamespacePath(namespace)
        val vault = AndroidSessionVault.createForTesting(context, namespace)
        vault.compareAndSet(0L, recordA())
        val committedBefore = vaultFile(namespace).readBytes()
        val outside = File(context.cacheDir, "vault-outside-$namespace").also {
            usedOutsideDirs.add(it)
            it.mkdirs()
        }
        val sentinel = File(outside, "sentinel.bin")
        val sentinelBytes = "temp-link-sentinel".toByteArray(Charsets.UTF_8)
        sentinel.writeBytes(sentinelBytes)
        try {
            Os.symlink(sentinel.absolutePath, tempFile(namespace).absolutePath)
        } catch (e: Exception) {
            fail("emulator must support symlinks for this privacy gate: $e")
        }
        try {
            try {
                vault.compareAndSet(1L, null)
                fail("expected fail-closed CAS through temp symlink")
            } catch (e: SessionVaultException) {
                assertNull(e.cause)
            }
            assertEquals(sentinelBytes.toList(), sentinel.readBytes().toList())
            assertEquals(committedBefore.toList(), vaultFile(namespace).readBytes().toList())
            assertEquals(1L, vault.load().revision)
        } finally {
            removeLinkNoFollow(tempFile(namespace).absolutePath)
            assertEquals(sentinelBytes.toList(), sentinel.readBytes().toList())
        }
    }

    private fun writeVaultString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    // Minimal PRIVATE independent version-1 record builder (published grammar only):
    // magic GTS1, version 1, revision, present tags, length-prefixed strict UTF-8.
    private fun independentRecordBytes(
        revision: Long,
        refresh: String,
        pendingBody: String,
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeInt(0x47545331)
            out.writeInt(1)
            out.writeLong(revision)
            out.writeByte(1)
            writeVaultString(out, ORIGIN_CANONICAL_A)
            writeVaultString(out, ACCOUNT_A)
            out.writeLong(7L)
            writeVaultString(out, OP_ACTIVE)
            out.writeByte(1) // ACTIVE
            out.writeByte(1) // displayName present
            writeVaultString(out, "Example")
            writeVaultString(out, "opaque-access-001")
            out.writeLong(1_700_000_000_000L)
            writeVaultString(out, refresh)
            out.writeLong(1_700_000_000_000L + 30L * 24 * 60 * 60 * 1000)
            out.writeByte(1) // pending present
            writeVaultString(out, OP_PENDING)
            writeVaultString(out, KEY_A)
            writeVaultString(out, pendingBody)
        }
        return bos.toByteArray()
    }

    @Test
    fun androidOrgJsonScalarFidelitySmoke() {
        // Platform org.json matters most here: complete bytes differing ONLY in body
        // quoting, with key/op/grant preserved, must split quoted-accept versus
        // unquoted-reject even when the unquoted text matches the stored grant exactly.
        for (scalar in listOf("1e400", "1e999999")) {
            val quotedBody = "{\"refreshToken\":\"$scalar\"}"
            val decoded = VaultRecordCodec.decode(independentRecordBytes(1L, scalar, quotedBody))
            assertEquals(1L, decoded.revision)
            val record = requireNotNull(decoded.record)
            assertEquals(scalar, record.session.refreshToken.value)
            val pending = requireNotNull(record.pendingRefresh)
            assertEquals(OP_PENDING, pending.operationId)
            assertEquals(KEY_A, pending.attempt.idempotencyKey)
            assertEquals(quotedBody, pending.attempt.requestJson)
            assertEquals(scalar, pending.attempt.refreshToken.value)

            val unquotedBytes = independentRecordBytes(1L, scalar, "{\"refreshToken\":$scalar}")
            try {
                VaultRecordCodec.decode(unquotedBytes)
                fail("expected UNREADABLE for unquoted scalar $scalar on matching grant")
            } catch (e: SessionVaultException) {
                assertEquals(VaultFailure.UNREADABLE, e.failure)
                assertNull("cause must stay closed", e.cause)
            }
        }
    }

    @Test
    fun insideNoBackupSymlinkTargetsStillRejected() = runTest {
        // Table over namespace/final/temp links whose targets are OWNED paths INSIDE
        // noBackup: canonical containment alone must not bypass original-path rejection.
        run {
            val namespace = testNamespace("insidelink-ns")
            deleteNamespacePath(namespace)
            val target = File(noBackupDir(), "gymtracka-test-r04a-linktarget-ns").also {
                usedNamespaces.add(it.name)
                it.mkdirs()
            }
            val sentinel = File(target, "sentinel.bin")
            val sentinelBytes = "inside-ns-sentinel".toByteArray(Charsets.UTF_8)
            sentinel.writeBytes(sentinelBytes)
            try {
                Os.symlink(target.absolutePath, namespaceDir(namespace).absolutePath)
            } catch (e: Exception) {
                fail("emulator must support symlinks for this privacy gate: $e")
            }
            try {
                val storage = AndroidVaultFileStorage(context, namespace)
                try {
                    storage.read()
                    fail("expected fail-closed on inside-noBackup namespace link")
                } catch (e: SessionVaultException) {
                    assertNull("cause must stay closed", e.cause)
                }
                assertEquals(sentinelBytes.toList(), sentinel.readBytes().toList())
            } catch (e: SessionVaultException) {
                assertNull("cause must stay closed", e.cause)
            } finally {
                removeLinkNoFollow(namespaceDir(namespace).absolutePath)
                assertEquals(sentinelBytes.toList(), sentinel.readBytes().toList())
            }
        }
        run {
            val namespace = testNamespace("insidelink-file")
            deleteNamespacePath(namespace)
            namespaceDir(namespace).mkdirs()
            val target = File(noBackupDir(), "gymtracka-test-r04a-linktarget-file").also {
                usedNamespaces.add(it.name)
                it.mkdirs()
            }
            val sentinel = File(target, "sentinel.bin")
            val sentinelBytes = "inside-final-sentinel".toByteArray(Charsets.UTF_8)
            sentinel.writeBytes(sentinelBytes)
            try {
                Os.symlink(sentinel.absolutePath, vaultFile(namespace).absolutePath)
            } catch (e: Exception) {
                fail("emulator must support symlinks for this privacy gate: $e")
            }
            try {
                val storage = AndroidVaultFileStorage(context, namespace)
                try {
                    storage.read()
                    fail("expected fail-closed on inside-noBackup final link")
                } catch (e: SessionVaultException) {
                    assertNull("cause must stay closed", e.cause)
                }
                val vault = AndroidSessionVault.createForTesting(context, namespace)
                try {
                    vault.compareAndSet(0L, recordA())
                    fail("expected fail-closed CAS through inside-noBackup final link")
                } catch (e: SessionVaultException) {
                    assertNull(e.cause)
                }
                assertEquals(sentinelBytes.toList(), sentinel.readBytes().toList())
            } finally {
                removeLinkNoFollow(vaultFile(namespace).absolutePath)
                assertEquals(sentinelBytes.toList(), sentinel.readBytes().toList())
            }
        }
        run {
            val namespace = testNamespace("insidelink-temp")
            deleteNamespacePath(namespace)
            val vault = AndroidSessionVault.createForTesting(context, namespace)
            vault.compareAndSet(0L, recordA())
            val committedBefore = vaultFile(namespace).readBytes()
            val target = File(noBackupDir(), "gymtracka-test-r04a-linktarget-temp").also {
                usedNamespaces.add(it.name)
                it.mkdirs()
            }
            val sentinel = File(target, "sentinel.bin")
            val sentinelBytes = "inside-temp-sentinel".toByteArray(Charsets.UTF_8)
            sentinel.writeBytes(sentinelBytes)
            try {
                Os.symlink(sentinel.absolutePath, tempFile(namespace).absolutePath)
            } catch (e: Exception) {
                fail("emulator must support symlinks for this privacy gate: $e")
            }
            try {
                try {
                    vault.compareAndSet(1L, null)
                    fail("expected fail-closed CAS through inside-noBackup temp link")
                } catch (e: SessionVaultException) {
                    assertNull(e.cause)
                }
                assertEquals(sentinelBytes.toList(), sentinel.readBytes().toList())
                assertEquals(committedBefore.toList(), vaultFile(namespace).readBytes().toList())
            } finally {
                removeLinkNoFollow(tempFile(namespace).absolutePath)
                assertEquals(sentinelBytes.toList(), sentinel.readBytes().toList())
            }
        }
    }

    @Test
    fun wrongKindAliasKeyRefusedWithoutOverwrite() {
        val namespace = testNamespace("wrongkind")
        deleteNamespacePath(namespace)
        // Owned RSA key under the test alias: the AES/GCM vault path must refuse it
        // without overwriting or deleting the foreign-kind key (Android framework only).
        val kpg = KeyPairGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_RSA,
            "AndroidKeyStore",
        )
        kpg.initialize(
            android.security.keystore.KeyGenParameterSpec.Builder(
                testAlias(namespace),
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            ).setKeySize(2048)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_ECB)
                .setEncryptionPaddings(
                    android.security.keystore.KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1,
                )
                .build(),
        )
        kpg.generateKeyPair()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val fingerprintBefore = digest.digest(
            requireNotNull(androidKeyStore().getCertificate(testAlias(namespace))).encoded,
        )
        assertEquals("RSA", androidKeyStore().getKey(testAlias(namespace), null).algorithm)

        // The provider boundary itself refuses the wrong kind for BOTH create flags
        // (its contract returns SecretKey, so the RSA key must never surface).
        val provider = AndroidKeystoreVaultKeyProvider(testAlias(namespace))
        for (createFlag in listOf(false, true)) {
            try {
                provider.key(createFlag)
                fail("expected UNAVAILABLE wrong-kind refusal for key($createFlag)")
            } catch (e: SessionVaultException) {
                assertEquals(VaultFailure.UNAVAILABLE, e.failure)
                assertNull("cause must stay closed", e.cause)
            }
        }

        val cipher = AesGcmVaultCipher(AndroidKeystoreVaultKeyProvider(testAlias(namespace)))
        try {
            cipher.seal("wrong-kind probe".toByteArray(Charsets.UTF_8))
            fail("expected fail-closed seal with RSA key")
        } catch (e: SessionVaultException) {
            assertEquals(VaultFailure.UNAVAILABLE, e.failure)
            assertNull("cause must stay closed", e.cause)
        }
        // Structurally valid GTV1/version1/12-byte-IV+tag envelope: refusal must come
        // from the occupied key (UNAVAILABLE), never malformed-header preflight.
        val envelope = byteArrayOf(0x47, 0x54, 0x56, 0x31, 0, 0, 0, 1) +
            ByteArray(12) { it.toByte() } +
            ByteArray(32) { (it * 3).toByte() }
        try {
            cipher.open(envelope)
            fail("expected UNAVAILABLE open with RSA key")
        } catch (e: SessionVaultException) {
            assertEquals(VaultFailure.UNAVAILABLE, e.failure)
            assertNull("cause must stay closed", e.cause)
        }
        // No overwrite: KeyStore-direct checks still show the ORIGINAL RSA key/cert.
        assertEquals("RSA", androidKeyStore().getKey(testAlias(namespace), null).algorithm)
        val fingerprintAfter = digest.digest(
            requireNotNull(androidKeyStore().getCertificate(testAlias(namespace))).encoded,
        )
        assertArrayEquals(fingerprintBefore, fingerprintAfter)
    }

    @Test
    fun invalidNamespaceShapesRejectedSafe() {
        // Total length allows 64 chars within owned gymtracka-test-r04a-* naming: the
        // 64-length name is a VALID positive, the 65-length name must be rejected.
        val valid64 = "gymtracka-test-r04a-" + "y".repeat(44)
        usedNamespaces.add(valid64)
        try {
            AndroidVaultFileStorage(context, valid64)
        } catch (e: SessionVaultException) {
            fail("64-char owned namespace must be accepted, got $e")
        } finally {
            deleteNamespacePath(valid64)
            usedNamespaces.remove(valid64)
        }
        for (bad in listOf(
            "",
            "UPPER",
            "has space",
            "../escape",
            "a/b",
            "gymtracka-test-r04a-" + "y".repeat(45),
        )) {
            try {
                AndroidVaultFileStorage(context, bad)
                fail("expected safe rejection for namespace <$bad>")
            } catch (e: SessionVaultException) {
                assertNull("cause must stay closed", e.cause)
            }
        }
    }

    @Test
    fun testFactoryRequiresTestPrefix() {
        try {
            AndroidSessionVault.createForTesting(context, "prod-like-name")
            fail("expected safe rejection for non-test namespace")
        } catch (e: SessionVaultException) {
            assertNull("cause must stay closed", e.cause)
        }
    }

    @Test
    fun allArtifactsStayUnderNoBackupWithOpaqueNames() = runTest {
        val namespace = testNamespace("paths")
        deleteNamespacePath(namespace)
        val storage = AndroidVaultFileStorage(context, namespace)
        val vault = AndroidSessionVault.createForTesting(context, namespace)
        vault.compareAndSet(0L, recordA())

        val noBackupCanonical = noBackupDir().canonicalPath
        assertEquals(File(noBackupCanonical, namespace).canonicalPath, storage.directory.canonicalPath)
        assertEquals(File(storage.directory, "session.vault").canonicalPath, storage.file.canonicalPath)
        assertTrue(storage.file.canonicalPath.startsWith(noBackupCanonical + File.separator))
        for (other in listOf(
            context.filesDir.canonicalPath,
            context.cacheDir.canonicalPath,
            context.codeCacheDir.canonicalPath,
        )) {
            assertFalse(storage.file.canonicalPath.startsWith(other + File.separator))
        }
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                val deviceProtected = context.createDeviceProtectedStorageContext()
                assertFalse(
                    storage.file.canonicalPath.startsWith(
                        deviceProtected.filesDir.canonicalPath + File.separator,
                    ),
                )
            } catch (_: Exception) {
            }
        }
        assertFalse(storage.file.name.contains(ACCOUNT_A))
        assertFalse(storage.directory.name.contains(ACCOUNT_A))
        val names = (namespaceDir(namespace).listFiles() ?: emptyArray()).map { it.name }.toSet()
        assertEquals(setOf("session.vault"), names)
    }

    // ---- Manifest + packaged backup XML ----

    @Test
    fun manifestKeepsOrdinaryBackupWhileExcludingVault() {
        val appInfo: ApplicationInfo =
            context.packageManager.getApplicationInfo(context.packageName, 0)
        assertTrue(
            "ordinary user database backup must NOT be disabled",
            appInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0,
        )
        val backupId = context.resources.getIdentifier("backup_rules", "xml", context.packageName)
        assertTrue("backup_rules.xml must be packaged", backupId != 0)
        val parser = context.resources.getXml(backupId)
        try {
            var hasVaultExclude = false
            var hasInclude = false
            var hasDatabaseExclude = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "exclude" -> {
                            val domain = parser.getAttributeValue(null, "domain")
                            val path = parser.getAttributeValue(null, "path")
                            if (domain == "root" && path == "no_backup/$PRODUCTION_NAMESPACE") {
                                hasVaultExclude = true
                            }
                            if (domain == "database") hasDatabaseExclude = true
                        }
                        "include" -> hasInclude = true
                    }
                }
                event = parser.next()
            }
            assertTrue("legacy XML must exclude the production vault namespace", hasVaultExclude)
            assertFalse("no include-only rule may narrow ordinary backup", hasInclude)
            assertFalse("whole-database exclusion is forbidden", hasDatabaseExclude)
        } finally {
            parser.close()
        }

        val extractionId = context.resources.getIdentifier(
            "data_extraction_rules",
            "xml",
            context.packageName,
        )
        assertTrue("data_extraction_rules.xml must be packaged", extractionId != 0)
        val xmlParser = context.resources.getXml(extractionId)
        try {
            val sections = mutableMapOf("cloud-backup" to false, "device-transfer" to false)
            val stack = ArrayDeque<String>()
            var xmlDatabaseExclude = false
            var xmlInclude = false
            var xmlEvent = xmlParser.eventType
            while (xmlEvent != XmlPullParser.END_DOCUMENT) {
                when (xmlEvent) {
                    XmlPullParser.START_TAG -> when (xmlParser.name) {
                        "cloud-backup", "device-transfer" -> stack.addLast(xmlParser.name)
                        "exclude" -> {
                            val domain = xmlParser.getAttributeValue(null, "domain")
                            val path = xmlParser.getAttributeValue(null, "path")
                            if (domain == "root" && path == "no_backup/$PRODUCTION_NAMESPACE") {
                                stack.lastOrNull()?.let { sections[it] = true }
                            }
                            if (domain == "database") xmlDatabaseExclude = true
                        }
                        "include" -> xmlInclude = true
                    }
                    XmlPullParser.END_TAG -> if (
                        xmlParser.name == "cloud-backup" || xmlParser.name == "device-transfer"
                    ) {
                        stack.removeLastOrNull()
                    }
                }
                xmlEvent = xmlParser.next()
            }
            assertTrue("cloud-backup must exclude the production vault", sections["cloud-backup"] == true)
            assertTrue(
                "device-transfer must exclude the production vault",
                sections["device-transfer"] == true,
            )
            assertFalse(xmlDatabaseExclude)
            assertFalse(xmlInclude)
        } finally {
            xmlParser.close()
        }

        assertEquals(
            context.packageName,
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
        )
    }
}
