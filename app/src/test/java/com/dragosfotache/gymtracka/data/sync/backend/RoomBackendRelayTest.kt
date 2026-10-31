package com.dragosfotache.gymtracka.data.sync.backend

import com.dragosfotache.gymtracka.data.sync.api.OpaqueToken
import com.dragosfotache.gymtracka.data.sync.api.SyncApiException
import com.dragosfotache.gymtracka.data.sync.codec.decodeDurableRequest
import com.dragosfotache.gymtracka.data.sync.http.BoundServiceAccessToken
import com.dragosfotache.gymtracka.data.sync.http.BoundServiceAccessTokenProvider
import com.dragosfotache.gymtracka.data.sync.http.HttpGymSyncApi
import com.dragosfotache.gymtracka.data.sync.http.OkHttpServiceTransport
import com.dragosfotache.gymtracka.data.sync.http.ServiceSessionBinding
import com.dragosfotache.gymtracka.data.sync.http.ServiceHttpTimeouts
import com.dragosfotache.gymtracka.data.sync.http.newServiceHttpClient
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.tls.HandshakeCertificates
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Checks host relay controls independently of Android Room convergence assertions.
 */
class RoomBackendRelayTest {
    @Test
    fun occupiedMetadataIsRefusedBeforeFixtureAccess() {
        val directory = Files.createTempDirectory("relay-relay-ownership-")
        val metadata = directory.resolve("metadata.json")
        Files.writeString(metadata, "unrelated")
        try {
            try {
                RoomBackendRelayMain.main(arrayOf("/missing-isolated-config", metadata.toString()))
                fail("Occupied metadata must fail before fixture access")
            } catch (_: IllegalArgumentException) {
                assertEquals("unrelated", Files.readString(metadata))
            }
        } finally {
            Files.delete(metadata)
            Files.delete(directory)
        }
    }

    @Test
    fun sameAccountSessionRequiresMintedBearerAndReturnsDistinctRealGrant() {
        RealBackendFixture.load().use { fixture ->
            RealBackendHttpBridge(fixture.baseUrl, RoomRelayControls(fixture)).use { relay ->
                val cert = CertificateFactory.getInstance("X.509").generateCertificate(
                    Base64.getDecoder().decode(relay.certificateBase64).inputStream(),
                ) as X509Certificate
                val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert).build()
                val client = newServiceHttpClient(
                    builder = okhttp3.OkHttpClient.Builder()
                        .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager),
                )
                try {
                    fun call(path: String, token: String? = null, body: String = ""): Pair<Int, String> {
                        val builder = Request.Builder()
                            .url("https://localhost:${relay.origin.port}$path")
                            .post(body.toRequestBody("application/json".toMediaType()))
                        token?.let { builder.header("Authorization", "Bearer $it") }
                        return client.newCall(builder.build()).execute().use {
                            it.code to it.body.string()
                        }
                    }
                    val path = "/__test__/same-account-session"
                    assertEquals(401, call(path).first)
                    assertEquals(401, call(path, "unknown-test-bearer").first)
                    val initial = call("/__test__/session")
                    assertEquals(200, initial.first)
                    val first = JSONObject(initial.second)
                    val replacement = call(path, first.getString("accessToken"))
                    assertEquals(200, replacement.first)
                    val second = JSONObject(replacement.second)
                    assertEquals(first.getString("accountId"), second.getString("accountId"))
                    assertTrue(
                        "Distinct setup session must have distinct access credentials",
                        first.getString("accessToken") != second.getString("accessToken"),
                    )
                    assertTrue(
                        "Distinct setup session must have distinct refresh credentials",
                        first.getString("refreshToken") != second.getString("refreshToken"),
                    )
                    assertTrue(second.getLong("accessExpiresAtMillis") > 0L)
                    assertTrue(second.getLong("refreshExpiresAtMillis") > second.getLong("accessExpiresAtMillis"))
                    // Credentials come from actual service rotation, not merely fixture
                    // JSON: the real service accepts the distinct new bearer over TLS.
                    val accepted = call("/v1/sync/push", second.getString("accessToken"), "{\"mutations\":[]}")
                    assertEquals(200, accepted.first)
                    assertEquals(0, JSONObject(accepted.second).getJSONArray("results").length())
                } finally {
                    client.dispatcher.cancelAll()
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                }
            }
        }
    }

    @Test
    fun responseLossIsOptInTokenScopedAndFollowsRealCommit() {
        RealBackendFixture.load().use { fixture ->
            RealBackendHttpBridge(fixture.baseUrl, RoomRelayControls(fixture)).use { relay ->
                val cert = CertificateFactory.getInstance("X.509").generateCertificate(
                    Base64.getDecoder().decode(relay.certificateBase64).inputStream(),
                ) as X509Certificate
                val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert).build()
                val client = newServiceHttpClient(
                    timeouts = ServiceHttpTimeouts(readMillis = 2_000L),
                    builder = okhttp3.OkHttpClient.Builder()
                        .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager),
                )
                try {
                    val origin = "https://localhost:${relay.origin.port}"
                    fun call(path: String, token: String? = null, json: String = ""): Pair<Int, String> {
                        val builder = Request.Builder().url(origin + path)
                            .post(json.toRequestBody("application/json".toMediaType()))
                        token?.let { builder.header("Authorization", "Bearer $it") }
                        return client.newCall(builder.build()).execute().use { it.code to it.body.string() }
                    }
                    fun account(): JSONObject {
                        val result = call("/__test__/account")
                        assertEquals(200, result.first)
                        return JSONObject(result.second)
                    }
                    val firstAccount = account()
                    val first = firstAccount.getString("accessToken")
                    val second = account().getString("accessToken")
                    assertEquals(401, call("/__test__/lose-next-push", "unrecognized").first)
                    assertEquals(204, call("/__test__/lose-next-push", first).first)
                    val root = UUID.randomUUID().toString()
                    val mutation = UUID.randomUUID().toString()
                    val mutationJson = """{"mutationId":"$mutation","type":"workout.start","aggregateType":"workout","aggregateId":"$root","localGeneration":1,"payload":{"id":"$root","name":"Relay control proof","startedAt":"2026-09-24T10:00:00.000Z"}}"""
                    val body = "{\"mutations\":[$mutationJson]}"
                    val durable = decodeDurableRequest(
                        JSONObject().put("payloadVersion", 1).put("requestJson", mutationJson)
                            .put("affectedRoots", org.json.JSONArray().put(
                                JSONObject().put("aggregateType", "workout")
                                    .put("aggregateId", root).put("localGeneration", 1),
                            )),
                    )
                    val binding = ServiceSessionBinding(relay.origin, firstAccount.getString("accountId"), 1L)
                    val api = HttpGymSyncApi(
                        binding,
                        BoundServiceAccessTokenProvider { BoundServiceAccessToken(binding, OpaqueToken(first)) },
                        OkHttpServiceTransport(relay.origin, client),
                    )
                    // Another token is unaffected; the armed token's loss is not consumed.
                    assertEquals(200, call("/v1/sync/push", second, "{\"mutations\":[]}").first)
                    runBlocking {
                        try {
                            api.push(listOf(durable))
                            fail("Real commit must lose its downstream response")
                        } catch (_: SyncApiException.Transient) {
                            // Production adapter classifies real loss as retryable uncertainty,
                            // not retained malformed content or an invented domain result.
                        }
                    }
                    val retry = call("/v1/sync/push", first, body)
                    assertEquals(200, retry.first)
                    val outcome = JSONObject(retry.second).getJSONArray("results").getJSONObject(0)
                    assertEquals("duplicate", outcome.getString("status"))
                    val changes = outcome.getJSONObject("batch").getJSONArray("changes")
                    assertEquals(1, changes.length())
                    val change = changes.getJSONObject(0)
                    assertEquals(root, change.getString("aggregateId"))
                    assertEquals(1L, change.getLong("revision"))
                    assertEquals("Relay control proof", change.getJSONObject("record").getString("name"))
                    assertTrue(change.getJSONObject("record").getJSONArray("exercises").length() == 0)
                } finally {
                    client.dispatcher.cancelAll()
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                }
            }
        }
    }
}
