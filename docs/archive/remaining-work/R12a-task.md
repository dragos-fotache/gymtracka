# R12a — real finite-edit admission and timer publication barriers

## Authority and boundary

Owner resumed and released fresh coordinator ownership for whole-R12 planning and **R12a only**.
The finite [three-slice plan](../../remaining-work-roadmap.md#r12--feature-and-app-reconciliation) is settled.
Stop after independently reviewed/accepted R12a, scoped local commit, prepared durable handoff and owner report.
The owner explicitly requires stopping after handoff preparation: do not launch a successor or begin later implementation.

Deliver the real composition/app-owned edit and timer barrier, wired to current local-only feature factories, and an
explicit request-bound adapter to the existing publisher interface. Production constructs no engine or publisher.
No account activation/visibility, epoch observation, canonical timer-impact reader, navigation remote reconciliation,
Google/account UI, schema/asset changes, network/backend, legacy-data import/deletion or process-death timer redesign.
R12b owns observation; R12c supplies the authoritative canonical timer-impact decision and scoped rebinding. An explicit
callback is required here, never a permissive default; its result is not durable/account authority.

## Required sources

Supplied AGENTS; [architecture persistence/state and timer sections](../../architecture.md#persistence-and-state-flow);
[approved prerequisites](../../sync/integration-design.md#7-autosave-timer-and-ui-publication-prerequisites);
[protected publication contract](../../sync/implementation.md#protected-publication);
[testing proportional/cadence policy](../../testing-strategy.md#proportional-coverage-and-verification-gates);
shared [product behavior](../../../shared-docs/docs/product-behavior.md) navigation, exercise, workout/timer and routine sections;
shared [local finish/ordered capture](../../../shared-docs/docs/backend-contract/android-client-protocol.md#ordered-capture-and-durable-request-identity)
and [workout invariants](../../../shared-docs/docs/backend-contract/domain-rules.md#workouts).
No transport/wire behavior is changed; existing Room publisher account-wide outbox and transaction rechecks remain authoritative.

## Inventory / concrete contracts

Feature classes are internal composition-scoped Kotlin classes in their screen files, not ViewModels:
`ActiveWorkoutState(workoutId, workoutStartMillis, repository: ActiveWorkoutStateRepository, scope)`;
`RoutineListState(repository: RoutineStateRepository, scope)`;
`RoutineEditorState(routineId, repository: RoutineStateRepository, scope)`;
`ExerciseCatalogueState(repository: ExerciseStateRepository, scope, initialSearchQuery="", initialEquipmentIds=emptySet(), initialMuscleGroupIds=emptySet())`;
`ExerciseCreationState(exerciseId: String?, repository: ExerciseStateRepository, scope)`;
`WorkoutHistoryState(repository: WorkoutHistoryStateRepository, scope)`.
Append optional named `edits: FiniteEditCoordinator = FiniteEditCoordinator(scope)` parameters, preserving all current calls.
`GymAppState(repository: GymAppStateRepository, scope, clock, timerHardware)` similarly appends
`val edits: FiniteEditCoordinator = FiniteEditCoordinator(scope)` and
`session: RepositorySession = RepositorySession.SIGNED_OUT`. Existing Android Context constructor stays working.
The same app coordinator must reach every mutating production feature factory through `LocalFiniteEditCoordinator` provided
inside `GymApp`; standalone screens may use their local coordinator fallback. Do not create a fresh coordinator per factory
when the app provides one. Root factory and injected test app construction must both work.

Publisher API already exists (no production changes needed):
```kotlin
data class ProjectionPublicationRequest(val accountId: String, val sessionGeneration: Long, val expectedCacheEpoch: Long)
fun interface ProjectionPublicationBarrier {
    suspend fun acquire(request: ProjectionPublicationRequest): ProjectionPublicationLease
}
interface ProjectionPublicationLease : AutoCloseable {
    fun checkValid()
    override fun close()
}
class ProjectionLeaseInvalidException : IllegalStateException
// RepositorySession(accountId: String?, sessionGeneration: Long), SIGNED_OUT = (null, 0L)
```
`ProjectionStore.publish` acquires outside Room, calls synchronous `checkValid` before/after graph writes and closes once
in finally. It independently rechecks account/generation/epoch, complete baseline and account-wide outbox inside Room.
The barrier must NOT read/write Room or claim visibility/activation. It must be safe when checks/close run on a Room thread.

Fully qualified packages (QA need not inspect production): `FiniteEditCoordinator`, `ActiveRestTimer`, `GymAppState`,
`GymAppClock` and `RestTimerHardware` are in `com.dragosfotache.gymtracka.ui`.
`ProjectionPublicationRequest`, `ProjectionPublicationBarrier`, `ProjectionPublicationLease`,
`ProjectionLeaseInvalidException` and `RepositorySession` are in `com.dragosfotache.gymtracka.data.sync.store`.
The appended constructor parameters and timer/barrier functions are members of the named UI types, not separate imports.

New API, internal under `ui/FiniteEditCoordinator.kt`:
```kotlin
class FiniteEditCoordinator(scope: CoroutineScope) {
    val isPaused: Boolean // Compose observable; admission and validity must also be synchronized
    fun launchEdit(key: String, onAccepted: () -> Unit = {}, block: suspend () -> Unit): Job?
    suspend fun awaitPendingEdits()
    suspend fun pauseAndDrain(): ProjectionPublicationLease
    fun launchExclusiveEdit(key: String, block: suspend () -> Unit): Job?
    fun launchTerminalEdit(key: String, block: suspend () -> Unit): Job?
    fun dispose()
}
```
Normal acceptance is synchronous: register finite work BEFORE returning and before `onAccepted` changes optimistic
state. Paused/disposed callbacks return null, invoke neither callback nor repository, and never alter domain-like drafts
or selection-success/navigation state. Pure presentation typing in the explicit custom-editor dialog is not persistence
acceptance; its Save is gated. Input snapshots (selection collections, custom editor inputs, source IDs, finish/start
choices) are captured at acceptance, not read later from mutable state. Keep successful navigation callbacks inside tracked
work. Loads/tickers/observers are NOT tracked.

All accepted writes use the coordinator's app-lifetime scope (standalone fallback uses the supplied scope). Feature
composition disposal cannot unregister/forget accepted work or retained failures. Serialize actual accepted normal writes and terminal claims in
acceptance order, preserving name/semantic command ordering and preventing previously claimed rest from committing
behind a newer accepted uncheck/autosave. Exclusive transition markers stay OUT of that actual-write predecessor chain:
finish drains preceding writes, then claims/drains its timer without joining itself. New countdowns still start immediately
while their predecessor's terminal persistence is queued.
A failed predecessor does not silently erase queued newer work. Track outcomes, not just Job.join. Retain failures by
stable mutation identity, with newer successful explicit retry/superseding write to the SAME identity able to settle it;
unrelated success/drain/recomposition does not clear a failure. Track cancellation including a job that never started.
Failure ordering refers to the failed edit's originating acceptance order, not the time it happens to fail. A successful
superseding same-key edit already queued before its predecessor fails still settles that older-origin failure.
Do not let an older completion clear a newer failure. Expose failures by throwing from drains; no raw error UI/logging.
Cancellation evidence must remain observable after work leaves the pending collection, including exclusive jobs that
never begin. External drains wait for accepted exclusive transitions too; only their own internal drain excludes the
current transition registration. Reentrant onAccepted callbacks cannot reverse registered FIFO order.

`pauseAndDrain` atomically pauses BEFORE suspension, excludes new normal acceptance, repeats draining registered finite
work and retained failures, and returns a live lease. Reentrant/concurrent pauses fail closed, not deadlock. Failure or
cancellation before lease return always releases this pause. Close is idempotent/nonblocking/nonthrowing and owner-specific;
old close cannot reopen a newer pause. Closed/invalid/disposed leases throw ProjectionLeaseInvalidException on check.
Keep pause held after invalidation until close; no admission gap before a transaction's final check. No joins of timers.
The public pauseAndDrain returns a granted live lease. The adapter may use an internal held-pause acquisition/grant seam
so its intentional pre-grant timer claim does not invalidate the lease it has not yet issued. Grant is atomic with a final
pending-terminal/failure recheck; terminal admission after grant synchronously invalidates the lease. Scope cancellation
invalidates leases and rejects new optimistic admission without cancelling the caller's parent.

`launchExclusiveEdit` reserves the pause synchronously and tracks the transition itself, drains only preceding accepted
work (not itself), then runs block. It releases on every path and retains its own write failure for retry. A same-key retry
must be able to run despite its OWN previous retained failure, but cannot bypass another unresolved edit failure. Used by
app-shell destructive transitions so accepted autosaves cannot become no-ops after finish/discard/replace. Publication
cannot race an exclusive transition; new concurrent actions are rejected. Preserve existing successful behavior and failure
navigation/dialog/timer state. Request-finish is a read-only drain/check with captured-root currentness rechecks after suspension; a stale request cannot
reopen a dialog after that root finishes/disappears. Confirm-finish rechecks under exclusive admission.

`launchTerminalEdit` is only for an ALREADY claimed timer's final persistence, not a bypass for ordinary feature edits.
Register before launch even while paused, synchronously invalidate any publication lease, and serialize terminal claims.
The barrier drain may observe these writes; terminal failures have unique timer-instance keys and never auto-retry/clear.
A terminal write admitted during lease use forces final validity failure/rollback, rather than silently racing publication.
Dispose cancels only owned finite work, invalidates pause/leases and preserves cancellation evidence; never cancels parent scope.

## Feature wiring and local transition safety

Cover every repository mutation: routine name/rest/set/add/delete/save/list delete; all three picker commits; catalogue
custom delete/create/update; history delete/save-as-routine; active name/notes/rest/set/add/delete/completion; app-shell
custom delete/draft creation/draft cancel/start/clone/from-routine/discard/finish; serial terminal rest.
Keep narrow feature repository interfaces and existing Room transactions unchanged. Replace/adapt active finite-edit
tracking without losing its failure retention or latest-set completion guard. `ActiveWorkoutState.awaitPendingEdits()`
remains callable and detects cancellation. Avoid optimistic name/rest changes and set-sequence changes on refused acceptance.
Success/error UI cleanup remains correct (e.g. history isSavingRoutine must not stick after a refused callback).
Existing finish-drain owner registration remains compatible; global coordinator is the authoritative cross-feature barrier.
Leaf autosave text/completion/rest controls must also reject BEFORE changing their remembered persisted-value drafts while
paused (including direct semantics callbacks). Supply an enabled/admission predicate or use the shared CompositionLocal;
retain compact BasicTextField sizing, valid decimal keyboards and existing standalone component defaults. Pure menu/focus/
unsaved dialog draft state can remain presentation-local. Do not treat a refused keystroke as accepted autosave.

Exclusive finish/discard/active replacement drains all accepted feature writes and terminal writes before destructive Room
calls. Finish claims the current timer and awaits success before capture. Explicit local discard/start replacement retains
its existing intentional cancellation semantics for the running timer after repository success; failure keeps that timer
intact. Do not introduce preemptive timer cancellation on a failed local replacement. Failed terminal persistence blocks
finish/publication; do not turn immutable history into a retry target.

## Timer adapter and ownership

Append defaulted fields to `ActiveRestTimer` without breaking four-argument callers:
`workoutId: String? = null`, `workoutExerciseId: String? = null`,
`session: RepositorySession = RepositorySession.SIGNED_OUT`.
Live timers snapshot app session, current root, entry, set and unique instance. Standalone tests may start without an active
root as today; do not infer a cloud owner from missing fields. Bound immutable session plus instance prevents stale callbacks.
Retain countdown/clock/hardware/whole-second behavior, serial claim order and at-most-once claim policy.

Add to GymAppState:
```kotlin
fun publicationBarrier(
    expectedRequest: ProjectionPublicationRequest,
    preservesTimer: suspend (ActiveRestTimer) -> Boolean,
): ProjectionPublicationBarrier
```
Factory binds an exact valid account/nonnegative epoch/positive generation request; acquire rejects any request mismatch.
Caller must already hold the engine/session authority and provide a canonical-graph impact decision at that fenced request;
this adapter grants no such authority. If an active timer's session differs from the requested account/generation, fail
closed, including a timer started by accepted completion DURING the pre-grant drain. Pause/drain accepted edits first,
then recheck resulting timer ownership and evaluate impact for that current timer. True means same active root,
same entry/set still completed; rename/reference-only replacement therefore preserves instance/countdown/hardware. False
claims terminal rest while the OLD set exists and drains it exactly once before returning. Outbox protection is rechecked
by the existing publisher after acquisition: newly captured rest postpones projection. This slice must not invent a canonical
reader or upload/filter/error recovery. False is not permission to drop rest because cloud history is immutable.

Drain existing terminal jobs/failures even when no running timer remains. Preserve origin failures; release pause on impact
callback failure/cancellation. Timer expiry while a lease exists invalidates that lease before terminal write. Timer skip,
adjust, start/uncheck and optimistic app active-name callbacks from NEW user acceptance must not mutate while paused;
an already accepted set-completion callback may start its timer during pre-lease drain and must then be included in impact
assessment. Append `acceptedEdit: Boolean = false` to `startRestTimer(set, durationSeconds, acceptedEdit)`; GymApp passes true ONLY
from the already tracked successful set-completion callback. Public user/direct calls use false. This bypass is limited to
the pre-lease drain; it must invalidate/refuse a granted lease, never allow a late untracked timer to race publication.
Distinguish that accepted completion path rather than indiscriminately suppressing it. No timer ticker join.
Failed rest writes remain terminal (no duplicate retries) AND observable to all later transition/publication drains.

## Independent roles / permitted files

Worker production READ: AGENTS and task references above; app/src/main/java/com/dragosfotache/gymtracka/ui/**,
app/src/main/java/com/dragosfotache/gymtracka/data/gym/** (exclude assets/schemas),
app/src/main/java/com/dragosfotache/gymtracka/data/sync/store/ProjectionStore.kt and SyncSession.kt.
Worker WRITE: ui/FiniteEditCoordinator.kt, ui/GymRepositoryProvider.kt, ui/GymApp.kt, ui/GymAppState.kt,
ui/activeworkout/ActiveWorkoutScreen.kt, ui/activeworkout/WorkoutExerciseComponents.kt,
ui/routines/RoutineScreens.kt, ui/exercises/ExerciseScreens.kt,
ui/workouts/WorkoutHistoryScreen.kt. No tests/fixtures/reports/QA diffs, build files or other production writes.

QA READ/WRITE: app/src/test/java/com/dragosfotache/gymtracka/ui/**,
app/src/test/java/com/dragosfotache/gymtracka/testing/**,
app/src/androidTest/java/com/dragosfotache/gymtracka/ui/**.
QA READ only: app/src/androidTest/java/com/dragosfotache/gymtracka/data/gym/** test support,
AGENTS and task documentation references. No production source/declarations/diffs, Worker work, decompilation or schemas.
Coordinator supplies signatures and owns build/resource integration, final review and docs. Both roles are fresh native
meta/muse-spark-1.3-contributor/high. All questions, blockers and completion reports go DIRECTLY and asynchronously to gym-coordinator using
`herdr agent prompt gym-coordinator '<message>'`, without `--wait`, agent wait or acknowledgment polling.
Do not use interactive question/request-user-input tools for routine contributor questions; those reach the owner UI.
No waiting/polling other agents.
No Gradle before an explicit serialized slot. Lint freezes both role sources.

QA harness facts: JVM TestScope/virtual time and existing TestRepositories.kt/StateFixtures.kt. Fakes are open except
FakeGymAppStateRepository; its restUpdate suspending hook and attempted/committed restUpdates distinguish faults.
FakeRoutineRepository exposes detailsProvider/nameUpdater; FakeExerciseRepository is open and all methods can override;
FakeWorkoutHistoryRepository exposes saveError/saveResult. Existing timers run indefinitely: use backgroundScope or cancel
owned scopes; no advanceUntilIdle over tickers. AppFlowTestSupport uses real in-memory Room and normal GymApp(state)
construction under LocalGymRepositoryOverride. Extend test support with protected appState access if needed. Compose v2
createComposeRule, one setContent, bounded actual-UI/Room waits, dispose/cancel/join before closing DB. No sleeps.

## Risk-selected acceptance

High cross-feature durability/concurrency risk without schema, credentials or production activation.
New distinct obligations:
- admission happens before launch; pause rejects late optimistic edits; repeat-drain catches accepted callbacks;
  cancellation-before-start, failed drains, same-key recovery, unrelated success cannot clear failure, old close isolation;
- representative routine autosave + picker/custom commit + history mutation + active callback use the same app gate;
  captured selections/inputs do not change after acceptance; screen disposal does not lose accepted work;
- finish/discard/replacement cannot overtake accepted edits; failed terminal write blocks finish and later publication with
  one rest attempt only; successful queued rest is drained in order;
- exact request/session binding, impact false terminates/drains old timer, true retains identity/countdown on rename/reference
  replacement; callback cancellation/failure releases pause; expiry during lease invalidates before rest; hardware regression;
- actual Compose/app construction demonstrates shared admission/refusal and autosave surviving routine→picker disposal.

Reuse existing feature/AppFlowTest and publisher transaction/outbox tests; no new full Room canonical matrix. Coordinator
reviews publisher compatibility and all mutation call sites. Required iteration compile/focused JVM. Acceptance: fresh affected
plus full offline JVM; compile instrumentation; execute new focused Compose class and existing AppFlowTest on isolated test-owned
API37 emulator (not personal AVD/device). Debug assembly and unsuppressed lint brought forward for cross-cutting app wiring.
No backend required: no protocol/storage behavior changes. Full unfiltered instrumentation and complete R12 scoped privacy/
remote-flow gates deferred to R12c package closure; API24/hosted CI/Google/phone/two-store HTTP convergence remain their existing
milestones. Missing required emulator/model/gate is a blocker, not a passed/deferred result.

Inspect executed XML class names/counts/failures/skips. git diff --check both trees; preserve asset/schema bytes. Acceptance
review records exact fresh commands/env/results and deferred gates. Extract durable facts, archive task/review, update roadmap/
handoff, commit scoped changes, report to owner and STOP.
