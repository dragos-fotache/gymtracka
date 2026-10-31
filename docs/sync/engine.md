# Sync engine

The [engine package](../../app/src/main/java/com/dragosfotache/gymtracka/data/sync/engine/) composes Room storage
with `GymSyncApi`. The app-facing entry is
[AppSessionCoordinator](../../app/src/main/java/com/dragosfotache/gymtracka/data/sync/session/AppSessionCoordinator.kt);
[AccountSyncState](../../app/src/main/java/com/dragosfotache/gymtracka/ui/account/AccountSyncState.kt) supplies
foreground demand and recovery presentation.

## Ownership and admission

One application-owned `PushRunOwner` is shared by all stores/coordinators for a physical database. Its mutex holds
one complete run across push, pull, snapshot, and publication, including HTTP outside Room transactions.
It is process-local; there is no multiprocess lease.

A `PushRun` binds account/session/cache epoch and live ownership. Short transactional operations also serialize
claim/checkpoint/stage handling. Nested admission and caller-owned enclosing transactions refuse. Structured children
settle before ownership releases. Closed capabilities and stale callbacks cannot affect a later run.

Write capabilities are conservatively retired before write attempts. Rollback or uncertain return does not make them
reusable. Recovery rereads durable state under fresh admission. Earlier committed acknowledgments/pages survive later
failure or cancellation.

## Push delivery

`PushDeliveryLoop` admits a run, or reuses an already admitted one, and processes one explicit head at a time:

1. Select the lowest selected-account sequence without filtering around rejection, corruption, retained errors,
   unsupported versions, retry time, or attempt exhaustion. A blocked head blocks successors.
2. Validate pending/frozen identity, freeze it durably, and increment the checked admission count once.
   This counts claim admission rather than proven transmission.
3. Recheck the exact claim and caller cancellation, then send its original request outside Room.
4. Own and validate the complete outcome, classify it, and atomically acknowledge a definitive accepted result.
5. Continue only after a returned acknowledgment, within a 1–100 mutation budget (default 100).

Frozen requests retain exact JSON text, mutation/effect/child IDs, order, and generation bindings across retry/reopen.
Claim checks compare every frozen field. Newer local edits do not rewrite the head. Cancellation never makes frozen
work coalescible. Budget exhaustion returns `LimitReached` without probing another head; `Empty` requires an actual
fenced empty selection.

### Outcomes and retries

Exactly one result must match the single claimed mutation. Accepted and duplicate-accepted outcomes carry the full
canonical batch, including additional server effects. The envelope target must be DELETE for aggregate deletion and
UPSERT otherwise. Classification alone applies/removes nothing.

Definitive rejection persists the exact service code with fixed safe context and blocks the head.
Malformed results retain frozen identity with a safe error. Transient failure retains frozen bytes and stores an
absolute retry time. Delay is the greater of exponential 1-second-base backoff capped at five minutes and the bounded
service hint; no jitter is added. Time overflow retains an error rather than wrapping. The driver does not sleep,
retry within that invocation, bypass successors, or clean credentials/cache.

Schema 15 records `retry_cause=connection` only for an actual transport I/O fault without a service
retry hint. Other failures and migrated deadlines use `unknown` and cannot be shortened by reconnect.
A freshly confirmed Unreachable→Connected event can authorize the admitted run's first head selection
to clear a connection delay during its normal freeze/claim transaction. State, wrapper, stage, account,
epoch and process fences still apply. The exact bytes/IDs/order are unchanged and admission count
continues increasing. The event is consumed by that first selection, including a blocked/empty one;
it never applies to a successor or a newly failed retry in the same run.

Access-authentication and foreground-reauthentication stops preserve work. The supplied credential decorator owns
its one unchanged access-401 retry; the push driver does not add another. Unknown codes remain queryable but are
excluded from diagnostic displays.

### Atomic acknowledgment

`PushRun.acknowledgeAccepted` revalidates the originating account, exact durable request, complete outcome, affected
generation reservations, current run, and head inside one transaction. It applies the complete canonical batch,
settles eligible affected metadata, records any original-import receipt, and removes exactly that head together.

Only roots whose domain generation and independent watermark both equal the frozen effect become synced.
Newer generations, business fields, children, tombstones, and tail requests remain protected. Old accepted duplicates
can remove their exact request without restoring an older canonical winner. Corrupt acknowledgment context retains
the head with a safe error and does not apply partial canonical changes. Successful acknowledgment marks projection
dirty; it does not publish visible rows, establish a baseline, advance the cursor, or record last-success.

## Incremental pull and tail recording

`PullDeliveryLoop.pull(run)` requires an already admitted run. It reads a complete-baseline, nonnull-cursor checkpoint;
missing eligibility requests snapshot recovery without issuing a null-cursor incremental bootstrap.
A present empty cursor or invalid last-success storage fails closed. Cursors are opaque and compared exactly.

Each request runs outside Room after checkpoint checks. The entire response is owned and validated before the next
suspension, including empty terminal/error responses. Pages must have coherent counts, boundaries, ordering and
progress; repeated IDs/cursors reject. Complete canonical batches and their next cursor commit atomically and
sequentially, with exact full-row/baseline/session/epoch checks. A later failure preserves earlier commits.

| Bound | Value |
|---|---|
| Pull page budget | 1–100, default 100 |
| Pull page limit | 1–200, default 100 |
| Snapshot page budget per driver call | 1–100 |
| Snapshot fixed page limit | 1–500, default 200 |

`SyncDeliveryLoop` holds one run uninterrupted through push, pull, and tail bookkeeping. Only an actual push
`Empty` permits pull. Push budget/error/deferred/rejection stops never probe or bypass the head.

Only the actual validated terminal-page callback records `last_success_millis` against its exact chained checkpoint.
The supplied nonnegative wall-clock value is stored unchanged, including equal/backward time; it does not order
conflicts or tokens. The transaction rechecks the complete sync-state row, baseline, fences, and selected outbox presence.
Standalone pull never records last-success. A valid tail may coexist with newer outstanding edits; nullable
`hasOutstandingLocalWork` is unknown until a successful tail transaction, rather than assumed clean.

Authentic cursor expiry requests snapshot recovery. Invalid, foreign, or wrong-context tokens remain failures.
The driver adds no automatic token repair, credential cleanup, delay loop, or publication.

## Snapshot bootstrap and full resync

`SnapshotDeliveryLoop.start(run, stageId, ...)` requires a real push `Empty`, then begins an empty-outbox stage and
fetches whole pages with one frozen baseline and exact continuation chain.
Typed snapshot expiry requests restart; other failures keep their category. Earlier stored pages survive failure.

Only the same driver's same-run `LimitReached` capability can continue download. Bounded chunks can continue
without a permanent account-size cap. A retained stage after restart is observation-only: explicit restart validates
the selected stage, abandons it, and uses a new UUID and null initial token. Corrupt stages remain retained.
There is no implicit cross-run append/resume or cleanup.

`complete` consumes actual terminal issuance from that driver/run and atomically validates/installs the complete
canonical baseline, absence, cursor, and stage removal. A stored downloaded label or constructible progress summary
cannot authorize completion. Snapshot completion does not publish the graph or record last-success.
See [snapshot storage](storage.md#frozen-snapshot-storage) for transaction details.

## ACTIVE reconciliation

`AppSessionCoordinator.reconcile(session, expectedCacheEpoch, ...)` checks the exact live handle and holds facade/run
ownership through push/pull, typed snapshot recovery, an actual post-baseline tail, and paired guarded publication.

- Missing baseline/cursor, authentic expiry, or an explicit matching retained-stage restart can enter snapshots.
  Restart UUID validation precedes abandonment. Invalid/foreign tokens cannot trigger bootstrap.
- After a real tail, reconciliation invokes the paired publisher, which acquires its edit/timer barrier before the
  transactional outbox check. Outstanding intent, including newly captured rest, returns `PROTECTED` and preserves
  the visible graph. There is no early skip based on the tail's outstanding-work field.
- Published/unchanged valid materialization returns `TAIL`. A retained stage returns `RETAINED_STAGE`.
  Budget, auth, retained-error, and backoff stops remain bounded; results do not cause an in-invocation retry loop.
- A healthy handle with an obsolete expected epoch reports unavailable. Actual process/session/authority mismatch
  remains stale. `observeCacheEpoch` provides fresh read-only progress after uncertain returns; it grants no authority.

The narrower `reconnect` API performs bounded push/pull without ACTIVE publication or automatic snapshot/stage restart.
The app's foreground controller uses reconciliation.

## Foreground and edit triggers

`AccountSyncState` has one nonqueueing operation slot with owned jobs and generation fencing. It follows the exact
ACTIVE handle, foreground lifecycle, and successful finite-edit counter. Edit demand uses a 500 ms debounce.
First-counter readiness plus raw/covered watermarks prevent replaying the initial backlog while retaining
newer commits. Foreground/activation demand survives busy retirement. Same-handle status emissions can repair missing
edit observation without resetting coverage.

The account root separately retains a Deferred upload's absolute retry deadline from the completed
result. `AccountRetry` shows the remaining wait and issues one new controller action for that exact
account/deadline when due and STARTED. The action rereads the epoch, authority and durable head as
usual; the countdown never bypasses backoff. Private activation uses the same scheduling path to
resume its saved sign-in after a deferred upload. Backgrounding suspends scheduling, turnover and
disposal cancel the old wait, and private Cancel disarms it. Pull, snapshot, rejection, authentication
and protected-publication stops do not acquire automatic retry timers.

Settings additionally gates manual network controls on advisory Connected/authentication/operation state. The
foreground/edit controller deliberately does not consume cached connectivity: fresh foreground/edit demand still
reaches normal engine admission after an earlier failed check. Checking never drains editors or claims terminal rest.
Retryable delivery stops (including push backoff) and unavailable sync/recovery operations ask the root to refresh
reachability while Settings is active. This is a separate credential-free probe. Only an actual
Unreachable result followed by a fresh Connected result emits a reconnection; initial/repeated Online,
Checking, unchecked and service-error observations do not manufacture one. The root binds this event
to the current handle/reservation and requests one fresh operation, coalescing through busy ACTIVE work.
Checks also appear during deferred private activation, so a foreground return or Check can resume it
without another Google sign-in. Only a fresh check updates the observation; an earlier probe is retired first.

Manual sync and recovery controls refuse while busy/private. START/STOP/DESTROY and disposal retire owned work without
cancelling the borrowed scope. Handle replacement cancels old observers, demand and confirmations. Late results cannot
replace new-handle presentation. New demand can schedule a fresh run; a result/error alone does not spin around
protected publication, rejection, retained stages, or backoff.

## Confirmed ordinary recovery

The Settings Account section can explicitly restart a retained snapshot stage or Resolve rejected changes.
Read-only selected-account status advertises ordinary recovery only for validated durable definitive rejected work;
imports, transient/frozen errors, malformed evidence, pending-only and clean queues do not advertise it. This is advisory
presence, not a promise that the entire remainder is recoverable: stage, legacy, authority and ordered-row checks still
run on every press. It neither admits a run nor drains an editor/timer or makes HTTP requests.

The controller uses `prepareOrdinaryRecoveryOutcome`: Prepared carries the opaque confirmation, NothingEligible is
informational, Blocked preserves retained work, and Failed/closed exceptions report a real preparation failure. An empty
or valid pending-only queue does not become Sync unavailable. The nullable compatibility adapter
`prepareOrdinaryRecovery` uses the same implementation. Frozen settlement that needs foreground authentication keeps
exact work and reports reauthentication beside its account action.

Preparation settles an already frozen head unchanged without sending pending successors, then preflights
stage, ready authority, epoch, full ordinary remainder, and absent/settled original-import proof before draining UI.
Ineligible preparation has no timer/editor drain.

An eligible drain is followed by fresh capture of exact ordered rows and a single-use owner/handle/process-bound
confirmation token. Confirmation drains again, compares all captured rows/evidence, and discards only that ordinary
remainder in one fenced transaction. It clears the cursor, preserves last-success, and marks projection dirty before
normal snapshot/tail recovery. Newly captured rest or edits invalidate stale confirmation.
Original import source, requests, progress and receipts are never ordinary-discard targets.

The UI retains the exact locally displayed token/stage for confirmation, retires it on turnover, and disarms a completed
or cancelled preparation that issued no fresh token. Preparation errors remain beside Resolve rejected changes;
confirmation failures explain how to retry without claiming a committed discard was rolled back. A later successful
sync clears superseded sync/recovery failures, while unresolved independent account errors and unknown metadata remain.

Cancel/dismiss performs no discard. No control promises rollback of a committed server prefix, treats generic auth
failure as deletion, or clears uncertain credentials.
