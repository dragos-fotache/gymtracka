# Implemented Android sync components

These components exist, but the production app remains signed-out/local-only. **Implemented is not activated.**
No running engine, canonical publication, staged bootstrap, durable account transition, Google UI, or scheduler is wired
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
from resetting publication bookkeeping. No caller marks a real baseline complete or publishes remote data yet.

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

Canonical storage exists, but no remote record/deletion writer, revision arbitration, batch application, projection,
claim/ack engine, staging, or vault/Room journal exists yet. Global physical Room primary-key collisions must be refused
at future publication, never overwritten or remapped.

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
  Auth/error boundaries additionally reject unquoted scalars, literal string controls, and numeric overflow becoming a
  string. Do not assume every old generic raw route has those additional auth/error guards.
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
