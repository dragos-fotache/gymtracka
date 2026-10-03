# Assignment 016 coordinator review — 2026-09-27

Status: approved. User explicitly requested Assignment 016, delegated implementation to the open Muse Spark session
through Herdr, and requested a detailed plan and independent verification. Model verified as Muse Spark Contributor
1.3 / Meta in Hermes session 20260927_203531_e60381, pane w3:p7. Detailed instructions: `016-muse-plan.md`; original
bounded specification: `016-task.md`. Baseline of 166 relevant source/schema/config/document files retained under
`/tmp/gymtracka-sync-coordination/016-baseline/`, with SHA-256 manifest. The baseline is temporary historical evidence;
this review is preserved in the Android repository's `docs/sync-assignments/`.

## Reviewed implementation

Seven source/test changes: shared FakeSyncService and FakeAccountSyncApi; new FakeSyncFixtures, FakeWorkoutCommandTest,
FakeRoutineCustomCommandTest and FakeImportDeleteCommandTest; SyncApiBoundaryTest adapted from push stubs to executed
empty/basic/unsupported outcomes. No production source, Room/schema/asset/config/dependency changes. Temporary
ScratchDetachDebugTest removed before the gate. Coordinator separately updated architecture.md, testing-strategy.md and
sync-implementation-plan.md to describe the approved slice.

The fake implements only workout.start/upsert, routine.upsert/save, customExercise.upsert, aggregate.import and
aggregate.delete. Reviewed staging before publication, account/type-scoped root ownership, namespaced catalogue
references and current canonical names, complete ordered children and null/metadata/rest fidelity, per-root revisions
independent of client clocks/known revisions, create-only collisions, retained root tombstones and child ownership,
custom reference-delete constraints, routine draft/promotion/autosave/import rules and atomic old-delete/new-upsert
active replacement. Deep copies separate request, canonical state, log, stored outcomes and inspection/returned values.

Review corrections implemented by Muse: child entry/reference confusion; omitted workout name/start scalar replacement;
silently coerced draft creation and upsert promotion bypass; re-delete tombstones for all root types; retained
removed-child reservations; root/child collision guards; save-time current names; invalid two-active workout test setup;
owner/tombstone rollback assertions; complete routine/custom import expectations; rejected active import preserving
old-active state; and actual nested request/outcome/log mutation checks using element replacement on fixed-size sorted
lists.

## Verification

Contributor focused gate: 52 executed cases across three new command suites, FakeSyncServiceTest and
SyncApiBoundaryTest. New cases: 12 workout + 10 routine/custom + 13 import/delete = 35. Full suite: 173 cases in 27 XML
reports; zero failures/errors/skips.

Coordinator independently ran:

```sh
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --no-configuration-cache --console=plain
./gradlew :app:testDebugUnitTest --rerun --no-configuration-cache --console=plain
git diff --check
git -C shared-docs diff --check
```

Combined gate passed; the second command forced the full JVM task to execute rather than relying on its up-to-date
result. Coordinator inspected nested XML testcase names/counts: 173, zero failures/errors/skips, with all 35 new cases
present and no temporary debug suite. Lint XML contains only the baseline NewerVersionAvailable warning. Shared fake
class outputs exist in both built_in_kotlinc debugUnitTest and debugAndroidTest intermediates; no
FakeSyncService/FakeAccountSyncApi descriptor occurs in production APK dex files. Baseline hashes confirm existing
production sources/schema/config/asset/dependencies were preserved.

No device run was required or claimed: this slice changes only fake/test sources. The latest full instrumentation gate
remains M3's 161 tests on API 37. Actual backend, OAuth, API 24 runtime and hosted CI remain outside this verification.

## Boundary and next assignment

Ten remaining semantic kinds explicitly return unsupported_mutation_kind. Pull/snapshot explicitly throw
UnsupportedOperationException. Recording an outcome is not execution deduplication: repeating a mutation ID re-executes
today. Stable original-result replay, identity retention, paging and snapshots remain Assignment 018. The fake cannot
yet serve as the complete engine oracle.

Assignment 017 remains unstarted; use [its preserved bounded task file](017-task.md) to implement the ten semantic
handlers after authorization, with a fresh Muse contributor. Assignment 016 was approved to the contributor,
acknowledged, and its session ended through native /exit. Herdr agent list confirms no contributor remains in w3:p7;
preserve the shell/pane and user focus. No commits or pushes.
