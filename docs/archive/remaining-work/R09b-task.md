# R09b — prepared vault/Room coordination and crash recovery

## Authority, goal and exact stop

Released next reduced-personal R09 slice under [workflow](../../coordinator-workflow.md). Add one **inert** coordinator that
reserves R09a private Room preparation, durably writes one returned service grant as PREPARED, and rereads both stores.
Recognize interrupted reservation/write on restart without pretending the stores share a transaction.

Stop at a stable, nonsecret **private prepared observation**. It is NOT a lease, engine admission, repository session,
bootstrap, publication, ACTIVE transition, promotion, refresh, account UI or app construction. No production wiring.
R12a real edit/timer barriers and core R10 convergence still precede live activation. Prepared promotion and account-bound
preparation-engine integration are subsequent bounded work. Existing local-only roots refuse unchanged; no legacy
import/delete/reassignment, account switching/sign-out, Google action, backend operation or private configuration.
Replacement of occupied credentials for reauthentication is deliberately NOT delivered here: preserve them and every
uncertain pending attempt; later bounded reauthentication must settle that protocol, not infer discard permission.

## Normative dependencies (read only the applicable sections)

- [Implemented vault/refresh/recovery](../../sync/implementation.md#encrypted-vault-and-refresh) and
  [R09a authority](../../sync/implementation.md#durable-private-room-account-authority).
- [Two-store design](../../sync/integration-design.md#6-vault-and-room-two-stores-recoverable-journal-no-fake-atomicity).
- Shared [authentication credential lifetimes/replay and Android storage](../../../shared-docs/docs/backend-contract/authentication.md),
  [account/privacy model](../../../shared-docs/docs/backend-contract/sync.md#principle-and-account-model),
  [authentication handoff](../../../shared-docs/docs/backend-contract/android-client-protocol.md#authentication-handoff),
  and P16 in [confirmed product decisions](../../../shared-docs/docs/backend-contract/open-decisions.md#confirmed-product-decisions).
- [Proportional testing](../../testing-strategy.md#proportional-coverage-and-verification-gates).

No wire/schema changes. Runtime Room12, exported schemas1–12 and bundled v1 asset stay byte-identical. Reuse strict
R09a transactional singleton/device/cache observations, origin provenance, local-root refusal, checked revision/generation,
exact updates/post-write checks and reservation rollback. No recreation of their machinery.

## Concrete API contract

All new APIs are `internal`, package `com.dragosfotache.gymtracka.data.sync.session`.

```kotlin
interface SessionPreparationAuthority : SessionRecoveryAuthority {
    suspend fun beginPreparation(
        expected: RecoveryRoomSnapshot,
        origin: ServiceOrigin,
        accountId: String,
        operationId: String,
    ): RecoveryRoomSnapshot
}
// Existing RoomSessionAuthority implements this interface; existing method becomes override.

class SessionPreparationCoordinator(
    origin: ServiceOrigin,
    authority: SessionPreparationAuthority,
    vault: SessionVault,
) {
    suspend fun prepare(
        expected: RecoveryRoomSnapshot,
        operationId: String,
        session: ServiceSession,
    ): PreparedSessionObservation
    suspend fun resume(
        expected: RecoveryRoomSnapshot,
        session: ServiceSession,
    ): PreparedSessionObservation
    suspend fun recover(): SessionPreparationRecovery
    fun invalidate()
}

data class PreparedSessionObservation(
    val room: RecoveryRoomSnapshot,
    val vaultRevision: Long,
)
sealed interface SessionPreparationRecovery {
    data object NotPreparing : SessionPreparationRecovery
    data class NeedsGrant(val room: RecoveryRoomSnapshot) : SessionPreparationRecovery
    data class Prepared(val observation: PreparedSessionObservation) : SessionPreparationRecovery
    data class Blocked(val failure: SessionPreparationFailure) : SessionPreparationRecovery
}
enum class SessionPreparationFailure {
    INVALID_INPUT, NOT_PREPARING, STALE_AUTHORITY, IDENTITY_MISMATCH,
    VAULT_OCCUPIED, AUTHORITY_UNAVAILABLE, VAULT_UNAVAILABLE,
    COUNTER_OVERFLOW, LOCAL_DATA_REQUIRES_DECISION, ACCOUNT_MISMATCH, STALE_OPERATION,
}
class SessionPreparationException(val failure: SessionPreparationFailure) : RuntimeException
```

`PreparedSessionObservation` constructor validates PREPARING, nonnull identity, baselineComplete=false and positive
vaultRevision; invalid inputs throw INVALID_INPUT. All new model/coordinator displays are fixed/redacted, including
nested room/account/origin/operation/grant. Exception has fixed message, null cause and disabled suppression (as existing
RoomSessionAuthorityException). No raw I/O/SQL/credential messages in errors. A report is advisory and not reusable write
or network authority; mutable stores must always be freshly fenced by their actual transactional operations.

One application owner constructs one coordinator per physical Room/vault pair. All suspend operations serialize through
one coordinator mutex. Each invocation creates a fresh opaque process fence **before** waiting for the mutex; newest
invocation retires older work. `invalidate()` immediately retires in-flight work without waiting for I/O/mutex and performs
no I/O, durable fencing or cleanup. Check caller activity then current process fence before/after each seam, before writes
and before returning. Cancellation takes precedence over stale/error mapping; an injected CancellationException in an
otherwise active caller escapes as the identical object. A stale invocation throws STALE_OPERATION, not a success/report
that might overwrite a newer result. No cached success/state or process token pretends to be cross-store authority.
Vault CONFLICT maps to IDENTITY_MISMATCH, REVISION_EXHAUSTED to COUNTER_OVERFLOW, other vault faults to VAULT_UNAVAILABLE.
Wrong full authority reread maps to STALE_AUTHORITY; wrong full vault reread/CAS result maps to IDENTITY_MISMATCH.
A matching vault binding/operation with ACTIVE or another non-PREPARED phase also maps to IDENTITY_MISMATCH.
NOT_PREPARING is reserved/unused; non-PREPARING Room uses the NotPreparing result, not that failure.

## Preparation protocol

### `prepare` from signed-out

1. Validate canonical lowercase operation UUID and SIGNED_OUT expected phase before I/O; other phases => INVALID_INPUT.
   Account comes ONLY from `session.account.accountId`, never Google identity/display name. No clock/UUID generation.
2. Read authority and require exact full expected snapshot (STALE_AUTHORITY). Read vault. Its record must be null;
   an empty revision0 or positive tombstone is allowed. ANY occupied record, including same-account, prepared, active,
   expired or pending, refuses VAULT_OCCUPIED with no Room/vault writes. Unreadable vault refuses unchanged.
   Preflight checked Room revision/generation+1 and vault revision+1; exhaustion => COUNTER_OVERFLOW, never wrap.
3. Recheck exact authority/current process/cancellation before `beginPreparation(expected, origin, targetAccount, operation)`.
   Room adapter remains responsible for atomic provenance/local-root refusal/counters. Propagate its closed local-data,
   account-mismatch, stale and overflow categories; corrupt/unavailable => AUTHORITY_UNAVAILABLE.
4. Compute and require the exact returned/reread PREPARING snapshot: revision/generation+1, configured origin, grant
   account, operation, private cache target and baselineComplete=false. Never trust a generic successful seam return.
5. CAS vault using the previously loaded **empty snapshot's exact revision**, writing one VaultSessionRecord with that
   Room identity, PREPARED, the entire original grant unchanged and pendingRefresh=null. Never clear/overwrite an
   occupied record or adopt an unpaired grant. No Room lock held during vault I/O.
6. Require CAS result revision=old+1 and semantic exact intended record. Then independently reload and verify both stores
   as below; success is not the CAS return or in-memory grant alone.

A Room error/cancellation/uncertain reservation result starts no vault write. Reread via recover recognizes durable state.
An error/cancellation/uncertain vault result is NOT rollback proof: retain the exact Room operation and any committed
vault; no fencePrivate/erase/compensating CAS/retry/new generation/key is automatic. Already-started synchronous writes
may finish after invalidation/cancellation; dead operations cannot return prepared success or use their result. Durable
reread is the only recovery path; mismatched/orphan records stay intact/private.

### `resume` after a reservation-only interruption

Require PREPARING expected phase, nonnull identity, false baseline, configured origin and grant account matching the
exact expected identity (wrong phase => INVALID_INPUT; origin/account mismatch => IDENTITY_MISMATCH). Reread exact Room.
Load vault. If empty, recheck exact expected Room immediately before CAS, then write PREPARED at that empty snapshot's
checked revision, using the SAME reserved generation and
operation and caller-provided original full service grant. Do NOT reserve again, invent an exchange retry, generate IDs,
refresh or extend expiries. No credential can be recovered from Room; the external foreground caller must supply the
service grant when none was durably written.

If occupied, accept **read-only idempotence** only for exact matching PREPARED identity and semantic equal supplied full
grant. Preserve any existing pendingRefresh unchanged; do not CAS it away. Different grant, binding, operation or phase
=> IDENTITY_MISMATCH, unchanged. `resume` never changes Room. If missing-credential recovery requires a new exchange or
reauthentication of occupied credentials, that is future orchestration, not this slice.

### Stable durable rereads

For a prepared result, read Room / vault / Room / vault / Room (reuse the initial appropriate read only if it provides
this ordering after any write). Every Room snapshot must exactly equal the expected PREPARING observation; every vault
snapshot must equal its expected revision, PREPARED binding/operation/full grant and pending attempt semantically.
Pending equality compares operationId, idempotencyKey, requestJson and refreshToken, not ServiceRefreshAttempt reference
identity; preserve whitespace/bytes, original grant fields/expiry and pending data. Mismatch refuses without writes.
A mutation after the final read cannot be ruled out: the observation never authorizes later actions.

### `recover` — read-only restart classification

- Read Room first. SIGNED_OUT/ACTIVE/TRANSITIONING => NotPreparing, **no vault read or writes**, no active eligibility.
- PREPARING at another origin => Blocked(IDENTITY_MISMATCH), no vault read.
- PREPARING + empty vault: perform the same alternating stable rereads and return NeedsGrant(exact room). Empty
  revision must remain equal, including tombstones; no automatic reservation/exchange/cleanup.
- PREPARING + matching PREPARED binding/operation: stable semantic rereads => Prepared(nonsecret observation).
  Expired original grants and retained exact pending refresh are valid stored evidence, never freshness/HTTP admission.
- Missing/unreadable/mismatched/change-racing state => closed Blocked reason (read faults mapped per store). No automatic
  promotion/fence/cleanup. Wrong/mismatched prepared/active vault refuses even with a complete cache.
- Cancellation and retiring process fences propagate as exceptions, not Blocked; do not reset newer work.

## Concrete existing signatures/harness facts for independent QA

QA MUST NOT inspect production; these are supplied contracts, not permission to read declarations.

- `ServiceOrigin.parse(String)` canonical HTTPS; `ServiceSessionBinding(origin, accountId, sessionGeneration)` requires
  positive generation. `RecoveryIdentity(binding, operationId)`; `RecoveryRoomSnapshot(revision, sessionGeneration,
  cachedAccountId, phase, identity, baselineComplete)`, phases SIGNED_OUT/PREPARING/ACTIVE/TRANSITIONING. Signed-out has
  null identity/false baseline. PREPARING identity generation/account matches snapshot and remains baseline=false.
- `ServiceSession(account: ServiceAccountRef, accessToken: OpaqueToken, accessExpiresAtMillis: Long,
  refreshToken: OpaqueToken, refreshExpiresAtMillis: Long)`; account ref `(accountId, displayName)` and token `(value)`.
  Immutable equality is full value equality. Expiry values are exact representable wire epoch milliseconds.
- `SessionVault.load(): VaultSnapshot`; `compareAndSet(expectedRevision: Long, record: VaultSessionRecord?): VaultSnapshot`.
  `VaultSnapshot(revision, record)`; `VaultSessionRecord(binding, operationId, phase, session, pendingRefresh=null)`;
  phases PREPARED/ACTIVE; `VaultPendingRefresh(operationId, attempt)`; `ServiceRefreshAttempt.fromJson(key, body)` exposes
  idempotencyKey/requestJson/refreshToken. Its equality is referential, so independently restored attempts must compare
  exact fields. Existing EncryptedSessionVault(storage, cipher, ioDispatcher, mutex=Mutex()) implements the actual codec/CAS.
- JVM QA may reuse RefreshTestFixtures, SessionRecoveryTestFixtures, VaultTestFixtures, FakeSessionVault,
  InMemoryVaultFileStorage/IdentityVaultCipher and realVault from the allowed existing test files. Implement a small
  hand-written SessionPreparationAuthority double with deterministic gates; do not mock DAOs or recreate a simulator.
- Android: `Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java).build()` or file-backed `databaseBuilder`;
  `RoomSessionAuthority(database)` provides read/beginPreparation/fencePrivate. Reopening a current-version test database
  needs no migration change. Reuse raw SQL and whole-table snapshot patterns in allowed R09a tests. Singleton
  journal columns: id, revision, session_generation, phase, service_origin, account_id, operation_id; phases lowercase.
  Device columns: id, active_account_id, cached_account_id, session_generation, cache_state. R09a preparation writes
  preparing/private identity with null active account; no extra journal phase/column is introduced.
- `AndroidSessionVault.createForTesting(context, namespace)` gives real Android encrypted/Keystore/file vault with shared
  process mutex. Namespace MUST start `gymtracka-test-`; corresponding alias is
  `com.dragosfotache.gymtracka.session.` + namespace. Clean ONLY test-owned namespace/alias and database. Recreate
  factory/database/coordinator to prove restart; no production factory/alias or personal app data. Existing Android
  SessionVaultAndroidTest supplies cleanup patterns; existing authority tests supply literal SQL preservation fixtures.
- Additional confirmed closed/fault-harness facts: `RoomSessionAuthorityException(failure: RoomSessionAuthorityFailure)`;
  `EncryptedSessionVault(storage, cipher, ioDispatcher=Dispatchers.IO, mutex=Mutex())` permits a two-argument call.
  `AndroidVaultFileStorage(context, namespace, faults=VaultWriteFaults {})` is permitted ONLY with a test-owned namespace
  here. `VaultWriteFaults` is a fun interface `fun before(stage: VaultWriteStage)`; stages OPEN_TEMP, SYNC_TEMP, RENAME,
  SYNC_DIRECTORY. Fault at SYNC_DIRECTORY is after rename, before directory fsync: uncertain durability on actual crash,
  but without a crash the newly renamed complete record must be immediately rereadable. Compose it with
  `AesGcmVaultCipher(AndroidKeystoreVaultKeyProvider(alias))` for the exact test-owned alias. No production namespace.
  SessionPreparationException's message is fixed/nonempty/redacted; tests need not couple to its exact wording.

## Independent read/write ownership

Both contributors read this spec, supplied AGENTS, workflow role/report rules, proportional testing and the task-specific
normative sections linked above. No broad archive, whole shared-contract map or opposite-role source reads.

**Worker production writes only**:

- `app/src/main/java/com/dragosfotache/gymtracka/data/sync/session/SessionPreparationModels.kt` (new interface/models)
- `app/src/main/java/com/dragosfotache/gymtracka/data/sync/session/SessionPreparationFailures.kt` (new)
- `app/src/main/java/com/dragosfotache/gymtracka/data/sync/session/SessionPreparationCoordinator.kt` (new)
- `app/src/main/java/com/dragosfotache/gymtracka/data/sync/session/RoomSessionAuthority.kt` (implements/override only)

Worker may read existing production files in `data/sync/session/`, `data/sync/api/ServiceAuth.kt`,
`data/sync/api/ServiceAuthRequests.kt`, `data/sync/http/ServiceOrigin.kt`, `data/sync/http/ServiceSessionBinding.kt`,
`data/sync/model/` validators needed by constructors, and `data/gym/` GymDatabase/SyncSessionAuthorityDao/
SyncSessionAuthorityEntities/SyncMetadataEntities. No tests/fixtures/reports/QA work or unrestricted LSP searches.

**QA writes only** new test files:

- `app/src/test/java/com/dragosfotache/gymtracka/data/sync/session/SessionPreparationCoordinatorTest.kt`
- `app/src/test/java/com/dragosfotache/gymtracka/data/sync/session/SessionPreparationTestFixtures.kt`
- `app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/session/SessionPreparationRoomVaultTest.kt`

QA may read existing test-only files in matching JVM/Android `data/sync/session/` directories, plus test-only
`data/sync/PushClaimTestSupport.kt` if helpful. No production declarations/implementations/diffs, Worker work, generated
source, decompiled classes or schema JSON as a shortcut. API/build errors: request missing facts from coordinator.
Do not weaken independent assertions to match production. Neither contributor edits docs/build/manifest/schema/assets
or coordinator integration files. Coordinator alone reads both layers, integrates and runs acceptance/packaging gates.

## Risk-selected test obligations and gates

High-risk authentication/persistence boundary, but no schema, live activation, transport or visible projection changes.
Each addition proves a distinct new defect; reuse accepted vault codec/crypto/file/refresh and authority/migration tests.

1. JVM happy preparation and tombstone CAS: exact +1 counters, PREPARED full grant unchanged, stable nonsecret result;
   resume missing grant under exact reserved operation WITHOUT new Room reservation; matching resume performs no CAS.
2. Crash/uncertain seams: reservation commit then throw/cancel => no vault write, fresh recover NeedsGrant; vault commit
   then throw/cancel => no compensating write, fresh recover Prepared. Real EncryptedSessionVault recreated over retained
   storage proves semantic pending equality and exact request/expiry preservation after restart.
3. Representative refusal/no-write cases: wrong expected/phase, canonical operation validation before I/O, legacy
   roots/provenance/another account via closed authority failure, occupied orphan/pending/active credentials, stale vault
   CAS/tombstone conflict, origin/account/generation/operation/phase mismatch, overflow; no arbitrary exhaustive matrix.
4. Stable rereads detect changing Room and vault (including full grant/pending exact body or revision); unreadable/fault
   paths stay closed/redacted. Recover non-PREPARING never probes vault. Expired grant never triggers clock/network.
5. Deterministic invalidation/newest invocation/caller cancellation at pre-reserve, pre-CAS and post-commit boundaries:
   late committed bytes remain private, no prepared result from dead invocation, no cleanup, cancellation identity.
6. Android actual Room + actual test-owned Keystore/file vault: successful reservation+write+factory/database reopen;
   reservation-only recovery/resume; wrapped postcommit vault failure and fresh recovery; durable fence during delayed
   vault write rejects result and leaves preserved orphan private. Use real Room, not DAO mocks. Include an occupied
   exact pending record and wrong binding refusing unchanged; assert whole prior domain/outbox/cursor/stage/cache/
   canonical/catalogue fixture unchanged except intended authority/device reservation. Reuse a representative rich
   R09a literal fixture/preservation approach rather than multiply every aggregate family.

Iteration: serialized coordinator-granted slot for production compile and focused new JVM tests; Android test compilation.
Acceptance: executed affected session JVM tests + **fresh full offline JVM**, executed focused Android session package
(new Room/vault integration and existing authority/vault regressions), compile/KSP, debug assembly/lint and scoped APK
secret/test-helper/backup-exclusion checks because credential writing is new. Inspect executed XML classes/names/counts/
failures/errors/skips; cached/filtered evidence is labeled honestly. Both diff whitespace, asset/schema byte preservation,
complete new-file diff/security/source-independent review required. No schema change => no new migration tests; existing
migration snapshots remain untouched. If schema changes become necessary, STOP and escalate/revise before work.

Full unfiltered instrumentation, real two-store engine/backend convergence and live finite-edit/timer-barrier/account
journeys remain required before R09/R10/R12 production activation; API24/OEM transfer/hosted CI/real Google/deployment
remain separate outstanding gates. No backend gate is relevant to this network-free slice. Missing declared Android gate
blocks acceptance; do not substitute compilation or silently defer it.

## Reporting and build ownership

Fresh native `meta/muse-spark-1.3-contributor`/high Worker and QA sessions, one per role for this slice. Report completion,
questions and blockers asynchronously with `herdr agent prompt gym-coordinator "..."` (NO --wait, agent wait or polling).
No Gradle without an explicit coordinator slot, no concurrent builds, no user-focus changes, no private/global config.
Do not self-accept or commit. Stop after your allowed deliverables and report; coordinator owns integration/final review.
