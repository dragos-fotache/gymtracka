# R12b acceptance — projection observation and feature refresh

**Accepted 2026-10-07.** [Specification](R12b-task.md) defines the bounded assignment.
Coordinator independently reviewed production, independent QA tests, new files and executed XML; contributor completion
and counts alone were not acceptance. Fresh native Worker/QA used disjoint production/test access under the workflow.

## Delivered boundary

- Narrow cold repository observation of committed selected-scope materialized epochs, with immutable session fencing,
  registration before the first read, distinct filtering, metadata validation and device-revocation detection.
- Catalogue/picker, exercise detail, routine list/detail/editor, history list/detail and active-feature refresh at equal
  IDs/parent revisions. Current scoped exercise joins supply every custom-reference label.
- Latest-request/accepted-edit/scope guards reject late loads and preserve accepted drafts. Pure catalogue/action lists
  remain readable during unrelated pending/failed edits; owned deletes reload after their own job. Reads do not clear
  finite failure evidence or join their own tracked write.
- Same-ID field reconciliation preserves sanitized-equal raw numeric text and stable focus, including insertion before
  a surviving set ID. Per-opening rest-dialog snapshots retain unconfirmed values through publication.
- Configured-rest wheel settling is stable/sequential and avoids self-triggered snap animation; countdown/timer ownership,
  hardware, app shell, database/schema and sync activation remain unchanged.

Enduring details belong in [architecture](../../architecture.md#projection-observation-and-feature-refresh) and
[sync implementation](../../sync/implementation.md#protected-publication), not this evidence record.
Production remains signed-out/local-only: one fenced initial0, no publisher/engine/account visibility grant at startup.

## Important review corrections and failed evidence

1. Removed a read-before-observer-registration gap; moved read-only model loads outside collectors so blocked old reads
   cannot stall newer epoch/revocation processing. Accepted generations, stream failure and disposed-scope guards fence
   late results; post-edit reloads do not mint an older queued write into the newest field snapshot.
2. The unchanged root predecessor/disposal regression initially failed **before** its gate release, while waiting for
   picker rows. It did not prove a lost autosave. Removing unrelated global drains from pure queries fixed the read
   dependency; final root and routine/history list tests preserve write order and retained failure evidence.
3. Rest-dialog tests initially passed value/persistence assertions but failed strict Compose idleness. A single-method
   diagnosis reproduced non-idleness on opening, before publication/disposal. The existing wheel snap effect was keyed
   by the scrolling flag that its own animation toggled. Stable sequential collection plus a misalignment guard fixed
   opening, actual wheel selection, publication, Done and cleanup without relaxing timeouts or suppressing failures.
4. Independent fixture corrections retained stronger oracles: unique child IDs/completed history sets; custom-only rename
   deltas at unchanged parent revisions; live rather than restarted collectors; fake persistence after successful writes;
   real shared finite admission for live-screen publication; cache-epoch-ahead/materialized-unchanged contrast prevents
   accidentally observing baseline progress. The existing routine name-order test's fake now stores the
   successfully written name; its call-order and `Second` assertions are unchanged. Existing root/leaf regressions were
   not weakened. Initial missing `isActive` imports and one test override-parameter warning were corrected.

Failed root/dialog XML is retained locally under `/tmp/r12b-evidence`, `/tmp/r12b-final` and `/tmp/r12b-diagnosis`.
It is failed iteration evidence, not acceptance or extra passed cases. Final successful scoped evidence is under
`/tmp/r12b-settle-final`, `/tmp/r12b-final-pure-lists` and `/tmp/r12b-baseline-contrast-final`; final JVM logs/XML are under
`/tmp/r12b-evidence/final-full-offline-jvm`. Temporary artifacts are not required project inputs.

## Executed gates

Environment: repository-root Gradle9.8/AGP9.4.1, Kotlin2.4.20, Room2.8.5, Compose BOM2026.09.00, Linux/JDK25.
Device: fresh empty test-owned API37/Android17 emulator, 16384-byte pages, isolated Android/AVD homes; no personal AVD,
Google/account data, backend operation or live journey. Every Gradle/device slot was explicitly serialized.

```sh
./gradlew :app:compileDebugKotlin --console=plain
./gradlew :app:compileDebugAndroidTestKotlin --console=plain
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --rerun --console=plain
# One class per invocation; no comma-separated runner filter.
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=<class below> \
  -Pandroid.testInstrumentationRunnerArguments.timeout_msec=120000 \
  --rerun --console=plain
```

Main Kotlin/KSP compiled after final production corrections; instrumentation compiled. The final unfiltered offline JVM
**test task executed freshly**: 985 cases across58 XML files/classes, zero failures/errors/skips; includes16 new refresh
cases and existing app/feature/edit/timer regressions, excludes the separately configured real-backend package. Valid
cached compilation dependencies were reused, not presented as fresh test execution. Final JVM output has no new Kotlin
warning. Connected tooling reports nonfatal protobuf/JDK `sun.misc.Unsafe`, existing experimental source-set option
and Gradle deprecation warnings when configuration is recalculated; configuration/dependencies are unchanged, with no
suppression.

| Individually executed device class | Cases | Result |
|---|---:|---|
| `data.sync.ProjectionObservationTest` | 9 | Pass |
| `ui.ProjectionFeatureRefreshTest` | 5 | Pass |
| `ui.AppFiniteEditGateTest` | 2 | Pass |
| `ui.PausedLeafRefusalTest` | 1 | Pass |
| `ui.FocusedComposeInteractionTest` | 14 | Pass |

All class names above have prefix `com.dragosfotache.gymtracka.`. **31 distinct scoped device cases**, zero failures/errors/
skips, nonempty fresh XML with the intended classes/names verified. Repeated method/class runs are not additional cases.
The last production change only adjusted pure routine/history list scheduling: root and full focused leaf classes were replayed
fresh afterward (2/14), and the final full JVM includes the pending/retained-failure pure-read oracle. The final
assertion-only baseline/materialized contrast was then compiled and replayed in the full9-case Room class on another
empty owned emulator. Other final device classes exercised unchanged relevant behavior; no full unfiltered
instrumentation claim is made.

Both repository whitespace checks passed. Bundled v1 asset and all exported schema snapshots are unchanged; Room stays12,
no migration required. Reviewed scope introduces no production test fixtures/markers, credentials, manifest/dependency/
backup changes or unrelated files. `shared-docs` is unchanged.

## Deferred gates and stop

Full unfiltered instrumentation, explicit debug assembly/lint/APK audit and private-scope/app/timer integration remain
**R12c package closure**, as declared before delegation. Connected-task packaging is not a fresh standalone assembly/lint
claim. Core R10 convergence, R09h authority/activation, API24, hosted CI, actual Google/phone/laptop and live deployment
remain their own gates. No unavailable required R12b gate is treated as passed. LSP initialization was intermittent;
bounded source reads/compilation were used, not a clean-LSP-diagnostics claim.

No remote finish/delete navigation, active-pill reconstruction, authoritative canonical timer-impact decision or account
rebinding is delivered. R12c still needs delivered app-facing R09h authority/core R10; R09h needs R12a, not all R12. Legacy
local-data disposition and the documented auth-replay limitation remain unresolved; no import/delete/reassignment or
credential cleanup is authorized.

Closure requires the accepted scope's local commit, prepared current handoff and release of all owned task build/device
resources. **Stop and report to owner; no successor launch or R12c implementation under this request.**
