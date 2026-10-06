# R09e — inert settled occupied-credential reauthentication

Status: accepted 2026-10-06; [review](R09e-review.md) owns scoped evidence. This is ONE bounded R09 slice under the
[workflow](../../coordinator-workflow.md), not all remaining account activation. The safe split is settled occupied ACTIVE
credentials plus exact pending refusal. Pending-resolution and signed-out/missing-vault reauthentication remain open.

## Goal, authority and stop

Replace a matching established account's settled occupied ACTIVE vault grant with a supplied same-service-account grant,
privately reserve a new durable session fence, and recover every local two-store interruption. Preserve retained domain,
canonical cache, queue, frozen requests, checkpoints and stages. Reuse Room12, encrypted vault CAS and existing private
PREPARING engine/completion. Return only advisory nonsecret preparation observations, never application authorization.

No app construction/visibility, ordinary repository rebinding, Google/challenge/exchange/refresh/logout HTTP, credential
freshness scheduling, account switching, sign-out, legacy import/delete/reassignment, projection/publication, completion,
new schema/migration, backend/configuration or live account/device actions. An uncertain pending refresh is NEVER cleared,
rekeyed, rebound or sent here. Settling/retiring a pending predecessor is a separate remaining bounded scope; this slice
must refuse it unchanged before reserving, and preserve a late predecessor if an already-started write races reservation.
No other service UUID may adopt retained data. R12a real edit/timer barriers and core R10 precede live activation.

## Required sources and existing facts

- Supplied AGENTS; [progressive policy](../../README.md), [workflow](../../coordinator-workflow.md), and
  [proportional gates](../../testing-strategy.md#proportional-coverage-and-verification-gates).
- [Two stores / personal scope](../../sync/integration-design.md#6-vault-and-room-two-stores-recoverable-journal-no-fake-atomicity)
  and [implemented authority, preparation, engine and completion](../../sync/implementation.md#durable-private-room-account-authority).
- Shared [authentication](../../../shared-docs/docs/backend-contract/authentication.md) credential lifetimes, exact pending
  retry and Android storage/recovery; [P16](../../../shared-docs/docs/backend-contract/open-decisions.md#confirmed-product-decisions);
  OpenAPI `ServiceSession`, `RefreshCredential`, `SessionResponse` and UUID shapes. No HTTP behavior changes here.
  [Legacy replay limitation](../../../shared-docs/docs/backend-contract/issues/auth-expiry-precision.md) remains unresolved.
- Runtime Room12 has `sync_session_authority` singleton id/revision/session_generation/phase/service_origin/account_id/
  operation_id and `sync_device_state` id/active_account_id/cached_account_id/session_generation/cache_state. Existing
  `transitioning` journal label is recognized and private but has no writer. Exact origin text is `https://host:port`.
- Room authority strictly observes journal/device/selected cache in one Room transaction; raw cursor types, singleton
  counts, exact update counts and full post-write tuples matter. Existing DAO already supplies exact-row updates and
  local-only-root existence queries. ACTIVE observations may have dirty cache, pending outbox or retained stages.
- Old ordinary stores/repositories are generation fenced. New TRANSITIONING and PREPARING have active=null; only the
  registered existing prepared engine can admit a fresh matching PREPARING/PREPARED pair. Ordinary stores stay ACTIVE-only.
- `PushRunOwner()` exposes its internal mutex; one owner per physical DB serializes private engine/delivery/completion.
  `SessionRefreshCoordinator(..., mutex: Mutex)` accepts a shared credential selection mutex. This new coordinator must
  REQUIRE that same mutex, not construct an independent refresh/transition owner. Lock order: run owner then credential
  mutex, held continuously through all reads/writes. No nested use from a held owner/credential slot. Construction is inert.
  Future application integration must use this shared ownership and drain accepted UI/timer edits under real barriers
  before scope transition. No live integration is delivered and no default/no-op application barrier is introduced.

## Normative API (internal session package)

Production signatures are fixed for independent QA:

```kotlin
interface SessionReauthenticationAuthority : SessionRecoveryAuthority {
    suspend fun beginReauthentication(
        expected: RecoveryRoomSnapshot,
        origin: ServiceOrigin,
        operationId: String,
    ): RecoveryRoomSnapshot
    suspend fun finishReauthentication(expected: RecoveryRoomSnapshot): RecoveryRoomSnapshot
}
// RoomSessionAuthority(database: GymDatabase) additionally implements this interface.
class SessionReauthenticationCoordinator(
    origin: ServiceOrigin,
    authority: SessionReauthenticationAuthority,
    vault: SessionVault,
    runOwner: PushRunOwner,
    credentialMutex: Mutex,
) {
    fun invalidate()
    suspend fun reauthenticate(
        expected: RecoveryRoomSnapshot,
        operationId: String,
        session: ServiceSession,
    ): PreparedSessionObservation
    suspend fun resume(
        expected: RecoveryRoomSnapshot,
        session: ServiceSession,
    ): PreparedSessionObservation
    suspend fun recover(): SessionReauthenticationRecovery
}
enum class SessionReauthenticationFailure {
    INVALID_INPUT, STALE_OPERATION, STALE_AUTHORITY, IDENTITY_MISMATCH,
    PENDING_REFRESH, LOCAL_DATA_REQUIRES_DECISION, COUNTER_OVERFLOW,
    AUTHORITY_CORRUPT, AUTHORITY_UNAVAILABLE, VAULT_UNAVAILABLE, VAULT_CONFLICT,
}
class SessionReauthenticationException(val failure: SessionReauthenticationFailure)
sealed interface SessionReauthenticationRecovery {
    data object NotReauthenticating
    data class NeedsGrant(val room: RecoveryRoomSnapshot)
    data class NeedsFinalization(val room: RecoveryRoomSnapshot, val vaultRevision: Long)
    data class Prepared(val observation: PreparedSessionObservation)
    data class Blocked(val failure: SessionReauthenticationFailure)
}
```

All new exceptions have fixed text/null cause/disabled suppression. All new objects/status displays are fixed/redacted.
Existing immutable `RecoveryRoomSnapshot(revision,sessionGeneration,cachedAccountId,phase,identity,baselineComplete)` and
`RecoveryIdentity(binding,operationId)` have exact value equality. Binding is
`ServiceSessionBinding(origin,accountId,sessionGeneration)`; generation is positive. Canonical lowercase UUIDs only.
`PreparedSessionObservation(room,vaultRevision)` requires PREPARING identity, baselineComplete=false, positive vault revision;
its unchanged validation throws `SessionPreparationException(SessionPreparationFailure.INVALID_INPUT)`.
`ServiceSession(account,accessToken,accessExpiresAtMillis,refreshToken,refreshExpiresAtMillis)` preserves full original grant;
`ServiceAccountRef(accountId,displayName)` and `OpaqueToken(value)` are existing validated types. Never read a clock.
`VaultSessionRecord(binding,operationId,phase,session,pendingRefresh)` and `VaultSnapshot(revision,record)` are existing types.
`SessionVault.load()` and `compareAndSet(expectedRevision,record)` atomically replace a full record with checked revision+1.
Pending equality is semantic operationId plus exact attempt.idempotencyKey/requestJson/refreshToken, NOT attempt-reference
identity; `VaultPendingRefresh(operationId,ServiceRefreshAttempt.fromJson(key,body))` can be separately decoded on every read.

## Room state transitions

`beginReauthentication` atomically admits ONLY the exact expected ACTIVE observation with complete baseline, configured
origin, retained account and identity. Require strict journal/device ACTIVE/ready tuple, no local-only workout/routine/custom
root (including deleted drafts), fresh operation != old operation, checked generation+1 and enough revision space for BOTH
reservation and finalization (expected revision <= Long.MAX_VALUE-2). It does not inspect the vault.
Write journal TRANSITIONING, revision+1, generation+1, same origin/account, new operation; device active=null,
cached=same account, generation=new, cache_state=preparing. Return exact TRANSITIONING snapshot, baselineComplete=false.
Use exact old-row predicates/counts and strict whole post-write tuple/snapshot checks before commit, including current
coroutine activity. Failures/cancellation roll back transactional effects where known; uncertain return requires reread.

`finishReauthentication` atomically admits ONLY exact expected TRANSITIONING identity plus device active=null,
cached=target/generation exact/cache_state=preparing; refuses local-only roots appearing after reservation. Increment
revision ONCE, retain generation/origin/account/operation, set journal PREPARING; leave exact preparing device tuple
unchanged. Check revision exhaustion before effects, exact counts and full tuples after writes. No domain/cache/outbox/
stage/checkpoint/catalogue writes. Both methods preserve every other table and sequence. Never call `fencePrivate` or
`beginPreparation` to compensate, restart the generation or turn retained account roots into local-only data.

## Coordinator ordering and crash boundaries

Every valid invocation opens a newest opaque process fence before waiting for serialized ownership. Validate structural
input/canonical UUIDs before retiring earlier work. At API entry check actual caller activity BEFORE input validation or
opening a new fence; an already-canceled caller cannot retire earlier valid work. `invalidate()` synchronously retires in-flight work without waiting
for locks/I/O and performs no durable effects. Check caller cancellation/current invocation around every seam; cancellation
has precedence, injected CancellationException escapes identically within its actual active caller. Dead invocation may
not report success/classification. One coordinator per physical pair, no cached success/admission.

### Start

1. Require input ACTIVE with complete baseline and configured origin, new operation != old operation, supplied grant
   account == expected retained service account. Other phases/invalid operation are INVALID_INPUT; foreign identity or
   grant is IDENTITY_MISMATCH. Load no vault for invalid or mismatched authority.
2. Under shared owner then credential mutex, require exact current Room expected and an occupied positive-revision ACTIVE
   vault record with the EXACT old binding/operation. Missing/tombstone/PREPARED/orphan/mismatched record refuses. If source
   pendingRefresh != null: PENDING_REFRESH, NO writes. Full Room/vault/Room/vault/Room preflight must remain exact; semantic
   pending comparison applies. Check Room's two revisions/generation and vault one revision before reservation.
3. Reserve once via `beginReauthentication`. Verify returned AND durably reread exact expected TRANSITIONING tuple before
   attempting vault write. Reservation failure/uncertain return starts NO CAS. Reload source after reservation and require
   full exact source revision/record, including no pending. An eligible predecessor with late pending maps PENDING_REFRESH
   before source revision/grant mismatch; other full post-reservation source drift maps VAULT_CONFLICT. Recheck exact
   TRANSITIONING Room AFTER this suspending vault load and immediately BEFORE replacement CAS; a Room fence during the
   load refuses STALE_AUTHORITY without a new credential write. Already-started writes may still commit; no retry CAS.
4. One CAS replaces ONLY exact old revision with full supplied grant, new Room binding/operation, PREPARED, pending=null.
   Preserve original tokens/display/expiry even expired. Verify returned revision+1/full record and alternating Room/vault/
   Room/vault/Room before finalization. CAS failure/uncertainty starts no finalization; no cleanup or CAS retry over conflict.
5. Finish exact TRANSITIONING -> PREPARING. Verify known return and stable alternating Room/vault/Room/vault/Room at exact
   final Room snapshot and exact vault revision/record, then return advisory PreparedSessionObservation. Not ACTIVE, not UI.

### Resume and reads-only recovery

`resume` accepts exact expected TRANSITIONING or PREPARING for configured origin and supplied same-account grant.
Never reserve again. TRANSITIONING can have either:
- A settled predecessor: occupied ACTIVE record at exactly target generation-1, same origin/account, operation different
  from target, no pending. Stable exact rereads permit one exact-revision supplied-grant CAS then Room finalization. No
  arbitrary older-generation/adopted grant, empty vault, PREPARED predecessor or different account/origin is eligible.
- An already installed target: exact PREPARED binding/operation/session. Preserve any target pending bytes unchanged;
  stable rereads permit ONLY Room finalization (no vault write). Target pending is not an old predecessor to discard.
PREPARING accepts only exact target PREPARED/session for read-only idempotence; preserve any target pending unchanged.
Wrong supplied grant against an installed target refuses, never replaces it. Missing/empty vault is Blocked, NEVER
NeedsGrant or permission to install a supplied grant; NeedsGrant requires the settled occupied predecessor.
TRANSITIONING source pending refuses even if
expired and even after another ordinary refresh reauthentication error. Missing credentials cannot be reconstructed.
`recover` makes NO writes/reservations/HTTP: non-TRANSITIONING/non-PREPARING returns NotReauthenticating without vault I/O;
other-origin identity blocks without vault I/O. Stable TRANSITIONING settled predecessor -> NeedsGrant; exact target
PREPARED -> NeedsFinalization; stable PREPARING target PREPARED -> Prepared. Missing/mismatched/corrupt/unreadable/changed
state -> Blocked with closed failure; pending predecessor -> Blocked(PENDING_REFRESH). A supplied authority's impossible
TRANSITIONING/PREPARING baselineComplete=true observation blocks AUTHORITY_CORRUPT, never escapes primitive validation.
NeedsGrant does not reconstruct or
authorize another exchange/key. Recover/rereads classify durable evidence, not cached prior outcomes.

A write already started may commit after invalidation/cancellation/error. Preserve durable bytes/operation and recover
by reread, not automatic clearing/tombstoning/fencing/reassignment. Room reservation-only -> NeedsGrant; vault committed
before lost CAS return -> NeedsFinalization; Room finalization committed before lost return -> Prepared. An out-of-band
Room/vault mismatch or late write must remain private. Old leases with required Room authorization checks and old-generation
repositories cannot use transition/new-session results; no unbound default lease is made application-safe by this slice.

## Disjoint contributor allowlists

Worker write: `app/src/main/java/com/dragosfotache/gymtracka/data/sync/session/RoomSessionAuthority.kt`, and NEW
`SessionReauthenticationCoordinator.kt`, `SessionReauthenticationModels.kt`, `SessionReauthenticationFailures.kt` in that
same directory. Worker read: those plus existing `SessionPreparationCoordinator.kt`, `SessionPreparationModels.kt`,
`SessionPreparationFailures.kt`, `SessionRecoveryModels.kt`, `RoomSessionAuthorityFailures.kt`, `VaultModels.kt`,
`VaultProtocols.kt`, `VaultFailures.kt`, `SessionRefreshCoordinator.kt`, `SessionRefreshLease.kt`, `EncryptedSessionVault.kt`;
`data/gym/{GymDatabase,SyncSessionAuthorityDao,SyncSessionAuthorityEntities,SyncEntities,SyncCanonicalEntities}.kt`;
`data/sync/{api/ServiceAuth.kt,api/ServiceAuthRequests.kt,http/ServiceOrigin.kt,http/ServiceSessionBinding.kt,
model/FamilyIdentityValidation.kt,store/PushStore.kt,store/SyncSession.kt}` and task-required explicitly named model
validation dependencies reached from these production files. No test/fixture/report/QA source, generated sources or QA diff.

QA write: NEW `SessionReauthenticationCoordinatorTest.kt` and `SessionReauthenticationTestFixtures.kt` under
`app/src/test/java/com/dragosfotache/gymtracka/data/sync/session/`; NEW `SessionReauthenticationRoomVaultTest.kt` and
`SessionReauthenticationRoomTest.kt` under the matching `app/src/androidTest/.../session/` directory.
QA read: these plus existing QA-owned JVM `SessionPreparationTestFixtures.kt`, `SessionPreparationCoordinatorTest.kt`,
`RefreshTestFixtures.kt`, `SessionRecoveryTestFixtures.kt`, `VaultTestFixtures.kt`, `SessionRefreshCoordinatorTest.kt`,
`SessionRecoveryAuthorizationTest.kt`; Android `SessionPreparationRoomVaultTest.kt`, `RoomSessionAuthorityTest.kt`,
`RoomSessionAuthorityConcurrencyTest.kt`, `RoomSessionAuthorityCorruptionTest.kt`, `RoomSessionAuthorityPreservationTest.kt`,
`PreparedSessionEngineTestSupport.kt`. No production declarations/implementations/diffs/generated/decompiled/schema files
or Worker work. Coordinator supplies missing signatures/harness facts, reviews BOTH source layers and owns docs/build/resource
integration. Both contributors can read this spec and referenced normative docs but not archived contributor source packets.
No contributor Gradle until an explicitly granted serialized slot; no model substitutions or source-boundary widening.

## Harness facts and proportional gates

Risk: high account privacy, occupied credential replacement, checked transactional fencing and two-store uncertainty.
Use pure handwritten SessionReauthenticationAuthority fakes + FakeSessionVault for deterministic JVM timing/cancellation;
NOT DAO mocks for transaction behavior. Reuse existing QA literal raw-SQL preservation fixtures, gate wrappers and actual
AndroidSessionVault test-owned Keystore/file namespaces instead of another large harness. No sleeps or real Google/service.

Android harness: `Room.inMemoryDatabaseBuilder(context,GymDatabase::class.java).build()` or named test-only file DB;
`RoomSessionAuthority(database)` implements read/new methods. `database.openHelper.writableDatabase.execSQL(...)`, raw query
cursor snapshots of tables and sqlite_sequence, plus literal journal/device/cache fixtures from existing QA tests.
`AndroidSessionVault.createForTesting(context, namespace)` returns SessionVault with real encrypted noBackup files;
namespace MUST start `gymtracka-test-`, alias is `com.dragosfotache.gymtracka.session.`+namespace, committed filename
`session.vault`, temporary `session.vault.new`. Remove ONLY test-owned namespace, alias and DB in cleanup. Do not use the
production factory/namespace. `PushRunOwner()` and `Mutex()` constructors are usable in JVM and Android tests; shared
SessionRefreshCoordinator constructor facts above enable a representative locking test if needed. Existing preparation
coordinator `(origin,authority:SessionPreparationAuthority,vault)` `recover()` yields Prepared on the final actual adapter pair.
`SessionRecoveryAuthority.read(): RecoveryRoomSnapshot` has no parameters; allowlisted QA fake/gate patterns may be copied
and renamed. Old-lease oracle uses `SessionRefreshLease(binding,operationId,phase,authorizationCheck:suspend()->Unit)`:
its supplied predicate compares `authority.read()` to the captured old ACTIVE snapshot and throws
`SessionRefreshException(SessionRefreshFailure.STALE_SESSION)` on mismatch; internal suspend `checkAuthorization(): Unit`
then proves the actual Room-checked stale boundary. Generation alone never invalidates a default unbound primitive lease.
Separate ordinary-store oracle: `RepositorySession(accountId:String?,sessionGeneration:Long)`,
`PushStore(database:GymDatabase,session:RepositorySession,owner:PushRunOwner)`, suspend
`withRun(expectedCacheEpoch:Long,block:suspend(PushRun)->T):T`; after reservation/finalization, the old-generation session
throws `StaleSessionException` before its block and without effects. Use a literal fixture cache epoch, not a production-derived
expected value. These supplied API facts do not grant production-source access.

Distinct new obligations (no count quota):
- Exact successful +1 generation/+2 journal revision/+1 vault revision, original full grant and old credential refusal;
  dirty baseline/retained queue/frozen bytes/stage/checkpoint/domain/foreign scope preservation on real Room.
- Source pending whitespace-exact refusal before ANY reservation/CAS; late pending or other source revision drift after
  reservation preserved and stops. Same Google/new service UUID, origin/operation/generation mismatches never adopt.
- Reservation-only, CAS-commit/lost-return and finalization-commit/lost-return recovery/resume without generation restart;
  installed target wrong grant refuses and separately restored target pending retains semantic equality.
- Shared run and credential ownership, newest invocation/invalidation/queued cancellation plus representative in-flight
  commit-after-invalidation/cancellation; old Room-checked lease and old-generation authority become stale.
- Real Room affected-count/valid-but-wrong trigger rewrite rollback, local roots at admission AND after reservation,
  strict malformed tuple/cache types and checked revision/generation/vault exhaustion before avoidable effects.
- Reads-only recovery/no-vault early exits, alternating reread drift, closed error mapping/displays and cancellation identity.
- Actual Room+Keystore/file reopen demonstrates final matching pair and a representative lost-result gap; existing R09b
  recovery recognizes the final pair. No fabricated fake-only claim of device/storage integration.

Iteration: coordinator compile production/tests; focused new JVM and two Android classes. Acceptance: fresh full offline
JVM, both new real-Room/actual-vault classes, affected existing SessionPreparationRoomVaultTest and Room authority
regressions (class filters individually with actual nested XML verified), compilation/KSP, assembly/lint/APK secret and
helper/backup checks, preservation of schema/asset/ordinary construction and both repository whitespace/diffs.
No schema change means no fabricated new migration gate; preserve all snapshots and asset byte-identical.
Full unfiltered instrumentation and two-store real-service engine convergence remain required R09/R10 integration gates
BEFORE activation; API24/hosted CI/Google/OEM/phone journeys remain milestone gates, not scoped acceptance claims.

Stop at inert PREPARING/PREPARED eligibility. Do not bootstrap/publish/promote/activate from this coordinator. Keep pending
predecessor-resolution and signed-out/missing-vault reauthentication outstanding in roadmap/handoff, with owner escalation
before any unresolved credential-disposition decision; accepted settled replacement is not all R09 recovery.
