# R11a acceptance review — 2026-10-08

**Accepted: deterministic foreground Android Google identity adapter only.**
[Specification](R11a-task.md). No service exchange/handoff, app startup/account UI, cloud activation, legacy import,
actual Google/account/device journey or deployment is delivered or authorized by this acceptance.

## Delivered boundary and independent review

- Existing SDK-independent `GoogleIdentityProvider` remains unchanged. The explicitly constructed Android adapter uses
  one Google button request, exact constructor audience and supplied service nonce, current Activity UI, actual Google
  custom-credential parsing and an unchanged opaque ID token. Missing configuration/invalid input refuses before launch.
- Fixed SDK-independent failure reasons, caller/user cancellation distinction, shared nonqueueing get/clear slot and
  two-minute default bounds. Close/destroy/recreation retire permanently, cancel owned SDK work without cancelling the
  caller scope and remove the observer on Android Main. Final caller dispatch/acceptance is fenced against retirement.
- Clear is an explicit Credential Manager primitive only, without service credential/cache cleanup or automatic calls.
  No Google proof persistence, JWT/email authority, HTTP, Room/schema/asset or default startup changes.
- Fresh independent Worker and QA sessions used `meta/muse-spark-1.3-contributor`/high with disjoint source allowlists;
  coordinator reviewed all production/test files, dependency/build edits and documentation. Contributors ran no Gradle.
  Kotlin LSP was unavailable (`no LSP server configured for language: kotlin`); bounded source reads and compilation
  supplied navigation/verification, not a clean language-server diagnostics claim.

Resolved review findings: preserve INVALID_CREDENTIAL instead of broad provider-error remapping; remove lifecycle observer
on real Main rather than swallowing an off-Main failure; recheck success and classified failure after caller dispatch;
refuse already-cancelled callers and retire already-destroyed hosts. Independent oracles use real SDK request/parser types,
precise failure reasons, cancellation-resistant late SDK completion, independently advanced Main/caller queues and virtual
adapter/parent deadlines. Expected values are synthetic independent literals, not production-derived expected encodings.

Initial test-harness compilation failed on an impossible sibling exception check and missing coroutine-test extension
imports; these were corrected without weakening assertions. The first API24 iteration executed 33 cases with two harness
failures (String versus OpaqueToken comparison; expected child failure cancelling the test parent). Corrected typed-value
comparison and supervised virtual caller preserve the oracles; the final 34-case snapshot below passed both APIs.

## Executed gates and scope

Environment: Linux host, Gradle9.8.0/JDK25, AGP9.4.1/Kotlin2.4.20; disposable x86_64 API24/Android7.0 and API37/Android17
emulators, emulator37.1.11. No personal device/account, backend, private configuration or real Google request was used.
Every Gradle invocation was coordinator-owned and serialized. Final source checksums stayed unchanged through device and
frozen assembly/lint checks. Normal valid compilation/build outputs were reused; test tasks actually reran.

| Gate | Command / actual result |
|---|---|
| Dependency graph | `./gradlew :app:dependencies --configuration debugRuntimeClasspath --console=plain` — resolved matched direct Credentials/Play Services bridge1.6.0 and GoogleID1.2.1; Kotlin/coroutines resolve to existing app versions |
| Metadata/manifest | `./gradlew :app:checkDebugAarMetadata :app:processDebugMainManifest --console=plain` — success |
| Kotlin/KSP | `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --console=plain` — corrected main/default instrumentation compile success; final device build compiled the final QA snapshot |
| Fresh full offline JVM | `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --rerun --console=plain` — **63 classes / 1,008 cases / 0 failures/errors/skips**; external backend package absent |
| Fresh focused API24 | `ANDROID_SERIAL=<owned-api24> ./gradlew :app:connectedDebugAndroidTest --rerun -Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.identity.AndroidGoogleIdentityProviderTest --console=plain` — **1 class / 34 cases / 0 failures/errors/skips** |
| Fresh focused API37 | Same command with owned API37 serial — **1 class / 34 cases / 0 failures/errors/skips** |
| Frozen assembly/lint | `./gradlew :app:assembleDebug :app:lintDebug --console=plain` — success; assembly reused the matching tested APK, all three lint analyses executed; **0 errors / 1 warning** (`NewerVersionAvailable`, existing org.json dependency) |
| Source/docs/packaging | Full source/asset/schema checksums, binary and merged manifest, APK marker/key/config exclusions, exact executed test-name sets versus 34 declared methods, relative links/anchors and both `git diff --check` — passed |

API24's unsupported additional-output notice did not replace native execution: complete XML contained every requested
case, with no skips. Current/API24 focused results are **not** fresh full default instrumentation or full minimum-API app
regression. Existing catalogue foreign-key-index, Gradle experimental/deprecation and unrelated Kotlin warnings remain
baseline limitations; no new production warning or lint error was introduced.

## Dependency and APK audit

- GoogleID1.2.1 AAR minSDK24; Credentials/bridge minSDK23. Actual binary APK and merged manifest remain min24/target37,
  namespace `com.dragosfotache.gymtracka`, cleartext disabled, existing backup rules intact.
- Stable bridge1.6.0 transitively brings Play Services auth21.1.1 and upstream
  `play-services-identity-credentials:16.0.0-alpha08`. No direct alpha API/version was selected; adapter code uses stable
  get/button/clear APIs. Synthetic compatibility is verified, actual provider interoperability is not.
- Transitive biometric dependency adds normal USE_BIOMETRIC/USE_FINGERPRINT permissions, not runtime-dangerous grants.
  Credential metadata/hidden UI activities are nonexported; GMS revocation service is exported only with its vendor
  REVOCATION_NOTIFICATION permission. No application sign-in initializer/trigger was added. Existing debug tooling/test
  manifest Activity remains intentional; new auth QA/backend/JDBC helpers are excluded from the application APK.
- DEX contains none of the R11a synthetic audience/proof markers, identity test/fake class, backend fixture namespace,
  PostgreSQL or java.sql markers. No PEM/key/keystore or Google configuration file is packaged; no audience/endpoint/client
  secret was supplied or invented. Packaged v1 catalogue exactly matches source; exported schemas are unchanged.
- Debug APK SHA256: `0b8d5a9e26641715033f6c33ff95cdbad1db86ee9543f5d651171bcc278ca286`.

## Deferred gates, residual limits and stop

All declared R11a synthetic gates passed. Full unfiltered default instrumentation and existing isolated auth
interoperability remain **R11b personal-package closure** gates, not rerun/claimed here. No transaction/capture behavior
changed; Room/migration instrumentation was not repeated for this adapter-only slice.

Configured audience/signing/package, actual Google sign-in/cancellation/same-account reauthentication, trusted service
HTTPS and phone journeys remain separately authorized **R17a**. Synthetic Google token parsing is not a verified provider
proof. Cancellation/process death cannot promise SDK/server rollback; Google exchange recovery remains foreground-memory
only in the future R11b boundary. Existing legacy auth replay limitation and P18 import-before-activation obligation are
unchanged.

Next is R11b bounded foreground service exchange and guarded grant handoff. Owner explicitly stopped after R11a acceptance,
scoped local commit and successor preparation: **no successor launched, no R11b implementation**. Owned emulator/build
resources are settled at closure; no live resource grant is carried forward. Existing R11/P18 documentation was separately
reviewed/committed with owner permission before this slice; the R11a commit excludes unrelated user work and no push occurs.
