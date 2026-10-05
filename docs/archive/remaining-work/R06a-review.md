# R06a acceptance — durable head claiming and exact replay

**Accepted 2026-10-05.** Scope: inert Room claiming/replay admission only. This does not complete R06 durable delivery
or authorize another slice, app activation, commit, push or deployment. See the [specification](R06a-task.md),
[current implementation](../../sync/implementation.md#durable-head-claiming-and-replay-admission), and
[remaining outcomes](../../remaining-work-roadmap.md#r06--durable-push).

## Delivered boundary

- Required shared `PushRunOwner` serializes complete caller-owned runs; `PushStore.withRun` performs coherent fenced
  Room entry checks, rejects nested/enclosing-transaction entry, joins structured children and closes its run capability
  with cross-thread-visible invalidation. Caller-owned HTTP occurs outside Room; this slice constructs no HTTP driver.
- Explicit selected-account sequence head is never filtered around rejected, corrupt, deferred or retained-error work.
  Valid pending/frozen work is frozen with one checked admission-count increment transactionally before claim return;
  overflow, invalid bookkeeping, unsupported versions and corruption retain the original row without repair/deletion.
- Supported v1 wrapper/effect fields are closed at claim admission; version dispatch precedes content inspection.
  Existing codecs/row reader remain unchanged. Original wrapper/request bytes, effects, stable child plans, identities
  and frozen generations survive replay/reopen. Exposed request models are defensively owned.
- Read-only `checkClaim` binds the originating live run, session/account/cache epoch and every frozen-row field, including
  admission count. Newer local edits remain pending and do not rewrite an unchanged predecessor. No canonical/outbox
  acknowledgment, cursor/last-success publication, root sync-state reconciliation or domain publication occurs.
- Production changes are two new store files plus focused `SyncDao` queries. Room stays v10; entities, schema snapshots,
  source asset, app construction, transport/session components, manifest and build configuration are unchanged.

## Independent review and resolved findings

Coordinator reviewed full production and independent QA source. Worker and QA used fresh Muse Spark 1.3 Contributor/high
sessions with disjoint source ownership; shared-output Gradle slots were serialized. Kotlin constructor visibility was
reviewed directly by coordinator; QA's proposed reflective constructor oracle was removed before acceptance.

Review required coherent entry-fence transactions, cancellation checking after normal caller return, cross-thread
capability closure, redacted blocked diagnostics, independent noncanonical-byte/child-plan fixtures, complete-table
no-write oracles, actual canceled-caller late-response validation and post-write fence/cancellation rollback.

An executed core gate exposed an actual claim-admission defect: an unrepresented v1 wrapper field was accepted by the
existing permissive outer-wrapper reader. Claim-only closure now rejects it and unknown effect members without changing
old codecs or bytes; QA's corruption assertion was retained. Subsequent fixture defects (querying a deliberately changed
mutation ID and treating every JVM `List` as mutable) were corrected without weakening row/byte/ownership invariants.
The final full suite below passed. Table-driven corrupt/tamper subcases are not counted as extra JUnit tests.

## Executed verification

Environment: repository-root commands on Linux/JDK 25, Gradle 9.8; test-owned cold-boot headless Android 17/API 37
x86_64 Google Play 16 KB-page emulator, isolated AVD/userdata and no real account/device data. The owned emulator's
host ADB authorization was completed without changing personal AVDs or credential/configuration files.

Final fresh, unfiltered combined command:

```sh
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin \
  :app:assembleDebug :app:lintDebug :app:connectedDebugAndroidTest \
  --rerun-tasks --no-configuration-cache --console=plain
```

**Exit 0 / BUILD SUCCESSFUL; 86 actionable tasks executed.** Coordinator inspected actual nested XML testcase
names, uniqueness, failures/errors/skips and migration-class execution:

| Gate | Observed result |
|---|---|
| Kotlin/KSP and Android-test compilation | Passed; no new warnings in R06a source |
| Full offline JVM | **803 unique cases / 48 classes**, zero failures/errors/skips; includes 4 R06a contract cases |
| Full unfiltered instrumentation | **425 unique cases / 28 classes**, zero failures/errors/skips; includes all 59 R06a cases and 3 migration methods |
| R06a instrumentation breakdown | Core 27, ownership 14, replay 18 |
| Debug assembly/lint | Passed; lint XML contains one `NewerVersionAvailable` warning and no errors |
| Scope/schema/asset/shared-tree checks | Passed; source asset, exported schemas and shared repository unchanged |
| Scoped APK check | Packaged database and three notice files byte-identical to source; production PushStore present; R06a test transports/classes, backend fixture, MockWebServer and PostgreSQL helpers absent |
| Whitespace/links | Both repository `git diff --check` plus new-file whitespace and local documentation-link/anchor checks passed |

Focused connected checks were run **separately**, never as a comma-separated class filter:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.PushClaimTest \
  --no-configuration-cache --console=plain
# Same command separately for PushClaimOwnershipTest and PushClaimReplayTest.
```

Final focused XML: 27/27, 14/14, 18/18 passed with no failures/errors/skips. These are scoped evidence, not the full gate.
After archival, two comment-only specification-path updates were followed by
`./gradlew :app:compileDebugAndroidTestKotlin :app:connectedDebugAndroidTest --no-configuration-cache --console=plain`:
compilation executed and another unfiltered 425-case run passed; production/JVM behavior was unchanged.
The initial connected attempt returned shell success but executed **zero tests** because its native ADB subprocess was
unauthorized; it was rejected as infrastructure evidence, never counted passed. A later core run failed on the wrapper
closure defect, then on the mutation-ID fixture. Direct installed-APK replay preflight found the list-mutability fixture
error (17/18); it was not XML/full acceptance. Final corrected connected/full execution supersedes those failed gates,
without erasing their causes.

Baseline output includes catalogue FK-index KSP warnings, pre-existing JVM-test warnings, experimental AGP/deprecated
Gradle/protobuf Unsafe warnings and unstripped `libandroidx.graphics.path.so`. A focused replay logcat collector emitted
`Stream closed` after execution; actual complete XML remained green, and the final full run passed. Optional Kotlin LSP
navigation worked once but later diagnostics remained initializing; no clean-LSP-diagnostics claim is made.

## Residual obligations and stop

- Required owner sharing is a single-process caller contract, not a registry, multiprocess lease or durable vault/Room
  authorization. The live capability is not persisted; restart reclaims the original frozen request with a new admission.
- A prior successful `checkClaim` is not atomic result-application authority. Later R06 outcome/ack transactions must
  recheck exact request/effects/admission/session/epoch together with canonical writes; push cursors must never advance pull.
- This slice computes no retry policy and persists no new rejection/error outcome. Existing retained errors block the
  head; explicit recovery, 413 handling and rejection persistence remain later R06 work.
- No API 24 runtime, hosted CI, new real-backend/Google/live-phone gate, engine convergence, session journal, baseline
  establishment, real edit/timer barrier or app activation pass is claimed. Legacy auth replay incompatibility is unchanged.

**Stop at accepted R06a.** R06b outcome/retry handling is the next proposed slice and needs specific authorization and
its own bounded specification. No delivery operations were performed.
