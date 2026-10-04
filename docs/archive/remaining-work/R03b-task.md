> **Historical record, not current instructions.** See the [archive boundary](../README.md) and [current pickup](../../coordinator-handoff.md).

# R03b — Bound sync HTTP endpoints and shared service errors

Status: **implemented and coordinator-accepted** (2026-10-03); see [review](R03b-review.md).
Owner instruction: “go ahead and start work at r03b”. Final evidence: 477 offline JVM tests/38 suites,
46 real-backend tests/9 suites through production adapter + test TLS relay, three filtered API37 Android tests,
compilation/assembly/lint/APK/preservation checks passed; zero failures/errors/skips. No activation/commit/push.
This is the SAME normative specification/API contract for Worker and QA. Accepted R02a–d/R03a are prerequisites, not
work to redo. Pickup Android HEAD `623207c2044f9503410bca4426d9226787a2bda9`, shared HEAD
`f65abb82512c373ade9ab08699ef68971b2e8ceb`; both working trees are dirty. Preserve every existing tracked/untracked file.
No R03c/d, R02e, engine/vault/Google/UI, activation/configuration/deployment, commit or push.

## Goal, authority and inventory

Implement production `GymSyncApi` push/pull/snapshot over the accepted R03a transport, with immutable service/account/
session credential selection and shared strict service-error decoding. Exercise actual production code against the
isolated black-box backend without weakening HTTPS. No automatic resend/refresh, outbox claims/acknowledgments, Room
writes/migrations, cursor publication, snapshot staging or simulator. HTTP failure can follow committed writes.

Authority: [R03 split](R03-task.md), [R01 design](R01-design.md), accepted [R02c failure contract](R02c-task.md),
[R03a APIs/security/ownership](R03a-task.md), finalized shared
[service protocol](../../../shared-docs/docs/backend-contract/service-protocol.md),
[authentication](../../../shared-docs/docs/backend-contract/authentication.md),
[client compatibility](../../../shared-docs/docs/backend-contract/client-compatibility.md),
[client protocol](../../../shared-docs/docs/backend-contract/android-client-protocol.md) and
[OpenAPI](../../../shared-docs/docs/backend-contract/openapi.yaml). Do not change shared contracts.

`api/SyncApi.kt` has GymSyncApi, MAX_PUSH_REQUESTS=100, DEFAULT_PULL_LIMIT=100/MAX=200,
DEFAULT_SNAPSHOT_LIMIT=200/MAX=500 and bounded limit helpers (clamp to 1..max). DurableCodec validates whole batches and
embeds frozen request text. ProtocolCodec/ProtocolValidation provide complete canonical result decoders. R03a provides
ServiceOrigin, immutable request, scoped response, normal TLS, exact UTF-8, one-send/cancellation/closure and bounded hints.
JVM-only `backend/HttpGymSyncApi` remains a LEGACY test adapter, not production. R02d's field-agnostic
`requireAuthJsonScalars` helper is internal despite its historical auth name; reuse it for new service response grammar,
then `parseStrictJsonObject` for original-text duplicate checking. No existing parser/auth/durable behavior changes.

Backend access is operational build/start and black-box HTTP only. Coordinator has started the checked-in isolated
launcher with disposable PostgreSQL 16/generated test keys, new mode-600 config `/tmp/gymtracka-r03b-backend/config.json`
and port 18080. No private config, personal database, backend source review/change, Maven suite or Google verification.
Startup is not a test pass. Neither contributor touches that service/configuration/harness.

## Required production API

All NEW declarations below are `internal`; existing SDK interfaces remain compatible. Package
`com.dragosfotache.gymtracka.data.sync.http`:

```kotlin
data class ServiceSessionBinding(
    val origin: ServiceOrigin,
    val accountId: String,
    val sessionGeneration: Long,
)
class BoundServiceAccessToken(val binding: ServiceSessionBinding, val token: OpaqueToken)
fun interface BoundServiceAccessTokenProvider {
    suspend fun accessToken(binding: ServiceSessionBinding): BoundServiceAccessToken
}
class HttpGymSyncApi(
    binding: ServiceSessionBinding,
    tokenProvider: BoundServiceAccessTokenProvider,
    timeouts: ServiceHttpTimeouts = ServiceHttpTimeouts(),
) : GymSyncApi {
    internal constructor(
        binding: ServiceSessionBinding,
        tokenProvider: BoundServiceAccessTokenProvider,
        transport: ServiceHttpTransport,
    )
    // Implements push/pull/snapshot, inheriting GymSyncApi default limits.
}
enum class SyncHttpOperation { PUSH, PULL, SNAPSHOT }
fun classifySyncHttpFailure(
    operation: SyncHttpOperation,
    statusCode: Int,
    errorCode: String?,
    headers: Map<String, List<String>> = emptyMap(),
): SyncApiException
```

The primary adapter constructor creates OkHttpServiceTransport FROM binding.origin; no caller route/base URL argument.
The secondary constructor is a trusted test I/O seam, not endpoint configuration. Do not change R03a or add a cleartext
origin. Existing generic AccessTokenProvider remains unchanged; the new bound provider requires explicit expected identity
and tags its returned token so accidental global/current-account selection is detected before send. Future R04 supplies
eligible vault access; this slice stores no credentials and performs no expiry/rotation policy.

Binding validates accountId as exact canonical lowercase UUID and sessionGeneration in 1..Long.MAX_VALUE on constructor
AND copy. No zero/signed-out binding or overflow normalization. Origin already has immutable canonical identity. Equality
includes origin/account/generation. Bindings, tagged credentials and adapter toString are fixed/redacted: no URL/account,
generation, token/body/query or provider values. Explicit getters are sensitive inputs, not display/log APIs.

Package `com.dragosfotache.gymtracka.data.sync.codec`:

```kotlin
class ServiceError(val errorCode: String)
fun decodeServiceError(jsonText: String): ServiceError
fun parseServiceResponseObject(jsonText: String): org.json.JSONObject
```

ServiceError validates nonempty code (NOT nonblank), preserves its exact string without whitelist/length/Unicode/case/
trim normalization, and has fixed redacted display. It retains no message/body/headers/cause. New reader failures are
fixed-text SyncModelException with null cause/no raw suppressed exceptions. Never echo unknown fields/code/input.

`parseServiceResponseObject` reuses requireAuthJsonScalars + parseStrictJsonObject. Also verify ORIGINAL scalar kinds
against the resulting org.json tree using an iterative work stack and the strict kotlinx JsonElement tree: string stays
String, boolean Boolean, null null/JSONObject.NULL, number Number (never normalized into String or nonfinite Double/Float).
Check actual Double/Float finiteness, not a lossy toDouble conversion of finite BigDecimal/BigInteger; no wide-number cap.
Objects/arrays retain their kinds too. This is a type-fidelity check, not domain validation or another custom parser;
using the existing JSON library for the original typed tree is allowed. It prevents Android JSONObject's numeric overflow
fallback (for example unquoted 1e400 becoming String) from satisfying a string field. No field type coercion or repair.
Reject non-JSON/unquoted arbitrary
scalars, literal unescaped string controls, duplicate decoded keys at every nesting level (including ignored extensions),
wrong top-level type and trailing/syntax errors. Valid escaped opaque data stays unchanged. This stricter HTTP-only entry
point does not modify shared parsing, direct old codec behavior or durable v1 bytes/versions. No recursive second parser,
new depth/length cap, JSON normalization for transmission or new dependency.

Service error shape: required `errorCode` JSON STRING, nonempty; optional `message` absent/null/string, validated but
DISCARDED. Other response fields are additive/ignored, including `fullResyncRequired`; their raw grammar/duplicates still
validate. This common reader does not infer recovery from flags: routing uses the explicit operation/status/code table.
Number/boolean/object/array/null/empty code or wrong-type message fails; whitespace-only/Unicode/long unknown codes survive.
Use strict typed reads, never optString/toString coercion. HTTP successful bodies also go through the new raw service
response entry point BEFORE the approved JSONObject result decoders; malformed HTTP JSON cannot exploit legacy parser
scalar normalization. Do not broaden this into domain/model changes.

## Endpoint, capture and credential contract

| Operation | Request | Successful status/result |
|---|---|---|
| push | POST `/v1/sync/push`; no query; body `encodePushMutationsBody` exactly once | 200 complete PushResultModel |
| pull | GET `/v1/sync/pull`; optional cursor (only omit null), then bounded limit | 200 complete PullResultModel |
| snapshot | GET `/v1/sync/snapshot`; optional pageToken (only omit null), then bounded limit | 200 complete SnapshotResultModel |

- BEFORE the first suspension/token lookup, snapshot caller request list, freeze the validated full push body and expected
  ordered mutation IDs. Call approved encoder once, no inner decode/re-encode or second post-suspension validation against
  caller-mutated collections. Whole envelope/version/identity validation happens before credentials/I/O. Empty push is
  valid. Duplicate IDs/over-100/corruption fail safe before lookup/send. UnsupportedDurableVersionException preserves its
  version/type; other invalid request/preflight exceptions become fixed safe SyncModelException (not transport failure).
- For GET, build immutable path/query draft before token lookup; limit helpers retain existing clamping/defaults. Cursor/
  pageToken may be empty/whitespace/Unicode/percent/plus/controls and remain opaque with safe encoding. Reject unpaired
  surrogates before lookup/I/O through the R03a boundary. Never trim/decode/order a token or retry without it.
- Select provider EXACTLY ONCE per explicit invocation, passing immutable expected binding. Check returned binding equals
  expected across canonical service origin, account UUID AND generation; mismatch => AuthenticationRequired BEFORE I/O.
  A suspended lookup followed by another account/generation selection cannot use the new binding's token. Same-binding
  token rotation may be observed on a later explicit call, never refetched midway through one call.
- Provider AuthenticationRequired/ReauthenticationRequired/Transient failures propagate as the same safe category/object;
  unexpected provider exceptions become closed-cause Transient. Original CancellationException always propagates. No
  implicit refresh, Google UI, retry, second provider call or session/account inference.
- Construct one final immutable request with only `Authorization: Bearer <selected exact token.value>` added. Other
  headers/body ownership stays with R03a. Do not add sync Idempotency-Key, owner/account/session fields, credentials to body,
  wrapper/affectedRoots/version or user-controlled route. Unrepresentable bearer header (controls/non-ASCII/edge whitespace,
  including leading/trailing SPACE in the selected token itself) => AuthenticationRequired without I/O, not trimming/
  escaping or retrying a different credential. Body/query text input
  errors remain preflight SyncModelException. The token's constructor/representation remains unchanged.
- Delegate one withResponse exchange; do not hold domain/Room locks, persist anything, acknowledge intent or claim zero
  effects on failure. Repeated EXPLICIT calls may each send once and must embed exactly the same frozen mutation bytes.

## Success, complete pages and failures

Consume R03a readJsonBody inside response scope; no raw response escapes. Preserve its Transient/MalformedResponse and
original cancellation. New grammar/result decoder exceptions map to closed-cause MalformedResponse, never raw input/cause.
Use existing JSONObject decoders; they validate complete aggregate/ID/revision/position/outcome/batch consistency and
preserve approved sorting/null/time/name behavior. Do not derive volume, write names into catalogue or compare revisions.

Push result MUST have exactly frozen request count, order and IDs. Duplicate accepted/rejected original outcomes and
normal rejected HTTP200 are returned normally; no top-level exception for per-mutation rejection. Empty push needs empty
results. Never match by set/reorder results or accept a committed prefix as a complete acknowledgment.

Per-call page consistency (no pagination loop/checkpoint or staged state here):
- Pull count <= bounded requested limit; nextCursor equals last batch cursor (existing codec). Nonempty page must advance
  from nonnull supplied cursor; empty tail preserves supplied nonnull cursor exactly. Null-input empty tail has a nonempty
  service boundary. No token decoding/lexical comparison; opaque equality is permitted for consistency/progress checks.
- Snapshot count <= bounded requested limit. Empty terminal account is valid. Nonterminal page needs records and a nonempty
  continuation; continuation cannot equal the supplied nonnull token. Existing codec enforces baseline/terminal-token and
  duplicate-root/upsert-only rules. Cross-page cycles/baseline/account publication are R08, not adapter state.
- Never truncate records/batches to meet count/byte constraints, synthesize IDs/cursors or silently return a partial page.

`classifySyncHttpFailure` is a PURE category function; no JSON/body/I/O, retries or auth endpoints. errorCode=null means
invalid/unavailable strict error envelope. Empty supplied code is treated as absent; nonempty strings are retained EXACTLY.
This freezes R02c status precedence without the legacy JDK adapter's code regex/length whitelist:

| HTTP/operation/code | Failure |
|---|---|
| 413, any code/body | RequestTooLarge |
| 429, 500, 502, 503, 504, any code/body | Transient(retryAfterMillis(headers)) |
| 401 + strict `reauthentication_required` | ReauthenticationRequired |
| Other 401, including absent/malformed body | AuthenticationRequired |
| PUSH/PULL/SNAPSHOT: other 400..499 with nonempty code | RequestRejected(code), except the four exact context rows below |
| PULL, 400 + invalid_cursor | InvalidCursor |
| PULL, 410 + cursor_expired | CursorExpired |
| SNAPSHOT, 400 + invalid_snapshot_token | InvalidSnapshotToken |
| SNAPSHOT, 410 + snapshot_expired | SnapshotExpired |
| Other ordinary 4xx with absent/invalid code | MalformedResponse |
| Anything else (including 200 passed to this failure-only function, other 2xx/3xx/1xx/5xx) | MalformedResponse |

Wrong operation OR wrong ordinary 4xx status never promotes read-token text to expiry. Never interpret generic 401/404/
network fault as deletion, drop cache or silently bootstrap. Unknown permanent code is not transient and is not echoed.

Actual non-200 response handling: 413/429/listed 5xx need no body read; status/hint is authoritative. For 401, best-effort
strict JSON error decode distinguishes explicit reauth; any non-cancellation body/codec failure falls back to conservative
AuthenticationRequired (no raw diagnostic retained). Other ordinary 4xx read strict JSON: media/grammar/envelope/framing
invalid => MalformedResponse; a genuine generic I/O timeout/offline failure while reading remains Transient. Cancellation
always wins. Unexpected statuses need no body and become MalformedResponse. No response body is consulted for scheduling.

## Exact allowlists and independent authoring

**Worker NEW production files only:**
- `app/src/main/java/com/dragosfotache/gymtracka/data/sync/http/ServiceSessionBinding.kt`
- `app/src/main/java/com/dragosfotache/gymtracka/data/sync/http/HttpGymSyncApi.kt`
- `app/src/main/java/com/dragosfotache/gymtracka/data/sync/http/SyncHttpFailures.kt`
- `app/src/main/java/com/dragosfotache/gymtracka/data/sync/codec/ServiceErrorCodec.kt`

May read existing production api/model/codec/http, root AGENTS, THIS spec and cited shared PROSE/OpenAPI. NEVER tests/
fixtures/reports/harness/QA transcripts, broad git status/diffs or LSP test references. No existing production/build/
manifest/docs/backend/script edits. Prefer restricted Kotlin mcpls; bounded unavailable fallback exact reads/compiler,
not configuration changes. No Gradle until slot; production compile only, never tests.

**QA NEW files only:**
- `app/src/test/java/com/dragosfotache/gymtracka/data/sync/http/ServiceErrorResponseTest.kt`
- `app/src/test/java/com/dragosfotache/gymtracka/data/sync/http/BoundSyncHttpApiTest.kt`
- `app/src/test/java/com/dragosfotache/gymtracka/data/sync/http/SyncHttpEndpointNetworkTest.kt`
- `app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/http/SyncHttpEndpointAndroidTest.kt`

May read root AGENTS, THIS spec/cited shared PROSE/OpenAPI and external library APIs only. NEVER production source/
declarations/diffs/bytecode, Worker work/transcripts, old tests/fixtures/harness/reports, broad git/LSP search or production
hover/definitions/completions. Restricted mcpls on OWN files only; missing facts => coordinator. No build/manifest/docs/
backend/script edits or service/config access. No live backend test authored/run by QA: coordinator supplies that seam.
JUnit4/runTest/runBlocking/AndroidJUnit4 and already-wired MockWebServer3/okhttp-tls 5.5.0 are available. Use gates/virtual
time/scripted responses for races; local network uses bounded synchronization/server waits, not sleeps or server logic.
No Gradle until slot; own focused JVM only and own XML. Compiler fixes to own files allowed within slot; behavior failures
STOP/report, do not weaken assertions or widen source access. Same session through corrections.

**Coordinator ONLY:** new test-only `backend/RealBackendHttpBridge.kt`; existing `backend/RealBackendFixture.kt` apiFor/
owned bridge cleanup; `backend/RealBackendAsserts.kt` ONLY generalizes pushOne/pullAll/snapshotAll parameters from legacy
HttpGymSyncApi to GymSyncApi (no assertion/fixture/scenario changes). Existing backend
scenarios/fixtures/assertions/guard setup remain; old JDK adapter/tests stay as legacy comparison. No backend source edit.
Coordinator may update GymSyncApi KDoc (future -> delivered) without signatures/constants and Android docs/review.
No production/library/manifest/source-set changes otherwise expected. Need more files => reviewed amendment first.

Fresh Pi `/new` sessions for R03b, meta/muse-spark-1.3-contributor/high. Reports fire-and-forget
`herdr agent prompt gym-coordinator '<READY/BLOCKER/DONE>'` WITHOUT --wait/agent wait. No cross-role/user coordination.
Gradle outputs are shared: exclusive serial slots, never overlapping Worker/QA/coordinator runs. No global config changes.

## Supplied API/fixture facts for QA (not source access)

R03a signatures are in [its spec](R03a-task.md). A scripted Call.Factory is the standard external-library seam: request
capture in newCall, response callback ONLY AFTER enqueue captures it. Build normal okhttp3.Response/ResponseBody with
Protocol.HTTP_1_1, status, explicit Content-Type application/json; inspect captured Request bytes/URL/header values.
Pass `OkHttpServiceTransport(binding.origin, factory)` through the adapter's trusted transport constructor. External
OkHttp5 Call test doubles implement execute/enqueue/cancel/request/isExecuted/isCanceled/timeout/clone, addEventListener,
and BOTH Class/KClass tag getter + computeIfAbsent overloads. ResponseBody.close is final: observe one cached wrapping
source's close, not an override/fresh source. No production constructor inspection necessary.

Model facts: api.OpaqueToken(value:String); all ten SyncApiException types/fields are in [R02c table](R02c-task.md).
model.UnsupportedDurableVersionException(payloadVersion:Int), SyncModelException; codec.decodeDurableRequest(String).
DurableRequestModel has payloadVersion, requestJson, affectedRoots, envelope and data-class copy; envelope.mutationId.
PushResultModel.results; MutationOutcomeModel mutationId/status/errorCode/batch; MutationStatus ACCEPTED/DUPLICATE/REJECTED.
PullResultModel batches/nextCursor/hasMore; SnapshotResultModel records/baselineCursor/nextPageToken/hasMore. Batch has
batchId/cursor/changes; change has aggregateType/aggregateId/operation/revision/record. CanonicalRecord.Workout/Routine/
CustomExercise have .record (typed model), .aggregateId/.revision/.aggregateType; nested typed records have id/name and
wire-corresponding Kotlin millis, exercise/set/relationship fields. Nested assertion signatures supplied equally:

```kotlin
// All below are in data.sync.model; .record has these types, no UI/Room classes.
WorkoutRecordModel: id: String, name: String, status: String, startedAtMillis: Long,
    finishedAtMillis: Long?, revision: Long, exercises: List<WorkoutExerciseModel>
WorkoutExerciseModel: id: String, exerciseId: String, position: Int, notes: String,
    restDurationSeconds: Int, sets: List<WorkoutSetModel>, exerciseName: String
WorkoutSetModel: id: String, position: Int, repetitions: Int?, weightKg: Double?,
    isCompleted: Boolean, restSeconds: Int?
RoutineRecordModel: id: String, name: String, isDraft: Boolean, revision: Long,
    exercises: List<RoutineExerciseModel>
RoutineExerciseModel: id: String, exerciseId: String, position: Int, restDurationSeconds: Int,
    sets: List<RoutineSetModel>, exerciseName: String
RoutineSetModel: id: String, position: Int, repetitions: Int?, weightKg: Double?
CustomExerciseRecordModel: id: String, name: String, type: String, category: String?,
    difficultyLevel: String?, forceType: String?, mechanics: String?, instructions: List<String>?,
    isUserCreated: Boolean, revision: Long, muscleGroups: List<MuscleGroupRefModel>,
    equipment: List<EquipmentRefModel>
MuscleGroupRefModel: id: String, name: String, type: String
EquipmentRefModel: id: String, name: String, numItems: Int?
```

Use independent raw JSON bodies, never production encoders for expected values. Example frozen mutation:

```json
{"mutationId":"11111111-1111-1111-1111-111111111111","type":"workout.deleteSet","aggregateType":"workout","aggregateId":"22222222-2222-2222-2222-222222222222","localGeneration":1,"payload":{"workoutSetId":"33333333-3333-3333-3333-333333333333"}}
```

Durable wrapper is `{payloadVersion:1,requestJson:<JSON STRING of exact mutation>,affectedRoots:[{aggregateType:"workout",
aggregateId:<same root>,localGeneration:1}]}`. JSONObject.quote(innerExact) is permitted for string quoting, not expected
serialization. Expected body is `{"mutations":[<EXACT inner1>,<EXACT inner2>]}`; preserve whitespace/order/number spelling.
Outcome rejection is `{results:[{mutationId:<same>,status:"rejected",errorCode:"future_code",batch:null}]}`; accepted or
duplicate-accepted instead has errorCode:null and a complete batch. A valid deletion change needs aggregateType/root UUID,
operation:"delete", positive revision, record:null. A batch needs fresh canonical UUID batchId, nonempty opaque cursor,
nonempty changes; a canonical upsert record's ID/revision/type must agree with its change.

Valid minimal canonical workout record: id/name/status:"active"/startedAt:"2026-10-03T12:00:00Z"/finishedAt:null/
revision:1/exercises:[]. Routine: id/name/isDraft:true/revision:1/exercises:[]. Custom: id/name/type:"reps"/
isUserCreated:true/revision:1/muscleGroups:[]/equipment:[]; optional metadata/null/instructions follow OpenAPI. Positive
nested multi-root/child fixtures can be authored directly from OpenAPI and supplied model signatures. IDs are distinct
within a workout/routine family; exerciseId reference is an independent namespace. Empty pull:
`{"batches":[],"nextCursor":"<same supplied boundary>","hasMore":false}`. Empty terminal snapshot:
`{"records":[],"baselineCursor":"opaque baseline","hasMore":false}`. No production fixture/encoder access needed.

## Coordinator-owned isolated interoperability seam and gates

Use a TEST-owned local trusted TLS proxy: production adapter and actual OkHttpServiceTransport send HTTPS to one locally
trusted origin; MockWebServer dispatcher forwards unchanged request bytes/encoded query/allowed headers to the ALREADY
GUARDED disposable loopback HTTP service and returns actual status/headers/body. Outbound forwarding also has explicit
no-retry/no-redirect policy. No fake domain logic, auth bypass, permissive trust, insecure production flag or new service
configuration. Test CA/helper never enters APK. This proves the Android-compatible adapter's I/O/codecs against the real
service via isolated TLS, not deployed backend TLS or Google verification.

RealBackendFixture.apiFor returns GymSyncApi backed by production adapter, bound to bridge origin/account/generation=1
and that fixture's immutable token. All existing 46 real-backend methods must execute through it. Coordinator preserves
mode-600/non-symlink config, disposable DB/run guard, account/session setup and read-only catalogue queries; domain calls
remain HTTP. Bridge/clients close with fixture, and launcher cleanup stops only its own service/container/config.
Do not print/copy ephemeral secrets, read private config, run backend source/Maven suites or touch personal databases.

Independent QA gate: strict error scalar/key/type/extension/message tables, unknown long/Unicode/whitespace/control codes,
fixed safe failures/redaction, all status/context rows and cap hints; exact push bytes/order/count/IDs/duplicate original
outcomes, all root families/full child values, result inconsistencies; GET query/default/limit/page-progression tables;
credential selection exactly once, all three binding mismatch axes, suspended A/B changes and caller-list mutation,
preflight no-lookup/no-send, no retry/refresh on 401/413/transient, original cancellation/provider/I/O errors. Real local
TLS route/bytes/query/error/media/truncation/cancellation/single-send tests plus focused Android endpoint/error parser
smoke where available. No server persistence simulator. Coordinator independently reviews all source/new tests and runs:

```sh
./gradlew :app:compileDebugKotlin --console=plain
./gradlew :app:testDebugUnitTest --tests 'com.dragosfotache.gymtracka.data.sync.http.ServiceErrorResponseTest' --tests 'com.dragosfotache.gymtracka.data.sync.http.BoundSyncHttpApiTest' --tests 'com.dragosfotache.gymtracka.data.sync.http.SyncHttpEndpointNetworkTest' --rerun-tasks --no-configuration-cache --console=plain
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --rerun-tasks --no-configuration-cache --console=plain
./gradlew :app:testRealBackend -Pgymtracka.realBackendConfig=/tmp/gymtracka-r03b-backend/config.json --console=plain
./gradlew :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.http.SyncHttpEndpointAndroidTest' --console=plain
git diff --check
git -C shared-docs diff --check
```

Inspect executed XMLs (not cached task counts), no failures/errors/skips, APK helper exclusion and preserved baseline
including R02d/R03a dirty files. API24 runtime/full instrumentation/real OAuth/phone-laptop deployment remain separately
unverified. Update Android architecture/testing/roadmap/handoff/README/implementation ledger, this status and new
R03b-review.md with actual evidence. Stop after coordinator R03b review: no R03c/d, R02e, engine/vault/account activation,
configuration/deployment, commit or push. Backend compatibility gaps are blockers for separate ownership, not permission
to edit backend or weaken tests.
