> **Historical record, not current instructions.** See the [archive boundary](../README.md) and [current pickup](../../coordinator-handoff.md).

# R02c — Distinguish typed read/authentication/request-size failures

Status: **implemented and coordinator-accepted** (2026-10-03); see [review](R02c-review.md).

Delivery update: the owner subsequently authorized committing the full Android working tree, including accepted
R02b/R02c and the existing retirement/HTTP/build/local-doc baseline. See [delivery record](R02bc-commit-review.md).
That authorization supersedes this specification's earlier coordinator no-commit boundary only; no push or further
implementation is authorized.
The owner authorized R02c after finishing R02b. R02b's coordinator review is complete; its code/tests/docs remain
uncommitted and must be preserved. No commit, push, backend/configuration change or R02d–e/transport activation is
included. This is the SAME normative specification/API contract for Worker and QA, with disjoint source access.

## Goal and non-goals

Complete the SDK-independent failure vocabulary needed by later Android adapters/engine and align the existing JVM-only
HTTP adapter with those categories. The production deliverable is failure TYPES and contract documentation, not a new
HTTP classifier, HTTP client, engine, refresh loop, cache cleanup or UI. Coordinator alone aligns the old test adapter;
Worker may never inspect that test implementation and QA tests it through the supplied black-box interface facts.

Authority: [service protocol, error matrix and read tokens](../../../shared-docs/docs/backend-contract/service-protocol.md),
[authentication](../../../shared-docs/docs/backend-contract/authentication.md),
[Android client protocol](../../../shared-docs/docs/backend-contract/android-client-protocol.md) and
[client compatibility](../../../shared-docs/docs/backend-contract/client-compatibility.md). Do not change these contracts.

No Room/schema/assets/dependencies, HTTP/auth SDK, challenge purpose/model, JSON grammar, new code-size/domain cap,
identity-rule edit or durable version. No simulator or backend source/Maven/configuration access. No credentials/domain
bodies/cursors retained as exception constructor fields or causes. Future recovery actions below are category meanings,
not claims that an engine or account lifecycle now implements them.

## Baseline inventory and representation choice

`data/sync/api/SyncApiFailures.kt` already has sealed `SyncApiException`, fixed-text/no-cause constructors, stable `code`,
`Transient` with a five-minute hint cap, `AuthenticationRequired`, `CursorExpired`, `RequestRejected` and `MalformedResponse`.
`AuthenticationRequired` currently conflates access rejection and foreground reauthentication. Invalid read tokens,
snapshot expiry and request-too-large lack dedicated types. Unknown permanent service codes are already represented as
`RequestRejected.errorCode`; preserve that unmodified opaque string without a new whitelist/cap or display leakage.

Existing `GymSyncApi`, `ServiceAuthApi` and `AccessTokenProvider` are SDK-independent interfaces only. Test-only
`HttpGymSyncApi` owns current scripted/local HTTP execution; it currently folds invalid/snapshot-expired errors into
request rejection and treats every 401 as the same category. No production HTTP/auth adapter exists.

Use the smallest compatible change: add five zero-argument nested subclasses, retain existing public constructors and
stable existing codes, correct the access-authentication message, and document category meanings in API KDoc. Do NOT
invent another response parser/public factory/retry-policy enum or implement recovery state machinery in R02c.

## Required production API and behavior

Package: `com.dragosfotache.gymtracka.data.sync.api`. The existing `SyncApiException` remains sealed and SDK-independent.
Messages below are normative fixed display text, not interpolated request/service text. Constructor parameter/type
names are public API facts supplied equally to both contributors.

| Nested type / constructor | `code` | Fixed `message` |
|---|---|---|
| `Transient(retryAfterMillis: Long? = null)` | `transient` | `transient sync failure; retry with bounded backoff` |
| `AuthenticationRequired()` | `authentication_required` | `service access authentication required` |
| **`ReauthenticationRequired()`** | `reauthentication_required` | `foreground reauthentication required` |
| **`InvalidCursor()`** | `invalid_cursor` | `change cursor is invalid` |
| `CursorExpired()` | `cursor_expired` | `change cursor expired; full resync required` |
| **`InvalidSnapshotToken()`** | `invalid_snapshot_token` | `snapshot token is invalid` |
| **`SnapshotExpired()`** | `snapshot_expired` | `snapshot expired; discard staging and restart` |
| **`RequestTooLarge()`** | `request_too_large` | `request too large; retain intent and require recovery` |
| `RequestRejected(errorCode: String)` | `request_rejected` | `permanent top-level request rejection` |
| `MalformedResponse()` | `malformed_response` | `malformed or inconsistent protocol response` |

- Existing constructors and `RequestRejected.errorCode` remain compatible. No new validation/caps on that string.
  Unknown permanent codes remain distinct data, not converted to transient, discarded or interpolated into message/toString.
- Every category has null cause; attempts to attach a raw cause with `initCause` must fail as before. Constructors do not
  accept raw text/cause/token/cursor/body parameters. Do not attach raw suppressed exceptions in adapters.
- `MAX_RETRY_AFTER_MILLIS` stays `300_000L`; null stays null, negative milliseconds clamp to 0, valid hints retain value,
  large positive values saturate at the cap, including Long.MIN/MAX boundaries without overflow. No new retry-policy API.
- Access rejection permits later one-refresh-and-unchanged-retry logic; explicit reauthentication requires foreground
  identity when refresh cannot recover. Neither type (nor generic 404/offline) authorizes account/cache deletion.
- Invalid tokens are NOT expiry. Only explicit correctly routed expiry may request full resync or snapshot-stage restart.
  No token decoding, lexical ordering, age inference or implicit null-token retry is added.
- Request-too-large is not a retryable transport fault or successful partial push. Retain frozen/unsent intent; later
  engine recovery must not endlessly resend, split a semantic mutation or mint new IDs automatically.
- Cancellation propagates as the original `CancellationException`; these category classes do not wrap it. Per-mutation
  rejected outcomes still return normally in a complete HTTP 200 result, including unknown permanent error codes.

## Coordinator-owned JVM adapter alignment

This section is normative black-box behavior, not permission for either contributor to inspect adapter source.
The adapter continues using current strict error-code extraction and existing safe transport/decoder boundaries. Its
pre-existing test-only machine-code syntax guard is not a new production code constraint; production error-code parsing
belongs to R03. Do not add raw error-envelope codecs/status factories to production for this alignment.

| Response/context | Category |
|---|---|
| 401 with parsed `reauthentication_required` on push/pull/snapshot | ReauthenticationRequired |
| Other 401 (including absent/malformed body) | AuthenticationRequired, safe conservative access rejection |
| Pull: 400 + `invalid_cursor` | InvalidCursor |
| Pull: 410 + `cursor_expired` | CursorExpired |
| Snapshot: 400 + `invalid_snapshot_token` | InvalidSnapshotToken |
| Snapshot: 410 + `snapshot_expired` | SnapshotExpired |
| Read-token code on wrong operation, or wrong ordinary 4xx status (not 401/413/429) | Existing permanent RequestRejected carrying the parsed code; no expiry shortcut |
| 413 on any sync operation | RequestTooLarge (status authoritative, even if body absent/malformed) |
| 429, 500, 502, 503, 504 | Existing bounded Transient; no raw body/cause |
| Other 4xx with a parsed safe machine code | RequestRejected with that exact code, including unknown codes |
| Other 4xx without valid error code; unexpected non-200 statuses | Existing MalformedResponse |
| Transport exception | Existing Transient without raw cause |
| Transport/decoder cancellation | Original cancellation, not a SyncApiException |
| HTTP 200 per-mutation rejected result | Normal PushResultModel outcome; no top-level exception |

Status precedence is authoritative: 401/413/429/retryable 5xx keep their listed categories regardless of a mismatched
read-token code. For example 422 + cursor_expired is RequestRejected, 503 + cursor_expired is Transient, and 401 +
cursor_expired is AuthenticationRequired. Wrong-context token errors must not become expiry merely because their code
text is recognizable. Retain existing exact request
encoding, bearer binding, opaque query forwarding, count/order checks, retry-hint parsing and safe error redaction.
No network/backend must be started; all new adapter checks use scripted in-memory transport results.

## File/source boundaries

**Worker writable only**, under `app/src/main/java/com/dragosfotache/gymtracka/data/sync/api/`:

- `SyncApiFailures.kt` — behavioral type additions and corrected fixed authentication text.
- `SyncApi.kt` and `ServiceAuth.kt` — relevant KDoc ONLY, no method/model/constant/implementation changes. SyncApi.kt
  already has pre-existing dirty retirement comments; preserve those edits exactly.

Worker may read the production API subtree, AGENTS.md, this specification and cited shared contracts. NEVER inspect
any tests/fixtures/reports/QA/coordinator adapter work, broad diffs or LSP test references. No test runs. Production compile
only after exclusive slot. Prefer restricted Kotlin mcpls; bounded unavailable/indexing attempts use exact reads/compiler,
not configuration edits or repeated sleeps. No other production changes without amendment.

**QA writable/readable only:**

- New `app/src/test/java/com/dragosfotache/gymtracka/data/sync/ServiceFailureTypesTest.kt`
- New `app/src/test/java/com/dragosfotache/gymtracka/data/sync/HttpFailureCategoriesTest.kt`

QA may read AGENTS.md, this spec and cited shared contract/OpenAPI. NEVER inspect production declarations/source/diffs/
decompilation, Worker work or the coordinator-owned adapter source/diffs/old tests. Use mcpls ONLY on these test files,
not production/adapter hover/definitions/completion. Ask coordinator for missing facts. No fixture/harness reads without
amendment. Standard JUnit4 (`org.junit.Test`, `org.junit.Assert`), coroutines `runTest`, no custom runner needed. Use
four-space Kotlin style, separate @Test lines, table-driven cases and raw triple-quoted JSON where useful. Avoid class-name
reflection; the exact public types/signatures are supplied here.

**Coordinator only:** align `app/src/test/java/.../data/sync/backend/HttpGymSyncApi.kt` and revise intentional legacy
expectations in `SyncApiBoundaryTest.kt` (access-auth fixed text) and `HttpSyncApiTest.kt` (snapshot expiry and unknown
permanent-code coverage). Audit amendment: `backend/RealBackendIsolationTest.kt` also consumes the old invalid-token
categories; coordinator changes ONLY its three catch/assert expectations to InvalidCursor/InvalidSnapshotToken. No
fixture/setup/backend operation changes and no real-backend pass is claimed from this source alignment.
Review full changes/new files and update architecture/testing/roadmap/handoff, this spec and `R02c-review.md`. Contributors
must not learn implementation facts from the other layer. Both receive this same normative contract.

## Supplied black-box harness/API facts

`HttpGymSyncApi(transport: SyncHttpTransport, accessToken: String)` is a public JVM-test-only implementation of
`GymSyncApi`; its API methods are:

```kotlin
suspend fun push(requests: List<DurableRequestModel>): PushResultModel
suspend fun pull(cursor: String?, limit: Int = 100): PullResultModel
suspend fun snapshot(pageToken: String?, limit: Int = 200): SnapshotResultModel
```

All test harness types below use package `com.dragosfotache.gymtracka.data.sync.backend`:

```kotlin
data class SyncHttpResponse(val statusCode: Int, val headers: Map<String, List<String>>, val body: String)
fun interface SyncHttpTransport {
    suspend fun exchange(method: String, path: String, query: Map<String, String>, headers: Map<String, String>, body: String?, authorization: String): SyncHttpResponse
}
```

A scripted transport can be `SyncHttpTransport { _, _, _, _, _, _ -> response }` or an object recording those arguments.
No call must open sockets/use JDK transport or fixtures. `push(emptyList())` is a valid shape and is enough for top-level
error routing. Paths are `/v1/sync/push`, `/v1/sync/pull`, `/v1/sync/snapshot`; bearer travels as `authorization` string.
Caller-supplied cursor/pageToken is passed opaquely in the respective `cursor`/`pageToken` query field. Successful empty
pages are `{"batches":[],"nextCursor":"opaque","hasMore":false}` and
`{"records":[],"baselineCursor":"opaque","hasMore":false}`.

Codec/model facts for a single per-mutation outcome or unchanged request test: package `data.sync.codec` exposes
`decodeDurableRequest(jsonText: String): DurableRequestModel`; package `data.sync.model` exposes
`DurableRequestModel.requestJson` and `.envelope.mutationId`, `PushResultModel.results`, and each outcome's
`.mutationId`, `.errorCode`, `.status` (`MutationStatus.REJECTED`). Independently hand-author a valid mutation such as:

```json
{"mutationId":"11111111-1111-1111-1111-111111111111","type":"workout.deleteSet","aggregateType":"workout","aggregateId":"22222222-2222-2222-2222-222222222222","localGeneration":1,"payload":{"workoutSetId":"33333333-3333-3333-3333-333333333333"}}
```

Local wrapper: `{payloadVersion:1,requestJson:<JSON STRING of exact mutation>,affectedRoots:[{aggregateType:"workout",
aggregateId:<same root>,localGeneration:1}]}`. Standard test `JSONObject.quote(innerExact)` may quote the string.
Expected full push body is hand-authored `{"mutations":[<EXACT inner>]}`. A scripted HTTP 200 body can return
`{results:[{mutationId:<same mutation>,status:"rejected",errorCode:"future_permanent_code",batch:null}]}`.
No production encoder should generate expected fixture values.

## Independent acceptance and gates

Cover all ten type/code/message rows, zero-argument compatibility, causes/initCause refusal, retry-hint null/negative/
large boundary values, and unknown RequestRejected.errorCode retention without display/cause leakage. Distinguish
access-auth from foreground reauth and invalid from expired read tokens by exact types, not just substring text.

Test the routing table across all three operations, correctly and incorrectly routed/status token codes, 413 with
valid/missing/malformed body, unknown permanent codes, bounded 429/5xx, fixed safe sentinel messages/toString/cause,
raw transport failure, original cancellation propagation, normal rejected per-mutation outcome and byte-exact frozen
push body without automatically replaying/rewriting anything. No test should use network/sleeps/backend state.

Both contributors author independent allowlisted files then report ready asynchronously via
`herdr agent prompt gym-coordinator '<role report>'` WITHOUT `--wait`; no agent wait, user questions or direct cross-role
coordination. No Gradle until exclusive slot. Worker compiles production; coordinator separately compiles its adapter
alignment before granting QA the focused run. On failure STOP/report exact own-file/own-XML issue, no repair loops.

```sh
# Worker
./gradlew :app:compileDebugKotlin --console=plain
# Coordinator adapter/test compilation
./gradlew :app:compileDebugUnitTestKotlin --console=plain
# QA focused gate after permission
./gradlew :app:testDebugUnitTest --tests 'com.dragosfotache.gymtracka.data.sync.ServiceFailureTypesTest' --tests 'com.dragosfotache.gymtracka.data.sync.HttpFailureCategoriesTest' --rerun-tasks --console=plain
```

Coordinator final gate is forced full offline JVM, production/Android-test Kotlin compilation, debug assembly/lint and
both diff checks. Inspect actual XML counts/names/failures/errors/skips and preserve untracked files and R02b changes.
Record device/backend/OAuth/engine limitations explicitly. Stop after R02c review, with no R02d–e, production transport,
commit, push or configuration/deployment step.
