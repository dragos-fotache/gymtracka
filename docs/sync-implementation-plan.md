# Android sync implementation plan

Status: M1–M3 and M4a boundaries are approved; Assignments 016–017 remain historical approved simulator slices.
The simulator was retired on 2026-10-02 and replaced by separately runnable real-backend HTTP tests plus small scripted
transport doubles. Assignment 018 remains paused and is not the next task. M5–M12 remain pending; the app is not syncing.

## Remaining-work task index

The user requested a task breakdown with detailed planning and further splitting on pickup. The
[remaining Android delivery roadmap](remaining-work-roadmap.md) defines proposed R01–R18 work packages, dependencies,
acceptance outcomes and coordinator boundaries. It is the current execution index for remaining work; this document
retains the historical milestones, invariant definitions and review evidence. These Android-only documents and
future task/review records belong in this repository's `docs/`; only cross-project product/contracts/catalogue belong
in `shared-docs/`. No implementation is authorized by the roadmap. [R01 planning](remaining-work/R01-task.md) and its
[integration design](remaining-work/R01-design.md) were approved by the owner on 2026-10-03. R02a–c are accepted:
[duplicate-key guard](remaining-work/R02a-review.md), [family identities](remaining-work/R02b-review.md) and
[typed failures](remaining-work/R02c-review.md). The owner authorized committing the full Android working tree,
including its retirement/HTTP baseline; see the [delivery record](remaining-work/R02bc-commit-review.md). The next
proposed R02d slice requires separate authorization. Current coordination is Pi Worker
(production only) and QA (tests only), both Muse Spark 1.3 Contributor/high reasoning, with asynchronous Herdr reports
and independent coordinator review. This supersedes the archived Hermes contributor workflow below.

The owner's current target is single-user personal use with the service on a personal always-on laptop. Prioritize
safe usable foreground sync, then unattended personal recovery. Public production readiness, export/deletion and broad
release gates remain planned but deferred; the roadmap separates personal R17a/R17b acceptance from future public
readiness. Historical public-release requirements below remain reference obligations, not today's personal-use blockers.
Keep transactional safety, stable retries, account/session isolation and credential/network security in scope.

## Simulator-retirement update — 2026-10-02 (historical gate)

The following counts and operational/pane descriptions record that gate only. Current offline coverage is 283 tests;
see the current roadmap/handoff and [delivery record](remaining-work/R02bc-commit-review.md) for later verification.

The user completed the separate Java backend and authorized simulator retirement as the first resumed Android task.
`FakeSyncService`, `FakeAccountSyncApi`, their fixtures, all 92 fake-only test methods, and unused `sharedTest` wiring
are removed. The current suite has 183 offline JVM tests, 46 real-backend JVM tests, and 11 Python launcher tests;
Room/state/UI coverage is retained. See the [coverage ledger](fake-retirement-coverage.md),
[testing strategy](testing-strategy.md#current-baseline--simulator-retired-2026-10-02), and
[retirement review](fake-retirement-review.md).

The JVM-only HTTP adapter reuses production wire codecs; it does not implement the production Android HTTP adapter,
sync engine, account UI, snapshot publication, or background scheduling. The backend runs in an isolated disposable
PostgreSQL 16 environment, without loading private personal configuration or mutating personal databases. Backend
repository access for this task is build/start only, not further source review, Maven tests, or implementation.
Future client slices should use this black-box service and scripted failures, not resurrect Assignment 018.

## Approved strategy change — real backend before further simulator expansion

The user approved temporarily retaining the fake during coverage transfer, then retiring it. That retirement is now
complete. Do not resume backend-sized simulator expansion; permanent maintenance of duplicate domain logic is not a goal.
Use the completed real backend in an isolated local instance and add only small scripted responses/failures required by
concrete Android tests. Completing M4d is no longer a prerequisite for Android engine work. This decision supersedes
the former fake-first sequencing and all archived 018 execution instructions below.

**Backend scope clarified by the user:** the implemented Java project is `/home/dragos/IdeaProjects/gt-backend`, with
its own independent `shared-docs/` checkout. The finalized v1 [backend contract](../shared-docs/docs/backend-contract/README.md) supplies
behavior and compatibility requirements. Treat the backend as an HTTP service: operational build/start is allowed, but
backend source review, backend-suite execution, and backend changes are not part of this Android assignment. Report
compatibility questions rather than broadening that scope.

Preserve production Android work and its regression coverage. The large fake has been removed under the gate below. Adapt the existing fake scenarios into real HTTP/database integration tests, checking expected behavior against
the shared contract rather than treating the fake as authoritative. Reuse scenarios where practical, not necessarily
their Kotlin harness. Target real-server coverage for ownership, atomic commands, deduplication, retention, cursors,
snapshots and two-client convergence. Use narrow scripted doubles for client cancellation, delayed/lost responses and
error paths; do not recreate server persistence/domain logic merely for those tests.

M5–M12 remain required capabilities, not a mandatory numerical execution order. Replan their bounded slices around
backend availability, bringing forward enough M9 transport work for real-service integration. Keep real Room tests for
client persistence and deterministic tests for client state machines. Initial snapshot publication still precedes
first-sign-in activation. No backend, OAuth, new test pass, or completed sync behavior is claimed by this strategy
change.

### Fake retirement gate

Completed on 2026-10-02; retained here as the acceptance checklist for the historical transition. The
[coverage ledger](fake-retirement-coverage.md) distinguishes current Android black-box evidence, prior backend XML
observations, and simulator-only cases without replacement. No interrupted Maven command is treated as a full pass.

1. Inventory tests and helpers that depend on `FakeSyncService` / `FakeAccountSyncApi`. Map useful domain/protocol
   scenarios to real-backend integration tests, validating expectations against the shared contract. Record coverage
   destinations or explicit reasons a fake-only case needs no replacement.
2. Replace Android client-test dependencies with small scripted doubles where needed. Retain production API/codec, Room,
   state-holder and other client regression coverage; do not delete it merely because it shares fixtures with fake
   tests.
3. Once transferred coverage passes and client tests no longer depend on the simulator, delete the large fake, tests
   that only verify it, and unused simulator-specific fixtures/wiring. Do not preserve duplicate handlers solely to keep
   historical test counts unchanged.
4. Verify affected Android test layers still compile and pass, run the replacement backend integration tests, and update
   coverage/status documentation with actual results. Historical review records remain historical.

Retirement is implemented and verified by the current update above. Assignment 018 remains paused; its missing fake
features were not retirement prerequisites.

## Historical coordinator update — 2026-09-28

Assignment 017 completed all ten remaining semantic handlers in the Android test-only fake, so push now executes all 17
typed mutation kinds. The [Assignment 017 review](sync-assignments/017-review.md) records the command and rollback
coverage, corrections, and final gate. The coordinator independently forced the complete JVM suite: 223 tests in 35 XML
reports, zero failures/errors/skips. Kotlin compilation, debug assembly, lint, Android-test source compilation, both
diff checks, and test-only fake exclusion from the production APK passed. No production source, schema, bundled asset,
dependency, backend, HTTP, authentication, UI or worker code changed in this assignment. The last unfiltered device gate
remains M3's 161 passing tests on API 37.

Original-result deduplication, retention, pull and stable paginated snapshots are not implemented in the
fake. [Assignment 018](sync-assignments/018-task.md) and its [Muse plan](sync-assignments/018-muse-plan.md) are retained
as paused historical proposals, not the next assignment. The [coordinator handoff](coordinator-handoff.md) now follows
the real-backend-first strategy above. M5–M12 remain pending; the app has no running sync engine or working sign-in.
Preserve the existing Android and nested shared-docs working trees.

## Historical coordinator handoff — 2026-09-27

This section preserves the prior handoff for audit. Its Assignment 017 status, worker location and next-step
instructions were superseded by the current coordinator update above.

Start with the durable [new-coordinator handoff](coordinator-handoff.md), which records this session's implementation,
verification, contributor closure and next assignment. The original Assignment 016 specification/plan/review and
Assignment 017/018 instructions are preserved under [sync-assignments/](sync-assignments/017-task.md); temporary `/tmp`
artifacts are optional historical evidence.

**Stop boundary:** the user explicitly resumed Assignment 016 and requested delegation to the open Muse Spark
Contributor 1.3 session through Herdr, with a detailed coordinator plan and independent review. Assignment 016 is
complete and coordinator-approved. The user then requested a handoff for a new coordinator. Assignment 017 has not
started; obtain an explicit continuation request before starting the next implementation slice.

**Confirmed implementation and verification:** M0–M3 are approved; see the review ledger below for the individual gates.
Room is v9 with a data-preserving migration from every supported start and the original bundled v1 asset. M2
models/codecs cover all 17 typed mutation kinds; exact durable wire requests, account/session-fenced local capture and
selected-scope reads are implemented. Every repository mutation was audited; app completion/finish/timer alignment is
covered. The final M3 gate passed 117 JVM and the full unfiltered 161 device tests, zero failures/errors/skips, on
emulator-5554/API 37. M4a adds SDK-independent sync/auth/token interfaces, fixed safe no-cause failure types (retry-hint
cap 300,000 ms), strict push validation through the existing codec, and test-only shared scaffold sources. Its gate: all
138 JVM tests passed with zero failures/errors/skips; compileDebugKotlin, assembleDebug, lintDebug and
compileDebugAndroidTestKotlin passed, and both diff checks were clean. The coordinator independently checked current
Kotlin/Android-test compilation, executed XML and lint results, confirmed shared scaffold classes compile in both test
layers, and found no fake-service class descriptor in the production debug APK. These boundary/scaffold tests do not
verify actual auth orchestration or fake server semantics. Assignment 016 adds the seven basic fake command kinds,
staged per-mutation execution, increasing per-root revisions, current canonical reference names, permanent domain
rejections, type-scoped root ownership, retained tombstones/child reservations and atomic sole-active replacement. Its
35 new independent tests cover complete expected records/batches, nulls/metadata/order, source eligibility,
saved-routine autosave and import rules, ownership/collision rollback, reversed client clocks/known revisions, and
mutation of request/outcome/log collections. The final full JVM gate passed 173 tests with zero failures/errors/skips.
CompileDebugKotlin, assembleDebug, lintDebug and compileDebugAndroidTestKotlin passed; both diff checks were clean. The
coordinator reviewed all seven changed/new source/test files, forced the full JVM task to execute again, inspected
actual XML and lint results, confirmed fake classes compile in both test layers and are absent from the production debug
APK, and checked a baseline manifest to confirm no production/schema/asset/config/dependency changes in this assignment.

**Pending:** M4c/d (existing 017–018) must implement/review the ten remaining semantic commands, original-result
deduplication, retention and stable read protocol. Fake push now executes only the seven basic kinds; the other ten
return `unsupported_mutation_kind`. Pull and snapshot still throw explicitly. Recording outcomes is not deduplication:
repeating a mutation ID currently re-executes its command. The fake is not yet an engine oracle. M5–M12 remain pending:
canonical reconciliation/push/pull, two-client convergence, account lifecycle/import/replace, staged bootstrap/full
resync, HTTP, Google, WorkManager and final audit. Initial snapshot support must precede completion of first-sign-in
activation, as specified in this plan. The app does not sync and has no working sign-in yet. Actual backend/OAuth
verification remains unperformed because the user confirmed neither exists; backend implementation is a separate effort.
Eighteen numbered assignment files have been prepared through M4; they are slices of the feature milestones below, not
evidence that the entire original plan is complete.

**Working trees and workers:** Android `master` remains at 823d8efcfba061ea2814ac21bdada0941885e7ad; nested shared-docs
`master` at eb3f553e149c2bae356c4026af43819db0ced09d. All changes are uncommitted, including new/untracked schema 9,
sync source/test directories, sharedTest, fixtures and new handoff/protocol docs. No commits/pushes were performed or
authorized. Review both `git status --short` outputs; ordinary `git diff` omits untracked new files. Changes span
Room/DAOs/repository, narrow feature interfaces, active-workout app/UI/fakes/tests, desugaring/JSON dependencies,
source-set wiring and the referenced shared docs; preserve the existing working trees. The user supplied a fresh Hermes
session 20260927_203531_e60381 in w3:p7 and switched it to Muse Spark Contributor 1.3 (Meta); its model was verified
before Assignment 016 was delegated. After approval it was exited with native `/exit`; pane w3:p7 is now a shell in this
repository with no detected agent. No Assignment 017 contributor was launched and no sync implementation worker remains
assigned. Coordinator is w3:p1; unrelated user panes were left untouched.

**Decisions/constraints:** scope is Android client plus production HTTP/Google/background adapters; no backend project.
Signing out retains account data/outbox privately while signed-out UI uses separate local data; same-account resume and
different-account cache clear follow [sync.md](../shared-docs/docs/backend-contract/sync.md). Exact commands, import collisions, stable
child plans and retry lifetime follow [android-client-protocol.md](../shared-docs/docs/backend-contract/android-client-protocol.md);
authentication/storage/recovery follow [authentication.md](../shared-docs/docs/backend-contract/authentication.md). Never recreate the
approved 17 typed kinds. Keep the v1 asset, Room migration discipline and composition-scoped architecture
from [architecture.md](architecture.md) and AGENTS.md. Do not edit global configuration/personal skills, recreate the
discarded context wrapper, restart Herdr/Codex servers, or perform unrelated cleanup.

**Known limits and assumptions:** no real service or Google project, no hosted CI result, and no current API 24 runtime
result are claimed. Last unfiltered instrumentation execution is the M3 gate; M4a/b have Android-test source compilation
only; no new device run or instrumentation method was required/claimed in these test-boundary/fake slices. The
instrumentation runner selected only the first class in comma-separated filters: execute a single class per filtered run
and inspect XML. Existing join-table KSP warnings and one newer-JSON-version lint warning are nonfatal baseline
findings. Temporary coordinator artifacts are in `/tmp/gymtracka-sync-coordination/`; their availability to a fresh
coordinator assumes the same machine/workspace. The canonical-cache/snapshot/concurrency ideas in
`reconciliation-design.md` are proposals requiring design/migration review, not authorized or implemented features.
Previous contributor reports identified automatic Hermes curator skill changes outside the project; no global repair was
performed.

**Exact next subtask:** existing [Assignment 017, M4c fake semantic commands](sync-assignments/017-task.md) (read fully;
the original temporary copy is `/tmp/gymtracka-sync-coordination/017-task.md`). Implement only the ten remaining
handlers: workout.finish, workout.performAgain, routine.startWorkout, workout.saveAsRoutine, workout.addExercises,
workout.replaceExercise, workout.deleteExercise/deleteSet, and routine.deleteExercise/deleteSet. Use current owned
source state, complete stable child bindings, finish cleanup, history selection/shape checks, replacement identity
preservation, and atomic delete/renumber behavior. Preserve the approved seven basic handlers and stage all effects
before committing. Acceptance requires independent expected-record tests for every command and rejected cases that
preserve roots/revisions/logs. No Room/engine/HTTP/Google/UI/worker/schema/dependency work. Deduplication, retention,
paging and snapshots remain Assignment 018. Run narrow then full JVM, compilation, assembly/lint, Android-test source
compilation and both diff checks; inspect executed XML and STOP for coordinator review. Read the
full [backend contract](../shared-docs/docs/backend-contract/README.md) and linked documents; do not infer server behavior from the fake
itself.

**Fresh Muse workflow:** after every verified subtask, exit that Hermes session and start a new Muse Spark Contributor
1.3 instance in the contributor pane. Review corrections stay within its current subtask. Herdr caller must first verify
`HERDR_ENV=1`; use [the Herdr skill](/home/dragos/.agents/skills/herdr/SKILL.md). Coordinator pane is w3:p1; contributor
pane is w3:p7, same cwd, preserve user focus. Closure and shell readiness are confirmed; start a fresh instance when the
next assignment is authorized. Do not resume or compress the completed contributor conversation. The proposed next
launch, derived from installed CLI help/provider registry but not executed, is
`herdr agent start muse-017 --kind hermes --pane w3:p7 -- --model muse-spark-1.3-contributor --provider meta-ai --tui`;
verify readiness and the actual model before sending 017. Use native `/exit` after approval to end only the completed
contributor, not the pane/server.

**Relevant paths/checks:** current boundaries are `app/src/main/java/com/dragosfotache/gymtracka/data/sync/api/`; shared
fake basic-command implementation is `app/src/sharedTest/java/com/dragosfotache/gymtracka/testing/service/`; boundary
tests are `SyncApiBoundaryTest`, `ServiceAuthBoundaryTest`, `FakeSyncServiceTest` under
`app/src/test/java/com/dragosfotache/gymtracka/data/sync/`. Existing mappings/models/codecs/capture live under
`data/sync`; repository and v9 schema are under `data/gym` and `app/schemas/`. Coverage/status details are
in [testing-strategy.md](testing-strategy.md). Local prior-slice audits/baselines/assignment files reside in
`/tmp/gymtracka-sync-coordination/`, including `m3-review.md`, `015-baseline/` and `016-baseline/manifest.json`. Durable
session records are [016-task.md](sync-assignments/016-task.md), [016-muse-plan.md](sync-assignments/016-muse-plan.md)
and [016-review.md](sync-assignments/016-review.md); upcoming instructions
are [017-task.md](sync-assignments/017-task.md) and [018-task.md](sync-assignments/018-task.md). Assignment 016 command
tests are `FakeWorkoutCommandTest` (12), `FakeRoutineCustomCommandTest` (10) and `FakeImportDeleteCommandTest` (13),
with `FakeSyncFixtures` supplying deterministic request builders and ownership/tombstone-aware state snapshots.

Run from `/home/dragos/AndroidStudioProjects/GymTracka`:

```sh
git status --short
git -C shared-docs status --short
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --no-configuration-cache --console=plain
# Device gate when required; do not use a class filter for a full gate.
./gradlew :app:connectedDebugAndroidTest --no-configuration-cache --console=plain
git diff --check
git -C shared-docs diff --check
```

Inspect `app/build/test-results/testDebugUnitTest/TEST-*.xml` and
`app/build/outputs/androidTest-results/connected/debug/TEST-*.xml` for actual executed
names/counts/failures/errors/skips. Gradle commands must be unpiped so failed exits are not masked.

## Scope and authority

Deliver Android aggregate sync readiness, real-backend integration coverage, targeted deterministic client tests, live
HTTP transport, Google sign-in, and WorkManager scheduling. Simulator retirement and the test-only HTTP harness are
complete; production client capabilities remain pending. Maintaining or rebuilding a full server simulator is not a
deliverable.

Follow the backend README, domain-rules.md, schema.md, sync.md, openapi.yaml, and fixture in
`shared-docs/docs/backend-contract/` in full. Preserve `shared-docs/docs/product-behavior.md`, local
`docs/architecture.md`, and `docs/testing-strategy.md`. This plan sequences work; it does
not silently change the contract.

Historical planning baseline: Room version 8; bundled catalogue asset version 1; no account, network, or sync
implementation. Both working trees were clean when that original planning began. Current Room is version 9; see the
current update and architecture rather than treating this historical baseline as present state.

A real backend is now available and exercised through the isolated test harness. Production HTTP/Google adapters and
actual app integration checks remain required; local JVM backend interoperability does not close real Google OAuth,
deployed-service, Room convergence, or account-lifecycle verification. Confirm each future client assignment boundary;
backend implementation or audit is not an implicit part of it.

## Coordination

The coordinator owns design, assignment boundaries, integration, and final specification review. Hermes Muse Spark
Contributor 1.3 handles one bounded assignment at a time.

For each assignment:

1. Coordinator supplies permitted files, exact behavior, acceptance tests, and stop condition.
2. Contributor inspects current diff and applicable docs, then implements only that slice.
3. Contributor reports changed files, actual commands/results, uncertainties, and remaining risks.
4. Coordinator reads the entire diff, independently checks invariants and test evidence, and requests corrections when
   necessary.
5. Only explicit coordinator approval authorizes the next assignment. Contributor completion never grants permission to
   continue.
6. User instruction from 2026-09-27: after each subtask is finished and verified, end that Hermes Muse session and start
   a fresh Muse Spark Contributor 1.3 instance for the next subtask. Reuse the contributor pane without taking focus;
   carry the approved plan/current files and the next bounded instructions, not the entire previous conversation. Review
   corrections stay in the current session until its subtask is approved.

Start with a read-only audit. Split each milestone into small tasks; do not ask the contributor to implement the entire
sync system in one turn. Milestone numbers identify feature gates; the coordinator may explicitly sequence a
prerequisite first. In particular, stage/publish snapshot support from M8 before completing M7's first-sign-in
activation, because initial bootstrap cannot rely on the retained incremental log. No commits, pushes, asset edits,
unrelated framework additions, or unapproved contract rewrites. Preserve user edits.

## Invariants to audit at every gate

- Room remains the offline UI source. Sync domain aggregates, never the SQLite file.
- Workouts, routines, and custom exercises are roots; child edits change the root. Bundled data is excluded from user
  uploads.
- Client UUIDs, complete ordered children, null inputs, configured/actual rest, draft semantics, and derived volume
  remain correct.
- Domain edit, root generation, and required outbox capture commit in one Room transaction. Remote application must not
  echo into the outbox.
- An in-flight mutation's ID and payload stay unchanged across retries and process death. Only never-sent work may
  coalesce.
- Account/session scope fences every selection, response, acknowledgment, cursor, and staged cache publication.
- Later valid server commit wins for the complete aggregate. Device time and known revisions do not choose winners.
  Older canonical revisions cannot overwrite newer revisions.
- Canonical application preserves newer unsent local intent explicitly; ordered children are never invisibly
  field/position merged.
- Whole multi-root batches apply atomically. Opaque cursors advance only with successful contiguous change application.
- Deletes remain discoverable through durable tombstone/outbox evidence and stay absent from ordinary feature queries.
- Preserve finish validation/cleanup, immutable history, replacement identity, cloning, catalogue guards, and
  exactly-once timer rest recording.
- Starting the sole active workout produces old-delete/new-upsert in one server batch; preserve local
  confirmation/failure flows.
- Full resync stages every page, preserves pending intent, and publishes complete cache plus baseline cursor atomically.
  Missing incremental rows do not imply deletion.

## M0 — code inventory and design approval

Contributor: read-only audit. Coordinator: design decisions and contract clarification.

Deliver:

- Every mutating GymRepository method, its DAO call, roots, transaction boundary, rejection/no-op behavior, and proposed
  command versus coalescible upsert/delete.
- Direct production DAO writes outside the repository; child-to-root lookup needs; feature queries needing
  tombstone/account filtering.
- Existing active replacement, finish/timer ordering, custom-exercise deletion constraints, migration harness, and
  injection seams.
- Proposed schema/defaults for signed-out and signed-in roots, pending/in-flight/rejected outbox work, retry order,
  dependencies, account/session fencing, and snapshot staging.
- Contract gaps with code/document evidence. In particular: typed semantic-command payloads/results, stable cloned child
  IDs, token exchange/refresh endpoints, import ID collisions, and sign-out policy.

Gate: complete mutation matrix and explicit approved design. No production changes. Resolve necessary contract
ambiguities before coding dependent behavior.

## M1 — Room foundations and migration

Slices: root metadata; support entities/DAO; migration and coverage. Coordinate one coherent schema version bump.

Deliver:

- Root server revision, increasing local generation, account association, sync state, and deletion metadata. Bundled
  exercise defaults are inert.
- Durable ordered outbox with unique stable mutation ID, root/command type, local generation, versioned immutable
  payload, pending/in-flight/rejected state, and retry/error context. Do not cascade-delete publication evidence.
- Account/session sync state with opaque cursor and last-success timestamp; separate catalogue version; reviewed
  snapshot staging support.
- Data-preserving 8-to-next-version migration, production registration, and exported schema. Existing signed-out data
  retains IDs/values and is not silently assigned to an account.

Gate: KSP compile, real Room table/query tests, migrations from all supported versions plus copied v1 asset, safe
defaults, no destructive fallback, no asset edits.

## M2 — versioned transport models and mappings

Slices: workouts; routines/custom exercises; protocol envelopes/codec.

Deliver:

- Transport-independent aggregate inputs/canonical records, tombstones, mutations/outcomes, full batches, pull/snapshot
  pages, and typed failures.
- Stable root/child UUIDs, consecutive ordering, nullable inputs, rest distinctions, draft/custom relationships, and RFC
  3339 UTC timestamps.
- Explicit Room mappings and serialization suitable for frozen durable outbox requests. Support API 24.
- Semantic commands aligned with the reviewed wire contract.

Gate: fixture compatibility, round trips, null/order/timestamp tests, unsupported-version handling; Room entities are
not DTOs and volume is not authoritative stored state.

## M3 — atomically capture all local mutations

Slices: common capture helper; custom exercises; routines; simple workouts/rest; complex workout commands/deletion.

Deliver:

- Existing domain write, successful generation advance, and applicable outbox capture in one transaction.
- No phantom work for no-ops, missing roots, failed finish, or bundled mutation attempts. Child roots/accounts verified
  inside the transaction.
- Signed-out edits remain local and discoverable for later import.
- Coalesce never-sent work only. Edits behind in-flight work produce later pending intent.
- Stable semantic command requests for
  finish/start/clone/from-routine/save-as-routine/add-history/replace/delete-and-renumber.
- Creation-before-reference dependencies, tombstone hiding, durable deletion propagation, and explicit remote-write path
  without echo uploads.

Gate: actual Room tests for each mutation group; failure rolls back data and outbox; request identity/coalescing tests;
existing domain/timer/app-flow regression suites.

## M4 — GymSyncApi and deterministic fake service

Slices: service/auth boundaries; fake server; fake contract tests.

Assignments 015–017 delivered the first three slices. Assignment 015 added SDK-independent `GymSyncApi`/failure/limit
boundaries and the identity/token boundary (challenge/exchange/refresh/logout, opaque credentials with redacted display,
bearer provider) in `data/sync/api`, plus the test-only shared fake-service scaffold (catalogue, type-scoped ownership,
dedup/log/revision state, recording account adapters with explicit stubs). Assignment 016 added independently tested
execution of the seven basic kinds with staged commits, source/ownership/reference/identity validation, complete
canonical records and atomic active replacement. Assignment 017 added the ten semantic handlers with independently
expected canonical records/batches and rejected-command rollback tests. Assignment 018's deduplication, retention and
read-protocol expansion is paused.

Revised boundary:

- Preserve the approved SDK-independent interfaces and client regression coverage. The simulator and its obsolete
  tests are removed; use the real-backend task and small scripted transport doubles.
- The retired fake never implemented retry deduplication, pull or snapshots. Its historical approval does not prove
  those behaviors; current HTTP interoperability exercises real replay and read pages.
- Verify server commit order, original-result replay, ownership, atomic batches, retention and snapshots against the
  real backend and database.
- Add narrowly scripted doubles only for identified Android test needs. Review each addition rather than resuming 018
  wholesale.

Gate: M4a–c remain approved; M4d is paused, not completed. Real-server integration and targeted client tests replace the
proposed full-fake prerequisite for later milestones.

## M5 — push and canonical reconciliation

Slices: durable claiming/recovery; outcomes/retry; canonical application/newer intent.

Deliver:

- Serialized account sync, transactional claims, no network inside database transactions.
- Process-death recovery uses unchanged ID/payload. Acknowledgment targets the exact account/session/request/generation.
- Canonical batches plus acknowledgment commit atomically. Transient failure retains work with bounded backoff;
  permanent failure retains explicit recovery context.
- Preserve edits/deletes arriving while a request is outstanding.
- Push response cursors do not skip unseen incremental changes; advancement requires contiguous coverage.

Gate: server commit with lost response executes once; delayed response versus newer edit/delete; restart/cancellation;
permanent/transient failure; stale-account response; no echo uploads.

## M6 — pull, batches, and two-device convergence

Slices: remote Room application; pagination/cursors; two-client tests.

Deliver:

- Push-before-pull and pull after successful push; explicit handling of permanently rejected pending intent.
- Whole batches and cursor in one transaction, including dependency-safe custom references and active replacement.
- Revision protection, safe replay, pagination through hasMore=false.
- Independent client stores for two-device tests.

Gate: failed page retains cursor; replay safe; invalid reference rolls back complete batch; clock disagreement does not
affect winner; complete ordered aggregate convergence; deletion propagation; atomic active replacement. Use real Room
for persistence invariants.

## M7 — account lifecycle and first-sign-in choice

Slices: durable account/session state; import/replace; switching/token expiry; app integration.

Deliver:

- Replaceable identity/token boundary with no Google types in sync/domain code.
- User decision: signing out retains the cached account's data and pending outbox privately. Signed-out feature queries
  show only separate local data. Re-signing into the cached account resumes its work; switching to a different account
  follows the existing cache-clear contract. Device state distinguishes active account from cached account.
- Documented one-time import or replace decision. Import includes custom dependencies and preserves IDs using reviewed
  collision handling.
- Switching clears prior user roots/relationships, outbox, cursor, errors, and staging while preserving catalogue. Fence
  stale requests.
- Distinguish reauthentication from transport retries and permanent domain errors.
- Preserve signed-out startup and production repository construction.
- Drain accepted local autosaves and terminal timer persistence before replacing a presentation/session scope; fence
  stale later calls and old network responses. Cancelling the old composition must not silently lose edits already
  accepted by the UI.
- Canonical Room changes must refresh the selected feature state and app-wide active-workout coordination; a background
  worker cannot leave an obsolete active pill or stale details indefinitely. Update persisted values while preserving
  currently accepted autosave/draft text and transient focus. Remember-by-ID fields need deliberate reconciliation when
  the same root changes remotely.

Gate: both import choices, A/B isolation, delayed response after switch, interrupted transitions, token expiry,
catalogue preservation, signed-out regression.

## M8 — staged full resync and cursor expiry

Slices: staging; atomic publication/recovery; expiry tests.

Deliver:

- Explicit 90-day cursor-expired recovery; push/preserve pending intent first.
- Initial bootstrap when no account cursor exists uses a complete snapshot too; unchanged old server roots may be absent
  from the retained log.
- Complete paginated snapshot staged outside visible cache.
- Publish cache and baseline cursor only after all pages succeed.
- Recover/discard incomplete staging on failure, cancellation, process death, account switch, and concurrent edits.

Gate: failed middle/final page, pending/rejected work, restart, newer local intent, dependency ordering, and account
switch. Partial snapshots never appear complete.

## M9 — real HTTP integration

Slices: reviewed API/auth contract; HTTP client/configuration; mock HTTP tests; live smoke test.

Deliver:

- Configurable HTTPS service URL, INTERNET permission, reviewed compatible client/codec dependencies.
- Correct bearer credentials, typed requests/results, idempotency headers where required, timeout/cancellation,
  error-body parsing, and HTTP-to-domain failure mapping.
- Token exchange/refresh behavior agreed with real backend; no fabricated endpoint or client secret in APK.
- No token leakage in logs, outbox, test artifacts, or backup. Review backup/device-transfer rules for session/account
  state.
- Mock HTTP coverage for payloads, 401/403/410/422, retryable errors, malformed bodies, and lost response.

Gate: transport shares the tested sync engine and passes mock HTTP contract tests. Keep actual endpoint smoke tests
explicitly pending until the separately built service exists. A mock server pass alone cannot close live verification.

## M10 — Google sign-in and account UI

Slices: Credential Manager adapter; token exchange/session; account/import/recovery UI.

Deliver:

- Google sign-in behind the identity interface using current official Android APIs.
- Configured Android OAuth package/signing fingerprints and web/server client ID/audience.
- Google ID token exchanged/verified by backend; authenticated service account identity drives ownership.
- Sign-in cancellation/failure, one-time local-data choice, safe switching, expiry/reauthentication, and reviewed
  sign-out behavior.
- Product-consistent Compose controls and accessibility; retain composition-scoped architecture.

Gate: deterministic adapter/UI tests and signed-out fallback. Implement the production Credential Manager adapter with
documented OAuth configuration. Keep actual Google sign-in/service identity checks explicitly pending until OAuth and
the external backend are available.

Reference: https://developer.android.com/identity/sign-in/credential-manager-siwg
Implementation details: https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation

## M11 — WorkManager scheduling

Slices: worker adapter; triggers/configuration; race/recovery tests.

Deliver:

- Account/session-scoped unique work with network constraints and bounded retry/backoff.
- Foreground/manual/edit/sign-in triggers plus appropriate periodic recovery; avoid duplicate workers and lost wakeups
  while a run is active.
- Cancellation/account switch fencing; no interactive Google UI from a background worker.
- Worker uses the same production store, HTTP API, and tested sync engine. Preserve durable recovery when stopped.

Gate: scheduling/worker tests, offline-to-online delivery, app restart/background execution, token expiry, new edit
during work, and account switch.

Reference: https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work
Constraints: https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work

## Adapter dependency and storage review notes

Checked on 2026-09-27 for the later integration
gates: [Credential Manager's release table](https://developer.android.com/jetpack/androidx/releases/credentials) lists
stable 1.6.0; the implementation guide's sample 1.7.0-alpha03 is not the default
choice. [Google ID release notes](https://developers.google.com/identity/android-credential-manager/releases) list
1.2.1. [WorkManager](https://developer.android.com/jetpack/androidx/releases/work) lists stable 2.12.0 with minimum API
24, matching this app. The [OkHttp project](https://github.com/lysine-dev/okhttp), reached by the original Square
repository redirect, documents 5.5.0 and API 21+/Java 8+ support; its matching MockWebServer can verify HTTP. No
integration dependencies have been added yet; compile/test compatibility must still be verified at their gates.

Use platform Android Keystore with the
recommended [AES/GCM/NoPadding encryption](https://developer.android.com/privacy-and-security/cryptography), randomized
per-write IVs, and atomic encrypted session-file
writes. [Auto Backup](https://developer.android.com/identity/data/autobackup) excludes noBackupFilesDir automatically.
Credential vault storage belongs there; ordinary local data backup need not be disabled merely to exclude credentials.
Startup must still handle restored Room active identity with a missing vault by signing out and keeping account data
private.

## M12 — final specification audit

Coordinator-owned gate. Contributor corrects bounded findings but cannot self-approve.

Deliver:

- Requirement-to-code-and-test matrix against all shared sync/domain rules and this plan.
- Re-audit every repository mutation, all remote writes, all feature queries, deletions, retries, acknowledgments,
  cursor advancement, and account fencing.
- Update architecture, testing strategy, status docs, and relevant product/API docs to actual behavior.
- Review both diffs, schemas, secrets/configuration, retained asset, and existing feature behavior.
- Distinguish deterministic/mock coverage from actual live results.

Final checks from Android root:

    ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:connectedDebugAndroidTest --console=plain
    git diff --check
    git -C shared-docs diff --check

Inspect executed test names/counts, failures, errors, and skips. Use narrow tests while iterating, appropriate full
layers at gates, and the full final command. Instrumentation needs a device. Missing device/backend/OAuth configuration
leaves that gate unverified; it is never a pass.

## Review ledger

Entries record the state at each historical gate. The current handoff above takes precedence over older pending-status
statements.

M4c / Assignment 017 approved: the Android test-only fake now executes all 17 typed push mutation kinds. Ten semantic
handlers implement finish cleanup, clone/copy bindings and atomic active replacement, deterministic history additions
with changed-shape rejection, exercise-reference replacement, and workout/routine child deletion with current-root
lookup, renumbering and retained-ID collision evidence. Fifty new independent JVM tests cover complete expected
records/batches and no-effect rejections. The coordinator forced all 223 JVM tests in 35 XML reports with zero
failures/errors/skips; compilation, assembly, lint, Android-test source compilation and both diff checks passed. Fake
classes compile in both test layers and are absent from the production APK. No new device run, backend or production
implementation is claimed. Pull/snapshot, original-result deduplication and retention remain Assignment 018; the user
instructed stopping before it.

M4b / Assignment 016 approved: seven basic fake command handlers, namespaced reference seeds, staged domain
validation/commit, per-root revisions, complete canonical changes, account/type-scoped ownership, tombstones and
retained child reservations, sole-active replacement, saved-routine autosave/import rules, custom reference-delete
restrictions and defensive copies. Coordinator review corrected ignored workout scalars, child/reference identity
confusion, draft coercion/promotion bypass, all-type tombstone re-delete, collision reservations, save-time display
names, invalid two-active test setup, complete import expectations and real nested mutation/rollback assertions. All 173
JVM tests passed (35 new: 12 workout, 10 routine/custom, 13 import/delete; zero failures/errors/skips). Kotlin/KSP,
assembly, lint and Android-test source compilation passed; both diff checks were clean. The coordinator reviewed every
changed/new source/test file, independently executed the full JVM task, inspected XML/lint/APK contents and both
test-layer class outputs, and checked baseline hashes for preserved production/schema/asset/config/dependencies. No
device execution or production integration is claimed. Ten kinds still explicitly reject; pull/snapshot still throw, and
recorded outcomes are not dedup execution. The completed Muse session was closed; Assignment 017 remains unstarted.

M4a / Assignment 015 approved: SDK-independent sync/auth/token interfaces and safe typed failures, approved raw-request
validation, and the shared test-only scaffold. All 138 JVM tests passed (21 new: 10 API boundary, 7 scaffold and 4 auth
boundary; zero failures/errors/skips). Kotlin/KSP, assembly, lint and Android-test source compilation passed; both diff
checks were clean. The coordinator reviewed every new production/scaffold/test file, independently verified current
compilation and XML/lint results, checked both test-layer class outputs and production APK exclusion, and confirmed
approved capture/models/UI/schema/asset were preserved. All fake API operations remain explicit unsupported stubs; no
server semantics, auth orchestration, HTTP, Google, vault or engine behavior is approved by this gate. The completed
Muse session was closed and no Assignment 016 or fresh instance was started, per the user's handoff stop instruction.

M3 complete: Assignment 014 approved after full production/test review and corrections to completion eligibility and
finish/timer ordering. The coordinator verified the final executed XML: all 117 JVM tests and the FULL UNFILTERED 161
instrumentation tests passed with zero failures, errors or skips on emulator-5554 (Pixel_6a (AVD), Android 17/API 37),
including all supported migration starts and the copied v1 asset. Kotlin/KSP compilation, assembly, lint and both diff
checks pass; the coordinator independently checked the compilation/JVM tasks against the current sources and confirmed
the bundled asset and approved transport models were unchanged. Atomic replacement calls, real persisted completion
transitions, latest-edit callback fencing, repeated failure-aware feature drains, ordered terminal writes and finish
waiting after replacement/skip/expiry are covered. All original repository mutation entry points and selected-scope
reads were audited. This approves local capture and app alignment only: remote canonical application without upload
echo, account lifecycle, snapshot publication, live adapters and scheduling still require their original later gates.
Existing Assignment 015 is the next bounded slice of M4; no additional feature milestone was added.

M3 history/finish/root-deletion slice (existing Assignment 013): approved after full code/test review, eligibility
session-fence correction and bounded large-workout cleanup. Independent coordinator Kotlin compilation and all 14
focused Room tests passed; contributor verified all 106 JVM tests, the 4 existing addition/history and 7 finish/deletion
tests, assembly/lint and both diff checks. Finish keeps only valid completed survivors and renumbers both levels,
ordered semantic requests preserve frozen predecessors, and account deletes retain tombstones/publication evidence. All
repository mutations are now captured; app/input/timer alignment and the full unfiltered M3 regression gate remain open.

M3 atomic starts/source commands (existing Assignment 012): approved after complete production/test review, source
eligibility/reference corrections and a post-capture rollback regression. All 14 focused real-Room tests passed in an
independent coordinator emulator run, alongside independent Kotlin compilation; contributor verified the existing
cloning (7), finish/deletion (7), addition/history (4) and all 106 JVM tests, assembly/lint and both diff checks. Starts
atomically replace selected-scope old actives and capture one command with all local affected generations; copied IDs
exactly match the durable child plan. Invalid sources preserve the old active. Repository finish/history/delete and
app/timer alignment remain open; M3 as a whole is still unapproved.

Coordinator regression checks after workout-edit integration: all 7 existing `WorkoutFinishAndDeletionTest` and 4
existing `WorkoutAdditionAndHistoryTest` methods passed on emulator-5554 in separate filtered runs with zero
failures/errors/skips. This runner selected only the first class when given a comma-separated class property, so
subsequent filtered gates must execute classes separately and inspect the actual XML suite names/counts. These checks do
not replace the final unfiltered instrumentation gate.

M3 workout-edit slice (existing Assignment 011): approved after complete code/test review and corrections preserving
explicit completion/unchecking, raw aggregate capture, stored positions and timer-recorded rest. All 18 focused
real-Room tests and 106 JVM tests pass with zero failures/errors/skips; Kotlin compilation was independently verified,
assembly/lint and both diff checks pass. Existing workout regression verification is recorded separately below. All nine
simple workout edits are fenced and captured; starts/source commands, history additions, finish/deletion and app/timer
coordination still need their planned reviews. This is partial M3 progress, not approval of M3 as a whole.

M0: corrected source inventory reviewed and accepted for foundation work. Sign-out policy is settled: retain the account
cache privately. Typed-command and import-collision details must be settled before their dependent slices; the report's
alternatives are not blanket-approved.
M1: Assignment 002 approved after coordinator review and corrections: v9 root metadata, durable ordered outbox, account
cursor/device session state (separate active and cached identities), and catalogue version. All 63 instrumentation tests
passed unfiltered on emulator-5554, including 9 foundation tests and both migration tests (all starting versions 1–8 and
copied bundled v1 asset). All 40 JVM tests, Kotlin/KSP compilation, debug assembly, and both diff checks passed.
Snapshot staging is deferred to its own reviewed migration when M8 is implemented. This gate delivers storage only, not
sync or sign-out behavior.
M2: Assignment 003 (workout transport models/mappings/strict codec) approved after review corrections and passing
compile, all 59 JVM tests (19 new transport tests, zero failures/errors/skips), assembly, lint (one nonfatal
newer-dependency warning), and both diff checks. Raw JSON uses strict kotlinx JSON parsing before explicit field
readers; java.time is desugared for API 24. Assignment 004 (routine/custom transport) approved after full code review
and additional expected-JSON/metadata/parent/deleted-root test cases; all 74 JVM tests, compilation, assembly, lint, and
both diff checks passed. Assignment 005 (typed canonical changes/result envelopes) approved after enum/full-record
validation and active replacement/outcome round-trip corrections; all 82 JVM tests, compilation, assembly, lint, and
both diff checks passed. Assignment 006 (basic typed mutations, imports, durable request v1) approved after raw/envelope
integrity, unknown-field, normalization, timestamp/import, and reordered-child acceptance corrections; all 96 JVM tests,
compilation, assembly, lint, and both diff checks passed. Assignment 007 (remaining nine typed semantic payloads)
approved after expected-envelope and fresh-child-identity corrections; all 103 JVM tests, compilation, assembly, lint,
and both diff checks passed. M2 is complete; all 17 kinds are modeled, and no repository capture or syncing is implied.
The coordinator added client-protocol/authentication handoff documents and the 0.3 client draft OpenAPI (all 19 mutation
variants validated structurally, 213 internal references resolved at that gate; subsequently extended typed
canonical/outcome schemas resolve all 235 references); these specify the expected separately built service, not a
deployed integration.
M3: Assignment 008 (common session/capture primitives) approved after wrapper/identity/tombstone corrections, full code
review, 17 real-Room capture tests and all 106 JVM tests passing, plus compilation/assembly/lint and both diff checks.
Extra-effect tail preservation and multi-root rollback are covered. Assignment 009 (selected-scope application reads and
custom mutations) approved after bundled guards, referenced-name isolation, retained-child deletion guards and fixture
corrections, complete code/test review, 19 scoped-repository tests and all 106 JVM tests passing,
compilation/assembly/lint and both diff checks. Assignment 010 (routine mutations) approved after returned-metadata,
parent/selection, fixture and rejection-semantics corrections, complete code/test review, 14 capture and 8 existing
routine tests plus all 106 JVM tests passing, independent Kotlin compilation, assembly/lint and both diff checks. All
ten routine mutation entry points are fenced/captured; saved blank/empty autosaves and immutable history rules remain
distinct. The remaining prepared workout/app slices require separate approval; the contributor conversation is
compressed between gates without adding feature scope. M4–M12 remain unstarted; the later fake-service slices are
planning within M4, not added feature scope.

M3 finish alignment must also drain accepted active-feature autosaves before requesting finish validation/confirmation.
The current feature queues name/notes/set writes independently of app finish; newly enforcing finished-history
immutability would otherwise turn delayed accepted edits into silent no-ops. Cover gated accepted edits, keep
composition ownership, and do not join the endless elapsed-time ticker. Full account/navigation scope draining remains
M7.

Before M3 capture integration, resolve an observed input mismatch: the current completed-set row can retain its
completion flag when a later numeric edit becomes unparsable, and saved routines can temporarily have blank names/empty
children during autosave. Capture must not turn such local editing into transaction failures. Preserve draft editing;
completed sets must become incomplete when their inputs cease to be valid, with presentation and timer coordination
following the persisted result. Routine save/start commands validate promotion/source eligibility separately from
draft/existing-routine autosave shapes. Add focused regressions and document the resulting behavior in
product-behavior.md at implementation time.

M3/M5/M6 design checks: finish must await all terminal timer writes, including earlier replaced/skipped/expired timers
whose active marker is already cleared. UI-provided set entities cannot reparent children or restore stale positions
after renumbering. Deletion revisions must remain durable even when the deleted root was previously unknown (hidden
owner-scoped root tombstone placeholders or an explicitly migrated tombstone store), so old upserts cannot resurrect it.
Remote batches must preserve the sole-active local presentation when a pending start protects a different active root;
explicit rejected-intent recovery reloads a full snapshot. Frozen wire JSON is carried unchanged from the durable
wrapper to HTTP.

The current finish DAO renumbers surviving exercises but does not renumber surviving sets when unfinished sets occur
between completed ones. M3 must close that gap transactionally, preserving every surviving set ID/value/rest and
assigning consecutive positions; cover interleaved completed/unfinished sets and rollback. This is required for the
existing storage invariant and canonical transport shape.

Approved foundation decisions: root defaults preserve signed-out data; signed-out edits do not accumulate upload work;
outbox sequence is an explicit autoincrement primary key; frozen requests are permanently non-coalescible; pending
replacement requires a newer generation; account keys are non-null with a separate singleton device/session row
distinguishing active and privately cached account. Payload/session/cursor fencing and domain capture are later
transactions, not features delivered by the metadata migration.

Planning baseline verification: direct Herdr context restored and contributor model verified. Kotlin compilation, all 40
JVM tests (no failures/errors/skips), and debug assembly passed before implementation; emulator-5554 is available for
migration/instrumentation gates. Baseline verification does not count as implementation acceptance.
