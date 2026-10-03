package com.dragosfotache.gymtracka.data.sync.backend

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Guarded test-only gateway to an isolated real backend started by a
 * supervisor script (see `scripts/run-isolated-backend.py`).
 *
 * The ephemeral JSON config path comes only from the explicit system property
 * `gymtracka.realBackendConfig` (normally set by the `testRealBackend` Gradle
 * task); there is no default that could silently accept a stale file. Every
 * guard below fails loudly with a fixed message that retains no raw values or
 * causes: the file must be a non-symlink regular file with owner-only
 * permissions, all required keys must be present, the backend origin must be
 * root-only loopback HTTP without credentials/query/fragment, the JDBC URL
 * must fully match the disposable `gymtracka_android_test` database on
 * loopback with an explicit port and no query suffix (never `gymtracka` or
 * `gymtracka_prod`), the decoded key must be 32 bytes, the database must be
 * reachable, and its `gymtracka_android_test_guard` table must hold this run's
 * canonical UUID.
 *
 * Per-test accounts are minted exactly as the backend's own HTTP tests do: one
 * transaction inserting a fresh account/session row plus an HMAC-SHA256 access
 * credential (15-minute lifetime, the production access duration) derived from
 * the ephemeral test key with the backend's `GymTracka auth HMAC v1` label.
 * Every HTTP call is still authenticated by the real `SessionAuthorization`;
 * Google is not involved in this slice. Tokens live only in memory and never
 * appear in assertions, logs, or failure text.
 *
 * Bundled catalogue IDs resolve from the actual pinned seed via read-only SQL;
 * domain mutations go through HTTP only, never SQL writes.
 */
class RealBackendFixture private constructor(
    val baseUrl: String,
    private val connection: Connection,
    private val authKeyBytes: ByteArray,
) : AutoCloseable {
    /** Fresh isolated account with its own bearer token. Never logged. */
    class TestAccount(val accountId: String, val accessToken: String)

    /** Two distinct bundled exercises plus muscle/equipment IDs and display names from the live seed. */
    data class SeedIds(
        val bundledExerciseA: String,
        val bundledExerciseB: String,
        val bundledExerciseAName: String,
        val bundledExerciseBName: String,
        val muscleA: String,
        val muscleB: String,
        val muscleAName: String,
        val muscleBName: String,
        val equipment: String,
        val equipmentName: String,
    )

    val seedIds: SeedIds = loadSeedOrClose(connection)

    /**
     * Mints a fresh account/session/access-token triple atomically, as backend
     * tests do. Any insert failure rolls back the whole triple.
     */
    fun newAccount(): TestAccount {
        val accountId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val token = randomToken()
        val fingerprint = hmac(authKeyBytes, token.toByteArray(Charsets.UTF_8))
        val autoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            connection.prepareStatement("insert into accounts(id) values(?)").use {
                it.setObject(1, accountId)
                it.executeUpdate()
            }
            connection.prepareStatement(
                "insert into auth_sessions(id,account_id,created_at) values(?,?,clock_timestamp())",
            ).use {
                it.setObject(1, sessionId)
                it.setObject(2, accountId)
                it.executeUpdate()
            }
            connection.prepareStatement(
                "insert into auth_credentials(fingerprint,session_id,kind,issued_at,expires_at)" +
                    " values(? ,?,'access',clock_timestamp(),clock_timestamp()+interval '15 minutes')",
            ).use {
                it.setBytes(1, fingerprint)
                it.setObject(2, sessionId)
                it.executeUpdate()
            }
            connection.commit()
        } catch (e: Exception) {
            try {
                connection.rollback()
            } catch (ignored: Exception) {
                // Rollback failure must not mask the original insert failure.
            }
            throw IllegalStateException("Real-backend account minting failed")
        } finally {
            connection.autoCommit = autoCommit
        }
        return TestAccount(accountId.toString(), token)
    }

    fun apiFor(account: TestAccount): HttpGymSyncApi =
        HttpGymSyncApi(JdkSyncHttpTransport(baseUrl), account.accessToken)

    override fun close() {
        connection.close()
    }

    companion object {
        const val CONFIG_PROPERTY = "gymtracka.realBackendConfig"
        const val DISPOSABLE_DATABASE = "gymtracka_android_test"
        private val CANONICAL_UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val LOOPBACK_JDBC =
            Regex("^jdbc:postgresql://(127\\.0\\.0\\.1|localhost):([0-9]{1,5})/gymtracka_android_test$")

        /** Statically validated config values. Produced without any network use.
         * `toString` is redacted: secrets never appear in reports. */
        internal data class ValidatedConfig(
            val baseUrl: String,
            val jdbcUrl: String,
            val databaseUser: String,
            val databasePassword: String,
            val authKeyBytes: ByteArray,
            val runId: String,
        ) {
            override fun toString(): String =
                "ValidatedConfig(baseUrl=$baseUrl, jdbcUrl=$jdbcUrl, runId=$runId, secrets=<redacted>)"
        }

        /**
         * Runs every static guard over the explicit config file without
         * opening any socket. Throws a fixed message on any failure.
         */
        internal fun validateConfig(path: String): ValidatedConfig {
            val file = Paths.get(path)
            if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
                throw IllegalStateException("Real-backend config is not a plain file")
            }
            try {
                val permissions = Files.getPosixFilePermissions(file)
                if (permissions != setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) {
                    throw IllegalStateException("Real-backend config must have owner-only permissions")
                }
            } catch (e: UnsupportedOperationException) {
                // Non-POSIX filesystem: permission check is unavailable; the
                // explicit-path and guard-row checks below still apply.
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException("Real-backend config permissions are unreadable")
            }
            val text = try {
                Files.readString(file)
            } catch (e: Exception) {
                throw IllegalStateException("Real-backend config is unreadable")
            }
            // Strict pre-parse: malformed JSON (including duplicate keys) is
            // rejected before the lenient org.json readers see the text.
            try {
                val element = kotlinx.serialization.json.Json.parseToJsonElement(text)
                if (element !is kotlinx.serialization.json.JsonObject) {
                    throw IllegalStateException("Real-backend config is not valid JSON")
                }
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException("Real-backend config is not valid JSON")
            }
            val json = try {
                org.json.JSONObject(text)
            } catch (e: Exception) {
                // Includes org.json duplicate-key rejection: still invalid JSON.
                throw IllegalStateException("Real-backend config is not valid JSON")
            }
            // Strict typed reads: wrong types never coerce into strings.
            val baseUrl = strictConfigString(json, "baseUrl")
            val jdbcUrl = strictConfigString(json, "jdbcUrl")
            val databaseUser = strictConfigString(json, "databaseUser")
            val databasePassword = strictConfigString(json, "databasePassword")
            val authKey = strictConfigString(json, "authKey")
            val runId = strictConfigString(json, "runId")
            if (baseUrl.isEmpty() || jdbcUrl.isEmpty() || databaseUser.isEmpty() ||
                databasePassword.isEmpty() || authKey.isEmpty() || runId.isEmpty()
            ) {
                throw IllegalStateException("Real-backend config is missing required keys")
            }
            if (!runId.matches(CANONICAL_UUID)) {
                throw IllegalStateException("Real-backend config run ID is not a canonical UUID")
            }
            validateLoopbackOrigin(baseUrl)
            validateDisposableJdbcUrl(jdbcUrl)
            val keyBytes = try {
                Base64.getDecoder().decode(authKey)
            } catch (e: Exception) {
                throw IllegalStateException("Real-backend config key is not valid base64")
            }
            if (keyBytes.size != 32) {
                throw IllegalStateException("Real-backend config key has wrong length")
            }
            return ValidatedConfig(baseUrl.trimEnd('/'), jdbcUrl, databaseUser, databasePassword, keyBytes, runId)
        }

        private fun strictConfigString(json: org.json.JSONObject, key: String): String {
            val raw = json.opt(key)
            if (raw == null || raw == org.json.JSONObject.NULL || raw !is String) return ""
            return raw
        }

        /** Loads, validates, and connects. Throws a fixed message on any guard failure. */
        fun load(): RealBackendFixture {
            // Blank means the task ran without -P/env: fail here with the same
            // explicit text the task documents, never a default or a skip.
            val path = System.getProperty(CONFIG_PROPERTY)
            if (path.isNullOrBlank()) {
                throw IllegalStateException(
                    "testRealBackend requires -Pgymtracka.realBackendConfig=<config.json> " +
                        "or GYMTRACKA_REAL_BACKEND_CONFIG; refusing to accept a default external config.",
                )
            }
            val config = validateConfig(path)
            Class.forName("org.postgresql.Driver")
            DriverManager.setLoginTimeout(10)
            val connection = try {
                DriverManager.getConnection(config.jdbcUrl, config.databaseUser, config.databasePassword)
            } catch (e: Exception) {
                throw IllegalStateException("Real-backend database is unreachable")
            }
            try {
                validateGuardRow(connection, config.runId)
            } catch (e: Exception) {
                try {
                    connection.close()
                } catch (ignored: Exception) {
                    // Close failure must not mask the guard failure.
                }
                throw e
            }
            return RealBackendFixture(config.baseUrl, connection, config.authKeyBytes)
        }

        private fun validateLoopbackOrigin(baseUrl: String) {
            val uri = try {
                java.net.URI(baseUrl)
            } catch (e: Exception) {
                throw IllegalStateException("Real-backend origin is not a valid URI")
            }
            if (uri.scheme != "http") {
                throw IllegalStateException("Real-backend origin must be loopback http")
            }
            val host = uri.host?.lowercase()
            if (host != "127.0.0.1" && host != "localhost" && host != "::1") {
                throw IllegalStateException("Refusing non-loopback real-backend origin")
            }
            if (uri.userInfo != null || uri.query != null || uri.fragment != null) {
                throw IllegalStateException("Real-backend origin must carry no credentials, query, or fragment")
            }
            val path = uri.path
            if (path != null && path.isNotEmpty() && path != "/") {
                throw IllegalStateException("Real-backend origin must be root-only")
            }
        }

        private fun validateDisposableJdbcUrl(jdbcUrl: String) {
            // Full match: explicit loopback host and port, exact disposable
            // database, no query suffix that could override host/user/options.
            if (!LOOPBACK_JDBC.matches(jdbcUrl)) {
                throw IllegalStateException(
                    "Refusing real-backend database URL; only the disposable loopback database is allowed",
                )
            }
        }

        private fun validateGuardRow(connection: Connection, runId: String) {
            val ids = mutableListOf<String>()
            try {
                connection.createStatement().use { statement ->
                    statement.queryTimeout = 10
                    statement.executeQuery("select run_id from public.gymtracka_android_test_guard").use { rows ->
                        while (rows.next()) ids += rows.getObject(1).toString().lowercase()
                    }
                }
            } catch (e: Exception) {
                throw IllegalStateException("Real-backend guard table is unreadable")
            }
            if (ids.size != 1 || !ids[0].matches(CANONICAL_UUID) || ids[0] != runId) {
                throw IllegalStateException("Real-backend guard mismatch; refusing an unknown database")
            }
        }

        private fun loadSeedOrClose(connection: Connection): SeedIds {
            try {
                return loadSeedIds(connection)
            } catch (e: Exception) {
                try {
                    connection.close()
                } catch (ignored: Exception) {
                    // Close failure must not mask the seed failure.
                }
                throw e
            }
        }

        private fun loadSeedIds(connection: Connection): SeedIds {
            fun one(sql: String): String {
                try {
                    connection.createStatement().use { statement ->
                        statement.queryTimeout = 10
                        statement.executeQuery(sql).use { rows ->
                            if (!rows.next()) throw IllegalStateException("Isolated backend seed is incomplete")
                            return rows.getObject(1).toString()
                        }
                    }
                } catch (e: IllegalStateException) {
                    throw e
                } catch (e: Exception) {
                    throw IllegalStateException("Isolated backend seed is unreadable")
                }
            }
            fun nameOf(table: String, id: String): String {
                try {
                    connection.prepareStatement("select name from $table where id=?::uuid").use { statement ->
                        statement.queryTimeout = 10
                        statement.setString(1, id)
                        statement.executeQuery().use { rows ->
                            if (!rows.next()) throw IllegalStateException("Isolated backend seed is incomplete")
                            return rows.getString(1)
                        }
                    }
                } catch (e: IllegalStateException) {
                    throw e
                } catch (e: Exception) {
                    throw IllegalStateException("Isolated backend seed is unreadable")
                }
            }
            val exerciseA = one("select id from exercises where owner_id is null order by id limit 1")
            val exerciseB = one("select id from exercises where owner_id is null order by id limit 1 offset 1")
            val muscleA = one("select id from muscle_groups order by id limit 1")
            val muscleB = one("select id from muscle_groups order by id limit 1 offset 1")
            val equipment = one("select id from equipment order by id limit 1")
            return SeedIds(
                bundledExerciseA = exerciseA,
                bundledExerciseB = exerciseB,
                bundledExerciseAName = nameOf("exercises", exerciseA),
                bundledExerciseBName = nameOf("exercises", exerciseB),
                muscleA = muscleA,
                muscleB = muscleB,
                muscleAName = nameOf("muscle_groups", muscleA),
                muscleBName = nameOf("muscle_groups", muscleB),
                equipment = equipment,
                equipmentName = nameOf("equipment", equipment),
            )
        }

        private val secureRandom = SecureRandom()

        private fun randomToken(): String {
            val bytes = ByteArray(32)
            secureRandom.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private fun hmac(key: ByteArray, value: ByteArray): ByteArray {
            val macKey = hmacRaw(key, "GymTracka auth HMAC v1".toByteArray(Charsets.UTF_8))
            return hmacRaw(macKey, value)
        }

        private fun hmacRaw(key: ByteArray, value: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(value)
        }
    }
}
