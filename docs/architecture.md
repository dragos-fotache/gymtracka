# Android architecture

GymTracka has one `:app` module using Kotlin, Compose, Material 3, coroutines, and Room. Minimum API is 24;
compile and target API are 37. State holders are composition-scoped classes. There is no Android ViewModel layer,
dependency-injection framework, or navigation library.

## Application structure

Paths in this table are relative to `app/src/main/java/com/dragosfotache/gymtracka/`.

| Component | Responsibility |
|---|---|
| `MainActivity.kt` | Edge-to-edge, theme, Compose setup, and the default `GymApp()` entry |
| `ui/GymApp.kt` | Scaffold, destinations, bottom navigation, active-workout pill, dialogs |
| `ui/GymAppState.kt` | Navigation, active-workout coordination, selected-model refresh, shared edits, rest timer |
| `ui/AccountGymAppState.kt` | Account runtime, exact-handle repository binding, transition/publication barriers, scope retirement |
| `ui/account/` | Startup/configuration gates, sign-in, import consent/progress, Settings account content, foreground sync and recovery |
| `ui/settings/` | Settings layout, advisory server-check state/lifecycle and manual network-action availability |
| `ui/exercises/` | Catalogue, detail, custom editor, exercise pickers and their state |
| `ui/workouts/` | Workout landing, history and finished-workout detail |
| `ui/history/` | Shared read-only set table for exercise history, workout summaries and routine details |
| `ui/components/` | Shared compact set inputs and swipe-to-delete set rows |
| `ui/routines/` | Routine list, detail and editor |
| `ui/activeworkout/` | Active workout, compact set controls, timer presentation |
| `ui/util/` | Locale-aware input/formatting, derived values, projection-refresh helpers |
| `data/gym/` | Room entities, DAOs, migrations, feature repository interfaces, `GymRepository` |
| `data/sync/` | Models/codecs, HTTP, identity, encrypted sessions, capture, canonical storage and delivery |

[GymApp](../app/src/main/java/com/dragosfotache/gymtracka/ui/GymApp.kt) delegates default startup to
`AccountStartupApp`. Invalid/missing service configuration renders a gate before opening Room, the vault, or HTTP.
Configured startup recovers account authority locally. Initial access requires explicit service-confirmed sign-in and
complete activation; matching established accounts reopen offline. See [authentication](sync/auth-and-recovery.md).
The private startup gate offers Finish signing in for saved activation and explains the separate fresh-session Sign in
action. The startup state holder freshly reads authority for each operation and before post-sign-in activation. Repeated
private same-account sign-in uses a new recovery reservation; exact saved-grant resume retains its old identity. These
paths preserve queued edits and credential uncertainty and never grant feature visibility from presentation state.

The account root supplies the currently valid handle's repository and shared edit coordinator to every feature.
`GymApp(appState)` and the default `rememberGymRepository()` construction remain explicit local/test compatibility
paths; the default app does not use them as a signed-out fallback. Same-handle status changes preserve navigation,
feature state, and timers. Handle replacement reconstructs the scoped app. Retired state stays alive while a transition
or publication lease still needs it; disposal revokes its handle and cancels owned child work.

## UI and state flow

Feature state holders expose observable state and mutation methods through the narrow interfaces in
[FeatureRepositories.kt](../app/src/main/java/com/dragosfotache/gymtracka/data/gym/FeatureRepositories.kt).
Screens connect state to callbacks; reusable controls receive values and callbacks. Composables do not access DAOs.
The account root uses `LocalGymRepositoryOverride` to provide its scoped repository; tests can also supply real Room.

Workout names, exercise notes, rest settings, set inputs, and completion autosave. Routine names, rest settings,
and set inputs also autosave. Accepted writes persist in order, and load-generation guards prevent stale reads from
replacing newer edits. Custom-exercise forms use explicit Save;
their unsaved drafts, focus, menus, confirmation dialogs, and swipe state remain in the composable. Navigation,
filters, selection, and countdown state are in memory.

Top-level destinations are Exercises, Workout, History, and Settings. All four share `GymBottomNavigation`: equal-width
icon-only tabs using Material list, dumbbell, history and gear icons with accessible names and selected semantics.
The selected tab fills its full rectangle with the FigCraft blue (`#404F85`); all icons retain the same muted tint.
Its 58-unit row, 2-unit top border and 2-unit bottom gap scale from the 448-unit reference independently of the page grid,
with a 48dp minimum tab height. Android bottom insets are added separately. When applicable, the active-workout pill
appears above navigation as a square outlined Surface panel aligned to the shared 23-unit gutters. Its bracketed
workout name wraps, with live elapsed time, optional rest countdown and a red `[Discard]` action beneath. Metrics and
actions stack when enlarged text needs more space. `WorkoutContentStyle` supplies the shared monospaced grid without
a screen-sized container. The root scaffold reserves the panel's actual height so it cannot cover tab content.
`showSettings()` only changes in-memory navigation; it neither replaces the scoped app nor ends a timer.
Tapping the panel resumes the active workout; Discard retains its confirmation.

`SettingsScreen` uses the shared monospaced grid, a fixed Surface title strip, and vertically scrolling Connection,
Sync and Account panels. Square 2-unit outlines, 23-unit gutters, 12-unit text insets, 48-unit rows and 24-unit panel
gaps match the history screens. Labels and timestamps wrap on shared text edges; bracketed text actions replace
the old icon buttons. Busy operations use thin square-ended progress bars, and recovery warnings/actions retain
their inline presentation. `SettingsComponents.kt` supplies shared labels, enabled/destructive actions and progress;
connection controls keep Material typography when reused by private activation. Settings consumes full
scaffold padding, including the measured collapsed-workout panel. The account root supplies the Sync and Account sections,
including the existing status, sync, reauthentication, cancellation and recovery controls, through
`settingsAccountContent`; it retains session/controller/lifecycle ownership. Import consent remains a root-owned dialog.
The former Account navigation item/status dialog is removed. Local/test compatibility roots show Settings with an
explicit unavailable account explanation and no configured server checker.

`ServerConnectionState` is composition-owned at the configured account root. The Settings Connection section or
deferred-activation gate activates it while visible and its lifecycle is at least STARTED. Entry/foreground return starts a fresh check; Check is
explicit and duplicate presses refuse. Leaving/backgrounding/disposal cancels owned work, invalidates its generation,
and resets status. A retiring job keeps its slot until completion; re-entry queues one fresh check without overlap.
The borrowed scope and account runtime/timer are never cancelled by a connection check.
The section contains only Online/Offline/Checking with a trailing green/red/yellow dot and the Check action. Unchecked,
unreachable and unconfirmed states display Offline; no probe details or explanatory messages appear here.

The five-second check uses the narrow `ServerChecker` interface and `HttpServerChecker` over a separate secure
fixed-origin transport (three-second connect, five-second read/write/call limits). One bodyless, credential-free
`GET /v1/sync/pull` confirms Connected only for a strictly decoded `401 authentication_required`. Other responses,
service errors and transport failure remain distinct; no account read, Google UI, refresh or sync is invoked.

Retryable push/pull/snapshot stops and unavailable sync/recovery operations request a fresh connection check while
Settings is in the foreground. Transient sync failures alone do not prove Offline: HTTP server errors and transport
faults share that category. The probe determines the updated connection state, disabling manual network actions while
Checking or Offline. A failed sync invalidates any earlier in-flight probe and queues a fresh one after cancellation
settles, so an old Online result cannot overwrite it. Authentication, permanent rejection, cancellation and budget
stops do not request a check. An initial Online result or repeated Online checks do not retry sync.
A confirmed Unreachable→Connected transition issues one fresh sync demand for the exact account.
Only a recorded connection-failure head may reset its local delay; service/unknown waits remain protected.

The account root also owns `AccountRetry` for observed deferred uploads. It shows the remaining
delay in Settings or the private sign-in gate and schedules a fresh controller action at the
unchanged durable deadline while STARTED. It is independent of the connection probe and tab
selection. Waiting holds no edit/Room/run lease; backgrounding suspends it and account turnover or
disposal cancels it. The sign-in gate distinguishes a completed authentication waiting on sync
from a failed sign-in and disables repeated authentication/continuation during the countdown.

Settings manual sync/recovery actions require Connected, no foreground-authentication requirement, and idle account/sync
operations. Reauthenticate requires Connected, available sign-in configuration and idle operations. Cancellation stays
available during owned account/sync work. These are UI gates only: existing account/engine admission and normal
refreshable access expiry remain authoritative. Automatic foreground/edit-triggered sync never consults this cached
observation, so old reachability cannot permanently prevent later synchronization.

`AccountSettingsContent` connects account controllers to `AccountStatusPresentation`, which chooses one explanation
per action without deriving success from absent metadata. Sync contains Pending changes, Last success, Sync now and
conditional sync/recovery messages. Ordinary upload/download/retry failures display Sync failed. Account shows Signed in,
Reauthenticate with a Material account icon, and conditional authentication messages/cancellation; authentication errors
do not replace the signed-in status of an admitted account. Resolve rejected changes is conditional on read-only scoped
validated rejection presence, with fresh typed preparation and exact captured confirmation before discard. The session
facade owns the Room queries and revalidation; composables never read DAOs. A later actual successful sync can clear a
superseded failed authentication attempt without clearing unrelated editor/identity/metadata errors. See
[ordinary recovery](sync/engine.md#confirmed-ordinary-recovery) for the retained safeguards.

Edge-to-edge rendering is enabled. Full-screen custom top bars apply status-bar padding; their content generally
consumes scaffold bottom padding. Tab screens consume full scaffold padding. Set tables use the compact
`BasicTextField` control. Weight uses decimal input, repetitions numeric input, and shared locale-aware formatters.

The application always uses a fixed dark palette, independent of the device theme and wallpaper. The native launch
background matches Compose, and `MainActivity` explicitly requests light status/navigation-bar icons. Catalogue fields
and selections use dark-safe theme colors.

The exercise catalogue, custom-exercise editor, workout landing, history, Settings and active-workout screens follow the dark design system. `ui/theme/WorkoutDesign.kt` scales their 448-unit
width to the available width, centering the 402-unit content area. All child offsets use that same scale and origin:
6-unit grid increments, 48-unit rows, 24-unit spacers, and 20-unit text with a 24-unit line height. These are proportions,
not physical screen pixels. Android system-bar insets are handled separately, and rows grow in grid steps for larger
system text. The bundled JetBrains Mono fonts and uniform typography are scoped to these screens, including their nested
menus and all app-owned dialogs; other screens retain normal Material typography. Font files are unmodified JetBrains Mono 2.304;
the redistribution license is bundled at `assets/licenses/JetBrainsMono-OFL.txt`.
`WorkoutGridLabel` places centered text blocks at the nearest six-unit horizontal position; wrapped lines share a
start edge. Grid action buttons use explicit grid row heights rather than Material's font-dependent content padding.

`ui/components/GymDialog.kt` supplies every app-owned modal with a square outlined surface and bracketed text actions.
Dismiss/clear actions align left, confirm actions right; a single action aligns right. Actions stack when their combined
width no longer fits, and the body scrolls without hiding the footer. `WorkoutContentStyle` shares the grid typography
without requiring a screen-sized container. Feature composables retain their callbacks, draft state, enabled flags and
confirmation ownership. See the complete [dialog inventory](dialogs.md).

The Exercises tab uses a surface title strip with Create, an outlined search field, bracketed Equipment/Muscles
controls and outlined three-line catalogue panels. Titles open detail; custom exercise menus retain edit/delete
and confirmation. Equipment and muscle selectors expand as an overlay beneath the controls, with two-column
scrolling options and fixed Clear/Show actions. Selecting options changes only a transient draft; Show applies it.
Closing, switching categories or Back discards the draft. Applied categories use the selected blue surface and accent
border while retaining stable bracketed names; accessibility state includes the selection count. Matching remains OR
within each category and AND between categories. The panel is capped at the reference's 492-unit height, leaving
Clear/Show outside its scrolling options. Category controls stack and options use one column when enlarged text no
longer fits two columns. Option heights follow the grid and grow for wrapped labels; they do not request intrinsic
measurement from subcomposed layouts. Opening a category clears search focus to dismiss its keyboard.
`ExerciseCatalogueContent` shares the search, inline filters and outlined panels between the Exercises tab and the
workout-add, routine-add and replacement pickers. It receives values and callbacks; draft filter state stays local.
Picker panels make the entire row selectable, using a blue surface, accent border and check for selection, with
checkbox semantics for adding and radio-button semantics for replacement. Selection survives search/filter changes.
Pickers share a monospaced title strip with Cancel and a rectangular confirmation action, disabled without a valid
selection; only confirmation commits the selection. Back dismisses an open filter draft before leaving the picker.
Custom-editor selection dialogs use the same modal surface and square outlined option cards; selected cards use the
navigation-selection blue. Clear selection changes the draft and Done applies it. Search and applied filters/list
position still survive tab navigation.

Custom-exercise creation and editing share the monospaced grid, flat surface toolbar and rectangular Save action.
Square outlined panels contain muted field labels, editable name/instructions and bracketed blue selector values.
Focused text panels use the accent outline. Labels and selected values wrap as needed;
the form scrolls above the keyboard while Save remains in the toolbar. Explicit Save, name validation, selector drafts
and discard confirmation retain their existing state-holder paths.

The landing page has a full-width surface-colored title strip, rectangular Start empty workout action, and a Routines
header with an inline Create action. Its centered 402-unit content uses a 6-unit horizontal / 12-unit vertical grid,
48-unit rows and 24-unit gaps. Routine cards show a bracketed clickable title leading to read-only detail and an
independent chevron that toggles inline Edit/Delete actions for that card. Start fills the bottom row; deletion retains
confirmation. Long names wrap and enlarged text grows rows. Expansion is transient, keyed by routine ID.

The active workout uses a full-width surface toolbar, with Finish aligned to the content edge and a blue collapse
chevron aligned with the inset title text. A 24-unit gap precedes the 48-unit surface title field. Each exercise's
blue chevron toggles an inline action row with `[Replace]` flush left, `[History]` centered and `[Delete]` flush right,
without hiding its notes, configured rest or sets; expansion
is transient and keyed by entry ID. Delete retains its confirmation. Configured rest uses a bracketed text action
without a timer icon, and `[Cancel workout]` uses the shared destructive color.
If enlarged action labels no longer fit across the row, they stack while retaining their left/center/right alignment.
Workout and history actions share `WorkoutInlineActions`, including accessible action names and destructive color.

Routine creation and editing use the workout screen's monospaced grid, surface toolbar and name field, separators,
bracketed rest action, rectangular Add set/Add exercise buttons and compact outlined inputs. Cancel and Save remain
in the fixed toolbar; Save is disabled without exercises and a blank name still prompts. Exercise sections expose
one red `[Delete]` action with confirmation. The three-column set table uses `# / WEIGHT / REPS`, with no trailing
delete button or completion control. Its fields stay centered under their headings and use the workout's compact
widths: 84/60 units for weight/repetitions, growing to 144/96 with enlarged text.
Name, configured rest and set values retain their existing autosave paths;
Cancel preserves edits to saved routines and deletes a new draft through the app state.

Read-only routine details follow the workout summary layout: a fixed surface toolbar, bold routine name, outlined
exercise panels with compact rows, and a fixed rectangular Start routine action. The back arrow optically aligns with
panel text. Configured rest remains a muted exercise-level label; routine sets use the shared `# / WEIGHT / REPS`
table with one grid-snapped column plan across panels. Starting retains the existing app-state replacement flow.

The workout table has `#`, `WEIGHT`, `REPS` and completion columns; the unused previous-value placeholder is removed.
Incomplete rows use outlined compact editors and a blue completion square containing a dark check. Completed rows use
the dark surface color, borderless editors and a light check, and remain editable. Number badges are 36-unit squares
with 18-unit text; numeric columns and checks retain the same centers in both states. Completion has a minimum 48dp
touch target around its compact visual. The bottom timer aligns rectangular blue -15/+15/Skip controls to the content
gutters and exposes remaining progress through accessibility semantics. Its lifecycle and persistence remain app-state owned.
Workout and routine sets share `SwipeToDeleteSetRow`: swiping left reveals a red area, crossing half the width gives
haptic feedback, and releasing beyond it requests deletion. Short/cancelled swipes and refused edit admission restore
the row. Rows keep their set identity through refreshes, use the latest deletion callback and offer an accessibility
delete action. `CompactSetTextField` is shared by both editors and remains a `BasicTextField`.

`[History]` opens the selected catalogue exercise's read-only history through `GymAppState`, then returns to the
same active workout with toolbar or system Back. The app-owned rest timer continues during browsing. The screen uses
the catalogue's square outlined panels and monospaced grid, with compact 24-unit rows and 12-unit vertical panel
insets and an extra 24-unit gap before the set-table header. Rows grow for wrapped or enlarged text.
Each panel shows a workout name, local completion date
(start date for legacy rows without a finish timestamp), nonblank exercise notes and numbered weight/repetition rows.
Workouts appear newest first; repeated occurrences of the exercise share a panel, with sets in entry/set order.
Only completed sets in visible, finished workouts belonging to the selected account are included. Weight is labeled
in kilograms. Empty, unavailable and retryable-error states are explicit.

The History tab uses the same surface heading, centered 402-unit content, square 2-unit outlined panels and 24-unit
panel gaps. Each workout keeps its detail-opening card action and bracketed title. A blue chevron independently
toggles `[Save]`, `[Perform]` and `[Delete]` beneath the title; these retain save-as-routine, perform-again and confirmed
deletion behavior. Expansion is transient and keyed by workout ID. Completion date and time occupy separate compact
rows. Each exercise has a full-width `count × name` row followed by `Best set: weight × reps`; long names wrap.
Duration and volume also use separate labeled rows. Content rows use the exercise-history panel's compact spacing,
with 24-unit section gaps; labels and actions grow for enlarged text. Scaffold padding reserves the active-workout panel.

Opening a workout from History shows a read-only Workout summary with the exercise-history screen's surface toolbar,
monospaced grid and compact outlined panels. Completion date/time and duration/volume use separate rows. Each exercise
panel contains its name, optional notes and a `# / WEIGHT / REPS / REST` table. Rest is recorded elapsed time, with a dash
when absent; each exercise's final set has a blank rest cell, including single-set exercises. This does not change
stored rest values. A fixed rectangular Perform again action retains the existing cloning/navigation behavior.
Both history toolbars optically align the back arrow's visible edge with the text inside their outlined panels.
`ui/history/HistorySetTable.kt` supplies the aligned set tables for both history screens; the exercise history keeps
its three columns. Each screen computes one shared column plan across all its panels. Widths grow in six-unit steps
for long values and enlarged text, with left-aligned headings and values at identical column starts; compact rows
retain the twelve-unit vertical rhythm. Tables scroll horizontally when more width is needed. The summary's Perform
again button uses the shared grid action layout. Toolbar and system Back return to the History tab.

## Domain persistence

[GymRepository](../app/src/main/java/com/dragosfotache/gymtracka/data/gym/GymRepository.kt) is the application-facing
persistence entry point. IDs are client-generated UUID strings. Child positions are consecutive and zero-based;
displayed numbering is one-based. Weight is kilograms; volume and best-set summaries are calculated rather than stored.
Negative repetitions and negative/nonfinite weights sanitize to null; configured rest clamps to nonnegative values.
Stale set inputs cannot change their stored parent or position.

### Catalogue and custom exercises

- `muscle_groups`, `equipment`, and `exercises` store definitions; relationship tables store muscle roles and optional
  equipment quantities. The bundled catalogue contains 899 exercises.
- Bundled exercises are immutable. Custom creation, updates, deletion, and relationship replacement are guarded by
  `is_user_created` and scoped ownership.
- The custom editor creates `reps` exercises with at most one equipment item and one primary muscle, plus secondary
  muscles. Editing preserves imported metadata and equipment quantity that the form does not replace.
- Summary queries bulk-load relationships. Detail, picker, routine, and history joins resolve current custom names.
- Catalogue checksums, mapping and parity tooling belong to the [shared catalogue package](../shared-docs/catalogue/README.md).
  Third-party notices are packaged in `app/src/main/assets/third_party_notices/`; the app has no notices screen.

### Workouts

| Table | Stored values |
|---|---|
| `workouts` | UUID, name, start time, optional finish time, active/finished state, sync metadata |
| `workout_exercises` | UUID, parent/catalogue reference, position, notes, configured rest seconds (default 30) |
| `workout_sets` | UUID, parent, position, nullable repetitions/weight, completion, nullable actual elapsed rest seconds |

Adding selected exercises is atomic. For each exercise, the repository copies completed set inputs and configured rest
from its most recent finished history, resetting completion and recorded rest. With no history it creates one empty
set and the default rest duration. Invalid or invisible selections fail before inserts.

Finishing requires at least one completed set with valid sanitized repetitions and weight. One transaction removes
all other sets and newly empty entries, renumbers survivors, and marks the workout finished. Finished history is
read-only. Perform-again creates new IDs from an eligible finished source. Starting an empty workout, performing again,
or starting a routine replaces existing active work atomically; UI navigation and timer cancellation follow success.
Failures preserve the previous active workout and presentation.

Replacing an exercise changes only its catalogue reference. Entry ID, notes, rest configuration, sets, and timer
association survive. Entry/set removal and sibling renumbering are transactional. History summaries use two fixed DAO
queries; full details load on demand.

Exercise history uses `ExerciseHistoryStateRepository.exerciseHistory` and one joined set query after validating the
catalogue reference, all inside the repository's fenced read transaction. It does not load unrelated workout graphs.
The state holder observes projection epochs, ignores older reads and clears visible history on session revocation.
The read adds no schema or sync storage and does not mutate the active workout.

### Routines

`routines` stores UUID, name, draft state, and sync metadata. `routine_exercises` stores parent, exercise, position,
and configured rest. `routine_sets` stores parent, position, and nullable repetitions/weight; it has no completion
or actual elapsed rest. Drafts are excluded from saved lists. Adding an exercise creates one empty set.
Draft promotion, child changes/renumbering, saving a workout as a routine, and starting a workout from a routine
are transactional. Starting a routine creates fresh workout/entry/set IDs and resets workout-only values.

### Scope and deletion

Repositories bind an immutable account/session generation and check it inside Room transactions. Account-root
repositories additionally check process revocation before effects, before commit, and after result dispatch.
Bundled catalogue is global; user roots and referenced custom exercises are scoped to their owner.

Account-owned root deletion removes children/relationships as applicable, retains a hidden root tombstone, and captures
a delete request in the same transaction. Referenced custom exercises cannot be deleted. Local compatibility paths
hard-delete and use foreign-key cascades. Account replacement, finishing, cloning, relationship changes, and capture
remain transactional. See [sync storage](sync/storage.md) for generations, tombstones, and exact outbox identity.

## Room database and migrations

[GymDatabase](../app/src/main/java/com/dragosfotache/gymtracka/data/gym/GymDatabase.kt) is version **15**.
Room copies `app/src/main/assets/databases/gym.db`, a **version-1 source catalogue**, into private storage and applies
registered data-preserving migrations. The asset contains no user-data tables. No destructive fallback is configured.
[Schema snapshots](../app/schemas/README.md) cover versions 1–15.

| Migration | Schema change |
|---|---|
| 1 → 2 | Workout, workout-exercise and workout-set tables |
| 2 → 3 | Non-null workout name, backfilled from the local start hour |
| 3 → 4 | Workout-exercise notes |
| 4 → 5 | Nullable actual set rest |
| 5 → 6 | Configured workout-exercise rest, backfilled to 30 seconds |
| 6 → 7 | Routine tables |
| 7 → 8 | `exercises.is_user_created`, default false |
| 8 → 9 | Account/revision/generation/state/deletion metadata; outbox, cursor, device/session and catalogue state |
| 9 → 10 | Independent canonical ledger, generation watermarks and account publication bookkeeping |
| 10 → 11 | Snapshot stage headers, whole records and continuation/page evidence |
| 11 → 12 | Singleton account authority and preparation journal |
| 12 → 13 | Immutable local import source/target and exact ordered request evidence |
| 13 → 14 | Import authority lineage and accepted-batch receipts |
| 14 → 15 | Outbox retry cause, conservatively defaulted to unknown for existing delays |

Canonical identity is keyed by account, family and root. Domain UUID primary keys remain global within each physical
table; publication refuses incompatible local, bundled, or foreign identities rather than overwriting or remapping them.
The additive sync tables preserve prior rows, exact requests, and sequence high-water values. Migrating a database
does not fabricate account activation, server records, acceptance receipts, consent, or a complete baseline.

## Finite edits and publication barriers

[FiniteEditCoordinator](../app/src/main/java/com/dragosfotache/gymtracka/ui/FiniteEditCoordinator.kt) is shared by the
app shell and all mutating feature factories. Inputs are snapshotted and registered synchronously before optimistic
draft changes. Refused, disposed, paused, or cancelled callbacks cannot accept work. Accepted edits survive feature
composition disposal; loads, Room observers, elapsed-time tickers, and countdowns stay outside the finite drain.

Normal edits and terminal rest writes commit in acceptance order. Exclusive finish/discard/active-replacement work
reserves admission, drains predecessors, and excludes only its own registration when draining again. External drains
still observe it. Failures remain visible in acceptance order. A successful same-identity retry or superseding edit
can settle nonterminal evidence; unrelated writes and disposal do not erase failures. Terminal rest is never retried
automatically. `committedEdits` advances once per successfully settled tracked write and saturates at its maximum.

A publication lease pauses admission and drains accepted work. It binds the exact account, generation, cache epoch,
and process. Terminal admission after issuance invalidates the lease before persistence; close releases the pause.
Failed acquisition and cancelled result delivery also release it. Open explicit-save custom editors refuse normal
account transitions without silently saving or discarding drafts; emergency invalidation hides the app immediately.
The account runtime supplies real edit/timer barriers to activation and publication.

## Projection observation and feature refresh

`GymRepository.observeProjectionEpoch()` registers Room invalidation before its first fenced read and emits distinct
selected-account `materialized_epoch` values. Baseline/dirty/outbox progress and other-account epochs are not
publication signals. Missing metadata yields zero; invalid counters fail closed. Every reread checks the immutable
session. Revocation ends the stream; it never follows a different account. Local compatibility streams emit a fenced
zero. Observation is read-only and grants no account or engine authority.

Feature states collect in their own scope and launch guarded latest-generation reloads. Autosaved-field reloads drain
accepted writes without self-joining; pure catalogue and action-list reads do not wait for unrelated edits. Accepted
drafts, focus, filters, dialog values, and surviving picker selections are preserved. Reference labels use current
custom joins even when the parent revision is unchanged. Errors expose `refreshFailed`; stale sessions clear selected
models without rebinding. The explicit-save custom editor retains a missing target's presentation with failure.

Set rows keep stable ID keys. Numeric text retains its raw spelling when its parsed value matches persisted data;
changed values replace it. Rest dialogs snapshot draft values per opening. Shell refresh updates active names,
selection and the active pill. Active-ID turnover retires old consent and returns old active/picker flows to Workout;
missing history/routine/custom selections use their corresponding fallback. A refresh never starts a timer or edits data.

## Rest-timer lifecycle

[GymAppState](../app/src/main/java/com/dragosfotache/gymtracka/ui/GymAppState.kt) owns `ActiveRestTimer`, which captures
session, workout, entry, completed set, timer instance, selected duration, and monotonic elapsed-realtime start.
Completion starts it only after an actual persisted incomplete-to-complete transition remains valid after reload.
The countdown updates every 100 ms, vibrates at two seconds, one second, and expiry, and holds a timed partial wake lock.
Manifest permissions are `VIBRATE` and `WAKE_LOCK`; vibration uses alarm audio attributes.

Skip, expiry, replacement, and finish claim whole elapsed seconds at most once per timer. Terminal writes join the
finite edit order and survive countdown cancellation; a replacement countdown can start immediately. Ownership guards
prevent stale callbacks, duplicate writes, or releasing another timer's lock. Finish waits for terminal success.
Unchecking stops only the matching timer without recording rest. Disposal cancels owned work and releases hardware.

Before publication removes or invalidates the source set, the runtime claims and drains rest while that row still
exists. Newly captured rest then protects the entire local graph through the outbox check. Rename/reference-only
replacement preserves a valid timer. Unsupported invalidation faults the exact account handle without writing late
rest into history or silently dropping it. Countdown state is not reconstructed after process death.
