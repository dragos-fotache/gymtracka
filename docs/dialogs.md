# Android dialog inventory

The app has **21 dialog variants across 19 render sites**. Custom-exercise deletion is rendered from both the
catalogue and detail screen; one selector implementation supplies four custom-editor variants.

All use [`GymDialog`](../app/src/main/java/com/dragosfotache/gymtracka/ui/components/GymDialog.kt): a square dark
surface, the shared 2-unit outline, monospaced text, and bracketed text actions. Dismiss/clear actions align left;
confirm actions align right. A single action aligns right. Long actions stack when needed while retaining those
edges, and long bodies scroll independently of the actions. Destructive actions are red; unavailable actions stay
disabled. Brackets are presentation only.

| Dialog / trigger | Left action | Right action | Source |
|---|---|---|---|
| Discard the current workout | `[Cancel]` | `[Discard]` | [App dialogs](../app/src/main/java/com/dragosfotache/gymtracka/ui/GymApp.kt) |
| Finish a workout, discarding unfinished sets | `[Cancel]` | `[Yes]` | App dialogs |
| Attempt to finish without a completed set | — | `[OK]` | App dialogs |
| Start an empty workout, repeat a workout, or start a routine while another workout is active | `[Cancel]` | `[Yes]` | App dialogs |
| Workout complete summary | — | `[Done]` | App dialogs |
| Configure rest duration in a workout or routine | — | `[Done]` | [Rest timer](../app/src/main/java/com/dragosfotache/gymtracka/ui/activeworkout/RestTimerComponents.kt) |
| Remove an exercise and its sets from an active workout | `[Cancel]` | `[Delete]` | [Workout exercise](../app/src/main/java/com/dragosfotache/gymtracka/ui/activeworkout/WorkoutExerciseComponents.kt) |
| Delete a finished workout | `[Cancel]` | `[Delete]` | [Workout history](../app/src/main/java/com/dragosfotache/gymtracka/ui/workouts/WorkoutHistoryScreen.kt) |
| Save a finished workout as a routine | `[Cancel]` | `[Save]` | Workout history |
| Routine saved confirmation | — | `[Done]` | Workout history |
| Delete a routine | `[Cancel]` | `[Delete routine]` | [Routine screens](../app/src/main/java/com/dragosfotache/gymtracka/ui/routines/RoutineScreens.kt) |
| Save a routine without a name | — | `[OK]` | Routine screens |
| Remove an exercise and its sets from a routine | `[Cancel]` | `[Delete]` | Routine screens |
| Delete a custom exercise | `[Cancel]` | `[Delete]` | [Catalogue](../app/src/main/java/com/dragosfotache/gymtracka/ui/exercises/CatalogueDesign.kt), [exercise screens](../app/src/main/java/com/dragosfotache/gymtracka/ui/exercises/ExerciseScreens.kt) |
| Leave a changed custom-exercise editor | `[Cancel]` | `[Discard]` | Exercise screens |
| Save a custom exercise without a name | — | `[OK]` | Exercise screens |
| Choose a primary muscle group | `[Clear selection]` | `[Done]` | Exercise screens |
| Choose secondary muscle groups | `[Clear selection]` | `[Done]` | Exercise screens |
| Choose equipment | `[Clear selection]` | `[Done]` | Exercise screens |
| Choose a category | `[Clear selection]` | `[Done]` | Exercise screens |
| Import a local active workout that may replace the cloud workout | `[Cancel]` | `[Import]` | [Account startup](../app/src/main/java/com/dragosfotache/gymtracka/ui/account/AccountStartupApp.kt) |

Selector changes remain drafts: Clear selection clears the draft, Done applies it, and outside/system Back dismissal
discards it. The rest picker keeps its three-row wheels and minute/five-second choices. Save-as-routine keeps its
name validation and in-progress controls; account import keeps its exact pending-decision callbacks.

This inventory covers app-owned modal dialogs. Catalogue filters are inline overlays, exercise menus are dropdowns,
and account recovery prompts are inline content. Android/Google credential UI is platform-owned.
