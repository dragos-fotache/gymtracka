# R06b acceptance — single-head outcomes and retry/error handling

**Accepted 2026-10-05.** Scope: inert push outcome classification and durable retry/error bookkeeping only.
This does not complete R06 delivery or authorize another slice, app activation, commit, push or deployment.
See the [specification](R06b-task.md), [current implementation](../../sync/implementation.md#single-head-push-outcomes-and-retryerror-handling),
and [remaining outcomes](../../remaining-work-roadmap.md#r06--durable-push).

## Delivered boundary

- `PushRun.handleResult` deeply owns and fully validates one exact claimed outcome. Accepted/duplicate accepted returns
  a defensively fresh complete read-only snapshot, including extra unknown cloud roots and old positive revisions.
  Target operation is DELETE for aggregate.delete, otherwise UPSERT. It applies no canonical records and acknowledges
  nothing. Rejected/duplicate rejection stores exact unknown codes with fixed safe context; malformed evidence remains
  uncertain frozen work, never success or definitive rejection.
- `handleFailure` persists bounded absolute retry timing for transient faults; admission-count exponential backoff and
  clamped hints cap at five minutes, with checked overflow retained rather than wrapped. Request-too-large/permanent/
  malformed/wrong-operation failures retain frozen identity and safe recovery context. Authentication/reauthentication
  checks are read-only. Neither handler performs HTTP, delays, refresh, bootstrap or cleanup.
- Handling and claim checking serialize under a per-run mutex and recheck exact head/session/cache admission inside
  Room. Every bookkeeping write verifies one changed row and post-write head/fences/cancellation. Its process-local
  callback token is conservatively consumed before the update, even on rollback or uncertain commit/resumption;
  this is not persistence/ack evidence. Recovery reclaims unchanged frozen identity for a fresh admission. Read-only
  accepted/auth branches do not consume. Domain/canonical/cache/watermark/cursor/other-scope data remain untouched.
- Production changes: new `PushOutcome.kt` and `PushRetryPolicy.kt`, focused `PushStore.kt` extensions and one narrow
  `SyncDao.kt` update. Room remains v10; entities/exported schemas/source asset, manifest/dependencies/build configuration,
  HTTP/session components, UI and app construction are unchanged. No HTTP driver, canonical acknowledgment or engine exists.

## Independent review and resolved findings

Worker and QA used fresh verified Muse Spark 1.3 Contributor/high native sessions, with disjoint source ownership and
no cross-role inspection. Coordinator reviewed all production/test source and owned serialized Gradle/device runs.
Independent expectations remain hand-authored literals or before/after exact-byte/full-table invariants, never production
encoder-generated expected responses. No DAO mocks, sleeps, reflective private API tests or second server were added.

Coordinator review identified two callback-consumption hazards: identical retry bookkeeping after clock rollback, and
canceled child/result resumption after a commit while the parent run survives. The final conservative pre-write token
rule closes both without schema changes or false persistence/ack success. New Room tests prove identical-due reuse is
stale, failed writes retain storage and require fresh admission, and exact request identity survives recovery.

Test review corrected fixture typing, nested ownership oracles, actual delayed scripted transport/newer same-root intent,
full-table isolation rather than count-only checks, post-write cancellation timing and original pre-close replay bytes.
Initial instrumentation compilation failed on a positional outbox constructor fixture, then a list delimiter typo;
coordinator supplied public named fields and fixed the delimiter as harness integration. An unnecessary non-null assertion
was removed before final compilation. None of these changes weakened behavioral expectations.

The first outcome class run executed 10 cases with one nested-ownership failure: a generic query callback could race
prior/background Room work during synchronous input capture. It was replaced by a separately gated transaction executor
with inherited `UNDISPATCHED` handling admission. The **same full literal batch expectation passed**, including nested
input mutation after suspension and exposed-getter mutation. Production was not changed to accommodate that failure.

## Executed verification

Environment: repository-root Linux/JDK 25, Gradle 9.8; test-owned cold-boot Android 17/API 37 x86_64 Google Play
16 KB-page emulator with isolated AVD/userdata, no real account/device data. Host ADB authorization was completed only
inside that emulator; personal AVD/backend configuration was unchanged. Owned emulator/server resources were stopped and disposable userdata/keys removed after verification.

Production Kotlin/KSP executed during iteration. Focused helper iteration used:

```sh
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest \
  --tests 'com.dragosfotache.gymtracka.data.sync.PushRetryPolicyTest' --rerun --console=plain
```

Final JVM/compilation gate:

```sh
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --rerun \
  :app:compileDebugAndroidTestKotlin --console=plain
```

Exit 0 / `BUILD SUCCESSFUL`. The full JVM test task executed freshly, with valid compilation/build dependencies reused;
Android-test compilation executed. The subsequent deterministic Room-gate correction changed only Android test code
and was recompiled by its connected run. No production/JVM behavior changed afterward.

Focused connected gates were separate single-class invocations, never a comma-separated filter:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.PushOutcomeTest \
  --console=plain
# Same command separately for PushOutcomeRetryTest, PushOutcomeFenceTest,
# PushClaimTest, PushClaimOwnershipTest and PushClaimReplayTest.
```

All final commands exited 0. Coordinator inspected actual nonempty nested XML testcase identities, uniqueness,
failures/errors/skips and expected class selection; table-driven subcases are not additional JUnit cases.

| Gate | Observed final result |
|---|---|
| Kotlin/KSP and instrumentation compilation | Passed; no new compiler warning remains in R06b source/tests |
| Fresh full offline JVM | **811 unique cases / 49 classes**, zero failures/errors/skips; includes 8 new helper/diagnostic cases |
| New focused R06b Room | **27 unique cases / 3 classes**: outcome 10, retry 6, fences 11; zero failures/errors/skips |
| Existing focused R06a Room regressions | **59 unique cases / 3 classes**: core 27, ownership 14, replay 18; zero failures/errors/skips |
| Combined scoped device evidence | **86 unique cases / 6 classes**; includes gated deep ownership, actual post-write cancellation, clock rollback, disk reopen and late response/newer intent |
| Scope/asset/schema/activation/shared-tree review | Passed; no entity/schema/asset/configuration/app activation or shared-contract changes |
| Documentation/whitespace | Relative links/anchors, new-file whitespace and both repository `git diff --check` passed |

Baseline tooling warnings include catalogue FK-index KSP, experimental AGP/deprecated Gradle and protobuf Unsafe output.
The isolated emulator's console-auth warning was nonfatal; ADB and actual instrumentation/XML executed. File-scoped Kotlin
LSP found no errors in new outcome/retry files; PushStore has one harmless `UnnecessaryVariable` alias hint. No clean-all-
LSP, lint or fresh APK audit is claimed. Test resource cleanup preserves unrelated devices, agents and processes.

## Deferred gates, residual obligations and stop

- **R06 package completion:** fresh unfiltered instrumentation, assembly/lint and APK checks. This inert slice changes
  no schema, packaging/dependency/security configuration or HTTP boundary; its declared scoped safety gates passed.
- **Core R10:** actual Room-backed engine convergence through isolated HTTP. No new backend interoperability or engine
  convergence pass is implied by these stores/JVM tests.
- **R17:** API24, real Google, phone/laptop deployment and authorized live journeys. Hosted CI and actual account/session
  activation remain unverified here; legacy accepted auth replay incompatibility is unchanged.
- R06c must commit complete canonical application and exact acknowledgment together, checking live admission/fences at
  that write boundary. An accepted snapshot or prior check is insufficient; push cursors must not advance pull.
- Process-local tokens/shared ownership are not multiprocess leases, durable vault/Room authorization or proof of commit.
  Retained failures still require explicit future recovery; no discard/reset API, delivery loop or live publication exists.

**Stop at accepted R06b.** R06c atomic canonical acknowledgment is next proposed and requires separate authorization.
No commits, pushes, deployment, private configuration changes or automatic continuation were performed.
