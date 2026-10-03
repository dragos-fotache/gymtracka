# Android sync coordinator handoff — 2026-10-03

## Current strategy and next task

**Documentation ownership:** Android architecture, tests, roadmap, handoff and assignment/review records now live in
this repository's top-level `docs/`, alongside `shared-docs/`. Shared product behavior, backend/API contracts and catalogue
artifacts remain in `shared-docs/`. Start with root [AGENTS.md](../AGENTS.md) and this handoff; follow the current roadmap,
not the archived execution instructions. Create detailed R01/subtask plans under `docs/remaining-work/`.

**Current delivery:** R01 planning/design and **R02a implementation are accepted** (2026-10-03).
Read the [R01 audit/task](remaining-work/R01-task.md), [integration design](remaining-work/R01-design.md),
[planning verification](remaining-work/R01-review.md), [R02a shared specification](remaining-work/R02a-task.md) and
[R02a coordinator review](remaining-work/R02a-review.md). R02a adds the focused iterative original-text duplicate-member
guard to `codec/StrictJson.kt`; escaped-equivalent names, nested/array objects and ignored extension fields reject safely.
Valid durable v1 bytes remain unchanged; no transport/auth/Room/UI work was added.

Actual R02a gates: **200 offline JVM tests in 26 suites**, **6 filtered Android parser smoke tests on API 37**,
production and Android-test Kotlin compilation, debug assembly and lint passed; zero test failures/errors/skips.
No full instrumentation, real-backend, OAuth or personal phone/laptop pass is added. The owner authorized the final
R02a delivery commit, including affected existing Android docs; unrelated dirty-tree and nested shared-docs work stays
outside it. No pushes, backend/configuration changes or next-slice execution are authorized.

**Next task:** prepare a bounded R02b identity-namespace plan only after explicit authorization. Do not automatically
continue all R02 or revive assignment 018. R01 itself remains a planning-only gate, not a build/device claim.

The proposed integration stores canonical records/deletion evidence separately and conservatively protects the entire
visible account projection while any local outbox intent remains. Clean complete-cache publication requires finite-edit
and timer barriers; R12a precedes production account activation. Snapshot staging, exact request acknowledgments,
crash-safe vault/Room journals, global Room PK collision refusal and personal/public subplans are documented, not implemented.
Phone access is required both on the LAN and away. HTTPS/private connectivity is not configured. The owner has a Google
Android debug client; a matching Web/server audience and installed signing certificate still need confirmation. No OAuth
client or laptop configuration was changed. These inputs block live R17a, not deterministic client development.

Use the [remaining-work roadmap](remaining-work-roadmap.md) as the current R01–R18 execution index. Historical M5–M12
capabilities remain required. The owner is the only current user and will host the server on a personal always-on laptop:
target safe usable personal sync first; public readiness, export/deletion and broad release gates are deferred. This is
prioritization, not a v1 contract rewrite or permission to deploy. Inspect live working trees rather than archived identities.
R01 pickup HEADs were Android `25bdfd66f1424e7deb06a5fedc585d49fa1f3ab3` and shared-docs
`f65abb82512c373ade9ab08699ef68971b2e8ceb`; both were already dirty and must be preserved.

The user completed the separate Java backend and authorized **removing the Android simulator first**. That task is
implemented: the two simulator classes, fixtures, 12 fake-only test classes (92 methods), and unused `sharedTest`
wiring are removed. Production interfaces, wire codecs, Room/capture tests, feature states, and UI regressions remain.
The current test layers are 200 offline JVM tests, 46 separate real-backend JVM tests, and 11 Python supervisor tests.
Only the offline suite and six new Android smoke tests were rerun for R02a; backend/Python counts are earlier evidence.
See the [coverage ledger](fake-retirement-coverage.md), [retirement review](fake-retirement-review.md), and
[testing strategy](testing-strategy.md#current-baseline--simulator-retired-2026-10-02) for actual gates and limits.
Assignment 018 remains paused; do not implement or recreate the simulator.

**Scope clarified by the user:** `/home/dragos/IdeaProjects/gt-backend` is supplied for operational build/start only.
Treat it as a black-box HTTP service, not another source-review, Maven-suite, or implementation assignment. The private
configuration directory `/home/dragos/.config/gymtracka-backend` is not a test input; do not print/copy/edit its secrets.
Tests instead use `scripts/run-isolated-backend.py`, disposable PostgreSQL 16, generated keys, a guarded mode-600 config,
and test-only credential setup. No personal database or backend source was changed. Earlier setup/test inspection and
an interrupted broad Maven run preceded this clarification; they are not a full backend verification claim.

The new `HttpGymSyncApi` is JVM-test-only. The app still has no running sync engine, production HTTP adapter, working
sign-in/account UI, snapshot publication, or background scheduling. M5–M12 remain pending; get explicit authorization
and plan a bounded client slice rather than continuing automatically. Real Google OAuth, deployed HTTPS, and device
sync/account flows are not proved by the local HTTP task. The last full device gate remains M3's 161 tests on API 37.

**Current contributor workflow (owner instruction):** coordinator owns design/planning and independent full review;
`gym-worker` and `gym-qa` are Pi agents using `meta/muse-spark-1.3-contributor` at high reasoning. R02a rediscovered
coordinator `w4:p7`, Worker `w4:pJ`, QA `w4:pK`; both roles finished R02a and are now idle. Start fresh contributor
sessions for a later authorized slice; do not assume these identities/processes remain unchanged.
Rediscover live identities/processes before reuse and preserve user focus. Worker must never inspect tests/fixtures/
reports or QA work; QA must never inspect production code/diffs/decompiled sources or Worker work. Both receive the
same normative spec/API contract with disjoint allowlists, use Kotlin mcpls navigation restricted to their own code,
and send fire-and-forget `herdr agent prompt gym-coordinator ...` reports without `--wait`/`agent wait`.
Coordinator supplies missing API/harness facts and reviews both layers, scheduling shared-output Gradle runs serially.
Coordinator resolved a bounded QA escape-fixture editing blocker without granting cross-role source access, then
returned focused execution to the same QA session. Contributor-session mcpls was unavailable/indexing; coordinator
navigation/diagnostics and actual compilation/tests supplied the gate. No agent changed global configuration.
R02a is complete; only its coordinator-owned final delivery commit is authorized. No next implementation or push is
scheduled. Preserve the existing uncommitted simulator-retirement/HTTP/build/AGENTS and nested-doc changes.

The remainder is the **archived pre-pivot baseline and 018 proposal**. Its old pending statuses, dirty-tree/pane
identifiers, imperative instructions, and delegation gates are historical, not current authorization.

## Archived state and stop boundary

Assignment 017 (M4c) is complete and coordinator-approved. The Android test-only fake now executes all 17 typed push
mutation kinds. The [017 review](sync-assignments/017-review.md) records the implementation audit, corrections, and
independent final gate. Assignment 018 (M4d) has **not** started. This handoff prepares its implementation; it is not
authorization to start it in the current coordinator session. The production app still has no running sync engine,
network adapter, or working sign-in.

Assignment 018 is the last **prepared fake-service assignment**, not the last task needed to make the Android app sync.
M5–M12 still cover reconciliation, two-client convergence, account lifecycle/import/replace, staged bootstrap and full
resync, live HTTP and Google adapters, WorkManager, and final audit. There is no backend or OAuth project to test
against; backend development is separate. The user's current scope is the Android app only. Do not implement backend
code.

## Read before delegating

1. Root [AGENTS.md](../AGENTS.md), this handoff, [implementation plan](sync-implementation-plan.md) current update
   and M4 section, [architecture](architecture.md), [testing strategy](testing-strategy.md),
   and [product behavior](../shared-docs/docs/product-behavior.md).
2. The [backend contract README](../shared-docs/docs/backend-contract/README.md) and its linked domain rules, schema, sync protocol,
   OpenAPI, fixture, Android client protocol, and authentication documents. These define future service behavior; the
   checked-in Android code defines what the app currently does. Do not copy historical baseline statements as current
   implementation facts.
3. [Assignment 018 specification](sync-assignments/018-task.md) in full and
   the [bounded Muse plan](sync-assignments/018-muse-plan.md). Use the 017 review as the accepted baseline. No 017 code
   needs to be reimplemented.

## Approved Android baseline

M0–M3 and M4a–c are approved. Room is v9 with data-preserving migrations from supported versions and the original
bundled v1 catalogue asset. Typed DTOs/codecs cover all 17 mutation kinds, durable requests preserve exact request JSON,
and account/session-fenced local capture and selected-scope reads are in place. M4a added the SDK-independent
`GymSyncApi`/auth boundaries and a fake compiled only into JVM and Android test source sets. M4b implemented seven basic
fake commands. M4c implemented ten semantic commands: finish, perform again, start from routine, save as routine,
history additions, reference replacement, and workout/routine child deletion. All effects stage before a complete
canonical batch commits; rejected commands leave roots, child ownership, revisions, active pointer, and log unchanged.

The coordinator forced the full JVM task after 017: **223 tests in 35 XML suites, zero failures, errors, or skips**.
`:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:lintDebug`, and
`:app:compileDebugAndroidTestKotlin` passed. Both diff checks passed, and the fake was absent from production APK dex.
Lint's `NewerVersionAvailable` and KSP catalogue join-table index warnings are existing nonfatal findings. No device run
was added in 017; the last unfiltered instrumentation gate remains M3's 161 passing tests on API 37. No production
source, Room/schema, bundled asset, dependency, HTTP/auth/UI/worker, or backend-contract code changed in 017.

Current 018 gaps are concrete: `FakeSyncService.executePush` stores outcomes but re-executes repeated mutation IDs; its
log lacks retained-history semantics; `FakeAccountSyncApi.pull` and `snapshot` still throw
`UnsupportedOperationException`. The fake is therefore not yet a reliable sync-engine oracle. Assignment 018 must finish
original-result deduplication, deterministic failure controls, 90-day log retention, account-bound opaque cursors,
complete-batch pull, and frozen paginated snapshots. Retain deduplication evidence beyond log pruning. An initial
snapshot must discover old unchanged roots that are no longer in the log.

## Implementation map for Assignment 018

All intended edits are Android test infrastructure and tests. Start with these exact files:

- `app/src/sharedTest/java/com/dragosfotache/gymtracka/testing/service/FakeSyncService.kt`: `executePush`,
  `recordOutcome`/`replayOutcome`, `appendChangeBatch`, `commitBatch`, account logs, roots, and injected `clockMillis`.
  The current `uuidProvider` creates opaque batch IDs/cursors; do not infer cursor order from their text. Preserve all
  17 handlers and atomic staging.
- `app/src/sharedTest/java/com/dragosfotache/gymtracka/testing/service/FakeAccountSyncApi.kt`: account-bound adapter,
  push validation/call recording, and the pull/snapshot stubs. Service methods must accept the bound account internally,
  never an owner field from a request.
- `app/src/main/java/com/dragosfotache/gymtracka/data/sync/api/SyncApi.kt` and `SyncApiFailures.kt`: existing page
  bounds and typed failures. These are approved boundaries, not a reason to add production engine work.
  `PullResultModel`, `SnapshotResultModel`, `MutationOutcomeModel`, and `SyncBatchModel` are in
  `data/sync/model/ProtocolModels.kt`.
- `app/src/test/java/com/dragosfotache/gymtracka/data/sync/`: independent JVM contract tests and `FakeSyncFixtures.kt`.
  Existing 017 command suites and M4a/b tests are regression gates. Shared fake compilation into Android-test sources is
  already wired by `app/build.gradle.kts`.

The [018 Muse plan](sync-assignments/018-muse-plan.md) defines three cohesive, sequential review gates: 018a durable
replay and push failures; 018b retained log and pull; 018c frozen snapshot and final failure/contract coverage. Each
subtask belongs in **one Muse session from start through review corrections**. Give Muse only the next subtask's
detailed instructions, then independently review its diff and tests before authorizing the next. Do not fragment a
subtask into new sessions as happened during 017b. Do not ask Muse to implement all three subtasks at once.

## Working trees and contributor lifecycle

Repository root: `/home/dragos/AndroidStudioProjects/GymTracka`. Android branch `master` was at
`0b8490502562ca4e5bf42653979bbfa9a17d092e`; nested `shared-docs` branch `master` was at
`eb3f553e149c2bae356c4026af43819db0ced09d` when this handoff was prepared. **All changes are uncommitted.** The Android
tree has modified shared-test fake/fixture/boundary files and eight untracked 017 command test classes. The nested docs
tree has earlier approved dirty backend/product/Android docs and untracked sync documents; preserve all of it. Plain
`git diff` omits untracked files. No commits or pushes were made for 017 or this handoff.

The 017d Muse Spark Contributor 1.3 Hermes agent in Herdr pane `w3:p8` delivered its report and was closed with native
`/exit`; `herdr pane process-info --pane w3:p8` showed a foreground zsh shell in this repository. The coordinator should
inspect live state before reuse; do not assume a pane, model, or process remains unchanged. The user's established
workflow is one fresh Muse session per approved subtask, a detailed orientation/prompt up front, coordinator review
after each, and no next subtask until approval. Keep corrections inside the same session. If using Herdr, verify
`HERDR_ENV=1` and read `/home/dragos/.agents/skills/herdr/SKILL.md`; preserve user focus and unrelated panes. Do not
change global configuration or personal skills.

## Verification and handoff gate

For each 018 subtask, inspect the whole changed diff **including new files**, run focused JVM tests, review exact
canonical records/outcomes/cursors and rejected no-effect state, and confirm previously accepted commands still pass. At
the final 018 gate run from the Android root:

```sh
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --no-configuration-cache --console=plain
./gradlew :app:testDebugUnitTest --rerun-tasks --no-configuration-cache --console=plain
git diff --check
git -C shared-docs diff --check
```

Inspect the executed `app/build/test-results/testDebugUnitTest/TEST-*.xml` counts rather than relying on an up-to-date
Gradle task. Confirm shared fake classes compile in both test layers and remain absent from the production APK.
Update [architecture](architecture.md), [testing strategy](testing-strategy.md), the implementation-plan status/review
ledger, and a new 018 coordinator review with actual evidence. Stop after 018 review; M5 or backend work requires its
own later authorization. No real backend, OAuth, hosted CI, or new device execution is claimed by the fake-service gate.
