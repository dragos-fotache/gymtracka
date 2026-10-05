# R08b — Bounded frozen-snapshot download validation acceptance

**Accepted 2026-10-05.** All predelegation R08b gates passed; scope stops before complete-account validation,
canonical baseline/absence/publication and activation. See [normative specification](R08b-task.md).

## Delivered boundary and independent review

Two inert production files add `SnapshotDeliveryLoop` and redacted immutable results. `start` validates all bounds/UUID
before effects, runs bounded push on the already admitted run, and begins actual snapshot HTTP only after fenced Empty.
Every other push stop remains push-only; retained stages block rather than bypass intent. `continueDownload` accepts only
this driver's LimitReached result on the same uninterrupted run. No new owner/admission/store/API/codec/schema exists.

Complete typed responses are deep-owned through existing codecs before the suspending post-response fence. Data,
malformed/empty-terminal and typed failure/expiry results all use exact stage fences. Complete pages commit through
R08a, preserving exact limit/token/baseline/source/history; cross-page storage/model/fence errors and cancellation
propagate. Only returned commits count; terminal wins at budget, no extra probe. A 102-page same-run test proves the
100-page invocation budget is not a permanent account cap. Authentic expiry retains staging for explicit abandon and
NEW UUID/null-token restart; invalid/wrong-context tokens never authorize cleanup. Failed/uncertain writes require honest
reread, not retired-token success inference. Downloaded is terminal transport/staging evidence only, not validated account
graph, complete bootstrap, canonical baseline/absence, last-success, publication or convergence.

Coordinator specified concrete seams/independent allowlists and reviewed all five new production/test files, including
new-file contents and corrections. Worker and QA used separate fresh native `meta/muse-spark-1.3-contributor`/high
sessions; Worker read no tests/reports/QA source and QA read no production declarations/implementation/diffs. Native
coordinator `openai-codex/gpt-6.1-sol`/high owned integration/final review. Communication was asynchronous; every Gradle
command was serialized under an explicitly sole-owned slot. Kotlin mcpls remained initializing after bounded attempts;
allowed-file reading and compilation were used, not a clean-LSP-diagnostics claim.

## Actual commands and fresh evidence

Commands ran from Android root; focused device commands used the test-owned emulator through native default ADB.
`--rerun` applies to the intended test task, not a blanket rebuild of dependencies. Actual XML testcase nodes, class
names and failures/errors/skips were inspected; table subcases are not extra test counts.

| Gate | Actual result |
|---|---|
| Worker `./gradlew :app:compileDebugKotlin --console=plain` corrected rerun | exit0; production/KSP executed |
| QA `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --console=plain` corrected rerun | exit0; Android-test compilation executed, production up-to-date |
| Coordinator corrected narrow race method `:app:connectedDebugAndroidTest --rerun -Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.SnapshotDeliveryTest#newIntentBetweenEmptyAndBegin_rejectsAndPreservesIntent --console=plain` | exit0; 1 actual test, all0 |
| Same connected command with ONE full-class filter `com.dragosfotache.gymtracka.data.sync.SnapshotDeliveryTest` | exit0; 17 actual tests, all0 |
| ONE class filter `com.dragosfotache.gymtracka.data.sync.SnapshotDeliveryFenceTest` | exit0; 12 actual tests, all0 |
| ONE class filter `com.dragosfotache.gymtracka.data.sync.SnapshotStageTest` | exit0; 21 actual tests, all0 |
| ONE class filter `com.dragosfotache.gymtracka.data.sync.SnapshotStageFenceTest` | exit0; 13 actual tests, all0 |
| ONE class filter `com.dragosfotache.gymtracka.data.sync.PullDeliveryTest` | exit0; 16 actual tests, all0 |
| ONE class filter `com.dragosfotache.gymtracka.data.sync.SyncDeliveryTest` | exit0; 7 actual tests, all0 |
| Coordinator final `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest --rerun --console=plain` | exit0; fresh FULL offline JVM 812 actual tests /49 classes, all0; compilation dependencies up-to-date |

“All0” means failures0/errors0/skips0. Final focused device evidence is **86 distinct tests /6 classes**: 29 new driver/
fence tests plus57 existing staging/pull/shared-driver regressions. The separately executed narrow race pass is not added
to that total. This is NOT a full unfiltered instrumentation run. After device gates, only the three new-test KDoc
spec paths were rebased to the archive; no behavior/assertion changed, and final compilation/JVM were repeated.
Production/KSP also executed in the coordinator's
first corrected race attempt; final corrected Android-test source compiled in the successful narrow pass. The earlier
fresh full JVM run also executed812/49 all0; only the final run is the acceptance count.

Independent tests cover exact opaque whitespace token/fixed limit chaining, whole multi-family/large record and later
reference pages, empty terminal/account and terminal-on-budget, preeffect rejection, no permanent cap, stale/foreign/
wrong-driver results, push-first accepted ack and non-Empty exclusion, retained stage blocking, intent races, deep input
ownership, exact typed failure versus authentic expiry, late response fences, queued/HTTP/post-write cancellation,
rollback/token retirement, shared ownership through continuation, file reopen and legitimate exact push-before-restart.
The production HTTP adapter is composed with real Room through an intercepted OkHttp factory and independent raw JSON;
API/store account/session are the same valid UUID. No live backend was contacted; existing JVM local-TLS/HTTP/refresh
coverage is reused, not a new backend or Google result.

## Honest failed gates and corrections

- Initial Worker compile exit1: four `VAL_REASSIGNMENT` errors in try/catch ownership locals. Replaced with synchronous
  owned-page helper; corrected compile passed. Result displays changed to the exact fixed type-name contract.
- Initial QA combined compile exit1: five missing model-import references; corrected imports without weakening tests.
  Three unnecessary-nonnull-assertion warnings in the first successful test compile were subsequently removed.
- First focused driver class exit1:17 tests/1 failure. Intent-race fixture held push-claim admission instead of begin,
  so inserted corrupt intent correctly produced a blocked push, not the expected begin exception. Gate corrected to
  release fenced Empty first and hold begin; complete preservation oracle moved after deliberately allowed injection.
- Next narrow race attempt exit1:1 test/1 failure. The expected IntentException escaped structured async parent handling.
  The child now catches/asserts that exact exception internally. Narrow and full-class reruns passed.
- Preexecution review corrected the fixture's speculative empty HTTP push, retained-stage exception expectation,
  missing admission seed and HTTP/Room account mismatch. Strengthened stale-token-before-fetch, post-page-write child
  cancellation, earlier-page-before-abandon, across-continuation ownership and reopen/intent preservation oracles.
  No assertions were weakened to accommodate production behavior; published seam contracts supplied missing facts.

No skipped/substitute gate or unresolved acceptance blocker remains. Observed build-tool warnings were the existing
AGP experimental option/Gradle deprecation and instrumentation protobuf `sun.misc.Unsafe` warnings; no new final Kotlin
warning. No fresh lint claim is made; existing catalogue FK-index warnings are a known pre-existing KSP boundary.

## Environment, preservation and cleanup

Owned headless x86_64 emulator used Android17/API37 (installed image directory37.2, 16KiB), private Android/AVD home,
normal native default ADB registration, no personal device/account. Emulator was stopped and private home removed;
existing default ADB server and Gradle/Kotlin daemons preserved. No active Gradle/resource owner transfers. No backend
was assumed or started. Compiler/JVM/device evidence is not API24, hostedCI, real backend, Google, two-store convergence
or activated-app evidence.

Room/exported schema remains11 with20 entity definitions (prior17 plus R08a's3); schemas1–11 and all prior entity
source are unchanged. Bundled sourceDB remains user_version1. SHA256 preserved:

- Asset: `defa08e9c464f90f0f48d8e3441fe20151803b48948647a91742c1bb82fa9f7d`.
- Room11 export: `b05f665c9ed1e63521f575b6bd827d50c424577d4706b1bcd5b00e3fd42f666d`.

No build/dependency/config/manifest/schema/assets or shared contract changes. Both repository scope/whitespace checks
and staged new-file review accompany the local scoped commit; no amend/rewrite/push. Domain/outbox/watermarks/checkpoint/
last-success/newer intent/other scopes/catalogue and original uncertain IDs/bytes remain protected by reused storage.

## Required later gates and stop condition

Predelegation deliberate deferrals remain: full unfiltered instrumentation, explicit assembly/lint/APK and applicable
isolated real backend at R08c R08 package acceptance and R10 integration, or earlier if packaging/security/schema/
activation risk changes. No unavailable R08b requirement was deferred after failure. API24/hostedCI/live Google/personal
journeys/two-store convergence/activation remain later authorized milestone obligations.

R08c complete canonical baseline/absence/publication recovery is next dependency-ready. Downloaded/staged absence
cannot delete canonical roots or establish a baseline; later-page dependencies, valid empty accounts and unchanged old
roots need complete graph/publication validation. R09 durable authority/journal, R12a real edit/timer barriers and R10
convergence still precede activation. Strict legacy accepted-auth replay remains unresolved: never round/rewrite/rekey
original uncertain grants or infer revocation/credential/cache cleanup. R08b implementation stops here.
