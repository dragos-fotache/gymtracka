# Future improvements

This file contains Android features that are not implemented, unresolved issues, and remaining verification work.
Current behavior is described in [architecture](architecture.md) and the [sync reference](sync/README.md).
Shared product/API requirements remain in [shared-docs](../shared-docs/docs/backend-contract/README.md);
items below do not change that contract.

## Settings verification and connectivity follow-up

The Settings layout and advisory server connectivity are implemented. The Server check confirms only reachability
from this device, which may depend on Tailscale; it is not a service/database/account health guarantee. A dedicated
health endpoint could later replace the narrow unauthenticated pull-route probe without changing the state holder.

The [Settings plan](implementation/settings-tab.md) records account/recovery presentation and interrupted sign-in
recovery, including actual verification. Remaining device/provider coverage is tracked under
[broader integration and device verification](#broader-integration-and-device-verification).

## Background synchronization

Current synchronization is composition-owned and runs through foreground, committed-edit, and manual demand.
There is no WorkManager dependency, worker, periodic scheduling, or guarantee of delivery after the app stops.

Add unique account/session-scoped work with network constraints, bounded retries, foreground/edit/sign-in wakeups,
and periodic recovery. Reuse the existing run owner, stores, transport and session coordinator; a worker must never
launch Google UI or become a second sync client. Cover stopping/restart, token expiry, lost wakeups, duplicate
ownership and permanent-error suspension. Account deletion/sign-out will also need cancellation hooks.

## Account lifecycle and data choices

The service-auth logout transport and private recovery machinery exist, but general lifecycle orchestration/UI is absent.

| Capability | Current boundary | Needed behavior and dependencies |
|---|---|---|
| Sign-out | No user-facing sign-out or durable app-level orchestration | Local privacy lock even offline; preserve private cache/outbox and settle edit/timer barriers; account for every retained credential record |
| Account switching | The app admits one retained service UUID/origin and refuses foreign grants | Isolate A/B/local data, retire old handles/jobs and prevent late A callbacks from affecting B; never copy data across owners |
| Signed-out local use | Local repository compatibility paths exist; default startup requires account access | Explicit product flow and scope-safe repository/navigation construction |
| General import/replace choice | One-time preserved local import is connected to explicit sign-in | Broader choice UI, collision handling and crash recovery; preserve IDs/create-only dependency order; destructive replacement requires complete successful cloud publication before deleting only the confirmed local graph |
| Fresh lifecycle proofs | Four challenge purposes and auth codecs exist; foreground orchestration obtains SIGN_IN | Purpose/account-bound export, deletion and status proof handoff, without implicit signup or substituting a sign-in grant |

Use the [account lifecycle](../shared-docs/docs/backend-contract/account-lifecycle.md) and
[authentication](../shared-docs/docs/backend-contract/authentication.md) contracts for purpose, freshness, identity,
offline sign-out and destructive-action semantics. General auth, offline, or 404 errors cannot authorize cache deletion.

## Cloud JSON export

No Android export model/adapter, export UI, or document-save flow is connected. The HTTP primitive supports streaming,
but that alone does not implement export.

Add fresh same-account proof, complete versioned data validation, and Android document output preserving IDs,
nulls, and relationships. Explain that export contains cloud data and can omit unsynced/device-only changes.
Exclude credentials and sync internals; clean partial output on failure/cancellation. Wrong account, unsupported
version, truncation, or expired proof cannot succeed. Retry with fresh proof. Export restore is not part of this feature.

Dependencies: lifecycle proof orchestration, lifecycle models/HTTP adapter, account presentation, document-output
tests, real provider proof and save journeys. See the shared
[account lifecycle contract](../shared-docs/docs/backend-contract/account-lifecycle.md).

## Account deletion and status recovery

There is no account deletion/status adapter, durable deletion-recovery journal, or user-facing deletion flow.
Add optional export, explicit confirmation, fresh same-account proof, and accurate immediate-disablement versus
later-purge wording.

An uncertain request must retain its identity across restart and resolve through authoritative status/receipt.
Only verified deletion can clear that account's cache/outbox/cursor/stages/credentials/jobs. Preserve local data,
other accounts, and superseded credential evidence until its correct disposition is established.
Late results for A cannot clear B; a later signup for the same Google identity has a different service UUID.
No status/export/delete request may implicitly create an account.

Dependencies: lifecycle proof/model/HTTP support, export, account transitions, restart-safe uncertain request handling,
and server-owned retention/purge/backups behavior from the shared contract.

## Known recovery issues

### Legacy auth replay compatibility

Some retained accepted auth responses contain timestamps incompatible with the strict lossless-millisecond wire rule.
New issuance compatibility does not make those original bytes readable. A still-current credential without an uncertain
pending predecessor may rotate freshly; an uncertain consumed-token attempt must keep its original body/key.

The client already supports explicit same-account fresh-session supersession while retaining/fencing original
encrypted evidence. This does not settle or repair the original replay. Further rollout/recovery work must preserve
that distinction; rounding deadlines, changing uncertain keys, blanket revocation, and automatic account/cache cleanup
are invalid workarounds. The [shared compatibility issue](../shared-docs/docs/backend-contract/issues/auth-expiry-precision.md)
owns the cross-project constraint.

### Intermittent private or paused recovery

The cause of intermittent privacy-gated or delivery-paused states during real-service reconciliation/reconnect is
not established. Reproduce with disposable data and correlate the exact handle, authority, delivery stop, lifecycle,
and network transition using redacted diagnostics. Distinguish expected bounded retry/reauthentication stops from
incorrect authority retirement. Do not infer a targeted fix from unrelated passing journeys.

The October 31 phone/server investigation found successful fresh sign-ins before queued uploads
were accepted, plus a Tailscale connection refusal; the original client delivery stop was not
retained. `ForegroundRecoveryRelayTest` now reproduces one candidate with real file-backed Room,
vaults, startup UI and an isolated service: injected upload failures build a five-minute backoff,
reachability recovers, and repeated successful reauthentication leaves Finish signing in paused
until the unchanged upload deadline. At equality, activation completes and independent service
readback finds the upload. The control retains ACTIVE offline access and recovers without
reauthentication. Preserve the distinction between this demonstrated mechanism and the unknown
original cause. Deferred-upload activation now retains its retry deadline, shows an explanatory
countdown and resumes automatically while foregrounded, as does ACTIVE sync. Other bounded activation
stops still collapse into generic preparation messages; distinguish those safely in future work.

Confirmed reconnection now resets a proven connection-failure head's local retry delay and requests
immediate delivery or saved activation. Initial/repeated Online checks and server-requested waits
do not reset it. Older deadlines migrate as unknown and remain protected because their original
cause was not recorded. This does not establish the cause of the original October 31 failure.

During reconnect verification, the existing `foregroundEditReconnectExactReplay` relay journey
passed in earlier full runs but later failed during initial activation: its first snapshot returned
HTTP 503, before the lost-response step or a reconnect reset. The targeted rerun reproduced that
setup response. Safe phase/path/status diagnostics are now included in its activation assertion;
the disposable service/relay failure still needs diagnosis. Reconnect scenarios, retry storage,
controller and migration checks passed separately.

## Avoid redundant publication drains

After a valid pull tail, `AppSessionCoordinator` calls the paired publisher even when the delivery result already
reports outstanding local work. `ProjectionStore.publish` acquires the edit/timer barrier before its transactional
outbox guard returns `PROTECTED`.

Consider returning protected before that acquisition when the actual tail already proves outstanding intent,
preserving the final transactional guard for races on clean tails. Verify that newer edits and the running timer
remain intact and that no drain is acquired on the already-protected path. The current implementation preserves
the graph but does not have this early-return optimization.

## Broader integration and device verification

Existing deterministic, Room, Compose, and isolated real-service suites cover the implemented paths described in
[testing](testing-strategy.md). Remaining areas include:

- Broader all-family conflicts, server commit order versus reversed device clocks, dependency/name changes, concurrent
  requests, original-result replay, account isolation, and service retention behavior across independent stores.
- Full minimum-API application regression and broader Android/OEM coverage; focused API-specific tests alone do not
  establish whole-app compatibility.
- Real process death during app/session/snapshot recovery. File reopen and coroutine cancellation do not reproduce
  every process-kill boundary.
- Real provider cancellation, repeat same-account reauthentication, installed signing/audience variations, and
  purpose-bound lifecycle proof journeys as those features become available.
- Independent fresh-cache cloud readback after offline edits and reconnect; cached histories and zero pending alone
  do not independently verify complete cloud content.
- Screen-off timer, wake-lock/vibration behavior, phone network changes, service restart, and long offline/expiry
  recovery on real devices. Add unattended journeys after background scheduling exists.
- Actual hosted CI execution and its API 35 emulator, plus release-signing/distribution, APK/configuration/notice
  packaging, and OEM/cloud/device-transfer backup behavior.
- Cloud export/deletion/status/new-signup journeys and old-account isolation once their Android flows exist.

## Operational readiness

The repository supplies a configurable client and a disposable test-service launcher. It does not provide an always-on
production host, deployment automation, or high availability. Personal runtime addresses, accounts, keys and device
state are not project configuration.

Practical service availability, LAN/away connectivity, host/network restart recovery, secure off-machine keys,
ongoing backups, and tested restore procedures need an operational setup outside this Android codebase.
Public distribution also needs release configuration and accurate privacy/retention/deletion disclosures backed by
the service's actual policies. Local test success alone does not establish those properties.

## Optional product enhancements

- Expose the packaged third-party catalogue notices in the app.
- Consider countdown restoration after process death. Current timers and navigation are in memory; reconstruction
  would need explicit persistence/elapsed-time semantics while preserving at-most-once recorded rest.
- Consider broader custom-exercise editing for catalogue metadata, exercise types, multiple equipment items and
  quantities. The current form creates reps exercises and exposes a narrower subset of stored metadata.
- Finished-history editing, export restore, richer statistics and catalogue update delivery would need separate
  product decisions and corresponding persistence/sync behavior; none is implemented by the current flows.
