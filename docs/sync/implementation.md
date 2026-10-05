# Implemented Android sync components

These components exist, but the production app remains signed-out/local-only. **Implemented is not activated.**
No running engine, activated canonical publication, staged bootstrap, durable account transition, Google UI, or scheduler is wired
into the app. The [roadmap](../remaining-work-roadmap.md) owns delivery status; the
[integration design](integration-design.md) owns approved future coordination. The
[shared contract](../../shared-docs/docs/backend-contract/README.md) remains the service authority.

Paths below are relative to `app/src/main/java/com/dragosfotache/gymtracka/data/`.

## Room and local capture

Room v10 retains the v9 root metadata: nullable account/server revision/deletion time, local generation, and sync state
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

### Canonical ledger and capture watermarks

R05a adds `sync_canonical_roots`, keyed by `(account_id, aggregate_type, aggregate_id)` independently of domain FKs.
It stores nullable server revision/canonical JSON, state (initially `unobserved`), local-generation watermark and cache
epoch. An unobserved reservation is local bookkeeping, not an observed server record or deletion. The public DAO is
read-only; internal reservation updates touch only the watermark and never replace canonical fields.

Separate `sync_account_cache` holds incomplete-baseline, cache epoch, projection-dirty and materialized-epoch fields.
It is not Room/vault authorization. Keeping it separate from `sync_state` prevents the existing cursor replacement API
from resetting publication bookkeeping. No production caller establishes a complete baseline or activates publication.

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
and [bounded incremental pull](#bounded-incremental-pull) exist, but no shared push/pull engine, staging,
vault/Room journal or production publication activation exists.
The independent ledger is not itself a visible projection.

### Protected publication

R05c's inert `ProjectionStore` and focused `SyncProjectionDao` reuse Room v10 without entity/schema changes.
The app constructs no publisher.

- A required `ProjectionPublicationBarrier` acquires a short bound lease outside Room after its caller-owned pause/drain.
  There is no permissive default or live edit/timer implementation. Nested publication is rejected before acquisition;
  every returned lease closes once after the fenced transaction, including blocked/failing/cancelled paths.
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

Current canonical/physical bindings are not account-lifetime removed-child reservation storage. R08 still owns baseline,
complete snapshot/absence establishment; R09 owns durable transition authority, and R12a owns the real finite-edit/timer
barrier before any live publication or account activation. These components do not authorize those integrations.

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
retry policy. It reuses Room v10 without entity/schema changes; production constructs none of it.

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
The app constructs none of it. The caller must pair the API with the same authorized service/account/session as the
store and share one required `PushRunOwner` per physical database; the driver creates no owner, credentials or scope.

- One `withRun` lease spans the entire invocation. Each explicit head freezes durably, its defensive request is obtained,
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
exist; R07c still owns shared push/pull orchestration and tail/last-success integration, R08 complete bootstrap,
R09 the Room/session journal, R10 convergence and R12 live publication/UI reconciliation.

## Incremental checkpoint transactions

R07a extends the existing admitted `PushRun` with inert `readPullCheckpoint`, `checkPullCheckpoint` and
`applyNextPullBatch` operations over Room v10. There is no separate pull owner or lock: callers share the required
`PushRunOwner` per physical database, and push settlement/checkpoint admission share the run's short handling Mutex.
Production constructs none of it.

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
validates complete ordered pages and run progress/cycles; R07c must compose uninterrupted push-before-pull ownership,
block retained errors, and establish valid-tail/last-success behavior without nested driver admission. R08 establishes
complete baselines, R09 supplies durable authority, and R12a supplies live publication barriers. No HTTP,
expiry/bootstrap recovery, publication, last-success update or convergence claim is produced by this seam.

## Bounded incremental pull

`sync/engine/PullDeliveryLoop(api).pull(run, maxPages, pageLimit)` is an inert explicitly constructed HTTP pull driver.
It receives an **already admitted `PushRun`**, owns no store admission/mutex/owner, and can be composed within eventual
R07c's uninterrupted shared ownership. The caller must pair API/store with the same authorized service/account/session
and share one `PushRunOwner` per physical database. Production constructs none of it.

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

Shared push-before-pull policy, retained-rejection blocking and tail/last-success integration remain R07c; bootstrap,
durable authority, live edit/timer barriers, convergence and app activation remain their later roadmap gates.

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

`sync/session` implements unused storage and refresh boundaries:

### Vault

- Immutable validated records bind origin/account/positive generation, activation operation/phase, original full grant,
  and exact pending-refresh operation/body/key. Pending token matches the stored refresh token. Prepared/active are
  stored labels, not account-visibility permission.
- `SessionVault` load/CAS operates on a checked monotonically increasing revision. Whole grant and pending attempt
  replace together. Clearing writes a positive-revision encrypted tombstone, not unlink/reset-to-zero; stale CAS cannot
  reinstall over that tombstone. Corrupt/unsupported/missing-key state fails closed and remains preserved.
- Strict version-1 binary UTF-8 retains exact sensitive text/expiry values, rejects malformed/trailing data, and avoids
  arbitrary content caps. `EncryptedSessionVault` rereads committed storage under a mutex; there is no success cache or
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
baseline. **No real Room adapter is implemented**, and the app constructs none of this machinery.

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
vault CAS may commit after invalidation; dead handles cannot use its result. R09 must implement the durable authority
adapter/journal, generation changes, prepared promotion and two-store crash recovery; result application still rechecks
inside Room transactions. R12 barriers, complete bootstrap and engine convergence precede app activation. No engine,
sign-in/out orchestration, account UI, Google adapter, or scheduler is constructed by these classes.

## Legacy auth replay limitation

The [shared auth-expiry issue](../../shared-docs/docs/backend-contract/issues/auth-expiry-precision.md) records a resolved
new-grant precision defect but an **unrepaired legacy accepted-replay limitation**. Pre-fix encrypted accepted response
bytes/deadlines may remain incompatible with strict millisecond readers. A known still-current legacy refresh without
an uncertain pending predecessor can rotate freshly. An uncertain consumed-token retry must retain its original key/body.

Never round timestamps, rewrite accepted replay, invent a new uncertain key, blanket-revoke sessions, or infer account/
cache deletion. Strict malformed replay preserves the original pending pair; passing new-grant interoperability does
not prove legacy recovery. Carry this limitation into startup/recovery design and release review.

## Test infrastructure boundary

Use real in-memory Room for client persistence, narrow scripted doubles for client timing/errors, and isolated real HTTP
for interoperability; do not maintain a second server implementation. `:app:testRealBackend` exercises production sync/auth
adapters over test TLS to the disposable service. Guarded setup bypasses external Google only to create isolated test
credentials, not service authorization. Legacy JVM JDK helpers remain test-only. Procedures and evidence are in
[testing strategy](../testing-strategy.md); real HTTP alone does not prove Room/engine convergence or real Google sign-in.
