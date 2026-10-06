# R09f acceptance review — exact-pending-refresh reconciliation

Accepted 2026-10-06. [Specification](R09f-task.md) owns the frozen slice contract;
[current implementation](../../sync/implementation.md#inert-exact-pending-refresh-reconciliation) owns enduring facts.

## Delivered boundary

Inert replay-only prerequisite over exact established ACTIVE Room/ACTIVE vault evidence. The required shared physical-DB
owner then referentially matched shared refresh/credential mutex span stable preflight, ONE retained exact refresh send,
complete original same-account grant/no-pending CAS, final stable pair and error rereads. Old Room and scoped invocation
lease authorization fence every seam. Exact returned deadlines remain unchanged, even expired; no fresh rotation,
clock/UUID, recapture, conflict retry, Room writer, schema/vault-format change or application construction.

`SettledSessionObservation` is nonsecret advisory evidence, never access credentials/visibility/reusable authority. R09e
replacement must separately freshly admit it. No-pending expired pairs are read-only idempotence. Domain/cache/queue/frozen
bytes/checkpoints/stages/foreign scopes remain unchanged. Unsuccessful/legacy-malformed or already-transitional uncertain
predecessors remain retained; no rekey, retirement, grant cleanup, orphan adoption or compensation is inferred.

## Independent review and corrections

Fresh native Worker and QA used `meta/muse-spark-1.3-contributor`/high with disjoint production/test access. The native
`openai-codex/gpt-6.1-sol`/high coordinator reviewed both layers and owned integration/serialized acceptance gates.
No mcpls tool was exposed in this coordinator harness; bounded exact reads/diffs/compilation were used, not a clean-LSP claim.

- Production review required explicit sent revision+1 proof, error mapping while both owners remain held, strict failed
  Room reread handling, post-settled authorization, and preservation of a one-shot observed corrupt Room category across
  a subsequent good reread. No existing general access-selection/rotation behavior was changed.
- Independent QA corrected an invalid mismatched pending-token fixture, vacuous semantic rereads/contention/supersession
  oracles, independent-owner replacement construction and partial reopen preservation. Actual strict HTTP legacy decoding,
  pre-CAS load versus already-started CAS boundaries, nonadjacent revision refusal and one-shot Room faults have distinct
  observable oracles. Existing R04/R09e matrices were reused rather than copied exhaustively.
- Initial integration compilation failed on two nested cancellation-probe qualifiers and a reopen baseline scoped inside
  a closed-DB try block. These harness defects were corrected without changing expected behavior. Test-only noncopying
  cancellation probes preserve exact identity without disabling coroutine stack recovery/debug. No executed behavioral
  test failed; all declared final scoped gates passed.
- Owner instruction added the major-task slicing rule: the first coordinator taking a NEW major task owns its finite
  whole-task breakdown, not successors receiving sub-slices. R09 requires a consolidated remaining plan before continuation;
  this acceptance does not automatically invent R09g or authorize more splits.

## Executed gates

Repository-root commands, offline Gradle9.8, test-owned API37/Android17 x86_64 emulator using the 37.2 PlayStore16KB image;
boot completion and actual16384-byte page size verified. All Gradle executions had explicit exclusive Worker compile-only
or coordinator integration/acceptance slots. Existing global ADB/Gradle resources were not restarted/claimed. No backend,
personal device/configuration/database or real Google/account input was used.

| Gate | Actual evidence |
|---|---|
| `./gradlew --offline :app:compileDebugKotlin --console=plain` | Three Worker compile-only passes executed Kotlin/KSP as needed, exit0; coordinator reviewed corrections independently |
| `./gradlew --offline :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin --console=plain` | Initial test compilation failed as above, not counted passed |
| Same compilation tasks plus `:app:testDebugUnitTest --tests 'com.dragosfotache.gymtracka.data.sync.session.SessionPendingRefreshReconciliationTest' --rerun --console=plain` | Both corrected test layers compiled; fresh focused23/23, zero failures/errors/skips |
| `./gradlew --offline :app:compileDebugKotlin :app:testDebugUnitTest --rerun --console=plain` | Fresh unfiltered offline JVM897/897 in52 suites, including23 new replay cases and existing refresh/recovery/preparation/R09e classes; zero failures/errors/skips |
| `ANDROID_SERIAL=emulator-5556 ./gradlew --offline :app:connectedDebugAndroidTest --rerun -Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.session.SessionPendingRefreshRoomVaultTest --console=plain` | Fresh new real Room/Keystore/file/reopen7/7, task exit0 and actual XML names verified |
| Same connected task with `-Pandroid.testInstrumentationRunnerArguments.package=com.dragosfotache.gymtracka.data.sync.session` | Fresh scoped session package173/173 in15 classes, zero failures/errors/skips, task exit0; new cases included, not additional distinct coverage |
| `./gradlew --offline :app:assembleDebug :app:lintDebug --console=plain` | Frozen-source unsuppressed lint analyses all executed, exit0; debug packaging had freshly executed during device gate; assembly reused that APK |
| `./gradlew --offline :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest --rerun :app:assembleDebug :app:lintDebug --console=plain` | Archive-citation closure compiled both test layers, fresh full JVM897/897 again and affected test-source lint analyses executed; exit0; no assertions changed |
| APK/preservation/docs | Source asset/exported1–12 schemas and app/manifest/backup construction inputs unchanged; packaged database exact; new QA/helper/fixture/namespace markers absent in production dex; local links/anchors/whitespace and both trees checked |

Device classes/counts: PreparedSessionCompletionFenceTest18, PreparedSessionCompletionRecoveryTest10,
PreparedSessionCompletionTest14, PreparedSessionEngineFenceTest12, PreparedSessionEngineRecoveryTest9,
PreparedSessionEngineTest8, RoomSessionAuthorityConcurrencyTest5, RoomSessionAuthorityCorruptionTest11,
RoomSessionAuthorityPreservationTest6, RoomSessionAuthorityTest14, SessionPendingRefreshRoomVaultTest7,
SessionPreparationRoomVaultTest9, SessionReauthenticationRoomTest14, SessionReauthenticationRoomVaultTest9,
SessionVaultAndroidTest27. Package filtering is NOT unfiltered whole-layer instrumentation.

Lint: one existing NewerVersionAvailable warning, zero errors; no suppression/configuration changes. Compilation/runtime
reported existing AGP experimental/deprecation, old test-source warnings and protobuf Unsafe warnings, no new-source warning.
Packaged APK SHA256: `d839ce328db1a8041fb2acb7798dac4cbfef63a7696284b56656b50d1edab12a`.
AAPT2 confirmed existing credential namespace exclusion in legacy backup/cloud-backup/device-transfer XML. No app caller
constructs the coordinator or invokes reconciliation. Shared-docs remained clean/unchanged; no schema/asset migration.

## Residual obligations and stop

Stopped at advisory settled old ACTIVE evidence before separately admitted R09e replacement. Production remains
signed-out/local-only. Failed/legacy-malformed and already-transitional pending disposition, signed-out/missing-vault
recovery and application/offline established-account integration remain outstanding. Exact durable bytes survive uncertain
writes; expired access does not authorize cleanup. Legacy accepted-auth replay remains unrepaired.

Fresh full unfiltered instrumentation and two independent real Room stores through actual engine/isolated-service HTTP
remain R09/R10 integration gates BEFORE activation. R12a real finite-edit/timer barriers, core R10 convergence, API24,
hosted CI, Google/OEM/cloud transfer/phone/deployment journeys remain separate milestone gates. Accepted prior real-service
refresh evidence is reused; this review claims no fresh backend or live Google pass. All declared R09f scoped gates passed.

The test-owned emulator is stopped and isolated Android/AVD homes removed; no Gradle/device/backend ownership transfers.
No automatic successor slice: settle a finite remaining R09 plan and unresolved owner dispositions before continuing.
