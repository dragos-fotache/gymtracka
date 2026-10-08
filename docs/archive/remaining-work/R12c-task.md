# R12c — Scoped app and timer reconciliation / R12 package closure

> Archived after acceptance on 2026-10-08; see [review](R12c-review.md). Historical assignment/API specification, not an active resource or execution grant.

## Authority, outcome and stop

The owner resumed R12c explicitly. R12a/b, R09h and Core R10a are accepted dependencies. This is the final slice of the finite three-slice R12 plan, not a new R09 slice.

Deliver an explicitly constructed account app runtime using the real R09h authority and real R12a edit/timer barriers, guarded ACTIVE sync/publication/full-resync, scoped root/feature construction, and app/navigation/timer reconciliation. Accept only after independent source-separated review and the package gates below. No partial or unavailable gate is a pass.

Non-goals: Google/Credential Manager, account/configuration/sign-in/recovery choice UI or foreground/edit/manual triggers (R11/R13), default-startup sign-in policy migration, WorkManager, sign-out/switching/import/delete/reassignment, schema changes, timer process-death reconstruction, deployment, private configuration, real accounts/phones or personal data. Keep the existing default local app construction usable until its R13 replacement. The new account root must have no local repository fallback. Existing local-only data refuses unchanged; do not resolve its disposition here. Preserve P16/P17 and exact uncertain evidence.

## Normative references (read progressively)

- Supplied agent guide; [workflow](../../coordinator-workflow.md), particularly independent roles and serialized builds.
- [Architecture](../../architecture.md#finite-edit-admission-and-publication-leases), projection refresh and timer lifecycle sections.
- [Implemented app authority](../../sync/implementation.md#app-facing-authority-and-offline-recovery), protected publication and complete snapshot boundaries as needed.
- [Approved design](../../sync/integration-design.md#6-vault-and-room-two-stores-recoverable-journal-no-fake-atomicity): personal/startup boundaries; [publication/timer prerequisites](../../sync/integration-design.md#7-autosave-timer-and-ui-publication-prerequisites).
- [Testing policy](../../testing-strategy.md#proportional-coverage-and-verification-gates), commands and isolated real-backend procedure.
- Shared [product shell](../../../shared-docs/docs/product-behavior.md#navigation-and-app-shell) and workout/rest behavior; [sync](../../../shared-docs/docs/backend-contract/sync.md), [aggregate/name/snapshot protocol](../../../shared-docs/docs/backend-contract/android-client-protocol.md), [domain workout rules](../../../shared-docs/docs/backend-contract/domain-rules.md#workouts), [P16/P17](../../../shared-docs/docs/backend-contract/open-decisions.md#confirmed-product-decisions).
- Shared [service cursor/snapshot boundaries](../../../shared-docs/docs/backend-contract/service-protocol.md#pull-boundaries-and-retention-t2); applicable `PullResult`, `SnapshotResult`, `SyncBatch`, `SyncChangeEnvelope`, workout/child and referenced scalar schemas in `openapi.yaml`. Invalid/foreign tokens are not expiry; null pull is not bootstrap.

No blanket traversal of linked contracts or archives. Prefer `mcpls` when it is available; the owner permits proceeding without the misbehaving Kotlin LSP. Use bounded allowlisted text navigation/exact reads and compilation instead of repeated initialization retries. Unavailable diagnostics are not a clean claim. All tools and output remain restricted to role allowlists.

## Current verified implementation/API inventory

All Kotlin paths below are relative to `app/src/main/java/com/dragosfotache/gymtracka/`. These facts are coordinator-supplied contracts, not permission for QA to inspect production.

- `data/sync/session/AppSessionCoordinator` takes `(database: GymDatabase, origin: ServiceOrigin?, vault: SessionVault, authApi: ServiceAuthApi, transport: ServiceHttpTransport, runOwner: PushRunOwner, transitionBarrier: AppSessionTransitionBarrier, publicationBarrier: ProjectionPublicationBarrier, clockMillis: () -> Long = ..., uuidProvider: () -> String = ...)`. Construction is inert/privacy locked.
- Its stable `state: StateFlow<AppSessionState>` has `PrivacyLocked`, `Private(reason: AppSessionReason, room: RecoveryRoomSnapshot?)`, and `Active(session: AppAccountSession, deliveryStatus: AppSessionDeliveryStatus)`.
- `recover(): AppSessionState` performs matching offline admission. `acceptGrant(expected: RecoveryRoomSnapshot, operationId: String, session: ServiceSession)` privately prepares. `completeActivation(expected: RecoveryRoomSnapshot, stageId: String, maxSnapshotPages: Int = 100, restartRetainedStage: Boolean = false)` bootstraps/completes. `invalidate()` synchronously revokes handles and publishes privacy-locked. Existing methods/semantics remain compatible.
- `AppAccountSession` has `identity: RecoveryIdentity`, `room: RecoveryRoomSnapshot`, `repository: GymRepository`, `checkCurrent()`. No external usable handle constructor. `identity.binding` holds origin/accountId/sessionGeneration. The exact process guard must remain effective inside repository transactions and result dispatch.
- Existing `reconnect(session, expectedCacheEpoch, maxPushMutations = 100, maxPullPages = 100, pullPageLimit = 100): SyncDeliveryResult` is intentionally nonpublishing. Preserve that contract; add the explicit R12c entry below rather than silently widening reconnect.
- `AppSessionTransitionBarrier.acquire(previous: RepositorySession?): ProjectionPublicationLease` is required. `ProjectionPublicationRequest(accountId, sessionGeneration, expectedCacheEpoch)` binds publication. Leases expose synchronous thread-safe `checkValid()` and `close()`.
- `ProjectionStore(database, session, barrier).publish(expectedCacheEpoch)` publishes atomically with `PUBLISHED`, `PROTECTED`, `INCOMPLETE_BASELINE`, `UNCHANGED`; result has `cacheEpoch`, `materializedEpoch`. Outbox protection is account-wide. Add optional process checking compatibly; every live ACTIVE publisher MUST supply it.
- `PushStore(database, session, runOwner, processCheck?)` admits `withRun(expectedCacheEpoch) { run -> ... }`. All app engine work uses one physical-DB owner. `run.observedCacheEpoch()` returns the currently adopted epoch. `run.publishProjection(pairedProjectionStore)` publishes on the same uninterrupted owner and adopts the returned epoch. `readSnapshotStage`, `abandonSnapshotStage` and snapshot completion already exist. Stage reads are observations, never resume authority.
- `SyncDeliveryLoop(store, api, clock).deliver(run, maxPushMutations, maxPullPages, pullPageLimit)` runs push-first plus actual pull tail on an already admitted run. Results retain typed `push`, nullable `pull`, nullable `hasOutstandingLocalWork`. Pull stops include `Tail`, `LimitReached`, `RecoveryRequired(MISSING_BASELINE_OR_CURSOR or CURSOR_EXPIRED)` and `Failed(failure)`; only typed recovery invokes a snapshot.
- `SnapshotDeliveryLoop(store, api, clock).start(run, stageId, maxPushMutations = 100, maxPages = 100, pageLimit = 200)` starts only after actual push Empty. `continueDownload(run, previous, maxPages)` accepts only its own uninterrupted LimitReached capability. `complete(run, previous)` accepts only its own actual terminal Downloaded capability. Stopped/retained/restarted work cannot fabricate completion. Budgets are invocation bounds, not permanent account-size ceilings.
- `GymAppState(repository: GymAppStateRepository, scope: CoroutineScope, clock: GymAppClock = ..., timerHardware: RestTimerHardware = ..., edits: FiniteEditCoordinator = ..., session: RepositorySession = SIGNED_OUT)` owns navigation, active pill and countdown. Existing context constructor and default root remain compatible. `GymAppState.publicationBarrier(expectedRequest, preservesTimer: suspend (ActiveRestTimer) -> Boolean)` pauses/drains, validates timer session, claims/drains terminal rest on false, and grants a request-bound lease. It currently has no canonical reader.
- `GymAppState.startRestTimer(workoutSet: WorkoutSetEntity, durationSeconds: Int, acceptedEdit: Boolean = false, expectedWorkoutId: String? = null)` is non-suspend Unit. Live-account positive starts are prevalidated accepted completion callbacks (`acceptedEdit = true`, matching composed active root ID, completed source set); invalid/noncallback/finished-parent starts refuse with no new timer/hardware/rest/outbox, not a promised exception. Legacy/local paths remain compatible.
- `ActiveRestTimer` captures session, workoutId, workoutExerciseId, workoutSetId, timerId, durationSeconds, startedAtElapsedMillis. `GymAppClock` has wallTimeMillis/elapsedRealtimeMillis; `RestTimerHardware` has vibrate/acquireWakeLock/releaseWakeLock. Timer transitions must run on the app owner dispatcher without adopting its Job.
- The actual drain test seam is `runtime.appState.edits`, the same app-owned coordinator supplied to every feature. `FiniteEditCoordinator.launchEdit(key: String, onAccepted: () -> Unit = {}, block: suspend () -> Unit): Job?` registers synchronously before return; observable `isPaused` is read-only to caller and `awaitPendingEdits()` is suspend. A test-controlled accepted REAL repository edit can park the actual runtime transition drain without substituting its barriers. Cancellation of only the grant caller before visibility lock preserves the healthy old handle and accepted edit; after lock/reservation, no general rollback or old-handle reinstall is promised.
- `GymApp(appState: GymAppState = rememberGymAppState())` supplies shared finite edits. All feature factories call `rememberGymRepository()`, which already consults `LocalGymRepositoryOverride: CompositionLocal<GymRepository?>`; otherwise it constructs a local repository. Scoped root MUST provide the exact handle repository around all root/leaf factories.
- Four feature repository interfaces already inherit `ProjectionObservationRepository.observeProjectionEpoch(): Flow<Long>`; real account repositories emit fenced materialized epochs. App-shell interface currently does not inherit it. Feature remembered state is repository-keyed and has latest-load guards; preserve same-ID drafts/focus and current custom labels.

## New fixed public-to-module API contract

Worker implements these names/signatures so QA can author independently. Helpers/private organization are implementation choices. Changes to this contract require coordinator publication before QA depends on them.

### ACTIVE orchestration (session package)

Add a read-only fresh-progress seam to `AppSessionCoordinator`:

```kotlin
suspend fun observeCacheEpoch(session: AppAccountSession): Long
```

Serialize on the existing operation mutex; require the exact current configured live handle and read-only authority before effects. Read selected-account cache metadata in a process/session-fenced Room transaction, validate complete baseline and nonnegative cache/materialized counters with materialized <= cache, and repeat exact handle/process/authority/caller checks after result dispatch. No HTTP, clock, UUID, mutation, publication, cleanup, stage restart, handle rebind or authority grant. Missing/invalid metadata fails closed with existing closed classifications; cancellation propagates. Binding faults retire only the exact current handle. Dirty complete cache/queued intent stays eligible. The result is an advisory observed epoch, not a reusable admission capability.

This seam is required after uncertain committed baseline/publication progress: immutable RecoveryRoomSnapshot contains no cache epoch, and materialized-only observation cannot recover dirty unmaterialized progress. Callers can freshly observe and then supply expectedCacheEpoch to reconcile; a later race still refuses safely. Do not require UI/Compose callers to query DAOs or pretend the initial handle snapshot changes.

Add the explicit reconciliation entry:

```kotlin
suspend fun reconcile(
    session: AppAccountSession,
    expectedCacheEpoch: Long,
    maxPushMutations: Int = 100,
    maxPullPages: Int = 100,
    pullPageLimit: Int = 100,
    maxSnapshotPages: Int = 100,
    snapshotPageLimit: Int = 200,
    restartStageId: String? = null,
): AppReconciliationResult
```

`AppReconciliationStop` enum: `TAIL`, `PROTECTED`, `PUSH_STOPPED`, `PULL_STOPPED`, `SNAPSHOT_STOPPED`, `SNAPSHOT_RESTART_REQUIRED`, `RETAINED_STAGE`.

`AppReconciliationResult` exposes `stop: AppReconciliationStop`, `cacheEpoch: Long`, `delivery: SyncDeliveryResult?`, `snapshot: SnapshotDownloadResult?`, `publication: ProjectionPublicationResult?`, `retainedStageId: String?`; fixed/redacted `toString`, no raw failure/token/credential display. Fields are observations, not authority.

Validate nonnegative epoch, push/pull/snapshot invocation budgets 1..100, pull page limit 1..200, snapshot limit 1..500, and canonical optional restart UUID before credential/store work. Invalid budgets/negative epoch throw IllegalArgumentException. Malformed, nonmatching or nonexistent restartStageId throws AppSessionException(IDENTITY_MISMATCH), preserves durable state and serves no HTTP. Zero epoch is not intrinsically invalid; a zero request against a later current cache epoch is an obsolete-epoch refusal, not negative-argument validation. With otherwise valid account authority, stale cache epoch maps to AppSessionException(UNAVAILABLE)/retry eligibility as reconnect does; it does not mean AppSessionStaleException or revoke a healthy handle. Actual process/session/authority mismatch remains stale. Serialize with the existing facade operation mutex, credential machinery and physical run owner. Preserve exact-handle pre/post authority and process guards; retain reconnect's auth/transient/stale/cancellation classification, without weakening it or introducing another coordinator/run owner.

Hold ONE admitted run through push/pull, any typed full-resync, actual post-baseline tail and publication. Do not call the separate reconnect method and reacquire the owner between phases. Reuse the guarded recovered HTTP adapter.

- Read retained selected stage first. Without explicit matching `restartStageId`, return RETAINED_STAGE unchanged with its ID. A supplied nonmatching/nonexistent stage refuses before abandonment. Explicit matching restart may abandon ONLY that account/run stage, preserving canonical/domain/outbox/uncertain evidence, then create a new stage with a fresh UUID. This is an internal bounded recovery API, not recovery choice UI.
- Otherwise use push-first delivery. Non-Empty push returns PUSH_STOPPED with typed result, no pull/bootstrap/rejection bypass. Ordinary failed/budget pull returns PULL_STOPPED; invalid/foreign tokens never snapshot.
- Only missing baseline/cursor, explicit cursor expiry, or matching explicit retained-stage restart starts complete snapshot recovery. Download may continue consecutive bounded chunks on the same run until terminal (cancellation remains effective; no permanent page cap). Authentic expiry returns SNAPSHOT_RESTART_REQUIRED retaining the stage; other typed failures return SNAPSHOT_STOPPED retaining evidence. Never silently restart or adopt a durable stage.
- Complete only the driver's actual terminal result, then perform an actual bounded push-before-pull tail at the adopted baseline epoch. Snapshot completion alone does not set last-success or imply convergence. Any post-baseline stop stays typed/nonconverged.
- After actual tail, call the guarded paired publisher through the same run with the real barrier. New local intent/claimed rest returns PROTECTED and preserves the entire visible graph. No repeated in-invocation claim/push/publication loop. Successful/unchanged publication returns TAIL. Incomplete/invalid graph fails closed, not a fabricated tail.
- Account edit eligibility survives transient/reauth stops. Binding/storage/authority faults revoke only the exact handle. Preserve exact pending requests, retained errors and superseded encrypted archives. Cancellation always propagates; uncertain commits require fresh durable reread.

### Canonical timer-impact read (store package)

Extend `ProjectionStore` compatibly with optional `processCheck: (() -> Unit)? = null`. Add:

```kotlin
suspend fun preservesTimer(
    expectedCacheEpoch: Long,
    workoutId: String,
    workoutExerciseId: String,
    workoutSetId: String,
): Boolean
```

This is an ACTIVE-only, read-only, transactionally session/epoch/process-fenced canonical-impact decision. Require a complete baseline and no selected snapshot stage; repeat process/session/epoch checks after reading. When projection is eligible, require valid retained canonical graph/family/reference invariants: invalid/incomplete/stale inputs throw, never a permissive true or destructive false. Reuse existing validated canonical helpers rather than inventing a second wire decoder.

If account outbox protection already prevents publication, return true after baseline/stage/process/session/epoch fences without interpreting the latent canonical graph: no pending graph can invalidate the visible timer. This certifies protection of the VISIBLE timer, not validity or authority to publish the latent graph; full graph validation remains mandatory when projection becomes eligible. Otherwise true iff the canonical live workout is active and contains the same entry ID containing the same completed set ID. Changing only exercise reference, names, configured rest, positions or other values does not end an otherwise valid timer. Remote finish/delete/sole-active replacement, removed entry/set or uncompleted set returns false. No DTO depends on UI classes.

Live guarded publication checks process currentness before acquisition, inside its transaction before effects and before commit, and after result dispatch. Existing prepared publication continues using its permit fence; legacy constructor callers remain compatible. No lease alone grants account authority.

### Scoped runtime/root (ui package)

Add `AccountGymAppState` with primary constructor:

```kotlin
internal class AccountGymAppState(
    database: GymDatabase,
    origin: ServiceOrigin?,
    vault: SessionVault,
    authApi: ServiceAuthApi,
    transport: ServiceHttpTransport,
    runOwner: PushRunOwner,
    scope: CoroutineScope,
    clock: GymAppClock,
    timerHardware: RestTimerHardware,
    uuidProvider: () -> String = { UUID.randomUUID().toString() },
)
```

Expose `val sessionCoordinator: AppSessionCoordinator`, observable/read-only `val appState: GymAppState?`, and `fun dispose()`. Add `@Composable internal fun GymApp(accountState: AccountGymAppState)` and a production `rememberAccountGymAppState(database, origin, vault, authApi, transport, runOwner)` factory using actual Android clock/hardware and composition scope. Construction starts private, does not automatically accept grants/run HTTP/trigger sync. Caller explicitly invokes delivered authority/reconciliation APIs. No dependency additions or MainActivity account/configuration changes.

The runtime owns real transition/publication barriers and watches the stable authority state. Bind a fresh app-state/repository/child scope only for the exact live Active handle; delivery-status updates on that same handle do not reconstruct state, navigation or timer. Hide old feature/app models immediately on loss of visibility. Dispose old feature/app/ticker/observer scopes after a normal successful drain AND release of every lease still held by that transition. In particular, acceptGrant publishes privacy-locked before reservation and checks its transition lease again afterward: neither the authority observer nor scoped root onDispose may dispose the old finite coordinator while that live lease is held. The runtime owns root disposal (reuse content without the legacy wrapper's automatic app-state disposal), pins retiring state until close, and performs final retirement on its owner dispatcher. Emergency invalidation cancels stale presentation/hardware without claiming successful persistence; it must not turn a normal in-progress reservation into lease failure merely because UI is already hidden. A stale callback cannot navigate/mutate the replacement scope. Never reclassify private account rows as local, fall back to signed-out factories, or use an advisory room snapshot as a grant.

The account root renders ordinary GymApp content ONLY for its exact current live handle, provides its repository and shared edits around all feature factories, keys scope reconstruction on handle identity (not epoch/status), and otherwise renders a generic stable privacy placeholder tagged `account-privacy-gate`. No account identifiers, raw reasons/causes, sign-in UI or cleanup controls. Recheck synchronous authority state/liveness at render/binding boundaries; an asynchronous collector alone must not permit a stale model to remain selected.

Transition acquisition pauses/drains accepted edits while old handle still valid, claims/drains running rest exactly once, then grants a held lease. A normal acceptGrant with an open CreateExercise/EditCustomExercise explicit-save editor refuses as AppSessionException(UNAVAILABLE) before reservation, retaining the same healthy Active handle, editor presentation and durable state; explicit close permits later transition. Emergency synchronous invalidation still hides private models immediately. Failures/cancellation close every acquired pause, retain healthy editing when acquisition failed, and cannot proceed to reservation. Validate captured previous scope. Initial/private runtime uses a real owned finite-admission coordinator, not a no-op lease. Retained drain failures cannot be erased by detach to authorize a transition.

Publication dynamically binds one exact request to the currently attached app-state; its impact closure invokes the guarded canonical reader at that request. Private prepared completion can use real admission when no ordinary scope/timer exists; it must not expose a private repository/graph. Missing/mismatching active app-state/authority inputs fail closed. Hold and validate the appropriate real lease for the entire publication transaction; no default true callbacks.

Expose `GymAppState.transitionBarrier(): AppSessionTransitionBarrier` for independently testing actual drain/claim behavior. Existing public methods remain compatible. Preserve owner dispatcher and lost-result lease-close safety.

### App-shell materialization observation

Make `GymAppStateRepository` inherit `ProjectionObservationRepository`; add abstract `routineDetails(routineId: String): RoutineDetails?` and `exerciseDetails(exerciseId: String): ExerciseDetails?`, matching the existing real GymRepository feature methods. QA adapts affected hand-written test implementations. Add `GymAppState.refreshFailed: Boolean` (read-only observable) and `suspend fun refreshProjection()` for the same guarded selected-model reload path used by the materialized-epoch observer.

Refresh app active-workout model/pill at materialized epochs, including same-ID name changes; preserve a valid unchanged timer. Use latest-load/scope/accepted-edit guards and failure-aware finite drains, not endless ticker/observer joins. Older loads cannot restore stale active/selected models or overwrite later local starts/navigation/drafts. Refresh does not start timers, create summary dialogs or emit mutations.

Remote active finish/delete/replacement: update newest active discovery/pill; if currently in old active/picker/replacement destination, return to Workout, not automatically navigate into a replacement. Clear obsolete active-root confirmation/source intent on ANY active-root identity change, including a collapsed pill on top-level tabs. Destructive confirmations remain bound to the root/token captured synchronously when requested; stale queued callbacks after invalidation cannot delete/finish/replace a different newly active root. A still-valid explicitly confirmed pending finish request must reserve exclusive admission and wait for its accepted/terminal writes even before the read-only request drain exposes its prompt. Dismissal or root change invalidates both pending and shown requests; async eligibility cannot resurrect a dismissed request. Top-level tab selection remains unchanged. A navigation/presentation change that invalidates a materialization reload must schedule a replacement guarded read or otherwise preserve active discovery: reconciliation cannot be lost until another epoch or manual refresh. Missing selected finished history returns History; missing selected routine/editor/picker returns Workout; missing selected custom detail returns Exercises. An open explicit-save custom editor whose same-scope target disappears retains its unsaved presentation draft and marks refreshFailed rather than silently navigating away; no hidden save/recreation or mutation of a deleted target is permitted. Reconcile only the captured selection; later navigation wins. Do not silently discard an unsaved custom-editor draft during normal scope change: conservatively refuse transitions with an explicit-save create/edit editor open until it is closed, or explicit owner/UI handling exists (R13), not hidden autosave. Ordinary valid same-ID projection refresh preserves raw numeric drafts/focus and dialog inputs via delivered R12b behavior.

Canonical-impact must claim rest BEFORE domain invalidation. Ordinary failed timer-target reads retain the current valid timer/hardware/models and publish only latest refreshFailed; a failed read is not authoritative invalidation. Confirmed unsupported timer-invalidating materialization must fail closed for the exact account binding, not merely clear a timer and reset a presentation flag on the next load. Post-publication app refresh must never attempt a late write into immutable/deleted history or silently discard a still-running invalid timer; missing expected prepublication machinery fails closed. Account timer starts require a selected active parent and completed source set; a finished-row/stale callback refuses rather than creating a phantom writeless countdown. Preserve legitimate accepted completion during pre-lease drains and legacy/local injected-clock paths. UUID-provider cancellation propagates on typed recovery; validate the new stage UUID before explicitly abandoning a retained stage so provider failure preserves its evidence. No process-death timer redesign.

## Source ownership

Worker READ/WRITE (production only):

- `app/src/main/java/com/dragosfotache/gymtracka/ui/GymApp.kt`, `GymAppState.kt`, `GymRepositoryProvider.kt`, new `AccountGymAppState.kt`.
- `ui/FiniteEditCoordinator.kt` and its production composition-local support, only if required for lease/lifecycle integration.
- `data/gym/FeatureRepositories.kt`, `GymRepository.kt` (narrow interface integration only).
- `data/sync/session/AppSessionCoordinator.kt`, `AppAccountSession.kt`, `AppSessionState.kt`, new `AppReconciliationResult.kt` and narrowly named production helper files in that session package.
- `data/sync/store/ProjectionStore.kt` and a new canonical-impact helper if needed.

Worker READ-only dependencies: other `app/src/main/java/com/dragosfotache/gymtracka/data/` and feature `ui/` Kotlin needed for these exact contracts. Do not broaden implementation outside listed files without coordinator approval. No test/fixture/report/QA source, diffs, decompilation, compiled test declarations or opposite-role LSP navigation. Do not inspect `app/src/*Test/`, `app/src/test/`, `app/src/androidTest/`, test build output or QA reports.

QA READ/WRITE (tests only): existing/new Kotlin under `app/src/test/`, `app/src/androidTest/`, and `app/src/roomBackendTest/` for these changed boundaries; preserve existing tests and source-separate expected results. No production declarations/implementations/diffs/decompilation or Worker work. Production signatures/harness facts come from this specification/coordinator. Test LSP navigation must stay in test paths; report missing API facts rather than following a production definition.

Coordinator owns normative/active/architecture/testing docs, build/harness configuration, resource setup, final review and staged commit. Both roles may read this spec and task-relevant Markdown/contracts; neither owns shared-docs changes. No role changes schema, asset, manifest, dependencies, global configuration or secrets. Build diagnostics/opposite-role integration corrections go through coordinator; Gradle only after explicit serialized slot grant. Lint freezes both roles' source.

## Independent test contract and reused harnesses

Risk: high, cross-feature authority and timer/publication integration. New cases target distinct failures, not an exhaustive redo of R05–R10 primitives. Real in-memory/file Room for persistence; fake repositories and virtual time for UI state; stable semantics for actual account-root Compose journeys. No sleeps, mock DAOs or expectations generated by production encoders.

QA may reuse QA-owned `data/sync/session/AppSessionTestSupport.kt` in androidTest: `AppSessionFixtures` supplies UUIDs/origin/grants and independently written empty snapshot/pull/routine-change bodies; `AppSessionHarness(suffix)` owns in-memory/file databases and real namespaced Android vault, reopen/cleanup and epoch/outbox/canonical observations. `AppSessionTransport` queues independently written OK/code bodies and response gates; `AppSessionAuthApi` scripts auth outcomes. Scripted publication/transition barriers may assist primitive tests, but runtime/root acceptance MUST exercise its real barriers. Existing QA fixtures under each test source set remain available to QA. JVM `testing/TestRepositories.kt` supplies hand-written feature/app fakes; update only affected contracts. Real-backend tests reuse the opt-in R10 relay/fixture, never production config. Coordinator-owned test-only `RoomBackendRelayMain.kt` adds POST `/__r10__/session`: guarded synthetic account/credential setup followed by an actual service refresh returns `{accountId, accessToken, accessExpiresAtMillis, refreshToken, refreshExpiresAtMillis}` (expiry fields are integer epoch milliseconds). Consume with existing QA `r10aPostControl` over trusted TLS; construct `ServiceSession(ServiceAccountRef(accountId, null), OpaqueToken(accessToken), accessExpiresAtMillis, OpaqueToken(refreshToken), refreshExpiresAtMillis)`. Do not log response/grant/credentials. `r10aNewTransport` exposes origin/client/transport; `HttpServiceAuthApi(origin, transport)` is the real auth adapter. Start facade/runtime tests with fresh EMPTY owned Room/vault, not the older r10aOpenStore helper's seeded ACTIVE device identity. Actual AccountGymAppState acceptance/bootstrap/reconcile is required; old R10 scripted-lease pipeline evidence alone does not satisfy the new-boundary gate.

Required distinct new outcomes:

1. Real authority activation/offline reopening selects the exact account repository in actual root/features; private/prepared/unconfigured/revoked/mismatched states show only privacy gate. Same-handle status updates preserve state/timer. Actual same-account reauthentication hides the old root while keeping its granted transition lease valid through reservation until close. Old callbacks and cancellation-ignoring reads cannot affect new scope or expose private rows.
2. App epoch observation refreshes same-ID pill/name and active discovery; remote finish/delete/replacement and representative missing nested history/routine/custom selections reconcile without forced navigation or phantom summaries. Latest load/navigation/local edit wins. Existing R12b focus/raw-draft tests remain regression.
3. Canonical timer decision covers valid unchanged/reference rename/replacement preservation, remote invalidations, protected local intent, invalid/incomplete graph and stale epoch/session/process fences. Verify no writes in impact read.
4. Actual barrier/runtime pipeline claims/drains elapsed whole-second rest once BEFORE affected old set disappears, sees newly created outbox protection and preserves visible old graph. Next successful delivery/publication applies remote state normally or retains typed domain rejection without bypass/rewrite. Include failed terminal/drain, caller cancellation/lost lease return and expiry/impact race; existing wake-lock/vibration regressions retained.
5. Guarded ACTIVE publisher cannot commit after same-durable-generation process invalidation, including post-effect/precommit rollback. Paired orchestration owner stays held across tail/snapshot/publication; foreign session/stale callbacks fail closed.
6. Typed cursor expiry/missing cursor downloads/completes whole snapshot then actual tail and real publication. Middle-page failure/expiry/cancellation leaves visible cache unchanged; retained stage requires exact explicit restart. Invalid cursor/token never implicitly snapshots; retained rejection blocks pull/bootstrap. Dirty baseline/reopen/uncertain publication recovers through durable reread. Same-run continuation has no permanent account page cap (reuse accepted primitive >100 coverage plus representative orchestrator chaining).
7. Legacy local-only roots refuse unchanged; no implicit import/deletion/reassignment. P17/uncertain credential evidence remains exact; existing R09h tests reused rather than whole duplicated vault matrix.
8. Representative isolated real Room/HTTP journey exercises the new scoped ACTIVE reconciliation/root boundary over actual service canonical outcomes, with independently expected data. Synthetic tests cannot claim real Google, deployed TLS, process death or phone readiness.

## Verification and delivery gates

Coordinator grants every Gradle slot; contributors do not run builds concurrently or inspect opposite-role source via diagnostics. Focused iteration: Kotlin compilation, affected JVM classes, affected Room/Compose tests. Available LSP diagnostics on changed allowed production files supplement compilation; the owner permits bounded fallback while it misbehaves. Unavailable/indexing diagnostics are not a clean claim.

R12c closes the package, so acceptance requires:

- Full offline JVM `:app:testDebugUnitTest --rerun` with actual XML inspection, not cached/filtered evidence.
- Full unfiltered default `:app:connectedDebugAndroidTest --rerun`, including existing supported migrations/bundled-asset tests and actual scoped Compose/app-flow cases, on explicitly owned disposable emulator/ADB/Android/AVD homes.
- Isolated `:app:testRealBackend` and representative opt-in real Room/HTTP convergence with new-boundary coverage, using the documented launcher/relay only; source/backend config review forbidden. Preserve evidence separately from default instrumentation.
- `:app:assembleDebug :app:lintDebug`, instrumentation compilation/assembly as needed; lint source freeze.
- APK inspection: test helpers/config/credentials excluded, bundled v1 asset and schemas preserved, manifest/hardware regression; no secret logs/displays.
- LSP diagnostics (report limitations truthfully), both repository scope preservation and `git diff --check`; documentation links/anchors and new-file whitespace.

No new Room entity/schema is planned; if unexpectedly necessary, stop and escalate before design/implementation. No additional major-task split inferred. R11/R13/live R17 gates are deliberately deferred to their named milestones; required R12c package gates above are not deferred. Unavailable safe resources or product/scope contradictions go to the owner.

At acceptance: review both full source layers independently, write actual scoped evidence in `R12c-review.md`, extract enduring facts into owning docs, close R12 roadmap status, replace handoff, archive spec/review, stage only slice-owned changes and commit locally. Then fresh coordinator continuation follows standing workflow, subject to next-slice input readiness; no pushes/deployment/configuration/live actions. Until then R12c stays in progress.
