# R10a acceptance — core engine convergence and recovery

**Accepted 2026-10-07.** Personal Core R10 convergence gate delivered. Expanded R10b remains deferred public;
this does not activate the application, complete all R10, or authorize Google/phone/deployment/personal-data actions.

## Delivered boundary

Two independently created, file-backed Room12 stores use transactional `GymRepository` capture, actual delivered
push/pull/snapshot/baseline/publication drivers and production `HttpGymSyncApi`/`OkHttpServiceTransport` through verified
local TLS to a disposable real service/PostgreSQL16. Stores do not copy databases or feed successful DTO fixtures to the
engine. The test-only composition does not introduce a new production engine or construction path.

Fresh native Worker and QA used `meta/muse-spark-1.3-contributor`/high with disjoint production/test access. Worker source-only
review found no production defect or required change. QA authored independent scenario oracles using the normative
packet/contract and allowed test support, without production source access or Gradle execution. Coordinator reviewed
production seams/DAO predicates and every new/changed test, host and build file, supplied missing API facts, corrected
fixture assumptions via the same QA session, and executed all gates in explicit serialized slots. No unresolved finding.

The opt-in `gymtracka.roomBackend=true` source set adds only external Android tests; default JVM/device runs remain
offline. Host fixture/JDBC/config/key helpers stay JVM-only. `prepareRoomBackendRelay` writes a JVM launch classpath;
the long-lived relay runs outside Gradle and holds no build slot. Guarded synthetic credential setup is the only SQL
write; domain operations go through HTTP. Relay metadata contains only a public certificate/port, uses exclusive
mode600 creation and identity-checked cleanup. Normal hostname verification remains enabled.

## Independently expected outcomes

Final external package executed exactly these five methods, with no failure/error/skip:

| Class / method | Distinct outcome |
|---|---|
| `RoomBackendBootstrapTest.emptyAndPopulatedBootstrapOfflineReopen` | Real empty baselines on both stores; offline custom/routine-draft/workout graph capture; pending→frozen failure without changing identity/wrapper/generation; exact post-failure outbox across reopen/new owner; deterministic backoff admission; real reconnect and paged populated peer bootstrap. Literal names/status/time/rest/set/null/relationship values, original workout/routine child IDs, live-root counts and positive canonical revisions are independently asserted. |
| `RoomBackendLostResponseTest.lostResponseExactReplayActiveReplacement` | One populated active autosave commits upstream but its definitive response body is withheld until bounded client timeout. Frozen exact bytes survive reopen. Peer observes the committed autosave and starts a sole-active replacement without explicit prior deletion. Unchanged retry is actual DUPLICATE with the original upsert revision; complete pull retains the newer replacement/delete, not the old winner. Explicit workout/descendant and freed-custom deletion converges to an empty live graph. |
| `RoomBackendNewerIntentDuringPullTest.newerLocalIntentDuringActualPull` | Hold an actually received real pull response, then accept a newer local edit on the same already-visible root. Remote canonical name/notes apply while PROTECTED publication preserves the entire local graph and exact pending intent. Subsequent real push/pull/publication converges both to independently expected later local values with stable children. |
| `RoomBackendFrozenSnapshotTest.frozenSnapshotPostBaselineCatchup` | Limit1/maxPages1 freezes a multi-family graph; a separately launched sibling peer commits a rename after page1 while the snapshot retains one uninterrupted run. Completion still contains the original frozen name; actual post-baseline pull supplies the later name before publication. Staging is not visible. |
| `RoomBackendRecoveryAbsenceTest.interruptedExpiredRecoveryAbsence` | Scripted explicit cursor expiry and middle-page transport failure preserve complete prior known/extra/custom details, cache and checkpoint. File reopen retains observation-only staging; same-run fresh observation/explicit abandon then NEW real snapshot recovers. Actual delete observer retains DELETE/incremented revision; snapshotter retains snapshot_absent/original revision, without inventing deletion evidence. Original surviving child IDs and removed descendant IDs are checked. |

Both-store quiescence checks require actual push Empty/valid Tail, false outstanding work, empty outbox/staging,
complete baseline, clean projection, matching materialized/cache epochs, nonblank cursor and last-success. Peer equality
alone is not an oracle; local generations/epochs and DELETE versus snapshot_absent evidence intentionally need not match.
No expected wire bytes are derived from a production encoder. Existing all-kind, rollback/fence/cancellation, codecs,
Keystore/session, migration and Compose coverage is reused rather than reproduced exhaustively.

## Executed verification

Environment: Linux x86_64; Gradle9.8, launcher JDK27/daemon JDK25; fresh test-owned Android17/API37 emulator with
16384-byte pages and isolated Android/AVD homes. Disposable service was freshly built/started using the existing
isolated launcher, with generated test-only keys/credentials. Backend access was operational build/start and black-box
HTTP, not source/private-configuration review or Maven-suite execution. No personal AVD/account/database/device used.

Commands below summarize final executed gates; public certificate/port inputs are transient, not committed:

```sh
python3 scripts/run-isolated-backend.py --backend-root /home/dragos/IdeaProjects/gt-backend \
  --config-path <exclusive-test-config> --port <owned-loopback-port>
./gradlew :app:prepareRoomBackendRelay --console=plain
java -cp "$(< app/build/r10/relay-classpath.txt)" \
  com.dragosfotache.gymtracka.data.sync.backend.RoomBackendRelayMain \
  <exclusive-test-config> <exclusive-public-relay-metadata>
./gradlew :app:compileDebugAndroidTestKotlin -Pgymtracka.roomBackend=true --console=plain
./gradlew :app:connectedDebugAndroidTest -Pgymtracka.roomBackend=true \
  -Pandroid.testInstrumentationRunnerArguments.package=com.dragosfotache.gymtracka.data.sync.roombackend \
  -Pandroid.testInstrumentationRunnerArguments.roomBackendPort=<owned-port> \
  -Pandroid.testInstrumentationRunnerArguments.roomBackendCertificate=<public-DER-base64> \
  -Pandroid.testInstrumentationRunnerArguments.timeout_msec=120000 --rerun --console=plain
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --rerun --console=plain
./gradlew :app:testRealBackend -Pgymtracka.realBackendConfig=<exclusive-test-config> --console=plain
./gradlew :app:assembleDebug :app:lintDebug -Pgymtracka.roomBackend=true --console=plain
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.timeout_msec=120000 --rerun --console=plain
python3 -B -m unittest discover -s scripts/tests -p 'test_*.py'
git diff --check
git -C shared-docs diff --check
```

| Gate | Actual final result |
|---|---|
| Enabled Kotlin/KSP compilation | New external sources actually compiled successfully; main compilation also checked. |
| External real Room/HTTP | 5 distinct classes/methods, 0 failures/errors/skips; final rerun after redaction/full-graph oracle corrections. |
| Full offline JVM | 985 cases / 58 classes, 0 failures/errors/skips; test task freshly executed, compilation reused where valid. |
| Full isolated HTTP/auth/host controls | 58 cases / 11 classes, 0 failures/errors/skips, fresh external execution. Includes 2 host-control tests for occupied-metadata refusal before fixture access and token-specific one-shot real-commit/lost-outcome/duplicate replay. |
| Full ordinary instrumentation | 848 cases / 76 classes, 0 failures/errors/skips; final unfiltered run after opt-in wiring correction, with no external package discovery. Includes existing Room/migration/HTTP/session/Compose/app flows. |
| Assembly/lint | Successful unsuppressed main/unit/device analysis with both contributors source-frozen. One existing `NewerVersionAvailable` warning for org.json; no new lint issue. Assembly dependencies reused where up-to-date. |
| Packaging/preservation | App DEX contains no Core test client/control route, host fixture/relay/JDBC helper. Opt-in test APK contains its required TLS/control client, not host fixture/JDBC. Final default test APK also excludes the Core client/routes. Packaged source v1 asset is byte-identical; all main sources and schema snapshots unchanged, Room stays12. |
| Python launcher | 11 tests passed. |
| Documentation/scope | Relative links/anchors and both-tree whitespace checked; shared repository unchanged; no new dependency, manifest/backup, asset/schema or production construction change. |

XML case names/counts/failures/errors/skips were inspected, not inferred from Gradle success. Full and scoped outputs
were preserved separately before subsequent runs replaced reports. Repeated executions do not add distinct test counts.
Explicit external inputs are required by the enabled test helper; missing inputs fail rather than skip.

## Findings resolved and limitations

- Initial java-directory opt-in wiring did not compile Kotlin under AGP9.4; an up-to-date task was not treated as new-source
  evidence. Kotlin-directory wiring and actual class/method execution closed the gap. Leftover fixture imports were
  corrected before the passing enabled compile.
- Prototype orderly EOF/header-start loss controls produced malformed content (and dynamic HTTP2 header-start handling
  did not apply the intended effect). The final control preserves real headers and withholds only the definitive body
  after complete upstream success; normal bounded read timeout yields actual Transient uncertainty. Production was not
  weakened to retry malformed evidence. Failed prototype gates were retained during diagnosis; final adapter and Room
  replay gates passed unchanged safety behavior.
- Fixture assumptions about pending→frozen state, stop-as-return versus throw, sole-active deletion, cross-run stage
  capabilities, nested cross-store runs and DELETE versus snapshot_absent revisions were corrected, not accommodated by
  production changes or weakened expected outcomes. Final credential displays are redacted.
- An initially overbroad APK scanner rejected the expected opt-in test-client control route. Final policy distinguishes
  test client from host/JDBC helpers and production; both enabled/default artifact boundaries were inspected.
- mcpls remained initializing during bounded navigation attempts; allowed-file reads and actual compilation supplied
  verification. No clean-LSP-diagnostics claim.

Restart means real file close/reopen with new owner/repositories, not actual app-process death. Offline/retention/middle-page
faults are narrow client scripts; successful pages/outcomes are real HTTP. No real 90-day pruning, server clock control,
Google identity, deployed TLS, API24, hosted CI, owner phone/laptop journey, production session activation or canonical
app/timer reconciliation was verified here. Those remain R10b/R09h/R12c/R11/R17 obligations at their declared milestones.
The legacy accepted auth-replay precision limitation remains unrepaired. Legacy local-only data disposition remains an
owner decision; no import/deletion/reassignment is inferred from this gate.

Core R10a is closed at this boundary; required package gates are not left unverified. Owned service/relay/emulator/build
resources are released before coordinator transfer. No pushes/deployment/personal configuration or activation authorized.
