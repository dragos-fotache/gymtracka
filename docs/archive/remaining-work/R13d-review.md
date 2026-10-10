# R13d review — accepted personal R13 closure

Accepted 2026-10-10 after renewed owner authority, independent production/test work, coordinator source/oracle review and fresh final-source package gates. R13a–c remain accepted dependencies; the finite personal R13a–d package is closed. This is not live readiness or public-scope acceptance.

## Delivered boundary

- Composition-owned `AccountSyncState` serializes bounded foreground/activation, successful finite-edit and manual demand on the exact current ACTIVE handle. Raw counters and covered watermarks are handle-scoped; entry demand survives cancellation retirement independently. Busy/private manual actions do not queue or wedge admission. Borrowed scope ownership is preserved.
- `FiniteEditCoordinator.committedEdits` advances only on settled successful normal/exclusive/terminal work. Matching runtime binding observation is event-driven, not polling. Lifecycle START/STOP/DESTROY and composition disposal fence admission and owned work; construction/startup recovery itself does no HTTP or Google UI.
- Account status offers manual synchronization, deliberate exact retained-stage restart and request-relative destructive recovery confirmation. Captured-value callbacks and turnover clearing prevent stale UI callbacks from acting on a later token/stage. Status/last-success is observation, not convergence authority.
- Ordinary preparation settles an already frozen head unchanged, never sends a pending successor, then strictly preflights eligibility before any real editor/timer drain. Clean, pending-only, malformed, import-only and settled-quiet refusals have zero per-preparation drain delta. Eligible preparation revalidates under real leases and issues an opaque registered owner/fence/exact-handle-bound, single-use confirmation.
- Confirmation compares complete ordered rows, epoch, exact ready authority and strict absent/settled legacy evidence. The same admitted run transactionally discards only captured ordinary intent, nulls its cursor while preserving recorded last-success and marks projection dirty. Full row comparison precedes captured deletes; final authority/legacy/process/session/epoch/caller/lease checks follow the final SQL. Trigger faults roll back. Original import source/entries/progress/receipts, credentials, catalogue and foreign scope are never discard targets.
- Complete snapshot recovery uses existing terminal capabilities and post-baseline push-before-pull. A real completed Tail with known newer pending intent reports `PROTECTED`/retry-required without publisher/drain, preserving the whole visible graph and exact newer row. Unknown/non-Tail remains fail-closed; only known clean tails reach existing publication fences.

Room remains14 with no entity/migration/exported-schema change. The bundled v1 asset is unchanged. DAO additions are two narrowly owned discard/cursor-write queries, not new schema or general authority.

## Independence and coordinator assessment

Worker changed production only; QA authored tests in the declared allowlists without inspecting production logic/diffs. Coordinator bridged APIs and reviewed both layers, including exact captured-row/token/legacy/graph oracles and the final corrected branches. QA disclosed one early filename-only grep over `main/`, with no content read; this metadata exposure is recorded rather than claiming zero directory access. No production-derived encoding oracle or forged recovery token was accepted.

Tests use real Room/vault/session handles and explicit controlled barriers, independent wire literals, hand-written operation fakes and virtual scheduling. The adopted-handle regression separates scripted direct state reads from backing StateFlow collection and proves Private refusal launches no H work before a genuinely later H commit. Expected refusal/cancellation `runCatching` assertions are distinct from best-effort teardown and bounds-only diagnostics; no catch authorizes recovery or suppresses required graph/row assertions.

## Fresh final-source gates

Coordinator independently parsed preserved native XML. All listed final gates have zero failures/errors/skips; repeated focused methods are not additional distinct coverage.

| Gate | Actual evidence | Preserved location |
|---|---|---|
| Production Kotlin/Room/KSP | Executed final `compileDebugKotlin`, successful | `/tmp/gymtracka-r13d-avds.EuDjYl/final-controller-compile.log` |
| Full offline JVM | 69 suites, **1076 tests**, unfiltered fresh execution | `/tmp/r13d-qa/final-JVM` |
| Full current default device | **1127 tests**, including38 R13d cases, no opt-in relay classes; owned emulator-5580/API37 | `/tmp/r13d-qa/final-default-device` |
| Actual Room/vault/HTTP | **2 exact nonempty journeys**, isolated backend/host TLS relay, final source | `/tmp/r13d-qa/final-relay` |
| Affected API24 | **160 tests /14 classes**, individually selected and verified on owned emulator-5582; deterministic Google adapter only | `/tmp/r13d-qa/API24-*` final-source directories below |
| Debug assembly/lint | Fresh package and lint analyze/report execution, successful; **0 errors,2 `NewerVersionAvailable` warnings** | `/tmp/gymtracka-r13d-avds.EuDjYl/final-package-lint.log`, `app/build/reports/lint-results-debug.*` |
| APK/config/assets/backup/security | Signature verifies; min24/target37; cleartext false; actual DEX BuildConfig endpoint/audience empty; host/JDBC/test-control markers absent; asset/backup unchanged | Safe audit `/tmp/r13d-qa/final-apk-config-safe-audit.json`; coordinator checks below |

Final API24 classes/counts: editor reauth2, legacy import activation26, prepared engine recovery9, R12c reconciliation recovery8/validation7/cache observation6/runtime timer6, Google identity adapter34, Room/vault reauthentication9, controller17, ordinary recovery18, flow3, startup root9 and status6. The four refreshed directories end in `-final`; use them, not the older controller16/recovery/flow/startup records. Status6 is the explicit post-timeout run.

Actual HTTP journeys prove foreground/edit/manual convergence, lost-response exact frozen replay and duplicate convergence; and distinct same-account replacement producing a real stale rejection followed by controller prepare/confirm recovery without rekey. Disposable setup provisions credentials only; domain outcomes come from the real service, not simulated data. No backend source/private configuration, personal credentials, payload/header logs or live Google operations were used.

## Audit details

Default APK has empty `GYMTRACKA_SERVICE_ORIGIN` and `GYMTRACKA_GOOGLE_SERVER_CLIENT_ID`, verified from encoded DEX static values as well as generated config. No `RealBackendFixture`, `RoomBackendRelayMain`, PostgreSQL, relay journey or `/__r10__/` marker is packaged in production. Default full instrumentation independently excludes opt-in classes. Test-only host/schema assets do not enter the production APK.

APK catalogue equals disk and accepted HEAD; SHA256 `defa08e9c464f90f0f48d8e3441fe20151803b48948647a91742c1bb82fa9f7d`. Manifest/dependencies/schema/backup inputs are unchanged. `allowBackup` remains true for ordinary data; the encrypted vault remains in platform-excluded `noBackupFilesDir`, with unchanged explicit legacy/cloud/device-transfer namespace exclusions. Packaged XML retains these rules and `usesCleartextTraffic=false`. Debug signature verification is not actual signing/OAuth/deployment certification.

The old Coordinator1461 unnecessary-safe-call warning is outside the R13d change; no unrelated cleanup was made. The new redundant repair predicate warning was removed without behavioral change before final gates. Language-server empty indexing and lexical balance were not treated as compiler/clean-diagnostics evidence.

## Historical failures and evidence limits

- Early production/test compilation and controller/UI failures were corrected under separately released exclusive Gradle slots; no implicit retry or concurrent source edits were allowed.
- Real UI confirmation initially delivered the exact second token but refused stale at a caught legacy-read failure. Durable rows/cache/cursor/journal were identical, with no ordinary retry/attempt bump. Strict synchronous cursor reads on Main outside a transaction were the cause. Transactional dispatch/atomic reading fixed it without weakening equality or fences. Flow runs1–7 remain under `/tmp/r13d-qa/R13dAccountSyncFlowTest-runN`; later successful runs are separate.
- Expanded recovery matrix exposed fixture baseline/facade errors, then five genuine unnecessary preparation drains. Correct zero-delta oracles exposed and verified strict preflight. A completed push-Empty/pull-Tail with outstanding intent was initially mislabeled pull-stopped; three-way classification fixed it without publication or pending-row loss. Recovery runs1–5 remain separate.
- Final production review initially described adopt/resolve self-healing, then verified legal same-handle ACTIVE return and StateFlow conflation could leave no edits observer. Coordinator required a real read-boundary regression, rejected real-turnover and erroneous manual-run fixtures, preserved native RED expected2/actual1 (`R13dAdoptedH-run1`), and verified GREEN (`run2`), full controller17 and fresh full-source gates. This is fixed, not a deferred probability note.
- One API24 CSV command silently executed only its first class. Coverage was never inferred from build success. Subsequent classes ran individually. An API24 status invocation exceeded1500s with only device-info and no XML/logcat: outcome remains **UNKNOWN**, not failed/passed retrospectively. Owned device/daemon checks found no continuing work; an explicitly regranted standard bounded run produced exact status6 native success. Safe diagnostic remains `/tmp/r13d-qa/API24-status-timeout-safe-diagnostic.json`.
- Ninth-slot intermediate filtered XML was overwritten; its counts were recorded contemporaneously but are not retained native XML claims. Final full69 JVM XMLs and final full device/HTTP/minAPI records own acceptance. Earlier-source full1076/current1126/HTTP2 records remain historical, not final-source substitutes.

No sleeps, shortened domain latency, rejection bypass, permissive barrier, row/count-only discard, fabricated convergence or weaker assertion was used to obtain passes.

## Residual boundaries and handoff

A commit landing after watermark coverage but before push claim can produce one harmless empty follow-up; durable work is not lost and publication stays guarded. Epoch movement before run admission refuses before effects and requires fresh retry. These are fail-safe consequences, not new authority. Same-handle observer repair is closed.

Legacy accepted-auth replay incompatibility and the earlier unexplained R12c real-HTTP Private stop remain explicit unrepaired risks; passing new journeys is not retrospective repair. Full minAPI, actual Google/audience/installed signing/trusted deployed HTTPS/phone/account journeys, process-death timer restoration, hosted CI, expanded R10b, lifecycle/public UI and WorkManager are not claimed.

R17a planning is dependency-ready but its external inputs and live operations require separate owner authorization. No push, deployment, private-config/signing/OAuth changes or personal account/device operations occurred. The archived [task](R13d-task.md) owns the normative scope; active architecture/product/testing/roadmap/handoff documents replace their current state rather than retaining this session history.
