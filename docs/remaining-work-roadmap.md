# Remaining Android delivery roadmap

Status: R01 planning/design approved and **R02a–d plus R03a/b accepted** (2026-10-03).
Remaining implementation slices require separate authorization.
Read the [R01 audit](remaining-work/R01-task.md), [integration design](remaining-work/R01-design.md),
[planning verification](remaining-work/R01-review.md), [R02a specification](remaining-work/R02a-task.md) and
[R02a implementation review](remaining-work/R02a-review.md), [R02b specification](remaining-work/R02b-task.md) and
[R02b review](remaining-work/R02b-review.md), [R02c specification](remaining-work/R02c-task.md) and
[R02c review](remaining-work/R02c-review.md), [R02d specification](remaining-work/R02d-task.md) and
[R02d review](remaining-work/R02d-review.md).
The owner authorized R02b/R02c execution and subsequently selected the full Android working tree for delivery commit,
including the existing retirement/HTTP/build/local-doc baseline; see [delivery record](remaining-work/R02bc-commit-review.md).
The owner subsequently authorized bounded R02d execution, now accepted with 340 passing offline JVM tests, and then
requested the [R03 subtask split](remaining-work/R03-task.md) (documentation/planning only).
The owner subsequently authorized **R03a only**, now accepted; see its
[shared specification](remaining-work/R03a-task.md) and [review](remaining-work/R03a-review.md): 423 offline JVM tests,
five filtered Android transport tests plus one signed-out startup smoke on API 37, build/lint/security gates.
The owner subsequently authorized **R03b only**, now accepted under its [specification](remaining-work/R03b-task.md)
and [review](remaining-work/R03b-review.md): 477 offline JVM, 46 real-backend via production adapter/test TLS and
three filtered API37 Android tests, plus build/lint/security gates. No R02e/R03c–d implementation, commit, push, backend change,
app activation or deployment is authorized. Each task will receive its
own detailed plan and may be split further by the current assistant or a successor coordinator before implementation.

## Scope and baseline

**Current target: personal use by the owner, with the server on the owner's always-on laptop.** A public production
release may happen later; absolute production readiness is background work, not the current completion criterion.
Deliver a safe, reliable personal sync app while preserving signed-out use and existing workout/routine/catalogue behavior.
The [finalized v1 contract](../shared-docs/docs/backend-contract/README.md) still defines compatible behavior. This is a delivery-priority
change, not removal of its eventual public-release requirements or authorization for insecure/data-losing shortcuts.
The [implementation plan](sync-implementation-plan.md) retains historical M0–M4 reviews and M5–M12 capability definitions.
**R identifiers are new work packages, not renumbered historical assignments.** Assignment 018 stays paused; do not
recreate the retired backend simulator.

Existing foundations are not tasks to reimplement: Room v9 and supported migrations, the unchanged bundled v1 asset,
17 typed mutation kinds and durable codecs, account/session-fenced local capture and selected-scope reads, SDK-independent
API boundaries, and the isolated real-backend HTTP harness. R02d's current gate is 340 offline JVM tests; R02a
previously passed six filtered Android smoke tests. Earlier evidence remains 46 separate real-backend tests, 11 Python launcher tests and
161 instrumentation tests at the last full device gate. See [testing strategy](testing-strategy.md),
[R02a review](remaining-work/R02a-review.md), [R02d review](remaining-work/R02d-review.md) and
[retirement review](fake-retirement-review.md); earlier evidence is not
verification of a new task. The Android simulator-retirement baseline was delivered in `623207c`; nested shared-docs changes remain separate and uncommitted.

R03a delivers a reusable production-compatible secure HTTP primitive, not endpoint adapters. There is no production
service-auth endpoint adapter, running sync engine, account UI, snapshot publication, Google integration or background scheduling.
R03b's production sync adapter is implemented and independently verified, but not constructed/activated by the app. R02d adds challenge purposes, auth codecs and exact-body attempts; account-bound lifecycle
proofs and export/delete/status models remain deferred. A JVM HTTP pass does not prove Android Room convergence or real Google OAuth.

Backend access remains **operational build/start and black-box HTTP only**. Use the checked-in isolated launcher;
do not inspect/change backend source, run its full Maven suite, read personal configuration, or touch personal databases.
Report service compatibility gaps as blockers for separate ownership rather than silently widening Android scope.

## How a coordinator picks up a task

1. Confirm user authorization for detailed planning or implementation of that specific task. Inspect both working trees,
   including untracked files, and reread current architecture, product/testing docs and relevant contract files.
2. Produce a task specification: goal/non-goals, current-code inventory, permitted files, dependencies, state/transaction
   boundaries, crash/cancellation/race cases, migration/security consequences, acceptance tests and exact stop condition.
3. Resolve internal design choices with the simplest compatible approach. Ask the user only for unresolved product,
   destructive-action, configuration, or scope decisions; do not reopen finalized contract decisions incidentally.
4. Split large tasks into reviewable subplans (for example R05a/R05b), each with one coherent deliverable and gate.
   Split candidates below are suggestions, not approved designs. Specify concrete interfaces/schema before dependent code.
5. Implement one approved slice at a time. Coordinator independently reviews the full diff and executed test results;
   contributor completion alone is not acceptance. Update task status/evidence and handoff immediately after approval.
6. Stop at the approved boundary. No automatic continuation, commit or push. Mark external gates blocked/unverified
   when inputs are absent; never convert a skip or cached task into a new test pass.

Suggested detailed-plan location: `docs/remaining-work/RNN-task.md` in the Android repository, with linked subplans
and an `RNN-review.md` acceptance record. Create these on pickup, not speculative implementation instructions for all
18 tasks now. This roadmap is the execution index; the historical plan is the invariant/review reference.

## Delivery priorities

1. **Usable personal version:** R01–R09, the core recovery/convergence gate of R10, sign-in/sign-out in R11,
   R12–R13, and R17a verification on the owner's phone against the laptop. Automatic foreground/edit sync and manual
   retry are enough for this first gate; background delivery need not delay trying the app.
2. **Reliable unattended personal use:** R16 background scheduling and R17b actual offline/restart/background verification.
   Use the existing server and a simple documented connection/start/backup/recovery setup, not a new hosting platform.
3. **Future public release:** R14 export, R15 account deletion/status, their deferred R02/R03/R11 support slices,
   expanded compatibility/hosted-CI/live-lifecycle gates and R18 public-release audit. These remain planned but do not
   block personal use. They may be brought forward if the owner wants them.

Public rollout, broad device/signing/distribution matrices, production compliance/operations audit, scale/high-availability
work and polish beyond usable account/error controls are not prerequisites for the personal milestones. Do not add new
infrastructure just to satisfy a hypothetical public launch. Tests for transactional safety, unchanged retries, failed
snapshots, credential handling and account/session fencing remain essential even with one real user. Two test stores
prove recovery/convergence; they do not require multiple actual users or a multi-user deployment.

R01 must identify explicit personal/public subplans where a task spans both. Record each subplan's real status; accepting
its personal slice does not mark the whole future-public task complete. Existing task outlines below describe the full
capability envelope and must be narrowed to the relevant milestone before implementation.

## Task index and dependencies

Numbers give a sensible default walkthrough, not a requirement to do independent branches serially. Dependencies are
capability gates, including their accepted subplans. R01 may refine the graph with a documented reason; successors must
not quietly omit a required capability. R01 planning and R02a–d/R03a implementation are accepted.
Other slices require individual planning/implementation authorization.

| ID | Task | Depends on | Main acceptance result |
|----|------|------------|------------------------|
| R01 | Audit v1 gaps and approve the integration design | Existing baseline | Reviewed requirement/design matrix and bounded next assignment |
| R02 | Align client models and API boundaries with finalized v1 | R01 | R02a–d accepted; R02e lifecycle deferred with public account features |
| R03 | [Implement Android HTTP transport](remaining-work/R03-task.md) | Relevant R02 slices | R03a primitive/R03b sync accepted; c auth pending; d lifecycle deferred |
| R04 | Implement encrypted session storage and refresh recovery | R03a, R03c | Crash-safe exact-key refresh, account/session-bound credentials |
| R05 | Implement remote Room application and protected local intent | R02; R01 design | Atomic revision-safe canonical batches without lost edits or echo uploads |
| R06 | Implement durable push orchestration | R03b, R04, R05 | Ordered unchanged retries and exact fenced acknowledgments |
| R07 | Implement incremental pull orchestration | R06 | Contiguous batch/cursor application and safe pagination |
| R08 | Implement staged bootstrap and full resync | R05, R06, R07 | Complete snapshot publication; partial reads never replace visible cache |
| R09 | Implement account transitions and local-data import/replace | R04, R08 | Recoverable scope transitions, private cache and explicit data choice |
| R10 | Prove two-client convergence and engine recovery | R03b/c, R06, R07, R08, R09 | Core safety gate now; expanded matrix before public release |
| R11 | Implement foreground Google identity and fresh-proof flows | Relevant R02 slices, R04 | Sign-in/out now; lifecycle proof flows with R14/R15 |
| R12 | Reconcile feature/app state with remote and session changes | R05, R07, R09 | Current screens, names, active workout and timer stay consistent |
| R13 | Integrate account UI, foreground sync and rejection recovery | R09, core R10, sign-in R11, R12 | Usable personal sign-in/out, import, status and recovery |
| R14 | Implement cloud JSON export | R02e, R03d export, R11, R13 | Fresh-proof, complete user-saved export; no partial-success claim |
| R15 | Implement account deletion and old-account status recovery | R02e, R03d delete/status, R09, R11, R13, R14 | Confirmed, account-fenced cleanup; safe lost-response/new-signup handling |
| R16 | Implement background sync scheduling | R06, R07, R08, R13 | Unattended personal sync; deletion cancellation integrated when R15 ships |
| R17 | Verify the personal laptop deployment and app journeys | Core R10, R13; R16 for background gate; connection/OAuth inputs | R17a usable personal app; R17b unattended recovery; public matrix later |
| R18 | Audit future public-release readiness | All tasks' public gates, including R14/R15 and expanded R17 | Deferred public-release audit, not a personal-use blocker |

### External inputs — track from R01, do not wait until R17

- **Personal now:** how the phone reaches the laptop (LAN-only versus access away from home), an agreed secure HTTPS
  endpoint/connection setup, actual installed signing fingerprint and matching Google Android/web audience configuration.
  R01 gathers these decisions; do not assume public Internet exposure, a domain, hosting provider or bypassed TLS/auth.
- **Personal verification:** the owner's phone and explicitly approved test account/data, plus an isolated service for
  automated tests. Document practical laptop/server restart and backup/recovery arrangements with the owner. Personal
  data/configuration is not an automated fixture; live actions need explicit permission.
- **Public later:** release signing/distribution, broad supported-device and hosted-CI gates, test accounts/permission
  for irreversible lifecycle journeys, and separately owned retention/purge/restore evidence and public disclosures.
- No client secrets or private configuration contents in the APK/docs. Android agents still do not implement/audit the
  backend operations or change laptop configuration/deploy services implicitly.

Collect readiness/owners without publishing secrets. Unconfigured signed-out use must remain functional. Deterministic
implementation work can proceed without OAuth/deployment inputs; live gates remain blocked until they are supplied.

## Task outlines

### R01 — Audit v1 gaps and approve the integration design

- Revalidate current code and the existing dirty-tree baseline; distinguish delivered foundations from older planning
  statements. Map v1 gaps to personal-use versus future-public subplans, prioritizing safe sync against the owner's laptop.
  Gather connection/signing/OAuth inputs without assuming public exposure or provisioning new infrastructure.
- Decide how canonical state and pending/rejected local intent coexist, how semantic commands protect multiple roots,
  how deletion revisions survive absent roots, and how snapshot staging/publication and durable account transitions fit.
- Design the crash-safe coordination between Room and the encrypted vault; they cannot share a database transaction.
  Include autosave/timer draining, exclusive engine ownership and first-sign-in activation prerequisites.
- Propose coherent data-preserving migration boundaries, injectable seams, UI entry points and configuration ownership;
  do not assign a future Room version or introduce a framework merely for this plan.
- **Gate:** reviewed architecture/state diagrams, invariant and test matrix, decisions/blockers, and the next bounded
  specification. Read-only code audit and documentation; no production code or migration in this task.

### R02 — Align client models and API boundaries with finalized v1

- Add only missing transport-independent request/result models/codecs and extend existing boundaries compatibly.
  Cover challenge purposes, account-specific proofs, export v1, deletion receipt and status.
- Align invalid versus expired read-token failures, authentication versus reauthentication, unknown permanent codes,
  request-too-large recovery and retryable errors; preserve safe fixed messages/redaction and cancellation behavior.
- Retain all existing typed mutation requests and byte-exact durable versions; define explicit handling for unsupported
  durable/export versions rather than reinterpretation.
- **Gate:** independent JSON/fixture and boundary tests including negative shapes, numeric/identity rules and leakage
  checks. No HTTP, Room writes, Google SDK or UI activation.
- **Possible splits:** protocol gap fixes; auth/proof models; lifecycle/export models.

### R03 — Implement Android HTTP transport

- Select supported Android HTTP dependencies at pickup, configure HTTPS and INTERNET permission, and implement sync,
  service-auth and account-lifecycle adapters using the approved production codecs. Reuse lessons/tests from the JVM
  adapter, not its JDK/JDBC fixture implementation in production.
- Preserve frozen mutation bytes and exact authentication/lifecycle retry bodies/keys. Implement bearer binding,
  timeout/cancellation, bounded Retry-After and typed status/body failures, including malformed/truncated responses.
- Plan direct export streaming and partial-download detection without keeping unbounded response bodies in memory.
  Prevent credential/domain logging and redirects or configuration that disclose credentials to another origin.
- **Gate:** deterministic transport tests plus isolated real-backend interoperability through the production-compatible
  adapter where feasible. Test-only loopback configuration must not enable cleartext or auth bypasses in release.

The owner-requested [R03 split](remaining-work/R03-task.md) makes the bounded gates explicit:

| Slice | Scope | Gate / stop |
|---|---|---|
| R03a | Secure origin/configuration, Android-compatible cancellable HTTP primitive, common I/O ownership/hint parsing | Independent TLS/redirect/encoding/cancellation/security tests; no endpoint adapter |
| R03b | Production push/pull/snapshot + shared service-error decoding | Exact frozen bytes, complete outcomes, operation-aware failures and isolated interoperability; no engine/Room writes |
| R03c | Purpose challenge, exact-body exchange/refresh and logout adapter | No bootstrap bearer, exact retained keys/bodies, safe grants/errors/cancellation; no vault/Google/account flow |
| R03d | Deferred account export/delete/status and owned export streaming | R02e prerequisite; complete download/account-bound results; no UI or destructive cleanup |

Default personal execution/review order is **a → b → c**, then stop; **d** follows public lifecycle needs. R04 requires a/c,
not deferred d; R06 requires b plus R04/R05. Exact library/version/API/allowlists are prepared at each authorized pickup.
R03a is now separately authorized and accepted under its [detailed specification](remaining-work/R03a-task.md)
and [review](remaining-work/R03a-review.md).
R03b–d and concurrent implementation of other slices remain unauthorized.

### R04 — Implement encrypted session storage and refresh recovery

- Implement app-private Keystore-backed vault storage excluded from backup/device transfer, atomically persisted grants
  and uncertain refresh attempts bound to service account and durable session generation.
- Serialize refresh; durably retain the same old token/key before transmission and recover that pair after loss/restart.
  Persist rotation before clearing recovery state; one refresh-and-retry for service 401, then foreground reauthentication.
- Expose machinery for R09 transitions, not working sign-in UI. Define startup mismatch/missing-vault handling that keeps
  account cache private, and fence logout/refresh races and stale grant writes.
- **Gate:** deterministic session-state tests, storage failure/restart/backup tests and Android Keystore checks. No
  credentials in Room, outbox, saved state or logs; no long-lived Google ID-token storage.
- **Possible splits:** vault/storage; refresh/access-token coordinator; startup recovery seams.

### R05 — Implement remote Room application and protected local intent

- Implement the R01-reviewed representation and transaction primitives for complete remote upserts/deletes, canonical
  revisions and retained pending/rejected overlays. Preserve complete ordered identities and custom reference integrity.
- Apply multi-root batches atomically, persist deletion revision evidence even for unknown roots, reject stale sessions,
  prevent older canonical resurrection and avoid generating outbox work from remote application.
- Protect later edits/deletes and sole-active-workout intent from old acknowledgments or pull. Do not silently field-merge
  children; convenience exercise labels cannot overwrite current custom-exercise data.
- **Gate:** real in-memory Room tests for all root types, absent-root deletes, dependency ordering, stale revisions,
  newer intent, multi-root replacement and forced rollback. Any schema change requires KSP/export and all-start migrations.
- **Possible splits:** reviewed schema/cache primitives; per-root application; protected-intent/batch transactions.

### R06 — Implement durable push orchestration

- Serialize sync per account/session; select and freeze the explicit sequence head transactionally, then perform network
  calls outside Room transactions. Recover uncertain frozen work unchanged after cancellation/process death.
- Commit complete canonical outcome and exact request/generation acknowledgment together. Multi-root affected bindings
  must agree; never advance the pull checkpoint using a push-result cursor.
- Distinguish original accepted versus rejected duplicate outcomes, transient transport failure, corruption and permanent
  rejection. Stop at a rejected predecessor; preserve recovery context and newer intent instead of dropping work or
  issuing a new ID automatically. Bounded retry must not endlessly resend an unrecoverable oversized request.
- **Gate:** real Room plus controlled transport tests for lost responses, delayed results/new edits/deletes, restart,
  malformed/mismatched outcomes, failure rollback, exact bytes/IDs and stale-account responses.
- **Possible splits:** claiming/recovery; outcomes/retry; exact acknowledgment with protected intent.

### R07 — Implement incremental pull orchestration

- Push before pull and pull after successful pushes. Allow reading around rejection only if the protected-intent design
  proves it safe; never bypass a dependent rejected outbox head to send later commands.
- Apply whole contiguous batches and durable cursor under the same session fence; keep revisions and cursors distinct.
  Validate pagination/progress without decoding or lexically comparing opaque tokens.
- Route explicit cursor expiry to R08 recovery; malformed/foreign/unknown tokens must not silently bootstrap. Never infer
  deletion from absence in an incremental page.
- **Gate:** page/batch replay, empty tail, failed page/cursor rollback, atomic active replacement, dependency failure,
  protected edits and cancellation/switch tests. No null-cursor pull as a substitute for initial snapshot.
- **Possible splits:** pull loop; cursor/failure transactions; push/pull run policy.

### R08 — Implement staged bootstrap and full resync

- Stage complete stable snapshots outside visible account data; verify account/baseline/limit consistency, duplicate roots,
  repeated tokens and termination. Support references on later pages even if the real service orders customs first.
- Publish the complete cache and baseline together in a fenced transaction, preserving pending/rejected/newer intent
  according to the approved design. Bootstrap accounts without a cursor and recover explicit 90-day cursor expiry.
- Recover/discard incomplete staging after failure, snapshot expiry, restart, cancellation and account switch. A replace
  choice must not destroy signed-out data before a complete cloud snapshot is ready.
- **Gate:** empty/old-unchanged account, middle/final-page failure, out-of-order dependencies, concurrent edits, restart,
  expiry and atomic publication tests with real Room. Staging schema changes follow full migration discipline.
- **Possible splits:** staging storage/migration; download validation; publication and recovery.

### R09 — Implement account transitions and local-data import/replace

- Implement durable account/session coordination: sign-out retains the private cache, same-account sign-in resumes it,
  different-account sign-in clears only the prior account cache/work/cursor/staging, and separate local data stays separate.
- Implement the explicit import/replace decision and dependency-ordered creation capture, including custom references,
  drafts and valid finished history. Preserve IDs; collisions become retained errors, not remapping or cloud replacement.
- Drain accepted feature edits and terminal rest writes before a scope transition, advance fences, cancel old account
  work and coordinate vault/Room recovery. Initial visible activation requires R08, not partial downloaded state.
- **Gate:** A/B/local isolation, import/replace with and without local data, same-account resume, interrupted transitions,
  collisions, vault mismatch and delayed old responses. Offline sign-out succeeds without deleting private cached data.
- **Possible splits:** transition state machine; import/choice capture; crash-safe activation/switch/sign-out.

### R10 — Prove two-client convergence and engine recovery

Prioritize core loss/retry/restart, delete/active-replacement, snapshot and newer-intent safety before personal activation.
The complete expanded matrix remains a future-public gate; this split must be explicit in the detailed plan.

- Exercise two independent real Room-backed client stores through the delivered engine and real HTTP service, not just
  DTO calls. Retain deterministic scripted timing/failure tests for cases unavailable through the black-box backend.
- Cover server-commit-order conflict winners with reversed device clocks, all mutation families, delete propagation,
  custom dependencies/names, active replacement, exact replay, snapshots and later local intent.
- Include client restart/offline-to-online and account isolation. Add retention-boundary coverage through approved test
  controls if available; do not wait 90 days, inspect backend source or claim clock controls exist when they do not.
- **Gate:** independently expected complete client/store outcomes and explicit environment limitations. This is an engine
  integration gate before user-facing activation, not a replacement for tests inside R03–R09.
- **Possible splits:** harness with isolated backend; convergence matrix; recovery/retention matrix.

### R11 — Implement foreground Google identity and fresh-proof flows

- Implement Credential Manager behind the identity interface with supplied server nonce, configured audience/package
  inputs, cancellation/failure and clearCredentialState. Verify SDK/dependency compatibility at pickup.
- Orchestrate purpose-bound sign-in/export/deleteAccount/accountStatus attempts without Google types in sync/domain code.
  Service account identity remains authoritative; never use email or decoded token contents as local ownership.
- Keep exchange retries identical within a bounded foreground attempt. No interactive worker auth, invented client IDs,
  APK client secret or persistent Google credential in ordinary preferences/domain state.
- **Gate:** deterministic adapter/flow tests and Android construction/configuration behavior. Actual Google verification
  remains R17 unless authorized inputs allow an earlier separately recorded live smoke test.
- **Possible splits:** SDK adapter/configuration; foreground proof orchestration and cancellation.

### R12 — Reconcile feature/app state with remote and session changes

- Refresh catalogue, routines, history/detail and active-workout state after relevant Room commits, even when parent root
  revision is unchanged but a referenced custom exercise was renamed. Preserve accepted drafts/focus/autosave ordering.
- Recreate session-bound repository/state scopes safely; update navigation and active pill when a workout is remotely
  replaced, deleted or finished. Review in-progress timer ownership and exactly-once elapsed-rest handling explicitly.
- Keep composition-scoped architecture, narrow repositories, unidirectional flow and compact set controls. No unrelated
  ViewModel/DI/navigation framework introduction.
- **Gate:** state-holder and actual Compose/app-flow tests for remote rename, same-ID updates, finish/replacement/deletion,
  concurrent local edits, picker/editor scope changes and retained/private account visibility.
- **Possible splits:** store observation/refresh; feature reconciliation; app shell/timer/session rebinding.

### R13 — Integrate account UI, foreground sync and rejection recovery

- Design product-consistent account entry/status controls at pickup. Wire sign-in/out, unavailable configuration,
  import/replace confirmation, foreground/manual/edit sync triggers, last success and pending/error/reauthentication states.
- Surface retained permanent rejection/corruption and stop dependent sending. Provide explicit, confirmed recovery using
  the approved policy, including discarding pending/rejected account intent and reloading a complete cloud snapshot.
  Never silently drop data, change uncertain mutation IDs, or imply that an offline sign-out revoked the remote session.
- Integrate accepted autosave draining and transition progress/cancellation behavior; protect signed-out local workflows.
- **Gate:** deterministic state/Compose and actual app journeys for local-only use, sign-in choices, refresh failure,
  rejection/recovery and account switching. Destructive wording and accessibility need coordinator review.
- **Possible splits:** UX/state specification; account flows; status/manual triggers; recovery controls.

### R14 — Implement cloud JSON export

Deferred from the personal-use critical path; required before a public release, or earlier at the owner's request.

- Warn that unsynced/device-only changes require successful sync to be included; make sync-before-export behavior and
  cloud-only scope clear. Obtain a fresh export-purpose proof for the selected account.
- Download/validate the version-1 response and let the user choose the save destination through Android's document APIs.
  Preserve domain IDs/relationships/nulls while excluding credentials/sync internals; no restore feature is added.
- Handle cancellation, wrong-account proof, unsupported version and incomplete download. Do not mark a partial file
  complete; clean temporary artifacts and retry with a new proof, not an assumed resumable export token.
- **Gate:** transport/codec/state/document tests plus isolated-service coverage where proof setup is available. Real
  Google/user-save journey is a distinct R17 gate.
- **Possible splits:** export coordinator/stream validation; Android save flow and UI.

### R15 — Implement account deletion and old-account status recovery

Deferred from the personal-use critical path; required before a public release. Until delivered, do not expose an account
Delete action or interpret authentication failure as deletion. Keep all future cleanup account-bound.

- Offer optional R14 export and warn of irreversible cloud deletion, immediate disablement and separate physical purge
  timing. Require explicit confirm=true and a fresh same-account delete-purpose Google proof; sign-out never deletes.
- Preserve exact bounded retry identity for uncertain deletion acceptance; plan restart recovery through authoritative
  accountStatus proof/receipt without indefinitely retaining raw Google credentials. Generic 401/404/offline failures
  must never erase cache.
- On a verified deletion receipt, clear only that account's cache/outbox/cursor/staging/credentials and stop its work,
  preserving separate local data. A delayed A receipt must not clear B; same-Google new signup creates a different account
  and cannot inherit old pending work. Status/export/delete must not implicitly sign up.
- **Gate:** confirmation/cancellation, lost response, restart, status failure, A-to-B race, new signup and local-data
  preservation tests. Server purge/backup operations are externally owned, not implemented by this Android task.
- **Possible splits:** acceptance/status coordinator; fenced cleanup/recovery; deletion UI and cross-device journeys.

### R16 — Implement background sync scheduling

Follow the usable foreground/manual personal version. R15 is not a prerequisite: preserve session/switch cancellation
now and add confirmed-deletion cancellation with R15 before exposing that feature.

- Add account/session-scoped unique WorkManager work, network constraints, bounded retry and foreground/edit/sign-in
  wakeups with periodic recovery. Worker uses the same engine/store/transport/session coordinator as foreground sync.
- Handle new edits while a run is active, stopped workers/process restart, cancellation and switch/deletion fencing.
  Refresh may be background; Google UI may not. Permanent errors suspend dependent delivery rather than endless retries.
- **Gate:** deterministic scheduling/race tests and device offline-to-online, restart/background, token expiry and switch
  cases. Prove no duplicate engine ownership or lost wakeups; preserve durable uncertain mutations when work is stopped.
- **Possible splits:** scheduler/triggers; worker adapter; race and device recovery gates.

### R17 — Verify the personal laptop deployment and app journeys

- **R17a — usable personal version:** verify actual Google sign-in/service identity, refresh/sign-out, local-data choice,
  foreground/manual sync and safe error recovery on the owner's phone against the agreed laptop HTTPS endpoint.
  Confirm offline editing, reconnect delivery, app/server restart and existing workout/timer flows. Keep automated
  integration tests isolated; do not turn the owner's real database into a failure-injection fixture.
- **R17b — unattended personal version:** after R16, verify real background/offline-to-online delivery, worker stopping,
  token expiry and restart without lost work. Document simple connection, server restart and backup/recovery instructions
  with known laptop/network availability limits; do not promise high availability because the laptop is usually on.
- **Future-public expansion:** verify export/deletion/status/new signup after R14/R15, release signing, minimum API 24
  plus broader supported-device coverage, and actual hosted CI. Existing CI may run earlier, but public-readiness-only
  matrix expansion is not a personal-use blocker. No mock pass substitutes for real OAuth or deployment checks.
- **Gate:** record executed reports, environment/device/signing inputs and limitations separately for each subplan.
  Missing inputs block the affected gate, not unrelated implementation. No production hosting/compliance project is
  required to complete R17a/R17b; no deploy or personal configuration change without separate authorization.

### R18 — Audit future public-release readiness and finish documentation

Background/deferred work until a public release is requested. Personal milestones still update documentation and receive
focused safety/regression review; they do not claim this broader audit has passed.

- Independently map finalized requirements to production code and executed evidence. Reaudit capture, remote writes,
  retries, acknowledgments, revisions/cursors, snapshots, account fencing, names and destructive flows.
- Review credential/log/backup/device-transfer behavior, APK configuration/secrets/test-helper exclusion, catalogue
  notices/parity, supported migrations and release configuration. Coordinate accurate privacy/account disclosures with
  separately supplied service retention/deletion evidence; do not assert legal compliance or silently expand backend scope.
- Run the full Android/JVM/lint/assembly/device gates and separate real-backend task, inspect testcase counts/failures/
  errors/skips and both diffs, then update architecture/product/testing/handoff to actual behavior.
- **Gate:** coordinator-owned release audit, resolved findings and explicit external verification record. Split findings
  into bounded corrections; no publication, commit or push without user authorization.

## Mapping to the historical milestones

| Historical capability | New work packages |
|-----------------------|-------------------|
| M5 push/reconciliation | R01, R05, R06 |
| M6 pull/two-device convergence | R07, R10, R12 |
| M7 account/import/switching | R04, R09, R12, R13, R15 |
| M8 snapshots/full resync | R08 (before first visible account activation in R09/R13) |
| M9 HTTP | R02, R03, R17 |
| M10 Google/account UI | R11, R13, R14, R15, R17 |
| M11 scheduling | R16 |
| M12 audit | R18 |

Export and account deletion/status remain explicit public-release requirements, but are deferred for the current
single-user laptop-hosted target. Personal use does not claim complete v1 public-release readiness.
Resource browsing/direct mutable HTTP routes are not an Android UI data-source replacement: Room remains authoritative
for responsive UI. Implement unused service surfaces only for a demonstrated client need. No new sharing, public
profiles, login provider, automatic catalogue updates, export restore, finished-workout editing, PR/1RM or unrelated
process-death timer redesign is included here.

## Common acceptance rules

- Test each task while implementing it; R10/R17/R18 are broader gates, not permission to defer core tests until the end.
- Use real Room for transaction/cascade/revision/migration behavior, virtual time and narrow scripted doubles for client
  state/timing, and isolated black-box HTTP for interoperability. Do not maintain a second server implementation.
- Preserve the v1 bundled database and current user changes. Each schema change needs a data-preserving migration,
  exported snapshots, KSP and every-supported-start migration coverage, including the copied bundled v1 asset.
- Every implementation slice needs Kotlin compilation and relevant JVM tests; add Android-test compilation, Room/Compose
  execution, assembly/lint and real-backend gates as appropriate. Inspect executed XML; up-to-date is not a rerun.
- Run `git diff --check` in Android and shared-docs; review new/untracked files as well as tracked diffs. Update Android
  architecture/testing/plans in `docs/` and shared product/contracts in `shared-docs/` as behavior changes, not claims
  of future behavior as already shipped.

**Next proposed pickup:** detailed R03c exact-body service-auth HTTP specification and execution, only after
separate owner authorization. R03b is accepted under its [specification](remaining-work/R03b-task.md) and
[review](remaining-work/R03b-review.md). R03a is accepted under its [specification](remaining-work/R03a-task.md) and
[review](remaining-work/R03a-review.md), following the [R03a–d split](remaining-work/R03-task.md).
R02e lifecycle support remains deferred with public account features.
R02 acceptance does not authorize transport work, commits or pushes. The separate owner-approved
Android delivery commit is recorded above. The
[R02d review](remaining-work/R02d-review.md) records 340 passing offline JVM tests and build/lint gates; R02a's six API 37
platform smoke tests remain earlier evidence. No new device/backend pass is added. R01 proposes account-wide visible-intent protection and requires R12 publication/edit
barriers before account UI activation; see its dependency refinements. Phone access is required on LAN and away;
HTTPS is unconfigured and Google setup is partial. These block live R17a, not deterministic R02/R03 work.
