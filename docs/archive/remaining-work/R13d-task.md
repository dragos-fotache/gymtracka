# R13d — foreground/edit/manual sync and confirmed ordinary recovery

## Authority and boundary

The owner explicitly renewed authority to start R13d after the R13c stop. This is the fourth and final slice of the
finite personal R13 plan, not a new split. R13a–c, R09a–h, core R10a, personal R11a–b and R12a–c are accepted dependencies.
Close the personal package with independent source review and the declared gates. No live-readiness claim follows.
No deployment, push, private configuration discovery/change, signing/OAuth change, actual Google/account/phone action,
background scheduler, switching/sign-out, general chooser, lifecycle/export/deletion, schema or timer redesign.

Normative references (read task-relevant sections, not the entire graph):
- `shared-docs/docs/backend-contract/android-client-protocol.md`: ordered capture/durable identity, first import, snapshots.
- `shared-docs/docs/backend-contract/service-protocol.md`: sync identity/atomicity, errors, pull and frozen snapshots.
- `shared-docs/docs/backend-contract/open-decisions.md`: P16–P18.
- `shared-docs/docs/backend-contract/openapi.yaml`: MutationEnvelope, PushResult/MutationOutcome, PullResult/SnapshotResult
  and directly needed referenced record types. No new wire model or endpoint.
- `docs/sync/integration-design.md`: exclusive ownership/rejection policy, snapshots, personal scope/startup, autosave/timer.
- `docs/sync/implementation.md`: ACTIVE reconciliation, scoped runtime, private import, account presentation.
- `docs/architecture.md`: finite edits, runtime/refresh and rest ownership; `docs/testing-strategy.md`: proportional gates
  and isolated backend/Room relay procedure; shared product personal scope and UI conventions.

## Existing seams (coordinator-verified facts)

`AppSessionCoordinator.observeCacheEpoch(session): Long` performs a fresh fenced metadata read.
`reconcile(session, expectedCacheEpoch, maxPushMutations=100, maxPullPages=100, pullPageLimit=100,
maxSnapshotPages=100, snapshotPageLimit=200, restartStageId: String?=null): AppReconciliationResult` owns one facade
mutex and physical-DB run across push/pull, typed snapshot/tail and real paired publication. Its stops are TAIL,
PROTECTED, PUSH_STOPPED, PULL_STOPPED, SNAPSHOT_STOPPED, SNAPSHOT_RESTART_REQUIRED, RETAINED_STAGE. Result fields include
cacheEpoch, delivery, snapshot, publication and retainedStageId; fixed display, observations not authority.
Retained-stage UUID must match current evidence; UUID provider is validated before abandoning it. No implicit restart.
`AppSessionState.Active.session` is an exact process handle, not reusable identity DTO. Currentness is synchronous and
rechecked inside/after transactions and HTTP. Recoverable network/auth stops leave matching offline editing alive.
`AccountGymAppState.visibleBinding()` selects only the exact synchronous live handle/repository/app state.
`FiniteEditCoordinator` owns ordered normal/exclusive/terminal writes and retained failures; only `settleSuccess` proves
successful block completion. Publication/transition barriers pause/drain real accepted work, reject explicit-save custom
editors and persist elapsed rest at most once. They cannot be replaced with no-op barriers.
`PushDeliveryLoop.deliver(run, maxMutations=1)` can settle one frozen head through existing exact request/refresh/outcome/
ack machinery under an admitted run, without sending a successor. Backoff/error checks remain binding.
`SyncDao.getOutboxForAccount` returns full ordered rows. Full row identity includes sequence, IDs/type/generation/version,
exact payload, state/attempts/retry/error columns. Pending rows may coalesce; comparing IDs/counts alone is insufficient.
`SyncStateEntity` contains nullable cursor and nullable last-success. Missing cursor forces complete snapshot, never
null-cursor incremental bootstrap. `SyncCanonicalDao.markProjectionDirty` exists. Runtime database remains Room14.

## Required behavior

### Foreground/edit/manual controller

Add a separate composition-owned `AccountSyncState`; do not expand mandatory startup recovery into HTTP.
Construction/startup remain inert until explicit foreground lifecycle admission. Production root supplies ON_START/
ON_STOP (including current already-started state); no background scheduling or network on constructor alone.
Foreground entry with a current ACTIVE handle queues one bounded reconciliation. First activation/new handle while
foreground queues one. Returning foreground queues one; recomposition and same-handle delivery/status emissions do not.
Actual successfully committed finite writes emit a monotonic conflated notification: normal, exclusive and terminal
successes; no optimistic admission, failed/cancelled write, load, status observer or publication notification. The
notification must not throw back into successful persistence or clear retained failures. Existing constructors remain
compatible. Include account-root explicit-save custom-editor persistence if it bypasses finite edits: wire its successful
save to the same account-bound signal without autosaving drafts or duplicating mutations.
Debounce edit bursts (default 500ms, injectable for virtual-time tests), serialize controller work and coalesce requests.
Observe exact-handle committed notifications lazily/event-driven even when ACTIVE authority precedes feature-runtime
binding; no polling, alternate scope or permanently swallowed absent-binding observation. Establish the initial raw
counter before debounce, and never discard a newly observed nonzero commit as if an earlier run necessarily saw it.
A real newer commit during a run schedules a subsequent fresh-epoch run; do not consume it as if the first run saw it.
Raw committed counters known before a fresh run's admission/epoch read are covered by that run; their delayed debounced
replay must not manufacture a successor. A counter first attached after an earlier tail is new demand. Track only the
exact current handle; handle replacement cannot inherit the prior handle's observed/covered counter.
No in-run spin/retry around PROTECTED, retained stage/rejection, backoff, network or reauth stops. No retry generated solely
by an operation's result/status/invalidation. Genuine future edits/foreground/manual actions may retry, respecting durable
backoff. Bounded mutation/page budgets are work limits, not domain caps; budget stops remain visible/manual retryable.

Manual Sync now performs the same fresh-epoch exact-handle reconciliation without bypassing backoff/rejection. Manual
or destructive actions while another operation is busy/retiring or authority is non-ACTIVE are nonqueueing no-ops; they
must not reserve a slot permanently or poison later activation. Genuine automatic foreground/edit demand remains
coalesced through retirement. Active sync cancellation/disposal/ON_STOP cancels and fences owned work; HTTP uncertainty
is retained unchanged. New invocations cannot overlap the retiring operation. Retirement completion releases busy/slot
ownership when no newer invocation owns it. Cancellation never cancels the borrowed scope; a fresh manual action after
retirement remains eligible. Handle replacement cancels old observations/requests/confirmations and late results
cannot replace new presentation. Same-handle status changes do not reconstruct feature state/navigation/timer.
Missing audience disables Google only, not established-account synchronization. No private/import HTTP automation.

### Deliberate retained-stage restart

Display a retained-stage stop safely. An explicit restart action captures the observed exact handle and stage UUID;
confirmation warns that staged download will restart (not ordinary local-intent deletion). Revalidate handle/generation
and exact current stage at execution, then delegate to existing `reconcile(...restartStageId=...)` with a fresh epoch.
Stale/dismissed/foreign callbacks cannot abandon another stage. Retained import preparation remains private and uses its
existing explicit continuation policy; this ordinary ACTIVE controller must not adopt it.

### Confirmed ordinary rejection recovery

Use opaque process-local confirmation objects owned by the exact facade/handle. Presentation exposes only captured
pending count, never account IDs/requests/errors/credentials. A token is not durable or transferable authority.

1. Explicit preparation may settle the already-frozen uncertain head unchanged, using existing machinery and respecting
   durable backoff/retained errors. Do not freeze/send a previously pending successor just to obtain a recovery decision.
   If frozen settlement is accepted, acknowledge exact original result; if rejected, retain original rejection. No generic
   HTTP error establishes definitive rejection. Unsettleable malformed/corrupt/unsupported/oversize/unknown evidence
   refuses unchanged rather than deleting it. No pull/snapshot/cleanup during preparation. If no validated definitive
   ordinary rejected head remains, return without acquiring the real editor/timer pause/drain barrier: clean/pending,
   malformed, retained-error or import-only observations cannot end a running timer merely to report no confirmation.
2. Prepare confirmation only for a validated definitive ordinary rejected head and validated pending/rejected remainder,
   no remaining frozen uncertainty and no selected snapshot stage. Strict legacy import proof must permit ordinary
   work: original import rows/source/progress/receipts are never discard targets; corrupt/incomplete/rejected import refuses.
   Fully settled retained legacy evidence must not disable recovery of later ordinary edits forever.
3. Pause/drain accepted real edits and terminal rest before capturing the full ordered remaining row snapshot, cache
   epoch, exact selected authority and legacy evidence. Close the lease before showing confirmation. No network or
   Room transaction spans the user's decision. If draining adds a rest edit, that is included in the captured count.
4. Confirmation warns that ONLY these unsynchronized ordinary changes will be discarded and the complete cloud
   snapshot reloaded; already accepted server writes cannot be undone. Dismiss/cancel has zero deletion effects.
5. On confirm, own the same facade mutex/run owner, acquire a fresh real pause/drain lease, and compare the complete
   captured rows/authority/epoch/legacy proof immediately before destructive SQL. A new/coalesced edit, changed row,
   different handle/stage or repeated/foreign token refuses with all remaining intent intact and requires a fresh decision.
   There must be no frozen work silently abandoned, even if injected/raced after preparation.
6. One fenced Room transaction deletes only those captured exact ordinary rows, resets selected cursor to null while
   preserving recorded last-success, and marks projection dirty. Preserve domain graph, canonical roots/watermarks,
   credentials, foreign scope, catalogue and all original import evidence. Verify affected row counts and postwrite
   state. Recheck lease/process/session/epoch/caller fences AFTER final SQL before commit. Rollback on any fault.
7. Release the short drain lease before network. On the same run use existing complete snapshot/tail/protected publication
   machinery (no second facade/run admission). Newly admitted edits remain protected and are never included in the discard.
   Failure/cancellation after committed discard keeps the old visible graph and durable null-cursor/retained-stage marker:
   restart must require complete snapshot, never publish old canonical state as a recovered success. Cancellation/lost
   return may follow commit; no compensating reinsert, cleanup, duplicated discard or success inferred from queue absence.

No schema change is intended. If existing state cannot safely encode crash recovery, escalate to coordinator/owner before
changing schema or splitting the slice. No scope broadening to arbitrary corruption or import cleanup.

## Normative API to add (internal Kotlin)

Package `data.sync.session`:
- `class AccountRecoveryConfirmation` with `val pendingCount: Long`, opaque constructor/retained fields and fixed redacted
  `toString`; owner/handle/snapshot checks enforced by facade, not consumers.
- `suspend fun AppSessionCoordinator.prepareOrdinaryRecovery(session: AppAccountSession): AccountRecoveryConfirmation?`
  (member method): null means no safely confirmable ordinary rejection; errors are existing closed categories.
- `suspend fun AppSessionCoordinator.recoverOrdinaryRejection(session: AppAccountSession,
  confirmation: AccountRecoveryConfirmation): AppReconciliationResult` (member): one captured discard then recovery.

Package `ui.account`:
- `interface AccountSyncOperations { val state: StateFlow<AppSessionState>;
  fun committedEdits(session: AppAccountSession): Flow<Long>;
  suspend fun observeCacheEpoch(session: AppAccountSession): Long;
  suspend fun reconcile(session: AppAccountSession, expectedCacheEpoch: Long,
    restartStageId: String? = null): AppReconciliationResult;
  suspend fun prepareOrdinaryRecovery(session: AppAccountSession): AccountRecoveryConfirmation?;
  suspend fun recoverOrdinaryRejection(session: AppAccountSession,
    confirmation: AccountRecoveryConfirmation): AppReconciliationResult }`.
- `class SessionAccountSyncOperations(accountState: AccountGymAppState): AccountSyncOperations` delegates only to exact
  runtime/facade/finite-edit seams. No DAO in UI.
- `enum AccountSyncError { UNAVAILABLE, REAUTHENTICATION_REQUIRED, STALE_CONFIRMATION }` fixed safe presentation.
- `data class AccountSyncUiState(val busy: Boolean=false, val stop: AppReconciliationStop?=null,
  val error: AccountSyncError?=null, val retainedStageId: String?=null,
  val recovery: AccountRecoveryConfirmation?=null)`.
- `class AccountSyncState(operations: AccountSyncOperations, scope: CoroutineScope, debounceMillis: Long=500L)` with
  `val uiState: StateFlow<AccountSyncUiState>`, `start()`, `setForeground(Boolean)`, `syncNow()`, `prepareRecovery()`,
  `confirmRecovery(confirmation: AccountRecoveryConfirmation)`, `restartStage(stageId: String)`, `cancel()`, `dispose()`.
  All action methods are non-suspending; bounded work is owned by supplied scope with generation fencing. Restart action
  is called only from captured deliberate UI confirmation. Confirmation presentation itself stays composable-local.
- Extend controlled `AccountStartupApp(accountState,startupState,consent,syncState: AccountSyncState?=null)` so existing
  R13c/standalone harnesses remain inert/compatible; production constructs and supplies real sync controller.
- Stable tags: `account-sync-now`, `account-sync-cancel`, `account-sync-error`, `account-sync-stop`,
  `account-restart-stage`, `account-restart-confirm`, `account-recovery-prepare`, `account-recovery-confirm`,
  `account-recovery-warning`, `account-recovery-cancel`. Busy disables duplicate destructive/reauth actions; safe status
  remains advisory, no raw IDs or exception displays. Use Material icons and established accessibility patterns.

Coordinator supplies any further exact production API/harness facts to QA; neither role inspects opposite source.
Small signature changes require shared spec amendment before tests guess at implementation.

## Ownership and allowlists

Worker production read/write only:
- `app/src/main/java/com/dragosfotache/gymtracka/ui/account/*.kt`, `ui/AccountGymAppState.kt`, `ui/FiniteEditCoordinator.kt`;
- `data/sync/session/AppSessionCoordinator.kt`, new `AccountRecoveryConfirmation.kt` and focused recovery helper if needed;
- `data/sync/store/PushStore.kt`, `data/gym/SyncDao.kt` for narrowly fenced transaction primitives.
Worker additionally production READ only: `ui/GymAppState.kt`, `ui/GymApp.kt`, `ui/exercises/ExerciseScreens.kt`,
`data/gym/SyncEntities.kt`, `SyncCanonicalDao.kt`, `SyncCaptureDao.kt`, `SyncLegacyImportDao.kt`,
`data/sync/legacy/*.kt`, `data/sync/store/*.kt`, `data/sync/engine/*.kt`, `data/sync/session/*.kt`,
`data/sync/model/*.kt`, `data/sync/api/*.kt`. Ask for additional write scope before changing it. Never inspect tests,
fixtures, reports, QA work/diffs, decompiled output or backend source/config. Coordinator owns docs/build/harness seams.

QA tests read/write only under these prefixes (files relevant to this slice; no broad ingestion):
- `app/src/test/java/com/dragosfotache/gymtracka/ui/account/` and `ui/FiniteEditCoordinatorTest.kt`;
- `app/src/androidTest/java/com/dragosfotache/gymtracka/ui/account/`, `ui/AppFiniteEditGateTest.kt`,
  `data/sync/session/` and task-relevant existing `data/sync/` test fixtures;
- `app/src/roomBackendTest/java/com/dragosfotache/gymtracka/data/sync/roombackend/`.
QA test READ only: existing `app/src/test/java/com/dragosfotache/gymtracka/data/sync/` fixtures/relay and
`app/src/androidTest/java/com/dragosfotache/gymtracka/data/gym/` test builders. No production declarations/implementation/
diffs, Worker source/reports or decompiled artifacts. Independent literal bytes/oracles, never production encoder
expectations. No backend source/private config, no shared build-file changes without coordinator delegation.
Both roles can read this spec and the task-relevant normative documentation above, not each other's source/work.

## Risk-selected verification

Risk: persistence/destructive recovery, account privacy, exact uncertain replay, lifecycle concurrency and timer/edit
preservation. Reuse accepted R13c startup/import/config/status, R12c reconcile/snapshot/root, R09 authority/vault, R06
head/replay and migration coverage; do not duplicate their complete matrices. New distinct obligations:
- virtual-time foreground entry/stop/disposal/handle replacement, inert startup, debounce/conflation, genuine newer commit
  during run, failed-edit nontrigger, no stop/status spin, manual retry respecting backoff and late/queued-result fencing;
- real Room definitive rejection capture, uncertain frozen exact settlement (accepted and rejected), original import
  refusal and settled-import ordinary eligibility; full-row coalescing/newer edit/stage/handle/stale/replayed token refusal;
- real transactional postwrite rollback/cancellation plus committed-discard lost return/file reopen/null cursor, snapshot
  failure/retained restart preserving domain/last-success/foreign/import evidence, and actual protected new/rest writes;
- actual Compose production wiring for foreground/edit/manual, destructive warning/cancel/stale confirmation and stage
  restart; real explicit-save-editor and timer boundaries (not fake DTO visibility or no-op drains);
- isolated black-box actual Room/vault/HTTP foreground, offline edit/reopen/reconnect and confirmed rejection recovery,
  using real service outcomes and production adapters/relay. No simulator or actual Google assumption.

Iteration: coordinator grants exactly one serialized Gradle slot; compile production/test Kotlin then focused JVM/device
checks and failing/affected regressions. Acceptance: full offline JVM rerun, fresh unfiltered default current-API device
suite, affected R13d plus startup/import/recovery/adapter regressions on API24, actual isolated Room/HTTP journeys,
assembly/lint, APK/config/asset/backup/security audit and both diffs/whitespace. Inspect executed XML classes/cases/failures/
skips; preserve independent device reports. Missing required gate is a blocker, never silently waived.
Actual provider/signing/trusted deployed HTTPS/phone evidence remains R17a; broad public convergence/lifecycle/background
remain deferred at their declared milestones. Legacy auth replay limitation and earlier unexplained R12c Private stop
remain explicit risks: investigate recurrence; passing new journeys is not retrospective repair.

## Coordinator API/harness appendix — independent QA

1. ACTIVE controller tests use Android real Room/facade handles from the existing `AppSessionHarness` plus
   `AppSessionFixtures.coordinator`, `acceptGrant(RoomSessionAuthority(db).read(), operationId, grant)` and
   `completeActivation(RoomSessionAuthority(db).read(), stageId)` activation pattern. There is no approved fake or JVM
   handle factory. Run the controller itself with `TestScope`/virtual time on Android where needed; Room activation
   remains real. Pure non-ACTIVE controller and committed finite-edit notification tests remain JVM.
2. Recovery tokens come only from the real facade `prepareOrdinaryRecovery`; no fake constructor, reflection or forged
   token is an approved test seam. Test confirm/stale/foreign/replayed-token behavior using actual issued tokens and
   independent real-store changes. Controller fakes can return such a real token in Android tests.
3. Scripted result construction is allowed: `AppReconciliationResult(stop, cacheEpoch, delivery=null, snapshot=null,
   publication=null, retainedStageId=null)` is an internal accessible constructor with that exact parameter order/names.
   All seven stop literals are TAIL, PROTECTED, PUSH_STOPPED, PULL_STOPPED, SNAPSHOT_STOPPED,
   SNAPSHOT_RESTART_REQUIRED, RETAINED_STAGE. A minimal fake result is not evidence of actual HTTP/publication.
4. R13c status `seedOutbox` uses invalid IDs and `{}` payload purely to count rows; do NOT reuse it as valid destructive
   recovery input. Use canonical lowercase hexadecimal UUID literals and a hand-authored complete durable wrapper:
   `{payloadVersion:1, requestJson:<JSON string of exact inner mutation>, affectedRoots:[{aggregateType,aggregateId,
   localGeneration}]}`. The inner `aggregate.delete` mutation has mutationId, type `aggregate.delete`, aggregateType
   `routine` (or another legitimate type), matching aggregateId and positive localGeneration, payload `{}`; optional
   knownServerRevision may be null/omitted. `routine.upsert` takes the complete RoutineInput directly as payload
   `{id,name,isDraft:true,exercises:[]}`, not an extra record/routine wrapper. Use `JSONObject.quote` for exact inner
   string escaping, never a production encoder for expected wire bytes.
   `sync_outbox` columns: sequence (positive ordered integer), mutation_id, account_id, aggregate_type, aggregate_id,
   mutation_type, local_generation (positive), payload_version=1, payload_json (full wrapper), state,
   attempt_count, retry_after_millis, error_code, error_message. Valid pending: state pending, attempts 0, retry/error
   null. Definitive rejected: state rejected, attempts >=1, nonempty original error code (e.g. not_found), nullable
   error_message, no uncertain frozen interpretation. Frozen: state frozen, attempts >=1, original exact wrapper/IDs;
   transient retry may be retained. Create representative domain/root generation reservations through real repository
   setup where needed; using production capture as setup is allowed, but it is NOT an independent expected-wire oracle.
   An HTTP200 hand-authored `{results:[{mutationId,status:"rejected",errorCode:"not_found",batch:null}]}` through the
   real adapter/driver supplies a definitive ordinary rejection without guessing the new recovery implementation.
   Raw SQL is permitted for narrowly documented malformed/race/fault fixtures, not fake successful backend outcomes.
5. Reuse `RoomBackendHarness`/R10a helpers and R12c facade relay setup. Exact runner arguments remain roomBackendPort
   and roomBackendCertificate with `-Pgymtracka.roomBackend=true`; fail on missing inputs, never skip. Source-independent
   test helpers may be extended in the test allowlist. New host relay/build changes require coordinator ownership.
6. Actual custom editor is `ui.exercises.ExerciseCreationState(exerciseId: String?, repository: ExerciseStateRepository,
   scope: CoroutineScope, edits: FiniteEditCoordinator=FiniteEditCoordinator(scope))`; `updateName(value)` and
   `requestSave(onSaved:()->Unit): Job?` cover explicit save. Save already uses `edits.launchEdit`, so the new success
   signal covers it with no extra production hook. Actual UI name tag is exercise-field-name; Save text is Save.
   Use the account-root UI and real shared edits, not direct repository writes as proof of automatic edit wiring.
   Timer seam: `GymAppState.startRestTimer(workoutSet: WorkoutSetEntity, durationSeconds: Int,
   acceptedEdit: Boolean=false, expectedWorkoutId: String?=null)`. For account mode, refresh/select the active parent and
   pass a completed set, acceptedEdit=true and the expected parent. `skipRestTimer(timer: ActiveRestTimer)` claims
   terminal persistence; real transition/publication barriers drain it. `refreshProjection()` rebinds real seeds.
   Reuse `R13cEditorReauthTest`'s mutable `GymAppClock` setup, whole-second SQL oracle and no-op hardware boundary;
   no sleeps. Controlled root parameter order is `(accountState, startupState, consent, syncState: AccountSyncState?=null)`.
   Sync/recovery controls live in the account status dialog: first open the visible `account-open` tab, then wait for
   the requested action to be idle/available; scroll real dialog content to lower controls when necessary. Do not
   invoke the leaf section directly to bypass root visibility. Explicit `GymApp(accountState)` is intentionally inert:
   automatic trigger tests must supply/start the real controller through the controlled startup root (or an explicitly
   owned controller plus foreground admission). Test UUID providers must supply fresh canonical UUIDs for every
   preparation AND repository/domain/child mutation; a short exhausted iterator is not valid user Save input.
   After editor Save, `appState.edits.awaitPendingEdits()` exposes any real finite write failure; do not replace a drain
   failure with an optimistic name assertion or sleep. Use independently expected names/values, not generated-ID equality.

## Closure

Coordinator reviews full independent production/test diff, runs required gates, records actual evidence/residual risks,
extracts enduring implementation/product/test facts, archives task/review and updates handoff/roadmap. Commit only accepted
slice-owned changes (shared docs first if changed); no push. R13 package acceptance permits dependency-ready R17a planning
under standing continuation, not externally gated operations without owner input. Escalate blockers instead of inventing
another slice, weakening assertions/gates or proceeding around unavailable tools/agents.
