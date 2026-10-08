# R11b — Bounded foreground exchange and guarded grant handoff

## Authority and boundary

The owner released coordinator ownership and **deterministic-only execution with injected synthetic configuration**.
No real Google/account/device action, deployed HTTPS, private-config discovery, OAuth/signing change or push is authorized.
Use the [workflow](../../coordinator-workflow.md), [finite R11 plan](../../remaining-work-roadmap.md#r11--foreground-google-identity)
and [testing policy](../../testing-strategy.md#proportional-coverage-and-verification-gates).

Deliver one explicitly constructed, SDK-independent foreground sign-in/reauthentication orchestrator over accepted
R11a/R03c/R04/R09h/R12c seams. It obtains a SIGN_IN challenge, supplies its exact nonce to the Google provider, captures
one exact exchange attempt, retries that attempt within a finite foreground budget, and consumes the service grant
through the existing guarded private preparation boundary. The invocation returns private preparation, not a freely
reusable/unbound service grant or ACTIVE account authority.

Non-goals: default startup/account UI/triggers, bootstrap/publication/activation, P18 import, switching/sign-out/clear,
lifecycle proofs, scheduler, replacement HTTP/vault/refresh/authority, Room/schema/asset changes and live readiness.
R13 owns activation/import/UI; R11c stays deferred public; real provider/signing/HTTPS/phone gates stay R17a.

## Normative sources and existing inventory

Read only task-relevant sections; expand for actual dependencies, not the entire contract map:

- [Authentication](../../../shared-docs/docs/backend-contract/authentication.md): credential lifetimes, challenge/replay,
  HTTPS challenge/exchange and Android session recovery. [Wire schemas](../../../shared-docs/docs/backend-contract/openapi.yaml):
  `/v1/auth/google/challenge`, `/v1/auth/google`, GoogleChallengeRequest/GoogleChallenge/ServiceSession/Uuid.
- [Compatibility](../../../shared-docs/docs/backend-contract/client-compatibility.md): wire types/timestamps and authentication.
  [HTTP errors](../../../shared-docs/docs/backend-contract/service-protocol.md#domain-failures-and-shared-http-errors-t3).
  [P16/P17/P18](../../../shared-docs/docs/backend-contract/open-decisions.md#confirmed-product-decisions).
- [Implemented identity/HTTP](../../sync/implementation.md#foreground-android-google-identity),
  [app authority](../../sync/implementation.md#app-facing-authority-and-offline-recovery),
  [retained evidence](../../sync/implementation.md#inert-retained-account-recovery-and-superseded-evidence),
  [legacy replay limit](../../sync/implementation.md#legacy-auth-replay-limitation).
- [Vault/Room design](../../sync/integration-design.md#6-vault-and-room-two-stores-recoverable-journal-no-fake-atomicity),
  [real edit/transition leases](../../architecture.md#finite-edit-admission-and-publication-leases),
  [test conventions/procedures](../../testing-strategy.md).

Existing APIs (coordinator-verified facts; QA must not inspect production to confirm them):

- `GoogleIdentityProvider`: `suspend credentialFor(nonce: String): OpaqueToken`, `suspend clearCredentialState()`.
  R11a implements explicit Activity-scoped Google acquisition with safe `GoogleIdentityException(reason)` and cancellation.
- `ServiceAuthApi`: `challenge(GoogleChallengePurpose.SIGN_IN): ServiceChallenge`, `exchange(GoogleExchangeAttempt): ServiceSession`,
  refresh/logout methods are not called by this slice. `ServiceChallenge(challengeId, nonce, expiresAtMillis)`.
- `GoogleExchangeAttempt.capture(key, challengeId, token)` is immutable; `idempotencyKey`, `requestJson`, `challengeId`,
  `idToken` retain the original body/key/credential. Exchange must use the typed attempt, never the convenience overload.
- `HttpServiceAuthApi(origin)` is production single-send secure auth; `(origin, ServiceHttpTransport)` is injectable.
  `ServiceOrigin.parse(root)` validates immutable canonical HTTPS origin. `ServiceSession` preserves exact service
  account/token/expiry values. Opaque tokens are not decoded.
- `SyncApiException.Transient(retryAfterMillis: Long? = null)` caps hints to 300000 ms. Permanent subclasses include
  `RequestRejected(errorCode)`, `ReauthenticationRequired`, `RequestTooLarge`, `MalformedResponse`.
- `AppSessionCoordinator.acceptGrant(expected: RecoveryRoomSnapshot, operationId: String, session: ServiceSession)`
  validates exact fresh Room expectation/configured origin/service UUID/operation and real transition lease, returns
  `AppSessionState.Private`, not activation. It preserves pending intent/cache and exact superseded encrypted evidence.
  `invalidate()` synchronously fences process work. Original APIs/behavior must remain compatible.
- `RecoveryRoomSnapshot(revision, sessionGeneration, cachedAccountId, phase, identity, baselineComplete)` is immutable;
  phases SIGNED_OUT/PREPARING/ACTIVE/TRANSITIONING. SIGNED_OUT has null identity/incomplete baseline; cached account may
  be retained. Other phases have `RecoveryIdentity(binding, operationId)` matching account/generation.
  `binding` is `ServiceSessionBinding(origin, accountId, sessionGeneration)`.
- `AppSessionException(reason)` and `AppSessionStaleException` are closed existing refusals; failed acquisition keeps a
  healthy handle. A refusal/cancellation after a started commit is not rollback proof. Required barriers have no defaults.

## New concrete API contract

New files under `data/sync/session`, all types `internal`:

```kotlin
enum class ForegroundSignInIntent { INITIAL_SIGN_IN, SAME_ACCOUNT_REAUTHENTICATION }

class ForegroundSignInRequest(
    val origin: ServiceOrigin,
    val intent: ForegroundSignInIntent,
    val expected: RecoveryRoomSnapshot,
    val operationId: String,
    // Optional internal opaque authority binding, populated only by the facade's capture factory.
    // Four-argument construction remains available for independent fake-handoff JVM tests.
)

fun interface ForegroundGrantHandoff {
    suspend fun acceptForegroundGrant(request: ForegroundSignInRequest, session: ServiceSession): AppSessionState
}

class ForegroundSignInCoordinator(
    origin: ServiceOrigin?,
    identityProvider: GoogleIdentityProvider,
    handoff: ForegroundGrantHandoff,
    authApi: ServiceAuthApi? = origin?.let { HttpServiceAuthApi(it) },
    clockMillis: () -> Long = System::currentTimeMillis,
    uuidProvider: () -> String = { UUID.randomUUID().toString() },
    attemptTimeoutMillis: Long = 180_000L,
) {
    suspend fun signIn(request: ForegroundSignInRequest): AppSessionState
    fun close()
}
```

`AppSessionCoordinator` implements `ForegroundGrantHandoff` and adds the synchronous no-I/O factory:

```kotlin
fun captureForegroundSignInRequest(
    intent: ForegroundSignInIntent,
    expected: RecoveryRoomSnapshot,
    operationId: String,
): ForegroundSignInRequest
```

Capture uses the facade's actual configured origin and records an opaque exact owner/process fence under its state
monitor. Unconfigured capture throws existing CONFIGURATION_UNAVAILABLE. Invalid intent/context/operation throws existing
IDENTITY_MISMATCH. A naked four-argument request is usable only with test handoffs, never accepted by the real facade.
Neither factory nor constructor reads Room/vault, launches Google, calls HTTP or changes observable authority.
Real handoff naked-request/owner mismatch throws `AppSessionException(IDENTITY_MISMATCH)`; obsolete captured process
fence throws `AppSessionStaleException`, propagated unwrapped through `signIn`, even at unchanged Room expectation.
Requests have fixed/redacted displays and cannot be serialized, saved or treated as durable/reusable authority.

Add `ForegroundSignInFailure` with CONFIGURATION_UNAVAILABLE, INVALID_REQUEST, BUSY, RETIRED, TIMED_OUT,
CHALLENGE_EXPIRED, IDENTITY_FAILED, TRANSIENT_FAILURE, RETRY_EXHAUSTED, REQUEST_REJECTED, MALFORMED_RESPONSE, UNAVAILABLE.
`ForegroundSignInException(val reason: ForegroundSignInFailure, val identityReason: GoogleIdentityFailureReason? = null)`
uses fixed safe messages/display, no cause or suppressed entries. Identity reason is set only for classified identity
failure. Other internal helpers/opaque binding representations are implementation choices, not test hooks.

The default HTTP adapter is constructed from the exact configured origin. Explicit auth injection is a trusted
assembly/test seam, not proof of another service's identity; callers must never relabel a foreign API. No origin/config
is obtained from a token, response display name, fallback URL or mutable supplier. Production handoff validates origin
against its own facade configuration, not an origin merely asserted by an external wrapper.

## Intent, process and durable authority guards

- INITIAL_SIGN_IN requires SIGNED_OUT with no cached account/identity. Legacy local roots remain the facade's refusal,
  not permission for import. SAME_ACCOUNT_REAUTHENTICATION requires a cached account; any identity must match the
  captured origin. These checks and canonical operation UUID validation precede Google/challenge/key I/O.
- PREPARING/TRANSITIONING uses exactly the reserved identity.operationId; ACTIVE uses a fresh different operation;
  SIGNED_OUT uses the caller-supplied canonical operation. Never regenerate an activation reservation on retry.
- `signIn` refuses a request origin different from its configured origin before I/O. The real handoff refuses an owner,
  origin, intent or opaque-fence mismatch before draining/reserving. Different grant account never adopts retained data.
- Carry the **captured facade fence into the existing acceptGrant critical section**. Do not check it and then recapture
  the latest fence on entering acceptGrant: invalidation between Google/exchange and handoff must fail even when the
  durable Room snapshot is unchanged. Refactor only the acceptGrant entry into a common private implementation taking
  the exact fence; preserve the original three-argument behavior and every existing guard/lease/error path.
- The actual handoff retains expected/operation/grant unmodified and delegates existing R09 preparation once. The same
  operation mutex, fresh exact Room checks, mandatory real barriers, credential/run ownership and P17 preservation apply.
  No replacement generation/journal, grant persistence before these guards, cleanup or implicit activation is added.

## Invocation ordering, retry and lifetime

1. Construction is inert; one nonqueueing admission slot spans challenge through final handoff acceptance. A second
   simultaneous call throws BUSY without effects. Already-cancelled caller cancellation takes precedence over admission.
2. Check configured origin/auth and request validity. One explicit SIGN_IN challenge call only; challenge failures do not
   reacquire automatically. Before Google acquisition and again immediately before FIRST exchange, require
   `clockMillis() < challenge.expiresAtMillis`. Equality refuses CHALLENGE_EXPIRED. No deadline rounding/extension.
3. Call Google once with the exact nonce; obtain an opaque token unchanged. After acquisition generate/validate one
   canonical exchange UUID and capture exactly one `GoogleExchangeAttempt`. Retain only within this invocation.
4. Maximum **three total exchange sends**. Retry only `SyncApiException.Transient` with the same attempt object/key/body/
   credential/challenge. Delay before send two is `max(1000, retryAfterMillis ?: 0)` ms; before send three is
   `max(2000, retryAfterMillis ?: 0)` ms. Honor a larger hint; never send early just to fit the foreground budget.
   Whole-invocation timeout ends a longer delay. Third transient returns RETRY_EXHAUSTED. No challenge/provider retry,
   rekey/recapture, retry on malformed/proof/reauth/permanent rejection, or automatic subsequent invocation.
5. After any first send, uncertain exact replay may occur after challenge expiry within the same budget. Do not refuse
   replay based on challenge/access/refresh expiry; service validates committed replay. Preserve returned original grant
   including expired access; existing later refresh owns renewal. No refresh is called by this orchestrator.
6. Check invocation/caller/process liveness after every suspend/dispatch, before next effects and final acceptance.
   Handoff is once only after service success. Success must be Private; an injected Active/PrivacyLocked result refuses
   UNAVAILABLE rather than claiming Google success activated an account.
7. Default total budget **180000 ms**, positive injectable timeout for virtual tests; includes challenge, Google, retries,
   delays and handoff. Use coroutine timeout/monotonic scheduling, not wall-clock subtraction to bound lifetime.
   Own timeout maps to TIMED_OUT; actual caller cancellation propagates and dominates racing faults/timeout.
   A dependency's independently thrown timeout while the caller remains live is UNAVAILABLE, not this budget expiring.
8. `close()` is synchronous, idempotent and permanently retires this orchestrator, cancels only its owned invocation
   work, never the caller scope or borrowed provider/facade. In-flight retirement yields fixed cancellation; later calls
   throw RETIRED. No provider clear/logout/authority invalidation/cleanup occurs automatically.
9. Cancellation/timeout must release the caller promptly even for cancellation-resistant injected seams. Owned work
   must have an exact invocation fence and cancellation propagation; detached late/queued results cannot initiate the
   next phase, consume a handoff or report success, even after a newer invocation acquires the slot. Never hold a monitor
   across suspensions. No in-flight predecessor may clear a newer slot. Actual already-admitted commits may survive lost
   returns: preserve evidence and require fresh explicit recovery, never compensate or claim server rollback.

Safe failures: classified Google failure -> IDENTITY_FAILED with its closed identityReason; challenge transient ->
TRANSIENT_FAILURE; nontransient RequestRejected/RequestTooLarge/ReauthenticationRequired -> REQUEST_REJECTED;
MalformedResponse -> MALFORMED_RESPONSE. Unexpected dependency/clock/UUID/model faults -> UNAVAILABLE, invalid request
preflight -> INVALID_REQUEST. Real AppSessionException/AppSessionStaleException propagate unchanged for consumer refusal.
Cancellation is never converted to a service failure. Raw provider/errorCode/exception messages, tokens, nonce, request
body, audience, account/origin/operation values never enter displays/logs/diagnostics. No exchange evidence enters Room,
vault, preferences, saved state or files; only the service grant passes to the existing guarded vault preparation.

## Independent test obligations and concrete harness facts

Risk is authentication/account privacy and uncertain replay; independently expected observables, not implementation
trivia. New QA tests:

1. JVM ordering/exact capture: literal nonce/token/challenge/key, one provider/challenge, one attempt across lost-response
   and throttled retries, actual exact body/key identity, required delays and exhaustion. Use literal parsed expected fields
   or independently handwritten body; never use the production encoder to generate expected bytes.
2. Expiry equality before provider and before first send; sent uncertain retry beyond challenge expiry returns original
   expired-access grant/deadlines unchanged to handoff. No refresh, clear or additional Google invocation.
3. Permanent/malformed/proof/reauth failures do not retry; unconfigured/mismatched origin/invalid intent/operation has zero
   effects. Safe fixed failures, identity reasons and hostile raw dependency details stay closed.
4. Whole budget, busy, caller cancellation, retirement, cancellation-resistant late seams and queued caller dispatch.
   Include cancellation at challenge/provider/exchange/delay/handoff seams using virtual time/gates, not sleeps.
5. Focused actual Room/vault/real-barrier foreground consumer integration: first EMPTY account yields PREPARING/Private
   vault+Room evidence only, with no bootstrap HTTP/activation; same-account recovery preserves domain/outbox and exact
   uncertain encrypted predecessor evidence while installing only fresh PREPARED credentials. Foreign account/origin,
   mismatched owner, naked request, stale Room expectation, process invalidation at unchanged Room expectation and
   cancelled/retired exchange result preserve stores and do not consume handoff. Representative simultaneous attempts
   cannot install twice; existing full R09 race matrices are reused rather than repeated.

Use existing QA-owned `AppSessionTestSupport.kt`: `AppSessionHarness(suffix)` has `openInMemoryDb()`, `newVault()` (real
Android Keystore/encrypted no-backup storage), `clean()`, `journalRow`, `deviceRow`, `outboxSnapshot`, `frozenIdentity`,
`customNames`; `AppSessionFixtures` has constructor wiring/grants/literal empty bootstrap/tail bodies. Existing support
is read-only reuse; don't enlarge/rewrite its broad matrices. `RoomSessionAuthority(db).read()` provides exact expectations.
`AppSessionFixtures.coordinator` accepts required barriers. Use the real `FiniteEditCoordinator(scope).pauseAndDrain()`
via `AppSessionTransitionBarrier` (no timer in an empty stack), or real `GymAppState.transitionBarrier()`/explicit
`AccountGymAppState` where timer/edit behavior is relevant. `FiniteEditCoordinator.launchEdit(key, onAccepted = {}, block)`
returns Job? and owns accepted ordered writes; `dispose()` releases its lifetime. Real `GymAppState(repository, scope,
clock, timerHardware, edits, session)` owns timer/edit transition drains; inject hardware only, not a fake live lease.
Publication barrier must be supplied but must not be invoked by foreground preparation. Scripted existing transport
literal responses may establish an existing account BEFORE the tested foreground flow; no actual Google success claim.

### Additional exact constructor facts for independent QA

All ordered/named parameters below are coordinator-verified contracts, not permission to inspect production:

```kotlin
ServiceOrigin.parse(value: String): ServiceOrigin
OpaqueToken(value: String)
ServiceChallenge(challengeId: String, nonce: String, expiresAtMillis: Long)
GoogleExchangeAttempt.capture(idempotencyKey: String, challengeId: String, idToken: OpaqueToken)
ServiceAccountRef(accountId: String, displayName: String?)
ServiceSession(account: ServiceAccountRef, accessToken: OpaqueToken, accessExpiresAtMillis: Long,
    refreshToken: OpaqueToken, refreshExpiresAtMillis: Long)
ServiceSessionBinding(origin: ServiceOrigin, accountId: String, sessionGeneration: Long)
RecoveryIdentity(binding: ServiceSessionBinding, operationId: String)
RecoveryRoomSnapshot(revision: Long, sessionGeneration: Long, cachedAccountId: String?,
    phase: RecoveryRoomPhase, identity: RecoveryIdentity?, baselineComplete: Boolean)
AppSessionState.Private(reason: AppSessionReason, room: RecoveryRoomSnapshot?)
AppSessionState.Active(session: AppAccountSession, deliveryStatus: AppSessionDeliveryStatus = IDLE)
// PrivacyLocked is a data object; Private is a data class.
```

`AppSessionReason`: CONFIGURATION_UNAVAILABLE, SIGN_IN_REQUIRED, PREPARATION_REQUIRED, IDENTITY_MISMATCH,
LOCAL_DATA_REQUIRES_DECISION, UNAVAILABLE. JVM fake handoff success may return
`AppSessionState.Private(AppSessionReason.PREPARATION_REQUIRED, request.expected)`; no real facade/Room is needed there.
`GoogleIdentityFailureReason`: UNCONFIGURED, NOT_FOREGROUND, UNAVAILABLE, USER_CANCELLED, INVALID_CREDENTIAL,
PROVIDER_FAILURE, BUSY, RETIRED. `GoogleIdentityException(reason: GoogleIdentityFailureReason)` uses fixed safe category
text and no raw cause. New foreground constructor names/order/defaults, enum and failure contract are exactly the new
API block and following paragraph above. QA uses four named request arguments only; any additional optional opaque
binding field is not a QA contract and must not be constructed/inspected. It is present only for facade-issued requests.

`FiniteEditCoordinator` is in `com.dragosfotache.gymtracka.ui`; `RepositorySession` is in
`com.dragosfotache.gymtracka.data.sync.store`. Its constructor takes `scope: CoroutineScope`; `dispose(): Unit` retires it.
`launchEdit(key: String, onAccepted: () -> Unit = {}, block: suspend () -> Unit): Job?` registers synchronously and
executes ordered accepted work in the supplied scope. For an empty/no-timer integration stack, wire
`AppSessionTransitionBarrier { _: RepositorySession? -> edits.pauseAndDrain() }` with real `FiniteEditCoordinator(scope)`.
Use this actual barrier, not ScriptedTransitionBarrier. A supplied `ScriptedPublicationBarrier()` is permitted and its
acquisition count must remain zero during tested preparation; it is not an invoked live publication stand-in.
Existing-account setup may use existing scripted fixtures to activate before the tested flow; reauthentication uses
real finite-edit drain and optionally the actual `GymAppState.transitionBarrier()` if a running timer is involved.
`GymAppState`/`AccountGymAppState` exact constructor names are listed in the harness paragraph; account root inputs are
`database, origin, vault, authApi, transport, runOwner, scope, clock, timerHardware, uuidProvider` (default UUID provider).
Do not instantiate the account root solely to inflate a no-UI preparation test.

## Source independence / allowed files

Worker production writes ONLY:

- `app/src/main/java/com/dragosfotache/gymtracka/data/sync/session/ForegroundSignInModels.kt` (new)
- `app/src/main/java/com/dragosfotache/gymtracka/data/sync/session/ForegroundSignInCoordinator.kt` (new)
- `app/src/main/java/com/dragosfotache/gymtracka/data/sync/session/AppSessionCoordinator.kt` (minimal capture/handoff/fence-entry change)

Worker may read those and existing production `data/sync/api/*.kt`, `data/sync/http/*.kt`, `data/sync/model/*.kt`,
`data/sync/codec/ServiceAuthCodec.kt`, `data/sync/session/*.kt`, and `data/sync/identity/AndroidGoogleIdentityProvider.kt`.
Do not inspect any tests/fixtures/reports, QA work or source/diffs outside this allowlist. No UI/storage/schema/dependency
writes; ask coordinator for missing facts/required scope expansions.

QA writes ONLY (new files):

- `app/src/test/java/com/dragosfotache/gymtracka/data/sync/session/ForegroundSignInCoordinatorTest.kt`
- `app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/session/ForegroundSignInHandoffTest.kt`

QA may read existing test-only `app/src/test/java/com/dragosfotache/gymtracka/data/sync/ServiceAuthAttemptTest.kt`,
`ServiceAuthBoundaryTest.kt`, `ServiceAuthCodecTest.kt`, `session/*.kt`, `http/ServiceAuthHttpApiTest.kt`,
and `app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/session/*.kt`,
`app/src/androidTest/java/com/dragosfotache/gymtracka/ui/FiniteEditCoordinatorTest.kt`,
`app/src/androidTest/java/com/dragosfotache/gymtracka/ui/AccountGymAppStateTest.kt` if present.
No production declarations/source/diffs/LSP/decompilation or Worker work, including generated production artifacts.
Compiler failures may identify missing API facts; ask coordinator rather than inspect production. Both roles read this
spec and task-relevant normative docs, not the opposite role's layer. Coordinator owns build/harness/docs/integration,
reviews both layers, supplies missing contracts, and grants exclusive serialized Gradle slots.

## Gates and stop condition

Iteration: Kotlin compilation and focused new JVM tests; instrumentation compilation then focused handoff device tests.
Acceptance: affected existing auth/session JVM checks plus **fresh full offline JVM**. At R11 personal package closure:
**full unfiltered default instrumentation**, `assembleDebug`, `lintDebug` (both source layers frozen), APK/merged-manifest
secret/test-helper/backup/permissions checks, and existing isolated auth interoperability
`:app:testRealBackend --tests '*RealBackendServiceAuthTest'` with a freshly owned disposable service under the
[isolated procedure](../../testing-strategy.md#isolated-real-backend-procedure). Verify executed XML names/counts/failures/
errors/skips; cached/filtered/compile-only evidence is not a full execution. One exclusive Gradle slot, owned emulator
serials/backend terminals, no personal resources. Retain full-device evidence before filtered runs overwrite it.

Reuse R11a parser/API24/current adapter and dependency proof unchanged; no new dependency changes planned. Full
engine-convergence is not an R11b gate. No schema/migration change planned: discovery is an owner scope blocker.
Unavailable required synthetic/package gates block acceptance. Actual Google/signing/HTTPS/phone remains explicitly
unverified R17a, public lifecycle/proof journeys R11c/later public milestones. Preserve the legacy replay limitation and
investigate any recurrence of unexplained Private activation failure, never hide it with retries.

Stop after independent review and all declared required synthetic/package gates, accepted documentation/evidence,
scoped local commit and fresh-coordinator handoff to dependency-ready R13. Do not activate startup, implement import,
claim personal-phone readiness, add a new R11 letter or silently defer a failed/unavailable gate.
