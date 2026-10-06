# R09a — Durable private account authority and preparation journal

**Accepted on 2026-10-06.** Normative delivered boundary: [R09a-task.md](R09a-task.md).
This accepts inert Room storage/observations/private preparation/fencing, not vault coordination or app activation.

## Delivered boundary and important findings

- Room12 adds only empty independent `sync_session_authority`; old device rows do not fabricate account authority.
- `RoomSessionAuthority` supplies strict transactional R04c observations, exact-expected private reservation and private
  fencing. Checked counters, canonical account/origin provenance, local-root refusal and retained cache privacy hold.
- No ACTIVE writer, credential I/O, engine admission, imported/deleted/reassigned user data, app wiring or live action.
- Fresh native Muse Contributor/high Worker and QA maintained production/test source independence. Coordinator reviewed
  both layers, owned all serialized Gradle slots, integration/security/schema checks and test-owned device resources.
- Production review corrected insufficient post-write validation: complete expected journal/device tuples plus snapshots
  now check hidden origin/cache_state before commit; insert row IDs/update counts and caller cancellation are fenced.
- QA review corrected INTEGER-affinity fixture assumptions, a nonexistent-column query, wrong PK flag and permissive
  corruption assertions. It added genuine stale-repository proof, deleted drafts, child/relationship preservation,
  suppression, deterministic post-write caller cancellation and valid-but-wrong row rollback. Expectations were not
  derived from production or softened to accommodate defects.
- First actual session-package run failed one valid-tamper test: its unconditional self-updating trigger recursed into
  an SQL failure (`UNAVAILABLE`) instead of exercising the expected corruption boundary. Guarding the trigger's NEW
  cache_state fixed the fixture while retaining strict `CORRUPT_AUTHORITY` and rollback assertions. Final package passed.
- A preliminary comma-separated class filter executed only the first authority class (14 cases). XML inspection caught
  the incomplete selection; it is not evidence for the other requested classes or an extra full-suite pass. Final package
  selection and separately verified single-class runs supplied the required gates.

## Actual verification

Repository-root execution, JDK25/Gradle9.8.0, test-owned headless API37/Android17 Google Play16KB image with fresh isolated
AVD/user home. Boot completion and16384-byte guest page size verified. No personal AVD/database/account/backend used.
Existing catalogue FK-index KSP warnings, dependency lint warning and Gradle/Unsafe toolchain notices are not new defects.

| Executed command/check | Result |
|---|---|
| `./gradlew :app:compileDebugKotlin --console=plain` | Executed KSP/Kotlin; schema12 exported; pass |
| `./gradlew :app:testDebugUnitTest --rerun --console=plain` | Fresh full offline JVM:812 cases/49 XML suites, zero failures/errors/skips;73 existing R04c recovery/authorization cases included |
| `./gradlew :app:compileDebugAndroidTestKotlin --console=plain` | Instrumentation KSP/Kotlin executed; pass; later corrected tests compiled through connected-task prerequisites |
| `./gradlew :app:assembleDebug :app:lintDebug --console=plain` | Assembly/package/lint executed; pass; one existing NewerVersionAvailable warning, zero fatal/errors |
| `./gradlew :app:lintDebug --console=plain` after final test corrections | Affected Android-test lint analysis executed; pass; main/report dependencies reused unchanged |
| `:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.dragosfotache.gymtracka.data.sync.session --console=plain` | Final fresh package:63 cases, zero failures/errors/skips; initial failed package honestly retained above |
| Same connected task with individual `class` filters below | Each fresh run's XML names/class/count checked; all pass, zero failures/errors/skips |
| Schema/export/asset inspection |21 entities, authority only; all20 prior entity objects/views unchanged; exported1–11 and bundled v1 bytes identical to pre-slice HEAD |
| APK ZIP + `aapt dump badging/xmltree` | Correct application ID/min24/target37 and INTERNET/VIBRATE/WAKE_LOCK; unchanged bundled asset, ordinary backup enabled and vault excluded from backup/cloud/device transfer; no new named tests/vault/keystore artifacts |
| Both repository diffs, new-file whitespace and relative links/anchors | Checked before acceptance/commit; source and document scope reviewed |

All connected commands were run with `ANDROID_SERIAL=emulator-5554` for the one test-owned device. Executed class counts:

| Class (under `com.dragosfotache.gymtracka.data`) | Cases |
|---|---|
| sync.session.RoomSessionAuthorityTest |14 |
| sync.session.RoomSessionAuthorityConcurrencyTest |5 |
| sync.session.RoomSessionAuthorityCorruptionTest |11 |
| sync.session.RoomSessionAuthorityPreservationTest |6 |
| sync.session.SessionVaultAndroidTest |27 |
| gym.SessionAuthorityStorageMigrationTest — populated11→12, prior rows/table+index/FK SQL/sequence preserved, new table empty |1 |
| gym.GymDatabaseMigrationTest — supported1..11→12 loop, copied real v1 and populated9→10 |3 |
| gym.SnapshotStorageMigrationTest — unchanged populated10→11 staging |1 |
| gym.SyncFoundationTest — existing storage/fence regression |9 |

**77 distinct scoped Android cases**, including37 new authority/migration cases. Repeated/preliminary/failed cases are
not added to that total. This is not a fresh full unfiltered instrumentation pass. Final XML verifies cancellation after
both provisional writes, strict valid-tamper rollback, all supported starts/copied asset, exact durable preservation and
reopen; no mocked DAOs or sleeps. Test-owned emulator/AVD stopped/removed after checks; unrelated default ADB/Gradle
resources and panes preserved. No build slot/device/backend resource transfers to the successor.

## Remaining obligations and stop

R09b must settle one bounded prepared-vault/Room coordination and crash-recovery slice on pickup; reuse R09a rather than
recreate storage. No legacy-data decision is made: future preparation encountering local roots still refuses without
modification. Same-account reauthentication/complete activation, R12a live edit/timer barriers and core R10 convergence
remain required before production use. General signed-out/switch/sign-out/import-replace UI is deferred public scope
under [P16](../../../shared-docs/docs/backend-contract/open-decisions.md#confirmed-product-decisions).

Full unfiltered instrumentation and two-store/HTTP convergence remain R09/R10 integration gates before activation.
API24, hosted CI, real Google, deployed phone connectivity, OEM/cloud transfer, background recovery and public lifecycle
journeys remain unverified milestones. No real-backend gate is required for this non-network slice. The
[legacy accepted-auth replay limitation](../../sync/implementation.md#legacy-auth-replay-limitation) is unchanged: never
round/rewrite/rekey uncertain grants or infer broad revocation/cache cleanup.

Stop at this accepted inert boundary. Owning active docs/indexes are updated; only accepted source/tests/schema and
owner-approved prerequisite product/workflow documentation belong in scoped local commits. Standing coordinator
rotation uses the minimal prompt and separate readiness/release protocol. No push, deployment, private configuration
or live account/device action is authorized by acceptance.
