# Android sync coordinator handoff — 2026-10-04

## Coordinator succession

The owner requested a fresh coordinator and retirement of the previous one. Successor **`gym-coordinator` in `w4:pQ`**
is a fresh Pi `openai-codex/gpt-6.1-sol` / high session in the Android root and has acknowledged read-only orientation,
live working-tree inventory, R03c acceptance/legacy limits and the R04 authorization boundary. Previous coordinator
`w4:p7` is renamed `gym-coordinator-retired` and stops after this handoff. Existing Android/backend contributors are left
holding; rediscover all live names/models before reuse. **No R04 planning/implementation is authorized by succession.**

## Current strategy and next task

**Current acceptance — R04b (2026-10-04):** owner authorized “start work at R04b”.
[Specification](remaining-work/R04b-task.md) and [review](remaining-work/R04b-review.md) accept UNUSED serialized exact
pending-first refresh, durable-before-send/whole-grant CAS, explicit atomic revocable leases and one unchanged401 retry.
Independent99 focused and **726 unique forced offline JVM tests in45 suites**, Kotlin/Android-test compilation,
assembly/lint/manifest/APK/asset/preservation passed; zero test failures/errors/skips. No new device/backend/Google/Room
convergence gate. Lease invalidation is NOT durable sign-out; R04c/R09 still own startup/two-store/Room integration.
No app construction/account/UI/engine activation. Legacy incompatible accepted replay remains UNREPAIRED: no rounding/
rewrite/new uncertain key/revocation/inferred cache deletion. New test compiler opt-ins resolved; two nonfatal unused-
expression warnings explicitly recorded. Both contributors hold idle in completed fresh R04b Pi Muse Contributor/high
sessions (Worker w4:pJ, QA w4:pK); same-session corrections/source isolation/serialized slots retained. Owner now requires
fully async Herdr reports: no coordinator Worker/QA output waits/polling. Rediscover live identities before reuse.
Android HEAD04f7ed2 and shared HEAD31c969f unchanged. Seven pickup receipt-doc edits preserved; all279 pickup files outside
seven intentionally updated local docs, including existing source/tests/build/schema/backup/asset and entire shared tree,
are hash-identical. Four new production/three new QA/two new task-review files plus local docs remain UNCOMMITTED; no staging/
commit/push. No emulator/service/config created. **Next ONLY after specific authorization: R04c startup/session recovery seams.**
No c specification/assignment, Room/account activation, backend/configuration/deployment or commit/push is authorized.
Older R04b stop/authorization descriptions below are historical gate records, not permission to continue. Stop after b.

**Prior R04a acceptance (2026-10-04):** after succession, owner instructed “Let's start work on R04a then”.
[R04 split](remaining-work/R04-task.md), [specification](remaining-work/R04a-task.md) and [review](remaining-work/R04a-review.md)
now record **R04a accepted**: UNUSED encrypted vault with exact pending-attempt/grant CAS/tombstones, Keystore/private
noBackup storage and explicit backup XML.627 forced JVM/27 filtered API37 vault tests, compilation/assembly/lint/manifest/
APK/preservation passed, zero failures/errors/skips. Room9/asset1/build/app/domain/state/manifest unchanged; no refresh
HTTP/access/startup/Room journal/account flow. Owned headless emulator stopped, no backend/service/configuration created.
Both fresh R04a contributors hold idle; source isolation/serialized slots preserved. All R03c tracked/untracked/shared
issue edits preserved at acceptance (Android6134a58/shared d5d68d4). The owner subsequently committed R03c/R04a and the
shared issue resolution; see the verified delivery commits below. No coordinator commit/push was performed.
**At that R04a gate**, next proposed pickup was R04b only after specific authorization; b was later authorized/accepted
as recorded above. R04c/account activation/backend/deployment/private configuration remain unauthorized. Legacy incompatible accepted
replay remains UNREPAIRED: no rounding/rewrite/new uncertain key/revocation/inferred data deletion. Earlier no-R04 text
below describes prior gates, not current pickup.

### Owner delivery commits — verified 2026-10-04

The owner reported committing the changes. Live local history/status confirms:

- Android `04f7ed24c39b4396122e6d60225942493ba500e2` (`sync work`), parent `6134a58`: accepted R03c/R04a production,
  tests/harness, backup XML and local task/review/handoff documents (37 files).
- Nested shared-docs `31c969f261bd3f0fe662e633c0dfffecb3c08393` (`sync work`), parent `d5d68d4`: the auth-expiry issue
  resolution and contract README index (2 files). Legacy incompatible accepted replay remains explicitly unresolved.

Both trees, including untracked files, were clean before this documentation-only follow-up. The earlier uncommitted
statements below are historical gate records. Inspect live HEAD/status on pickup; this receipt update is separate from
the owner's commits. No build/test rerun, coordinator commit/push, remote/push verification or new task authorization
is added by that receipt. R04b was separately authorized/accepted afterward as recorded at the top; R04c still requires
specific owner authorization.

**R03c pickup history:** the owner authorized R03c (“Go ahead and start work at task R03c”). Its
[specification](remaining-work/R03c-task.md) and [review](remaining-work/R03c-review.md) now record **R03c acceptance
(2026-10-04)**: client gates 553 offline JVM/six API37 smoke/build/lint passed; unchanged corrected-service auth10/10 and
broad56/56 passed with all expiry-precision flags true. Historical incompatible accepted replay remains a known legacy
limitation, not counted repaired. Android/backend contributors hold; source isolation and bounded ownership preserved.
Owned rerun service/container/config are cleaned up; no new emulator/offline/device pass was added in the backend-only rerun. Live pickup found clean Android HEAD
`6134a58bacaa20bcfc7ccb872a77ffb104867d21` and clean shared-docs HEAD
`61fe3f9e2e7d4b502abea7532cf566759fb6ada4`; the earlier uncommitted R02d/R03a/b statements below are historical.
No R04/R03d/R02e, commit/push, activation, deployment or private configuration is authorized.

**Documentation ownership:** Android architecture, tests, roadmap, handoff and assignment/review records now live in
this repository's top-level `docs/`, alongside `shared-docs/`. Shared product behavior, backend/API contracts and catalogue
artifacts remain in `shared-docs/`. Start with root [AGENTS.md](../AGENTS.md) and this handoff; follow the current roadmap,
not the archived execution instructions. Create detailed R01/subtask plans under `docs/remaining-work/`.

**Current delivery:** R01 planning/design and **R02a–d/R03a–c implementations are accepted** (R03c: 2026-10-04).
Read the [R01 audit/task](remaining-work/R01-task.md), [integration design](remaining-work/R01-design.md),
[planning verification](remaining-work/R01-review.md), [R02a shared specification](remaining-work/R02a-task.md) and
[R02a coordinator review](remaining-work/R02a-review.md). R02a adds the focused iterative original-text duplicate-member
guard to `codec/StrictJson.kt`; escaped-equivalent names, nested/array objects and ignored extension fields reject safely.
Valid durable v1 bytes remain unchanged; no transport/auth/Room/UI work was added.

Actual R02a gates: **200 offline JVM tests in 26 suites**, **6 filtered Android parser smoke tests on API 37**,
production and Android-test Kotlin compilation, debug assembly and lint passed; zero test failures/errors/skips.
No full instrumentation, real-backend, OAuth or personal phone/laptop pass is added. The owner authorized the final
R02a delivery commit, including affected existing Android docs; unrelated dirty-tree and nested shared-docs work stays
outside it. No pushes or backend/configuration changes are authorized.

**R02b result:** see the [shared specification](remaining-work/R02b-task.md) and
[coordinator review](remaining-work/R02b-review.md). Root/entry/set family aliases now reject for full aggregates/imports;
copy plans enforce internal source/target uniqueness and same-family disjointness while permitting independent
workout/routine UUID overlap. **216 offline JVM tests in 28 suites**, Kotlin and Android-test compilation, assembly and
lint passed with zero test failures/errors/skips. No new device/backend/Room/migration pass is claimed. Delivery commit
is covered by the later owner authorization below.

**R02c result:** see its [shared specification](remaining-work/R02c-task.md) and
[coordinator review](remaining-work/R02c-review.md). Five SDK failure types now distinguish foreground reauthentication,
invalid/expired read tokens and request size rejection; access-auth text is corrected. API interfaces changed only in
KDoc. Coordinator aligned the JVM-only error mapper and legacy test expectations, including three Android-owned
real-backend invalid-token catches (compiled, not executed). **283 offline JVM tests in 30 suites**, compilation,
assembly and lint passed with zero failures/errors/skips; no new device/backend gate is claimed.

**Delivery commit:** the owner requested committing the changes and selected **all Android changes**, including the
existing retirement/HTTP/build/scripts/local-doc baseline, so the delivery is self-contained. See the
[commit review](remaining-work/R02bc-commit-review.md) and Android git history after `2a93c56`. Nested shared-docs is
preserved exactly and is not committed/pushed. This authorizes the Android delivery commit only, not another slice.

**R02d result:** owner-authorized auth representation slice accepted; see the
[shared specification](remaining-work/R02d-task.md) and [coordinator review](remaining-work/R02d-review.md).
Four challenge purposes, raw auth request/response codecs, all expiry constructor/copy range checks and immutable
exact-body exchange/refresh attempts are delivered. Typed ServiceAuthApi methods preserve attempts; legacy caller
conveniences delegate. Auth-only lexical checks reject unquoted scalars/literal controls without changing shared parser
or durable behavior; long fractional zero tails remain lossless without normalizing malformed fractions.
**340 offline JVM tests in 32 suites**, production/Android-test compilation, debug assembly/lint and both diff checks
passed with zero test failures/errors/skips. Independent temporary probe: 1,138 auth bodies, zero mismatches after
same-session review corrections. No device/backend/OAuth gate or auth activation was added. Android R02d changes are
uncommitted; nested shared-docs remains exactly preserved.

**R03 planning split:** the owner requested a split like R02; see [R03 subtask plan](remaining-work/R03-task.md).
R03a is secure origin/cancellable HTTP primitive, R03b sync endpoints/shared service errors, R03c exact-body service auth,
and R03d deferred account-lifecycle transport/export streaming. Default review order is a → b → c, then stop; d needs
R02e and public lifecycle feature pickup. R04 needs a/c and R06 needs b, not deferred d.
This planning task changes Android docs only, assigns no contributors and adds no build/test/backend/device pass.

**R03a result:** owner-authorized secure primitive accepted; see the
[shared specification](remaining-work/R03a-task.md) and [coordinator review](remaining-work/R03a-review.md).
OkHttp 5.5.0 supplies strict immutable HTTPS origin/fixed requests, exact UTF-8, no redirect/cookie/cache/auth/replay,
per-Call on-wire guard (including GET503 Retry-After:0), scoped cancellable response/chunk reads, strict complete UTF-8/
media/framing and capped hints. INTERNET/cleartext-refusal/standard Startup wiring is delivered, not app activation.
**423 offline JVM tests in 35 suites**, **5 filtered Android transport tests + 1 signed-out startup smoke on API 37**,
compilation, debug assembly, lint, manifest/APK/preservation and both diff checks passed, zero failures/errors/skips.
Coordinator/source review corrections include actual blocked-read cancellation/late close, proper per-Call guard,
quoted-media/closure ownership and exact consumer exception preservation across coroutine stack-trace recovery; identity
assertions were not weakened. Initial test/compiler failures were corrected before final forced acceptance.
API24 runtime/full instrumentation, backend/OAuth/Python/phone-laptop gates were not run. Android R02d/R03a remains
uncommitted; nested shared-docs is exactly preserved. Both roles completed fresh R03a Pi sessions and are idle.

**R03b result:** accepted; see [specification](remaining-work/R03b-task.md) and
[review](remaining-work/R03b-review.md). Bound production push/pull/snapshot preserve frozen bytes, select one tagged
service/account/generation credential, require complete ordered outcomes/pages and apply strict scalar/error/status rules.
**477 offline JVM tests in 38 suites**, **46 real-backend tests in 9 suites through actual production adapter + test TLS
relay**, **3 filtered Android parser/endpoint tests on API37**, compilation/assembly/lint/APK/preservation checks passed,
zero failures/errors/skips. Coordinator corrected test fixtures and Android accessor/DEX naming issues before acceptance.
No auth adapter, engine/vault/Google/UI or app activation. Both fresh R03b contributors are complete/idle; owned isolated
backend/container/config and emulator cleaned up. Existing R02d/R03a changes and nested shared-docs are preserved.

**R03c result:** accepted. See [review](remaining-work/R03c-review.md): four-purpose challenge/exact-body exchange-refresh/
verified-empty204 logout, no bearer/retry, original cancellation and safe errors. Client gates 553 offline/six API37/
compilation/assembly/lint/APK/preservation passed. Initial eight auth failures were retained as evidence. Separately owned
backend correction then passed unchanged auth10/10 and broad56/56 (all46 sync cases), zero failures/errors/skips, all
precision flags true; 201/201 client inputs and reviewed JAR SHA256 unchanged. Backend reported independent full344/344,
but Android did not run its Maven suite or inspect backend source. Operational rerun used --no-build and generated
isolated configuration; owned service/container/config cleaned up. No new Google/API24/full-device/Room convergence gate.

**Legacy limit:** pre-fix encrypted accepted replay bytes/deadlines remain original and incompatible; fresh still-current
legacy refresh can rotate, but uncertain consumed-token retries never get a new key/rounding/replay rewrite automatically.
No blanket revocation or cache/data deletion. This is retained rollout limitation, not full v1 release compliance.

**Next proposed task:** R04c startup/session recovery seams, ONLY after specific owner authorization; carry this legacy
limitation into recovery design. R04a/b unused vault/refresh machinery is accepted as recorded above; no c specification/
implementation has started. No R04c/R03d, R02e, commit/push, account activation/deployment/private configuration or018 is authorized.

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
The current test layers are 477 offline JVM tests, 46 separate real-backend JVM tests, and 11 Python supervisor tests.
R02b–d reran offline/build gates only; R03a added five filtered transport device tests and one startup smoke.
R02a's six parser smoke tests and backend/Python counts are earlier evidence.
See the [coverage ledger](fake-retirement-coverage.md), [retirement review](fake-retirement-review.md), and
[testing strategy](testing-strategy.md#current-baseline--simulator-retired-2026-10-02) for actual gates and limits.
Assignment 018 remains paused; do not implement or recreate the simulator.

**Scope clarified by the user:** `/home/dragos/IdeaProjects/gt-backend` is supplied for operational build/start only.
Treat it as a black-box HTTP service, not another source-review, Maven-suite, or implementation assignment. The private
configuration directory `/home/dragos/.config/gymtracka-backend` is not a test input; do not print/copy/edit its secrets.
Tests instead use `scripts/run-isolated-backend.py`, disposable PostgreSQL 16, generated keys, a guarded mode-600 config,
and test-only credential setup. No personal database or backend source was changed. Earlier setup/test inspection and
an interrupted broad Maven run preceded this clarification; they are not a full backend verification claim.

The legacy backend-test `HttpGymSyncApi` is JVM-test-only. R03a delivers a reusable secure production-compatible HTTP
primitive; R03b adds the unused production sync adapter and R03c adds the accepted unused auth adapter with its explicit
historical replay limitation.
The app still has no running engine, working sign-in/account UI, snapshot publication or background scheduling. M5–M12 remain pending; get explicit authorization
and plan a bounded client slice rather than continuing automatically. Real Google OAuth, deployed HTTPS, and device
sync/account flows are not proved by the local HTTP task. The last full device gate remains M3's 161 tests on API 37.

**Current contributor workflow (owner instruction):** coordinator owns design/planning and independent full review;
`gym-worker` and `gym-qa` are Pi agents using `meta/muse-spark-1.3-contributor` at high reasoning. R02a rediscovered
coordinator `w4:p7`, Worker `w4:pJ`, QA `w4:pK`; R02b–d rediscovered the live panes/models and used fresh native
`/new` sessions for each slice. R02d retained the same sessions for corrections (including a stopped/resumed stalled
Worker planning turn before source writes). Both roles completed R02d and then owner-authorized R03a in fresh sessions; they are idle. Rediscover/start fresh sessions for
another authorized slice; do not assume these identities/processes remain unchanged.
Rediscover live identities/processes before reuse and preserve user focus. Worker must never inspect tests/fixtures/
reports or QA work; QA must never inspect production code/diffs/decompiled sources or Worker work. Both receive the
same normative spec/API contract with disjoint allowlists, use Kotlin mcpls navigation restricted to their own code,
and send fire-and-forget `herdr agent prompt gym-coordinator ...` reports without `--wait`/`agent wait`.
Coordinator supplies missing API/harness facts and reviews both layers, scheduling shared-output Gradle runs serially.
Coordinator resolved a bounded QA escape-fixture editing blocker without granting cross-role source access, then
returned focused execution to the same QA session. Contributor-session mcpls was unavailable/indexing; coordinator
navigation/diagnostics and actual compilation/tests supplied the gate. No agent changed global configuration.
R02a is committed as `2a93c56`; the subsequent owner-authorized Android delivery includes R02b/R02c and the prior
retirement/HTTP/build/AGENTS/local-doc baseline, delivered as `623207c`. Accepted R02d/R03a/b were subsequently
owner-committed in `6134a58`; R03c/R04a delivery is recorded above.
Nested-doc history remains separate and untouched; later owner-authorized R03c pickup/status is recorded above.
No R03d, commit or push is authorized.

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
