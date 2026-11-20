# Account synchronization

The default application uses one configured service account. Google sign-in, private preparation, complete activation,
matching offline recovery, foreground/edit-triggered synchronization, manual sync, and confirmed ordinary recovery
are connected to the app. The [project README](../../README.md#build-and-configuration) describes build configuration.

## Application flow

1. `GymApp()` enters `AccountStartupApp`. Missing/invalid service origin displays a configuration gate without opening
   Room, the credential vault, or HTTP.
2. Configured startup recovers local authority. Only matching ACTIVE Room/vault evidence with a complete baseline
   permits the account UI. Credential expiry does not prevent established-account offline access.
3. Explicit sign-in obtains a service challenge, Google credential, and service-confirmed grant. Private preparation
   binds the service origin/account and retains any original local data through the import path.
4. Complete bootstrap, a valid pull tail, guarded projection publication, Room readiness, and vault promotion precede
   admission of the live account handle. Google success or an empty outbox alone does not grant visibility.
5. Once ACTIVE, a separate foreground controller serializes activation/foreground demand, debounced committed-edit
   demand, and manual sync through that exact handle. Healthy accounts remain editable after recoverable delivery stops.

Local recovery itself performs no HTTP or Google launch. A clean interrupted completion may finish the exact vault
phase promotion locally. Subsequent foreground synchronization is a separate operation. A valid service origin with
no Google audience still permits matching offline recovery; sign-in/reauthentication is unavailable.
Private activation offers Finish signing in for the saved session. Deliberate repeated sign-in instead obtains a
fresh same-account session and recovery reservation, preserving queued edits and uncertain credential evidence;
both paths remain private until complete activation. See [authentication and recovery](auth-and-recovery.md).

## Components

Paths are relative to `app/src/main/java/com/dragosfotache/gymtracka/`.

| Component | Role |
|---|---|
| `ui/account/AccountStartupApp.kt` | Production factory, lifecycle wiring, gates, Settings account content and import consent |
| `ui/account/AccountStartupState.kt` | Explicit sign-in/reauthentication/continue, private progress and status |
| `ui/account/AccountImportConsent.kt` | Invocation-bound active-import replacement confirmation |
| `ui/account/AccountSyncState.kt` | Foreground/manual/edit demand and retained-stage/ordinary recovery controls |
| `ui/AccountGymAppState.kt` | Exact-handle app root, real transition/publication barriers, runtime retirement |
| `data/gym/GymRepository.kt` | Transactional user edits and account-bound mutation capture |
| `data/sync/store/` | Session-fenced outbox, canonical batches, snapshots and visible projection |
| `data/sync/engine/` | Bounded push, incremental pull and frozen-snapshot drivers |
| `data/sync/session/` | App authority, Room/vault coordination, preparation, refresh and recovery |
| `data/sync/identity/` | Activity-owned Google Credential Manager adapter |
| `data/sync/http/` | Fixed-origin HTTPS transport and sync/auth adapters |
| `data/sync/model/`, `data/sync/codec/`, `data/sync/mapping/` | Validated aggregates, commands, exact durable wrappers and wire conversion |
| `data/sync/legacy/` | Preserved local source, exact import requests, receipts and consent |

## Data and authority boundaries

Room is the responsive UI data source. Server canonical records are stored independently of visible domain rows.
A complete validated baseline can be published only while real edit/timer barriers are held and no selected-account
outbox work remains. New intent protects the whole visible account graph until it can be delivered or explicitly
recovered. Each actual publication advances a materialized epoch that feature state holders observe.

One process-owned `PushRunOwner` serializes runs for the physical database, including network work outside transactions.
Room checks account, generation, cache epoch, and exact retained rows again at commit. Live app paths also check
process/handle validity. Credentials live in an encrypted vault outside Room; Room and vault have a recoverable
journal protocol rather than a cross-store transaction.

The Settings tab's Sync section contains pending/last-success metadata, Sync now and current sync/recovery messages.
Ordinary upload/download failures display Sync failed; deferred uploads show a countdown and retry
at their durable deadline while foregrounded. The Account section contains Signed in, Reauthenticate
as a bracketed text action, and conditional authentication messages. Settings uses shared outlined grid panels;
busy operations use thin progress bars and enabled/destructive states keep their established meanings.
Actionable authentication errors appear beside Reauthenticate; sync and recovery controls show their own current
explanation. Resolve rejected changes appears only for advisory validated definitive ordinary rejection, and preparation
rechecks eligibility before issuing a captured discard warning. A clean queue does not produce a recovery error.
Controller admission, foreground/edit demand, recovery eligibility and session ownership remain in the existing runtime.
The Connection section checks device-to-service reachability on entry/foreground return and explicit Check. It shows
only Online/Offline/Checking with a trailing green/red/yellow dot and the Check action. It enables
manual account network actions only when Connected and authentication/operation state permits. A successful probe
establishes neither account authentication nor successful synchronization; its expected unauthenticated 401 never
enters account authentication/refresh machinery. See [Settings lifecycle and transport](../architecture.md#ui-and-state-flow).
Automatic foreground/edit-triggered sync remains independent of this advisory observation.
Retryable sync failures request a fresh foreground Settings probe; unavailable operations do so as well. Checking and
Offline disable manual network actions. Authentication and permanent request errors leave reachability unchanged,
and an initial or repeated Online check only restores eligible actions. A confirmed Offline→Online
transition requests immediate sync, resetting only a proven connection-failure head's local backoff.
Explicit service hints and older delays with unknown provenance keep their deadlines.

Account status exposes selected pending count, nullable recorded last-success, retained-error presence, advisory
rejected-ordinary-work presence, and closed delivery/error states. Metadata observation is read-only; unknown status is never displayed as zero/success.
Last-success records a valid bounded pull tail and can coexist with newer outstanding edits. All changes synced requires
known empty metadata and no unresolved account/sync/recovery problem; last-success alone is insufficient.

## Detailed references

- [Storage](storage.md): local intent, generations, canonical arbitration, snapshots, import evidence and publication.
- [Engine](engine.md): exact push replay, bounded pull/snapshot processing, reconciliation and ordinary recovery.
- [Authentication and recovery](auth-and-recovery.md): Google exchange, vault, refresh, authority and activation.
- [Architecture](../architecture.md): feature state, finite edits, projection observation and timer coordination.
- [Testing](../testing-strategy.md): deterministic, Room, Compose and real-service test layers.
- [Future improvements](../future-improvements.md): absent capabilities, unresolved issues and verification gaps.

The [shared backend contract](../../shared-docs/docs/backend-contract/README.md) defines service behavior and wire
requirements. Its broader lifecycle requirements do not imply Android sign-out/switching, export, deletion, or
background scheduling is present.
