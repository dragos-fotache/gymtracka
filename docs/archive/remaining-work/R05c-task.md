# R05c — Protected canonical publication

## Authority, outcome and stop

The owner authorized R05c implementation with fresh Worker/QA agents on 2026-10-04. Deliver an **inert** account-scoped
Room publication boundary over the accepted R05a/b foundations. Production remains signed-out/local-only. No engine,
HTTP operation, claim/ack/checkpoint, snapshot download/baseline completion, vault/journal/account transition, UI observer,
Google flow, timer integration, scheduler, backend work, commit or push is authorized. Stop after independent acceptance
and documentation handoff; R06 requires separate authorization.

Authoritative references: [integration design §2](../../sync/integration-design.md#2-canonical-state-versus-local-intent-account-wide-protection-first),
[implementation](../../sync/implementation.md), [architecture](../../architecture.md), [testing strategy](../../testing-strategy.md),
[workflow](../../coordinator-workflow.md), and the finalized [shared contract](../../../shared-docs/docs/backend-contract/README.md).

## Inventory and dependencies

Room v10 already has account/type/root canonical evidence with independent generation watermarks and account metadata
`baseline_complete`, `cache_epoch`, `projection_dirty`, `materialized_epoch`. `CanonicalStore` applies complete batches
without changing domain data, cursor, baseline or epochs. Its helpers validate original stored JSON/scalars, complete
records and retained family roles. Root/entry/set domain PKs are global; exercise relationships have non-cascading FKs.
`GymRepository` captures scoped local edits and outbox work atomically; do not invoke it to publish canonical data.
`RepositorySession(accountId: String?, sessionGeneration: Long)` is immutable; account is an opaque nonblank service
identity, not necessarily a UUID. Device active identity/generation is the current primitive Room authority, not a
vault/session activation implementation. R12a remains mandatory before any live-app use of publication.

No entity/schema change is needed. Adopt this bounded epoch refinement: **every actual complete graph publication
increments `cache_epoch` by one and sets `materialized_epoch` to that new value**, providing an observable monotonic
publication signal even when parent revisions are unchanged (e.g. custom rename). Canonical row epochs may remain older,
never greater than account epoch. Publication invalidates callbacks carrying the previous epoch; future engine callers
must use the result's epoch. Snapshot publication will also use this invalidation boundary. Checked overflow aborts,
never wraps. Clean unchanged/blocked/error paths never increment. This reuses existing v10 storage instead of adding a
second signal column/migration, and must be documented in the approved integration design at acceptance.

## Normative public API

All types below live in `com.dragosfotache.gymtracka.data.sync.store` and are available to instrumentation tests.

```kotlin
data class ProjectionPublicationRequest(
    val accountId: String,
    val sessionGeneration: Long,
    val expectedCacheEpoch: Long,
)

fun interface ProjectionPublicationBarrier {
    suspend fun acquire(request: ProjectionPublicationRequest): ProjectionPublicationLease
}

interface ProjectionPublicationLease : AutoCloseable {
    fun checkValid()
    override fun close()
}

enum class ProjectionPublicationStatus {
    PUBLISHED, PROTECTED, INCOMPLETE_BASELINE, UNCHANGED,
}

data class ProjectionPublicationResult(
    val status: ProjectionPublicationStatus,
    val cacheEpoch: Long,
    val materializedEpoch: Long,
)

class ProjectionStore(
    database: GymDatabase,
    session: RepositorySession,
    barrier: ProjectionPublicationBarrier,
) {
    suspend fun publish(expectedCacheEpoch: Long): ProjectionPublicationResult
}

class ProjectionLeaseInvalidException : IllegalStateException
class ProjectionGraphException : IllegalStateException
class ProjectionCollisionException : IllegalStateException
```

Exceptions have default fixed safe messages, no stored JSON/domain values, raw causes or private identifiers.
Projection-owned low-level SQLite failures map to `CanonicalStorageException` without retaining the raw cause;
caller cancellation is never translated. Barrier/lease-origin failures retain their original identity.
Reuse existing `StaleSessionException`, `StaleCanonicalEpochException` and `CanonicalStorageException` for their existing
boundaries. Constructor rejects null account or nonpositive session generation. Negative expected epoch rejects before
barrier acquisition/Room work. A barrier is **required**, with no permissive production/default implementation.

The barrier owns pause/drain/timer coordination and grants one short lease only after accepted finite edits have drained
successfully. R05c implements the interface, not the live barrier. Tests implement controllable leases. `checkValid()`
is a synchronous pure validity check; revoked implementations throw `ProjectionLeaseInvalidException`. `close()` must
release once without blocking, mutating Room, or throwing; a thrown cleanup failure is never proof of rollback.
If `acquire` throws/cancels, it owns cleanup of any partially acquired admission pause. The store closes every successfully
returned lease exactly once in `finally`, including blocked, unchanged, failure and cancellation paths. No Room
transaction spans acquisition/draining. Lease remains held through transaction completion; check it inside Room before
work and immediately before commit. Cancellation must remain cancellation and roll back uncommitted changes.

## Publication transaction

1. Acquire the bound lease outside Room. Inside one `withTransaction`, check cancellation, lease validity and immutable
   account/session/cache fences (reuse canonical checks). Reject malformed account counters (negative or materialized
   greater than cache epoch) safely. Recheck durable fences and lease before committing actual publication.
2. **Any** outbox row for this account protects its **entire** domain projection. Do not decode/filter by row state,
   version, request validity, generation, target or error. Pending/frozen/rejected/corrupt/unknown states all protect.
   Return `PROTECTED` with no writes, regardless of canonical graph validity. Foreign account work does not protect A.
3. Missing cache or `baseline_complete=false` returns `INCOMPLETE_BASELINE` with no writes. A cursor/device label,
   accepted batch or empty outbox never manufactures a complete baseline. Priority is protected before incomplete.
4. Complete, clean metadata with `materialized_epoch == cache_epoch` returns `UNCHANGED` without writes or epoch advance.
   Otherwise load/validate the **whole** retained canonical account graph before touching domain tables, including
   deletion/reservation/absence evidence. Corrupt retained state/body/counters throw `CanonicalStorageException` unchanged;
   retained family-role/parent disagreements reuse the accepted `CanonicalConflictException` boundary.
5. Validate resulting graph and physical identities as below. Check epoch overflow before destructive writes.
6. Preserve generation reservations for every existing owned domain root (including removed/tombstoned roots): keep
   `max(existing domain generation, ledger watermark)` independently of rematerialization, without inventing a server
   observation. Reject negative domain generations. If necessary insert an unobserved reservation/update only watermark.
7. Replace only the selected account graph, using explicit deletes/inserts, never root `INSERT OR REPLACE`:
   remove its workout/routine children/roots first; clear its custom relationships/obsolete custom roots; install customs
   and relationships before referencing workout/routine children. All selected account roots, including old tombstones,
   may be rebuilt. Unobserved/delete/snapshot_absent rows do not become fake visible domain roots. Remove obsolete roots
   only as part of this complete-baseline publication, never infer absence from an incremental page.
8. Upsert domain roots carry exact account, canonical positive server revision, preserved max generation, `sync_state`
   `synced`, and null deletion timestamp. Preserve all root/child IDs, references, positions, fields, null vs zero/empty,
   timestamps (including finish before start), draft/saved states, completion, configured vs recorded rest, custom optional
   metadata/instruction arrays and relationship quantities. Derived labels/volume are not persisted. Catalogue ID/name/
   metadata/relationships stay untouched. Current names resolve through selected-scope exercise rows, never embedded
   workout/routine `exerciseName` or custom relationship names.
9. Atomically clear dirty and set account cache/materialized epoch to checked old epoch + 1. Preserve complete baseline.
   Return `PUBLISHED` with both new epochs. Do not modify canonical revision/body/state/row epochs (watermarks alone may
   rise), any account's outbox bytes/order/errors, cursor/last success, device identity or catalogue metadata.
   No repository capture, network, acknowledgment, uploads or new local generations.

## Whole-graph and physical identity validation

- Reuse strict retained canonical decoding and family validation across all retained rows, including unobserved/delete/
  snapshot_absent root reservations. Workout and routine namespaces remain independent. Complete graph has at most one
  active workout. Every workout/routine exercise reference resolves to either a live canonical custom in A or an existing
  immutable global bundled exercise (`!isUserCreated`, null account, null deletion); a surviving local/foreign custom or
  a custom ledger deletion/reservation is not a canonical dependency.
- Every custom muscle/equipment ID exists in global lookup tables. Do not overwrite/create those tables or trust embedded
  convenience names. Missing/deleted/custom-reference dependencies or multiple active roots throw `ProjectionGraphException`.
- Validate global physical PKs and family roles against **all** current domain rows before removing anything. A target
  root/entry/set cannot collide with a local-only/foreign/bundled physical row. Existing same-account matching root/role/
  parent identity may be rebuilt. Same-account child movement to another root or set parent, or root/entry/set role change,
  rejects even when that prior graph would be removed. Workout vs routine IDs may overlap because namespaces/tables are
  independent. Custom exercise root must be custom and owned by A if already physical, never claim a bundled row.
- Surviving local/foreign workout/routine references to an A custom scheduled for removal must fail safely before writes;
  publication cannot rewrite or cascade another scope to make its graph fit. Surface `ProjectionGraphException` (invalid
  surviving references) or a fixed safe storage failure for impossible pre-existing relational corruption.
- Collision throws `ProjectionCollisionException`; retain complete canonical evidence, all domain/intent/other-scope
  data and metadata unchanged. Never remap UUIDs, relax FKs, or use ignore/replace to make publication succeed.
- No account-lifetime removed-child reservation schema is introduced. Validate currently known canonical and physical
  roles/parents; do not claim this implements server lifetime reservation storage.

## Contributor boundaries and concrete harness facts

Fresh native sessions: `gym-worker` and `gym-qa`, Pi `meta/muse-spark-1.3-contributor`, high reasoning. Coordinator alone
reviews both layers and owns integration/docs. All reports are asynchronous via
`herdr agent prompt gym-coordinator "..."` (no `--wait`, no `agent wait`, no output polling). Do not launch other agents,
inspect the other contributor session, or commit/push. Gradle runs are coordinator-owned initially; request a slot before
any shared-output build. Do not read decompiled/generated production or reports as a substitute for forbidden source.

### Worker

Read: this spec, root guide/relevant local/shared docs, **production** Kotlin under `data/gym`, `data/sync/store`,
`data/sync/model`, `data/sync/codec`, `data/sync/mapping`; build configuration only if necessary.
Write: new `data/sync/store/ProjectionStore.kt`, focused `Projection*.kt` helpers; new `data/gym/SyncProjectionDao.kt`;
necessary focused changes to `GymDatabase.kt` (DAO accessor only), `SyncCanonicalDao.kt`, `CanonicalValidation.kt`.
No entities/schema/asset/migrations/repository/UI/session/HTTP/config changes. Ask coordinator for any ownership extension.
**Never read test source, test fixtures/results/reports, QA work or their diffs.** Do not use broad git diff or searches
crossing test directories. Coordinator supplies findings as behavior contracts, not test code.

### QA

Read: this spec, root guide/relevant docs/shared contract; **test-only** source under `app/src/androidTest` and
`app/src/test` for independent harness/fixtures. May reuse test-only R05b independent JSON fixture helpers or author new.
Write: new `app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/ProjectionPublicationTest.kt` and focused
`ProjectionPublicationTestSupport.kt`/additional `Projection*Test.kt` files if needed. Existing tests remain unchanged
unless coordinator grants a focused correction.
**Never inspect production source/declarations/implementation/diffs, generated KSP/decompiled production, or Worker work.**
Compilation diagnostics may be used for tests, not source discovery. Ask coordinator for missing signatures/Room facts.

Available harness contracts (supplied by coordinator, not permission to read production):
- Real in-memory Room: `Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java).build()`; close per test. No DAO mocks.
- `database.syncDao()` exposes suspend `upsertDeviceState(SyncDeviceStateEntity)`, `insertOutbox(SyncOutboxEntity): Long`,
  `getOutboxForAccount(String): List<SyncOutboxEntity>`, `upsertSyncState(SyncStateEntity)`, `getSyncState(String)`.
- `SyncDeviceStateEntity(activeAccountId = account, cachedAccountId = account, sessionGeneration = 7L)` defaults id=1.
- `database.syncCanonicalDao()` exposes suspend `getRootsForAccount(String)`, `getRoot(account,type,id)` and
  `getAccountCache(String)`. R05b test helpers seed cache/canonical via raw SQL and decode independent JSON batches.
- `CanonicalStore(database, RepositorySession(account, 7L)).applyBatch(batch, expectedCacheEpoch)` seeds canonical via the
  accepted API; JSON expectations must be handwritten, never generated by production encoder.
- Raw SQLite through `database.openHelper.writableDatabase.execSQL(sql, arrayOf<Any?>(...))`/`query(...)` is allowed for
  independent setup/corruption/error injection and complete row oracles. Use real FKs, no disabling constraints.
- Domain tables/columns follow architecture and exported v10 schema; QA may read `app/schemas/**/10.json` as the storage
  contract, not generated implementation. Account cache table columns are listed above. Root metadata is account_id,
  server_revision, local_generation, sync_state, deleted_at_millis. All names/nullable values are asserted independently.
- `SyncOutboxEntity` required args: mutationId, accountId, aggregateType, aggregateId, mutationType, localGeneration,
  payloadJson; optional sequence=0, payloadVersion=1, state="pending", attemptCount=0, retryAfterMillis=null,
  errorCode=null, errorMessage=null. Arbitrary/corrupt state/payload rows intentionally protect without decoding.

## Required independent acceptance coverage

1. Full three-family graph fidelity, customs/multiple relationships, metadata/quantity, null/empty instructions, finished
   history, draft/saved blank/empty routine, nullable/zero/max set inputs, consecutive children, distinct rest values;
   same-ID full replacement removes old children and unknown deletes/reservations/absence create no fake roots.
2. Whole-account protection across pending/frozen/rejected/corrupt/unsupported/unknown outbox rows, including different
   root/custom references, locally sole-active replacement, tombstones and new generations. Canonical updates retained
   independently. Foreign-account outbox does not block A. No baseline/missing cache never publishes or invents baseline.
3. Acquire/drain failure/cancellation and close-once; invalid/revoked lease; edit captured during acquisition/drain prevents
   overwrite. Stale account/session/cache epoch, missing device, negative counters and checked Long.MAX epoch overflow.
4. Whole graph invalid dependency (unknown/deleted/local/foreign custom), missing muscle/equipment and multiple active;
   complete batch active replacement/custom dependency deletion; every error leaves all tables/metadata unchanged.
5. Local/foreign/bundled root PK collision; entry/set PK and family-role collisions; same-account wrong-root/wrong-parent
   reuse even if old graph is removed. Valid same binding and independent workout/routine ID overlap succeed. Cross-scope
   references to removed A custom fail without deleting/editing other scope.
6. Exact generation preservation for removed/rematerialized/reappearing roots and capture after publication exceeding
   retained watermark. Canonical JSON/revisions/state/epochs unchanged, outbox/cursor/device/other scope/catalogue
   unchanged; no echo upload. Custom rename updates actual selected-scope joins even at unchanged parent revision and
   advances account publication signal. Clean retry returns UNCHANGED without advancing it.
7. Fault after deletes/final child insert (e.g. SQLite trigger RAISE(ABORT)) and deterministic cancellation before commit
   roll back complete graph, watermark/cache changes; lease released once. Real file-backed close/reopen retains signal,
   deletion evidence and projection with no accidental baseline completion. Avoid sleeps.

Coordinator runs Kotlin/KSP, fresh offline JVM tests, Android-test compilation, focused publication instrumentation then
unfiltered full instrumentation if an isolated emulator is available; assembly/lint and scope/schema/asset/APK checks.
Inspect executed XML cases/failures/errors/skips. API 24, backend/Google/engine/app-activation gates remain unverified by
this task. Record actual environment/results and residual limitations in one acceptance review, then update owning docs
and archive this spec only after acceptance. Never mark partial/failing work accepted.
