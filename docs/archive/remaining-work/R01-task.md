> **Historical record, not current instructions.** See the [archive boundary](../README.md) and [current pickup](../../coordinator-handoff.md).

# R01 — Current-code/v1 audit and integration plan

Status: **R01 planning/design approved by the owner** (2026-10-03). R02 implementation is not yet authorized.
Authorization: the owner requested proceeding with R01 after establishing coordinator/Worker/QA roles.
R01 permits read-only implementation audit and Android-local documentation only. It does not authorize R02,
production edits, tests added to the repository, schema changes, configuration, backend review, deployment, commits or pushes.

## Deliverables and reading order

1. This audit, gap/priorities matrix and external-input register.
2. [Integration design](R01-design.md): canonical/intent storage, ownership, crash transitions, snapshots and UI barriers.
3. [R02a bounded shared specification](R02a-task.md): the proposed first implementation assignment.
4. [Planning verification record](R01-review.md): evidence, owner approval and explicit implementation limits.

Authority: [roadmap](../../remaining-work-roadmap.md), current Android [architecture](../../architecture.md),
[testing strategy](../testing-history.md), shared [product behavior](../../../shared-docs/docs/product-behavior.md),
and the finalized [v1 contract](../../../shared-docs/docs/backend-contract/README.md).
The complete linked domain, sync, service, authentication, account-lifecycle, retention, client-protocol,
compatibility, schema, acceptance-scenario, decision, OpenAPI and example-fixture documents were read.
The catalogue README was read for version/ID/ownership obligations. No backend source was read.

## Live baseline, not archived identities

- Android HEAD: `25bdfd66f1424e7deb06a5fedc585d49fa1f3ab3`.
- Nested shared-docs HEAD: `f65abb82512c373ade9ab08699ef68971b2e8ceb`.
- Both working trees were dirty before R01. Android already had simulator deletions, boundary/build/dependency changes,
  new HTTP tests/harness/scripts and untracked `docs/`; shared-docs already had documentation relocation/deletions and
  README/product/contract updates. Preserve all of these. Ordinary tracked diff does not cover untracked docs/tests.
- Room runtime is v9; the bundled catalogue asset stays v1. No new Room version is assigned by this plan.
- Recorded earlier gates: 183 offline JVM, 46 separately runnable real-backend JVM, 11 launcher Python and 161
  instrumentation methods at the last full device gate. **None is a fresh R01 test pass.**
- Current agents are Pi with `meta/muse-spark-1.3-contributor`, high reasoning: `gym-worker` and `gym-qa`.
  Coordinator is `gym-coordinator`. Pane identifiers are discovery hints only; rediscover before later use.
  Both contributors received setup-only instructions and remain unassigned during R01.
- The historical reference to `reconciliation-design.md` in the implementation plan does not resolve in current
  `docs/`; it is not an approved design input. The new R01 design is self-contained.

## Code inventory and concrete v1 gaps

Paths below are relative to `app/src/main/java/com/dragosfotache/gymtracka/` unless otherwise stated.
This is a focused integration audit, not a claim of a complete fresh correctness/security review of every existing file.

| Area / source | Delivered behavior observed | Gap / next owner |
|---|---|---|
| `data/gym/GymDatabase.kt`, root/support entities | v9, registered migrations, root account/revision/generation/tombstone fields; sequence-ordered outbox; account cursor; active/cached identity; no destructive fallback | No canonical shadow/deletion ledger, staging, transition journal or engine publication state. R05/R08/R09 migrations |
| `data/sync/model`, `codec`, `mapping` | Three aggregate families, all 17 typed kinds, canonical batches/outcomes, imports, strict numeric/time/position validation, no network writes in mappings | Specific finalized-v1 gaps below; do not recreate the DTO suite. R02 |
| `codec/StrictJson.kt` | Parses raw syntax into kotlinx `JsonObject`, then builds `JSONObject` from normalized text | Duplicate names can already have collapsed before field validation; original/escaped-equivalent duplicate keys must fail. R02a |
| `model/WorkoutValidation.kt`, `RoutineValidation.kt` | Entry IDs unique and set IDs unique separately, ordered children normalized by explicit positions | Root/entry/set cross-role aliases are not checked across the whole family aggregate. Shared v1 reservation namespace forbids them. R02b |
| `model/MutationValidation.kt` | Fresh target IDs, kind/target matching, copy/history plans, stable IDs | Copy rules need family-aware alignment: cross-family root/source equality is rejected today, although workout/routine namespaces are independent; same-family target versus *all* source roles needs checking. Do not apply same-family exclusions to cross-family copies. R02b |
| `api/SyncApiFailures.kt` | Fixed failure messages, no raw causes, bounded retry hints | Access authentication and foreground reauthentication conflated; explicit invalid cursor/snapshot token and snapshot expiry types absent; request-too-large is not distinguished. R02c/R03 |
| `api/ServiceAuth.kt` | Opaque credentials, four SDK-independent operations, Google interface, explicit exchange/refresh keys | Challenge has no purpose parameter; no production auth codecs/frozen exact-body attempts; direct construction does not validate all expiry ranges. R02d/R04/R11 |
| `store/SyncStore.kt`, `DurableRowReader.kt` | In-transaction immutable session fence, local generations and outbox captured together, tail-only never-sent autosave coalescing, immutable v1 wire text and affected roots, unsupported/corrupt row failures | No claiming loop, exact acknowledgment, canonical writes, rejection orchestration or cache publication. R05–R08 |
| `data/gym/SyncDao.kt` | Basic freeze/delete/state primitives | These are not an engine: no fenced head claim/outcome transaction; broad primitives must not be used as successful acknowledgment in isolation. R06 |
| `data/gym/GymRepository.kt` | All current mutation families fenced/captured; scoped reads; customs/routines/workouts and active replacement; finish retains semantic ordering | Context constructor always defaults to signed-out null/0. No import/switch lifecycle or remote reconciliation. R09/R12 |
| Root/child Room entities | UUID string PKs are global within each physical table, not `(scope, UUID)` | Canonical cache must be independently account-keyed; never REPLACE a local/foreign/bundled PK. Validate materialization collisions and fail closed. R05/R09 |
| `ui/GymRepositoryProvider.kt` | Composition-local override; production `remember(context) { GymRepository(context) }` | Startup/session coordinator must construct/key a repository from recovered scope/generation, not reuse this default after transitions. R09/R12 |
| `ui/activeworkout/ActiveWorkoutScreen.kt` | Failure-retaining finite edit drain and callback ownership | General cross-feature transition/publication barrier absent. R12 |
| `ui/GymAppState.kt` | Finish drains registered feature edits and terminal timer jobs; serial claim-once rest writes; hardware boundaries | Terminal write failures are swallowed, join is not failure-aware; dispose cancels terminal jobs. No remote invalidation or session drain. R12 must expose outcomes without retrying a timer twice |
| Routine/catalogue/history/detail state holders | Composition-scoped state, explicit loads and local refreshes | No Room change observation for remote changes; routine finite mutations lack common failure-retaining drain; custom editor has unsaved presentation draft. R12 |
| `app/src/main/AndroidManifest.xml`, XML backup rules | VIBRATE/WAKE_LOCK, backup enabled with template rules | No INTERNET permission, HTTPS configuration, vault or explicit credential storage exclusion implementation. R03/R04 |
| `app/src/test/.../sync/backend/HttpGymSyncApi.kt` | JDK-only loopback HTTP adapter; exact production push encoder and response decoders; matching outcome IDs/order; safe failure boundary | Not Android transport or auth verification; production cannot import JDK HTTP/JDBC fixtures. R03 |
| Account/export/delete/status, Google, WorkManager | Contract requirements only | No production implementation. Personal/public slices below |

### Local mutation foundation to preserve

`GymRepository.kt` was read in full; Kotlin LSP references of `SyncStore.withFencedTransaction` located the existing
integration sites. Current families, all inside fenced transactions:

| Entry points | Existing durable intent |
|---|---|
| Custom create/update; delete | Complete custom upsert, or owned tombstone/delete; bundled guards and retained-reference restrictions |
| Routine draft/create, name/add/rest/set autosaves | Complete routine upsert; saved blank/empty autosave distinct from promotion |
| Routine save; delete child/set; delete root | Semantic save/delete-child/delete-set; owned root tombstone/delete |
| Workout empty start; perform again; routine start | Semantic creation with stable planned child IDs and all locally replaced actives in affected roots |
| Save workout as routine | Semantic source command, new routine target/child bindings |
| Workout name/notes/rest/set/add-set/recorded-rest edits | Complete *active* upsert; set sanitization, actual completion transition, stored parent/position guards |
| Workout add history/replace/delete child/set | Semantic commands, stable IDs, history plan and transactional renumbering |
| Workout finish; discard/delete root | Finish after active predecessors, or root tombstone/delete; signed-out root deletes remain hard deletes |

No import method, remote application or session orchestration was found in these delivered integration surfaces.
Source command dependencies and capture order must not be sorted globally by root type.

## Proposed reviewable subplans and dependency refinement

These are **planning slices**, not blanket implementation assignments. Except R02a, detailed file/API specifications
are prepared only when that slice is picked up. All remain implementation-pending.

| Package | Personal now | Deferred public / later gate |
|---|---|---|
| R02 | **a** duplicate-key guard; **b** identity namespace validation; **c** typed read/auth/size failures; **d** sign-in challenge/auth codecs and exact-body request boundaries | **e** lifecycle proof/export/delete/status models/codecs with R14/R15 |
| R03 | **a** HTTPS origin/cancellable transport; **b** sync endpoints; **c** sign-in/refresh/logout adapters | **d** lifecycle endpoints/export stream with R14/R15; no unused browsing routes |
| R04 | **a** encrypted atomic vault; **b** serialized exact-key refresh/recovery; **c** startup fence contract | Same credential integrity remains mandatory publicly; no weaker personal mode |
| R05 | **a** canonical/deletion/publication storage and capture-generation integration; **b** complete canonical batch application; **c** protected projection/transaction gates | Later per-root optimization optional, not required; never weaken correctness |
| R06/R07 | **a** one-head durable claiming/replay; **b** complete outcomes/rejections; pull loop and transactional cursor; shared exclusive run ownership | Expanded interoperability/fault matrix later, core safety now |
| R08 | **a** staging migration; **b** validated frozen download; **c** complete publication/expiry/restart | Same completeness required for personal bootstrap |
| R09 | **a** journal/vault/Room lifecycle; **b** import/replace capture; **c** crash-safe activation/sign-out/switch | Confirmed deletion cleanup attaches in R15, never inferred from auth failure |
| R10 | **a** core two-store restart/retry/delete/active/snapshot/newer-intent convergence | **b** expanded all-family/concurrency/retention/service-control matrix before public release |
| R11 | **a** foreground Google SDK/config; **b** sign-in/out cancellation | **c** fresh export/delete/status flows when corresponding features ship |
| R12 | **a** finite-edit/publication/session barriers; **b** observations/label refresh; **c** active/timer/navigation rebinding | Broad UI/device matrices later; data safety now |
| R13 | **a** account UX spec; **b** personal account/status/manual/error/rejection recovery UI and foreground/edit triggers | Lifecycle UI follows R14/R15 |
| R14/R15 | Not personal blockers; no delete/export controls advertised as delivered | Complete export then deletion/status/re-signup features and proofs |
| R16 | Unattended personal phase after usable foreground app | Integrate deletion-specific cancellation when R15 ships |
| R17 | **a** phone/laptop Google+HTTPS foreground journeys; **b** unattended/restart/background journeys | **c** release signing/device/hosted-CI/lifecycle expansion |
| R18 | Focused per-slice coordinator reviews still apply | Public readiness audit explicitly deferred |

Retain the roadmap graph with two explicit refinements:

- R05 representation work needs the R02b identity rules; R03 needs relevant R02c/d boundaries, not public R02e.
- R12a barriers must be accepted **before production projection/session activation** in R09/R13. Storage and
  deterministic engine tests R05–R10 can use injected barriers before UI integration; this is not permission to
  enable an app engine with a no-op barrier while screens accept edits. R09 journal tests may precede R12a, activation may not.

Recommended first assignment is R02a because its verified boundary defect affects every later network codec and can
be fixed/tested without Room, transport, identity SDK, user credentials or configuration.

## External inputs gathered now

| Input | Owner response / readiness | Action / boundary |
|---|---|---|
| Phone reachability | **Home LAN and away-from-home access required** | Recommend private remote connectivity plus properly trusted HTTPS; exact VPN/tunnel/domain/certificate solution needs separate approved configuration task. No public exposure assumed |
| HTTPS/private setup | **Not configured** | R03 can implement a secure configurable boundary; R17a live gate blocked, not R02 deterministic development |
| Google Android client | Owner has “GymTracka android debug” client | Reuse if its type/package/certificate match actual APK; console not inspected |
| Web/server OAuth audience | Not established by owner response | Need a Web application OAuth client ID in the same Google project, or identify existing one; Android uses it as `serverClientId`, backend verifies it as audience. No secret in APK/docs |
| Installed signing certificate | Owner unsure | Later authorized `./gradlew :app:signingReport --console=plain` establishes build certificate SHA-1/SHA-256; compare actual installed artifact certificate if it differs. Do not read/copy keystore private keys |
| Phone/account/live data | Device details and explicit live test account/data permission not supplied | Obtain before R17a; owner data is not a failure-injection fixture |
| Laptop start/backup/recovery | Always-on personal laptop target, practical procedures not supplied | Later separately approved operational setup; backend is build/start/HTTP black box only |

Official Google implementation guide checked 2026-10-03:
<https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation> explicitly uses a Web client ID
in `setServerClientId`. An Android debug client alone does not establish the server audience setup. Release Android
client/fingerprint can wait until that signing variant is actually used. Client IDs/fingerprints are not private keys,
but do not publish personal configuration incidentally. No OAuth client was created or modified.

## R01 stop condition

The owner approved the R01 design and next-slice plan. Both agents remain idle until explicit R02a execution
authorization; no production/test implementation or migration has begun. R01 approval is distinct from R02a execution. Configuration/live gates remain
external blockers, not reasons to weaken TLS, credentials, session fences, staging or transactional tests.
