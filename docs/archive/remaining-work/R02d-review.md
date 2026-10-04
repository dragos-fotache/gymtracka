> **Historical record, not current instructions.** See the [archive boundary](../README.md) and [current pickup](../../coordinator-handoff.md).

# R02d — Coordinator acceptance review

Date: 2026-10-03. Status: **accepted**.
Scope: owner-authorized purpose-bound auth codecs, expiry validation and exact-body exchange/refresh attempt boundaries.
Specification: [R02d shared contract](R02d-task.md). No next slice, commit, push, backend/configuration or deployment action.

## Reviewed deliverable

Production:
- `app/src/main/java/com/dragosfotache/gymtracka/data/sync/api/ServiceAuth.kt`
- New `app/src/main/java/com/dragosfotache/gymtracka/data/sync/api/ServiceAuthRequests.kt`
- New `app/src/main/java/com/dragosfotache/gymtracka/data/sync/codec/ServiceAuthCodec.kt`

Independent tests:
- New `app/src/test/java/com/dragosfotache/gymtracka/data/sync/ServiceAuthCodecTest.kt` — 39 methods
- New `app/src/test/java/com/dragosfotache/gymtracka/data/sync/ServiceAuthAttemptTest.kt` — 18 methods
- Coordinator-only `ServiceAuthBoundaryTest.kt` alignment — all four legacy tests retained

Coordinator read all production/new test contents and tracked diffs, including untracked files. Finalized linked v1
contracts, OpenAPI/examples and relevant Android/product/design/testing documents were read. Kotlin LSP symbols and
references identified the sole old ServiceAuthApi implementation; later diagnostics encountered indexing, so actual
compilation/tests supplied the authoritative gate. No backend source or private configuration was accessed.

## API/behavior findings

1. All four challenge purpose enum/wire values are explicit. `{}` decodes to signIn; new default encoding includes
   signIn explicitly. Closed request fields reject; additive response/account fields remain tolerated.
2. Attempt constructors are private/final, with no data-class copy/componentN or mutable state. Capture encodes once;
   restore retains the complete validated input String unchanged. Constructor consistency checks ensure exposed fields
   agree with the exact body. Keys remain canonical UUID header data, not request members. Display is fixed redacted text.
3. Typed ServiceAuthApi methods take the attempt itself; legacy caller convenience signatures capture/delegate. No-argument
   challenge calls remain source-compatible via a default purpose. Implementer override source changes are intentional;
   the only old JVM fake was adapted by the coordinator. Neither contributor inspected the other layer or legacy test.
   Token comparisons in that old test now assert values rather than incidental object identity.
4. Required string/object fields, empty tokens/nonce, UUIDs, expiry constructor/copy ranges, strict UTC dates and lossless
   millisecond precision validate safely. Long fractional zero tails are accepted without changing TimeFormat. No clock,
   fixed lifetime, account continuity or expiry ordering is inferred; original expired-access replay remains representable.
5. Invalid UUID/timestamp/unknown-field/purpose/body failures carry fixed safe text with null cause and no raw suppressed
   exception. Only direct OpaqueToken empty construction retains its pre-existing IllegalArgumentException guard.
   Challenge display excludes nonce; session/token display remains credential-safe.
6. New auth readers share original-text duplicate checking. An auth-only iterative lexical guard closes two existing
   parser permissiveness gaps for these new routes: arbitrary unquoted scalar normalization and unescaped literal string
   controls. Valid escaped opaque controls/Unicode/JSON-looking strings remain data. No recursive parser, size/depth cap,
   global parser/numeric change, durable mutation reserialization/version, or secret persistence was added.
7. No transport, refresh/retry orchestration, Google verification/UI, vault, lifecycle proof/export/delete/status, Room,
   migration, manifest, dependency or app activation change. These remain later separately authorized capabilities.

## Corrections before acceptance

Coordinator kept corrections inside the same fresh Worker/QA sessions:
- Replaced overly broad test failure acceptance and field-name leakage assertions with SyncModelException-only raw-reader
  assertions, sensitive sentinels and fixed-message comparisons. Only the direct legacy OpaqueToken guard stays distinct.
- Improved independent required-field/constructor/copy/UTF-8/cancellation coverage and a private-constructor reflection
  check that excludes Kotlin synthetic bridges. Cancellation now asserts the original instance for both typed operations
  and both production legacy-default delegation paths; restored Unicode/escaped bodies are observed byte-exactly.
- Corrected direct empty nonce validation to the specified model exception type.
- Source review caught zero-tail stripping that could normalize `.Z` or `.0.000Z` into valid timestamps. Worker now requires
  a nonempty all-ASCII-digit original fraction at the first dot before removing only trailing zeros. QA added independent
  malformed-fraction negatives and long-zero-tail positives on challenge/session expiry paths.
- A temporary compiled-code probe found literal newline/tab/NUL accepted by the shared parser. Worker added bounded
  auth-local control checks; QA independently added escaped-control positive and literal-control negative fixtures.
- A stalled Worker planning generation was stopped and resumed in the **same native session**, before any production
  files were written. No replacement session/configuration change or cross-role source permission was needed.

These findings were fixed and rerun before acceptance; the initial probe's three mismatches are not the final result.
The auth-only lexical/timestamp refinements are documented in the shared normative Android task spec, not changes to the
finalized cross-project contract or to existing durable wire behavior.

## Executed gates

Worker production compile passed after the initial implementation and each bounded production correction. Coordinator
unit-test compilation passed before granting QA exclusive focused slots. Final QA execution: **57 tests**, 39 codec + 18
attempt, zero failures/errors/skips. QA inspected only its own two XMLs.

Coordinator independently forced the final complete gate after the final cancellation test correction:

```sh
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --rerun-tasks --no-configuration-cache --console=plain
```

Result: **BUILD SUCCESSFUL**, 61 actionable tasks executed. Actual XML inspection:
- **340 offline JVM testcases in 32 suites**; zero failures, errors or skips.
- New suites: 39 + 18; legacy auth suite: 4. Prior 283 methods retained.
- Production and Android-test Kotlin compilation, debug assembly and lint passed.
- Lint XML contains only the existing `NewerVersionAvailable` warning. Existing catalogue FK-index, test-helper,
  Gradle-deprecation and native-symbol packaging warnings remain nonfatal; no new R02d compiler/lint warning.

Reports: `app/build/test-results/testDebugUnitTest/`, `app/build/reports/tests/testDebugUnitTest/`,
`app/build/reports/lint-results-debug.xml`. APK: `app/build/outputs/apk/debug/app-debug.apk`.
Temporary final command log: `/tmp/gymtracka-r02d-final-gate.log` (not a successor dependency).

Supplemental coordinator-only probe: **1,138 independently constructed auth bodies**, 449 valid accepted / 689 invalid
rejected / **zero mismatches** on the final compiled code. It checks all five reader/attempt routes, exact restored text,
required/type/duplicate/escape controls, all literal U+0000..U+001F (also after backslash), valid escaped controls, date
bounds, malformed fractions and 500-digit zero tails. This is a temporary stateless diagnostic, not another checked-in
suite, backend pass or platform test. Probe artifacts under `/tmp/` are not required by future work.

## Preservation and limits

Android pickup HEAD `623207c` was clean. Nested shared-docs had pre-existing relocation/product/contract changes and
remains separate/uncommitted. A 197-file read-only source/assets/schema/script/shared-docs inventory changed only the
allowlisted existing ServiceAuth.kt and coordinator legacy test; new production/test files are the four listed above.
No existing test was removed. Android-local architecture/testing/roadmap/handoff/spec/review, documentation index and
implementation-plan current summary were updated.
Both `git diff --check` and `git -C shared-docs diff --check` passed; local Markdown links and explicit new-file whitespace
checks passed. Schema/asset/build/scripts/AGENTS and nested shared-docs are preserved.

No new device/instrumentation, Room/migration, real backend, Google OAuth, hosted CI, personal-phone/laptop or deployment
pass is claimed. R02a's six Android parser smoke tests, the earlier 46 real-backend/11 Python checks, and M3's full
161-device-test gate remain previous evidence. This bounded auth slice does not make the app networked or signed in.

R02a–d now supply the personal-use model/API prerequisites for a separately authorized bounded **R03a** transport plan.
R02e lifecycle models remain deferred with public export/deletion/status work. Both contributors are idle and must be
rediscovered/fresh-sessioned for another slice. Stop here: no commit, push, R03/R02e, configuration or assignment 018.
