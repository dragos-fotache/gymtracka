# GymTracka Android architecture

This Android-local document records the current implementation and local persistence invariants; it is not a shared
server specification. See [the backend contract](../shared-docs/docs/backend-contract/README.md) for the cross-project
service boundary.

## Application structure

GymTracka is a single `:app` module using Kotlin, Jetpack Compose, Material 3, coroutines, and Room. It targets Android
API 37 and supports API 24 and newer.

- `MainActivity.kt` enables edge-to-edge rendering, applies `GymTrackaTheme`, and calls `GymApp()`.
- `ui/GymApp.kt` owns the root scaffold, destination rendering, bottom navigation, active-workout pill, and app-level
  dialogs.
- `ui/GymAppState.kt` owns in-memory navigation, active-workout replacement/finish/discard coordination, catalogue
  screen restoration state, and active rest-timer lifecycle.
- `ui/exercises/ExerciseScreens.kt` owns catalogue/detail/create/edit and all picker variants plus their state holders.
- `ui/workouts/` owns the workout landing screen and history list/detail screens and state holders.
- `ui/routines/RoutineScreens.kt` owns routine list/detail/editor UI and state holders.
- `ui/activeworkout/` owns the active-workout screen, exercise/set components, and rest-timer UI.
- `ui/util/WorkoutFormatters.kt` owns shared derived calculations and locale-aware display parsing/formatting.

The app uses composition-scoped state-holder classes and does not currently use Android `ViewModel`, dependency
injection, or a navigation library. `FeatureRepositories.kt` defines narrow interfaces for feature state holders.
Production uses `GymRepository`; JVM state tests use hand-written fakes.

`ui/GymRepositoryProvider.kt` defines the optional internal `LocalGymRepositoryOverride` composition local and
`rememberGymRepository()`. With no override, the helper remembers `GymRepository(applicationContext)`, preserving the
normal production construction path. The app-state factory and feature factories call the helper; the override lets
instrumentation provide a real in-memory Room-backed repository while exercising normal app and feature construction. It
does not change the narrow state-holder interfaces, constructor behavior, composition scopes, or hardware behavior. The
app-flow harness creates root app state with a composition scope and no-op timer hardware, mounts the real app, then
disposes composition to cancel feature scopes and joins the root composition-scope `Job` before closing Room. No
dependency-injection, navigation, or ViewModel framework was added. See
the [Android testing strategy](testing-strategy.md#implemented-coverage) for verified flow coverage.

## Room database

`app/src/main/assets/databases/gym.db` is a version-1 bundled catalogue asset. On first use, Room copies it to the
app-private runtime database and runs migrations. The asset remains version 1 and must not contain user-data tables.

The runtime `GymDatabase` is version 9 and includes catalogue, custom-exercise, workout,
and routine data plus inert sync foundations (root metadata columns and empty
`sync_outbox`/`sync_state`/`sync_device_state`/`catalogue_state` tables; see Future sync impact).

### Catalogue tables

- `muscle_groups`: ID, slug, and name.
- `equipment`: ID, name, type, and optional usage type.
- `exercises`: ID, name, type, optional difficulty/force/mechanics/category, JSON-converted instruction strings, and
  `is_user_created`.
- `exercise_muscle_groups`: exercise/muscle/type join where type is primary, secondary, or tertiary.
- `exercise_equipment`: exercise/equipment join with optional number of items.

The bundled catalogue currently contains 899 exercises. Its original English JSONs are pinned in
the [shared catalogue package](../shared-docs/catalogue/README.md), version `gymtracka-catalogue-v1`, with checksums, import
mapping and complete five-table parity verification. The backend consumes these JSONs, not the Android SQLite file.
Catalogue MIT/foundation notices and attribution are packaged under `app/src/main/assets/third_party_notices/`; no
notices UI was added. The source asset and Room schema are unchanged. `ExerciseDao.getAllSummaries()` bulk-loads
relationships to avoid per-row queries. Bundled exercise mutations are guarded; custom exercise creation/update writes
the exercise and its relationships transactionally. Current custom exercises use type `reps`, at most one selected
equipment row, at most one primary muscle, and any number of secondary muscles.

### Workout aggregate

- `workouts`: UUID `id`, persisted `name`, `started_at_millis`, optional `finished_at_millis`, and status `active` or
  `finished`.
- `workout_exercises`: UUID `id`, workout/exercise foreign keys, consecutive zero-based `position`, notes, and
  non-negative `rest_duration_seconds` (default 30).
- `workout_sets`: UUID `id`, workout-exercise foreign key, consecutive zero-based `position`, nullable repetitions,
  nullable `weight_kg`, `is_completed`, and nullable actual `rest_seconds`.

Deleting a workout cascades to its exercises and sets; deleting a workout exercise cascades to its sets. Set and
exercise deletions renumber remaining siblings transactionally.

`addExercisesToWorkout()` appends all selected exercises and their initial sets in one transaction. It reads each
exercise's most recent finished workout, copying completed set inputs and configured rest duration. Copies are
incomplete and have null recorded rest. Without completed history it creates one empty set; without a previous duration
it uses 30 seconds.

`finishWorkout()` is one Room transaction. It requires a completed set, deletes unfinished sets, removes newly empty
exercises, renumbers surviving exercises, then sets status and finish time. `performWorkoutAgain()` accepts only a
finished source and creates a fresh aggregate. Replacement updates only `workout_exercises.exercise_id`.

Finished history summaries are assembled with two fixed DAO queries instead of per-workout/per-exercise queries. Full
details are loaded on demand. Volume and best-set display values are derived, not stored.

### Routine aggregate

- `routines`: UUID `id`, name, and `is_draft`.
- `routine_exercises`: UUID `id`, routine/exercise foreign keys, consecutive zero-based position, and configured rest
  duration.
- `routine_sets`: UUID `id`, routine-exercise foreign key, consecutive zero-based position, nullable repetitions, and
  nullable weight kilograms.

Drafts are excluded from saved-routine queries. Adding exercises creates one empty set each in one transaction.
Deletions cascade and renumber. Starting a routine, saving a workout as a routine, and inserting complete cloned
aggregates are transactional.

### Migrations

The supported migration chain is:

1. `1 → 2`: workout tables.
2. `2 → 3`: non-null workout name, backfilled from the local start hour.
3. `3 → 4`: workout-exercise notes.
4. `4 → 5`: nullable recorded set rest.
5. `5 → 6`: configured per-workout-exercise rest duration, backfilled to 30 seconds.
6. `6 → 7`: routine tables.
7. `7 → 8`: `exercises.is_user_created`, default false.
8. `8 → 9`: sync foundations — `account_id`, `server_revision`, `local_generation` (default 0), `sync_state` (default
   `local_only`), and `deleted_at_millis`, plus an `(account_id, deleted_at_millis)` index, on workouts, routines, and
   exercises; plus empty `sync_outbox` (sequence-ordered, generation-guarded pending replacement), `sync_state`,
   `sync_device_state` (active + cached account ids), and `catalogue_state` tables.

Schema snapshots for versions 1–9 are retained in `app/schemas/`. Instrumentation tests migrate every supported starting
version and a copied real v1 asset to the current schema.

## State and persistence flow

- `GymRepository` owns UUID creation and is the app-facing local persistence API.
- State holders load and mutate through narrow repository interfaces; leaf composables receive data and callbacks.
- Mutable workout/routine controls autosave through coroutine-backed state holders. Ordered autosave tests protect call
  ordering, and refresh generation guards prevent stale delayed loads from overwriting newer state.
- `ActiveWorkoutState` tracks every accepted finite edit (name, notes, rest, sets, deletions) with failure-retaining
  signals and exposes `awaitPendingEdits()`, which fails while any accepted edit is uncommitted or unresolved. The
  screen registers that drain with `GymAppState` under its workout/owner identity, so requesting and confirming finish
  both wait for accepted edits without joining the endless elapsed-time ticker; disposal detaches only its own
  registration.
- A set-completion callback starts a timer only when the fenced repository write reports a real incomplete-to-complete
  transition, no newer edit superseded it, and the reloaded active parent still shows the visible set completed. The row
  checkbox re-keys on the persisted flag so refreshes cannot leave a stale checked box, while draft text stays keyed on
  the row ID.
- `GymAppState` loads the newest active workout at initialization. Starts atomically replace the previous active
  aggregate in one repository call: confirmation publishes navigation and the new active only after success, and a
  failed start keeps the previous active, navigation, dialog, and timer intact.
- Search/filter/list-position restoration is in-memory app state. Navigation, dialogs, and the active rest timer are
  also in memory.

## Rest-timer lifecycle

`ActiveRestTimer` identifies the completed workout set, selected duration, and monotonic elapsed-realtime start. The app
updates remaining time every 100 ms, signals two/one seconds and expiry, and holds a bounded partial wake lock. Skip,
expiry, replacement, or finishing persists elapsed whole seconds to that set.

`GymAppState` receives the wall/monotonic clock, narrow app-state repository, and rest-timer hardware through small
interfaces. JVM tests use a hand-written repository fake and virtual time. Terminal timer writes snapshot elapsed time
when the user action or expiry occurs, use timer ownership to reject stale callbacks and duplicate writes, and are
detached from countdown-job cancellation once persistence begins. Each claimed transition queues its DAO write on a
serial queue in claim order while countdowns start immediately; finishing awaits the whole queue, and disposal cancels
owned terminal jobs without touching the parent scope. Unchecking ends only the matching timer with no rest write.
Replacing or ending a timer releases only its own wake lock; composition/scope disposal cancels countdown work and
releases the held lock. A failed local rest write is terminal for that timer, so it is attempted at most once. The
production adapter still uses elapsed realtime, alarm-usage vibration, and a bounded partial wake lock so countdown cues
work while the screen is off.

## Future sync impact

The v9 schema carries inert sync foundations — nullable account/revision/deletion metadata,
local generation, `local_only` state, and empty `sync_outbox`/`sync_state`/`sync_device_state`/`catalogue_state`
tables with narrow `SyncDao` storage primitives — plus transport-independent workout,
routine, and custom-exercise aggregate models, explicit org.json codecs with strict raw JSON
parsing (RFC 3339 UTC via desugared java.time), and explicit
Room mappings under `data/sync` (upload input vs canonical records, validated positions/IDs,
no ID generation, no database writes). Typed canonical result envelopes, all 17 semantic/autosave mutation kinds, import
snapshots, and version-1 durable request wrappers are implemented with strict request-field/identity validation and
unchanged raw wire bytes across retries. There is still no running sync engine, production network/auth adapter, snapshot publication,
or sign-out logic, so the app is not syncing. The device row's `cached_account_id` is storage for a retained signed-out
cache,
independent of the active account; retaining cached data on sign-out and clearing the cache when
switching to a different account are not implemented behaviors in this slice. Later sync work will
add remote reconciliation, account lifecycle and production adapters while
keeping Room as the local source for UI behavior. Do not expose Room entities directly as HTTP DTOs.

`data/sync/api` now defines the SDK-independent `GymSyncApi` push/pull/snapshot boundary,
identity and bearer-token interfaces, and the four service-auth operations documented in
[authentication.md](../shared-docs/docs/backend-contract/authentication.md). Opaque token/session display is
redacted; typed failures use fixed text, cannot retain a raw cause, and cap service retry hints
at five minutes. Push validation reuses the approved durable codec, including valid empty
batches, the 100-request limit, duplicate-ID rejection and unchanged validated request text.
These interfaces do not implement authentication, HTTP or syncing. The former in-memory backend simulator, its command tests/fixtures, and `sharedTest` source-set
wiring have been removed. Assignment 018 remains paused; completing the simulator is not required.

JVM-only `HttpGymSyncApi` and `JdkSyncHttpTransport` under `app/src/test/java/.../data/sync/backend`
now exercise the production durable-request encoder and canonical result decoders against the real
Java service over HTTP. `:app:testRealBackend` runs only that package, requires an explicit isolated
configuration, and always executes against external state, including when configuration/build
caching is enabled. Ordinary JVM tests exclude that package and remain independent of a backend.
Small scripted transport doubles cover client boundary/error behavior without reproducing server
persistence or domain logic. See the [coverage transfer ledger](fake-retirement-coverage.md).

`scripts/run-isolated-backend.py` builds/starts the external backend against a disposable PostgreSQL
16 container with generated test-only keys; it never loads the personal environment file or touches
personal databases. Its exclusive mode-600 configuration, run-ID database guard, and owned-resource
cleanup prevent accidental reuse/overwrite. `RealBackendFixture` validates those guards before
minting isolated account/session credentials and reading real catalogue IDs. All domain mutations
and canonical reads under test use HTTP, not SQL fixture writes. This bypasses only external Google
sign-in for test setup; it does not add an auth bypass to the service or verify real Google OAuth.
The HTTP adapter, JDBC driver, fixtures, and integration tests are test-only and do not make the
production Android app networked or synchronized.

The later shared-contract completion in `shared-docs/docs/backend-contract/service-protocol.md` and
`shared-docs/docs/backend-contract/account-lifecycle.md` adds purpose-bound Google proofs, account export/delete/status,
resource-list pagination and exact retry/read-expiry rules. These are contract requirements, not
features delivered by the existing Android v0.3 DTO/API foundation. Align production adapters,
new endpoint models, account-fenced deletion recovery and derived exercise-name refresh when those
client slices are implemented; neither the old fake gate nor the schema-only contract checks prove
that Android implements them. The shared v1 contract is now finalized, including retention.md;
resource browsing uses stateless keyset pages rather than frozen list sessions. These finalized
requirements do not imply that the v0.3 Android foundation has already been upgraded or integrated.

Capture primitives (`data/sync/store`, `SyncCaptureDao`) provide an immutable session
fence checked inside the database transaction, same-transaction generation/state
advance, tail-only coalescing of upsert autosaves, and a read-only durable-row
reader. GymRepository binds an immutable expected account/session (default signed-out null/0), checks it inside
transactions, and scopes all application-facing reads to visible owned roots plus global bundled catalogue rows. Named
child/detail/history joins also filter referenced custom exercises, and raw complete internal reads remain separate.
Custom creation/edit atomically advance metadata and capture complete custom upserts; no-ops and guarded
bundled/foreign/deleted attempts create no work. Editing preserves imported metadata and retained equipment quantity.
Signed-out custom deletion stays a constrained hard delete; account deletion checks all retained owned child references,
removes relationships and captures a root tombstone/delete. All ten routine mutation entry points now use the same
fenced capture boundary. Drafts and autosaves capture full routine upserts, promotion captures routine.save, child
deletions capture exact semantic commands with transactional renumbering, and account deletion retains a root tombstone
while removing children. Stale set entities cannot change parent or stored position. Nullable inputs sanitize negative
repetitions and negative/nonfinite weights to null; configured rest is clamped to zero or higher. Invalid exercise
selections fail atomically before inserts. Full account lifecycle remains a later slice; the production context
constructor still defaults to the original signed-out session.

## R02a raw JSON member uniqueness — implemented

`codec/StrictJson.kt` now checks the original text before tree normalization. A focused iterative scope/string walk
tracks decoded member names independently for every object, including objects inside arrays and ignored additive
response fields. Member literals use the existing kotlinx JSON reader, so JSON-escape-equivalent names reject without
case folding, trimming or Unicode normalization. Duplicate failures are fixed-text `SyncModelException` values with
no raw cause. String values remain opaque; no new depth/length cap, recursive walk or serialization stack was added.

All raw-string aggregate, mutation, result and durable entry points share this boundary. Supported durable wrappers
separately parse their embedded `requestJson`; valid v1 text and exact push embedding remain unchanged. Already-built
`JSONObject` overloads cannot reconstruct keys lost upstream and do not promise raw uniqueness validation. Existing
syntax/model validation remains authoritative: arbitrary untyped tokens can already normalize into strings, while
represented numeric fields reject them through typed readers. R02a does not introduce a raw numeric grammar fix.

See the [R02a coordinator review](remaining-work/R02a-review.md) for independent tests and actual build/device gates.
Room stays v9, the asset stays v1, and no HTTP/auth/engine/UI capability was activated.

## R02b family identity validation — implemented

The workout/routine validators now reject aliases across the complete root/entry/set identity set, including children
under different entries. This covers input/canonical records and import snapshots; existing codecs and pure Room
mappings inherit the checks through their validator delegation. Catalogue `exerciseId` references and mutation IDs
remain independent, and aggregate position sorting is unchanged.

Copy plans validate the source root plus all source children and the target root plus all target children independently.
`workout.performAgain` rejects any UUID overlap across those same-family sides, including off-diagonal roles and roots.
`routine.startWorkout` and `workout.saveAsRoutine` allow cross-family reuse when each side is internally valid, including
equal roots and children. Binding order and valid durable v1 bytes are preserved. New collision messages do not echo IDs.

These are stateless checks of supplied identifiers, not an account-lifetime reservation store or source eligibility
lookup. Storage/canonical publication still must refuse global Room PK collisions under R05/R09. No schema/capture/
transport/UI changes were made. See the [R02b review](remaining-work/R02b-review.md) for actual verification.

## R02c failure vocabulary — implemented

`data/sync/api/SyncApiFailures.kt` now distinguishes service access authentication from explicit foreground
reauthentication, invalid pull cursors from expired cursors, invalid snapshot tokens from expired snapshots, and request
size rejection from transient failure. Five zero-argument types were added: `ReauthenticationRequired`, `InvalidCursor`,
`InvalidSnapshotToken`, `SnapshotExpired` and `RequestTooLarge`. Existing constructors/codes, unmodified opaque
`RequestRejected.errorCode` and the five-minute transient hint cap remain; all display text is fixed and causes are closed.

`GymSyncApi` and service-auth interfaces received contract KDoc only. The JVM-only adapter now routes known errors by
status AND operation, with authoritative authentication/size/throttling/server status precedence; wrong-context token
codes cannot become expiry. Its pre-existing test-only error-code extraction guard remains, not a production parsing
policy. No production HTTP classifier/client, refresh loop, staging cleanup or retry/recovery state was added. These
category meanings guide future adapters/engine work and never establish account-deletion authority.

See the [R02c review](remaining-work/R02c-review.md) for actual scripted/JVM and build gates. Room, assets, scopes,
request versions and app activation remain unchanged.

## Proposed integration design — not implemented

R01 planning/design was approved by the owner on 2026-10-03; see [the audit](remaining-work/R01-task.md) and
[integration design](remaining-work/R01-design.md). It proposes independent account-keyed canonical/deletion storage,
account-wide protection of visible local intent while outbox work remains, clean complete-cache publication through
finite-edit/timer barriers, staged snapshots and a recoverable vault/Room transition journal. Domain-table global PK
collisions must fail closed rather than overwrite/remap another scope. R12 barriers precede production activation.
No schema version, production behavior or framework was changed by this plan; runtime remains v9 and no engine runs.
