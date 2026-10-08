# R11b acceptance review — bounded foreground exchange and guarded handoff

**Accepted 2026-10-08, deterministic-only.** The owner released coordinator ownership and explicitly selected injected
synthetic configuration. The [specification](R11b-task.md) owns the assignment. Personal R11a–b and their declared
synthetic/package gates are complete; R11c lifecycle proofs remain deferred public. This is not real Google, deployed
HTTPS, account UI/activation or personal-phone readiness.

## Delivered boundary and independent review

- New `ForegroundSignInCoordinator` composes one SIGN_IN service challenge, exact nonce Google acquisition, one immutable
  `GoogleExchangeAttempt`, and the existing private grant consumer. Three exchange sends maximum, 1000/2000 ms minimum
  retry delays extended by service hints, and a default 180000 ms whole-invocation budget. Only transient exchange
  failures retry, retaining the same object/key/body/token/challenge; no new challenge, refresh or provider fallback.
- Challenge equality/expiry refuses before actual provider and first-send admission, including queued dispatch. Already
  sent uncertain replay may cross challenge expiry; returned grant/deadlines, including expired access, remain unchanged.
  Proof/request/malformed failures terminate. Closed displays/errors retain no raw details or credential-bearing evidence.
- `AppSessionCoordinator.captureForegroundSignInRequest` captures actual configured origin, intent, exact expected Room
  observation, operation and immutable opaque owner/process fence without I/O. `acceptForegroundGrant` carries that
  fence into the existing acceptGrant critical section, never recapturing the latest fence. Original three-argument
  acceptGrant behavior, mutexes, real transition leases, exact Room/vault preparation and P17 retention remain shared.
  Naked/foreign-owner/origin/account requests refuse; process invalidation refuses even when Room is unchanged.
- One nonqueueing foreground slot, owned cancellable work, permanent close and post-seam/final fences prevent late or
  queued work from advancing or returning success. Disposal never clears Google/service credentials or invalidates a
  healthy account automatically. Cancellation/lost return is not rollback proof; already-admitted commits remain subject
  to existing explicit recovery. Google/exchange evidence remains foreground memory only.
- Production and independent QA used fresh native coordinator `openai-codex/gpt-6.1-sol`/high and separate
  `meta/muse-spark-1.3-contributor`/high sessions, with disjoint source allowlists. Coordinator reviewed both layers.
  Every Gradle run was serialized; both source layers were frozen for package/lint gates. No schema, asset, dependencies,
  default startup/screens, import, activation or backend source/configuration changes were made.

### Important review findings resolved

Immutable request binding replaced a mutable attachment. Owned work now registers lazily, rechecks caller/slot before
starting effects, and cancels every created child on all exits, including failed pre-start registration. Freshness checks
occur at actual queued provider/first-exchange admission. A dependency-owned timeout is not the foreground budget.

Two initial JVM cancellation-identity failures exposed a missing original-caller recheck outside the timeout scope.
The outer caller context now reestablishes actual caller cancellation instead of propagating a child-scope recovery copy.
The original `assertSame` oracles were retained and passed. No cause/message unwrapping, global stack-recovery disabling
or internal coroutine APIs were introduced.

Independent harness corrections separated true queued dispatcher return/admission from suspended fake seams, used
actual `withContext(NonCancellable)` for resistant work, preserved newer-slot/key isolation, and used supervised outcome
capture for expected child failures. Device tests use real Room, Android Keystore vault and actual finite-edit transition
leases; wall-clock guarded coroutine execution avoids virtual-time auto-advancement across real storage. Stored origin
oracles use independent canonical literals; uncertain refresh evidence is valid and unchanged; post-setup checks compare
deltas. Teardown cancels/joins owned scopes before closing Room. These corrected harnesses did not weaken behavior.

## Actual verification

All results below are fresh executed gates unless explicitly labeled compilation or artifact reuse. The disposable
current emulator was API37/Android17 x86_64 with software graphics. No personal device/account was used. Isolated auth
used a newly launched disposable PostgreSQL16/backend and test-only trusted TLS adapter under the approved launcher;
backend access was operational build/start and black-box HTTP, not source review or its Maven test suite.

| Command / inspection | Result and scope |
|---|---|
| `./gradlew :app:compileDebugKotlin --console=plain` | Initial production compile passed; final corrected production also compiled through subsequent QA gates. Existing unrelated Kotlin warnings remain, not clean-LSP evidence |
| `./gradlew :app:testDebugUnitTest --tests com.dragosfotache.gymtracka.data.sync.session.ForegroundSignInCoordinatorTest --rerun --console=plain` | Focused final **23 cases, 0 failures/errors/skips**; real queued return/cancel/retire, exact retry, budget/seam timeout and original caller identity oracles |
| Same JVM task filtered to `.queuedAdmissionExpiryRefusesBeforeSend`, `--rerun` | Final tightened provider/first-send queued-admission oracle: **1 case, 0 failures/errors/skips**, including zero provider admission after expiry |
| `./gradlew :app:compileDebugAndroidTestKotlin --console=plain` | Passed; compilation only |
| `ANDROID_SERIAL=<owned-current> ./gradlew :app:connectedDebugAndroidTest --rerun -Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.session.ForegroundSignInHandoffTest --console=plain` | Focused **10 cases, 0 failures/errors/skips**, actual Room/Keystore/finite-edit barrier, private preparation and preserved/refused account evidence |
| `./gradlew :app:testDebugUnitTest --rerun --console=plain`; final `./gradlew :app:testDebugUnitTest --rerun :app:lintDebug --console=plain` | Fresh unfiltered offline **64 classes / 1031 cases / 0 failures/errors/skips** in the final current-source execution; backend package excluded, build dependencies reused. Final annotation-only test opt-in hygiene compiled and unit lint reanalysis passed without new foreground warnings; production/device source and APK unchanged |
| `ANDROID_SERIAL=<owned-current> ./gradlew :app:connectedDebugAndroidTest --rerun --console=plain` | Fresh unfiltered default **89 classes / 970 cases / 0 failures/errors/skips**; includes all 34 R11a adapter cases, new handoff, existing session/Room/migration/copied-asset and Compose/app suites; external roomBackend layer not selected |
| `./gradlew :app:assembleDebug :app:lintDebug --console=plain` | Successful; assembly reused the current verified artifact, lint analyses/reports executed with both source layers frozen. **0 lint errors**, one existing newer-`org.json` dependency warning; no new foreground lint issue |
| `./gradlew :app:testRealBackend --tests '*RealBackendServiceAuthTest' -Pgymtracka.realBackendConfig=<new-exclusive-test-config> --console=plain` | Fresh isolated auth **1 class / 10 cases / 0 failures/errors/skips**: challenge lifetimes/purposes, invalid Google-proof rejection, exact refresh/replay/changed-byte refusal, revocation/logout/isolation and lossless new-grant expiry. This is not a successful Google exchange or another full engine-convergence run |
| `python3 -B -m unittest discover -s scripts/tests -p 'test_*.py'` | **11 launcher safeguards passed** against test-owned temporary resources |
| APK ZIP/DEX scans, `aapt2 dump badging` and binary backup XML / merged manifest | Min24/target37; INTERNET/VIBRATE/WAKE_LOCK and explicit cleartext refusal preserved; legacy/cloud/device-transfer vault exclusions present in the APK. R11b synthetic credential/origin markers, fake/dispatcher/real-backend relay/JDBC helpers, configured OAuth client-ID patterns and secret/config-file markers absent from application APK |
| Packaged asset versus `git show HEAD:app/src/main/assets/databases/gym.db`, schema/asset diff and `git diff --check` | Bundled v1 database byte-identical; asset SHA256 `defa08e9c464f90f0f48d8e3441fe20151803b48948647a91742c1bb82fa9f7d`; no schema/asset diff; whitespace clean |

Executed XML class/method names and counts were inspected, not inferred from shell success or cached tasks. Retained
local command/XML evidence is under `/tmp/gym-r11b.iDhsNp`, including prior failed focused runs, corrected final scoped
runs, full offline/default-device suites, lint/manifest/backup dumps and isolated auth. It is historical evidence, not a
resource grant. Owned emulator/backend/container/config resources were stopped/removed at closure; no Gradle slot
remains assigned. Verified debug APK SHA256: `a6b84e358ee4f73645dd49a4eefa503b6e417457834ec37aa28108060ca0ad15`.

## Limits and stop condition

Required R11b synthetic/package gates passed. R11a's accepted scoped API24 adapter evidence is reused unchanged; no fresh
full minimum-API app regression is claimed. Kotlin LSP initialization/empty indexing did not supply clean diagnostics;
bounded exact reads and compilation were used. No hosted CI, OEM backup/transfer, expanded R10b, actual process-death,
live Google/audience/signing, trusted deployed HTTPS or owner phone/account journey is verified here. Actual provider/
service/phone journeys remain separately authorized R17a; lifecycle proofs remain R11c/public dependencies.

Default startup remains signed-out/local-only. Foreground success is only guarded private preparation, not ACTIVE
admission, bootstrap/publication or legacy import. P18 one-time preserve/import and account UI/triggers belong to R13;
current local roots still refuse until that work is delivered. The unrepaired legacy accepted-auth replay limitation and
P17 evidence retention remain binding. The earlier R12c unexplained scoped Private activation stop remains a review
limitation; the scoped real Room/HTTP engine journey was not rerun or claimed repaired by these package gates.

Stop at this accepted boundary, settle owned resources, commit only R11b and transfer dependency-ready R13 planning to
a fresh coordinator under the standing workflow. The first R13 coordinator must settle its finite major-task plan before
bounded implementation. No push, personal configuration, account/device action or deferred-public work is authorized.
