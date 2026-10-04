# R04 — Encrypted session storage and refresh recovery

Status: owner authorized **R04a only** (2026-10-04: “Let's start work on R04a then”).
R03a–c are accepted prerequisites; this does not authorize R04b/c, R09, app activation, commit/push or deployment.

## Sequential slices

| Slice | Deliverable | Boundary |
|---|---|---|
| [R04a](R04a-task.md) | Inert encrypted vault, complete versioned records, exact-attempt storage, checked atomic revision replacement, Keystore/private-backup exclusion | [Accepted](R04a-review.md):627 JVM/27 API37/build/lint; no HTTP/session orchestration |
| R04b | Serialized refresh/access-token orchestration using persist-before-send and unchanged uncertain attempts | Proposed only; prepare detailed specification after authorization |
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

Live Android HEAD `6134a58bacaa20bcfc7ccb872a77ffb104867d21`, shared-docs HEAD
`d5d68d4cfa919839e6f52a590cebda46c31647ae`. Existing R03c tracked/untracked production/tests/harness/docs and two shared
issue/index edits are preserved. Coordinator succession edits are also preserved. No backend work is needed in a.
Only R04a has concrete APIs/allowlists. Its independent review/gates are accepted; stop here. R04b refresh coordination
is next ONLY after specific owner authorization. No b/c detailed specification, contributor assignment or implementation
is started; do not continue the roadmap.
