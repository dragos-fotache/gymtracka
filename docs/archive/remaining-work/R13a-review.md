# R13a acceptance — durable legacy graph and private import capture

**Accepted 2026-10-09, inert/private only.** Specification: [R13a](R13a-task.md).
This closes the first slice of the finite personal R13 plan, not import delivery, activation, UI or live readiness.

## Delivered boundary

Room13 adds independent `sync_legacy_import`/`sync_legacy_import_entries` through an additive12→13 migration. Schema1–12
and the actual bundled v1 asset remain byte-identical; prior Room entity definitions are unchanged.

Explicit `LegacyImportStore.capture/read` freezes complete genuinely local-only graphs, exact raw SQLite source/error
values and origin/first confirmed UUID/operation. Valid graphs preserve business data/IDs and atomically associate roots,
queue complete typed create-only imports in dependency order and reserve private PREPARING authority. Invalid source
commits immutable blocked evidence plus a private fence without association/outbox/cache/canonical writes. Empty storage
is a true no-op. Neither path returns an account handle, writes credentials/ACTIVE/cursor/baseline, sends HTTP, or changes
startup. Existing authority local-root refusal remains intact; ordinary null-account repositories refuse retained evidence.

Strict final and recovery checks retain raw source, exact wrappers, family-scoped generations, actual request/entry order,
Room/private identity, cache/ledger metadata and checked sqlite_sequence space. Repeat/reopen/lost result resumes the same
migration, not a second capture. Missing work is corruption, not definitive remote acceptance.

Durable implementation details live in [sync implementation](../../sync/implementation.md#durable-legacy-graph-and-private-import-capture)
and [architecture](../../architecture.md#room-database); remaining integration is in the
[roadmap](../../remaining-work-roadmap.md#remaining-r13-plan).

## Independent review

Fresh production Worker and independent QA used the required Muse Spark Contributor/high model with disjoint source/test
access. Coordinator supplied normative APIs/harness facts, reviewed both layers and exclusively ran Gradle. QA expectations
use independent SQL/complete wire literals, not production encoders.

Review corrections included strict journal column types; unknown ownership flags/nonstring instructions/multiple primary
muscles; checked retained sequence and per-family root generations; final raw business/metadata/ledger/cache rechecks;
shared source/request/authority recovery fencing; closed callback/storage failures and actual cancellation precedence even
when cancellation occurs during lease close. These preserve evidence instead of fixing/remapping domain values.

Tests additionally prove genuine caller cancellation after a Room commit runnable returns while caller result dispatch is
held, with a nonthrowing lease and durable reread/idempotent recapture. Lease-close error alone is not that proof. File
reopen, concurrent single capture/stale loser, rejected/corrupt/missing bytes, SQL/late-trigger rollback, source drift and
independent all-family complete import bodies are covered.

Earlier failures are retained as resolved evidence: initial instrumentation compilation had QA fixture coroutine/inner
receiver errors; a production rewrite had a stray brace; the first legacy gate failed two pre-acquisition close-count
oracles and a concurrent-loser expectation. The strengthened wire gate then exposed an active-repetition seed/oracle
mismatch (seed10, mistaken null expectation). Corrections followed the declared contract; no invariant assertion was
weakened to fit a production defect. Final fresh gates below passed.

## Verification environment and commands

Linux; Gradle9.8 daemon on provisioned Temurin JDK25 (shell Java27). Coordinator-owned disposable **headless API37 / Android17
x86_64, 16KB-page Google APIs Play system image** with normal native ADB registration (`emulator-5580`); no personal
AVD/device/account used. This serial is historical execution evidence, not reusable resource ownership.
All Gradle commands were serialized. No backend was needed or started.

Intended tasks executed (not merely cached passes); XML testcase classes/names/counts/failures/errors/skips were inspected:

| Gate | Actual command / result |
|---|---|
| Production/KSP + instrumentation compilation | `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --console=plain` — passed after corrections; schema13 exported |
| Final production + fresh full offline JVM | `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --rerun --console=plain` — **1031 cases, 0 failures/errors/skips**, external backend package excluded |
| Final legacy package | `ANDROID_SERIAL=emulator-5580 ./gradlew :app:connectedDebugAndroidTest --rerun -Pandroid.testInstrumentationRunnerArguments.package=com.dragosfotache.gymtracka.data.sync.legacy -Pandroid.testInstrumentationRunnerArguments.timeout_msec=45000 --console=plain` — **49 cases, 0 failures/errors/skips**, only LegacyImportCaptureTest |
| Additive12→13 preservation | Same fresh connected task, single `class=com.dragosfotache.gymtracka.data.gym.LegacyImportStorageMigrationTest` — **2 cases, 0 failures/errors/skips**, prior objects/rows/sqlite_sequence and next allocation preserved |
| Every supported start + actual asset | Single `class=com.dragosfotache.gymtracka.data.gym.GymDatabaseMigrationTest` — **3 cases, 0 failures/errors/skips**, includes starts1–12→13, copied bundled v1 and retained historical9→10 proof |
| Existing capture regression | Single `class=com.dragosfotache.gymtracka.data.sync.SyncCaptureTest` — **17 cases, 0 failures/errors/skips** |
| Existing authority/refusal regression | Single `class=com.dragosfotache.gymtracka.data.sync.session.RoomSessionAuthorityTest` — **14 cases, 0 failures/errors/skips** |
| Preservation/scope | SHA256 manifest checked for asset and schemas1–12; schema13 old entities structurally identical; both repository diffs/whitespace/link checks reviewed |

Each device filter was a separate task invocation; no comma-filter inference. Migration/foundation gates executed before
the final source-only lease-close correction; that correction changes no schema/capture guard/migration behavior and was
covered by the final full legacy package and fresh JVM. Connected tasks packaged debug/test APKs as prerequisites; that
is not a separate full assembly/lint/security audit. Existing catalogue FK-index/compiler and JDK protobuf Unsafe warnings
were nonfatal; no new source warnings or dependency/configuration changes were introduced.

## Limits and stop condition

- Captured/empty queue is **not** accepted/original-accepted import settlement. R13b must add durable per-import definitive
  progress and legitimate post-ack transitions before permitting original request/domain removal or complete activation.
  Fresh service/vault evidence must supply the initial target/operation and explicitly coordinate later same-account
  authority transitions without retargeting or recapture; this DTO is not service/network authority. Collision/rejection/
  partial remote acceptance retains exact source/request/errors privately.
- Real admission/rest/editor Save/Discard/Cancel, warning/consent before cloud-active replacement, HTTP import delivery,
  complete snapshot/protected publication and vault/Room recovery remain R13b; mandatory startup/UI remain R13c;
  foreground/edit/manual synchronization and package closure remain R13d.
- Fresh full unfiltered instrumentation, API24 startup/import/recovery and assembly/lint/APK/backup/security gates were
  deliberately deferred to R13c/d per specification; actual import HTTP/activation gates belong to R13b. No unavailable
  R13a-required gate is being presented as passed or silently deferred.
- No actual Google/provider/audience/signing, deployed TLS, personal account/phone, background recovery or public-release
  claim. R17a remains separately authorized. Existing legacy auth replay and unexplained R12c private-stop limitations
  are unchanged, not exercised/repaired here.
- Kotlin LSP initialization/empty indexing was unavailable for clean diagnostics; bounded source reads plus actual
  compilation were used. No clean-LSP-diagnostics claim.

Stop at this reviewed inert persistence/recovery boundary. Locally commit only this slice and its owning documentation;
no push/deployment/configuration action. Next eligible slice is **R13b**, through the standing fresh-coordinator handoff.
