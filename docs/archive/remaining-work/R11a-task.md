# R11a — foreground Android Google identity adapter

> Archived specification, accepted 2026-10-08. [Acceptance review](R11a-review.md).
> Task-era authority below is historical, not a live assignment or next-slice release.

## Authority and stop

Owner released **R11a only**, deterministic-only with injected synthetic configuration; actual Google, OAuth/service/signing
configuration, personal accounts/devices and deployment are unverified and unauthorized. Finish independent review and
all declared synthetic gates, archive evidence, commit locally and prepare R11b pickup; **stop without launching a
successor or implementing R11b**. The finite roadmap remains two personal slices and deferred public R11c.

## Goal and non-goals

Implement the existing SDK-independent `GoogleIdentityProvider` with stable Credential Manager's explicit Google button
request. Return only an opaque ID token for later server verification. No account inference, JWT decoding, service
challenge/exchange, HTTP, Room/vault/prefs/saved-state writes, startup construction, UI, activation, clear-on-cancellation,
new schema/asset, sign-out workflow or public lifecycle orchestration. Merely constructing the adapter must not contact
Google or launch UI. Configuration is constructor input, never a checked-in audience or configuration discovery.

## Binding sources and inventory

Read supplied AGENTS and `docs/coordinator-workflow.md` independence/serialization rules;
`docs/testing-strategy.md` layers/proportional coverage/execution cadence; roadmap R11 section;
shared `authentication.md` introduction, HTTPS endpoints, Android storage and external configuration sections;
shared `android-client-protocol.md#authentication-handoff`. No full contract traversal or backend source/configuration access.

Coordinator verified these signatures from current code (QA must not read their declarations):

```kotlin
// package com.dragosfotache.gymtracka.data.sync.api
class OpaqueToken(val value: String) // requires nonempty; toString is redacted
interface GoogleIdentityProvider {
    suspend fun credentialFor(nonce: String): OpaqueToken
    suspend fun clearCredentialState()
}
```

The interface remains unchanged. A nonce is supplied by the service challenge, not generated/hashed/trimmed by Android.
Audience is the configured web/server OAuth client ID, not the Android client/package, email or service URL.
Current build: min24/target37/compile37, Kotlin2.4.20, AGP9.4.1, Compose2026.09, coroutines1.11.0.
No Kotlin LSP server is configured; allowlisted exact reads plus compilation are the bounded fallback, not clean diagnostics.

Official guide, checked 2026-10-08:
https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation
uses `GetSignInWithGoogleOption` for explicit button and current Activity for UI. Its generated nonce and alpha examples
are not normative here. Resolve matched Credentials/Play Services **1.6.0**, Google ID **1.2.1**. Public Maven POM shows
Google ID depends on Credentials1.6.0; its AAR manifest minSDK24 matches this app. Coordinator owns dependency changes,
resolved graph/merged manifest review and actual compatibility gates.

## Concrete API contract (new)

Use package `com.dragosfotache.gymtracka.data.sync.identity` for the Android adapter/SDK launch seam:

```kotlin
internal interface GoogleCredentialClient {
    suspend fun getCredential(activity: android.app.Activity,
        request: androidx.credentials.GetCredentialRequest): androidx.credentials.GetCredentialResponse
    suspend fun clearCredentialState(request: androidx.credentials.ClearCredentialStateRequest)
}
internal class CredentialManagerGoogleCredentialClient(context: android.content.Context) : GoogleCredentialClient
internal class AndroidGoogleIdentityProvider(
    activity: androidx.activity.ComponentActivity,
    serverClientId: String?,
    client: GoogleCredentialClient = CredentialManagerGoogleCredentialClient(activity.applicationContext),
    requestTimeoutMillis: Long = 120_000L,
) : GoogleIdentityProvider {
    fun close()
}
```

Construct the adapter on Android Main (for example in `ActivityScenario.onActivity`); its lifecycle observer registration
is Main-only. Synchronous `close()` may be called from any thread and must schedule actual observer removal on Android Main.
No SDK types escape the existing interface. Android client delegates suspend calls to real `CredentialManager`, created
using application context, while get uses the supplied Activity; clear uses `ClearCredentialStateRequest()`.
Use the stable suspend API's cancellation path rather than an unmanaged global scope/callback. No automatic retries.
`requestTimeoutMillis` must be positive and bounds both explicit SDK operations (default two minutes). Caller cancellation
is not translated to a failure. Tests may use `Dispatchers.setMain`/virtual time with injected client to prove timeout.

Add SDK-independent safe failures in `data/sync/api/GoogleIdentityFailure.kt`:

```kotlin
enum class GoogleIdentityFailureReason {
    UNCONFIGURED, NOT_FOREGROUND, UNAVAILABLE, USER_CANCELLED,
    INVALID_CREDENTIAL, PROVIDER_FAILURE, BUSY, RETIRED,
}
class GoogleIdentityException(val reason: GoogleIdentityFailureReason) : Exception
```

Only fixed safe messages; no raw provider exception, message, cause/suppressed exception, credential bundle, nonce,
audience, account/email or token in exception/display/logs. Cancellation propagated from the caller is the exception;
do not construct a cause chain around SDK failures. Constructor/config display must not reveal inputs.

### Request, parsing and failure policy

- Each explicit `credentialFor` builds exactly one `GetCredentialRequest` containing exactly one
  `GetSignInWithGoogleOption.Builder(serverClientId).setNonce(nonce).build()`.
- Exact original audience and nonce pass unchanged. Null/blank audience -> UNCONFIGURED before launch. Nonblank
  whitespace-padded audience -> UNCONFIGURED (reject, never repair). Empty/blank nonce -> INVALID_CREDENTIAL before launch;
  otherwise preserve it exactly, including whitespace. No authorized-account/auto-select fallback ladder, hosted-domain
  filter, extra claims, origin impersonation or prefer-immediately-available shortcut.
- Require current Activity RESUMED, not finishing/destroyed, for get admission; otherwise NOT_FOREGROUND. Returning
  provider UI can temporarily pause/stop the Activity: result fencing checks disposal/destroy/finishing/caller liveness,
  not a requirement to remain RESUMED throughout Google UI. Recheck immediately before launching and before accepting.
- Parse only `CustomCredential` of `GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL`, using real
  `GoogleIdTokenCredential.createFrom(data)`. Wrong type, missing/malformed required bundle, empty/blank token ->
  INVALID_CREDENTIAL. Return exact nonblank token as `OpaqueToken`; never decode claims or inspect email as authority.
- `GetCredentialCancellationException` -> USER_CANCELLED if caller remains live; coroutine `CancellationException`
  propagates, not user-cancel classification. `NoCredentialException`, provider configuration/unsupported absence ->
  UNAVAILABLE. Google parsing/shape failures -> INVALID_CREDENTIAL. Other SDK/provider faults -> PROVIDER_FAILURE.
  Clear failures similarly redact and classify absence/provider failure; caller cancellation still propagates.
- Both operations use one shared nonqueueing admission slot per adapter. Concurrent get/get or get/clear/clear -> BUSY;
  never launch a second UI, cancel the first in order to silently replace it or queue old intent. Failure/cancel/success
  releases the slot so a later explicit attempt is possible with its own nonce; no hidden retries.
- Adapter-owned timeout -> PROVIDER_FAILURE (bounded safe failure), not USER_CANCELLED. Never convert a parent timeout
  or external cancellation into a provider error. Parent cancellation takes precedence over a racing SDK fault/result.

### Lifetime and security

`close()` is idempotent and permanently retires the instance, invalidates its operation and cancels only adapter-owned
in-flight work (not the caller's parent scope). It must be safe against a concurrent completion; no result after retirement.
Final caller acceptance must recheck after dispatcher handoff; protected acceptance linearizes against close. A token ready
on Main but queued for caller dispatch when retirement occurs is not accepted.
ON_DESTROY (including recreation) retires automatically. Remove the lifecycle observer/release Activity references on
close; never reuse an old Activity for a new one. Closed-before-entry -> RETIRED; retirement during a call propagates safe
cancellation. A new Activity needs a new adapter. Cancellation-resistant injected SDK work returning late must not publish
its token; attempt/lifetime fences and caller-active checks apply even when that work ignores cancellation.

Orchestrate SDK/lifecycle interactions on `Dispatchers.Main.immediate`; use protected state for synchronous close/fencing
as needed. Do not hold locks across suspending SDK work. Clear is an **explicit primitive only** and needs a live,
not-destroyed adapter (not necessarily RESUMED), not configured audience. It neither signs out service state nor runs on
get failure, timeout, cancellation, close, destroy or construction. Store no token/nonce/result beyond operation locals;
Google state remains foreground-memory only. No Android hardware/persistence changes.

## Source-separated assignments

Worker read/write:
- new `app/src/main/java/com/dragosfotache/gymtracka/data/sync/identity/AndroidGoogleIdentityProvider.kt`
- new `app/src/main/java/com/dragosfotache/gymtracka/data/sync/identity/GoogleCredentialClient.kt`
- new `app/src/main/java/com/dragosfotache/gymtracka/data/sync/api/GoogleIdentityFailure.kt`
- read-only current `data/sync/api/ServiceAuth.kt` and listed normative docs/public SDK sources.

QA read/write:
- new `app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/identity/AndroidGoogleIdentityProviderTest.kt`
- optional additional new tests/fixtures only within that androidTest identity package.
- listed normative docs and public SDK APIs/sources, not production/Worker source/declarations/diffs/reports.
- Coordinator supplies build/API/harness facts. `ActivityScenario.launch(ComponentActivity::class.java)` uses the existing
  debug Compose test-manifest Activity; QA may instead request a test-only manifest/Activity from coordinator if needed.
  ActivityScenario callbacks must not block the main thread while waiting on a suspend launch. Scope lifecycle operations
  with `scenario.onActivity`, use gates/virtual time, not sleeps. Fake `GoogleCredentialClient` records actual SDK requests
  and returns actual SDK `Credential` objects; independent audience/nonce/token literals are the oracle.

Coordinator owns build/catalog/manifest changes, all docs, review, device/resources and all Gradle runs. Neither contributor
may start Gradle without an explicit serialized slot. Neither may inspect the opposite layer or use broad git/code searches
that expose it. Verify PI_PROVIDER/meta, PI_MODEL/muse-spark-1.3-contributor, PI_REASONING_LEVEL/high at startup. Completion/
questions via fire-and-forget `herdr agent prompt gym-coordinator ...`, no waits or acknowledgment polling.

## Proportionate acceptance gates

Authentication/dependency/lifetime risk, but no Room transaction/capture or app integration: no new Room matrix and no
backend/Google journey for R11a. Reuse existing auth/transport/vault/Room regressions via full offline JVM. New distinct
Android oracles cover exact audience/nonce/button/default request policy; SDK parsing and wrong/malformed/blank credentials;
config/foreground/provider absence; user versus caller cancellation and safe failures; one-owner concurrency; timeout and
cancel/late-success/late-fault fencing; close/destroy/recreation and clear primitive/no automatic cleanup. Avoid exhaustive
constructor/enum-only matrices. Coordinator inspects both full layers independently before acceptance.

Iteration: main + androidTest Kotlin compile, focused adapter tests. Acceptance:
1. `:app:compileDebugKotlin :app:compileDebugAndroidTestKotlin` (KSP, normal defaults).
2. Fresh full offline `:app:testDebugUnitTest`, inspect XML names/counts/failures/errors/skips (not a cached pass).
3. Fresh focused `:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.identity.AndroidGoogleIdentityProviderTest`
   on disposable **API24 and current API37** emulators; inspect complete focused XML, no real provider calls.
4. Frozen source/test `:app:assembleDebug :app:lintDebug`; resolved runtime dependency graph and merged manifest/API24,
   no new dangerous permissions/startup flow; APK synthetic-token/test-helper/JDBC/secret/config-exclusion audit.
5. Both diffs/links and `git diff --check`; unchanged asset/schemas/core interface/default startup.

Full unfiltered default instrumentation and existing isolated auth interoperability are deferred **R11b personal package
closure**, not claimed fresh here. Actual Google/audience/signing/trusted HTTPS/phone readiness is separately authorized
**R17a**. Missing required synthetic gate blocks acceptance. No source changes while lint is frozen.
