# R02d — Purpose-bound auth codecs and exact-body attempts

Status: **implemented and coordinator-accepted** (2026-10-03); see [review](R02d-review.md).
The owner authorized starting R02d. Final gate: 340 offline JVM tests in 32 suites, zero failures/errors/skips;
production/Android-test compilation, assembly/lint and preservation checks passed. No device/backend/OAuth pass or commit.
This is the SAME normative specification/API contract for Worker and QA.
R02a–c are accepted; Android pickup HEAD is `623207c` and its working tree was clean. Nested shared-docs has pre-existing
relocation/product/contract changes; preserve it exactly. No commit, push, next slice, backend or configuration change.

## Goal and stop boundary

Deliver transport-independent challenge purpose selection, strict auth request/response codecs, validated expiry models
and immutable exchange/refresh attempts that carry the exact request body and caller UUID key. No running sign-in,
transport, vault, retries, Google SDK, account UI, lifecycle proof/export/delete/status, Room/schema/assets, dependencies,
manifest, simulator or backend work. R02e and R03/R04/R11 remain separate tasks.

Authority: finalized [authentication](../../shared-docs/docs/backend-contract/authentication.md),
[OpenAPI](../../shared-docs/docs/backend-contract/openapi.yaml),
[service protocol](../../shared-docs/docs/backend-contract/service-protocol.md),
[client compatibility](../../shared-docs/docs/backend-contract/client-compatibility.md) and
[purpose rules](../../shared-docs/docs/backend-contract/account-lifecycle.md). Do not modify shared contracts.

## Inventory and design

`api/ServiceAuth.kt` has OpaqueToken, ServiceAccountRef, ServiceChallenge, ServiceSession, GoogleIdentityProvider,
ServiceAuthApi and AccessTokenProvider. Only the legacy JVM boundary test implements ServiceAuthApi; no production
adapter exists. Existing raw parser checks per-object decoded key uniqueness, including ignored response extensions.
Existing UTC parser enforces Z-only millisecond-lossless timestamps but its errors can echo supplied text; new auth
boundaries must sanitize them. Do not change shared parsing/UUID helpers or existing durable mutation versions/bytes.

Use final immutable attempt classes with PRIVATE constructors and companion factories, not data classes or mutable
JSONObject/ByteArray state. Retain validated original requestJson unchanged on restore; adapters will use that property
as the complete UTF-8 body, never reserialize parsed fields. No storage wrapper/version or automatic key/ID generation.
Exchange attempts are foreground-memory-only. Refresh attempts are storage-neutral input for future encrypted vault work;
R02d does not persist anything. The UUID key is header data, never a JSON body field.

## Required public API

Package `com.dragosfotache.gymtracka.data.sync.api`:

```kotlin
enum class GoogleChallengePurpose(val wireValue: String) {
    SIGN_IN("signIn"), EXPORT("export"), DELETE_ACCOUNT("deleteAccount"), ACCOUNT_STATUS("accountStatus");
    companion object { fun fromWire(value: String): GoogleChallengePurpose }
}

// Existing classes/constructor property names remain unchanged:
class OpaqueToken(val value: String)
data class ServiceAccountRef(val accountId: String, val displayName: String?)
data class ServiceChallenge(val challengeId: String, val nonce: String, val expiresAtMillis: Long)
data class ServiceSession(
    val account: ServiceAccountRef, val accessToken: OpaqueToken, val accessExpiresAtMillis: Long,
    val refreshToken: OpaqueToken, val refreshExpiresAtMillis: Long,
)

class GoogleExchangeAttempt private constructor(/* implementation-owned */) {
    val idempotencyKey: String
    val requestJson: String
    val challengeId: String
    val idToken: OpaqueToken
    companion object {
        fun capture(idempotencyKey: String, challengeId: String, idToken: OpaqueToken): GoogleExchangeAttempt
        fun fromJson(idempotencyKey: String, requestJson: String): GoogleExchangeAttempt
    }
}
class ServiceRefreshAttempt private constructor(/* implementation-owned */) {
    val idempotencyKey: String
    val requestJson: String
    val refreshToken: OpaqueToken
    companion object {
        fun capture(idempotencyKey: String, refreshToken: OpaqueToken): ServiceRefreshAttempt
        fun fromJson(idempotencyKey: String, requestJson: String): ServiceRefreshAttempt
    }
}

interface ServiceAuthApi {
    suspend fun challenge(purpose: GoogleChallengePurpose = GoogleChallengePurpose.SIGN_IN): ServiceChallenge
    suspend fun exchange(attempt: GoogleExchangeAttempt): ServiceSession
    suspend fun refresh(attempt: ServiceRefreshAttempt): ServiceSession
    suspend fun logout(refreshToken: OpaqueToken)
    // Preserve caller source compatibility through DEFAULT delegating convenience methods:
    suspend fun exchange(idempotencyKey: String, challengeId: String, idToken: OpaqueToken): ServiceSession
    suspend fun refresh(idempotencyKey: String, refreshToken: OpaqueToken): ServiceSession
}
```

Legacy exchange/refresh methods capture once then delegate to the typed method; implementers implement only typed methods.
Legacy no-argument challenge CALLS remain valid through the default purpose. Implementer override source changes are
intentional and coordinator will align the one old fake. Document that future retry orchestration retains/reuses a typed
attempt; repeatedly invoking a convenience method is not durable recovery. No default typed method may unpack an
attempt into a legacy call (that would lose arbitrary restored bytes). GoogleIdentityProvider/AccessTokenProvider stay
unchanged. All suspending methods remain boundary-only, no coroutine/state implementation added.

Package `com.dragosfotache.gymtracka.data.sync.codec`, raw String API only for new functions:

```kotlin
fun encodeGoogleChallengeRequest(purpose: GoogleChallengePurpose = GoogleChallengePurpose.SIGN_IN): String
fun decodeGoogleChallengeRequest(jsonText: String): GoogleChallengePurpose
fun decodeServiceChallenge(jsonText: String): ServiceChallenge
fun decodeServiceSession(jsonText: String): ServiceSession
fun encodeLogoutRequest(refreshToken: OpaqueToken): String
```

No response encoder, error classifier, HTTP API or lifecycle codecs. Private/internal request-field readers/encoding
helpers may support the attempt factories. Worker chooses their implementation names; QA does not inspect them.

## Normative shapes and validation

| Boundary | JSON and behavior |
|---|---|
| Challenge encode | Exactly one purpose field with its explicit wire enum; signIn default encoded explicitly. Object key order/whitespace not a new canonicalization contract |
| Challenge request decode | `{}` defaults SIGN_IN; otherwise only optional purpose, exact case-sensitive enum. Null, wrong type, unknown enum/field fail |
| Challenge response | Required challengeId canonical lowercase UUID, nonce nonempty string, expiresAt UTC RFC3339 string mapped losslessly to millis |
| Session response | Required account object with canonical lowercase `id`; optional/null displayName maps null, blank/Unicode preserved. Required nonempty string accessToken/refreshToken and UTC expiry strings |
| Exchange attempt | Exactly required challengeId and idToken; no purpose/account/key/owner/extra field accepted |
| Refresh attempt/logout encode | Exactly required refreshToken; no extra field accepted. Logout has no idempotency key/attempt |

- All raw readers go through the original-text duplicate guard and strict JSON object parser. Reject duplicate/escaped
  equivalent names at any nesting level, including ignored response extensions. Request unknown fields reject; response
  additive fields are ignored (including account additions) but never bypass duplicate checking.
- Audit refinement: the shared parser can normalize arbitrary unquoted scalar tokens into strings. Enforce JSON scalar
  grammar locally for these NEW auth readers only: skip quoted literals, and outside them allow only JSON structural
  punctuation/whitespace, true/false/null and RFC8259 numbers. Leave full syntax/string/duplicate validation to the shared
  parser. A small iterative lexical check is sufficient; no second recursive parser, field-path extraction or global
  parser change. This prevents unquoted NaN/foobar/signIn credentials/profile/purpose values becoming strings.
  Independent compiled-code probing also found that the shared parser accepts literal unescaped string controls.
  The same auth-local scan must reject literal U+0000..U+001F inside quoted strings; valid escaped controls remain opaque
  accepted string data. Keep escape/syntax validation delegated to the shared parser and leave durable behavior unchanged.
- String fields must be JSON strings, not booleans/numbers/arrays/objects. Required fields cannot be absent or null.
  Nonempty opaque tokens/nonce accept whitespace-only and Unicode strings without trimming, parsing as JWT, imposing
  length/entropy caps or client-side Google validation. OpaqueToken's existing constructor behavior stays compatible.
- UUIDs are exact lowercase 36-character wire UUIDs; no normalization/remapping. Attempt header keys validate equally
  at capture and restore. Never encode a key into the body. Parsed attempt fields must derive from the exact retained text;
  no public constructor/copy method can supply mismatched text and fields.
- Direct ServiceAccountRef/ServiceChallenge constructor and copy paths validate UUIDs. Direct ServiceChallenge and
  ServiceSession constructor and copy paths validate ALL expiry millis within inclusive
  `-62167219200000L..253402300799999L`. Token and nonce empty guards remain.
- UTC timestamp rules: four-digit years 0000..9999, strict actual calendar dates, T and Z uppercase, no timezone offsets,
  hour 24/leap seconds/trailing junk, or nonzero sub-millisecond fractions. `.123000` is valid and lossless; arbitrarily
  long fractional zero tails (for example `.123000000000`) are also lossless. Auth-local timestamp handling may discard
  ONLY trailing zero precision before using the existing strict UTC reader; do not silently discard nonzero digits or
  change the shared reader. Accept pre-epoch/min/max instants. No clock dependency, current-time expiry rejection, inferred issuance, fixed lifetime or
  access-vs-refresh ordering check. Original expired-access replay with current refresh must decode unchanged; this
  stateless layer cannot decide credential eligibility/account continuity.
- Failures in new factories/codecs and auth model UUID/expiry/nonce validation are SyncModelException with short fixed
  text (field names permitted) and null cause/no suppressed raw exceptions; never echo raw body, unknown keys/enum,
  tokens, nonce, UUID input or timestamp. Existing OpaqueToken empty failure remains IllegalArgumentException. Do not
  alter global SyncModelException behavior/cause mutability in this task. Public functions do not swallow cancellation.
- OpaqueToken retains redacted toString. ServiceSession keeps its existing token-safe display (account UUID/expiry
  metadata permitted, no displayName). ServiceChallenge toString must no longer print its nonce (ID/expiry allowed).
  Attempt toString must not expose body, token, nonce or IDs/keys; use fixed redacted text. requestJson/value getters are
  explicitly sensitive transport inputs, not logging/display APIs. No generated data-class copy/componentN for attempts.

## Exact identity and lifecycle invariants

Capture validates/encodes once. Repeated access/transmission through the typed interface exposes identical key/body.
Restore accepts valid arbitrary whitespace, field order and escape spelling, including outer JSON whitespace, and
retains the complete original String byte-for-byte. Decode for validation must not normalize requestJson. Valid raw JSON
strings containing JSON-looking text remain opaque, not recursively parsed. A changed valid raw body remains a different
body; no canonicalization, digest/replay service, equality-based dedup, random UUIDs or secret persistence is implemented.
Private constructors must ensure factories cannot bypass validation; there are no mutable child collections to alias.

Cancellation/races/crashes: attempts have no mutable state, clock, network, jobs or side effects; caller cancellation does
not mutate them. Future R04 persists uncertain refresh before send and fences grants; R11 bounds foreground exchange
retry; R03 forwards body/key exactly. No retry, account deletion inference or credential expiry extension in R02d.

## File/source separation

**Worker read/write:** existing `app/src/main/java/com/dragosfotache/gymtracka/data/sync/api/ServiceAuth.kt`, new
`api/ServiceAuthRequests.kt`, new `codec/ServiceAuthCodec.kt` under that same data/sync root. May READ existing production
api/model/codec files, AGENTS, this spec and cited shared contracts. No other production edits without amendment.
NEVER inspect tests, fixtures, reports, QA files/transcripts, broad diffs or LSP test references. No test runs.
Use mcpls restricted to production; bounded indexing/unavailable failure permits exact reads/compiler, not config edits.

**QA read/write:** NEW files only:
- `app/src/test/java/com/dragosfotache/gymtracka/data/sync/ServiceAuthCodecTest.kt`
- `app/src/test/java/com/dragosfotache/gymtracka/data/sync/ServiceAuthAttemptTest.kt`

May READ AGENTS, this spec and cited shared contracts/OpenAPI only. NEVER inspect production declarations/source/diffs,
Worker files/transcripts, bytecode/decompilation, existing tests/fixtures/harness or production hover/definitions/LSP
completions. Restrict mcpls to own two files. Ask coordinator for missing API facts. JUnit4 and coroutines runTest are
available. Model failure package is `data.sync.model.SyncModelException`. Helpers/constants in this spec are sufficient;
no fixture access is needed. Do not use production encoders to generate expected bodies/results. Compare encoder bodies
through independent JSON field observations, not incidental org.json key order.

**Coordinator only:** adapt `ServiceAuthBoundaryTest.kt` fake overrides to purpose + typed attempt methods, retain its
four tests and adjust intentional validation expectations to safe field-based failures if needed. Review all changes,
new files and preservation. Update architecture/testing/roadmap/handoff, this spec and new R02d-review.md.
Neither contributor edits docs or legacy tests. No contributor broad git status/diff or cross-role communication.

## Independent acceptance tests and serialized gates

QA covers all four purpose wire values/defaults; request closed fields vs additive responses; nullable/blank/Unicode
profile fidelity; required/type/null/empty/UUID/timestamp tables; constructor AND copy expiry boundaries/overflow; expired
replay and unordered expiry acceptance; duplicate escaped keys at root/account/ignored arrays; malformed raw shapes;
fixed safe failure sentinel tests (including malicious timestamp/UUID/key/unknown-field/enum and token-looking strings).
Exercise capture + restore, arbitrary whitespace/order/escapes, exact UTF-8 preservation/repeated typed sends, parsed token
fidelity, changed bytes retained distinctly, legacy convenience delegation and default noarg challenge. Assert redacted
attempt/challenge/session/token display. Cancellation from a recording boundary fake propagates unchanged with no attempt
mutation. Scripted doubles record arguments only, no server logic, network, sleeps, persistence or backend configuration.

Both roles author files then report READY asynchronously with `herdr agent prompt gym-coordinator '<report>'`, WITHOUT
`--wait` or agent wait. No user questions; send coordinator blockers. No Gradle until coordinator grants an exclusive slot.
Worker production compile only; QA focused two-suite run only, inspecting own XML, not broad reports. On failure STOP
and report; coordinator reviews then directs same-session corrections. Gradle shares build outputs: never overlap runs.

```sh
# Worker, on permission
./gradlew :app:compileDebugKotlin --console=plain
# Coordinator, after legacy override alignment
./gradlew :app:compileDebugUnitTestKotlin --console=plain
# QA, on permission
./gradlew :app:testDebugUnitTest --tests 'com.dragosfotache.gymtracka.data.sync.ServiceAuthCodecTest' --tests 'com.dragosfotache.gymtracka.data.sync.ServiceAuthAttemptTest' --rerun-tasks --no-configuration-cache --console=plain
# Coordinator final forced regression gate
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --rerun-tasks --no-configuration-cache --console=plain
git diff --check
git -C shared-docs diff --check
```

Inspect actual testcase counts/failures/errors/skips, not cached task outcomes. No new platform parser machinery is added;
Android-test compilation suffices for this bounded slice unless review finds a platform-specific gap. Earlier R02a six
Android parser smoke tests, 46 real-backend and full M3 device gates are prior evidence, not new R02d passes. Stop after
coordinator review/docs, no commit/push/transport/lifecycle/Google/vault/config activation or next task.
