# R02b — Coordinator implementation review

Date: 2026-10-03. Status: **accepted**.
The owner authorized R02b implementation, then explicitly authorized R02c after R02b finishes. Subsequent delivery
update: the owner authorized the full Android working-tree commit; see [delivery record](R02bc-commit-review.md).
The no-commit statements below describe the implementation review gate before that later authorization. No push,
backend/configuration change, device/account journey or later implementation task is authorized.

## Delivered scope and invariant review

Production changes are confined to `data/sync/model/WorkoutValidation.kt`, `RoutineValidation.kt`,
`MutationValidation.kt` and new `FamilyIdentityValidation.kt` under `app/src/main/java/com/dragosfotache/gymtracka/`.

- Full workout/routine inputs, canonical records and import snapshots reject aliases in the complete root/entry/set
  family set, including across different entries. Existing public signatures, value/position validation and sorting remain.
- Copy plans validate the source root and all source entries/sets, and the target root and all target entries/sets,
  independently. Same-family `workout.performAgain` additionally rejects every source/target overlap across all roles.
  `routine.startWorkout` and `workout.saveAsRoutine` allow equal UUIDs across independent families, including equal roots
  and children; duplicate roles within either side still reject.
- Public payload validation receives source-root context directly, so constructor/payload calls cannot bypass the rule.
  Existing envelope kind/target matching, canonical UUID checks, ordered copy bindings and addition-root guards remain.
- Catalogue/custom reference `exerciseId` and mutation UUIDs are independent, not added to the family set.
- Codecs and pure Room mappings already delegate to these validators; coordinator read those delegation paths and
  used Kotlin LSP symbols/references. No codec, mapping, persistence/capture or schema file needed an edit.
- Valid durable v1 text and exact push embedding remain unchanged. Forbidden supported data rejects rather than
  repairing/remapping/dropping it; unsupported versions remain distinct. R02a's raw duplicate-key guard is unchanged.

These are stateless checks of identifiers PRESENT in a supplied graph/plan. They do not prove ownership, account-lifetime
reservations, complete current source membership/eligibility or global Room PK materialization safety. Those checks
remain service/store responsibilities; no registry, simulator, migration, network/auth/UI/scheduler or new dependency was
introduced. Runtime remains Room v9 and the bundled asset remains v1.

## Independent contributor and coordinator review

Live Pi identities/models were rediscovered (`meta/muse-spark-1.3-contributor`, high reasoning) and fresh native `/new`
Worker/QA sessions started in `w4:pJ` / `w4:pK`; coordinator remained `w4:p7`. Both received the same
[bounded specification](R02b-task.md), with exact APIs, disjoint source access and serialized Gradle slots.
Worker read/edited production only; QA authored two independent test files without production declarations/diffs/
decompilation or Worker access. Missing harness/scalar facts were supplied by the coordinator and added to the shared
specification. No role inspected the other's code, and no global/personal configuration was manually changed.

Coordinator reviewed all four production files and both complete new test files. Review corrections stayed in the same
contributor sessions:

1. Worker formatted the expanded child-plan calls and replaced temporary list/set/intersection allocation in the new
   same-family overlap helper with one source set and membership checks. Final compilation passed; no behavior broadened.
2. QA corrected fixture arity, an aliased-reference positive comparison, a constructor parenthesis, and addition-root
   assertions that incorrectly lacked envelope-root context. It added active/draft/import variants, both canonical
   protocol families and off-diagonal cross-binding cases, used exact unsupported-version catches and precise terminology.
3. QA performed a formatting-only complete rewrite of its new files to four-space Kotlin style, preserving string
   contents/scenarios and removing redundant always-true catch assertions. The initial focused run and final cleanup rerun
   both passed. No failing build/test gate or old regression-test rewrite was needed.

Contributor-session mcpls was unavailable/indexing after bounded attempts; no retry/sleep/configuration loop was used.
Coordinator Kotlin diagnostics reported no errors in changed production, with only the pre-existing return-lifting style
hint in `validateImportRecord`. The focused new files compile without new compiler warnings. Actual execution, not LSP
availability or contributor completion alone, is the gate.

## Executed evidence

Worker production compile passed after initial implementation and bounded corrections:

```sh
./gradlew :app:compileDebugKotlin --console=plain
```

QA passed twice, with coordinator inspection of actual XML names/counts:

```sh
./gradlew :app:testDebugUnitTest --tests 'com.dragosfotache.gymtracka.data.sync.FamilyIdentityValidationTest' --tests 'com.dragosfotache.gymtracka.data.sync.CopyIdentityValidationTest' --rerun-tasks --console=plain
```

`FamilyIdentityValidationTest`: **8 tests**; `CopyIdentityValidationTest`: **8 tests**; zero failures/errors/skips.
Tables exercise identity-only counterexamples, independent reference/mutation namespaces, direct models/payloads,
envelopes, raw/encoding/durable paths, canonical push/pull/snapshot records, valid scalar/order/null controls and exact
hand-authored full push bodies. Test method count is not the acceptance oracle.

Coordinator independently forced the full offline gate (61 actionable tasks executed):

```sh
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --rerun-tasks --no-configuration-cache --console=plain
```

- **216 offline JVM tests in 28 suites**, zero failures/errors/skips: all prior 200 plus 16 new methods executed.
  Real-backend tests were excluded from offline execution, not skipped.
- Production/Android-test Kotlin compilation, debug assembly and lint passed. Lint reports only the existing
  `NewerVersionAvailable` warning; existing catalogue FK-index, backend-test helper, Gradle and native-symbol warnings
  remain outside this slice. No new app warning/dependency/schema change was introduced.
- Android and nested shared-docs diff checks passed. Explicit whitespace checks cover new/untracked delivery files;
  local Markdown references resolve. No code/data gate was inferred from a cached/up-to-date task.

Supplementary coordinator-only, stateless oracle probes rerun against final compiled code: deterministic seed-20261003
cases cover 738 copy bodies (**109 valid accepted, 629 forbidden rejected**) and 561 aggregate/import bodies across 11
representation variants (**191 valid accepted, 370 forbidden rejected**). The independent oracle uses role uniqueness
and family-qualified overlap rules; **zero mismatches**. Temporary evidence lives under
`/tmp/gymtracka-r02b-review-qiqe61y9`, not a successor dependency or maintained/backend test suite.

## Preservation and next boundary

Pickup Android HEAD was R02a `2a93c56`; nested shared-docs remained `f65abb82512c373ade9ab08699ef68971b2e8ceb`.
Both trees were already dirty. A 215-file SHA-256 inventory confirms only the three existing production validators and
four affected delivery documents changed; **208 other inventoried files remain unchanged**. The helper, two tests,
R02b specification and this review are new. No old test was changed/removed; all previous unrelated retirement/HTTP/
build/dependency/AGENTS/docs/scripts and nested changes are preserved. Index remains empty; no commit or push was made.
Verification applies to that preserved working tree, not a clean-checkout claim.

No device, Room/migration, real-backend, Google OAuth, hosted CI or personal phone/laptop gate was executed for R02b.
R02a's six platform tests and M3's full 161-test instrumentation run remain prior evidence. The app still has no running
sync engine, production HTTP/auth adapter or working account UI.

R02b is complete. The owner explicitly authorized R02c next: audit and specify typed invalid/expired read-token,
authentication/reauthentication and request-size failures before starting fresh contributors. R02d–e, transport,
configuration, commits and pushes require separate authorization.
