# Coordinator pickup

> **Current snapshot only. Replace state; do not append session history.**
> Follow [minimal successor prompts](coordinator-workflow.md#minimal-successor-prompts--owner-rule) and
> [progressive reading](README.md#progressive-reading-and-context-discipline).

## Current boundary and next authority

**R09g — complete inert same-account recovery is accepted.**
[Review](archive/remaining-work/R09g-review.md) owns executed evidence;
[specification](archive/remaining-work/R09g-task.md) records its contract. No implementation specification is open.
Production remains signed-out/local-only; no new coordinator/engine/recovered app handle is constructed at startup.

[Implemented recovery](sync/implementation.md#inert-retained-account-recovery-and-superseded-evidence) covers retained
signed-out/missing-vault recovery, explicit fresh-grant supersession of unsuccessful/legacy-malformed uncertainty and
interrupted transitions. Room12 journal/device storage is reused. Archive-free vault records retain v1 bytes; archive-bearing
v2 atomically retains/fences exact uncertain evidence with the new grant. Readable strictly older same-origin/service-UUID
records are evidence only, never credentials/data authority; resume needs a fresh grant at the exact existing reservation.
No retirement, cleanup, old-token rekey, expiry rounding, orphan credential adoption or server-revocation claim.
Cached domain/children/relationships, canonical/cache/intent/frozen bytes, checkpoints/stages and foreign scopes remain intact.

**Owner-selected next package: R12 — feature/app reconciliation, beginning with R12a real edit/timer barriers.**
**Pickup is prepared but paused: do not launch a successor, specify, delegate, edit implementation or build until the owner
resumes and verified fresh-coordinator ownership is released.** The first coordinator taking this new major package must
settle its finite whole-R12 outcome/dependency plan in the roadmap before implementation; the existing a/b/c outline is
not a detailed assignment. Prepare the concrete R12a specification only after release, following the
[major-task slicing rule](coordinator-workflow.md#major-task-slicing--owner-rule).

R09h app-facing activation/offline recovery remains BLOCKED. The [finite R09 plan](remaining-work-roadmap.md#remaining-r09-plan)
stays R09g (accepted), R09h, then reduced personal closure. R12a real finite-edit/timer barriers and core R10 actual
two-Room-store engine/HTTP convergence must precede production activation. Selecting R12 does not waive core R10 or
bring dependency-blocked R12 outcomes forward. Escalate sequencing/blockers instead of bypassing gates or inventing R09
slices. R11 Google sign-in and R13 account UI/foreground triggers remain separate.

[P16/P17](../shared-docs/docs/backend-contract/open-decisions.md#confirmed-product-decisions) own mandatory initial sign-in,
established-account offline use and evidence-only same-account supersession. General switching/sign-out/import-replace UI
remains deferred public scope. **Legacy local-only data disposition is unresolved:** no import, deletion or reassignment;
preparation refuses those roots unchanged, including roots introduced after reservation. Obtain owner direction before
activation work requiring that choice. Unreadable/foreign/unknown-provenance and incompatible same/newer vault bindings
remain preserved/private. Historical malformed accepted replay is not repaired by supersession.

## Pickup and execution

Supplied unchanged AGENTS → README policy → this handoff → roadmap status/dependencies → workflow authority/transfer rules.
Verify BOTH live trees/indexes/all untracked/HEADs, actual roles/models and resource ownership. Readiness is bounded scope/
authority/dependencies/gates, not a full audit. Do not specify, edit, delegate or build before verified ownership release.
Read the full [workflow](coordinator-workflow.md) before delegation and relevant contracts/code before each concrete decision.
Escalate blockers/questions; never waive a gate, silently substitute a model, choose another task or change the finite plan.

Coordinator must be fresh native Pi `openai-codex/gpt-6.1-sol`/high. Worker/QA must be fresh native
`meta/muse-spark-1.3-contributor`/high, with disjoint production/test access. Rediscover task-owned panes; completed
conversations do not transfer. Reports are fully asynchronous to the live `gym-coordinator`, without `--wait`, agent wait
or acknowledgment polling. EVERY Gradle needs an explicit serialized slot; lint freezes both roles' sources.
Preserve accepted history/unrelated changes. No pushes, deployment, private configuration, real Google/account/device
or personal-data actions without separate permission.

## Foundations and verification limits

- [Room authority](sync/implementation.md#durable-private-room-account-authority),
  [prepared coordination](sync/implementation.md#prepared-vaultroom-coordination-and-recovery),
  [registered private engine](sync/implementation.md#private-prepared-session-engine-and-bootstrap) and
  [completion/promotion](sync/implementation.md#private-completed-preparation-and-vault-promotion) remain inert foundations.
  Stored phases/results never authorize app visibility; real barriers must replace controlled test leases before live use.
- R09e settled ACTIVE replacement and R09f exact replay retain their narrower contracts. R09g adds fresh-grant retained-account
  recovery, not broad revocation, cleanup or another service UUID's adoption. All current refresh/promotion consumers
  preserve archives; future separately authorized lifecycle cleanup must handle ALL retained credential material.
- Latest [R09g evidence](archive/remaining-work/R09g-review.md): fresh full offline JVM, full unfiltered API37/Android17 16KB
  instrumentation, scoped real Room/Keystore/reopen/engine regression, unsuppressed lint/assembly/APK/preservation.
  NOT two-store real-service convergence, live edit/timer admission, API24, hosted CI, Google/OEM/cloud transfer or phone
  deployment proof. Those remain their capability/milestone gates.
- [Legacy replay limitation](sync/implementation.md#legacy-auth-replay-limitation) remains unrepaired. Expiry/auth/offline
  failure never proves account deletion. Unknown/corrupt authority/vault state cannot authorize automatic repair or cleanup.
- Secure laptop endpoint/connectivity, OAuth audience/signing and authorized phone/account/test data remain external inputs.
  Backend work is isolated operational build/start plus black-box HTTP only, never backend source/private configuration/suite.

## Resources

The test-owned emulator is stopped and isolated Android/AVD homes removed. No Gradle/device/backend ownership transfers;
default ADB/Gradle resources remain untouched. Rediscover live roles/processes before starting fresh sessions or granting
slots. Accepted commit receipts belong in the short successor prompt, not this current-state document.
