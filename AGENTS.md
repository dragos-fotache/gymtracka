# Gym Workout App

This is a single-module Android app (`:app`) written in Kotlin with Jetpack Compose and Material 3. The application ID and namespace are `com.dragosfotache.gymtracka`. The minimum SDK is 24; use APIs compatible with that level.

## Build and verification

- Build the debug app with `./gradlew :app:assembleDebug`.
- A quicker Kotlin/Room verification is `./gradlew :app:compileDebugKotlin --console=plain`.
- Room/KSP currently emits pre-existing warnings about missing foreign-key indexes on catalogue join tables. Do not treat those warnings as a failure unless modifying those tables.
- Compose Material icons come from `androidx.compose.material:material-icons-extended`; use Material icons rather than Unicode glyphs for UI icons.

## App structure and navigation

- The UI currently lives in `app/src/main/java/com/dragosfotache/gymtracka/MainActivity.kt`; it uses Compose state and small private composables rather than a ViewModel/navigation-library architecture.
- Edge-to-edge is enabled. Custom top bars must account for system status bars (`Modifier.statusBarsPadding()`). Nested active-workout and picker screens apply only the app-level scaffold's bottom inset (`innerPadding.calculateBottomPadding()`), because their own top bar handles the status-bar inset; other screens apply the full `innerPadding`.
- The app opens on the **Workouts** tab. The bottom navigation has only **Exercises** (dumbbell icon) and **Workouts** (auto-mirrored runner icon). It is 80dp high and is shown only on those two tab screens; there is no Home/Hello Android screen.
- `Destination` is local UI state. It contains `Exercises`, `Workout`, `ActiveWorkout`, `AddExercises`, and `WorkoutHistoryDetail`.
- Active-workout and picker navigation is in-memory only. A newly created workout is persisted immediately. The active-workout top bar has a left-aligned down-chevron that returns to Workouts and collapses the workout into the active-workout pill; app relaunch does not yet automatically resume an active workout. An active rest timer is also in-memory, but is hoisted to app-level state so it continues after collapsing the workout.

### Current screens

- **Exercises:** read-only catalogue list. It shares the `ExerciseListItem` visual component with the picker, but has no selection interaction; catalogue item taps can later navigate to exercise details.
- **Workouts:** creates a persisted empty workout with `Start empty workout`. Below it, finished workouts are shown as compact, faintly outlined cards with their name, completion date, per-exercise set count and best set, duration, and total volume. Tapping a card opens its read-only detail screen; its three-dot menu offers red `Delete` with confirmation. If an active workout exists, starting a new one confirms before discarding it.
- **Workout history detail:** a read-only, full-screen view with a back arrow, workout title/date, exercise sections, numbered weight × reps rows, recorded per-set rest time when available, duration, and total volume. It intentionally does not show 1RM, PRs, or comments.
- **Active workout:** fixed top bar with the collapse chevron, elapsed time, calculated total volume, and Finish. The scrollable content begins with an editable workout name, then exercise cards, then `+ Add Exercise`, then a red Cancel workout action. Exercise headers have a right-aligned red delete button with confirmation. Finish requires at least one completed set; it warns otherwise. After confirmation, it persists any active rest timer's elapsed time, discards unfinished sets and any exercises left empty by that cleanup, then saves the finished status and presents a summary with name, duration, and volume. Cancel/discard deletes the workout and its cascaded contents.
- **Exercise picker:** separate multi-select catalogue screen. Its compact `Add exercises` top bar has a right-aligned Cancel action that returns to the active workout without adding selections. Its fixed bottom `Add selected (n)` button inserts the choices and returns to the active workout. A newly added exercise is populated with its most recent finished workout's completed set count, weights, and reps when available; those copied sets are incomplete and have no carried-over rest time. Exercises without history receive one empty set. Selected exercise rows use a very light green background and a small circular green check; unselected rows have no selection indicator.
- **Active-workout pill:** shown above bottom navigation when an active workout is left for the Exercises or Workout tab. It is a raised rounded pill with the workout name and live duration on its first row, an active rest countdown on its second row when relevant, and a red discard icon. Tapping the pill resumes the active workout; tapping the discard icon shows the discard confirmation.

### Catalogue list and filters

- Both the Exercises tab and Add Exercise picker use the shared `ExerciseListItem` and the same live filtering behavior. Rows blend into the app background with no elevation; name is prominent, while muscle groups and equipment are muted `onSurfaceVariant` text. Do not show `Primary`, `Secondary`, or `Equipment` prefixes. Primary and secondary groups are joined with ` • `.
- `ExerciseSearchField` is a compact 40dp, light-gray rounded field with a leading search icon and a small circular clear button when text is present. Search updates the list immediately and is case-insensitive across exercise name, muscle groups, and equipment.
- Equipment and muscle-group filters appear as two buttons beneath the search field. They combine with text search; selected values within a category match any selected value, while equipment and muscle filters combine with each other. Active filter buttons are light green, include a compact clear icon, and show up to two selected names; three or more display `Equipment (n)` or `Muscles (n)`.
- Filter dialogs use compact, two-column tappable cards with no checkboxes. The dialog background is gray for contrast, selected cards are very light green, and draft selections apply only when `Show results` is pressed. `Clear filters` clears the draft selection.

## Catalogue database

- `app/src/main/assets/databases/gym.db` is a **version 1, read-only source asset** containing the bundled exercise catalogue: `exercises`, `muscle_groups`, `equipment`, and the join tables.
- It is not the database users edit. On first use, Room copies it to the app-private runtime path (`/data/data/com.dragosfotache.gymtracka/databases/gym.db`), then applies migrations. Do not expect newly added app-data tables in the source asset.
- Catalogue entities and DAOs are in `data/gym/GymEntities.kt` and `GymDao.kt`. `ExerciseDao.getAllSummaries()` bulk-loads relationships so the 899-exercise list does not perform per-row queries.

## Workout persistence

`GymDatabase` is Room database version **5** in `data/gym/GymDatabase.kt`. Its runtime database combines the bundled catalogue with user workout data.

### Schema

- `workouts` (`WorkoutEntity`): UUID `id`, persisted `name`, `started_at_millis`, optional `finished_at_millis`, and `status` (`active` or `finished`). Discarded workouts are deleted rather than retained with a separate status.
- `workout_exercises` (`WorkoutExerciseEntity`): UUID `id`, workout FK, catalogue exercise FK, and zero-based `position`. Deleting a workout cascades to its workout exercises.
- `workout_sets` (`WorkoutSetEntity`): UUID `id`, workout-exercise FK, zero-based `position`, nullable `repetitions`, nullable `weight_kg` (`Double`, kilograms), `is_completed`, and nullable `rest_seconds`. `rest_seconds` stores only the actual elapsed rest in whole seconds after a completed set's timer ends or is skipped; it is initially null and does not store the selected countdown duration. Deleting a workout exercise cascades to its sets.
- Workout-specific entities are in `data/gym/WorkoutEntities.kt`; access/query models and transactional detail loading are in `WorkoutDao.kt`.

### Migration rules

- Keep the asset schema at v1. Any new user-data schema must be introduced with a Room version bump and migration(s) in `GymDatabase`.
- Existing migrations are `1 -> 2` (workout tables), `2 -> 3` (non-null `workouts.name`), `3 -> 4` (exercise notes), and `4 -> 5` (nullable set `rest_seconds`). A fresh install therefore copies the v1 asset and runs all four migrations.
- The v2-to-v3 migration backfills existing blank names from `started_at_millis` using local time. Preserve data during future migrations; do not use destructive fallback migration behavior.

### Repository conventions

- `GymRepository` is the application-facing API. It owns UUID creation and should be extended instead of exposing database builders to UI code.
- Creating a workout calls `startEmptyWorkout()` and writes it immediately. Default names are time-of-day based: Morning (05:00–11:59), Afternoon (12:00–16:59), Evening (17:00–20:59), otherwise Night.
- `addExercisesToWorkout()` creates selected `workout_exercises` and their initial sets in one DAO transaction. It copies the completed set count, weights, and reps from each exercise's most recent finished workout when available; copied sets are incomplete with null rest time. Exercises without completed history receive exactly one empty initial set. Preserve this atomic behavior when changing the picker flow.
- Set edits, completion toggles, and workout-name edits are autosaved immediately. New mutable workout controls should follow the same persistence-first behavior.
- `discardWorkout()` deletes the workout immediately; its workout exercises and sets cascade. `finishWorkout()` atomically requires at least one completed set, removes unfinished sets and exercises made empty by that removal, renumbers remaining workout exercises, and then sets the finished status and `finished_at_millis`.
- Deleting a set is transactional: after deletion, the remaining sets for that workout exercise are renumbered to consecutive zero-based positions.
- Deleting an exercise from an active workout cascades to its sets and transactionally renumbers the remaining workout exercises. Deleting a finished workout cascades to all of its workout exercises and sets.
- Finished-workout history is loaded as `WorkoutDetails` through `finishedWorkoutDetails()`; only workouts with `finished` status appear in the history list.
- Total volume is derived in UI as the sum of `weightKg * repetitions` across persisted sets; it is not a database column.

## Active-workout UI conventions

- Exercise cards have no elevated gray container; they blend into the app background.
- Each exercise contains a compact table: headers `SET`, `PREVIOUS`, `WEIGHT`, `REPS`, and a check icon. `PREVIOUS` is currently a static en dash placeholder; historical comparison is not implemented.
- `CompactSetTextField` is a custom `BasicTextField`, not `OutlinedTextField`. This is deliberate: Material text fields enforced a large minimum size and clipped/overflowed in the table. Keep it compact (currently 36dp), centered, and label-free.
- Weight uses `KeyboardType.Decimal`; reps uses `KeyboardType.Number`.
- Empty set rows have light `surfaceVariant` input borders and number/check cells. The check is disabled until both weight and reps parse successfully.
- Completion is reversible and persisted. Completed rows are light green, their check cell is dark green with a white icon, and input borders are removed.
- Pressing a row's check cell clears text-field focus before toggling completion, so a field cursor does not remain visible.
- Set rows support swipe-left deletion. The row moves as a unit to reveal a red delete band and bin icon; crossing half the row width triggers haptic feedback. Releasing beyond that threshold animates the row offscreen and deletes it; otherwise it springs back.
- `+ Add Set` and `+ Add Exercise` are light, rounded `TextButton` actions. Add Exercise belongs in the scrollable content after all exercise cards and immediately before Cancel workout; it is not fixed.
- The Add Exercise button uses the same 4dp horizontal inset as exercise-card content so its width matches Add Set.
- The workout name is a borderless, bold `BasicTextField` beneath the fixed workout top bar; do not replace it with a floating-label field.
- Beneath each exercise's notes field, show a tappable blue `Rest Timer: …` row before the set table. Its dialog has compact minutes (0–9) and seconds (0, 5…55) three-row wheel pickers: the centered gray-highlighted row is selected. The dialog's default is 30 seconds and its Done action is a full-width standard primary button. The picker choice is UI-only until a set is completed.
- Completing a set starts the selected rest countdown unless it is `off` (0:00). The timer bar is shown at the bottom with a primary-color progress line, compact gray `−15`/`+15` adjustment buttons, a countdown centered between those buttons, and Skip separated to the right. Skip, expiry, replacement by a new timer, or finishing the workout hides the timer and saves actual elapsed whole seconds to that set's `rest_seconds`; the selected countdown duration is never persisted. The final cues are 100ms vibrations at 2s and 1s remaining, then a 500ms vibration at expiry; `android.permission.VIBRATE` is required.

## Current limitations / intended future work

- There is no workout-resume flow after process restart despite persisted active workouts.
- Catalogue item taps in the Exercises tab do not yet open a detail screen.
- There is no workout-history editing, 1RM/PR calculation, comments, or validation beyond parsable numeric input.

## Code style

- Follow existing Kotlin formatting: trailing commas in multiline calls and Room annotations/queries; prefer clear private composables and repository methods.
- Keep labels and data model terms explicit: weight is kilograms; positions are zero-based in storage and displayed one-based.
- For any change affecting Room entities, update the database version/migration and compile with KSP before handoff.
