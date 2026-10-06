# R08c acceptance — Complete canonical baseline, absence and publication recovery

**Accepted 2026-10-05.** [Normative specification](R08c-task.md).
Reviewed production/test content and the mandatory gates below close R08a–c. This is inert storage/driver/publication
recovery, NOT app activation or complete running-engine convergence. The owner explicitly requires stopping at R08c:
prepare durable handoff documentation and the scoped local acceptance commit, but launch NO successor coordinator and
perform NO next-slice work. No push/deployment/live account or personal-device action occurred.

## Delivered boundary

- `SnapshotDeliveryLoop.complete(run, previous)` requires a privately tracked actually issued terminal Downloaded result
  from THIS driver and the current exact capability on the SAME admitted run. Generic result shapes, storage-only
  terminal stages, durable restart/read observations, foreign/closed runs and repeated attempts cannot authorize a
  baseline. Eligible-attempt issuance consumption is atomic before Room admission; write-token retirement is separate
  and never success evidence. Concurrent duplicate attempts settle at most once.
- One Room transaction strictly validates the full frozen graph and retained stage/source/session/epoch/page/token/body
  evidence; verifies catalogue/custom/relationship dependencies, sole active workout, retained/incoming family/parent
  bindings and fail-closed revision consistency; installs whole canonical upserts and observed absence at epoch+1;
  preserves unobserved reservations/watermarks; establishes complete/dirty metadata and exact baseline cursor; preserves
  materialized epoch and last-success; removes only the selected stage/children. No partial visible publication or outbox
  rewrite occurs. Empty/old-unchanged accounts and later-page dependencies are valid.
- `PushRun.publishProjection(pairedStore)` reuses the SAME owner/run/handling Mutex and existing required barrier,
  verifies the physical database instance/account/session before lease acquisition, conservatively retires old process-
  local tokens, and adopts only returned active-resumption epochs. Direct publication blocks any selected durable stage,
  including corrupt/downloaded headers. ANY selected outbox protects the entire old/local graph.
- Clean publication retains existing full graph/global PK/family/reference collision checks and atomic materialization,
  advancing epoch again. Collision/failure preserves an already committed dirty baseline and old projection. Durable
  complete dirty baselines recover under a fresh run at their actual epoch without redownload; clean postcommit recovery
  is UNCHANGED. Retained downloaded stages are still explicit abandon/NEW-stage restart, never completion readoption.
- Real tests distinguish rollback from lost result AFTER commit: a separately completed Room transaction stands while a
  queued caller resumption is canceled, with no delivered baseline result; durable-epoch publication then succeeds.
  A lease.close failure after publication likewise leaves a committed clean epoch, and fresh retry is UNCHANGED.
  Stage absence/retired tokens/thrown results never prove a specific call succeeded or rolled back.
- Completion/publication intentionally preserve source last-success. A frozen baseline is not a post-baseline valid
  pull tail; future orchestration reuses R07's actual tail rather than inventing a snapshot success/convergence timestamp.

Production changes: four existing files (`PushStore`, `ProjectionStore`, `SnapshotDeliveryLoop`, `SyncCanonicalDao`) and
new focused `SnapshotBaselineResult`/`SnapshotBaselineStorage`. Independent tests: `SnapshotBaselineTest` (10 methods),
`SnapshotBaselineFenceTest` (12 methods), shared test-only support. No new owner/API/codec/entity/schema/asset/build/
manifest/production activation path. Room remains11 with20 entities; exported schemas1–11 remain unchanged.

## Independence, review and corrections

Fresh native Pi exact coordinator `openai-codex/gpt-6.1-sol`/high; fresh independent Worker/QA both
`meta/muse-spark-1.3-contributor`/high. Worker inspected/changed only allowed production; QA authored expected literal
values/oracles from the spec and allowed test harnesses without production declarations/implementation/diffs.
Coordinator reviewed the entire tracked/new-file production and test content, supplied missing contracts, integrated
focused corrections and owned EVERY explicitly serialized Gradle slot. No waits/acknowledgment polling, model/gate
substitution, production-derived expected encoder bytes or weakened assertions.

Important review findings resolved before acceptance:

- Worker initially retired terminal issuance only after graph/revision validation and did not retire old tokens on
  coordinated publication. Corrections consume issuance at the first eligible boundary (requiring successful private
  removal under the existing Mutex) and conservatively retire prior tokens before paired publication. No test source
  was disclosed to Worker. Initial production compile passed but warned about a redundant Elvis; it was removed and
  later compilation was clean apart from existing framework warnings.
- Initial Android-test compilation FAILED: missing `GymDatabaseTest` import caused unresolved `database` cascades in
  the fence class. Failure log was retained; corrected compile actually executed and passed. No device test executed
  during that failing compile attempt.
- Test review corrected preservation baselines taken before legitimate download, invalid DELETE snapshot seeding,
  capture-before-begin bypass, wrong-run/driver masks, session/epoch/fixture-scope mistakes, invalid corrupt-stage cleanup
  assumptions and raw Int/Long count mismatch. Whole-domain protection and exact frozen label-field oracles replaced
  insufficient workout-only/substring checks. Valid family/revision rejection cases no longer also violate unrelated
  graph rules. Late SQL failure/cancellation exercise installed upserts AND observed absence, with complete rollback.
- Initial focused 10/12 runs passed, but review still strengthened following-query cancellation and strict corrupt-read
  rejection. Canceling IN a before-SQL callback is not postwrite evidence. The final callback fires only when prior
  install+absence observations were already established; its firing query is explicitly distinguished from the absence
  statement. Coordinator restored the accidentally omitted corrupt-COMPLETION oracle before the strict corrupt READ.
- That small coordinator restoration initially omitted a local `terminal` binding: its affected-method command FAILED
  at Kotlin compilation, not device execution. This coordinator-owned error is retained, not attributed to QA or hidden.
  Binding fixed; both affected methods then separately executed/passed, followed by the FULL final unfiltered gate.
- Only archive-path KDoc text changed after the full device gate, without behavior/assertion changes; postarchive
  compilation/offline JVM/lint checks are recorded below. No old/filtered/cached task is labeled a new full test pass.

## Environment and evidence ownership

Base Android HEAD `fc0744d0468170618b119acf558817823c439787`, parent accepted R08a
`5c3c50d95c2d596b39c77b49973f55c927820fd4`. Accepted progressive-doc/R07/R08 ancestry was verified and never amended or
rewritten. Shared HEAD `31c969f261bd3f0fe662e633c0dfffecb3c08393` unchanged; both trees/indexes/all untracked were clean at
pickup. Subsequent changes were checked for scoped ownership before staging/commit.

Linux amd64; Gradle9.8.0, launcher JVM27, pinned compatible Java25 daemon (existing Eclipse Adoptium daemon preserved).
Test-owned `r08c-test`, serial `emulator-5580`, API37/Android17, installed 37.2 `google_apis_playstore_ps16k/x86_64` image,
private Android/AVD home and KVM/software GPU. Default ADB server was not restarted; animations changed only on this guest.
Instrumentation was explicitly restricted to that owned serial; no personal device used.

Isolated backend launcher built the JAR under its existing Maven lock and pinned-input check, started disposable
PostgreSQL16 and the loopback backend, generated test-only secrets and published exclusive mode600 ephemeral config.
Only Android fixture/HTTP gates were run: no backend source/suite/private configuration/personal databases reviewed or
changed. Credentials/config contents were not copied into evidence. Existing guarded TLS/JDBC helpers remained test-only.

Execution receipts/logs/XML are preserved under `/tmp/gymtracka-r08c-JyuFf8` (ephemeral local evidence, not assumed live
resources or a replacement for this checked-in review). All actual nested testcase names/counters were inspected.

## Actual gates

Each Gradle invocation had a coordinator-exclusive slot, no contributor builds authorized. `flock -n
/tmp/gymtracka-r08c-gradle.lock` guarded implementation/final gates; the initial preflight also had explicit exclusive
ownership. Commands below ran from the Android root; test-task `--rerun` reruns intended tests, not all dependencies.

| Gate / actual command | Actual result |
|---|---|
| `./gradlew :app:compileDebugKotlin --console=plain` | Executed KSP/production compile; passed. Redundant Elvis warning subsequently corrected; existing join-table FK-index warnings only. |
| `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --rerun --console=plain` | Corrected compilation and FULL OFFLINE test task executed: **812 testcase nodes / 49 classes**, failures0/errors0/skips0; backend package excluded. |
| `./gradlew :app:compileDebugAndroidTestKotlin --console=plain` | Initial import failure retained; corrected KSP/Android-test compilation executed and passed. Internal constructor negative-test visibility confirmed by actual compile, no alternative gate. |
| `ANDROID_SERIAL=emulator-5580 ./gradlew :app:connectedDebugAndroidTest --rerun -Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.SnapshotBaselineTest --console=plain` | **10** actual cases, failures0/errors0/skips0; focused, not full. |
| Same command with class `...SnapshotBaselineFenceTest` | **12** actual cases, failures0/errors0/skips0; pre-final-oracle refinements, not final full evidence. |
| Separate `...SnapshotBaselineFenceTest#postwriteCancellation_rollsBackAndRetiresResult_freshRecovery` and `#sourceSessionEpochCorruption_fencesBeforeEffects_redactionHolds` filters | Initial coordinator integration compile failure retained; after fix each affected method executed **1** case, all0. Repeats are NOT added to distinct totals. |
| `ANDROID_SERIAL=emulator-5580 ./gradlew :app:connectedDebugAndroidTest --rerun --console=plain` — NO filter property | **FULL UNFILTERED 671 distinct testcase nodes / 56 classes**, failures0/errors0/skips0, successful task/shell exit. Includes final new22, R08b driver/fence, existing staging/pull/shared driver/publisher/HTTP/vault/persistence/Compose/app flows and migrations. |
| `./gradlew :app:assembleDebug --console=plain` | Explicit assembly passed; changed debug packaging/dex tasks executed. Subsequent combined assembly/lint reused this identical production APK. APK inspected, not merely found on disk. |
| `./gradlew :app:assembleDebug :app:lintDebug --console=plain` | Lint analyses/report/task executed and passed; **0 errors, 1 existing NewerVersionAvailable warning** in `gradle/libs.versions.toml`. Assembly dependencies/aggregate were up-to-date at this repeat. |
| Isolated `:app:testRealBackend --tests '...RealBackendPullSnapshotTest' -Pgymtracka.realBackendConfig=<owned ephemeral config> --console=plain` | Preflight ONLY: **3 / 1 class**, all0; not the full acceptance gate and not added to its count. |
| `./gradlew :app:testRealBackend -Pgymtracka.realBackendConfig=<owned ephemeral config> --console=plain` — NO filter | Actual FULL isolated package task executed: **56 testcase nodes / 10 classes**, failures0/errors0/skips0; cache disabled by existing task. No offline test-task dependency. |
| Both repository `git diff --check`, new-file whitespace, scope and local Markdown links/anchors | Passed after closure updates; final staged diff checks and local commit verification follow workflow, without blanket staging/amend/push. |

The full device run freshly executed four migration methods, not an invented per-loop test count:
`everySupportedVersion_migratesToCurrentSchemaAndPreservesAvailableData`,
`populatedVersionNine_upgradesToTenWithLedgerReservationsAndNoFabrication`,
`bundledVersionOneAsset_migratesWithCatalogueIntact`,
`populatedVersionTen_upgradesToElevenWithStagingEmptyAndPriorBytesPreserved`.
The unchanged vault class contributed27 cases; the full run also included both Compose/app-flow classes. No schema change
was made; these are fresh regressions of existing migrations, not new migration acceptance.

### APK and preservation checks

`aapt2 dump permissions/xmltree`, ZIP/DEX descriptor checks, packaged DB hash/read-only SQLite version and source/diff
checks verified required INTERNET/VIBRATE/WAKE_LOCK (plus existing generated nonexported-receiver permission),
`usesCleartextTraffic=false`, unchanged backup/data-extraction vault exclusions, packaged source DBv1 and attribution
notices. Checked R08/test/backend fixture and PostgreSQL/JDBC driver descriptors/config/session-file names were absent.
This is a scoped packaging check, not a full release/security/secrets audit.

- Debug APK SHA256 `1ef2282ca4bc99318c0802ef9f9c9bcb413f88a185833af7ec7f302fe7364e75`, bytes22,492,513, 17 DEX files.
- Bundled source/packaged DB SHA256 `defa08e9c464f90f0f48d8e3441fe20151803b48948647a91742c1bb82fa9f7d`, user_version1.
- Exported schema11 SHA256 `b05f665c9ed1e63521f575b6bd827d50c424577d4706b1bcd5b00e3fd42f666d`; all exported1–11
  unchanged, as are existing entities/build/manifest/backup rules and shared repository.

### Postarchive metadata-only checks

Final archived-spec KDoc path rewrites have no behavior/oracle changes. Actual closure command:
`./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest --rerun :app:assembleDebug :app:lintDebug --console=plain`.
It passed with Android-test KSP/compilation and test lint analysis EXECUTED; unchanged production compilation/assembly
and main/unit lint analyses reused valid outputs. FULL OFFLINE JVM task EXECUTED again: **812 / 49 classes**, all0.
Final lint findings remained0 errors/1 existing warning; aggregate report was up-to-date. APK hash stayed identical.
Postarchive XML/logs were preserved separately. The full device/backend evidence above belongs to the accepted same
production and assertion content; neither is misrepresented as another postarchive run.

## Unverified boundaries and stop

No unavailable REQUIRED R08c gate remains. Kotlin LSP stayed initializing during bounded navigation attempts; exact
allowed-file reads and actual compilation were used, NOT a clean-diagnostics claim. Launcher scripts were unchanged,
so no new launcher-regression pass is claimed. Existing framework/JDK/AGP warnings were retained, not suppressed.

NOT verified: API24, hosted CI, deployed TLS/phone connectivity, successful live Google, real account/device journeys,
durable Room-vault journal/authority, real edit/timer barriers, two-store Room-backed engine convergence, lifecycle or
app activation. R09/R10/R12 and later milestone gates remain; isolated HTTP fixtures do not substitute for them.
Strict legacy accepted-auth replay remains unresolved: original uncertain grant IDs/bytes must never be rounded,
rewritten/rekeyed, broadly revoked or used to infer credential/cache cleanup.

Owning architecture/design/implementation/testing/roadmap/handoff documents were replaced in place, not appended as a
session log; spec/review archived. Future coordinator uses minimal read-only pickup and then concrete task-specific
source/contract/harness reading after explicit release. R09 is dependency-ready but NOT started here.

Owned emulator/private Android/AVD home and isolated backend/container/config are stopped/removed, temporary backend
pane closed. Existing default ADB/Gradle/Kotlin daemons and unrelated panes remain. Worker/QA are completed/frozen and
hold no resource/Gradle slot. No successor coordinator was launched; current coordinator stops after scoped local
acceptance commit and durable handoff. No pushes or history rewrite.
