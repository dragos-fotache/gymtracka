> **Historical record, not current instructions.** See the [archive boundary](../README.md) and [current pickup](../../coordinator-handoff.md).

# R01 — Coordinator planning verification

Date: 2026-10-03.
Status: **R01 planning/design approved by the owner**. No implementation accepted or authorized.
The owner replied “Ok I approve. R02 is next, right?”; this records R01 acceptance and confirms the sequence,
not authorization to launch R02 code work.

## Reviewed deliverables

- [Audit, gap/priorities matrix and external-input register](R01-task.md).
- [Integration design and invariant/test matrix](R01-design.md).
- [Proposed first bounded R02a shared specification](R02a-task.md), with API facts, disjoint contributor allowlists,
  independent expected behavior, focused/broad gates and explicit stop.
- Updated architecture/testing/index/roadmap/implementation-plan/handoff documents describe proposals as proposals,
  not as new shipped capabilities.

Coordinator read the relevant current Android/product/testing docs and the complete finalized v1 contract documents,
OpenAPI, example fixtures and catalogue README. Source audit used Kotlin mcpls connection, symbols, hover and references,
then exact file reads to inspect behavior. `GymRepository.kt` was read in full. The supplied Java backend source and
private backend configuration were not accessed. Workers did role setup only; neither was delegated implementation,
source review outside its role, or test development during R01.

## Evidence obtained in R01

- Both `git status --short` inventories (including untracked paths) and both HEADs were inspected; pre-existing dirty
  simulator-retirement/document-relocation work was preserved.
- A read-only SHA-256 inventory captured 199 files under app source/assets/schemas, scripts, Gradle metadata,
  workflow, AGENTS and shared-docs before documentation editing. Temporary evidence:
  `/tmp/gymtracka-r01-audit-0q1f5j0v/baseline.json` (not a successor dependency).
- Static `StrictJson.kt` inspection shows normalization through a kotlinx JSON tree before `JSONObject` construction.
  A synthetic JShell call to the **existing compiled** `StrictJsonKt.parseStrictJsonObject` with
  `{"sentinel":1,"sentinel":2}` returned `{"sentinel":2}`; JShell exited 0. This is a focused diagnostic supporting
  the source finding, **not a fresh compilation, suite pass or accepted implementation**.
- Owner clarified LAN and away-from-home access, HTTPS/private setup not yet configured, and an existing Google
  Android debug client with web audience/signing readiness unconfirmed. Official Google implementation documentation
  checked the Web/server client-ID requirement. No clients/keys/configuration were created or changed.

## Documentation-only gate

Passed coordinator checks:

- Preservation comparison: all **199 inventoried non-Android-doc files unchanged**, including app source/tests/assets/
  schemas, existing harness/scripts/build metadata and nested shared-docs. No production/test file was edited by R01.
- **104 local Markdown path references across 10 new/updated documents** resolve.
- All 10 document files passed explicit no-index whitespace checks (needed because `docs/` was already untracked).
- `git diff --check` and `git -C shared-docs diff --check` passed.

The first verification script incorrectly treated no-index's ordinary difference exit code 1 as a whitespace failure;
it was corrected to accept 0/1 only with empty diagnostic output, then rerun successfully with fail-fast shell handling.
This was a verification-script issue, not a production or test-suite failure. Owner review is separate from these
mechanical checks.

## Approved planning choices

1. Independent canonical/deletion storage with **account-wide** visible-intent protection while outbox work remains,
   rather than per-root/child merge/rebase machinery. Unrelated visible remote changes may wait while local work remains.
2. Initial import preparation can remain private/recoverable until its ordered queue is accepted and a complete snapshot
   publishes. A retained import rejection blocks ordinary account activation, not local recovery/sign-out.
3. Full snapshot publication, vault/Room journals, request/ack/session fencing and finite-edit/timer barriers are personal
   safety prerequisites. R12a barriers must precede production activation.
4. Global physical Room PK collisions fail closed with retained recovery data, never scope overwrite/ID remapping.
5. First implementation assignment: R02a raw duplicate-key guard only. Identity namespace, failure/auth alignment and
   transport follow separately; public export/deletion/status remain deferred.

No unresolved v1 behavior was reopened. Precise schema SQL/interfaces for later packages are still pickup deliverables,
not permission for contributors to invent the design independently.

## Limits and stop

No Gradle build/test task, backend start/Maven suite/HTTP gate, device journey, OAuth verification or deployment was
executed for R01. Earlier recorded test counts remain historical baseline evidence. The duplicate-key defect is queued
for R02a, not fixed. Configuration and live test inputs still block R17a. Room stays v9; bundled asset stays v1.

R01 owner review is complete. Both agents await explicit R02a execution authorization and a bounded assignment.
Approval of the design is not automatic authorization for commits, pushes, personal configuration changes or backend work.
