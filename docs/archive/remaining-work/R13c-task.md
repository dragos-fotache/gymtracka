# R13c — mandatory account gate and startup/account UI

## Authority and stop condition

R13b is accepted on Android `b37e961` / shared `edc380b`; ownership is released. Execute the existing third slice of the finite R13a–d plan, not another split. Follow [workflow](../../coordinator-workflow.md), [progressive reading](../../README.md) and [testing policy](../../testing-strategy.md#proportional-coverage-and-verification-gates). **Owner stop: finish R13c, then stop.** Stop after independently reviewed R13c default-startup/account UI and its declared gates are accepted and committed, with owned resources settled. Do not launch/transfer a successor or specify/delegate/build R13d. R13d triggers/ordinary recovery and R17a live operations remain separate and require renewed continuation authority.

Normative policies: shared [P16–P18](../../../shared-docs/docs/backend-contract/open-decisions.md#confirmed-product-decisions), [first-sign-in import](../../../shared-docs/docs/backend-contract/android-client-protocol.md#first-sign-in-import-and-cache-transitions), [Android credential recovery](../../../shared-docs/docs/backend-contract/authentication.md#android-session-storage-and-recovery), and [activation design](../../sync/integration-design.md#startup-and-activation). Personal P16 overrides public signed-out-use language: unconfigured startup is unavailable/private, never local editing.

## Goal, non-goals and current inventory

Replace **no-argument default `GymApp()`** with the mandatory account startup root. Explicit `GymApp(appState)` remains an internal feature/test composition path, not a signed-out production fallback. MainActivity stays Android/Compose setup only. Use the delivered `AccountGymAppState` exact-handle root, real transition/publication barriers, `AppSessionCoordinator`, foreground Google/exchange coordinators and opt-in `LegacyImportPolicy`. Default construction does not sign in, send HTTP, complete preparation, refresh, or trigger synchronization. Startup performs one read-only recovery/completion-gap reconciliation; matching complete established accounts reopen/edit offline independent of credential deadlines, audience availability or online login.

Deliver clear configuration/sign-in/private preparation/recovery screens; one foreground service-confirmed sign-in followed by at most one explicit complete-activation attempt; active-import warning/consent; cancellation and retained progress/error display; same-account reauthentication; account status presentation. No private rows, names, account identifiers, credentials, errors, requests or endpoint/audience strings enter the gate/status presentation or diagnostics. No account chooser/switch/sign-out, destructive clean start, overwrite/remapping, ordinary rejection discard, retained-stage abandonment/restart, automatic foreground/edit/manual sync, timer redesign, schema change, backend change or private configuration discovery is in scope.

Existing facts to reuse:

- Room14/v1 source asset and all migrations remain unchanged. No schema change is planned.
- Facade `state: StateFlow<AppSessionState>` begins PrivacyLocked; Private has closed `reason` and optional advisory `room`; Active has exact `session` and safe delivery status. Only synchronous `AccountGymAppState.visibleBinding()` admits feature content.
- Facade `recover()` is read-only except exact clean completion-gap promotion; performs no HTTP/deadline checks. Never automatically call `completeActivation` for a retained private startup.
- `captureForegroundSignInRequest(intent, expected, operationId)` binds actual origin/process/owner; `ForegroundSignInCoordinator.signIn(request)` consumes challenge/Google/service grant into private preparation, not visibility.
- `completeActivation(expected, stageId, maxSnapshotPages=100, restartRetainedStage=false)` requires PREPARING and exact current Room observation; performs import consent/delivery, complete snapshot/tail and coherent Room/vault completion. A non-Active stop is retained/private, not a signal to loop/retry. Never pass restartRetainedStage=true here.
- `observeLegacyImport(): LegacyImportProgress?` strictly rereads evidence without effects. Progress fields are phase BLOCKED/CAPTURED/SETTLED, importCount, acceptedCount, activeReplacementRequired/Consented. Counts are observations, not authorization. Observation may wait behind ongoing facade work; busy UI must not fabricate live acceptance counts.
- `LegacyImportReplacementConsent.confirm(request)` runs before original active-import delivery and pins exact request/lineage durably in the facade. UI may suspend for confirmation but may not hold or replay an earlier Boolean as new consent.
- Initial SIGNED_OUT/no cached account uses INITIAL_SIGN_IN and fresh UUID. Retained account uses SAME_ACCOUNT_REAUTHENTICATION; PREPARING/TRANSITIONING uses its exact identity.operationId except **valid captured/settled private legacy import** in PREPARING uses PRIVATE_IMPORT_REAUTHENTICATION with a fresh UUID. ACTIVE always uses a fresh UUID different from current. Invalid/blocked/foreign evidence must not be bypassed to choose initial sign-in.
- The runtime pins retiring app/timer state until acquired transition/publication leases close. Explicit-save custom editors refuse transitions before/after drain; accepted finite writes and terminal whole-second rest drain exactly once. Preserve these implementations, not a no-op barrier.
- Production database is `GymDatabase.getInstance(context)`; vault is `AndroidSessionVault.create(context)`; transport is `OkHttpServiceTransport(origin)`; auth is `HttpServiceAuthApi(origin, transport)`; physical-DB owner is `PushRunOwner()`. Reuse one application-process vault/run owner for production factories, with Activity-scoped identity adapter and composition-scoped runtime. No credentials in saved state.

## Configuration, UI and lifecycle behavior

Add only a nonsecret **build-input seam**, with empty checked-in defaults: Gradle properties `gymtracka.serviceOrigin` and `gymtracka.googleServerClientId` into generated BuildConfig strings `GYMTRACKA_SERVICE_ORIGIN` and `GYMTRACKA_GOOGLE_SERVER_CLIENT_ID`. Coordinator owns Gradle edits. No value is supplied or discovered for this slice. Strict original HTTPS root-origin parsing uses ServiceOrigin; malformed/absent origin yields unavailable configuration without database/vault/HTTP reads or SDK launch. A missing/blank/padded audience disables foreground sign-in only: a valid configured origin still attempts offline established-account recovery. Changed origin never adopts/sends old credentials, even when displayed account or provider identity appears similar. Do not add cleartext/test trust to production.

Gate semantics: `account-privacy-gate`, `account-configuration-unavailable`, `account-sign-in`, `account-preparation`, `account-cancel`, `account-continue-preparation`, `account-import-warning`, `account-import-confirm`, `account-import-cancel`, `account-error`. Active account entry/dialog semantics: `account-open`, `account-status`, `account-reauthenticate`, `account-pending`, `account-last-success`. These tags identify behavior, not incidental structure. Use accessible Material controls/icons, status-bar/bottom insets and fixed safe text.

Warning must explain importing the original local active workout may replace the cloud active workout; already accepted remote imports cannot be undone by cancellation. Confirm is explicit; cancel/dismiss returns false or cancels the current operation, never proceeds. Each warning is owned by the current invocation. Cancel cancels only owned startup work/consent, does not clear SDK credential state/vault/import/outbox or claim remote rollback. Disposal fences/cancels owned work, closes foreground coordinator/Activity identity provider and disposes runtime; late/queued/cancellation-resistant old completion cannot overwrite newer UI or grant visibility. No raw Throwable display/logging. Preserve offline feature access on recoverable delivery/sign-in failure while the exact healthy handle remains valid; UI busy status alone is not revocation.

Account status entry can be a fourth tab-bar action opening a presentation dialog (not a new navigation destination). Keep existing compact feature controls and custom top bars unchanged. Display actual pending count, nullable last-success and a closed retained-error indication; unknown/unavailable is not zero/success. Reauthentication while an explicit-save custom editor is open must refuse **before Google launch**, leave the editor/draft visible and tell the user to finish Save/Discard or cancel the account action. Recheck through the existing runtime barrier after suspension. No hidden save/discard. Tests call the real action while the editor is open even if account entry is normally on tabs.

## Required concrete APIs (internal unless existing public)

Worker implements these stable interfaces so QA authors independently. Additional private helpers are allowed within production allowlist; do not silently rename/alter this contract.

In `ui/account/AccountStartupState.kt`:

```kotlin
enum class AccountStartupError { UNAVAILABLE, SIGN_IN_FAILED, PREPARATION_PAUSED, IDENTITY_MISMATCH, EDITOR_OPEN, CANCELLED }
data class AccountStartupUiState(
    val busy: Boolean = false,
    val error: AccountStartupError? = null,
    val importProgress: LegacyImportProgress? = null,
    val syncStatus: AccountSyncStatus? = null,
)
interface AccountStartupOperations {
    val state: StateFlow<AppSessionState>
    suspend fun recover(): AppSessionState
    suspend fun signIn(intent: ForegroundSignInIntent, expected: RecoveryRoomSnapshot, operationId: String): AppSessionState
    suspend fun completeActivation(expected: RecoveryRoomSnapshot, stageId: String): AppSessionState
    suspend fun observeLegacyImport(): LegacyImportProgress?
    fun observeSyncStatus(session: AppAccountSession): Flow<AccountSyncStatus>
}
class AccountStartupState(
    operations: AccountStartupOperations?,
    signInAvailable: Boolean,
    scope: CoroutineScope,
    uuidProvider: () -> String = { UUID.randomUUID().toString() },
) {
    val uiState: StateFlow<AccountStartupUiState>
    val configurationAvailable: Boolean // operations != null; safe presentation fact
    val signInAvailable: Boolean // audience + origin available, not visibility authority
    fun start() // idempotent; one startup recovery, no HTTP
    fun signIn() // nonqueueing; fresh recover/expected request, sign-in then at most one activation
    fun continuePreparation() // explicit; fresh recover; only exact PREPARING, no restart flag
    fun cancel() // owned current invocation only; settle fresh read-only recovery, never auto activation
    fun dispose() // fences old presentation and cancels owned jobs; does not cancel borrowed scope
}
```

Operations null means configuration unavailable; no effects and initial uiState.error=UNAVAILABLE. Both unavailable gate tags must be rendered by the controlled root as well as the production no-origin factory. UI positive visibility remains runtime exact handle selection, never this DTO. `start`, `signIn`, `continuePreparation` serialize one nonqueueing owned operation. Synchronous occupation/generation fencing must precede launch/dispatch; cancellation/lost-return cannot release a newer slot. Old operation/status collector cannot replace new-handle status. Fresh recover for actions prevents stale advisory snapshots from authorizing work. Blocked/corrupt import observation refuses activation/sign-in with UNAVAILABLE and retains prior progress instead of treating failure as null. Failed fresh post-sign-in recovery also maps UNAVAILABLE, never stale-snapshot fallback. Normal user cancel after private durable reread presents CANCELLED. Each activation uses one fresh canonical stage UUID; UUID provider faults/invalid values/current-operation equality refuse without provider retry loops. Catch cancellation distinctly; final presentation may show CANCELLED but durable reread is authoritative. Scope cancellation/disposal cannot start recovery work on a dead scope.

In `ui/account/AccountImportConsent.kt`:

```kotlin
class AccountImportConsentDecision internal constructor() // opaque identity; no request data
class AccountImportConsent : LegacyImportReplacementConsent {
    val pending: StateFlow<AccountImportConsentDecision?>
    override suspend fun confirm(request: LegacyImportReplacementRequest): Boolean
    fun answer(decision: AccountImportConsentDecision, confirmed: Boolean)
    fun close()
}
```

One current nonqueueing deferred decision. Composable callbacks capture the exact opaque decision token. Caller cancellation releases it; a stale token answer after cancellation/close cannot consent a future request. Close permanently fences and cancels pending owned decision with CancellationException; caller cancellation also propagates CancellationException, never successful consent. Request data never displayed/saved. Warning dialog collects pending directly; mutable transient dialog visibility stays in composable except this asynchronous domain decision bridge.

In `data/sync/session/AccountSyncStatus.kt`:

```kotlin
data class AccountSyncStatus(val pendingCount: Long, val lastSuccessMillis: Long?, val hasRetainedError: Boolean)
```

Facade adds `fun observeSyncStatus(session: AppAccountSession): Flow<AccountSyncStatus>`: cold, read-only selected-account metadata observation, no HTTP/UUID/clock/cleanup/admission. Each initial/invalidation reread and return/emission requires exact current live handle/process and transactional Room authority/baseline checks. Observe `sync_outbox`, `sync_state`, account/device/authority metadata needed for fencing; register before initial read. Count all selected-account outbox rows, including frozen/rejected; retained error is rejected or stored error presence, never raw code/message. Last-success comes only from selected `sync_state`; inspect SQLite storage type in the same fenced transaction before a typed Long getter, accepting only missing/SQL-null or nonnegative INTEGER (never coerced TEXT/REAL/BLOB). Negative/inconsistent data closes to fixed unavailable, never normalization. Prefer scalar DAO queries over full outbox payloads. Additive DAO query methods do not change schema. Check caller cancellation and synchronous exact currentness before emission; old status cannot bind to a new handle. Foreign metadata remains private. This stream is presentation only, not an edit/sync trigger.

`AccountGymAppState` and `rememberAccountGymAppState` gain a trailing optional `legacyImportPolicy: LegacyImportPolicy? = null`, passing it to the facade without changing existing constructor paths. Default account factory supplies consent policy. Existing exact-handle root and `GymAppContent` gain only optional account-action presentation wiring; legacy explicit callers retain behavior.

In `ui/account/AccountStartupApp.kt`:

- `@Composable internal fun AccountStartupApp()` is production default factory/root; uses LocalContext Activity for identity. `GymApp()` calls it.
- `@Composable internal fun AccountStartupApp(accountState: AccountGymAppState, startupState: AccountStartupState, consent: AccountImportConsent)` exposes the same root presentation for controlled real-Room tests. It starts the state once and renders features only through the existing account root's synchronous exact binding. No test-only static override/global credential routing in production.
- `internal class SessionAccountStartupOperations(accountState: AccountGymAppState, signInCoordinator: ForegroundSignInCoordinator) : AccountStartupOperations` delegates only through facade, checking explicit custom editor before signIn and throwing a fixed `AccountStartupException(AccountStartupError.EDITOR_OPEN)` with no details. Define `AccountStartupException(val error: AccountStartupError)` as fixed/redacted RuntimeException. Do not catch a foreign provider grant to force another intent.
- `internal class AccountStartupConfiguration` has `val origin: ServiceOrigin?`, `val serverClientId: String?`, `val signInAvailable: Boolean`; `companion object fun fromValues(serviceOrigin: String?, serverClientId: String?): AccountStartupConfiguration`. Invalid origin maps null, invalid audience maps null; no trimming/default discovery, no raw toString. Independent origin recovery and audience sign-in availability as above.

Coordinator supplies any missing existing signatures/harness facts to QA asynchronously, never production-source access.

## Independent roles and permitted files

All paths relative to repository root; supplied AGENTS and task-relevant normative documentation are allowed to both. No opposite-role declarations, implementations, diffs, reports, artifacts, LSP or fixture inspection.

**Worker read/write:** production `app/src/main/java/com/dragosfotache/gymtracka/ui/account/**` (new), `ui/GymApp.kt`, `ui/AccountGymAppState.kt`, `data/sync/session/AccountSyncStatus.kt` (new), `data/sync/session/AppSessionCoordinator.kt`, `data/gym/SyncDao.kt`. Worker may read other production dependencies under `app/src/main/java/**`, production resources and Gradle declarations for exact construction facts, but must not inspect tests/reports/QA work. No tests/docs/build scripts/schema/assets edits. Request coordinator help for out-of-allowlist changes. Worker owns production implementation, not independent expected results.

**QA read/write:** new `app/src/test/java/com/dragosfotache/gymtracka/ui/account/**`, new `app/src/androidTest/java/com/dragosfotache/gymtracka/ui/account/**`, and the single obsolete no-argument-local test in `ui/R12cAccountRootPrivacyTest.kt` (replace with new default configuration gate expectation; retain explicit root tests). QA may read existing test/fixture sources under `app/src/test/**` and `app/src/androidTest/**`, supplied task docs and coordinator-provided API contracts; no production/build/generated/decompiled source or Worker work. QA requests missing facts instead of guessing APIs. Coordinator owns shared harness/build integration and resolves any necessary legacy expectation change.

**Coordinator:** Gradle BuildConfig seam, normative specification, owning docs/shared product updates, both-layer integration, all final review and acceptance/resource orchestration. Shared Gradle slots explicitly serialized; neither contributor builds without a granted slot. Lint freezes both source layers. Contributors report asynchronous/fire-and-forget to gym-coordinator, never acknowledgment polling.

## Risk-selected tests and gates

Cross-feature activation/auth/privacy change: high risk. Independent focused additions prove distinct new UI/controller/metadata composition risks, not another entire codec/engine matrix.

1. JVM configuration and state: no-origin no effects, invalid original input; valid origin/missing audience can recover; idempotent startup with no HTTP activation; initial/private-import/ordinary reauth intent/operation selection; no authority on Google/private result; at-most-one complete attempt; blocked/corrupt progress retained; nonqueueing start/action, cancel/dispose, late/queued old completion and collector fencing. Consent cancel/dismiss/stale answer and new decision isolation. Independently observe calls/state, not private helpers.
2. Compose/actual root: unconfigured default **MainActivity** has no workout/catalogue/nav access; new configured install gate; populated legacy graph stays hidden during Google/private/consent/preparation; explicit warning text/consent/cancellation; successful exact import activation exposes actual scoped features; blocked import/rejected/corrupt evidence never falls back; fixed safe errors. Configuration absence must preserve seeded local graph unchanged. Use actual in-memory/file Room and test-namespace encrypted vault where durable reopen matters; scripted SDK/service seams are deterministic evidence, not actual Google.
3. Established account: offline runtime restart with expired credentials and queued edits, matching exact handle and actual repository edit capture; missing/mismatched/unreadable/changed-origin binding stays private; same-account foreground reauth preserves data/intent; foreign service account cannot adopt. Status count/last-success/retained error refreshes after real metadata/edit commit without any HTTP. Stale status/result after handle retirement cannot leak.
4. Real editor/edit/timer integration: reauth action refuses open custom editor before provider launch with draft preserved; actual finite accepted edit and whole-second timer drain occurs through runtime on successful transition exactly once; failed/cancelled drain remains private or retains healthy handle as appropriate. Reuse existing R12c/R13b real admission/authority/migration/HTTP coverage where it proves unchanged internals.

Iteration: production compile, JVM affected classes, focused new Room/Compose classes and impacted existing startup/runtime tests. Acceptance: fresh **full offline JVM** (`:app:testDebugUnitTest --rerun`), fresh **unfiltered default current-API instrumentation** (no roomBackend flag/package/class filters; inspect nonempty executed XML and preserve reports), affected **API24 startup/import/offline recovery/reauthentication** new classes plus existing Android Google adapter and real legacy admission classes. Current image37.2 and API24 image are installed; no device is currently owned. Create disposable headless devices, no personal phone. Schema unchanged; reuse existing migration tests in full current layer, do not regenerate snapshots.

Run assembly/lint with both layers frozen, APK/DEX absence of fixture/credential/test helper markers, v1 source asset equality, INTERNET/VIBRATE/WAKE_LOCK and backup/data-transfer vault exclusion checks, both-repository `git diff --check` and doc links. No required gate may silently defer. Combined R13 package real-backend foreground/offline/reconnect journeys remain R13d; actual signing/Google/audience/deployed HTTPS/phone remain R17a. R13c does not need to repeat all R13b real HTTP import matrices, but recurrence of the unrepaired auth replay/R12c private activation-stop limitation is investigated and escalated rather than masked with retries/rounding.

No push/deployment/configuration/signing/OAuth/personal-account/device action. Acceptance evidence distinguishes synthetic service/SDK/UI success from live interoperability and active editing from synchronization/convergence.

## Coordinator-supplied existing API/harness appendix

This appendix grants contracts, **not production-source access**. QA may reuse all listed QA-owned prerequisites: AppSessionHarness/Fixtures, CountingClock/Uuids/scripted barriers, GymDatabaseTest and accepted R12c/R13b admission/authority/migration/HTTP fixtures. Do not repeat unchanged matrices. Raw SQL through real test Room is allowed for independent seeds/corruption/preservation/metadata oracles; query details live in QA-owned fixtures. New production scalar DAO method names are private implementation choices, not QA API dependencies. Observe new status through the facade contract above.

Existing types below live in `data.sync.session` unless specified. All named parameters shown are exact:

```kotlin
enum class AppSessionReason { CONFIGURATION_UNAVAILABLE, SIGN_IN_REQUIRED, PREPARATION_REQUIRED, IDENTITY_MISMATCH, LOCAL_DATA_REQUIRES_DECISION, UNAVAILABLE }
enum class AppSessionDeliveryStatus { IDLE, RETRY_REQUIRED, REAUTHENTICATION_REQUIRED }
sealed interface AppSessionState {
    data object PrivacyLocked : AppSessionState
    data class Private(val reason: AppSessionReason, val room: RecoveryRoomSnapshot?) : AppSessionState
    data class Active(val session: AppAccountSession, val deliveryStatus: AppSessionDeliveryStatus = AppSessionDeliveryStatus.IDLE) : AppSessionState
}
enum class RecoveryRoomPhase { SIGNED_OUT, PREPARING, ACTIVE, TRANSITIONING }
data class RecoveryIdentity(val binding: ServiceSessionBinding, val operationId: String)
data class RecoveryRoomSnapshot(
    val revision: Long, val sessionGeneration: Long, val cachedAccountId: String?,
    val phase: RecoveryRoomPhase, val identity: RecoveryIdentity?, val baselineComplete: Boolean,
)
enum class ForegroundSignInIntent { INITIAL_SIGN_IN, SAME_ACCOUNT_REAUTHENTICATION, PRIVATE_IMPORT_REAUTHENTICATION }
class AppSessionException(val reason: AppSessionReason) : RuntimeException
class AppSessionStaleException : IllegalStateException
```

Snapshot counters are nonnegative; canonical lower-case UUIDs required. SIGNED_OUT has identity=null and baselineComplete=false; cachedAccountId may be retained. Other phases require identity with matching positive generation/account. `ServiceSessionBinding(origin: ServiceOrigin, accountId: String, sessionGeneration: Long)` is in `data.sync.http`. ServiceOrigin.parse(value: String): ServiceOrigin strictly parses original root-only HTTPS; no whitespace/userinfo/query/fragment/non-root path. Configuration null/empty/invalid/padded origin yields null without I/O. Audience null/blank/padded yields null; **valid origin + missing audience still recovers offline**. New configuration/root/operations/state types all live in `ui.account`; `AccountSyncStatus` is `data.sync.session`. MainActivity is `com.dragosfotache.gymtracka.MainActivity`, a ComponentActivity whose theme setup calls no-arg GymApp. Its new unconfigured default must display both `account-privacy-gate` and `account-configuration-unavailable`; nav-workout/nav-exercises/nav-history/ordinary feature content absent. Replace the obsolete R12c no-arg-local test accordingly; retain all explicit-runtime tests.

```kotlin
// Existing facade constructor (new observation method is specified above)
class AppSessionCoordinator(
    database: GymDatabase, origin: ServiceOrigin?, vault: SessionVault,
    authApi: ServiceAuthApi, transport: ServiceHttpTransport, runOwner: PushRunOwner,
    transitionBarrier: AppSessionTransitionBarrier, publicationBarrier: ProjectionPublicationBarrier,
    clockMillis: () -> Long = System::currentTimeMillis,
    uuidProvider: () -> String = { UUID.randomUUID().toString() },
    legacyImportPolicy: LegacyImportPolicy? = null,
) : ForegroundGrantHandoff {
    val state: StateFlow<AppSessionState>
    fun invalidate()
    suspend fun recover(): AppSessionState
    suspend fun acceptGrant(expected: RecoveryRoomSnapshot, operationId: String, session: ServiceSession): AppSessionState
    fun captureForegroundSignInRequest(intent: ForegroundSignInIntent, expected: RecoveryRoomSnapshot, operationId: String): ForegroundSignInRequest
    suspend fun completeActivation(expected: RecoveryRoomSnapshot, stageId: String, maxSnapshotPages: Int = 100, restartRetainedStage: Boolean = false): AppSessionState
    suspend fun observeLegacyImport(): LegacyImportProgress?
}
class ForegroundSignInCoordinator(
    origin: ServiceOrigin?, identityProvider: GoogleIdentityProvider, handoff: ForegroundGrantHandoff,
    authApi: ServiceAuthApi? = origin?.let { HttpServiceAuthApi(it) },
    clockMillis: () -> Long = System::currentTimeMillis,
    uuidProvider: () -> String = { UUID.randomUUID().toString() },
    attemptTimeoutMillis: Long = 180_000L,
) {
    suspend fun signIn(request: ForegroundSignInRequest): AppSessionState
    fun close()
}
fun interface ForegroundGrantHandoff {
    suspend fun acceptForegroundGrant(request: ForegroundSignInRequest, session: ServiceSession): AppSessionState
}
```

`AppAccountSession` exposes `val identity: RecoveryIdentity`, `val room: RecoveryRoomSnapshot`, `val repository: GymRepository`, `internal fun isAlive(): Boolean`, `suspend fun checkCurrent()`. Use real facade/harness admission for Room/root tests. For narrow JVM state fixtures only, the internal constructor is `(identity: RecoveryIdentity, room: RecoveryRoomSnapshot, repository: GymRepository, fence: Any, alive: java.util.concurrent.atomic.AtomicBoolean, checkAuthority: suspend () -> Unit, isFacadeCurrent: () -> Boolean, onAuthorityFault: () -> Unit)`; no fake handle is evidence of real admission. Prefer existing QA test factories over inventing one.

```kotlin
// data.sync.legacy
fun interface LegacyImportReplacementConsent { suspend fun confirm(request: LegacyImportReplacementRequest): Boolean }
data class LegacyImportReplacementRequest(val target: LegacyImportTarget, val mutationId: String)
data class LegacyImportTarget(val origin: ServiceOrigin, val accountId: String, val operationId: String)
class LegacyImportPolicy(val replacementConsent: LegacyImportReplacementConsent)
enum class LegacyImportProgressPhase { BLOCKED, CAPTURED, SETTLED }
data class LegacyImportProgress(
    val phase: LegacyImportProgressPhase, val importCount: Long, val acceptedCount: Long,
    val activeReplacementRequired: Boolean, val activeReplacementConsented: Boolean,
)
// LegacyImportPhase (CAPTURED/BLOCKED/SETTLED) is a DIFFERENT storage enum; do not use for progress.
```

```kotlin
// ui
class AccountGymAppState(
    database: GymDatabase, origin: ServiceOrigin?, vault: SessionVault,
    authApi: ServiceAuthApi, transport: ServiceHttpTransport, runOwner: PushRunOwner,
    scope: CoroutineScope, clock: GymAppClock, timerHardware: RestTimerHardware,
    uuidProvider: () -> String = { UUID.randomUUID().toString() },
    legacyImportPolicy: LegacyImportPolicy? = null, // R13c trailing addition
) {
    val sessionCoordinator: AppSessionCoordinator
    val appState: GymAppState?
    fun dispose()
    internal fun currentHandle(): AppAccountSession?
    internal fun visibleBinding(): VisibleBinding?
    internal data class VisibleBinding(val app: GymAppState, val handle: AppAccountSession)
}
interface GymAppClock { fun wallTimeMillis(): Long; fun elapsedRealtimeMillis(): Long }
interface RestTimerHardware { fun vibrate(durationMillis: Long); fun acquireWakeLock(timeoutMillis: Long); fun releaseWakeLock() }
// Explicit root remains: @Composable fun GymApp(accountState: AccountGymAppState)
// Explicit local/test root remains: @Composable fun GymApp(appState: GymAppState)
// No-arg root now: @Composable fun GymApp() -> AccountStartupApp()
```

`SessionAccountStartupOperations` checks `accountState.appState?.destination` for CreateExercise or EditCustomExercise before capturing/launching foreground sign-in, and throws fixed AccountStartupException(EDITOR_OPEN), preserving the exact healthy runtime/draft. The delivered barrier rechecks after any drain. Use real MainScope/Android dispatcher and finite wall-clock guards for Room/Keystore/drains; use TestScope/virtual time only for pure fake state and consent tests. NoOp test hardware implements all three methods above; controllable clock implements both. Dispose/cancel/join scopes before Room close. The SDK adapter can be independently tested through accepted AndroidGoogleIdentityProviderTest; do not reopen its entire matrix.

```kotlin
// data.sync.session
object AndroidSessionVault {
    fun createForTesting(context: android.content.Context, namespace: String): EncryptedSessionVault
}
// namespace must start gymtracka-test-; matching test key alias allocated internally.
// data.sync.api
interface GoogleIdentityProvider {
    suspend fun credentialFor(nonce: String): OpaqueToken
    suspend fun clearCredentialState()
}
interface ServiceAuthApi {
    suspend fun challenge(purpose: GoogleChallengePurpose = GoogleChallengePurpose.SIGN_IN): ServiceChallenge
    suspend fun exchange(attempt: GoogleExchangeAttempt): ServiceSession
    suspend fun refresh(attempt: ServiceRefreshAttempt): ServiceSession
    suspend fun logout(refreshToken: OpaqueToken)
}
data class ServiceChallenge(val challengeId: String, val nonce: String, val expiresAtMillis: Long)
data class ServiceAccountRef(val accountId: String, val displayName: String?)
data class ServiceSession(val account: ServiceAccountRef, val accessToken: OpaqueToken, val accessExpiresAtMillis: Long, val refreshToken: OpaqueToken, val refreshExpiresAtMillis: Long)
class OpaqueToken(val value: String)
```

Foregound request must come from real facade capture factory for real handoff. Controlled identity/auth can be injected into ForegroundSignInCoordinator; controlled transport goes into real runtime. Canonical UUIDs/nonempty tokens/epoch-millisecond values and valid independent wire literals required. Existing AppSessionHarness empty snapshot/pull and grant fixtures are allowed, but clocks must match challenge and grant expiry on each actual admission. New production observation queries are not exposed to QA; status tests seed selected sync_state/outbox via existing QA/raw-SQL fixtures and assert the new Flow results. Do not extract expected output from production encoders.

Gradle slots: currently coordinator-only; QA has **no slot**. Request a slot after tests are authored and production APIs exist. Coordinator owns disposable headless emulator-5554 (current37.2) and will later own API24; no contributor may start/stop/reconfigure devices or use a personal device. Coordinator supplies serial and grants an exclusive command scope, then contributor reports actual command/result/XML names/counts and releases the slot asynchronously. No tests/source changes during lint freeze.
