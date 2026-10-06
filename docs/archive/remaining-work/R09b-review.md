# R09b acceptance review — 2026-10-06

**Accepted:** inert prepared vault/Room coordination and restart classification. This is not preparation-engine admission,
bootstrap, promotion, ACTIVE authority, reauthentication replacement, live UI/sync or app activation.

## Delivered boundary

- `SessionPreparationAuthority` reuses R09a Room12 reservation unchanged; RoomSessionAuthority changes only its
  implemented interface and method override. No schema/DAO/migration/storage primitive changes.
- `SessionPreparationCoordinator.prepare` checks exact signed-out authority and an empty/tombstone vault, reserves
  private checked generation/revision, verifies the returned AND reread reservation, CAS-writes one complete original
  PREPARED grant, then independently fences alternating durable rereads.
- `resume` uses the same exact reserved identity; empty credentials can be written only after another Room check.
  Matching prepared grant is read-only idempotence, preserving exact pending operation/key/body/token. Occupied/different
  credentials are never replaced, cleared or adopted. Legacy roots/provenance refusal is retained.
- `recover` only classifies stable PREPARING pairs as NeedsGrant/Prepared/Blocked. Non-PREPARING Room and wrong-origin
  preparation never inspect the vault. Results are nonsecret advisory observations, not write/network/visibility leases.
- Serialized operations, newest opaque fences and immediate invalidation reject late results. Failure/cancellation after
  a committed write is not rollback proof; retained bytes/operation recover by durable reread. No compensating cleanup,
  new uncertain key, expiry rewriting, clock/network action or grant freshness claim.

## Independent review and corrections

Fresh native Contributor/high Worker and QA kept disjoint production/test source allowlists; coordinator reviewed both
complete new layers. No backend source/private configuration or personal fixture access. Exact grant/account/origin/
generation/operation/phase/revision and pending scalar equality, full-row preservation and closed/redacted errors reviewed.

Production review caught missing durable Room checks immediately before prepared writes and incomplete process-fence
error/pre-load guards; Worker corrected these before its successful production compile. Wrong vault phase is consistently
IDENTITY_MISMATCH; NOT_PREPARING is reserved/unused. Test review corrected digits-only uppercase UUID and impossible
nested fixtures, read-only Blocked expectations, canceled-child scheduling/identity and actual mutation/late-result
oracles. Empty placeholder checks were replaced, not counted as coverage. QA reported two fixture iterations (suspending
helper and canceled-child await); final independent expectations were retained, not weakened to match implementation.

## Executed verification

Linux local host, JDK25/Gradle9.8, test-owned isolated API37/Android17 Google Play x86_64 emulator with verified16384-byte
page size and boot completion. Native default ADB registration used; no personal AVD/device/database/account. Every
Gradle run had one explicit serialized owner slot. The following are actual scoped results, not old evidence.

| Command / check | Result |
|---|---|
| Worker: `./gradlew :app:compileDebugKotlin --console=plain` | BUILD SUCCESSFUL; production compile executed, no tests inspected |
| QA: `./gradlew :app:testDebugUnitTest --tests 'com.dragosfotache.gymtracka.data.sync.session.SessionPreparationCoordinatorTest' --rerun --console=plain` | Fresh33 new cases; XML inspected by QA and coordinator; zero failures/errors/skips |
| QA: `./gradlew :app:compileDebugAndroidTestKotlin --console=plain` | BUILD SUCCESSFUL; compilation, not Android behavior proof |
| Coordinator: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --rerun --console=plain` | Fresh full offline JVM845 cases/50 suites, including33 new cases; zero failures/errors/skips. Compilation/KSP dependencies legitimately up-to-date |
| Coordinator: `ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest --rerun -Pandroid.testInstrumentationRunnerArguments.package=com.dragosfotache.gymtracka.data.sync.session --console=plain` | BUILD SUCCESSFUL; fresh72 focused cases; executed nested XML classes/names checked; zero failures/errors/skips |
| Coordinator: `./gradlew :app:assembleDebug :app:lintDebug --console=plain` | BUILD SUCCESSFUL; assembly reused the newly packaged debug APK from connected run; lint executed with zero errors and one existing NewerVersionAvailable warning for org.json |
| APK ZIP/DEX + `aapt dump badging/xmltree` | Correct application/min24/target37, INTERNET/VIBRATE/WAKE_LOCK retained; ordinary backup enabled and production vault excluded from legacy/cloud/device transfer; packaged asset exact; named new test/helpers, synthetic test credentials, credential files/keys absent |
| Accepted-tree byte comparison; both repository whitespace checks; new-file/staged inspection | All12 exported schema snapshots and bundled v1 asset byte-identical; no schema/manifest/build/config change; no shared-docs change |

Focused Android executed classes: RoomSessionAuthorityTest14, Corruption11, Concurrency5, Preservation6,
SessionVaultAndroidTest27 and **SessionPreparationRoomVaultTest9** =72. The new nine prove actual Room plus actual
Keystore/file grant storage/reopen, reservation-only resume, stale preparation/fenced resume, wrong ACTIVE phase,
post-rename uncertain failure followed by exact revision1 reread, occupied pending preservation, a real Room fence while
actual committed vault CAS return is delayed, and whole representative domain/outbox/cursor/cache/canonical/stage-child/
catalogue-row preservation. This is NOT fresh full unfiltered instrumentation.

## Remaining obligations and stop

No test failure or R09b blocker remains. Stopped at private prepared observations; production remains signed-out/local-only.
No schema change means no new migration is required; existing migration coverage is reused, not claimed freshly rerun.
R09c should settle one bounded inert prepared-session engine admission/bootstrap integration slice, without recreating
storage/drivers or enabling app/UI. Subsequent complete ready transition/promotion, same-account occupied-credential
reauthentication and live activation remain required. No legacy local-only disposition is inferred.

Full unfiltered instrumentation and real Room-engine/backend convergence remain R09/R10 integration gates before
activation; R12a real finite-edit/timer barriers remain mandatory. API24, OEM/cloud transfer, hosted CI, real Google,
owner-approved phone/account/data and deployed endpoint journeys are unverified external/later gates. This network-free
slice neither required nor executed a backend gate. No push/deployment/private configuration/live action was performed.

Test-owned emulator/AVD cleanup and build-slot release verified before transfer; default ADB/Gradle resources remain.
Only the extra task-created R09b contributor panels were closed by this coordinator; no original/unrelated pane was
closed or focused by coordinator command. No contributor panel remains in the final live inventory, so none is assumed
transferable. Fresh sessions, not new panels, are required; reuse suitable task-owned panels if present at next pickup.
