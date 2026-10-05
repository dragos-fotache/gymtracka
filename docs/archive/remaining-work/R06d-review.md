# R06d acceptance review — bounded durable push delivery

**Accepted: 2026-10-05, bounded inert slice only.** Specification: [R06d-task.md](R06d-task.md).
No app activation or next-task authority is implied.

## Delivered boundary

`PushDeliveryLoop.kt` and `PushDeliveryResult.kt` under
`app/src/main/java/com/dragosfotache/gymtracka/data/sync/engine/` implement explicitly constructed single-account/session
push orchestration over the accepted R06a/b/c seams. One shared run lease spans durable freeze, pre-send exact-claim
check, one-request HTTP outside Room, classification and atomic acknowledgment. Only successful acknowledgments count
and permit successors. Failures stop without in-run retry/refresh, pull/bootstrap/publication or cleanup; a strict
1..100 work budget returns LimitReached without probing another head. Empty is not convergence.

Nine independent new real-Room instrumentation classes add 25 distinct driver cases, reusing existing capture/snapshot
fixtures and narrow scripted APIs. They cover ordered transport/commit boundaries, budget and no-work stops, exact
hand-authored child-bearing replay after file reopen, retained rejection/malformed/size/auth results, newer intent,
cancellation and typed-failure races, pre-send/late fencing, shared-owner serialization/recovery, rollback, invalid ack
context and an earlier committed acknowledgment surviving a later head failure.

No existing store/DAO/entity/schema, v1 asset, dependency, build configuration, app construction or shared contract
changed. Production remains signed-out/local-only.

## Independent review and resolved findings

- Production review found no behavioral defect. Removed a redundant null check and reused existing retained-code
  constants. Final production mcpls diagnostics were empty with `indexingInProgress=false`; discovered references were
  instrumentation only, not app construction. Test diagnostic availability was intermittent during indexing and is not
  claimed as an all-files clean LSP gate; actual compilation/lint/device execution establish those boundaries.
- Review corrected independent test oracles rather than weakening safety: actual frozen-head/predecessor-removal proof,
  canonical/domain/metadata assertions, unknown-code redaction, exact transmitted bytes, natural frozen auth readmission,
  genuine typed-failure-after-cancellation delivery, inside-caller cancellation identity and same-owner recovery.
- The initial focused gate executed 24 cases with two fixture failures. Retry's raw seed omitted captured pending/
  watermark context; the pre-send hook fired before freeze SQL and correctly rolled the claim back. Real fenced capture
  plus a never-sent hand-authored wrapper substitution repaired the first fixture; a post-claim transaction-executor
  admission barrier repaired the second. Narrow reruns passed without changing production or acknowledgment/frozen-state
  expectations. A final additional case independently proves earlier committed progress survives later failure.

## Executed gates

Environment: 2026-10-05, repository root; Gradle 9.8/JDK 25; fresh isolated API37 x86_64 Google APIs PlayStore emulator,
16KB pages. No owner phone/account, personal emulator data, backend source/suite or private configuration was used.
Build tasks reused valid compilation/packaging dependencies; intended test tasks executed freshly. Nested XML names,
counts, failures/errors/skips were inspected, not inferred from a successful shell exit.

| Command / check | Actual result |
|---|---|
| `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --rerun --console=plain` | Fresh full offline test task: 811 unique cases / 49 classes, zero failures/errors/skips; production compilation up-to-date |
| `./gradlew :app:compileDebugAndroidTestKotlin --console=plain` (serialized contributor slots) | Passed after final test corrections; independent device/lint runs reused those compiled outputs |
| `ANDROID_SERIAL=<isolated-emulator> ./gradlew :app:connectedDebugAndroidTest --rerun -Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.<PushDeliveryClass> --console=plain`, one class per scoped run | Corrected focused coverage passed; latest Retry/Fence/Cancellation reruns 8/8; final Rejection/progress class 8/8. Final full gate below independently includes all 25 new cases |
| `ANDROID_SERIAL=<isolated-emulator> ./gradlew :app:connectedDebugAndroidTest --rerun --console=plain` | Final fresh **505 unique cases / 42 classes**, zero failures/errors/skips; all 25 new driver cases, existing push/canonical/publication/capture, migration, HTTP/vault, persistence and Compose/app-flow regressions |
| `./gradlew :app:assembleDebug :app:lintDebug --console=plain` | Passed. Debug APK reused the packaging produced by device builds; lint analyzed changed sources/tests, zero errors and one unchanged `NewerVersionAvailable` warning for test-only org.json |
| APK/source preservation | Packaged v1 database and notice files byte-identical to source; driver present, checked new test/backend/JDBC helpers absent from main dex; schema/asset unchanged |
| Both repository `git diff --check`, changed-doc relative links/anchors and new-file whitespace | Passed at closure |

Nonfatal tooling warnings: existing experimental AGP Kotlin source-set option, deprecated Gradle features and test
collector protobuf Unsafe calls. No new dependency or warning suppression was introduced.

No required R06 gate remains unavailable/deferred. Unchanged transport/contracts need no new backend interoperability
run; real-HTTP/Room engine convergence remains R10. API24, hosted CI, actual Google/phone/laptop, release audit and app
activation remain unverified later gates, not inferred from this acceptance.

## Residual boundaries and stop

Callers must explicitly pair API/store service/account/session and share one run owner per physical database.
This primitive is not multiprocess coordination or durable vault/Room authority. Pre-send checks cannot retract already
admitted bytes; auth/errors never authorize cleanup. R07 pull, R08 bootstrap, R09 journal, R10 convergence and R12 real
edit/timer publication barriers remain outstanding. Legacy accepted auth replay precision remains unresolved.

R06d and the declared R06 package Android gates are closed. Enduring implementation/design/test facts were extracted to
owning documents; roadmap/handoff were replaced and this specification/review archived. Stop here: no R07 planning or
implementation, app activation, commit/push, deployment or personal configuration is authorized.
