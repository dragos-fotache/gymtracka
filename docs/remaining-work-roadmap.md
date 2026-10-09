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
offline recovery; those primitives alone do **not** enable startup or legacy migration. [P18](../shared-docs/docs/backend-contract/open-decisions.md#confirmed-product-decisions)
now selects one-time preservation/import of existing local-only data; its bounded implementation/gates belong to R13.

[R12a real edit/timer barriers](archive/remaining-work/R12a-review.md), [R12b observation/feature refresh](archive/remaining-work/R12b-review.md)
and [Core R10a convergence/recovery](archive/remaining-work/R10a-review.md) remain accepted. **[R12c](archive/remaining-work/R12c-review.md)
and the finite R12a–c package are accepted:** explicit scoped runtime/root, real timer/edit barriers, guarded ACTIVE
reconciliation/full-resync and complete package gates are delivered. **Personal R11a–b is
accepted, deterministic-only:** [Google adapter](archive/remaining-work/R11a-review.md),
[bounded foreground exchange/guarded grant handoff and personal package gates](archive/remaining-work/R11b-review.md).
Mandatory account startup is delivered by R13c; no live journey is released. R13 has a finite [four-slice personal plan](#remaining-r13-plan)
for account UI/triggers and P18 legacy preservation/import. **[R13a](archive/remaining-work/R13a-review.md) is accepted:
inert durable source/import capture and private recovery on Room13.** **[R13b](archive/remaining-work/R13b-review.md) is accepted:** opt-in exact import delivery, complete private activation/recovery and actual isolated Room/vault HTTP gates on Room14. **[R13c](archive/remaining-work/R13c-review.md) is accepted:** mandatory account startup/UI, matching offline reopen/edit, foreground same-account reauthentication, import consent/progress and safe status. R13d remains pending; the owner requires stopping after R13c. See [current authority](coordinator-handoff.md).
Automatic synchronization/confirmed ordinary recovery and package closure remain R13d; live journeys remain R17 and R11c stays deferred public.
No new R09 slices or deferred-public work is inferred.
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
| R09 single-account activation/recovery | a–h accepted; finite reduced authority/recovery boundary closed. Explicit app-facing authority/private activation/offline recovery and bounded reconnect delivered; primitive delivery alone does not activate screens/publisher. Legacy private capture/delivery/activation is delivered by R13a–b; mandatory startup/account UI is delivered by R13c; general transitions/import-replace UI deferred public | R04/R08 and accepted R12a/core R10; live scoped integration R12c, identity/UI/triggers R11/R13 |
| R10 engine convergence/recovery | Core a accepted; expanded b deferred before public release | R03b/c, R06–R09 |
| R11 Google identity/proofs | Finite three-slice plan: **a–b accepted deterministic-only, personal package closed; c lifecycle proofs deferred public**. Explicit foreground exchange consumes grants through guarded private preparation; sign-out UI remains deferred public R13 | Personal dependencies accepted; c needs lifecycle foundations and public release scope |
| R12 feature/app reconciliation | **a–c accepted; finite package closed**: real barriers, projection observation, explicit scoped app/timer runtime, ACTIVE reconciliation/full-resync and package gates | R12a/b, R09h and Core R10a accepted; default startup/UI delivered by R13c; triggers remain R13d |
| R13 account UI/foreground sync | Finite personal a–d plan recorded below; a–b accepted private/opt-in, c accepted mandatory startup/UI, d pending; owner stop after c. P18 preservation/import and activation/recovery stay inside R13; public workflows deferred | R09, core R10, personal R11 and R12 accepted; a → b → c → d; import gates precede affected activation |
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
- Room v13, existing mutation capture/watermarks, all 17 kinds, codecs, transport, vault, refresh, inert staging/recovery,
  legacy private capture, authority/journal and prepared vault/Room coordination seams are foundations to reuse, not tasks to recreate.
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
accepted as its named prerequisites. R12c supplies explicit scoped publication/UI/timer integration; no store/baseline primitive
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

Pre-existing local-only data disposition is **decided: preserve and import once into the first service-confirmed account**
under P18. Implementation belongs to a bounded R13 import/activation workstream; R09a–h remain closed. Preserve the graph
until transactional capture, definitive import settlement and complete snapshot publication are implemented/verified.
No destructive clean/cloud-only start is selected; current activation still refuses local roots. No general choice UI is
required for new installations.

**Personal gate:** interrupted initial activation/reauthentication, matching offline restart/edit/reconnect, vault/Room
mismatch, delayed callbacks, different-account rejection and pending-work preservation; R13 additionally owns the approved
one-time legacy import gate. R12a barriers and core R10 remain required before production activation. First bounded
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
with mandatory supplied barriers, exact expectations and process-fenced repositories. Scoped publication/rebinding is
delivered by R12c; P18 capture/delivery by R13a–b and mandatory account startup/UI by R13c. Triggers/recovery closure remain R13d.

### Remaining R09 plan

**Finite reduced R09 authority/recovery plan closed: R09a–h are accepted.** The owner's confirmed R09h boundary delivers
reusable app-facing authority, not standalone screen/publisher activation. Usable personal-account delivery still
uses accepted R12c/personal R11 integration and still needs R13/R17 gates, including implemented P18 import before affected
legacy-data activation.
No hidden prerequisite slices, sub-letter sequences or R09i are authorized. A genuine safety/dependency reason for revising
this finite plan must go to the owner; deferred public transitions remain separate obligations.

| Slice | Outcome and completion boundary | Dependencies / blockers | Non-goals |
|---|---|---|---|
| **R09g — complete same-account recovery (accepted)** | [Review](archive/remaining-work/R09g-review.md): signed-out/missing-vault and uncertain/interrupted recovery with exact encrypted evidence retention/fencing and fresh same-account grants; strictly older evidence never supplies authority. Preserves all cached data/intent; stops at inert preparation eligibility. | R09a–f delivered. P17 disposition implemented without schema expansion. Unreadable/foreign/unknown-provenance or incompatible same/newer states remain preserved/private; approved P18 import implementation remains R13. | No Google adapter/account UI, fresh uncertain retry key, expiry rounding, implicit evidence retirement/cleanup, account switching/sign-out UI, legacy import/deletion, production activation or convergence work. |
| **R09h — app-facing activation and offline recovery (accepted)** | [Review](archive/remaining-work/R09h-review.md): explicit authority/private activation/offline recovery and bounded reconnect over delivered seams; matching cache reopens/edits offline, exact expected grants and process guards fence stale work. Mandatory transition/publication inputs; no production startup/screens/publisher activation. | R09g, R12a/core R10 accepted. R12c consumes delivered authority for real scoped/timer integration. Approved P18 import must be implemented/verified in R13 before affected legacy-data activation. | Google identity R11; account UI/triggers R13; no switching/sign-out/import-replace UI, legacy disposition, scheduler, public lifecycle, deployment or live account/device actions. |

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

**Finite plan: three slices total; two personal slices (R11a–b), one deferred public slice (R11c).**
[R11a](archive/remaining-work/R11a-review.md) and [R11b](archive/remaining-work/R11b-review.md) are accepted with injected
synthetic configuration; personal R11 and its declared synthetic/package gates are closed. Actual Google/configuration
verification remains separately gated. R11c is deferred public, not another personal slice. Any additional split requires
the [major-task slicing rule](coordinator-workflow.md#major-task-slicing--owner-rule).

| Slice | Outcome / completion boundary | Dependencies / input gates | Non-goals |
|---|---|---|---|
| **R11a — foreground Android Google identity adapter (accepted personal, deterministic-only)** | [Review](archive/remaining-work/R11a-review.md): existing SDK-independent provider, exact nonce/audience Google button request and actual credential parser, opaque token, safe failures, shared admission/time bounds, Activity retirement and late/queued-result fences; explicit clear primitive. Independent API24/current and declared compile/JVM/dependency/assembly/lint/APK gates passed. | Existing R02 identity boundary; synthetic injected configuration scope confirmed; matched SDK dependency/API24 packaging compatibility verified. Actual Google configuration/provider journey remains R17a. | No service challenge/exchange orchestration, token decoding as authority, automatic sign-in on construction/startup, account/sign-out UI, Room/vault writes, cloud activation, personal configuration or actual Google journey. |
| **R11b — bounded foreground sign-in/reauthentication exchange (accepted personal, deterministic-only)** | [Review](archive/remaining-work/R11b-review.md): one SIGN_IN challenge/Google acquisition and captured exact exchange with bounded transient retries; private grant consumption through existing Room/vault/real-barrier authority, preserving origin/intent/expected Room and captured process fence. Concurrency, disposal, queued/stale handoff and same-account retention verified; declared personal package gates passed. | Accepted R11a/R03c/R04/R09h/R12c reused. Synthetic disposable resources only; actual provider/audience/signing/HTTPS remains R17a. | No replacement auth transport/vault/refresh/authority, bootstrap/publication or ACTIVE admission on Google success alone, account screens/startup/triggers, legacy import, switching/sign-out, scheduler or lifecycle proofs. |
| **R11c — purpose/account-bound lifecycle proofs (deferred public)** | Reuse the foreground adapter for fresh `export`, `deleteAccount` and `accountStatus` challenges, retaining exact target/purpose/attempt context and cancellation/stale-result fences. Supply proof handoff to the lifecycle adapters; never create a service account or substitute a sign-in grant for proof. Independent proof orchestration and account/purpose refusal gates close the remaining R11 obligation. | R11a–b; relevant R02e/R03d lifecycle models/adapters and separately released public workflow scope; authoritative [fresh-proof contract](../shared-docs/docs/backend-contract/account-lifecycle.md#fresh-google-proof). | No export file delivery, deletion acceptance/cleanup/status recovery or sign-out/account-switch UI implementation; those belong to R14/R15/public R13. No automatic pull-forward into personal delivery. |

**Reuse and ownership.** `data/sync/api/ServiceAuth.kt` already defines `GoogleIdentityProvider`, opaque credentials,
service challenges/grants and `ServiceAuthApi`; `ServiceAuthRequests.kt` owns immutable exact exchange attempts.
`HttpServiceAuthApi` already performs single-send secure auth HTTP with strict decoding and typed failures. R04 owns
credential encryption/exact refresh recovery; R09h's `AppSessionCoordinator.acceptGrant(expected, operationId, session)`
accepts fresh service-confirmed grants under exact current expectations and real transition barriers, returning private
preparation rather than activation. R12c supplies the explicit scoped runtime. Extend these narrow seams, not duplicate
them or place Google SDK types in session/sync/domain interfaces. The delivered foreground handoff preserves caller
origin/intent, expected Room authority, captured facade process fence and invocation liveness; R09 remains the definitive
guard against different-account adoption and stale installation. R13 owns consuming the foreground flow in UI and
completing activation, including P18 legacy import.
No schema or bundled-asset change is planned; a discovered persistence requirement is a scope question, not permission
for another authority layer or persisted Google proof.

**Execution inputs and dependency gate.** R11a–b used owner-confirmed deterministic-only injected configuration.
Required synthetic/package gates passed in their reviews; live gates stay unverified. Before any later live verification,
ask the owner to supply/confirm the intended
HTTPS service origin, web/server OAuth audience ID and matching backend audience, Android OAuth registration for
`com.dragosfotache.gymtracka`, intended debug/release signing fingerprints, and live-verification resource/permission
boundary. Deterministic-only acceptance does not supply those inputs or authorize live actions. Do not discover values
in private configuration, generate/change signing keys, configure Google/backend,
embed a client secret or invent a personal endpoint. The nonce is **not** a static owner input: each service challenge
supplies it and the adapter passes it unchanged; generic Google examples generating a client nonce do not override the
[shared auth contract](../shared-docs/docs/backend-contract/authentication.md#https-endpoints).

The [official integration guide](https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation)
uses `GetSignInWithGoogleOption` for an explicit button and a current Activity context for UI. Prefer that bounded flow;
no automatic bottom-sheet/auto-select ladder is needed for personal delivery. The
[accepted adapter](sync/implementation.md#foreground-android-google-identity) owns the matched stable SDK versions;
[R11a review](archive/remaining-work/R11a-review.md) records the resolved graph, upstream alpha transitive, manifest/min24
and actual synthetic compatibility checks. Actual provider compatibility remains R17a. Recheck APIs/transitives and
Kotlin/AGP/Compose/manifest compatibility if dependencies change; consult the
[Credentials release page](https://developer.android.com/jetpack/androidx/releases/credentials) and
[Google Maven metadata](https://dl.google.com/dl/android/maven2/com/google/android/libraries/identity/googleid/googleid/maven-metadata.xml),
not guide alpha examples or silent minSdk increases. Keep lifetime/cancellation safe without alpha-only API reliance.

**Foreground safety boundary.** Each explicit attempt has one owner, captured configuration/intent and a finite retry/
time budget fixed in its specification. No interactive auth from a worker, replay of superseded/disposed attempts,
automatic new challenge/token/key after uncertain exchange, or generic failure treated as account deletion. Before a
first exchange, an expired challenge refuses (`now >= expiresAt`); an already-sent uncertain exchange may replay only
its original challenge/token/key/body within the foreground budget, since the service checks committed replay before
consumed/expired-challenge rejection. Do not recapture bytes, change credentials, round deadlines or extend returned
grant expiry. A valid replay with expired access preserves the original grant; the existing refresh boundary, not a
new exchange under the old key, handles access renewal. Google token/challenge/exchange body remain foreground-memory
only, excluded from saved state, Room, vault, logs and diagnostics. Cancellation/process death cannot promise server
rollback or durable exchange recovery; a later explicit sign-in is a distinct attempt, not settlement of the lost one.
The caller cannot install a late grant against newer authority. Only service UUID/origin establish account ownership;
email/display names and decoded Google claims never do.

**Proportionate verification and completion.** Authentication/security/dependency risk requires independent expected
oracles, deterministic clocks/UUIDs/virtual time and representative cancellation/late-result/concurrency cases:

- **R11a:** prove exact audience/nonce/request policy, Android credential type/parser handling, provider/user/caller
  cancellation, absent configuration/provider and retired Activity behavior using an injected SDK launch boundary.
  Execute focused Android adapter tests on API24 and a current API; these do not contact real Google. Run Kotlin and
  instrumentation compilation, full offline JVM, assembly/lint and APK/merged-manifest/secret-exclusion checks because
  dependencies and Android packaging change.
- **R11b:** prove challenge→credential→exchange ordering, one captured exact attempt across lost-response/throttled
  retry, bounded termination and nonretryable proof rejection, cancellation at seams, simultaneous/stale handoff and
  different-service/account refusal. Reuse existing auth codec/HTTP and R04/R09 suites instead of duplicating their
  matrices. Add focused real Room/vault/real-barrier handoff checks for private preparation and refusal preserving
  account data, pending intent and uncertain evidence; no synthetic successful Google token is a real-provider pass.
  Slice acceptance includes affected checks and full offline JVM. At personal R11 package closure run full unfiltered
  default instrumentation, assembly/lint/APK checks and the existing isolated auth interoperability suite (challenge,
  invalid-proof rejection and refresh/replay), not another whole engine-convergence run. Any newly touched transaction
  or capture behavior needs its actual Room gate; unavailable required synthetic gates block acceptance.
- **R11c:** specify the affected purpose/account/freshness/cancellation matrix and relevant lifecycle interoperability
  gates when its public dependencies are released; lifecycle effects remain tested in their owning packages.
- **Actual Google:** separately authorized R17a installed-app/provider/service verification must cover configured
  audience, actual signing/package, initial sign-in, cancellation and same-account reauthentication before personal
  live readiness. Deterministic R11a–b acceptance permits dependency-ready R13 development, not a successful Google,
  deployed HTTPS or phone-readiness claim; release-signing/public proof journeys remain later public live gates.

**Stop boundary:** personal R11 is complete only when R11a–b and their declared synthetic/package gates are accepted;
R11c remains visibly deferred until its public proof gates pass. Default local startup remains unchanged throughout
R11. Account/configuration UI, initial access gating, foreground/edit/manual sync and one-time legacy import/activation
are R13; sign-out UI/credential-clearing journey stays public R13. Personal R11a–b is closed, deterministic-only;
next pickup is R13's finite plan and bounded implementation, not a new R11 slice.

### R12 — Feature and app reconciliation

**Finite plan closed: all three slices R12a–c and R12 package gates are accepted.**
[R12c evidence](archive/remaining-work/R12c-review.md) records final full JVM/default instrumentation, isolated backend/Room
HTTP, frozen assembly/lint/APK and independent source review. R09h authority and Core R10a are accepted dependencies;
no additional R12 slice is planned or inferred.

| Slice | Outcome / completion boundary | Dependencies | Non-goals |
|---|---|---|---|
| **R12a — real finite-edit and timer barriers (accepted)** | [Review](archive/remaining-work/R12a-review.md). One app-owned admission/drain boundary covers every feature mutation and survives screen disposal. Failed/cancelled accepted writes remain visible to drains; paused callbacks cannot optimistically accept edits. Short request-bound publication leases exclude new edits; serial terminal rest writes expose failures and preserve at-most-once claims. Explicit timer-impact decision composes with the real barrier before any future publisher. Prove state races and actual Compose wiring. | Existing R05 publication contract, R09 inert coordination and current feature/repository seams; no R10 dependency for inert barrier delivery. | No engine/publisher construction at startup, account activation, epoch observation, remote navigation reconstruction, new schema, timer process-death redesign or legacy-data choice. |
| **R12b — projection observation and feature refresh (accepted)** | [Review](archive/remaining-work/R12b-review.md). Narrow fenced materialized-epoch observation and selected routine/picker/history/active refresh at equal IDs/parent revisions; current custom-reference joins, accepted drafts, latest-load/scope guards, stable focus and dialog drafts. Pure action lists do not drain unrelated writes; configured-rest wheel settles without self-triggering. | R12a; existing Room publication epochs. Remains inert/local-scoped until app-facing account authority is delivered. | No account visibility grant, engine activation, app/pill/timer reconstruction, Google/account UI or timer redesign. |
| **R12c — scoped app and timer reconciliation / package closure (accepted)** | [Review](archive/remaining-work/R12c-review.md). Exact-live scoped runtime/root and lease-safe retirement, guarded ACTIVE push/pull/typed snapshot/tail/paired publication, fresh epoch observation, validated canonical timer-impact, at-most-once rest protection, app pill/nested navigation and captured consent; independent actual Compose/Room/HTTP and full package gates. | R12a/b, R09h and Core R10a accepted. One uninterrupted owner, real barriers and final precommit/process fences; no private fallback or stale callback authority. | Default local startup unchanged; no R11 identity/R13 UI/triggers, schema/framework/timer process-death redesign, cloud cleanup/import or live deployment. |

R12 completion includes all three outcomes and full package instrumentation/assembly/lint gates, scope privacy and
actual app/timer reconciliation. It does not close remaining R10b/R11/R13/live milestones or authorize live accounts.
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
reauthentication and recovery; approved one-time legacy preserve/import; core R10 before activation.

### Remaining R13 plan

**Finite personal plan: four slices, R13a–d.** R13a and [R13b](archive/remaining-work/R13b-review.md) are accepted;
[R13c](archive/remaining-work/R13c-review.md) is accepted, and R13d remains pending. **Owner stop after
R13c:** settle scoped closure/resources, without a successor launch or R13d specification/delegation/build/transfer.
R13d needs renewed continuation authority. Dependency order remains a → b → c → d. This is an
outcome/dependency plan, not a bounded specification or acceptance evidence. Concrete APIs,
source allowlists, schema versions, independent test assignments and exact slice gates are settled at each pickup under
[the workflow](coordinator-workflow.md#scope-and-planning). A safety discovery requiring another split returns to the owner;
there is no implied R13e or new major R task.

The policy authorities are [P16–P18](../shared-docs/docs/backend-contract/open-decisions.md#confirmed-product-decisions),
[create-only first-sign-in import](../shared-docs/docs/backend-contract/android-client-protocol.md#first-sign-in-import-and-cache-transitions)
and [activation/import design](sync/integration-design.md#startup-and-activation). Reuse accepted R09a–h, Core R10a,
personal R11a–b and R12a–c, plus the existing capture/codecs, shared run/credential owners, prepared push/bootstrap,
complete publication and real edit/timer barriers. Narrow integration changes to those seams are permitted by R13;
rebuilding their authority/engine or reopening the accepted packages is not a planned outcome.

| Slice | Outcome and non-goals | Dependencies / acceptance boundary |
|---|---|---|
| **R13a — durable legacy graph and import capture (accepted)** | Delivered the inert persistence boundary for one-time P18 migration: freeze genuinely local-only active/finished workouts, saved/draft routines and custom exercises with all children/relationships; retain exact source/error evidence; bind configured origin, first service-confirmed target and durable progress. Atomically associate eligible roots privately and capture complete create-only imports in dependency order with stable mutation identities. Resume the same migration after uncertain return/reopen without duplicate capture. Invalid/foreign/unknown-provenance data is preserved, never repaired, remapped or adopted. No network delivery, app activation, account UI or default-startup change. | Accepted storage/model/authority foundations. Real populated Room preservation/rollback, source and target fencing, checked counters and duplicate prevention; data-preserving migration/KSP/export plus every-supported-start and copied v1 asset coverage for any schema change. Stops at an explicit internal private capture/recovery boundary, not an app handle. |
| **R13b — private import delivery and complete activation/recovery (accepted)** | Delivered opt-in composition of a with existing foreground grant/private preparation and prepared engine. Settle the exact import sequence before complete snapshot/bootstrap, protected publication and vault/Room activation recovery. Preserve partial remote acceptance, frozen requests and original source/errors through cancellation, process restart and repeated same-account sign-in. Include explicit admission/drain and active-import replacement-warning/consent seams for later UI use. Collision/rejection/invalid source blocks activation; no collision overwrite, new-ID replay, destructive clean start or foreign-account reassignment. No default startup/account screens or automatic triggers. | R13a plus accepted R09/R10a/R11/R12. Actual Room/vault and isolated black-box HTTP import-to-activation gates, including partial acceptance/lost response/reopen, same-account reauthentication, collision retention, failed snapshot and Room/vault completion gap. Complete matching evidence alone permits an explicit scoped account runtime; empty installs use the same complete-activation policy without a fabricated import. |
| **R13c — mandatory account gate and startup/account UI (accepted)** | Replaced the default local editing root with configuration/sign-in/private preparation/recovery presentation and the existing exact-handle account root. Wire Google/service sign-in, b activation/progress, same-account reauthentication and required import warning/consent. Expose safe pending/last-success/error state and retained import problems without private data fallback. Matching established accounts reopen/edit offline without online login every launch; unavailable/mismatched binding stays private. Present unavailable configuration without discovering or inventing personal values; credentials never move to a changed origin. No foreground/edit automation, destructive rejection recovery, sign-out/switching or general chooser UI. | R13b accepted before enabling affected startup activation. State/Compose/actual app tests for new/unconfigured/legacy installs, privacy during preparation, successful import, blocked import, cancellation, offline restart/edit and reauthentication; real custom-editor/edit/timer drain integration. Cross-feature startup acceptance includes full current-API default instrumentation and relevant assembly/lint/APK/backup/security checks, with affected API24 startup/import/recovery coverage explicitly specified at pickup. |
| **R13d — foreground/edit/manual sync and confirmed recovery; personal package closure** | Add serialized, bounded foreground and committed-edit triggers plus manual retry using the exact current handle and existing ACTIVE reconciliation. Coalesce redundant triggers without losing newer committed intent or spinning around protected publication, backoff or retained errors. Keep offline editing on recoverable network/credential stops. Provide deliberate retained-stage restart and confirmed ordinary-account rejection recovery: settle uncertain frozen work unchanged before discarding only the captured confirmed remaining intent and reloading a complete snapshot. Legacy import evidence is not an ordinary discard target; rejected imports remain private/blocked. Close the whole personal R13 package, not live readiness. No background scheduler, timer redesign, account switching, lifecycle/export/deletion or deployment. | R13c plus accepted ACTIVE reconciliation/barriers. Virtual-time trigger/lifecycle/disposal/concurrency and confirmation-staleness tests; real Room/Compose recovery, rest-write/new-edit protection and failure preservation; actual black-box HTTP foreground/offline-edit/reconnect/recovery journeys. Full offline JVM, fresh unfiltered current-API default instrumentation, assembly/lint/APK/backup and affected API24 gates close the personal package. R17a still owns actual Google/signing/trusted deployed HTTPS/phone/account evidence. |

**Cross-slice safety:** source capture must gate new local admissions and drain accepted finite work; explicit-save custom
editor drafts require Save/Discard/Cancel, never hidden autosave. Preserve IDs, values, optional custom metadata, nullable
rest and saved/draft routine snapshots without normalization to satisfy an import. Credentials stay in the vault, not
migration/outbox/source records or reports. All transaction and late-result checks retain configured origin, service UUID,
operation/generation and process fences; private cache/intent is never recategorized as local-only. Capture, durable
association and retry progress must survive a committed-but-cancelled return without a second migration.

**Import completion is stricter than an empty queue:** each captured import must have definitive accepted/original-accepted
settlement, with exact recoverable source/progress evidence retained. A collision, duplicate rejection, missing/corrupt
record or uncertain result cannot be called settled success. An accepted active import may replace the cloud active
workout under the existing sole-active rule; warnings/consent precede that effect, and cancellation never promises to
undo an already accepted remote prefix. Activation additionally requires complete cloud snapshot publication and matching
durable Room/vault completion. No incomplete baseline, progress DTO or Google success grants account visibility.

**Testing placement:** every slice has independent Worker/QA review and focused invariant tests plus full offline JVM at
code acceptance. Schema gates belong to the slice changing storage; actual import HTTP gates belong to b, default-startup
full instrumentation/security gates to c, and combined personal-package journeys/regressions to d. Reuse prerequisite
fixtures and coverage instead of repeating whole codec/engine matrices. Unavailable required gates are blockers, not
implicit deferrals. Synthetic test resources must be explicitly owned/isolated; earlier resource/evidence receipts are
not new grants. The legacy auth replay limitation and unexplained R12c activation-stop limitation remain visible; a
recurrence is investigated, not masked by retries or expiry changes.

**Major-task completion boundary:** a–d are independently accepted and the complete personal package proves mandatory
initial sign-in/import-before-activation, retained preparation/recovery errors, matching offline restart/editing,
same-account reauthentication, foreground/edit/manual synchronization and safely confirmed ordinary rejection recovery.
Default workout access has no signed-out/private fallback; one-time legacy import is never repeated or reassigned.
Completion enables dependency-ready R17a planning, not a claim of live Google/phone/service readiness or unattended use.

**Deferred public:** signed-out workflows, sign-out/account-switch controls and general import/replace confirmations with
their choice/isolation gates; R11c, expanded R10b and lifecycle/export/deletion/public release gates remain deferred.
Offline sign-out must never claim server revocation. R16 background scheduling remains after the usable personal milestone.
No live migration, private-config discovery/change, signing/OAuth change, push, deployment or personal account/device
operation is authorized by this plan. R13a–b are accepted at their explicit private boundaries; R13c's mandatory startup/UI boundary is accepted.
The owner stop after R13c overrides automatic continuation into R13d.

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
