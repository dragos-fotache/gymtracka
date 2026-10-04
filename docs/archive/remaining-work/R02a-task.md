> **Historical record, not current instructions.** See the [archive boundary](../README.md) and [current pickup](../../coordinator-handoff.md).

# R02a — Reject duplicate keys at the shared raw JSON boundary

Status: **implemented and coordinator-accepted** (2026-10-03); see [review](R02a-review.md).
The owner authorized R02a implementation and a final commit, then approved including the affected existing Android
architecture/testing/roadmap/handoff/specification documents with the R02a delivery record. Unrelated dirty-tree work
and nested shared-docs changes remain outside the commit; no push is authorized.
[R01](R01-task.md) planning/design was approved by the owner on 2026-10-03. This single normative specification goes to both Worker and QA.
Role allowlists below differ; required behavior and acceptance expectations do not.

## Goal and non-goals

Enforce the finalized v1 rule that raw JSON has no duplicate object member names, before a tree/JSONObject conversion
can collapse them. Cover exact and JSON-escape-equivalent member names at every object depth. Keep all valid request
bytes and existing field/model rules unchanged.

Normative rule: [service-protocol.md, Request identity and atomicity](../../../shared-docs/docs/backend-contract/service-protocol.md).
Existing strict-body and exact-UTF-8 retry rules continue. Do not add transport/auth/Room/UI/scheduler code, dependencies,
new domain caps, a new durable version, identity-namespace corrections, broader refactoring or a server simulator.
Do not modify personal/global configuration, backend code, the bundled asset, schemas or shared contract.

## Behavior requirements

1. Every raw-string decoder that uses the shared JSON-object boundary rejects duplicate member names in **any object**,
   including objects inside arrays and ignored additive response fields. Identical repeated values also reject.
2. Compare decoded JSON member names: `"name"` and `"\u006eame"` alias; escaped slash/quote/backslash and other valid
   escape spellings must have JSON string semantics. Do not Unicode-normalize, trim or case-fold names. Distinct names
   such as `"Name"`/`"name"` and composed/decomposed Unicode remain distinct.
3. Member-name uniqueness is per object. The same name in sibling objects/array elements or an outer and nested object
   is valid. Repeated string values and duplicate-looking text within a string value are not duplicate object members.
4. Reject with `SyncModelException`, with fixed/safe text that includes neither the raw body, member name nor values.
   Do not retain a raw parser cause for this failure. Never silently keep the first/last member.
5. Keep strict syntax rejection: nonobject root, trailing content, malformed escapes/quotes, comments, single quotes,
   unquoted names, trailing commas and nonfinite/untyped numeric input must not become newly accepted.
   Coordinator baseline clarification: existing tree parsing can normalize untyped tokens such as `NaN`, `Infinity`,
   `0x1` and `01` into strings in arbitrary raw fields. Their rejection is asserted through represented model numeric
   fields, whose typed readers reject strings/nonfinite values. R02a does not add a new raw numeric grammar or claim
   that every arbitrary ignored field already rejects such tokens.
6. Unique, valid JSON continues to decode exactly as before: explicit nulls, optional/default fields, correct numbers,
   whitespace/escapes, array contents, millisecond timestamps and tolerated canonical convenience fields.
   Object-based (`JSONObject`) overloads receive already-materialized objects: they cannot reconstruct lost raw keys;
   do not claim that they can. Transport/durable readers must continue using raw-string entry points.
7. Durable outer wrapper parsing and the separate embedded `requestJson` object parsing both enforce uniqueness.
   `requestJson` is a JSON string in the wrapper: the raw outer scanner treats it as a string, while the durable decoder
   separately parses that contained mutation normally. Arbitrary other strings containing JSON-looking text are not
   reparsed by the duplicate-key guard.
8. Valid durable v1 request text must be retained byte-for-byte. `encodePushMutationsBody` embeds that exact text,
   including internal whitespace/key order/number spelling. The guard must not regenerate mutation IDs, child IDs,
   wrappers or wire bytes. Unsupported durable versions remain distinguishable for unique, valid wrapper syntax;
   corrupt duplicate-key data is rejected, not repaired/dropped/migrated.
9. Use platform-independent behavior. Android's `org.json` differs from the test dependency; do not rely on the JVM
   `JSONObject(String)` duplicate exception as the production guarantee. No new arbitrary input-length/depth cap.
   The additional check must not introduce exponential rescanning or unchecked recursion as a new failure mode.

Design direction: keep existing strict syntax/model readers; add a small duplicate-name guard over the **original**
text before relying on a normalized JSON object. It may leverage the existing JSON library to decode quoted names.
A regex or scanning the normalized map is insufficient. An iterative object/array scope walk avoids introducing an
extra recursion-depth limitation. Implementation structure is Worker's choice within these constraints; it is not
permission to replace the serialization stack or invent another full parser/service.

## Existing API contract supplied by coordinator

Package `com.dragosfotache.gymtracka.data.sync.codec`:

```kotlin
internal fun parseStrictJsonObject(text: String): org.json.JSONObject
fun decodeWorkoutInput(jsonText: String): WorkoutInputModel
fun decodeWorkoutRecord(jsonText: String): WorkoutRecordModel
fun decodeRoutineInput(jsonText: String): RoutineInputModel
fun decodeRoutineRecord(jsonText: String): RoutineRecordModel
fun decodeCustomExerciseInput(jsonText: String): CustomExerciseInputModel
fun decodeCustomExerciseRecord(jsonText: String): CustomExerciseRecordModel
fun decodeMutationEnvelope(jsonText: String): MutationEnvelopeModel
fun decodeDurableRequest(jsonText: String): DurableRequestModel
fun encodePushMutationsBody(requests: List<DurableRequestModel>): String
fun decodePushResult(jsonText: String): PushResultModel
fun decodePullResult(jsonText: String): PullResultModel
fun decodeSnapshotResult(jsonText: String): SnapshotResultModel
```

Model package: `com.dragosfotache.gymtracka.data.sync.model`.
`class SyncModelException(message: String) : IllegalArgumentException(message)`.
`UnsupportedDurableVersionException` is a distinct exception for unsupported wrapper version.
`DurableRequestModel.requestJson: String` retains the supplied inner text; `payloadVersion: Int` is 1 for supported data.
Raw parser is internal and available to app test friend sources; do not expose a new public helper solely for tests.

Independent minimal wire fixtures (use distinct lowercase UUIDs for mutation/root; field names follow shared OpenAPI):

```json
{"id":"11111111-1111-1111-1111-111111111111","name":"","status":"active","startedAt":"2026-10-03T12:00:00Z","exercises":[]}
{"id":"22222222-2222-2222-2222-222222222222","name":"","isDraft":true,"exercises":[]}
{"id":"33333333-3333-3333-3333-333333333333","name":"Custom"}
{"results":[]}
{"batches":[],"nextCursor":"opaque-tail","hasMore":false}
{"records":[],"baselineCursor":"opaque-baseline","hasMore":false}
```

Canonical empty active workout adds `revision: 1` and nullable `finishedAt`; canonical routine adds `revision: 1`.
Canonical custom additionally has `type:"reps"`, `isUserCreated:true`, `revision:1`, `muscleGroups:[]`, `equipment:[]`.
A `workout.start` mutation carries `mutationId`, `type:"workout.start"`, `aggregateType:"workout"`, matching
`aggregateId`, `localGeneration:1` and payload `{id,name,startedAt}`. Its durable wrapper is
`{payloadVersion:1, requestJson:"<that exact JSON text>", affectedRoots:[{aggregateType:"workout",aggregateId:"<root>",localGeneration:1}]}`.
Construct raw strings independently, not expected JSON via production encoders. Ask the coordinator for any other
signature/harness fact; QA must not inspect production declarations.

## File boundaries

Paths are relative to repository root.

**Worker writable:**

- `app/src/main/java/com/dragosfotache/gymtracka/data/sync/codec/StrictJson.kt`
- Optional new `app/src/main/java/com/dragosfotache/gymtracka/data/sync/codec/DuplicateJsonKeys.kt` if a focused helper
  merits a separate file. No other production changes without coordinator amendment.

Worker may read other production model/codec files under `data/sync/model` and `data/sync/codec` for integration,
root `AGENTS.md`, this specification and cited shared docs. Worker may not read/search/navigate test source, fixtures,
reports/diffs or QA transcript and may not run tests. Compile production only. Use Kotlin `mcpls` document-symbol,
hover/definition and diagnostics tools restricted to allowed production paths; never follow test references.

**QA writable/readable test paths:**

- New `app/src/test/java/com/dragosfotache/gymtracka/data/sync/StrictDuplicateKeyTest.kt`
- New `app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/StrictDuplicateKeyAndroidTest.kt`

No other test/harness edits without amendment. Tests may use standard JUnit/JVM `org.json` APIs and existing installed
Android test runner as a black-box build facility. QA may read root `AGENTS.md`, this specification and cited shared
contract/fixtures. Production paths, production diffs, decompilation and Worker transcript are forbidden, including
through LSP hover/definition/completion/source diagnostics. Use Kotlin `mcpls` on QA's test files only. If diagnostics
require production code inspection, report to coordinator. Compilation can implicitly compile production; this is not
source access. Do not broaden tests to later R02 identity/auth/store/transport work.

**Coordinator:** full source/test review and documentation updates in architecture/testing/roadmap/handoff plus
`docs/remaining-work/R02a-review.md`. Existing tests remain coordinator-owned regression inputs; neither contributor
needs to inspect the other layer to satisfy this assignment. Execution amendment: after QA reported a repeated
escape-text editing blocker and stopped writing, coordinator repaired the two allowlisted QA files, then returned an
execution-only assignment to the same QA session. The independent scenarios and source-access separation were retained.

## Required independent acceptance cases

| Case | Expected observable result |
|---|---|
| Exact root duplicate, same value and different values | Safe SyncModelException; no first/last-wins acceptance |
| Escaped-equivalent root and nested names | Reject, including escaped Unicode and correctly decoded escapes |
| Nested objects/objects in arrays, multiple levels | Reject duplicate within one object, including ignored canonical extension field |
| Same member name in different objects | Accept; no cross-object false positive |
| Strings with braces, escaped quotes/backslashes and duplicate-looking JSON text | Accept valid distinct members; no value parsing/false positive |
| Case-distinct, empty and distinct Unicode member names | Correct JSON equality, no normalization; empty repeated key rejects |
| Valid minimal input/canonical records of all three families | Decode unchanged; adding duplicate required field fails through each raw-string route |
| Push/pull/snapshot raw decoders | Unique fixtures accepted; duplicate envelope/deep extension member rejects |
| Mutation raw decoder and durable outer wrapper | Duplicate envelope/payload/wrapper keys reject |
| Embedded durable requestJSON duplicate | Reject inner corruption even when outer wrapper keys are unique |
| Valid unusual-spelling/whitespace durable request | Exact `requestJson` and exact expected push body unchanged; expectations are hand-authored |
| Unsupported version, unique valid wrapper | Existing distinct unsupported-version failure, no repair/version bump |
| Bad syntax/nonobject/trailing data | Still rejected; duplicate scan does not bypass strict parser |
| Error text/toString/cause leakage sentinel in keys/values | Duplicate failure contains no raw sentinel/body/name/value or raw cause |
| Representative long/deep valid and duplicate data | Deterministic behavior without new cap/exponential scan; no sleeps/performance wall-clock thresholds |
| Android platform raw parsing smoke | Exact/escaped/nested duplicate rejects, distinct siblings and escaped string values accept |

Use observable behavior, not the name/private structure of the chosen helper. Parameterization/table-driven cases are
fine; do not inflate method counts as the acceptance criterion. An initially red defect test is not a completed task.

## Execution, gates and stop

Both contributors author disjoint files independently. Coordinator schedules Gradle execution serially to avoid two
agents building in shared output directories at once. Worker reports ready-to-compile; QA reports ready-to-run. Each
uses `herdr agent prompt gym-coordinator '<Worker/QA report>'` **without `--wait`** and then stops. No `agent wait`,
user questions or direct Worker/QA coordination; coordinator supplies clarifications to both if the shared spec changes.

Worker compile gate:

```sh
./gradlew :app:compileDebugKotlin --console=plain
```

QA focused gates (after compile/test execution permission):

```sh
./gradlew :app:testDebugUnitTest --tests 'com.dragosfotache.gymtracka.data.sync.StrictDuplicateKeyTest' --rerun-tasks --console=plain
./gradlew :app:compileDebugAndroidTestKotlin --console=plain
# Device/emulator required; if unavailable, report blocked/unverified, never pass or skip.
./gradlew :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.StrictDuplicateKeyAndroidTest' --console=plain
```

Coordinator independently reviews complete new/changed files and runs the broader offline JVM regression task,
production compilation/assembly and Android-test compilation. Inspect actual executed XML names/failures/errors/skips,
not up-to-date task status. Run both diff checks. The isolated real backend need not be started for this raw-codec slice;
no service/OAuth/live phone pass is claimed. A device gate absent when needed stays explicitly unverified.

Stop after R02a coordinator review. No automatic R02b, transport, migration or configuration work. Execution
authorization is limited to this slice and its coordinator-owned delivery commit. Contributor commits,
backend/configuration changes and pushes remain forbidden.
