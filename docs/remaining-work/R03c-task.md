# R03c — Exact-body service-auth HTTP adapter

Status: **owner-authorized and coordinator-accepted (2026-10-04)**.
See [review](R03c-review.md): client gates 553 offline JVM/six API37 smoke/build/lint passed; corrected backend rerun
passes unchanged auth10/10 and broad56/56 with all expiry-precision flags true. No skip, decoder relaxation or rounding.
Historical incompatible accepted replay remains an explicit backend legacy limitation, not counted repaired.
Owner instruction: “Go ahead and start work at task R03c”. This SAME normative specification is supplied to Worker
and QA, with disjoint access below. Pickup Android HEAD `6134a58bacaa20bcfc7ccb872a77ffb104867d21`, shared-docs HEAD
`61fe3f9e2e7d4b502abea7532cf566759fb6ada4`; both working trees are clean. Earlier handoff statements about uncommitted
R02d/R03a/b are historical: these are now included in the owner's `6134a58` baseline. Preserve it exactly outside this
slice. No commit/push, R03d/R02e/R04, app activation, private configuration, deployment or backend implementation.

## Goal and authority

Implement unused production ServiceAuthApi challenge/exchange/refresh/logout over accepted R03a secure I/O and R03b
strict service responses/errors. No bearer provider, Google SDK, vault, refresh orchestration/retry, Room writes,
account selection/UI, lifecycle proof endpoints, logout queue or simulator. A lost response can follow server acceptance;
the adapter neither invents a new attempt nor promises rollback.

Authority: [R03 split](R03-task.md), [R01 design](R01-design.md), [R02d models](R02d-task.md),
[R03a transport](R03a-task.md), [R03b service parsing](R03b-task.md), finalized shared
[authentication](../../shared-docs/docs/backend-contract/authentication.md),
[service protocol](../../shared-docs/docs/backend-contract/service-protocol.md),
[compatibility](../../shared-docs/docs/backend-contract/client-compatibility.md),
[account lifecycle](../../shared-docs/docs/backend-contract/account-lifecycle.md) and
[OpenAPI](../../shared-docs/docs/backend-contract/openapi.yaml). Shared contracts are unchanged.

Current production inventory: ServiceAuthApi has purpose/typed attempt methods and legacy delegating overloads;
GoogleExchangeAttempt/ServiceRefreshAttempt are immutable, private-constructor capture/fromJson factories retaining
requestJson and UUID key; ServiceAuthCodec supplies encoders and raw-string challenge/session decoders.
parseServiceResponseObject adds original scalar-kind fidelity/lexical/duplicate validation; decodeServiceError supplies
strict nonempty exact codes and discarded presentation messages. R03a owns normal HTTPS/TLS, exact UTF-8, no redirects/
replay/cookies/cache/logging, scoped cancellation/closure and bounded hints. No library/build/manifest changes needed.

## Required API and construction

All NEW declarations are internal in `com.dragosfotache.gymtracka.data.sync.http`:

```kotlin
class HttpServiceAuthApi(
    origin: ServiceOrigin,
    timeouts: ServiceHttpTimeouts = ServiceHttpTimeouts(),
) : ServiceAuthApi {
    internal constructor(origin: ServiceOrigin, transport: ServiceHttpTransport)
    // Implements challenge(purpose), exchange(attempt), refresh(attempt), logout(refreshToken).
    // Inherits defaults/convenience overloads unchanged.
}
enum class AuthHttpOperation { CHALLENGE, EXCHANGE, REFRESH, LOGOUT }
fun classifyAuthHttpFailure(
    operation: AuthHttpOperation,
    statusCode: Int,
    errorCode: String?,
    headers: Map<String, List<String>> = emptyMap(),
): SyncApiException
```

Primary constructor creates OkHttpServiceTransport from immutable origin; secondary is trusted I/O injection, NOT a
cleartext configuration or route argument. No token provider/current-account lookup. Adapter display is fixed redacted
text; no URL/body/key/token/account/provider logging. Constructor origin fixes service destination but refresh identity/
generation validation is R04/R09, not this stateless adapter. Missing configuration remains handled by ServiceOrigin's
existing nullable factory, with no app-shell wiring.

## Requests and success

| Operation | Request body and headers | Successful status/result |
|---|---|---|
| challenge | POST `/v1/auth/google/challenge`; encodeGoogleChallengeRequest(purpose), explicit purpose | 200 ServiceChallenge |
| exchange | POST `/v1/auth/google`; exact attempt.requestJson; Idempotency-Key=attempt.idempotencyKey | 200 ServiceSession |
| refresh | POST `/v1/auth/refresh`; exact attempt.requestJson; Idempotency-Key=attempt.idempotencyKey | 200 ServiceSession |
| logout | POST `/v1/auth/logout`; encodeLogoutRequest(refreshToken); no key | 204 verified empty body |

All four have no query, Authorization, account/session/owner fields or caller route. Transport supplies JSON media/
identity encoding headers. Challenge/logout have no Idempotency-Key. Typed exchange/refresh send the entire retained
string as exact UTF-8, including outer whitespace, order, escapes and newline. Key is header-only. Never encode from
attempt parsed fields, regenerate key, extend expiry, retry, refresh or recapture a restored attempt. Repeated EXPLICIT
calls may each send once with identical bytes/key; inherited legacy methods remain conveniences, not durable recovery.

Build immutable complete request before first suspension/I/O. Preflight exchange/refresh original text through the
existing parseServiceResponseObject for scalar-kind fidelity (a represented numeric-overflow token must not become a
string); attempts already enforce closed fields/key/body consistency. Do not change attempt factories/codecs or rewrite
validated bytes. R03a request construction checks literal unpaired surrogates before UTF-8 replacement. Invalid request
text/encoding fails fixed safe SyncModelException with null cause before send, not authentication/transport failure.
Do not impose bearer-header ASCII restrictions on body credentials: valid blank/Unicode/escaped control/opaque values
remain supported. No permanent size limit or token decoding/current-time policy.

Inside response scope, read complete strict UTF-8/media/framing body for successful challenge/grants, call
parseServiceResponseObject on the original string FIRST, then approved decodeServiceChallenge/decodeServiceSession on
that SAME string. Scalar/shape/key/type/UUID/time/codec failures become closed-cause MalformedResponse. Existing transport
Transient/MalformedResponse and original CancellationException propagate, no raw causes/suppressed errors. Additive
response/account fields remain tolerated but their raw grammar/types/duplicates validate. Use codec's lossless expiry,
opaque tokens and nullable display fidelity. Expired access/original expiries and unordered lifetimes are representable;
no scheduling/eligibility inference. Refresh returns authoritative account unchanged from service representation; checking
it against selected prior account is outside this adapter because no such account is an input.

Logout 204 must be VERIFIED bodyless, not just status checked: consume readBodyChunk(ByteArray(1)) and require clean -1;
any byte => MalformedResponse, framing/length/encoding must still validate. No Content-Type requirement for empty 204,
no JSON read. Unexpected 200/202/etc is malformed and never local sign-out success. A network failure remains visible to
the caller; future local sign-out orchestration can proceed offline independently. No persistent logout queue.

## Frozen operation/status failure table

Pure classifyAuthHttpFailure performs no I/O/body parsing/retry. Null/empty code is unavailable; nonempty code is exact,
including whitespace/Unicode/long/unknown values. No whitelist, trim or casing. It NEVER returns AuthenticationRequired
(service ACCESS/bearer recovery) or read-token expiry categories: these auth routes do not require access bearer.

| Status / operation / code | Failure |
|---|---|
| 413, any operation/body | RequestTooLarge |
| 429, 500, 502, 503, 504, any operation/body | Transient(retryAfterMillis(headers)) |
| REFRESH 401, any code/body | ReauthenticationRequired |
| EXCHANGE 401 + strict `reauthentication_required` | ReauthenticationRequired (original grant replay unavailable) |
| Other ordinary 400..499 + nonempty strict code | RequestRejected(code) |
| Other ordinary 400..499 + absent/invalid code | MalformedResponse |
| Everything else, including success status passed to failure-only classifier, other 2xx/3xx/1xx/5xx | MalformedResponse |

Thus EXCHANGE 401 invalid_google_credential is an explicit permanent proof rejection, NOT service bearer expiry or
transient sync recovery. CHALLENGE/LOGOUT reauthentication text is not promoted outside its defined context; wrong-status
codes (e.g. 422 reauthentication_required) stay RequestRejected. 409 idempotency_key_reused is retained permanent identity
conflict; never mint another key. Generic auth/404 failures never imply deletion or cache cleanup.

Actual non-success handling: authoritative 413/rate/listed5xx need no body read; REFRESH401 likewise immediately requires
foreground reauthentication even if media/body absent or broken. EXCHANGE401 performs best-effort strict error read: if
valid, route table; non-cancellation read/codec failure => MalformedResponse (not invented invalid_google_credential or
access recovery). All other ordinary4xx including CHALLENGE/LOGOUT401 strictly read error envelope: genuine generic body
I/O => Transient; invalid media/framing/UTF-8/grammar/shape => MalformedResponse. Unexpected statuses need no body read.
Cancellation always propagates unchanged, including while decoding an error. Error presentation message is validated and
discarded; never consulted for category/backoff. Safe transport categories propagate; unexpected seam exceptions become
closed-cause Transient outside decoding, MalformedResponse inside decoding. Preserve cancellation identity through the
actual accepted transport, do not mask it with generic wrappers. At the outer auth API boundary, recheck the current
caller context with public ensureActive() when catching CancellationException: a cancelled caller must observe its
original stored cancellation cause rather than a nested coroutine stack-recovery copy. Recheck caller activity before
propagating safe SyncApiException or mapping generic seam Exception too: cancellation-triggered source closure can race
IO-child cancellation and produce a safe I/O category first. An already-cancelled caller must still observe its original
cancellation, not that racing category. If the caller is active, preserve the original safe/injected exception/category.
No internal coroutine API or cause-unwrapping heuristic.

## Exact disjoint contributor allowlists

**Reviewed bounded transport amendment:** OkHttp rejects forbidden nonempty204 framing with ProtocolException before
its response callback, which the accepted awaitHeaders boundary currently categorizes as generic Transient. For R03c's
strict logout requirement, Worker may additionally edit ONLY the imports and onFailure category mapping in existing
`http/OkHttpServiceTransport.kt`: java.net.ProtocolException => closed-cause MalformedResponse, other IOException =>
Transient, with original cancellation precedence unchanged. No raw diagnostics/status inference, EOF/TLS broad
reclassification, retry/cancellation/security/parser change or extra transport files. This corrects protocol framing
classification at header arrival; final regression must cover accepted R03a/b too.

**Worker writes NEW production files (plus the exact amendment above):**
- `app/src/main/java/com/dragosfotache/gymtracka/data/sync/http/HttpServiceAuthApi.kt`
- `app/src/main/java/com/dragosfotache/gymtracka/data/sync/http/AuthHttpFailures.kt`

May read existing production api/model/codec/http, root AGENTS, THIS specification, cited shared prose/OpenAPI and external
library APIs. NEVER tests/fixtures/reports/harness/QA work, broad git status/diffs or LSP test references. No other existing
production/build/manifest/docs/script/backend edits. Restricted mcpls within production only; bounded unavailability
fallback exact reads/compiler, never global configuration. No Gradle until explicit exclusive slot; production compile only.

**QA writes NEW files only:**
- `app/src/test/java/com/dragosfotache/gymtracka/data/sync/http/ServiceAuthHttpApiTest.kt`
- `app/src/test/java/com/dragosfotache/gymtracka/data/sync/http/ServiceAuthHttpNetworkTest.kt`
- `app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/http/ServiceAuthHttpAndroidTest.kt`

May read root AGENTS, THIS spec, cited shared prose/OpenAPI and external library APIs. NEVER production source/declarations/
diffs/bytecode/Worker work, existing tests/fixtures/harness/reports or broad git/LSP searches. mcpls on OWN files only, no
production hover/definitions/completions. Missing API/harness facts => coordinator. No live-backend tests/config/service
access. JUnit4/runTest/runBlocking/AndroidJUnit4/MockWebServer3/okhttp-tls5.5.0 already available. Use gates/virtual time,
local network bounded synchronization, not sleeps/server domain logic. Do not run Gradle until slot; own focused tests
and own XML only. Compiler corrections to own files allowed; behavioral failures stop/report, never weaken assertions.

**Coordinator owns:** specification/docs/status/review, complete independent diff review, serialized gates, optional
API KDoc-only correction, test-only real-backend integration. Permitted harness extensions: RealBackendFixture new isolated
refresh-credential setup/authApi construction (existing guards and domain setup preserved), RealBackendHttpBridge fixed
four auth POST route/header forwarding alongside sync, and NEW RealBackendServiceAuthTest.kt. No backend source review,
Maven suite, private config, production auth bypass or domain SQL writes. Additional files require a recorded amendment.

Fresh Pi sessions, meta/muse-spark-1.3-contributor/high, same session for corrections. Reports fire-and-forget
`herdr agent prompt gym-coordinator '<READY/BLOCKER/DONE>'` WITHOUT --wait/agent wait. Neither contributor contacts user/
other role. Shared Gradle outputs require exclusive serial slots; coordinator final regression. No global config changes.

## Supplied API facts for QA (not source access)

Transport signatures/seams and strict ownership are fully in [R03a spec](R03a-task.md); R03b supplies parser/error facts.
All following types live in `data.sync.api`:
- ServiceAuthApi.challenge(purpose:GoogleChallengePurpose=SIGN_IN), exchange(GoogleExchangeAttempt),
  refresh(ServiceRefreshAttempt), logout(OpaqueToken); suspend methods. Legacy exchange(key:String,challengeId:String,
  idToken:OpaqueToken)/refresh(key:String,refreshToken:OpaqueToken) delegate.
- GoogleChallengePurpose SIGN_IN/EXPORT/DELETE_ACCOUNT/ACCOUNT_STATUS, wireValue.
- OpaqueToken(value:String), exact .value, nonempty (blank is legal), redacted display.
- GoogleExchangeAttempt.capture(key,challengeId,OpaqueToken), .fromJson(key,requestJson); getters
  idempotencyKey/requestJson/challengeId/idToken; no copy/public constructor.
- ServiceRefreshAttempt.capture(key,OpaqueToken), .fromJson(key,requestJson); getters
  idempotencyKey/requestJson/refreshToken; no copy/public constructor.
- ServiceChallenge(challengeId:String,nonce:String,expiresAtMillis:Long).
- ServiceAccountRef(accountId:String,displayName:String?).
- ServiceSession(account:ServiceAccountRef,accessToken:OpaqueToken,accessExpiresAtMillis:Long,
  refreshToken:OpaqueToken,refreshExpiresAtMillis:Long).
- SyncApiException: AuthenticationRequired/ReauthenticationRequired/RequestTooLarge/MalformedResponse zero args;
  RequestRejected(errorCode:String); Transient(retryAfterMillis:Long?=null), .retryAfterMillis. Fixed messages/null cause.
  SyncModelException is in data.sync.model. Auth classifier above supplies the expected category table.

Scripted ServiceHttpTransport may record immutable requests but responses require the standard external Call.Factory
seam: OkHttpServiceTransport(origin,factory) captures okhttp3.Request and asynchronous callbacks. Construct Response with
Protocol.HTTP_1_1/status/headers/body. OkHttp5 Call doubles implement request/execute/enqueue/cancel/isExecuted/isCanceled/
timeout/clone/addEventListener plus Class/KClass tag/get+computeIfAbsent overloads. ResponseBody.close final: observe a
cached wrapping source close rather than overriding/fresh source. For local TLS use newServiceHttpClient(builder=...)
with a test trusted CA and normal hostname validation, never permissive verifier. HandshakeCertificates.trustManager is
a PROPERTY, not trustManager(). Android method names must be plain Kotlin identifiers, no spaced backtick names for DEX.

Expected requests/results authored as independent raw strings, not production expected-value encoders. Valid challenge:
`{"challengeId":"11111111-1111-1111-1111-111111111111","nonce":"opaque nonce","expiresAt":"2026-10-03T12:05:00Z"}`.
Valid grant: `{"account":{"id":"22222222-2222-2222-2222-222222222222","displayName":null},"accessToken":"opaque access","accessExpiresAt":"2026-10-03T12:15:00Z","refreshToken":"opaque refresh","refreshExpiresAt":"2026-11-02T12:00:00Z"}`.
No clock/current-time validation. Inspect fields individually; ServiceSession data-class equality includes OpaqueToken
value equality. Preserve raw whitespace/Unicode/escapes for exact-body expectations. Source access is not required.

## Initial interoperability blocker and subsequent resolution

Initial coordinator production-auth black-box run executed 9 new real-backend tests: 2 passed, 7 failed with
MalformedResponse. A separate sanitized HTTP challenge probe confirmed status200/application-json and correct field
kinds, but expiresAt has nine fractional digits with NONZERO precision beyond milliseconds. This violates the finalized
lossless Android timestamp contract; accepted R02d readers correctly reject it. No nonce/token/raw body was printed or
retained. Challenge success and successful refresh/grant-dependent cases remain blocked pending separately owned service
correction. Do not round/truncate client timestamps, alter shared contract, weaken tests, or change/review backend source.
The initial broad run executed56 tests, 48 passed/eight failed (all46 prior sync tests passed); the diagnostic confirmed
all three expiry fields incompatible. The separately owned correction subsequently passed the unchanged auth10/10 and
broad56/56 rerun, with all compatibility flags true and 201/201 client inputs unchanged. See [review](R03c-review.md)
for frozen artifact/evidence/cleanup and the explicitly retained legacy replay incompatibility. R03c is now accepted
within its unused transport scope; no R04, activation, commit/push or full legacy/v1 release compliance is implied.

## Independent acceptance and stop

Independent tests: four purposes/default/legacy delegation; exact restored exchange/refresh bodies+keys on explicit repeats
and loss; no bearer/query/key on inappropriate routes; Unicode/blank/escaped credential fidelity, preflight no-send;
complete challenge/grant fields/types/time/additive/duplicate/scalar-overflow tables, expired original grant fidelity;
204 bodylessness/framing/encoding/no media requirement; full operation/status/code precedence table and unknown code
retention; capped/malformed hints; no leaks/raw causes; one-send/closure/original cancellation before headers and during
success/error/body reads. Concrete trusted local TLS verifies routes/headers/bytes, no redirects or retry on failures,
strict media/UTF-8/truncation/logout. Filtered Android smoke verifies platform scalar overflow/duplicates and auth TLS.

Coordinator may extend guarded isolated fixture to mint ONLY refresh/session credentials using existing ephemeral HMAC
setup, not Google proofs. Real challenge, refresh original replay/key conflict/invalid credential/logout/revocation can be
verified through production adapter + trusted TLS relay. Existing 46 sync tests remain regression. Real Google exchange
success is NOT feasible without provider inputs; explicitly record unverified, do not forge verification/create bypass/
claim skips as success. Real invalid-proof rejection can be exercised black-box. Service compatibility gaps are blockers
for separate backend ownership, not permission to edit it or weaken expected client behavior.

Gates from repository root (serial): focused new JVM; forced all offline JVM; Kotlin/Android-test compilation;
assembly/lint; isolated testRealBackend when ready; filtered ServiceAuthHttpAndroidTest on available emulator; actual XML
counts/failures/errors/skips, APK helper exclusion, baseline preservation and both git diff checks. No API24 runtime/full
instrumentation/Room/migration/Google/hosted-CI/personal-phone gate implied. Update Android architecture/testing/roadmap/
handoff/README/implementation ledger, this status and R03c-review.md with actual evidence. STOP after R03c review;
R04/R03d/R02e, activation/configuration/deployment, commit and push remain separately authorized.
