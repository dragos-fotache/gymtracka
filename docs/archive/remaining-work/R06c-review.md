# R06c acceptance — atomic canonical acknowledgment

**Accepted 2026-10-05.** Scope: inert single-head canonical application and exact acknowledgment only.
This does not complete R06 durable delivery or authorize another slice, app activation, commit, push or deployment.
See the [specification](R06c-task.md), [implemented boundary](../../sync/implementation.md#atomic-canonical-acknowledgment),
and [remaining delivery outcomes](../../remaining-work-roadmap.md#r06--durable-push).

## Delivered boundary

- `PushRun.acknowledgeAccepted` reuses the R06b handling mutex/token discipline and R05b canonical writer. One fenced
  Room transaction applies the complete accepted batch, acknowledges exact-generation affected metadata, removes only
  the exact frozen row and marks projection dirty. It performs no HTTP, successor claim, publication or capture.
- Accepted snapshots privately bind originating account and exact immutable durable identity. Ownership/protocol/binding
  validation precedes suspension; live run, exact head, session/cache fences and affected context recheck with writes.
  Unchanged fresh admission can reuse an earlier snapshot; matching IDs alone cannot reuse A's snapshot for B or changed
  same-account request bytes. `handleResult` accepted handling remains read-only.
- All local affected roots require independent generation evidence, including vanished roots and effects not returned
  by the cloud. Existing roots must be owned/custom and pending with valid covered metadata. Invalid context retains
  `frozen` with fixed `ack_context_corrupt` recovery text, preserves canonical/domain/cache state and blocks successors.
- Only physical roots whose domain generation and watermark both equal the frozen effect become synced, with monotonic
  highest known positive revision. Newer metadata, business rows/children/tombstones, watermarks and later outbox work
  remain unchanged. Unknown extra cloud deletes are canonical evidence, not acknowledgment of unrelated local intent.
  Synced means that generation's command settled, not equality of the local business projection with canonical data.
- Old accepted duplicates acknowledge without restoring obsolete winners; equal conflicts/invalid retained evidence roll
  back the whole operation. Dirty is set even for an all-no-op batch. Pull cursor, last-success, baseline/cache/materialized
  epochs, device identity, catalogue and other scopes are not advanced/changed.
- Tokens consume before write attempts even on rollback/uncertain commit; this is not persistence evidence. Post-write
  exact removal, affected metadata/watermarks, remaining outbox and live fences/cancellation checks fail closed. A canceled
  child cannot reuse its token while the parent run remains live; fresh admission recovers unchanged frozen bytes.
- Production changes are two focused acknowledgment files, PushStore/Accepted provenance extensions, a minimal canonical
  helper visibility change and narrow outbox/metadata DAO queries. Room remains v10. No entity/schema/asset, app
  construction, manifest/dependency/build configuration, HTTP/session implementation or UI changes were made.

## Independent review and resolved findings

Worker and QA used fresh verified Muse Spark 1.3 Contributor/high native sessions, disjoint production/test access and
asynchronous reporting. Coordinator reviewed both layers and owned serialized integration/device runs. Expectations are
hand-authored literals, complete persisted-row comparisons or original exact-byte comparisons, not production-generated
expected wire output. Existing independent Room/snapshot helpers were reused; no DAO mocks, sleeps or second server.

Coordinator review required private accepted account/exact-request provenance and post-write affected watermark checks,
including vanished/newer roots. The final implementation also reuses existing corrupt-outcome persistence instead of
forking its token/update logic. No production changes were made to accommodate incorrect test expectations.

QA fixtures were corrected before acceptance: valid root/capture seeding, post-admission rollback oracles, complete child
rows rather than counts, isolated account-versus-request provenance, a genuinely dirty-reset obsolete-result oracle,
exact original wrapper/inner bytes across reopen, valid pending tombstone replacement and the canonical UPDATE path.
Two initial instrumentation compilation errors were test-only tuple destructuring and an aggregate-helper receiver.

An initial fence run executed 10 cases with two fixture failures: a `runTest` receiver's child lost the live coroutine
marker, and the supposed head-tamper trigger modified an unrelated business field instead of acknowledgment context.
The corrected tests retain full rollback/token/recovery assertions, use an inherited child scope and mutate the actual
frozen admission after a canonical write. A subsequent main-class failure modeled a newer tombstone as `sync_state=
'deleted'` after capture; coordinator corrected domain-before-capture ordering and the established pending/non-null
`deleted_at_millis` convention. Finally, a compact DELETE-target case proves the separate target-operation branch plus
watermark-only newer protection. Final runtime results below supersede these fixture failures, without claiming the
unrelated arbitrary-business-trigger scenario is a supported malicious-trigger sandbox.

## Executed verification

Environment: repository-root Linux, Gradle 9.8/JDK 25 daemon; test-owned cold-boot headless Android 17/API 37 x86_64 Google
Play 16 KB-page emulator with isolated AVD/userdata. No real account/device data, personal AVD changes, backend service,
Google action or deployment. The emulator's skip-ADB-auth option applied only to this disposable test instance.

Production Kotlin/KSP compiled successfully during Worker iteration/review correction (coordinator consumed actual
source/API diffs and verified unchanged production inputs thereafter). Coordinator's fresh full offline JVM gate was:

```sh
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --rerun --console=plain
```

Exit 0 / BUILD SUCCESSFUL; the JVM task executed, with valid production/build dependencies reused. Instrumentation
compilation also executed successfully after fixture integration and final corrections. Production/JVM inputs remained
unchanged throughout subsequent Android-only test corrections; no cached JVM task was represented as another fresh pass.

Focused real-Room gates were separate single-class invocations, never comma-separated filters:

```sh
./gradlew :app:compileDebugAndroidTestKotlin :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.PushAcknowledgmentTest \
  --console=plain
# Same acknowledgment command separately for PushAcknowledgmentFenceTest.
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.PushOutcomeTest \
  --console=plain
# Same regression command separately for PushOutcomeRetryTest, PushOutcomeFenceTest,
# PushClaimTest, PushClaimOwnershipTest, PushClaimReplayTest and CanonicalBatchTest.
```

Final commands exited 0. Coordinator inspected actual nonempty nested XML testcase identities, uniqueness, intended
class selection, failures/errors/skips. Table-driven subcases are not additional JUnit cases.

| Gate | Observed final result |
|---|---|
| Kotlin/KSP and Android-test compilation | Passed; no new compiler warning in R06c production/tests |
| Fresh full offline JVM | **811 unique cases / 49 classes**, zero failures/errors/skips |
| New R06c Room | **28 unique cases / 2 classes**: acknowledgment 18, fences 10; zero failures/errors/skips |
| R06a/b push regressions | **86 unique cases / 6 classes**: 27 outcomes/retry and 59 claiming/replay; zero failures/errors/skips |
| R05b canonical regressions | **71 unique cases / 1 class**, zero failures/errors/skips |
| Combined scoped device evidence | **185 unique cases / 9 classes**; not an unfiltered/full instrumentation pass |
| Scope/source asset/exported schemas | Passed; original asset and all 10 schema fingerprints unchanged; production inputs unchanged after fresh JVM |
| Documentation/whitespace/shared scope | Relative targets/anchors and new-file whitespace checked; both repository diff checks passed; shared repository unchanged |

Baseline output includes catalogue foreign-key-index KSP, experimental AGP/deprecated Gradle and protobuf Unsafe warnings.
Kotlin LSP diagnostics remained initializing on bounded attempts; no clean-diagnostics claim is made. Test packaging
occurred as connected-task dependencies, not a separate fresh assembly/lint/APK audit.

## Residual obligations and stop

- **R06 package completion after delivery-loop delivery:** fresh unfiltered instrumentation, assembly/lint and APK checks.
  This slice changes no schema, packaging/dependency/security configuration or app/HTTP activation; scoped safety gates
  passed. R06d bounded durable push delivery loop is proposed next, not authorized by this acceptance.
- **Core R10:** actual Room-backed engine convergence through isolated HTTP. No new backend interoperability or engine
  convergence pass is implied by these store tests.
- **R17/later activation:** API 24, hosted CI, real Google, owner phone/laptop journeys and actual session/publication
  activation remain unverified. R08 complete baseline, R09 durable authority/journal and R12a edit/timer barriers remain
  prerequisites. Legacy accepted auth replay incompatibility is unchanged.
- Shared process-local ownership/tokens are not multiprocess leases, durable vault/Room authorization or proof of commit.
  Recovery after uncertain/failing acknowledgment still requires live fresh admission of unchanged frozen identity.
  Retained failures need future explicit recovery; no discard/reset API or delivery loop was added.

Owned test emulator resources were stopped and disposable AVD/userdata removed; unrelated agents, devices, personal
configuration and processes were preserved. **Stop at accepted R06c.** No commits, pushes, deployment or automatic
continuation were performed.
