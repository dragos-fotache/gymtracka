# Android testing

Tests are split by runtime and external dependencies. The commands below describe the checked-in test setup;
they are not a record of results for a particular checkout.

## Test layers

| Source / task | What it exercises | Requirements |
|---|---|---|
| `app/src/test/` / `:app:testDebugUnitTest` | Models/codecs, formatting, state holders, HTTP/session faults, deterministic races | JVM; no external service configuration; scripted local HTTP/TLS is allowed |
| `app/src/androidTest/` / `:app:connectedDebugAndroidTest` | Real Room, migrations, Android parser/Keystore/identity boundaries, Compose and app flows | Emulator or test device |
| `app/src/test/.../data/sync/backend/` / `:app:testRealBackend` | Production sync/auth interoperability against an isolated service | Explicit isolated config, service and disposable PostgreSQL |
| `app/src/roomBackendTest/` | Independent file-backed Android Room/vault stores through the real engine and service | Opt-in source set, test emulator, host relay and isolated service |
| `scripts/tests/` | Isolated launcher resource/configuration safeguards | Python unittest |
| `shared-docs/catalogue/` | Catalogue/asset parity | Shared package and Python; separate from Android tests |

Ordinary JVM tasks exclude the backend package. The real-backend task reuses the JVM compiled classes and classpath,
does not depend on running the ordinary suite, always executes against external state, disables result caching, and
fails when config/tests are missing. The `gymtracka.roomBackend=true` flag adds its source only to `androidTest`;
without it, default device tests do not contain the external Room/service journeys.

## Coverage map

| Area | Existing test coverage |
|---|---|
| Domain persistence | Finish cleanup/validity/renumbering/rollback, cascades, history defaults, cloning, replacement identity, routines and custom guards |
| Migrations | Every starting schema 1–14 to 15, copied v1 catalogue asset, additive sync-table preservation, exact prior values/requests/sequences |
| Feature state | Validation, locale input, autosave order/failures, accepted drafts, late loads, filters and naming |
| App and timer | Active discovery/replacement, shared finite admission, transition/publication drains, terminal at-most-once writes, clocks and hardware |
| Compose/app flows | Catalogue/picker/custom/routine/set/history controls, Settings selection/sections/large-text scrolling, startup/privacy, actual feature factories, focus/dialog preservation and timer/editor barriers |
| Workout presentation | Four-column layout, editing completed sets, timer actions at narrow widths and enlarged text, reference screenshots from real in-memory Room fixtures |
| Workout landing | Title/detail navigation, independent inline routine actions, starting from a template, shared icon navigation and 320dp/double-text controls |
| Wire and HTTP | Duplicate raw keys, family identity, exact durable bytes, auth scalar/expiry rules, origin/TLS/redirect/retry refusal, framing/UTF-8/cancellation |
| Canonical/outbox | Watermarks, frozen-head replay, outcomes/retry, atomic acknowledgment, newer intent, strict retained evidence, transaction rollback and reopen |
| Pull and snapshots | Complete checkpoint transactions, opaque progress, budgets, frozen stages, terminal issuance, absence, explicit restart, stale capabilities and durable recovery |
| Publication | Whole-account outbox protection, complete graph and physical collisions, generations, epochs, final-write rollback, process fences and canonical timer impact |
| Sessions and credentials | Vault codec/CAS/crypto, Keystore/file placement, pending-first refresh, Room journal gaps, same-account replacement, retained uncertainty and offline access |
| Foreground account flow | Google SDK request/parser with injected launch, exact exchange retries/budgets, consent, private import/activation, status, foreground/edit/manual demand and ordinary recovery |
| Isolated service | All 17 command kinds/imports, complete outcomes, replay, isolation, pull/snapshot, challenge/refresh/logout, invalid Google-proof rejection |
| Room/service composition | Lost responses, file reopen, snapshots, newer intent, scoped runtime, import/activation and foreground/recovery across independent stores |

Injected Google launch/parser tests do not contact the real provider. Synthetic service credentials and scripted faults
do not establish deployed signing/TLS, actual process death, service retention pruning, or unattended phone behavior.
Remaining verification areas are collected in [future improvements](future-improvements.md#broader-integration-and-device-verification).

## Test conventions

Use real in-memory Room for DAO/repository transactions, queries, cascades and aggregate behavior. Use file-backed
Room/vault stores for reopen/durable recovery. Do not mock DAOs. State-holder tests use hand-written narrow fakes,
`TestScope`, virtual time and deterministic gates rather than sleeps.

Expectations must be independent of the production encoder/behavior being checked. Exercise observable invariants:
durability, exact retry identity, account/privacy fences, rollback, cancellation, and preservation of newer edits.
Use stable accessibility/test semantics rather than incidental Compose layout. Repository/scope/clock/UUID/hardware
injection keeps production constructor paths usable.

Every schema change needs KSP export, a data-preserving migration, supported-start coverage and a copied real asset.
The schema fixtures and their provenance are described in [app/schemas](../app/schemas/README.md).

## Commands and reports

Run from the repository root:

```sh
./gradlew :app:compileDebugKotlin --console=plain
./gradlew :app:testDebugUnitTest --console=plain
./gradlew :app:compileDebugAndroidTestKotlin --console=plain
./gradlew :app:assembleDebug :app:lintDebug --console=plain
./gradlew :app:connectedDebugAndroidTest --console=plain
```

The connected task needs an available emulator/device. Select a test device explicitly when several are attached.
`WorkoutAppearanceTest` checks workout editing and timer controls at normal/narrow widths and 150% text. It writes
PNG captures under the target application's private `files/workout-appearance/` directory for visual review; these
captures are verification artifacts rather than golden-image assertions.
`WorkoutLandingTest` checks the landing-page title, chevrons, edit/start/create routing and shared navigation, including
320dp width with 200% text. It writes collapsed, expanded and enlarged-text PNGs under `files/workout-landing/`.
Focused examples:

```sh
./gradlew :app:testDebugUnitTest \
  --tests 'com.dragosfotache.gymtracka.ui.util.WorkoutFormattersTest' --rerun --console=plain

ANDROID_SERIAL=TEST_EMULATOR_SERIAL ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.gym.GymDatabaseMigrationTest \
  --rerun --console=plain
```

Task-scoped `--rerun` executes the selected task while retaining cached compilation dependencies.
A green shell exit alone does not prove that device tests ran. Check nonempty XML, actual class/method names,
failures/errors/skips, and the selected device. Use a single class or `Class#method` per class filter and inspect
what executed. Filtered execution covers only that selection. Later runs can replace reports at the same paths.

| Output | Path |
|---|---|
| JVM XML / HTML | `app/build/test-results/testDebugUnitTest/`, `app/build/reports/tests/testDebugUnitTest/` |
| Device XML / HTML | `app/build/outputs/androidTest-results/connected/`, `app/build/reports/androidTests/connected/` |
| Backend XML / HTML | `app/build/test-results/testRealBackend/`, `app/build/reports/tests/testRealBackend/` |
| Lint | `app/build/reports/lint-results-debug.xml` |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |

Room/KSP reports missing foreign-key indexes on catalogue join tables. Inspect diagnostics separately from task success;
those warnings do not suppress new failures. Documentation validation checks relative links/anchors, source references,
whitespace and scope with `git diff --check`; it does not require executing Android tests.

## Settings checks

`SettingsTabTest` exercises all four tab selections with a running timer, pill resumption, preserved workout/set data,
terminal rest persistence, offline presentation without a configured checker and scrolling at 200% font size.
`GymAppStateTest` additionally uses virtual time to verify continued countdown/wake-lock ownership and exactly one
terminal rest write across Settings navigation. `ServerConnectionStateTest` uses virtual time and
cancellation-resistant gates for entry/recheck, timeout,
duplicate refusal, retirement, stale results, disposal and borrowed-scope cancellation.
The connection state tests also cover a sync failure retiring an older probe, coalescing fresh checks and refusing
checks outside foreground Settings. `AccountNetworkActionsTest`
checks connection/authentication/competing-operation availability without treating reachability as account authority.
`HttpServerCheckerTest` uses actual local TLS for the exact credential-free request, strict expected 401 envelope,
malformed/status/media/framing failures, redirect/TLS refusal and actual Call cancellation.

`AccountStartupRootTest`, `AccountStartupReopenTest` and `AccountSyncFlowTest` exercise the actual admitted account root
through `nav-settings`. The sync flow checks reachability transitions and button gating, real account reauthentication
requirements with Connected retained, cancellation after reachability loss, and continued editor-triggered sync after a
cached Unreachable result. Its timer journey checks that in-flight probe cancellation/tab switching preserves timer
identity and once-only elapsed rest. `ServerConnectionSectionTest` drives a lifecycle owner through stop/start and
composition disposal/re-entry to verify automatic checks and cancellation through the actual screen integration,
including Online/Offline/Checking labels and the status dot in place of a progress row.
The sync flow also checks fresh reachability after a retryable HTTP/transport failure, disabled actions while checking
and offline, repeated identical failures, permanent errors retaining Online, and explicit Check restoring actions
without treating initial/repeated Online checks as reconnection. Confirmed Offline→Online transitions
are separately covered by the real-backend reconnect regressions.

To capture synthetic-fixture screenshots for visual inspection, add
`-Pandroid.testInstrumentationRunnerArguments.settingsScreenshots=true` to a focused `SettingsTabTest`,
`AccountStartupRootTest` or `AccountSyncFlowTest` run. Retain the APK after execution with
`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` to retrieve the cache images.
Images are written to the test target's cache as `settings-*.png`; they contain test data, not live account evidence.
`AccountStatusPresentationTest` covers clean/unknown queues, retained errors, incomplete stops, authentication
classification, concise Sync failed messages and distinct recovery messages. `AccountStartupStateTest` checks selective
clearing after actual sync success. `AccountSettingsPresentationTest` checks the rendered metadata, separate signed-in
status/authentication messages and conditional rejected-change controls. `AccountSyncFlowTest` additionally checks failed authentication followed by a
later successful manual sync, and the real captured warning/keep/discard journey. Real Room ordinary-recovery tests
cover advisory eligibility, typed empty/blocked/failure outcomes, credential stops and unchanged confirmation fences;
controller integration tests exercise those preparation outcomes and selective error clearing with real facade handles.

`AccountStartupStateTest` covers saved activation without sign-in, fresh operations for repeated private sign-in,
fresh post-grant activation expectations, duplicate operation refusal and blocked import preservation.
`ForegroundSignInContextTest` checks fresh-operation and foreign-origin admission. `ForegroundSignInHandoffTest` uses
real Room/Keystore vaults to exercise PREPARING/TRANSITIONING repeated sign-in, unchanged exchange replay identity,
queued intent and exact archived credential evidence, wrong-account/origin/retired-request refusal and strict saved-grant
resume. `AccountStartupRootTest` drives a foreign grant refusal followed by successful repeated same-account sign-in
through actual controls. `AccountStartupReopenTest` closes/reopens file-backed Room and the vault, then presses Finish
signing in with foreground sign-in unavailable and checks the saved identity/grant through complete activation.

The Settings plan records actual checks and limitations for [Slice 1](implementation/settings-tab.md#slice-1-verification),
[Slice 2](implementation/settings-tab.md#slice-2-verification), [Slice 3](implementation/settings-tab.md#slice-3-verification)
and [Slice 4](implementation/settings-tab.md#slice-4-verification).

## Android and coroutine fixtures

- Compose tests use `createComposeRule`, one content installation, bounded `waitUntil` on actual UI/Room outcomes,
  and `runOnIdle` for callback assertions. Idle alone does not prove a Room write completed.
- Acquire drains asynchronously when completion needs the test scheduler. Do not block the Compose thread with
  `runBlocking` on work that requires it. Dispose composition and cancel/join owned scopes before closing Room.
- Google adapter tests construct on Android Main. Virtual-time tests substitute coroutine Main independently of real
  Activity lifecycle operations. Cover resistant late SDK work and already-queued caller return separately.
- Real Room/Keystore/barrier handoff tests need normal scheduling and finite timeouts; virtual auto-advance across
  real I/O can manufacture auth budget expiry.
- On API 24, list-backed suppliers use `removeAt`/iterators rather than newer platform `removeFirst/removeLast`
  members. A successful current-device run does not establish minimum-API compatibility.
- Preservation assertions snapshot after allowed setup/capture and before the tested operation. Query callbacks
  run before SQL; post-write cancellation checks must gate a following query or hold result delivery after commit.
- Prove rollback separately from committed-but-cancelled return. Inspect durable rows and reread with fresh authority.
  A consumed token or failed lease close alone does not establish rollback or success.
- Coroutine exception identity belongs in the actual caller; async stack recovery can copy exceptions. Preserve the
  intended child/run context, gate actual entry, and join cancelled children before asserting final state.

## Isolated real-backend tests

[run-isolated-backend.py](../scripts/run-isolated-backend.py) builds/starts a disposable backend with labelled
PostgreSQL 16, generated test keys, loopback-only HTTP and an exclusively created mode-600 config.
It needs the backend checkout's Java/Maven environment plus Docker and `flock`.
Use a disposable database; the fixture rejects personal/nonloopback identities and unsafe config ownership/mode.

In a separate foreground terminal, replace the backend path with the intended checkout:

```sh
python3 scripts/run-isolated-backend.py --backend-root /absolute/path/to/gt-backend
```

The default config is `/tmp/gymtracka-real-backend/config.json` and port is 18080.
`--config-path` / `--port` select alternatives; occupied paths/ports refuse rather than overwrite unrelated resources.
`--no-build` uses an already-built suitable JAR. Ctrl+C stops only the launcher's owned process/container/config.

```sh
./gradlew :app:testRealBackend \
  -Pgymtracka.realBackendConfig=/tmp/gymtracka-real-backend/config.json --console=plain
python3 -B -m unittest discover -s scripts/tests -p 'test_*.py'
```

`GYMTRACKA_REAL_BACKEND_CONFIG` is the alternative Gradle input; there is no implicit config discovery.
`RealBackendFixture` guards disposable identities before provisioning synthetic sessions. Domain outcomes go through
real HTTP, not SQL fixtures or a second service implementation. A locally trusted TLS relay exercises production
adapters. JDK/JDBC/key/config helpers remain test-only, outside the APK. Synthetic setup does not verify Google sign-in.

## Real Room and service composition

The opt-in Room layer uses independent file-backed Android databases/vaults and the real capture/push/pull/snapshot/
publication machinery. A host-only relay reuses the guarded service fixture, provisions disposable sessions through
actual service refresh, and can withhold a selected response after real upstream success. It does not simulate
successful domain outcomes. Preparation compiles the host launch classpath:

```sh
./gradlew :app:prepareRoomBackendRelay --console=plain
```

With the isolated service still running, launch the relay in another foreground terminal:

```sh
java -cp "$(cat app/build/room-backend/relay-classpath.txt)" \
  com.dragosfotache.gymtracka.data.sync.backend.RoomBackendRelayMain \
  /tmp/gymtracka-real-backend/config.json /tmp/gymtracka-room-relay.json
```

The new relay metadata file contains its port and public Base64 DER certificate, is exclusively created mode 600,
and is removed only if its file identity still matches. The relay stays outside Gradle and both Android APKs.
Stop its owned process with Ctrl+C. Do not log setup bodies, bearer tokens, or private backend config.

Read the port/certificate from that metadata. Reverse the chosen port on the test emulator with
`adb -s TEST_EMULATOR_SERIAL reverse tcp:RELAY_PORT tcp:RELAY_PORT`, then replace the example arguments below:

```sh
ANDROID_SERIAL=TEST_EMULATOR_SERIAL ./gradlew :app:connectedDebugAndroidTest \
  -Pgymtracka.roomBackend=true \
  -Pandroid.testInstrumentationRunnerArguments.package=com.dragosfotache.gymtracka.data.sync.roombackend \
  -Pandroid.testInstrumentationRunnerArguments.roomBackendPort=RELAY_PORT \
  -Pandroid.testInstrumentationRunnerArguments.roomBackendCertificate=PUBLIC_BASE64_DER \
  -Pandroid.testInstrumentationRunnerArguments.timeout_msec=120000 --rerun --console=plain
```

Explicitly enabled tests fail when relay inputs are missing. Inspect executed XML. The public certificate is trusted
explicitly with normal hostname verification. Scripted response loss/retention faults and file reopen establish their
client recovery boundaries, not actual Google, deployed operations, service pruning or real process death.

`ForegroundRecoveryRelayTest` also characterizes reconnect during durable upload backoff. A control
keeps the established account ACTIVE and delivers at the retry deadline without reauthentication.
The recovery journey builds the five-minute cap through ten injected pre-send transient failures,
checks real credential-free reachability, then uses the production foreground handoff, startup
controller and Finish signing in UI with freshly issued same-account test-service grants.
Repeated programmatic sign-in and saved-session continuation preserve the exact frozen upload and deadline;
the gate shows the remaining wait and disables redundant presses. It remains private one millisecond
before the deadline and completes automatically at equality without another button press. A separate
ACTIVE-root test verifies automatic retry without authentication and the countdown in Settings. An
independent Room/vault runtime reads the uploaded exercise back from the service. An injected clock
advances the delays without sleeping. Google UI/challenge/exchange acquisition and connectivity
failure are injected; successful synchronization uses the real isolated service. This reproduces a
possible cause of the October 31 incident, not its unrecorded original failure or Tailscale behavior.

The reconnect scenarios additionally confirm immediate ACTIVE delivery and private activation after
a real reachability check reports Offline→Online, without advancing the backoff clock. Initial and
repeated Online checks cannot reset connection backoff; explicit service waits survive reconnection.
Independent service readback verifies exactly one created exercise. `PushOutcomeRetryTest` covers
retry-cause persistence across file reopen, unchanged request bytes/admission history, one-use reset,
and service/legacy-delay protection. Migration coverage includes 14→15 outbox preservation and
conservative unknown causes, every supported starting version, and the bundled v1 asset.

`AccountRetryTest` uses virtual time for deadline equality, rounding, cancellation and wall-clock
changes. `AccountRetryEffectTest` checks STARTED admission, suspension in the background, one dispatch
per owner/deadline, disabled waits, owner turnover and disposal through the Compose lifecycle.
`AccountStartupStateTest` also verifies that cancelling a deferred activation preserves saved authority.

## Catalogue verification

The shared catalogue package owns seed/tool details. Read-only parity commands are:

```sh
python3 -B shared-docs/catalogue/verify.py --android-db app/src/main/assets/databases/gym.db
python3 -B -m unittest discover -s shared-docs/catalogue -p 'test_*.py'
```

The version-1 asset stays separate from runtime migrations. APK inspection can compare the packaged database/notices
with the source bytes.

## CI

[android-tests.yml](../.github/workflows/android-tests.yml) defines two Ubuntu 24.04/JDK 25 jobs with API 37 SDK/build
tools. Build/JVM/assembly runs on push, pull request and manual dispatch. Instrumentation runs on pull request/manual
dispatch using a headless API 35 Google APIs x86_64 emulator with KVM and animations disabled.

Actions are pinned by SHA, permissions are read-only, older same-ref runs are cancelled, and available reports/APKs
upload even after failure. Lint and external backend/Room-service tasks are not part of this workflow.
