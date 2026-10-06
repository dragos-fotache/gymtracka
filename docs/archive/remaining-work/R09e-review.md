# R09e acceptance review — settled occupied-credential reauthentication

Accepted 2026-10-06. [Specification](R09e-task.md) owns the frozen slice contract; the
[current implementation](../../sync/implementation.md#inert-settled-occupied-credential-reauthentication) owns enduring facts.

## Delivered boundary

Inert same-origin/same-service-account replacement of an exactly matching settled occupied ACTIVE grant. Room12's existing
journal/device storage privately reserves TRANSITIONING (+1 generation/+1 revision), exact vault CAS installs the full
supplied grant PREPARED (+1 vault revision), and Room finalizes PREPARING (+1 revision, same reserved generation).
Registered existing prepared engine/completion eligibility is reused, not recreated. No application construction or activation.

Reads-only recovery distinguishes the retained settled predecessor, installed target and finalized pair; resume never
reserves again. Missing/foreign/orphan evidence stays private. Pending predecessors refuse unchanged, including late
pending writes after reservation; installed target pending bytes survive semantic rereads and read-only finalization.
Expiry is not rewritten or interpreted as freshness. Both checked Room writers preserve every other table and sequence.
The required shared physical-DB owner then supplied credential mutex serialize effects. No HTTP/Google/clock/UUID generation,
new schema, import/deletion, projection/promotion, default application barrier or ordinary repository rebinding was added.

## Independent review and corrections

Fresh native Worker/QA sessions used the required Muse Contributor/high model with disjoint production/test source access;
coordinator reviewed all delivered production/tests and owned integration/serialized Gradle execution. No mcpls tool was
available in this coordinator harness; bounded exact source reads/compilation were used, not a clean-LSP claim.

- Production review added caller cancellation checks before validation/fence rotation, exact Room recheck after the
  post-reservation vault load immediately before CAS, installed-target revision-exhaustion refusal and closed impossible
  transitional-baseline handling. Full alternating Room/vault rereads and semantic pending equality remain mandatory.
- QA corrected incomplete ACTIVE cache fixtures, empty-vault mistaken NeedsGrant expectations, destructive reopen/vacuous
  foreign checks and call-count-based seam assumptions. Distinct current oracles cover no-effects pending refusal,
  retained rich tables/frozen bytes/foreign scopes, actual occupied restart gaps, count-zero rollback and a well-formed
  but wrong finalization operation rewrite. Assertions were not relaxed to accommodate production behavior.
- Initial focused JVM execution had three cancellation-harness failures (canceled Deferred.await and a default-start
  canceled child that never entered); the corresponding device queued test also failed. After those harness corrections,
  two JVM queued identity assertions still encountered coroutine stack-recovery copies. A test-only CopyableThrowable
  probe with createCopy=null preserves exact identity without disabling debug/stack recovery or weakening assertions.
  A nested-class qualifier compile error was corrected; no test body/expectation was changed by that fix.
- The first assembly/lint attempt crashed in the Compose LocalContextResourcesConfigurationReadDetector's K2/FIR test
  analysis. It was not counted passed or bypassed. Frozen compilable-source rerun passed all lint analyses unchanged,
  with no detector suppression or global/build configuration changes. The initial lint attempt overlapped permitted QA
  edits; this is not proof that overlap alone caused the detector crash.

## Executed gates

Repository-root commands, offline Gradle9.8, test-owned API37/Android17 x86_64 emulator using the 37.2 PlayStore16KB image;
boot completion and actual16384-byte page size verified. Every Gradle ran under an explicit coordinator-only serialized slot.
Normal existing ADB server/default Gradle resources were not restarted or claimed; no personal device/backend/Google input.

| Gate | Actual evidence |
|---|---|
| `./gradlew --offline :app:compileDebugKotlin --console=plain` | Initial and corrected production/KSP compilation executed and passed; later unchanged dependencies were reused |
| `./gradlew --offline :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin --console=plain` | Both layers compiled; later corrected test compilation also executed in focused/device tasks |
| `./gradlew --offline :app:testDebugUnitTest --tests 'com.dragosfotache.gymtracka.data.sync.session.SessionReauthenticationCoordinatorTest' --rerun --console=plain` | Final focused run29/29, zero failures/errors/skips |
| `./gradlew --offline :app:compileDebugKotlin :app:testDebugUnitTest --rerun --console=plain` | Fresh unfiltered offline JVM874/874 in51 suites, including29 new reauthentication cases; zero failures/errors/skips |
| Individual `ANDROID_SERIAL=emulator-5556 ./gradlew --offline :app:connectedDebugAndroidTest --rerun -Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.session.<class> --console=plain` | Final new classes plus affected classes below:68 distinct device cases, all task exits0 and actual nested XML names/classes verified; zero failures/errors/skips |
| `./gradlew --offline :app:assembleDebug :app:lintDebug --console=plain`; final `./gradlew --offline :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:lintDebug --console=plain` | Frozen-source rerun and archive-citation closure exit0; debug APK already freshly packaged by device execution, assembly reused it; all lint analyses executed, one existing NewerVersionAvailable warning and no errors |
| APK/preservation/whitespace | Asset and exported1–12 schemas, MainActivity/manifest/backup inputs byte-identical; packaged catalogue asset exact; no new QA class/probe/fixture-token/test-namespace markers in production dex; both repository checks clean |

Device classes/counts: SessionReauthenticationRoomTest14, SessionReauthenticationRoomVaultTest9,
SessionPreparationRoomVaultTest9, RoomSessionAuthorityTest14, RoomSessionAuthorityCorruptionTest11,
RoomSessionAuthorityConcurrencyTest5, RoomSessionAuthorityPreservationTest6. Repeated iteration/closure runs are not
additional distinct coverage. Device closure included real Keystore/file persistence, non-destructive file-DB/vault reopen,
R09b actual-adapter recognition, late Room fencing, rollback, retained intent and old-generation/Room-checked lease refusal.

Packaged debug APK SHA256: `f43a6e3a181959a8c7edfcc04408f35f6c3c99991168281db8692cb12ea2b605`.
AAPT2 confirmed the existing credential namespace exclusion in packaged legacy backup, cloud-backup and device-transfer
XML. No app constructor calls the new coordinator. Shared-docs remained unchanged. No schema or source asset was modified.
Source citations were rebased to the archived specification after test execution; no assertions changed during archive
closure, and final compilation/lint also passed on those citations.

## Residual obligations and stop

This is settled occupied ACTIVE replacement, NOT general same-account reauthentication or completed R09. Pending-refresh
reconciliation/terminal or malformed predecessor disposition and signed-out/missing-vault reauthentication remain open;
no permission to discard an uncertain pair is inferred. Legacy accepted-auth replay remains unrepaired. An already-started
write may commit after invalidation/cancellation/lost return; preserved durable reread, never compensation, owns recovery.

Full unfiltered instrumentation and two independent real Room stores through actual engine/isolated-service HTTP remain
R09/R10 integration gates BEFORE activation; they were deliberately not claimed here. R12a finite-edit/timer barriers,
core R10 convergence, application/offline established-account integration, API24, hosted CI, real Google/OEM/cloud transfer,
phone and deployment journeys remain separate outstanding gates. No backend, live account, private configuration,
legacy-data disposition or deployment action was performed. All declared scoped acceptance gates passed.

Stopped at advisory inert PREPARING/PREPARED eligibility. Production remains signed-out/local-only. Test-owned emulator
and isolated Android/AVD homes are released before successor ownership; no Gradle/device/backend ownership transfers.
