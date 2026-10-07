# R12b — Projection observation and feature refresh

## Authority and stop

Owner-authorized R12b only, after verified ownership release. R12a is accepted at `8a304ca`.
The finite R12 plan remains three slices. Stop after independent acceptance, this slice's scoped local commit,
prepared handoff and owner report. Do not launch a successor or implement R12c.

Normative references: [workflow](../../coordinator-workflow.md), [publication gate](../../sync/integration-design.md#projection-gate),
[autosave/UI requirements](../../sync/integration-design.md#7-autosave-timer-and-ui-publication-prerequisites),
[publication implementation](../../sync/implementation.md#protected-publication),
[finite admission](../../architecture.md#finite-edit-admission-and-publication-leases),
[testing policy](../../testing-strategy.md#proportional-coverage-and-verification-gates), and
[product rename behavior](../../../shared-docs/docs/product-behavior.md#exercise-catalogue).
Shared contract: [agreed behavior](../../../shared-docs/docs/backend-contract/README.md#agreed-first-release-behavior)
(custom renames, privacy and current-name resolution). No wire/type/schema changes are needed.

## Goal, inventory and non-goals

Room12 already commits `materialized_epoch` with each complete selected-account projection. Canonical cache changes,
baseline-only advances and dirty markers are not publication signals. Repository reads already select the immutable
`RepositorySession` scope inside `SyncStore.withFencedTransaction`; they must never follow a new device account.
Feature states currently load once or explicitly reload; remembered text keyed only by row ID misses same-ID changes.
Routine names initialize once. Several post-edit reads can replace a newer accepted draft.

Deliver observation through narrow repository interfaces and consumption by exercise catalogue/pickers/detail,
routine list/selected detail/editor, history list/selected detail and active-workout feature states. Every selected
custom reference must resolve from current scoped exercise data, even at equal parent revision. Existing scoped DAO
read models already provide joined current labels; reload those models rather than storing convenience labels.

No publisher/engine construction, account visibility grant, account/session activation/rebinding, app navigation or
active-pill reconciliation, canonical timer-impact decisions, timer redesign, new framework, schema/migration,
Google/account UI, legacy-data disposition, backend operation or live journey. Those remain R09h/R10/R12c/R11/R13.
The explicit-save custom creation/editor draft is not an autosaved feature and is not reconstructed by this slice;
never overwrite its unsaved draft. All current finite admission, completion, rest and failure guarantees remain binding.

## Repository observation contract

Add `internal interface ProjectionObservationRepository` in `FeatureRepositories.kt`:

```kotlin
fun observeProjectionEpoch(): kotlinx.coroutines.flow.Flow<Long> = kotlinx.coroutines.flow.flowOf(0L)
```

ExerciseStateRepository, ActiveWorkoutStateRepository, RoutineStateRepository and WorkoutHistoryStateRepository extend
it. The compatibility default makes old hand-written fakes inert; production GymRepository MUST override it.
Do not add it to the app-shell interface or start app-wide reconciliation.

For an account-bound repository the cold stream emits its current `materialized_epoch` initially (0 if absent),
then distinct selected-scope materialized epochs only, after committed Room invalidations. Canonical/baseline-only/
dirty/outbox changes and other-account epochs must not produce a new epoch. Multiple fast complete publications may
conflate to the newest epoch; this is a reload trigger, not a counted event log. Observe device-state invalidation too,
so a revoked session fails closed even if no selected cache row changes. Every value is read inside the existing
immutable session fence, before distinct filtering. Negative/out-of-range metadata (`cacheEpoch < 0`, `materializedEpoch < 0`, or
`materializedEpoch > cacheEpoch`) must fail closed; never treat cache progress as visible publication. No particular
new exception type is required for invalid metadata; failure with no invalid emission is the contract. Cancellation detaches the observer, with no writes/capture.

Signed-out/local-only production emits only the initial fenced 0. It exposes no retained account epochs. It still
uses existing explicit refresh/navigation for local mutations; a projection stream is not a universal local change log.
No missing/complete cache metadata grants visibility or changes authority. Session mismatch throws existing
`StaleSessionException`; the old repository never follows the new scope. No new entity/schema/DAO write is needed.
Prefer Room invalidation flow plus a fenced metadata reread; no polling, singleton repository or global observer.

## State/load contract

Each covered state collects its repository stream in the existing composition-owned scope and reloads its selected
model on initial and changed epochs. Keep observer/ticker/load jobs OUTSIDE the finite edit registrations.
Default initial observation and constructor loading must not create duplicate uncontrolled startup loads.

Every model load uses a latest-request/accepted-edit generation guard. A load started before a newer refresh or edit
may never install afterward, including a cancellation-ignoring fake. Invalidate outstanding loads synchronously in
`onAccepted`; refusal must not invalidate, mutate drafts or invoke writes. For autosaved field states, observer/explicit
loads wait for accepted finite work before reading (outside tracked edits), then recheck their generation. Catalogue/
picker summaries and routine/history action-list models are pure persisted read models, not autosaved domain drafts:
initial and epoch reads must remain usable without waiting for unrelated app writes/failures (including a queued
predecessor). Their local confirmation drafts are independent presentation state. After an owned mutation that changes
that read model (such as delete), follow its registered job with a fresh guarded read so signed-out initial0 observation
still reflects the committed result. Preserve filters/selection/dialog drafts and actual acceptance ordering; never
clear finite failures or alter the finite coordinator to unblock queries.
Post-edit refresh must not join its own tracked write. In-write reads needed for set-completion callbacks remain independent and must
not install older data over a newer accepted edit. Track generation around those reads as well.

Successful edits trigger a fresh guarded reload as needed for persisted/sanitized data; failures remain in the shared
finite coordinator, never cleared/retried by observation. Preserve accepted name/rest/set/notes drafts while their
writes are outstanding or failed; do not let an old read roll them back. Normal new publication can then replace the
persisted values. Do not start/end a timer on observation or rename, and do not invoke completion callbacks from reloads.

Expose `var refreshFailed: Boolean` with private setter on each covered state: false initially/on successful newest
load, true on a newest non-cancellation load/stream/drain failure. It is diagnostic state, not private error text or a
new UI. Retain accepted drafts on ordinary failure; on `StaleSessionException` invalidate loads and clear visible
selected models (and routine editor name), without adopting a new repository/account. R12c still owns actual scoped
reconstruction/navigation. Preserve cancellation, no catch-and-report success, no uncaught observer exception crashing
composition. Disposal stops observation/load work but not app-owned accepted writes.

Preserve search, filters, list restoration, picker selection and dialog drafts on refresh. Remove selected picker IDs
only when the latest complete exercise list proves they no longer exist. A rename must preserve selection. Lists and
selected details refresh independently; deleting a selected root yields null details, not a fabricated object or
navigation change. Pure read-only states need not drain finite writes.

## Remembered fields and focus

Keep existing BasicTextField controls and stable row/field identities. Refresh same-ID workout name, notes,
configured rest, weight/reps/completion and routine name/rest/weight/reps from the latest installed model.
Do not key the whole field/card/subtree by epoch or recreate focus nodes. Do not reset menus, dialog drafts or swipe
state incidentally. Dialog draft values remain local until confirmed.

Raw accepted text is not identical to its parsed persistence representation. When incoming weight/reps equal the
currently accepted draft's sanitized parsed value, retain raw text (for example `12.00` or incomplete input that stores
null). A genuinely different persisted value replaces stale text without clearing focus. String name/notes reconcile
exactly; rest displays reconcile to the committed integer. State generation/drain guards prevent old pending snapshots
from masquerading as new persisted values. Small internal reconciliation helpers are allowed; do not expose private
composables solely for tests. Existing accepted/rejected callback semantics remain unchanged.

## Contributor ownership

One fresh native Worker and QA per this slice, `meta/muse-spark-1.3-contributor`/high. Coordinator owns this specification,
integration decisions, docs, final review and resource grants. No role may inspect opposite-role sources/diffs/reports.

Worker read/write allowlist:
- `app/src/main/java/com/dragosfotache/gymtracka/data/gym/FeatureRepositories.kt`, `GymRepository.kt`.
- `app/src/main/java/com/dragosfotache/gymtracka/ui/exercises/ExerciseScreens.kt`.
- `app/src/main/java/com/dragosfotache/gymtracka/ui/routines/RoutineScreens.kt`.
- `app/src/main/java/com/dragosfotache/gymtracka/ui/workouts/WorkoutHistoryScreen.kt`, `WorkoutHistoryDetailScreen.kt`.
- `app/src/main/java/com/dragosfotache/gymtracka/ui/activeworkout/ActiveWorkoutScreen.kt`, `WorkoutExerciseComponents.kt`.
- `app/src/main/java/com/dragosfotache/gymtracka/ui/activeworkout/RestTimerComponents.kt`: ONLY configured-rest
  `RestDurationPickerColumn` settling effects and their required imports. Opening/settling must quiesce rather than
  self-trigger another snap animation. Countdown/RestTimerBar/lifecycle/hardware remain outside this allowance.
- New small internal observation/reconciliation helpers under `ui/util/`.
Worker additional READ ONLY: `data/gym/` entity/DAO/Database declarations required for scoped reads,
`data/sync/store/SyncSession.kt`, `SyncStore.kt`, `ui/FiniteEditCoordinator.kt`, `ui/GymRepositoryProvider.kt`,
`ui/util/WorkoutFormatters.kt`. Do not change those read-only dependencies without reporting to coordinator.
No test, fixture, test report or QA-work access. No app-shell/engine/session-authority implementation changes.

QA read/write allowlist:
- Existing `app/src/test/java/com/dragosfotache/gymtracka/ui/` tests and their test-owned fixtures.
- READ ONLY `app/src/test/java/com/dragosfotache/gymtracka/testing/` shared JVM fakes/fixtures; subclass them in new
  tests. Shared fake edits require a specific coordinator assignment.
- New projection refresh JVM tests in those feature packages (or `ui/ProjectionRefreshStateTest.kt`).
- New `app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/ProjectionObservationTest.kt`.
- New `app/src/androidTest/java/com/dragosfotache/gymtracka/ui/ProjectionFeatureRefreshTest.kt`.
QA additional READ ONLY: existing Android tests/fixtures in `data/gym`, `data/sync`, `ui`, exported Room12 schema,
and `app/build.gradle.kts` as test-harness configuration only. No production declarations/implementation/diffs,
decompiled source, Worker reports or unrestricted LSP navigation. Request missing signatures from coordinator.

Both may read this spec, supplied AGENTS and the normative task-relevant documentation sections. No blanket contract
suite reading. Reports/questions/completion asynchronously to live `gym-coordinator`; never `--wait`, agent wait or
acknowledgment polling. Every Gradle/device operation requires an explicit coordinator-granted serialized slot.
Lint freezes both roles' source edits. No pushes or global/private configuration/personal device/account changes.

## QA API and harness facts

Existing constructor/mutation signatures and fake methods appear in the permitted existing JVM tests; they remain
unchanged except the inherited optional observer and `refreshFailed` stated above. Covered classes:
`ExerciseCatalogueState(repository,scope,initialSearchQuery="",initialEquipmentIds=emptySet(),initialMuscleGroupIds=emptySet(),edits=FiniteEditCoordinator(scope))`,
`ExerciseDetailState(exerciseId,repository,scope)`, `RoutineListState(repository,scope,edits=...)`,
`RoutineEditorState(routineId,repository,scope,edits=...)`, `RoutineDetailState(routineId,repository,scope)`,
`WorkoutHistoryState(repository,scope,edits=...)`, `WorkoutHistoryDetailState(workoutId,repository,scope)`,
`ActiveWorkoutState(workoutId,workoutStartMillis,repository,scope,edits=...)`.
Model properties: catalogue `summaries`, `selectedExerciseIds`; lists `routines:List<RoutineDetails>?`/
`workouts:List<WorkoutHistorySummary>?`; selected states `details`; routine editor additionally `name`.
`RoutineDetails(routine:RoutineEntity,exercises:List<RoutineExerciseDetails>)` exposes name through `.routine.name`.
`WorkoutHistorySummary(workout:WorkoutEntity,exercises:List<WorkoutExerciseHistorySummary>)` exposes name through
`.workout.name`. Neither constructor has defaults. Optional independent reference-summary fixture:
`WorkoutExerciseHistorySummary(workoutExerciseId:String,workoutId:String,position:Int,exerciseName:String,setCount:Int,
volumeKg:Double,bestSetWeightKg:Double?,bestSetRepetitions:Int?)`, no defaults. Fake lists return their supplied models
without mapping. Existing ExerciseSummary fixture shape (`exercise`, `muscleGroups`, `equipment`) remains unchanged.
Each state has `refreshFailed`. Use a fake override `observeProjectionEpoch()` returning
MutableStateFlow<Long>; suspend read/write with CompletableDeferred gates, virtual time, no sleeps.

Production `GymRepository(database: GymDatabase, session: RepositorySession = SIGNED_OUT)` implements all feature
interfaces. `RepositorySession(accountId:String?,sessionGeneration:Long)` is immutable. Existing Room test fixtures
and R05b/R05c publication builders may be reused to publish real complete graphs and independently assert same-ID/
unchanged-revision/current labels. Existing `GymDatabaseTest` provides in-memory Room and test insertion helpers;
Room12 schema is the authority for raw SQL fixture/oracles. New observation stream is Flow<Long> as specified.
Use existing test-owned R05c barriers only in store instrumentation or initial setup before a screen is live.
Live selected-feature Compose publication injects one shared real `FiniteEditCoordinator` into the feature state and
its publication barrier; acquire the coordinator's real `pauseAndDrain()` lease on the owner/Main dispatcher.
These isolated leaf fixtures explicitly have no running timer; do not substitute a permissive production timer-impact
callback or activate root/account navigation. Exact interface: `fun interface ProjectionPublicationBarrier {
suspend fun acquire(request:ProjectionPublicationRequest):ProjectionPublicationLease }`, where request is
`ProjectionPublicationRequest(accountId:String,sessionGeneration:Long,expectedCacheEpoch:Long)`.
`FiniteEditCoordinator.pauseAndDrain():ProjectionPublicationLease`. Existing R05c store builder accepts `barrier=...`.
Use the existing asynchronous Main acquisition harness, never block the Compose test thread awaiting work requiring it.

Compose may inject a repository through `LocalGymRepositoryOverride` and existing `AppFlowTestSupport`, or explicit
feature state parameters on screens. Use actual public/internal feature screens, not newly exposed private text fields.
Existing `ActiveWorkoutScreen`, `RoutineEditorScreen` and test semantics/tags are shown in permitted Android tests.
The retained `ActiveWorkoutScreen` and `RoutineEditorScreen` calls in `FocusedComposeInteractionTest` remain current.
Exact selected constructors (internal classes, no defaults): `ExerciseDetailState(exerciseId:String,
repository:ExerciseStateRepository,scope:CoroutineScope)`, `RoutineDetailState(routineId:String,
repository:RoutineStateRepository,scope:CoroutineScope)`, `WorkoutHistoryDetailState(workoutId:String,
repository:WorkoutHistoryStateRepository,scope:CoroutineScope)`. `RoutineListState` has private constructor properties
`repository:RoutineStateRepository, scope:CoroutineScope, edits:FiniteEditCoordinator = FiniteEditCoordinator(scope)`.
Catalogue additionally exposes `searchQuery:String`, `selectedEquipmentIds:Set<String>`,
`selectedMuscleGroupIds:Set<String>`; all observable model/selection properties have private setters.
The required refresh diagnostic declaration is `var refreshFailed by mutableStateOf(false); private set` (or an
observably equivalent Boolean property); it is new normative API, not a claim it already exists before implementation.
Active and routine editor retain public `refresh()`; other states' private load entry points need not be exposed.
`GymRepository`'s override is `override fun observeProjectionEpoch(): Flow<Long>` on its internal class; public member,
no suspend. RepositorySession/SIGNED_OUT/StaleSessionException retain their existing names and constructor semantics.

Requests for missing public/internal signatures go to coordinator. Same-ID field tests must verify observable text,
focus retention, accepted formatting and no spurious mutation/completion/timer callback. No account shell activation.

## Risk-selected verification before acceptance

Risk: cross-feature refresh and scoped asynchronous reads, not activation/schema/security configuration changes.
Distinct new obligations:
1. Real Room observation: initial selected epoch; actual complete publication; equal-parent custom rename joined labels;
   no signal for canonical/baseline-only/dirty/foreign progress; rollback/no-op isolation; stale session never follows B;
   signed-out cannot observe retained private epochs. Use real Room, not DAO mocks.
2. Virtual-time states: each distinct feature loading path consumes epochs; same-ID name/rest/set/notes refresh; selected
   routine/history and picker rename with preserved filters/selection; changed epoch rejects an older blocked load;
   newer accepted draft rejects old read and remains durable; refused admission unchanged; failure/cancellation/disposal
   do not report success or enroll observers/tickers in finite drains. Representative shared-helper failures suffice;
   do not duplicate a Cartesian account/type/race matrix.
3. Compose: actual active and routine editor same-ID fields update; stable focused input retains focus and accepted raw
   numeric text on equal-value refresh/rename; genuine changed value updates; refusal still leaves displayed draft intact.
   Real repository+Room and real shared finite admission for at least one complete-publication-to-selected-feature
   journey. Open active/routine rest-dialog drafts survive a changed persisted countdown while the dialog is live;
   Done confirms the retained draft rather than silently adopting the remote initial value. Snapshot dialog initial
   input per opening; opening, Done and disposal must pass strict Compose idleness, including the configured-rest wheel's
   settling effects. A no-op/own animation must not re-trigger or cancel its own snap loop. No timeout relaxation,
   suppressed idleness failure, rest-timer lifecycle redesign or new private composable exposure.

Reuse existing R12a finite admission/timer/completion tests and R05 publication validation/fence/rollback coverage;
do not repeat their full matrices. Iteration: changed-source compilation plus affected JVM classes; instrumentation
compilation then individual new device classes. Acceptance: freshly executed full offline JVM, new scoped Room/Compose
classes and relevant existing `AppFiniteEditGateTest`/active/routine leaf regression classes, Kotlin/KSP compilation,
both diff whitespace checks and reviewed asset/schema preservation. Exact existing leaf classes will be identified
before execution from live test files. Full unfiltered instrumentation, assembly/lint/APK and private-scope/account/
app-timer integration remain R12c package closure; no fresh full pass claimed by this slice. Bring broader gates forward
if changes unexpectedly touch packaging, authority/schema or cross-feature activation; escalate scope questions.

Record actual command/environment/XML execution evidence in one acceptance review, including deferred gates. Extract
current implemented facts into architecture/sync implementation/testing owners, archive spec/review, replace current
roadmap/handoff, inspect scoped staging/commit and both post-commit trees. No acceptance while required gates fail.
