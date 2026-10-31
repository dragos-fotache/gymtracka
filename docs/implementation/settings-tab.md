# Settings tab implementation plan

Status: Slices 1–4 complete and verified (2026-10-25).

Created: 2026-10-25.

## Objective

Replace the Account navigation item and dialog with a full Settings tab containing two sections: Server and Account.
Follow the supplied visual reference: bold section titles, spacious rows, thin dividers, and a vertically scrolling
screen. Keep the app's Material theme, accessibility semantics, and edge-to-edge/scaffold padding conventions.

The user approved Slices 1, 2, 3 and 4 on 2026-10-25. Each slice has its own implementation and verification checkpoint.

## References and findings before Slice 1

- [Architecture](../architecture.md): app navigation, composition-scoped state holders, account binding, and timer ownership.
- [Authentication and recovery](../sync/auth-and-recovery.md): account admission, credentials, preparation, and recovery.
- [Sync engine](../sync/engine.md): foreground/manual demand and confirmed ordinary recovery.
- [Testing strategy](../testing-strategy.md): deterministic state tests, real Room, Compose, and device verification.
- [Shared product behavior](../../shared-docs/docs/product-behavior.md#personal-account-scope) and
  [authentication contract](../../shared-docs/docs/backend-contract/authentication.md): existing account and preservation rules.

Before Slice 1, the Account item opened a dialog rather than selecting a destination. Connection, authentication, and sync
messages are presented independently and can produce redundant or misleading combinations. Review unsent changes is
always offered, although its actual purpose is recovery from definitive server rejection. Its preparation result can
mean that nothing is eligible, but the controller displays that as Sync unavailable.

An interrupted activation also exposes Sign in and Continue without explaining their different roles. Repeating
Google sign-in can supply a new service session to a path expecting the original exact saved session, producing
Account mismatch even for the same account.

## Intended screen

### Server

Show the current connection status and a Recheck button. Status describes reachability from this device, which can
depend on Tailscale; it does not establish global server availability, database health, account authentication, or
successful synchronization.

| Connection state | Presentation and actions |
|---|---|
| Checking | Show Checking… and progress; disable another check, Sync now, and Reauthenticate |
| Connected | Enable account actions as authentication and operation state permit |
| Unreachable | Show Can't reach server; disable Sync now and Reauthenticate; allow Recheck |
| Server error or unexpected response | Explain that the service could not be confirmed available; disable account network actions and allow Recheck |

Check automatically on entering Settings and when the app returns to the foreground with Settings selected.
Use a short bounded timeout, allow only one check at a time, cancel obsolete checks, and prevent late results from
overwriting newer state. Checking the server must not interrupt offline editing or the rest timer.

The backend checkout has no dedicated health endpoint. Use an existing read-only route without credentials:
`GET /v1/sync/pull`. Its expected `401 authentication_required` response confirms that the configured service is
reachable. Validate the expected response through the existing secure fixed-origin transport. The deliberate
unauthenticated probe must not become an account authentication error or trigger credential refresh, Google UI,
account data reads, sync, or mutations. Server errors and unexpected responses must not be reported as Connected.

This approach requires no backend or shared-contract change. Verify the response against the deployed backend during
implementation. Keep the reachability check behind a narrow interface so a dedicated health endpoint can replace it
later if separately introduced.

### Account

Move the existing account information and controls into the Settings screen:

- Authentication status and any actionable authentication error.
- Number of changes waiting to sync and the last successful sync time.
- Sync now, progress, and cancellation while a sync operation runs.
- Reauthenticate, with relevant authentication errors directly beside it.
- Conditional rejected-change recovery and retained-download recovery controls.

Render one clear current explanation instead of stacking Delivery paused, Sync paused, and a generic failure.
Keep connection failures separate from authentication failures. An actual authentication failure can say
Authentication failed. Sign in again to resume sync. Preserve distinct explanations for an expired session, a wrong
account, an open editor, and cancelled work where those distinctions guide the next action.

If the server is reachable but foreground authentication is required, enable Reauthenticate and disable manual sync
until authentication succeeds. Existing refreshable access expiry should continue through the normal refresh path.
Competing account operations remain disabled while owned work runs; cancellation remains available where supported.
Connection state is advisory and never grants account authority or replaces engine admission checks.

Show All changes synced only when the observed queue is empty and there is no unresolved sync problem. Unknown
metadata must remain unknown. Last successful sync is historical information and does not prove current connectivity.

## Implementation slices

Implement the following four slices in order after implementation approval. Each slice is a reviewable checkpoint
and includes its relevant tests and documentation updates. Compile and pass those checks before proceeding to the
next slice, subject to separate approval. All four slices are complete; implementation and verification follow below.

### Slice 1: Settings tab and layout

Dependency: None.

Status: Complete and verified (2026-10-25).

- Add a Settings destination and navigation action. Replace Account with Settings and a Material gear icon.
- Render a real selected tab with the two reference-style Server and Account sections. Replace the Account dialog
  integration with screen content supplied by the account root, keeping account/session ownership in the runtime.
- Move the existing account content into the screen while preserving its underlying behavior for this slice.
  Connection checks arrive in slice 2; do not present simulated connectivity as real status.
- Keep bottom navigation visible and preserve the active-workout pill and running timer when switching tabs.

Verification: Kotlin compilation, focused Compose checks for navigation, section layout and scrolling, and an
emulator check that tab switching preserves the active workout and timer. Update the navigation/structure references.

Complete when Settings is a usable fourth tab and account controls no longer require the former Account dialog.

#### Slice 1 verification

Implementation replaces the former Account dialog with `Destination.Settings`, a selected Material gear navigation
item, and `ui/settings/SettingsScreen.kt`. The account root supplies existing controls through a content slot while
retaining its runtime, lifecycle and operation ownership. The scrolling screen has bold Server/Account headings,
spacious content, thin dividers and full scaffold padding. Bottom navigation and the active-workout pill remain visible.
Navigation keeps its 88dp default height and expands with system font scale; centered labels have spacing to wrap
without clipping, and the pill follows the bar height.
The Server section says **Not checked**, with no simulated connectivity or new action gating.

Verification on 2026-10-25:

| Check | Actual result |
|---|---|
| `./gradlew :app:compileDebugKotlin --console=plain` | Passed, including final layout compilation |
| `./gradlew :app:testDebugUnitTest --console=plain` | Passed: 1,077 tests / 69 suites; zero failures, errors or skips |
| `:app:compileDebugAndroidTestKotlin` and `:app:assembleDebug` | Passed, including the final default-size device run |
| `SettingsTabTest` | 2 passed: four-tab selection, sections, unchecked server state, constrained 200% text scrolling, workout/pill/timer preservation, resume and terminal rest persistence |
| `AccountStartupRootTest` | 9 passed: admitted/private gates, existing activation/import/reauthentication and Settings account presentation |
| `AccountStartupReopenTest` | 4 passed: offline reopening, post-reopen edits, origin and missing-vault privacy |
| `AccountSyncFlowTest` | 3 passed: manual sync, confirmed recovery/cancellation, editor-triggered demand and once-only timer rest |
| `AccountRuntimeTimerPipelineTest` | 6 passed: real scoped runtime, accepted completion, timer/publication protection and refusal of late/invalid writes |
| Full Settings account screen at device font scale 2.0 | The Settings account presentation method passed again; screenshots inspected before/after scrolling |
| Documentation link targets and `git diff --check` | Passed |

Device runs used separate single-class (or single-method) filters on **Pixel_6a / emulator-5554 / API 37**.
Nonempty XML verified **24 distinct device tests**, all passing with zero failures/errors/skips; repeated layout checks
are not additional distinct tests. XML copies and synthetic screenshots were retained for this session under
`/tmp/gymtracka-settings-slice1-verification/`. The final Settings navigation/timer class and enlarged-text account method
were rerun after the navigation layout adjustment. Default-size and 200% screenshots confirm bold section headings,
thin dividers, visible navigation/gear selection, wrapping labels, scrollable account actions and the active timer pill.
The emulator font scale was restored to its original 1.0.

An initial large-text assertion assumed the full emulator viewport required scrolling; the test now uses a constrained
viewport to exercise scrolling deterministically. Screenshot inspection found clipping in the fixed-height navigation
at 200% text; the delivered layout fixes it. Two sync-flow launch attempts executed no tests because the emulator had
stopped or was still booting; they are excluded from the totals above. A fresh boot and verified rerun passed all three
sync-flow tests.

Scope and limitations: Slice 1 adds no Room/schema, backend or shared-docs changes, and preserves existing working-tree
changes. Only the explicitly selected emulator was used; no personal phone data was cleared or touched. Device coverage
is focused API 37 coverage, not a full instrumentation/minimum-API sweep, live Google/deployed-service verification or a
new screen-off hardware endurance check. Timer state/whole-second/once-only hardware ownership is additionally covered
by deterministic JVM tests. Connectivity checks/action gating, account-message/recovery fixes and interrupted sign-in
changes remained deferred to Slices 2–4 during the Slice 1 checkpoint; Slice 2 had not yet started.

### Slice 2: Server connectivity

Dependency: Slice 1.

Status: Complete and verified (2026-10-25).

- Add the composition-scoped connection state holder, narrow server-check interface, and read-only HTTP probe.
- Implement automatic entry/foreground checks, Recheck, timeout, cancellation, duplicate-check prevention, and
  stale-result handling according to the Server section above.
- Derive network-action availability from connection, authentication, and operation state. The expected probe 401
  must not require reauthentication, and an account authentication failure must not imply an offline server.
- Preserve existing foreground/edit-triggered sync admission; an old connectivity observation must not permanently
  prevent future synchronization.

Verification: Deterministic state tests, HTTP response/transport tests, Compose checks for status and disabled actions,
and emulator connectivity transitions. Verify the deployed probe response without sending account credentials.
Update the connection-state and lifecycle documentation.

Complete when Settings automatically checks the configured service, Recheck works, and account network actions
follow the connection-state rules while offline editing remains available.

#### Slice 2 verification

Implementation adds `ServerChecker`, the credential-free `HttpServerChecker`, `ServerConnectionState` and the
Settings Server presentation/lifecycle. The five-second check starts on entry and foreground return, supports Recheck,
refuses duplicates, cancels obsolete work and fences late results. Retiring work retains its slot until it actually
exits; one new entry can request one fresh successor. Probe status never grants account authority or enters sign-in,
credential refresh, sync, Room or account reads.

Manual sync/recovery requires Connected and no foreground-authentication requirement or competing operation.
Reauthenticate requires Connected, configured sign-in and idle account/sync operations. Cancellation stays available.
Automatic foreground/edit demand deliberately ignores cached reachability and retains existing engine admission.
No account-message consolidation, recovery eligibility/outcome change or interrupted sign-in change is included.

Verification on 2026-10-25:

- Kotlin compilation, default Android-test compilation and debug build: passed.
- JVM suite: **1,095 tests / 72 suites**, zero failures/errors/skips, including 18 new state/availability/HTTP tests.
- `ServerConnectionSectionTest`: 1 passed (entry, foreground stop/start, Recheck, disposal and re-entry).
- `AccountSyncFlowTest`: 5 passed (existing manual/recovery flows, connection/auth/button transitions, cancellation
  after connection loss, cached-unreachable editor demand, and timer preservation while checking/leaving/re-entering).
- `AccountSyncStateIntegrationTest`: 17 passed, preserving foreground/edit/manual demand, retirement, handle and
  confirmation behavior independently of connectivity.
- Deployed service: credential-free GET confirmed HTTP 401 plus strict `authentication_required`. Actual Android
  `HttpServerChecker` with normal TLS and fixed origin passed **Connected → Unreachable → Connected** as the selected
  emulator's Wi-Fi/mobile data were disabled and restored. No Google UI, credentials, Room or account data were used.
  A temporary instrumentation driver/source-set init script under `/tmp` performed these three checks; external-service
  dependencies are not added to the ordinary test suite or checked-in sources.
- Regression device classes: `AccountStartupRootTest` 9 passed; `AccountStartupReopenTest` 4 passed;
  `AccountRuntimeTimerPipelineTest` 6 passed; `SettingsTabTest` 2 passed.
- **44 distinct regular device tests** passed in total, zero failures/errors/skips, with nonempty XML checked for each
  exact class on **Pixel_6a / emulator-5554 / API 37**. Separate runs copied XML before later invocations replaced it.
  The 200% font-size connection/authentication transition method passed again and its screenshots were inspected;
  repeated methods and the three one-off deployed-probe scenarios are not additional distinct regular tests.
- Default-size screenshots inspected: Checking/progress/disabled Recheck, Unreachable, Connected, unconfirmed responses,
  authentication required with Connected retained, and the active rest-timer pill while probing. At 200% text size,
  Server/Account content scrolls and Recheck/account actions remain reachable. Font scale was restored to 1.0.
- The temporary deployed-service driver is absent from the normal test APK. Only ordinary offline tests remain in the
  repository's default device source set; no new external-service requirement or skipped external test was added.
- Android documentation link targets, changed/new file whitespace and `git diff --check`: passed. Session XML/logs and
  synthetic screenshots are retained under `/tmp/gymtracka-settings-slice2-verification/`.

The initial malformed-response HTTP fixture reused a connection closed by its truncation case. Each malformed framing
case now uses an independent TLS fixture, proving the actual response was received rather than relying on a stale
socket. The temporary deployed-probe driver initially failed class loading because its Kotlin source directory was
not included; that setup attempt is excluded, and all three actual network scenarios subsequently passed.

Scope: Android only; no Room/schema, backend or shared-docs changes. The existing working tree, including Slice 1,
is preserved. Only `emulator-5554` was used; no personal phone data was read, modified or cleared. The emulator's
original Wi-Fi/mobile data settings (both enabled) were restored. Device coverage is focused API 37 coverage, not a
full unfiltered/minimum-API sweep, real Google authentication or a new screen-off hardware endurance check. Reachability
confirms the configured route from this device, not global server/database health or account synchronization.
At the Slice 2 checkpoint, Slices 3–4 remained unapproved and not started.

### Slice 3: Account status and recovery UI

Dependency: Slice 2.

Status: Complete and verified (2026-10-25).

- Consolidate account messages and put authentication errors beside Reauthenticate. Clear superseded errors after
  successful operations without hiding unrelated unresolved work or fabricating success.
- Keep authentication, pending changes, last successful sync, and current sync progress distinct. Show All changes
  synced only under the conditions in the Account section above.
- Replace Review unsent changes with Resolve rejected changes, offered only when definitive rejected ordinary work
  needs attention. Expose advisory eligibility through the state/repository boundary and revalidate when pressed.
- Distinguish nothing eligible, blocked recovery, and actual failure instead of mapping every absence to Sync
  unavailable. Preserve the explicit discard-and-cloud-reload warning and captured confirmation protections.

Verification: State and Compose tests for authentication messages, successful-sync error clearing, clean queues,
conditional recovery controls, and unknown metadata. Use real Room for rejected-work eligibility and confirmation
preservation checks. Update the account status and ordinary-recovery documentation.

Complete when a healthy clean queue produces no recovery error, recovery controls explain their purpose, and each
failure is displayed beside the action that can resolve it.

#### Slice 3 verification

Implementation adds `AccountSettingsContent` and the pure `AccountStatusPresentation` policy. Authentication errors
appear beside Reauthenticate with distinct failed/required authentication, wrong-account, editor and cancellation
messages. One current sync explanation replaces stacked generic delivery/stop/error messages. Pending count, nullable
historical last-success and active progress stay independent; absent metadata remains unavailable and null last-success
is unknown. All changes synced requires an observed empty queue, known last-success, a completed current sync result and
no unresolved account, sync or recovery problem. Starting new work retires an earlier completion result, so interrupted work cannot reuse historical
success. An actual later successful sync clears a superseded failed authentication attempt, preserving
independent editor/identity/metadata errors. Successful reauthentication clears its own error.

Read-only selected-account status now exposes validated definitive ordinary-rejection presence. Resolve rejected
changes is conditional on that advisory value; pending-only, frozen/transient, malformed, import-only and clean work
do not advertise ordinary discard. The facade revalidates on every press and returns explicit prepared, nothing-eligible,
blocked or failed outcomes. Missing eligibility is informational rather than Sync unavailable. Frozen settlement retains
exact retry identity and surfaces foreground authentication requirements beside Reauthenticate.

Preparation/confirmation still use the real preflight, drain and exact captured ordered-row/authority/epoch/import
checks. Keep changes never discards; changed/coalesced rows, newer edits/rest, replayed or foreign confirmations refuse.
Warnings retain the discard-and-complete-cloud-reload text and accepted-server-write warning. A completed/cancelled
preparation that issued no fresh token disarms local warning adoption. Confirmation actions stack vertically so both
remain readable at enlarged text sizes. Recovery and retained-download failures keep their action context rather than
claiming rollback or fabricating success.

Verification on 2026-10-25:

| Check | Actual result |
|---|---|
| Kotlin compilation with KSP, Android-test compilation and debug build | Passed |
| Full JVM suite | 1,105 tests / 73 suites; zero failures/errors/skips |
| `AccountStatusPresentationTest` | 9 passed: known/unknown queues, retained work/stops, authentication and recovery messages |
| `AccountStartupStateTest` | Passed, including selective clearing of superseded authentication failure |
| `AppSessionOrdinaryRecoveryTest` | 21 passed: real Room eligibility, typed empty/blocked outcomes, actual preparation faults, frozen authentication stops, exact confirmations, newer/coalesced intent, import preservation and guarded reload |
| `AccountSyncStateIntegrationTest` | 18 passed: controller outcomes/error clearing plus existing demand/retirement/confirmation checks |
| `AccountSyncFlowTest` | 6 passed: connection/action gates, actual later successful sync clearing authentication error, captured keep/discard, timer and edit-triggered demand |
| `AccountSyncStatusTest` | 6 passed: selected/foreign metadata, unknown/invalid last-success and retired handles |
| `AccountStartupRootTest` | 9 passed: admitted/private startup and account controls |
| `AccountSettingsPresentationTest` | 2 passed: clean/unknown/rejected presentation and authentication action/error grouping |
| `SettingsTabTest` | 2 passed: navigation, scrolling and workout/timer preservation |
| Enlarged text and screenshots | Authentication-error-to-clean-sync and warning/keep/discard methods passed at device font scale 2.0; default/200% screenshots inspected |
| Documentation links, whitespace and `git diff --check` | Passed |

**64 distinct focused device tests** passed on **Pixel_6a / emulator-5554 / API 37**, with nonempty exact-class XML
checked for zero failures/errors/skips. Repeated large-text methods are not additional distinct tests. Screenshot review
found the horizontal recovery actions squeezing Keep changes at 200%; the final actions stack vertically. The complete
warning/keep/discard method passed again at both 100% and 200% after this layout fix. The final compilation/JVM/build
checks include that adjustment. The emulator's font scale was restored to its original 1.0.

New test-fixture setup mistakes and one incorrect class selector were corrected and rerun; those failed setup runs are
excluded from the successful totals. Session logs, verified XML and synthetic-fixture screenshots are retained under
`/tmp/gymtracka-settings-slice3-verification/`.

Scope: Android only, preserving the existing working tree. No Room entity/schema, asset, backend or shared-docs changes.
Only the selected emulator and disposable fixtures were used. Coverage is focused API 37 coverage, not an unfiltered
instrumentation/minimum-API sweep, live Google/deployed-service verification or a new screen-off hardware endurance
check. At the Slice 3 checkpoint, interrupted activation and fresh-session sign-in routing remained Slice 4 work.

### Slice 4: Interrupted sign-in recovery

Dependency: Slice 3.

Status: Complete and verified (2026-10-25).

- Label interrupted activation Finish signing in and resume the saved activation.
- Route repeated same-account sign-in through the appropriate fresh-session recovery path instead of comparing
  new credentials with the old exact session.
- Retain account/origin checks, uncertain credential evidence, queued edits, exact retry identity, and privacy
  barriers. Keep this credential/activation change separate from the presentation changes in earlier slices.

Verification: Deterministic account-state tests and real Room/vault tests for interrupted activation, saved-session
continuation, repeated same-account sign-in, wrong-account/origin refusal, and retained evidence. Add the relevant
Compose flow checks and update authentication/recovery documentation.

Complete when both saved activation and deliberate repeated same-account sign-in recover correctly without false
Account mismatch errors or loss of data/evidence, while foreign account/origin attempts still refuse.

#### Slice 4 verification

Implementation labels saved activation **Finish signing in**, explains the separate fresh-session Sign in action,
and makes the private gate scrollable. Saved activation freshly recovers authority and keeps the original reserved
operation/generation and uses the saved credentials without Google/exchange. Foreground sign-in from ordinary PREPARING/TRANSITIONING
instead captures a fresh operation and enters `SameAccountRecoveryCoordinator.prepare` under the existing facade fence,
operation mutex and transition lease. The private-import-specific path is preserved. Direct exact saved-grant resume
still requires the original complete grant and operation.

The existing coordinator retains queued edits and exact frozen mutation bytes, original credential deadlines and
pending refresh key/body/token evidence, and refuses foreign or incompatible evidence. New foreground credentials
never authorize replay/rebinding of archived credentials. A fresh post-sign-in recovery supplies the activation
expectation; neither the prior snapshot nor the private progress presentation grants visibility.

Verification on 2026-10-25:

| Check | Actual result |
|---|---|
| Kotlin compilation with KSP, Android-test compilation and debug build | Passed |
| Full JVM suite | **1,110 tests / 74 suites**; zero failures/errors/skips |
| `AccountStartupStateTest` and `ForegroundSignInContextTest` | 24 and 2 passed: saved activation, fresh private operations/expectations, reused-operation and origin refusal |
| `ForegroundSignInHandoffTest` | 14 passed: actual Room/Keystore, PREPARING/TRANSITIONING fresh recovery, queued edits, exact archived uncertainty, unchanged exchange retry, foreign/stale refusal and strict saved-grant resume |
| `AccountStartupRootTest` and `AccountStartupReopenTest` | 10 and 5 passed: actual controls, foreign-grant refusal followed by same-account success, file/vault reopen and saved activation without Google |
| `SameAccountRecoveryRoomVaultTest`, `AppSessionFencingReconnectTest`, `LegacyImportActivationTest` | 13, 11 and 26 passed: retained evidence, crash gaps, scoped/privacy fences and preserved import/activation behavior |
| Settings/sync/timer regressions | 62 passed across `SettingsTabTest` (2), `ServerConnectionSectionTest` (1), `AccountSyncFlowTest` (6), `AccountRuntimeTimerPipelineTest` (6), `AccountSyncStateIntegrationTest` (18), `AccountSyncStatusTest` (6), `AccountSettingsPresentationTest` (2) and `AppSessionOrdinaryRecoveryTest` (21) |
| Enlarged text and screenshots | Both new recovery journeys and the authentication-failure-to-successful-sync method passed at device font scale 2.0; default/200% recovery screenshots inspected |
| Documentation links/anchors and `git diff --check` | Passed |

**141 distinct focused device tests** passed on **Pixel_6a / emulator-5554 / API 37**, with nonempty exact-class XML
checked for zero failures/errors/skips. Repeated large-text/default methods are not additional distinct tests. The two
new recovery methods passed again at default text size after the enlarged-text run. The combined Settings checks
exercise connection loss/recovery and action gating, authentication failure/recovery, navigation/probing with a running
rest timer, timer identity and once-only elapsed-rest persistence. The full JVM suite also preserves deterministic
timer/hardware ownership coverage. The emulator font scale was restored to its original 1.0.

Initial new-test setup/assertion errors (a mismatched fixture binding generation and object-identity comparison of a
restored credential attempt) were corrected and rerun. The broader vault suite exposed an existing unquoted SQLite
trigger name containing a hyphen; only that test fixture's create/drop name was corrected, and all 13 tests then passed.
Failed setup/assertion runs are excluded from the successful totals above. Session logs, verified XML and synthetic
screenshots are retained under `/tmp/gymtracka-settings-slice4-verification/`.

Scope: Android only, preserving prior working-tree changes. No Room entity/schema, bundled asset, backend or shared-docs
changes. Only the selected emulator and disposable fixtures were used; no personal phone data was touched. This is
focused API 37 coverage, not an unfiltered/minimum-API sweep, real Google/provider or deployed-service re-verification,
actual process-kill coverage, or a new screen-off hardware endurance check. File reopen and scripted authentication
exercise the delivered boundaries; remaining broader gaps stay in [future improvements](../future-improvements.md#broader-integration-and-device-verification).

### Combined verification

The Slice 4 verification above records the completed broader checks and Settings emulator journeys. Required checks include
connection loss/recovery, authentication failure/recovery, and navigation with a running rest timer. Update affected
Android architecture, sync, and testing documentation to match delivered behavior; record remaining gaps in
[future improvements](../future-improvements.md). Record actual checks and results for each completed slice.

## Technical scope

Expected areas of change:

- `ui/GymApp.kt`, `ui/GymAppState.kt`, and `ui/AccountGymAppState.kt` for Settings navigation and account-root composition.
- New focused UI under `ui/settings/`, following the existing composition-scoped state-holder conventions.
- `ui/account/AccountStartupApp.kt`, `AccountStartupState.kt`, `AccountSyncPanel.kt`, and `AccountSyncState.kt` for
  account presentation, action availability, activation, and recovery outcomes.
- A narrow reachability adapter using `data/sync/http/` transport and test seams.
- Account/session status or recovery result types where needed to expose accurate presentation without DAO access
  from composables.

The plan requires no Room entity/schema changes, backend deployment changes, endpoint editor, sign-out, account
switching, or new background scheduler. Preserve existing foreground/edit-triggered sync behavior and its ownership
rules; a cached connectivity observation must not permanently prevent future synchronization.

## Verification and acceptance

- JVM tests cover connection transitions, timeout/cancellation, duplicate checks, late results, authentication versus
  connectivity classification, and button availability. Use hand-written fakes and virtual time.
- HTTP tests verify the unauthenticated probe, expected response validation, server errors, unexpected responses,
  and existing HTTPS/redirect protections. A probe must have no credential or account side effects.
- Account state and real Room tests cover clean queues, definitive rejected work, ineligible recovery, preserved
  confirmations, and repeated same-account sign-in after interrupted activation.
- Compose tests verify Settings selection, both sections, automatic checks on entry, Recheck, authentication errors
  beside Reauthenticate, disabled actions, and conditional recovery controls using stable semantics.
- Verify the reference-style layout, scrolling, larger text, and connectivity changes on an emulator. Confirm that
  switching tabs and checking connectivity preserve active-workout and timer behavior. Use disposable test data for
  recovery tests; never clear or run destructive tests against the personal phone database.
- Run narrow relevant tests while iterating, then Kotlin compilation, the appropriate JVM/device checks, a debug
  build, and `git diff --check`. Record actual coverage and any device/service verification that remains unperformed.

Completion requires a usable Settings tab, clear separation of server/account/sync state, correct action availability,
no false Sync unavailable error for a healthy clean queue, and preserved data and recovery safeguards.
