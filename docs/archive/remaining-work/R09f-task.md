# R09f — inert exact-pending-refresh reconciliation before reauthentication

## Authority and boundary

Released R09f continues the reduced R09 roadmap after accepted R09e. This is ONE replay-only prerequisite: reconcile an
exact established ACTIVE Room/ACTIVE vault pair before a separate R09e supplied-grant replacement. It does not combine
refresh and reauthentication in a fake atomic operation. Production remains signed-out/local-only and constructs none
of these coordinators. Stop at a stably reread settled old ACTIVE pair, with no pending predecessor. R09e must freshly
read/admit it again; the result is advisory, not access credentials, reusable authority, visibility or permission to
replace an unsuccessful pending predecessor.

Non-goals: fresh refresh selection/rotation, clock/UUID calls, automatic retries/backoff, Google/challenge/exchange/logout,
TRANSITIONING pending adoption/reconciliation, signed-out/missing-vault recovery, app wiring, repository rebinding,
bootstrap/projection/promotion/activation, schema/vault-format changes, legacy import/delete/reassignment, retirement or
cleanup of failed/legacy-malformed uncertain attempts, backend source/configuration, live Google/account/device actions.
Another service account UUID cannot adopt retained data. R12a real edit/timer barriers and core R10 still gate activation.
Every unresolved blocker/question goes to the user; no new uncertain key, expiry rounding, compensation or cleanup.

## Normative sources and current inventory

- Supplied AGENTS and [workflow](../../coordinator-workflow.md), [testing policy](../../testing-strategy.md#proportional-coverage-and-verification-gates).
- [Implemented refresh](../../sync/implementation.md#encrypted-vault-and-refresh),
  [Room authority](../../sync/implementation.md#durable-private-room-account-authority),
  [R09e replacement](../../sync/implementation.md#inert-settled-occupied-credential-reauthentication),
  [two-store design](../../sync/integration-design.md#6-vault-and-room-two-stores-recoverable-journal-no-fake-atomicity).
- Shared [authentication](../../../shared-docs/docs/backend-contract/authentication.md): credential lifetimes, T1,
  refresh endpoint, Android storage/recovery; [OpenAPI](../../../shared-docs/docs/backend-contract/openapi.yaml)
  `/v1/auth/refresh`, `IdempotencyKey`, `RefreshCredential`, `ServiceSession`, `SessionResponse`, `ServiceError`, `Uuid`;
  [compatibility](../../../shared-docs/docs/backend-contract/client-compatibility.md#wire-types-and-validation) and
  [legacy limitation](../../../shared-docs/docs/backend-contract/issues/auth-expiry-precision.md#historical-accepted-replay--explicit-unresolved-compatibility-limitation).

Reuse Room12 `RoomSessionAuthority.read()` with its strict coherent immutable snapshot; no new Room writer. Reuse the
ONE supplied `SessionRefreshCoordinator` and its exact typed attempt/send/full-grant CAS/reload/lease machinery. It has no
success cache; its existing access selection may rotate after replay and therefore MUST NOT be used for this operation.
Reuse `SessionReauthenticationCoordinator`'s shared physical-DB owner, required credential mutex, newest opaque fence,
closed failures, alternating rereads and settled R09e replacement/resume. No new refresh coordinator/owner/storage driver.

## Concrete API and ownership

Extend the existing internal coordinator without changing its constructor or existing R09e methods:

```kotlin
suspend fun reconcilePendingRefresh(
    expected: RecoveryRoomSnapshot,
    refreshCoordinator: SessionRefreshCoordinator,
): SettledSessionObservation
```

Add internal immutable `SettledSessionObservation(val room: RecoveryRoomSnapshot, val vaultRevision: Long)` to
`SessionReauthenticationModels.kt`. Require ACTIVE, complete baseline, identity, positive vault revision. Fixed redacted
`toString()`. It contains no grant/token/lease. Existing `SessionReauthenticationFailure`/exception remain the closed local
failure surface. No new result-shape matrix is needed.

The supplied refresh coordinator MUST use the same configured origin, referentially identical vault, and referentially
identical credential mutex held by the reauthentication coordinator. Add a pure internal compatibility predicate to
refresh (no I/O). Mismatch is INVALID_INPUT BEFORE opening a new invocation fence/locks/I/O. No silent independent owner.
Caller-active/input-valid entry requires ACTIVE+complete baseline and same configured origin; invalid phase/incomplete
baseline is INVALID_INPUT, foreign origin IDENTITY_MISMATCH. Invalid input must not supersede valid in-flight work.

Lock order: shared DB `runOwner.mutex` then the supplied refresh coordinator's matching credential mutex. Hold both
continuously through preflight, exact refresh HTTP/CAS and all final rereads. Do not acquire the credential mutex twice,
call existing `accessToken()` from a held mutex, or wait for an owner from a credential callback. Constructor stays inert.
Existing R09e methods share these same locks; no refresh/replacement overlap is supported outside this ownership domain.

A bounded internal refresh helper may own the credential mutex and invoke preflight/final callbacks under it:

```kotlin
internal suspend fun <T> withExactPendingRefresh(
    lease: SessionRefreshLease,
    preflight: suspend () -> VaultSnapshot,
    settled: suspend (VaultSnapshot) -> T,
    failed: suspend (Throwable) -> Nothing,
): T
```

It checks lease origin, caller/authorization/admission, uses the preflight exact snapshot, reuses private send/commit
helpers for at most ONE pending transmission, and invokes settled while still holding the mutex. No token escapes this
helper. Error mapping/rereads run through failed while both owners remain held; cancellation bypasses that callback.
Its only new consumer is the coordinator above. Internal helper names may vary without changing the normative
external API. Coordinator owns old Room authorization; default/unbound primitive leases are NOT application authorization.

## Exact protocol, fences and disposition

1. Validate active caller/input/resources before opening the newest opaque invocation fence. Under both owners require
   actual Room equals the full supplied expected snapshot (revision/generation/account/origin/operation/phase/baseline).
   Load a positive-revision occupied ACTIVE vault with exact old binding+operation; missing/empty/PREPARED/foreign/adjacent
   generation/orphan evidence refuses IDENTITY_MISMATCH, no HTTP/CAS. Do not reconcile a pending predecessor after R09e
   has reserved TRANSITIONING: old Room authorization is no longer valid; it remains retained for later owner disposition.
2. Alternating Room/vault/Room/vault/Room preflight compares exact vault revision, full grant/expiry/phase/binding/operation,
   and semantic pending operation/key/body/token. Separate decoded attempt instances compare by content, never reference;
   whitespace/order/escaping and the old refresh token remain exact. Any vault drift refuses without adoption. Room drift
   refuses STALE_AUTHORITY. A last old-Room authorization check precedes HTTP admission.
3. No pending: perform only stable pair rereads and return advisory SettledSessionObservation. No HTTP/CAS/clock/UUID,
   even if expired. Pending: check vault revision space BEFORE transmission; Long.MAX_VALUE is COUNTER_OVERFLOW. Do not
   require Room increment space since no Room mutation occurs. Send that exact retained typed ServiceRefreshAttempt ONCE,
   even if old access appears live or the consumed predecessor's refresh expiry is past. No recapture/reencoding/key/UUID.
4. Use a fresh scoped ACTIVE refresh lease with a REQUIRED predicate rechecking exact old Room and current invocation,
   independently of the process-local reauth latch. Around every vault/network seam and immediately before full-grant CAS,
   check caller activity/currentness/old Room. A response must be same service account. Preserve exact returned full grant
   and deadlines, including expired access or refresh; never rotate again or infer failure solely from local expiry.
5. Before CAS reload exact sent revision/full old grant+pending tuple. Exactly one CAS at that revision replaces session
   with complete returned grant and clears pending together, preserving old binding/operation/ACTIVE phase. No conflict
   retry. Reload verifies actual revision == sent revision+1 and exact full installed grant/no pending. Final alternating
   Room/vault/Room/vault/Room rereads prove unchanged old Room and exact installed vault before advisory return.
6. Safe SyncApiException failures from refresh retain their accepted category/hints, fenced for late Room/invocation/caller
   changes. Wrong-account/strict malformed legacy replay => MalformedResponse and original pending retained. Explicit
   refresh AuthenticationRequired maps to ReauthenticationRequired as existing refresh machinery does. Transient/rate-limit/
   reauth/malformed/conflict do not retire pending, reserve Room, replace with a supplied grant, or clean credentials/data.
   Other local errors map to existing closed failures: strict Room errors use R09e mapping; vault errors use R09e mapping;
   refresh STALE_SESSION with unchanged Room => IDENTITY_MISMATCH, refresh UNAVAILABLE => AUTHORITY_UNAVAILABLE (required
   authorization seam), INVALID_INPUT => INVALID_INPUT. ACCESS_UNAVAILABLE is impossible in replay-only scheduling.
   Recheck full old Room on error before surfacing categories; Room drift => STALE_AUTHORITY, newest fence/invalidation =>
   STALE_OPERATION, caller cancellation precedes all. Raw faults map closed; no causes/suppressed secret material.
7. Invalidate synchronously/newest valid invocation fences queued/in-flight old work without waiting for I/O. Retire the
   scoped lease at invocation settlement, including failure/cancellation. Already-started synchronous CAS can commit
   despite lost/canceled/invalidated return; dead work cannot report success. Never compensate/reinstall/rekey/clear.
   On restart reread: exact pending => retry exact attempt; settled ACTIVE => read-only idempotence; mismatched Room =>
   refuse private. No Room write occurs in this operation, so crash after replay CAS but before R09e reservation leaves
   old ACTIVE authority unchanged. R09e later separately performs its already accepted exact replacement protocol.

No domain, children, relationships, outbox/frozen request bytes, canonical ledger/watermark, cache epochs/dirty flags,
checkpoints/last-success, snapshot stages/tokens/records, foreign scopes, catalogue or local-only roots may change during
reconciliation. Local roots are not adopted; subsequent R09e reservation still independently refuses them unchanged.

## Contributor allowlists and supplied harness facts

Worker production read/write:
- `app/src/main/java/com/dragosfotache/gymtracka/data/sync/session/SessionReauthenticationCoordinator.kt`
- `app/src/main/java/com/dragosfotache/gymtracka/data/sync/session/SessionReauthenticationModels.kt`
- `app/src/main/java/com/dragosfotache/gymtracka/data/sync/session/SessionRefreshCoordinator.kt`

Worker read-only production dependencies: `data/sync/session/{SessionRefreshLease,SessionRefreshFailures,
SessionReauthenticationFailures,SessionRecoveryModels,VaultModels,VaultProtocols,VaultFailures,RoomSessionAuthority,
RoomSessionAuthorityFailures}.kt`, `data/sync/api/{ServiceAuth,ServiceAuthRequests,SyncApiFailures}.kt`,
`data/sync/http/{ServiceOrigin,ServiceSessionBinding,HttpServiceAuthApi}.kt`, `data/sync/model/` UUID/millis helpers,
`data/sync/store/PushStore.kt` (PushRunOwner declaration). No tests/fixtures/reviews/QA output or other source/diff access.
Ask for missing permitted dependency paths rather than broad search. Worker uses compileDebugKotlin only with explicit slot.

QA read/write ONLY new test files `SessionPendingRefreshReconciliationTest.kt` and optional matching test fixture under
`app/src/test/java/com/dragosfotache/gymtracka/data/sync/session/`, and `SessionPendingRefreshRoomVaultTest.kt` under matching
`app/src/androidTest/java/`. QA read-only existing tests/fixtures in those two session directories plus test-owned
HTTP fixtures under `app/src/test/java/com/dragosfotache/gymtracka/data/sync/http/`. No production declarations/diffs,
generated/decompiled source/schema JSON/Worker work. Coordinator owns integration/build/docs/gate output inspection.
Both roles read this spec and its task-relevant normative docs; do not recursively ingest entire contract/architecture.

QA contract facts (not permission to inspect production):
- Existing fixtures `SessionReauthenticationTestFixtures` supplies activeSnapshot(revision=11,generation=7), activeRecord,
  whitespacePending, newGrant, coordinator(authority,vault,runOwner,credentialMutex), semantic comparisons and noncopying
  cancellation probe. `ScriptedReauthenticationAuthority` exposes readGate/readFaults/seedDurable/read/begins/finishes.
  FakeSessionVault and FakeServiceAuthApi are in RefreshTestFixtures; reuse existing deterministic gates where suitable.
- `SessionRefreshCoordinator(origin,vault,authApi,clockMillis,uuidProvider,mutex)` named constructor. Clock/UUID providers
  may throw AssertionError to prove zero calls here. `ServiceAuthApi.refresh(attempt)` takes retained immutable typed input.
  Other methods challenge(default purpose SIGN_IN), exchange(attempt), logout(refreshToken) must not run. Exact grant
  construction ServiceSession(account,accessToken,accessExpiresAtMillis,refreshToken,refreshExpiresAtMillis).
- `SessionReauthenticationCoordinator(origin,authority,vault,runOwner,credentialMutex)`, R09e reauthenticate(expected,
  operationId,session), resume(expected,session), recover(), invalidate(). PushRunOwner() exposes internal mutex: Mutex.
- `RoomSessionAuthority(database)` supplies read(), fencePrivate(expected), and R09e writer methods. Real GymDatabase via
  Room builders, no destructive fallback. Seed literal SQL using existing QA-owned R09e tests; dirty complete baseline
  is eligible. Canonical origin storage is `https://sync.example.test:443`; generation7/revision11/ACTIVE journal and
  ready device with account A/cache complete; retained domain/outbox/cache/stage snapshots already have vetted helpers.
- `AndroidSessionVault.createForTesting(context,namespace)` uses own namespace `gymtracka-test-r09f-*` and Keystore alias
  `com.dragosfotache.gymtracka.session.<namespace>`; cleanup only these keys/files/test DBs. Reopen must NOT delete DB.
- VaultSnapshot(revision,record), VaultSessionRecord(binding,operationId,phase,session,pendingRefresh),
  VaultPendingRefresh(operationId,attempt), ServiceRefreshAttempt.fromJson(key,body) preserve exact whitespace bytes.
  `HttpServiceAuthApi(origin,transport)` scripted production-adapter tests are already available in QA-owned fixtures;
  ask coordinator for any missing signature, never inspect production. All expected wire bytes/grants are independent.

## Risk-selected tests and gates

High-risk credential/late-result boundary, no schema/packaging/app change. Distinct new obligations (reuse existing R04/R09e
matrices, do not duplicate every constructor/validator or all mutation families):

- Pending-first exact whitespace/key/token replay despite old live access and expired consumed-token deadline; ONE send,
  one full grant CAS/no pending, exact old identity/phase and zero clock/UUID; returned expired access/refresh still stored,
  no fresh rotation. Nonpending expired pair is read-only idempotence; successful replay then separately R09e replacement.
- Representative missing/mismatched pair, Room drift before admission/pre-CAS/post-CAS, vault drift/conflict, overflow
  before HTTP; no unauthorized writes/adoption. Semantic rereads must work across distinct restored attempts.
- Transient/rate-limit/explicit reauth/wrong-account/actual strict HTTP malformed legacy replay retain exact pending bytes
  and Room; no replacement/reservation, key changes or cleanup. Safe categories/hints and closed errors/cancellation.
- Newest/invalidate/caller cancellation during queued owner or credential mutex, HTTP, pre-CAS load and committed-CAS
  delayed result; late result/category fences, locks released, restart uses exact retained or settled state.
- Shared physical owner THEN credential lock contention with R09e/ordinary shared refresh; no deadlock/nested mutex,
  no mixed replacement/replay. Invalid resource/input must not supersede a valid invocation.
- Real Room+Android encrypted file/Keystore integration: complete dirty baseline/retained frozen queue/stage/foreign scope
  preservation, exact replay then R09e transition; fail/cancel/lost result before/after CAS and non-destructive file reopen;
  durable Room fence during delayed refresh cannot authorize late vault CAS/result. Full table snapshots around replay,
  representative retained snapshot through separate R09e writes (only journal/device changes). No fake DAO tests.

Iteration: compile production, focused new+affected JVM, instrumentation compilation; coordinator serialized slots.
Acceptance: fresh full offline JVM (task-scoped --rerun), focused new real Room/Keystore/reopen class plus existing R09e
coordinator/Room-vault and affected refresh/recovery/prepared engine session regressions as needed; inspect executed XML
classes/counts/failures/errors/skips. Frozen-source unsuppressed lint and debug assembly/APK exclusion/security/preservation
checks are brought forward for credential risk. Check both trees, asset/schema hashes, diff whitespace and links.
No schema changes => no new migration matrix; existing exported schemas/asset unchanged. Fresh unfiltered full
instrumentation and actual two-store real-service engine convergence remain R09/R10 integration gates BEFORE activation;
real API24/hosted CI/Google/OEM/phone remain milestone gates. No new backend behavior/codec/endpoint: reuse accepted
R03c/R04b real-service exact replay evidence; this slice adds deterministic client composition, not a fresh backend pass.
Required focused device gate needs an isolated test-owned emulator; unavailable gate is a blocker, never silently waived.

## Stop and delivery

After independent coordinator review and declared gates, archive this specification with scoped dated R09f review;
replace owning implementation/design/roadmap/handoff facts, commit only accepted slice paths and preserve both trees.
Stop at advisory settled old ACTIVE eligibility. Escalate unresolved unsuccessful/legacy-malformed pending disposition
and next dependency scope to user; do not infer retirement or plan broad activation. Fresh coordinator continuation only
for a concretely eligible slice under the standing rule, never around these blockers.
