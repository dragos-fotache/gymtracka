# R13c — acceptance review

**Accepted: mandatory account startup and account UI.** The [specification](R13c-task.md) owns the bounded assignment.
No-argument GymApp now gates initial workout access; explicit local/test composition is not a production fallback.
The owner requires stopping after R13c: no successor launch, R13d specification/delegation/build or ownership transfer.

## Delivered boundary and independent review

- Empty-default, nonsecret build-input seam; missing/invalid origin shows configuration/privacy gates without database,
  vault or HTTP reads. Valid origin/missing audience still reopens matching established accounts offline. Credentials
  never move to a changed origin. No personal configuration or signing/OAuth values were discovered or changed.
- Composition-owned startup/controller and Activity-owned Google adapter consume service-confirmed foreground grants
  through the existing exact-handle runtime and real editor/edit/timer barriers. Startup recovery is HTTP-free;
  sign-in/reauthentication owns at most one complete activation attempt, without automatic delivery/stage restart.
- Actual initial legacy graph capture, original-active warning/opaque-token consent, exact accepted import settlement
  and complete materialization precede visibility. Blocked/corrupt/foreign evidence and partial progress stay private;
  cancel never promises remote rollback. Same-account clean reauthentication advances durable generation/operation,
  retires the old handle and preserves canonical data. File reopen retains queued intent and actual offline edit capture,
  including expired access credentials and missing audience.
- Account status observes selected pending count, recorded last-success and closed retained/delivery errors. Observer
  registration precedes read; full transactional ready authority/cache plus exact handle/process fences protect every
  emission. SQLite storage-type checks reject malformed TEXT/REAL/BLOB before typed getters can fabricate epoch zero.
  Old status is cleared on private/new-handle authority; no metadata DTO grants visibility or triggers HTTP.
- Final review corrected unreachable ACTIVE reauth, unusable busy cancellation, stale post-grant snapshot fallback,
  swallowed import faults, unowned recovery work, pre-body cancellation occupation/presentation, stale status adoption,
  incomplete ready-tuple checks and missing policy remember keys. UUID-boundary cancellation remains cancellation,
  not unavailable. Normal editor refusal preserves an actual typed draft before provider launch; real projection-bound
  timer transition drains five whole seconds once. No schema/entity/source-asset change or foundation package reopening.

Worker and QA used fresh independent native Muse Spark Contributor/high sessions with disjoint source allowlists;
coordinator alone reviewed both complete layers and integrated build/private presentation changes. No opposite-role
source access was granted. Kotlin LSP supplied empty indexing, not clean diagnostics; compiler/allowed reads were used.

## Executed gates — 2026-10-09

Repository-root commands; explicitly owned disposable headless x86_64 current37.2/Android17 16K and API24/Android7.0
emulators. Gradle slots were exclusively serialized; both layers were frozen for lint. Actual nonempty executed XML was
inspected and preserved separately per run. Cached compilation/assembly dependencies are not new test execution claims.

| Gate | Actual command / selection | Result |
|---|---|---|
| Kotlin/Room | `./gradlew :app:compileDebugKotlin --console=plain` and final code compilation dependencies | Success with executed Kotlin/KSP after production corrections; pre-existing compiler/KSP warnings retained. Room14/schemas1–14 and source asset unchanged. |
| Full offline JVM | `./gradlew :app:testDebugUnitTest --rerun --console=plain` | Final fresh execution: **1068 tests /68 classes**, zero failures/errors/skips; no backend-package classes. Includes33 new controller/config/consent tests. |
| Full current default instrumentation | `ANDROID_SERIAL=<owned-current> ./gradlew :app:connectedDebugAndroidTest --rerun -Pandroid.testInstrumentationRunnerArguments.timeout_msec=120000 --console=plain` | Final fresh **1089 tests /100 classes**, zero failures/errors/skips. No class/package filter or roomBackend opt-in. Includes migrations/all supported starts/copied asset,22 new account cases and existing feature/authority/runtime regressions. |
| Affected API24 startup/import/recovery | Same connected task on owned API24 with `package=com.dragosfotache.gymtracka.ui.account` | Final **22 tests /5 classes**, zero failures/errors/skips: actual MainActivity default, initial configured/import/consent, successful same-account reauth, metadata type/currentness, file reopen/offline edit and real editor/timer drain. Not a full minimum-API app regression. |
| API24 Android Google adapter | Same connected task with `class=com.dragosfotache.gymtracka.data.sync.identity.AndroidGoogleIdentityProviderTest` | **34 tests**, zero failures/errors/skips; injected SDK-client/request/parser/lifecycle evidence, not actual Google. |
| API24 real import admission | Same connected task with `class=com.dragosfotache.gymtracka.ui.LegacyImportAdmissionTest` | **6 tests**, zero failures/errors/skips; real finite-edit/rest/editor lease behavior. |
| Assembly/lint | `./gradlew :app:assembleDebug :app:lintDebug --console=plain` | Success. Assembly reused the current debug artifact; final lint analysis executed, **0 errors /1 pre-existing NewerVersionAvailable warning**. |
| APK/config/security/backup | ZIP/DEX/source-asset checks and build-tools36 `aapt2 dump permissions`, `dump badging`, `dump xmltree` | No fixture/relay/JDBC/test-token markers in debug DEX. Both generated service/audience fields empty. Exact v1 asset bytes retained. Binary min24/target37, cleartext disabled, INTERNET/VIBRATE/WAKE_LOCK and production vault legacy/cloud/device-transfer exclusions verified. |
| Scope/docs | Both complete diffs/new files, schema/asset unchanged, relative links/anchors and `git diff --check` | Slice-owned source/tests/build/docs only; no backend/private configuration changes, migrations or added dependencies. |

Failed-gate evidence was not counted as passing: initial device fixtures used invalid challenge IDs, ambiguous warning
selectors, colliding reopened UUID suppliers, invalid pending/tail scripts and unjoined teardown. These were corrected
without weakening domain/privacy expectations. A reauth waiter that could see the **old** Active before completion was
replaced by busy-terminal, generation/operation advancement, old-handle staleness and new-handle currency assertions;
no nondeterministic product-wrapper claim survives. Initial-import proof now uses only eligible raw local setup and the
facade's actual capture/consent/one activation attempt, never preinstalled capture or retry-until-success loops.
API24 exposed newer-platform MutableList.removeFirst resolution in a test supplier; removeAt(0) fixed it and the
required device gate reran. A misselected owned serial produced zero execution and was explicitly not a pass. Malformed
status cells and UUID cancellation boundaries received independent new tests after review corrections.

## Limits, retained risks and stop

No required R13c gate is unavailable. Synthetic SDK/service/root outcomes are not real Google, deployed HTTPS, installed
signing/audience, phone readiness, OEM/cloud transfer or background/process-death evidence. R13d still owns ordinary
foreground/edit/manual sync, confirmed ordinary rejection/stage recovery and combined personal-package real HTTP journeys;
R17a owns separately authorized live resources. No sign-out/switch/general chooser/deletion/export/scheduler was added.
The legacy accepted-auth replay limitation and earlier unexplained R12c real-HTTP Private stop remain unrepaired;
passing deterministic/current gates do not retroactively repair them. No expiry rounding, uncertain rekey or revocation
claim was introduced.

Owning implementation/architecture/product/testing documents replace stale current-state claims; this specification and
review are historical evidence. Owned emulator work is stopped, temporary synthetic AVD configurations are removed and
Gradle slots are released before final handoff summary. Scoped local commits only; no push or deployment. **Stop here.**
