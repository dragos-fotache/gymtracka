# GymTracka Android testing strategy

This document tracks Android test conventions, implemented coverage, and remaining test expansion.

## Principles

- Protect multi-row domain behavior with real Room DAO/repository integration tests, not mocked DAOs.
- Test state holders with narrow hand-written repository fakes, injected coroutine scopes, and virtual time. Do not use
  sleeps.
- Test pure formatting/filtering/naming logic on the JVM.
- Test observable behavior and invariants instead of private methods or incidental Compose structure.
- Keep the production construction path intact while adding injection seams for repositories, clocks, UUIDs, and Android
  hardware only when they improve determinism.
- Every Room version bump must add schema snapshots and migration tests from every supported version, including the
  bundled v1 catalogue asset.

## Sync test strategy — approved pivot

Prioritize real-backend integration tests against an isolated local service and database instead of completing the
backend-sized fake. Assignment 018 is paused, not a remaining mandatory test milestone. The simulator was retired on
2026-10-02 after coverage transfer; do not recreate its domain/persistence handlers in client tests.

- Adapt existing command scenarios into real API/database tests for ownership, transactions, domain rules and
  original-result replay. Validate expected results against the shared backend contract; the Kotlin fake and its
  assertions are not the specification and may need adapter/harness changes.
- Verify retention, cursor expiry, stable snapshots and two-client convergence against the real service. Share contract
  scenarios where practical without requiring a second full server implementation.
- Use small scripted API/transport doubles for Android retries, cancellation, delayed/lost responses and malformed/error
  responses. Add behavior only when a concrete client test needs it.
- Keep real Room persistence/migration tests and deterministic state-holder tests. Local-service results,
  deployed-service checks and real Google OAuth verification are separate gates.
- The [coverage transfer ledger](fake-retirement-coverage.md) maps all 92 removed fake methods and the 10 retained
  boundary tests. Observable reservation/collision behavior remains tested; simulator-only map/copy mechanics and the
  unsupported finish-time-ordering rule were not carried forward. Finish before start is explicitly allowed by
  `client-compatibility.md` and is now a positive HTTP regression.
- The backend repository is an operational build/start input for this Android work, not an additional code-review or
  implementation scope. Exercise its HTTP API; do not automatically run its full Maven suite.

See the
[revised implementation plan](sync-implementation-plan.md#approved-strategy-change--real-backend-before-further-simulator-expansion).
Older counts and fake-service coverage below are historical milestone records, not the current suite.

The subsequent shared-contract finalization is OpenAPI 1.0.0, not an Android implementation milestone. Local
specification checks passed: OpenAPI 3.1 validation, all 345 internal references, 81 positive/negative JSON-schema cases
(including all 17 mutation kinds / 19 variants, export exclusions, deletion confirmation, paging and numeric bounds),
local Markdown link paths and both diff checks. These checks use the shared YAML/fixtures, not a real server or the
Android fake. Production Android adapters/models still need integration tests for purpose-bound Google proofs,
export/delete/status, account-fenced cleanup and reference-name refresh. No real backend, OAuth, app build or device run
is claimed by the contract check.

## Catalogue seed verification

The separate [fixed catalogue package](../shared-docs/catalogue/README.md) now includes 11 standard-library Python test methods
for seed integrity, relationship validation, duplicate IDs/keys, null/instruction fidelity, and read-only SQLite
comparison (including mismatch/missing-file rejection). These are not Android JVM tests and do not change the historical
counts below.

Verified `gymtracka-catalogue-v1` against the unchanged bundled v1 asset: every mapped value in all five tables matches
(899 exercises, 17 muscles, 36 equipment items, 2,638 muscle joins and 899 equipment joins), with SQLite
integrity/foreign-key checks and the recorded asset SHA-256. `./gradlew :app:assembleDebug --console=plain` passed,
including the Kotlin compilation task. The resulting APK contains byte-identical MIT/foundation notices, catalogue
attribution and the unchanged database. No Room migration, app-behavior change, backend importer, OAuth verification or
device execution was part of this seed task.

Repeat the offline checks from the Android root:

```sh
python3 shared-docs/catalogue/verify.py --android-db app/src/main/assets/databases/gym.db
python3 -B -m unittest discover -s shared-docs/catalogue -p 'test_*.py'
```

## Current baseline — simulator retired (2026-10-02)

This records the retirement baseline. The newer R02a gate below raises the offline JVM total to 200 and adds six
filtered Android smoke tests; it is not a new full instrumentation or real-backend pass.

- **183 offline JVM tests**, including 29 HTTP-adapter tests, 23 pure fixture-configuration tests, and the 10 retained
  sync-boundary tests. They need no backend or external configuration. A local loopback HTTP stub tests cancellation
  only; it implements no server domain logic.
- **46 separately runnable real-backend JVM tests** exercise all 17 mutation kinds (including all three import types),
  complete canonical records, rejected no-effect state, exact-byte replay, account isolation, pull, and frozen snapshots.
  These send production durable wire requests and decode real responses with the production codecs.
- **11 Python stdlib launcher tests** cover configuration ownership/refusal and cleanup safeguards without a backend.
- Removed: `FakeSyncService`, `FakeAccountSyncApi`, `FakeSyncFixtures`, 12 fake-only test classes (92 methods), and the
  unused `sharedTest` wiring. Hand-written repository/auth/clock doubles, transport/model regressions, and all
  Room/state/UI tests remain.
- Android instrumentation coverage is unchanged: 161 methods, last full device gate at M3 on API 37. Android-test
  source compilation is checked for this slice; no new device or hosted-CI pass is claimed.

The real-backend task requires an explicit `-Pgymtracka.realBackendConfig=...` or
`GYMTRACKA_REAL_BACKEND_CONFIG`; missing configuration fails, never skips. It does not run the offline Test task as a
dependency. It always executes against external state, with build caching disabled and configuration-cache reuse tested.
The fixture validates owner-only configuration, loopback/disposable database identity and run guard before writing
isolated account/session credentials. Domain writes/reads go through HTTP. External Google authentication is not tested.
The app still has no production HTTP adapter, running sync engine, account UI, or snapshot publication.

From the Android repository root, start the service in a separate foreground terminal:

```sh
python3 scripts/run-isolated-backend.py --backend-root /home/dragos/IdeaProjects/gt-backend
```

The launcher builds the external JAR (use `--no-build` only for an already-built JAR), starts disposable PostgreSQL 16,
and publishes `/tmp/gymtracka-real-backend/config.json` with mode 600. It never loads the personal `dev.env` or uses
personal databases. Stop it with Ctrl+C; only its own process/container/configuration are cleaned up. Existing config
paths and occupied ports are refused, not overwritten/killed; choose another `--config-path` and `--port` for a second run.

Then run:

```sh
./gradlew :app:testRealBackend -Pgymtracka.realBackendConfig=/tmp/gymtracka-real-backend/config.json --console=plain
./gradlew :app:testDebugUnitTest --console=plain
python3 -B -m unittest discover -s scripts/tests -p 'test_*.py'
```

Separate reports: `app/build/test-results/testRealBackend/` and `app/build/reports/tests/testRealBackend/` versus
`testDebugUnitTest`. Inspect actual testcases and failures/errors/skips; repeated real-task execution must not be
up-to-date or restored from the build cache. See the [retirement review](fake-retirement-review.md) for actual final gates
and limitations. Historical backend XML observed before the scope clarification is supporting prior evidence only;
an interrupted full Maven command is not a backend-suite pass.

## R02a — shared raw JSON guard (2026-10-03)

The [R02a coordinator review](remaining-work/R02a-review.md) accepts the focused raw duplicate-member guard:

- **200 offline JVM tests in 26 XML suites**, all executed by the coordinator with `--rerun-tasks` and
  `--no-configuration-cache`; zero failures/errors/skips. This includes 17 independently authored
  `StrictDuplicateKeyTest` methods covering all raw routes, exact/escaped/deep duplicates, per-object scopes,
  opaque strings, safe failures, durable outer/inner corruption, exact frozen wire bytes and existing syntax/model rules.
- **6 filtered `StrictDuplicateKeyAndroidTest` tests** passed on Pixel_6a, Android 17/API 37 (`emulator-5554`), with
  zero failures/errors/skips. This proves the Android raw-parser smoke, not the full 167-method instrumentation layer.
  The last full existing device gate remains M3's 161 tests.
- Production Kotlin compilation, Android-test Kotlin compilation, debug assembly and lint passed. Lint reports only
  the existing `NewerVersionAvailable` warning; the existing catalogue FK-index and test-helper compiler warnings remain.
- A coordinator-only deterministic differential probe checked 2,000 valid JSON bodies against Python's independent
  decoded-name oracle: 773 unique bodies accepted, 1,227 duplicate bodies rejected, zero mismatches. This supplementary
  temporary probe is not an additional checked-in suite or backend pass.

QA authored expectations without production access. Coordinator corrected fixture/escape/syntax issues after a bounded
QA editing blocker, then returned execution to the same QA session; Worker did not inspect tests. Duplicate-rejection
helpers compare fixed failure text to a known raw duplicate, avoiding incidental model-validation false positives. Raw untyped
numeric tokens already normalized to strings at baseline; rejection tests use represented model numeric fields rather
than expanding this slice into a new raw grammar. See the [shared specification](remaining-work/R02a-task.md).

No real backend, Google OAuth, phone/laptop journey, hosted CI, Room migration or full device regression was executed
for R02a. The 46 real-backend and 11 Python launcher tests remain earlier evidence, not fresh passes.

## Historical baseline before simulator retirement

Testing milestones 1–8 are complete. After Assignment 016, the suite contained 173 JVM test methods and 161
instrumentation test methods: 36 Room/repository/migration tests, 9 sync-foundation tests, 17 sync-capture tests, 19
scoped-repository tests, 14 routine-capture tests, 19 workout-capture tests, 14 start-command tests, 14
finish/delete-capture tests, 14 focused Compose interactions, and 5 actual-app-flow tests. Assignment 015 added 21 JVM
tests for the M4a boundary (10 sync-API boundary, 7 fake-service scaffold, 4 service-auth boundary); no instrumentation
tests were added. The sync M1 unfiltered instrumentation gate passed all then-existing 63 tests on 2026-09-27 on
emulator-5554 (Pixel_6a (AVD), Android 17/API 37); the newer capture, scoped-repository, routine-capture,
workout-capture, start-command and finish/delete-capture suites passed all 17, 19, 14, 19, 14 and 14 tests respectively
in filtered runs, and all 8 existing routine persistence tests passed after routine integration. These runs had zero
failures, errors, or skips. The full 161-test unfiltered instrumentation gate passed on 2026-09-27 on emulator-5554 with
zero failures, errors, or skips. Assignment 016 added 35 JVM tests for seven basic fake command kinds (12 workout, 10
routine/custom and 13 import/delete); all 173 JVM tests passed with zero failures, errors or skips, including an
independent coordinator rerun. Kotlin compilation, debug assembly, lint and Android-test source compilation passed, both
diffs were clean, and fake classes were excluded from the production APK. No device execution was added in this slice.
Assignment 017 added 50 JVM tests for the ten semantic fake commands; that historical full JVM suite passed 223 tests in 35
XML reports with zero failures, errors, or skips. Compilation, assembly, lint, Android-test source compilation, both
diff checks, and production-APK fake exclusion passed. Fake deduplication and the read protocol are absent; their
planned expansion is paused in favor of real-backend integration coverage. Hosted GitHub execution remains unverified;
see [CI status](#ci-status-and-final-local-gate).

### Implemented coverage

1. **Foundation**
    - Coroutine and Room testing dependencies.
    - Room schema export in `app/schemas/`.
    - Injectable database/repository construction and in-memory Room harness.
    - Reusable persistence and state fixtures.
    - Formatter/converter tests replacing generated placeholders.

2. **Workout persistence**
    - Finish validation, cleanup, renumbering, status update, and transaction rollback.
    - Workout/set/exercise cascades and delete-plus-renumber behavior.
    - Add-from-history ordering, defaults, rest-duration copying, and atomic rollback.
    - Exercise replacement preserving the workout entry and mutable data.
    - Finished-history filtering and deterministic aggregation.
    - Perform-again, routine-start, and save-as-routine cloning, rejection, and rollback.

3. **Routine and custom-exercise persistence**
    - Draft promotion, autosaved editing, add/delete/renumber behavior, cloning, and cascades.
    - Bundled-exercise mutation guards.
    - Custom exercise relationships and transaction atomicity.

4. **Migrations and sync foundations**
    - Retained schema JSON for versions 1–9.
    - Migration from each supported version 1–8 to version 9, plus a copied bundled v1 catalogue asset, asserting inert
      root defaults, empty sync tables, preserved domain values, and an intact catalogue (including a seeded v8 custom
      exercise with relationships).
    - New `SyncFoundationTest` (9 methods, real in-memory Room): constructor/default inertness, mutation-ID uniqueness,
      per-account isolation, explicit-sequence ordering with IDs opposing lexical order, generation-guarded pending
      replacement (stale/same rejected) plus frozen-payload protection, outbox survival of root deletion, independent
      support-table round-trips, singleton-ID guards, and cached-account retention on active-account clear (storage
      coverage only). These cover inert storage foundations only; no sync behavior is claimed.
    - `data/sync` workout slice (JVM): transport-independent `WorkoutInput`/`WorkoutRecord` models matching the OpenAPI
      schemas, strict org.json codec (exact field names, explicit nulls, no coercion, derived volume ignored; raw bodies
      pre-validated with strict kotlinx-serialization JSON, failures never echo the body), RFC 3339 UTC helpers
      (desugared java.time, millisecond precision, no silent normalization), canonical lowercase UUID wire IDs, and
      explicit Room mappings with position/ID/parent validation. Covered by fixture, input-codec, mapping, validation,
      and strict-body JVM tests. Core library desugaring (`desugar_jdk_libs` 2.1.5) is enabled for java.time on minSdk
      24.
    - `data/sync` routine and custom-exercise slice (JVM): transport-independent `RoutineInput`/`RoutineRecord` models
      (sets never carry completion/rest; blank names and empty drafts/children are valid shapes, promotion rules stay
      server-side) and `CustomExerciseInput`/canonical custom records (nullable metadata/quantity/instructions preserved
      exactly; primary/secondary/equipment constraints; tertiary rejected; bundled rows never uploadable). Covered by
      routine-transport and custom-exercise-transport JVM tests.
    - `data/sync` protocol envelopes (JVM): typed `AggregateType`/`ChangeOperation`/`MutationStatus` discriminators with
      explicit wire values, canonical record union, single/multi-root batches, and push/pull/snapshot result codecs with
      opaque-cursor and outcome-shape rules. Covered by protocol-envelope JVM tests.
    - `data/sync` typed mutations and durable v1 (JVM): sealed mutation-kind/payload union with kind/target/ID matching,
      workout import snapshots with active/finished fidelity, shared millisecond range guard, strict request-boundary
      field checks, immutable durable wrappers with byte-for-byte push embedding and version dispatch, and the nine
      remaining semantic commands (clone/copy child-ID plans, history additions, targeted deletes) with stable
      identities. Covered by mutation-transport, durable-payload, and semantic-command JVM tests.
    - `data/sync/store` capture primitives (real in-memory Room): fenced transactions with in-transaction session
      checks, same-transaction metadata advance and tail-only coalescing, tombstone/delete rules, rollback of
      domain/metadata/outbox under forced failures, and read-only durable-row identity checks. Covered by sync-capture
      instrumentation tests (repository wiring is a later slice).
    - Scoped application reads and custom-exercise capture (real in-memory Room): per-scope visibility matrices for
      every read family, deterministic history selection, custom create/edit/delete capture with coalescing,
      metadata/quantity preservation, tombstone and reference rules, and rollback coverage. Covered by scoped-repository
      instrumentation tests.
    - Routine-local mutation capture (real in-memory Room): draft/save/rename/add/set/rest/delete capture with typed
      payloads, semantic child commands, stale-entity protection, sanitized inputs, tombstones, and rollback coverage.
      Covered by routine-capture instrumentation tests.
    - Simple active-workout mutation capture (real in-memory Room): name/notes/rest/set/rest/delete/replace capture with
      complete upserts and semantic child commands, explicit completion validated against sanitized inputs,
      incomplete-to-complete transition results for callbacks, timer-rest preservation, stale-entity protection, and
      rollback coverage. Covered by workout-capture instrumentation tests.
    - Atomic start/source-command capture (real in-memory Room): empty start, perform-again, start-from-routine, and
      save-as-routine with exact semantic payloads/bindings, sole-active replacement (signed-out hard-delete, account
      tombstone), source eligibility/visibility guards, and full-transaction rollback coverage. Covered by start-command
      instrumentation tests.
    - History additions, finish, and root deletion capture (real in-memory Room): scoped history selection with
      deterministic tie-breaks, exact workout.addExercises IDs, valid-completion finish with exercise/set renumbering
      and workout.finish ordering after frozen snapshots, fenced hasCompletedSets with stale-session rejection,
      signed-out hard-delete versus account tombstone plus aggregate.delete, and post-cleanup/post-capture rollback
      coverage. Covered by finish/delete-capture instrumentation tests.
    - M4a SDK-independent boundaries and shared scaffold (21 JVM tests): approved push-envelope validation, opaque call
      recording, cancellation, fixed safe failure text/no raw cause and bounded retry hints, credential display
      redaction, typed auth-call compatibility, explicit catalogue seeds, guarded type-scoped ownership and
      account-separated scaffold state. Both test source layers compile the shared helper; it is excluded from the
      production APK. That M4a gate did not verify auth orchestration, command semantics, deduplication or paging. Basic
      command semantics are covered by M4b below and the remaining semantic commands by M4c; auth orchestration and
      dedup/read-protocol behavior remain pending.
    - M4b basic fake commands (35 new JVM tests): independently expected complete records/batches for workout
      start/upsert, routine upsert/save, custom upsert and aggregate import/delete; source eligibility and immutable
      history, account/type isolation, current reference names, ordered children/nulls/metadata, draft/saved
      autosave/import differences, custom references blocking deletion, create-only/tombstone/child collisions,
      persistent removed-child ownership, active-replacement ordering and rejected-import rollback, later server commits
      winning with reversed client clocks/known revisions, and real mutations of request/outcome/log collections leaving
      stored state unchanged. At the M4b gate, the ten later semantic kinds rejected explicitly; pull/snapshot still
      throw. Existing push boundary tests now assert execution/empty results and unsupported outcomes. No engine,
      original-result deduplication, auth orchestration or read-protocol behavior is approved by this gate.

    - M4c semantic fake commands (50 new JVM tests): independent complete records and ordered batches for finish,
      perform-again, start-from-routine, save-as-routine, history additions, reference replacement, and workout/routine
      child deletions. Tests cover current owned source eligibility, finish cleanup, exact clone/copy bindings and
      source immutability, old-active DELETE then new UPSERT, deterministic finished-history selection and changed-shape
      rejection, replacement field preservation, first/middle/last deletion and renumbering, saved routines remaining
      saved when emptied, retained child IDs, and rejected-command rollback. All 17 typed push kinds now have fake
      handlers. The forced full JVM gate passed 223 tests in 35 XML reports with zero failures/errors/skips; Kotlin
      compilation, debug assembly, lint, Android-test source compilation and both diff checks passed. The fake is absent
      from the production APK. Pull/snapshot and original-result deduplication remain pending; no new device execution
      or real backend behavior is claimed.

5. **Pure logic and feature states**
    - Locale-aware kilogram formatting/parsing and workout/rest formatting.
    - Default workout naming across hour boundaries and time zones.
    - Exercise search/filter matching and displayed muscle order.
    - Validation, ordered autosave, persistence failures/cancellation, and stale/delayed-load protection for active
      workout, routines, history, and exercise editing.

6. **App state and rest timer**
    - A narrow app-shell repository interface with a hand-written fake; injected wall and monotonic clocks; and small
      vibration/wake-lock hardware interfaces.
    - Initial active-workout discovery without automatic navigation, navigation transitions, and atomic
      empty/clone/routine starts (single repository call, no separate discard): failed starts keep prior
      active/navigation/dialog/timer, and invalid sources fall back to an empty start.
    - Finish/discard success and failure, no-completed-set finish rejection, and finish request/confirmation draining of
      registered feature edits (failed drains block finish; late edits drain again before commit; disposal detaches only
      its own registration).
    - Virtual-time timer start, adjustment to zero, off duration, replacement, skip, expiry, finish, discard,
      composition disposal, and scope cancellation.
    - Whole elapsed seconds are snapshotted when a timer ends and persisted at most once. Tests cover delayed
      replacement/expiry writes, stale callbacks, failed persistence, two/one/expiry vibration cues, bounded wake-lock
      timeouts, and ownership-safe release.
    - Claimed terminal writes queue serially in claim order while countdowns start immediately; finish awaits the whole
      queue; disposal cancels owned terminal jobs without touching the parent scope; unchecking ends only the matching
      timer with no rest write. Completion callbacks require a fenced incomplete-to-complete transition, latest-edit
      ownership, and a reloaded active parent showing the visible set completed.
    - Verification passed: `:app:compileDebugKotlin`, all 117 JVM tests, `:app:assembleDebug`, `:app:lintDebug`, the
      full 161-test `:app:connectedDebugAndroidTest` suite, and both diff checks.

7. **Focused Compose interactions**
    - Catalogue live search, staged equipment filter application, picker selection IDs/order/initial sets, cancellation
      without writes, and exercise replacement preserving entry IDs, notes, configured rest, and completed set data.
    - Custom-exercise required-name validation and discard confirmation/cancel flows.
    - Routine-name validation without draft promotion, disabled save for an empty routine, add-exercise callback, copied
      set inputs and renumbering after deletion, and routine-delete confirmation/persistence. Routine name edits retain
      the existing autosave behavior.
    - Active-workout compact weight/repetition persistence, exact set/rest-start callback payload, swipe deletion
      retaining the exercise, and exercise-delete cancellation/persistence/cascades.
    - Set-completion invalidation shows a disabled check with no phantom timer, and unchecking reports cancellation for
      exactly that set.
    - History card navigation, save-as-routine copied inputs/new IDs, perform-again callback, delete
      cancellation/persistence/cascades, and exact detail back/perform-again callbacks.
    - Active-workout pill and timer countdown labels, exact resume/discard/skip callback counts, and adjustment
      payloads.
    - Stable test tags and content descriptions identify the interacted controls. Compact `BasicTextField` controls
      remain intact. Tests use in-memory Room for persisted actions; routine/history screen states are preloaded with
      managed Main scopes before composition. Bounded waits replace busy preload loops, and all state-holder scopes are
      cancelled before Room closes.
    - Espresso was upgraded to 3.7.0 because the prior 3.5.1 runner crashed on the available Android 17 emulator while
      reflecting `InputManager.getInstance`.
    - Verification passed: `:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin`, all 39 JVM tests, all 13
      focused Compose tests, the full 49-test `:app:connectedDebugAndroidTest` suite (including Room and migrations),
      `:app:assembleDebug`, and `git diff --check`.
    - Coordinator review and final unfiltered verification completed on 2026-09-26 on Android 17/API 37, with no test
      failures, errors, or skips. Production changes for this milestone are selectors/content descriptions and the
      Espresso compatibility update.

8. **End-to-end app flows and CI**
    - Added an optional internal composition-local repository override with a remembered production fallback. The
      app-flow tests mount the real `GymApp` against one in-memory Room database per test, seed the base catalogue plus
      each journey's specified initial aggregates, and use normal remembered feature factories without preloading
      feature state.
    - One startup smoke test checks the normal Workout, Exercises, and History routes, then starts an empty workout,
      collapses/resumes it, and creates a routine draft. Four UI-driven journeys cover custom-exercise
      create/edit/add-to-workout; workout start/edit/finish/history detail; routine creation/start; and performing a
      finished workout again with safe active-workout replacement and discard.
    - Assertions check visible route outcomes and persisted IDs, ordering, values, completion state, and
      source-aggregate preservation. Disposing test composition cancels feature scopes; the harness joins the root
      composition-scope `Job` before the per-test Room database closes.
    - A preload/loading question from the earlier focused interaction harness did not reproduce in the actual-app
      harness. Normal construction was verified on real app routes without preload; no product-behavior fix was made.
      The 13 focused Compose tests retain managed preload only as interaction-test setup.
    - The GitHub Actions workflow has independent Ubuntu 24.04 build/JVM/assembly and instrumentation jobs, each with a
      30-minute timeout. Build runs on push, pull request, and manual dispatch; unfiltered instrumentation runs on pull
      requests and manual dispatch using a headless API 35 Google APIs x86_64 emulator with animations disabled and KVM
      enabled. It uses JDK 25, the checked-in Gradle wrapper 9.8.0, and explicitly installs `platforms;android-37.0`,
      `build-tools;37.0.0`, and `platform-tools` before Gradle. Its actions are pinned to reviewed commit SHAs,
      permissions are `contents: read`, and concurrency cancels earlier runs for the same workflow/ref. Always-run
      artifact steps upload available JVM/instrumentation reports and the debug APK from the build job.

## CI status and final local gate

The 2026-09-27 final local gate ran the following full, unfiltered command on emulator-5554 (Pixel_6a (AVD), Android
17/API 37):

```text
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:connectedDebugAndroidTest --console=plain
```

The result was 40 JVM testcases and 54 instrumentation testcases (36 Room/repository/migration, 13 focused Compose, 5
`AppFlowTest`), with zero failures, errors, or skips. Lint and debug assembly also passed. `git diff --check` passed in
both the GymTracka repository and its nested `shared-docs` repository. The debug APK was produced at
`app/build/outputs/apk/debug/app-debug.apk`; JVM XML/HTML reports are under `app/build/test-results/testDebugUnitTest/`
and `app/build/reports/tests/testDebugUnitTest/`; connected XML/HTML reports are under
`app/build/outputs/androidTest-results/connected/debug/` and `app/build/reports/androidTests/connected/debug/`.

The workflow configuration, event conditions, action pins, emulator inputs, shell syntax, and artifact paths were
reviewed. The hosted GitHub run and its API 35 runtime remain unverified until the workflow is pushed and executed. The
local gate used API 37. No hosted CI pass is claimed.

Workflow: [`.github/workflows/android-tests.yml`](../.github/workflows/android-tests.yml). App-flow tests: [
`AppFlowTest.kt`](../app/src/androidTest/java/com/dragosfotache/gymtracka/ui/AppFlowTest.kt).

The suite protects four critical user journeys against the actual app on an emulator; their visible outcomes and
persisted aggregate invariants are covered by the milestone 8 app-flow tests above.

Run JVM tests on every change. Run Room/migration/Compose tests on pull requests or the equivalent integration gate.
Keep Kotlin compilation as the minimum implementation check and assemble the debug APK before milestone handoff.

## Commands

From the `GymTracka` repository root:

```text
./gradlew :app:compileDebugKotlin --console=plain
./gradlew :app:testDebugUnitTest --console=plain
./gradlew :app:lintDebug --console=plain
./gradlew :app:connectedDebugAndroidTest --console=plain
./gradlew :app:assembleDebug
git diff --check
```

For the final milestone gate, run the exact combined command recorded
in [CI status and final local gate](#ci-status-and-final-local-gate), then check diffs in both repositories.

Instrumentation tests require an available emulator or device. Use narrow test filters while iterating, but run the
appropriate full layer before marking a milestone complete.

## Verification lessons

- Filter instrumentation runs by one fully qualified `Class#method` or a whole class. Comma-separated method filtering
  ran only one intended method in the local environment; always verify the executed test names and counts. For example:

  ```text
  ./gradlew :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.ui.AppFlowTest#actualGymAppStartsAndLoadsFeaturesWithoutPreload' --console=plain
  ```

- Inspect all nested XML `testcase` elements, rather than only direct children of the report root. Check names, counts,
  failures, errors, and skips. A passing filtered run does not establish that the full suite passed; use unfiltered
  reports for the final gate.
- Use the Compose testing v2 `createComposeRule` API so composition coroutines follow standard `runTest` scheduling. The
  deprecated v1 rule can mask ordering assumptions with eager coroutine execution.
- Use one `setContent` per Compose test and bounded `waitUntil` checks on actual UI or repository outcomes. Avoid
  sleeps, busy-yield loops, frame churn, and relying on `waitForIdle` alone after Room writes. Assert callback results
  with `runOnIdle`.
- Dispose composition and cancel/join owned state-holder scopes before closing the test Room database. Focused tests may
  preload state to isolate interactions, but normal app initialization must be verified through actual app routes
  without preload.
- For ambiguous Compose loading failures, inspect the unmerged semantics tree with `printToLog` and the failing test's
  logcat before changing setup or production behavior.

## Sync test expansion

The [remaining-work roadmap](remaining-work-roadmap.md) assigns per-task acceptance outcomes and separate engine
convergence, live/device and future-public audit gates. R02a is accepted; all other implementation slices remain pending.
[R01 planning](remaining-work/R01-task.md) and its [invariant/test matrix](remaining-work/R01-design.md) were approved by
the owner on 2026-10-03. These planning updates add no test
pass or implemented capability. The current target is the owner's phone and personal laptop-hosted service: core
transaction/retry/recovery/security tests plus real personal-device verification remain essential, while broad public
compatibility/hosted-CI/lifecycle gates may follow later. Personal live verification must not use real user data as an
automated failure-injection fixture. Add tests within each slice rather than waiting for the final gates.

The current owner-directed contributor split keeps Worker production-only and QA test-only. Both receive the same
normative specification and coordinator-supplied API signatures. Worker cannot inspect tests/fixtures/reports; QA cannot
inspect production declarations/implementation/diffs, including through LSP. Both use Kotlin mcpls only within their
allowed paths and report asynchronously through Herdr. Coordinator reviews both layers, supplies missing interface facts,
and schedules Gradle runs serially in the shared tree. The first [R02a gate](remaining-work/R02a-task.md)
is accepted with independent raw duplicate-key/escape/exact-wire tests and an executed Android parser smoke; see its
[review](remaining-work/R02a-review.md).

For the remaining sync engine/account/adapter slices, combine real Room migration/persistence tests, narrow scripted
transport doubles, and the isolated real-backend HTTP task. Do not recreate a server simulator. Expand coverage for:

- every local aggregate mutation creates or coalesces outbox work in the same Room transaction;
- forced failures roll back both the domain edit and outbox change;
- a committed server mutation with a lost response is safely retried with the same mutation ID;
- a failed pull does not advance the cursor and replaying a page is safe;
- aggregate last-write-wins follows server commit order even when device clocks disagree;
- delete propagation, 90-day cursor expiry/full resync, pagination, process death, legacy-data import choice, and
  account switching cannot leak or lose account data.
