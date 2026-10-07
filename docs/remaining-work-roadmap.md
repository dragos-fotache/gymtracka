# Android delivery roadmap

**Single execution index.** Update status in place; keep completed-task narratives in the archive. This roadmap defines
outcomes and dependencies; the owner's [standing workflow rule](coordinator-workflow.md#coordinator-rotation-and-automatic-continuation)
authorizes scoped slice commits and fresh-coordinator continuation within the finite
[major-task slice plan](coordinator-workflow.md#major-task-slicing--owner-rule), with user escalation for blockers/questions
and proposed additional splits. Detailed specifications are created [on pickup](coordinator-workflow.md).
Current pickup and blockers are in [the handoff](coordinator-handoff.md); Android integration decisions are in
[the approved design](sync/integration-design.md). Public v1/backend requirements are unchanged; the narrower personal
account milestone is approved in [P16](../shared-docs/docs/backend-contract/open-decisions.md#confirmed-product-decisions).

## Delivery priorities

1. **Usable personal app:** owner’s phone against an always-on laptop service; R01–R08, reduced single-account R09,
   core R10, sign-in/reauthentication R11, R12–R13 and R17a. Initial sign-in is mandatory; activated account use/editing
   continues offline without online login every launch. Foreground/edit-triggered sync and manual retry suffice.
2. **Unattended personal use:** R16 and R17b offline/restart/background recovery. No new hosting platform or high-availability
   promise is required.
3. **Future public release:** lifecycle/export/deletion slices, expanded compatibility/CI/live gates and R18. These remain
   obligations, but do not block trying a safe personal app. They can be brought forward by owner direction.

Personal use does not relax transactional safety, exact retries, account/privacy fencing, credential security, or complete
snapshot publication. Accepted personal slices never imply completion of their deferred public counterparts.

## Status and dependencies

[R09h app-facing authority/offline recovery](archive/remaining-work/R09h-review.md) is accepted. R09a–h close the finite
[reduced authority/recovery plan](#remaining-r09-plan), including [P17](../shared-docs/docs/backend-contract/open-decisions.md#confirmed-product-decisions)
retention/fencing of exact uncertain encrypted evidence. The delivered boundary supports private activation and matching
offline recovery but does **not** activate current production startup/screens/publisher or resolve legacy local-data disposition.

[R12a real edit/timer barriers](archive/remaining-work/R12a-review.md), [R12b observation/feature refresh](archive/remaining-work/R12b-review.md)
and [Core R10a convergence/recovery](archive/remaining-work/R10a-review.md) remain accepted. **R12c is the next eligible
integration slice:** its R09h authority/core convergence dependencies are delivered. It still owns real scoped UI/timer
reconciliation and package gates; no no-op callback or private-scope exposure is permitted. The owner requires stopping
after R09h's scoped commit and prepared handoff, with no successor launch; see [current authority](coordinator-handoff.md).
Google identity/account UI/triggers and live journeys remain R11/R13/R17. No new R09 slices or deferred-public work is inferred.
“Pending” requires eligibility under explicit/standing authority and a bounded pickup spec, not another approval for each
eligible next slice. “Deferred” remains required for its later
milestone and is not automatically brought forward. Historical M-series assignments are not a second execution plan.

| Package | Status / outstanding scope | Dependencies |
|---|---|---|
| R01 integration design | Accepted; maintained in the design reference | Existing foundations |
| R02 models/API | a–d accepted; **e lifecycle/proof/export/delete/status models deferred** | R01; e with lifecycle features |
| R03 HTTP | a primitive, b sync, c auth accepted; **d lifecycle/export streaming deferred** | Relevant R02 slices; d needs e |
| R04 session recovery | a vault, b refresh, c startup/session recovery seams accepted; durable Room integration remains R09 | R03a/c; c uses a/b |
| R05 canonical Room / local intent | **a storage/capture watermarks, b canonical batches and c protected publication accepted**; production activation remains later integration | R02b and approved integration design |
| R06 durable push | **a claiming/exact replay, b outcomes/retry, c atomic canonical acknowledgment and d bounded delivery loop accepted**; production activation remains later integration | R03b, R04, R05 |
| R07 incremental pull | **a checkpoint transactions, b bounded HTTP pull, c shared push-first/valid-tail orchestration and package gates accepted** | R06; b after a, c after b |
| R08 bootstrap/full resync | **a staging, b bounded HTTP download and c complete canonical baseline/absence/protected publication recovery accepted; no further R08 slice** | R05–R07; app-facing authority/convergence/barriers remain R09/R10/R12 |
| R09 single-account activation/recovery | a–h accepted; finite reduced authority/recovery boundary closed. Explicit app-facing authority/private activation/offline recovery and bounded reconnect delivered; current startup/screens/publisher unchanged. Legacy-data disposition unresolved; general transitions/import-replace UI deferred public | R04/R08 and accepted R12a/core R10; live scoped integration R12c, identity/UI/triggers R11/R13 |
| R10 engine convergence/recovery | Core a accepted; expanded b deferred before public release | R03b/c, R06–R09 |
| R11 Google identity/proofs | Personal sign-in/reauthentication pending; sign-out UI and lifecycle proofs deferred public | Relevant R02, R04 |
| R12 feature/app reconciliation | Finite three-slice plan; a real edit/publication/timer barriers and b projection observation/feature refresh accepted; c scoped app/timer rebinding pending | R05/R07/R09 integration; barriers can be developed earlier |
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
- Room v12, existing mutation capture/watermarks, all 17 kinds, codecs, transport, vault, refresh, inert staging/recovery and
  private authority/journal and prepared vault/Room coordination seams are foundations to reuse, not tasks to recreate.
  The retired simulator is not a prerequisite or backlog item.

## Outstanding acceptance outcomes

These are capability requirements, not detailed assignments. Split further when authorized; specify concrete APIs/schema,
independent tests, allowed files and stop conditions before implementation. Tests belong inside every slice, not only R10/R17.

### R05 — Canonical Room application and protected intent

R05a storage/capture watermarks, R05b fenced canonical batches and R05c protected publication are accepted; see the
[R05a review](archive/remaining-work/R05a-review.md), [R05b review](archive/remaining-work/R05b-review.md) and
[R05c review](archive/remaining-work/R05c-review.md). The app constructs no publisher; R12a barriers and later engine/
baseline/session integration remain prerequisites for production activation. R06a claiming/replay, R06b single-head
outcome/retry handling, R06c atomic acknowledgment and R06d bounded push delivery are accepted; pull/engine convergence
and production integration remain outstanding.

Actual store boundaries and regression invariants are owned by [sync implementation](sync/implementation.md).
R05 requires no further implementation slice. R08c supplies inert complete baseline establishment; durable session
authority remains R09 and live edit/timer leases/observation R12. Accepted store evidence does not authorize activation.

### R06 — Durable push

[R06a](archive/remaining-work/R06a-review.md) claiming/replay,
[R06b](archive/remaining-work/R06b-review.md) single-head outcomes/retry,
[R06c](archive/remaining-work/R06c-review.md) atomic acknowledgment and
[R06d](archive/remaining-work/R06d-review.md) bounded push delivery are accepted over Room v10.
The explicitly constructed inert driver composes these seams under the shared run owner, sending one exact head outside
Room and continuing only after atomic acknowledgment. No further R06 implementation slice remains. No accepted slice
activates sync; R07a checkpoint transactions, R07b bounded HTTP pull and R07c shared push-first/valid-tail orchestration
are accepted. R07 package gates are recorded in the [R07c review](archive/remaining-work/R07c-review.md); complete
bootstrap and later activation remain separate.

Serialize account/session runs; claim/freeze the explicit sequence head transactionally, then perform HTTP outside Room.
Recover uncertain requests unchanged. Canonical outcome and exact request/generation/affected-root acknowledgment commit
together; push cursors never advance the pull checkpoint. Preserve newer edits and retained rejection/corruption/oversize
errors; do not bypass a rejected predecessor, endlessly retry an unrecoverable 413, or invent another mutation ID.

**Gate delivered:** real Room plus controlled timing/transport for lost responses, cancellation/restart, late results,
malformed/mismatched outcomes, accepted/rejected duplicates, stale accounts and rollback. R06 package full Android
instrumentation/build/lint gates are recorded in the R06d review. Real-service engine convergence remains R10.

### R07 — Incremental pull

Push before pull and pull after successful pushes. The approved personal policy does **not** pull around retained rejection.
Apply complete contiguous batches and checkpoint atomically under session fences; validate progress/pagination without
interpreting opaque tokens. Explicit cursor expiry invokes R08; invalid/foreign tokens do not silently bootstrap. Absence
in an incremental page never means deletion.

**Gate:** replay/empty tail, page failure/checkpoint rollback, active replacement/dependencies, protected edits and switch/
cancellation. [R07a review](archive/remaining-work/R07a-review.md) accepts the inert fenced batch/checkpoint transactions
on Room v10; [R07b review](archive/remaining-work/R07b-review.md) accepts bounded HTTP pull on an already admitted run.
[R07c review](archive/remaining-work/R07c-review.md) accepts uninterrupted shared push-first orchestration,
retained-error policy, exact-checkpoint valid-tail/last-success bookkeeping and full R07 package instrumentation/
assembly/lint/APK gates. Outstanding newer intent, publication and convergence stay separate from bounded tail.
No further R07 slice remains. R08a–c and reduced R09a–c private authority/preparation/engine bootstrap are accepted;
the current handoff owns the next R09 slice under the standing rotation workflow.

### R08 — Staged bootstrap and full resync

Stage a complete stable snapshot outside visible data; verify account/baseline/limit, duplicate roots, token cycles and
termination, including dependencies arriving on later pages. Publish complete cache/baseline under fences while preserving
new local intent. Recover explicit 90-day cursor expiry and accounts with no baseline. Failed/expired/cancelled/incomplete
stages cannot replace visible cache or destroy separate local data selected for replacement.

**Gate:** real Room empty/old-unchanged accounts, middle/final failure, out-of-order references, concurrent edits, restart,
switch/expiry and atomic publication. [R08a review](archive/remaining-work/R08a-review.md) accepts the Room10→11 durable
staging migration and inert same-run storage, strict whole-record/progress ownership, selected-stage exclusion and
explicit discard/restart recovery, preserving canonical/domain/outbox/checkpoint/other scopes. Staging alone is NOT
complete bootstrap, absence authority or activation.

[R08b bounded frozen-snapshot download validation](archive/remaining-work/R08b-review.md) accepts actual pushEmpty-gated
HTTP download on the same admitted run, exact staged tokens/fixed limit and response/failure/cancellation/expiry fences.
Same-run bounded continuation has no permanent account-size cap; durable read/restart never grants append authority.
Downloaded remains inert transport/staging progress, not a validated account graph or canonical/domain publication.
[R08c complete canonical baseline, absence and publication recovery](archive/remaining-work/R08c-review.md) accepts
actual-terminal issuance/current-run completion, whole frozen account graph/revision/family validation and one atomic
canonical baseline/absence/checkpoint/stage-removal transaction. Same-run paired publication protects newer intent,
adopts returned epochs and recovers durable dirty/clean baselines after crash or lost results. Completion/publication
preserve last-success; a subsequent actual pull tail remains separate. Full R08 package instrumentation, JVM, isolated
backend, assembly/lint/APK and preservation gates passed as scoped in that review.

No further R08 slice remains. R09h composes durable authority/private bootstrap over these seams, with R12a and Core R10a
accepted as its named prerequisites. Live scoped publication/UI/timer integration remains R12c; no store/baseline primitive
alone activates production.

### R09 — Single-account activation, recovery and reauthentication

**Reduced personal scope:** coordinate encrypted vault and durable nonsecret Room account authority/journal without
pretending they share a transaction. Initial sign-in and complete account bootstrap gate workout use. Recover interrupted
activation and same-account reauthentication; bind service/account/generation, preserve exact uncertain requests and
pending edits, drain accepted edits/terminal rest when required, and fence late results. An activated matching account
remains usable/editable offline across ordinary restarts without a fresh online login each launch. Access expiry follows
normal serialized refresh; unrecoverable credential expiry suspends delivery for same-account reauthentication, not
local-data deletion. Missing/mismatched vault fails closed/private.
A different service account UUID cannot silently adopt retained data or pending work, even for the same Google identity.

Pre-existing local-only data disposition is **unresolved**. Before any activation that encounters it, obtain the owner's
choice of one-time preservation/import or confirmed clean/cloud-only start and scope that work. No deletion/import is
currently authorized; no general choice UI is required for new installations.

**Personal gate:** interrupted initial activation/reauthentication, matching offline restart/edit/reconnect, vault/Room
mismatch, delayed callbacks, different-account rejection and pending-work preservation; include the selected legacy-data
migration gate once decided. R12a barriers and core R10 remain required before production activation. First bounded
slice: [R09a durable private account authority/journal](archive/remaining-work/R09a-review.md), accepted with inert
storage/read/reservation/private fencing. [R09b prepared coordination/recovery](archive/remaining-work/R09b-review.md)
is accepted, stopping at stable private observations without occupied credential replacement or engine admission.
[R09c private prepared engine/canonical bootstrap](archive/remaining-work/R09c-review.md) is accepted, reusing complete
baseline and actual valid-tail drivers without projection, promotion or ACTIVE writing.
[R09d completed preparation/promotion](archive/remaining-work/R09d-review.md) is accepted, separately composing complete
projection/Room ACTIVE-ready in one transaction and exact vault phase promotion/recovery without app activation.
[R09e settled occupied ACTIVE reauthentication](archive/remaining-work/R09e-review.md) is accepted: private TRANSITIONING
reservation/new generation, exact full supplied-grant PREPARED vault CAS and PREPARING finalization, retaining all intent
and refusing uncertain pending predecessors. It is not all same-account recovery. [R09f exact established ACTIVE pending replay](archive/remaining-work/R09f-review.md)
is accepted as a separate prerequisite: shared refresh machinery sends ONE retained exact attempt and commits its complete
original grant/no-pending without fresh rotation or Room writes, returning advisory settled old-ACTIVE evidence. R09e must
freshly admit replacement. [R09g complete inert same-account recovery](archive/remaining-work/R09g-review.md) is accepted:
P17 fresh-grant supersession atomically retains/fences exact uncertain encrypted evidence, including signed-out/missing-vault
and interrupted older-generation gaps, without domain/cache/intent changes. No retirement/cleanup or old-credential authority.
[R09h](archive/remaining-work/R09h-review.md) composes the app-facing authority/private activation/offline recovery boundary,
with mandatory supplied barriers, exact expectations and process-fenced repositories. Production startup is unchanged;
live scoped publication/rebinding remains R12c and account UI/triggers R13. No legacy-data disposition is inferred.

### Remaining R09 plan

**Finite reduced R09 authority/recovery plan closed: R09a–h are accepted.** The owner's confirmed R09h boundary delivers
reusable app-facing authority without current screen/publisher startup activation. Usable personal-account delivery still
needs the separate R12c/R11/R13 integration and R17 gates, plus owner disposition before affected legacy-data activation.
No hidden prerequisite slices, sub-letter sequences or R09i are authorized. A genuine safety/dependency reason for revising
this finite plan must go to the owner; deferred public transitions remain separate obligations.

| Slice | Outcome and completion boundary | Dependencies / blockers | Non-goals |
|---|---|---|---|
| **R09g — complete same-account recovery (accepted)** | [Review](archive/remaining-work/R09g-review.md): signed-out/missing-vault and uncertain/interrupted recovery with exact encrypted evidence retention/fencing and fresh same-account grants; strictly older evidence never supplies authority. Preserves all cached data/intent; stops at inert preparation eligibility. | R09a–f delivered. P17 disposition implemented without schema expansion. Unreadable/foreign/unknown-provenance or incompatible same/newer states remain preserved/private; legacy choice unresolved. | No Google adapter/account UI, fresh uncertain retry key, expiry rounding, implicit evidence retirement/cleanup, account switching/sign-out UI, legacy import/deletion, production activation or convergence work. |
| **R09h — app-facing activation and offline recovery (accepted)** | [Review](archive/remaining-work/R09h-review.md): explicit authority/private activation/offline recovery and bounded reconnect over delivered seams; matching cache reopens/edits offline, exact expected grants and process guards fence stale work. Mandatory transition/publication inputs; no production startup/screens/publisher activation. | R09g, R12a/core R10 accepted. R12c consumes delivered authority for real scoped/timer integration. Owner choice remains required before affected legacy-data activation. | Google identity R11; account UI/triggers R13; no switching/sign-out/import-replace UI, legacy disposition, scheduler, public lifecycle, deployment or live account/device actions. |

R09g implements P17 without implicit retirement/discard of uncertain evidence; new unresolved disposition/blockers still
require user escalation. R09h cannot bypass its external package dependencies; if
sequencing those packages is required, bring the dependency/next-task question to the user instead of adding more R09 slices.

**Deferred public:** signed-out local editing, sign-out and switching UI, and general import/replace choices. Retain their
existing isolation, drain, crash and collision obligations: sign-out keeps private account cache/outbox; switching never
copies A into B; import preserves IDs/create-only dependency order; replace deletes only the confirmed separate local
graph with complete successful cloud publication. Deferral is not acceptance or permission to erase legacy data.

### R10 — Engine convergence and recovery

**Finite plan: two slices, R10a and R10b.** Core R10a is the personal pre-activation convergence gate; expanded R10b
remains deferred until public release. No hidden prerequisite slices or production activation are part of this plan.

| Slice | Outcome / completion boundary | Dependencies | Non-goals |
|---|---|---|---|
| **R10a — core engine convergence and recovery (accepted)** | Two independent real, reopenable Room stores drive the delivered push/pull/snapshot/publication engine through isolated black-box HTTP. Prove lost-response exact replay, offline/restart recovery, deletes/active replacement, complete frozen/interrupted snapshots and newer local intent with independently expected complete outcomes. | R03b/c, R06–R09 inert foundations, R12a barriers; isolated service and owned emulator. Evidence: [R10a review](archive/remaining-work/R10a-review.md). | No production activation/new app-facing authority, R12c, real Google/phone/deployment, backend source review or personal data/configuration. |
| **R10b — expanded convergence (deferred public)** | Broader all-family conflict, reversed-device-clock/server-commit-order, dependency/name, exact replay, account-isolation and concurrency/retention coverage; close full R10 after expanded gates. | R10a; public milestone inputs and applicable integration gates. | No invented service clock control, waiting 90 days, or implicit deployment/lifecycle authorization. |

R10a uses representative cross-family graphs, not an exhaustive all-kind matrix already covered by HTTP/store packages.
Reuse scripted failures for unavailable service controls and record external limitations. R10 completion requires both
slices; accepting core alone only satisfies the declared personal convergence prerequisite. Detailed APIs, independent
assignments and risk-selected gates belong to each slice specification at pickup. Additional splits require owner escalation.

### R11 — Foreground Google identity

Personal delivery adds Credential Manager behind the identity interface, supplied nonce/server audience/package/signing
inputs and foreground sign-in/reauthentication exchange/cancellation. Sign-out UI and its credential-state clearing
journey are deferred with public account workflows. Service account identity—not email or decoded Google
claims—owns local data. Keep bounded foreground exchange retries exact; no interactive worker auth or persisted Google
proof/client secret. Deferred c supplies purpose/account-bound export/delete/status proofs. Verify dependency compatibility
at pickup, not from old planning versions. Deterministic adapter tests and actual Google journeys are separate gates.

### R12 — Feature and app reconciliation

**Finite plan: three slices, R12a–c, then close R12 after its package gates.** R12a/b are accepted; R12c remains pending
scoped integration/package acceptance. R09h authority and Core R10a are accepted; R12c is eligible for the next fresh-coordinator pickup. Additional splitting
requires user escalation.

| Slice | Outcome / completion boundary | Dependencies | Non-goals |
|---|---|---|---|
| **R12a — real finite-edit and timer barriers (accepted)** | [Review](archive/remaining-work/R12a-review.md). One app-owned admission/drain boundary covers every feature mutation and survives screen disposal. Failed/cancelled accepted writes remain visible to drains; paused callbacks cannot optimistically accept edits. Short request-bound publication leases exclude new edits; serial terminal rest writes expose failures and preserve at-most-once claims. Explicit timer-impact decision composes with the real barrier before any future publisher. Prove state races and actual Compose wiring. | Existing R05 publication contract, R09 inert coordination and current feature/repository seams; no R10 dependency for inert barrier delivery. | No engine/publisher construction at startup, account activation, epoch observation, remote navigation reconstruction, new schema, timer process-death redesign or legacy-data choice. |
| **R12b — projection observation and feature refresh (accepted)** | [Review](archive/remaining-work/R12b-review.md). Narrow fenced materialized-epoch observation and selected routine/picker/history/active refresh at equal IDs/parent revisions; current custom-reference joins, accepted drafts, latest-load/scope guards, stable focus and dialog drafts. Pure action lists do not drain unrelated writes; configured-rest wheel settles without self-triggering. | R12a; existing Room publication epochs. Remains inert/local-scoped until app-facing account authority is delivered. | No account visibility grant, engine activation, app/pill/timer reconstruction, Google/account UI or timer redesign. |
| **R12c — scoped app and timer reconciliation / package closure (pending)** | Compose delivered scoped authority and epoch observation with repository/state rebinding, app navigation/active pill and canonical timer-impact decisions. Remote finish/delete/replacement/completion invalidation drains rest while the old set exists, rechecks local-intent protection and preserves valid unchanged timers. Complete private-scope and real Compose/app-flow package gates. | R12a/b, R09h app-facing authority and Core R10a are accepted. Use real scoped/timer barriers and preserve authority through every read/write/publication boundary; missing safe inputs block integration, never permit private-scope exposure. | No R11 identity adapter, R13 account UI/triggers, framework migration, cloud cleanup/import or live deployment. |

R12 completion means all three outcomes and full package instrumentation/assembly/lint gates are accepted, including
scope privacy and actual app/timer reconciliation. It does not close R09/R10/R11/R13 or authorize live accounts.
Detailed APIs/tests belong to each slice specification; selecting R12 never bypasses core R10 or activation gates.

Implement a failure-aware finite-edit/admission/publication barrier across routines, pickers, history, app shell and active
workout; then observe Room epochs and rebind scoped repositories/states. Refresh same-ID fields and custom-reference names
even when parent revisions stay equal, preserving accepted drafts/focus. Reconcile navigation/active pill and remote
finish/delete/replacement with timer ownership and at-most-once rest persistence. No framework or process-death timer
redesign. **Gate:** virtual-time state plus real Compose/app-flow tests, including failed drains and private scope visibility.

### R13 — Account UI and foreground sync

Personal UI gates workout access on initial sign-in/complete activation, exposes configuration/sign-in/reauthentication
and pending/last-success/error status, and permits established-account offline editing. Add foreground/edit/manual
triggers and retained rejection recovery. An unconfigured new installation shows configuration/sign-in state, not
signed-out workout editing. Confirm destructive recovery; settle uncertain frozen work unchanged before discarding
confirmed remaining intent and reloading a complete snapshot. Preserve autosave draining, accessibility and cancellation.
**Personal gate:** state/Compose/actual app journeys for initial access gating, offline restart/edit/reconnect, failure,
reauthentication and recovery; selected legacy-data handling once decided; core R10 before activation.
**Deferred public:** signed-out workflows, sign-out/account-switch controls and general import/replace confirmations with
their choice/isolation gates. Offline sign-out must never claim server revocation.

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
