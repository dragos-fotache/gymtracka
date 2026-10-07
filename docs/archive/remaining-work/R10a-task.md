# R10a — core engine convergence and recovery

## Authority, goal and stop

Owner released Core R10 to the fresh coordinator. The finite R10 plan is **two slices, a/b** in
[roadmap](../../remaining-work-roadmap.md#r10--engine-convergence-and-recovery). R10a proves the personal pre-activation
outcome: two independent real Room stores through the delivered engine and isolated real HTTP, not DTO-only calls.
Stop after independent review, actual required gates, durable evidence and a scoped local commit. R10b remains public;
R09h/R12c/Google/account UI/deployment are not pulled into this slice. Missing gates/inputs/scope questions go to owner.

No production activation, application construction changes, permissive live timer barrier, new schema, legacy-data
import/deletion/reassignment, real Google/phone/personal data, backend source/private-config access, push or deployment.
The production startup remains local-only. A test-controlled publication lease is allowed in isolated stores with no
live screens/timers. No second domain server, direct domain SQL fixture on service or permanent account-size cap.

## Binding reading and inventory

Follow [workflow](../../coordinator-workflow.md) and [proportional testing](../../testing-strategy.md#proportional-coverage-and-verification-gates).
Task-relevant requirements: shared backend `sync.md` synchronization/push/pull/retention/reconciliation;
`android-client-protocol.md` aggregate representations, name projection, mutations, ordered capture and snapshots;
`service-protocol.md` sync identity, pull boundaries and frozen snapshots; `domain-rules.md` aggregates/workouts/routines;
OpenAPI represented aggregate, mutation-envelope, change/batch/outcome/pull/snapshot schemas. These remain normative;
this document supplies source-independent harness facts, not replacement wire rules.

Existing delivered seams, reused unchanged unless a concrete defect is found:
- Room12; immutable source v1 asset; fenced `GymRepository` edits capture transactional outbox and watermarks.
- `PushStore`/`PushRunOwner`, bounded `PushDeliveryLoop`, `SyncDeliveryLoop`, `SnapshotDeliveryLoop`, whole-baseline
  completion and paired protected publication. No monolithic ACTIVE app engine is invented for a verification task.
- `HttpGymSyncApi` plus actual `OkHttpServiceTransport`; existing guarded host `RealBackendFixture` and TLS relay.
- Existing independent Room helpers under androidTest data/sync: `claimSeedDevice`, `claimPushStore`, raw durable queries,
  snapshot/claim/publication support. Reuse helpers; do not duplicate whole prerequisite test matrices.

## Independent ownership

Fresh native `meta/muse-spark-1.3-contributor`/high sessions only. Both receive this specification. All reports are direct
fire-and-forget to live `gym-coordinator`, without wait/poll. No Gradle without an explicit serialized slot; lint freezes
both roles' sources. No opposite-role source, fixtures, diffs, reports or generated/decompiled source access via any tool.

**Worker:** production-only audit of the delivered seams and concrete invariant risks. Read/write allowlist is
`app/src/main/java/com/dragosfotache/gymtracka/data/sync/{engine,store,api,http,model,mapping,codec}/` and
`app/src/main/java/com/dragosfotache/gymtracka/data/gym/GymRepository.kt`. Read only the task-relevant docs above.
No tests/fixtures/build harness/QA output. Report actionable production defects with exact path/invariant and proposed
bounded fix; coordinator settles changes before edits. If no defect, report no production change needed. Existing engine
composition is the subject of verification; adding code solely to create a Worker diff is not a deliverable.

**QA:** read/write only new `app/src/roomBackendTest/java/com/dragosfotache/gymtracka/data/sync/roombackend/` tests.
Read existing test-owned support under `app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/` (excluding
`session/`), plus this spec and binding contract/testing references. No main/production declarations, implementation,
diffs, generated sources, Worker output or host coordinator harness. Ask coordinator for missing signatures.
Use explicit independent literal/domain expectations and observable durable state, not production encoder output as oracle.

**Coordinator:** host-only relay/setup/fault harness, Gradle test source/task wiring, emulator/service/build ownership,
source-independent API facts, integration, full independent production/test review and owning docs. Host code stays JVM
test-only, never application/androidTest APK JDBC/secret/config contents. No contributor conversations/resources transfer.

## Host service and Android gate

Use the existing isolated launcher; build/start backend only, no backend source review/Maven suite. Guarded host fixture
validates exclusive mode-600 config, loopback disposable DB and live run guard before SQL credential provisioning.
Only synthetic account/session credentials and read-only seed lookups use SQL; all domain operations go through HTTP.

Add a JVM host main `RoomBackendRelayMain` in the existing JVM backend package, compiled by an explicit Gradle
`prepareRoomBackendRelay` task. Task writes its resolved JVM test classpath under `app/build/r10/`; launch the host main
with ordinary `java` outside Gradle so it holds no Gradle build slot. Args: guarded config path and exclusive metadata path.
Metadata contains only TLS public certificate in Base64 and relay port, not DB credentials, keys or access tokens.
Relay binds loopback and forwards only existing represented /v1 routes unchanged over HTTP to the guarded service.
Reuse the vetted relay, extending only narrow opt-in test controls and public certificate exposure. No request/body/token
logging. Host lifetime owns only its relay/metadata; exclusive creation and identity-checked cleanup preserve unrelated files.

Test control contract (same trusted TLS origin):
- `POST /__r10__/account`, empty body: mint a fresh synthetic account/access session; JSON
  `{ "accountId": "<uuid>", "accessToken": "<opaque>" }`. Two stores use this same account, not copied databases.
- `POST /__r10__/lose-next-push`, with that synthetic bearer: arm ONE account-token-specific response loss.
  After a real upstream successful sync push returns completely, relay preserves real headers but withholds the downstream definitive outcome body until
  the client's bounded read timeout.
  It never changes mutation bytes, upstream results/domain data or credentials. Client must keep its uncertain head.
  A later successful real response observes original duplicate status/batch, never manufactures a response in the relay.

Opt-in source wiring: `-Pgymtracka.roomBackend=true` adds `src/roomBackendTest/java` to androidTest. Ordinary JVM/device
runs remain offline and do not discover these external tests. An explicit enabled gate MUST fail if inputs are absent,
not silently skip. Instrumentation args `roomBackendPort` and `roomBackendCertificate` supply the relay's port/public DER
Base64 certificate; `adb reverse` on the specifically owned emulator connects localhost. The Android helper constructs
normal verified TLS trust using `HandshakeCertificates`, `ServiceOrigin.parse("https://localhost:<port>")`,
`newServiceHttpClient(builder=...)`, `OkHttpServiceTransport(origin, client)` and
`HttpGymSyncApi(ServiceSessionBinding(origin, accountId, 1L), BoundServiceAccessTokenProvider { boundToken }, transport)`.
`boundToken = BoundServiceAccessToken(binding, OpaqueToken(accessToken))`. Never disable hostname verification/TLS trust.
For setup use the same trusted OkHttp client; do not dump secret-bearing setup JSON on failure.

## Source-independent Room / engine API facts

Tests may construct isolated **file-backed** `Room.databaseBuilder(context, GymDatabase::class.java, uniqueName).build()`
(no asset necessary when graph references only created customs; no destructive fallback). Each store has its own filename,
owner and immutable `RepositorySession(accountId, 1L)`. Seed synthetic device using vetted `claimSeedDevice(accountId, 1L)`;
this is test authority only, not R09h startup/activation evidence. Missing account cache has epoch 0 and incomplete baseline.
Open/reopen at the durable epoch, never copy files from the peer or reseed after restart. Close all DBs and delete only owned
files in finally. Use Dispatchers.IO/runBlocking or bounded coroutine gates; no sleeps. Real HTTP is outside Room.

Construct `GymRepository(database, session)` for domain edits (internal available to tests):
- `startEmptyWorkout(startedAtMillis: Long): WorkoutEntity` (id, name, startedAtMillis, status etc.).
- `updateWorkoutName(workoutId: String, name: String)`; `workoutDetails(id): WorkoutDetails?`; `activeWorkout(): WorkoutEntity?`.
- `createCustomExercise(name, primaryMuscleGroupId: String?, secondaryMuscleGroupIds: Set<String>, equipmentId: String?,
  category: String?, instructions: String): String`; use no catalogue relationships if no bundled fixture needed.
- `addExercisesToWorkout(workoutId, exerciseIds: Collection<String>)`; configured rest defaults 30, fallback one null-input
  incomplete set. `updateWorkoutExerciseNotes(entryId, notes)` and `updateWorkoutExerciseRestDuration(entryId, seconds)`.
- `updateWorkoutSet(set: WorkoutSetEntity): Boolean`; `updateWorkoutSetRestSeconds(setId, elapsedSeconds)`;
  `finishWorkout(workoutId, finishedAtMillis): Boolean`; `deleteWorkout(workoutId)` / `discardWorkout(workoutId)`.
- `createRoutineDraft(): RoutineEntity`; `addExercisesToRoutine(routineId, exerciseIds)`;
  `updateRoutineName(routineId, name)`; `routineDetails(id): RoutineDetails?`; `deleteRoutine(id)`;
  `deleteCustomExercise(id): Boolean` (only unreferenced owned custom).
- `WorkoutDetails` has `workout` and `exercises`; each exercise detail has `workoutExercise` and `sets`.
  `workoutExercise` is `WorkoutExerciseWithName` with id/workoutId/exerciseId/position/notes/restDurationSeconds/exerciseName.
  `RoutineDetails` has routine/exercises; each detail has routineExercise (id/routineId/exerciseId/position/
  restDurationSeconds/exerciseName) and sets. `ExerciseDetails` has exercise/muscleGroups/equipment.
  `WorkoutSetEntity` fields id/workoutExerciseId/position/repetitions/weightKg/isCompleted/restSeconds; `.copy(...)` edits.
  Use vetted test helpers/SQL or request additional facts; never inspect main declarations to resolve a guess.

Engine composition under **one uninterrupted admitted run per physical DB**:
```
val store = PushStore(db, session, owner)
store.withRun(durableEpoch) { run ->
    // Initial/explicit resync, only after real fenced push Empty:
    val snapshot = SnapshotDeliveryLoop(store, api, nowMillis)
    val start = snapshot.start(run, freshUuid, maxPages=100, pageLimit=1)
    // start.push.stop must be PushDeliveryStop.Empty; download must be present.
    // Continue only actual LimitReached on same driver/run; complete only actual Downloaded.
    val baseline = snapshot.complete(run, actualDownloaded)
    // run internally adopts baseline.cacheEpoch. No publication/last-success yet.
    val delivery = SyncDeliveryLoop(store, api, nowMillis).deliver(run)
    // Internal admitted overload available in tests; no nested epoch overload.
    // A valid returned Tail is necessary, not convergence by itself.
    val published = run.publishProjection(ProjectionStore(db, session, testBarrier))
}
```
Ordinary delivery calls the admitted `SyncDeliveryLoop.deliver(run, maxPushMutations=100, maxPullPages=100,
pullPageLimit=100)` then paired publication only after eligible tail. Result exposes `push`, nullable `pull`, nullable
`hasOutstandingLocalWork`. Push Empty permits pull; rejected/deferred/budget/failed head does not. Pull Tail does not imply
publication/convergence; `PullDeliveryStop.RecoveryRequired` reasons MISSING_BASELINE_OR_CURSOR/CURSOR_EXPIRED are explicit
snapshot requirements; invalid tokens remain failures. No automatic stage discard/cleanup.

`ProjectionPublicationBarrier.acquire(request): ProjectionPublicationLease`; request binds account/sessionGeneration/epoch.
Test lease must track valid/open and close once. Publication result `status` is PUBLISHED/PROTECTED/INCOMPLETE_BASELINE/
UNCHANGED and has `cacheEpoch`. PROTECTED preserves the entire visible graph and local wrappers/watermarks; returned
PUBLISHED advances epoch. Use returned/durable epochs for later admission. A fake live-app/timer barrier is forbidden.

For interrupted snapshot recovery: `run.readSnapshotStage(): SnapshotStage?` observes selected retained stage;
`run.abandonSnapshotStage(observed)` explicitly removes only this valid selected stage. Begin a NEW UUID/null-token snapshot;
never append/complete a retained terminal after restart. Observe and abandon inside the SAME fresh admitted run;
an observation inspected after run closure cannot supply another run's authority. Cross-store peer delivery must run in
an independently launched sibling coroutine outside the snapshot run; nested admission on the same coroutine is refused. Failed stages cannot expose partial domains/baseline.
For backoff tests inject a mutable nonnegative `nowMillis`; read/advance beyond durable `retry_after_millis`, no real waits.
`GymSyncApi` wrappers may gate actual responses or inject typed offline/expiry/snapshot-page failures, but cannot generate
successful domain data. `SyncApiException.Transient(retryAfterMillis: Long? = null)` models offline;
`SyncApiException.CursorExpired()` models explicit retention expiry. `GymSyncApi.push(requests: List<DurableRequestModel>):
PushResultModel`, `pull(cursor: String?, limit: Int = 100): PullResultModel`, `snapshot(pageToken: String?, limit: Int = 200):
SnapshotResultModel`. `SyncDeliveryResult.push.stop` uses PushDeliveryStop; successful `pull?.stop == PullDeliveryStop.Tail`.
`MutationOutcomeModel` has mutationId:String/status:MutationStatus/errorCode:String?/batch:SyncBatchModel?;
`MutationStatus` in model is ACCEPTED/DUPLICATE/REJECTED. `SyncBatchModel.changes:List<SyncChangeModel>`;
change aggregateId:String/operation:ChangeOperation/revision:Long/record:CanonicalRecord?, with UPSERT/DELETE operations.
Other constructor/failure shapes not supplied here must be requested before use.

Durable oracles may use vetted raw Room queries against outbox/canonical/account-cache/sync-state/domain tables. Record
exact outbox sequence/mutation ID/payload version/wrapper UTF-8/frozen generation/effects before loss/reopen; compare exact
retained values, not regenerated encoder output. Assert complete visible roots/children/relationships and canonical winners,
positive revisions/delete or snapshot_absent evidence, empty selected outbox/staging, complete baseline, clean matching
materialized/cache epochs and a valid post-baseline tail at quiescence. Peer equality alone is NOT the expected oracle.
Device-local generation/watermarks need not equal across devices; opaque cursor strings/labels are not conflict clocks.

## Additional source-independent signatures and schema

Packages: all store types above are `com.dragosfotache.gymtracka.data.sync.store`; loops/results/stops/recovery reasons
are `.data.sync.engine`; API/model types are `.data.sync.api` / `.data.sync.model`; HTTP types/functions are
`.data.sync.http`; database/repository/entities are `.data.gym`. Constructors:
`PushRunOwner()`, `RepositorySession(accountId: String?, sessionGeneration: Long)`,
`PushStore(database: GymDatabase, session: RepositorySession, owner: PushRunOwner)`,
`ProjectionStore(database: GymDatabase, session: RepositorySession, barrier: ProjectionPublicationBarrier)`;
`GymRepository(database: GymDatabase, session: RepositorySession = SIGNED_OUT, newMutationId: () -> String = randomUuid,
tombstoneClock: () -> Long = System::currentTimeMillis)` is internal, available to instrumentation tests.
All listed repository methods and Room builder usage are confirmed. No seeded journal/visibility authority is inferred.

`PushStore.withRun<T>(expectedCacheEpoch: Long, block: suspend (PushRun) -> T): T`;
`PushRun.publishProjection(projection: ProjectionStore): ProjectionPublicationResult`;
`PushRun.readSnapshotStage(): SnapshotStage?`; `PushRun.abandonSnapshotStage(stage: SnapshotStage): Unit`.
`SnapshotStage` exposes stageId:String/pageLimit:Int/baselineCursor:String?/nextPageToken:String?/downloaded:Boolean/
pageCount:Long/recordCount:Long/canAppend:Boolean, no public constructor/copy.
`SnapshotDeliveryLoop(store: PushStore, api: GymSyncApi, nowMillis: () -> Long = System::currentTimeMillis)`:
`start(run: PushRun, stageId: String, maxPushMutations: Int = 100, maxPages: Int = 100, pageLimit: Int = 200):
SnapshotStartResult`; `continueDownload(run: PushRun, previous: SnapshotDownloadResult, maxPages: Int = 100):
SnapshotDownloadResult`; `complete(run: PushRun, previous: SnapshotDownloadResult): SnapshotBaselineResult`.
`SnapshotStartResult.push: PushDeliveryResult`, `.download: SnapshotDownloadResult?`;
download fields stage/committedPages:Int/committedRecords:Int/stop:SnapshotDownloadStop;
stops Downloaded/LimitReached/RestartRequired/Failed(failure:SyncApiException).
`SyncDeliveryLoop(store: PushStore, api: GymSyncApi, nowMillis: () -> Long = System::currentTimeMillis)`:
`deliver(expectedCacheEpoch: Long, maxPushMutations: Int=100, maxPullPages:Int=100, pullPageLimit:Int=100): SyncDeliveryResult`;
internal admitted `deliver(run: PushRun, same budgets, onTailCommit: ((PullTailCommitResult)->Unit)? = null)`.
Result push:PushDeliveryResult/pull:PullDeliveryResult?/hasOutstandingLocalWork:Boolean?.
Push result acknowledgedMutations:Int/stop:PushDeliveryStop; stops Empty/LimitReached/Deferred(retryAfterMillis:Long)/
Blocked(reason:PushBlockReason,errorCode:String?=null)/AuthenticationRequired/ReauthenticationRequired.
Pull result committedBatches:Int/stop:PullDeliveryStop; stops Tail/LimitReached/
RecoveryRequired(reason:PullRecoveryReason)/Failed(failure:SyncApiException); reasons MISSING_BASELINE_OR_CURSOR/CURSOR_EXPIRED.
Publication result status:ProjectionPublicationStatus/cacheEpoch:Long/materializedEpoch:Long; statuses supplied above.
Barrier SAM `suspend acquire(request:ProjectionPublicationRequest): ProjectionPublicationLease`;
request accountId:String/sessionGeneration:Long/expectedCacheEpoch:Long; lease `checkValid():Unit`, `close():Unit`.

HTTP constructors (internal available to tests): `ServiceSessionBinding(origin:ServiceOrigin, accountId:String,
sessionGeneration:Long)`; `BoundServiceAccessToken(binding:ServiceSessionBinding, token:OpaqueToken)`;
provider SAM `suspend accessToken(binding:ServiceSessionBinding):BoundServiceAccessToken`; `OpaqueToken(value:String)` in api.
`HttpGymSyncApi(binding:ServiceSessionBinding, tokenProvider:BoundServiceAccessTokenProvider,
transport:ServiceHttpTransport)`; `OkHttpServiceTransport(origin:ServiceOrigin, client:OkHttpClient)`;
`ServiceOrigin.parse(value:String):ServiceOrigin`; use parse, not a guessed constructor/configured overload.
`newServiceHttpClient(timeouts:ServiceHttpTimeouts = ServiceHttpTimeouts(),
builder:OkHttpClient.Builder = OkHttpClient.Builder()):OkHttpClient`; use named builder arg.
For bounded real response loss use `ServiceHttpTimeouts(readMillis = 2_000L)` (other defaults retained); this existing
client timeout seam is not a service-clock/retention control.
SyncApiException no-arg subtypes: AuthenticationRequired/ReauthenticationRequired/InvalidCursor/CursorExpired/
InvalidSnapshotToken/SnapshotExpired/RequestTooLarge/MalformedResponse; Transient(retryAfterMillis:Long?=null),
RequestRejected(errorCode:String). No custom message/cause args.

Instrumentation input pattern: `InstrumentationRegistry.getArguments().getString("roomBackendPort")` and
same for certificate; `requireNotNull` with fixed messages. `android.util.Base64.decode(value, Base64.DEFAULT)`;
`CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate`;
`HandshakeCertificates.Builder().addTrustedCertificate(cert).build()` then supply sslSocketFactory/trustManager to
OkHttpClient.Builder. Reuse that exact trusted client for setup and production transport. No hostname verifier override.

Raw durable columns (query through db.openHelper.readableDatabase.query(sql, args), close cursor):
- sync_outbox: sequence,mutation_id,account_id,aggregate_type,aggregate_id,mutation_type,local_generation,payload_version,
  payload_json,state,attempt_count,retry_after_millis,error_code,error_message.
- sync_state: account_id,cursor,last_success_millis.
- sync_account_cache: account_id,baseline_complete,cache_epoch,projection_dirty,materialized_epoch.
- sync_canonical_roots: account_id,aggregate_type,aggregate_id,server_revision,canonical_state,canonical_json,
  local_generation_watermark,cache_epoch. Types workout/routine/customExercise; states unobserved/upsert/delete/snapshot_absent.
- sync_snapshot_stages: account_id,stage_id (use vetted existing stage queries for further details).
- workouts: id,name,started_at_millis,finished_at_millis,status,account_id,server_revision,local_generation,sync_state,
  deleted_at_millis; workout_exercises: id,workout_id,exercise_id,position,notes,rest_duration_seconds;
  workout_sets: id,workout_exercise_id,position,repetitions,weight_kg,is_completed,rest_seconds.
- routines: id,name,is_draft,account_id,server_revision,local_generation,sync_state,deleted_at_millis;
  routine_exercises: id,routine_id,exercise_id,position,rest_duration_seconds;
  routine_sets: id,routine_exercise_id,position,repetitions,weight_kg.
- exercises custom fields and relationship tables can be observed through exerciseDetails/allowed existing helpers;
  ask for any needed unlisted columns. No production declaration read to resolve missing information.

## Distinct acceptance matrix (QA)

Consolidate safely into approximately five focused scenarios, not a quota; retain every distinct invariant below:
1. **Empty and populated bootstrap + offline/reopen:** two independent file stores start from real complete empty snapshot.
   Offline A edits via repository (representative custom + routine draft + workout with ordered entries/sets, configured versus
   elapsed rest and nullable inputs). Inject offline failure, reopen unchanged durable intent, reconnect through actual engine.
   B bootstraps via paged real snapshot. Independently expected full graph, child IDs/order/values, revisions, clean publication
   and outbox/stage/tail predicates must agree; no invisible merge or encoder-derived oracle.
2. **Actual lost response / exact replay / active replacement:** relay loses A's response AFTER real server commit. Keep exact
   frozen ID/bytes/effects across file reopen. B discovers A's committed workout, starts a replacement through its engine.
   A's unchanged retry observes original duplicate batch then catches up the complete delete+new-active batch. Assert exactly
   one current active, no old children/ghost root, retained delete revision, no reexecution and no lost newer winner. Include
   explicit ordinary deletion (with descendants) and eventual empty visible root set where appropriate.
3. **Newer local intent during actual pull:** gate an actual production pull response before return to driver; accept a newer
   local repository edit while remote canonical data is pending. Tail may return with outstanding=true; publication PROTECTED
   must preserve entire local graph, exact pending wrapper/generation. Subsequent real push/pull/publication converges both to
   independently expected later local values with no dropped/merged children.
4. **Frozen snapshot + post-baseline catchup:** page limit 1 over several real roots. Commit a peer change after first page,
   continue same frozen view/limit/baseline, complete atomically then actual pull catches up. Check staged progress isn't visible,
   old frozen values are stable and post-baseline changes are neither lost nor guessed from incremental absence.
5. **Interrupted/expired recovery + absence:** script explicit cursor expiry where real retention controls are unavailable.
   Begin real snapshot, fail middle page deterministically; old visible graph/checkpoint remains complete and unchanged.
   Reopen; retained stage has no append authority. Explicit abandon and NEW snapshot fully recover; a previously known root
   deleted while offline becomes snapshot_absent/removed only after complete snapshot/publication. Newer local intent is
   preserved by the separate case, not discarded to manufacture recovery. Snapshot failure never claims last-success.

Reused existing gates cover full all-17 HTTP kinds, store rollback/fences/cancellation, malformed codecs, session recovery,
Keystore and migrations. Do not duplicate their whole matrices. Expanded R10b owns broad conflict/clock/account/retention
combinations; real Google/deployed TLS/API24/hosted CI/phone remain their own milestones, not evidence from this run.

## Verification, evidence and closure

Risk: high persistence/transport integration, but no planned production behavior/schema change. Before acceptance:
- Worker source-only audit and coordinator independent full production/test/harness diff review.
- Compile main + enabled instrumentation; focused external Room scenarios must actually execute with no skipped tests.
- Full fresh offline JVM (task-scoped --rerun), existing full isolated real-backend HTTP suite, Python launcher regressions.
- Full unfiltered ordinary instrumentation, debug assembly/lint and APK scan for host JDBC/relay/config/credential helpers.
  Source sets must keep host JVM helpers and opt-in tests out of application APK. No new schema/asset changes.
- Inspect executed nested XML class names/counts/failures/errors/skips; no cached/scoped compile-only pass claims.
- Both repo diff/whitespace, relative doc links, scoped acceptance review and post-commit trees. No blanket staging/push.

Every Gradle needs a recorded coordinator slot; host serving Java/emulator/backend remain separately owned. Record exact
commands/environment, scripted-versus-real boundary, limitations and actual gates in one review; update implementation/
testing/handoff/roadmap only for enduring facts. Unavailable required gates or incompatibility are owner blockers, not
silent deferrals or substitute DTO tests. Release only owned resources before fresh-coordinator handoff.
