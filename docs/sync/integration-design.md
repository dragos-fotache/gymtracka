# Approved Android sync integration design

**Approved design, not a claim that the integration is implemented.** This is the task-relevant reference for Android
choices, not coordinator startup reading. The public v1/backend contract is unchanged; the personal account scope follows
[P16](../../shared-docs/docs/backend-contract/open-decisions.md#confirmed-product-decisions). Execution requires authorization
for a bounded slice; [the roadmap](../remaining-work-roadmap.md) owns status and dependencies.

The original R01 design was approved on 2026-10-03. Existing model, transport, vault and refresh primitives now implement
parts of this foundation, including inert startup recovery seams; [sync implementation](implementation.md) describes their
actual boundaries. R05a supplies independent canonical storage and transactional capture watermarks; R05b now adds inert fenced canonical
record/batch application. R05c adds an inert protected publisher; R06a adds inert serialized durable head claiming and
exact replay admission; R06b adds inert single-head outcome classification and retry/error bookkeeping; R06c adds inert
atomic canonical acknowledgment; R06d adds an inert bounded push delivery driver; R07a adds inert incremental batch/
checkpoint transactions on that same admitted run; R07b adds bounded incremental HTTP pull receiving an already
admitted run. R07c adds an inert shared push-first driver and separate fenced valid-tail/last-success transaction.
R08a adds inert durable whole-page snapshot staging with the data-preserving Room10→11 migration; R08b adds actual
bounded HTTP download and R08c complete canonical baseline/absence plus coordinated protected publication/recovery.
All reuse the same admitted run/physical-DB owner. R09a now supplies inert Room12 private authority observations and
atomic preparation/fencing; see [implementation](implementation.md#durable-private-room-account-authority).
R09b adds inert [prepared vault/Room persistence and reread recovery](implementation.md#prepared-vaultroom-coordination-and-recovery).
R09c adds inert [private prepared engine admission/canonical bootstrap](implementation.md#private-prepared-session-engine-and-bootstrap).
R09d adds inert [completed preparation/exact vault promotion](implementation.md#private-completed-preparation-and-vault-promotion).
R09e/f add inert [settled occupied replacement](implementation.md#inert-settled-occupied-credential-reauthentication) and
its [exact established ACTIVE pending-replay prerequisite](implementation.md#inert-exact-pending-refresh-reconciliation).
R09g adds inert [retained-account recovery and superseded evidence](implementation.md#inert-retained-account-recovery-and-superseded-evidence).
R12a supplies the [real app-owned finite-edit/timer barrier seam](../architecture.md#finite-edit-admission-and-publication-leases).
R12b supplies [narrow projection observation and selected-feature refresh](../architecture.md#projection-observation-and-feature-refresh).
Production bootstrap/projection activation, convergence, authoritative canonical timer-impact selection and scoped app
reconstruction/reconciliation remain planned. Broader account transitions are deferred public scope. Stored vault phases and
explicit refresh leases alone never authorize activation. Preserve the unresolved
[legacy auth replay limitation](implementation.md#legacy-auth-replay-limitation) in all recovery work.

Maintain approved decisions here in place; preserve their rationale and distinguish adopted refinements from proposals.
The original approval evidence is [archived](../archive/remaining-work/R01-review.md), not required orientation.

## 1. Smallest safe personal architecture

Keep composition-scoped feature state, narrow repository interfaces, Room as UI persistence and explicit constructors.
Add one application-owned coordination object shared by foreground sync and, later, WorkManager. It is constructed at
the app boundary, not inside leaves. No ViewModel/DI/navigation framework is introduced.

```text
Google foreground adapter -> service-auth adapter -> encrypted session vault
                                      |                     |
                              SessionCoordinator <---- startup recovery
                                      |
                     durable Room identity + transition journal
                                      |
                selected-scope repository + composition-scoped UI
                                      |
                           transactional local capture
                                      v
Room domain projection <--- publication gate --- canonical cache / staging
          |                                          ^
          +---- stable ordered outbox -> SyncEngine --+ -> HTTPS sync adapter
                                              |
                                  exact outcomes / pull checkpoint
```

Proposed narrow responsibilities (names are design labels, not yet API signatures):

- **SessionCoordinator**: startup privacy lock, foreground attempts, vault/Room journal protocol, initial single-account
  activation and reauthentication fences. Sign-out/switching UI belongs to deferred public workflows; durable identity
  and late-result fences remain personal prerequisites. Domain cleanup is explicit and account-bound.
- **SyncEngine**: one serialized account/session run, durable head claiming, pushes, pulls and snapshots. It never derives
  identity from Google/email, never opens credential UI, and never performs network calls in Room transactions.
- **CanonicalStore**: independent complete canonical records/deletion evidence and publication bookkeeping;
  fenced batch/outcome/page transactions. It never calls `GymRepository` mutations to apply remote records.
- **ProjectionGate**: pause UI mutation admission briefly, drain already accepted finite edits, coordinate affected
  timers, and grant a short publication lease. Local capture and publication recheck durable state inside Room.
- **Scheduler**: foreground/manual/edit dirty-run trigger initially; WorkManager later uses the same coordinator/engine,
  not its own parallel client or account repository.

Inject a scope, clocks, UUIDs, transport, vault I/O and publication barrier only at the boundaries needing deterministic
fault/race tests. Production defaults remain available. No test-only server implementation is added.

## 2. Canonical state versus local intent: account-wide protection first

**Selected simplification:** while *any* pending/frozen/rejected/corrupt outbox row exists for the selected account,
protect its **entire visible domain projection**, not just the envelope target. Canonical batches can update a separate
account-keyed store without overwriting visible local roots/children. No field/position merge and no semantic-command
reimplementation/replay against remote records is required.

This deliberately delays unrelated remote visible updates while the account has outstanding local work. That is a
personal-use simplicity trade-off, not loss of canonical data. UI distinguishes outstanding work from complete sync.
Per-root overlays/dependency graphs are optional future optimization, not a prerequisite for personal use.

### Why a separate canonical store is necessary

One root `serverRevision` beside mutable local rows cannot simultaneously retain a winning server aggregate and a
newer local aggregate. Keeping only old acknowledgment JSON in an outbox also loses unrelated remote deletes/changes.
Store canonical data independently; the existing domain tables remain the durable latest local projection and local
intent. Frozen requests remain commands, not a recipe to reconstruct all local domain values.

Room storage boundaries below retain the approved design. R05a has delivered the canonical ledger and a separate
`sync_account_cache` in v10; exact current fields/capture/migration behavior are owned by
[the implementation reference](implementation.md#canonical-ledger-and-capture-watermarks). R05b adds
[fenced canonical batch writers](implementation.md#fenced-canonical-batches), preserving watermarks and marking dirty
without incrementing cache epochs or changing visible data. R05c's protected publisher reuses this storage; R08a–c
supply inert staging/download/complete-baseline recovery. R09a delivers private authority/journal storage; vault/Room
coordination and production activation remain future work:

| Storage | Required logical fields and constraints |
|---|---|
| `sync_canonical_roots` | PK `(account_id, aggregate_type, aggregate_id)`; nullable highest observed positive revision; state `unobserved`, `upsert`, `delete`, or `snapshot_absent`; validated complete canonical JSON only for upsert; local-generation watermark; cache epoch. No FK cascade from domain tables |
| Account cache metadata (`sync_account_cache`, delivered R05a) | Account PK; complete-baseline flag; cache epoch; projection-dirty flag; materialized epoch. Existing opaque cursor/last success stay in `sync_state`; admission binds immutable session and per-callback cache-epoch fences, the frozen row's attempt count and a live run capability; successful snapshot completion/publication adopts returned run epochs. Outcome bookkeeping and atomic acknowledgment additionally use conservative process-local write tokens; bounded push orchestration reuses these seams without advancing pull checkpoints or epochs |
| Snapshot staging (R08a delivered Room11) | Unique account/stage; origin session/epoch, fixed limit, original full checkpoint/baseline observation, frozen baseline/continuation and phase/counters; whole records PK `(stage_id,type,id)` plus page/position; durable exact continuation/page evidence. Stage-only child cascades, no visible-domain/canonical FKs. Read/restart observes for explicit abandon/new-stage restart, never resumes append authority |
| `sync_session_authority` (R09a delivered Room12) | Independent singleton id1, checked revision/session generation, strict phase and nullable canonical service_origin/account_id/operation_id. Private signed-out cache retains known origin provenance. Private preparation/fencing and R09d's explicit registered completion ACTIVE/ready writer exist; exact vault phase promotion is separate, and no app activation is wired. Legacy-data fields require owner-selected migration; broader local-choice/import provenance is deferred public. **No credentials/Google proofs** |

A generation watermark survives domain rematerialization/removal; canonical writes must not lower a local generation.
Capture advances any relevant ledger watermark in the same transaction as its existing domain/outbox update. Local-only
rows are not seeded as canonical and never acquire a fabricated server revision. A new account-scoped local root may
reserve its generation watermark as `unobserved` with no canonical JSON/revision until a server result arrives;
snapshot absence does not turn that unsent creation into an invented server deletion.

### Projection gate

- If outbox is nonempty, keep the complete last visible projection plus its local edits/tombstones unchanged. A rejected
  predecessor protects all subsequent intent, including different roots/custom references and the locally sole active.
- If outbox is empty **and canonical baseline is complete**, a publication lease can materialize the complete current
  canonical account graph in one Room transaction. Recheck empty outbox, account/generation, baseline/epoch and lease
  after draining; an intervening edit aborts publication rather than getting overwritten.
- If baseline is incomplete, even the final accepted push cannot expose a partial cache. Perform a full snapshot first.
- Publication changes only that account's domain projection. Remove account workout/routine children first, then
  obsolete custom relationships/rows; install customs before referencing workout/routine children. Preserve all IDs,
  positions, nulls, values, metadata and generation watermarks. Do not use root `INSERT OR REPLACE` as an accidental
  FK-cascade algorithm.
- Validation of the **whole resulting canonical graph** occurs before domain publication: custom references, global
  catalogue IDs, family identities and at most one canonical active workout. Intermediate canonical rows may have
  unresolved dependencies while fetching/staging, but no incomplete graph is presented as a complete visible cache.
- Remote publication emits no outbox work. Commit a change signal/epoch that all currently selected state holders
  observe, including a custom rename even if parent workout/routine revisions do not change. R05c reuses the existing
  v10 counters: each actual complete graph publication increments `cache_epoch`, sets `materialized_epoch` to that new
  value, and clears dirty atomically. Blocked/clean-no-op/error paths never advance it; checked overflow fails closed.
  Older canonical row epochs remain valid. This deliberately fences prior-epoch callbacks; future engine callers must
  continue with the returned epoch. R12a/b supply finite-edit/observation seams; R12c still owns scoped app rebinding and
  authoritative canonical timer-impact integration.

### Global Room PK collisions

Current domain tables have global UUID PKs. The independent canonical store avoids destroying data during download,
but materialization must still validate all root/entry/set IDs against surviving signed-out/other-owned/bundled rows
and parent bindings. Never overwrite/remap them to make publication succeed.

A collision rolls back domain publication, retains canonical/staged state and local intent, and surfaces a recoverable
storage/import error. Different-account transition clears only the previous private account before target publication;
import/replace resolves the separate local graph before activation. Retained-cached versus new local UUID collisions
also fail closed. Supporting simultaneous same-UUID graphs by composite domain PKs would be a larger optional migration;
this plan does not silently require that rewrite. Test this explicit limitation and error path, not just random-UUID
happy paths.

## 3. Revision, deletion and original-outcome rules

- Root identity is `(account,type,id)`; workout/routine family roles and parent bindings are checked before writes.
- Canonical revision chooses whether an incoming record is older/equal/newer locally; it does **not** decide which
  server command is eligible. `knownServerRevision` stays informational.
- Persist DELETE evidence even for a previously unknown root. Never create a visible fake workout/custom placeholder
  with fabricated business fields just to remember a revision. The independent ledger survives removal of domain rows.
- Older changes are canonical no-ops; equal revisions must agree on authoritative fields/operation. Ignore embedded
  convenience-label differences at equal workout/routine revision. An inconsistent equal-revision domain record fails
  the complete batch transaction, not a best-effort field repair.
- Apply a batch's selected newer changes atomically, including old-active DELETE/new-active UPSERT. Skipping an obsolete
  change does not split commit visibility. Validate projected canonical graph before exposing it.
- Snapshot absence is authoritative only for a **complete** snapshot. Mark previously known missing roots
  `snapshot_absent`, retaining their known revision evidence without inventing a deletion revision. Increment cache epoch.
  Reject stale run/claim/page callbacks from before publication; no uncertain push runs concurrently with snapshots.
  Pulls start at the new baseline under the new epoch. Absence in an incremental page is never deletion.
- The inert [atomic acknowledgment boundary](implementation.md#atomic-canonical-acknowledgment) now persists canonical
  result and exact outbox acknowledgment in one fenced transaction. Accepted snapshots privately bind account and exact
  durable row identity, but live admission/fences/context still recheck with writes. A delayed duplicate accepted batch
  may be older than current canonical state: still acknowledge the exact request, never restore its old winner.
- Affected-root bindings are acknowledgment context for *local* effects, not a promise that the server deleted exactly
  the locally known old active. A start can delete another cloud active the client never saw; apply the whole returned
  batch but never acknowledge unrelated outbox rows or silently mark higher local generations synced.
- Removing the selected row requires exact account, sequence/mutation ID, version, unchanged JSON, frozen generation
  and affected-root bindings to match the claim. A newer visible generation remains protected while any later work
  exists. Unexpected root/generation/state mismatch retains a corruption error; no guessing or new mutation ID.

## 4. Exclusive engine ownership, push and pull

Single-process app singleton plus a coroutine Mutex serializes sync runs. WorkManager later runs in the normal app
process and shares it. Do not add a multiprocess lease/remote service incidentally. Cancellation joins/invalidates owned
run work before another run can apply results; durable session/cache/run checks provide defense against late callbacks.
Lock ordering: brief coordination state lock -> fenced Room transaction; never hold Room or the state lock across HTTP
or Google UI. A run Mutex may span the network, but sign-out can fence/cancel it without waiting for the service.

```text
run(scope fence):
  claim explicit account sequence head transactionally
  if rejected/corrupt/oversized-unrecoverable: report retained error; stop
  freeze/retain exact wrapper and affected roots; commit before HTTP
  push ONE mutation with exact UTF-8 mutation text
  apply complete definitive result + exact acknowledgment transactionally
  repeat pending heads (bounded run); never bypass rejected predecessor
  if no cursor/baseline: snapshot; else pull from persisted cursor
  apply each complete batch + its boundary in a fenced transaction
  request safe projection publication; if new edits arrived, schedule another run
```

- The inert [bounded push driver](implementation.md#bounded-push-delivery) now implements the push portion under one
  shared run owner. Its strict 1..100 acknowledgment budget returns LimitReached without a successor probe; Empty is
  only fenced outbox emptiness, not a valid pull tail/bootstrap or convergence. Failure stops immediately with durable
  retry/error context and no in-run delay. R07a's [checkpoint seam](implementation.md#incremental-checkpoint-transactions)
  commits one complete canonical batch with its boundary on that same run, without last-success/publication or HTTP.
  R07b's [bounded pull driver](implementation.md#bounded-incremental-pull) accepts an already admitted `PushRun` with
  no store admission or second owner/mutex. It validates/owns whole pages before suspension, fences transport results,
  detects run cycles and chains complete batches within strict page/count budgets. The inert
  [shared driver](implementation.md#shared-push-before-pull-and-valid-tail) reuses the admitted push overload and
  internal exact-checkpoint terminal callback under one uninterrupted run, never nesting admission. Only fenced push
  Empty permits pull; push budget/error/auth stops do not probe or bypass the head. Its separate tail transaction
  records last-success and observes outstanding newer intent without advancing a cursor or publishing projection.
  Bootstrap/publication and user-facing activation remain later integration.
- Frozen means possibly transmitted forever, even if socket construction/cancellation occurred early. Transient failure
  never makes it coalescible. Process restart selects the same frozen head and unchanged ID/bytes/child plans.
- `duplicate` is accepted only if it carries the original accepted batch; duplicate rejection remains rejection.
  A complete push response must have exactly the expected outcome count/order/IDs. Malformed/truncated responses keep
  uncertain work intact. HTTP failure can follow a server commit; it is never proof of no server effects.
- Access `401` permits one serialized refresh-and-retry with unchanged mutation bytes. Invalid refresh/repeated `401`
  suspends delivery for foreground reauthentication. Generic errors never erase domain data.
- Bounded backoff retains frozen work; `429`/transient hints are capped safely. The inert
  [single-head implementation](implementation.md#single-head-push-outcomes-and-retryerror-handling) uses deterministic
  admission-count backoff and conservative write-attempt token consumption, not persistence/ack evidence. Failed or
  uncertain bookkeeping attempts require fresh admission of unchanged frozen identity; accepted/auth read-only checks
  leave admission available for atomic acknowledgment, which shares that mutex/token discipline and marks dirty even
  for an all-obsolete result without advancing epochs/checkpoints. `413 request_too_large` retains intent
  and requires explicit recovery/configuration; do not split a semantic mutation or endlessly retry unchanged bytes.
- Permanent rejection stores code and fixed safe presentation/recovery context; stop all dependent sending.
  **Personal policy: do not pull around a retained rejection.** The canonical/projection design could support it, but
  it is unnecessary complexity. Confirmed recovery first settles any uncertain frozen head unchanged, then can discard
  the explicitly confirmed remaining pending/rejected intent and stage a complete cloud snapshot. Never abandon an
  uncertain accepted write by changing its ID. Sign-out is still possible without discarding work.
- Pull page transaction boundaries are complete batches and their cursors. Earlier committed batches can remain if a
  later batch fails; the durable checkpoint is exactly the last successfully contiguous batch, never page tail on failure.
  Empty tail preserves the input boundary. Validate count/hasMore/progress and repeated IDs/tokens across the run; tokens
  are opaque and never lexically compared. An invalid token is not expiry and never silently triggers bootstrap.
- `lastSuccess` means the defined run reached its valid tail/complete bootstrap, not merely one HTTP 200. R07c records
  only a validated terminal page's exact chained checkpoint under complete-row/baseline/session/epoch/cancellation
  fences. Failed/uncertain writes require durable reread; retired tokens are not commit evidence. The supplied
  nonnegative wall time is retained exactly, including equal/backward observations, and is never a conflict clock.
  Track outstanding local work and projection publication separately: a bounded valid tail/empty observed outbox is
  not account convergence or absence of later edits. R08c complete-snapshot establishment/publication preserve source
  last-success: a frozen baseline is not a post-baseline valid pull tail. Future bootstrap orchestration reuses the actual
  R07 tail after snapshot catch-up, rather than treating download/baseline/publication as last-success evidence.

## 5. Frozen snapshots and full resync

Push recoverable queued intent before beginning a snapshot; reject/corrupt head blocks ordinary resync until explicit
recovery. No other push runs until that download publishes or is abandoned; local UI edits may still capture during a
same-account full resync, and the account-wide protection rule retains their projection.

```text
complete old visible cache ------ remains visible throughout download
          |
stage(null token) -> stage(page 2) -> ... -> terminal page
          | failure/expiry/account switch: discard/restart stage, old cache unchanged
          v
validate complete graph + one baseline + fence
          v
Room transaction: replace canonical cache, checkpoint baseline, mark complete/new epoch
          |
          +-> clean + publication lease: publish complete domain graph
          +-> newer local work: retain full old/local projection, dirty publication marker
```

Repeat the same page limit. Validate baseline consistency, unique root keys across **all** pages, progress/nonempty
nonterminal pages, continuation cycles and terminal absence of a token. References may be on later pages. Stage whole
aggregates; never truncate a record/batch to meet a local memory limit. No new domain-size cap is introduced.

R08a now persists staging safely enough to recognize incomplete work after process death. The adopted personal
recovery is **explicit discard-and-restart**, not cross-run resume: only a stage begun/chained in the same live admitted
run can append. Durable reread yields an abandonable observation, never new append authority. A downloaded stage
remains outside canonical/domain publication and is not complete bootstrap. Existing storage validates whole records/
progress without resolving account-wide dependencies; R08b now implements actual bounded HTTP download on that same
admitted run after fenced push Empty. Only its own LimitReached result permits same-run bounded continuation;
transport failures/expiry retain stages for explicit recovery. R08c now implements separate actual-driver-issued terminal
completion: complete-graph/retained revision/family validation and canonical baseline/absence/checkpoint/stage removal
commit atomically at epoch+1 without visible edits or last-success. Missing unobserved reservations remain unobserved.
The paired protected publisher runs on the SAME admitted run/handling Mutex; actual publication advances epoch again,
and callers use returned epochs. Physical collisions are checked at publication, never repaired by remapping.

Failed/uncertain writes require durable reread, never result/token-retirement or stage-absence success inference.
Downloaded retained stages cannot be adopted for completion after restart: explicit valid abandon/NEW stage remains
policy. A complete dirty baseline surviving crash can publish under a fresh authorized run at the durable epoch without
redownload; clean postcommit recovery returns UNCHANGED. A lease-close failure is not rollback evidence. No credentials,
personal import/sign-out authority, live barrier, engine convergence or app activation is inferred.

Budgets bound invocation work, never permanent account size; live same-run continuation differs from cross-run recovery.
Frozen requests/outbox are unaffected. Expired snapshot and invalid token stay distinct; never mix fresh/prior pages.

A complete empty account is valid. It can remove cached cloud roots only in the complete publication transaction;
separate local data is untouched except its explicitly confirmed replace choice. Initial sign-in has no visible account
cache until this gate passes. An old unchanged root must be discoverable without any retained pull history.

## 6. Vault and Room: two stores, recoverable journal, no fake atomicity

The [implemented vault and refresh primitives](implementation.md#encrypted-vault-and-refresh) provide app-private
`noBackupFilesDir`, Keystore AES/GCM, versioned atomic records, backup exclusions, and exact uncertain-refresh persistence.
The [startup recovery seams](implementation.md#startup-recovery-seams) now supply read-only eligibility and runtime
fencing over an explicit authority interface, now supplied by R09a's
[private Room adapter](implementation.md#durable-private-room-account-authority). The remaining design below delivers
cross-store coordination, not replacement vault or authority storage. Vault records bind
service credentials, pending attempt, account/generation/operation and revision. Google ID tokens remain bounded
foreground memory; no persistence across restart. R09a's Room journal holds only nonsecret private identity/transition
state. R09b now implements the bounded reserve/PREPARED-write/stable-reread portion privately, with reservation-only
resume and read-only restart classification. R09c now separately supplies fresh private engine admission, canonical
bootstrap and actual valid-tail coordination under registered Room permits/shared ownership. R09d adds the bounded
completion portion: actual terminal tail, required paired publication lease, projection plus ACTIVE/ready journal/device
in one Room transaction, then exact phase-only vault CAS and coherent alternating readiness/record rereads. Its inert
recovery promotes only that exact completed gap, without HTTP, publication or Room writes. Those preparation/completion
paths replace no occupied credentials and activate no app. R09e separately adds inert settled occupied ACTIVE same-account replacement:
checked private TRANSITIONING reservation/new generation, exact full supplied-grant PREPARED vault CAS, then PREPARING
finalization at the same reserved generation. It requires shared physical-DB/credential ownership, coherent pair rereads
and no pending predecessor; retained intent/cache/domain are untouched. R09f separately supplies exact established ACTIVE
pending replay through the shared refresh coordinator before R09e replacement: ONE retained attempt, complete returned
grant/no-pending CAS, required old Room authorization, stable pair rereads and advisory settled evidence, with no fresh
rotation or Room mutation. See [implemented replay boundary](implementation.md#inert-exact-pending-refresh-reconciliation).
[P17 preserve-and-supersede](../../shared-docs/docs/backend-contract/open-decisions.md#confirmed-product-decisions)
selects the remaining unsuccessful/legacy-malformed/interrupted uncertain-session recovery policy: atomically retain and
permanently fence exact encrypted predecessor evidence while installing distinct fresh same-account sign-in credentials.
[R09g](implementation.md#inert-retained-account-recovery-and-superseded-evidence) delivers this and signed-out/missing-vault
recovery privately, including nonadjacent older-evidence reservation gaps with a fresh grant. Live app integration remains
outstanding; no old evidence supplies credentials/visibility, and R12a/core R10 still gate production activation. See
[implemented settled boundary](implementation.md#inert-settled-occupied-credential-reauthentication).
Existing canonical-only engine recovery is reused, not recreated for completed preparation.
Ordinary local backup need not be disabled to exclude credentials.

### Personal scope boundary

The personal milestone requires initial sign-in and complete account activation, then permits the established account's
cached data and edits offline across ordinary restarts without online login every launch. Same-account reauthentication
resumes retained intent; access expiry follows normal serialized refresh, and only unavailable credential recovery
requires foreground reauthentication for delivery, not local-data deletion. Missing/unreadable/
mismatched vault binding still fails closed/private, and another service UUID cannot adopt the account's data or queue.

No signed-out editing, account switching, sign-out UI or general import/replace UI is required for personal delivery.
Existing local-only data disposition is unresolved: retain it without import/deletion/reassignment until owner direction.
A selected one-time migration/import or confirmed clean/cloud-only start needs its own bounded scope and gates.
The protocol below retains durable account authority/journal, crash recovery and late-result fences; legacy-choice
branches apply only after that decision, and broader transition branches remain deferred public obligations.

### Startup and activation

```text
PrivacyLocked -> reconcile Room journal with vault
   match + ready phase + complete cache -> selected authenticated account scope
   recoverable prepared transition    -> resume preparation privately, no ordinary account UI
   missing/unreadable/mismatched vault -> fence active identity off, retain private cache, require sign-in
   configuration unavailable          -> setup/sign-in gate for initial use; never activate against another origin
```

Bind vault to configured HTTPS service identity as well as account and session generation: credentials from one service
must never be sent to another origin just because an endpoint setting changed. A configuration change requires a
separately fenced sign-out/reconfiguration flow; do not fall back to cleartext or another origin.

Proposed durable activation protocol (no local-choice branch on a new empty personal installation; no legacy-data
branch is authorized until its disposition is selected):

1. Drain/gate old UI mutations; reserve monotonic new device generation and transition operation in Room. Hide any
   preparing account; no new ordinary repository follows the provisional target. Freeze captured local choice inputs.
2. Persist the returned grant in the vault as **prepared** for that target/generation/operation.
3. Room records preparation with the same identity; target sync/import is permitted only through explicit internal
   preparation operations. Clear a different previous cache/work/checkpoint/staging under the confirmed switch rule,
   preserving separate local data/catalogue. Nothing is copied from old account A to B.
4. Finish required pending import delivery and staged complete bootstrap (details below). Prepare publication lease.
5. Room transaction publishes complete target projection/baseline and completes local-data choice/active phase;
   it verifies the prepared vault binding has been durably written, not merely kept in memory.
6. Atomically promote the matching prepared vault record. A crash between 5 and 6 recovers/promotes the same record
   through the journal before UI/sync activation. R09d retains operation/origin provenance; no journal cleanup is delivered.
   Any later cleanup must preserve the exact identity required by startup/recovery rather than erase binding evidence.

No step assumes Room and encrypted file share a transaction. Every crash boundary either resumes the exact operation
or fails closed/private. Orphan prepared vault grants do not authorize an unmatched Room generation. Use checked
monotonic generations; overflow is a retained error, not wraparound.

### Import/replace and initial activation

General import/replace choices and account switching below are deferred public workflows. Their preservation/collision
rules also constrain any owner-selected one-time legacy migration; they do not authorize selecting it. Same-account
reauthentication and complete initial bootstrap remain personal prerequisites.

- Gate new local admissions and drain finite actions before capturing the local graph/choice. Unsaved custom editor
  draft values are presentation state: require Save/Discard/Cancel before replacing its scope, not a hidden autosave.
- Show choice whenever genuinely separate local roots exist without a completed decision for that graph. Account cache
  is never recategorized as local-only, including a same-Google new service UUID.
- **Import:** in one Room preparation transaction associate the chosen local roots with the target and capture complete
  create-only imports in dependency order (customs, then routines/workouts; active replacement warning included).
  Preserve IDs and source snapshots/error context. This is a preparing/private account scope, not premature visible
  activation. Settle each import unchanged, then fetch a complete snapshot. Personal simplification: ordinary account
  activation waits for this initial import queue to be definitively accepted and a full snapshot to publish; errors
  remain visible on the preparation/recovery screen rather than exposing an incomplete account cache.
- Domain/child collision is a retained permanent import error, never ID remapping or overwrite. If some imports have
  already committed remotely, cancellation/sign-out cannot claim to undo them. Retain prepared account data/outbox
  privately for same-account recovery; never automatically restore/reassign it as separate local data.
- **Replace:** leave the separate local graph intact until target credentials and complete snapshot are ready. Delete
  only that graph in the final publication transaction, rechecking captured IDs/generations, then publish complete
  cloud data. Middle/final download failure does not destroy local roots.
- Same-account reauthentication with no new local data resumes the retained queue/cache. A complete existing cache and
  valid checkpoint need not bootstrap again; no checkpoint always needs a snapshot. New local data still requires a
  choice. Reauthentication refreshes the session fence, not root ownership.
- Different-account sign-in explicitly warns about clearing the previous private account cache/pending work before
  proceeding. This implements the existing one-account cache contract, not permission to copy A's queue into B.

### Refresh and sign-out

Refresh/recovery and identity fencing remain personal scope. The sign-out UI/journey and signed-out repository behavior
below are deferred public scope, not required to enable the mandatory-sign-in personal milestone.

- Serialize refresh selection/persistence; before transmission, atomically store unchanged old refresh token, exact
  encoded body and idempotency key in the vault. Retry that attempt after cancellation/lost response/restart.
- Persist the rotated grant and attempt completion atomically; a crash must see either the old uncertain pair or the
  complete new grant, never a new key with consumed old token. Replayed expired access grant can be stored first and
  then refreshed anew if its refresh is current. No client expiry-extension invention.
- A refresh result must match account/generation/operation and vault revision before saving. Late A responses cannot
  overwrite B or reinstall credentials after sign-out. HTTP is outside the state lock so sign-out can invalidate it.
- Sign-out never waits for server revocation. Stop new account actions, drain accepted local work/claim timer terminal
  writes before cancelling their owner, fence active identity off/increment generation in Room, erase vault, cancel
  scheduling and clear Google credential state; best-effort logout uses an in-memory credential only. Retain private
  canonical/projection/outbox/checkpoint for cached account. Recreate the signed-out repository with the new generation,
  not `RepositorySession.SIGNED_OUT`'s old null/0 default.
- If Room/vault I/O fails, lock authenticated presentation/delivery immediately; do not falsely report durable cleanup
  or cancel unsaved accepted edits as if they committed. Retain a recoverable error; startup mismatch recovery remains
  private. Offline sign-out has no network prerequisite; arbitrary disk failure is not a successful persistence claim.
  All normal scope-changing actions must fail safely if their local drains have unresolved failures.
- Generic `401`/`404`/offline/expired credentials never prove account deletion. Selected offline cache can remain during
  recoverable expiry while prompting reauthentication; missing/mismatched vault on startup fails closed/private.

## 7. Autosave, timer and UI publication prerequisites

Finite edit registration must cover routine name/set/rest/add/delete, picker commits, history actions and app-shell
mutations as well as active-workout edits. Track failures, not just `Job.join()`. Admission pause plus a repeat drain
prevents edits accepted between drain and publication. Do not join endless timers/tickers/Room observers.

For same-root updates, reload persisted values without destroying an accepted draft/focus. A publication epoch, not
root UUID alone, drives reconciliation of remembered field text. For a custom rename refresh every reference label from
selected-scope exercise data even at unchanged parent revision; never store convenience labels into catalogue or upload.

A timer's ownership key must include session, workout/root, set and timer instance. Before projecting a remote finish,
delete, replacement or completion invalidation that would orphan the timer, terminate/claim it while the old domain set
is still present, drain its serial rest write, then **recheck outbox protection**. If that write created local intent,
postpone projection and deliver/recover it normally; never silently drop it because the cloud parent is already finished.
A rejected late rest intent is surfaced, not rewritten as an edit to immutable history. An unchanged valid set/reference
replacement retains its timer association. A rename alone must not restart/end a timer.

Expose terminal write success/failure to transitions, preserving the current at-most-once claim policy. Do not retry a
failed terminal timer write twice. An unresolved failure prevents pretending the timer was safely persisted. Wake locks,
alarm-usage vibration, elapsed whole seconds, completion transition guards and local finish drain remain regression
invariants. No process-death countdown reconstruction is added incidentally.

R12a must land before production engine publication/account activation. Pure store/engine tests inject a controllable
barrier; app integration must not silently replace it with a no-op while compositions are live.

## 8. Migration boundaries and acceptance matrix

R05a delivered the **data-preserving v9→v10 migration** for canonical ledger/publication state and capture watermarks.
R05b canonical batches and R05c protected publication reuse v10 without entity/schema changes. R08a delivered the
**data-preserving v10→v11 staging migration** while preserving all prior entity definitions/bytes and the v1 asset.
R09a delivered the **data-preserving v11→v12 authority/journal migration**, adding only an empty independent table;
no existing device/root/schema bytes change and no activation is fabricated. Later schema versions are assigned only on
pickup. Combine adjoining changes only if they are one reviewed deliverable, not to avoid migration coverage.
No entity change without KSP/export, preserved snapshots, all supported starts and copied v1 asset tests.

| Invariant / fault | Mandatory layer and gate |
|---|---|
| Duplicate raw keys, escaped-key aliases, exact unchanged v1 wire retry | Independent JVM codec fixtures; R02a; Android parser smoke because platform org.json differs |
| Family role aliases, cross-family copy identities, negative/overflow/null/time bounds | Independent model/codec JVM; R02b; no generated expected values from production encoder |
| Invalid versus expired read token; access versus refresh auth; oversized and unknown rejection | JVM boundary/transport; R02c/R03 |
| Existing v1 wrappers untouched by model/adapter upgrade; unsupported versions retained | JVM durable and real Room claim/recovery; R02/R06 |
| Lost response/cancel/restart keeps head ID/body/child bindings; stale response cannot ack a later row | Real Room + scripted transport/time gates; R06; real-service original-result replay in R10 |
| DELETE unknown root; replay after later winner; equal revision with different convenience labels | Real Room complete batch/ledger tests; R05 |
| Multi-root old-active/new-active while later local start exists; custom delete/reference conflict | Real Room account-wide projection guard/rollback; R05/R06/R10 |
| New edits during ack/snapshot/drain; outbox nonempty; failure after last child insertion | Real Room barrier/transaction rollback; R05/R08; state tests R12 |
| Source/history command result differs from local materialization without child merge | Scripted canonical batch + real Room and real-service valid rejection; R06/R10 |
| Full snapshot references on later pages, old unchanged/empty cache, cycles/duplicates/mixed baselines | Scripted page sources + real Room staging; R08; real-service frozen view R10 |
| Failure on middle/final snapshot; checkpoint/projection rollback; stale prior-epoch page | Real Room crash/restart tests; R08 |
| Global physical PK collision with local/bundled/foreign root/child never overwrites | Real Room full publication/import/switch; R05/R09 |
| Refresh intent persisted before HTTP; each vault/Room journal crash boundary; late grant cannot cross generation/account | JVM state-machine + injected vault/Room failures; Android atomic-file/Keystore/backup checks R04/reduced R09 |
| Initial gate, prepared activation, matching offline restart/edit/reconnect, same-account reauth; reject mismatched/new account UUID | Real Room + vault/identity scripted boundary, then Compose; reduced R09/R13 |
| Legacy local-data preservation and selected one-time migration/clean-start behavior | Owner decision and bounded spec before affected activation; real Room/vault/publication gates R09/R13 |
| General A/B/local privacy, sign-out/switch, import choice/collision and replace failure | Deferred public R09/R13 workflows; not personal acceptance claims |
| Accepted routine/picker/history edits and failed timer drain cannot disappear on remote/scope changes | Virtual-time state tests and real Compose/app flows; R12 |
| Current custom names, navigation/active pill and same-ID fields after remote updates | State + real Compose with Room; R12 |
| Secure origin/TLS, no redirect credential leak/log bodies; unconfigured initial-use sign-in/setup gate | Deterministic HTTP/config, APK/config review, Android startup; R03/R04/R13 |
| Two independent stores converge after offline/restart/lost-response/delete/snapshot | Real Room-backed engine through isolated black-box service; core R10 before activation |
| Real Google, phone/laptop trusted HTTPS, foreground/then background recovery | Explicit owner-approved device/account journeys; R17a/R17b, blocked until configuration |
| Export/deletion/status/old receipt/new signup, public device/signing/CI/retention claims | Deferred R14/R15/R17c/R18, never marked passed by personal sync |

Independent implementation/testing ownership and review gates follow [coordinator workflow](../coordinator-workflow.md).
Completed rows in this matrix describe enduring regression obligations, not tasks to reimplement; consult the roadmap
for accepted versus outstanding slices.

## 9. Security and stop boundary

Release transport only uses the approved HTTPS origin with normal certificate/hostname verification and no credential
forwarding redirects; isolated cleartext loopback remains test-only. No private environment/keys/config in docs, APK,
Room, logs or outbox. Fixed safe error text and cancellation preservation continue. Public lifecycle controls remain
absent until delivered; sign-out is never deletion, auth failure never cleanup authority.

The conservative projection and initial-preparation policies are approved. Concrete interfaces and migrations for
outstanding packages are designed at their authorized pickup; the logical names/tables above are not settled SQL/API
signatures or assigned future schema versions. External connection/OAuth setup requires separate approval. No backend
source/operations audit, installation, live account action, or deployment is implied by this design.
