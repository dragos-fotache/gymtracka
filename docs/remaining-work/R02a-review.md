# R02a — Coordinator implementation review

Date: 2026-10-03. Status: **accepted**; stop at R02a.
Authorization: the owner requested R02a execution and a final commit, then approved including the affected existing
Android documentation. No push, next implementation slice, backend/configuration change or live account action is authorized.

## Delivered scope

- `app/src/main/java/com/dragosfotache/gymtracka/data/sync/codec/StrictJson.kt`: original-text duplicate-member guard.
- `app/src/test/java/com/dragosfotache/gymtracka/data/sync/StrictDuplicateKeyTest.kt`: 17 independent JVM test methods.
- `app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/StrictDuplicateKeyAndroidTest.kt`: six platform smoke methods.
- Android architecture, testing strategy, roadmap, coordinator handoff, R02a specification and this review.

Only the raw parsing boundary changed in production. No model/API shape, durable version, ID, dependency, Room schema,
asset, repository, transport, authentication, UI or scheduler was changed by R02a. Runtime remains Room v9; asset remains v1.

## Independent review and corrections

Live discovery confirmed the established Pi contributor roles (`meta/muse-spark-1.3-contributor`, high reasoning):
Worker `w4:pJ`, QA `w4:pK`, coordinator `w4:p7`. Both received the same normative specification with disjoint allowlists.
Worker read/edited production only and compiled production; QA authored test expectations without production source,
diffs, decompilation or Worker access. Reports were fire-and-forget; shared-output Gradle runs were serialized.

Coordinator reviewed the complete production change and both new test files, including intermediate corrections:

1. The initial guard duplicated unnecessary value/number/escape grammar and had a form-feed decoding defect. Worker
   replaced it in the same session with a focused scope/string/colon walk and existing-library member decoding.
2. QA fixture review caught incorrect default-argument references/erased model typing, mismatched durable root bindings,
   a unique convenience-field rejection expectation, an invalid timestamp in a positive case, and substring-only wire
   assertions. These were corrected without exposing production to QA.
3. Further review required genuinely escape-equivalent names, fixed-message comparison to avoid incidental
   model-validation false positives, valid additive result envelopes, actual outer/nested name reuse and numeric spelling
   in frozen requests. QA hit a repeated escape-text edit blocker and reported a broken intermediate test file. Coordinator
   paused QA writes and repaired only its two allowlisted files with precise edits, preserving the independently authored
   scenarios. The same QA session then received an execution-only assignment; all focused gates passed on the first run.

Contributor-session mcpls did not provide usable Kotlin diagnostics (unconfigured/indexing). Coordinator used Kotlin LSP
symbols/references to verify shared-boundary routing and obtained empty production diagnostics with indexing complete;
subsequent test-diagnostic attempts were still indexing. Actual compilation and executed tests are the acceptance evidence,
not an unavailable LSP result. No global/personal tool configuration was changed.

## Correctness findings

The guard checks original text before tree normalization, iterates over object/array scopes and skips quoted values
opaquely. Only quoted member literals are decoded using the existing JSON library. Decoded-name sets are per object;
case, whitespace and Unicode normalization are not changed. Nested objects, array elements and ignored extensions are
covered. The walk has no recursive calls or new size/depth cap; it does not rescan complete subtrees or regenerate bytes.

All raw aggregate, mutation, push/pull/snapshot and durable routes use the same boundary. Supported durable wrappers
parse embedded `requestJson` separately; arbitrary JSON-looking string values are not reparsed. Already-materialized
`JSONObject` overloads cannot recover lost keys. Unique unsupported durable versions still fail distinctly; corrupt
supported data is rejected rather than repaired, migrated or dropped. Hand-authored expected push bodies prove exact
request text, ordering, whitespace/escapes and `2.000e+1` numeric spelling survive.

Duplicates throw fixed-text `SyncModelException` without a raw cause or body/member/value leakage. Existing syntax/model
validation remains authoritative. A coordinator probe of the **pre-R02a compiled parser** showed arbitrary raw tokens
`NaN`, `Infinity`, `-Infinity`, `0x1` and `01` already normalize to strings. The specification records this baseline fact:
represented numeric fields remain rejection boundaries; R02a neither fixes nor newly accepts arbitrary raw numeric grammar.

## Executed gates

From the Android repository root, Worker passed:

```sh
./gradlew :app:compileDebugKotlin --console=plain
```

QA passed, with coordinator inspection of actual XML names/counts:

```sh
./gradlew :app:testDebugUnitTest --tests 'com.dragosfotache.gymtracka.data.sync.StrictDuplicateKeyTest' --rerun-tasks --console=plain
./gradlew :app:compileDebugAndroidTestKotlin --console=plain
./gradlew :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.StrictDuplicateKeyAndroidTest' --console=plain
```

- Focused JVM: **17 tests, zero failures/errors/skips**.
- Focused Android: **6 tests, zero failures/errors/skips**, Pixel_6a read-only headless emulator, Android 17/API 37,
  `emulator-5554`. Exact/escaped/nested rejection, distinct scopes, opaque escaped values and safe errors all executed.
  Existing AVD configuration was not changed; the coordinator-owned emulator was stopped after verification.

Coordinator independently passed this forced broad gate (61 actionable tasks executed):

```sh
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --rerun-tasks --no-configuration-cache --console=plain
```

- **200 offline JVM tests in 26 XML suites**, zero failures/errors/skips; all 183 prior offline methods remain plus 17 new.
  The separate real-backend package was excluded from execution, not skipped.
- Production/Android-test Kotlin compilation, debug APK assembly and lint passed. Lint has only the existing
  `NewerVersionAvailable` warning. Existing catalogue FK-index, backend-test helper/deprecation, Gradle option/deprecation
  and native-symbol stripping warnings are nonfatal and outside this slice.
- `git diff --check` in Android and nested shared-docs passed. Explicit checks of new/untracked delivery files also passed.

Supplementary coordinator-only diagnostic: a deterministic seed-20261003 corpus of 2,000 valid raw JSON bodies was
classified by Python's independent decoded-member-name oracle, then passed through the compiled raw boundary. **773
unique bodies accepted; 1,227 duplicate bodies rejected; zero mismatches.** Temporary evidence is under
`/tmp/gymtracka-r02a-review-ihcbz1b9`; this is not a successor dependency or an extra maintained test/backend suite.

## Preservation, commit and limits

Pickup Android HEAD was `25bdfd66f1424e7deb06a5fedc585d49fa1f3ab3`; nested shared-docs HEAD was
`f65abb82512c373ade9ab08699ef68971b2e8ceb`. Both were already dirty. A 212-file SHA-256 inventory verifies that only
`StrictJson.kt` and the five affected pre-existing Android documents changed; the other **206 inventoried files remain
unchanged**, including pre-existing source/tests/build metadata, scripts, assets, schemas and nested shared-docs.
The two tests and this review are new R02a files. Verification ran against that preserved working-tree baseline,
not a clean-checkout claim.

The owner explicitly chose to include the five affected documents even though `docs/` was already untracked; their
pre-existing relocation/planning/history text is consequently included with the delivery. Unrelated simulator-retirement,
HTTP/harness/build/dependency/AGENTS changes, other untracked docs/scripts/tests, and all nested shared-docs changes stay
outside the R02a commit. No broad staging, cleanup, nested commit or push is permitted.

No real-backend, Google OAuth, hosted CI, personal phone/laptop journey, Room/migration run or full instrumentation gate
was added. The last full device gate remains 161 tests at M3; this slice adds only its six filtered platform tests.
The app still has no running engine, production HTTP/auth adapter or account UI. R02a acceptance does not complete R02.
A bounded R02b identity-namespace plan is the next proposed pickup and requires separate owner authorization.
