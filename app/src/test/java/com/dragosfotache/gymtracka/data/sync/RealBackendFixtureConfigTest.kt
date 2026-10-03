package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.backend.RealBackendFixture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Offline tests for the real-backend fixture's static guards, exercised
 * through the pure validator: scratch config files only, no sockets, no
 * database, no live backend. These run in the ordinary offline JVM suite.
 */
class RealBackendFixtureConfigTest {
    private val scratchDir = Files.createTempDirectory("fixture-config-test")

    @After
    fun cleanScratch() {
        scratchDir.toFile().walkBottomUp().forEach { it.delete() }
    }

    private fun isPosix() =
        java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    private fun writeConfig(name: String, content: String, lockedDown: Boolean = true): String {
        assumeTrue(isPosix())
        val path = scratchDir.resolve(name)
        Files.writeString(path, content)
        if (lockedDown) {
            Files.setPosixFilePermissions(
                path,
                setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                ),
            )
        }
        return path.toString()
    }

    private fun validShape(
        baseUrl: String = "http://127.0.0.1:18080",
        jdbcUrl: String = "jdbc:postgresql://127.0.0.1:5432/gymtracka_android_test",
        runId: String = "12345678-1234-1234-1234-123456789012",
        authKey: String = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        databasePassword: String = "pw-S3cret-shape",
    ) = "{\"baseUrl\":\"" + baseUrl + "\",\"jdbcUrl\":\"" + jdbcUrl + "\"," +
        "\"databaseUser\":\"android_test\",\"databasePassword\":\"" + databasePassword + "\"," +
        "\"authKey\":\"" + authKey + "\",\"runId\":\"" + runId + "\",\"containerName\":\"test\"}"

    private fun validateExpecting(path: String, message: String) {
        try {
            RealBackendFixture.validateConfig(path)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals(message, e.message)
            assertEquals(null, e.cause)
        }
    }

    @Test
    fun missingFileFailsExplicitly() {
        validateExpecting(
            scratchDir.resolve("absent.json").toString(),
            "Real-backend config is not a plain file",
        )
    }

    @Test
    fun directoryFailsExplicitly() {
        validateExpecting(scratchDir.toString(), "Real-backend config is not a plain file")
    }

    @Test
    fun symlinkFailsExplicitly() {
        assumeTrue(isPosix())
        val target = scratchDir.resolve("real.json")
        Files.writeString(target, validShape())
        val link = scratchDir.resolve("link.json")
        Files.createSymbolicLink(link, target)
        validateExpecting(link.toString(), "Real-backend config is not a plain file")
    }

    @Test
    fun groupReadableFileFailsExplicitly() {
        assumeTrue(isPosix())
        val path = scratchDir.resolve("open.json")
        Files.writeString(path, validShape())
        Files.setPosixFilePermissions(
            path,
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.GROUP_READ,
            ),
        )
        validateExpecting(path.toString(), "Real-backend config must have owner-only permissions")
    }

    @Test
    fun malformedJsonFailsExplicitly() {
        validateExpecting(writeConfig("bad.json", "{not json"), "Real-backend config is not valid JSON")
    }

    @Test
    fun duplicateKeysFailExplicitly() {
        validateExpecting(
            writeConfig("dup.json", "{\"baseUrl\":\"http://127.0.0.1:18080\",\"baseUrl\":\"http://127.0.0.1:18081\"}"),
            "Real-backend config is not valid JSON",
        )
    }

    @Test
    fun nonObjectJsonFailsExplicitly() {
        validateExpecting(writeConfig("array.json", "[1,2]"), "Real-backend config is not valid JSON")
    }

    @Test
    fun missingKeysFailExplicitly() {
        validateExpecting(
            writeConfig("keys.json", "{\"baseUrl\":\"http://127.0.0.1:18080\"}"),
            "Real-backend config is missing required keys",
        )
    }

    @Test
    fun wrongTypedValuesFailExplicitly() {
        // Numbers/booleans/null must not coerce into strings.
        validateExpecting(
            writeConfig(
                "types.json",
                "{\"baseUrl\":18080,\"jdbcUrl\":null,\"databaseUser\":true," +
                    "\"databasePassword\":1,\"authKey\":[],\"runId\":{}}",
            ),
            "Real-backend config is missing required keys",
        )
    }

    @Test
    fun nonCanonicalRunIdFailsExplicitly() {
        validateExpecting(
            writeConfig("run.json", validShape(runId = "NOT-A-UUID")),
            "Real-backend config run ID is not a canonical UUID",
        )
    }

    @Test
    fun nonLoopbackOriginFailsExplicitly() {
        validateExpecting(
            writeConfig("origin.json", validShape(baseUrl = "http://192.168.1.10:18080")),
            "Refusing non-loopback real-backend origin",
        )
    }

    @Test
    fun originWithUserInfoFailsExplicitly() {
        validateExpecting(
            writeConfig("user.json", validShape(baseUrl = "http://user@127.0.0.1:18080")),
            "Real-backend origin must carry no credentials, query, or fragment",
        )
    }

    @Test
    fun originWithQueryFailsExplicitly() {
        validateExpecting(
            writeConfig("query.json", validShape(baseUrl = "http://127.0.0.1:18080/?debug=1")),
            "Real-backend origin must carry no credentials, query, or fragment",
        )
    }

    @Test
    fun originWithPathFailsExplicitly() {
        validateExpecting(
            writeConfig("path.json", validShape(baseUrl = "http://127.0.0.1:18080/v1")),
            "Real-backend origin must be root-only",
        )
    }

    @Test
    fun personalDatabaseUrlFailsExplicitly() {
        validateExpecting(
            writeConfig("prod.json", validShape(jdbcUrl = "jdbc:postgresql://127.0.0.1:5432/gymtracka_prod")),
            "Refusing real-backend database URL; only the disposable loopback database is allowed",
        )
    }

    @Test
    fun developmentDatabaseUrlFailsExplicitly() {
        validateExpecting(
            writeConfig("dev.json", validShape(jdbcUrl = "jdbc:postgresql://localhost:5432/gymtracka")),
            "Refusing real-backend database URL; only the disposable loopback database is allowed",
        )
    }

    @Test
    fun querySuffixUrlFailsExplicitly() {
        validateExpecting(
            writeConfig(
                "suffix.json",
                validShape(jdbcUrl = "jdbc:postgresql://127.0.0.1:5432/gymtracka_android_test?sslmode=require"),
            ),
            "Refusing real-backend database URL; only the disposable loopback database is allowed",
        )
    }

    @Test
    fun nonLoopbackJdbcHostFailsExplicitly() {
        validateExpecting(
            writeConfig("host.json", validShape(jdbcUrl = "jdbc:postgresql://10.0.0.5:5432/gymtracka_android_test")),
            "Refusing real-backend database URL; only the disposable loopback database is allowed",
        )
    }

    @Test
    fun missingJdbcPortFailsExplicitly() {
        validateExpecting(
            writeConfig("port.json", validShape(jdbcUrl = "jdbc:postgresql://127.0.0.1/gymtracka_android_test")),
            "Refusing real-backend database URL; only the disposable loopback database is allowed",
        )
    }

    @Test
    fun malformedKeyFailsExplicitly() {
        validateExpecting(
            writeConfig("key.json", validShape(authKey = "!!!not-base64!!!")),
            "Real-backend config key is not valid base64",
        )
    }

    @Test
    fun shortKeyFailsExplicitly() {
        validateExpecting(
            writeConfig("short.json", validShape(authKey = "AAAA")),
            "Real-backend config key has wrong length",
        )
    }

    @Test
    fun validShapePassesStaticGuardsWithoutSockets() {
        val config = RealBackendFixture.validateConfig(writeConfig("good.json", validShape()))
        assertEquals("http://127.0.0.1:18080", config.baseUrl)
        assertEquals("12345678-1234-1234-1234-123456789012", config.runId)
        assertEquals(32, config.authKeyBytes.size)
        // Secrets stay out of every string form.
        assertTrue(!config.toString().contains("pw-S3cret-shape"))
        assertTrue(config.toString().contains("<redacted>"))
    }

    @Test
    fun loadWithoutPropertyFailsExplicitly() {
        val previous = System.getProperty(RealBackendFixture.CONFIG_PROPERTY)
        System.clearProperty(RealBackendFixture.CONFIG_PROPERTY)
        try {
            RealBackendFixture.load()
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals(
                "testRealBackend requires -Pgymtracka.realBackendConfig=<config.json> " +
                    "or GYMTRACKA_REAL_BACKEND_CONFIG; refusing to accept a default external config.",
                e.message,
            )
            assertEquals(null, e.cause)
        } finally {
            if (previous != null) System.setProperty(RealBackendFixture.CONFIG_PROPERTY, previous)
        }
    }
}
