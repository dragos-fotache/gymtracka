# R13a — durable legacy graph and private import capture

> Historical accepted specification, not a live assignment or resource grant. Current scope/authority lives in the
> [roadmap](../../remaining-work-roadmap.md) and [handoff](../../coordinator-handoff.md).

## Authority and stop

Owner authorized R13a and lifted the planning-only stop. This is slice one of the finite
[four-slice R13 plan](../../remaining-work-roadmap.md#remaining-r13-plan). Worker owns production, QA independently owns tests;
coordinator owns specification, integration/schema export, review, documentation and serialized acceptance execution.
Stop after a reviewed inert Room capture/recovery boundary. No HTTP, grant/vault installation, app handle, ACTIVE transition,
account UI, default-startup change, background scheduling, private configuration, real account/device action or backend work.
R13b owns delivery/definitive settlement/activation; R13c owns startup/UI; R13d closes personal-package sync journeys.

## Normative references (task-relevant sections only)

- [P16–P18](../../../shared-docs/docs/backend-contract/open-decisions.md#confirmed-product-decisions).
- [Import/cache transitions](../../../shared-docs/docs/backend-contract/android-client-protocol.md#first-sign-in-import-and-cache-transitions),
  aggregate representations, mutation kinds and ordered durable identity in that document.
- [Domain rules](../../../shared-docs/docs/backend-contract/domain-rules.md): identifiers, custom restrictions, workouts/routines.
- OpenAPI `MutationEnvelope`, `ImportWorkoutPayload`/`ImportRoutinePayload`/`ImportCustomExercisePayload`,
  custom/routine/workout input and import schemas (including referenced
  UUID, numeric and timestamp scalar schemas); use existing typed codecs/validators rather than another wire format.
- [Activation/import design](../../sync/integration-design.md#startup-and-activation) and import/replace subsection.
- [Room/capture](../../sync/implementation.md#room-and-local-capture), private authority subsection;
  [architecture Room](../../architecture.md#room-database), finite-edit admission subsection.
- [Testing policy](../../testing-strategy.md#proportional-coverage-and-verification-gates), commands and instrumentation lessons;
  [independent roles](../../coordinator-workflow.md#independent-contributor-roles).

## Verified inventory / design choice

Runtime is Room12; asset v1 and schemas1–12 are immutable inputs. Root sync metadata exists on `workouts`, `routines`,
`exercises`; children and relationships do not have account columns. Outbox is durable v1 and strictly ordered by
AUTOINCREMENT sequence; `aggregate.import` and active/finished workout import union already exist. Signed-out captures
advance local generation without DTO validation/outbox, so legacy rows must not be assumed wire-valid.

`RoomSessionAuthority.read/beginPreparation/fencePrivate` strictly fence actual device/journal/counters. Ordinary
`beginPreparation` must continue refusing local roots. Reuse it inside the same outer transaction **after** valid roots
have been associated/captured, rather than add an unconstrained bypass flag. Its transaction joins Room's outer transaction.
`SyncTransaction` has an internal constructor usable inside a real database transaction; `captureEdit(...AGGREGATE_IMPORT)`
reuses generation/watermark, durable wrapper and noncoalescible ordered append. Verify post-write rows/counts: existing
capture metadata methods alone do not detect every trigger/no-op rewrite.

For invalid source, commit only immutable source/error evidence plus `fencePrivate(expected)`; do not associate any root
or append any import. This advances the old local repository fence without inventing target Room/vault authority. A durable
legacy journal locks further ordinary signed-out reads/writes (including a freshly constructed null-account repository).
Target provenance is retained in the legacy journal, not inferred from a cache label. Existing app behavior is unchanged
unless this explicit inert boundary is invoked. There is no process success cache.

Fresh import is restricted to genuinely separate local storage: exact SIGNED_OUT, no cached account/identity and no
pre-existing account-owned roots or account cache/canonical/outbox/cursor/stage evidence. Refuse foreign/unknown account
storage without copying, deleting, rewriting or adopting it. Legitimate local generations may be positive. Nullable
account roots with nonlocal metadata, tombstones, invalid wire values/relationships or corrupted SQLite types are blocked
source, not repaired or silently excluded. Malformed singleton authority is refused by the existing strict reader.

Empty bundled-only storage returns `NoLocalData`, with no journal, device/journal mutation, UUID allocation or import.
R13b will use existing empty-install preparation. No artificial empty migration record is created.

## Frozen source and schema13

Add only two independent tables, no foreign keys to domain/outbox (evidence must survive their later removal), additive
12→13 migration leaving all old tables, schema definitions, rows, sequences, schemas1–12 and asset bytes unchanged:

`sync_legacy_import` singleton:

| Column | SQLite / nullability |
|---|---|
| id | INTEGER NOT NULL primary key, API requires 1 |
| evidence_version | INTEGER NOT NULL, supported value 1 |
| service_origin | TEXT NOT NULL, canonical HTTPS origin with explicit effective port (`https://sync.example.test:443`) |
| account_id | TEXT NOT NULL, canonical first service-confirmed UUID |
| operation_id | TEXT NOT NULL, canonical capture/activation operation UUID |
| source_revision | INTEGER NOT NULL, original authority revision |
| source_session_generation | INTEGER NOT NULL, original local generation fence |
| room_revision | INTEGER NOT NULL, private post-capture/fence revision |
| room_session_generation | INTEGER NOT NULL, private post-capture/fence generation |
| phase | TEXT NOT NULL, `captured` or `blocked` (neither means remotely accepted) |
| source_json | TEXT NOT NULL, immutable raw source evidence |
| import_count | INTEGER NOT NULL, captured number (0 for blocked) |
| error_code | TEXT nullable, closed code for blocked, null for captured |

`sync_legacy_import_entries`:

| Column | SQLite / nullability |
|---|---|
| ordinal | INTEGER NOT NULL primary key, consecutive zero-based |
| aggregate_type | TEXT NOT NULL, wire root type |
| aggregate_id | TEXT NOT NULL |
| mutation_id | TEXT NOT NULL, unique index |
| sequence | INTEGER NOT NULL, unique index |
| local_generation | INTEGER NOT NULL |
| payload_version | INTEGER NOT NULL |
| payload_json | TEXT NOT NULL, exact original durable wrapper bytes |

Do not store credentials, Google values, tokens or deadlines here. Source evidence may contain private domain values;
never include it in logs, displays, reports, throwable chains or `toString`.

Freeze all local workout/routine/custom root rows and their complete children/relationships **before changing metadata**
in the same transaction. Include active and finished, saved and empty drafts, saved routines temporarily blank/empty,
optional custom metadata and exact stored instructions, nullable values, recorded rest and source sync metadata. Use
stable table/column/row ordering with a versioned lossless raw SQLite cell representation (NULL, INTEGER decimal string,
FLOAT raw double bits, TEXT exact string, BLOB base64). Read storage types before numeric/boolean conversion; retain
malformed instructions/scalars in raw evidence even if typed validation fails. Source evidence must distinguish SQL NULL,
empty text, INTEGER/REAL and literal TEXT values. Do not serialize domain floats through a rounding format. Do not freeze
foreign-account rows or the entire global catalogue; lookup referenced bundled IDs/muscle/equipment for validation only.
Raw source encoding is local evidence, not another wire/request format. The reviewed v1 structure is a JSON object
with integer `v: 1` and one array per table (`workouts`, `workout_exercises`, `workout_sets`, `routines`,
`routine_exercises`, `routine_sets`, `exercises`, `exercise_muscle_groups`, `exercise_equipment`). Rows are arrays in the
explicit SELECT column orders given in the QA schema/harness section and schemas12 (root orders: workout id/name/start/
finish/status/account/revision/generation/state/deletion; routine id/name/draft/account/revision/generation/state/deletion;
custom id/name/type/difficulty/force/mechanics/category/instructions/custom-flag/account/revision/generation/state/deletion).
A cell is exactly `{t:"null"}` or `{t:"int"|"float"|"text"|"blob",v:"..."}`. Int/float values are canonical decimal Long
strings (float raw double bits); text is exact SQLite text; blob is canonical base64. Keys/widths/types/version are strictly
validated on recovery. QA may mutate this documented local evidence format to probe recovery, but expected wire requests
and preservation oracles remain independent raw literals/SQL, never a duplicated production encoder.

## Required internal API (exact QA contract)

New package `com.dragosfotache.gymtracka.data.sync.legacy`; all new declarations internal:

```kotlin
data class LegacyImportTarget(
    val origin: ServiceOrigin,
    val accountId: String,
    val operationId: String,
) // canonical UUIDs; fixed redacted toString

fun interface LegacyImportCaptureBarrier {
    suspend fun acquire(expected: RecoveryRoomSnapshot): ProjectionPublicationLease
}

enum class LegacyImportPhase { CAPTURED, BLOCKED }
data class LegacyImportEvidence(
    val target: LegacyImportTarget,
    val phase: LegacyImportPhase,
    val room: RecoveryRoomSnapshot,
    val sourceJson: String,
    val importCount: Long,
    val errorCode: String?,
) // fixed redacted toString
sealed interface LegacyImportCaptureResult {
    data object NoLocalData : LegacyImportCaptureResult
    data class Retained(val evidence: LegacyImportEvidence) : LegacyImportCaptureResult
}
enum class LegacyImportFailure {
    INVALID_INPUT, STALE_AUTHORITY, TARGET_MISMATCH,
    FOREIGN_STORAGE, CORRUPT_EVIDENCE, COUNTER_OVERFLOW, UNAVAILABLE,
}
class LegacyImportException(val failure: LegacyImportFailure) : RuntimeException
// RuntimeException("Legacy import failure", null, false, true): fixed message,
// suppression disabled, no cause/domain text. IllegalStateException lacks that protected constructor.

class LegacyImportStore(
    database: GymDatabase,
    barrier: LegacyImportCaptureBarrier,
    processCheck: () -> Unit,
    newMutationId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun capture(
        expected: RecoveryRoomSnapshot,
        target: LegacyImportTarget,
    ): LegacyImportCaptureResult
    suspend fun read(target: LegacyImportTarget): LegacyImportEvidence?
}
```

`capture` is explicit caller-owned private work. The target conveys already service-confirmed identity to this inert
storage seam; it is NOT a visibility or network grant. R13b must derive it from accepted fresh-service evidence under
existing process/vault fences and compose the same activation operation. No public UI/DAO integration now.

`capture` must not run inside a caller's existing transaction (`INVALID_INPUT` before barrier acquisition). Validate input,
acquire one real admission/drain lease
before source capture, check lease/process/cancellation before writes, after writes before commit, and after dispatch
before returning. Always close once, even failed/cancelled/lost-result paths. The future production barrier must gate
local edits and drain accepted finite edits/rest writes; explicit-save custom drafts need Save/Discard/Cancel in R13b/c,
never hidden autosave. R13a tests use controllable leases; do not introduce a live no-op barrier.

`read` needs no edit lease and performs one strictly fenced read transaction with process/cancellation checks. It returns
null only for no journal **and no orphan entries**. Extra singleton rows, unsupported evidence version, wrong SQLite types,
unknown phase, invalid UUID/origin/counters, malformed source JSON, bad ordinal/count/entry/wrapper/envelope identities,
missing/mismatched recorded outbox work or unexpected Room tuple fail closed `CORRUPT_EVIDENCE`, retaining every byte.
Target comparison is exact origin/account/operation; mismatch is `TARGET_MISMATCH`, no retargeting. Outbox retry fields
(state pending/frozen/rejected, attempts, retry/error) may evolve; original request identities/payload must remain identical.
Missing outbox is NOT success: R13b must later add durable definitive original-accepted progress before interpreting removal.

`capture` with existing valid matching evidence and exact current expected authority returns it unchanged; no second UUID,
sequence, generation, association or evidence capture. After committed-but-cancelled return/process reopen, `read(target)`
recovers the same work, and capture(current read authority, same target) is idempotent. Stale expectation fails without writes.
Repeated sign-in must reuse durable target/operation, not replace it with a newly minted migration identity.

## Transaction / validation algorithm

1. Acquire lease, then inside real Room transaction inspect exact current authority against `expected`, process and lease.
   Existing evidence is strictly read and returned only if matching, otherwise refused unchanged. For fresh work require
   initial local-only account storage described above. Nonempty work preflights checked revision/generation/root counters
   and retained `sqlite_sequence` high-water mark for enough room to append every import; do not infer next sequence
   solely from currently surviving outbox rows. Empty-source no-op requires no counter advance.
2. Snapshot complete raw source. Bundled-only empty source returns NoLocalData. Validate strict metadata/types, canonical
   root/child/reference IDs, same-family distinctness across graph, order, finite/nonnegative/range values, custom `reps`,
   optional metadata and relationship cardinality/role/equipment quantity. Lookup references without adopting a foreign
   custom root. Active workout has no finish time; at most one local active workout. Finished import must already satisfy
   finish invariants including completed surviving sets and nonempty exercises; never run cleanup/renumbering to fix it.
   Saved routine blank/empty and empty drafts are legal imports. Distinct empty/null instructions remain distinct; invalid
   stored JSON blocks, never `getOrElse(emptyList())` or string coercion of numeric/boolean/object array elements.
   Multiple primary muscles cannot become null via singleOrNull. Unknown/custom ownership flags cannot be filtered
   away as bundled/empty. Use accepted validators to validate typed records.
3. Invalid source: source/error evidence (`error_code = invalid_source`) and private fence commit together. No domain,
   canonical, cache, outbox or entries write. Retain exact roots/children/relationships unmodified. Blocked evidence has
   importCount0, post-room SIGNED_OUT identity null/cached null and checked revision/generation+1. Any failure rolls back
   fence and evidence together. An overflow is an exception, not a blocked record with wrapped counters.
4. Valid source: associate eligible roots' account metadata only (affected count exactly1 and strict post-read). Capture
   complete AGGREGATE_IMPORT records via existing typed capture in deterministic dependency order: custom UUID order,
   routine UUID order, finished workout UUID order, active workout last. Never upsert finished history or coalesce imports.
   Preserve every business column/child/relationship, source server revision must be null; generation advances exactly
   once through existing checked capture. Outbox actual sequence/identity/request bytes and root/ledger/cache post-state
   are verified against independent intended values, including trigger tampering and append failure. Keep immutable exact
   request evidence in entries. Checked count/ordinal/sequence admission (no AUTOINCREMENT wrap), UUID conflicts roll back.
5. Reuse RoomSessionAuthority.beginPreparation(expected, origin, account, operation) in that outer transaction after
   association removes local-only roots. Verify exact PREPARING identity/device, incomplete account cache, no ACTIVE or
   cursor/materialized-baseline manufacture. Persist singleton source/progress plus entries and recheck all final written
   data, source business values and authority/lease/process/cancellation before commit. Existing older local repositories
   become stale. Captured evidence is private preparation only, not remote settlement.
6. Catch cancellation with cancellation precedence. Propagate CancellationException from process/lease callbacks;
   preserve an explicitly thrown LegacyImportException's closed failure category. Other callback/storage runtime
   exceptions map to LegacyImportException(UNAVAILABLE), without causes/suppression or supplied text. No arbitrary
   callback throwable is exposed. SQLite constraint/trigger failures are storage failures (`UNAVAILABLE`), not evidence
   of stale authority; classify stale expectations from actual fenced observations. Lease-close runtime failure is not
   swallowed or attached to another throwable: cancellation still has precedence, and other failures remain closed.
   Post-commit invalidation/cancellation may hide the result, never claim rollback;
   durable reread is the recovery oracle.

Narrow signed-out `SyncStore.withFencedTransaction` guard: if any legacy-import journal/entry exists, refuse null-account
ordinary reads/writes (closed LegacyImportException CORRUPT_EVIDENCE or STALE_AUTHORITY as appropriate). Keep all existing
no-journal behavior and explicit ACTIVE repository paths. Do not construct a domain repository for private preparation.
No broad edits to authority, engines, codec, publication or UI are planned.

## Source access / ownership

Worker read/write:
- New `data/sync/legacy/*.kt` (models/store/source helpers), new `data/gym/SyncLegacyImportEntities.kt`,
  `SyncLegacyImportDao.kt`, `LegacyImportStorageMigration.kt`.
- `data/gym/GymDatabase.kt` (only entity/DAO/version/migration registration), `data/sync/store/SyncStore.kt` (only legacy
  null-account admission guard; reuse existing capture unchanged unless coordinator agrees a necessary bounded fix).

Worker read-only dependencies: production files under `data/gym/`, `data/sync/model/`, `data/sync/codec/`,
`data/sync/mapping/`, plus `data/sync/http/ServiceOrigin.kt`, `ServiceSessionBinding.kt`,
`data/sync/store/SyncSession.kt`, `ProjectionStore.kt`, `DurableRowReader.kt`, `CanonicalValidation.kt`, and
`data/sync/session/RoomSessionAuthority.kt`, `RoomSessionAuthorityFailures.kt`, `SessionRecoveryModels.kt`.
Read only the normative doc sections above and this spec. No tests/fixtures/test reports/schemas/QA work or broad diff.
Worker must request a slot before any Gradle; coordinator exports/reviews schema13. No migration asset edits.

QA read/write tests only:
- New `app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/legacy/*.kt`.
- New `app/src/androidTest/java/com/dragosfotache/gymtracka/data/gym/LegacyImportStorageMigrationTest.kt`.
- Existing `app/src/androidTest/java/com/dragosfotache/gymtracka/data/gym/GymDatabaseMigrationTest.kt` (current target13,
  supported starts1–12, exact new-table empty checks; retain historical9→10 tests).

QA read-only harnesses: existing `data/sync/session/RoomSessionAuthority*Test.kt`,
`data/sync/SyncCaptureTest.kt`, `data/sync/CanonicalStorageCaptureTest.kt`, `data/gym/GymDatabaseTest.kt`,
`data/gym/SessionAuthorityStorageMigrationTest.kt` and `SnapshotStorageMigrationTest.kt`, exported schemas1–12.
QA can compile/use declared APIs but must not inspect production sources/declarations/diffs/new schema13/Worker work,
including LSP/generated/decompiled classes. Missing facts come from coordinator. No broader tests, docs/build edits or
Gradle without a slot. No request bytes expected from production encoders. Both roles report asynchronously to
`gym-coordinator` without --wait, agent wait or polling.

## QA harness facts (not source access)

Use real `Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java).build()` plus raw SQL for independent seeds,
assertions and injected triggers. File reopen uses `Room.databaseBuilder(...).addMigrations(*GymDatabase.MIGRATIONS)`.
`RoomSessionAuthority(database).read()/beginPreparation(...)/fencePrivate(...)` are internal actual-authority APIs.
`RecoveryRoomSnapshot(revision, sessionGeneration, cachedAccountId, phase, identity, baselineComplete)`;
phase enum SIGNED_OUT/PREPARING/ACTIVE/TRANSITIONING; `RecoveryIdentity(binding, operationId)`;
`ServiceSessionBinding(ServiceOrigin.parse("https://sync.example.test"), canonicalAccountUUID, generation)`.
Durable canonical origin text is `https://sync.example.test:443` (lowercase/bracketed IPv6 host and explicit effective port),
matching the existing authority journal; ServiceOrigin equality considers host/scheme/effective port.
Fresh snapshot is revision0/generation0/SIGNED_OUT/null/null/false. Successful captured preparation is revision1/generation1,
PREPARING/cached target/identity(target binding1, original operation)/baselineComplete false. Blocked source is revision1/
generation1/SIGNED_OUT/null/null/false. `GymDatabase.MIGRATIONS` is internal exported migration array, target13.
`SyncStore(database, RepositorySession.SIGNED_OUT) { mutationUUID }`, `withFencedTransaction { ... }` checks old local fence;
`RepositorySession(accountId=null, sessionGeneration=1)` is constructible but blocked by legacy journal guard.
`ProjectionPublicationLease` is in `com.dragosfotache.gymtracka.data.sync.store`; methods `checkValid()` and `close()`,
callbacks synchronous/thread-safe on Room threads. Mutation kind/outbox mutation_type is `aggregate.import`;
aggregate_type literals are `customExercise`, `routine`, `workout`. For a fresh eligible local root generation g,
no account ledger exists and capture yields exactly g+1. One newMutationId call per import in declared dependency
order, none for empty/blocked/idempotent recovery. See step6 for closed process/lease exception handling.

Tables/columns use existing schemas12 (read-only QA oracle). Raw SQL sets `instructions` TEXT JSON array or NULL;
`is_user_created/is_draft/is_completed` INTEGER 0/1; malformed numerics can be seeded as REAL/TEXT or invalid integer flags.
Generation/root identity is `(aggregateType, UUID)`, not UUID alone. Workout and routine families have independent
identity namespaces; equal UUIDs across those families remain representable with independent generations. Within each
family, roots/entries/sets must be distinct across all roles and parents.
All root, workout/routine child, exercise-reference, muscle and equipment IDs in a valid graph must be canonical UUIDs;
plain mnemonic strings such as `we-fin`, `ws-act`, `muscle-a` or `equip-a` intentionally make a graph invalid. Labels/slugs
may be mnemonic. Root metadata columns account_id, server_revision, local_generation, sync_state, deleted_at_millis. Workout status active/
finished; notes and configured rest on workout_exercises; sets weight_kg nullable REAL, repetitions nullable INTEGER,
rest_seconds nullable INTEGER. UUIDs should be canonical; referenced bundled/muscle/equipment can be independent raw seeds.
Use parsed JSON structural and independently literal wire expectations; check exact stored wrapper bytes survive reopen.
New journal/entry columns are the table above, usable raw SQL even before production declarations are visible.

## Distinct verification obligations / risk

High-risk local-data persistence and crash/account fencing, but inert and no network/activation. QA should consolidate cases
by distinct plausible defect, not multiply full codec/engine matrices:

- One rich all-family valid graph: optional custom metadata/quantity/instructions, repeated references, finished and active
  workout fields/nullable recorded rest, saved blank/empty routine and empty draft. Independent root/child/relationship
  snapshots unchanged except allowed root metadata. Dependency order, complete request shapes/no owner/revision/labels,
  checked generations/watermarks/count/sequence and private authority are asserted independently.
- Empty install no-op; original refusal API still rejects local roots without the explicit importer. Repeated capture,
  concurrent capture (one commit/no duplicate), file reopen and lost return recover identical source/request identities.
- Representative invalid-source classes: finished invariant/positions, invalid custom cardinality or raw instructions,
  SQLite scalar/boolean coercion, nullable-root nonlocal metadata/tombstone. Block retains exact graph/error without any
  association/outbox/ledger; source must contain exact bad value/type. No silent normalization or draft exclusion.
- Foreign cache/root/intent evidence and origin/account/operation drift; no row adoption, copying, cleanup or retargeting.
  Corrupt/extra singleton, orphan/missing/mismatched entry/outbox/source evidence: retained failure, never empty success.
- SQL append/metadata/journal failure and valid-but-wrong final trigger tamper; cancellation during writes, stale/lost
  process/lease checks; all transactional effects rollback, whereas postcommit canceled result recovers via durable read.
  Old and freshly null-account repositories are refused after capture/block; no-journal paths keep prior behavior.
- Checked authority/root/sequence overflow with zero partial state; unique mutation failure after earlier staged writes.
- Additive12→13 exact prior-table/sqlite_sequence preservation; all supported1–12 starts to13 and copied actual v1 asset.

Reuse prior validator/codec/authority/finite-barrier coverage; do not retest every mutation/scalar/hardware combination.
Iteration: Kotlin/KSP compile, instrumentation compile, focused new tests and affected failures. Acceptance: fresh full offline
JVM suite; separate verified runs of new legacy package, LegacyImportStorageMigrationTest and GymDatabaseMigrationTest,
plus scoped SyncCaptureTest and RoomSessionAuthorityTest regression. Verify actual XML class/case/failure/error/skip results.
Run on one coordinator-owned disposable headless current API emulator, not personal Pixel AVD/device. No backend is needed
for R13a. Assembly/lint/full unfiltered default instrumentation/security and affected API24 startup/import coverage remain
explicit R13c gates; combined package/HTTP/API24 recovery gates remain R13b/R13d as roadmap requires. Schema13/KSP and
all supported migration/asset checks cannot be deferred. Run both tree diff checks and verify old schema/asset hashes.

## Acceptance closure

Coordinator independently reviews both full diffs/new files against this contract and shared invariants, records exact
commands/evidence/unverified gates, updates architecture/sync implementation/testing coverage, replaces handoff/roadmap,
archives spec/review and commits only reviewed R13a scope. Preserve pre-existing roadmap/handoff finite-plan edits;
those have been inspected and are the task's starting plan, not permission to stage unrelated changes. No push.
Next bounded slice is R13b only after R13a accepted/local commit and verified fresh-coordinator handoff.
