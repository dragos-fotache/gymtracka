# R09a — Durable private account authority and preparation journal

**Accepted first slice of reduced R09.** This records the normative delivered implementation boundary; current status
and future authority belong to the active handoff/roadmap.
No authenticated app activation, credential mutation, import/deletion or legacy-data decision is authorized here.

## Goal and boundary

Deliver inert, explicitly constructed real Room account-authority observations and atomic private preparation/fencing.
Reuse R04c `SessionRecoveryAuthority` and the Room11 foundations; do not recreate vault, refresh, bootstrap or engine.
A stored phase is not credential/visibility permission. This slice can reserve an identity privately, but cannot write
ACTIVE or supply an account-bound engine/repository for preparation. Later R09 work owns vault coordination, prepared
promotion and admission/publication integration; R12a and core R10 still precede production activation.

Personal product scope: [P16](../../../shared-docs/docs/backend-contract/open-decisions.md#confirmed-product-decisions).
Mandatory initial sign-in, established-account offline editing/restart and same-account reauthentication are planned.
General signed-out editing, switching/sign-out and import/replace UI are deferred public workflows.
Existing local-only data disposition is unresolved. Preparation here refuses local roots without modifying them;
private fencing never deletes/imports/reassigns them. No Google/device/account action or service configuration is authorized.

## Authoritative reading and concrete inventory

- Supplied AGENTS; [architecture Room/persistence](../../architecture.md#room-database);
  [testing proportional gates](../../testing-strategy.md#proportional-coverage-and-verification-gates).
- [Two-store design](../../sync/integration-design.md#6-vault-and-room-two-stores-recoverable-journal-no-fake-atomicity),
  especially personal boundary and startup. Full vault/activation protocol remains later work.
- [R04c implemented recovery](../../sync/implementation.md#startup-recovery-seams);
  [shared credential storage/recovery](../../../shared-docs/docs/backend-contract/authentication.md#android-session-storage-and-recovery).
- Worker reads only allowed production files below; QA reads only allowed tests/prior schemas and this contract.
  Do not ingest unrelated contract routes, archives or opposite-role source. Ask coordinator for missing signatures.

Verified current runtime schema is 11 with 20 entities. `GymDatabase.MIGRATIONS` includes all adjacent 1→11 migrations;
`getInstance` copies the unchanged version-1 asset. Existing `SyncDeviceStateEntity` columns are singleton `id`, nullable
`active_account_id`, nullable `cached_account_id`, nonnegative `session_generation` default0 and `cache_state` default
`local_only`. Existing stores fence **active account and generation**, not a new authority row. Preserve their APIs and
behavior. Inert preparation must therefore clear active account and advance generation atomically, not set a provisional
active account. Do not construct/rebind the live signed-out repository at this private boundary.

Existing `sync_account_cache` holds account PK, baseline_complete(default0), cache_epoch(default0), projection_dirty
(default0), materialized_epoch(default0). Complete canonical baseline is not by itself visible activation.
Existing recovery models (all internal):

```kotlin
enum class RecoveryRoomPhase { SIGNED_OUT, PREPARING, ACTIVE, TRANSITIONING }
data class RecoveryIdentity(val binding: ServiceSessionBinding, val operationId: String)
data class RecoveryRoomSnapshot(
    val revision: Long,
    val sessionGeneration: Long,
    val cachedAccountId: String?,
    val phase: RecoveryRoomPhase,
    val identity: RecoveryIdentity?,
    val baselineComplete: Boolean,
)
fun interface SessionRecoveryAuthority { suspend fun read(): RecoveryRoomSnapshot }
```

`ServiceOrigin.parse(String)` strictly validates HTTPS root-only service identity; public properties scheme, host, port
are canonical, equality is semantic and toString is redacted. `ServiceSessionBinding(origin,accountId,generation)` requires
canonical lowercase UUID account and positive generation. `RecoveryIdentity` requires canonical lowercase operation UUID.
SIGNED_OUT snapshot requires null identity and baselineComplete=false; cached private account may remain. Other phases
require identity generation/account to match snapshot generation/cached account. Revision/generation are nonnegative.
`SessionRecoveryCoordinator(origin,vault,authority,authApi,clockMillis,uuidProvider).reconcile()` is read-only: it locks
PREPARING/TRANSITIONING, and only offers ACTIVE for complete ACTIVE authority plus matching ACTIVE vault, unchanged
through repeated reads. It does not check expiry for offline eligibility. None is constructed by the app.

## Schema12 — additive, no fabricated authority

Add `SyncSessionAuthorityEntity` in `data/gym/SyncSessionAuthorityEntities.kt`, one independent table
`sync_session_authority` with no FKs/indexes/domain credentials:

| Column | SQL type / nullability |
|---|---|
| id | INTEGER NOT NULL primary key; only id1 accepted by entity/runtime |
| revision | INTEGER NOT NULL |
| session_generation | INTEGER NOT NULL |
| phase | TEXT NOT NULL |
| service_origin | TEXT nullable |
| account_id | TEXT nullable |
| operation_id | TEXT nullable |

No SQL defaults are needed. Entity constructor properties: `id: Int = 1`, `revision: Long`,
`sessionGeneration: Long`, `phase: String`, `serviceOrigin: String?`, `accountId: String?`, `operationId: String?`.
Redact entity display. A new narrow `SyncSessionAuthorityDao` supplies read plus insert/update primitives required by the
store, never app-facing activation. Register `syncSessionAuthorityDao()` on GymDatabase.
`SessionAuthorityStorageMigration` is an internal Migration(11,12); create only this empty table. Bump Room to12, append
migration and export12.json through KSP. All old tables/rows/schema/sequence, exported1–11 and bundled asset bytes stay
unchanged. Never infer origin/operation or ACTIVE authority from old device/cache rows. Existing legacy active rows
remain physically preserved but cannot become recovered account authority merely through migration.

## Application-facing seam (inert; internal)

```kotlin
class RoomSessionAuthority(private val database: GymDatabase) : SessionRecoveryAuthority {
    override suspend fun read(): RecoveryRoomSnapshot
    suspend fun beginPreparation(
        expected: RecoveryRoomSnapshot,
        origin: ServiceOrigin,
        accountId: String,
        operationId: String,
    ): RecoveryRoomSnapshot
    suspend fun fencePrivate(expected: RecoveryRoomSnapshot): RecoveryRoomSnapshot
}
enum class RoomSessionAuthorityFailure {
    INVALID_INPUT, CORRUPT_AUTHORITY, STALE_AUTHORITY, ACCOUNT_MISMATCH,
    LOCAL_DATA_REQUIRES_DECISION, COUNTER_OVERFLOW, UNAVAILABLE,
}
class RoomSessionAuthorityException(val failure: RoomSessionAuthorityFailure) : RuntimeException
```

Each exception has fixed message `Room session authority failure`, null cause and disabled suppression; display includes
only closed failure. Models/adapter displays never echo account/origin/operation or raw SQL/error material.
No secrets, clocks, UUID generation, network, vault I/O or app-wide coroutine scope. Cancellation propagates unchanged
while caller remains active; actual caller cancellation takes precedence. Transaction cancellation/failure is not a
rollback/success claim after an uncertain return: callers must reread durable state. No process success cache.

### Strict transactional observation

Read new authority, device and selected cache together in one real `withTransaction`. Read all singleton rows (not only
WHERE id1); extra singleton rows, unexpected SQLite storage types, negative/fractional/text counters, unknown phases,
invalid nullable shapes/UUID/origin or inconsistent device/journal identity are CORRUPT_AUTHORITY. Do not coerce REAL
or TEXT numeric values into counters/booleans; inspect cursor types or equivalent strict raw observations. Cache Boolean
must be integer0/1 and epoch counters nonnegative with materialized_epoch<=cache_epoch; selected row identities exact.

- No authority row: only a missing device or a valid device with active_account_id=null is signed-out observation
  revision0, actual generation(default0 if missing), retained cached account, no identity, baselineComplete=false.
  A retained cached account must be a canonical UUID. An old active device without journal fails closed as corruption;
  do not change, adopt or clear it. Arbitrary legacy cache_state is not authority and need not be an enum here.
- Journal rows require revision>=1 and matching device row/session_generation. Allowed phase strings exactly
  `signed_out`, `preparing`, `active`, `transitioning`.
- signed_out: operation null, device active null, journal account_id exactly equals device cached account (nullable).
  service_origin is retained canonical cache provenance when known, not an active identity; it may be null for an
  unbound/legacy private cache. A nonnull origin with no cached account is corrupt. Return no identity/baseline even for
  retained cache. Private fencing must not erase known origin provenance.
- preparing/transitioning: positive generation, all three identity fields valid, cached account equals account, device
  active null. Return the bound identity; baselineComplete=false (no offline/ordinary scope eligibility).
- active: positive generation, valid identity, device active and cached account both equal journal account. Derive
  baselineComplete from matching selected cache's baseline_complete (false if absent). Do not infer it from cursor or
  cache_state. Reader supports later durable ACTIVE state; **no method in R09a writes ACTIVE**. Test fixtures may seed
  a full ACTIVE tuple directly to verify reader/reconciler behavior. Stored ACTIVE alone is never vault authorization.
- service_origin is canonical `https://<host>:<effective-port>` (IPv6 bracketed); parsing and reserialization must equal
  retained text exactly. No origin invention, cleartext or path/query. Other selected/foreign tables remain unchanged.

### beginPreparation

Validate supplied account/operation UUIDs before writes. In the same transaction observe current authority and require
exact equality with expected; mismatch is STALE_AUTHORITY. Only SIGNED_OUT is admitted in this slice; already PREPARING,
TRANSITIONING or ACTIVE is STALE_AUTHORITY. This is reservation, not the whole same-account reauth implementation.
If cached account is nonnull and differs from target, ACCOUNT_MISMATCH without changes. If a cached account exists,
  its durable journal origin must be known and exactly equal supplied origin; missing legacy provenance or another origin
  is ACCOUNT_MISMATCH, even with the same account UUID. No switching, origin reconfiguration, cleanup or provenance
  invention. A fresh missing/null cached account can reserve the supplied canonical origin.
If ANY local-only workout/routine root (account_id IS NULL, including drafts/deleted rows) or user-created exercise root
(is_user_created=1 AND account_id IS NULL, including deleted rows) exists, LOCAL_DATA_REQUIRES_DECISION without changes.
Bundled catalogue alone never blocks. Unknown/corrupt local graph must not be imported or deleted.
Checked increment revision and session generation; either at Long.MAX_VALUE fails COUNTER_OVERFLOW without writes.
Atomically create/update journal preparing with target binding/new operation and set device singleton to
active=null,cached=target,generation=new,cache_state=`preparing`. No other table changes. Recheck exact post-write
journal/device and snapshot before commit (including affected-count validation). Return preparing snapshot. Concurrent
reservations using one expected observation cannot both win. A stale default repository generation is fenced afterward.

### fencePrivate

Observe and require exact expected equality in one transaction; checked increment revision/generation. Write signed_out
journal with account_id=same cachedAccountId, retained known service_origin (null only if never established), null
operation_id and device active=null, same cachedAccountId, new generation, cache_state=`private`.
Works from any valid recognized phase, including fresh signed-out; it is durable private invalidation, NOT successful
sign-out, vault cleanup, pending-operation abandonment or account deletion. Preserve all cached/domain/outbox/stage/
cursor/baseline/watermark/catalogue data and exact uncertain bytes. Recheck exact post-write rows before commit. Return
SIGNED_OUT snapshot with false baseline. No local-root refusal for private fencing because it does not touch roots.

## Independent contributors and file ownership

Coordinator owns this spec, documentation/integration/final review, Gradle slots, emulator, acceptance and commit.
Preserve earlier owner-requested doc changes in both repositories; they are current product/workflow context, not user
files to reset. Contributors do not stage/commit, use Gradle without a grant, or touch backend/config/personal AVDs.
All reports fire-and-forget to the verified live `gym-coordinator` name; no --wait, agent wait or output polling.

**Worker production read/write:** new `data/gym/SyncSessionAuthorityEntities.kt`, `SyncSessionAuthorityDao.kt`,
`SessionAuthorityStorageMigration.kt`; `data/gym/GymDatabase.kt`; new `data/sync/session/RoomSessionAuthority.kt` and
`RoomSessionAuthorityFailures.kt`. May split private helper(s) into `RoomSessionAuthority*.kt` only.
Additional read-only: `data/gym/SyncEntities.kt`, `SyncDao.kt`, `SyncCanonicalEntities.kt`, `SyncCanonicalDao.kt`,
`SnapshotStorageMigration.kt`; `data/sync/session/SessionRecoveryModels.kt`, `SessionRecoveryFailures.kt`,
`SessionRecoveryCoordinator.kt`; `data/sync/http/ServiceOrigin.kt`, `ServiceSessionBinding.kt`;
`data/sync/model/WorkoutValidation.kt` if needed for requireWorkoutUuid (ask if actual defining path differs).
Paths are under app/src/main/java/com/dragosfotache/gymtracka/. No tests/fixtures/reports/QA source, including LSP or diffs.
Worker does not inspect schemas/tests as source. Coordinator owns generated12.json verification; KSP may create it.

**QA test read/write:** new `app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/session/RoomSessionAuthorityTest.kt`
and narrowly named `RoomSessionAuthority*Test*.kt`; new `data/gym/SessionAuthorityStorageMigrationTest.kt`;
existing `data/gym/GymDatabaseMigrationTest.kt` updated current version loop1..11→12 and copied-asset empty-new-table check.
Read-only any existing tests under app/src/androidTest and app/src/test; exported schemas1–11 and docs expressly linked
above (no production declarations, source/diffs, new schema12 or Worker work/decompiled classes). Prefer raw SQL
independent literals and existing test harnesses. Only use contract signatures here; ask coordinator for missing facts.

## Risk-selected verification and stop

High-risk persistence/authorization/schema boundary, **not activation**. No full new protocol matrix.
Distinct independent real Room obligations:
1. Fresh read and empty preparation/private fence with exactly expected snapshots; bundled-only permitted. Real Room
   reader assertions verify PREPARING/private snapshot shapes and seeded ACTIVE with complete/incomplete selected cache.
   Existing R04c JVM tests independently cover reconciler privacy precedence/no vault read for preparation and matching
   ACTIVE vault offline eligibility even with expired grants; reuse those in full offline JVM, rather than importing
   src/test fakes into androidTest or repeating the vault/refresh matrix. No Android-to-JVM test-source dependency.
2. Separate representative local workout/routine/custom presence refuses preparation with exact graph/byte preservation;
   retained different account or same UUID at a different/unknown origin refuses; known origin survives private fence
   and file reopen. No general import UI or destructive action.
3. Exact expected snapshot, concurrent reservation, checked revision/generation overflow; stale old repository fence fails.
4. Atomic journal+device: abort trigger on second write/post-write mismatch rolls back; cancellation at real transaction
   boundary/reopen observes only committed state. Reopen both preparing/private states; no in-memory success dependency.
5. Malformed raw storage representative types/counters/phases/binding/device/selected baseline fail closed, preserve data;
   no-journal legacy active and orphan vault cannot become ACTIVE. Include valid seeded ACTIVE + incomplete cache.
6. Preserve exact pending/frozen/rejected/uncertain outbox/cursor/stage/canonical/watermark/domain/foreign/cache/catalogue
   rows through private fence; no callbacks can keep old authority after generation change. Use one rich fixture.
7. Schema11→12 populated rows/all prior table+index/FK SQL and sqlite_sequence unchanged (including corrupt/exact outbox,
   retained legacy active device and nonempty stage); new table empty, only new schema addition. Reuse prior schema/test
   helpers without importing production encoders as expected results. All supported1..11 starts→12 and copied real v1.

Iteration: coordinator-serialized compileDebugKotlin/KSP, compileDebugAndroidTestKotlin; focused new Room/migration tests.
Acceptance: fresh full offline JVM, all new Room tests + GymDatabaseMigrationTest + SnapshotStorageMigrationTest +
SyncFoundationTest + SessionVaultAndroidTest; every-supported-start/copied asset gates mandatory.
Assembly/lint/APK/schema inspection are required at this security/schema slice: ensure no new secrets/config/backup
inclusions, asset/export1–11 byte preservation, 21 entities/new table only. API37 test-owned emulator is available;
API24/live Google/phone/backend are not this slice's gates and remain unverified milestone obligations.
Full unfiltered instrumentation and real Room two-store/HTTP convergence are deferred to R09/R10 integration before
activation, not claimed here. No backend gate is needed for a non-network adapter. git diff --check in both trees.

Exact stop: accepted inert schema/storage/read/preparation/private-fence APIs and independent selected gates, no app/vault
activation or data disposition. Archive spec/review, update durable facts/handoff/roadmap, scoped local commit then rotate
only to the next eligible reduced R09 slice under the short-prompt rule. Escalate a blocker; legacy-data choice cannot be
silently selected by the successor. Existing owner stop is lifted only for the explicitly started R09 workflow, not public
branches or live/device/deployment actions. No acceptance before required gates actually pass.
