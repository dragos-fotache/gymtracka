# R06c — atomic canonical acknowledgment

## Authority, goal and stop

The owner authorized R06c. Deliver an **inert single-head acknowledgment operation** on the existing PushRun. Commit the complete accepted canonical batch, exact frozen outbox removal and generation-scoped local metadata acknowledgment in one fenced Room transaction. R06a/b and R05a/b/c are accepted foundations, not work to recreate.

Stop after independent review and scoped acceptance. No HTTP driver/delivery loop, pull/checkpoint, bootstrap, publication invocation, session activation, UI, scheduler, backend operations, migration, asset/schema edits, commit or push. Production remains signed-out/local-only. R06 package completion still needs the delivery loop.

Owning references: [implementation](../../sync/implementation.md), [approved design](../../sync/integration-design.md#3-revision-deletion-and-original-outcome-rules), [testing policy](../../testing-strategy.md#proportional-coverage-and-verification-gates), [workflow](../../coordinator-workflow.md), and the finalized [shared contract](../../../shared-docs/docs/backend-contract/README.md). No shared-contract change is intended.

## Concrete API

Keep all existing public R06a/b behavior/signatures intact. In `data.sync.store` add:

```kotlin
// Member of existing PushRun; accepted is an existing R06b owned snapshot.
suspend fun acknowledgeAccepted(
    claim: PushClaim,
    accepted: PushOutcomeResult.Accepted,
): PushAcknowledgmentResult

sealed class PushAcknowledgmentResult {
    data class Acknowledged(
        val canonical: CanonicalBatchResult,
        val acknowledgedRoots: Int,
    ) : PushAcknowledgmentResult()
    data object RetainedCorruption : PushAcknowledgmentResult()
}
```

`acknowledgedRoots` counts physical affected roots changed from pending to synced at exactly their frozen local generation. It is not a server-change count, removal count or convergence signal. Missing roots and roots with newer domain/ledger generations are not counted. String displays must not include batch bodies, IDs or service codes.

The `accepted` snapshot is data, **not write authority**. Extend its private construction in handleResult to retain immutable source identity: account and exact original frozen row identity (sequence, mutation ID, aggregate type/ID, kind, local generation, payload version and exact wrapper bytes). This binding excludes attempt/retry bookkeeping and live run/session/epoch, so unchanged durable replay may reuse an earlier snapshot with fresh admission. Do not expose identity or allow caller construction. Before first suspension, own/revalidate the outcome and require that source identity matches the claim, exact claimed mutation ID, accepted or duplicate-accepted status, no error, full valid batch, and correct envelope target operation (DELETE for aggregate.delete, UPSERT otherwise). A snapshot from another account or a changed request must not apply merely because mutation/target IDs match. Wrong snapshot binding throws fixed `IllegalArgumentException("Accepted outcome does not match push claim")` before writes and does not consume claim. Existing handleResult remains read-only for accepted results; it must not be changed to acknowledge automatically. An accepted snapshot from an earlier admission may be reused only with a freshly admitted exact unchanged request and all live checks.

Reuse the R05b owned canonical writer inside this same transaction (make its private helper internal if needed), not an independent nested convenience transaction or duplicate revision algorithm. Existing CanonicalStore/CanonicalTransaction behavior must remain unchanged.

## Transaction and context contract

1. Reject foreign/closed/outside-run capabilities and enclosing Room transactions as existing R06b does. Serialize acknowledge/check/result/failure with the existing per-run handling mutex. Check cancellation, consumed token, live admission, device account/session, cache epoch and **every field of the exact current explicit head** inside Room before acknowledgment writes. Reclaimed/tampered/nonhead/missing head is stale, never acknowledgment evidence.
2. Validate **all frozen local affected roots**, including effects absent from the server batch. Their account/type/ID independent ledger reservation must exist and have nonnegative watermark >= frozen generation. Existing physical root, if any, must belong to the selected account (custom must be user-created), have local generation >= frozen generation, positive-or-null server revision and `sync_state='pending'`; its ledger watermark must also be >= physical generation. A vanished physical root is valid if independent reservation supplies the generation evidence. Do not fabricate/reinsert a missing root.
3. Invalid acknowledgment context retains the exact frozen head as `frozen`, code `ack_context_corrupt`, message `Push acknowledgment context was invalid; recovery required.`, null retry time. No canonical/domain/cache/watermark/cursor writes occur. Use the existing fenced bookkeeping/token-consumption protocol; return RetainedCorruption. A stale head/fence is an exception, not this persisted error. Do not repair missing reservations, foreign ownership, lowered generations, invalid state or bundled identities.
4. After valid context, conservatively consume the process-local claim token **before any canonical/metadata/removal write attempt**. Rollback/uncertain commit never releases it. Original cancellation/fence/storage errors still propagate; reclaim the same frozen identity to retry. This is not persistence evidence.
5. Apply the entire batch with R05b arbitration/family validation: older results are no-ops, equal authoritative conflicts reject the complete transaction, newer observations replace complete independent canonical evidence. Extra cloud roots (including unknown cloud-old-active DELETE) are stored but never local acknowledgment context. An all-obsolete accepted duplicate still removes the exact request without restoring old winners.
6. For each existing affected physical root whose domain generation **and ledger watermark equal the frozen effect generation**, update **only** `server_revision` and `sync_state='synced'`. Revision becomes max(existing physical positive revision, retained canonical positive revision for that affected root), treating null as no evidence; absence from the server batch never invents a revision. Synced means this exact local generation's command settled, not that local business fields equal canonical data or that the account converged. If either generation is newer, preserve all physical metadata as well as business fields. Never lower/increment generations or watermarks. Do not acknowledge unrelated server-change roots or later outbox rows.
7. Remove only the selected exact frozen row with row-count checking. Recheck the exact row immediately before removal, and verify absence afterward. Recheck affected metadata write results/unchanged newer roots, every affected ledger reservation's unchanged watermark (including vanished/newer roots), live admission, session/cache fences and cancellation after writes before commit. Trigger-induced outbox/affected-root metadata tampering must not commit unnoticed. These checks defend acknowledgment context; they are not an arbitrary malicious-trigger sandbox. Separately assert that the operation itself preserves domain business rows/children exactly. No successor is claimed/sent here.
8. Mark selected account projection dirty on successful acknowledgment **even if the whole canonical batch is obsolete/equal**: outbox protection has changed and local materialization can differ from canonical. Preserve baseline-complete, cache/materialized epochs, cursor/last-success, device identity, catalogue, other scopes, all domain business data/children/tombstones and all other outbox rows byte-for-byte. Never invoke protected publication or repository capture; no new upload work.

Owned SQLite errors map to existing PushStorageException with fixed text/no raw cause; canonical conflict/retained-evidence and stale fence exceptions retain existing safe types. Caller errors/cancellation retain provenance. No clock, UUID provider, transport or permissive barrier is added. Reuse Room v10: DAO queries/access changes only, no entity/schema/migration change.

## Contributor boundaries

One fresh native session per role; verified `meta/muse-spark-1.3-contributor`, high reasoning. Reports to `gym-coordinator` are asynchronous `herdr agent prompt` without --wait. No waiting/polling contributor output. All Gradle work needs a coordinator-granted serialized slot.

**Worker read:** this spec, relevant owning docs/shared contract, production `app/src/main/java/com/dragosfotache/gymtracka/data/sync/**` and `data/gym/**` plus catalogue entity declarations needed for custom metadata. No tests, QA files, fixtures, reports, test diffs or decompiled code. **Worker write:** `data/sync/store/PushStore.kt`, `PushOutcome.kt` (private accepted provenance only), `PushAcknowledgment.kt` (new), focused `PushAcknowledgmentContext.kt` (new if useful), `CanonicalStore.kt` (minimal helper visibility change), `data/gym/SyncDao.kt`, `SyncCaptureDao.kt`. Ask coordinator for any wider file need; no docs/build/schema changes.

**QA read:** this spec, owning docs/shared contract, test sources under `app/src/androidTest/**` and `app/src/test/**`, including existing independent support listed below; no production declarations/implementations/diffs, Worker work or decompiled code. **QA write:** new `data/sync/PushAcknowledgmentTest.kt`, `PushAcknowledgmentFenceTest.kt`, `PushAcknowledgmentTestSupport.kt` under androidTest (split another acknowledgment test class if coherent; report path). Preserve existing tests/support. Ask for missing API facts, never inspect production to infer them. Compilation/diagnostics may name production APIs but do not grant source access.

Coordinator owns spec, cross-role harness facts, shared integration/build changes, final independent review and owning-document updates. No required new pure JVM helper suite: transactional behavior needs real Room and existing codec/ownership validation already has extensive coverage.

## Existing API and harness facts for QA

Reuse `GymDatabaseTest` (real in-memory Room, protected `database`) and existing `PushClaimTestSupport.kt` / `PushOutcomeTestSupport.kt` helpers rather than clone a harness. Allowed support supplies claimSeedDevice, claimSeedCache, claimExec, claimSnapshot (all-table SQLite snapshot), claimSeedWorkout, claimCaptureStore, claimCaptureStart with ExtraAffectedRoot, claimCaptureUpsert, claimPushStore, claimOwner, outcomeSeedFenced, outcomeCaptureHead, outcomeBatch/AcceptedResult and fixed IDs. `claimCaptureUpsert` captures payload/metadata but does not itself edit physical workout name; direct SQL can prepare realistic business edits inside capture transactions. Metadata reads can use SQL to avoid requesting new declaration access.

Existing constructors: PushStore(database, RepositorySession(account, positiveGeneration), shared PushRunOwner); withRun(expectedCacheEpoch) { run -> ... }; claimHead(nowMillis) -> PushHeadResult.Claimed.claim; handleResult(claim, PushResultModel) -> PushOutcomeResult.Accepted; checkClaim(claim); CanonicalStore(database, session).applyBatch(batch, expectedCacheEpoch) -> CanonicalBatchResult(appliedRoots, ignoredRoots). Canonical exceptions: CanonicalConflictException, CanonicalStorageException, StaleCanonicalEpochException; push: PushStorageException, StalePushClaimException; session: StaleSessionException. Constructor/model/codec call shapes can be learned from allowed existing tests only.

SQL tables: sync_canonical_roots(account_id, aggregate_type, aggregate_id, server_revision, canonical_state, canonical_json, local_generation_watermark, cache_epoch); sync_account_cache(account_id, baseline_complete, cache_epoch, projection_dirty, materialized_epoch); sync_state(account_id, cursor, last_success_millis); root metadata is account_id/server_revision/local_generation/sync_state/deleted_at_millis in workouts/routines/exercises. Custom exercise flag is is_user_created. Read persisted canonical JSON with existing decoders or literal independent expectations; do not generate expected JSON from production encoder output.

Use existing Room query-callback/transaction-executor gates from PushOutcomeFenceTest for pre-suspension ownership and post-write cancellation. Query callbacks occur **before** SQL: cancellation must fire on a following query after observed write. Verify hook fired and compare complete persisted state. Test-owned SQLite triggers can abort metadata UPDATE, canonical INSERT/UPDATE or outbox DELETE and can change fences/rows after those writes; final validation must roll everything back. No sleeps.

## Risk-selected independent acceptance matrix

High persistence/exact-retry/account safety risk, bounded inert operation. Every case needs independently expected values and a distinct plausible defect; combine representative variants rather than reproduce full prerequisite matrices.

- Complete accepted multi-root replacement, target acknowledgment and locally affected old root absent from cloud changes; cloud-old-active unknown deletion stored; unrelated/out-of-scope data, children/tombstones, cursor/last-success/epochs and tail bytes unchanged. Exercise all three physical metadata update paths (workout, routine, custom) with compact representative cases.
- Newer same-root edit and later replacement effects while HTTP/result pending: lower admitted generations settle but newer physical metadata/children/watermarks/tails remain unchanged. Missing physical root with valid independent reservation succeeds without recreation. Exact matching metadata receives monotonic highest known revision and synced state.
- Duplicate original accepted result after later canonical winner: correct canonical ignored count, old body not restored, exact head removed, dirty set even all-no-op; existing equal-revision convenience-name coverage reused. Representative equal authoritative conflict/invalid retained evidence rolls back acknowledgment and leaves head recoverable.
- Corrupt local acknowledgment context (missing/low ledger, foreign/bundled root, lowered physical generation/invalid state): fixed retained error, no partial canonical/root effects, head blocks successor. Use compact table-driven setup, not every field/family cross-product.
- Previously returned accepted snapshot and prior check are not authority: reclaimed/tampered/nonhead claim, stale account/session/epoch, consumed/foreign/closed run, enclosing transaction and wrong accepted mutation/account/exact-request snapshot reject without writes. Representative boundaries suffice; retain existing comprehensive R06a/b regressions.
- Concurrent acknowledgment callbacks settle once; competing outcome/failure handling serializes and cannot reuse consumed claim. Post-write cancellation, fence/row/watermark tampering and SQLite abort after canonical/metadata/deletion demonstrate whole-transaction rollback and conservative token consumption; reclaim unchanged frozen bytes and settle after fault removal.
- File-backed Room reopen before acknowledgment keeps exact identity; committed acknowledgment persists removal and canonical/metadata/dirty state on reopen. Rejected/malformed read-only prior results cannot become success; existing result-shape matrix reused.

## Verification gates

Iteration: focused Kotlin/KSP compilation and acknowledgment tests; Android-test compile after API/test integration. No concurrent contributor/coordinator builds.

Slice acceptance: fresh full offline JVM `:app:testDebugUnitTest --rerun`, `:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin`, executed focused real-Room acknowledgment classes plus affected PushOutcome and PushClaim/Replay and CanonicalStore regressions on an isolated available emulator. Verify actual XML names/counts/failures/errors/skips; one fully qualified filter at a time because comma filters are unreliable locally. Check both diffs/whitespace and asset/schema preservation.

Full unfiltered instrumentation, assembly/lint/APK review remain explicitly deferred to **R06 package completion after delivery-loop delivery** (no packaging/dependency/activation change in this slice). API 24, hosted CI, real backend/engine convergence and owner phone/Google journeys remain their existing later gates; scoped Room tests never imply those passed. If required focused execution is unavailable, record blocker and leave acceptance open rather than silently declaring completion.
