# R07a acceptance — fenced incremental batch/checkpoint transactions

**Accepted 2026-10-05**, against the [bounded specification](R07a-task.md). Independent production and QA contributors
used fresh Muse Spark 1.3 Contributor/high sessions with disjoint source allowlists; coordinator reviewed both layers and
executed the gates below. No contributor ran Gradle or inspected the opposite source layer.

## Delivered boundary

Room v10 is unchanged. The existing admitted `PushRun` now exposes read/check/atomic-next-batch operations for an opaque
`PullCheckpoint`, sharing the physical database's required `PushRunOwner` and short handling Mutex. Eligible admission
requires a complete baseline and nonnull persisted cursor; missing baseline/cursor returns null, never initial-history
pull authority. Present malformed cursor/last-success rows fail closed without repair, even when baseline is unavailable.

Complete owned canonical batch application and cursor-only advancement commit together under exact full checkpoint,
session/generation/epoch and live-run fences. Returned fresh tokens support a sequential chain without reread. Clearing
the single current-token reference before read/write retires prior tokens, including failed/uncertain writes, without
an unbounded token ledger. Recovery rereads durable state; process-local consumption is not commit evidence.

Newer intent, domain data, outbox, watermarks, other scopes, baseline/epochs and last-success stay intact. Canonical
revision/family arbitration and dirty/no-op semantics are reused. Explicit DELETE evidence and unresolved references
remain independent of visible projection. No schema/migration/asset/build/dependency/production factory changes occurred.

## Review findings and resolution

- Initial read implementation validated corruption only after eligibility. Moved validation ahead of missing/incomplete
  baseline/null-cursor branching; independent tests cover those contexts.
- Removed an unnecessary retained token set: fresh-object identity against one current token supplies sticky retirement.
  Coherent database read/fence work remains inside Room, followed by active capability/cancellation checks.
- Corrected independently authored fixtures and oracles before acceptance: matched change/body revisions, snapshots
  after deliberate tamper, valid no-progress batches, foreign-run versus consumed-token expectations, and additional
  same-run chaining/reopen/protection cases. Removed new Kotlin test warnings.
- The first fence run executed 10 cases with one failure: post-write last-success mismatch produced a generic invariant
  exception where QA had inferred typed stale classification. Coordinator explicitly settled the public contract to
  `StalePullCheckpointException` for cursor update-count/post-row/baseline mismatches, preserving existing session/epoch
  fence types. Production was corrected; independent last-success/session assertions remain distinct, not weakened.
  Final fresh class reruns passed all 32 cases and affected regressions below.

## Executed evidence

Linux amd64; Gradle 9.8.0 with configured Java 25 daemon/toolchain (launcher Java 27). An isolated test-owned Android 17 /
API 37 Google Play x86_64 emulator with 16,384-byte pages executed the Room tests. No personal phone/account/backend
configuration or database was used. Actual XML testcase identities/counts/failures/errors/skips were inspected; test tasks
executed freshly while unchanged build dependencies were reused.

```sh
./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --console=plain
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --rerun --console=plain
# Executed separately for EACH class in the table (no comma-separated filtering):
./gradlew :app:connectedDebugAndroidTest --rerun \
  -Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.<Class> --console=plain
git diff --check
git -C shared-docs diff --check
```

| Fresh final gate | Cases | Result |
|---|---:|---|
| Full offline JVM / 49 classes | 811 unique | 0 failures/errors/skips |
| `PullCheckpointTest` | 21 | 0 failures/errors/skips |
| `PullCheckpointFenceTest` | 11 | 0 failures/errors/skips |
| Existing `CanonicalBatchTest` | 71 | 0 failures/errors/skips |
| Existing `PushAcknowledgmentTest` | 18 | 0 failures/errors/skips |
| Existing `PushAcknowledgmentFenceTest` | 10 | 0 failures/errors/skips |

The five separately filtered Room runs establish **131 unique executed cases**, including 32 new cases, not an
unfiltered instrumentation pass. Production/KSP and Android-test compilation passed; final test compilation emitted no
new Kotlin warnings. Existing catalogue foreign-key-index KSP warnings, experimental AGP option/deprecation notices and
Java/protobuf Unsafe warnings remain nonfatal infrastructure findings. Bounded mcpls diagnostics completed without
indexing: changed production files had no errors; PushStore retained its pre-existing local-variable inlining warning.

The source asset SHA-256 remains `defa08e9c464f90f0f48d8e3441fe20151803b48948647a91742c1bb82fa9f7d`.
Schema snapshots/entities/database version, manifests, packaged notice sources and build/dependency inputs are unchanged;
shared-docs remains clean. Changed documentation paths/anchors/whitespace and both repository diff checks passed.

## Residual obligations and stop

No required R07a gate remains unverified. Full unfiltered instrumentation and explicit debug assembly/lint/APK inspection
remain deliberately deferred to **R07c's package gate**, as specified before delegation. Connected-task packaging is not
an additional explicit assembly/lint/security audit. No new API24, Google, backend, engine-convergence, app-activation
or live-device result is claimed.

R07b must validate bounded HTTP pages/run progress and distinguish invalid versus expired cursors without interpreting
opaque tokens. R07c must compose uninterrupted push-before-pull ownership, not nest driver admission, and define tail/
last-success behavior without claiming convergence from push summaries. R08 owns complete baseline/bootstrap; R09 owns
Room/vault authority; R12a owns real edit/timer publication barriers. Strict legacy auth replay limitations are unchanged.

Stop at accepted R07a. The app is still signed-out/local-only and constructs none of this machinery. R07b requires new
owner authorization and a bounded specification. No commit, push, deployment, personal configuration change or automatic
continuation was performed or authorized by this acceptance.
