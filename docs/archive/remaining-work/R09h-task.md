# R09h — app-facing activation and offline recovery

## Authority and boundary

Owner released R09h following accepted Core R10a (`45875b9`). Owner confirmed the finite plan: deliver the app-facing authority/recovery boundary for R12c to consume, **without activating current screens or a publisher at production startup**. R12c owns scoped UI rebinding, navigation/pill reconciliation and authoritative canonical timer-impact decisions. R11 owns Google identity; R13 owns account UI and triggers. No circular all-R12 prerequisite, no additional R09 slices, no substitute no-op app barrier.

After acceptance, prepare durable handoff and a scoped local commit, then **stop without launching a successor** (owner override of automatic rotation).

Pre-existing local-only data remains preserved; activation encountering it refuses unchanged. No import, deletion, reassignment or legacy choice is authorized. No deployment, personal configuration/device/account actions, backend source review, scheduler, general switching/sign-out UI or deferred lifecycle work.

## Required reading

Use supplied AGENTS and task-relevant documentation: `docs/coordinator-workflow.md` role/gate rules, `docs/testing-strategy.md` proportional coverage, `docs/sync/implementation.md` session boundaries (startup through R09g), `docs/sync/integration-design.md` sections 6–9, and shared `backend-contract/authentication.md` Android recovery, `sync.md` account/failure/push/pull boundaries, `android-client-protocol.md` ordered capture and durable request identity, and `open-decisions.md` P16/P17. Follow a directly needed wire/model invariant without traversing unrelated routes. Existing implementation APIs are coordinator-provided facts below; QA must not inspect their production sources. Historical reviews are not required contributor reading.

## Goal and inventory

Compose delivered Room12 authority, R09b/g preparation/recovery, R09c bootstrap, R09d completion/promotion and R04 startup/refresh into one explicitly constructed application boundary. Matching established accounts reopen offline without credential freshness/login as visibility prerequisites. Safe reconnect reuses bounded push-before-pull; reauth/transient stops preserve cached edits and exact uncertain requests.

Existing signatures (relative to `app/src/main/java/com/dragosfotache/gymtracka/`):

- `RoomSessionAuthority(database)` implements recovery/preparation/same-account recovery authority. `read(): RecoveryRoomSnapshot`; snapshots include revision, generation, cached account, phase, identity and baseline completeness.
- `SessionPreparationCoordinator(origin, authority, vault, mutex = Mutex())`: `prepare(expected, operationId, session: ServiceSession)`, `resume(expected, session)`, `recover()`, `invalidate()`.
- `SameAccountRecoveryCoordinator(origin, authority, vault, runOwner, credentialMutex)`: `prepare(expected, operationId, session)`, `resume(expected, session)`, `recover()`, `invalidate()`; reserves only retained same-account/origin and preserves exact encrypted superseded evidence under P17.
- `PreparedSessionEngineCoordinator(database, origin, vault, authApi, transport, runOwner, clockMillis, uuidProvider)`: `bootstrap(stageId, maxSnapshotPages=100, snapshotPageLimit=200, maxPushMutations=100, maxPullPages=100, pullPageLimit=100, restartRetainedStage=false)`, `completePreparation(barrier, maxPushMutations=100, maxPullPages=100, pullPageLimit=100)`, `recoverCompletion()`, `invalidate()`. Bootstrap is canonical-only; completion publishes/Room-ready then vault-promotes. Neither result grants app authority.
- `SessionRecoveryCoordinator(origin: ServiceOrigin?, vault, authority, authApi, clockMillis, uuidProvider)`: `reconcile(): SessionRecoveryState`, synchronous `invalidate()`. Matching ACTIVE and complete baseline produces `RecoveredSession(identity)` through alternating Room/vault reads. Its `withAccessToken` uses one refresh coordinator with required Room predicate and unchanged retry policy. Add a narrow read-only `RecoveredSession.checkCurrent()` rather than minting an unbound refresh lease.
- `GymRepository(database, session=SIGNED_OUT, newMutationId, tombstoneClock)` delegates scoped reads/writes to `SyncStore.withFencedTransaction`; existing production context constructor stays unchanged.
- `SyncDeliveryLoop(PushStore(database, session, runOwner), api, clockMillis).deliver(expectedCacheEpoch, maxPushMutations=100, maxPullPages=100, pullPageLimit=100)` returns actual bounded delivery, not publication or convergence. PushRun checks durable fences and process admission around write boundaries.
- `ProjectionPublicationBarrier.acquire(request)` yields `ProjectionPublicationLease.checkValid()/close()`. R12a supplies real admission/drain/timer machinery; production integration still needs R12c's explicit canonical impact decision.

## Concrete new API contract

All new APIs are `internal` under `data/sync/session`. Constructor names/order and methods below are normative for independent tests. Additional private helpers are implementation choices.

```kotlin
fun interface AppSessionTransitionBarrier {
    suspend fun acquire(previous: RepositorySession?): ProjectionPublicationLease
}

enum class AppSessionReason {
    CONFIGURATION_UNAVAILABLE, SIGN_IN_REQUIRED, PREPARATION_REQUIRED,
    IDENTITY_MISMATCH, LOCAL_DATA_REQUIRES_DECISION, UNAVAILABLE,
}
enum class AppSessionDeliveryStatus { IDLE, RETRY_REQUIRED, REAUTHENTICATION_REQUIRED }
sealed interface AppSessionState {
    data object PrivacyLocked : AppSessionState
    data class Private(val reason: AppSessionReason, val room: RecoveryRoomSnapshot?) : AppSessionState
    data class Active(val session: AppAccountSession,
                      val deliveryStatus: AppSessionDeliveryStatus = AppSessionDeliveryStatus.IDLE) : AppSessionState
}
class AppSessionStaleException : IllegalStateException // fixed/redacted text
class AppSessionException(val reason: AppSessionReason) : RuntimeException // fixed, no cause/suppressed

class AppSessionCoordinator(
    database: GymDatabase, origin: ServiceOrigin?, vault: SessionVault,
    authApi: ServiceAuthApi, transport: ServiceHttpTransport, runOwner: PushRunOwner,
    transitionBarrier: AppSessionTransitionBarrier,
    publicationBarrier: ProjectionPublicationBarrier,
    clockMillis: () -> Long = System::currentTimeMillis,
    uuidProvider: () -> String = { UUID.randomUUID().toString() },
) {
    val state: StateFlow<AppSessionState>
    suspend fun recover(): AppSessionState
    suspend fun acceptGrant(expected: RecoveryRoomSnapshot, operationId: String,
                            session: ServiceSession): AppSessionState
    suspend fun completeActivation(expected: RecoveryRoomSnapshot, stageId: String,
        maxSnapshotPages: Int = 100, restartRetainedStage: Boolean = false): AppSessionState
    suspend fun reconnect(session: AppAccountSession, expectedCacheEpoch: Long,
        maxPushMutations: Int = 100, maxPullPages: Int = 100,
        pullPageLimit: Int = 100): SyncDeliveryResult
    fun invalidate()
}
class AppAccountSession { // no externally usable constructor
    val identity: RecoveryIdentity
    val room: RecoveryRoomSnapshot
    val repository: GymRepository
    suspend fun checkCurrent()
}
```

Normative refusal policy: `recover` returns closed Private classifications. `acceptGrant` and `completeActivation` return Private on successful preparation or typed incomplete engine progress, and Active only on authorized completion; refused input/dependency/seam failures throw `AppSessionException(reason)`. Account/operation incompatibility maps IDENTITY_MISMATCH; local roots map LOCAL_DATA_REQUIRES_DECISION; configuration maps CONFIGURATION_UNAVAILABLE; drain/vault/authority/other local faults map UNAVAILABLE; non-preparing completion maps PREPARATION_REQUIRED. An exact expected-snapshot mismatch, process invalidation or dead handle throws AppSessionStaleException. Caller cancellation is always rethrown. Numeric budget violations remain fixed-text IllegalArgumentException preflight errors before I/O. Early refusal and failed acquisition preserve the current healthy Active state/handle; once reservation has started, errors leave the affected operation private/locked until explicit recovery. Exceptions never imply durable rollback.

`state`/exceptions/handles must have redacted displays and expose no grants, credentials, request bytes or exception causes. Private observations are nonsecret advisory expectations, not authority. No production startup/UI factory constructs this boundary in this slice. Required transition/publication inputs have **no permissive defaults**. Caller owns ONE coordinator/run owner per physical DB/vault; constructor is inert.

### Ownership, admission and process fencing

- Serialize state-changing operations with a coordinator operation mutex. Synchronous invalidation never waits for locks/network/drains: kill all subordinate process permits/leases/handles and publish PrivacyLocked immediately. Dead queued/in-flight work cannot install new state, report success or expose data; caller cancellation takes precedence. An operation's finally/cancellation must not clear a newer state.
- Share one credential mutex among facade preparation, retained-account recovery, prepared refresh and active recovered refresh. Add trailing optional mutex injection to existing prepared/recovery constructors (preserve all current constructor callers/default paths). Prepared and recovered refresh owners are mutually exclusive: invalidate prepared work before active admission, invalidate recovered work before replacement.
- A handle captures exact Room identity/revision plus opaque facade fence. Its repository never follows another scope. Add an explicit guarded constructor overload to GymRepository/SyncStore, preserving the original legacy/default/trailing-lambda constructors exactly (never let a UUID/clock supplier be coerced to a Unit guard); for facade-created repositories the required guard runs inside every scoped transaction before effects and before return/commit, so a dead handle cannot read/mutate even if durable generation has not yet changed. Legacy constructors retain compatibility.
- Reconnect similarly binds a process check to its PushStore/PushRun (optional injection preserving current callers, including Kotlin trailing-lambda semantics). Check before run admission and at every existing `requireAdmitted`/transactional terminal guard; review early-return paths. Cancellation/process invalidation during an uncommitted transaction must roll it back; an already completed synchronous commit is not undone. No stale response can acknowledge or checkpoint via a dead facade handle.

### Recovery and grant handling

- Construction starts PrivacyLocked and does no I/O. Unconfigured `recover` returns Private(CONFIGURATION_UNAVAILABLE, null), with no Room/vault/clock/UUID/HTTP. No LocalOnly/editing grant is exposed under P16.
- Configured recovery uses existing startup reconciliation. Only matching ACTIVE Room/vault and complete baseline produces a new AppAccountSession, with no HTTP/clock/UUID regardless of access/refresh deadlines or pending attempt. Cache dirty/queued intent does not revoke healthy offline eligibility.
- If Room is ACTIVE but credentials are PREPARED, invoke existing completion-gap recovery then freshly reconcile; incomplete/dirty/queued or mismatched gaps remain private unchanged. Other preparing/transitional observations remain Private(PREPARATION_REQUIRED, room); never invent credentials, restart generations or automatically abandon stages. Foreground grant is needed where primitives require it.
- If already Active, `recover` revalidates the current handle rather than silently rebinding and revoking accepted local edits. A revoked/mismatched authority locks it; recovery errors fail closed.
- `acceptGrant` requires a caller-captured **exact expected Room snapshot**, fresh canonical operation UUID for a new reservation, and authoritative service grant. Validate input and reread expected authority BEFORE superseding healthy handles or acquiring destructive authority. Delayed grant callbacks against changed expectations cannot replace a newer session. Wrong origin/account, unknown provenance, local roots or occupied incompatible credentials refuse with fixed categories and no cleanup.
- Acquire caller-supplied transition pause/drain **while the previous account handle is still valid**, so accepted edits and terminal rest can persist. Only after successful lease validation invalidate old process admission/hide presentation, then freshly recheck expectation and perform reservation/credential changes. Hold transition admission through the private reservation/replacement; check lease before effects and after return; always close. A failed drain must not reserve or change credentials.
- SIGNED_OUT with no retained account uses R09b prepare. Retained SIGNED_OUT/ACTIVE uses R09g prepare. PREPARING/TRANSITIONING resumes the exact existing operation, requiring supplied operation to equal its reserved operation, without another generation. No caller can use a fresh operation to restart an interrupted reservation. P17 archive behavior remains in existing primitives.
- Returned preparation evidence is always Private, never a raw result promoted to Active. Fresh durable re-observation decides private outcome; errors after uncertain writes require recover/reread, no compensating fence/cleanup/retry.
- `completeActivation` requires exact PREPARING expected authority and canonical stage UUID. Bootstrap privately via delivered engine with supplied bounded budget and explicit restart flag, then complete with mandatory publication barrier. Retained stage/nonterminal/error stops stay private; don't complete on bootstrap summary alone. Only actual COMPLETED followed by fresh startup reconciliation supplies Active. No auto-restart, import, stage cleanup, budget loop or timer-impact default.

### Reconnect

- Require the exact current handle and read-only authority check before effects. Reuse bounded SyncDeliveryLoop, existing shared run owner and recovered refresh machinery, not a second unbound ACTIVE lease.
- Freeze/deep-own push wrappers before credential suspension using the approved encode/decode roundtrip and batch preflight (may factor existing SessionRefreshingGymSyncApi's pure preflight into a shared helper). Returned callback results are checked against both recovered Room authority and facade handle. Validate read limits before credential work.
- Safe transient delivery/reauthentication stops retain Active cached editing eligibility and original queue/credentials; update only closed delivery status. Missing/mismatched/faulted authority or dead process handle locks/fences, never adopts another account. Caller cancellation is rethrown and never represented as a successful delivery. Runtime credential-binding/storage failures lock the exact affected handle immediately; a network-only transient/reauthentication stop does not. Canonical incremental batches mark projection dirty but do not advance account cache/materialized epochs; only their validated checkpoint/tail bookkeeping advances, with ACTIVE projection still gated to R12c.
- Reconnect updates canonical/cache/checkpoint via existing drivers but **does not construct/publish an ACTIVE projection**. R12c consumes the boundary and must supply real timer impact plus app reconciliation before live publication. Explicit cursor-expiry/retained-stage stops remain typed recovery obligations, not hidden snapshot restarts.

## Risk and verification

High-risk authentication/account/privacy orchestration, no schema/migration/asset changes and no live UI activation. Reuse prior R09a–g transaction/vault/refresh and R12a barriers; do not rewrite their exhaustive matrices. Core R10a is accepted convergence evidence, not a fresh R09h integration pass.

Independent new tests (distinct plausible defects):

1. Constructor/unconfigured initial-use remains private without credential/clock/UUID/HTTP I/O.
2. Real Room initial preparation → complete canonical snapshot/tail → required barrier → ACTIVE/vault phase → app handle. No premature app handle on preparation/bootstrap stop or failed barrier.
3. Reopen/reconstruct coordinator over matching ACTIVE cache with expired credentials/queued edits; recover/read/edit offline without HTTP; new edits capture same-account intent. Reconnect uses same queue/frozen identity and strict delivered refresh semantics.
4. Same-account fresh-grant recovery composed through this boundary preserves pending/domain and uncertain encrypted evidence; different UUID/legacy local roots refuse unchanged.
5. Exact completed RoomACTIVE/vaultPREPARED gap promotion; mismatched/missing/incomplete credentials remain private. No silent stage restart.
6. Failed transition drain retains current handle/credentials and makes no reservation; invalidation/cancellation during drain/network/Room/vault return cannot republish a dead handle or hide a newer result. Delayed grant expectation refuses unchanged.
7. Old facade repository read/mutation refuses after **process-only** invalidation at unchanged durable generation. A transactional in-flight guard rollback is covered at the narrowest real-Room layer.
8. Reconnect response invalidated before acknowledgment/checkpoint does not apply; transient/reauth stops keep offline cache editing eligibility, pending work and exact retry identity. Independent expected request bytes, not production encoder-derived expected wire values.

Use real in-memory Room for transaction tests, hand-written vault/transport/auth fakes, coroutines gates (no sleeps). JVM tests cover pure/process/input boundaries where Room is unnecessary; focused Android tests prove real composition with Room. Existing accepted R12a app finite-edit/timer gates remain reused evidence; full private-scope/real Compose/package R12 gates run at R12c, not claimed here.

Iteration: coordinator-granted serialized compilation/focused test slots only. Acceptance: affected and full offline JVM (test task `--rerun`), focused new Room plus affected prior session/capture/push regressions, `assembleDebug`, `lintDebug`, APK helper/secret exclusion review and both diffs/`git diff --check`. If a required emulator/agent/model/gate is unavailable, escalate; don't call it passed. No fresh real-backend run required: existing engine semantics are reused and accepted Core R10a is the named prerequisite. Full instrumentation/package scope/Compose gates remain explicitly R12c and account UI R13; live journeys R17 separately authorized.

## Contributor boundaries and stop condition

Worker reads/writes production only:
- new `data/sync/session/AppSession*.kt`, `AppAccountSession.kt`, `RecoveredSessionGymSyncApi.kt`;
- `data/sync/session/{SessionRecoveryCoordinator,PreparedSessionEngineCoordinator,SessionRefreshingGymSyncApi}.kt`;
- `data/gym/GymRepository.kt`, `data/sync/store/{SyncStore,PushStore}.kt`.
Worker may read directly needed production session/API/http/model/codec/store/gym dependencies and R12a finite-edit/app-barrier seams; never tests/fixtures/reviews/QA work. No schema/entity/assets/build/UI edits.

QA reads/writes test source only:
- new JVM `data/sync/session/AppSession*Test.kt`/support;
- new Android `data/sync/session/AppSession*Test.kt`/support.
QA may read/reuse existing test-only session/Room/capture/push fixtures and tests, build config/testing docs, normative specification and referenced contracts. **No production source/declarations/diffs/LSP/decompilation or Worker output.** Missing signatures/harness facts come from coordinator. No changes to existing test assertions without coordinator approval.

Coordinator owns spec, documentation, resource/harness/build integration, both-source review and acceptance commit. Neither role runs Gradle without explicit serialized slot, inspects opposite-role work, stages/commits or starts other agents. Reports are asynchronous to `gym-coordinator`; no waiting/polling.

Stop at a reviewed, verified app-facing boundary with current production signed-out/local startup unchanged and outstanding R12c/R11/R13/legacy/live obligations explicit. Close reduced R09 only to the declared **authority/recovery boundary**; do not claim live activation or package integration acceptance. Archive this spec/review, replace current docs, commit slice-owned changes and prepare next pickup, then stop without a successor launch.
