# GymTracka Android agent guide

GymTracka is a single-module (`:app`) Kotlin Android application built with Jetpack Compose, Material 3, and Room. The namespace/application ID is `com.dragosfotache.gymtracka`; the minimum SDK is 24.

This file contains technical rules for Android changes. Architecture, sync, and testing references live in `docs/`; cross-project product behavior and backend/sync contracts live in the nested `shared-docs/` repository. Do not duplicate either here.

## Read the relevant documentation

Start with [`docs/README.md`](docs/README.md) for the documentation index. Documentation describes the checked-in implementation; future features, unresolved issues, and verification gaps belong in [`docs/future-improvements.md`](docs/future-improvements.md).

Android-only documentation belongs in this repository's `docs/`. The nested `shared-docs/` repository contains only material shared with the backend project. Before changing an area, read the relevant sections of its authoritative source document:

- Product behavior, screen flows, UI conventions, and known limitations: [`shared-docs/docs/product-behavior.md`](shared-docs/docs/product-behavior.md).
- Android structure, Room schema/migrations, repositories, state holders, and persistence invariants: [`docs/architecture.md`](docs/architecture.md).
- Test layers, commands, fixtures, coverage boundaries, and CI: [`docs/testing-strategy.md`](docs/testing-strategy.md).
- Current sync capabilities: [`docs/sync/README.md`](docs/sync/README.md), with focused references for [storage](docs/sync/storage.md), [engine](docs/sync/engine.md), and [authentication/recovery](docs/sync/auth-and-recovery.md).
- Backend domain contract, proposed server schema, offline sync, and HTTP API: [`shared-docs/docs/backend-contract/README.md`](shared-docs/docs/backend-contract/README.md).

Read the sections and code needed for each concrete decision; follow dependencies when an invariant is unclear rather than recursively reading the entire documentation tree. For backend or sync changes, read the applicable contract sections and referenced wire schemas/types before changing behavior. Android code is the authority for current implementation; the shared contract is the authority for finalized cross-project requirements. Keep affected documentation consistent with intentional changes, within the requested repository scope.

## Code navigation and search

- Prefer `mcpls` language-server tools for code navigation, symbol search, definitions, references, diagnostics, formatting, and refactoring.
- Use text tools such as `rg`, `grep`, `find`, `sed`, and `cat` only when `mcpls` cannot provide the information needed for the task, such as reading full documentation or exact file contents.

## Build and verification

- Run commands from this repository root.
- Minimum Kotlin/Room compilation check: `./gradlew :app:compileDebugKotlin --console=plain`.
- JVM tests: `./gradlew :app:testDebugUnitTest --console=plain`.
- Debug build: `./gradlew :app:assembleDebug`.
- Instrumentation and migration tests require an emulator/device: `./gradlew :app:connectedDebugAndroidTest --console=plain`.
- Run the narrowest relevant tests while iterating, then the broader checks appropriate to the change. Run `git diff --check` before finishing.
- Room/KSP has pre-existing warnings about missing foreign-key indexes on catalogue join tables. Do not treat them as a failure unless the change touches those tables.
- For every Room entity change, bump the database version, add a data-preserving migration, update exported schema snapshots, and compile with KSP. Never use destructive migration fallback.

## Architecture boundaries

- Keep `MainActivity.kt` limited to Android/Compose setup. `GymApp.kt` owns the root scaffold and destination rendering; `GymAppState.kt` owns app-wide navigation, active-workout coordination, and the rest-timer lifecycle.
- Feature UI belongs under the matching `ui/exercises`, `ui/workouts`, `ui/routines`, or `ui/activeworkout` package. Cross-feature formatting belongs in `ui/util`.
- The app intentionally uses composition-scoped state-holder classes, not Android `ViewModel`, a dependency-injection framework, or a navigation library. Do not introduce one incidentally.
- Preserve unidirectional data flow: state holders expose observable state and mutation methods; screen composables connect state to UI; reusable and leaf composables receive values and callbacks.
- UI code must use repository/state interfaces. Do not construct Room databases or repositories inside leaf components and do not call DAOs from composables.
- `GymRepository` is the production application-facing persistence entry point. Extend its narrow feature interfaces in `FeatureRepositories.kt` when a state holder needs new behavior so JVM tests can keep using hand-written fakes.
- Keep transient presentation state—open menus, confirmation visibility, field focus, draft dialog values, and swipe animation—inside the relevant composable. Persist domain edits through the state holder/repository.

## Persistence and domain safeguards

- The bundled `app/src/main/assets/databases/gym.db` is a version-1 source asset. Room copies it into app-private storage and migrates the runtime database. Do not edit the asset to add user-data tables.
- The runtime Room database is currently version 15. It combines the read-only bundled catalogue with custom exercises, workouts, routines, and sync storage foundations.
- Bundled exercises are immutable. Creation, editing, and deletion apply only to rows marked `is_user_created`; keep repository/DAO guards intact.
- IDs are client-generated UUID strings. Storage positions are zero-based and consecutive; UI numbering is one-based.
- Weight is kilograms. The selected rest countdown (`rest_duration_seconds`) and actual elapsed rest (`rest_seconds`) are different values.
- User editing is persistence-first. Workout names, notes, rest durations, sets, completion, and routine edits autosave. New mutable controls should follow this behavior.
- Multi-row behavior must remain transactional. This includes finishing a workout, cloning/performing again, starting from or saving as a routine, adding exercises with history, relationship replacement, and deletion plus sibling renumbering.
- Finishing requires at least one completed set with valid repetitions and weight, removes other sets and newly empty exercises, renumbers survivors, and only then marks the workout finished.
- Replacing an exercise changes only its catalogue exercise reference. Preserve the workout-exercise ID, notes, rest duration, sets, and any timer association.
- Account-owned root deletion retains a tombstone and captures a delete command while removing children/relationships as applicable. Local compatibility paths hard-delete and use foreign-key cascades. Preserve scoped reference guards and transactional capture; consult the sync contract before changing deletion semantics.
- Derived values such as total volume are calculated, not stored.

## Compose and interaction rules

- Edge-to-edge rendering is enabled. Full-screen custom top bars must apply `Modifier.statusBarsPadding()`. Screens with such a top bar generally consume only the root scaffold's bottom padding; tab screens consume the full scaffold padding.
- Use Material icons from `material-icons-extended`, never Unicode glyphs as UI icons.
- Preserve the established compact controls in workout/routine set tables. `CompactSetTextField` is deliberately a `BasicTextField`; replacing it with `OutlinedTextField` causes sizing and clipping problems.
- Weight input uses `KeyboardType.Decimal`; repetitions use `KeyboardType.Number`. Use the locale-aware helpers in `WorkoutFormatters.kt` rather than ad hoc parsing/formatting.
- Rest-timer completion, skip, replacement, or workout finish must persist elapsed whole seconds exactly once. Preserve the timed partial wake lock and alarm-usage vibration behavior while a timer runs with the screen off. The required manifest permissions are `VIBRATE` and `WAKE_LOCK`.
- Keep accessibility semantics/content descriptions appropriate when adding controls. Prefer stable semantics over tests coupled to incidental layout structure.

## Testing conventions

- Use real in-memory Room databases for DAO/repository transaction, cascade, query, and aggregate behavior. Do not mock DAOs for these tests.
- Use hand-written repository fakes and `TestScope`/virtual time for state-holder tests. Do not use sleeps.
- Keep production constructor paths working while injecting repositories, scopes, clocks, UUID providers, or hardware boundaries only where deterministic behavior warrants it.
- Test observable behavior and domain invariants, not private functions or implementation trivia.
- Migration tests must cover every supported starting version through the current schema and a copied bundled v1 asset. Preserve exported schema JSON files in `app/schemas/`.
- Put pure matching/formatting logic in small `internal` helpers when that makes it testable; do not expose UI internals solely for coverage.

## Code style and change discipline

- Follow existing Kotlin formatting, including trailing commas in multiline calls, annotations, and queries.
- Prefer focused private composables, small state-holder methods, explicit names, and narrow repository methods.
- Keep user-visible terminology and persistence terminology precise: workout versus routine, configured rest versus recorded rest, and active versus finished.
- Avoid unrelated cleanup. Preserve existing user changes in this working tree and inspect the diff before editing overlapping code.
- Keep Android architecture, schema, sync, and testing descriptions current in `docs/`; keep proposed improvements in `docs/future-improvements.md`. Shared product and sync/API requirements belong in `shared-docs/docs/` and should be changed only within an explicitly requested shared scope. Do not duplicate those references in this guide.
