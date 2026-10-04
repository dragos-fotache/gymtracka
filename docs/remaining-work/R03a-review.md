# R03a — Coordinator acceptance review

Status: **accepted** (2026-10-03). Owner authorization: “start work at R03a”.
Scope and normative APIs: [R03a specification](R03a-task.md). R03a is the reusable HTTP primitive only;
R03b/c/d, endpoint configuration/activation, engine/vault/Google/UI, commit and push remain unauthorized.

## Delivery and independent review

Three new production files under `app/src/main/java/com/dragosfotache/gymtracka/data/sync/http/`:

- `ServiceOrigin.kt`: original-text strict HTTPS root-origin validation, canonical scheme/host/effective-port identity,
  null unavailable configuration, private immutable construction and redacted display. Rejects unsafe/ambiguous origins,
  credentials/query/fragment/base paths, Unicode/encoded hosts, zones and invalid ports before URL normalization.
- `ServiceHttpTransport.kt`: immutable defensive request/header/query snapshots, path/header/body/surrogate/timeout
  guards, scoped status/header response, strict complete UTF-8 JSON reads and incremental chunk reads, explicit framing/
  encoding/media validation and bounded overflow-safe Retry-After. No aggregate-size cap or export String pipeline.
- `OkHttpServiceTransport.kt`: fixed-origin exact UTF-8 requests, suspend callback bridge, structured cancellation
  watcher spanning consumer/body I/O, actual Call cancellation, late-response cleanup and joined once-only off-Main
  response closure. Original consumer Throwable is retained across coroutine stack-trace recovery without arbitrary
  cause unwrapping. Exceptions from I/O/setup are closed-cause safe categories; consumer exceptions are not masked.

Coordinator selected/wired OkHttp **5.5.0** after checking current Maven metadata and upstream compatibility/release docs.
Resolved production variant is `okhttp-android:5.5.0`, with Okio 3.18.1 and AndroidX Startup 1.2.0; existing app Kotlin/
coroutines/desugaring remain. Production needs only core; MockWebServer3 and okhttp-tls are test-only. INTERNET and
`usesCleartextTraffic=false` are explicit; the normal OkHttp Startup initializer remains enabled. No URL/audience/key,
trust override or construction was added to the app shell.

Normal TLS/hostname verification, no cookies/cache/authenticators/redirects/connection retries/logging. The trusted
internal test-builder seam carries TLS trust only into a fresh default builder; it is not an insecure-origin setting.
A fresh per-Call application-bound marker and network guard prevent second on-wire exchanges, including GET 503
Retry-After:0 (which retryOnConnectionFailure=false alone does not stop). POST bodies are one-shot. A refused library
follow-up can become Transient rather than expose the closed initial response; explicit subsequent calls remain independent.

## Contributor separation and review corrections

Rediscovered coordinator `w4:p7`, Worker `w4:pJ`, QA `w4:pK`; both contributors were Pi
`meta/muse-spark-1.3-contributor` / high. Fresh native `/new` sessions for R03a; same sessions through corrections.
Both received one normative spec with disjoint production/test allowlists and asynchronous Herdr reports. Gradle slots
were serial. No cross-role source access was granted; coordinator reviewed all source and tests, supplied missing API/
external-library facts and owned build/manifest/Android integration. No global configuration or private backend inputs changed.

Source review corrected private-constructor placement, bracketed IPv6/empty-port handling, actual cancellation during
blocked reads, cancel-after-resume cleanup, chunk framing/encoding validation, EOF mapping, per-Call (not per-request)
guards, TLS-only builder construction, malformed quoted media and safe setup failures before acceptance. Public structured
coroutine APIs replaced internal/experimental job-hook approaches. Once-only response close is off Main and outside locks.

The first focused execution ran **82 tests: 80 passed, two failed** exact consumer-exception identity assertions.
Coroutine scope stack-trace recovery was copying otherwise same-type exceptions. Production now captures the original
consumer-thrown object inside the scope and restores it at the outer boundary; the assertions were **not weakened**.
The final independent set is **83 tests**, including controlled resumed-before-dispatch cancellation and read/close thread
observations. QA corrected enqueue-before-callback fixture ordering, escapes, actual close observations, deterministic
signals instead of sleeps/polling, server request-queue handling and external OkHttp 5 APIs in its own files.

Coordinator fixed the Android-only `android.security.NetworkSecurityPolicy` import after an initial compile failure,
replaced redundant type-existence assertions with meaningful redaction/metadata observations, and corrected a final
thread-observing body fixture to return its cached source rather than a fresh buffer on close. The final forced gate
below followed these corrections. No legacy test was removed or weakened.

## Actual final gates

All commands ran from Android root; final full gate used `--rerun-tasks --no-configuration-cache --console=plain`:

```sh
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --rerun-tasks --no-configuration-cache --console=plain
./gradlew :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.http.ServiceHttpAndroidTest' --console=plain
./gradlew :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.ui.AppFlowTest#actualGymAppStartsAndLoadsFeaturesWithoutPreload' --console=plain
git diff --check
git -C shared-docs diff --check
```

- **423 offline JVM tests in 35 XML suites**, zero failures/errors/skips: prior 340 plus 42 origin/request, 26 ownership
  and 15 real local-TLS/network tests. Actual XML inspected after final forced execution, not an up-to-date result.
- **5 filtered transport instrumentation tests**, zero failures/errors/skips, on Pixel_6a / Android 17 / API 37,
  `emulator-5554`: INTERNET, effective cleartext refusal, unavailable/secure construction, trusted local HTTPS round-trip
  and cancellable TLS chunk I/O.
- **1 filtered existing AppFlow startup test**, zero failures/errors/skips, same device: normal signed-out tab/start/
  routine construction still works without endpoint configuration after the dependency/initializer/manifest addition.
  These are separate filtered runs, NOT a full instrumentation pass. XML was inspected and archived separately before
  the next filter overwrote connected outputs.
- Production and Android-test Kotlin compilation, debug assembly and lint passed. Final compiler output has only the
  pre-existing catalogue FK-index/test-helper notes; lint XML contains only existing `NewerVersionAvailable` Warning.
  Existing Gradle/packaging/JDK test-runner warnings remain nonfatal.
- Debug AND release merged manifests: minSdk **24**, INTERNET present, cleartext false, nonexported Startup provider
  with the normal OkHttp initializer. `checkDebugAarMetadata` and runtime dependency resolution passed.
- Debug APK has the production primitives but no MockWebServer3, HeldCertificate/local-CA helper, new test class,
  PostgreSQL driver or JDK java.net.http reference. Tests/helper trust remains outside production packaging.
- Both diff checks and new-file whitespace/local documentation-link checks passed. Pickup SHA-256 inventory verifies
  all baseline files outside authorized Android build/manifest/docs changes are unchanged, including accepted dirty
  R02d code/tests/docs, Room v9/schema snapshots, bundled v1 asset, UI/feature/store code and backend scripts. Nested
  shared-docs tracked/untracked bytes, index/worktree status and HEAD are preserved exactly; no commit or push.

Local evidence: `app/build/test-results/testDebugUnitTest/`, `app/build/reports/lint-results-debug.xml` and
`app/build/outputs/apk/debug/app-debug.apk`. Optional temporary logs: `/tmp/gymtracka-r03a-final-gate.log`,
`/tmp/gymtracka-r03a-android.log`, `/tmp/gymtracka-r03a-startup.log`, and separately retained device XML under
`/tmp/gymtracka-r03a-platform-evidence/`. These temporary paths are supplementary, not successor prerequisites.

## Limits and stop

No API 24 image/device is installed. Library API-21+ compatibility, minSdk-24 compilation/metadata/manifest and API-37
construction/I/O passed; **API 24 runtime remains unverified**. No full instrumentation, migration/Room, actual backend,
Python supervisor, Google OAuth, hosted CI, real phone/laptop HTTPS or personal-account gate ran in R03a. Earlier 46
backend/11 supervisor/161 full-device counts remain historical evidence. Local TLS tests are not deployed-service proof.

`GymSyncApi` and `ServiceAuthApi` still have no production endpoint implementation. There is no engine, configured service,
working account flow or app activation. The future isolated-HTTP interoperability harness can supply a test-owned
Call.Factory bridge/local trusted TLS endpoint without weakening ServiceOrigin; that bridge belongs to R03b pickup,
not this delivery. Both contributors are complete/idle; rediscover and start fresh sessions only for another authorized slice.
R02d and R03a Android work remain uncommitted; nested shared-docs is untouched. Next proposed task is detailed **R03b**
sync endpoints/shared error boundary, only after separate owner authorization. Stop here.
