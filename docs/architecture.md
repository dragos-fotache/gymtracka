# Android architecture

Current implementation only; approved future integration belongs in [the design](sync/integration-design.md).
The [shared product document](../shared-docs/docs/product-behavior.md) owns user-visible behavior, and the
[shared contract](../shared-docs/docs/backend-contract/README.md) owns service requirements.

## Application structure

Single `:app` module: Kotlin, Jetpack Compose, Material 3, coroutines, and Room; min API 24, target API 37.
The app uses composition-scoped state holders, not Android ViewModel, a DI framework, or a navigation library.

Paths below are relative to `app/src/main/java/com/dragosfotache/gymtracka/`.

| Component | Responsibility |
|---|---|
| `MainActivity.kt` | Edge-to-edge/Compose/theme setup; calls `GymApp` |
| `ui/GymApp.kt` | Mandatory no-argument startup entry; explicit scoped scaffold, destinations, bottom navigation, active-workout pill and dialogs |
| `ui/account/` | Configuration/privacy gates, composition-scoped startup controller, foreground sign-in/reauthentication, import consent/progress and account status |
| `ui/GymAppState.kt` | Navigation/active-pill materialization reconciliation, catalogue restoration, real edit/timer barriers and rest timer |
| `ui/AccountGymAppState.kt` | Explicit account runtime/root, exact-handle repository binding, real import-policy barriers, privacy gate and lease-safe scope retirement |
| `ui/exercises/` | Catalogue/detail/custom editor, picker variants, feature state |
| `ui/workouts/` | Workout landing, history/detail, feature state |
| `ui/routines/` | Routine list/detail/editor and feature state |
| `ui/activeworkout/` | Active screen, compact exercise/set controls, timer UI and feature state |
| `ui/util/WorkoutFormatters.kt` | Shared derived calculations and locale-aware parsing/formatting |
| `data/gym/GymRepository.kt` | Application-facing persistence, UUID creation, transactional edits and sync capture |
| `data/sync/` | Fenced capture/canonical storage, protected publication, push/pull/snapshot drivers, HTTP/vault/session authority, explicit guarded ACTIVE reconciliation and inert foreground Google identity adapter; [details](sync/implementation.md) |

`FeatureRepositories.kt` exposes narrow interfaces to state holders; reusable/leaf composables receive values and callbacks.
`ui/GymRepositoryProvider.kt` supplies `rememberGymRepository()` and optional internal `LocalGymRepositoryOverride` for
real-Room tests and the account root. With no override, it remembers `GymRepository(applicationContext)` for explicit
standalone/local compatibility paths, not a production signed-out fallback. No-argument `GymApp()` calls
`ui/account/AccountStartupApp`: absent/invalid service origin renders configuration/privacy gates without database,
vault or HTTP reads; a configured origin constructs the runtime and performs read-only recovery. Missing audience
disables foreground sign-in only, not matching established-account offline recovery. The account root provides the exact live handle repository
and shared edits around every feature factory, with no local fallback. The
[startup recovery seams](sync/implementation.md#startup-recovery-seams) use the inert durable Room-authority
adapter. R09b's [prepared vault/Room coordinator](sync/implementation.md#prepared-vaultroom-coordination-and-recovery)
supplies inert private grant persistence/restart observations. R09c supplies registered private engine admission/bootstrap;
R09d supplies [inert completed preparation/promotion](sync/implementation.md#private-completed-preparation-and-vault-promotion).
`data/sync/session/AppSessionCoordinator` now composes these seams into an explicitly constructed
[app-facing authority/offline-recovery boundary](sync/implementation.md#app-facing-authority-and-offline-recovery), with
mandatory transition/publication inputs and guarded account repositories. `rememberAccountGymAppState` supplies the
real Android clock/hardware and composition scope; construction starts private and runs no HTTP automatically.
`GymApp(accountState)` selects only the synchronously current live handle, keys reconstruction by handle identity and
otherwise renders `account-privacy-gate`. Same-handle delivery status updates preserve navigation/timers. Retiring
state stays pinned while a transition/publication lease is held, even after privacy hide; whole-runtime disposal
revokes the facade and cancels owned child scopes. The default factory reuses one application-process vault/run owner,
with an Activity-owned [Google adapter](sync/implementation.md#foreground-android-google-identity) and
composition-owned runtime/controller. Sign-in is explicit; it consumes a service-confirmed grant, opts into private P18
capture/consent and makes at most one complete activation attempt. Matching complete Room/vault bindings reopen/edit
offline without HTTP or deadline checks. No-argument startup never renders the explicit local/test `GymApp(appState)`
path. See [startup/account presentation](sync/implementation.md#mandatory-startup-and-account-presentation) for lifecycle,
configuration and read-only metadata details; automatic foreground/edit/manual sync remains separate.

## Room database

`app/src/main/assets/databases/gym.db` is the unchanged **version-1 source catalogue**. Room copies it into app-private
storage and migrates the runtime database. Never add user-data tables to the asset or use destructive migration fallback.
Runtime `GymDatabase` is **version 14**; exported versions 1–14 are retained in `app/schemas/`.
Private delivery is opt-in at the facade boundary; the mandatory startup factory supplies the approved P18 policy
for explicit service-confirmed sign-in/activation. Startup recovery itself never captures or delivers imports.

### Catalogue and custom exercises

- `muscle_groups`, `equipment`, and `exercises` store catalogue definitions; `exercise_muscle_groups` and
  `exercise_equipment` store relationships, including muscle role and optional equipment quantity.
- The fixed catalogue has 899 exercises. Its version, import mapping, checksums, and parity tooling live in the
  [shared catalogue package](../shared-docs/catalogue/README.md). MIT/foundation notices and attribution are packaged
  under `app/src/main/assets/third_party_notices/`; no notices UI is implemented.
- Bundled rows are immutable. Custom edits and relationship replacement are transactional and guarded by
  `is_user_created`. Current creation uses type `reps`, at most one equipment row and primary muscle, and any number of
  secondary muscles. Existing imported metadata/quantity is preserved during editing.
- `ExerciseDao.getAllSummaries()` bulk-loads relationships rather than querying each row separately.

### Workout aggregate

- `workouts`: UUID, persisted name, start/optional finish milliseconds, active/finished status.
- `workout_exercises`: UUID, parent/catalogue reference, consecutive zero-based position, notes, configured nonnegative
  rest duration (default 30 seconds).
- `workout_sets`: UUID, parent, consecutive position, nullable repetitions/weight kilograms, completion, nullable actual
  elapsed rest seconds. Configured countdown and recorded elapsed rest are different fields.

Workout/entry deletion cascades to descendants and renumbers surviving siblings transactionally. Adding selected
exercises is atomic: copy completed set inputs and rest duration from each exercise's most recent finished history,
reset completion/recorded rest, or create one empty set with the default duration when history is absent.

Finishing requires valid completed work, removes unfinished sets and newly empty entries, renumbers surviving entries
and sets, and then marks the workout finished in one transaction. Finished history is immutable. Perform-again requires
an eligible finished source and creates new IDs. Exercise replacement changes only its catalogue reference, preserving
entry ID, notes, rest settings, sets, and timer association. History summaries use two fixed DAO queries; full details
load on demand. Volume/best-set display values are derived, not stored.

### Routine aggregate

`routines` stores UUID/name/draft state; `routine_exercises` stores parent/catalogue reference, position and configured
rest; `routine_sets` stores parent, position and nullable repetitions/weight. Routine sets have no completion/actual rest.
Drafts are excluded from saved lists. Add creates one empty set per selected exercise; deletion cascades and renumbers.
Promotion, start-from-routine, save-workout-as-routine, and complete cloning preserve transactional boundaries. Saved
blank/empty autosaves are distinct from save/start eligibility validation.

### Migration chain

| Migration | Addition |
|---|---|
| 1 → 2 | Workout tables |
| 2 → 3 | Non-null workout name, backfilled from local start hour |
| 3 → 4 | Workout-exercise notes |
| 4 → 5 | Nullable actual set rest |
| 5 → 6 | Configured workout-exercise rest, backfilled to 30 seconds |
| 6 → 7 | Routine tables |
| 7 → 8 | `exercises.is_user_created`, default false |
| 8 → 9 | Root account/revision/generation/state/deletion metadata and indexes; outbox, account cursor, device/session and catalogue state tables |
| 9 → 10 | Independent account/type/root canonical ledger and separate account publication bookkeeping; unobserved generation reservations backfilled without fabricating canonical records/baselines |
| 10 → 11 | Independent snapshot stage headers, whole records and durable continuation/page evidence; old tables/bytes/sequence unchanged, new tables empty |
| 11 → 12 | Independent singleton account-authority/preparation journal; prior tables/bytes/sequence unchanged, new table empty; no fabricated activation |
| 12 → 13 | Independent one-time legacy-import source/target journal and exact ordered request evidence; prior tables/bytes/sequence unchanged, new tables empty; no activation or network delivery |
| 13 → 14 | Independent import lineage and accepted-batch receipts; prior tables/bytes/sequence unchanged; captured journals initialize exact PREPARING lineage, with no fabricated acceptance or consent |

Each future entity change requires a version bump, data-preserving migration, schema export/KSP, all supported starting
versions, and a copied real v1 asset test. Domain table UUID primary keys remain global within each physical table;
canonical publication refuses incompatible physical identities instead of overwriting/remapping another scope.

`sync_snapshot_stages`, `sync_snapshot_records` and `sync_snapshot_tokens` store inert frozen-snapshot staging independently
of domain/canonical tables. Child FKs cascade only from their stage. One stage per account binds origin session/epoch,
fixed limit, original full checkpoint/baseline observation and frozen page baseline/progress. Downloaded is not a complete
canonical baseline or visible publication; see [staging boundaries](sync/implementation.md#frozen-snapshot-staging-storage).

`sync_session_authority` stores nonsecret revision/generation, phase, service-origin/cache provenance and activation
operation independently of credentials. The inert Room adapter reads strict transactional observations and can atomically
reserve private preparation or fence private state with the device generation; these paths do not mutate
vault/domain/outbox/cache data. See [durable private authority](sync/implementation.md#durable-private-room-account-authority).
The separate R09b coordinator composes reservation with exact encrypted-vault CAS/rereads; its observations are advisory.
R09c freshly admits the matching pair through registered exact private permits and shared canonical bootstrap/tail
ownership. R09d's explicit internal completion path reuses protected projection publication and writes journal/device
ACTIVE/ready in the SAME Room transaction, then phase-promotes the exact vault record with coherent pre/post-CAS
rereads. Operation/origin/generation remain retained. Ordinary repositories/public publication stay ACTIVE-only and
bootstrap stays canonical-only. No app activation/rebinding is constructed; see
[completion boundaries](sync/implementation.md#private-completed-preparation-and-vault-promotion).
R09e adds separate settled occupied ACTIVE reauthentication: exact private TRANSITIONING reservation at a new generation,
full same-account PREPARED vault replacement, then PREPARING finalization at the same reserved generation. Existing Room12
storage is reused; Room changes journal/device and, when an import exists, its current-lineage anchor, while retained intent/cache/domain remain untouched. Required shared run/credential
ownership, pending-predecessor refusal and advisory crash recovery remain inert; see
[settled reauthentication](sync/implementation.md#inert-settled-occupied-credential-reauthentication).
R09g adds broader inert retained-account recovery over the same Room12 journal/device tables and exact vault CAS.
Archive-free vault records keep v1 bytes; archive-bearing v2 atomically retains and permanently fences exact uncertain
predecessor evidence while installing a fresh same-account grant. Known origin/UUID, strict phase/device tuples, shared
owners and unchanged domain/cache/intent remain mandatory. See
[retained-account recovery](sync/implementation.md#inert-retained-account-recovery-and-superseded-evidence).

`sync_legacy_import` and `sync_legacy_import_entries` retain one private source/target/capture operation and exact
create-only request evidence independently of domain/outbox FKs. Explicit inert capture either binds validated local roots
and creates dependency-ordered imports with private PREPARING authority, or retains invalid source/error evidence plus a
private generation fence without association. It never creates ACTIVE, credentials, a cursor or a complete baseline.
Once evidence exists, ordinary null-account repository reads/writes refuse. Startup performs no capture; explicit
sign-in uses the facade's real held transition lease and the approved import policy.
Recovery is exact-target/authority/source/request fenced; missing work is not remotely accepted progress.
Room14's `sync_legacy_import_progress` binds the current authority lineage separately from immutable capture provenance;
`sync_legacy_import_acceptances` retains the exact validated canonical batch for each definitively accepted original request.
Receipt insertion, canonical acknowledgment and frozen-head removal share one transaction. These tables have no domain/outbox
cascade, and source/requests/receipts are never automatically cleaned up. The opt-in private delivery/activation boundary
is accepted at the explicit private boundary. See [private import integration](sync/implementation.md#private-import-delivery-and-activation).

## Persistence and state flow

- State holders load/mutate through repository interfaces. Domain edits autosave; transient menus, focus, dialogs and
  custom-editor drafts remain presentation state. Ordered writes and refresh-generation guards prevent stale loads
  from replacing newer accepted edits.
- Inert push storage reuses the v10 outbox with a required shared single-process run owner, transactional explicit-
  head freezing and exact session/epoch/row callback checks. Single-head outcome handling returns read-only owned
  accepted snapshots or persists rejection/retained errors and bounded retry timing. Conservative process-local
  write tokens prevent callback reuse even after identical scheduling or failed/uncertain writes. Inert acknowledgment
  commits complete canonical application, exact frozen-row removal and generation-scoped metadata together, preserving
  newer intent and marking projection dirty. Accepted snapshots privately bind account/exact durable identity but remain
  insufficient authority without fresh transactional checks. An inert `sync/engine/PushDeliveryLoop` now composes these
  seams under one required shared owner: freeze/check/send one head outside Room, classify, acknowledge atomically,
  then continue within a strict 1..100 budget. Failure stops without in-run retry or successor bypass; only committed
  acknowledgments count. The same admitted run now reads/checks opaque incremental checkpoint capabilities and commits
  complete canonical batches with cursor-only advancement under full row/baseline/session/epoch fences, preserving
  last-success and visible intent. Inert `PullDeliveryLoop(api).pull(run)` now validates bounded complete ordered pages
  and run-local ID/token cycles, fetches outside Room, and chains whole-batch checkpoint commits on that already admitted
  run without owning admission or another mutex. Missing baseline/cursor or explicit expiry requests R08; invalid tokens
  never bootstrap. Earlier commits survive later failure; standalone Tail is not last-success/convergence.
  Inert `SyncDeliveryLoop` holds that single admitted run uninterrupted through push, pull and a separate exact-checkpoint
  tail transaction. Only fenced push Empty authorizes pull; budget/error/auth stops never probe or bypass the head.
  A validated terminal callback updates only last-success with full-row/baseline/session/epoch/cancellation fences and
  conservative token retirement, reporting outstanding local work separately. No projection/convergence is claimed.
  The same admitted run now owns inert begin/read/check/append/abandon snapshot storage over Room11. Selected durable
  stages block ordinary push/pull; whole pages remain outside canonical/domain data. New capture stays protected;
  restart/uncertain writes require observation, explicit abandonment and a new stage, never implicit resume/cleanup.
  Inert `SnapshotDeliveryLoop.start(run, ...)` now follows only fenced push Empty into actual bounded snapshot HTTP on
  that same admitted run; pages are owned before response fences and appended through the existing stage chain.
  Same-driver LimitReached results can continue only in the uninterrupted run, without a permanent account-size cap;
  durable restart cannot resume. Downloaded alone is transport/staging progress. Separate inert
  `SnapshotDeliveryLoop.complete(run, previous)` requires actual terminal issuance/current same-run authority and commits
  the validated complete canonical graph, absence, baseline checkpoint and stage removal atomically at epoch+1 without
  visible edits or last-success. Same-run `publishProjection(pairedStore)` reuses the protected publisher and adopts its
  returned epoch (actual publication advances again). Retained stages cannot be adopted after restart; durable complete
  dirty baselines can publish under a fresh run at the observed epoch without redownload. See
  [complete baseline/recovery](sync/implementation.md#complete-snapshot-baseline-and-publication-recovery).
  Configured startup constructs the facade privately, with no automatic engine invocation. R09a/b private authority/preparation and R09c private engine admission/canonical bootstrap
  are delivered separately, with exact durable and process fences. R09d completes inert protected projection/Room-ready
  and exact vault promotion/gap recovery under those fences. R09e/f add inert settled occupied replacement and its exact
  established ACTIVE pending-refresh replay prerequisite. R09g adds retained-account signed-out/missing-vault and
  uncertain/interrupted-transition recovery; see [session boundaries](sync/implementation.md#inert-retained-account-recovery-and-superseded-evidence).
  The explicit [app-facing boundary](sync/implementation.md#app-facing-authority-and-offline-recovery) supplies matching
  offline account eligibility, exact grant expectations, private activation/recovery and bounded reconnect without ACTIVE
  projection publication. Its explicit foreground request factory and grant handoff retain actual configured origin,
  caller intent/expected authority/operation and an immutable captured process fence through that same preparation
  critical section. The [bounded foreground exchange](sync/implementation.md#bounded-foreground-exchange-and-guarded-grant-handoff)
  composes identity/auth without reusable grants or visibility; the startup controller separately completes activation. Its repositories additionally check
  process revocation before effects/commit and after Room result dispatch; legacy UUID/clock trailing-lambda constructor
  behavior remains unchanged. Repositories never follow
  private preparation. Explicit `AppSessionCoordinator.reconcile` holds one admitted owner through push/pull, typed
  snapshot recovery, actual post-baseline tail and paired guarded publication. `reconnect` remains nonpublishing;
  fresh fenced `observeCacheEpoch` is advisory progress, not authority. No observation starts engine work.
  The [shared driver](sync/implementation.md#shared-push-before-pull-and-valid-tail),
  [push](sync/implementation.md#bounded-push-delivery), [checkpoint](sync/implementation.md#incremental-checkpoint-transactions)
  and [pull](sync/implementation.md#bounded-incremental-pull) references own details.
- All existing repository mutation families use in-transaction session fencing and local capture. Default signed-out
  edits create no upload work or canonical/cache rows and use hard deletion. Account-bound internal paths advance
  independent generation watermarks and dirty publication bookkeeping together with domain/outbox writes. The ledger
  survives physical root removal; generations advance beyond both domain and ledger values. The inert canonical batch
  writer separately applies complete revisioned observations under account/session/cache-epoch fences, without changing
  visible domain data or outbox/cursor state. The inert protected publisher requires an injected pause/drain lease,
  protects the entire account graph while any account outbox row remains, and publishes a validated complete baseline
  atomically with generation preservation and a checked account epoch signal. A real app-owned barrier now exists,
  with the account runtime supplying canonical impact and process guards. Startup recovery does not publish;
  explicit private activation uses the paired protected completion path. See [capture boundaries](sync/implementation.md#room-and-local-capture),
  [canonical batches](sync/implementation.md#fenced-canonical-batches) and [protected publication](sync/implementation.md#protected-publication).
- `ActiveWorkoutState.awaitPendingEdits()` delegates to the shared finite-edit coordinator, never the endless elapsed-time
  ticker. The screen retains its workout/owner-specific finish-drain registration; disposal detaches only the matching
  registration, not accepted edits or their failures. Finish request drains all features and fences its captured active
  root after suspension; confirmation also reserves exclusive admission before the destructive repository call.
- Completion starts a timer only after a fenced incomplete-to-complete transition, with no superseding edit, and after
  reload still shows the visible set completed in the active parent. The checkbox re-keys on persisted completion;
  draft text remains keyed on row ID.
- `GymAppState` discovers the newest active workout at startup. Starting empty/again/from-routine replaces the old active
  in one repository transaction. Navigation/new-active state publish only after success; failure keeps the old active,
  dialog, navigation and timer intact.
- Navigation, filters/list restoration, dialogs and countdown remain in memory. App/feature epoch observation and
  canonical timer-impact reconciliation use the delivered scoped runtime, without persisted navigation or timer
  process-death reconstruction.

### Finite-edit admission and publication leases

`GymAppState` owns one `FiniteEditCoordinator`, provided through `GymApp` to every mutating feature factory. Standalone
screens retain scope-bound fallbacks; the app never constructs a separate feature coordinator when one is provided.
Accepted mutation inputs are snapshotted and registered synchronously before optimistic state changes. Paused, disposed
or cancelled-scope callbacks cannot accept edits or alter autosave drafts. The app-owned write scope survives feature
composition disposal; loads, observers and countdown tickers remain outside the finite drain.

Normal mutations and terminal rest claims share acceptance-ordered actual writes. Exclusive finish/discard/active-replacement
markers stay outside that predecessor chain: they reserve admission, drain preceding writes, and exclude only their own
registration when draining again. External drains still observe those transitions. Local replacement/discard cancels a
running timer only after repository success. Failure/cancellation evidence is retained by originating acceptance order;
only a successful same-identity retry/superseding mutation settles nonterminal evidence. Unrelated edits, screen disposal
and drain observation do not erase it. Terminal evidence is never automatically retried or cleared.

Public pause/drain returns an owner-specific live publication lease; synchronous thread-safe checks/close work on Room
threads. Pre-grant work is drained before atomic issuance; post-grant terminal admission invalidates the lease before
persistence, holding admission until close. Failed/cancelled acquisition and lost result dispatch release the pause.
`GymAppState.publicationBarrier` binds one exact account/generation/cache-epoch request and requires an explicit canonical
impact callback. Acquisition and timer claims run on the app-state owner dispatcher without adopting its Job, preserving
caller cancellation. `AccountGymAppState` supplies the request-bound, process-fenced canonical reader and real
transition barrier. Open explicit-save custom editors refuse normal account transitions without discarding drafts;
emergency invalidation hides immediately. The publisher still rechecks authority, lease and account-wide outbox
protection, including after final SQL at its newly advanced epoch. Startup recovery runs no HTTP, engine bootstrap or
projection publication; explicit sign-in/activation uses those existing guarded seams.

## Projection observation and feature refresh

`ProjectionObservationRepository` is inherited by the four feature interfaces and `GymAppStateRepository`. Its compatibility default is
an inert initial `0` for hand-written fakes; `GymRepository` overrides it. An account-bound cold stream registers Room
invalidation for `sync_account_cache` and `sync_device_state` before its first fenced metadata read, then emits distinct
selected-account `materialized_epoch` values. Cache/baseline progress, dirty/outbox state and foreign-account epochs are
not publication signals. Missing metadata yields `0`; negative counters or materialized epoch beyond cache epoch fail
closed. Every reread checks the immutable repository session before distinct filtering; revocation terminates the old
stream, never follows a new account. Cancellation detaches observation without writes/capture or schema changes.

The default signed-out repository stream emits one fenced `0`, never retained private metadata. The explicit account
root supplies a process/session-fenced account repository. Local mutations use explicit refresh; the stream is not a
universal local-change log or visibility grant.
No engine/publisher/account authority is activated by observation.

Catalogue/pickers/exercise detail, routine list/detail/editor, history list/detail and active-workout feature states
collect in their composition scope. Each signal advances a latest-load generation and launches the selected-model load
outside the collector. Accepted edits invalidate loads synchronously; newer requests, stream failures and disposal fence
late results, including cancellation-ignoring loads. Observer/ticker/load jobs never enter finite drains. Autosaved field
loads drain accepted finite work outside tracked writes; their post-edit refresh is scheduled after registration and
waits for commit without self-joining. Required in-write set-completion reads retain acceptance-captured validity and
never mint a new generation over a later accepted draft. Pure catalogue and routine/history action-list reads do not
drain unrelated app writes/failures, so persisted lists and pickers remain usable during queued autosaves. List-changing
deletes follow only their own registered job with a guarded reload; reads never clear finite failure evidence.

Loads reuse current scoped joined models, so custom-reference labels refresh across routine, history, picker and active
states even when parent revisions are unchanged. Search/filters, dialog drafts and surviving picker selection remain;
missing IDs are pruned only from a latest complete catalogue read. Each state exposes `refreshFailed` without raw error
UI. Ordinary failure retains valid models/accepted drafts; stale-session failure clears selected models (and routine
editor name) without rebinding or navigating. A terminated observer is not automatically retried or account authority.
The explicit-save custom editor's unsaved draft is not reconstructed.

Editable set rows have stable composition keys by set ID, not epoch/position. Same-ID name/notes/rest/set values reconcile
without replacing focus nodes. Numeric text preserves its accepted raw spelling when its sanitized parsed value equals
the incoming persisted value, including trailing zeros and null-valued invalid/incomplete drafts; genuinely changed
values replace stale text. Rest-dialog initial input is snapshotted per opening, so a publication does not reset an
unconfirmed draft. The configured-rest wheel settles through one stable, sequential idle observer with a misalignment
check, not an animation keyed by its own scrolling flag; opening and dismissal quiesce without a self-triggered snap
loop. Feature observation/rename never invokes completion or starts a timer.

The app shell uses the same initial/epoch-driven guarded load, finite drain and latest-generation fences. Navigation
invalidates and schedules a replacement load so discovery cannot be lost. Same-ID active names refresh; an active-ID
change retires old-root consent and source intent on every tab, returning old active/picker/replacement flows to Workout
without forcing navigation into a replacement. Independently captured missing history/routine/custom selections apply
History/Workout/Exercises fallbacks even in the same pulse as an active change. A disappeared explicit custom-editor
target retains its presentation and flags failure. Destructive dialogs and pending finish eligibility bind the root/token
at request time; dismissal or root replacement cannot resurrect consent. Refresh emits no domain edits or summaries.

## Rest-timer lifecycle

`ActiveRestTimer` snapshots session, workout, entry, completed set, unique timer instance, selected duration and monotonic
elapsed-realtime start. The countdown
updates every 100 ms, cues two/one seconds and expiry, and holds a bounded partial wake lock. Production uses alarm-usage
vibration; wall/monotonic clocks, repository, and hardware have narrow injectable boundaries for deterministic tests.

Skip, expiry, replacement, or finish snapshots elapsed whole seconds and claims persistence at most once per timer.
Claimed terminal writes run in acceptance order with ordinary finite edits, while replacement countdowns start immediately.
Finish waits for terminal success; timer ownership rejects stale callbacks and prevents duplicate writes or releasing
another timer's lock.
Unchecking stops only the matching timer without recording rest. Once a terminal write starts it is detached from
countdown cancellation. A failed local rest write is terminal, not automatically retried. Disposal cancels owned work
without cancelling the parent scope and releases held hardware resources.

Terminal success/failure is visible to every later transition/publication drain, even after the running timer is gone;
a failed rest write is never retried as an edit to immutable history. The explicit publication-impact callback retains a
valid unchanged timer (including rename/reference-only replacement) or claims/drains old-set rest before lease issuance.
Newly captured rest remains local intent: the publisher's outbox recheck postpones projection. `ProjectionStore.preservesTimer`
requires complete baseline/no selected stage and transaction/session/epoch/process fences; protected outbox returns true
without treating the latent graph as publication authority. Eligible graphs receive full validation. Remote finish/delete,
removed entry/set or uncompleted source returns false; reference-only replacement preserves the timer. The real barrier
claims/drains rest before old domain rows disappear, and then protection prevents a destructive publish. Account timer
starts require an accepted completed-set callback bound to the selected active parent. Unsupported timer-invalidating
materialization faults only the exact handle, without a late history write or silent timer loss. See the
[publication/timer design](sync/integration-design.md#7-autosave-timer-and-ui-publication-prerequisites).
