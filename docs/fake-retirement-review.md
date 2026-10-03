# Simulator retirement review — 2026-10-02

Status: coordinator-approved; final independent Android build and test gates passed.

Historical gate record: its process/pane identifiers and running-server statements below are NOT live operational
instructions. Rediscover current state through the [handoff](coordinator-handoff.md); the later
[Android delivery record](remaining-work/R02bc-commit-review.md) records owner-authorized commit scope and fresh checks.

## Scope and result

- Removed `FakeSyncService`, `FakeAccountSyncApi`, `FakeSyncFixtures`, 12 fake-only test classes (92 methods), and
  unused `sharedTest` wiring. Production interfaces/codecs and Room/state/UI/identity regressions remain.
- Added JVM-only HTTP interoperability infrastructure, 46 black-box tests, 29 offline transport-adapter tests,
  23 pure configuration-guard tests, and 11 stdlib launcher tests. The 10 original boundary intents are retained.
  The ordinary offline JVM suite now contains 183 methods.
- Production Kotlin changes are KDoc only. Room v9, schemas 1–9, bundled v1 database, manifest, UI behavior, and
  production dependencies are unchanged. PostgreSQL JDBC is a test-only dependency.
- The [coverage ledger](fake-retirement-coverage.md) maps every retired method and distinguishes direct Android HTTP
  proof from supporting prior backend report evidence and simulator-only cases.

## Coordinator review corrections

Review corrected real-service expectations and test harness issues before retirement approval:

- Exact complete batches/records, source preservation, no-effect rejection checks captured before the action, bounded
  paging, random globally fresh IDs, and unconditional foreign-token checks.
- Foreign/deleted history, finish target guards, rejected active-import preservation, copy without an old active,
  and routine/removed-child identity reservations received focused black-box regressions.
- Finish before start is allowed explicitly by `client-compatibility.md`; the contrary fake rule was not a server
  regression. The real cleanup test preserves a supplied pre-start finish time.
- Safe error parsing without JSON coercion/duplicate keys or raw causes, saturated retry hints, genuinely in-flight
  cancellation, and redacted response/configuration string forms.
- Exclusive configuration publication, refusal of pre-existing paths/occupied ports, owned-file cleanup, and private
  disposable database/session bootstrap. No personal env file or database is a fixture.
- Gradle real-task wiring no longer brings the offline Test task into its graph. External-state tests always execute,
  build caching is disabled, configuration-cache reuse is clean, and missing configuration fails explicitly.

## Verification evidence

Contributor retirement gates: 183 offline JVM tests and 46 real-backend tests passed, with zero failures/errors/skips;
Kotlin and Android-test source compilation and both diff checks passed. No device execution was claimed.

Before deletion, the coordinator independently passed the 46-test HTTP task twice, including configuration-cache reuse,
62 affected offline tests, and 11 supervisor tests. Final post-deletion verification then passed:

| Check | Result |
|---|---|
| `:app:compileDebugKotlin` | Passed against current sources |
| `:app:testDebugUnitTest --rerun` | 183 tests in 25 XML suites; zero failures/errors/skips; no fake or backend-package suites |
| `:app:compileDebugAndroidTestKotlin` | Passed; no device execution |
| `:app:assembleDebug` | Passed; `app/build/outputs/apk/debug/app-debug.apk` produced |
| `:app:lintDebug` | Passed; one `NewerVersionAvailable` warning, no errors |
| `:app:testRealBackend` on a fresh supervisor-created database | 46 tests in 9 XML suites; zero failures/errors/skips; executes with configuration-cache reuse |
| Missing-config negative check (one selected HTTP test) | Explicit expected refusal/nonzero exit, not a skip; subsequent full HTTP run restored all 46 passing reports |
| Python supervisor tests | 11 tests passed |
| Production APK inspection | No simulator, test HTTP fixture, or PostgreSQL driver descriptors; bundled database and notices byte-identical to source |
| Retirement ledger | All 92 old test methods have explicit destination/reason rows |
| Both `git diff --check` and edited-document local links | Passed |
| Backend and its independent shared-docs checkout | Clean; no source/config changes |

Exact coordinator build/test commands (Android root):

```sh
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:lintDebug --console=plain
./gradlew :app:testDebugUnitTest --rerun --console=plain
./gradlew :app:testRealBackend -Pgymtracka.realBackendConfig=/tmp/gymtracka-real-backend/config.json --console=plain
python3 -B -m unittest discover -s scripts/tests -p 'test_*.py'
git diff --check
git -C shared-docs diff --check
```

The combined build initially found the offline test task up-to-date, so the separate `--rerun` executed it genuinely
without needlessly rebuilding its dependencies. Actual testcase XML, not cached task success alone, establishes the
183/46 totals. No emulator/device was connected; the 161-method device layer remains a historical gate, not a new pass.

The coordinator stopped the provisional owned test server, confirmed its configuration was removed and its pane returned
to a shell, then restarted through the checked-in supervisor with `--no-build` against a fresh disposable database.
That supervisor remains running in Herdr `w4:pK` at loopback port 18080; its config is mode 600. Stop it with Ctrl+C
when no longer needed. The contributor is idle; no next implementation slice is authorized.

## Boundary and remaining work

The backend repository is operational build/start input only. No backend files were changed. Setup/test inspection and
an overly broad Maven command occurred before the user clarified that boundary; the command timed out, so no full
backend-suite pass is claimed. Completed test-method XML observed during that interrupted run is prior supporting
report evidence, not an independent audit of every cited assertion. No further backend source review/tests occurred
following the clarification.

The isolated HTTP tests bypass external Google only for guarded test account/session setup; real service authorization
still checks their bearer credentials. They do not verify real OAuth, deployed HTTPS, Android Room reconciliation,
account transitions, process-death sync recovery, or WorkManager. The production app still has no running sync engine
or production HTTP/sign-in integration. The last full instrumentation gate remains M3's 161 methods on API 37.

Assignment 018 remains paused. M5–M12 client capabilities require separate authorization; do not recreate the simulator.
No commits or pushes were performed.
