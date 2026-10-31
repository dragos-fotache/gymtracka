# Authentication and account recovery

[AccountStartupApp](../../app/src/main/java/com/dragosfotache/gymtracka/ui/account/AccountStartupApp.kt) constructs
the production runtime from explicit build configuration. Session machinery is in
[data/sync/session](../../app/src/main/java/com/dragosfotache/gymtracka/data/sync/session/), Google SDK integration in
[data/sync/identity](../../app/src/main/java/com/dragosfotache/gymtracka/data/sync/identity/), and transport in
[data/sync/http](../../app/src/main/java/com/dragosfotache/gymtracka/data/sync/http/).

## Configuration and visibility

Service identity is the canonical HTTPS scheme, host, and effective port parsed from the original configuration.
Whitespace, user-info, nonroot paths, queries, fragments, and invalid authorities refuse; the app does not trim an
invalid value or discover another endpoint. Google Web/server audience is a separate nonsecret input.
See [build configuration](../../README.md#build-and-configuration).

`AppSessionCoordinator` begins privacy-locked and exposes a stable read-only state flow. Only fresh coherent ACTIVE
Room authority/device/cache plus matching ACTIVE vault evidence and a complete baseline admit `AppAccountSession`.
Stored labels, Google credentials, pending counts, progress objects, or old UI snapshots cannot grant visibility.
Foreign, corrupt, incomplete, mismatched, preparing, and transitional evidence remain private.

Matching established accounts reopen/edit offline without checking credential deadlines or calling HTTP.
Dirty cache and queued edits do not remove that eligibility. Missing audience disables foreground sign-in only.
Startup recovery does not automatically retry private bootstrap/import. Its narrow clean completion-gap path can
phase-promote the exact prepared vault record locally; other interrupted states remain private for explicit recovery.

The account runtime synchronously selects the current handle and binds its repository, shared finite edits, and
barriers to every feature. Process revocation fences reads/writes even if durable generation has not changed.
Status-only updates preserve the same scoped app. A handle/authority fault hides that exact scope; recoverable delivery
errors preserve healthy offline editing. Observation is advisory and cannot rebind a repository to another account.

## Settings presentation

Admitted account controls live in the Settings tab's Sync and Account sections. The root passes
presentation/actions into the shell while retaining the exact-handle runtime and startup/sync controllers. Navigating
to another tab does not cancel their owned account operations or retire the account scope. The Connection check alone
cancels on leaving/backgrounding. Its credential-free expected 401 establishes reachability without changing delivery
status, refreshing credentials or opening Google UI.

Settings enables Reauthenticate only when Connected, sign-in is configured and neither startup/account nor sync work
is busy. A known foreground-authentication requirement (delivery status or sync-controller error) disables manual sync
and recovery while leaving Reauthenticate available when otherwise eligible. Ordinary refreshable access expiry still
uses the existing refresh path. Checking/unreachable/unconfirmed service status disables manual account network actions;
cancellation remains available.

`AccountStatusPresentation` derives one current explanation per action. Account shows Signed in above Reauthenticate
and its account icon, followed by conditional authentication messages. Failed authentication, required reauthentication,
wrong account, an open explicit-save editor and cancellation remain distinct without replacing the admitted account's
signed-in status. Sync contains Pending changes, Last success and Sync now. Ordinary upload/download/retry failures show
Sync failed; a deferred upload instead shows the retry countdown supplied by the account root and
resumes at its deadline while foregrounded. Progress and other conditional messages remain beside Sync now. Retained-download and rejected
ordinary-work explanations sit beside their recovery controls rather than adding generic Delivery paused/Sync paused
messages. Pending count and recorded last-success remain independent metadata; an absent observation is unavailable,
and a null last-success is unknown. Historical success never establishes present connectivity.

All changes synced requires an observed empty queue, known last-success, a completed current sync result, idle
authentication/operations, and no retained error, stage, confirmation, unresolved recovery failure or incomplete sync stop. New work retires an earlier successful
stop, so interrupted work cannot reuse historical success. A later real successful sync clears a
superseded failed sign-in attempt; editor, identity and metadata failures remain until their own operation resolves.
Successful reauthentication clears its own prior error. Neither presentation nor advisory recovery eligibility grants
account authority.

Private interrupted activation offers **Finish signing in** to resume the saved activation after a fresh local
authority read. It acquires no Google credential or new service grant and keeps the reserved operation/generation;
normal prepared-engine refresh may still settle an exact retained refresh attempt. Missing sign-in audience does
not disable this saved-session action. Complete baseline, guarded publication and vault promotion still precede
visibility. The private gate scrolls so recovery controls remain reachable with enlarged text.

When an explicit activation stops on an upload's durable retry deadline, its Private result retains
that advisory deadline. The root shows Signed in with a countdown, disables repeated sign-in/continue
presses during the wait, and resumes the saved activation when due while its lifecycle is STARTED.
Backgrounding suspends the wait; foreground return rechecks the same wall clock used by delivery.
Cancel disarms the private countdown without clearing the saved session or queued work. A fresh
process still requires explicit Finish signing in; initial recovery never invents automatic activation.
The countdown grants no authority and does not shorten durable backoff or change frozen request bytes.

A connection check is also available while that deferred activation is waiting. A confirmed
Offline→Online transition resumes the same saved activation immediately, with permission to reset
only a proven connection-failure head's local delay. Server hints and unknown migrated delays remain
unchanged. Cancelled or replaced private operations cannot be resumed by the old check.

**Sign in** deliberately requests a fresh service session for the retained account. PREPARING/TRANSITIONING ordinary
recovery uses a fresh operation distinct from the saved reservation and `SameAccountRecoveryCoordinator.prepare`,
rather than passing new credentials into exact-session resume. Private import retains its stricter import-specific
reauthentication path. A foreign service UUID/origin, changed expectation or retired process request refuses without
adopting the grant. Queued edits, exact frozen mutation bytes and uncertain encrypted predecessor evidence survive.
After sign-in, the controller freshly recovers authority before its single activation attempt; the old observation
cannot authorize activation of the replacement session.

## Google credential acquisition

`AndroidGoogleIdentityProvider` is owned by one `ComponentActivity` and constructed on Android Main.
Credential Manager uses application context for its manager and the current Activity for interactive UI.

- Each explicit request uses `GetSignInWithGoogleOption` with the exact configured audience and service nonce.
  Blank/padded audience and blank nonce refuse before SDK launch. Admission requires a RESUMED, live Activity;
  provider UI may temporarily pause/stop it.
- Only the Google custom credential type is accepted, through the Google ID parser. The opaque nonblank token is
  returned unchanged. Decoded Google claims, email and display names never establish service account ownership.
- A nonqueueing slot covers acquisition and explicit credential-state clear. The default timeout is two minutes.
  Cancellation, Activity destruction, close, and late/queued delivery are fenced before acceptance.
- `close()` retires owned SDK work and the Activity reference without cancelling caller scopes.
  `clearCredentialState()` is an explicit SDK primitive, not service logout or cache deletion; cancellation/disposal
  never invokes it automatically.
- Diagnostics use fixed safe reasons and redact tokens, nonce, audience and provider details.

## Foreground exchange

`ForegroundSignInCoordinator` owns one nonqueueing invocation through final private grant acceptance.
The startup controller invokes it only for an explicit sign-in/reauthentication action:

1. Capture configured origin, caller intent, exact expected Room authority/operation, and immutable facade process fence.
2. Obtain one SIGN_IN service challenge and pass its nonce unchanged to Google.
3. Capture one immutable `GoogleExchangeAttempt`, including body and idempotency key.
4. Exchange it and consume the service-confirmed grant once through the guarded facade handoff.
5. Let the startup controller separately attempt complete activation.

The whole-invocation budget defaults to 180 seconds. Only transient exchange faults retry, for at most three sends
of the same attempt/key/body/token/challenge. Minimum delays are 1 and 2 seconds, extended by service retry hints.
No new challenge, Google credential, or idempotency key is invented for an uncertain exchange.

Challenge expiry is checked at Google and first-send admission, including queued work; equality is expired.
An already-sent uncertain exchange may replay its original bytes after challenge expiry within the budget.
Returned original grant/deadlines remain unchanged, including expired access; the refresh boundary owns renewal.
Challenge, Google proof and exchange attempt remain foreground-memory-only, outside saved state, Room, vault and logs.

Initial sign-in needs an exact empty signed-out expectation. Reauthentication needs the retained service UUID/origin
and a new operation for occupied ACTIVE, PREPARING or TRANSITIONING authority. Direct saved-grant resume still requires
the exact reserved operation and complete session equality; foreground sign-in never represents that retry.
Late/foreign/disposed requests cannot install a grant against newer authority. Google success and grant preparation
remain private until bootstrap/publication/completion and fresh account admission succeed.

## Room authority and two-store coordination

`RoomSessionAuthority` strictly reads the singleton journal, device tuple and selected cache in one transaction.
Extra rows, SQLite scalar coercion, invalid counters/UUIDs/origin, unknown phases, and inconsistent identity refuse.
Legacy device/cache labels alone do not imply account authority.

| Phase | Meaning |
|---|---|
| SIGNED_OUT | No active identity; retained provenance may still constrain recovery |
| TRANSITIONING | Private reservation for same-account replacement/recovery |
| PREPARING | Private account/grant preparation; no ordinary feature access |
| ACTIVE | Eligible only with coherent device/cache and matching vault evidence |

Preparation reserves a checked revision/generation and activation operation, clears active device authority, and
fences older repositories. Unassociated local roots refuse ordinary preparation; explicit import handles them under
the [preservation policy](storage.md#preserved-local-import).
Private fencing preserves data, outbox bytes, stages, canonical records and credentials; it is not logout or deletion.

`SessionPreparationCoordinator` reserves Room before installing one full original PREPARED grant by exact-revision
vault CAS. Initial preparation requires an empty/tombstoned vault. Resume reuses the exact reserved operation and
generation; matching prepared evidence permits idempotent reread rather than another reservation.
Alternating Room/vault rereads validate full grant and exact pending-request semantics. Room cannot reconstruct missing
credentials, and no observation claims atomicity across Room and the vault.

`PreparedSessionEngineCoordinator` freshly admits a registered private permit for the matching pair.
One shared database run owner spans bootstrap network/commits, complete baseline, valid pull tail, and protected
completion. Credential/network admission checks the permit separately; ordinary repositories/public publishers remain
ACTIVE-only. Retained stages require explicit restart; budget/error/private progress never grants visibility.

Completion publishes the coherent graph and writes exact Room ACTIVE/ready in the same transaction.
After known Room return and lease close, one phase-only CAS promotes the exact full vault PREPARED record to ACTIVE.
Full rereads then precede facade admission. A clean Room-ACTIVE/vault-PREPARED gap can be recovered without HTTP,
domain/journal writes, a new operation, or grant renewal. It cannot adopt mismatched, dirty, or incomplete evidence.

Already-started synchronous Room/CAS writes may commit despite cancellation or lost result delivery. Recovery uses
durable rereads; failure does not authorize compensation, cleanup, generation restart, or invented success.
Real transition/publication barriers drain accepted edits and terminal rest. Open explicit-save editors refuse normal
transitions rather than silently saving/discarding drafts.

## Encrypted vault

`SessionVault` stores origin/account/generation, activation operation/phase, the original complete grant, exact pending
refresh operation/key/body/token, and any superseded credential evidence. Whole records replace through checked,
monotonically increasing revision CAS.

- An empty-record clear writes an encrypted positive-revision tombstone rather than unlinking/resetting revision.
  Stale CAS cannot reinstall over it. Corrupt/unsupported/missing-key evidence remains preserved.
- Version-1 encoding represents records without superseded entries; version 2 adds flat retained full source records.
  Strict binary UTF-8 validation rejects malformed/trailing data without arbitrary content caps.
- `EncryptedSessionVault` rereads committed storage under a mutex; there is no success cache or implicit repair.
- Android uses nonexportable Keystore AES-256/GCM, provider-generated 12-byte IV and 128-bit authentication tag.
  Reads do not regenerate missing/wrong-kind keys.
- Ciphertext lives in credential-protected `noBackupFilesDir/gymtracka-session-vault`. File checks, checked 0600
  temporary writes, fsync, same-directory rename and directory fsync protect replacement. Load does not promote stale
  temporary files. Cloud, device-transfer and legacy backup XML also exclude the namespace; ordinary backup is enabled.
- Owned byte buffers are cleared best effort. This does not promise flash/GC erasure, hardware-backed storage,
  multiprocess coordination, or behavior of every OEM backup implementation.

## Refresh and exact retry

One shared `SessionRefreshCoordinator`/credential mutex and authorized `SessionRefreshLease` bind origin, account,
generation, operation and phase. Recovered leases additionally check fresh Room/process authority around credential
and network seams.

Pending refresh settles first with its exact retained body/key/token, even when access appears live or the consumed
predecessor refresh has expired. Otherwise one attempt is captured, committed as pending, and reloaded before HTTP.
The complete same-account returned grant and cleared pending attempt commit/reload together before use.

An accepted replay's original expired-access grant is persisted before optionally rotating its current refresh once.
Selection is bounded to pending replay plus at most one fresh rotation. Local clocks schedule renewal; they cannot
extend returned expiry values. `withAccessToken` runs transport outside the credential mutex and allows one unchanged
access-401 retry. Concurrent rejection can use a newer committed grant. Repeated 401/explicit reauthentication latches
foreground recovery without clearing credentials/cache.

`SessionRefreshingGymSyncApi` owns/validates frozen push wrappers before suspension. Each attempt uses one tagged bearer;
mutation bytes, IDs/order, read tokens and queries stay unchanged across the credential retry.
Cancellation precedes reauthentication latching. Dead leases cannot use late results or undo already-admitted bytes/CAS.

## Same-account reauthentication and retained evidence

`SessionReauthenticationCoordinator` handles settled occupied ACTIVE pairs. It requires exact same origin/service
account, complete baseline, and no pending predecessor before reserving TRANSITIONING at a new generation.
After exact old-vault/Room rereads it installs a full fresh PREPARED grant, then finalizes PREPARING at that reserved
generation. Existing domain/cache/outbox/cursor/stages stay intact. Resume reuses the reservation.

Its pending-refresh reconciliation prerequisite sends at most the exact retained old attempt under shared run then
credential ownership. It neither creates a fresh rotation nor retires uncertainty on failure.
Broader `SameAccountRecoveryCoordinator` handles retained signed-out/missing-vault and interrupted/uncertain same-account
recovery using fresh grants, known provenance, exact reservations, and the same preservation rules.

Fresh-session supersession atomically retains the exact uncertain source revision/full grant/deadlines/pending attempt
as `VaultSupersededSessions` while installing the new grant. Entries are immutable, ordered, same-origin/account,
non-nested, and excluded from selection/replay/rebinding. CAS refuses removal/modification/reordering or null clearing
of retained evidence. Ordinary refresh, completion, and recovery carry it forward without automatic pruning.
A missing/foreign/unreadable or incompatible same/newer binding stays private.

Certain retained accepted auth responses remain incompatible with strict lossless-millisecond readers.
The client preserves exact uncertainty and supports explicit same-account supersession; it does not round timestamps
or invent another retry key. The [known compatibility issue](../future-improvements.md#legacy-auth-replay-compatibility)
describes the unresolved boundary.

## HTTP and wire validation

`OkHttpServiceTransport` fixes the HTTPS origin and uses normal certificate/hostname verification.
Redirects, cookies, cache, logging, authenticators and automatic retries are disabled, including transparent GET
Retry-After replay; POST bodies are one-shot. Cancellation cancels the actual Call and late responses close.

Scoped JSON/streaming reads require complete strict UTF-8, correct media/framing and identity encoding.
There is no silent truncation or permanent aggregate-size cap. Streaming support alone is not cloud export.
`HttpGymSyncApi` binds origin/account/positive generation and validates complete push/pull/snapshot responses.
`HttpServiceAuthApi` supplies four-purpose challenges, exact exchange/refresh attempts, and verified empty-204 logout.
The logout primitive has no user-facing sign-out orchestration.

Raw JSON guards reject duplicate decoded member names before normalization. Durable requests preserve exact original
text, including whitespace/order/escapes/numeric spelling. Auth/error and retained-canonical boundaries additionally
enforce scalar fidelity and lossless wire-millisecond expiry; generic model routes do not all have identical lexical
guards. Expired original grants remain representable evidence.

Failure categories separate access authentication, foreground reauthentication, expired versus invalid/wrong-context
read tokens, oversized requests, unknown permanent rejection, malformed response, and transient faults.
Unknown codes remain exact; user-facing messages are fixed and sensitive displays redacted. Generic 401/404/offline
failures never establish account deletion or authorize data cleanup.
