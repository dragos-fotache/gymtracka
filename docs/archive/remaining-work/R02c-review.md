> **Historical record, not current instructions.** See the [archive boundary](../README.md) and [current pickup](../../coordinator-handoff.md).

# R02c — Coordinator implementation review

Date: 2026-10-03. Status: **accepted**.
The owner explicitly authorized R02c after finishing R02b. R02b's review closed before this slice began. Subsequent
update: the owner authorized the full Android working-tree commit; see [delivery record](R02bc-commit-review.md).
No-commit statements below record the prior implementation gate, not the later delivery authorization. No push, next
slice, production transport, backend/configuration change or live account/device action is authorized.

## Delivered scope

Production changes are limited to `data/sync/api/SyncApiFailures.kt` plus KDoc-only updates in `SyncApi.kt` and
`ServiceAuth.kt`, under `app/src/main/java/com/dragosfotache/gymtracka/`:

- Five zero-argument categories: `ReauthenticationRequired`, `InvalidCursor`, `InvalidSnapshotToken`, `SnapshotExpired`
  and `RequestTooLarge`. `AuthenticationRequired` now means service-access authentication, not automatically foreground
  reauthentication. All ten categories have fixed codes/messages and closed null causes.
- Existing public constructors/codes, opaque `RequestRejected.errorCode` and five-minute transient hint clamping remain.
  Unknown codes are not whitelisted, capped, interpolated into display text or converted into transient failures by SDK types.
- API comments distinguish invalid from expired read tokens, access refresh from explicit foreground auth, and request-size
  recovery from retry. No interface signatures/constants/models or production classifier/retry machinery changed.

Coordinator-owned test-layer alignment:

- `app/src/test/java/.../data/sync/backend/HttpGymSyncApi.kt` routes top-level failures by operation AND status: matching
  400/410 pull/snapshot errors use the new read types; 401 explicitly distinguishes reauth; 413 is status-authoritative.
  Authentication/size/throttling/retryable-server statuses retain precedence. Wrong-context token codes never become expiry.
- `SyncApiBoundaryTest.kt` updates access-auth display text. `HttpSyncApiTest.kt` now expects dedicated snapshot expiry
  and uses an unknown permanent code for generic rejection coverage; old methods/coverage were not dropped.
- Consumer audit found `backend/RealBackendIsolationTest.kt` still catching old invalid-token categories. A shared-spec
  amendment allowed coordinator-only updates to its three catch/assert expectations, not fixtures/setup/operations.
  The suite compiled but was NOT executed; no external backend source/configuration/database was touched.

QA independently authored `ServiceFailureTypesTest.kt` (24 methods) and `HttpFailureCategoriesTest.kt` (43 methods).
All new transport cases use scripted results, not sockets or backend persistence/domain handlers.

## Independent review and corrections

Coordinator rediscovered Pi contributor identities/models (`meta/muse-spark-1.3-contributor`, high reasoning), started
fresh native `/new` sessions in Worker `w4:pJ` and QA `w4:pK`, and supplied the same
[normative specification](R02c-task.md) with exact API/harness facts and disjoint allowlists. Worker never inspected tests,
fixtures/reports, QA or coordinator adapter source. QA never inspected production declarations/diffs/decompilation,
Worker or the old adapter/harness implementation. Shared-output Gradle execution was serialized.

Full production/new-test/changed-adapter and legacy-expectation review included bounded corrections:

1. Worker clarified frozen OR unsent intent (frozen may already be uncertain), authoritative status precedence and
   access-auth versus explicit-reauth contract comments; no behavior/interface expansion. Production compile passed.
2. QA replaced an incompatible sibling cancellation-type check, caught exact initCause refusal, tested long opaque SDK
   codes without a new cap, actual Retry-After headers rather than vacuous null hints, exact bearer values, frozen 413
   single-send/body preservation and original transport cancellation on all three operations.
3. Coordinator test compilation exposed five redundant always-false checks after RequestRejected smart casts. QA removed
   only those assertions before the focused run; exact positive category/code observations still enforce no expiry shortcut.

Contributor-session Kotlin mcpls was unavailable/indexing after bounded attempts; coordinator obtained API symbols for
navigation but final diagnostics were still indexing. No retry/sleep/configuration loop or manual global configuration
change was used. Actual compiler/test execution supplies the gate, not an unavailable diagnostic result.

## Correctness boundaries and limits

The failure types are SDK-independent category vocabulary. They do NOT implement refresh, foreground Google UI,
account/cache deletion, outbox retries, splitting, staging cleanup or production response parsing. Generic 401/404/offline
still never confirms deletion. A new type/message is not evidence that those recovery actions now run in the app.

The JVM-only adapter remains test-only, uses existing frozen request encoding and count/order validation, preserves
opaque cursor/token/bearer forwarding and original cancellation, and returns HTTP 200 rejected outcomes normally.
Messages/cause/toString checks keep raw response/service text and credentials out of failures. 413 sends once and does
not regenerate IDs or bytes; this proves adapter observations, not Room outbox retention or uncertain-write orchestration.

Existing JVM error extraction retains its pre-existing machine-code syntax/64-character guard and JVM org.json duplicate
check. That is not a new SDK limit or proof of Android production parsing. R03 must implement the finalized raw/error
contract through production-compatible codecs/transport; R02c adds no parser/factory or production HTTP dependency.

## Actual gates

Worker production compile passed:

```sh
./gradlew :app:compileDebugKotlin --console=plain
```

Coordinator adapter/new-test compilation passed, then QA passed its exclusive focused run:

```sh
./gradlew :app:compileDebugUnitTestKotlin --console=plain
./gradlew :app:testDebugUnitTest --tests 'com.dragosfotache.gymtracka.data.sync.ServiceFailureTypesTest' --tests 'com.dragosfotache.gymtracka.data.sync.HttpFailureCategoriesTest' --rerun-tasks --console=plain
```

Coordinator independently inspected XML: **24 + 43 = 67 tests**, zero failures/errors/skips. Tables and exact observable
results, not method count, establish category/routing/redaction/byte/cancellation coverage.

Final coordinator forced gate (61 actionable tasks executed):

```sh
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --rerun-tasks --no-configuration-cache --console=plain
```

- **283 offline JVM tests in 30 XML suites**, zero failures/errors/skips: prior 216 plus 67 new methods all executed.
  The real-backend package was excluded, not skipped; compilation of its updated assertions does not prove service behavior.
- Production and Android-test Kotlin compilation, debug assembly and lint passed. Lint has only existing
  `NewerVersionAvailable`; existing FK-index, test transport/helper, Gradle and native-symbol warnings remain. No new
  compiler/lint finding was introduced by the final new tests/production API changes.
- Both repository diff checks and explicit new/untracked delivery whitespace checks passed; local Markdown links resolve.

No device, Room/migration, real-backend, Python launcher, OAuth, hosted CI or personal phone/laptop gate was executed.
R02a's six API 37 smoke tests and M3's full 161-device-test gate remain earlier evidence. The app still has no running
sync engine, production HTTP/auth adapter, snapshot publication or working account UI.

## Preservation and stop

R02c pickup HEAD remains R02a `2a93c56`; accepted R02b and the older Android/shared-docs baseline were already dirty.
A 220-file SHA-256 inventory confirms only the three existing production API files, four coordinator-owned test/adapter
files and four current delivery docs changed; **209 other inventoried files remain unchanged**, including R02b source/
tests/spec/review, schemas, assets, dependencies/build setup, scripts and all nested shared-docs. Two tests and the R02c
specification/review are new. Verification applies to this preserved working tree, not a clean-checkout claim.

No staging/commit/push was performed; index remains empty. R02b/R02c are accepted but uncommitted. Preserve all older
retirement/HTTP/build/AGENTS and nested-doc changes alongside them.

Stop after R02c. The next proposed pickup is a bounded R02d sign-in challenge/auth codec and exact-body-boundary plan,
only after explicit authorization. R02e public lifecycle, production transport, configuration/deployment and commits/
pushes are not authorized by this review.
