> **Historical record, not current instructions.** See the [archive boundary](../README.md) and [current pickup](../../coordinator-handoff.md).

# R02b — Align workout/routine family identity validation

Status: **implemented and coordinator-accepted** (2026-10-03); see [review](R02b-review.md).

Delivery update: the owner subsequently authorized the full Android working-tree commit, including R02b/R02c and the
existing retirement/HTTP/build/local-doc baseline. See [delivery record](R02bc-commit-review.md). This supersedes the
coordinator's earlier no-commit boundary only; no contributor commit, push or new implementation slice is authorized.
The owner said “ok go ahead with task r02b” after the R02a delivery. This authorizes this model/codec validation slice,
its tests and Android-local delivery documentation, not R02c–e, configuration, backend access, commits or pushes.
R02a's final-commit authorization applied to R02a only. The owner subsequently authorized R02c AFTER R02b finishes;
that separate authorization does not expand this slice. This is the SAME normative specification/API contract for Worker
and QA; their access allowlists differ.

## Goal, authority and non-goals

Enforce known identity-reservation rules at pure validation boundaries without treating independent namespaces as one.
Normative authority: [service-protocol.md, Identity reservations](../../../shared-docs/docs/backend-contract/service-protocol.md),
[Android client protocol](../../../shared-docs/docs/backend-contract/android-client-protocol.md),
[client compatibility](../../../shared-docs/docs/backend-contract/client-compatibility.md) and shared OpenAPI.
Coordinator context: [R01's gap audit](R01-task.md), not implementation evidence. Contributors need this shared
specification and the cited shared contract, not other Android planning/review files.

The client can validate only identifiers supplied in one aggregate/command. It cannot establish account ownership,
account-lifetime reservations, source existence/eligibility or complete-current-source membership without service state.
Do not simulate those checks or add a backend simulator. Room global-PK materialization collision handling stays R05/R09.

No new public API, schema/migration, durable version, dependency, raw JSON grammar, HTTP/auth/UI/scheduler or capture
orchestration. Do not change custom-exercise validators, unrelated numeric/name/state rules or targeted-delete lookup
semantics. No UUID generation/remapping, trimming/case folding, command reordering or frozen-byte rewriting. No personal,
backend or global tool configuration changes. Keep the signed-out production path working.

## Inventory and chosen implementation boundary

Current code is the authority for available APIs; the contract defines required rules:

- `WorkoutValidation.kt` validates input/canonical children and positions but checks entry IDs and set IDs separately.
- `RoutineValidation.kt` has the same gap. Their callers include codecs and pure Room mappings.
- `MutationValidation.kt` validates workout imports through the shared workout child validator. Child plans currently
  reject source/target overlaps only at matching child roles; source-root context is absent. Envelope checks wrongly
  forbid equal root/source UUIDs for the two cross-family commands.
- All three copy payloads expose the source root, new target root and complete ordered child binding plan. Additions
  already reject fresh entry/set/target-root aliases and must retain that behavior.

Prefer small internal, set-based checks shared where useful. Keep existing position sorting, immutable return copies
and public signatures. Internal helper signatures can change within the production allowlist; do not expose helpers
solely for tests. Worker chooses structure; no per-root store/state machinery is needed.

## Required behavior

### A. Full aggregate identities

For every workout/routine full aggregate, the family identity set consists of:

```text
root id + every exercise-entry id + every set id across ALL exercise entries
```

Every member must be distinct across all roles and parents. Reject root=entry, root=set, entry=set (same or different
entry), duplicate entries and duplicate sets within/across entries. Apply this to workout active input, active/finished
canonical records, active/finished workout import snapshots, routine draft/saved input/records and routine imports.
Use `SyncModelException` rather than repairing IDs. New collision failures use fixed context text, no raw body/UUID/cause.
Existing UUID-format/scalar error handling is not a new global redaction assignment.

`exerciseId` is a reference in the independent catalogue/custom-exercise namespace, NOT a workout/routine entry ID.
Repeated exercise references, or reference text equal to a root/entry/set UUID, remain valid shapes. `mutationId` is also
an independent identity; do not ban equality to an aggregate/child UUID. Same UUID values in separate workout/routine
aggregate families remain representable; no global registry is added to pure validators.

All existing public validation/encoding/decoding paths must enforce the aggregate rule consistently. Pure Room mappings
already delegate to those validators and must inherit rejection without edits or writes to storage. Canonical protocol
results containing an invalid full record must reject too.

### B. Clone/copy plans

A binding's `sourceExerciseId` is the source FAMILY ENTRY identifier, not its referenced catalogue exercise UUID.
Define these two role-bearing identifier collections for a copy command:

```text
source = source root + every sourceExerciseId + every sourceSetId across all bindings
 target = new root + every target entry id + every target set id across all bindings
```

**Within each side**, identifiers must be unique across all roles. Source entry=set, source child=source root,
duplicated source child across parents, target entry=set and target child=new root all reject for ALL three commands.
An empty plan and an entry binding with no sets are valid structural shapes; no source lookup/promotion rule is added.

**Between the two sides**:

| Command | Source family | Target family | Source/target UUID overlap |
|---|---|---|---|
| `workout.performAgain` | workout | workout | Reject ANY overlap across all roles, including root/children and off-diagonal entry/set overlaps |
| `routine.startWorkout` | routine | workout | Allow overlap across independent families, including equal roots, equal corresponding children, off-diagonal and root/child UUID equality |
| `workout.saveAsRoutine` | workout | routine | Allow the same independent-family overlaps |

Cross-family overlap does NOT excuse duplication within either side. Same-family target freshness is checked against
all supplied source IDs, not merely its corresponding source binding; account-lifetime freshness still belongs to the
service. Validate consistently when payloads are validated directly, through `validateMutationEnvelope`, through raw
`decodeMutationEnvelope`, and when encoding/capturing/reading durable commands.

Keep kind/target/payload matching, canonical lowercase UUIDs, timestamps, positive generations/revisions, unknown-field
rejection and target uniqueness intact. Preserve child-plan and set-binding order exactly; unlike aggregates' position
sorting, copy bindings have no position field to sort by. Return valid supplied IDs unchanged.

### C. Durability and regression

Unique valid v1 requests retain `requestJson` BYTE-FOR-BYTE, including key order, internal whitespace and number/escape
spelling. `encodePushMutationsBody` embeds that exact text. Equal cross-family UUIDs are valid v1, not a new version.
A supported wrapper with forbidden family aliases rejects as corruption; no repair, discard, row deletion, migration,
new IDs or re-encoding. Unique unsupported wrapper versions remain the distinct unsupported-version failure.

R02a raw duplicate-key rejection remains intact. Other 17-kind DTO/capture behavior remains regression input, not a new
scope. Representing a targeted delete against an unknown source/child still leaves lookup eligibility to the service.

## File and source-access boundaries

All paths below are relative to the Android root.

**Worker writable only:**

- `app/src/main/java/com/dragosfotache/gymtracka/data/sync/model/WorkoutValidation.kt`
- `app/src/main/java/com/dragosfotache/gymtracka/data/sync/model/RoutineValidation.kt`
- `app/src/main/java/com/dragosfotache/gymtracka/data/sync/model/MutationValidation.kt`
- Optional new `app/src/main/java/com/dragosfotache/gymtracka/data/sync/model/FamilyIdentityValidation.kt` for a focused
  internal shared helper. No other production edits without coordinator amendment.

Read-only Worker production navigation: `data/sync/model`, `data/sync/codec`, `data/sync/mapping`, plus AGENTS.md, this
specification and cited shared contract docs. NEVER read/navigate/search tests, fixtures, reports, QA work/transcript or
broad repository diffs. No test runs. Worker may compile production ONLY with an explicit serialized Gradle slot.

**QA writable/readable only:**

- New `app/src/test/java/com/dragosfotache/gymtracka/data/sync/FamilyIdentityValidationTest.kt`
- New `app/src/test/java/com/dragosfotache/gymtracka/data/sync/CopyIdentityValidationTest.kt`

QA may read AGENTS.md, this specification and cited shared contract/OpenAPI/fixtures. NEVER inspect production code,
declarations/diffs/decompilation or Worker work, including through LSP completion/hover/definition. No other tests/harness
sources without amendment. Use independently constructed model/JSON cases, not production encoders as expected-value
oracles. New tests can use public encoders for observed behavior only. No private-helper-name assertions. Ask coordinator
for missing API/harness facts instead of production navigation. Keep tests table-driven/focused; avoid unnecessary fixture
frameworks and huge files. Kotlin raw triple-quoted JSON is preferable where hand-written JSON needs escaping.

Both roles attempt Kotlin mcpls navigation/diagnostics ONLY in their allowed paths. If their session is unconfigured or
still indexing after a bounded attempt, report the limitation and use permitted exact reads/compiler gates rather than
repeated long sleeps; never change global configuration. Compiler invocation implicitly compiling production is not QA
source access. Coordinator owns old regression tests, full source/test review and doc updates. Any necessary old-fixture
correction needs coordinator review; do not teach Worker via existing tests or QA via implementation.

## Existing API facts supplied to both contributors

Model package: `com.dragosfotache.gymtracka.data.sync.model`. Data classes have named parameters and `copy`/value equality.

```kotlin
class SyncModelException(message: String) : IllegalArgumentException(message)
// Public validators return a validated same-type copy; no persistence side effects.
fun validateWorkoutInputModel(input: WorkoutInputModel): WorkoutInputModel
fun validateWorkoutRecordModel(record: WorkoutRecordModel): WorkoutRecordModel
fun validateWorkoutImportModel(snapshot: WorkoutImportModel): WorkoutImportModel
fun validateRoutineInputModel(input: RoutineInputModel): RoutineInputModel
fun validateRoutineRecordModel(record: RoutineRecordModel): RoutineRecordModel
fun validateMutationPayload(payload: MutationPayload): MutationPayload
fun validateMutationEnvelope(envelope: MutationEnvelopeModel): MutationEnvelopeModel

// Constructor parameter order/types (each declaration is a data class):
WorkoutSetModel(id: String, position: Int, repetitions: Int?, weightKg: Double?, isCompleted: Boolean, restSeconds: Int?)
WorkoutExerciseInputModel(id: String, exerciseId: String, position: Int, notes: String, restDurationSeconds: Int, sets: List<WorkoutSetModel>)
WorkoutExerciseModel(id: String, exerciseId: String, position: Int, notes: String, restDurationSeconds: Int, sets: List<WorkoutSetModel>, exerciseName: String)
WorkoutInputModel(id: String, name: String, status: String, startedAtMillis: Long, exercises: List<WorkoutExerciseInputModel>)
WorkoutRecordModel(id: String, name: String, status: String, startedAtMillis: Long, finishedAtMillis: Long?, revision: Long, exercises: List<WorkoutExerciseModel>)
WorkoutImportModel(id: String, name: String, status: String, startedAtMillis: Long, finishedAtMillis: Long?, exercises: List<WorkoutExerciseInputModel>)
RoutineSetModel(id: String, position: Int, repetitions: Int?, weightKg: Double?)
RoutineExerciseInputModel(id: String, exerciseId: String, position: Int, restDurationSeconds: Int, sets: List<RoutineSetModel>)
RoutineExerciseModel(id: String, exerciseId: String, position: Int, restDurationSeconds: Int, sets: List<RoutineSetModel>, exerciseName: String)
RoutineInputModel(id: String, name: String, isDraft: Boolean, exercises: List<RoutineExerciseInputModel>)
RoutineRecordModel(id: String, name: String, isDraft: Boolean, revision: Long, exercises: List<RoutineExerciseModel>)
ChildSetBinding(sourceSetId: String, id: String)
ChildExerciseBinding(sourceExerciseId: String, id: String, sets: List<ChildSetBinding>)
ExerciseAddition(id: String, exerciseId: String, setIds: List<String>)
MutationEnvelopeModel(mutationId: String, kind: MutationKind, aggregateType: AggregateType, aggregateId: String, localGeneration: Long, knownServerRevision: Long?, payload: MutationPayload)
AffectedRootModel(aggregateType: AggregateType, aggregateId: String, localGeneration: Long)
```

`AggregateType`: `WORKOUT`, `ROUTINE`, `CUSTOM_EXERCISE`.
`MutationKind`: `WORKOUT_PERFORM_AGAIN`, `ROUTINE_START_WORKOUT`, `WORKOUT_SAVE_AS_ROUTINE`, `WORKOUT_UPSERT`,
`ROUTINE_UPSERT`, `AGGREGATE_IMPORT`, `WORKOUT_ADD_EXERCISES` (wire spellings follow the contract).
Nested data classes under sealed `MutationPayload`:

```kotlin
WorkoutPerformAgain(sourceWorkoutId: String, id: String, startedAtMillis: Long, childIds: List<ChildExerciseBinding>)
RoutineStartWorkout(sourceRoutineId: String, id: String, startedAtMillis: Long, childIds: List<ChildExerciseBinding>)
WorkoutSaveAsRoutine(sourceWorkoutId: String, id: String, name: String, childIds: List<ChildExerciseBinding>)
WorkoutUpsert(input: WorkoutInputModel)
RoutineUpsert(input: RoutineInputModel)
WorkoutAddExercises(additions: List<ExerciseAddition>)
AggregateImport(record: ImportRecord)
```

`ImportRecord.Workout(input: WorkoutImportModel)`, `.Routine(input: RoutineInputModel)` are nested data classes.

Codec package: `com.dragosfotache.gymtracka.data.sync.codec`:

```kotlin
fun decodeWorkoutInput(jsonText: String): WorkoutInputModel
fun decodeWorkoutRecord(jsonText: String): WorkoutRecordModel
fun decodeRoutineInput(jsonText: String): RoutineInputModel
fun decodeRoutineRecord(jsonText: String): RoutineRecordModel
fun decodeMutationEnvelope(jsonText: String): MutationEnvelopeModel
fun encodeWorkoutInput(input: WorkoutInputModel): org.json.JSONObject
fun encodeWorkoutRecord(record: WorkoutRecordModel): org.json.JSONObject
fun encodeRoutineInput(input: RoutineInputModel): org.json.JSONObject
fun encodeRoutineRecord(record: RoutineRecordModel): org.json.JSONObject
fun encodeMutationEnvelope(envelope: MutationEnvelopeModel): org.json.JSONObject
fun captureDurableRequest(envelope: MutationEnvelopeModel, affectedRoots: List<AffectedRootModel>): DurableRequestModel
fun decodeDurableRequest(jsonText: String): DurableRequestModel
fun encodePushMutationsBody(requests: List<DurableRequestModel>): String
fun decodePushResult(jsonText: String): PushResultModel
fun decodePullResult(jsonText: String): PullResultModel
fun decodeSnapshotResult(jsonText: String): SnapshotResultModel
```

`DurableRequestModel` exposes `payloadVersion: Int`, `requestJson: String`, `affectedRoots`, `envelope`.
`UnsupportedDurableVersionException` is distinct. Wrapper format:
`{payloadVersion:1, requestJson:"<exact inner object text>", affectedRoots:[{aggregateType,aggregateId,localGeneration}]}`.
Pure mapping entry points already call aggregate validators. QA need not inspect or mock Room/mappings; coordinator
reviews mapping delegation and runs existing mapping/capture regression gates as appropriate.

## Harness and scalar fixture facts

JVM tests use standard JUnit4 (`org.junit.Test`, `org.junit.Assert`); no custom runner/rules are needed here. The two QA
classes use package `com.dragosfotache.gymtracka.data.sync`. Exact new message spelling is not mandated: assert the
exception type, nonblank safe text, no supplied UUID/raw body and null cause for new identity-collision cases.

Otherwise-valid scalar examples: startedAtMillis `1000L` / raw `1970-01-01T00:00:01Z`; finish `2000L` /
`1970-01-01T00:00:02Z`. Active status is `active` with null finish; finished status is `finished` with a finish and
at least one nonempty entry whose sets are all completed. A set at position 0 with repetitions 8, weight 20.0,
isCompleted true and recorded rest 3 is valid in both states. Entry notes `""`, configured rest 30, canonical label
`"Exercise"`, revision/generation `1L` and null known revision are valid. Routine draft/saved blank names and empty
children are valid autosave representations; routine sets accept nullable inputs. Save-as-routine command name `"Copy"`
is an otherwise-valid example. Start with distinct role UUIDs and change only the identity under test.

Wire envelope uses `type`, not Kotlin `kind`; UTC strings use `startedAt`/`finishedAt`, not `*Millis`.
Protocol UPSERT has `{aggregateType,aggregateId,operation:"upsert",revision:1,record:<same-id/revision canonical>}`.
Batch has `{batchId:<UUID>,cursor:"opaque",changes:[upsert]}`; push has
`{results:[{mutationId,status:"accepted",batch}]}`, pull has
`{batches:[batch],nextCursor:"opaque",hasMore:false}`, snapshot has
`{records:[upsert],baselineCursor:"opaque",hasMore:false}`. Standard test `JSONObject.quote(innerExact)` may quote the
durable wrapper's string, but the inner request and full expected push body must be independent of production encoders.

## Independent acceptance matrix

| Case | Expected |
|---|---|
| Root=entry, root=set, entry=set same entry or different entry | Reject for every full aggregate/import representation |
| Duplicate entries / duplicate sets within/across entries | Existing rejection preserved |
| Distinct family IDs, reordered aggregate arrays with valid positions | Accept; expected values/nulls/IDs and zero-based position sorting unchanged |
| Draft/saved routine blank/empty shapes; valid finished workout/import | Accept independently of identity checks; finish invariants preserved |
| `exerciseId` numerically matches root/entry/set or repeats | Accept as independent reference, not a false family duplicate |
| Source root=entry/set or source entry=set (including across bindings), duplicate sources | Reject for ALL copy commands |
| Target root=entry/set, target entry=set/duplicates across bindings | Reject for ALL copy commands |
| Perform-again source/target root/entry/set cross-product, including off-diagonal and different bindings | Reject every overlap, not just matched roles |
| Routine-start/save-as-routine source/target cross-product | Accept overlap when each side remains internally valid |
| Same root/source UUID with empty copy plan | Perform-again rejects; both cross-family commands accept |
| Empty child plan / binding with empty sets, arbitrary binding order | Preserve valid shape and exact order; no lookup or sorting |
| Direct payload/model validation versus envelopes, raw decoders, encoders | Same identity rule, no constructor bypass |
| Workout/routine upsert and both imports carrying forbidden aliases | Reject through envelope/raw/durable route |
| Canonical protocol record with role alias in push/pull/snapshot | Reject full result, not repair/partial acceptance |
| Cross-family copied v1 durable request with unusual whitespace/key order | Exact requestJson and hand-authored full expected push body preserved |
| Supported corrupt wrapper with forbidden IDs; unsupported version | Corrupt rejects without rewriting; unsupported distinct behavior preserved |
| Mutation ID equal to root, repeated catalogue refs, existing addition-root guards | Independent identities accepted; existing local-target guards retained |
| New collision failures | SyncModelException with safe fixed context and no cause; no generated/repaired IDs |

Use valid counterexamples: vary ONLY identities in an otherwise valid fixture. No negative test should pass due to
bad positions, missing fields, status/timestamp/finished-state errors. Where tables collapse many cases, retain useful
labels/diagnostics. Do not treat method counts as the acceptance criterion or make production encoders the oracle.

## Serialized execution and stop

Worker authors its slice, reports ready-to-compile and stops. QA independently authors its two test files, reports
ready-to-run and stops. Use `herdr agent prompt gym-coordinator '<role report>'` WITHOUT `--wait`; no `agent wait`,
user questions or direct Worker/QA coordination. Coordinator supplies missing facts and amendments to both roles.
No Gradle until the coordinator grants the relevant exclusive slot. No commits, staging, resets or cleanup.

Worker gate:

```sh
./gradlew :app:compileDebugKotlin --console=plain
```

QA gate after production compile and permission:

```sh
./gradlew :app:testDebugUnitTest --tests 'com.dragosfotache.gymtracka.data.sync.FamilyIdentityValidationTest' --tests 'com.dragosfotache.gymtracka.data.sync.CopyIdentityValidationTest' --rerun-tasks --console=plain
```

If a compile/test gate fails, STOP and report the concrete failure rather than entering an unbounded edit loop.
Coordinator reviews and supplies bounded corrections in the same session. LSP unavailability is recorded, not a reason
to edit configuration or skip compiler/test gates. Final coordinator gate is forced full offline JVM plus production
compilation, debug assembly/lint, Android-test Kotlin compilation and both diff checks; inspect actual XML names/counts/
failures/errors/skips. No new device/backend pass is required by this pure model slice, and none may be invented.

Update architecture/testing/roadmap/handoff and `R02b-review.md` to actual evidence. Preserve the R02a commit and all
prior dirty-tree work. Stop this slice after R02b review. The owner's subsequent explicit R02c authorization permits
its bounded planning/execution next, not an automatic R02d–e, commit, push or configuration/deployment step.
