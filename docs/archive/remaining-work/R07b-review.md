# R07b acceptance — bounded incremental HTTP pull

**Accepted 2026-10-05**, against the [bounded specification](R07b-task.md). Fresh native Pi
`meta/muse-spark-1.3-contributor`/high Worker and QA sessions maintained disjoint source allowlists. Coordinator settled
interfaces, independently reviewed production/tests, corrected integration/harness defects and owned all serialized
Gradle slots. Contributors ran no builds and inspected no opposite-role source.

## Delivered boundary

`PullDeliveryLoop(api).pull(run, maxPages, pageLimit)` receives an already admitted `PushRun`; it owns no admission,
mutex, store, credentials or run owner. It composes with eventual uninterrupted R07c ownership rather than nesting it.
Caller API/store authorization pairing and one shared `PushRunOwner` per physical DB remain required.

The inert loop reads one eligible checkpoint, fetches outside Room under exact fences, deep-owns/validates the **whole
page before any post-response suspension**, rejects malformed page/count/progress and run-local repeated batch IDs/
opaque cursors before the page's first commit, and chains complete ordered batches with fresh R07a tokens and their own
boundaries. Empty terminal tail preserves its input cursor. Earlier commits survive later failure; storage/canonical/
fence/cancellation errors propagate, and uncertain commits require a durable reread instead of token reuse.

Strict budgets are 1..100 pages and 1..200 complete batches per page, default 100 each, without domain-size caps or
truncation. Terminal pages return `Tail`; nonterminal budget stops return `LimitReached` without another fetch.
Missing baseline/cursor or explicit cursor expiry requests R08. Invalid/foreign/wrong-context tokens and other typed API
failures retain their exact category/instance, never silently bootstrap/retry/clear. Unexpected transport errors preserve
provenance. Safe summaries print no identities/tokens/bodies/service-issued codes.

Room v10/entities/schemas/asset, existing push/checkpoint/canonical/HTTP/refresh implementations, build/dependencies,
manifest/backup configuration and production construction are unchanged. No publication/domain/outbox/watermark/
baseline/epoch/last-success writes or convergence claim was added. Shared-docs is unchanged.

## Review findings and resolution

- Settled the admitted-run-only API before delegation, avoiding a second admission/owner and speculative R07c policy.
- Clarified whole-page ownership must precede the suspending post-response check, not merely precede first commit.
  An independent transaction-executor test mutates caller page/batch/change lists after API return and proves ownership.
- Initial production compilation failed because mutable checkpoint inference remained nullable despite the prior
  null-return branch. Added the explicit nonnullable type; final compilation passed. No behavioral workaround.
- Independent QA harness review corrected a queued-owner gate that never signaled, an ownership interception armed
  before initial read rather than after API return, cancellation identity across `await`, JUnit `fail` Elvis typing,
  reflective implementation-trivia assertions and a happy path that did not actually contain complete replacement/
  later dependency. Reused existing vetted fixtures/hooks; added representative post-write cancellation.
- Initial `PullDeliveryTest` execution ran 16 cases with one failure: selected-account retained UPSERT fixture contained
  only a revision, not complete canonical evidence. Corrected it with an independently authored valid existing fixture;
  also removed a contradictory null assertion on preserved newer domain intent. Final fresh rerun passed all 16.
  No assertion was weakened to accommodate a production defect; newer-intent preservation remains asserted exactly.

## Executed evidence

Linux amd64; Gradle 9.8.0, configured Java 25 daemon/toolchain, Java 27 launcher. A newly provisioned isolated test-owned
Android 17/API 37 Google Play x86_64 emulator with 16,384-byte pages executed real Room tests. No personal phone,
Google/account/backend/private configuration or fixture was used. Actual XML testcase identities/counts/failures/errors/
skips were inspected; intended test tasks executed freshly with unchanged build dependencies reused.

```sh
# Initial production-only compile failed, corrected and recompiled:
./gradlew :app:compileDebugKotlin --console=plain
# Production/KSP and full offline JVM executed after correction:
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --rerun --console=plain
./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --console=plain
# Final compilation and a fresh full offline JVM pass:
./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin \
  :app:testDebugUnitTest --rerun --console=plain
# Separately executed for EACH class below (not comma-separated filtering):
ANDROID_SERIAL=<isolated-emulator> ./gradlew :app:connectedDebugAndroidTest --rerun \
  -Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.<Class> --console=plain

git diff --check
git -C shared-docs diff --check
```

| Fresh final scoped gate | Cases | Result |
|---|---:|---|
| Full offline JVM / 49 classes | 811 unique | 0 failures/errors/skips |
| `PullDeliveryTest` | 16 | 0 failures/errors/skips |
| `PullDeliveryFenceTest` | 9 | 0 failures/errors/skips |
| Existing `PullCheckpointTest` | 21 | 0 failures/errors/skips |
| Existing `PullCheckpointFenceTest` | 11 | 0 failures/errors/skips |

The four separately filtered Room classes establish **57 unique cases**, including 25 new driver cases, not a full
instrumentation pass. Parameterized cases inside methods are not additional XML testcase counts. Fresh production/KSP
compilation occurred after the correction; final compilation reused unchanged production output while rebuilding
Android tests after archive-comment path updates. Full JVM was freshly rerun. Connected-task packaging is not an
explicit assembly/lint/APK security gate.

No new Kotlin compiler warnings were emitted. Existing experimental AGP option/Gradle deprecation and Java/protobuf
Unsafe notices remain nonfatal infrastructure findings. Bounded mcpls initially reported initialization; later reads
completed without indexing: result declarations had no diagnostics, loop had only a destructuring-style suggestion,
no errors. Compilation and direct review supplied the fallback; no clean diagnostic claim while indexing.

Source catalogue asset SHA-256 remains
`defa08e9c464f90f0f48d8e3441fe20151803b48948647a91742c1bb82fa9f7d`.
Owning docs, rebased archived-spec links/anchors and both repository whitespace/scope checks passed. Only the reviewed
new driver/tests and Android documentation belong to the scoped acceptance commit; no accepted R07a history is rewritten.
Test-owned emulator resources were stopped/removed before handoff; no Gradle slot remains owned.

## Residual obligations and stop

No required R07b gate remains unverified. **R07c must execute full unfiltered instrumentation and explicit debug
assembly/lint/APK checks** as the declared R07 package gate. No new API24, real-service interoperability, real Google,
engine convergence or activated-app result is claimed. Existing HTTP/refresh/codecs were reused unchanged; no backend
run was required for this bounded driver. R10 still owns real Room-backed two-store engine convergence.

R07c must settle minimal compatible admitted-run push composition, uninterrupted push-before-pull ownership,
retained-error policy and fenced valid-tail/last-success integration. Tail is not convergence/publication/outstanding
intent resolution. R08 owns complete bootstrap, R09 durable Room/vault authority, R12a real edit/timer barriers; all
remain prerequisites for production activation. Strict legacy accepted-auth replay limitations remain unchanged.

Stop implementation at accepted R07b. The app stays signed-out/local-only and constructs none of these components.
Scoped local commit and fresh-coordinator R07c continuation follow the owner's standing workflow. No push, deployment,
private configuration or live account/personal-device action was performed.
