# Coordinator workflow

> Follow [the documentation rules](README.md#documentation-rules): **replace current state; append only to historical evidence.**
> This records the owner's standing slice-commit and coordinator rotation/continuation authorization below, not blanket
> permission for pushes, deployment, live actions or changes outside the approved roadmap scope.

## Minimal successor prompts — owner rule

**Keep the next coordinator's initial prompt short: orient, identify the next slice, report readiness, wait for release.**
Durable docs carry project state, authority, dependencies, safeguards and execution rules. Do not paste summaries of
architecture/contracts, capability inventories, contributor instructions, test plans or the workflow into the prompt.
Use the [short template](#successor-prompt-template), with the exact slice and live outgoing report target; an optional
single-line accepted commit/review reference is enough for a receipt. Put missing durable facts in their owning document,
not a growing handoff packet. The successor discovers live state and follows the progressive reading route itself.
Send ownership release separately after readiness acknowledgment. Prompt brevity does not waive any existing gate,
authority check, contributor independence or transfer safeguard, and never overrides an explicit owner stop.

## Scope and planning

- Follow [progressive reading](README.md#progressive-reading-and-context-discipline). Pickup readiness verifies authority,
  bounded scope/dependencies/gates and live repository/role/resource state; it is not a completed design audit. After
  release, read the concrete authoritative sections/code needed to settle the specification, expanding for dependencies
  and safety questions rather than traversing the whole documentation/contract graph.
- Confirm that the selected bounded planning/implementation slice is covered by explicit owner direction or the
  [standing next-slice authorization](#coordinator-rotation-and-automatic-continuation). Acceptance evidence alone is not
  authorization. App activation still requires its capability/acceptance gates. The accepted slice's scoped local commit
  is required by the standing rule; pushes, deployment, personal configuration and live actions need separate permission.
- Return to the owner for every unresolved blocker/question: missing inputs, unavailable required gates/agents/tools,
  unmet dependencies, ambiguous product/destructive/configuration decisions or scope changes. Do not silently substitute
  a model, weaken a gate, skip a dependency, choose another task or infer permission. Resolve routine implementation/
  review defects within the approved specification; when a blocker cannot be resolved there, report it and ask the user.
  Automatic continuation never removes this escalation obligation.
- Inspect live working trees, including untracked files, before assigning ownership. Preserve user changes. Rediscover
  agents/processes before reuse; historical pane IDs, models, HEADs, and cleanup reports are not live facts.
- Coordinator owns design, integration, and independent final review. Resolve internal choices with the simplest
  compatible approach; ask the owner about unresolved product, destructive-action, configuration, or scope decisions.
  Do not reopen finalized shared-contract decisions incidentally.
- Create one normative specification under `docs/remaining-work/` per approved slice. Include goal/non-goals, current
  inventory, dependencies, concrete API/harness facts, permitted files, transaction/state boundaries, crash/cancellation/
  race cases, migration/security consequences, acceptance tests, and exact stop condition. Before delegation, apply the
  [proportional testing policy](testing-strategy.md#proportional-coverage-and-verification-gates): declare risk, distinct
  new-test obligations, reused existing coverage/harnesses, iteration checks and acceptance gates, with a specific
  milestone for deferred broad gates. Size QA's assignment accordingly; do not default every slice to exhaustive tests
  or full-stack verification. Contributor independence and safety/milestone requirements remain unchanged.
- Split large packages into coherent reviewable slices. Prepare detailed specifications on pickup, not speculative APIs
  for the whole roadmap. No dependent implementation before its necessary design/interface gate.

## Independent contributor roles

The established roles are `gym-worker` (production) and `gym-qa` (tests), using Pi Muse Spark 1.3 Contributor at high
reasoning (`meta/muse-spark-1.3-contributor`). Verify live availability/model before reuse; do not silently substitute.
The owner has explicitly authorized future coordinators to use Herdr for this project's agent coordination without
asking again. This standing permission covers inspecting live agents and coordinating the authorized slice, not new task
scope, unrelated panes, configuration changes or delivery operations. Read the Herdr skill and verify the required
environment before controlling agents.

- Give both roles the same normative behavior/API specification, task-relevant standards/contract references and
  disjoint read/write allowlists. Do not add blanket whole-system/full-contract reading to every contributor packet.
  Reuse supplied context; coordinator-provided facts point to authoritative sources without granting cross-role access.
- Worker must not inspect tests, fixtures, reports, or QA work. QA must not inspect production declarations,
  implementations, diffs, decompiled code, or Worker work. LSP navigation obeys the same boundaries.
- Coordinator supplies missing API/harness facts as contracts rather than granting cross-role source access. Coordinator
  alone reviews both layers and owns shared integration/build/harness changes unless explicitly assigned.
- Use one fresh native session per role per authorized slice. Keep review corrections in those sessions until accepted;
  do not fragment a subtask or carry a completed contributor conversation into the next assignment.
- Reports are fully asynchronous/fire-and-forget. With Herdr use `herdr agent prompt gym-coordinator ...`, without
  `--wait` or `agent wait`. Coordinator must not wait/poll Worker/QA output; contributors report completion/blockers.
- Shared-output Gradle runs are serialized through explicit coordinator-granted slots. No concurrent Worker/QA/coordinator
  builds. Keep user focus and unrelated panes/processes intact; do not change global configuration or personal skills.
- Prefer restricted Kotlin mcpls navigation. If unavailable/indexing, use bounded attempts then allowed-file reads and
  compilation; unavailable diagnostics are not a clean-diagnostics claim.

## Review and acceptance

1. Review the full production/test diff, including new files, against the spec and invariants. Contributor completion
   and test counts alone are not acceptance.
2. Require independent expected results and observable oracles. Do not weaken assertions to accommodate implementation
   defects or derive purportedly independent expected wire bytes from the production encoder.
3. Run the specification's risk-selected gates using [the execution cadence](testing-strategy.md#execution-cadence):
   focused checks while iterating, affected tests plus full offline JVM at code-slice acceptance, and broader gates at
   package/integration milestones or earlier when risk requires them. Ensure the intended test tasks execute without
   routinely forcing all build dependencies to rerun. Inspect executed XML names/counts/failures/errors/skips.
   Compilation, filtered tests, cached tasks and reported external results prove different things; record unavailable
   required gates as unverified, never passed. A scoped pass does not imply a fresh full regression.
4. Check scope, secrets/backup/test-helper exclusion when relevant, asset/schema preservation, and both repository diffs.
   A schema change needs a data-preserving migration, KSP/export, and every-supported-start plus bundled-asset coverage.
5. Write a concise acceptance review: delivered boundary, important findings/residual risks, actual commands/environment/
   results, unverified required gates, deliberately deferred broad gates with their milestone, and stop condition.
   Preserve failed-gate evidence where needed to explain an unresolved issue; do not copy the debugging transcript
   into active docs.
6. Extract enduring implementation facts into architecture, approved future decisions into design, and test procedures
   into testing strategy. Update roadmap status and replace the handoff snapshot. Archive the completed spec/review and
   remove obsolete active instructions. Keep outstanding subparts visible even when their parent has accepted slices.
7. Stop implementation at the slice boundary. Once accepted, commit the slice's changes and transfer the next eligible
   slice to a fresh coordinator using the protocol below; do not continue it in the completed coordinator session.
   Pushes, deployment and live actions still require separate explicit owner direction.

## Coordinator rotation and automatic continuation

**Owner-approved standing rule:** after finishing and accepting an implementation slice, the coordinator starts a new
coordinator pane with a fresh native session, gives it the prompt to start the next bounded roadmap slice, and closes
its own pane after a verified ownership handoff. **Before that handoff, commit the accepted slice's changes locally.**
The successor may plan and implement that next slice automatically; asking for next-slice authorization again is
unnecessary within this standing scope. Repeat this rotation at every
accepted slice, including a sub-slice of a larger package. Do not carry a completed coordinator conversation forward.

This permission applies to the next dependency-ready slice in the current handoff/approved roadmap and its declared
milestone. A bounded specification and independent Worker/QA assignments are still required before implementation.
Do not automatically pull forward deferred milestones, activate a feature before its gates, start multiple successors,
skip to an unrelated branch or perform pushes, deployment, private configuration, real Google/account actions or
personal-device journeys. The required local commit is limited to the accepted slice; it is not permission to commit
unrelated work or push anything. Bring any blocker or unresolved question back to the user. If there is no eligible next
slice, required authority is missing, or the current slice is partial/failing, ask the user rather than claiming completion or
launching around the problem. Documentation-only maintenance is not itself an implementation-slice completion.

### Safe handoff sequence

1. **Close the accepted slice durably first.** Finish independent review and required gates; write acceptance evidence,
   extract enduring facts, archive the specification/review, and replace roadmap/handoff state. Identify the exact next
   slice, dependencies, boundaries and still-deferred gates. Inspect both working trees and preserve unrelated
   uncommitted/untracked work. Release Gradle slots and settle owned builds/work.
2. **Commit the accepted slice.** Include its reviewed production/tests and owning documentation/archived evidence.
   Stage only the slice-owned paths/hunks, inspect the complete staged diff (including new files), run staged whitespace
   checks, and create a meaningful local commit. Do not use blanket `git add .`/`-A`, include unrelated changes, reset the
   user's index/worktree, amend/rewrite history or bypass failing hooks. If pre-existing staged or overlapping user work
   prevents a clearly scoped commit, ask the user rather than guessing ownership. When authorized slice changes span
   both repositories, commit the scoped shared-docs changes first and then the Android changes, including any tracked
   shared reference only if applicable. Verify commit contents and both post-commit working trees; unrelated work may
   remain dirty, but accepted slice changes must not be left behind accidentally. A failed/uncertain commit is a blocker:
   retain the outgoing coordinator and return to the user, without retrying blindly, pushing or launching the next slice.
3. **Discover, do not assume.** Read the Herdr skill and verify `HERDR_ENV=1`. Discover the outgoing caller's actual pane
   ID, repository cwd and live agents; verify its coordinator model/reasoning and the successor's availability. Never use
   archived pane/session IDs. If Herdr, the model or startup is unavailable/blocked, return to the user and keep the
   outgoing pane alive; do not silently substitute a model or close the only coordinator.
4. **Launch a fresh sibling coordinator.** Create one new pane in the same tab/repository cwd, choosing usable geometry
   and `--no-focus`. Do not reuse Worker/QA or an unrelated pane, create a new worktree, or change global configuration.
   Start Pi with the verified coordinator model/reasoning and a fresh session (no `--continue`, `--resume`, old
   `--session` or old session ID). Use a unique temporary live name while the outgoing `gym-coordinator` still exists.
5. **Send the minimal next-slice prompt asynchronously.** Use the [short template](#successor-prompt-template), not a
   duplicated handoff packet. Identify the exact next slice, documentation entry point and live outgoing readiness target;
   optionally add one accepted commit/review reference line. All standing rules and durable scope facts stay in docs.
   The successor verifies minimal pickup facts and reports readiness via fire-and-forget
   `herdr agent prompt <outgoing-name> ...`; do not require complete design/contract/code ingestion for this acknowledgment.
   It must not specify, edit, delegate or build until ownership is released. Task-relevant design/source reading follows
   release, before specification/delegation. Do not use `--wait`, `agent wait`, or poll for readiness; use the acknowledgment
   message.
6. **Transfer the live role only after acknowledgment.** Verify that the acknowledgment identifies the successor and
   next slice. Rename the outgoing agent to a unique retiring name, rename the successor to `gym-coordinator`, and send
   the asynchronous release/start prompt. This keeps Worker/QA reports routed to the new owner without duplicate names
   or simultaneous coordinators. If startup/prompt/rename/acknowledgment fails or its outcome is uncertain, inspect only
   the owned handoff resources, report the blocker to the user, and do not blindly retry launches or close the outgoing
   pane. Keep authority unambiguous until the problem is resolved.
7. **Close only your own outgoing pane.** Give the user the completion/handoff summary before shutdown; finish any
   bookkeeping and release resources first. Use the captured outgoing pane ID with `herdr pane close <outgoing-pane-id>`
   as the final control operation. This user authorization explicitly covers closing the current coordinator pane even
   if it predates the session. Never close the successor, Worker/QA, another user pane, a tab/workspace/session or the
   Herdr server. Do not issue further edits/builds/control commands after self-closure.

### Successor prompt template

Use this brief prompt; do not expand it into an implementation packet. Substitute only current live facts:

```text
You are the next GymTracka coordinator for <next bounded slice>.
Orient yourself using supplied AGENTS.md and docs/README.md, then the current handoff and roadmap.
Verify live state and report pickup readiness asynchronously to <outgoing live agent name>.
Do not specify, edit, delegate or build until ownership is released.
After release, execute the slice under docs/coordinator-workflow.md; escalate blockers/questions to the user.
```

If useful, append only `Accepted: <commit hash(es)>; <review path>.` No copied inventories or verification claims.
After verified acknowledgment and role transfer, send a separate short release message:

```text
Ownership of <slice> is released to you as gym-coordinator. Begin under docs/coordinator-workflow.md.
```

This release is sent only when the current slice/start authority permits it; the template itself grants no authority.

## External boundaries

For Android tasks, `/home/dragos/IdeaProjects/gt-backend` is an operational build/start input and black-box HTTP service,
not a source-review, implementation, or Maven-suite assignment. Use the isolated launcher described in testing strategy.
Do not read/copy/edit `/home/dragos/.config/gymtracka-backend` secrets or use personal databases as fixtures. Compatibility
problems require separate ownership; do not silently widen Android scope. Live Google/device/account actions and laptop
configuration require explicit permission. No credential/configuration contents belong in docs or reports.
