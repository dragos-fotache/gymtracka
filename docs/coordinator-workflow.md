# Coordinator workflow

> Follow [the documentation rules](README.md#documentation-rules): **replace current state; append only to historical evidence.**
> This is durable process guidance, not authorization for a task or an agent launch.

## Scope and planning

- Confirm the owner's authorization for the specific planning/implementation slice. Approval of a design or earlier
  task does not authorize the next slice, app activation, commits, pushes, deployment, or personal configuration changes.
- Inspect live working trees, including untracked files, before assigning ownership. Preserve user changes. Rediscover
  agents/processes before reuse; historical pane IDs, models, HEADs, and cleanup reports are not live facts.
- Coordinator owns design, integration, and independent final review. Resolve internal choices with the simplest
  compatible approach; ask the owner about unresolved product, destructive-action, configuration, or scope decisions.
  Do not reopen finalized shared-contract decisions incidentally.
- Create one normative specification under `docs/remaining-work/` per approved slice. Include goal/non-goals, current
  inventory, dependencies, concrete API/harness facts, permitted files, transaction/state boundaries, crash/cancellation/
  race cases, migration/security consequences, acceptance tests, and exact stop condition.
- Split large packages into coherent reviewable slices. Prepare detailed specifications on pickup, not speculative APIs
  for the whole roadmap. No dependent implementation before its necessary design/interface gate.

## Independent contributor roles

The established roles are `gym-worker` (production) and `gym-qa` (tests), using Pi Muse Spark 1.3 Contributor at high
reasoning (`meta/muse-spark-1.3-contributor`). Verify live availability/model before reuse; do not silently substitute.
When explicitly using Herdr, read its skill and verify the required environment before controlling agents.

- Give both roles the same normative behavior/API specification and disjoint read/write allowlists.
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
3. Run focused checks, then the appropriate broader gates in [testing strategy](testing-strategy.md). Inspect executed
   XML names/counts/failures/errors/skips. Compilation, filtered tests, cached tasks, and reported external results prove
   different things; record unavailable gates as unverified, never passed.
4. Check scope, secrets/backup/test-helper exclusion when relevant, asset/schema preservation, and both repository diffs.
   A schema change needs a data-preserving migration, KSP/export, and every-supported-start plus bundled-asset coverage.
5. Write a concise acceptance review: delivered boundary, important findings/residual risks, actual commands/environment/
   results, unverified gates, and stop condition. Preserve failed-gate evidence where needed to explain an unresolved
   issue; do not copy the debugging transcript into active docs.
6. Extract enduring implementation facts into architecture, approved future decisions into design, and test procedures
   into testing strategy. Update roadmap status and replace the handoff snapshot. Archive the completed spec/review and
   remove obsolete active instructions. Keep outstanding subparts visible even when their parent has accepted slices.
7. Stop at the authorized boundary. Only explicit owner direction starts another slice or permits delivery operations.

## External boundaries

For Android tasks, `/home/dragos/IdeaProjects/gt-backend` is an operational build/start input and black-box HTTP service,
not a source-review, implementation, or Maven-suite assignment. Use the isolated launcher described in testing strategy.
Do not read/copy/edit `/home/dragos/.config/gymtracka-backend` secrets or use personal databases as fixtures. Compatibility
problems require separate ownership; do not silently widen Android scope. Live Google/device/account actions and laptop
configuration require explicit permission. No credential/configuration contents belong in docs or reports.
