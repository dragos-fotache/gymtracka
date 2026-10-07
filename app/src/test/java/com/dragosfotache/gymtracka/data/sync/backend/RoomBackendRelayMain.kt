package com.dragosfotache.gymtracka.data.sync.backend

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.json.JSONObject

/** Narrow opt-in controls for synthetic credentials and downstream response loss only. */
internal class RoomRelayControls(private val fixture: RealBackendFixture) {
    private val tokens = ConcurrentHashMap.newKeySet<String>()
    private val responseLoss = ConcurrentHashMap.newKeySet<String>()

    fun dispatch(request: RecordedRequest): MockResponse? {
        if (!request.target.startsWith("/__r10__/")) return null
        if (request.method != "POST") return response(405)
        return when (request.target) {
            "/__r10__/account" -> {
                // The JDBC fixture is host-only and already checks its live run guard.
                val account = synchronized(fixture) { fixture.newAccount() }
                tokens.add("Bearer ${account.accessToken}")
                response(
                    200,
                    JSONObject()
                        .put("accountId", account.accountId)
                        .put("accessToken", account.accessToken)
                        .toString(),
                )
            }
            "/__r10__/lose-next-push" -> {
                val authorization = request.headers["Authorization"]
                if (authorization == null || authorization !in tokens) {
                    response(401)
                } else {
                    responseLoss.add(authorization)
                    response(204)
                }
            }
            else -> response(404)
        }
    }

    fun consumeResponseLoss(request: RecordedRequest): Boolean =
        request.headers["Authorization"]?.let(responseLoss::remove) ?: false

    private fun response(code: Int, body: String = ""): MockResponse = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .body(body)
        .build()

    override fun toString(): String = "RoomRelayControls(redacted)"
}

/**
 * Host JVM only. Run outside Gradle so service lifetime never occupies a build slot.
 * args: guarded isolated-backend config, exclusive public relay metadata path.
 * No credentials, private certificate key, DB config or response bodies are logged.
 */
object RoomBackendRelayMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Expected isolated config and exclusive metadata path" }
        val metadata = Path.of(args[1])
        require(!Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) { "Relay metadata path is occupied" }
        System.setProperty(RealBackendFixture.CONFIG_PROPERTY, args[0])
        RealBackendFixture.load().use { fixture ->
            RealBackendHttpBridge(fixture.baseUrl, RoomRelayControls(fixture)).use { relay ->
                metadata.toAbsolutePath().parent?.let(Files::createDirectories)
                val permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
                // CREATE_NEW also refuses symlinks/concurrent creators; never truncate.
                Files.newByteChannel(
                    metadata,
                    setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    permissions,
                ).use { channel ->
                    val bytes = JSONObject()
                        .put("port", relay.origin.port)
                        .put("certificate", relay.certificateBase64)
                        .toString().toByteArray(Charsets.UTF_8)
                    val buffer = java.nio.ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                }
                val identity = Files.readAttributes(metadata, BasicFileAttributes::class.java).fileKey()
                val shutdown = Thread {
                    try {
                        relay.close()
                    } finally {
                        try {
                            fixture.close()
                        } finally {
                            removeOwnedMetadata(metadata, identity)
                        }
                    }
                }
                Runtime.getRuntime().addShutdownHook(shutdown)
                println("Room backend relay ready")
                try {
                    CountDownLatch(1).await()
                } finally {
                    Runtime.getRuntime().removeShutdownHook(shutdown)
                    removeOwnedMetadata(metadata, identity)
                }
            }
        }
    }

    private fun removeOwnedMetadata(path: Path, identity: Any?) {
        if (identity != null && !Files.isSymbolicLink(path) &&
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
            Files.readAttributes(path, BasicFileAttributes::class.java).fileKey() == identity
        ) {
            Files.delete(path)
        }
    }
}
