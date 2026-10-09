# R13b — acceptance review

**Accepted: private opt-in import delivery and complete activation/recovery only.** Default startup remains local-only;
account screens/startup policy/triggers remain R13c–d. This acceptance supplies no Google/deployment/configuration/phone
permission. The [specification](R13b-task.md) owns the bounded assignment and independent contributor contracts.

## Delivered boundary and important review findings

Room14 adds independent current-lineage and accepted-batch receipt tables without changing old entities, exported1–13 or
the bundled v1 source asset. Opt-in facade integration captures under the SAME real held transition lease, installs the
matching private grant, records original active-import replacement consent, settles unchanged requests, establishes a
complete snapshot/tail and publishes with coherent vault/Room activation recovery. Empty installs create no migration.
Same-account fresh grants advance current session fences while preserving original capture provenance, request IDs/bytes,
source, receipts, consent and encrypted superseded evidence. Collision/rejection/invalid or foreign evidence remains
private; no overwrite, ID remapping, destructive clean start, reassignment or automatic evidence cleanup exists.

Independent final review corrected materially weaker validation: complete raw-source closure/local-only provenance and
payload/receipt correspondence, family-qualified identities, empty-queue/orphan fencing, exact post-SQL evidence pins,
transactionally fenced consent and only the exact known completion anchor advance. Canonical acknowledgment, receipt and
head removal commit together. Publication failure preserves already accepted receipts, not a fictional remote rollback.
Source/admission guard failures and lease-close/caller cancellation remain fail-closed. Real finite edits/whole-second rest
are drained once; explicit-save editors refuse without hidden save/discard.

Local Private activation-stop recurrence was investigated as a scripted snapshot/pull checkpoint mismatch and corrected
without extra retries or relaxed validators. Real HTTP normal/whole-graph activation now passes. This does **not** explain
or repair the earlier R12c historical stop or the retained legacy accepted-auth-replay limitation; those risks remain visible.

## Executed gates — 2026-10-09

Repository-root commands, owned headless Pixel_6a API37/x86_64 16K emulator, guarded disposable PostgreSQL16/backend and
explicitly trusted loopback TLS relay. Contributors used fresh independent Muse Spark Contributor/high sessions; coordinator
used Codex GPT6.1-sol/high. Host restart resumed the SAME R13b native sessions and models, not new/cross-role assignments.
Gradle commands were exclusively serialized; lint froze both source layers. Counts below are actual nonempty XML execution,
not compilation/cached test passes. Successful HTTP domain outcomes came from the real backend, never simulated.

| Gate | Command / selection | Result |
|---|---|---|
| Kotlin/Room/export | `./gradlew :app:compileDebugKotlin --console=plain` | Executed Kotlin/KSP on final corrected source; success. Export14 adds only progress/acceptance tables; pre-existing entities/views and snapshots remain unchanged. |
| Offline JVM | `./gradlew :app:testDebugUnitTest --rerun --console=plain` | Fresh final offline task:1035 tests /65 classes; zero failures/errors/skips, no backend-package classes. |
| Sync storage/session regression | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.dragosfotache.gymtracka.data.sync -Pandroid.testInstrumentationRunnerArguments.timeout_msec=120000 --console=plain` | Fresh final962 tests /73 classes; zero failures/errors/skips. Scoped sync package only, not unfiltered default instrumentation. |
| New activation/source-closure matrix | Same connected task with `class=com.dragosfotache.gymtracka.data.sync.session.LegacyImportActivationTest` | 26 tests; zero failures/errors/skips. Consent, immutable source/provenance/receipt corruption, same-account lineage, genuine complete materialization, cross-family identities and offline edits; included again in sync package. |
| Real finite-edit/rest/editor admission | Same connected task with `class=com.dragosfotache.gymtracka.ui.LegacyImportAdmissionTest` | 6 tests; zero failures/errors/skips. Actual Room edits, whole-second rest, held admission, editor refusal and cancellation/release. |
| Additive13→14 migration | Same connected task with `class=com.dragosfotache.gymtracka.data.gym.LegacyImportProgressMigrationTest` | 3 tests; zero failures/errors/skips. Captured lineage initialization, blocked/empty preservation, no fabricated receipts/consent. |
| Every supported start / copied asset | Same connected task with `class=com.dragosfotache.gymtracka.data.gym.GymDatabaseMigrationTest` | 3 tests; zero failures/errors/skips. Loop covers1..13→14 and copied bundled v1 asset, plus historical9→10 preservation. |
| Host relay controls | `./gradlew :app:testRealBackend -Pgymtracka.realBackendConfig=<owned-config> --tests 'com.dragosfotache.gymtracka.data.sync.backend.RoomBackendRelayTest' --console=plain` | 3 tests; zero failures/errors/skips. Exclusive metadata refusal, token-scoped response loss AFTER real commit, and distinct same-account setup+actual refresh grant. |
| Real file-Room/vault HTTP | `./gradlew :app:connectedDebugAndroidTest -Pgymtracka.roomBackend=true -Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.roombackend.RoomBackendLegacyImportTest -Pandroid.testInstrumentationRunnerArguments.roomBackendPort=<owned-port> -Pandroid.testInstrumentationRunnerArguments.roomBackendCertificate=<public-DER-base64> -Pandroid.testInstrumentationRunnerArguments.timeout_msec=120000 --console=plain` | Fresh final14 tests; zero failures/errors/skips. Whole seven-root graph including saved-blank/empty draft/history/optional metadata/nullable inputs, exact source/request/receipt retention and materialization; partial/lost response+file reopen/exact duplicate, fresh same-account grant, real collision, ack/publication rollback, failed snapshot, clean/corrupt completion gap and HTTP-free recovery. |
| Assembly/lint | `./gradlew :app:assembleDebug :app:lintDebug -Pgymtracka.roomBackend=true --console=plain` | Success. Assemble reused current debug artifact; final lint analysis executed:0 errors /1 pre-existing NewerVersionAvailable warning. Opt-in instrumentation compiled and executed in HTTP gate. |
| APK/backup boundaries | ZIP/DEX marker/source-asset checks; build-tools36 `aapt2 dump permissions` / XML trees | No host relay/JDBC/real-backend fixture/test credential markers in debug DEX. Packaged source asset equals checked-in v1 bytes. INTERNET/VIBRATE/WAKE_LOCK retained; manifest backup/data-transfer rules reference production vault exclusions. |
| Launcher safeguards | `python3 -B -m unittest discover -s scripts/tests -p 'test_*.py'` | 11 passed. Fresh actual isolated startup subsequently verified guarded configuration/relay ownership and real HTTP behavior. |
| Scope/docs | Both repository diffs, exported schema comparison, staged whitespace and relative links/anchors | Slice-owned production/tests/docs only; no source asset/history-schema changes, private configuration or backend source changes. |

Initial failures were preserved as failures, not passes: Docker bridge setup failed before configuration publication until
owner host restoration; source consent SQL binding and legitimate completion-pin transition defects were corrected.
Independent fixtures needed canonical UUIDs, valid JSON/namespaces/raw envelopes/checkpoints, source-tag grammar and numeric
weight assertions; rollback oracles were corrected to preserve capture's existing ledger reservations and prior committed
receipts. Expected data remained independently seeded; no production encoder supplied purportedly independent wire oracles.

## Limits and stop condition

No required R13b gate remains unavailable. Full unfiltered default current-API startup instrumentation and affected API24
startup/import/recovery remain R13c; combined personal-package journeys/regressions remain R13d. Actual Google/audience/
signing/trusted deployed HTTPS/phone/account operations remain separately authorized R17a. Kotlin LSP initialization/empty
indexing supplied no clean-diagnostics claim. Synthetic backend setup is not a successful real Google exchange or deployment.
Legacy auth replay and superseded encrypted evidence remain retained; no expiry rounding, uncertain rekey or revocation claim.

Stopped at an explicit opt-in private import-to-complete-activation boundary. No default startup/account UI or automatic
triggers were enabled. Scoped local commits and fresh-coordinator R13c transfer follow the standing workflow; pushes and
live actions require separate authority. Owned test resources/Gradle slots are settled before transfer.
