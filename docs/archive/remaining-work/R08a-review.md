# R08a acceptance — durable frozen-snapshot staging storage

**Accepted 2026-10-05**, against the [bounded normative specification](R08a-task.md), after explicit owner release.
Fresh native Pi `meta/muse-spark-1.3-contributor`/high Worker and QA maintained disjoint source allowlists; coordinator
settled the contract, independently reviewed both layers, integrated corrections and owned every serialized Gradle slot.
Contributors ran no builds and inspected no opposite-role source. The owner separately authorized the minimal existing
nonblank→nonempty snapshot continuation correction and focused tests. Progressive-reading documentation maintenance was
committed separately first, not bundled into this implementation slice. No accepted history was rewritten or pushed.

## Delivered boundary

Room10→11 adds ONLY `sync_snapshot_stages`, `sync_snapshot_records` and `sync_snapshot_tokens`, with unique account/stage,
whole root bodies/page positions and exact continuation/page evidence. Stage-only child cascades; no domain/canonical FKs.
All17 prior entity definitions and schemas1–10 remain unchanged; all supported starts and the copied real v1 asset migrate
without fabricated baselines or rewriting existing domain/outbox/canonical/checkpoint/sequence values.

Existing admitted `PushRun` owns begin/read/check/append/abandon operations on the same handling Mutex/shared owner.
Begin requires no selected stage and ANY selected-account outbox presence=false, binds origin session/epoch/fixed limit
and full source checkpoint/baseline observation, and retires an old pull token before writes. Entire typed pages are
deep-owned before suspension; whole records, continuation and checked header progress commit atomically. Strict retained
bodies and exact type/ID/revision, root uniqueness, baseline/limit, token cycles and every nonterminal page are validated.
Malformed Long counters are compared with actual evidence before iteration/allocation; no permanent content/history cap.
Nonempty opaque whitespace continuations remain exact. Later-page dependencies and independent family namespaces stage
without being presented as a complete validated account graph.

Write attempts retire capabilities before effects even on rollback/uncertain commit; only returned commit/active resumption
installs a fresh token. Restart/durable reread yields a nonappendable observation, not resume authority. Recovery explicitly
abandons ONLY the exact valid selected stage under current fences, then begins a new UUID/null-token stage. Corruption is
preserved as a safe failure; cancellation/run close/invalid tokens perform no implicit cleanup. Earlier committed pages
survive later failure. Local capture after begin remains allowed and exact newer intent stays unchanged.

Durable selected stages, including downloaded/corrupt metadata, block ordinary push before head selection/freezing and
reject checks/outcomes/acks and all incremental checkpoint/batch/tail operations. Foreign scopes are unaffected; existing
budget exhaustion never probes a head. One application-owned `PushRunOwner` per physical DB and authorized API/store
service/account/session pairing remain explicit caller obligations.

Downloaded storage is NOT actual HTTP exhaustion, complete bootstrap, canonical baseline/absence, last-success,
convergence or visible publication. No snapshot HTTP driver, complete publication, durable authority, UI/account/Google,
scheduler, live timer/edit barrier or app construction was added. Production remains signed-out/local-only.

## Review findings and honest failed evidence

- Coordinator corrected retained JSON from a change envelope to the existing strict canonical-record body reader/writer,
  avoiding accepted DELETE/no-record evidence and permissive scalar parsing. Rejected fabricated Long-sized token ranges;
  downloaded progress now requires every nonterminal page even when a terminal page exists. Added independent corrupt
  counter/missing-page coverage. Reused DAO abstract-class/internal-mutator pattern and final source/session/epoch checks
  AFTER durable evidence reread, including initial zero-child validation.
- Initial compilation passed production/KSP but failed QA compilation: nullable-single/list helper mismatch, missing
  import/matrix loop brace and source-independent baseline signature correction. Same QA session repaired these without
  production access. New redundant conversion warnings were removed, not hidden.
- Initial full JVM executed812/49 with ONE failure in the previous exact five-reason enum expectation. Added the specified
  sixth `SNAPSHOT_STAGE` reason while retaining exact equality and all other assertions. Final fresh full runs passed.
- Initial focused Room executed20 cases, ONE fixture UNIQUE-workouts.id failure from reseeding a physical UUID after
  clearing only outbox. Distinct test-owned identities corrected the fixture; final21 cases passed. Fixed capture/reopen
  setup/oracle timing rather than weakening preservation assertions.
- QA prematurely reported some review corrections complete; coordinator live review caught missing absent-source,
  post-write source-fence and migration autoindex/sequence details. Those were actually added and executed before acceptance.
  Coordinator restored a dropped `afterMaster` query and strengthened the source-trip oracle to exact stale failure.
- Migration comparisons retain every prior schema/value and exclude ONLY new-table schema entries by `tbl_name`, including
  new PK autoindices. Next sequence61 is demonstrated after preservation assertions. Raw legacy/corrupt retained bytes
  remain preservation fixtures, not fabricated validated canonical observations.
- Initial APK verification script incorrectly included repository-only `.gitattributes` as a packaged notice. Corrected
  the script to the actual three packaged `.txt` notices and reran; no APK/data/assertion/required gate was substituted.

All original failures remain distinct from the later passes. No skipped test, cached task, direct runner or alternate
model/device/backend gate was counted as a successful required gate.

## Executed evidence

Linux amd64; Gradle9.8.0, configured Java25 toolchain/daemon. Isolated test-owned Android17/API37 Google Play x86_64
16KB-page emulator with SwiftShader, private temporary AVD/Android-home and metrics disabled. Normal native ADB registration
used the existing default server; no personal device/configuration, backend or Google/account fixture. Every Gradle command
had an explicit sole coordinator slot. Intended test tasks really executed with task-scoped `--rerun`; unchanged dependencies
were reused. Nonempty XML names/counts/failures/errors/skips and shell exits were inspected and retained separately.

```sh
./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --console=plain
# Separate executions for EACH stage/fence/populated-migration/all-start-migration class:
ANDROID_SERIAL=<owned-emulator> ./gradlew :app:connectedDebugAndroidTest --rerun \
  -Pandroid.testInstrumentationRunnerArguments.class=<fully-qualified-class> --console=plain
# Required full UNFILTERED schema-risk regression:
ANDROID_SERIAL=<owned-emulator> ./gradlew :app:connectedDebugAndroidTest --rerun --console=plain
./gradlew :app:assembleDebug :app:lintDebug --console=plain
# Final fresh KSP/production/JVM plus archive-comment Android compilation:
./gradlew :app:kspDebugKotlin --rerun :app:compileDebugKotlin --rerun \
  :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest --rerun --console=plain

git diff --check
git -C shared-docs diff --check
```

| Required final gate | Actual result |
|---|---|
| Focused staging/fences | Separate21 / 13 Room cases, zero failures/errors/skips |
| Focused migration coverage | Populated10→11 case1; existing migration class3 (all1–10 starts, copied realv1, preserved historical9→10); zero failures/errors/skips |
| **Full unfiltered instrumentation** | **620 unique / 52 classes**, zero failures/errors/skips; includes new35, all migration/persistence/sync/HTTP/vault/Compose/app-flow regression |
| **Final full offline JVM** | **812 unique / 49 classes**, zero failures/errors/skips; new whitespace boundary and exact stage-reason compatibility included |
| Production/KSP + Android-test compilation | Fresh KSP/production executed, schema11 exported; final Android-test compilation passed without new warnings after archive-comment rebasing |
| **Explicit assembly/lint/APK** | Passed; assembly reused valid device-built APK, lint freshly analyzed production/Android/JVM tests; zero errors and only prior test-only org.json NewerVersionAvailable warning |
| Schema/asset/scope | Schemas1–10 byte-identical; schema11 preserves all17 previous entity definitions; source/packaged v1 catalogue and all3 notices byte-identical; checked project test/backend/JDBC/JUnit helpers and credential/config artifacts absent; shared-docs unchanged |

Schema11 identity: `7632eadf2dcf0de1c1be6e45e5a79b11`.
APK fingerprint at packaging gate: `22b0ee0f2f15115c20c08d973343a700720906f6bf614435ec65cbf75d719c68`.
Source catalogue SHA256: `defa08e9c464f90f0f48d8e3441fe20151803b48948647a91742c1bb82fa9f7d`.
Archive-comment changes after device gates changed no tested behavior; final compilation/full JVM verified the rebased paths.
Existing catalogue FK-index KSP, experimental AGP option, Gradle deprecation and collector protobuf Unsafe notices remain.
Bounded mcpls navigation became available after initial indexing; no clean-diagnostics claim. Direct review, compilation
and lint supply acceptance. This targeted APK check is not a release secrets/security audit.

## Stop, resource closure and residual obligations

All required R08a gates passed; none silently deferred. Test-owned emulator and private Android-home/AVD were stopped/
removed. No active build/Gradle slot remains; pre-existing default ADB server and Gradle daemon were preserved. Temporary
verification logs remain only as test-owned evidence. No backend/device resource ownership is transferred implicitly.

R08b bounded actual snapshot download validation is next eligible. R08c owns complete canonical baseline/absence and
publication recovery; R09 durable Room/vault authority/journal, R12a live finite-edit/timer barriers and R10 two-store
HTTP convergence still precede activation. Snapshot budgets must not create an account-size cap. Existing publication
advances epoch and eventual callers must use the returned epoch. Strict legacy accepted-auth replay remains unresolved,
never rounded/rewritten/rekeyed or treated as cleanup authority. API24/hostedCI/live Google/owner phone/backend/activated
app journeys are not proved by these results. Commit ONLY the reviewed R08a slice locally, rotate to a fresh exact-model
coordinator for R08b with asynchronous readiness/release, and close only the outgoing coordinator pane LAST.
