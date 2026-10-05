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
| `ui/GymApp.kt` | Root scaffold, destinations, bottom navigation, active-workout pill, app dialogs |
| `ui/GymAppState.kt` | In-memory navigation, active-workout coordination, catalogue restoration, rest timer |
| `ui/exercises/` | Catalogue/detail/custom editor, picker variants, feature state |
| `ui/workouts/` | Workout landing, history/detail, feature state |
| `ui/routines/` | Routine list/detail/editor and feature state |
| `ui/activeworkout/` | Active screen, compact exercise/set controls, timer UI and feature state |
| `ui/util/WorkoutFormatters.kt` | Shared derived calculations and locale-aware parsing/formatting |
| `data/gym/GymRepository.kt` | Application-facing persistence, UUID creation, transactional edits and sync capture |
| `data/sync/` | Models/codecs, fenced capture/canonical batches, inert protected publication, durable head claiming/replay, outcome/retry bookkeeping, atomic acknowledgment, inert bounded push/pull and shared push-first drivers, incremental checkpoint/valid-tail transactions, durable inert snapshot staging, unused HTTP/session components; [details](sync/implementation.md) |

`FeatureRepositories.kt` exposes narrow interfaces to state holders; reusable/leaf composables receive values and callbacks.
`ui/GymRepositoryProvider.kt` supplies `rememberGymRepository()` and optional internal `LocalGymRepositoryOverride` for
real-Room app-flow tests. With no override, it remembers `GymRepository(applicationContext)`. Production construction
still binds the original signed-out session; no account/session rebinding is wired into the app. The unused
[startup recovery seams](sync/implementation.md#startup-recovery-seams) require a future durable Room-authority adapter;
they do not alter production startup or authorize cached-account presentation.

## Room database

`app/src/main/assets/databases/gym.db` is the unchanged **version-1 source catalogue**. Room copies it into app-private
storage and migrates the runtime database. Never add user-data tables to the asset or use destructive migration fallback.
Runtime `GymDatabase` is **version 11**; exported versions 1–11 are retained in `app/schemas/`.

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

Each future entity change requires a version bump, data-preserving migration, schema export/KSP, all supported starting
versions, and a copied real v1 asset test. Domain table UUID primary keys remain global within each physical table;
canonical publication refuses incompatible physical identities instead of overwriting/remapping another scope.

`sync_snapshot_stages`, `sync_snapshot_records` and `sync_snapshot_tokens` store inert frozen-snapshot staging independently
of domain/canonical tables. Child FKs cascade only from their stage. One stage per account binds origin session/epoch,
fixed limit, original full checkpoint/baseline observation and frozen page baseline/progress. Downloaded is not a complete
canonical baseline or visible publication; see [staging boundaries](sync/implementation.md#frozen-snapshot-staging-storage).

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
  The app constructs none of it; complete bootstrap, durable authority, convergence and activation remain outstanding.
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
  atomically with generation preservation and a checked account epoch signal. No live barrier, account UI or production
  publication activation exists. See [capture boundaries](sync/implementation.md#room-and-local-capture),
  [canonical batches](sync/implementation.md#fenced-canonical-batches) and [protected publication](sync/implementation.md#protected-publication).
- `ActiveWorkoutState.awaitPendingEdits()` tracks finite accepted edits with retained failures, rather than joining the
  endless elapsed-time ticker. The screen registers its workout/owner-specific drain with `GymAppState`; finish request
  and confirmation both wait, and disposal detaches only the matching registration.
- Completion starts a timer only after a fenced incomplete-to-complete transition, with no superseding edit, and after
  reload still shows the visible set completed in the active parent. The checkbox re-keys on persisted completion;
  draft text remains keyed on row ID.
- `GymAppState` discovers the newest active workout at startup. Starting empty/again/from-routine replaces the old active
  in one repository transaction. Navigation/new-active state publish only after success; failure keeps the old active,
  dialog, navigation and timer intact.
- Navigation, filters/list restoration, dialogs and countdown are in memory. General cross-feature edit barriers, remote
  Room observation, and session-bound UI reconstruction are not implemented; [R12](remaining-work-roadmap.md#r12--feature-and-app-reconciliation)
  owns that integration.

## Rest-timer lifecycle

`ActiveRestTimer` identifies the completed set, selected duration, and monotonic elapsed-realtime start. The countdown
updates every 100 ms, cues two/one seconds and expiry, and holds a bounded partial wake lock. Production uses alarm-usage
vibration; wall/monotonic clocks, repository, and hardware have narrow injectable boundaries for deterministic tests.

Skip, expiry, replacement, or finish snapshots elapsed whole seconds and claims persistence at most once per timer.
Claimed terminal writes run in a serial queue in claim order while new countdowns can start immediately. Finish waits
for that queue; timer ownership rejects stale callbacks and prevents duplicate writes or releasing another timer's lock.
Unchecking stops only the matching timer without recording rest. Once a terminal write starts it is detached from
countdown cancellation. A failed local rest write is terminal, not automatically retried. Disposal cancels owned work
without cancelling the parent scope and releases held hardware resources.

Failure-aware cross-feature transition/publication handling is still planned: joining the current terminal queue alone
is not proof of successful persistence. Preserve these at-most-once and hardware semantics when implementing the
[publication/timer design](sync/integration-design.md#7-autosave-timer-and-ui-publication-prerequisites).
