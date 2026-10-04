# R04 — Encrypted session storage and refresh recovery

Status: **R04a and R04b accepted** (2026-10-04). Owner authorized b: “start work at R04b”.
[Specification](R04b-task.md) / [review](R04b-review.md):726 forced JVM/99 focused/build/lint/preservation passed;
unused refresh/lease/decorator only. R03a–c/R04a are accepted prerequisites;
R04c, R09, app activation, commit/push and deployment remain unauthorized.

## Sequential slices

| Slice | Deliverable | Boundary |
|---|---|---|
| [R04a](R04a-task.md) | Inert encrypted vault, complete versioned records, exact-attempt storage, checked atomic revision replacement, Keystore/private-backup exclusion | [Accepted](R04a-review.md):627 JVM/27 API37/build/lint; no HTTP/session orchestration |
| [R04b](R04b-task.md) | Serialized refresh/access-token orchestration using persist-before-send and unchanged uncertain attempts | [Accepted](R04b-review.md):726 JVM/build/lint; unused lease/refresh/bounded retry only |
| R04c | Startup/session recovery integration seams and restart/logout race matrix | Proposed only; prepare detailed specification after authorization; Room transitions remain R09 |

R04a tests storage-level restart, malformed/missing keys, interrupted replacement and concurrent stale revisions now;
these are not deferred to c. R04b tests its network/persistence ordering and fences inside that slice. R04c does not
substitute for either gate or implement account activation/Room transitions incidentally.

## Invariants carried forward

Follow accepted [R01 design](R01-design.md#6-vault-and-room-two-stores-recoverable-journal-no-fake-atomicity) and finalized
[authentication contract](../../shared-docs/docs/backend-contract/authentication.md). Credentials and exact uncertain
refresh body/key belong only in encrypted, app-private, backup-excluded storage. Google exchange/challenge/ID tokens
remain bounded foreground memory. Bind records to configured HTTPS origin, service account and durable device generation.
Vault and Room never share a transaction; prepared/active vault labels alone never authorize account visibility.

[Auth precision legacy limitation](../../shared-docs/docs/backend-contract/issues/auth-expiry-precision.md): original
pre-fix encrypted ACCEPTED replay bytes/deadlines are preserved and can remain incompatible. Strict readers remain strict.
A known current legacy refresh may rotate freshly; an uncertain consumed-token predecessor must never acquire another
key automatically. No rounding, replay rewrite, blanket revocation or inferred account/cache deletion. R04a stores the
original pair without expiry-policy guesses; future recovery/release review must retain the limitation.

## Pickup and stop

R04a pickup Android HEAD `6134a58bacaa20bcfc7ccb872a77ffb104867d21`, shared-docs HEAD
`d5d68d4cfa919839e6f52a590cebda46c31647ae`. Existing R03c tracked/untracked production/tests/harness/docs and two shared
issue/index edits were preserved at the gate, as were coordinator succession edits. No backend work was needed in a.
The owner subsequently committed Android R03c/R04a in `04f7ed2` and shared issue resolution/index in `31c969f`; both trees
were clean before this documentation-only follow-up. See
[the verified delivery record](../coordinator-handoff.md#owner-delivery-commits--verified-2026-10-04); earlier dirty state is historical.
R04a's independent review/gates are accepted. Owner subsequently authorized R04b; its concrete APIs/allowlists and
refresh/CAS/lease/unchanged-retry matrix are in [R04b-task.md](R04b-task.md); its [review](R04b-review.md) now accepts
unused orchestration with726 forced JVM/99 focused/compile/assembly/lint/APK/preservation gates. Seven existing receipt-doc
edits preserved at Android HEAD04f7ed2; shared HEAD31c969f remains byte-preserved/clean. Both contributors hold idle.
Next proposed pickup is R04c ONLY after specific owner authorization. No c specification/assignment, Room/startup/account
activation, backend/configuration, commit/push or deployment. Stop after b.
