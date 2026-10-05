# R07c acceptance — uninterrupted push-before-pull and valid tail

**Accepted 2026-10-05**, against the [bounded normative specification](R07c-task.md). Fresh native Pi
`meta/muse-spark-1.3-contributor`/high Worker and QA maintained disjoint source allowlists in new sessions.
Coordinator settled interfaces, independently reviewed both layers, corrected integration/fixture defects and owned
all serialized Gradle slots. Contributors ran no builds and inspected no opposite-role sources.

## Delivered boundary

Inert `SyncDeliveryLoop(store, api, nowMillis).deliver(...)` holds exactly one uninterrupted admitted `PushRun` through
bounded push, pull and valid-tail bookkeeping. The compatible admitted `PushDeliveryLoop.deliver(run, budget)` overload
shares the existing loop; standalone epoch push remains unchanged. Public standalone pull still writes no last-success;
its internal terminal callback shares the paging core and carries the exact fenced/chained checkpoint before returning Tail.

Only actual fenced push `Empty` permits pull. Every retained/rejected/corrupt/deferred/auth/oversize/budget stop remains
push-only; budget exhaustion never probes even if its last acknowledgment emptied the queue. Missing complete baseline/
cursor or explicit expiry requests R08 without snapshot/null-cursor bootstrap. Invalid/foreign/wrong-context tokens
remain failures. No in-run retry, token interpretation, outbox rewriting, publication or cleanup was introduced.

`PushRun.recordPullTail` uses the shared handling Mutex and one Room transaction. It checks the complete row (including
null last-success), baseline/account/session/epoch/cancellation, observes selected-account outbox presence before/after
writes through `EXISTS`, and updates only last-success with null-safe exact-row predicates/count-1 and post-write checks.
Nonnegative supplied time remains exact, including equal/backward observations. Conservative token retirement survives
rollback/uncertain commit; only returned commit/active resumption installs a fresh token. Failed/uncertain writes require
durable reread. Earlier acknowledgments/batches survive later failure.

Tail/outstanding-intent/projection/convergence remain distinct. A valid bounded tail can coexist with newer local intent;
its nullable outstanding-work summary is an observation, not a convergence claim. Domain/outbox/watermarks/other scopes,
cursor, baseline/cache/materialized epochs, device/catalogue are preserved by tail bookkeeping. Displays are redacted.
API/store authorization pairing and one required shared owner per physical DB remain explicit caller obligations.

Room v10/entities/schemas/v1 asset, build/dependencies/manifest/backup configuration, HTTP/refresh and production
construction are unchanged. Shared-docs is unchanged. The app remains signed-out/local-only and constructs none of it.

## Independent review and resolved findings

- Settled same-run push composition and exact-checkpoint terminal callback before delegation, avoiding nested admission,
  release/reacquire or a post-Tail reread that could silently substitute a different boundary.
- Production review corrected missing pre-write outbox observation/recheck, full-row stale classification before fresh-read
  corruption validation, and count-based presence sampling (replaced with overflow-free `EXISTS`). Added explicit admitted
  push return cancellation check. Fresh production/KSP compilation passed; no behavioral workaround or activation.
- QA review repaired invalid UUID literals, a mis-typed helper receiver, pre-capture/pre-ack preservation oracles,
  missing account restoration, swallowed fence failures and nondeterministic canceled-child/tail gates. Added proportional
  budget/recovery/invalid/malformed, standalone compatibility, precise stale/trigger/rollback/reopen coverage. Equal timestamps,
  post-write cancellation and fresh reread are asserted without relying on private implementation oracles.
- Initial executed `SyncDeliveryTest`: 7 cases, 3 fixture failures from reusing physical workout UUIDs while resetting only
  sync tables. Reset the test-owned workout fixtures between subcases; final checks retain exact bytes and preservation.
  Initial tail run: 10 cases, 1 cursor-trigger fixture failure from recursively firing on its own cursor rewrite. Restricting
  the trigger to UPDATE OF last-success tests the intended post-write mismatch rather than a recursion/storage fault.
  Count-zero `RAISE(IGNORE)` correctly expects stale checkpoint; a selected-outbox removal trip proves complete rollback.
- Initial separate-ADB-server device task discovered the emulator but its native runner found no device: zero executed cases,
  never counted passed despite shell build success. TCP alias diagnostics produced 7 zero-failure cases but nonzero task exits,
  including a single-use-daemon/clean-generated-report check. Direct runner diagnosis was not substituted for the gate.
  Restarting ONLY the owned emulator with normal ADB registration resolved the gate; fresh filtered/full Gradle tasks passed.
  No personal device/configuration or unrelated process was altered; the pre-existing default ADB server was not
  restarted/stopped.

## Executed evidence

Linux amd64; Gradle 9.8.0, configured Java25 daemon/toolchain and Java27 launcher. Disposable test-owned Android17/API37
Google Play x86_64 emulator, 16,384-byte pages, SwiftShader. No backend/Google/account/personal-device action or fixture.
All Gradle tasks serialized; intended test tasks executed with task-scoped `--rerun`, valid unchanged build dependencies reused.
Actual nonempty XML testcase identities/counts/failures/errors/skips were inspected, not inferred from shell exits.

```sh
./gradlew :app:compileDebugKotlin --console=plain
./gradlew :app:compileDebugAndroidTestKotlin --console=plain
# Separately executed for EACH new class, never comma-filtered:
ANDROID_SERIAL=<owned-emulator> ./gradlew :app:connectedDebugAndroidTest --rerun \
  -Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.<Class> --console=plain
# REQUIRED full unfiltered package gate:
ANDROID_SERIAL=<owned-emulator> ./gradlew :app:connectedDebugAndroidTest --rerun --console=plain
./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin \
  :app:testDebugUnitTest --rerun --console=plain
./gradlew :app:assembleDebug :app:lintDebug --console=plain

git diff --check
git -C shared-docs diff --check
```

| Final scoped/package gate | Actual result |
|---|---|
| New `SyncDeliveryTest` / `SyncDeliveryFenceTest` / `PullTailCommitTest` | Separately filtered 7 / 6 / 10; 23 unique cases, 0 failures/errors/skips |
| **Full unfiltered instrumentation** | **585 unique / 49 classes**, 0 failures/errors/skips; includes all new23, R07a/b, R06/canonical/capture/publication/migrations, HTTP/vault and Compose/app-flow regression |
| **Fresh full offline JVM** | **811 unique / 49 classes**, 0 failures/errors/skips |
| Production/KSP and Android-test compilation | Fresh production/KSP executed after production corrections; Android tests rebuilt after fixture corrections; final checks reused unchanged outputs |
| **Explicit debug assembly/lint** | Passed; final assembly reused valid device-built APK, lint analyzed production/Android/JVM tests; zero errors and one unchanged test-only org.json `NewerVersionAvailable` warning |
| **APK/preservation** | Shared driver present; source database and all3 catalogue notice files byte-identical; checked project test/backend/JDBC helper classes and credential/config artifacts absent; entities/schemas/asset/build/config unchanged |
| Docs/scope | Owning docs/archive links/anchors, complete diffs/new files, both trees and whitespace checked at closure |

APK fingerprint at the packaging gate: `7a13854d81fa41ba4a6c15a5daae8b8d9f16bb393d87d179e4d24f5acf5a06c2`.
Source catalogue asset SHA256: `defa08e9c464f90f0f48d8e3441fe20151803b48948647a91742c1bb82fa9f7d`.
Exported schemas1–10 remained byte-identical. APK guard was scoped to actual packaged notices/project helpers (not hidden
Git metadata or OkHttp's unrelated `TaskRunner.RealBackend`); this is a targeted packaging check, not a release secrets audit.

No new Kotlin warnings; existing catalogue missing-index KSP, experimental AGP option, Gradle deprecation and collector
protobuf Unsafe notices remain. Bounded mcpls diagnostics attempts during integration were unavailable while Kotlin
initialized; no clean LSP claim. Direct review, compilation and lint supplied verification. Archived-spec comment-path
updates do not change tested behavior; final compilation verifies those source-only comment adjustments.

## Residual obligations and stop

All required R07c/package gates are executed; none silently deferred. Unchanged wire/refresh behavior needed no new backend
interoperability gate. R10 still owns real Room-backed two-store HTTP engine convergence; API24, hosted CI, real Google,
owner phone/laptop, release/security audit and activated-app behavior are not established by these results.

R08 owns complete staged bootstrap/baseline and explicit expiry recovery; R09 durable Room/vault authority/account
transitions; R12a actual finite-edit/timer publication barriers. Existing inert publication advances epoch and future
callers must use its returned epoch. Strict legacy accepted-auth replay precision remains unresolved, never rounded,
rewritten, assigned a new uncertain key or treated as cleanup authority.

Implementation stops at accepted R07c. Owned emulator/AVD/test Android-home and the separately created ADB server
were stopped/removed; no Gradle slot or active build remains. The pre-existing default ADB server/Gradle daemon were
not stopped. Commit only reviewed slice changes locally (no accepted-history rewrite/push), then hand off dependency-ready
**R08a staging storage foundations** to a fresh coordinator under the standing workflow. No R08 implementation or activation
in this session.
