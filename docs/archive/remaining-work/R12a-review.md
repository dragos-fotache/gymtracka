# R12a acceptance — real finite-edit admission and timer barriers

**Accepted 2026-10-07.** [Specification](R12a-task.md); [finite R12 plan](../../remaining-work-roadmap.md#r12--feature-and-app-reconciliation).
Explicit owner authority ends after this slice's scoped local commit, prepared handoff and owner report. No successor launch
or later implementation. This review records executed evidence, not activation authority.

## Delivered boundary

- One app-owned finite-edit coordinator reaches all mutating feature factories and survives feature composition disposal.
  Inputs and acceptance register before optimistic changes; refused leaf/state callbacks cannot change autosave drafts.
- Normal writes and already claimed terminal rest share acceptance-ordered writes, including immediate/reentrant dispatch.
  Exclusive transitions are externally observable but exclude only their own coordinator-bound registration from internal
  drains, never their timer writes. Cancellation-before-start releases admission and retains evidence.
- Originating acceptance order governs retained failures/cancellation and successful same-key supersession. Different-root
  name keys cannot settle another root's failure; terminal outcomes never auto-retry or disappear on observation/disposal.
- Finish/discard/active replacement drain accepted work before destructive persistence; local replacement failures preserve
  the current timer. Read-only finish requests fence the captured active root after suspension rather than reopening stale dialogs.
- Exact-request publication adapters pause/drain, recheck resulting timer session, require an explicit impact decision,
  preserve valid timers or claim/drain old-set rest, and atomically grant a short lease. Post-grant terminal admission
  invalidates before persistence. Caller/owner cancellation and cancelled result dispatch close unreturned leases.
- Timer ownership snapshots session/root/entry/set/instance. Acquisition/claims are confined to the app owner dispatcher;
  Room-thread lease checks/close remain synchronous and owner-specific. Wake-lock/vibration/elapsed-second and at-most-once
  behavior remain intact. No schema, asset, manifest, credentials, dependency, backend or configuration changes.

The existing publisher remains authoritative for transactional baseline/session/epoch and whole-account outbox protection.
No app engine/publisher, activation/visibility grant, epoch observer, canonical impact reader or scoped remote reconciliation
was constructed. The real canonical-impact decision and remote app/timer integration remain R12c; R09h/core R10 and the
other package gates remain separate. The app still runs signed-out/local-only.

## Independent review and important findings

Fresh native Worker and QA used `meta/muse-spark-1.3-contributor`/high, with disjoint production/test access. Coordinator
`openai-codex/gpt-6.1-sol`/high reviewed complete production/test diffs and new files. Every Gradle ran in an explicit
serialized compile-only Worker, focused-test QA or coordinator acceptance slot. Lint froze all role-owned sources;
pre/post source hashes matched.

Review corrected missing exclusive registration/cancellation evidence, completion-time versus acceptance-time failure
ordering, reentrant tail installation, pre-grant versus granted lease semantics, caller cancellation at empty drains and
lost-dispatch cleanup, cross-normal/terminal ordering, post-drain timer session checks, owner-dispatcher timer claims,
post-disposal timer refusal, stale finish reads and optimistic/swipe refusal. Independent expected oracles cover those
boundaries; production was not exposed to QA and tests were not exposed to Worker.

Initial integration compile failures (test visibility and context-marker/import/return typing) were corrected before the
passing gates. The first focused UI run had an old finish-dialog timing assertion and an expected failing async child
that cancelled its test parent; corrections retained exact no-overtake/rest-order and fail-closed assertions.

The initial `AppFiniteEditGateTest` run hung in its first case after the preceding standalone leaf class passed. It produced
no completed XML and is **not a pass**. Owner restarted the coordinator; live state showed no Gradle client, with an orphaned
test-owned emulator instrumentation process. Only that process was stopped. QA replaced blocking app-drain acquisition
with asynchronous acquisition plus bounded Compose completion waits; teardown joins now fail on timeout instead of
swallowing it. Final device commands also had 45-second per-test and 150-second outer limits. All final cases completed.

## Executed acceptance evidence

Repository-root offline Gradle **9.8.0**, launcher OpenJDK27 and configured Adoptium Java25 daemon, Linux x86_64.
Test-owned emulator37.1.11 with installed API37/Android17 `37.2/google_apis_playstore_ps16k/x86_64` image;
actual boot completion and **16384-byte** page size verified. Normal ADB registration; no personal AVD/device/account.

| Command / check | Actual final result |
|---|---|
| `./gradlew --offline :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest --rerun --console=plain` | Exit0; fresh full offline **969/969 in57 classes**, zero failures/errors/skips. Kotlin/KSP and both test-layer compilation passed. New finite coordinator16, adapter10, shared gate4 cases plus existing app/feature regressions executed. |
| `ANDROID_SERIAL=emulator-5556 ./gradlew --offline :app:connectedDebugAndroidTest --rerun -Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.ui.PausedLeafRefusalTest -Pandroid.testInstrumentationRunnerArguments.timeout_msec=45000 --console=plain` | Fresh **1/1**, exit0, zero failures/errors/skips; visible and real-Room autosave refusal, then success after close. |
| Same device command, class `com.dragosfotache.gymtracka.ui.AppFiniteEditGateTest` | Fresh **2/2**, exit0: normal GymApp root shared-admission refusal and accepted routine autosave surviving editor→picker disposal, including picker persistence. |
| Same device command, class `com.dragosfotache.gymtracka.ui.AppFlowTest` | Fresh **5/5**, exit0: actual startup, custom creation/edit/add, workout edit/finish/history, routine start and repeat/replace/discard flows. |
| `./gradlew --offline :app:assembleDebug :app:lintDebug --rerun --console=plain` | Exit0. Final debug APK assembled by preceding device build; assemble reused that output. Fresh main/unit/device lint analyses/report executed unsuppressed. Zero errors; one existing `NewerVersionAvailable` warning for org.json. |
| Source freeze / preservation / APK | All owned sources unchanged across lint; bundled v1 asset and all12 exported schemas byte-identical to pickup. APK bundled asset equal to source; none of the five new test-class markers in production DEX. APK22437491 bytes. Manifest/backup/configuration unchanged; no new secret/private-data inputs. |
| Repository/document checks | Full scoped diffs/new files independently reviewed; `git diff --check` in both repositories; relative documentation links/anchors checked. Shared-docs tree/index remained clean at unchanged HEAD. |

Device invocations were separate single-class runs, wrapped in `timeout --signal=TERM --kill-after=15s 150s` and followed
by actual nonempty XML class/name/failure/error/skip inspection. Combined device result is **8 distinct cases**, not a
full unfiltered instrumentation pass. Fresh tests reran intended test tasks; cached compilation/assembly is not counted
as a fresh from-scratch build. Existing AGP experimental-option, Gradle deprecation and runner Unsafe notices were not
suppressed. No new Kotlin warning remains.

Test-owned emulator stopped and isolated Android/AVD homes removed after verification. No active build or device slot
transfers. Diagnostic logs were retained outside the repository; no runtime configuration was added to either tree.

## Deferred gates / stop

- Full unfiltered instrumentation, full R12 privacy/epoch/remote app/timer journeys and package integration gates: **R12c closure**.
- Canonical graph impact selection and account-scoped reconstruction: **R12b/c**, not proven by this explicit callback seam.
- Two real Room stores through engine/HTTP: **core R10 before activation**. No backend gate was required/run for this UI-only seam.
- API24, hosted CI, real Google, OEM/cloud transfer, owner phone/laptop/network and deployment: their existing roadmap milestones.
- Legacy local-only disposition remains unresolved; no import, deletion, reassignment, cleanup or credential action inferred.

All R12a required gates passed; no unresolved R12a blocker. Finite R12 remains three slices, with b/c outstanding.
Prepare the durable handoff and scoped local commit, report accepted R12a to the owner, then stop. No push, successor
launch or later implementation under this request.
