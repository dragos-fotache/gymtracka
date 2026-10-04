# R05b — fenced canonical batches

Status: **accepted, 2026-10-04 — frozen historical specification**. The owner authorized R05b on 2026-10-04.
This specification records the completed contributor contract, not permission to resume or start another slice. [Workflow](../../coordinator-workflow.md), [implementation](../../sync/implementation.md), and approved
[design §§2–3](../../sync/integration-design.md#2-canonical-state-versus-local-intent-account-wide-protection-first) apply.
The finalized [shared contract](../../../shared-docs/docs/backend-contract/README.md) is unchanged.

## Goal and stop

Deliver a reusable, inert Room canonical-batch writer over the existing v10 independent ledger. Persist complete
validated server aggregates and unknown-root deletion evidence atomically, arbitrate revisions, retain local generation
watermarks, and reject stale account/session/cache-epoch work. Provide a transaction composition seam for future exact
ack/checkpoint integration without implementing either operation.

**Stop before R05c:** no visible-domain writes, publication gate, complete-baseline establishment, snapshot absence writer,
staging, claims/acks, cursor/last-success writes, HTTP/session construction, engine, account transition, UI or activation.
No commits/pushes/deployment/backend source/configuration/live account work. No schema/entity/asset/build changes are planned.
If implementation needs one, report the blocker before widening the allowlist. Production remains local-only.

## Inventory and decisions

- `GymDatabase` is v10; `syncCanonicalDao()` exposes read-only public ledger/cache queries and internal capture primitives.
- `sync_canonical_roots` PK is `(account_id,aggregate_type,aggregate_id)` with nullable observed revision/JSON, state,
  independent local-generation watermark and cache epoch. It has no domain FK.
- `sync_account_cache` separately stores baseline-complete, cache epoch, projection-dirty and materialized epoch.
- `SyncStore` already fences local capture and advances reservations/dirty state transactionally. Reuse its session
  semantics, not its mutation/capture path for server writes.
- Models/codecs already validate whole changes/batches, IDs, positions, metadata, nulls, timestamps and complete records.
  `exerciseName` is a nonauthoritative workout/routine convenience label.
- Cache epoch fences callbacks across future snapshot replacement; **ordinary batches do not increment it**. A changed
  canonical batch marks projection dirty. Baseline-complete/materialized epoch remain unchanged. Missing cache is epoch 0
  and incomplete; create its default row only when a selected change actually writes.
- Canonical downloads may have unresolved exercise/catalogue dependencies, multiple active roots pending missing batches,
  and physical UUID collisions with signed-out/other account domain rows. Retain those records independently. Complete
  reference/sole-active/global physical PK validation belongs to R05c publication, not download.
- Validate family identity bindings against currently retained canonical evidence (including before-replacement records),
  scoped by account and workout/routine family. This is **not a new account-lifetime removed-child reservation database**;
  the service owns lifetime reservations. Do not invent an extra ledger/schema for that purpose in R05b.

## Concrete API contract

Package `com.dragosfotache.gymtracka.data.sync.store`; new files `CanonicalStore.kt` and optional focused
`CanonicalValidation.kt`. Public names/signatures below are fixed for independent QA:

```kotlin
class CanonicalStore(database: GymDatabase, session: RepositorySession) {
    suspend fun applyBatch(batch: SyncBatchModel, expectedCacheEpoch: Long): CanonicalBatchResult
    suspend fun <T> withFencedTransaction(
        expectedCacheEpoch: Long,
        block: suspend (CanonicalTransaction) -> T,
    ): T
}
class CanonicalTransaction /* internal construction only */ {
    suspend fun applyBatch(batch: SyncBatchModel): CanonicalBatchResult
}
data class CanonicalBatchResult(val appliedRoots: Int, val ignoredRoots: Int)
class StaleCanonicalEpochException /* IllegalStateException */
class CanonicalConflictException /* IllegalStateException */
class CanonicalStorageException /* IllegalStateException */
```

Exceptions may take a fixed safe message; no constructor signature is needed by QA. Existing `StaleSessionException`
remains the account/generation failure. Invalid incoming models use existing `SyncModelException` (IllegalArgumentException).
`CanonicalStorageException` means invalid retained ledger/cache evidence, not permission to repair/delete it.
`CanonicalConflictException` means equal-revision authoritative conflict or known family-role/root/parent disagreement.
Exception messages must not include stored JSON/domain values. Preserve caller cancellation, do not wrap it as a storage error.

- Require a nonnull account and positive session generation when constructing `CanonicalStore`; signed-out/default and
  account generation zero throw IllegalArgumentException. Retain existing nonblank opaque service identity support.
- Negative expected cache epoch throws IllegalArgumentException. Inside Room, match active account and session generation
  against `sync_device_state` (absence means null/0), then match actual cache epoch (absence means 0).
- `withFencedTransaction` owns one `database.withTransaction`, checks fences before exposing its capability, and closes
  it in `finally`. Each `CanonicalTransaction.applyBatch` rechecks fences before writes. Escaped/closed transaction use
  throws IllegalStateException; no write capability may work outside its active Room transaction.
- Public direct `applyBatch` is the single-batch convenience. Deep-own and validate input before its first suspension;
  a caller mutating supplied lists while Room admission is delayed cannot change admitted data. The transaction method
  also deep-owns/validates before its own first suspension. Existing canonical codecs can supply the owning round-trip.
- Return appliedRoots for selected newer/first-observed writes and ignoredRoots for older/equal/no-resurrection no-ops;
  their sum equals the input change count. A thrown batch returns no partial result.
- Future callers may compose batch writes and their own exact acknowledgment/checkpoint storage operations in this
  transaction; this slice adds no implementation of those operations. An exception/cancellation from the callback rolls
  back every batch and callback write in the transaction. No network in this callback.

## Storage and revision rules

1. Preflight the entire batch even when a change will be revision-obsolete: nonempty, valid batch UUID/nonempty opaque
   cursor, unique `(type,id)` changes, valid operation and matching record type/id/revision, full family aggregate validity.
   Never parse/order cursors; this writer stores no batch ID/cursor history and never advances `sync_state`.
2. Validate existing account cache counters as nonnegative, materialized epoch not above cache epoch; validate retained
   account rows: canonical type/root UUID, nonnegative watermark/epoch, root epoch not above account epoch; legal states:
   - `unobserved`: revision/JSON null;
   - `upsert`: positive revision plus strict valid complete record JSON whose type/id/revision match the row;
   - `delete`: positive revision, JSON null;
   - `snapshot_absent`: JSON null and null or positive retained revision.
   Preserve malformed/unknown-state evidence and fail the transaction. No repair or fabrication. Retained JSON must pass
   raw duplicate-member checks and original JSON scalar grammar/type fidelity, not just `JSONObject` normalization.
   Unquoted arbitrary strings, numeric-overflow-to-string coercion and literal string controls must fail closed; quoted
   exponent-like strings and valid escaped controls remain valid data. All existing rows for this account may be inspected
   for identity validation; unrelated accounts are not inspected or modified.
3. An unobserved/missing root accepts the first incoming observation regardless of local watermark. For observed evidence,
   lower revision is a no-op, higher revision selects the whole new operation/record. Never choose by business timestamps,
   local generations, batch cursor spelling, or known local domain server revisions.
4. At equal revision: DELETE vs DELETE is a no-op; UPSERT vs UPSERT must agree on all validated represented authoritative
   values after position/membership normalization. Ignore only workout/routine `exerciseName` differences. Keep the
   originally stored JSON/labels on that no-op. Operation disagreement or any other changed authoritative field rejects
   the **whole batch**. No partial merge/repair. This includes root status, time, IDs/parent bindings, nulls, set values,
   notes/rest, custom name/metadata/instruction order/relationship values. Unknown additive fields and derived volume
   are not authoritative.
5. For `snapshot_absent`, lower/equal incoming observations cannot restore an absent record; leave retained absence
   unchanged. Higher observations use the normal selection rule. R05b never creates absence or a deletion revision from it.
6. Persist selected DELETE even for an unknown root, with positive revision and null JSON; do not create a fake domain
   placeholder or delete domain children. Subsequent older UPSERT cannot resurrect it. A selected UPSERT replaces its
   canonical body completely; preserve IDs, order, null/empty distinctions, metadata and supplied millisecond times.
7. Every selected root uses the current account cache epoch and retains its exact existing local-generation watermark
   (new unknown remote root gets 0). Neither canonical application nor no-op changes domain generations/revisions/state.
   Never lower/drop an existing watermark, including after deletion or with newer local pending work.
8. All selected writes and account dirty marking commit together. Older/equal-only batches change no rows, including cache
   bookkeeping/JSON. For selected changes preserve baseline-complete, epoch and materialized epoch; set dirty true.
   Preserve existing device/cache identity, outbox byte identity/order/state/errors and cursor/last-success exactly.

## Family identity and dependency boundary

For known workout/routine canonical evidence, root, entry and set UUID roles bind to that family, root and (for sets)
entry parent. Every retained ledger state reserves its root role, including unobserved/delete/snapshot_absent; every
selected incoming family change, including DELETE, also reserves a root role. Validate retained bindings even for a
whole no-op batch. Reject incompatible reuse across selected incoming records and existing upserts/root evidence, including
reparenting at a higher revision and moving an entry/set to another root while deleting its old root in the same batch.
Repeated catalogue/custom `exerciseId` references are allowed. Workout and routine family namespaces are independent;
other accounts are independent in this download store. Obsolete incoming records must not be installed or allowed to
replace newer binding evidence. A root's own removed child may disappear/reappear at the same known binding; no ID remap.

Do not query domain tables to demand resolved references or reject physical PK collisions at download time. An upsert
referencing an absent custom is stored; a later batch can supply the custom. A custom delete while a retained canonical
workout still refers to it can be stored, but can never authorize incomplete visible publication in R05c. Bundled/custom
catalogue collision checks and complete graph availability likewise remain publication gates. No leaf UI observes this
store directly.

## Independent QA harness facts

QA imports the specified APIs without reading production declarations/implementations. For missing facts, ask coordinator.

- Real database setup: `Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
  GymDatabase::class.java).build()`. Close after all owned coroutines/transactions finish. JUnit4 and kotlinx-coroutines-test
  are available. File-backed Room recreation is available for restart checks; own/delete only your named test database.
- `database.syncDao().upsertDeviceState(SyncDeviceStateEntity(activeAccountId = "account-a", sessionGeneration = 7L))`.
  `RepositorySession("account-a", 7L)` binds the store. `cachedAccountId` alone is not admission.
- `database.syncCanonicalDao().getRoot(account,typeWire,id)` / `getRootsForAccount(account)` /
  `getAccountCache(account)` return data classes with fields matching the inventory column names in camelCase:
  `accountId, aggregateType, aggregateId, serverRevision, canonicalState, canonicalJson, localGenerationWatermark,
  cacheEpoch`; cache has `accountId, baselineComplete, cacheEpoch, projectionDirty, materializedEpoch`.
- Independently handwritten full response JSON can be decoded using public `decodePullResult(String).batches.single()`
  from `data.sync.codec`; construct valid `{batches:[{batchId,cursor,changes:[...]}], nextCursor:<same cursor>,hasMore:false}`.
  `SyncBatchModel` has `(batchId: String,cursor: String,changes: List<SyncChangeModel>)`; `SyncChangeModel` has
  `(aggregateType: AggregateType,aggregateId: String,operation: ChangeOperation,revision: Long,record: CanonicalRecord?)`.
  Enums: `WORKOUT,ROUTINE,CUSTOM_EXERCISE`; operations `UPSERT,DELETE`. Direct copy is available for malformed-model tests.
- Typed fixture constructors below are contract facts (all in `data.sync.model`). These are data classes with `copy`.
  Declared `List<T>` parameters accept an independently owned `MutableList<T>`/`ArrayList<T>`; declaration as List does
  not imply defensive ownership. Use direct construction/copy to bypass decoder validation in negative writer tests.

  ```kotlin
  CanonicalRecord.Workout(record: WorkoutRecordModel)
  CanonicalRecord.Routine(record: RoutineRecordModel)
  CanonicalRecord.CustomExercise(record: CustomExerciseRecordModel)
  WorkoutRecordModel(id: String, name: String, status: String, startedAtMillis: Long,
      finishedAtMillis: Long?, revision: Long, exercises: List<WorkoutExerciseModel>)
  WorkoutExerciseModel(id: String, exerciseId: String, position: Int, notes: String,
      restDurationSeconds: Int, sets: List<WorkoutSetModel>, exerciseName: String)
  WorkoutSetModel(id: String, position: Int, repetitions: Int?, weightKg: Double?,
      isCompleted: Boolean, restSeconds: Int?)
  RoutineRecordModel(id: String, name: String, isDraft: Boolean, revision: Long,
      exercises: List<RoutineExerciseModel>)
  RoutineExerciseModel(id: String, exerciseId: String, position: Int, restDurationSeconds: Int,
      sets: List<RoutineSetModel>, exerciseName: String)
  RoutineSetModel(id: String, position: Int, repetitions: Int?, weightKg: Double?)
  CustomExerciseRecordModel(id: String, name: String, type: String, category: String?,
      difficultyLevel: String?, forceType: String?, mechanics: String?, instructions: List<String>?,
      isUserCreated: Boolean, revision: Long, muscleGroups: List<MuscleGroupRefModel>,
      equipment: List<EquipmentRefModel>)
  MuscleGroupRefModel(id: String, name: String, type: String)
  EquipmentRefModel(id: String, name: String, numItems: Int?)
  ```

- `SyncOutboxEntity` (`data.gym`) is a data class; equality compares all fields. Besides required identity/body fields,
  fields/defaults are `sequence: Long = 0`, `payloadVersion: Int = 1`, `state: String = "pending"`,
  `attemptCount: Int = 0`, `retryAfterMillis: Long? = null`, `errorCode: String? = null`, `errorMessage: String? = null`.
  SQL columns in `sync_outbox`: `sequence,mutation_id,account_id,aggregate_type,aggregate_id,mutation_type,
  local_generation,payload_version,payload_json,state,attempt_count,retry_after_millis,error_code,error_message`.
  `syncDao().getOutboxForAccount(accountId)` returns sequence-ordered complete entities; compare the full list before/after.
  Corrupt/unsupported work may be represented by an invalid/unsupported `payloadVersion` or body, not only a state label.
- `SyncStateEntity(accountId: String,cursor: String? = null,lastSuccessMillis: Long? = null)` (`data.gym`) maps to
  `sync_state(account_id,cursor,last_success_millis)`; `syncDao().getSyncState(accountId)` returns its complete row.
- Raw domain snapshots may use `SELECT * FROM <table> ORDER BY id` (relationship tables order by their compound key).
  Roots: `workouts(id,name,started_at_millis,finished_at_millis,status,account_id,server_revision,local_generation,
  sync_state,deleted_at_millis)`; `routines(id,name,is_draft,account_id,server_revision,local_generation,sync_state,
  deleted_at_millis)`; `exercises(id,name,type,difficulty_level,force_type,mechanics,category,instructions,is_user_created,
  account_id,server_revision,local_generation,sync_state,deleted_at_millis)`. Instructions are nullable JSON string-array
  text. Children: `workout_exercises(id,workout_id,exercise_id,position,notes,rest_duration_seconds)`;
  `workout_sets(id,workout_exercise_id,position,repetitions,weight_kg,is_completed,rest_seconds)`;
  `routine_exercises(id,routine_id,exercise_id,position,rest_duration_seconds)`;
  `routine_sets(id,routine_exercise_id,position,repetitions,weight_kg)`. Custom relationships:
  `exercise_muscle_groups(exercise_id,muscle_group_id,type)` and `exercise_equipment(exercise_id,equipment_id,num_items)`.
  Global `muscle_groups(id,slug,name)` and `equipment(id,name,type,usage_type)` may be seeded by allowed fixture helpers.
  SQL complete before/after snapshots or full entity equality are allowed independent no-write oracles.
- Canonical record JSON is the full record object, not the SyncChange wrapper. Inspect with independent JSON access or
  public `decodeWorkoutRecord(String)`, `decodeRoutineRecord(String)`, `decodeCustomExerciseRecord(String)`; assert literal
  expected values. Do not generate expected JSON with production encoders. Wire shapes come from shared OpenAPI.
- SQL via `database.openHelper.writableDatabase.execSQL(sql, bindArgs)` / `.query(sql,bindArgs)` is permitted for fault
  fixtures and complete table before/after assertions. Seed ledger/cache directly when testing corrupt or existing state.
  Column names/defaults are in the inventory; all root columns except the three PK columns have defaults/nullability.
  Cache has account PK plus default `baseline_complete=0,cache_epoch=0,projection_dirty=0,materialized_epoch=0`.
- A trigger using `SELECT RAISE(ABORT, 'injected')` can fail a later canonical INSERT/UPDATE; use it to prove rollback.
  Coroutine gates/`awaitCancellation` inside the public transaction callback prove cancellation rollback without sleeps.
- Existing fixture API `GymDatabaseTest` and existing R05a tests may be read within QA's allowlist below. Raw SQL
  sentinel rows can prove domain/outbox/sync state preservation; fixture setup does not grant production source access.

## Acceptance tests

Independent real Room tests must cover:

1. Whole complete workout, finished workout, draft/saved (including blank/empty) routine and custom observations; ordered
   child replacement/removal, all metadata, null versus empty instructions, zero/null weight/repetitions/rest, distinct
   configured/recorded rest and reversed start/finish business time preserved. No upload/cursor/domain echo.
2. Unknown-root deletion, replay DELETE, older UPSERT/DELETE ignored, newer winner/replayed old accepted batch, and equal
   revision agreement/conflict for all three types. Workout/routine convenience-label difference is a no-op with stored
   labels unchanged. Conflicting authoritative field or operation rolls back otherwise-new roots in the batch.
3. Atomic old-active DELETE/new-active UPSERT and mixed obsolete/new changes. No intermediate state persists on injected
   later-write failure or callback failure/cancellation. Multi-batch composed transaction rollback, plus closed capability.
4. Stale account, generation, missing/signed-out device and wrong cache epoch fail with no writes; epoch-zero absent cache
   works. Dirty-marker/baseline/materialized/epoch preservation; no-op creates no default bookkeeping. Restart retains data.
5. Existing watermark remains exact for newer canonical observations/deletions while newer visible intent and pending,
   frozen, rejected/corrupt/unsupported outbox rows remain unchanged. Subsequent local capture still advances beyond it
   (existing R05a APIs/fixtures may be reused, not weakened).
6. Whole-batch structural preflight including duplicate roots, mismatched ID/type/revision, invalid family aliases,
   malformed positions/values; malformed retained state/JSON/raw duplicate members/scalar coercion/literal controls and
   negative counters fail closed, with valid quoted/escaped counterparts preserved.
7. Cross-root family role/parent collisions and higher-revision reparent reject; independent family/account same UUIDs
   succeed. Missing custom dependency arriving later, retained-reference custom delete, and physical local/foreign/bundled
   collision are retained canonically without touching domain rows. These are download tests, not publication acceptance.
8. Mutable input list admission race uses deterministic transaction/gates, not sleeps. Check all nested mutable collections
   used in the fixture, and ensure no caller-owned input can change a suspended admitted direct batch.

Run focused checks, then Kotlin/KSP, full offline JVM, Android-test compilation, assembly/lint and unfiltered device
instrumentation when an isolated emulator is available. Inspect executed XML names/counts/failures/errors/skips. No backend
run is required for this Room-only slice; no API24/live Google/engine/publication claim follows a local API37 pass.
`git diff --check` in both repositories; asset/schema snapshots unchanged. No test failure/partial work is acceptance.

## Role allowlists and reporting

- **Worker READ:** this spec, AGENTS.md, `docs/README.md`, coordinator workflow, architecture, sync implementation/design,
  testing strategy, shared product/backend contract/catalogue docs; production files under `data/gym/`,
  `data/sync/store/`, `data/sync/model/`, `data/sync/codec/`. Restricted mcpls must obey these same bounds.
  **Worker WRITE:** `data/sync/store/CanonicalStore.kt`, optional `CanonicalValidation.kt`,
  `data/gym/SyncCanonicalDao.kt` only. Do not inspect any tests/fixtures/reports or QA work, including whole git diff/status
  that reveals QA content. Path-scoped diff of permitted production files only.
- **QA READ:** this spec, AGENTS.md, the same listed documentation; existing `app/src/androidTest/.../data/gym/GymDatabaseTest.kt`,
  `TestFixtures.kt`, and `app/src/androidTest/.../data/sync/CanonicalStorageCaptureTest.kt`. Your own new tests and their
  scoped diagnostics/reports only. **No production source/declarations/diffs/generated/decompiled code or Worker work**,
  including LSP, build logs exposing implementations, or source inspection to fix compilation. Coordinator supplies facts.
  **QA WRITE:** new `app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/CanonicalBatchTest.kt` and optional
  `CanonicalBatchTestSupport.kt` only. Do not alter old accepted tests.
- Coordinator owns docs, shared integration/harness/build changes, final review and acceptance. Dependencies/interfaces
  outside allowlists are blockers to report, not permission to read/edit more files.
- No contributor runs Gradle until coordinator grants an explicit exclusive slot. Reports/slot requests are asynchronous:
  `herdr agent prompt gym-coordinator "R05b <role>: ..."` **without --wait**, no wait/poll loops. Report readiness/API
  blockers/completion and stop; corrections remain in these same fresh native sessions through acceptance.
