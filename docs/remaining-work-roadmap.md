# Android delivery roadmap

**Single execution index.** Update status in place; keep completed-task narratives in the archive. This roadmap defines
outcomes and dependencies, not authorization. Detailed specifications are created [on pickup](coordinator-workflow.md).
Current pickup and blockers are in [the handoff](coordinator-handoff.md); Android integration decisions are in
[the approved design](sync/integration-design.md). Shared v1 requirements are unchanged.

## Delivery priorities

1. **Usable personal app:** owner’s phone against an always-on laptop service; R01–R09, core R10, sign-in/out R11,
   R12–R13 and R17a. Foreground/edit-triggered sync and manual retry suffice for this milestone.
2. **Unattended personal use:** R16 and R17b offline/restart/background recovery. No new hosting platform or high-availability
   promise is required.
3. **Future public release:** lifecycle/export/deletion slices, expanded compatibility/CI/live gates and R18. These remain
   obligations, but do not block trying a safe personal app. They can be brought forward by owner direction.

Personal use does not relax transactional safety, exact retries, account/privacy fencing, credential security, or complete
snapshot publication. Accepted personal slices never imply completion of their deferred public counterparts.

## Status and dependencies

Statuses below reflect accepted delivery through R05a. “Pending” requires specific authorization and a pickup spec;
“deferred” remains required for its later milestone. Historical M-series assignments are not a second execution plan.

| Package | Status / outstanding scope | Dependencies |
|---|---|---|
| R01 integration design | Accepted; maintained in the design reference | Existing foundations |
| R02 models/API | a–d accepted; **e lifecycle/proof/export/delete/status models deferred** | R01; e with lifecycle features |
| R03 HTTP | a primitive, b sync, c auth accepted; **d lifecycle/export streaming deferred** | Relevant R02 slices; d needs e |
| R04 session recovery | a vault, b refresh, c startup/session recovery seams accepted; durable Room integration remains R09 | R03a/c; c uses a/b |
| R05 canonical Room / local intent | **a storage/capture watermarks accepted**; b canonical batches and c protected publication pending | R02b and approved integration design |
| R06 durable push | Pending | R03b, R04, R05 |
| R07 incremental pull | Pending | R06 |
| R08 bootstrap/full resync | Pending | R05–R07 |
| R09 account transitions/import | Pending | R04, R08; production activation also needs R12a |
| R10 engine convergence/recovery | Core a pending; expanded b before public release | R03b/c, R06–R09 |
| R11 Google identity/proofs | Personal sign-in/out a/b pending; lifecycle c deferred | Relevant R02, R04 |
| R12 feature/app reconciliation | a edit/publication barriers, b observation, c app/timer rebinding pending | R05/R07/R09 integration; barriers can be developed earlier |
| R13 account UI/foreground sync | Pending | R09, core R10, personal R11, R12 |
| R14 cloud JSON export | Deferred public | R02e, R03d export, R11 proofs, R13 |
| R15 deletion/status recovery | Deferred public | R02e, R03d delete/status, R09, R11 proofs, R13, R14 |
| R16 background scheduling | After usable personal app | R06–R08, R13; deletion hooks added with R15 |
| R17 actual deployment/journeys | a personal pending; b unattended after R16; c public expansion deferred | Core R10, R13, external inputs; R16 for b |
| R18 public release audit | Deferred public | All public gates, including R14/R15 and expanded R17 |

Dependencies are capability gates, not a mandate to implement every independent branch serially. In particular:

- R04 needs R03a/c, and R06 needs R03b—not deferred R03d. Do not wait for public lifecycle adapters to build personal sync.
- R12a finite-edit/timer barriers must precede production projection/session activation in R09/R13. Store/engine tests
  may inject controllable barriers; the live app must never substitute a no-op while screens accept edits.
- Initial sign-in needs R08's complete snapshot; null-cursor incremental pull is not bootstrap. Core R10 must pass before
  user-facing activation. R09 journal tests can precede R12a, but visible activation cannot.
- Room v10, existing mutation capture/watermarks, all 17 kinds, codecs, transport, vault, refresh and inert recovery seams are
  foundations to reuse, not tasks to recreate. The retired simulator is not a prerequisite or backlog item.

## Outstanding acceptance outcomes

These are capability requirements, not detailed assignments. Split further when authorized; specify concrete APIs/schema,
independent tests, allowed files and stop conditions before implementation. Tests belong inside every slice, not only R10/R17.

### R05 — Canonical Room application and protected intent

R05a storage and capture watermarks are accepted; see the
[scoped review](archive/remaining-work/R05a-review.md). Next proposed slice is R05b canonical batches, followed by
R05c protected publication; each needs owner authorization and a bounded specification.

Implement account-keyed canonical records/deletion evidence, generation watermarks and protected projection from the
approved design. Preserve whole ordered identities and references; atomic multi-root batches must reject stale sessions,
retain unknown-root deletion revisions, prevent older resurrection and never echo uploads. Protect all visible account
intent while outbox work remains; no field/child merge. Publication must refuse global physical PK collisions.

**Gate:** real Room root/batch/revision/dependency/rollback/newer-intent tests; schema changes need migration/KSP/export and
all-start/asset coverage. Remaining slices: canonical batches, protected publication.

### R06 — Durable push

Serialize account/session runs; claim/freeze the explicit sequence head transactionally, then perform HTTP outside Room.
Recover uncertain requests unchanged. Canonical outcome and exact request/generation/affected-root acknowledgment commit
together; push cursors never advance the pull checkpoint. Preserve newer edits and retained rejection/corruption/oversize
errors; do not bypass a rejected predecessor, endlessly retry an unrecoverable 413, or invent another mutation ID.

**Gate:** real Room plus controlled timing/transport for lost responses, cancellation/restart, late results, malformed or
mismatched outcomes, accepted/rejected duplicate results, stale accounts and rollback. Suggested slices: claiming/replay,
outcomes/retry, exact acknowledgment.

### R07 — Incremental pull

Push before pull and pull after successful pushes. The approved personal policy does **not** pull around retained rejection.
Apply complete contiguous batches and checkpoint atomically under session fences; validate progress/pagination without
interpreting opaque tokens. Explicit cursor expiry invokes R08; invalid/foreign tokens do not silently bootstrap. Absence
in an incremental page never means deletion.

**Gate:** replay/empty tail, page failure/checkpoint rollback, active replacement/dependencies, protected edits and switch/
cancellation. Suggested slices: pull loop, cursor transactions, shared push/pull policy.

### R08 — Staged bootstrap and full resync

Stage a complete stable snapshot outside visible data; verify account/baseline/limit, duplicate roots, token cycles and
termination, including dependencies arriving on later pages. Publish complete cache/baseline under fences while preserving
new local intent. Recover explicit 90-day cursor expiry and accounts with no baseline. Failed/expired/cancelled/incomplete
stages cannot replace visible cache or destroy separate local data selected for replacement.

**Gate:** real Room empty/old-unchanged accounts, middle/final failure, out-of-order references, concurrent edits, restart,
switch/expiry and atomic publication. Suggested slices: staging migration, download validation, publication/recovery.

### R09 — Account transitions and import/replace

Coordinate encrypted vault and nonsecret Room journal without pretending they share a transaction. Sign-out retains private
account cache/outbox; same-account sign-in resumes; different-account sign-in clears only prior account state, not separate
local data/catalogue. Drain accepted edits/terminal rest, advance fences and cancel old work. Import preserves IDs and
captures dependency-ordered create-only customs/routines/workouts, including drafts/valid history; collisions remain
errors, not remapping. Replace deletes the confirmed local graph only with successful complete cloud publication.

**Gate:** A/B/local isolation, both choices with/without local data, interrupted activation/sign-out/switch, collisions,
vault mismatch and delayed responses. Offline sign-out needs no network, but failed disk/drain operations are not success.
Suggested slices: transition journal, import/choice preparation, crash-safe activation/switch/sign-out.

### R10 — Engine convergence and recovery

Core a proves two independent real Room stores through the delivered engine and isolated HTTP—not DTO calls alone—for
lost response/restart/offline recovery, deletes, active replacement, snapshots and newer local intent. Expanded b covers
all families, server-commit-order winners with reversed device clocks, dependencies/names, exact replay, account isolation
and wider concurrency/retention cases. Use scripted failures for unavailable service controls; never invent a clock seam
or wait 90 days. Record independently expected complete outcomes and external limitations.

### R11 — Foreground Google identity

Personal a/b adds Credential Manager behind the identity interface, supplied nonce/server audience/package/signing inputs,
foreground exchange/cancellation and credential-state clearing. Service account identity—not email or decoded Google
claims—owns local data. Keep bounded foreground exchange retries exact; no interactive worker auth or persisted Google
proof/client secret. Deferred c supplies purpose/account-bound export/delete/status proofs. Verify dependency compatibility
at pickup, not from old planning versions. Deterministic adapter tests and actual Google journeys are separate gates.

### R12 — Feature and app reconciliation

Implement a failure-aware finite-edit/admission/publication barrier across routines, pickers, history, app shell and active
workout; then observe Room epochs and rebind scoped repositories/states. Refresh same-ID fields and custom-reference names
even when parent revisions stay equal, preserving accepted drafts/focus. Reconcile navigation/active pill and remote
finish/delete/replacement with timer ownership and at-most-once rest persistence. No framework or process-death timer
redesign. **Gate:** virtual-time state plus real Compose/app-flow tests, including failed drains and private scope visibility.

### R13 — Account UI and foreground sync

Specify usable account controls, unconfigured state, sign-in/out, import/replace confirmations, pending/last-success/error/
reauth status, foreground/edit/manual triggers and retained rejection recovery. Confirm destructive recovery; settle
uncertain frozen work unchanged before discarding confirmed remaining intent and reloading a complete snapshot. Never
claim offline sign-out revoked the service. Preserve signed-out workflows, autosave draining, accessibility and cancellation.
**Gate:** state/Compose/actual app journeys for choices, failure, recovery and account switch; core R10 before activation.

### R02e / R03d — Deferred lifecycle foundations

Add transport-independent account-bound proofs, export v1, deletion receipt/status models and strict codecs; keep durable
mutation versions unchanged and unsupported versions explicit. Then implement only needed lifecycle routes, fresh-proof
failure mapping, exact deletion body/key retry identity and owned streaming with complete-download detection. No unbounded
whole-export String, partial success, bearer-only proof substitute, UI cleanup, or unused browsing adapters.
**Gate:** independent shape/scalar/account/version tests, transport failure/cancellation/stream cleanup, and isolated
interoperability where authorized proof setup exists. These slices enable R14/R15; they do not themselves authorize actions.

### R14 — Cloud JSON export

Explain cloud-only scope and unsynced/device-only omissions; obtain fresh same-account export proof. Validate full v1 data
and use Android document APIs for the chosen destination, preserving IDs/nulls/relationships and excluding credentials/
sync internals. Clean partial artifacts; cancellation/wrong account/unsupported version/truncation cannot succeed. Retry
with fresh proof, not assumed resumability. No restore feature. **Gate:** transport/codec/state/document tests and separate
real Google/user-save journey.

### R15 — Account deletion and old-account status

Offer optional export, irreversible-cloud/immediate-disablement/separate-purge wording, explicit confirmation and fresh
same-account proof. Preserve bounded uncertain deletion identity; recover restart through authoritative status/receipt
without indefinitely storing Google credentials. Generic auth/404/offline failures never authorize cleanup. Verified
receipt clears only that account's cache/outbox/cursor/staging/credentials/work, preserving local data; late A cannot clear
B, and same-Google new signup has a new service UUID. Status/export/delete must not implicitly sign up.
**Gate:** cancellation/lost response/restart/status failure/A–B/new-signup preservation tests and separately authorized live
journeys. Server retention/purge/backups are externally owned. Do not expose Delete before this is delivered.

### R16 — Background scheduling

Use account/session-scoped unique WorkManager work, network constraints, bounded retry and foreground/edit/sign-in wakeups
with periodic recovery. Share the app's engine/store/transport/session coordinator; no parallel client or interactive
Google UI. Preserve uncertain work through worker stopping/restart, prevent duplicate ownership/lost wakeups and suspend
permanent errors. **Gate:** deterministic races plus real offline/background/token-expiry/switch recovery. Integrate deletion
cancellation when R15 ships; R15 does not block personal scheduling.

### R17 — Live personal and public journeys

- **a:** actual Google/service identity, trusted laptop HTTPS, phone LAN/away connectivity, refresh/sign-out, data choice,
  foreground/manual sync/error recovery, offline/reconnect, app/server restart and existing workout/timer behavior.
- **b:** after R16, actual unattended delivery, stopped work/token expiry/restart without lost work; document practical
  connection, server restart, backup/recovery and laptop/network availability limits with owner permission.
- **c:** public expansion after lifecycle features: export/delete/status/new signup, release signing/distribution, minimum
  API 24 and broader devices, actual hosted CI. Mock/local passes cannot substitute for these live gates.

Record environment, permission, actual results and limitations separately per subplan. Never use personal data as an
automated failure-injection fixture. Missing external inputs block the affected live gate, not unrelated implementation.

### R18 — Public readiness audit

Map finalized requirements to code and executed evidence; audit mutation capture, canonical writes, retries/acks/revisions/
cursors, snapshots, identity/name/destructive flows, credentials/log/backup/transfer, APK secrets/test-helper exclusion,
catalogue notices/parity, migrations and release configuration. Run full Android/JVM/lint/build/device plus real-backend
gates. Obtain separately owned retention/deletion/disclosure evidence without claiming legal compliance or auditing backend
operations implicitly. Resolve findings in bounded tasks; publication/commit/push still require authorization.

## External inputs

Track readiness at pickup; no secrets in docs/APK. Current blockers are summarized in the handoff.

| Input | Required before |
|---|---|
| Approved secure laptop endpoint/private connectivity on LAN and away | R17a live service access |
| Matching Google Android client, installed signing certificate and Web/server audience | Real Google sign-in |
| Owner-approved phone, account and test data | Live journeys |
| Practical server start/restart and backup/recovery arrangements | Personal operational handoff |
| Release signing/distribution, broader devices/hosted CI, irreversible-lifecycle permission and external retention evidence | Public gates |

No automatic hosting, public exposure, deployment, private configuration changes, additional login provider, sharing,
public profiles, catalogue updates, export restore, finished-history editing, PR/1RM, or unrelated timer redesign is in scope.
Room remains the responsive UI data source; unused resource browsing routes are not an alternative architecture.
