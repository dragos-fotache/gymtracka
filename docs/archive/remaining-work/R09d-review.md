# R09d acceptance review — 2026-10-06

**Accepted: inert private completed-preparation and exact vault promotion coordination.**
[Specification](R09d-task.md). Production remains signed-out/local-only; no app activation or repository rebinding.

## Delivered boundary

- Internal `PreparedSessionEngineCoordinator.completePreparation(requiredBarrier, budgets...)` freshly admits the exact
  PREPARING/PREPARED pair, reuses the actual R07 push-before-pull/committed terminal-tail callback, and holds the existing
  physical-DB owner uninterrupted. No caller-supplied progress/result authorizes completion. Retained stages, missing
  baseline/checkpoint, retained delivery errors, budget/transport stops and newer intent never complete preparation.
- Paired protected publication reuses the R05 whole-graph/collision/generation implementation. Complete projection/cache
  materialization and exact journal/device ACTIVE/ready transition commit in ONE Room transaction. Checked journal
  revision advances once, generation/origin/account/operation stay retained; full postwrite checks and cancellation
  protect domain/cache/authority/device rollback. Ordinary bootstrap and public publication remain unchanged/private.
- Fresh post-tail vault pin admits legitimate same-completion refresh rotation. After known Room return and lease close,
  exact phase-only PREPARED→ACTIVE CAS preserves the full grant and pending operation/key/body/token, including whitespace.
  Room/vault/Room/vault/Room checks before CAS and after promotion compare full coherent readiness and semantic records.
- `recoverCompletion()` holds the same owner and rereads the exact ready journal/device/cache/checkpoint/no-stage/
  no-outbox/no-local-root state in one Room transaction per observation. It promotes only the matching retained PREPARED
  record, or observes matching ACTIVE idempotently. No HTTP, refresh, clock/UUID, barrier, publication or Room write occurs.
  This is completion-gap recovery, not general activated-account recovery after subsequent editing.
- Invalidation/newest invocation retires old capabilities; an already-started commit can remain durable despite a lost,
  canceled or invalidated result. No compensating cleanup, grant replacement, generation restart or fake two-store
  atomicity. Results carry no handle or reusable authority, and failures/displays stay closed/redacted.

Six production files and four new QA files; Room12, entities/DAOs/migrations, exported schemas, bundled source database,
UI/application construction, transport, manifests/backup rules, build configuration and shared contract remain unchanged.

## Independent review and corrections

Fresh native coordinator `openai-codex/gpt-6.1-sol`/high and fresh native Worker/QA
`meta/muse-spark-1.3-contributor`/high were verified. Disjoint source allowlists applied; QA used independent wire literals,
real Room/Keystore/file storage and controlled timing, not production-generated expected bytes. Coordinator reviewed both
layers and exclusively owned serialized Gradle/device gates.

Production review corrected operational barrier/missing-credential mapping; stale authentication mapping; full semantic
post-tail vault equality; pre-CAS rereads; coherent single-transaction ready observations rather than split authority/cache
and checkpoint reads; exact pre/post-CAS alternating rereads; and strict post-ACTIVE checks after the lease callback.
Coordinator inserted one omitted recovery pre-CAS stable check despite a contributor report claiming it was present.
No acceptance was based on reports or test totals alone.

Initial new-device iterations failed on independent QA setup/oracle defects: omitted canonical `:443` origin, invalid
foreign grant/binding fixture, a durable fence placed before rather than during invocation, and an invented pre-tail
no-HTTP overflow assertion. Corrected to the authoritative contracts without relaxing domain/privacy requirements.
Final review strengthened actual materialized-domain assertions, exact publication epochs, genuine shared-owner contention,
postwrite trigger/count-zero/cancellation rollback, uncertain post-CAS results, expired/pending phase promotion and refresh
rotation DURING completion rather than before admission. Failed iteration logs/results remain historical evidence, not passes.

## Executed gates

Local Arch Linux x86_64; Gradle9.8.0, launcher JDK27 with configured compatible JDK25 daemon; Kotlin compilation/KSP.
Disposable test-owned API37/Android17 Google Play x86_64 emulator; boot complete and verified16384-byte page size. No
personal phone/account/Google/backend action. Gradle slots serialized; default ADB/Gradle resources left intact.

| Actual command/check | Result |
|---|---|
| `./gradlew --offline :app:compileDebugKotlin --console=plain` | Executed production Kotlin/KSP pass |
| `./gradlew --offline :app:compileDebugAndroidTestKotlin --console=plain` | Executed Android-test Kotlin pass; later device tasks compiled final test corrections |
| `./gradlew --offline :app:testDebugUnitTest --rerun --console=plain` | Fresh full offline845 cases in50 classes; zero failures/errors/skips; real-backend package excluded by established task configuration |
| Individual `ANDROID_SERIAL=emulator-5556 ./gradlew --offline :app:connectedDebugAndroidTest --rerun -Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.<class> --console=plain` | All listed final classes actually executed; every task exit0 and inspected nonempty nested XML with zero failures/errors/skips |
| `./gradlew --offline :app:assembleDebug :app:lintDebug --console=plain` (final closure) | Pass; final lint zero errors/fatal and one existing `NewerVersionAvailable` warning |
| Asset/schema hashes, APK assets/DEX markers, scope, new-file whitespace, both `git diff --check` | Pass; unchanged v1 asset and exported1–12 schema hashes; no completion/engine test markers or fixture/config assets in debug APK |
| Changed-production mcpls cached diagnostics | Empty cached diagnostics for six files, indexing=false; compilation above is the execution gate, not a fresh LSP-analysis claim |

Actual individually executed class names (prefix `com.dragosfotache.gymtracka.data.sync.`):

| Class suffix | Cases |
|---|---:|
| session.PreparedSessionCompletionTest | 14 |
| session.PreparedSessionCompletionRecoveryTest | 10 |
| session.PreparedSessionCompletionFenceTest | 18 |
| session.PreparedSessionEngineTest | 8 |
| session.PreparedSessionEngineFenceTest | 12 |
| session.PreparedSessionEngineRecoveryTest | 9 |
| session.SessionPreparationRoomVaultTest | 9 |
| ProjectionPublicationTest | 33 |
| ProjectionPublicationGraphTest | 26 |
| SnapshotBaselineTest | 10 |
| SnapshotBaselineFenceTest | 12 |
| SyncDeliveryTest | 7 |
| SyncDeliveryFenceTest | 6 |

**42 new +132 affected regression =174 focused Android cases.** Completion/recovery were rerun after final strengthened
phase-promotion and same-completion rotation oracles; fencing assertions were unchanged after its successful closure
except an honest comment about pre-publication lease revocation. No filtered pass is presented as full instrumentation.

Final debug APK SHA256: `609b6fb6bad3907353dbbcb9ea004459a700deb67e91f9477b5f95fe3e4e4846`.
Routine experimental AGP/deprecated-Gradle notices are nonfatal; no new security/configuration warning was introduced.
Changed documentation relative files/anchors and new-file whitespace were validated. Test specification provenance
comments were rebased to the archive after execution; no test assertions changed. Owned emulator stopped, isolated
Android/AVD homes removed, and the coordinator Gradle slot released before transfer; default resources stayed untouched.

## Residual obligations and exact stop

- No fresh unfiltered instrumentation or two-store real-service HTTP convergence claim. These remain R09/R10 integration
  gates BEFORE production activation. API24/hosted CI/live Google/OEM transfer/deployed personal journeys remain unverified
  milestone gates. No schema change required a new migration gate; existing supported-start/copied-v1 evidence is not fresh.
- R12a live finite-edit/timer barriers and core R10 convergence still precede production projection/session activation.
  Test-controlled barriers never authorize a no-op barrier in an editing app.
- Same-account occupied-credential reauthentication is next bounded R09e work. Application activation/recovery, identity/UI
  integration and offline established-account journeys remain later obligations; no general account transitions enabled.
- Legacy local-only data disposition remains unresolved and refused unchanged; no import/deletion/reassignment chosen.
  Historical accepted-auth replay precision remains retained, not repaired or permission to rekey uncertain credentials.
- No push/deployment/private configuration/real account action is authorized by this acceptance. Preserve existing history.

Stop implementation here. Extract durable facts, archive the spec, replace roadmap/handoff, commit this scoped slice
locally and perform the standing fresh-coordinator transfer. Contributor sessions/resources do not transfer by inference.
