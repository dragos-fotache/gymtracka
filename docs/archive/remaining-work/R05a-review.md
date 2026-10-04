# R05a acceptance review — canonical storage and capture watermarks

Date: **2026-10-04**. Result: **accepted at the bounded storage/capture stop**.
[Specification](R05a-task.md); enduring behavior is in [sync implementation](../../sync/implementation.md#canonical-ledger-and-capture-watermarks),
[architecture](../../architecture.md), and [the roadmap](../../remaining-work-roadmap.md).

## Delivered boundary

- Room **v10**, with independent account/type/root canonical ledger and separate account baseline/epoch/dirty/materialized
  bookkeeping. No domain FKs/cascades or public remote writer; existing tables and v1–9 snapshots are unchanged.
- Account capture advances checked `max(domain, ledger) + 1` for every local affected root. Domain/outbox/watermark/dirty
  writes share the existing session-fenced transaction; signed-out/no-op paths create no new sync storage.
- Data-preserving 9→10 migration reserves unobserved generations from owned roots and supported outbox target metadata;
  only valid strict v1 wrappers contribute extra effects. Corrupt/unsupported bytes/errors/order remain intact. No
  fabricated canonical revision/body/deletion/baseline, cursor advance, or account activation.
- QA-authored real-Room tests cover scope/type isolation, all root families, generation max/overflow/corruption, physical
  removal/recreation, semantic barriers/coalescing, frozen identity, multi-root effects, payload/SQL/caller-cancellation
  rollback, canonical/cache preservation, SQL defaults and file-backed restart. Migration tests cover every start 1–9,
  copied real v1 asset, and a populated v9 graph with exact SQL snapshots and vanished valid extra effects.

No canonical record/revision/batch application, domain projection, claim/ack engine, bootstrap/staging, durable transition,
UI/network/account/Google activation or scheduler was added. R05b/c remain separately authorized work.

## Ownership and review findings

The coordinator initially drafted production and a test file instead of following the required Worker/QA ownership.
After the owner corrected this, the test draft was removed from the repository and excluded from acceptance. Fresh native
Worker and QA sessions both verified `meta/muse-spark-1.3-contributor` at high reasoning. Worker independently reviewed
and adopted the bounded production drafts without changes; QA independently authored final tests from the normative
contract and supplied API/schema facts, without production/decompiled/schema/coordinator-draft access. Coordinator
reviewed both layers and performed final verification. This is not a claim that Worker originally authored the drafts.

Review corrections were applied by QA in that same session:

- Mixed-type SQL argument arrays needed explicit `Any?`; Android compilation initially failed, then passed.
- A semantic start was incorrectly expected to coalesce; it now remains a barrier and the coalescing case uses autosave.
- Unsupported/corrupt original row target metadata reserves its known generation; unvalidated extra effects do not.
  Expectations now independently cover nine populated-upgrade reservations without server-state fabrication.
- Complete literal canonical fixtures and full before/after SQL snapshots replaced partial JSON/count-only preservation
  claims; original escaped/whitespace request text is retained.
- Added missing default/boundary/payload/extra-SQL/cancellation cases and preserved complete-baseline/cache epochs across
  capture and separate cursor writes.
- Cancellation harness imports initially failed compilation; the first runnable version failed 1 of 27 device cases
  because `Job.join()` does not propagate target cancellation to a live observer. Final test cancels the actual gated
  capture caller after all effects are written, joins it, asserts cancellation, and observes unchanged DB state from a
  live parent. No production rule or rollback assertion was weakened.

The final source review has no unresolved R05a finding. Contributor Gradle slots were serialized: initially coordinator,
then a narrowly granted QA own-class slot, then coordinator again after explicit release. No backend/personal configuration,
user database, live phone/account, commit or push was used.

## Executed verification

Environment: Linux x86_64, Gradle 9.8.0, Java 25 Gradle daemon (Java 27 launcher), installed API 37 SDK/build tooling;
blank disposable x86_64 Google Play 16-KiB emulator image Android 17 / API 37 (`android-37.2` image package).
The existing personal AVD was not opened. XML nested testcase identities/counts/failures/errors/skips were inspected;
no table-driven subcases are counted as additional tests.

### Focused iterations

```sh
./gradlew :app:compileDebugKotlin --console=plain
./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --console=plain
ANDROID_SERIAL=emulator-5580 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.CanonicalStorageCaptureTest \
  --console=plain
ANDROID_SERIAL=emulator-5580 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.gym.GymDatabaseMigrationTest \
  --console=plain
```

First Android-test compilation failures and the 26/27 capture run are retained above, not presented as passes. After
correction, QA ran compilation and 27/27 own-class cases; coordinator **independently reran 27/27**, zero failures/errors/
skips. Coordinator separately executed migration **3/3**, including all nine starting versions inside one test and the
copied bundled asset inside another. These filtered runs are not full-suite evidence.

### Final full gate (fresh, unfiltered)

```sh
ANDROID_SERIAL=emulator-5580 ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest \
  :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:lintDebug :app:connectedDebugAndroidTest \
  --rerun-tasks --no-configuration-cache --console=plain
```

**BUILD SUCCESSFUL**, 86 executed tasks:

| Layer | Executed result |
|---|---|
| Kotlin/KSP and Android-test compilation | Passed; v10 schema exported |
| Offline JVM | **799 unique cases / 47 suites**, zero failures/errors/skips |
| Unfiltered instrumentation | **236 unique cases / 22 classes**, zero failures/errors/skips |
| R05a cases within full instrumentation | **27 capture/storage + 3 migration cases**; migrations include every v1–9 start and real asset |
| Debug assembly | Passed |
| Lint | Passed; one existing `NewerVersionAvailable` warning, no errors |

Other warnings: pre-existing catalogue relationship FK-index KSP warnings, existing JVM test warnings (including R04c
unused expressions), native library stripping/toolchain deprecations and experimental AGP option. No new R05a Kotlin
warning or suppression was introduced.

Additional coordinator checks:

- v10 exported schema adds exactly the two approved tables; all prior table definitions and schema JSONs 1–9 match HEAD.
- Bundled v1 asset matches HEAD and the packaged APK byte-for-byte; all three packaged third-party notices match sources.
  Asset SHA256: `defa08e9c464f90f0f48d8e3441fe20151803b48948647a91742c1bb82fa9f7d`.
- Selected test-only class strings (R05a migration/capture tests, backend fixture/relay and recovery test fixtures) are
  absent from APK DEX. This is a scoped exclusion check, not a full release/security audit.
- APK SHA256: `7791f396bbd13529c4dd8eef2f5de406eba914e461a05d84a44bc475f9aec114`.
- Both repository diff whitespace checks and changed-document relative-link/anchor checks passed. Shared-docs unchanged;
  pre-existing R04c production/tests remain intact.

## Residual obligations and stop

No API 24 runtime, hosted CI, new real-backend/Google/phone/laptop, two-store engine-convergence or app-activation result
is claimed. Existing legacy accepted-auth-replay precision limitation is unchanged. Canonical storage is inert except
local generation/dirty capture; no complete canonical cache or protected publication exists yet. Global physical PK
collisions, stale revisions/deletion replay and graph/publication validation remain R05b/c gates, not tested features
silently inferred from these storage tests.

R05a stops accepted with documentation/spec/review archived. R05b is next **proposed**, not authorized. No commit, push,
deployment, personal configuration, next-slice implementation, or production activation is included.
