# Coordinator pickup

> **Current snapshot only. Replace this state at handoff; do not append session history.**
> Follow [progressive reading](README.md#progressive-reading-and-context-discipline) and the
> [coordinator workflow](coordinator-workflow.md).

## Next eligible task and authority

**R08c — complete canonical baseline, absence and publication recovery** is next dependency-ready under the owner's
[standing local-commit/rotation/continuation rule](coordinator-workflow.md#coordinator-rotation-and-automatic-continuation).
Fresh coordinator verifies minimal scope/authority/dependency/gate/live-state readiness, reports asynchronously to outgoing
live coordinator and stays read-only until release. Readiness is not a completed design/contract/code audit. After release,
read concrete applicable authoritative sections/source/API/harness, settle ONE bounded normative spec and independent
allowlists/proportional gates/stop before delegation. Bring every unresolved blocker/question to user. No activation,
push, deployment, private configuration or live Google/account/personal-device action is authorized.

[R08b](archive/remaining-work/R08b-review.md) accepts bounded actual frozen-snapshot HTTP download after R08a/R07/R06/R05.
No implementation spec remains open. Reuse Room11 staging begin/read/check/append/abandon on the SAME admitted `PushRun`,
`SnapshotDeliveryLoop` start/continue and redacted results, existing snapshot models/codecs/`GymSyncApi`/secure HTTP/refresh,
canonical ledger/batches/protected publisher and R07 checkpoint/shared driver. No replacement owner/API/codec/storage.
See [staging storage](sync/implementation.md#frozen-snapshot-staging-storage),
[bounded download](sync/implementation.md#bounded-frozen-snapshot-download),
[canonical batches](sync/implementation.md#fenced-canonical-batches),
[protected publication](sync/implementation.md#protected-publication) and
[snapshot design](sync/integration-design.md#5-frozen-snapshots-and-full-resync).
Read task-relevant frozen snapshot/token/retention, graph/domain/persistence/publication and wire requirements, not the
entire shared contract map during pickup.

## Accepted capability and invariant boundaries

- One application-owned `PushRunOwner` per physical DB must span network/commit/publication coordination. API/store/run
  bind the same authorized service/account/session. Only actual fenced push Empty authorizes following download;
  retained/rejected/corrupt/oversize/deferred/auth/failure/budget stops never authorize recovery around intent. Limits
  never probe heads; push cursors are not checkpoints. Selected stages, including downloaded/corrupt headers, block
  ordinary push/pull until explicitly completed or abandoned. Begin requires ANY selected outbox EXISTS=false;
  capture AFTER begin remains allowed and unchanged.
- `SnapshotDeliveryLoop.start(run,stageId,maxPushMutations,maxPages,pageLimit)` is admitted-only/push-gated.
  `continueDownload(run,previous,maxPages)` accepts ONLY this driver's LimitReached result with the existing same-run
  capability. Invocation budgets1..100 pages and fixed1..500 records per page do not impose permanent account-size caps.
  Live begin/append/driver chaining can continue in ONE uninterrupted run; durable read/restart is ONLY a nonappendable
  observation for explicit exact valid-stage abandon and NEW UUID/null-token restart, not cross-run append authority.
- Whole typed pages are owned/validated before post-response suspension. Exact stage/account/session/epoch/fixed limit,
  full original checkpoint/baseline observation, frozen baseline/root/token/page evidence and cancellation fences apply
  to data, terminal, malformed and typed failure/expiry results. Only authentic SnapshotExpired requests explicit restart;
  invalid/foreign/wrong-context tokens never infer cleanup. Failed/uncertain writes require durable reread; retirement is
  not commit evidence. Earlier returned push/page commits remain durable after later failure. No automatic cancellation
  cleanup or publication/last-success exists in the downloader.
- Downloaded storage alone proves stored terminal progress, not actual HTTP exhaustion. R08b driver Downloaded summarizes
  validated terminal transport/staging for its uninterrupted chain, still NOT complete account graph/bootstrap/canonical
  baseline/absence/last-success/publication/convergence. Constructible generic summaries must not become authority.
- Preserve domain/outbox/watermarks/canonical checkpoint/last-success/newer intent/other scopes/catalogue and exact frozen
  uncertain IDs+bytes. Reuse existing protection/capture transactions, not incremental/staged absence deletion.
  Existing publication advances epoch; eventual callers must use the returned epoch.

## R08c scope and required gates

Settle concrete complete-stage validation/transaction/publication/recovery interfaces against authoritative code after
release. Only a complete validated frozen account snapshot can establish canonical absence/baseline, including old
unchanged roots, later-page dependencies and a valid empty account. Never publish partial/staged data or infer deletion
from ordinary incremental absence. Validate complete graph and physical collisions under correct scope/fences; preserve
old/local projection while newer intent exists. Distinguish canonical baseline establishment from protected visible
publication and their crash/uncertain-commit/restart evidence. Do not infer personal replace/sign-out/account authority.

R08c closes the R08 package: declared full unfiltered instrumentation, explicit assembly/lint/APK and applicable isolated
real backend package/integration gates deferred by R08b remain obligations, with exact task-relevant selection before
new delegation. Real Room complete empty/old-unchanged account, middle/final failure, later reference, concurrent edit,
restart/switch/expiry and atomic baseline/absence/publication gates remain outstanding. Unavailable required tools/models/
gates are user blockers; no substitute/skipped gate. Any entity change requires version bump/data-preserving migration,
KSP/export/every-supported-start plus copied bundled v1 coverage; verify live version before assigning changes.

Production remains signed-out/local-only. Room11 retains prior17 entity definitions and adds R08a's3 independent staging
entities; exported1–11 and bundled sourceDB v1 are unchanged. No complete bootstrap/full baseline/absence recovery,
durable Room/vault authority/journal, Google/account UI, live publication/edit/timer barrier, scheduler or engine
convergence is constructed by the app. R09 authority/journal, R12a real finite-edit/timer barriers and R10 convergence still
precede activation; do not pull them forward under R08c.

## Evidence, risks and live pickup checklist

- R08b review owns actual fresh full offline JVM, focused new driver/fence plus existing staging/pull/shared-driver Room
  gates and compilation evidence; initial Kotlin/import/race-fixture failures are honestly retained. R08b deliberately
  did not repeat full unfiltered instrumentation/explicit assembly/lint/APK/backend; R08a review owns prior full/device/
  migrations/packaging evidence. Neither proves API24/hostedCI/Google/two-store convergence/activation.
- Strict [legacy accepted-auth replay](sync/implementation.md#legacy-auth-replay-limitation) remains unresolved: preserve
  original uncertain key/body, never round/rewrite/rekey, revoke broadly or infer credential/cache deletion. Stored vault
  phases/process-local leases are not durable Room authority/sign-out; admission cannot recall already-sent network bytes.
- Phone LAN/away HTTPS/private connectivity, OAuth audience/signing and authorized device/account inputs remain live
  milestone blockers. Backend is isolated operational build/start/black-box HTTP ONLY if a declared gate requires it;
  no backend source/private configuration/personal fixtures/suite or live account/Google actions.
- Verify BOTH live trees/indexes/untracked/HEADs and actual roles/models/resources. Preserve subsequent user work and
  accepted commits; never amend/rewrite. Completed R08b Worker/QA conversations must be replaced ONLY in task-owned
  panes by fresh native `meta/muse-spark-1.3-contributor`/high sessions after release, with source independence.
- Test-owned emulator/private Android/AVD home were stopped/removed. Default ADB server/Gradle/Kotlin daemons preserved;
  no active build/Gradle slot/resource owner transfers. Do not assume any backend/device running. Serialize EVERY Gradle.
- Reports fully async: never --wait/agent wait/acknowledgment polling. At accepted gates update owning docs/archive
  evidence/spec, commit ONLY scoped accepted slice locally(no push), launch one fresh exact-model next coordinator,
  receive asynchronous readiness, transfer live role and close ONLY your own pane LAST.
