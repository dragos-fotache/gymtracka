# Coordinator pickup

> **Current snapshot only. Replace state; do not append session history.**
> **Short prompt, durable docs:** use the [minimal successor-prompt rule](coordinator-workflow.md#minimal-successor-prompts--owner-rule).
> Follow [progressive reading](README.md#progressive-reading-and-context-discipline).

## Current boundary and next authority

**R09f — inert exact-pending-refresh reconciliation is accepted.**
[Review](archive/remaining-work/R09f-review.md) owns scoped evidence; [spec](archive/remaining-work/R09f-task.md) records its
contract. No implementation specification remains open. Production is still signed-out/local-only. One exact established
ACTIVE pending replay through shared refresh machinery → original full-grant/no-pending CAS → advisory settled old ACTIVE
observation is NOT reauthentication, activation, projection/promotion or all same-account recovery. R09e replacement must
separately freshly admit that settled pair. Unsuccessful/legacy-malformed uncertain attempts remain retained unchanged.

**Next: R09g — complete same-account recovery**, after verified fresh-coordinator transfer. The owner approved the
[two-slice remaining R09 plan](remaining-work-roadmap.md#remaining-r09-plan): R09g recovery, then R09h app-facing activation/
offline recovery after its gates, then reduced personal R09 closure. R09g consolidates signed-out/missing-vault recovery,
unsuccessful/legacy-malformed pending handling and interrupted transitions; no automatic sub-slices or R09i. The
[major-task slicing rule](coordinator-workflow.md#major-task-slicing--owner-rule) assigns the first coordinator taking a NEW
major task its finite whole-task breakdown, not each sub-slice successor. Additional splits need a concrete reason brought
to the user before changing the plan.

R09g pickup/design is authorized, but **failed/legacy-malformed uncertain predecessor disposition remains unresolved**.
Escalate that decision before implementing affected recovery; this plan authorizes no retirement, grant cleanup, new
uncertain key, expiry rounding, rekey or orphan adoption. Already-TRANSITIONING pending gaps also remain retained until
safe disposition is settled. No other service UUID may adopt retained data. Reuse delivered Room12, vault CAS, shared
owner/refresh/lease machinery and R09e/f boundaries rather than recreating them. Stop R09g at inert same-account preparation
eligibility, not application activation. R09h cannot bypass R12a/core R10 or duplicate R11 Google/R13 account UI work.

[P16](../shared-docs/docs/backend-contract/open-decisions.md#confirmed-product-decisions) owns mandatory initial sign-in and
established-account offline use/editing/restart without online login every launch. General signed-out editing, switching/
sign-out and import-replace UI remain deferred public scope. **Legacy local-only disposition is unresolved:** no import,
delete or reassignment is authorized; preparation and reauthentication refuse those roots unchanged, including roots
appearing after reservation. Obtain owner direction before work requiring their activation. R12a real finite-edit/timer
barriers and core R10 convergence still precede production activation. Controlled test barriers never substitute for a
no-op in a live editing app. No push/deployment/private configuration/live Google/account/device action.

## Pickup and execution

Supplied unchanged AGENTS → README policy → this handoff → roadmap status/dependencies → workflow authority/transfer rules;
inspect BOTH live trees/indexes/all untracked/HEADs, actual roles/models and resource ownership. Readiness is bounded scope/
authority/dependencies/gates, not a full design audit. Do not specify, edit, delegate or build until verified release.
Read the full workflow before delegation. After release, settle R09g's concrete specification under the approved finite
plan and escalate unresolved disposition/blockers to the user before affected implementation. Do not split again, waive
gates or skip to another roadmap branch around a blocker.

Coordinator must be EXACT fresh native Pi `openai-codex/gpt-6.1-sol`/high. Worker/QA must be FRESH native
`meta/muse-spark-1.3-contributor`/high sessions in rediscovered task-owned panes. Reuse suitable contributor panels by
starting fresh sessions; completed conversations do not transfer. Preserve production/test source independence;
coordinator owns integration/final review. Reports are asynchronous (`herdr agent prompt gym-coordinator ...`), never
--wait, agent wait or acknowledgment polling. EVERY Gradle requires an explicit serialized owner slot. Lint analyzes test
sources too: freeze both roles' sources during its execution. Preserve accepted history/subsequent work; never amend/
rewrite, silently substitute a model, waive a gate, select legacy disposition, choose another task or widen scope.

## Foundations and unresolved obligations

- [Room authority](sync/implementation.md#durable-private-room-account-authority): strict coherent observations, exact
  reservation/private fencing and registered completion writer, checked revision/generation, retained provenance. Room12;
  entity changes require preserving migration/KSP/export/all-supported/copied-v1 gates, never destructive fallback/asset edits.
- [Prepared coordination](sync/implementation.md#prepared-vaultroom-coordination-and-recovery): exact empty/tombstone CAS,
  reservation-only resume; initial methods never replace occupied/pending grants. NeedsGrant reconstructs nothing.
- [Private engine](sync/implementation.md#private-prepared-session-engine-and-bootstrap): referential registered permits,
  durable epoch read INSIDE shared owner, exact Room/refresh/HTTP fences, canonical-only bootstrap. Public prepared-run
  publication refuses; retained/corrupt stages never authorize automatic cleanup.
- [Completion](sync/implementation.md#private-completed-preparation-and-vault-promotion): actual terminal tail, coherent
  complete projection/journal/device in ONE Room transaction, exact phase-only vault promotion with alternating rereads.
  Post-tail rotation/pending bytes preserved; lease-close/uncertain return is not rollback. Gap recovery is narrower than
  established-account recovery after editing; stored phases/results are not visibility.
- [Settled reauthentication](sync/implementation.md#inert-settled-occupied-credential-reauthentication): shared DB owner
  then required shared credential mutex; exact ACTIVE source/no pending, two checked Room revisions/one new generation,
  exact supplied-grant CAS and fresh Room-after-vault-before-CAS check. No domain/cache/intent writes. Only adjacent settled
  predecessor or exact installed target resumes; missing/foreign/orphan evidence stays private.
- [Exact pending replay](sync/implementation.md#inert-exact-pending-refresh-reconciliation): same shared refresh resources,
  required old ACTIVE Room authorization, ONE retained operation/key/body/token transmission, original full-grant CAS and
  exact revision+1/full pair rereads. No clock/UUID/fresh rotation or Room writer. Scoped lease/error rereads are fenced;
  already-started writes may commit, never compensate. Advisory settled evidence is not app authorization.
- [Refresh/recovery](sync/implementation.md#encrypted-vault-and-refresh): expired access is not a deletion clock; primitive
  default/unbound lease is not app authorization. Live integration must coordinate existing handles/edit drains/ownership.
- Latest [R09f evidence](archive/remaining-work/R09f-review.md): fresh full offline JVM, scoped session-package actual
  Room/Keystore/reopen/retained-intent/prepared-engine regressions, unsuppressed frozen-source lint/assembly/APK/preservation.
  NOT fresh full instrumentation or two-store real-service engine convergence. Those remain R09/R10 gates BEFORE
  activation; API24/hosted CI/Google/OEM/phone journeys remain unverified milestone gates.
- [Legacy accepted-auth replay](sync/implementation.md#legacy-auth-replay-limitation) remains unresolved. Never round/
  rewrite/rekey uncertain grants or infer broad revocation/credential/cache cleanup from a malformed response.
- Approved secure laptop endpoint/connectivity, OAuth audience/signing and authorized phone/account/test data remain external
  inputs. Any declared backend gate is isolated operational build/start plus black-box HTTP only, not backend source,
  private configuration/personal fixtures/suite or live Google/account work.

## Resources

The test-owned emulator is stopped and isolated Android/AVD homes removed. No Gradle/device/backend ownership transfers;
default ADB/Gradle resources and unrelated work remain untouched. Rediscover contributor panels/processes and settle old
conversations before fresh native sessions; no historical pane/model ownership is assumed. Accepted local commit references
belong in the short successor prompt, not this current-state document.
