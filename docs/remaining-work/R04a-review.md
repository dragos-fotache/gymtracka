# R04a — Coordinator review

Status: **R04a coordinator-accepted (2026-10-04)**. Owner authorized a only: “Let's start work on R04a then”.
Normative [specification](R04a-task.md), [R04 split](R04-task.md). Subsequent owner delivery commits are recorded below;
R04b/c, account activation, backend, deployment/private configuration and additional agent commits/pushes remain
unauthorized. Legacy incompatible accepted replay remains UNREPAIRED.

## Delivered boundary — UNUSED

New `data/sync/session/` models/seams, strict version1 binary record codec, `EncryptedSessionVault`, AES/GCM cipher,
Android Keystore key provider, original-path atomic file adapter and inert `AndroidSessionVault` factory:

- Immutable validating/copy-guarded service-origin/account/positive-generation/operation/phase/grant records with exact
  uncertain refresh operation/key/ORIGINAL body. Pending token matches grant token; original scalar fidelity rejects
  numeric-overflow string fallback without changing approved auth codecs/API/transport. No timestamp rounding or clock policy.
- Checked revision CAS re-reads committed state under mutex, atomically replaces the WHOLE grant+attempt snapshot, and
  clears via positive-revision encrypted tombstone (not revision0 reset/ABA). Corrupt/unsupported/missing-key state stays
  closed and preserved, never silently empty/repaired or permission for account/cache deletion.
- Keystore nonexportable AES256/GCM/NoPadding, provider-generated12-byte IV,128-bit tag/header AAD, lazy key access and
  no user-auth/StrongBox mandate/software-key fallback. Decrypt never creates a missing key; occupied wrong-kind aliases
  refuse without overwrite. All displays/errors are safe/redacted with closed causes.
- Credential-protected noBackupFilesDir only; original base/namespace/final/temp lstat checks reject symlinks and wrong
  shapes, including targets inside noBackup. Checked stale-temp unlink, nonfollowing/exclusive0600 ciphertext temp,
  complete write/fsync, atomic rename and namespace PLUS parent fsync on EVERY successful replacement. Public Android
  stub omits O_DIRECTORY, so directory fd uses O_RDONLY|O_NOFOLLOW + fstat type verification, not a hidden numeric flag.
- Factory construction is inert/lazy and repeated production/test opens share one process-wide mutex. All codec/crypto/file
  work runs on IO. Owned-copy cleanup is best effort; Strings/GC/flash secure erasure, hardware security and multiprocess
  support are NOT claimed.
- Cancellation identity survives context stack recovery; the actual caller cause dominates racing categories/other
  cancellations. Waiting/prewrite cancellation performs no replacement; a cancellation or failure after commit may
  leave complete new state and requires reload/fencing, NEVER rollback inference/new key. Outer unexpected active-context
  failures are closed as unavailable. No internal coroutine APIs, heuristic cause unwrap or disabled recovery.

Two backup XMLs now explicitly exclude `root/no_backup/gymtracka-session-vault` in legacy backup and modern cloud/D2D
sections. allowBackup=true and ordinary database backup are preserved. Runtime/app scope activation, network refresh/access
provider, Google proof persistence, Room/schema/migration/build/manifest/asset/domain/repository/state/UI changes are absent.
No vault reference exists outside the new session package in production; the app constructs/activates nothing new.

## Independent ownership and review

Successor coordinator gym-coordinator w4:pQ fully read relevant Android/product/R01 and shared contract map before design.
Live Pi Worker w4:pJ and QA w4:pK were rediscovered via list/process-info/footer; both used fresh native /new sessions,
meta/muse-spark-1.3-contributor/high. Worker owned ONLY new session production/two XMLs; QA ONLY new session tests, using
identical normative APIs/binary grammar and coordinator-supplied integration facts. Corrections remained in the same
sessions; no cross-role source/report access. Gradle slots were explicitly serialized and reports fire-and-forget.

Coordinator independently read all production/tests/fixtures/new files. Source review corrected incompatible caller-IV
initialization for randomized-required Keystore, open cause initialization, cancellation/context recovery and buffer
ownership, canonical-before-lstat symlink checks, ignored stale unlink, missing parent sync after a failed first write,
device-protected wrappers, wrong-kind key overwrite, strict pending scalar fidelity and active-context failure closure.

First focused run: **70 JVM tests,64 passed/6 FAILED**, zero errors/skips. Two failures were real outer-withContext
cancellation identity bugs; four were fixtures (cleared temporary captured too late, whitespace empty echo needle, valid
port444 misclassified, recreated attempt object identity). QA retained in-caller assertSame oracles; production was fixed.
Coordinator also required full valid malformed-fixture controls, exact tag offsets, matching-grant scalar negatives,
actual callback-driven cancellation, postcommit reload and private Android fixtures (src/test is not shared with androidTest).
Wrong-kind Android fixture expectations were corrected BEFORE device execution, preserving explicit UNAVAILABLE/no-overwrite.
No prior test was removed/weakened. Final focused QA **74/74** and final independent gates below are green.

Kotlin mcpls had bounded indexing/unavailable diagnostics (an existing-symbol search succeeded); exact allowed reads and
actual compilation supplied authority. No global configuration/server reset or backend/source access occurred.

## Actual final gates

- **627 offline JVM tests in43 XML suites**, coordinator forced execution, zero failures/errors/skips: all prior553 plus
  independent32 SessionVaultTest,31 VaultRecordCodecTest and11 VaultCipherTest methods. Table-driven subcases are not
  additional test-count claims. Complete fields/exact whitespace+escapes/UTF8/IPv6/expiry bounds/>64KiB, malformed/version/
  tags/length/binding, CAS/overflow/tombstone/restart/failures, cancellation identity/dominance and independent JCA AAD are covered.
- **27 filtered SessionVaultAndroidTest tests** on owned Pixel_6a / Android17 / API37 (`emulator-5554`), zero failures/errors/
  skips. Actual Keystore settings/nonexportability/randomized writes, reopen/exact attempts/tombstones, missing/wrong-kind
  key refusal, atomic write faults/.new residue, concurrency, permission/type/symlink/protected-context failures, platform
  scalar fidelity, inert factory and packaged backup XML/manifest checks. Not a full instrumentation or API24 runtime gate.
- Production/Android-test Kotlin, debug assembly and lint passed. Lint XML has only existing NewerVersionAvailable Warning.
  Existing KSP catalogue indexes/test-helper/Gradle/native/protobuf notes remain. Two NEW nonfatal QA warnings (unnecessary
  `!!` and safe-call) are recorded, not mislabeled existing; R03c's Call.enqueue parameter-name warning remains earlier source.
- Debug/release merged manifests parsed: min24, allowBackup=true, existing cleartext refusal and both backup XML references.
  Release main-manifest processing was explicitly rerun. Packaged XML includes all three focused exclusions and no
  include-only/database exclusion. No actual hosted cloud backup/OEM D2D execution is claimed.
- APK contains production vault, not independent vault test/JCA fixture/local TLS/backend/JDBC helpers. Packaged bundled
  source v1 database is byte-identical (SHA256 `defa08e9c464f90f0f48d8e3441fe20151803b48948647a91742c1bb82fa9f7d`).
  Room remains9; schemas/build/app/domain/state baseline unchanged. Both tracked diffs and all untracked whitespace checks pass.

```sh
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --rerun-tasks --no-configuration-cache --console=plain
./gradlew :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.session.SessionVaultAndroidTest' --no-configuration-cache --console=plain
./gradlew :app:processReleaseMainManifest --rerun-tasks --no-configuration-cache --console=plain
git diff --check
git -C shared-docs diff --check
```

Executed XMLs: `app/build/test-results/testDebugUnitTest/TEST-*.xml` and
`app/build/outputs/androidTest-results/connected/debug/TEST-Pixel_6a(AVD) - 17.xml`. Counts/unique testcase names/failures/
errors/skips were independently inspected, not inferred from cached tasks. Supplementary `/tmp/gymtracka-r04a-{final-gate,
android,release-manifest,qa-focused-final,worker-compile}.log`/hash manifest are not pickup prerequisites.

## Preservation, limitations and stop

Pickup Android HEAD `6134a58bacaa20bcfc7ccb872a77ffb104867d21`, shared HEAD
`d5d68d4cfa919839e6f52a590cebda46c31647ae`, both were live at the acceptance gate. Existing R03c tracked/untracked production/
tests/harness/docs and predecessor succession edits preserved. All232 pickup Android files outside the eight authorized
existing docs/XML are hash-identical; all29 shared files are unchanged, including its two existing dirty issue/index edits.
No coordinator commits/pushes/staging or unrelated pane/process/checkout/private input changes occurred at that gate.

**Subsequent owner delivery (verified 2026-10-04):** Android
`04f7ed24c39b4396122e6d60225942493ba500e2` (`sync work`, parent `6134a58`) includes accepted R03c/R04a source/tests/
harness/backup XML and local docs. Shared-docs `31c969f261bd3f0fe662e633c0dfffecb3c08393` (`sync work`, parent `d5d68d4`)
commits the existing auth-expiry issue resolution/index edits. Both working trees, including untracked files, were clean
before this documentation-only receipt update. These are owner commits, not coordinator commits or new verification
runs; push/remote state was not checked. Earlier dirty-tree descriptions are historical. No later slice is authorized.

Owned headless emulator was stopped after verification, without focus change or other device control. Gradle's device
harness had already uninstalled the test package (post-run run-as reported no package); no extra installation/activation
was performed for residue inspection. No backend service/container/configuration was created in a. Worker/QA hold idle
in completed R04a sessions; rediscover live identities and start fresh native sessions only for a separately authorized slice.

No HTTP/Google/OAuth/backend/Python/Room migration/engine convergence/full-device/hosted-CI/phone-laptop activation gate was
added. R03c553/six-device and backend auth10/broad56 evidence are prior gates, not a new backend rerun. No full-v1/public
release, hardware-backed/secure-erasure, actual cloud/OEM-transfer or API24 runtime compliance claim.

**Legacy limit still current:** original pre-fix encrypted accepted replay bytes/deadlines may remain incompatible. Known
still-current legacy refresh can rotate freshly, NEVER an uncertain consumed predecessor under another key. Strict
responses remain strict; no rounding, replay rewrite, blanket revocation or inferred data/cache deletion. Carry this into
future R04b/c session recovery and release review. R04a stores exact identity; it does not prove recovery of legacy attempts.

**Next proposed pickup: R04b refresh coordination, only after specific owner authorization.** No b/c specification or
implementation, contributor assignment, app activation, deployment or commit/push is authorized by this acceptance.
