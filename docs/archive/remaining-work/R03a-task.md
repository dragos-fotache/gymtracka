> **Historical record, not current instructions.** See the [archive boundary](../README.md) and [current pickup](../../coordinator-handoff.md).

# R03a — Secure origin and cancellable HTTP primitive

Status: **implemented and coordinator-accepted** (2026-10-03); see [review](R03a-review.md).
The owner instructed “start work at R03a”. Final gate: 423 offline JVM tests in 35 suites, five filtered Android
transport tests plus one signed-out startup smoke on API 37, compilation/assembly/lint and preservation checks passed.
Zero test failures/errors/skips. No backend/OAuth/API-24/full-device pass, activation, commit or push.
This is the SAME normative specification/API contract for Worker and QA. R02a–d are accepted; R02d is uncommitted.
Pickup Android HEAD `623207c2044f9503410bca4426d9226787a2bda9`, shared HEAD
`f65abb82512c373ade9ab08699ef68971b2e8ceb`. Preserve both dirty trees, including untracked R02d/R03 planning files.
No R03b/c/d, R02e, commit/push, endpoint activation, deployment, backend or private configuration change.

## Authority, inventory and scope

Read root AGENTS, [R03 split](R03-task.md), [R01 design](R01-design.md), finalized shared
[service protocol](../../../shared-docs/docs/backend-contract/service-protocol.md),
[authentication](../../../shared-docs/docs/backend-contract/authentication.md),
[compatibility](../../../shared-docs/docs/backend-contract/client-compatibility.md) and
[OpenAPI](../../../shared-docs/docs/backend-contract/openapi.yaml). Do not modify shared contracts.

Production has SDK-independent sync/auth interfaces, strict codecs, redacted opaque tokens and closed-cause
SyncApiException categories; it has no HTTP client or endpoint configuration. JVM-only JDK HTTP infrastructure is NOT
production implementation. No Room/schema/assets/UI/shell/state/session changes belong here.

Deliver reusable origin/request/response/I/O primitives only. INTERNET and explicit release cleartext refusal are allowed;
networking is NOT wired into GymApp or any repository. Missing configuration is null, not an invented URL or failure of
signed-out construction. No URLs/keys/OAuth inputs added to Gradle, APK or documentation.

## Library selection and coordinator wiring

Select **OkHttp 5.5.0**, current stable confirmed from Maven Central metadata (release 5.5.0,
lastUpdated 20260816161727) and upstream README at pickup. Android API 21+/Java 8+ is supported, below app minSdk 24;
Gradle selects the Android variant. Existing Kotlin 2.4.20/coroutines 1.11.0 are retained. Use only okhttp core in
production, no Retrofit/logging/coroutine wrapper. OkHttp's AndroidX Startup initializer remains enabled; do not edit app
startup. Test-only `mockwebserver3` and `okhttp-tls`, same version, support local TLS tests, not backend simulation.

Sources checked: https://repo.maven.apache.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml and
https://raw.githubusercontent.com/square/okhttp/master/README.md . Upstream links now refer to lysine.dev/okhttp.
Coordinator also checked tagged RetryAndFollowUpInterceptor: retryOnConnectionFailure(false) alone does NOT prevent
GET follow-up on 503 Retry-After: 0. A per-call on-wire guard is mandatory (see below), not reliance on a flag alone.

Coordinator ONLY edits gradle/libs.versions.toml, app/build.gradle.kts and app/src/main/AndroidManifest.xml:
production okhttp; test-only TLS/server helpers for JVM and Android tests; INTERNET; application usesCleartextTraffic=false.
No debug cleartext override or permissive trust manager, network security XML, URL, logging interceptor or global config.

## Required API — package data.sync.http

All declarations below are `internal`, except inherited public library overrides. This is app-internal infrastructure,
not a new SDK. QA imports these exact signatures without source access. New classes are final unless interfaces below.

```kotlin
class ServiceOrigin private constructor(/* implementation-owned */) {
    val scheme: String // always "https"
    val host: String // canonical host, bracket-free IPv6
    val port: Int // effective port, 443 if omitted
    companion object {
        fun parse(value: String): ServiceOrigin
        fun configured(value: String?): ServiceOrigin? // null => unavailable; invalid nonnull fails
    }
    // equality/hashCode compare scheme/host/effective port, no original-text identity
}

data class ServiceHttpTimeouts(
    val connectMillis: Long = 10_000L,
    val readMillis: Long = 30_000L,
    val writeMillis: Long = 30_000L,
    val callMillis: Long = 60_000L,
)

enum class ServiceHttpMethod { GET, POST }
class ServiceHttpRequest(
    val method: ServiceHttpMethod,
    val path: String,
    query: List<Pair<String, String>> = emptyList(),
    headers: Map<String, String> = emptyMap(),
    val body: String? = null,
) {
    val query: List<Pair<String, String>> // immutable defensive snapshot, order/repeats preserved
    val headers: Map<String, String> // immutable defensive snapshot
}
interface ServiceHttpTransport {
    suspend fun <T> withResponse(
        request: ServiceHttpRequest,
        consume: suspend (ServiceHttpResponse) -> T,
    ): T
}
class OkHttpServiceTransport(
    origin: ServiceOrigin,
    timeouts: ServiceHttpTimeouts = ServiceHttpTimeouts(),
) : ServiceHttpTransport {
    internal constructor(origin: ServiceOrigin, callFactory: okhttp3.Call.Factory)
}
// Trusted construction seam: default builder uses normal platform TLS and hostname verification.
// The optional builder is internal test wiring for a local trusted CA, NEVER settings/user URL configuration.
fun newServiceHttpClient(
    timeouts: ServiceHttpTimeouts = ServiceHttpTimeouts(),
    builder: okhttp3.OkHttpClient.Builder = okhttp3.OkHttpClient.Builder(),
): okhttp3.OkHttpClient

class ServiceHttpResponse /* no public caller construction required */ {
    val statusCode: Int
    val headers: Map<String, List<String>> // immutable, case-insensitive lookup via helper
    suspend fun readJsonBody(): String
    suspend fun readBodyChunk(buffer: ByteArray): Int // fills from offset 0, -1 on verified EOF
}
fun retryAfterMillis(headers: Map<String, List<String>>): Long?
```

Existing API facts: `data.sync.api.SyncApiException.Transient(retryAfterMillis: Long? = null)`;
`SyncApiException.MalformedResponse()`; `data.sync.model.SyncModelException(message: String)`.
`MAX_RETRY_AFTER_MILLIS` is 300_000L. Network/response failures retain no raw cause. No existing failure type edits.
Library `Call.Factory.newCall(Request): Call`, `Call.enqueue(Callback)`, `Call.cancel()`, `Callback.onResponse(Call, Response)`
and `onFailure(Call, IOException)` form the narrow scripted cancellation/late-response seam. QA may use external library
APIs/docs, not production source. The injected factory is trusted I/O supplied by tests/later constructor wiring; it does
not allow production URLs with HTTP. No production insecure-origin switch or loopback factory.

## Origin and request contract

- Parse ORIGINAL text with strict URI syntax before any permissive HttpUrl normalization. Do not trim. Reject empty or
  whitespace, control characters, backslashes, unpaired surrogates, non-HTTPS, absent/invalid host, empty/invalid port,
  ports outside 1..65535, any user-info (including empty @), query or fragment (including empty ?/#), and any path other
  than absent or exactly `/`. Reject percent-encoded authority/host and percent-encoded/normalized root paths.
- Scheme and ASCII host case normalize; explicit :443 equals omitted. Accept conventional DNS names, IPv4 and bracketed
  IPv6, including localhost for HTTPS tests. ASCII/punycode host names only in v1 configuration; reject Unicode host
  rather than implicit IDN conversion, and reject IPv6 zone IDs. No substring/suffix service-origin comparison. IPv6 is
  canonicalized by HttpUrl after strict original validation. `configured(null)` is null; `configured("")` is invalid.
- Every request URL is built FROM this origin and validated path, never resolve arbitrary relative/absolute URL text.
  Path is `/v1/` followed by one or more nonempty ASCII segments of `[A-Za-z0-9_-]+`, separated by `/`. No trailing slash,
  empty segment, dot/dotdot, percent encoding, backslash, query, fragment, scheme or authority accepted. This grammar
  supports fixed v1 endpoint and UUID resource paths without adding endpoint adapters.
- Query names are nonempty, values may be empty. Preserve every valid Unicode scalar/string, whitespace and repeats in
  submitted order, without trimming or predecoding `%`, `+`, `/`, `?`, `#`, `&`, `=`. Encode as UTF-8 query components;
  literal spaces use `%20`, plus is `%2B`, percent is `%25`. Literal controls may be percent-encoded safely. Reject
  unpaired surrogates before URL encoding. No account/page-token parsing or length cap.
- Caller headers allow ONLY Authorization and Idempotency-Key, case insensitive. Reject case-equivalent duplicates,
  empty/unknown names (including Host/Cookie/Content-Length/Proxy-Authorization/Accept-Encoding), injection controls,
  tabs, non-ASCII, DEL, empty values and leading/trailing space. Interior visible ASCII and spaces are preserved exactly.
  Never trim/silently rewrite credentials; opaque body/query tokens remain broader than representable HTTP headers.
- Transport owns `Accept: application/json`, `Accept-Encoding: identity`, and, for POST, `Content-Type: application/json; charset=utf-8`.
  GET requires body null; POST requires nonnull body (empty body permitted; primitive does not parse JSON). Encode exact
  supplied String as UTF-8 with no normalization/BOM/newline or JSON serialization. Reject unpaired surrogates before
  transmission, rather than Java replacement. No permanent body/query/domain size cap.
- Invalid configuration/request/timeout values fail with fixed safe SyncModelException BEFORE I/O. Validate timeout
  constructor/copy values in 1..Int.MAX_VALUE milliseconds (no zero/infinite/negative timeout).
- Origin/request/response/transport toString must not show raw URLs, paths, query names/values, header names/values, bodies,
  tokens or original input. Origin identity is available only through explicit sensitive properties/equality, not display.
  Timeout/method metadata is nonsensitive. Defensive snapshots must not expose mutable backing collections.

## Concrete client and exactly-one-send policy

Default TLS certificate and hostname verification, default platform trust; no pinning/private credentials/trust changes.
No cookies, disk cache, redirects (same-origin included), cross-scheme redirects, automatic authenticator/proxy auth,
connection-failure retry or logging. Explicit positive connect/read/write/whole-call timeouts above.

`newServiceHttpClient` must enforce this policy even when a trusted test builder is supplied: reset cache/cookie jar,
authenticators/redirect/retry flags, configured timeouts, clear caller application/network interceptors, disable custom
event logging. Preserve only test TLS trust setup for local CA tests; never install a permissive hostname verifier.
Enforce normal hostname verification rather than preserving a test builder's permissive verifier.
Use a fresh per-CALL one-send guard installed at the network boundary (not per transport/request reuse): forbid another
on-wire exchange for the same Call, including 503 Retry-After:0 GET and HTTP/2 coalescing follow-ups. POST bodies are
one-shot. A refused internal follow-up may surface a safe Transient instead of the already-closed initial 503; no
second request is sent. Repeated EXPLICIT withResponse invocations may each send once with identical bytes.
Test the guard with actual network observation, not only inspecting flags. No explicit retry scheduling here.

## Response consumption, streaming and cancellation

`withResponse` owns the Call and response throughout the suspend consumer. Invoke the consumer exactly once after
headers; it receives status/header metadata even for non-2xx. No operation/status/error-envelope classifier in R03a.
Always close response on consumer return/throw/cancellation, failed setup and late callback. Cancellation cancels actual
Call both before headers AND during body consumption/suspension; no orphan readers/background jobs after return.
Preserve the original coroutine CancellationException, including injected cancellation. Prevent a callback after
cancellation from resuming successful work; close late response. Cleanup must not attach raw suppressed causes.
Do not mask arbitrary exceptions thrown by consumer code. Close methods/resources idempotently, with safe cleanup.

- Response lifetime is scoped to consumer; after it ends, any body read fails fixed-safe SyncModelException, without
  touching I/O. Mode misuse also fails fixed-safe SyncModelException. One sequential consumer only (no concurrent reads
  required). Raw header snapshots survive but remain sensitive.
- `readBodyChunk` supplies incremental suspend reads without buffering the entire body; buffer must be nonempty (fixed
  safe input failure). A sequence of reads is permitted; -1 only on clean/verified EOF. This is export-compatible resource
  ownership, not an export feature/file/save pipeline. After chunk mode begins, readJsonBody fails fixed-safe; vice versa,
  readJsonBody is one-shot and chunk mode cannot follow it.
- `readJsonBody` consumes the complete body, no truncation, no arbitrary permanent size cap, strict UTF-8 decoder with
  REPORT for malformed/unmappable bytes, no BOM stripping or replacement. Application/json is required (case insensitive);
  charset absent or UTF-8 (case insensitive, optionally quoted) is allowed. Duplicate Content-Type/charset, invalid media
  syntax, missing/wrong type or another charset reject as MalformedResponse. Unrelated valid media parameters may be
  ignored; body text is returned unchanged, JSON grammar remains the later codec's responsibility.
- Transport requests identity encoding. Unexpected nonidentity Content-Encoding is malformed (no silent compressed-byte
  JSON decoding). Complete body reads verify Content-Length where available (header and body metadata must agree if both
  specify it); premature EOF/framing/length mismatch is MalformedResponse, not a partial success. General I/O/offline/TLS/timeouts are closed-cause Transient. Preserve
  CancellationException without converting cancellation-induced I/O to Transient. No raw URL/body/header/cause in errors.
- Blocking stream work stays off caller/Main dispatcher. Actual call cancellation/response closure interrupts a blocked
  read; `withContext(IO)` alone is insufficient as cancellation wiring. Use structured ownership, not GlobalScope,
  unmanaged threads/executors or detached readers. Whole-call timeout spans body consumption.

## Retry-After

Case-insensitive header-name lookup; exactly ONE value across all equivalent keys. Strip only HTTP outer SP/HTAB.
Accept ASCII digits representing a positive whole-second integer; leading zeros permitted if positive. Any length is
allowed: validate ALL digits, saturate overflow safely to five minutes BEFORE multiplication. 1 => 1000; 299=>299000;
300 and larger=>300000. Null for absent/empty/multiple values, all zero, negative/plus/decimal/exponent, Unicode digits,
HTTP dates or controls. Never schedule retries or retain raw hints. Malformed/nonpositive hints mean caller backoff.

## Disjoint file/source allowlists

**Worker:** NEW production files ONLY under `app/src/main/java/com/dragosfotache/gymtracka/data/sync/http/`:
`ServiceOrigin.kt`, `ServiceHttpTransport.kt`, `OkHttpServiceTransport.kt`. May read existing production api/model/codec,
root AGENTS, this spec/cited shared PROSE/OpenAPI and external library APIs. NEVER tests, fixtures, reports, QA work,
broad diffs/status or test LSP references. No build/config/docs edits. Restricted production mcpls; bounded failure may
fall back to exact reads/compiler, never configuration changes. No tests or Gradle without coordinator slot.

**QA:** NEW files ONLY:
- `app/src/test/java/com/dragosfotache/gymtracka/data/sync/http/ServiceOriginRequestTest.kt`
- `app/src/test/java/com/dragosfotache/gymtracka/data/sync/http/ServiceHttpOwnershipTest.kt`
- `app/src/test/java/com/dragosfotache/gymtracka/data/sync/http/ServiceHttpNetworkTest.kt`
- `app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/http/ServiceHttpAndroidTest.kt`

May read root AGENTS, this spec/cited shared PROSE/OpenAPI, external library docs/APIs and OWN files only. NEVER production
source/declarations/diffs/bytecode, Worker work/transcripts, existing tests/fixtures/harness, broad git/LSP searches or
production hover/definitions/completions. mcpls restricted to own files. Missing facts => coordinator, not source reads.
JUnit4, coroutine runTest/runBlocking, AndroidJUnit4 are available. Local server tests use real bounded synchronization,
not sleeps; scripted races use gates/virtual time. Standard MockWebServer3/TLS library interfaces are test-owned. No
backend required; do not start it or use private config. Contributors do not amend docs or contact user/each other.

**Coordinator:** all build/manifest/docs integration, full source/test review, regression and Android execution, isolated
harness seam decisions. Later R03b can inject a TEST-owned Call.Factory which records validated HTTPS requests and bridges
to disposable loopback HTTP, or use a local trusted TLS bridge. Secure ServiceOrigin never accepts HTTP. Do not implement
that bridge/backend pickup in R03a; current scripted/local-TLS seam is sufficient and lives exclusively in test sources.

Fresh native /new contributor sessions, meta/muse-spark-1.3-contributor at high; corrections stay in same session.
Asynchronous READY/BLOCKER/DONE through `herdr agent prompt gym-coordinator '<report>'` without --wait/agent wait.
No Gradle before exclusive coordinator slot; shared outputs run serially. Worker production compile only, QA own focused
suites only/own XML; coordinator final gates. On failure stop/report, do not widen scope or modify global config.

## Independent acceptance and stop

QA covers table-driven origin/path/query/header/body/timeout validity, canonical identity, null configuration,
UTF-8 exact bytes and mutable-input snapshot/refusal; retry hint grammar/overflow/all-digit scanning; strict response
UTF-8/content-type/framing and >ordinary-buffer complete bodies. Scripted actual Call cancellation/late callback closure,
consumer throwing/suspending, response lifetime/mode guards and read cancellation. Local TLS server proves trusted CA
success with normal hostname validation, default untrusted rejection, wrong hostname rejection, no redirect to target,
no cookies/cache/replay on 408/503, concrete timeout and blocked-body cancellation. No server domain logic.
Android smoke proves minSdk-compatible construction path on available platform, normal no-config startup/transport
construction and cancellable local TLS I/O where feasible. No local test CA or helper in production APK.

Coordinator runs focused suites and reviews exact observations; then forced offline JVM, production/Android-test Kotlin,
assembly/lint and filtered Android smoke on available emulator. Inspect actual XML testcase/failure/error/skip counts,
merged production manifest/cleartext policy, dependency variants and helper exclusion. If API 24 device/image is absent,
record minSdk compilation/library compatibility separately; API 37 execution is NOT an API 24 pass.

```sh
./gradlew :app:compileDebugKotlin --console=plain
./gradlew :app:testDebugUnitTest --tests 'com.dragosfotache.gymtracka.data.sync.http.*' --rerun-tasks --no-configuration-cache --console=plain
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --rerun-tasks --no-configuration-cache --console=plain
./gradlew :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.http.ServiceHttpAndroidTest' --console=plain
git diff --check
git -C shared-docs diff --check
```

Update this status, [R03 plan](R03-task.md), architecture/testing/roadmap/handoff/README/implementation ledger and new
R03a-review.md with ACTUAL evidence. Stop after R03a coordinator review; no b/c/d, engine/vault/Google/UI/configuration,
service startup, backend source/Maven, device personal-account action, commit or push. Earlier backend/OAuth/device gates
remain earlier evidence, not proved by the HTTP primitive.
