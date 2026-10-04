# R05b — fenced canonical batches acceptance

Status: **ACCEPTED, 2026-10-04**, within the [R05b specification](R05b-task.md).
No R05c, visible publication, engine/app activation, commit/push or deployment is authorized.

## Delivered boundary

`CanonicalStore` and its scoped transaction capability write complete validated workout/routine/custom observations and
unknown-root DELETE evidence to the existing independent v10 ledger. Account/session/cache-epoch fences, pre-suspension
input ownership, revision arbitration, equal-revision authoritative comparison, known family bindings, exact watermark
preservation and atomic dirty marking are delivered. Older/equal-only observations leave bookkeeping/body unchanged.

Canonical writes change no visible domain rows, outbox requests/order/errors, device identity, pull checkpoint or
last-success value. No baseline establishment, snapshot-absence writer, staging, claim/ack or network orchestration is
added. The app constructs none of this machinery and remains signed-out/local-only. No entity/schema/migration/asset,
build/dependency, manifest, UI or shared-contract change was needed.

Worker implemented production and QA independently authored 71 real Room cases from the same specification and supplied
API facts, with disjoint source access and serialized Gradle slots. Coordinator reviewed both complete layers and final
scope. Existing accepted tests remain unchanged; expected canonical values/JSON are independently authored, never
produced by the encoder under test.

## Findings resolved before acceptance

- Root-role evidence must include unobserved/deleted/absent roots and selected DELETE, not only live upserts. Retained
  family checks also run for all-no-op batches. Direct/composed APIs now share one owned-batch write algorithm.
- Selected missing-root INSERT uses ABORT semantics rather than silently ignoring a failed observed write.
- The older normalized JSON parser accepted unquoted strings/literal controls and let overflow escape as raw
  `JSONException`. Independent 71-case baseline had three failures. The retained reader now reuses the existing original
  scalar/type-fidelity boundary; malformed evidence maps safely to `CanonicalStorageException`, while valid quoted
  exponent-like strings/escaped controls survive initial application and retained reread/replay. Negative assertions were
  not weakened; the corrected 71-case rerun and full coordinator suite passed.
- Initial focused QA execution had four fixture/expected-exception failures. Fixtures were corrected for real FK
  dependencies, canonical UUIDs and one-equipment custom representation; family disagreement uses the specified conflict
  exception. Corrupt-row cases are isolated, callback failures have an identity oracle, complete no-write snapshots and
  nested mutable-input admission are exercised. Failed and green XML were inspected/preserved separately during review.

These findings are resolved, not deferred defects. No generic codec redesign or parser behavior change outside the new
retained-reader boundary was made.

## Executed evidence

All commands ran from the Android repository root. Final coordinator build against frozen production/tests:

```sh
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:lintDebug --rerun-tasks --no-configuration-cache --console=plain
```

Exit 0, **61 tasks executed**. Kotlin/KSP, Android-test compilation, assembly and lint passed. Nested JVM XML contained
**799 unique cases / 47 suites**, zero failures/errors/skips. Lint XML had zero errors and one existing
`NewerVersionAvailable` org.json warning. Known catalogue foreign-key-index KSP and existing test-source warnings,
Gradle deprecation and unstripped `libandroidx.graphics.path.so` notices remain nonfatal; no new R05b warning was observed.
Kotlin mcpls remained unavailable while initializing, so no clean-LSP-diagnostics claim is made.

QA's final filtered command, independently inspected by coordinator:

```sh
ANDROID_SERIAL=emulator-5580 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.CanonicalBatchTest --console=plain
```

Exit 0; **71 unique R05b cases / one class**, zero failures/errors/skips, including all three retained-reader regressions
and positive reread/replay. This filtered evidence is separate from the full gate below.

Final coordinator **unfiltered** instrumentation:

```sh
ANDROID_SERIAL=emulator-5580 ./gradlew :app:connectedDebugAndroidTest --no-configuration-cache --console=plain
```

Exit 0. Build dependencies were up-to-date after the fresh compilation above; the instrumentation task actually executed.
Nested XML contained **307 unique cases / 23 classes**, zero failures/errors/skips, including 71 R05b and all three
migration cases. Test counts are testcase identities, not table-driven subcase counts. The owned fresh headless emulator
used emulator 37.1.11.0 and the installed android-37.2 Google Play 16KB x86_64 image revision 5; guest reported API 37 /
Android 17. No personal AVD data/snapshots or owner phone/account was used.

Final scope/preservation checks:

- Source asset, GymDatabase declaration, app build file and all ten schema snapshots remain byte-identical to HEAD.
- APK bundled database plus three notices match source bytes. Scoped scanning of all 16 DEX string tables found no named
  R05b/backend/PostgreSQL/JDBC/MockWebServer/coroutine-test helpers; this is not a complete release/security audit.
- Bundled v1 asset SHA256: `defa08e9c464f90f0f48d8e3441fe20151803b48948647a91742c1bb82fa9f7d`.
- Both repository diffs, new-file whitespace and owning-document local links/anchors were checked. Shared-docs is unchanged.

## Residual boundaries and stop

Canonical storage intentionally permits unresolved dependencies, intermediate multiple active roots and physical
local/foreign/bundled UUID collisions. R05c must validate the complete resulting graph and refuse global domain PK
collisions before protected visible publication. Current known bindings are not a lifetime removed-child reservation
store; no new reservation schema was introduced. Ordinary batches do not increment cache epochs or mark a baseline
complete. R06/R07 still own exact acknowledgment/checkpoint composition; R08 owns complete snapshots/absence.

No new backend run was required for this Room-only task. API24 runtime, hosted CI, real Google/phone/laptop journeys,
engine convergence, account transitions and protected publication remain unverified/not delivered. The legacy accepted
auth replay limitation is unchanged. R05b is closed; R05c requires separate owner authorization and its own bounded spec.
