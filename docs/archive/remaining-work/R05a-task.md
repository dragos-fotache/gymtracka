# R05a — Canonical storage and capture watermarks

Authorized scope: the first R05 slice, storage/capture only. This specification is normative for this slice;
[approved design](../../sync/integration-design.md#2-canonical-state-versus-local-intent-account-wide-protection-first)
and the unchanged [shared contract](../../../shared-docs/docs/backend-contract/README.md) supply its invariants.

## Goal and stop

Add independent account-keyed canonical/deletion storage and incomplete-baseline/publication bookkeeping, and make
local capture advance durable generation watermarks in the same fenced transaction as domain metadata and outbox.
Stop after schema/migration/capture tests and review. No remote batch application, revision arbitration, graph
publication, acknowledgment/claiming, snapshots, transition journal, network, UI, or production activation.
R05b/c, R06, R08 and R09 require separate authorization.

## Current inventory and dependencies

Room is v9 with exported v1–9 schemas, the unchanged bundled v1 catalogue, root metadata and independent ordered outbox.
`SyncStore.withFencedTransaction` checks the immutable account/session inside Room; all repository capture funnels
through `SyncTransaction.captureEdit`. Root generations currently live only in domain rows. All 17 mutation models,
canonical models/codecs and durable v1 wrappers already exist. No canonical writer or complete baseline exists.
R04c code/tests/docs are pre-existing working-tree changes and must remain intact. R02b and the integration design
are accepted dependencies. Do not inspect/change backend source, personal configuration or databases.

## Exact storage contract

Runtime schema becomes **v10** via a data-preserving 9→10 migration; retain all previous migrations/snapshots.
Add two tables, without domain/outbox/device foreign keys (deletion/rematerialization must not cascade evidence):

- `sync_canonical_roots`, PK `(account_id, aggregate_type, aggregate_id)`: nonnull account/type/id;
  nullable `server_revision`, `canonical_json`; nonnull `canonical_state` default `unobserved`,
  `local_generation_watermark` and `cache_epoch` default 0. Future states are `upsert`, `delete`,
  `snapshot_absent`; only future validated upserts may store a complete canonical record. Reservations store no
  revision/JSON and are **not** observed server records or visible fake roots. No inferred server deletion.
- `sync_account_cache`, account PK: `baseline_complete` false, `cache_epoch` 0,
  `projection_dirty` false, `materialized_epoch` 0. It is deliberately separate from `sync_state`, so the existing
  cursor-row replacement API cannot reset publication bookkeeping. No cursor/last-success changes in this slice.
  No run fencing token is guessed before R06's concrete claim design.

`GymDatabase.syncCanonicalDao()` provides narrow account-keyed reads (`getRoot`, `getRootsForAccount`,
`getAccountCache`) and internal insert-ignore/reservation-watermark/dirty-marker primitives. There is no remote-apply
or unrestricted entity replacement API. Reservation advancement touches only the watermark; preserve every existing
canonical field and epoch. Marking dirty touches only `projection_dirty`, inserting inert metadata if absent.

## Capture transaction contract

For each changed account root (target and unique extra affected roots):

1. Preserve existing root ownership, bundled, tombstone, duplicate-effect and session checks.
2. Read the account/type/id ledger watermark. Reject negative domain/ledger generations and checked overflow.
3. New generation is `max(domain_generation, ledger_watermark_or_0) + 1`.
4. Advance domain metadata and reserve/advance the ledger to exactly that generation, preserving canonical data.
5. Mark that account's projection dirty and persist/coalesce the exact durable request with matching affected generations.

All steps are one Room transaction with the existing fence. Payload/UUID/SQL failure, cancellation or stale fence must
roll back domain edit, metadata, watermark, dirty marker and outbox together. Coalescing remains tail-only/never-sent;
frozen/rejected/corrupt predecessors are not repaired or bypassed. No-op and signed-out edits create no canonical/cache
rows; signed-out generation behavior and no-payload/no-UUID behavior remain unchanged. A ledger survives physical domain
removal; a reintroduced row cannot reset the next captured generation. Account/type keys isolate identical UUIDs.

## Migration contract

Preserve domain rows/children, root metadata, outbox sequence/identity/version/exact bytes/state/errors, cursors/success,
device and catalogue rows. Never seed visible/local-only/bundled rows as canonical server records.
Reserve `unobserved` watermark entries for account-owned workouts/routines/customs (including tombstones), and supported
outbox target metadata; use maximum known nonnegative generations. Supported target metadata means a represented
`aggregate_type` and nonnegative row generation, independently of wrapper readability/version: reserve that original
row target without inferring server state, including corrupt/unsupported wrappers. Preserve retained target generation
history rather than resetting it if that root is reintroduced. Unknown aggregate types/negative generations are not
invented into valid reservations, and their original rows remain untouched. Also recover extra effects from **valid** durable v1
rows using the strict existing row reader, including effects whose domain rows no longer exist. Corrupt/unsupported
outbox rows remain byte-for-byte untouched; no repair or deletion, no guessing extra effects from unvalidated JSON.
Domain server revision metadata remains untouched but is not promoted into a canonical record without its server body.
Discovered root/outbox/cursor/device account identities get incomplete, conservatively dirty cache bookkeeping; an old
cursor or `cache_state` never proves a complete baseline. Pure signed-out/asset upgrades have empty new tables.

## Allowed files and security

Production: new canonical entities/DAO and migration helper under `data/gym/`, `GymDatabase.kt`,
`data/sync/store/SyncStore.kt`; no incidental repository/UI/session changes. Tests: new real-Room storage/capture tests,
`GymDatabaseMigrationTest.kt`, scoped regression assertions where useful. Export `app/schemas/.../10.json` with KSP.
Documentation: this task/review and owning architecture/sync/design/testing/roadmap/handoff indexes; update only the
runtime-version fact in root `AGENTS.md` to keep the operating guide aligned. Keep shared contract
unchanged. No credentials/proofs/configuration in new storage, errors or documentation. Asset must remain byte-identical.

## Contributor ownership and harness contract

Follow [coordinator workflow](../../coordinator-workflow.md). One fresh native Pi session each for `gym-worker` and
`gym-qa`, both `meta/muse-spark-1.3-contributor` at high reasoning. Reports to `gym-coordinator` are asynchronous
`herdr agent prompt gym-coordinator ...` (no wait/poll). No contributor Gradle slot is granted initially.

- **Worker** reads production and normative docs only; writes the three new production canonical entity/DAO/migration
  files, `GymDatabase.kt` and `SyncStore.kt`. Worker must review/adopt or replace the coordinator's unaccepted
  production drafts against this spec; cannot inspect tests, test fixtures, reports, QA changes, or coordinator test drafts.
- **QA** reads normative docs and Android/JVM test harness sources only; writes new R05a instrumentation tests and
  `GymDatabaseMigrationTest.kt` (focused assertions in existing capture tests if necessary). Do not read production
  source/declarations/diffs/decompiled output or Worker changes, including LSP. Independently author expectations from
  this contract. Coordinator's test draft is removed from the repository and must not be inspected or reused.
- **Coordinator** owns specification, shared schema export/build integration, final review, verification and docs.
  Preserve pre-existing R04c work. No automatic commit/push/next slice.

Concrete API facts supplied to QA (not permission to read production):

- `data.gym.GymDatabase` extends RoomDatabase; `syncCanonicalDao(): SyncCanonicalDao`, `syncDao(): SyncDao`,
  `syncCaptureDao(): SyncCaptureDao`, `workoutDao()`, `routineDao()`, `exerciseDao()` exist.
  `GymDatabase.MIGRATIONS: Array<Migration>` is internal and accessible from instrumentation; v10 is current.
- `SyncCanonicalRootEntity(accountId: String, aggregateType: String, aggregateId: String,
  serverRevision: Long? = null, canonicalState: String = "unobserved", canonicalJson: String? = null,
  localGenerationWatermark: Long = 0, cacheEpoch: Long = 0)`; ordinary data-class equality/copy.
- `SyncAccountCacheEntity(accountId: String, baselineComplete: Boolean = false, cacheEpoch: Long = 0,
  projectionDirty: Boolean = false, materializedEpoch: Long = 0)`; ordinary data-class equality/copy.
- DAO reads suspend: `getRoot(accountId: String, aggregateType: String, aggregateId: String):
  SyncCanonicalRootEntity?`, `getRootsForAccount(accountId: String): List<SyncCanonicalRootEntity>`,
  `getAccountCache(accountId: String): SyncAccountCacheEntity?`. Seed canonical/cache test state with literal SQL.
- `data.sync.store.RepositorySession(accountId: String?, sessionGeneration: Long)` with `SIGNED_OUT` null/0;
  `SyncStore(database: GymDatabase, session: RepositorySession = SIGNED_OUT,
  newMutationId: () -> String = UUID supplier)`; suspend generic `withFencedTransaction(block: suspend
  (SyncTransaction) -> T): T`; stale fence throws `StaleSessionException`.
- `SyncTransaction.captureEdit(rootType: AggregateType, rootId: String, kind: MutationKind, changed: Boolean,
  extraRoots: List<ExtraAffectedRoot> = emptyList(), newPayload: () -> MutationPayload): CaptureOutcome?`;
  `ExtraAffectedRoot(type: AggregateType, id: String)`; outcome has `request: DurableRequestModel?`,
  `coalesced: Boolean`, `sequence: Long?`. Request has `envelope` (mutation ID/generation/kind/type/id) and
  `affectedRoots` (type/id/localGeneration). Capture validation throws `SyncModelException`.
- Aggregate types `WORKOUT`, `ROUTINE`, `CUSTOM_EXERCISE` wire values `workout`, `routine`, `customExercise`.
  Payloads usable without children: `WorkoutStart(id, name, startedAtMillis)`, `WorkoutUpsert(input)`,
  `RoutineUpsert(input)`, `CustomExerciseUpsert(input)`, singleton `AggregateDelete`.
  `WorkoutInputModel(id, name, status, startedAtMillis, exercises)`;
  `RoutineInputModel(id, name, isDraft, exercises)`;
  `CustomExerciseInputModel(id, name, primaryMuscleGroupId, secondaryMuscleGroupIds, equipmentId,
  equipmentNumItems, category, difficultyLevel, forceType, mechanics, instructions)` (nullable optional scalar/list values).
  All under `data.sync.model`. Full durable v1 JSON format and field names are fixed in the shared contract.
- SyncDao suspend primitives: `getOutboxForAccount(accountId): List<SyncOutboxEntity>`,
  `getOutboxMutation(mutationId, accountId): SyncOutboxEntity?`, `insertOutbox(entry): Long`,
  `freezePendingMutation(mutationId, accountId): Int`, `deleteMutation(mutationId, accountId)`,
  `getSyncState(accountId): SyncStateEntity?`, `upsertSyncState(state)`, `upsertDeviceState(state)`.
  Existing test sources demonstrate constructors/fixtures; QA can use SQL for all existing domain/device/outbox rows.
- Existing `data.gym.GymDatabaseTest` test harness supplies protected in-memory `database`, `@Before` creation and
  `@After` close; QA may read/use this test harness and existing test fixtures/tests. Migrations use
  `MigrationTestHelper`, schemas as Android-test assets, `runMigrationsAndValidate(name, 10, true, *MIGRATIONS)`.

The coordinator already ran Kotlin/KSP compilation once on its drafts; that is not independent acceptance. No tests
have been executed for R05a. An isolated, blank API 37.2 test emulator is available as `emulator-5580`;
no personal AVD/device/account is part of the fixture. Coordinator alone runs Gradle until granting a serialized slot.
If API facts are insufficient, report the missing signature/behavior rather than inspecting excluded source.

## QA literal-SQL schema facts

This is coordinator-supplied normative harness information, not permission to inspect production or exported schemas.
Types below are SQLite affinities. `NN` means NOT NULL. Nullable columns have no explicit default (omission yields NULL).
All columns are listed; there are no hidden owner/proof fields. No new table has foreign keys or additional indexes.

### New v10 tables

| Table | Columns / defaults | Primary key |
|---|---|---|
| `sync_canonical_roots` | `account_id TEXT NN`, `aggregate_type TEXT NN`, `aggregate_id TEXT NN`, `server_revision INTEGER nullable`, `canonical_state TEXT NN DEFAULT 'unobserved'`, `canonical_json TEXT nullable`, `local_generation_watermark INTEGER NN DEFAULT 0`, `cache_epoch INTEGER NN DEFAULT 0` | `(account_id, aggregate_type, aggregate_id)` in that order |
| `sync_account_cache` | `account_id TEXT NN`, `baseline_complete INTEGER NN DEFAULT 0`, `cache_epoch INTEGER NN DEFAULT 0`, `projection_dirty INTEGER NN DEFAULT 0`, `materialized_epoch INTEGER NN DEFAULT 0` | `(account_id)` |

Boolean storage is INTEGER 0/1. SQL inserts specifying only the three canonical key columns, or only the cache account,
exercise all new defaults. Neither SQL defaults nor a reservation assert that the account baseline is complete.
`canonical_json` is a complete canonical **record** body for future upserts, not a durable mutation wrapper or batch.
For preservation tests seed a literal valid record and state/revision/epochs with SQL; no canonical writer exists here.

### Existing v9 support tables (unchanged in v10)

| Table | Complete columns / defaults | Keys / indexes |
|---|---|---|
| `sync_outbox` | `sequence INTEGER NN PRIMARY KEY AUTOINCREMENT`; `mutation_id TEXT NN`, `account_id TEXT NN`, `aggregate_type TEXT NN`, `aggregate_id TEXT NN`, `mutation_type TEXT NN`, `local_generation INTEGER NN`, `payload_version INTEGER NN DEFAULT 1`, `payload_json TEXT NN`, `state TEXT NN DEFAULT 'pending'`, `attempt_count INTEGER NN DEFAULT 0`, `retry_after_millis INTEGER nullable`, `error_code TEXT nullable`, `error_message TEXT nullable` | Unique index `index_sync_outbox_mutation_id(mutation_id)`; indexes `index_sync_outbox_account_id_sequence(account_id, sequence)` and `index_sync_outbox_account_id_aggregate_type_aggregate_id(account_id, aggregate_type, aggregate_id)` |
| `sync_state` | `account_id TEXT NN`, `cursor TEXT nullable`, `last_success_millis INTEGER nullable` | PK `(account_id)` |
| `sync_device_state` | `id INTEGER NN`, `active_account_id TEXT nullable`, `cached_account_id TEXT nullable`, `session_generation INTEGER NN DEFAULT 0`, `cache_state TEXT NN DEFAULT 'local_only'` | PK `(id)`; seed singleton `id = 1` (there is no SQL id default) |
| `catalogue_state` | `id INTEGER NN`, `version TEXT nullable` | PK `(id)`; seed singleton `id = 1` (no SQL id default) |

No support-table foreign keys. The outbox unique mutation ID index is physical/global, while all reads/edits are
account-scoped. Literal v9 fixture inserts may specify positive sequence values explicitly to prove order/identity
preservation; do not infer/recompute sequence from UUID sorting or use raw payload as an HTTP body.

### Existing v9 roots (unchanged in v10)

Every root has PK `(id)` and the five sync columns:
`account_id TEXT nullable`, `server_revision INTEGER nullable`, `local_generation INTEGER NN DEFAULT 0`,
`sync_state TEXT NN DEFAULT 'local_only'`, `deleted_at_millis INTEGER nullable`.
Each has index `index_<table>_account_id_deleted_at_millis(account_id, deleted_at_millis)`.
The remaining **complete** business columns are:

| Root table | Business columns / defaults |
|---|---|
| `workouts` | `id TEXT NN`, `name TEXT NN DEFAULT ''`, `started_at_millis INTEGER NN`, `finished_at_millis INTEGER nullable`, `status TEXT NN DEFAULT 'active'` |
| `routines` | `id TEXT NN`, `name TEXT NN`, `is_draft INTEGER NN DEFAULT 0` |
| `exercises` | `id TEXT NN`, `name TEXT NN`, `type TEXT NN`, `difficulty_level TEXT nullable`, `force_type TEXT nullable`, `mechanics TEXT nullable`, `category TEXT nullable`, `instructions TEXT nullable`, `is_user_created INTEGER NN DEFAULT 0` |

No root-table foreign keys. Relationship/child fixture SQL is already documented by the allowed existing migration/test
harness sources; those tables do not change. Custom root seeding must set `is_user_created = 1`; otherwise it is bundled
and cannot be captured. Routine drafts set `is_draft = 1`. Account tombstones remain real original roots, not fake server
placeholders. Invalid/out-of-range metadata can be seeded literally to verify capture failure/rollback.

### Version and API confirmation

- Current **draft** runtime version is 10; `GymDatabase.MIGRATIONS` includes every adjacent 1→2 through 8→9 migration
  plus 9→10. Production construction registers the whole chain; KSP has exported 10.json. This is an API/schema fact,
  **not** evidence that any all-start/asset migration test has passed. QA must prove those gates independently.
- `SyncCanonicalDao` public reads are exactly the three suspend signatures listed above: `getRoot`,
  `getRootsForAccount` (ordered by aggregate_type then aggregate_id), `getAccountCache`. There is no public entity
  insert/replace/canonical-apply API. Its internal capture primitives are not the test seeding interface.
  **Use literal SQL to seed canonical/cache state**, then these reads or independent SQL to observe results.
- The separately owned cursor DAO and rows are unchanged. No new baseline flag is inferred from nonnull cursor,
  `last_success_millis`, active account, or device `cache_state`.

## Independent acceptance cases

Use literal IDs, JSON and expected generations/SQL facts rather than production-generated expected values:

- Empty/inert new schema; account/type key isolation; evidence survives physical root removal.
- Workout/routine/custom changed capture, no-op, signed-out, bundled/foreign/missing/stale refusal.
- Ledger above/below/equal domain generation; recreated root advances rather than resets; Long boundary and corruption.
- Tail coalescing advances ledger while reusing identity; frozen predecessor retains exact bytes and later work advances.
- Multi-root start/delete effects; failure on an extra effect and payload/outbox failure roll back all rows/dirty markers.
- Existing canonical record/epoch/cache bookkeeping survives local capture; unrelated account stays unchanged.
- Every starting version 1–9 migrates to v10 preserving available data; copied real v1 asset remains intact.
- Populated v9 upgrade: all root types, local and foreign scopes, tombstones, frozen/rejected/corrupt/unsupported outbox,
  vanished extra effect, exact replay text, nonnull cursor/success/device/catalogue; no fabricated baseline/revision/JSON.
- File-backed close/reopen demonstrates durable watermark and outbox identity.

Run Kotlin/KSP compilation, JVM regression, Android-test compilation, focused real-Room and migration instrumentation
on an isolated emulator if available, then appropriate broader device/build gates. Inspect executed test XML, distinguish
filtered/full/cache evidence, run both repository diff checks and verify v1 asset/old-schema preservation. If no isolated
device is available, record instrumentation as unverified and keep acceptance open; compilation is not a Room pass.
