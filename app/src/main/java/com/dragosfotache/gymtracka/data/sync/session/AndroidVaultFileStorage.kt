package com.dragosfotache.gymtracka.data.sync.session

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor

private const val VAULT_FILE_NAME: String = "session.vault"
private const val VAULT_TEMP_FILE_NAME: String = "session.vault.new"

/** Owner-only file mode 0600 for the temp ciphertext. */
private const val TEMP_FILE_MODE: Int = 0x180

/** Owner-only directory mode 0700 for the namespace directory. */
private const val NAMESPACE_DIR_MODE: Int = 0x1C0

private const val READ_CHUNK_BYTES: Int = 4096

/** Deterministic failure seam stages for the atomic file replacement. */
internal enum class VaultWriteStage {
    OPEN_TEMP,
    SYNC_TEMP,
    RENAME,
    SYNC_DIRECTORY,
}

/**
 * Deterministic failure seam for the file replacement. The default is a
 * no-op; the seam never owns or logs bytes. An injected
 * [CancellationException] propagates as the original object; any other seam
 * failure maps safe without a raw cause.
 */
internal fun interface VaultWriteFaults {
    fun before(stage: VaultWriteStage)
}

private val NAMESPACE_SHAPE = Regex("^[a-z][a-z0-9_-]{0,63}$")

private fun validateNamespace(namespace: String): String {
    if (!NAMESPACE_SHAPE.matches(namespace)) {
        throw SessionVaultException(VaultFailure.INVALID_RECORD)
    }
    return namespace
}

/**
 * App-private credential-protected `noBackupFilesDir` ciphertext storage with
 * atomic same-directory replacement. All final, temp, and journal artifacts
 * stay under the namespace directory; file names disclose no account or
 * service IDs, and no plaintext is ever written. External, device-protected,
 * preference, cache, and filesDir locations are never used; a
 * device-protected context is rejected.
 *
 * Construction performs no I/O: even resolving `noBackupFilesDir` may create
 * directories, so directory and file handles resolve lazily on first property
 * access or I/O operation.
 *
 * Shape checks run on the ORIGINAL paths with `lstat`, never on a resolved
 * canonical path that would already have followed symlinks: symlinks and
 * non-regular shapes at the base, namespace, final, or temp locations are
 * rejected closed. Reads return null only for a genuinely absent committed
 * file, detected via `lstat` ENOENT rather than a bare existence check, so an
 * access error is never mistaken for an empty store. The temp file opens with
 * `O_CREAT | O_EXCL | O_NOFOLLOW` and mode 0600 after a checked stale unlink,
 * so no ignored create/delete errors can promote residue.
 *
 * Replacement writes ciphertext to `session.vault.new`, runs the sync-stage
 * fault seam before syncing its descriptor, closes it, atomically renames it
 * over `session.vault` with `android.system.Os.rename`, then fsyncs the
 * namespace directory and, on every replacement, the `noBackupFilesDir`
 * parent before the commit is acknowledged: a crash between namespace
 * creation and a later write must not lose the directory containing an
 * already-persisted attempt. A failure before the rename keeps the committed
 * base unchanged; a failure at or after the rename is uncertain (old or
 * complete new state after a crash) and never reports success or retries
 * automatically. Explicit error checks replace
 * `android.util.AtomicFile.finishWrite`, which swallows important
 * fsync/rename failures.
 *
 * An injected caller cancellation propagates as the original object and
 * dominates a racing storage error.
 */
internal class AndroidVaultFileStorage(
    context: Context,
    namespace: String = SESSION_VAULT_PRODUCTION_NAMESPACE,
    private val faults: VaultWriteFaults = VaultWriteFaults {},
) : VaultFileStorage {
    private val applicationContext: Context = context.applicationContext
    private val safeNamespace: String = validateNamespace(namespace)

    init {
        // Both the supplied context and its application context must be
        // credential-protected: a device-protected wrapper can hand back a
        // normal application context while still resolving protected paths.
        if (context.isDeviceProtectedStorage || applicationContext.isDeviceProtectedStorage) {
            throw SessionVaultException(VaultFailure.INVALID_RECORD)
        }
    }

    val directory: File by lazy { File(resolveBase(), safeNamespace) }

    val file: File by lazy { File(directory, VAULT_FILE_NAME) }

    private val tempFile: File by lazy { File(directory, VAULT_TEMP_FILE_NAME) }

    override fun read(): ByteArray? {
        val base = resolveBase()
        val dir = directory
        val target = file
        requireBaseDirectory(base.absolutePath)
        val dirStat = try {
            Os.lstat(dir.absolutePath)
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ENOENT) {
                return null
            }
            throw SessionVaultException(VaultFailure.UNAVAILABLE)
        }
        requireDirectoryShape(dirStat)
        val targetStat = try {
            Os.lstat(target.absolutePath)
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ENOENT) {
                return null
            }
            throw SessionVaultException(VaultFailure.UNAVAILABLE)
        }
        if (OsConstants.S_ISLNK(targetStat.st_mode) || !OsConstants.S_ISREG(targetStat.st_mode)) {
            throw SessionVaultException(VaultFailure.UNREADABLE)
        }
        val fd: FileDescriptor = try {
            Os.open(target.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        } catch (e: ErrnoException) {
            throw SessionVaultException(VaultFailure.UNAVAILABLE)
        }
        try {
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(READ_CHUNK_BYTES)
            while (true) {
                val count = try {
                    Os.read(fd, chunk, 0, chunk.size)
                } catch (e: ErrnoException) {
                    throw SessionVaultException(VaultFailure.UNAVAILABLE)
                }
                if (count <= 0) {
                    break
                }
                out.write(chunk, 0, count)
            }
            return out.toByteArray()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionVaultException) {
            throw e
        } catch (e: Exception) {
            throw SessionVaultException(VaultFailure.UNAVAILABLE)
        } finally {
            try {
                Os.close(fd)
            } catch (_: ErrnoException) {
                // The read result above decides; ignore close noise.
            }
        }
    }

    override fun replace(bytes: ByteArray) {
        val payload = bytes.copyOf()
        try {
            replaceCommitted(payload)
        } finally {
            payload.fill(0)
        }
    }

    private fun replaceCommitted(payload: ByteArray) {
        val base = resolveBase()
        val dir = directory
        val target = file
        val temp = tempFile
        requireBaseDirectory(base.absolutePath)
        ensureNamespaceDirectory(dir.absolutePath)
        requireReplaceableTarget(target.absolutePath)
        unlinkStaleTemp(temp.absolutePath)
        runFault(VaultWriteStage.OPEN_TEMP)
        val fd: FileDescriptor = try {
            Os.open(
                temp.absolutePath,
                OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW,
                TEMP_FILE_MODE,
            )
        } catch (e: ErrnoException) {
            throw SessionVaultException(VaultFailure.UNAVAILABLE)
        }
        try {
            var offset = 0
            while (offset < payload.size) {
                val written = try {
                    Os.write(fd, payload, offset, payload.size - offset)
                } catch (e: ErrnoException) {
                    throw SessionVaultException(VaultFailure.UNAVAILABLE)
                }
                if (written <= 0) {
                    throw SessionVaultException(VaultFailure.UNAVAILABLE)
                }
                offset += written
            }
            runFault(VaultWriteStage.SYNC_TEMP)
            try {
                Os.fsync(fd)
            } catch (e: ErrnoException) {
                throw SessionVaultException(VaultFailure.UNAVAILABLE)
            }
        } finally {
            try {
                Os.close(fd)
            } catch (_: ErrnoException) {
                // The write/sync result above decides; ignore close noise.
            }
        }
        runFault(VaultWriteStage.RENAME)
        try {
            Os.rename(temp.absolutePath, target.absolutePath)
        } catch (e: ErrnoException) {
            throw SessionVaultException(VaultFailure.UNAVAILABLE)
        }
        runFault(VaultWriteStage.SYNC_DIRECTORY)
        fsyncDirectory(dir.absolutePath)
        fsyncDirectory(base.absolutePath)
    }

    private fun requireBaseDirectory(basePath: String) {
        val stat = try {
            Os.lstat(basePath)
        } catch (e: ErrnoException) {
            throw SessionVaultException(VaultFailure.UNAVAILABLE)
        }
        requireDirectoryShape(stat)
    }

    private fun requireDirectoryShape(stat: StructStat) {
        if (OsConstants.S_ISLNK(stat.st_mode) || !OsConstants.S_ISDIR(stat.st_mode)) {
            throw SessionVaultException(VaultFailure.UNREADABLE)
        }
    }

    private fun ensureNamespaceDirectory(path: String) {
        val existing = try {
            Os.lstat(path)
        } catch (e: ErrnoException) {
            if (e.errno != OsConstants.ENOENT) {
                throw SessionVaultException(VaultFailure.UNAVAILABLE)
            }
            try {
                Os.mkdir(path, NAMESPACE_DIR_MODE)
            } catch (mkdirError: ErrnoException) {
                if (mkdirError.errno != OsConstants.EEXIST) {
                    throw SessionVaultException(VaultFailure.UNAVAILABLE)
                }
            }
            val created = try {
                Os.lstat(path)
            } catch (restatError: ErrnoException) {
                throw SessionVaultException(VaultFailure.UNAVAILABLE)
            }
            requireDirectoryShape(created)
            return
        }
        requireDirectoryShape(existing)
    }

    private fun requireReplaceableTarget(path: String) {
        val stat = try {
            Os.lstat(path)
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ENOENT) {
                return
            }
            throw SessionVaultException(VaultFailure.UNAVAILABLE)
        }
        if (OsConstants.S_ISLNK(stat.st_mode) || !OsConstants.S_ISREG(stat.st_mode)) {
            throw SessionVaultException(VaultFailure.UNREADABLE)
        }
    }

    private fun unlinkStaleTemp(path: String) {
        val stat = try {
            Os.lstat(path)
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ENOENT) {
                return
            }
            throw SessionVaultException(VaultFailure.UNAVAILABLE)
        }
        if (OsConstants.S_ISLNK(stat.st_mode) || !OsConstants.S_ISREG(stat.st_mode)) {
            throw SessionVaultException(VaultFailure.UNREADABLE)
        }
        try {
            Os.remove(path)
        } catch (e: ErrnoException) {
            if (e.errno != OsConstants.ENOENT) {
                throw SessionVaultException(VaultFailure.UNAVAILABLE)
            }
        }
    }

    private fun runFault(stage: VaultWriteStage) {
        try {
            faults.before(stage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SessionVaultException(VaultFailure.UNAVAILABLE)
        }
    }

    private fun fsyncDirectory(path: String) {
        val fd: FileDescriptor = try {
            Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        } catch (e: ErrnoException) {
            throw SessionVaultException(VaultFailure.UNAVAILABLE)
        }
        try {
            val stat = try {
                Os.fstat(fd)
            } catch (e: ErrnoException) {
                throw SessionVaultException(VaultFailure.UNAVAILABLE)
            }
            if (OsConstants.S_ISLNK(stat.st_mode) || !OsConstants.S_ISDIR(stat.st_mode)) {
                throw SessionVaultException(VaultFailure.UNREADABLE)
            }
            try {
                Os.fsync(fd)
            } catch (e: ErrnoException) {
                throw SessionVaultException(VaultFailure.UNAVAILABLE)
            }
        } finally {
            try {
                Os.close(fd)
            } catch (_: ErrnoException) {
                // The fsync result above decides; ignore close noise.
            }
        }
    }

    private fun resolveBase(): File {
        try {
            return applicationContext.noBackupFilesDir
                ?: throw SessionVaultException(VaultFailure.UNAVAILABLE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionVaultException) {
            throw e
        } catch (e: Exception) {
            throw SessionVaultException(VaultFailure.UNAVAILABLE)
        }
    }
}
