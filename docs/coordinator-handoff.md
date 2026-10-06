# Coordinator pickup

> **Current snapshot only. Replace this state at handoff; do not append session history.**
> Follow [progressive reading](README.md#progressive-reading-and-context-discipline) and the
> [coordinator workflow](coordinator-workflow.md).

## Stopping state and future authority

**R08c — complete canonical baseline, absence and publication recovery is accepted.** The owner requires stopping at
this boundary with durable handoff documentation: **no successor coordinator or next-slice work starts automatically.**
No successor was launched. [R08c review](archive/remaining-work/R08c-review.md) owns the acceptance evidence and
[archived spec](archive/remaining-work/R08c-task.md) the normative delivered contract. No implementation spec remains open.

**R09 — account transitions and import/replace** is dependency-ready for a future explicitly started/released coordinator.
Its first bounded journal/authority slice must be settled then, not specified or implemented during R08c closure.
R04 and R08 foundations are available; R12a real finite-edit/timer barriers and core R10 convergence still precede
production account/projection activation. No push, deployment, private configuration, live Google/account/personal-device
action or deferred milestone is authorized.

Future pickup is minimal/read-only: supplied unchanged AGENTS → README progressive policy → this handoff → roadmap
STATUS/DEPENDENCIES → workflow authority/transfer rules, plus BOTH live trees/indexes/all untracked/HEADs and actual
roles/models/resource ownership. Readiness is bounded scope/authority/dependencies/gates, not a completed system audit.
After explicit release, read applicable authoritative sections, referenced wire types and concrete source/API/harness
invariant dependencies; settle ONE normative spec with disjoint allowlists/risk-selected gates/exact stop before delegation.
Read the full workflow before delegation. Bring EVERY unresolved blocker/question to the user; never substitute a model,
skip a gate, infer authority, choose another task or widen scope. Preserve accepted local history and subsequent user work;
never amend/rewrite. Rediscover commits/live state rather than trusting this document as a receipt.

Coordinator model must be EXACT native Pi `openai-codex/gpt-6.1-sol`/high, not Contributor. Future Worker/QA must be FRESH
native `meta/muse-spark-1.3-contributor`/high sessions in rediscovered task-owned panes. Current R08c conversations are
completed, not reusable for R09. Maintain production/test source independence; coordinator owns integration/final review.
Reports remain asynchronous (`herdr agent prompt gym-coordinator ...`), never `--wait`, `agent wait` or acknowledgment
polling. EVERY Gradle requires an explicitly serialized owner slot.

## Accepted capability boundaries to reuse

- SAME admitted `PushRun`, handling Mutex and one application-owned `PushRunOwner` per physical database across HTTP,
  canonical commits and coordinated publication. Pair API/store/run/publisher to the same already authorized service,
  account and session. These inert components do not supply durable Room/vault authority.
- Existing models/codecs/`GymSyncApi`/secure HTTP/refresh, canonical ledger/batches/watermarks, protected publisher and
  R07 checkpoints/shared push-first driver are foundations, not replacement tasks. See
  [implementation](sync/implementation.md#complete-snapshot-baseline-and-publication-recovery) and
  [snapshot design](sync/integration-design.md#5-frozen-snapshots-and-full-resync).
- Only actual fenced push Empty authorizes `SnapshotDeliveryLoop.start(run,stageId,maxPushMutations,maxPages,pageLimit)`.
  Retained/rejected/corrupt/oversize/deferred/auth/failure/budget stops never authorize recovery around intent. Begin
  requires selected outbox EXISTS=false; capture AFTER begin remains allowed/protected. Invocation budgets1..100 are
  not permanent account-size caps; fixed record limit1..500 and whole aggregate/page ownership remain unchanged.
- Selected durable stages, including downloaded/corrupt, block ordinary push/pull and direct publication. Actual same-
  driver LimitReached results continue only in the uninterrupted run. Read/restart observes for explicit exact valid-stage
  abandon and NEW UUID/null-token restart, NEVER append or completion readoption. Authentic SnapshotExpired requests
  explicit restart; invalid/foreign/wrong-context failures never infer cleanup. Corrupt retained evidence fails closed.
- Downloaded storage/result alone is not a baseline. `SnapshotDeliveryLoop.complete(run,previous)` requires actual
  private terminal issuance and current same-run stage/source/session/epoch evidence. First eligible attempt consumes
  issuance even on later validation failure; attempted writes retire stage/checkpoint tokens. Generic summaries do not
  authorize writes, and retired tokens are never success evidence.
- Complete frozen graph validation includes valid empty/old-unchanged accounts, later-page dependencies, catalogue and
  relationship lookups, sole active workout and known family/parent bindings. Lower/equal-conflicting retained revisions
  fail closed. Atomic canonical upserts/observed absence, complete/dirty cache, exact baseline checkpoint and stage removal
  commit at epoch+1 WITHOUT visible edits or last-success. Missing unobserved reservations stay unobserved; known missing
  roots retain revision evidence without invented deletion revisions. Preserve watermarks/exact uncertain IDs+bytes.
- `PushRun.publishProjection(pairedStore)` reuses the existing required barrier/publisher under the SAME handling Mutex,
  verifies database/account/session before barrier, retires old capabilities and adopts only returned active-resumption
  epochs. ANY selected outbox protects the whole old/local projection. Clean publication validates physical/global PK/
  family/reference collisions and advances epoch again; NEVER overwrite/remap local/foreign/bundled identities.
- A publication failure leaves the already committed canonical baseline dirty/durable; failed/uncertain writes require
  actual durable stage/cache/canonical/checkpoint reread. Retained stages require explicit abandon/restart. Complete dirty
  baseline with no stage can publish from a fresh paired run at its durable epoch WITHOUT redownload; clean recovery is
  UNCHANGED. Stage disappearance/old summaries/lease-close failure do not prove a call's success or rollback.
- Completion/publication preserve source last-success; subsequent actual R07 valid pull tail remains separate from
  baseline, protected publication and convergence. No incremental/staged absence deletion or partial visible graph.
  Preserve domain/newer intent/outbox/watermarks/other scopes/catalogue except the explicitly fenced baseline/publication
  effects. No personal import/replace/sign-out, credential/cache deletion or activation authority is inferred.

## Evidence, remaining gates and resources

- R08c review records fresh full offline JVM, full UNFILTERED API37/Android17 instrumentation, full isolated real-backend
  package, explicit assembly/lint/APK inspection, compilation/KSP and preservation evidence. Initial QA import and
  coordinator local-binding compile failures, plus review-driven oracle refinements, are honestly retained. Focused and
  repeated-method runs are not extra full-suite counts. R08a/b reviews remain scoped historical evidence, not new passes.
- Room/schema11 still has20 entities; exported1–11 and bundled source v1 bytes are unchanged. Any future entity change
  requires preserving migration/version bump/KSP/export and every-supported-start/copied-v1 gates; never destructive fallback.
- API24, hosted CI, successful Google, deployed phone TLS/connectivity, real Room/vault authority/journal, two-store engine
  convergence, live edit/timer barriers and app activation remain unverified/dependent milestones. R08 package passes do
  not substitute for R09/R10/R12 or later authorized journeys.
- Strict [legacy accepted-auth replay](sync/implementation.md#legacy-auth-replay-limitation) remains unresolved: NEVER
  round/rewrite/rekey original uncertain grants, infer broad revocation or credential/cache cleanup. Stored vault phases
  and process-local leases are not durable account authorization or sign-out.
- Owner phone LAN/away HTTPS/private connectivity, OAuth audience/signing and authorized device/account inputs remain
  external milestone blockers. Backend access remains isolated operational build/start/black-box HTTP ONLY for declared
  gates, never backend source/private configuration/personal fixtures/suite or live Google/account operations.
- Test-owned R08c emulator/private Android/AVD home and isolated backend/container/ephemeral config are stopped/removed;
  its temporary backend pane is closed. Existing default ADB/Gradle/Kotlin daemons and unrelated panes are preserved.
  No active build/Gradle slot/device/backend/resource owner transfers. Rediscover; assume nothing is running.
