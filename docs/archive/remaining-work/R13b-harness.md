# R13b — source-independent harness facts

Supplement to the [normative assignment](R13b-task.md), supplied by coordinator. This grants test-only reuse, not production/source access. No implementation is acceptance evidence.

## Exact packages and calls

All five new consent/policy/progress types are in `com.dragosfotache.gymtracka.data.sync.legacy`; their exact signatures are in the task's New source-independent API section. `LegacyImportTarget`, `LegacyImportStore`, `LegacyImportEvidence`, `LegacyImportPhase`, `LegacyImportCaptureBarrier`, `LegacyImportException`/failure are in the same package. Existing capture/read signatures and evidence fields remain, with final default `acceptedCount: Long = 0`; phase gains SETTLED. Original target.operationId stays original forever; evidence.room is current verified lineage.

`AppSessionCoordinator`, `AppSessionState`, `AppSessionReason`, `AppSessionTransitionBarrier`, `ForegroundSignInIntent`, `RecoveryRoomSnapshot`/phase, `RoomSessionAuthority` are in `com.dragosfotache.gymtracka.data.sync.session`. The new facade constructor parameter is LAST after uuidProvider: `legacyImportPolicy: LegacyImportPolicy? = null`. New methods and enum are exactly as specified in the task. Unconfigured observe refuses CONFIGURATION_UNAVAILABLE without migration reads. Closed failures use existing facade exception/privacy machinery, not raw storage text.

`GymAppState.legacyImportTransitionBarrier(processCheck: () -> Unit): AppSessionTransitionBarrier` is an internal extension in `com.dragosfotache.gymtracka.ui`. `Destination` lives in that same package. Test factories may reuse allowed test-only GymAppState, finite-edit and clock/hardware fixtures. Open CreateExercise/EditCustomExercise refuses; caller closes/resolves explicitly before retry. No added editor dialog or hidden save/discard.

`ProjectionPublicationLease` has synchronous `checkValid()` and `close()`; `ProjectionPublicationBarrier.acquire(request)` suspends; request binds accountId/sessionGeneration/expectedCacheEpoch. Those types and `PushRunOwner`, `RepositorySession(accountId: String?, sessionGeneration: Long)` are in `data.sync.store`. Transition barrier `acquire(previous: RepositorySession?)` suspends and returns that lease. A null previous argument does not mean no live local edits: pass the real local adapter, not dormant fallback.

## Normative Room14 DDL

```sql
CREATE TABLE IF NOT EXISTS sync_legacy_import_progress (
  id INTEGER NOT NULL,
  room_revision INTEGER NOT NULL,
  room_session_generation INTEGER NOT NULL,
  room_phase TEXT NOT NULL,
  operation_id TEXT,
  active_consent_mutation_id TEXT,
  PRIMARY KEY(id)
);
CREATE TABLE IF NOT EXISTS sync_legacy_import_acceptances (
  ordinal INTEGER NOT NULL,
  mutation_id TEXT NOT NULL,
  batch_json TEXT NOT NULL,
  PRIMARY KEY(ordinal)
);
CREATE UNIQUE INDEX IF NOT EXISTS index_sync_legacy_import_acceptances_mutation_id
  ON sync_legacy_import_acceptances(mutation_id);
```

No FK/cascade, AUTOINCREMENT, fabricated receipts or consent. No custom SQL CHECK constraints: strict application transaction readers enforce singleton/nonnegative counters/known lowercase `preparing`, `transitioning`, `active`, `signed_out` phases and null operation only signed_out. Migration copies captured journal's original room_revision/session_generation/operation into progress with phase `preparing`; blocked rows/no journal create no progress. Exact original journal and entries remain unchanged.

The migration object is `internal object LegacyImportProgressMigration : Migration(13, 14)` in `data.gym`. `GymDatabase.MIGRATIONS: Array<Migration>` is internal and includes it; tests can use `*GymDatabase.MIGRATIONS`. No existing exposed CURRENT_VERSION constant: test terminal is literal14, every supported starting version1..13. Coordinator owns exported `app/schemas/com.dragosfotache.gymtracka.data.gym.GymDatabase/14.json` from KSP and preserves1..13 plus source asset.

QA may update GymDatabaseMigrationTest terminal assertions/loop to14 and1..13, adding new table-empty checks. LegacyImportStorageMigrationTest proves historic12→13 and may keep that exact gate; do NOT turn it into12→14 just for an arbitrary new-terminal convention. Add independent13→14 tests for progress initialization and immutable bytes/old schema/sequence preservation. Test-only migration helpers may be read/reused; request a concrete extra write path before modifying a helper outside the task allowlist.

## Reopen and actual vault

Allowed existing test-only sources include `SessionPreparationRoomVaultTest.kt`, `SameAccountRecoveryRoomVaultTest.kt`, `PreparedSessionCompletionRecoveryTest.kt`, `AppSessionTestSupport.kt`, `AppSessionRecoveryTest.kt`, `LegacyImportCaptureTest.kt`, `GymDatabaseMigrationTest.kt`, `LegacyImportStorageMigrationTest.kt` and migration utilities. Use a unique file-backed Room name with `Room.databaseBuilder(context, GymDatabase::class.java, name).addMigrations(*GymDatabase.MIGRATIONS).build()`; reopen same file after close, not seed another capture. Teardown owned scopes/jobs before closing/deleting DB.

`AndroidSessionVault.createForTesting(context, namespace)` returns an actual encrypted/Keystore vault using an explicitly test-owned namespace. Reopening uses the same namespace. Existing allowed vault tests contain cleanup/file/key patterns; no private config or production source required. Wrappers implementing SessionVault may gate/fail CAS AFTER or BEFORE delegate to prove uncertainty; do not simulate successful durable state. Existing test-only SessionVault fixtures define load/CAS and full-record/pending/archive expectations.

## Real Room/backend relay

Coordinator owns backend, emulator, relay processes, configuration allocation and cleanup. QA must not start/kill or read private configuration. Build input is isolated launcher, black-box HTTP only; no backend source/Maven tests/SQL domain fixture review.

Opt-in source set `app/src/roomBackendTest/java` is included in androidTest only with `-Pgymtracka.roomBackend=true`. Instrumentation args: `roomBackendPort` numeric loopback TLS port and `roomBackendCertificate` Base64 public DER. Missing inputs FAIL, never skip. Reuse `RoomBackendHarness.kt` functions `r10aRequireInputs()`, `r10aNewTransport(inputs)`, `r10aPostControl(transport, port, path, bearer)` and real TLS transport. Host metadata has only public certificate+port. Android reverse is coordinator-owned. Successful mutations/snapshot/pull always delegate to production transport/real service. Test wrappers may gate/drop an actual response or fail selected snapshot/phase CAS, never supply successful domain data.

Existing host controls:

- POST `/__r10__/account`: synthetic isolated setup returning accountId/accessToken.
- POST `/__r10__/session`: synthetic new account/session setup then ACTUAL real service refresh; returns accountId, accessToken, accessExpiresAtMillis, refreshToken, refreshExpiresAtMillis.
- POST `/__r10__/lose-next-push` with a relay-minted Bearer: lose next downstream push response AFTER real service returns. Exact retry must recover original accepted/rejected outcome.

The coordinator-owned host relay implements POST `/__r10__/same-account-session` with a previously relay-minted Bearer. It provisions a distinct synthetic session for that same guarded isolated account and performs actual service refresh to return the same complete grant shape. Unknown Bearer is401/no setup. This is a test-only setup control, not a service/Google bypass or domain mutation. Its host-control and actual Room/vault HTTP gates passed under fresh guarded isolated resources; the acceptance review owns commands/evidence. This remains synthetic setup, not real Google/deployed account evidence.

Consume session JSON into `ServiceSession(ServiceAccountRef(accountId, null), OpaqueToken(accessToken), accessExpiresAtMillis, OpaqueToken(refreshToken), refreshExpiresAtMillis)` in memory only; no token/body/header/config logging. APIs are in `data.sync.api`. Real HTTP adapter/service transport/session binding seams are documented in task and QA-owned harnesses.

## Real admission reuse

Allowed QA-only reuse: `ui/AppFiniteEditGateTest.kt`, `R12cExplicitEditorTransitionTest.kt`, `R12cRuntimeTimerPipelineTest.kt`, `R12cRuntimeInvariantsTest.kt`; offline `ui/FiniteEditCoordinatorTest.kt`, `GymAppStateTest.kt`, `R12cTransitionBarrierTest.kt`; timer-impact tests. Use shared finite coordinator, actual local repository/Room for durability, fake hardware and injected virtual elapsed clock. For UI dispatch/Compose tests avoid blocking the thread whose work completes a drain: start asynchronously, advance virtual/normal Main scheduling, await bounded actual completion, then assert. No sleeps. Explicit editor refusal can check actual destination/draft unchanged, with zero capture; normal adapter retry after explicit closure must drain real writes.

## Failure/journey test contracts

- An actual typed lost/transient push stop makes `completeActivation` return Private(PREPARATION_REQUIRED), retaining exact frozen work/retry-after. Arbitrary storage/seam faults throw closed facade failures; neither is Active. Reopening uses a fresh facade and fresh authority observation; injected clock must respect retained retry-after.
- Real collision requires no service control: create the intended UUID through actual HTTP for the SAME grant account with a distinct mutation ID, then capture a genuinely local root at that UUID. Create-only import must retain collision/rejection and block dependents/visibility, never overwrite. Successful domain outcomes always come from the real service.
- Ack rollback can use SQLite BEFORE INSERT on `sync_legacy_import_acceptances` with RAISE(ABORT). Compare actual canonical/root/head/receipt state around the accepted push transaction; permitted prior capture/preparation effects are not claimed rollback. Closed failure is expected, never Active.
- Completion gap can be injected by a required publication lease whose close throws AFTER Room combined publication/completion, before phase-only vault promotion; actual RoomACTIVE/vaultPREPARED persists. Fresh `recover()` promotes only exact completed evidence without HTTP. Another seam is a SessionVault wrapper refusing only PREPARED→ACTIVE before delegate. Corrupt/missing receipts or source must block promotion.
- For real HTTP snapshot failure, a test transport wrapper may return500 for snapshot only and delegate all successful push/pull/domain outcomes to actual service; no additional host control is required.

Exact real admission calls:

```kotlin
GymAppState(
    repository: GymAppStateRepository,
    scope: CoroutineScope,
    clock: GymAppClock = SystemGymAppClock,
    timerHardware: RestTimerHardware = NoopRestTimerHardware,
    edits: FiniteEditCoordinator = FiniteEditCoordinator(scope),
    session: RepositorySession = RepositorySession.SIGNED_OUT,
)
fun startRestTimer(
    workoutSet: WorkoutSetEntity,
    durationSeconds: Int,
    acceptedEdit: Boolean = false,
    expectedWorkoutId: String? = null,
)
```

Use named session (fifth positional argument is edits). `activeRestTimer` is readable with private setter, and `edits` is readable. `AppSessionState.Active.session.checkCurrent()` checks current process authority; handles/displays are not account-string oracles. Empty snapshot/pull body literals and scripted transport shapes may be reused from permitted AppSession test fixtures.

## Execution ownership

R13b contributor assignments are closed/frozen after acceptance; no contributor Gradle slot is held. These completed sessions must not carry into R13c. Coordinator owns further gate execution and grants any necessary named bounded correction slot explicitly. Never infer slot permission from an idle pane, running daemons or shared output. Lint freezes both layers. Completed tasks/reported counts are not gates until XML and actual execution are reviewed.
