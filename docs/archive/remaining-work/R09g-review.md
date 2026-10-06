# R09g acceptance review — inert same-account recovery

Accepted 2026-10-06. [Specification](R09g-task.md) owns the frozen slice contract;
[current implementation](../../sync/implementation.md#inert-retained-account-recovery-and-superseded-evidence) owns enduring facts.
[P17](../../../shared-docs/docs/backend-contract/open-decisions.md#confirmed-product-decisions) owns preserve-and-supersede.

## Delivered boundary

Unused SameAccountRecoveryCoordinator composes retained-account signed-out/missing-vault recovery, explicit fresh-grant
supersession of unsuccessful/strict-legacy uncertain predecessors, and interrupted-transition resumption. Required shared
physical-DB owner then shared credential mutex span effects, full alternating pair rereads and closed failure mapping.
Only configured origin/service UUID with known Room provenance is admitted. Local-only roots, foreign/unreadable/unknown
provenance and incompatible same/newer bindings stay preserved/private. No HTTP, clock/UUID generation or app caller.

Room12 reuses journal/device storage: checked private TRANSITIONING reservation, full target PREPARED vault CAS, existing
PREPARING finalization. Resume reuses the exact operation/generation. Cached domain, children, relationships, canonical
ledger/watermarks, queue/frozen bytes, checkpoints/stages, foreign scopes and catalogue remain unchanged.

Vault v1 bytes remain exact without archives; archive-bearing records use strict v2. One atomic encrypted CAS installs
the complete supplied grant plus exact uncertain predecessor revision/grant/deadlines/operation/key/body/token evidence.
Flat defensively owned archives are permanently excluded from credential selection/replay/rebinding; CAS cannot remove,
modify or silently clear them. Ordinary refresh, preparation/completion and R09e replacement preserve archives semantically.
No retirement, old-token rekey, expiry rounding, cleanup or server-revocation claim. Storage labels/results are not visibility.

## Independent review and decisions

Fresh native Worker and QA used meta/muse-spark-1.3-contributor/high with disjoint production/test sources. Native
openai-codex/gpt-6.1-sol/high coordinator reviewed both layers and owned integration, resources and serialized Gradle gates.
Kotlin mcpls remained initializing on bounded attempts; exact reads/diffs/compilation were used, not a clean-LSP claim.

- Production corrections closed mutable multi-entry archive escape, missing mandatory uncertain-source append, count-driven
  allocation, archive-blind comparisons, archived-token reactivation, incorrectly refused ACTIVE predecessors, incomplete
  terminal pair/root checks and error precedence gaps. Both seam exceptions and synthesized source/pair failures reread
  durable Room under the owners; cancellation/newest fencing and strict authority faults remain closed.
- The owner selected preserve-and-supersede before affected implementation. Review then found a legitimate signed-out
  pre-CAS gap: Room7/vault6 reserves Room8 before vault installation, so adjacency-only resumption cannot recover it.
  The owner selected strictly older same-origin/same-UUID **evidence-only** recovery with a fresh service-confirmed grant,
  exact existing reservation and no schema expansion, old-credential authority or implicit generation restart.
- QA corrected invalid model/storage seeds, wrong revision/category oracles, ungated cancellation, pre-write-only rollback
  claims, disconnected legacy replay, incomplete reopen preservation and nominal rather than actual engine/refresh paths.
  Final tests use independent v1/v2 bytes, actual strict HTTP replay on the same stores, real post-write trigger/cancellation,
  true fault/drift seams, real encrypted pre/post-commit reopen and genuine recovery→bootstrap→completion→reauth propagation.
- Initial integration compilation failed on duplicate method/local declarations, a misplaced preservation baseline and
  guessed bearer/fixture names. Initial focused JVM failed three stale/invalid QA oracles; initial scoped device failed
  eight fixture/oracle paths, including incoherent signed-out cache state. These were corrected against the specification,
  not by weakening production or assertions.
- The first unfiltered device run executed831 with one failure: an old storage roundtrip called activation-operation
  replacement an ordinary refresh. Its fixture now preserves binding/operation, retaining grant/pending/reopen/tombstone/
  ABA checks. Fresh full rerun831/831 passed. Failures are not counted as passes.

## Executed gates

Repository-root offline Gradle9.8; test-owned API37/Android17 x86_64 emulator37.1.11 using the installed37.2 PlayStore16KB
image. Actual boot completion and16384-byte page size verified. Every Gradle had an explicit exclusive Worker compile-only
or coordinator integration/acceptance slot; both roles froze sources for lint. No unrelated ADB/Gradle resources restarted,
backend source/configuration, personal data/device, Google/account or deployment input used.

| Gate | Actual evidence |
|---|---|
| `./gradlew --offline :app:compileDebugKotlin --console=plain` | Worker W1/W2/W3 and final coordinator policy compile executed Kotlin/KSP, exit0; no new-source warnings |
| `./gradlew --offline :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest --tests 'com.dragosfotache.gymtracka.data.sync.session.SameAccountRecoveryCoordinatorTest' --tests 'com.dragosfotache.gymtracka.data.sync.session.VaultSupersededEvidenceTest' --rerun --console=plain` | Corrected both layers compiled; fresh focused42/42 (26 coordinator,16 archive), zero failures/errors/skips |
| `ANDROID_SERIAL=emulator-5556 ./gradlew --offline :app:connectedDebugAndroidTest --rerun -Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.session.SameAccountRecoveryRoomVaultTest --console=plain` | Fresh13/13 real Room/Keystore/file/reopen, including pre-CAS signed-out gap and post-commit recovery; exit0 and actual XML names verified |
| `./gradlew --offline :app:testDebugUnitTest --rerun --console=plain` | Fresh unfiltered offline JVM939/939 in54 suites, zero failures/errors/skips; real-backend package deliberately excluded by established offline task |
| `ANDROID_SERIAL=emulator-5556 ./gradlew --offline :app:connectedDebugAndroidTest --rerun --console=plain` | Initial831/1 failure retained above; corrected fresh full unfiltered831/831 in72 classes, zero failures/errors/skips, exit0. Includes13 new cases,27 Android vault cases, migrations and existing persistence/sync/HTTP/session/Compose/app flows |
| `./gradlew --offline :app:assembleDebug :app:lintDebug --console=plain` | All unsuppressed main/unit/device lint analyses executed, exit0; one existing NewerVersionAvailable warning, zero errors. Assembly reused debug APK freshly packaged during the device gates |
| `./gradlew --offline :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest --rerun :app:assembleDebug :app:lintDebug --console=plain` | Archive-citation closure compiled both test layers, freshly reran full offline JVM939/939 and re-executed affected test lint, exit0; no executable/assertion changes |
| APK/preservation | Packaged database byte-exact; source asset/exported1–12 schemas, manifest/build/backup inputs unchanged. New QA/helper/namespace markers absent from production dex. AAPT2 confirms credential namespace exclusions for legacy/cloud backup and device transfer |

No schema/entity/asset change or migration. APK SHA256:
`ad7f7a9ac38fd7fbd56e24a60e7d5583dbedf4bc7d8a7465bdc86577c9bc9d44`.
Compilation/runtime emitted existing AGP/deprecation, old test-source and protobuf Unsafe warnings; no suppression or
configuration changes. Repeated/filter passes are not additional distinct coverage or substitutes for the full gates.

## Stop and residual obligations

Stopped at inert safely recoverable same-account PREPARING/PREPARED eligibility. Production remains signed-out/local-only;
no construction/recovered app handle, repository/UI rebinding, timer drain, visibility or live activation delivered.
Historical incompatible accepted replay remains unrepaired; explicit fresh-session supersession retains/fences its original
uncertain evidence instead. Archives have no automatic pruning; future separately authorized sign-out/deletion must account
for all credential material. Unreadable/foreign/unknown-provenance states and legacy local-only disposition remain closed.

R09h requires R12a real finite-edit/timer barriers and core R10 two-independent-Room-store actual-engine/isolated-HTTP
convergence before production activation. R11 Google adapter and R13 account UI remain separate. API24, hosted CI, real
Google, OEM/cloud transfer, owner phone/laptop TLS and deployment journeys are unverified milestone gates, not waived.
No automatic R09i/sub-slices or branch substitution around these dependencies. Escalate next-task sequencing to the user.

The test-owned emulator is stopped and isolated Android/AVD homes removed. Default ADB/Gradle resources remain untouched;
no contributor Gradle/device/backend ownership transfers. Archive citation/document closure is validated separately without
changing assertions or executable behavior.
