# Sync storage

The storage implementation is under
[data/sync/store](../../app/src/main/java/com/dragosfotache/gymtracka/data/sync/store/), with entities, DAOs and migrations
under [data/gym](../../app/src/main/java/com/dragosfotache/gymtracka/data/gym/).
The [architecture](../architecture.md#room-database-and-migrations) lists the complete migration chain.

## Tables and ownership

| Storage | Purpose |
|---|---|
| Domain roots/children/relationships | Visible exercises, workouts, routines and ordered contents |
| `sync_outbox` | Ordered exact durable requests, affected-root generations, frozen identity, retry/error bookkeeping |
| `sync_state` | Selected-account pull cursor and nullable recorded last-success |
| `sync_device_state` | Durable session generation and active/cached account state |
| `catalogue_state` | Catalogue bookkeeping |
| `sync_canonical_roots` | Independent account/family/root canonical observations and local-generation watermarks |
| `sync_account_cache` | Complete-baseline flag, cache epoch, dirty state and materialized epoch |
| `sync_snapshot_stages`, `sync_snapshot_records`, `sync_snapshot_tokens` | Frozen snapshot source, whole pages and continuation evidence |
| `sync_session_authority` | Singleton revision/phase/origin/account/activation-operation journal |
| `sync_legacy_import`, `sync_legacy_import_entries` | Immutable local source, target, original operation and exact ordered imports |
| `sync_legacy_import_progress`, `sync_legacy_import_acceptances` | Current authority lineage, consent and accepted canonical receipts |

Credential grants, pending refresh bodies, and superseded credential evidence are kept in the
[encrypted vault](auth-and-recovery.md#encrypted-vault), never these tables.

## Local mutation capture

`GymRepository` uses `SyncStore` / `SyncTransaction` to check its immutable session inside every scoped transaction.
Live account repositories additionally check process revocation. Domain edits, root generation/state changes,
independent generation watermarks, dirty cache bookkeeping, and applicable outbox rows commit together.

All 17 command kinds have models/codecs. Autosaves capture complete aggregates; semantic requests cover finishing,
starting from history/routines, saving as a routine, replacement, child deletion, and root deletion. No-op, invalid,
bundled, invisible, or stale operations create no upload work. Local compatibility edits create no canonical/outbox
work. Account root deletes retain tombstones and capture delete commands; local deletes hard-delete.

A changed account root uses checked `max(domain generation, ledger watermark) + 1`, including every affected root.
Watermarks survive physical removal. Only compatible never-sent tail autosaves coalesce. Semantic commands and frozen
predecessors retain their identities, bytes, order, affected generations, and planned child IDs.

The durable row reader validates supported versions and exact column/wrapper identity. Corrupt/unsupported requests
remain retained errors. It does not repair, normalize, discard, regenerate IDs, or silently reinterpret a stored request.
See [push delivery](engine.md#push-delivery) for freezing, replay and acknowledgment.

The outbox's `retry_cause` is scheduling metadata, separate from frozen request identity. `connection`
means a proven transport fault without a service hint; `unknown` includes service failures/hints and
all migrated deadlines. A confirmed reconnect may clear only the first head's connection delay in
its fenced claim transaction. It never rewrites payloads, resets admission history or changes ordering.

## Canonical records and revisions

`CanonicalStore` / `CanonicalTransaction` write the ledger keyed by
`(account_id, aggregate_type, aggregate_id)`, independently of domain foreign keys.
`unobserved` reservations are local watermarks, not server records or server deletions.

A complete input batch is owned before suspension and applied atomically under session/cache-epoch checks.
Older observations are no-ops. Newer observations replace the complete operation/body. Equal revisions must agree
on authoritative fields and operation; workout/routine convenience exercise names and unrepresented additive data
do not determine equality. Equal no-ops preserve stored JSON exactly. Unknown-root deletes retain revision evidence
without inventing visible rows.

Strict retained validation checks raw duplicate JSON members, represented scalar types, identity, body/state/revision
agreement, counters, and epochs. Family roles and known child parent/root bindings cannot change, including in
all-no-op batches. Family namespaces remain independent. Current known bindings do not reserve removed child IDs
for the lifetime of an account.

Canonical writes preserve watermarks, use the admitted cache epoch, and mark the projection dirty. They do not edit
visible domain data, advance the cursor by themselves, remove outbox work, or grant visibility.
Unresolved dependencies and intermediate active-workout conflicts can remain canonical until complete-graph validation
at publication. Cursor application and accepted-request acknowledgment compose canonical writes transactionally.

## Protected publication

`ProjectionStore` / `SyncProjectionDao` materialize a complete canonical account into the visible Room graph.
The live account runtime supplies a real request-bound publication barrier; there is no permissive default barrier.

- Any selected-account outbox row protects the entire visible graph, regardless of state, payload version, error, or
  parseability. Foreign-account rows do not block this account.
- Missing/incomplete baseline and selected durable snapshot stages prevent publication. A clean equal-epoch retry
  returns unchanged. A downloaded stage alone is still staging.
- Eligible publication validates complete references, catalogue lookups, family/parent identity, at most one active
  workout, and physical UUID ownership. Local, bundled, or foreign collisions refuse without overwrite/remapping.
  Other-scope references can also prevent removal of a custom exercise.
- Account-scoped graph replacement preserves exact IDs, order, nullable values, optional metadata, timestamps,
  configured rest, actual rest, and maximum generation watermarks. Custom roots update in place before relationships
  and dependent children; root replacement does not accidentally cascade retained data.
- Actual publication increments `cache_epoch`, sets `materialized_epoch` to that value, and clears dirty in the same
  transaction. Overflow fails. Blocked, unchanged, and failed paths do not advance the signal.
- Canonical bodies/revisions, outbox bytes, cursor/last-success, device identity, catalogue, and other accounts remain
  unchanged. Publication creates no upload work.

Lease/session/epoch/process/cancellation checks repeat after graph writes and final metadata SQL. Failure before
commit rolls back the whole graph. Failure or cancellation after a synchronous commit, including lease-close failure,
requires durable reread; an exception alone does not establish rollback.
The run adopts only a successfully returned publication epoch.

Before invalidating a running timer, the barrier claims and drains elapsed rest while the source row exists.
The newly captured write then protects the graph at the outbox recheck. Reference-only replacement or rename preserves
a valid timer. See [timer lifecycle](../architecture.md#rest-timer-lifecycle).

## Frozen snapshot storage

One stage per account binds the originating session/generation/epoch, fixed page limit, complete original cursor/
last-success/baseline observation, frozen baseline, exact continuations, phase, and checked page/record counters.
Records retain whole canonical bodies with page/position order; continuation evidence enforces progress.
Child foreign keys cascade only from their stage.

Beginning requires no selected stage and no selected-account outbox rows. Complete pages append atomically under
exact capability/header/source fences. Root keys cannot repeat across pages; baseline and page limit stay fixed.
Nonterminal pages are nonempty, continuations are new by exact equality, and terminal continuation is null.
Cross-page dependencies may stage before complete-graph validation.

Retained reads validate actual counts, consecutive positions, original bodies and every continuation. Corrupt counters
do not become allocation/iteration sizes. Reads after restart or uncertain writes are observation-only; they cannot
resume append authority. Recovery explicitly abandons the exact valid stage and starts a new UUID/null-token request.
Invalid/foreign tokens and cancellation never imply automatic cleanup. Corrupt stages remain preserved.

A selected durable stage blocks ordinary push, incremental checkpointing, tail recording, and direct publication.
New local capture is still allowed and remains protected. Complete-baseline establishment validates the entire frozen
graph, installs records at checked epoch+1, records absence for previously observed missing roots without inventing
delete revisions, preserves watermarks, installs the baseline cursor, marks dirty, and removes the stage atomically.
It preserves last-success, visible domain data, and newer outbox intent. Actual terminal driver issuance is required;
a stored downloaded label alone cannot establish a baseline.

## Preserved local import

[LegacyImportStore](../../app/src/main/java/com/dragosfotache/gymtracka/data/sync/legacy/LegacyImportStore.kt) captures
an existing local-only graph for explicit sign-in. The production factory supplies `LegacyImportPolicy`; startup
recovery never captures or delivers imports. Calls without that policy still refuse local roots.

One held transition/drain lease covers capture. Source evidence retains ordered roots, children and relationships,
including exact SQLite NULL/INTEGER/FLOAT/TEXT/BLOB representations. Valid source is associated transactionally and
translated into create-only imports: customs, routines, finished workouts, then active workout. IDs, metadata,
nullable values, draft state, and original inputs survive. Invalid/foreign/unknown source stays private with retained
error/raw evidence; it is not coerced, skipped, renamed, or deleted.

Immutable source/target/original-operation/requests are separate from current authority lineage and receipts.
Once evidence exists, ordinary null-account reads/writes refuse. Each definitive accepted/original-accepted outcome
must match its original request and full business data. Canonical application, receipt insertion, and exact frozen-head
removal commit together. Queue absence or duplicate rejection is not acceptance.

Original active import needs confirmation bound durably to its exact lineage/mutation before delivery. Cancelled,
false, or stale consent sends no imports. Partial-prefix recovery preserves receipts and original suffix bytes.
Same-account reauthentication may advance current lineage without rewriting original provenance, consent or receipts.
Once all original receipts exist, legitimate later publication/edits need not match the original live graph forever.

Capture, acknowledgment, snapshots and activation revalidate complete evidence, including after final SQL.
Source, requests, receipts, and encrypted superseded credentials have no automatic cleanup.
No import evidence has a domain/outbox cascade that could erase it as visible rows change.
