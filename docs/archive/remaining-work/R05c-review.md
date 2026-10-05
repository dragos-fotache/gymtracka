# R05c — Protected publication acceptance

Status: **ACCEPTED, 2026-10-04**, within [the bounded specification](R05c-task.md).
No R06, engine/account activation, delivery operation, commit/push or deployment is authorized.

## Delivered boundary

The inert `ProjectionStore` publishes a validated complete canonical account graph into Room only with an explicit
pause/drain lease, a complete baseline, an empty account outbox and matching account/session/cache fences. Every outbox
row protects the whole visible account graph, including rejected/corrupt/unsupported/unknown work. Missing/incomplete
baseline and clean equal-epoch retry leave storage unchanged. There is no default/live barrier or app construction.

Whole-graph dependencies, sole-active state, retained family reservations and physical scope/role/parent identities are
checked before replacement. Explicit scoped deletes/inserts and in-place surviving-custom updates preserve complete
ordered identities, fields, null/empty distinctions, metadata/quantity, configured/recorded rest and generation maxima.
Local/foreign/bundled collisions and surviving foreign references fail closed without remapping or cross-scope deletion.
Independent workout/routine/exercise namespaces remain independent. Unknown deletion/absence/reservation evidence does
not create fake domain roots. Current names resolve through selected-scope exercise data, not stale embedded labels.

Every actual publication increments the existing account cache epoch and sets materialized epoch to it atomically with
dirty clearing. This v10 signal includes custom renames at unchanged parent revisions and invalidates prior-epoch
callbacks. Checked overflow aborts. Canonical body/revision/state/row epochs, outbox, checkpoint/last success, device
identity and catalogue remain unchanged; only independent watermarks and publication bookkeeping may change.

Fresh native Worker and QA sessions used the required Contributor model at high reasoning, with disjoint production/
test source ownership. Coordinator supplied concrete APIs, reviewed both layers and owned all Gradle runs and integration
corrections. QA authored **59 real Room cases** with independent fixtures/row expectations, not production-encoder
oracles. Existing tests are unchanged. No entity/schema/migration/asset, repository/UI, dependency/configuration, manifest,
HTTP/session or shared-contract change was made; Room remains v10.

## Findings resolved before acceptance

- Dirty projections must publish even when cache/materialized epochs are equal. Retained family checks include reserved/
  deleted/absent roots, and physical other-role checks must run even alongside a matching same-role row.
- Fences/lease validity recheck after graph writes; cancellation checks cover entry and final metadata work. Returned
  leases close once. Lease-origin SQLite failures preserve their original identity, while projection-owned SQLite failures
  become fixed safe storage errors with no raw cause and caller cancellation takes precedence.
- Surviving customs update without FK-cascade replacement; update counts are checked. Obsolete customs delete per ID,
  avoiding unbounded SQLite binding lists without adding a domain cap. Missing generation reservations validate root UUIDs.
- Initial production compilation failed on a coroutine-context import; coordinator corrected it. Initial test compilation
  failed on suspend fixture setup and a factory lambda bound to the clock; those harness issues were corrected.
- Initial focused tests exposed duplicate intra-family IDs and an incorrect comparison with the post-capture watermark.
  Fixtures now reach their intended publication boundaries, and capture proves exact 23→24 domain/ledger/outbox advance.
  The final-revocation probe throws at the observed post-write point rather than assuming another callback. Cancellation
  probes cancel the actual caller after observing installed rows, and compare complete rollback snapshots.
- Assertions were strengthened for exact IDs/positions, maximum numeric values, null versus empty instructions/quantity,
  saved blank/empty routines, all root families' physical ownership collisions and full negative-counter rollback.
  No production invariant or negative assertion was weakened to accommodate a defect. Failed compile/focused evidence
  was inspected and retained separately from the final green reports during review.

These findings are resolved, not deferred defects.

## Executed evidence

All commands ran from the Android repository root. Fresh combined build gate:

```sh
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:lintDebug --rerun-tasks --no-configuration-cache --console=plain
```

Exit 0; **61 tasks executed**. Kotlin/KSP, JVM, Android-test compilation, assembly and lint passed. Nested JVM XML contained
**799 unique cases / 47 suites**, zero failures/errors/skips. Two subsequent test-only assertion/probe corrections were
recompiled by the final focused execution below; production was unchanged.

Final focused commands (separate class invocations, not comma-filter assumptions):

```sh
ANDROID_SERIAL=emulator-5582 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.ProjectionPublicationTest --no-configuration-cache --console=plain
ANDROID_SERIAL=emulator-5582 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.ProjectionPublicationGraphTest --no-configuration-cache --console=plain
```

Both exit 0; inspected XML contained **33 + 26 unique cases**, zero failures/errors/skips. These are scoped results.
Final coordinator unfiltered regression:

```sh
ANDROID_SERIAL=emulator-5582 ./gradlew :app:connectedDebugAndroidTest --no-configuration-cache --console=plain
```

Exit 0; instrumentation actually executed while build dependencies were up-to-date. Nested XML contained
**366 unique cases / 25 classes**, zero failures/errors/skips, including all 59 publication cases and all three migration
methods (every supported starting version, populated v9→v10 and copied bundled v1 asset). Table-driven subcases are not
counted as additional testcases. File-backed publication evidence is Room close/reopen, not a process-death/live-engine claim.

The owned fresh headless emulator used emulator 37.1.11.0 with the installed android-37.2 Google Play 16KB x86_64 image;
guest reported API 37 / Android 17. Writable data was isolated; no personal AVD data/snapshot, owner phone or account was used.

Lint XML had zero errors and one existing `NewerVersionAvailable` warning. Known catalogue foreign-key-index KSP warnings,
existing JVM warnings, Gradle/host protobuf deprecations and unstripped graphics-library notice remain nonfatal. Scoped
Kotlin LSP diagnostics observed no errors but did report style suggestions; later reference lookup was initializing,
so no whole-project clean-LSP claim is made.

Final preservation/scope checks:

- All ten schema snapshots, v1 source asset, build file and manifest remain byte-identical to the pickup baseline;
  GymDatabase changes only by its DAO accessor. Asset SHA256 remains
  `defa08e9c464f90f0f48d8e3441fe20151803b48948647a91742c1bb82fa9f7d`.
- Read-only catalogue parity/integrity/version verification passed for all five tables.
- APK database and three notice files match source bytes. A scoped scan of class definitions across 16 DEX files found
  no named publication/batch/backend/PostgreSQL/MockWebServer/coroutine-test helpers. Library logger-name strings are not
  helper class definitions. This is not a complete release/security audit.
- Production callsite search confirms no publisher construction outside its own declaration. Both repository diffs,
  new-file whitespace, and owning-document relative links/anchors were checked; shared-docs is unchanged.

## Residual boundaries and stop

The app remains signed-out/local-only. R08 owns real complete baselines/snapshots/absence establishment; R09 owns durable
Room/vault authority and transitions; R12a owns the real finite-edit/timer barrier, and R12 observation/rebinding is absent.
There is no running push/pull engine, Google/account UI or scheduler. The supplied lease contract requires nonthrowing,
nonblocking release; cleanup failure is not proof of rollback after commit. Current known canonical/physical bindings are
not account-lifetime removed-child reservation storage.

No backend run was required for this Room-only slice. API 24 runtime, hosted CI, live Google/phone/laptop connectivity,
engine convergence and production activation remain unverified. The legacy accepted auth replay limitation is unchanged.
R05 is closed through a/b/c; **R06 durable push** is the next proposed package and requires separate owner authorization
and its own bounded pickup specification. Stop here without commits or pushes.
