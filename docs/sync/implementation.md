# Implemented Android sync components

Default `GymApp()` is the mandatory account/configuration gate. A configured factory constructs private authority,
real scoped edit/timer barriers and foreground identity/exchange seams. Startup recovery itself is read-only and never
calls HTTP or Google UI; after complete ACTIVE admission, a separate foreground controller may reconcile automatically. Explicit service-confirmed sign-in uses approved P18 capture/consent, exact delivery
and complete Room14 activation. Matching established accounts reopen/edit offline without login every launch.
Foreground/edit/manual synchronization and captured confirmed ordinary rejection/stage recovery use the existing
fenced ACTIVE seams. No sign-out/switching, scheduler, deployment or personal account/device operation is inferred.
The [roadmap](../remaining-work-roadmap.md) owns delivery status; the
[integration design](integration-design.md) owns approved future coordination. The
[shared contract](../../shared-docs/docs/backend-contract/README.md) remains the service authority.

Paths below are relative to `app/src/main/java/com/dragosfotache/gymtracka/data/`.

## Mandatory startup and account presentation

`../ui/account/AccountStartupApp` owns the default factory/presentation; MainActivity remains setup only.
Explicit `GymApp(appState)` is an internal feature/test path, never a production signed-out fallback.

- Nonsecret build inputs `gymtracka.serviceOrigin` and `gymtracka.googleServerClientId` generate BuildConfig strings
  with empty defaults. Strict original HTTPS root-origin parsing never discovers an endpoint/audience, trims invalid
  inputs or transfers old credentials to another origin. Missing/invalid origin renders privacy/configuration gates
  without database/vault/HTTP reads. A valid origin with missing audience still recovers a matching offline account;
  only foreground sign-in is unavailable. Actual provider/signing/audience/trusted deployment remain separate gates.
- Production reuses one application-process vault and physical-DB run owner, an Activity-owned Google adapter and a
  composition-owned runtime/controller. Startup invokes one recovery, including exact clean completion-gap promotion,
  not private bootstrap or preparation retries. Visibility is exclusively the runtime's synchronous exact live handle
  plus complete matching Room/vault evidence; collected DTOs, Google success and private progress never grant it.
- `AccountStartupState` nonqueues explicit sign-in/reauthentication/continue with lazy owned jobs registered before
  dispatch, generation fencing and cancellation-safe occupation release. Fresh advisory recovery precedes effects;
  failed fresh recovery cannot fall back to an old snapshot. Initial and ordinary/private-import reauthentication
  choose the existing exact intent/operation contracts; service grant consumption is followed by at most one activation
  attempt. Existing real editor/edit/timer barriers drain accepted work and whole-second rest once. Open explicit-save
  editors refuse before Google launch and after drain; no hidden Save/Discard or guessed scope substitutes for a lease.
- `AccountImportConsent` suspends for the original-active replacement warning. Each answer captures one opaque decision
  token; stale callbacks cannot consent a later invocation. Cancel/dismiss never proceeds or claims remote rollback.
  Confirmed consent is durably pinned by the facade to exact retained import lineage/mutation. Validated accepted/total
  progress and closed errors remain private during blocked, cancelled or incomplete preparation; no queue-empty shortcut,
  collision overwrite, ID remapping, cleanup, account reassignment or local editing fallback exists.
- The account tab/status dialog exposes selected pending count (including frozen/rejected), nullable recorded last-success,
  retained-error presence and closed delivery/error states. `AppSessionCoordinator.observeSyncStatus` is a cold, read-only
  metadata Flow: observer registration precedes initial read; each reread uses transactional exact ready journal/device/
  cache checks and process/handle checks after dispatch. Last-success requires SQL-null/missing or a nonnegative INTEGER
  storage cell; TEXT/REAL/BLOB refuses unavailable before a typed getter can fabricate epoch-zero success. No HTTP,
  clock, UUID, cleanup, rebind or reusable authority is emitted. Private/new-handle transitions clear the old DTO before
  a new collector emits. Unknown status is never zero/success; missing audience disables reauthentication, not offline UI.
- Cancellation/disposal fences owned operations/status/consent, closes foreground/provider/runtime ownership and preserves
  durable source, exact requests, vault and retained errors. It never invokes credential-state clear/logout or compensates
  a committed remote prefix. Recoverable pre-grant/delivery stops preserve the healthy exact offline handle. Prepared
  bindings remain private until coherent complete activation. Retained stages are displayed, not implicitly restarted.

See [runtime architecture](../architecture.md#application-structure), [private import](#private-import-delivery-and-activation),
[app authority](#app-facing-authority-and-offline-recovery) and [foreground exchange](#bounded-foreground-exchange-and-guarded-grant-handoff).

## Foreground synchronization and ordinary recovery

`../ui/account/AccountSyncState` is composition-owned; `SessionAccountSyncOperations` delegates only exact current handles
and matching runtime edit counters. It owns one serialized operation slot, nonqueueing busy/private manual actions,
foreground/activation demand and debounced successful-edit demand. Handle-scoped raw counters/covered watermarks and
first-counter readiness prevent duplicate activation/backlog runs while preserving genuinely newer intent. Cancellation
retires owned work before readmission, never cancels the borrowed scope; a same-handle status emission repairs missing
edit observation without turnover or watermark/readiness resets. Bounded tail/stop completion does not spin around
protected publication, retained errors or backoff. Recoverable stops keep healthy offline editing available.

The account panel provides manual sync, explicit retained-stage restart and confirmed ordinary recovery. Stage/token
callbacks capture exact values; turnover clears old observations. Request-relative warning arming requires a fresh
preparation, not an old token repaint. Cancel/dismiss performs no discard and promises no remote rollback.

`prepareOrdinaryRecovery(session)` and `recoverOrdinaryRejection(session, confirmation)` keep facade/run ownership across
all phases. Preparation settles an already frozen head unchanged, never sends a pending successor, and strictly preflights
stage/ready authority/epoch/full ordinary remainder/absent-or-settled import proof **before** real editor/timer drain.
Nonconfirmable work returns without acquiring that drain. Eligibility is advisory: actual capture revalidates under the
real lease, including newly drained rest writes, then registers an opaque owner/fence/exact-handle-bound single-use token.
No import mutation/source/progress/receipt is an ordinary discard target.

Confirmation pauses/drains again, compares full ordered rows and strict authority/legacy evidence, and transactionally
discards only captured ordinary intent under the same admitted run. Cursor becomes null; recorded last-success is
preserved and projection marked dirty. Final SQL is followed by authority/legacy/lease/process/session/epoch/caller
checks before commit. Failures roll back; cancellation retains genuinely committed results for fresh durable reread.
Strict synchronous legacy cursor reads use Room transaction dispatch even from the actual Main UI caller.

Existing complete snapshot/terminal/post-baseline tail machinery reloads canonical state. A real Tail with known newer
outstanding intent returns `PROTECTED`/retry-required with no publisher/drain, preserving the entire visible graph and
exact pending bytes; unknown/non-Tail fails closed. Only a known clean tail proceeds through existing guarded publication.
The [R13d review](../archive/remaining-work/R13d-review.md) owns independent current/API24/HTTP/package evidence; actual
Google/configuration/deployment/phone and deferred public lifecycle/background work are not inferred.

## Room and local capture

Room v14 retains the v9 root metadata, v10 canonical ledger/publication bookkeeping, v11 staging and v12 authority
journal; v13 adds immutable legacy-import capture evidence and v14 adds current lineage/accepted-batch receipts. Independent
[staging](#frozen-snapshot-staging-storage) and [private authority/journal](#durable-private-room-account-authority) are
described below. Root metadata remains: nullable account/server revision/deletion time, local generation, and sync state
(default `local_only`). `sync_outbox` stores explicit sequence order, immutable mutation identity/version/payload and
local affected-root bindings; `sync_state` stores account cursor state; `sync_device_state` distinguishes active and
privately cached account; `catalogue_state` stores catalogue metadata. These storage fields do not implement sign-out.

`sync/store`, `SyncCaptureDao`, and `GymRepository` provide:

- Immutable expected account/session fences rechecked inside each transaction. The production context constructor
  still defaults to signed-out null/0; future transitions must construct a repository for their actual generation.
- Domain mutation, generation/state advance, and applicable outbox capture in one transaction. Only newer never-sent
  tail upserts coalesce; frozen payloads remain unchanged. No-op, invalid, bundled, foreign, or stale attempts create no
  phantom work. The durable-row reader validates stored identity without repairing corrupt/unsupported data.
- Application-facing reads scoped to visible owned roots plus global bundled catalogue. Child/detail/history joins also
  scope referenced customs; complete internal reads stay separate. Custom edits preserve imported metadata/quantity.
- Custom creation/edit capture full upserts; account deletion checks retained references, removes relationships and
  captures a tombstone/delete. Signed-out custom deletion remains constrained hard deletion.
- Routine drafts/autosaves capture full upserts; promotion captures `routine.save`; child deletions capture semantic
  commands with renumbering; account root deletion retains a tombstone and removes children.
- Workout autosaves capture complete active upserts; start/clone/routine-start/save-as-routine/history/replacement/child
  deletion/finish use stable semantic requests and affected-root bindings. Sole-active replacement is atomic, including
  old deletion and new creation. History selection is scoped/deterministic. Finish preserves frozen predecessors,
  retains valid completed survivors, renumbers both levels, and captures the final semantic command.
- Stale set entities cannot change parent or stored position. Negative repetitions and negative/nonfinite weights
  sanitize to null; configured rest clamps nonnegative. Invalid selections fail atomically before inserts. Completion
  returns an actual transition result for UI/timer coordination.

### Durable legacy graph and private import capture

`sync/legacy/LegacyImportStore` is an explicitly constructed internal storage boundary, not an app handle or service grant.
Its target binds canonical configured HTTPS origin (explicit effective port), first service-confirmed account UUID and
one durable operation. Production callers need real pause/drain/process authority; configured startup supplies it
only for explicit sign-in, never during startup recovery.
The additive12→13 migration leaves old schema/rows/sequences and source asset unchanged; both evidence tables start empty.

- Capture acquires one caller-owned admission/drain lease before reading local source and rechecks lease/process/cancellation
  through the transaction and result dispatch. It refuses caller-owned transactions, foreign/unknown account storage and
  stale expected Room observations. Bundled-only empty storage makes no record, counter advance or UUID allocation.
- Frozen v1 local evidence retains all root/child/relationship rows in fixed ordered arrays. Cells preserve NULL, exact
  INTEGER decimal strings, FLOAT raw-double-bit decimal strings, TEXT and BLOB base64. Invalid metadata/IDs/relationships,
  instructions or scalar types stay recoverable; no cleanup, renumbering, remapping, coercion or skipped drafts repairs them.
- Valid source is associated and captured atomically through existing `SyncTransaction`/typed durable-v1 `aggregate.import`:
  custom UUID order, routine UUID order, finished-workout UUID order, active last. Saved blank/empty routines and empty
  drafts remain their original snapshots. Optional custom metadata/quantity and nullable set/recorded-rest inputs survive;
  raw NULL configured rest remains blocked source evidence, never silently repaired.
  Generation keys include aggregate family; checked g+1 and retained sqlite_sequence high-water space prevent wraparound.
- Raw business/source comparisons allow only account/generation/state metadata changes. Exact final outbox, root,
  unobserved-ledger, incomplete-dirty-cache, journal/device and source checks detect late SQL/trigger tampering and roll back
  together. The ordinary authority refusal stays intact: capture reuses `beginPreparation` only after association removes
  local roots inside the same outer transaction. No ACTIVE/cursor/baseline or vault write is fabricated.
- Invalid source instead commits `blocked`/`invalid_source` with immutable source and a private generation fence, but no
  association, imports, cache or canonical ledger. A retained journal/entry locks ordinary null-account `SyncStore` reads
  and writes, including a newly constructed null-account repository. No-journal behavior is unchanged.
- Strict matching reread/recapture validates target, Room tuple, source/entry count/order/g+1, exact original wrappers,
  import kind/affected-root identity and retained raw business evidence. Extra/orphan/corrupt/missing work fails closed and
  remains retained. Repeat/reopen or committed-but-cancelled result resumes the same capture, never allocates it again.
  Pending/frozen/rejected retry fields may evolve; captured is not accepted. The separate delivery integration below
  supplies definitive receipts before permitting original request removal or legitimate post-publication domain changes.
- Errors/displays are closed/redacted; no credentials enter source or outbox. Caller cancellation, including during lease
  close, takes precedence; closing failure can hide a committed result but cannot imply rollback. Durable reread is the oracle.

Capture itself does not send imports, install credentials or grant visibility. The explicitly opt-in integration below
composes those effects privately; the mandatory account controller invokes it only for explicit sign-in/activation.
Real Google/deployed HTTPS/personal-account/device
evidence remains separately authorized R17a.

### Private import delivery and activation

**Explicitly opt-in storage/delivery boundary:** the [R13b review](../archive/remaining-work/R13b-review.md) owns real Room/vault/HTTP,
source-preservation, migration and scoped regression evidence; the [archived specification](../archive/remaining-work/R13b-task.md)
retains the bounded assignment. The mandatory startup factory supplies this policy for explicit foreground actions;
automatic foreground/edit/manual triggers apply only to an admitted ACTIVE handle, never to private import continuation.

- `LegacyImportPolicy` opts an explicit `AppSessionCoordinator` into capture through its SAME held real transition lease
  and existing private grant preparation. A borrowed check-only lease never closes the outer owner. Empty installs use
  ordinary complete activation without migration records; invalid/foreign source stays private. Default/null-policy
  capture refusal remains intact, and startup constructs no importer or network automation.
- Room14 separates immutable source/target/original operation/entries from current authority lineage and acceptance receipts.
  Captured source validation accounts for every row/parent and original local-only provenance, rejecting orphan/duplicate
  or foreign metadata while retaining blocked raw evidence unchanged. Original durable wrappers are decoded and compared
  to full source-reconstructed inputs, including children/relationships; family-qualified identity preserves cross-family UUID independence. Receipts require accepted/original-accepted complete batches
  with the original root business data, never queue absence or duplicate rejection.
- The existing acknowledgment transaction pins original evidence, commits canonical/metadata effects plus one receipt and
  exact frozen-head removal, then verifies exact expected source/entry/prior-receipt/new-receipt bytes after final SQL.
  Partial prefix recovery retains exact suffix requests and acknowledgment-aware metadata. All receipts permit legitimate
  later publication/ordinary edits without comparing live domain forever to the original graph. Orphans/corruption/rejection
  remain retained/private; no remapping, overwrite, destructive clean start or automatic cleanup exists.
- Original active import needs durable matching replacement-warning consent before delivery. False/canceled/stale consent
  sends no imports; true consent is transactionally bound to the exact expected current lineage and original active mutation.
  Same-account private reauthentication uses a new current operation/generation through accepted P17 recovery, retaining
  original migration provenance, request bytes, receipts, consent and encrypted superseded evidence.
- Generic push Empty/head, snapshot establishment, combined publication/completion, completion-gap promotion and facade
  admission revalidate evidence. Snapshot/publication transactions pin immutable evidence and compare it after final SQL;
  completion allows only its exact known PREPARING→ACTIVE anchor advance. Existing complete snapshot/tail, paired publication
  lease and coherent Room/vault readiness remain mandatory; progress objects authorize neither delivery nor visibility.
- `GymAppState.legacyImportTransitionBarrier` uses the existing owner-dispatcher checked barrier composition seam. It drains
  real finite work and terminal elapsed rest once, refuses explicit-save editors before/after drain without hidden save/discard,
  and propagates close failures. There are no new dialogs, default account screens, triggers or timer redesign.

### Canonical ledger and capture watermarks

R05a adds `sync_canonical_roots`, keyed by `(account_id, aggregate_type, aggregate_id)` independently of domain FKs.
It stores nullable server revision/canonical JSON, state (initially `unobserved`), local-generation watermark and cache
epoch. An unobserved reservation is local bookkeeping, not an observed server record or deletion. The public DAO is
read-only; internal reservation updates touch only the watermark and never replace canonical fields.

Separate `sync_account_cache` holds incomplete-baseline, cache epoch, projection-dirty and materialized-epoch fields.
It is not Room/vault authorization. Keeping it separate from `sync_state` prevents the existing cursor replacement API
from resetting publication bookkeeping. Default startup establishes no account baseline and triggers no publication;
the explicit authority/reconciliation APIs require fresh fenced admission and real runtime barriers.

Changed account captures use checked `max(domain generation, ledger watermark) + 1` for the target and every affected
root. Domain metadata, ledger reservation/advance, account dirty marker and outbox commit in the same fenced transaction;
failures/cancellation roll all effects back. No-op and signed-out capture create no ledger/cache rows. Coalescing remains
never-sent tail autosave only; semantic/frozen predecessors retain their original identities.

Migration 9→10 preserves existing tables and exact requests, seeding only unobserved nonnegative maximum watermarks from
owned roots (including tombstones) and supported outbox column targets. Valid v1 row/wrapper identities additionally
recover extra effects, including vanished roots. Corrupt/unsupported rows remain untouched and their extra effects are
not guessed; supported original target metadata still reserves its generation. Existing domain server revisions remain
in domain rows, never promoted to canonical JSON/revisions without a server record. Discovered account identities receive
incomplete, conservatively dirty bookkeeping even with old cursors/device cache labels. Signed-out/asset upgrades leave
both new tables empty. No local-only or bundled row is seeded as canonical.

### Fenced canonical batches

R05b adds inert `CanonicalStore`/`CanonicalTransaction` writers over the existing v10 ledger; no migration is needed.
A nonnull account/positive repository generation and caller-supplied cache epoch are checked inside Room. Direct calls
deep-own the whole typed batch before suspension; composed capabilities recheck fences and reject closed/outside-
transaction use. Batch writes and dirty marking roll back together on failure/cancellation, including composed callbacks.

- Unknown-root DELETE persists revision evidence without a fake domain row. Older observations are no-ops; newer ones
  replace the complete canonical operation/body. Equal revisions must agree on authoritative fields/operation, ignoring
  only workout/routine exerciseName and unrepresented additive data. Equal no-ops preserve stored JSON/labels exactly.
- Retained evidence validates state/body/revision/type/identity, nonnegative counters and epoch bounds. Original JSON
  duplicate members, scalar grammar/type fidelity and literal controls are checked using the existing strict boundary;
  malformed evidence becomes a safe retained storage failure, never repaired. Valid quoted/escaped strings remain intact.
- Every retained family root reserves its role, including unobserved/delete/snapshot_absent; prior and selected child
  bindings reject role/root/parent changes even when deleting the old root in the batch. Checks include all-no-op batches.
  Account and workout/routine namespaces remain independent. This is current known evidence, not lifetime removed-child
  reservation storage.
- Selected writes preserve exact local-generation watermarks, use but do not increment the account cache epoch, and mark
  projection dirty. Baseline-complete/materialized epoch, domain graph/metadata, outbox bytes/order/errors, device identity
  and cursor/last-success remain unchanged. The writer never calls repository capture or emits upload work.
- Unresolved references, multiple intermediate active roots and physical local/foreign/bundled PK collisions can be stored
  independently; the protected publisher validates the complete graph and physical identities before materialization.
  Existing snapshot_absent evidence blocks lower/equal restoration; R05b does not create absence or invent delete revisions.

Inert [head claiming/replay admission](#durable-head-claiming-and-replay-admission),
[atomic canonical acknowledgment](#atomic-canonical-acknowledgment), a
[bounded push driver](#bounded-push-delivery), [incremental checkpoint transactions](#incremental-checkpoint-transactions)
and [bounded incremental pull](#bounded-incremental-pull) exist, composed by the inert
[shared push-before-pull driver](#shared-push-before-pull-and-valid-tail). Inert
[snapshot staging storage](#frozen-snapshot-staging-storage), [bounded download](#bounded-frozen-snapshot-download)
and [complete baseline/recovery](#complete-snapshot-baseline-and-publication-recovery) exist. The separate durable
vault/Room journal and [app-facing authority boundary](#app-facing-authority-and-offline-recovery) compose them; these
primitives alone grant no account visibility. Production publication/startup activation remains absent.
The independent ledger is not itself a visible projection.

### Protected publication

R05c's inert `ProjectionStore` and focused `SyncProjectionDao` reuse Room v10 without entity/schema changes.
The app constructs no publisher.

- A required `ProjectionPublicationBarrier` acquires a short bound lease outside Room after its caller-owned pause/drain.
  There is no permissive default. The [real app-owned edit/timer barrier](../architecture.md#finite-edit-admission-and-publication-leases)
  exists separately; the app still constructs no publisher. Nested publication is rejected before acquisition; every
  returned lease closes once after the fenced transaction, including blocked/failing/cancelled paths.
- Any selected-account outbox row protects the entire visible account graph, without parsing/filtering state, payload,
  version or error. Protection precedes incomplete-baseline handling and canonical graph validation. Foreign outbox rows
  do not block the account. Missing/incomplete baseline never publishes; clean equal-epoch retry is an unchanged no-op.
- Complete publication validates strict retained bodies/counters and family reservations, all resulting references and
  global lookups, at most one active workout, and current physical family roles/parents/owners before replacement.
  Local/foreign/bundled collisions fail closed without remapping. Workout/routine/exercise namespaces remain independent.
  Surviving other-scope references to an obsolete selected custom prevent its removal.
- Explicit account-scoped deletes/inserts rebuild ordered workout/routine graphs; surviving customs update in place and
  relationships replace without root `INSERT OR REPLACE` cascades. Customs precede referencing children. IDs, fields,
  null/empty distinctions, optional metadata/quantity, timestamps and configured/recorded rest remain exact; embedded
  reference labels never overwrite global/current exercise names. Delete/absence/reservation evidence creates no fake root.
- Every owned root's maximum domain/ledger generation survives removal and rematerialization as an independent watermark.
  Publication emits no outbox work and changes no canonical revision/body/state/row epoch, cursor/last success, device
  identity, other scope or catalogue data. Only watermarks and account publication bookkeeping may change.
- Every actual complete graph publication atomically increments `cache_epoch` and sets `materialized_epoch` to that new
  value while clearing dirty. This existing-v10 monotonic signal covers custom renames at unchanged parent revisions;
  callers must use the returned epoch, since prior-epoch callbacks become stale. Overflow rolls back; blocked/no-op/error
  paths never advance it or establish a baseline. Canonical row epochs may remain older than the account epoch.
- Durable fences and lease validity recheck after graph writes; cancellation rolls back uncommitted changes. Projection-
  owned SQLite failures use fixed safe storage errors without raw causes; barrier/lease-origin failures preserve identity.
  A caller-supplied lease must release without blocking/throwing; cleanup failure is not proof of rollback after commit.

Current canonical/physical bindings are not account-lifetime removed-child reservation storage. R08c supplies inert
[complete baseline/absence and coordinated recovery](#complete-snapshot-baseline-and-publication-recovery).
Any selected durable snapshot stage blocks direct publication before writes, including downloaded/corrupt stages;
foreign stages do not. R12a delivers the real finite-edit/timer seam; R12b adds
[narrow epoch observation and selected-feature refresh](../architecture.md#projection-observation-and-feature-refresh)
without granting account visibility. The [app-facing authority boundary](#app-facing-authority-and-offline-recovery)
is delivered separately. The explicit account runtime supplies guarded canonical timer-impact/scoped app reconciliation;
no primitive, metadata observation or publication lease alone grants account visibility.
These components alone do not authorize production activation.

## Durable head claiming and replay admission

R06a adds inert `PushStore`, required shared `PushRunOwner`, live `PushRun` capabilities and defensively owned `PushClaim`
snapshots over Room v10. No schema change or app construction is involved.

- One caller-owned Mutex serializes complete runs across stores, including caller HTTP outside Room. Entry session/cache
  checks are coherent Room transactions. Nested/enclosing-transaction entry rejects; structured children settle before
  ownership releases; cancellation/exception closes the capability with cross-thread-visible invalidation.
- Selection reads the account's explicit lowest sequence without filtering around state, errors or due time. Empty,
  deferred, rejected, corrupt, unsupported-version and exhausted-count results create/change nothing and never admit a
  successor. Stored error messages are not exposed; original unknown codes remain available separately from diagnostics.
- Valid pending or already frozen work freezes with exactly one checked `attempt_count` increment before claim return.
  This is admission count, not proof of transmission. Legacy frozen/count-zero rows replay compatibly; pending/nonzero
  attempts are corrupt. Exhaustion never wraps. Once frozen, cancellation/transient faults never make it coalescible.
- Column/wrapper versions dispatch before supported-content validation. Claim admission closes v1 wrapper/effect fields
  and reuses the strict row/envelope reader; original wrapper/request JSON, effects and child IDs never normalize,
  regenerate or repair. Older codecs are unchanged. Fresh decoded request getters cannot mutate retained claim authority.
- `checkClaim` is a read-only fenced Room check of the originating live run, immutable session/cache epoch and every
  frozen row field, including exact bytes/effects and admission count. Another claim invalidates older callbacks while
  replaying the same wire identity. Newer local edits retain their own generations/work without rewriting an unchanged head.
- Post-write fences/cancellation roll back freeze/count together. Owned SQLite faults use fixed safe errors; caller-block
  transport/SQLite exceptions preserve provenance. Domain/canonical/cache/device/cursor/last-success remain unchanged.

Every future caller for one physical database must explicitly share the same application-owned owner; this is not a
multiprocess lease, registry or durable vault/Room authorization. Restart creates a new live run and reclaims the same
frozen identity. Successful checking is only admission evidence at that boundary: later R06 outcome/ack transactions
must recheck atomically with their writes. Claiming itself sends no HTTP, applies no canonical outcome and removes no outbox work.
Single-head classification/retry bookkeeping and atomic canonical acknowledgment are implemented below;
[bounded push delivery](#bounded-push-delivery) composes these seams; pull/engine convergence and publication activation
remain outstanding.

## Single-head push outcomes and retry/error handling

R06b extends inert `PushRun` with `handleResult`/`handleFailure`, owned `PushOutcomeResult` snapshots and a pure bounded
retry policy. It reuses Room v10 without entity/schema changes; configured facade actions compose it explicitly,
never as automatic startup delivery.

- Typed results are deeply owned before the first suspension and fully protocol-validated. Exactly one outcome must
  match the claimed mutation ID. Accepted/duplicate-accepted batches must contain the envelope target: DELETE for
  `aggregate.delete`, otherwise UPSERT. Extra cloud changes, including deletion of an unknown prior active, remain
  in their complete original order; local affected roots are not assumed to describe all server effects.
- Accepted and duplicate accepted return defensively fresh complete snapshots, preserving status and old positive
  revisions without touching any storage. They are not acknowledgment authority. Rejected/duplicate rejection stores
  `rejected`, the exact unknown nonblank service code, fixed safe context and null retry time. Invalid result shapes/
  counts/IDs/target operations retain `frozen` with `malformed_response`; they never become success or definitive rejection.
- Transient faults keep frozen identity and null errors, storing an absolute due time. Delay is the larger of
  `min(1000 * 2^(attempt_count-1), 300000)` ms and the service hint clamped to 0..300000, without jitter. Checked time
  overflow retains `retry_time_exhausted` instead of wrapping. Failure handling never increments admission count;
  due replay increments it while retaining exact request/wrapper/effects/child IDs, including after database reopen.
- Access authentication and foreground reauthentication failures are read-only/resumable exact checks, not credential/
  cache cleanup or refresh orchestration. Request-too-large and top-level permanent rejection remain uncertain frozen
  work with exact codes and fixed safe context. Malformed/wrong-operation read-token failures retain malformed response,
  never bootstrap or delete staging. Retained errors and definitive rejection block the head through existing selection.
- Handling and `checkClaim` share a short per-run mutex. Every write rechecks live admission, session/cache fences and
  the entire exact head inside its transaction, updates one row and verifies post-write head/fences/cancellation.
  Before the update it conservatively consumes the process-local claim token, even if the attempt rolls back or its
  commit/resumption is uncertain. This covers identical retry bookkeeping after clock rollback and canceled structured
  children while a parent run survives. Token consumption is **not persistence/ack evidence**: original failures still
  propagate, uncommitted writes roll back, and retry requires fresh admission of the same frozen identity. Read-only
  accepted/auth branches do not consume. Another handler/check on a consumed token is stale.
- Outcomes change only state/error/retry bookkeeping on that head. Domain/canonical/watermark/cache/device/catalogue,
  cursor/last-success, newer tail intent and other-scope rows remain unchanged. Owned SQLite failures keep fixed safe
  diagnostics and no raw causes; result displays omit batches, IDs, payloads and service-issued codes.

Classification remains separate from acknowledgment: accepted handling itself applies/removes nothing. The inert
[bounded push driver](#bounded-push-delivery) supplies HTTP push orchestration; the separate
[checkpoint seam](#incremental-checkpoint-transactions) and [bounded pull driver](#bounded-incremental-pull)
add no recovery/discard/reset API, publication invocation or app activation. A previously returned accepted snapshot
or successful check is insufficient write authority; the acknowledgment operation below
rechecks live admission and durable context atomically.

## Atomic canonical acknowledgment

R06c adds inert `PushRun.acknowledgeAccepted`, reusing the same handling mutex, conservative consumed-claim tokens and
Room v10 without schema/entity changes. The app constructs no push store or engine.

- Accepted snapshots privately bind the originating account and exact immutable frozen-row identity, including sequence,
  version, wrapper/request bytes and local effects. Attempt/retry bookkeeping and live run/session/epoch are excluded so
  fresh admission of unchanged durable work can reuse a prior snapshot. Before suspension, acknowledgment deep-owns and
  revalidates provenance, outcome identity/status, complete batch and target operation. An A snapshot cannot acknowledge
  B merely because IDs/bytes match; changed same-account bytes also fail closed.
- Inside one transaction, live capability/session/cache fences and every exact explicit-head field are checked again.
  All local affected roots need independent reservations at or above frozen generation. Existing physical roots must be
  owned/custom, pending, and carry valid generation/revision metadata covered by their watermark. Vanished physical roots
  may settle on reservation evidence alone; no root is fabricated. Invalid context retains the frozen head with fixed
  `ack_context_corrupt` recovery context and blocks successors, without canonical/domain/cache writes.
- Complete canonical application uses the existing R05b writer and revision/family arbitration in the same transaction
  as metadata acknowledgment and exact row removal. Extra cloud effects are stored, not local acknowledgment context.
  Obsolete accepted duplicates still remove the exact request without restoring older canonical winners; equal conflicts
  and malformed retained evidence roll back the entire acknowledgment.
- Only affected physical roots whose domain generation **and watermark** exactly equal the frozen effect become synced.
  Their server revision becomes the highest positive physical/retained canonical evidence, never an invented revision.
  Synced means that exact generation's command settled, not business-field equality with canonical or account convergence.
  Newer generations/watermarks preserve all metadata; business rows/children/tombstones, watermarks and other outbox rows
  remain unchanged. Physical account tombstones remain pending while newer intent exists, with nonnull deletion time.
- Token consumption precedes canonical/metadata/removal write attempts and survives rollback or uncertain commit; it is
  not acknowledgment evidence. Exact pre-removal identity, removal count/absence, affected metadata/watermarks, remaining
  account outbox rows and final admission/fences/cancellation are checked before commit. A canceled child cannot reuse
  the token while its parent run survives; recovery reclaims unchanged frozen identity. Owned SQLite errors remain safe.
- Successful acknowledgment marks projection dirty even for an all-no-op batch. Baseline/cache/materialized epochs,
  cursor/last-success, device identity, catalogue and other scopes stay untouched. No capture, publication, successor claim,
  HTTP, pull checkpoint or convergence signal is produced. Complete baseline and a real publication lease remain required
  before visible materialization; R09 still owns durable session authority and R12a the live edit/timer barrier.

## Bounded push delivery

`sync/engine/PushDeliveryLoop` is an inert, explicitly constructed driver over `PushStore` and `GymSyncApi`.
The configured facade composes this driver only on explicit activation/reconciliation. The caller must pair the API
with the same authorized service/account/session as the
store and share one required `PushRunOwner` per physical database; the driver creates no owner, credentials or scope.

- The standalone `deliver(expectedCacheEpoch, maxMutations)` holds one `withRun` lease. Its compatible
  `deliver(run, maxMutations)` overload reuses an already admitted run without another admission or owner; both share
  the same head loop. Each explicit head freezes durably, its defensive request is obtained,
  and `checkClaim` plus caller cancellation are checked immediately before a single-request HTTP push outside Room.
  Request JSON, mutation/effect/child identity and sequence are never regenerated or normalized.
- Only a `SyncApiException` from that HTTP call is classified as transport failure. Typed failure handling honors caller
  cancellation and persists existing bounded failure-time retry/error rules, then stops. Auth/reauth remain read-only
  resumable stops; any supplied refreshing API owns its existing one unchanged 401 retry. The driver adds no refresh,
  in-run retry, delay, successor bypass, pull, snapshot, discard, publication or credential/cache cleanup.
- Complete accepted/duplicate-accepted responses use existing classification and atomic acknowledgment. Only a returned
  `Acknowledged` increments the summary count and permits a successor. Rejection, malformed responses, retained errors
  and invalid acknowledgment context stop immediately. Fence, exact-row, canonical/storage and unexpected transport
  failures propagate; conservative consumed tokens and uncertain commits are never counted as success.
- Each call accepts a strict 1..100 acknowledgment budget (default 100). Exhaustion returns `LimitReached` without
  claiming/probing another head, even when the final acknowledgment emptied the queue. `Empty` requires an actual fenced
  empty selection and means neither convergence nor publication. Negative epoch/budget/clock inputs fail before their
  associated work; summaries preserve exact unknown codes through explicit getters but never print those codes or bodies.
- Cancellation/late results cannot settle through a dead run; new runs/processes reclaim unchanged frozen work. Earlier
  committed acknowledgments survive a later failure, and newer captured intent stays protected. No push cursor,
  last-success, baseline or account epoch is advanced. A pre-send check cannot recall bytes already admitted before a
  subsequent session change.

This is bounded push orchestration, not a complete running sync engine or durable session authority. R07a's
[checkpoint transactions](#incremental-checkpoint-transactions) and [bounded pull driver](#bounded-incremental-pull)
exist and the [shared driver](#shared-push-before-pull-and-valid-tail) composes them uninterrupted with valid-tail
bookkeeping. R08c supplies inert complete-snapshot baseline/recovery; the separate [app-facing authority boundary](#app-facing-authority-and-offline-recovery)
composes the Room/session journal and prepared bootstrap. Explicit ACTIVE reconciliation and scoped UI/timer integration
are described below; startup recovery itself runs no delivery; the separately owned foreground controller admits only healthy ACTIVE reconciliation.

## Incremental checkpoint transactions

R07a extends the existing admitted `PushRun` with inert `readPullCheckpoint`, `checkPullCheckpoint` and
`applyNextPullBatch` operations over Room v10. There is no separate pull owner or lock: callers share the required
`PushRunOwner` per physical database, and push settlement/checkpoint admission share the run's short handling Mutex.
Startup runs no checkpoint delivery automatically.

- Read coherently requires a complete selected-account baseline and nonnull persisted cursor. Missing/incomplete
  baseline or absent/null cursor returns null, never authorization for null-cursor retained-history bootstrap. A present
  empty cursor or negative last-success fails closed without repair even when baseline is unavailable. Tokens are opaque;
  whitespace is preserved, and equality is exact rather than lexical/numeric ordering.
- Immutable `PullCheckpoint` privately binds its originating live run, account/session/cache epoch and complete observed
  sync-state row. Read-only checks recheck exact row/baseline/fences inside Room. Foreign/closed/outside-run/enclosing
  use rejects; another read attempt, including failed/null read, supersedes the previous token.
- Batch input is deeply owned/validated before suspension. The complete canonical batch and cursor-only update from the
  exact prior boundary commit in one transaction with post-row/baseline/session/epoch/cancellation rechecks. Returned
  fresh tokens support a sequential chain. All-obsolete/equal canonical observations still advance the cursor while
  preserving canonical no-op/dirty semantics. Earlier committed batches survive a later batch failure.
- Before any write attempt, clearing the one current-token reference conservatively retires the input token; failed or
  uncertain commit cannot reuse it even in a surviving parent run. Recovery rereads durable state. Retirement is not
  persistence evidence, and cancellation after synchronous commit cannot prove rollback. No unbounded token ledger is kept.
- Cursor update-count, expected-row and baseline mismatches are typed stale checkpoints; session/epoch retain existing
  fence exceptions. Owned SQLite failures use fixed safe messages with no raw causes. Summaries omit tokens/identities.
- Only selected-account canonical observations, their existing dirty bookkeeping and its pull cursor can change. Domain
  rows/metadata, outbox/newer intent, watermarks, baseline/account/materialized epochs, other scopes, catalogue/device
  identity and last-success stay unchanged. Explicit unknown DELETE persists evidence without fabricating a domain row;
  unresolved references may arrive in later batches. Absence never becomes deletion or snapshot absence.

This is a transaction primitive, not a paging/HTTP policy or running engine. The [bounded pull driver](#bounded-incremental-pull)
validates complete ordered pages and run progress/cycles; the [shared driver](#shared-push-before-pull-and-valid-tail)
adds push-first policy and a separate tail transaction without nested admission. R08 establishes
complete baselines, R09 supplies durable authority, and R12a supplies live publication barriers. No HTTP,
expiry/bootstrap recovery, publication, last-success update or convergence claim is produced by this seam.

## Bounded incremental pull

`sync/engine/PullDeliveryLoop(api).pull(run, maxPages, pageLimit)` is an inert explicitly constructed HTTP pull driver.
It receives an **already admitted `PushRun`**, owns no store admission/mutex/owner, and composes within
[shared driver](#shared-push-before-pull-and-valid-tail) uninterrupted ownership. The caller must pair API/store with the
same authorized service/account/session and share one `PushRunOwner` per physical database. Startup runs no pull automatically.

- Strict page budget 1..100 and page limit 1..200 (both default 100) reject before checkpoint/HTTP work. Limits count
  complete pages/batches, never bytes or changes; no domain-size cap/truncation is introduced. Seen IDs/cursors are
  bounded by at most 20,000 batches per invocation, not durable lifetime history.
- A single initial checkpoint read needs complete baseline and a nonnull cursor. Missing eligibility returns an R08
  recovery requirement without HTTP/null-cursor bootstrap. Corrupt storage and stale admission propagate unchanged.
- Every fetch uses the exact current checkpoint outside Room, after a read-only fence/cancellation check. A complete
  typed page is deeply owned and validated synchronously immediately after API return, **before** the suspending
  post-response checkpoint check. That check fences malformed, empty-tail, failure/recovery and data results alike.
- Complete page shape/count/hasMore/last-boundary and every batch ID/cursor are validated before its first commit,
  including repetition versus earlier pages and the initial opaque boundary. Strings stay exact, never interpreted or
  ordered lexically. Returned order and each batch's own boundary enforce contiguous client checkpoint progression;
  opaque tokens cannot prove service-internal commit ordering. Empty terminal tail preserves its input cursor.
- Complete batches commit sequentially through `applyNextPullBatch` with fresh returned checkpoint chaining. Only a
  returned commit counts. A later transport/malformed-page/storage/canonical/cancellation failure leaves earlier commits
  durable; failed/uncertain commits stop and recovery rereads durable state, never reuses a retired token or page tail.
- Only typed exceptions from the actual API call become transport stops: explicit `CursorExpired` requests R08;
  invalid/foreign/wrong-context/auth/transient/unknown failures retain their exact category/instance without bootstrap,
  retry or cleanup. Unexpected errors, storage/canonical/fence errors and cancellation propagate. Existing supplied
  refresh decoration owns its one unchanged 401 retry; the driver adds none.
- A validated terminal page returns `Tail`, including at the budget; a nonterminal budget boundary returns `LimitReached`
  without another fetch/probe. These are bounded transport summaries, not last-success/convergence/publication claims.
  Domain data/newer intent/outbox/watermarks/baseline/epochs/last-success/other scopes remain protected by R07a.

The public standalone pull API still writes no last-success. Its internal `pullWithTail` composition seam shares the
same private paging core and invokes a callback exactly once at a validated terminal page, with its exact current
chained checkpoint before returning Tail. There is no post-Tail reread that could substitute another boundary;
callback failures propagate rather than becoming transport failures. The shared driver uses this seam below.

## Shared push-before-pull and valid tail

`sync/engine/SyncDeliveryLoop(store, api, nowMillis)` is inert and explicitly constructed; startup never invokes delivery automatically. One required application-owned `PushRunOwner` per physical DB serializes its entire `deliver` invocation,
including push, all pull HTTP/commits and terminal bookkeeping. API/store must bind the same authorized service/account/
session. It uses the admitted push overload and the internal pull terminal callback on one uninterrupted `withRun`;
there is no nested admission, release/reacquire gap, additional owner or background work.

- Strict epoch and push/page/count budgets validate before admission, clocks or effects. Only an actual fenced push
  `Empty` permits pull. Every retained/deferred/auth/rejection/corruption/oversize or budget stop remains push-only;
  `LimitReached` never probes a head even when the final acknowledgment emptied the queue. Exceptions propagate.
- Missing complete baseline/cursor or explicit expiry returns the existing R08 requirement without snapshot/null-cursor
  bootstrap. Invalid/foreign/wrong-context tokens remain failures. No retry, token interpretation, outbox rewriting,
  credential/cache cleanup, publication or authority transition is introduced.
- Only a validated terminal page invokes `PushRun.recordPullTail(checkpoint, nowMillis)` on its exact driver-supplied
  checkpoint. Generic checkpoints/constructible Tail summaries alone are not transport-tail evidence; the transaction
  primitive's caller must prove terminal validation. Standalone public pull does not invoke bookkeeping.
- Tail recording shares the run handling Mutex and one Room transaction: exact complete sync-state row (including null
  last-success), complete baseline, account/session/epoch/live admission and cancellation recheck before writes. Only
  `last_success_millis` changes through a null-safe exact-row update, with count-1 and full post-row/baseline/fence checks.
  The nonnegative supplied wall-clock time is preserved exactly, even equal/backward; it never orders conflicts/cursors.
- Selected-account outbox presence is sampled before writes and rechecked before commit using `EXISTS`, without parsing,
  filtering or changing any row. A changed observation fails closed. Foreign rows do not count. Legitimate newer capture
  during network remains protected; a valid bounded tail may coexist with outstanding intent.
- The input checkpoint retires immediately before every write attempt, including equal timestamps. Retirement survives
  rollback/uncertain commit and is not success evidence. Only returned commit and active resumption install a fresh token
  binding the new complete row. Failed/uncertain writes require durable reread; earlier acknowledgments/batches survive.
- Tail changes no cursor, canonical/domain data, watermarks, outbox, cache/baseline/materialized epochs, device/catalogue
  or other account. Owned SQLite errors stay fixed/safe; cancellation and external callback/clock failures propagate.
- `SyncDeliveryResult` carries push/pull summaries and a nullable `hasOutstandingLocalWork`: null means no returned
  successful tail commit, not false/clean. Nonnull is only the observation at that transaction. Neither false nor
  last-success establishes account convergence, visible publication or absence of later edits. Displays remain redacted.

No app-facing authority or production activation is implied by this driver alone. R08c adds separate inert
complete-baseline establishment and same-run publication coordination; callers use returned epochs. The [app-facing boundary](#app-facing-authority-and-offline-recovery)
composes delivered authority/bootstrap/recovery and explicit guarded ACTIVE reconciliation. Default startup does not
activate those APIs.

## Frozen-snapshot staging storage

R08a adds a data-preserving **Room10→11** migration and inert storage on the SAME admitted `PushRun`/handling Mutex.
No snapshot is started by startup recovery. One required application-owned `PushRunOwner` per physical DB remains mandatory;
there is no second owner, public unguarded writer or network transaction.

- `sync_snapshot_stages` has a unique account and stage UUID, origin generation/epoch, fixed1..500 page limit,
  exact original sync-state presence/cursor/last-success and baseline-complete observation, frozen baseline/continuation,
  phase and checked page/record counters. `sync_snapshot_records` keeps whole strict canonical record bodies with
  stage/type/root PK and unique page/position; `sync_snapshot_tokens` retains exact outbound continuations with
  stage/token PK and unique page number. Children cascade ONLY from their stage; no canonical/domain FKs.
  Migration leaves every prior table/byte/sequence unchanged and starts all3 tables empty. Schemas1–11 are retained.
- `beginSnapshotStage(stageId,pageLimit=200)` requires no selected stage and ANY selected-account outbox presence=false,
  without interpreting/parsing/filtering around retained errors or uncertain intent. It binds the complete original
  source observation, retires an old pull token before writes, inserts only the initial header, and rechecks durable
  initial-zero evidence, source/outbox/session/epoch/cancellation before returning an appendable capability.
  Missing cache/checkpoint stays absent; staging fabricates no canonical baseline or cursor.
- `appendSnapshotPage(stage,page)` deep-owns/validates the entire typed page BEFORE suspension, then commits complete
  records, continuation and header progress atomically under exact current-token/header/source/origin/live fences.
  Every root key is unique across pages, even for identical content; one exact frozen baseline and fixed limit persist.
  Nonterminal pages are nonempty, all outbound tokens are new by exact equality and terminal continuation is null.
  Reuse existing codecs; snapshot continuations require nonempty, not nonblank, preserving whitespace exactly.
  No token interpretation, record split/truncation, permanent history/content cap or canonical revision arbitration.
- Strict retained validation checks source/header/counter shape, record type/ID/revision/body agreement, consecutive
  page/position and token evidence, EVERY nonterminal page and optional empty terminal. It compares actual evidence
  counts before iterating; corrupt Long-sized counters never become fabricated iteration/allocation targets.
  Later-page dependencies, family namespace overlaps and intermediate multiple active roots can stage; complete-account
  graph/absence establishment and physical collision validation belong to the separate complete-baseline/publisher
  boundaries below, never staging alone.
- `readSnapshotStage()` supersedes earlier tokens and returns only a nonappendable recovery observation, including
  after reopen or uncertain writes. `checkSnapshotStage` validates exact current capability/durable evidence; appendable
  capabilities additionally recheck origin/source fences. `abandonSnapshotStage` explicitly deletes only that exact
  valid selected stage/children under CURRENT authorization, even if its old origin/source differs. Other stages/scopes
  remain untouched. Corrupt retained evidence fails closed/preserved; invalid/foreign HTTP tokens never imply cleanup.
- Personal recovery is **discard-and-restart**, not cross-run resume. Read/restart never adopts append authority.
  Write attempts retire capabilities before the first write, including rollback/uncertain commit; returned commit and
  active resumption alone install fresh tokens. Recovery rereads durable state and explicitly abandons before a NEW
  stage UUID/null initial request. Cancellation/run close perform no automatic cleanup; earlier pages remain durable.
- Durable selected-account stages, including downloaded/corrupt headers, block `claimHead` with `SNAPSHOT_STAGE` before
  head selection/freezing and reject claim checks/outcomes/acks plus all incremental checkpoint/batch/tail operations.
  Foreign stages do not block. Existing budget exhaustion still never probes a head. Same-account local capture after
  begin remains allowed; stage operations never rewrite newer domain intent, outbox bytes/generations/watermarks, cache,
  canonical/checkpoint/last-success, device/catalogue or other-scope data.

A `downloaded` stage proves only stored terminal page/progress validity, NOT actual transport exhaustion, a complete
validated graph, complete bootstrap, canonical baseline/absence, last-success, convergence or visible publication.
The bounded driver below validates actual transport pages; its separate R08c completion boundary establishes the
canonical baseline/absence and coordinates protected publication recovery. R09 authority/journal, R12a real edit/timer
barriers and R10 convergence still precede activation.
Existing secure HTTP/refresh and protected publisher are reused, never constructed by this storage.

## Bounded frozen-snapshot download

`sync/engine/SnapshotDeliveryLoop(store, api, nowMillis)` is inert and explicitly constructed. Its `start(run, stageId,
maxPushMutations, maxPages, pageLimit)` runs on an already admitted `PushRun`: only an actual fenced push `Empty`
authorizes begin and actual `GymSyncApi.snapshot` calls. Every other push stop returns `SnapshotStartResult` with null
`download`; a retained stage returns push-only `Blocked(SNAPSHOT_STAGE)`, never implicit read/abandon around intent.
The driver creates no admission/owner/Mutex; caller must pair API/store/run to the same authorized service/account/session
and hold ONE application-owned `PushRunOwner` per physical DB uninterrupted across all network work.

- Canonical stage UUID, push budget1..100, page budget1..100 and fixed page limit1..500/default200 validate before clocks/
  Room/HTTP. Initial request token is null; later requests use the exact staged continuation/fixed limit. Before every
  fetch, cancellation and exact stage checks run. Whole typed pages are synchronously deep-owned through existing codecs
  before any suspending post-response fence; malformed, empty terminal and failure/expiry results cannot bypass fencing.
- Whole pages append through R08a's current capability and retained baseline/root/token/source/history validation.
  Cross-page model, storage and fence errors, unexpected exceptions and cancellation propagate unchanged. Earlier commits
  survive. No durable stage read is used to chain, no new invocation-limited uniqueness set replaces retained evidence,
  and no partial record/account publication occurs.
- `SnapshotDownloadResult` counts only THIS invocation's returned page/record commits and exposes the latest exact stage.
  A validated committed terminal page returns `Downloaded`, including at budget; a nonterminal boundary returns
  `LimitReached` without another fetch/probe. `continueDownload(run, previous, maxPages)` accepts ONLY this driver's
  LimitReached result with the existing same-run capability; no begin/push/reread or owner gap. Repeated bounded calls
  in the uninterrupted run have no total account-size/page/history ceiling. Results have no public constructor/copy;
  their fixed displays redact identity, tokens, bodies and failures.
- Only typed exceptions from actual snapshot API calls become transport stops, after exact stage/cancellation checks.
  Authentic `SnapshotExpired` returns `RestartRequired` with pages retained for explicit valid abandon/NEW UUID/null-token
  restart. Every other category/instance, including invalid or wrong-context tokens, remains `Failed` without retry,
  cleanup or recovery inference. Existing refresh decoration owns its unchanged auth retry; this driver adds none.
- Failed/uncertain writes require durable reread; retired capabilities are not commit evidence. Read/restart stays
  nonappendable. Newer capture after begin remains allowed and unchanged; source checkpoint/last-success/baseline,
  canonical/domain/outbox/watermarks/device/catalogue/other scopes remain untouched except legitimate preceding push
  effects and allowed capture. A new intent racing Empty→begin fails the existing empty-outbox begin fence.

Downloaded now summarizes actual validated terminal transport/staging progress for that uninterrupted chain, NOT full
account graph validation, canonical baseline/absence, bootstrap, last-success, publication or convergence. The existing
incremental shared driver's recovery signal is not automatically routed here. R08c supplies the separate completion
boundary below; delivered R09/R10/R12 and complete activation remain prerequisites. Startup never downloads automatically.

## Complete snapshot baseline and publication recovery

R08c adds inert `SnapshotDeliveryLoop.complete(run, previous)` over Room11 with no entity/schema change. It accepts only
an actually issued terminal Downloaded result from THIS driver on the SAME uninterrupted admitted run. Private
referential issuance evidence, the current exact stage capability and durable fences all apply: generic Downloaded
shapes, storage-only terminals, superseded/read/restarted observations, foreign/closed runs and repeated attempts never
establish a baseline. The first eligible attempt consumes issuance before Room admission; graph/revision/overflow or
later write failure cannot reuse it. Staging/checkpoint tokens retire before writes, never as success evidence.

- One fenced Room transaction validates the entire frozen graph, including valid empty accounts, old unchanged roots,
  later-page custom dependencies, catalogue/relationship lookups, at most one active workout and known family roles/
  root/entry/set parents. It rechecks strict retained stage/page/token/body evidence, original session/epoch and full
  source checkpoint/baseline observation, including on nonappendable terminal tokens.
- Every staged upsert installs whole at checked `oldEpoch+1`, preserving existing local-generation watermarks. Lower
  observed revisions and equal authoritative disagreement fail closed rather than silently omit a frozen member;
  equal authoritative upserts may refresh frozen convenience labels. Equal/lower delete/absence restoration rejects.
  Previously observed missing roots become `snapshot_absent`, retaining known revisions without invented deletion
  revisions; missing unobserved local reservations stay unobserved. Canonical evidence/watermarks are never hard-deleted.
- The same transaction establishes complete/dirty cache metadata at the new epoch, preserves materialized epoch, writes
  the exact baseline cursor, preserves source last-success (null for a missing source), and removes only the completed
  stage/children. All effects roll back together on uncommitted failure/cancellation. Completion performs no HTTP,
  clock, automatic publication, outbox parsing/removal or visible-domain changes. Newer capture after begin remains
  valid/protected, with exact IDs/bytes/order/generations, other scopes and catalogue unchanged.
- Returned `SnapshotBaselineResult` reports the baseline epoch/cursor, durable page/record totals and selected outbox
  presence; its display is fixed/redacted and it is not fresh authorization, last-success or convergence. Only successful
  committed resumption installs the returned epoch into the live run. Physical UUID collisions are still checked at
  publication, so a valid canonical baseline can remain durable while its projection is protected or fails safely.
- `PushRun.publishProjection(pairedProjectionStore)` serializes the existing publisher on the SAME handling Mutex under
  uninterrupted physical-DB owner admission. It verifies the same database instance/account/session before the required
  barrier, retires old process-local checkpoint/stage tokens conservatively, and invokes the barrier/publisher outside
  an enclosing Room transaction. No new owner, default lease or live edit/timer implementation is supplied.
- Any selected outbox work protects the entire old/local graph. Clean complete publication reuses existing whole-graph/
  physical collision/foreign-reference checks and atomic materialization. Actual publication advances epoch again;
  only returned active resumption adopts its `cacheEpoch`. Old callbacks fail; fresh same-run checkpoint reads use the
  returned epoch. Failed/protected/no-op publication never masquerades as baseline download or convergence.
- After crash/uncertain writes, reread durable stage/cache/canonical/checkpoint under current authority; neither a thrown
  result, stage disappearance nor token retirement proves that call succeeded. Retained stages remain observation-only
  for explicit valid abandon/NEW UUID/null-token restart. A durable complete dirty baseline with no selected stage can
  publish from a fresh paired run at its observed epoch without another snapshot. Clean recovery returns UNCHANGED;
  lease-close failure after commit is not rollback evidence. Snapshot completion/publication preserve last-success;
  only the existing actual valid pull-tail seam establishes it later.

Startup recovery does not invoke this publication path. Delivered Room/vault authority/journal, real finite edit/timer
barriers, engine convergence and complete private account/import activation remain prerequisites; sign-out is separate. Exact uncertain
legacy accepted-auth replay limitations are unchanged.

## Models, codecs, and durable identity

`sync/model`, `sync/codec`, and `sync/mapping` implement transport-independent workout/routine/custom aggregates, canonical
records/batches/outcomes/pages, all 17 mutation kinds, import snapshots, stable child plans, and version-1 durable
wrappers. Room entities are not HTTP DTOs; mappings validate but do not generate IDs or write the database. java.time is
desugared for API 24. Complete ordered children, nullable values, canonical lowercase UUIDs, millisecond timestamps, and
configured versus actual rest remain distinct. Bundled exercises are not uploadable.

- Raw-string entry points validate original JSON member uniqueness before normalization. Decoded-name uniqueness is
  per object, including nested arrays and ignored additive fields; escaped-equivalent names reject without trimming,
  case folding or Unicode normalization. Built `JSONObject` overloads cannot recover names already lost upstream.
- Workout/routine root, entry and set IDs are unique across their whole family aggregate. Copy source/target sides are
  independently valid; same-family perform-again rejects all cross-side overlap, while independent workout/routine
  namespaces may overlap. These are supplied-ID checks, not an account-lifetime reservation store.
- Durable retry wrappers preserve original mutation JSON byte-for-byte, including whitespace/order/escapes/numeric
  spelling. Unsupported versions/corruption are retained failures, not reinterpretation, repair, or deletion authority.
- Shared parser normalization is not a universal lexical-type guarantee: represented domain fields use typed readers.
  Auth/error boundaries and the retained canonical reader additionally reject unquoted scalars, literal string controls,
  and numeric overflow becoming a string. Do not assume every old generic raw route has those additional guards.
- `ServiceAuthCodec` represents four challenge purposes, closed auth requests, additive responses, complete grants and
  logout bodies. Expiry constructor/copy paths validate inclusive wire-millisecond bounds, not freshness/lifetime/order.
  Arbitrarily long lossless fractional zero tails are allowed; nonzero submillisecond precision and malformed fractions
  reject. Expired original grants remain representable, not automatically usable.
- Immutable `GoogleExchangeAttempt` and `ServiceRefreshAttempt` capture once or restore exact valid original body text.
  UUID idempotency keys are header data. Typed auth methods receive the attempt; legacy conveniences delegate. Exchange
  attempts/Google proofs remain foreground-memory-only; uncertain refresh persistence belongs in the encrypted vault.

## Foreground Android Google identity

`data/sync/identity/AndroidGoogleIdentityProvider` implements the existing SDK-independent `GoogleIdentityProvider`.
It is explicitly constructed on Android Main for one `ComponentActivity`, with an injected web/server audience and
optional `GoogleCredentialClient`; configured startup constructs it without launching UI. The production client delegates to Credential
Manager using application context for the manager and the captured current Activity for UI. Dependencies are matched
Credentials/Play Services bridge 1.6.0 and Google ID 1.2.1; no actual OAuth/service/signing configuration is packaged.

- Each explicit call builds one Google button option (`GetSignInWithGoogleOption`), passing exact configured audience
  and service nonce unchanged. Missing/blank/padded audience and blank nonce refuse before SDK launch. Admission needs
  a RESUMED, nonfinishing/nondestroyed Activity; provider UI may temporarily pause/stop it without invalidating the result.
- Only the Google custom credential type is accepted, parsed through the real Google ID parser. A nonblank opaque token
  is returned unchanged, never decoded as account authority. No challenge, HTTP exchange, vault/Room write or activation
  follows Google success in this adapter; foreground exchange consumes it and the account controller separately completes activation.
- One nonqueueing slot covers get and explicit clear. Operations have a two-minute default timeout, caller cancellation
  precedence and fixed safe SDK-independent failure reasons. No provider details/causes, tokens, nonces, audiences or
  identity data enter diagnostics. No automatic fallback/retry or credential-state cleanup is performed.
- `close()` permanently fences the adapter, cancels its owned SDK work without cancelling the caller scope, releases its
  Activity and removes the observer on Android Main. Activity destruction/recreation retires it automatically. Caller
  dispatch and final acceptance recheck retirement/liveness, so cancellation-resistant late work cannot publish a token.
- `clearCredentialState()` is only an explicit SDK primitive; it needs a live adapter but no configured audience or
  RESUMED Activity. It does not clear service credentials, sign out, erase account data or run on cancellation/close.

Configuration, real Google verification and installed signing/audience interoperability remain separate R17a gates.
Default local startup/account visibility policy is unchanged; no interactive auth is wired to a background worker.

## Bounded foreground exchange and guarded grant handoff

`data/sync/session/ForegroundSignInCoordinator` is explicitly constructed with one configured origin, Google provider,
auth API and guarded private handoff. The configured account factory constructs it; only an explicit foreground action invokes it. `signIn(request)` obtains one SIGN_IN challenge,
passes its exact nonce once, captures one immutable `GoogleExchangeAttempt` and consumes the original service grant once
through `AppSessionCoordinator.acceptForegroundGrant`; it returns private preparation, never a reusable grant or ACTIVE
authority. Initial sign-in requires an empty signed-out expectation; reauthentication requires the retained account.

- Default whole-invocation budget is 180000 ms. Only transient exchange failures retry, with at most three sends of the
  same object/key/body/token/challenge. Minimum delays are 1000/2000 ms, extended by service hints without sending early.
  Challenge/provider/permanent/malformed failures do not reacquire or rekey. No refresh, logout, clear or lifecycle proof.
- Expiry equality refuses at actual Google and first-send admission, including queued work. Sent uncertain replay may
  cross challenge expiry within the budget. Original returned grant/expiry values remain unchanged, including expired
  access; the existing later refresh boundary owns renewal. All exchange evidence is foreground-memory-only.
- One nonqueueing slot spans final acceptance. Owned lazy seam work checks caller/slot before starting effects, is
  cancelled on every exit and cannot clear a newer slot. Close permanently retires/cancels only owned work, not borrowed
  provider/facade or caller scope. Late/queued results and cancellation-resistant seams cannot advance or report success.
  Original outer caller cancellation is rechecked after the timeout scope; a seam's timeout is not the invocation budget.
- `captureForegroundSignInRequest(intent, expected, operationId)` performs no I/O, uses the facade's actual configured
  origin and binds immutable expected authority/operation/intent to its exact process fence and owner. Real handoff
  refuses naked/foreign-owner/origin/account requests and carries the captured fence into the existing acceptGrant
  critical section without recapturing currentness. Process invalidation thus refuses even at unchanged Room authority.
- Existing exact Room/vault expectations, serialized ownership, real transition barriers, private preparation and P17
  uncertain-evidence retention remain definitive. Already-admitted commits may survive cancellation/lost return;
  explicit fresh recovery, not compensation or automatic activation, settles observation. Displays/errors are closed.

[R11b acceptance](../archive/remaining-work/R11b-review.md) closes deterministic personal R11a–b/package gates, not real
Google/signing/HTTPS/phone verification. The mandatory account controller composes private P18 import and complete
activation; the separate foreground/edit/manual controller consumes only the resulting exact ACTIVE handle. Null-policy local-root refusal and deferred public lifecycle proofs remain intact.

## API and secure HTTP

`sync/api` exposes SDK-independent `GymSyncApi` (push/pull/snapshot), service-auth, identity, and bearer boundaries.
Push validates frozen envelopes, including empty batches, maximum 100 requests, duplicate IDs, and unchanged text.
Failure categories distinguish service access authentication, foreground reauthentication, invalid versus expired read
tokens, request-too-large, unknown permanent rejection, malformed response, and transient faults. Safe messages are fixed,
raw causes are closed, credentials have redacted display, and retry hints cap at five minutes. Errors never prove deletion.

`sync/http` provides:

- Immutable canonical HTTPS `ServiceOrigin`; missing configuration is unavailable, never an invented endpoint.
  Fixed-origin requests defensively own method/path/query/header/body/timeout inputs.
- `OkHttpServiceTransport` over OkHttp 5.5.0: normal platform TLS/hostname validation, exact UTF-8, no redirects,
  cookies, cache, logging, authenticators, or automatic retries. A per-Call guard also blocks GET 503 Retry-After:0 replay;
  POST bodies are one-shot. INTERNET, explicit cleartext refusal, and standard OkHttp Startup wiring are present.
- Scoped whole-body JSON or incremental response reads, strict complete UTF-8/media/framing/identity encoding, no silent
  truncation or permanent aggregate-size cap, and exclusive consumption/lifetime ownership. Cancellation cancels the
  actual Call; late responses close, cleanup is once-only/off-Main, and original consumer cancellation/errors survive
  coroutine stack recovery. Header-level protocol violations map to malformed response. Retry-After parsing does not
  schedule retries. Chunk support is not an implemented export feature.
- `HttpGymSyncApi`: immutable origin/account/positive-generation binding, one tagged credential selection per explicit
  call, exact frozen push bytes and ordered complete outcomes, validated bounded/progressing pull/snapshot pages.
  No refresh, acknowledgment, cursor publication, or Room writes occur in this adapter.
- `ServiceErrorCodec` and operation/status classifiers: original scalar fidelity, exact unknown nonempty codes without
  whitelist/cap, validated/discarded optional message, authoritative status/context precedence, bounded hints and safe
  failures. Wrong-context token codes never become expiry.
- `HttpServiceAuthApi`: four-purpose challenge, exact-body exchange/refresh with retained header-only keys, and verified
  empty 204 logout. No bearer, query credential, automatic resend, expiry extension, or new attempt. Auth failures cannot
  become service-access recovery or account deletion. Actual caller cancellation takes precedence over racing I/O errors.

Production factories permit neither cleartext origins nor insecure TLS. Test-only trusted TLS/JDBC/JDK/relay helpers are
not APK components. No URL, OAuth configuration, account construction, or adapter activation has been added to the app.

## Encrypted vault and refresh

`sync/session` implements internal storage/refresh boundaries, composed only by explicitly admitted account APIs:

### Vault

- Immutable validated records bind origin/account/positive generation, activation operation/phase, original full grant,
  and exact pending-refresh operation/body/key. Pending token matches the stored refresh token. Prepared/active are
  stored labels, not account-visibility permission.
- `SessionVault` load/CAS operates on a checked monotonically increasing revision. Whole grant and pending attempt
  replace together. Clearing an archive-empty record writes a positive-revision encrypted tombstone, not unlink/reset-to-zero;
  stale CAS cannot reinstall over that tombstone. Retained superseded evidence cannot be silently cleared; see
  [same-account recovery](#inert-retained-account-recovery-and-superseded-evidence). Corrupt/unsupported/missing-key state
  fails closed and remains preserved.
- Archive-free snapshots retain exact version-1 bytes; strict version-2 adds flat encrypted superseded evidence.
  Binary UTF-8 preserves sensitive text/expiry values, rejects malformed/trailing data and avoids arbitrary content caps. `EncryptedSessionVault` rereads committed storage under a mutex; there is no success cache or
  implicit repair. Owned byte buffers are cleaned best effort on IO, without a secure GC/flash-erasure claim.
- The inert Android factory defers I/O/key creation and shares a process mutex. Android Keystore uses nonexportable
  AES-256/GCM, provider-generated 12-byte IV and 128-bit tag. Missing/wrong-kind keys are not regenerated during reads.
- Ciphertext only occupies credential-protected `noBackupFilesDir`. Original-path type/symlink checks, checked 0600 temp
  writes, fsync, same-directory rename, and namespace/parent fsync prevent silent partial success. Load never promotes
  stale temp files. Legacy and cloud/device-transfer backup XML also exclude the namespace; ordinary backup stays enabled.
- Cancellation or failure after a synchronous commit is not rollback proof. Reload/fence before further work. No
  multiprocess, hardware-backed, or actual OEM/cloud transfer guarantee is claimed.

### Refresh coordinator and decorator

- One application-owned `SessionRefreshCoordinator`/mutex and one explicit shared `SessionRefreshLease` per authorized
  activation are required. Lease binding includes origin/account/generation/operation/phase. Atomic admission,
  process-local reauth latching and immediate permanent invalidation do not establish durable Room authorization.
- Settle pending refresh FIRST, with its exact retained operation/key/body/token, even if old access looks live or its
  consumed predecessor's refresh expired. Otherwise capture a fresh pair once; whole-grant+pending CAS and reload/fence
  must complete before HTTP. Returned same-account grant+cleared-pending CAS and reload/fence complete before use.
- Persist an original expired-access replay grant before optionally rotating its current refresh once. Local expiry is
  a scheduling hint, not permission to extend deadlines. Selection is bounded: pending replay plus at most one fresh
  rotation, not an automatic backoff/network loop or another uncertain key.
- `withAccessToken` executes pure transport callbacks outside the run mutex, fences admission and late results/errors,
  and permits one unchanged access-401 retry. Concurrent rejected old credentials reuse a newer committed grant.
  Repeated 401/explicit reauth latches foreground recovery without deleting credentials/cache.
- `SessionRefreshingGymSyncApi` validates and privately deep-owns push wrappers before suspension, preserving original
  mutation text/IDs/order. Programming preflight errors precede credential I/O. Each attempt uses a fixed tagged bearer;
  read tokens/queries and frozen mutation bytes remain unchanged across the one retry.
- Caller cancellation precedes reauth latching. Safe failures retain uncertain identity. Invalidation cannot recall
  already-admitted bytes or undo an in-progress synchronous vault commit; dead leases cannot admit credentials/results.
  No independently constructed concurrent coordinators or duplicate activation leases are supported.

The refresh lease also accepts an optional suspending authorization predicate. Admission and post-seam checks invoke it
independently of the reauth latch, preserving cancellation precedence and closed failures. The empty default is only a
primitive compatibility path; recovered sessions always supply their bound authority check.

### Startup recovery seams

`SessionRecoveryCoordinator` is an inert, reads-only reconciler. Its required `SessionRecoveryAuthority` supplies immutable
nonsecret observations of revision, session generation, private cached identity, activation operation, phase and complete
baseline. The configured account facade uses the [inert real Room adapter](#durable-private-room-account-authority)
for mandatory startup recovery, without HTTP or interactive sign-in.

- Starts privacy-locked. Unconfigured use is local-only without I/O; signed-out observations never inspect/adopt orphan
  vault credentials. Preparing/transitional state, incomplete baseline, missing/unreadable vault, changed origin or
  mismatched account/generation/operation stay private. Matching prepared credentials require R09 promotion/recovery.
- A complete active authority observation and matching active vault must survive authority/vault rereads unchanged before
  one recovered handle is published. Reload comparison preserves semantic equality of separately restored pending
  attempts while requiring their exact operation/key/body/token. Reconcile never writes, repairs, refreshes or reads clocks.
- Fresh opaque operation tokens make newest reconciliation win. Synchronous invalidation kills old handles/leases without
  waiting for reads or network. Observed cancellation leaves only the current operation privacy-locked; older completion,
  fault or cancellation cannot reset a newer state. Injected cancellation remains identical inside an active caller.
- The single shared refresh coordinator rechecks captured authority around credential seams and network admission/results.
  Transport callbacks are checked before invocation and after success/error. Authority changes/faults permanently kill
  the handle and lock visibility; authority-fault reason remains available even when lease invalidation surfaces stale.
- Active is offline scope eligibility, not credential freshness or convergence. Expired credentials, transient/auth errors
  and strict malformed legacy replay do not erase valid private cache or uncertain identity. Pending-first replay and at
  most one access-401 retry still follow the accepted refresh rules.

These observations and handles are not Room transaction authorization or durable sign-out. An already-started synchronous
vault CAS may commit after invalidation; dead handles cannot use its result. R09a supplies private durable Room
observations/reservation/fencing; R09b adds [inert prepared coordination/recovery](#prepared-vaultroom-coordination-and-recovery).
[Inert completed preparation/promotion](#private-completed-preparation-and-vault-promotion) is delivered separately;
app activation remains later integration. The separate [private prepared engine](#private-prepared-session-engine-and-bootstrap)
supplies inert engine admission, not visibility.
Result application still rechecks inside Room transactions. R12 barriers, complete bootstrap and engine convergence precede app activation. No engine,
sign-in/out orchestration, account UI, Google adapter, or scheduler is constructed by these classes.

## Durable private Room account authority

`RoomSessionAuthority(database) : SessionRecoveryAuthority` provides the internal adapter over Room12's independent
`sync_session_authority` table. Its additive11→12 migration starts the table empty; all prior tables/rows/schema/sequence,
exported1–11 and bundled v1 asset bytes remain unchanged. No legacy device/cache label invents origin, operation or ACTIVE.

- One real Room transaction strictly observes all authority/device singleton rows and selected cache metadata. Extra
  rows, SQLite type coercion, invalid counters/UUIDs/canonical HTTPS origin, unknown phases or inconsistent tuples fail
  closed and remain preserved. Existing active device without journal is not adoptable authority.
- Read returns immutable revision/generation/private-cache/phase/identity/baseline observations. SIGNED_OUT has no
  identity/baseline; PREPARING/TRANSITIONING stay private even with a complete cache. Only a valid ACTIVE tuple can
  report selected canonical baseline completeness; the existing reconciler still requires matching ACTIVE vault evidence.
- `beginPreparation(expected,origin,accountId,operationId)` admits only an exact SIGNED_OUT observation. It refuses
  different/unknown retained account-origin provenance and any local workout/routine/custom root, including deleted
  drafts. Bundled-only data is allowed. No legacy import/delete/reassignment is inferred.
- Preparation atomically increments checked revision/generation, binds the target privately and clears device active
  account; existing repositories at an older generation are fenced. Reservation alone authorizes no engine/repository;
  the separate private engine requires fresh vault/Room admission and exact registered permits. A concurrent reservation
  with the same observation cannot also win.
- `fencePrivate(expected)` increments the same fences, clears activation operation and retains cached account and known
  service origin. It is not sign-out/vault cleanup, pending-stage abandonment or deletion. Exact domain/child/relationship,
  outbox/uncertain bytes, cursor, stage, cache, canonical watermarks, foreign scopes and catalogue remain untouched.
- Inserts/update counts and complete expected post-write journal/device tuples are verified before commit, including
  hidden signed-out provenance/cache_state. Valid-but-wrong trigger rewrites fail closed and roll back. Caller cancellation
  takes precedence; uncertain returns require durable reread, not an in-memory success cache. Errors/displays are closed.
- These application-facing private reservation/fencing methods do not write ACTIVE, access credentials, generate IDs,
  call HTTP or construct app/session machinery. R09d's separate internal registered completion writer below adds exact
  ACTIVE/ready transition inside its caller's protected publication transaction; raw storage is not app authorization.
  [Prepared grant coordination](#prepared-vaultroom-coordination-and-recovery),
  [private engine admission](#private-prepared-session-engine-and-bootstrap) and
  [inert completion/promotion](#private-completed-preparation-and-vault-promotion) are delivered separately;
  legacy choice, real edit/timer barriers, convergence and activation remain later gated work. Full suite boundaries and
  actual scoped gates belong to the [R09a review](../archive/remaining-work/R09a-review.md).

## Prepared vault/Room coordination and recovery

`SessionPreparationCoordinator(origin, authority, vault)` provides internal preparation and the narrow
`SessionPreparationAuthority` extension implemented by the unchanged R09a reservation method. No new schema or Room
writer exists. Configured account preparation composes it explicitly; results are nonsecret private observations, never leases or engine,
repository, refresh, publication or account-visibility admission.

- `prepare` requires exact SIGNED_OUT authority and empty revision0/positive tombstone vault. Any occupied grant,
  including same-account/orphan/expired/pending, refuses unchanged. Checked Room/vault counters precede reservation.
  Reservation binds the service-returned account to the configured origin and supplied operation, privately increments
  revision/generation, and retains R09a provenance/local-root refusal. Returned AND durable reread identity must match
  before one full original-grant PREPARED CAS at the exact empty revision.
- `resume` never reserves again. It requires the same exact PREPARING identity and supplied grant account/origin,
  rereads Room after loading an empty vault before CAS, and uses the existing operation/generation. An existing exact
  prepared grant permits only read-only idempotence; retained pending refresh is preserved, never cleared/rekeyed.
- Post-write results and read-only `recover` use alternating Room/vault/Room/vault/Room rereads. Full snapshot/grant
  equality and exact pending operation/key/body/token equality tolerate separately decoded attempt instances without
  changing original request whitespace or expiry. Missing credentials yield NeedsGrant; Room cannot reconstruct a grant.
  Mismatched/unreadable/change-racing state stays private/Blocked. Non-PREPARING Room and other-origin preparation do
  not read the vault. A matching ACTIVE vault during PREPARING is mismatch, not promotion or completed activation.
- One coordinator mutex serializes operations for a physical Room/vault pair. Newest opaque invocation fences and
  synchronous invalidation retire old work without waiting for I/O. Caller cancellation precedes stale/error mapping;
  injected cancellation remains identical in an active caller. No process success cache or token grants durable authority.
- An already-started synchronous reservation/CAS may commit despite a failed/canceled/invalidated return. Keep its
  durable operation and bytes; recover by reread, never compensating cleanup, automatic fencePrivate, generation restart
  or grant replacement. Such an orphan cannot authorize mismatched Room. No fake cross-store transaction is claimed.
- No HTTP/clock/UUID generation or Google proof persistence; expired stored grants are evidence, not freshness or delivery
  permission. No ACTIVE/promotion, occupied-credential reauthentication, local import/deletion, UI or startup wiring.
  The separate private engine below supplies fresh admission/bootstrap; the [app-facing authority boundary](#app-facing-authority-and-offline-recovery) composes it without production startup activation.

[R09b review](../archive/remaining-work/R09b-review.md) owns fresh full JVM, focused actual Room/Keystore/file plus existing
session regression, build/lint/APK and preservation evidence. It is not full instrumentation, engine convergence or live
activation evidence.

## Private prepared-session engine and bootstrap

`PreparedSessionEngineCoordinator(database, origin, vault, authApi, transport, runOwner, clockMillis, uuidProvider)` is
internal and inert. The caller supplies ONE shared physical-DB `PushRunOwner`; construction performs no I/O. Room12,
ordinary repositories, credential storage and the app construction path remain unchanged.

- Input validation precedes newest opaque invocation fencing. R09b recovery plus fresh alternating Room/vault rereads
  require exact PREPARING identity/revision, matching PREPARED grant and semantic pending operation/key/body/token equality.
  Missing credentials, mismatches, corruption and stale results stay private without cleanup or generation restart.
- Only the concrete coordinator can privately register a referential permit under its opaque invocation fence. Arbitrary
  permit candidates cannot admit. Open/invalidate/final settlement retire the matching permit and lease without affecting
  newer work. An already-started write may commit despite a failed/canceled result; fresh durable rereads remain required.
- Prepared admission observes epoch INSIDE the shared owner slot, then holds that owner uninterrupted through all
  snapshot/push/completion/pull/tail work. Explicit permits thread only through composed PushRun transactions and their
  canonical-batch helper. Strict Room authority/device checks run before effects and after writes: origin/account/
  generation/operation/revision exact, device active=null/cached=target/cacheState=preparing, epoch and current invocation.
  Vault/HTTP/clock work never runs inside Room; credential/transport authorization independently rechecks the same permit.
- The existing shared refresh/decorator preserves pending-first exact retries and permits legitimate same-session grant
  rotation without pinning the old vault revision after admission. Ordinary PushStore, CanonicalStore, ProjectionStore
  and capture/repository paths remain ACTIVE-only. Private PushRun publication rejects before barrier/visible writes.
- Selected retained stages are validated observation-only and block HTTP. Explicit abandonment requires a NEW UUID and
  forces a fresh null-token snapshot even with an older complete baseline; corrupt stages remain preserved. Failure,
  expiry, cancellation and budget exhaustion never automatically abandon, resume retained terminals or discard intent.
- R08 start/continuation/completion run on the SAME driver/run after actual push Empty. A positive invocation page budget
  is chunked into the existing <=100-page calls, with no permanent account-size cap. Only actual issued terminal
  completion establishes canonical baseline/absence/checkpoint and adopts its returned epoch; it does not publish.
- A durable complete baseline plus valid checkpoint and no stage skips redownload, including dirty post-crash recovery.
  The admitted R07 overload again checks actual push Empty and uses its actual validated terminal callback for
  last-success. Snapshot itself preserves last-success; outstanding=false/tail never establishes convergence or visibility.
- `PreparedBootstrapResult` is nonsecret private progress, not promotion authority. Typed driver stops remain stops;
  coordinator failures have closed categories and caller cancellation precedence. Bootstrap performs no ACTIVE writing
  or vault promotion. The separate completion path below reuses this coordinator; occupied-credential reauthentication,
  ordinary preparation repositories, UI/scheduler/startup construction and live actions remain absent.

[R09c review](../archive/remaining-work/R09c-review.md) owns scoped private-bootstrap evidence. R09d completion below is inert;
core R10 convergence and R12a live barriers still precede activation. Legacy local-data disposition is not inferred.

## Private completed-preparation and vault promotion

R09d extends the internal, explicitly constructed `PreparedSessionEngineCoordinator` with `completePreparation(barrier,
budgets...)` and `recoverCompletion()`. Construction stays inert; Room12 and application wiring are unchanged.
`PreparedCompletionResult` is a fixed/redacted nonsecret status/epoch/delivery summary, never a handle or reusable authority.

- Completion freshly admits the exact PREPARING/PREPARED pair and holds the shared physical-DB owner continuously through
  actual R07 push Empty/terminal-tail commit, required barrier, combined Room publication/completion, vault CAS and final
  rereads. The internally captured actual tail checkpoint—not a supplied bootstrap/progress summary or old last-success—
  binds the exact cursor/last-success/epoch. Retained stages and missing baseline/checkpoint do not redownload or abandon;
  rejection, budget/transport stop and outstanding/newer intent cannot complete preparation.
- The paired internal publisher reuses the existing whole-graph/collision/generation materialization; public publication
  and ordinary stores remain ACTIVE-only, and bootstrap remains canonical-only. One Room transaction publishes coherent
  complete projection/cache and writes exact journal/device ACTIVE/ready. Revision increments once; reserved generation,
  origin/account/operation remain. No vault/HTTP/clock work occurs inside Room. Full tuple/cache/checkpoint/stage/outbox/
  local-root/currentness/lease checks after writes protect rollback, including trigger rewrites/count-zero/cancellation.
- Retire prepared refresh before completion. Fresh post-tail PREPARED record/revision pins legitimate same-completion
  grant rotation, not the old admission revision. After known Room return AND lease close, promote only that exact full
  record via one PREPARED→ACTIVE phase-only CAS; preserve original grant and exact pending operation/key/body/token.
  Checked Room/vault exhaustion refuses before publication. No retry-over-conflict, cleanup, rekey or generation restart.
- Each full READY observation reads strict ACTIVE journal/ready device/cache and selected checkpoint/stage/outbox/local
  roots in ONE Room transaction. Alternating Room/vault/Room/vault/Room before a new CAS and after promotion compares exact
  revision/identity/clean materialized epoch/full checkpoint and semantic full vault evidence. Missing, mismatched,
  unreadable, changed or corrupt evidence never authorizes a new CAS or COMPLETED. Generic barrier I/O faults are closed;
  explicit invalid leases may return NOT_READY after rollback. Stored labels alone never authorize application visibility.
- Completion-gap recovery holds the same owner and makes no HTTP/refresh/clock/UUID/barrier/publication/domain/journal
  write. Exact RoomACTIVE/vaultPREPARED can phase-promote; matching ACTIVE is read-only idempotence without another revision.
  Expired grants and whitespace-exact pending refresh stay retained. Non-ACTIVE Room stays NOT_READY without vault adoption.
  This narrow recovery requires clean materialization/no selected work; it is not general account recovery after editing.
- An already-started Room/CAS write may commit despite canceled/invalidated/failed return. Dead invocation cannot report
  completion. Lease-close failure after Room commit leaves a retained unpromoted gap, not rollback; reread durable stores,
  never compensate or infer completion from a result/phase/stage disappearance. Keep operation and origin provenance.

[R09d review](../archive/remaining-work/R09d-review.md) owns scoped completion execution. Completion itself supplies no app
construction, recovered handle, repository/UI rebinding, occupied replacement, legacy import/deletion/reassignment,
convergence or live activation. The separate settled reauthentication path below remains inert. Core R10 and R12a precede activation.

## Inert settled occupied-credential reauthentication

`SessionReauthenticationCoordinator(origin, authority, vault, runOwner, credentialMutex)` is internal, explicitly admitted machinery.
Room12's `RoomSessionAuthority` additionally implements `SessionReauthenticationAuthority` over existing journal/device
storage; no schema, vault format or app construction changed. Only an exact established ACTIVE Room/ACTIVE vault pair
at the configured origin and service account, complete baseline and no pending predecessor can start. Dirty cache,
retained queue/frozen bytes, checkpoints, stages, children, foreign scopes and catalogue stay unchanged.

- One required shared physical-DB owner then the supplied shared refresh/credential mutex span all effects. No independent
  owner/default lock, HTTP, Google proof, clock or UUID generation exists here. Future live integration still requires real
  accepted-edit/timer drains and app/session coordination; primitive default/unbound leases are not application-safe.
- Full alternating Room/vault rereads precede one private Room reservation: TRANSITIONING at checked revision+1/generation+1,
  same origin/account, fresh supplied operation, device active=null/cached=target/cache_state=preparing. Room preflights space
  for both journal increments, strict tuples/local roots, exact old-row predicates/counts and full post-write observations.
- After known reservation and durable reread, reload the exact old occupied grant and recheck Room AFTER that vault load
  immediately before one exact-revision full supplied-grant PREPARED CAS. Eligible late predecessor pending stops
  PENDING_REFRESH before other source drift; no retry over conflict. Preserve original grant/expiry, even expired.
- Stable target evidence permits exact Room TRANSITIONING→PREPARING at one more checked revision, reserved generation and
  identity retained, device unchanged. Full post-write tuple/local-root/active-caller checks protect rollback, including
  count-zero and well-formed wrong operation rewrites. Final alternating pair rereads return advisory PreparedSessionObservation,
  never ACTIVE, visibility or reusable authorization. Existing registered preparation engine/completion must freshly admit it.
- Resume never reserves again: only a settled same-origin/account ACTIVE predecessor at exactly target generation-1 and
  different operation can be replaced, or an installed exact PREPARED target can be finalized without a vault write.
  Wrong supplied grant against an installed target refuses. Target pending operation/key/body/token is preserved and
  compared semantically across separately decoded records. PREPARING target resume is read-only idempotence.
- Reads-only recovery classifies settled predecessor as NeedsGrant, installed target as NeedsFinalization, or finalized
  matching pair as Prepared. Missing/empty/foreign/orphan/corrupt/changed evidence blocks; NeedsGrant never reconstructs
  credentials or authorizes another exchange/key. Non-transitional/non-preparing Room and foreign-origin identities do not
  probe/adopt vault credentials. Impossible transitional complete-baseline evidence fails closed.
- Valid invocations open newest opaque process fences only after caller activity/input validation. Synchronous invalidation
  never waits for I/O or compensates. Already-started writes may commit after cancellation/invalidation/lost return; dead
  work cannot report success. Preserve exact durable bytes/operation, reread and resume the gap without generation restart,
  automatic fencePrivate, clearing/tombstoning or cleanup. Older Room-checked leases/ordinary generation-bound stores are stale.

[R09e review](../archive/remaining-work/R09e-review.md) owns scoped settled occupied ACTIVE replacement/pending-refusal
evidence. Exact established pending replay and the separate broader retained-account recovery boundary are delivered
below. R09e itself remains settled/adjacent-only; no uncertain pair retirement is inferred. No app activation,
projection/promotion, ordinary repository rebinding, legacy disposition or convergence is delivered.

## Inert exact-pending-refresh reconciliation

`SessionReauthenticationCoordinator.reconcilePendingRefresh(expected, refreshCoordinator)` is an internal prerequisite. It stops at
nonsecret advisory `SettledSessionObservation(room, vaultRevision)` for the unchanged old ACTIVE authority and settled
ACTIVE vault; R09e replacement must separately freshly admit that pair. No Room writer, schema/format or app wiring changed.

- Validate active caller, ACTIVE complete-baseline input and configured origin before the newest invocation fence. The
  supplied ONE shared refresh coordinator must match origin and referential vault/credential mutex; incompatible ownership
  refuses without superseding valid work. Shared physical-DB owner THEN credential mutex span all preflight/HTTP/CAS/final
  and error rereads continuously; no nested mutex/default owner or application-safe unbound lease is inferred.
- Exact positive-revision ACTIVE vault binding/operation and full alternating Room/vault/Room/vault/Room preflight precede
  admission. Full grant/expiry and semantic pending operation/key/body/token must remain exact across decoded instances.
  Missing/foreign/orphan/PREPARED evidence and TRANSITIONING predecessor reconciliation refuse without adoption.
- No pending means read-only settled idempotence, even expired. Pending means ONE exact retained typed refresh transmission,
  with checked vault increment space before HTTP, independent of old live access/consumed refresh expiry. Reuse existing
  refresh send/full-grant CAS/reload machinery, not access selection: no clock/UUID, recapture, fresh rotation or retry.
- Required scoped ACTIVE lease authorization rechecks full old Room and invocation around vault/network seams and immediately
  before CAS. Commit complete same-account returned grant plus cleared pending together at the sent revision, preserving
  old binding/operation/ACTIVE phase and original deadlines, even expired. Verify exact revision+1 and full committed
  record, then alternating final pair rereads/post-callback authorization before advisory return. No conflict retry.
- Safe refresh failures/hints keep their accepted category (access AuthenticationRequired maps to ReauthenticationRequired);
  wrong-account/strict legacy malformed responses retain the original uncertain pair. Local failures are closed. Error
  rereads remain inside both owners; strict observed Room faults retain their closed category even after a good reread.
  Caller cancellation, newest fence/invalidation and current Room drift fence late errors/results in that order.
- Scoped lease retires on settlement; already-started synchronous CAS may commit despite lost/canceled/invalidated return.
  Restart sees exact retained pending or settled old ACTIVE evidence, never a reason to compensate/rekey/clear. Room,
  domain/children/relationships, queue/frozen bytes, canonical/cache/watermarks, checkpoints/stages and foreign scopes remain
  unchanged. Local roots are not adopted; subsequent R09e reservation still independently refuses them unchanged.

[R09f review](../archive/remaining-work/R09f-review.md) owns fresh scoped replay/composition evidence, not complete R09 or
live activation. R09g supplies the separate retained-account recovery boundary below; core R10 and R12a remain activation
gates. The [roadmap](../remaining-work-roadmap.md#remaining-r09-plan) owns the finite remaining plan.

## Inert retained-account recovery and superseded evidence

`SameAccountRecoveryCoordinator(origin, authority, vault, runOwner, credentialMutex)` supplies internal recovery and narrow
`SameAccountRecoveryAuthority` checks/reservation on Room12. It handles retained signed-out/missing-vault recovery,
explicit fresh-grant supersession of unsuccessful/legacy-malformed uncertainty and interrupted transitions. No schema,
asset, HTTP/Google, clock/UUID, app construction, ordinary repository/UI rebinding or activation changed.

- Exact retained service UUID and known configured-origin provenance are required; coherent device cache-state is
  SIGNED_OUT/private, ACTIVE/ready or private/preparing. Unknown/foreign/corrupt provenance and local-only roots refuse
  unchanged, including roots introduced after reservation. Complete or incomplete existing baseline stays private.
- Shared physical-DB owner THEN shared credential mutex span all effects. Newest opaque invocation/cancellation checks,
  full alternating Room/vault/Room/vault/Room rereads, terminal exact provenance/root checks and durable error rereads
  fence source/target success and failures. Synthesized drift errors have the same precedence as seam faults; no CAS retry.
- `prepare` reserves TRANSITIONING at checked revision+1/generation+1, preserves the account and every domain/cache/intent
  row, verifies exact old vault and Room-after-vault before one full supplied-grant PREPARED CAS, then reuses R09e's exact
  PREPARING finalization at revision+1 with the reserved generation/operation unchanged. No generation restart/compensation.
- `resume` needs a fresh supplied same-account grant for missing credentials or readable strictly older same-origin/UUID
  evidence with different operation. Older records are NOT credential/data authority. This includes signed-out7/vault6 →
  reserved8/vault6 pre-CAS restart gaps. It installs under the exact existing reservation; an installed exact PREPARED
  target instead requires full supplied-grant equality and only finalizes/idempotently rereads, preserving target pending.
  Same/newer incompatible bindings, foreign/unreadable evidence and old-token reuse refuse; Room reconstructs no grant.
- Read-only `recover` returns nonsecret replacement/NeedsGrant/NeedsFinalization/Prepared/Blocked observations, never
  credentials, reusable authorization or visibility. Healthy established offline startup still uses its separate admission.
- Owner-approved [P17](../../shared-docs/docs/backend-contract/open-decisions.md#confirmed-product-decisions) supersession
  atomically appends the exact uncertain source revision/full grant/deadlines/pending operation/key/body/token as flat
  `VaultSupersededSessions` while installing the new grant. Collections are defensively owned and unmodifiable; entries
  have increasing revisions/generations, consistent origin/UUID, no nesting and no archived-token reactivation.
- Archive-free records preserve EXACT v1 encoding. Archive-bearing v2 extends the grammar with a checked positive count
  and full source records, validates remaining bytes before allocation and rejects malformed/inconsistent/trailing data.
  Ordinary refresh, completion/promotion, startup and R09e use full semantic archive/pending equality and carry archives.
  CAS refuses archive removal/modification/reordering, archive-bearing null clearing and uncertain identity replacement
  without its exact append. Successful ordinary same-binding rotation still settles grant/pending atomically.
- Already-started writes may commit after an uncertain return; recover durable state, never infer rollback or cleanup.
  Archives are permanently excluded from selection/replay/rebinding and have no automatic pruning. Later separately
  authorized sign-out/deletion must account for ALL credential material. No rounding/rekey/server-revocation claim.

[R09g review](../archive/remaining-work/R09g-review.md) owns fresh JVM/full instrumentation/lint/APK evidence. Stopped at
inert safely recoverable same-account preparation eligibility. R12a real edit/timer drains and core R10 two-store actual
engine/HTTP convergence are the named prerequisites for the [app-facing boundary](#app-facing-authority-and-offline-recovery).
These recovery primitives do not grant UI visibility alone. Scoped runtime and mandatory account presentation are
delivered separately; automatic synchronization and live milestone gates remain open.

## App-facing authority and offline recovery

`AppSessionCoordinator` explicitly composes the delivered private authority/preparation, retained-account recovery,
bootstrap/completion and startup/refresh components. Construction performs no I/O and exposes one stable read-only
`StateFlow`, initially privacy-locked. Configured mandatory startup constructs it with the exact-handle account runtime,
real scoped UI/timer integration and the approved P18 policy. Foreground exchange/grant handoff and complete private
activation are invoked by the startup controller; the separate sync controller owns foreground/edit/manual ACTIVE demand.
Transition and publication barriers are mandatory caller inputs with no permissive defaults.

- One operation mutex serializes facade work, one supplied physical-DB run owner serializes engine work, and one shared
  credential mutex spans preparation, retained recovery and mutually exclusive prepared/recovered refresh ownership.
  Synchronous invalidation kills the exact facade/subordinate handles and publishes privacy-locked under one short
  state monitor, without waiting for operation/credential/run mutexes or network. Older faults cannot retire a newer handle.
- Only fresh matching ACTIVE Room/vault evidence and a complete baseline admit an `AppAccountSession`. Offline recovery
  does not inspect credential deadlines, call HTTP or require login every launch. Dirty cache and queued edits retain
  eligibility. An existing active handle is revalidated without silent repository rebinding; exact clean completion-gap
  recovery may promote PREPARED credentials before fresh startup reconciliation. Other interrupted states remain private.
- Grants require an exact caller-captured Room expectation and configured-origin/service-UUID compatibility. A new ACTIVE
  reservation needs a fresh operation; private resume uses the exact reserved operation/generation. Drain/acquire occurs
  while the previous handle is valid; successful admission then hides it before reservation/CAS. Failed acquisition keeps
  healthy editing eligibility. Already-started writes may commit despite cancellation/lost return: reread durable stores,
  never compensate, erase evidence or restart generations. P17 evidence retention remains in the existing recovery seam.
- Private bootstrap stops, retained stages and budgets do not launch another completion attempt. Actual bounded valid tail
  precedes required protected completion, and only COMPLETED plus fresh startup reconciliation yields an app handle.
  Default/null-policy local-only roots still refuse unchanged. The separately opt-in accepted P18 integration above
  composes exact private import; no deletion, reassignment or default-startup activation is inferred.
- Facade repositories have an explicit process guard inside scoped transactions before effects/commit and after result
  dispatch. Process-only invalidation prevents stale reads/edits even at unchanged durable generation. Guarded constructor
  overloads preserve original legacy/default/trailing-lambda UUID/clock semantics; no supplier is coerced to a Unit guard.
  Reconnect binds the same process guard to PushStore/PushRun and requires a guarded recovered-session HTTP decorator.
- Reconnect reuses bounded push-before-pull and exact pending-refresh/request handling; it never publishes ACTIVE projection
  or automatically restarts an expired cursor/stage. Transient/reauth stops retain account editing, credentials and frozen
  intent. Observed credential-binding/storage or authority faults lock the exact handle; cancellation is not revocation.
  Typed stops remain available; closed delivery status does not claim convergence. Incremental canonical writes mark dirty
  without advancing cache/materialized epochs. The separate explicit reconcile API below adds paired publication through
  real timer-impact/admission barriers without widening reconnect.
- Refusals, states and handles have fixed/redacted displays. Expectations/progress are advisory, not reusable authority.
  Caller cancellation wins; acquired transition leases close on every path, and failed close cannot report success.
  No Google proof, configuration, scheduler, sign-out/switching UI, lifecycle cleanup or live account/device action is added.

## Guarded ACTIVE reconciliation and scoped runtime

`AppSessionCoordinator.reconcile(session, expectedCacheEpoch, budgets, restartStageId)` requires the exact current
configured live handle and validates arguments before credential/store work. One facade mutex and one physical-DB run
owner span push/pull, typed complete-snapshot recovery, actual post-baseline tail and paired protected publication.
It never calls reconnect and reacquires ownership between phases. An obsolete epoch with healthy authority reports
closed `UNAVAILABLE`, not dead-handle staleness; actual process/session/authority mismatch remains stale.

- Retained stages are observations, not resume authority. Without an explicit matching canonical restart UUID return
  `RETAINED_STAGE`; foreign/nonexistent requests refuse before effects/HTTP. Generate/validate a fresh stage UUID before
  abandoning the selected stage, preserving evidence on provider failure. Cancellation propagates; other provider faults
  are closed/redacted unavailable. No implicit stage restart or rejection bypass.
- Only missing baseline/cursor, authentic cursor expiry or explicit matching retained-stage restart invokes snapshots.
  Continue only actual same-driver/same-run limit capabilities in bounded chunks, without a total account-size ceiling.
  Complete only actual terminal issuance, then execute the real push-before-pull tail at its adopted epoch. Invalid/foreign
  tokens never bootstrap. Snapshot failure/expiry retains evidence; completion alone is not last-success or convergence.
- After actual tail publish through the same admitted run, exact process guards and real request-bound barrier. Claimed
  rest/new intent returns `PROTECTED`, preserving the entire visible graph; there is no in-invocation claim/push/retry loop.
  Valid published/unchanged materialization returns `TAIL`; incomplete/invalid graph cannot fabricate it. Result display
  is fixed/redacted and fields are progress observations, not reusable capabilities.
- `observeCacheEpoch(session)` is serialized read-only fresh progress after uncertain commits. It reads the freshly selected
  device/cache tuple transactionally, validates a complete baseline and nonnegative ordered cache/materialized counters,
  then repeats exact handle/process/authority/caller fences after dispatch. Dirty cache/queued intent remain eligible.
  No HTTP, clock, UUID, mutation, cleanup, rebind or authority grant; callers need no UI DAO access.

`ProjectionStore` accepts an optional process guard for compatible primitives; every live ACTIVE publisher supplies it.
`preservesTimer` requires complete baseline/no stage and repeated session/epoch/process fences. Protected account outbox
returns true without interpreting latent canonical graph; this protects the visible timer, not graph publication authority.
Eligible reads validate the full graph/dependencies before deciding whether the same active parent/entry/completed set
survives. Live publication checks caller/lease/process/session/epoch before effects, after graph replacement, after FINAL
metadata SQL at the newly advanced epoch and after dispatch. The prepared path retains its registered-permit/combined
completion fences; no lease alone grants account authority. Guard failure before commit rolls back the entire graph.

`AccountGymAppState` and `GymApp(accountState)` provide the exact handle repository/shared edits around every feature,
with synchronous currentness selection and a generic privacy gate instead of local fallback. They own real transitions,
canonical timer impact and lease-safe retirement, including hidden reauthentication reservation. Construction stays
private with no automatic HTTP. Open explicit-save custom editors refuse normal transitions until explicitly closed;
emergency invalidation hides immediately. App/feature epochs reconcile selected models, active pill and request-bound
consent without late history writes or timer resurrection. See [runtime/refresh architecture](../architecture.md#projection-observation-and-feature-refresh).
Mandatory startup/account presentation composes this runtime with separately serialized foreground/edit/manual ACTIVE delivery;
private startup recovery itself remains HTTP-free. Public sign-out/switch/general
import-choice/deletion UI and background scheduling remain absent.

## Legacy auth replay limitation

The [shared auth-expiry issue](../../shared-docs/docs/backend-contract/issues/auth-expiry-precision.md) records a resolved
new-grant precision defect but an **unrepaired legacy accepted-replay limitation**. Pre-fix encrypted accepted response
bytes/deadlines may remain incompatible with strict millisecond readers. A known still-current legacy refresh without
an uncertain pending predecessor can rotate freshly. An uncertain consumed-token retry must retain its original key/body.

Never round timestamps, rewrite accepted replay, invent a new uncertain key, blanket-revoke sessions, or infer account/
cache deletion. Strict malformed replay preserves the original pending pair; passing new-grant interoperability does
not prove legacy replay repair. Explicit fresh-session [supersession](#inert-retained-account-recovery-and-superseded-evidence)
retains/fences the original encrypted evidence instead; carry the unrepaired replay limitation into release review.

## Test infrastructure boundary

Use real in-memory Room for client persistence, narrow scripted doubles for client timing/errors, and isolated real HTTP
for interoperability; do not maintain a second server implementation. `:app:testRealBackend` exercises production sync/auth
adapters over test TLS to the disposable service. Guarded setup bypasses external Google only to create isolated test
credentials, not service authorization. Legacy JVM JDK helpers remain test-only. The opt-in real Room/HTTP test layer
now composes the delivered capture/push/pull/snapshot/publication seams across two independent file-backed stores.
[Core R10a](../archive/remaining-work/R10a-review.md) accepts representative lost-outcome exact replay, offline/reopen,
active replacement/delete, frozen/interrupted snapshot/absence and newer-intent convergence. The host relay remains
JVM-only; it provisions synthetic credentials and may withhold a definitive response after real upstream success, never
simulate successful domain outcomes. Procedures are in [testing strategy](../testing-strategy.md#opt-in-real-roomengine-convergence).
The new scoped-facade/root real journey starts fresh independent Room/vault stores, obtains a current synthetic service
session through the test-only relay's actual refresh, and verifies intended custom/routine IDs/names/references, quiescent
outboxes and paired materialization over real HTTP. The relay never simulates domain success and stays outside the app
APK. Neither Core nor scoped synthetic evidence proves process death, expanded R10b,
service retention pruning, deployed TLS, real Google or personal-phone readiness.
