# Assignment 017 coordinator review — 2026-09-28

Status: approved. The user authorized Assignment 017 only, required Android app work without backend changes, requested
Muse implementation through Herdr with coordinator review after each subtask, and explicitly prohibited starting
Assignment 018. After the user corrected an overfragmented 017b handoff, the remaining 017b commands stayed in one Muse
session; 017c and 017d each ran in one session. No commit or push was made.

## Reviewed implementation

The test-only `FakeSyncService` now executes all 17 typed push mutation kinds. Assignment 017 added the ten semantic
handlers: `workout.finish`, `workout.performAgain`, `routine.startWorkout`, `workout.saveAsRoutine`,
`workout.addExercises`, `workout.replaceExercise`, both workout child deletes, and both routine child deletes. Request
builders and independent JVM command tests were added under `app/src/test`; the existing fake boundary/unsupported
expectations were updated. Production sources, Room/schema, the bundled database asset, dependencies, HTTP, auth, UI,
workers and backend contract were unchanged.

The coordinator reviewed current owned source eligibility, finish cleanup and consecutive positions, exact clone/copy
child bindings, immutable source copies, prior-active DELETE then new UPSERT in one batch, history selection by finished
time descending/workout UUID descending/exercise position ascending, `history_shape_changed` rollback, reference
replacement preserving the entire workout entry except reference/display name, current-root lookup for child deletion,
retained removed-child ID reservations, and saved routines remaining saved when emptied. Every handler stages one
complete effect before commit; rejected commands preserve roots, revisions, active pointer, ownership and account log.
The fake still records outcomes without deduplicating execution.

Corrections made during review included fixture IDs colliding with retained children, invalid attempts to recreate
tombstoned active roots, a root-ID plan rejected by local envelope validation before fake execution, one post-rejection
snapshot moved before its request, and Kotlin test compile/expected-count fixes. The final tests assert accepted setup
mutations where those fixtures matter. Finished canonical workouts cannot contain incomplete sets under
`WorkoutValidation`, so history tests use valid finished imports and verify copied completed inputs; the fake also
filters defensively.

## Verification

Eight new command suites contain 50 tests: finish 6, perform-again 5, routine start 6, save-as-routine 5, history
additions 9, reference replacement 4, workout child deletion 7, and routine child deletion 8. The coordinator
independently forced `:app:testDebugUnitTest` to execute: **223 tests in 35 XML reports, zero failures, errors or
skips**. Focused command suites also passed after corrections.

The coordinator ran
`:app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --no-configuration-cache --console=plain`,
then `:app:testDebugUnitTest --rerun-tasks --no-configuration-cache --console=plain`; both passed. Both
`git diff --check` commands passed. Lint XML contained only the pre-existing `NewerVersionAvailable` warning; Room/KSP
emitted the pre-existing catalogue join-table index warnings. The shared fake classes were present in JVM and
Android-test class outputs and absent from the production debug APK dex files. This test-only slice did not require or
claim a new device run; the last unfiltered instrumentation gate remains M3's 161 passing tests on API 37.

## Stop boundary

Assignment 017 is complete. Pull, snapshots, original-result deduplication/replay and retention
remain [Assignment 018](018-task.md), which was **not started**. The fake is not yet a complete sync-engine oracle, and
the production app still has no running sync or working sign-in. No backend or OAuth project behavior was implemented or
verified here.
