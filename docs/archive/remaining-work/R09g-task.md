# R09g — complete inert same-account recovery

## Authority, outcome and stop

Execute the owner-approved two-slice [R09 plan](../../remaining-work-roadmap.md#remaining-r09-plan) under the
[coordinator workflow](../../coordinator-workflow.md). R09a–f are accepted. The owner selected **preserve-and-supersede**:
[P17](../../../shared-docs/docs/backend-contract/open-decisions.md#confirmed-product-decisions) and
[authentication recovery](../../../shared-docs/docs/backend-contract/authentication.md#android-session-storage-and-recovery)
own that decision. Fresh service-confirmed foreground same-origin/same-service-UUID sign-in may supersede an
unrecoverable predecessor **only while atomically retaining and permanently fencing its exact encrypted evidence**.
This is not successful replay, revocation, old-token rekey or deletion. Transient failure alone never initiates it.

Deliver one consolidated slice: signed-out retained-account/missing-vault recovery, explicit unsuccessful/legacy-malformed
pending-session supersession, and interrupted-transition resumption. Reuse Room12, vault CAS, shared owner/credential
mutex, R09e finalization and R09b/c/d preparation/engine/completion boundaries. Stop at advisory private PREPARING/PREPARED
eligibility. No app construction/visibility/repository rebinding/bootstrap/promotion/Google adapter/account UI/switching/
sign-out/legacy-data choice/convergence/deployment/backend source/configuration/live account or device actions.

Unreadable or foreign vaults remain preserved and blocked. Fresh sign-in cannot adopt another UUID/origin's cache or
vault. Unknown retained provenance and local-only workout/routine/custom roots still require owner direction. No cleanup,
expiry rounding, automatic new key for an old token, orphan credential adoption or hidden R09 sub-slice.

## Inventory and concrete interfaces

Paths below use `app/src/main/java/com/dragosfotache/gymtracka/data/sync/session/` unless qualified.
Existing `RecoveryRoomSnapshot(revision, sessionGeneration, cachedAccountId, phase, identity, baselineComplete)` is immutable
and nonsecret. Phases: SIGNED_OUT (identity=null/baseline=false), ACTIVE, PREPARING, TRANSITIONING. Identity:
`RecoveryIdentity(binding: ServiceSessionBinding, operationId: String)`. Binding:
`ServiceSessionBinding(origin: ServiceOrigin, accountId: String, sessionGeneration: Long)`. Origin parses canonical HTTPS;
UUIDs are lowercase. `ServiceSession(account: ServiceAccountRef, accessToken: OpaqueToken, accessExpiresAtMillis: Long,
refreshToken: OpaqueToken, refreshExpiresAtMillis: Long)` preserves original scalar values/deadlines. No clock in recovery.

`SessionVault.load(): VaultSnapshot`; `compareAndSet(expectedRevision: Long, record: VaultSessionRecord?): VaultSnapshot`.
Snapshot: positive monotonic revision plus optional record; zero means genuinely empty, positive/null is a tombstone.
Record: binding, operationId, phase (PREPARED/ACTIVE), session, pendingRefresh. Pending:
`VaultPendingRefresh(operationId, attempt: ServiceRefreshAttempt)`; attempt has idempotencyKey/requestJson/refreshToken;
`ServiceRefreshAttempt.fromJson(key, originalJson)` restores exact whitespace/scalars. Pending token equals grant refresh.
`EncryptedSessionVault(storage: VaultFileStorage, cipher: VaultCipher, ioDispatcher=Dispatchers.IO, mutex=Mutex())` rereads
committed bytes and increments exactly once. File storage `read(): ByteArray?`, `replace(bytes: ByteArray)`; cipher
`seal(plaintext): ByteArray`, `open(sealed): ByteArray`. `VaultRecordCodec.encode/decode` are internal.

Required ownership is `PushRunOwner.mutex` THEN the supplied shared refresh credential `Mutex`, held continuously across
reads/reservation/vault CAS/finalization/error classification. No default owners/duplicate refresh machinery/nested slots.
Constructors are inert. Production still uses signed-out/local-only repositories and constructs none of these components.

### Retained encrypted evidence

Add these internal model contracts in VaultModels.kt:

- `VaultSupersededSession(sourceRevision: Long, record: VaultSessionRecord)`: positive source revision; the source record
  has an EMPTY superseded collection (no recursive records). Fixed redacted display. Equality compares full source
  revision/binding/operation/phase/grant/deadlines and semantic pending operation/key/body/token, not attempt reference.
- `VaultSupersededSessions(entries: List<VaultSupersededSession>)`: defensively owned, immutable ordered `entries`, value
  equality/hash matching the above semantics, fixed redacted display. No mutable list escapes. Empty is allowed. Entries
  have strictly increasing source revisions and source generations and consistent origin/account; duplicates refuse.
- Append `supersededSessions: VaultSupersededSessions = VaultSupersededSessions(emptyList())` to VaultSessionRecord.
  All archived sources match current record's origin/account and have LOWER generation; no current refresh token equals
  an archived source's refresh token. Snapshot additionally requires each sourceRevision < snapshot.revision.

Archive only an uncertain source being replaced: append source revision and full original source record with its archive
field emptied to the existing archive. A settled source replacement simply carries its existing archive forward. Active
pending on the new target remains independent. Archived material is never selected, replayed, rebound, promoted or used
as authentication; it is private retained evidence. No retirement API or expiration cleanup in this slice.

CAS must reject removal/reordering/modification of already archived entries, clearing a nonempty archive via null record,
or reinstalling archived-session credentials. A binding-changing replacement of a pending current record must append
that exact source revision/record once; stale CAS/overflow writes nothing. A successful ordinary same-session refresh
still atomically replaces its grant+pending using accepted machinery and carries all archived evidence unchanged.
No secure flash/String-erasure claim. Later authorized sign-out/deletion needs an explicit all-material cleanup boundary;
primitive tombstoning cannot silently discard archives.

Codec: retain EXACT v1 encoding for snapshots with no archive, and decode all existing v1 records unchanged. Introduce v2
only for archive-bearing records: existing big-endian v1 grammar with version=2, followed by Int positive archive count
and for each entry Long sourceRevision plus the existing record grammar (without recursive archive suffix). No trailing
bytes/invalid tags/invalid UTF8/type normalization. Validate counts against remaining input before allocation; no arbitrary
content cap. Reject unknown versions, empty v2 archives, malformed/recursive/inconsistent entries and invalid counters.
A v1 load performs no write/key creation. Ordinary refresh/promotion/reauth CAS retains v2 archives. Update every existing
stable semantic comparator and refresh pre/post-CAS comparison to include archives; no reference-equality regressions.

### Same-account authority (new narrow interface)

`SameAccountRecoveryAuthority : SessionReauthenticationAuthority` adds:

```kotlin
suspend fun checkSameAccountRecovery(
    expected: RecoveryRoomSnapshot, origin: ServiceOrigin, accountId: String,
)
suspend fun beginSameAccountRecovery(
    expected: RecoveryRoomSnapshot, origin: ServiceOrigin, accountId: String, operationId: String,
): RecoveryRoomSnapshot
```

RoomSessionAuthority implements it using existing strict transactional observations, predicates/counts/post-write checks.
`check` reads only, requires exact expected snapshot, nonnull cached target account, known exact durable journal origin,
matching identity when present and no local-only roots. Coherent device cache-state is `private` for SIGNED_OUT,
`ready` for ACTIVE, and `preparing` for PREPARING/TRANSITIONING (the existing atomic writers establish these tuples).
Never infer provenance from vault/cache labels. New-install empty
SIGNED_OUT is not retained-account recovery (reuse R09b elsewhere); foreign/unknown provenance refuses ACCOUNT_MISMATCH.

`begin` repeats all checks INSIDE its transaction, accepts coherent retained SIGNED_OUT/ACTIVE/PREPARING/TRANSITIONING,
requires a fresh operation different from present Room operation, preflights revision+2 and generation+1, and reserves
TRANSITIONING at revision+1/generation+1 with the SAME account/origin/new operation; device active=null/cached=same,
cache_state=preparing/new generation. Baseline=false. It changes only journal/device. An incomplete ACTIVE baseline is
allowed here because the result stays private and later existing engine must bootstrap. Exact expected device/journal
and full post-write reread are required; triggers/count-zero/cancellation roll back. No entities/schema/asset change.
`finishReauthentication(expected)` remains the existing exact TRANSITIONING→PREPARING writer with revision+1, same
reserved generation/identity, local-root and full tuple checks. Resume never reserves/restarts a generation.

### SameAccountRecoveryCoordinator (new consolidated boundary)

Constructor `(origin: ServiceOrigin, authority: SameAccountRecoveryAuthority, vault: SessionVault,
runOwner: PushRunOwner, credentialMutex: Mutex)`. Required args, no I/O. Methods:

```kotlin
fun invalidate()
suspend fun prepare(expected: RecoveryRoomSnapshot, operationId: String, session: ServiceSession): PreparedSessionObservation
suspend fun resume(expected: RecoveryRoomSnapshot, session: ServiceSession): PreparedSessionObservation
suspend fun recover(): SameAccountRecovery
```

`session` is a supplied fresh service-confirmed foreground sign-in grant at configured origin and exact retained UUID,
NOT a Google token, inferred email account or reconstructed credential. These methods do no HTTP, refresh, exchange,
clock/UUID generation, projection or cleanup. Fresh-input validation precedes newest opaque invocation fences/locks/I/O.
All methods hold shared owners in stated order, fence every seam/late result/error, and alternating Room/vault/Room/vault/
Room rereads compare the ENTIRE exact pair including archive/pending semantic bytes. Room checks after vault loading and
immediately before CAS/finalization are mandatory. No CAS retry. Caller cancellation wins, then newest fence, then durable
Room drift, then closed errors. Invalid input does not supersede valid in-flight work. Already-started writes may commit;
never compensate or report success from uncertain returns. Restart rereads the durable pair. No process success cache.
Use existing SessionReauthenticationException/Failure categories and map Room/vault failures consistently with R09e.
Canonical failure facts (coordinator-supplied contract, not permission for QA production access): malformed operation,
missing retained account input or non-private `resume` input → INVALID_INPUT; foreign account/origin, incompatible
same/newer-generation source, wrong installed-target supplied grant or attempted archived-token reuse → IDENTITY_MISMATCH. An uncertain source
supplied with its same refresh token → INVALID_INPUT. Changed expected Room → STALE_AUTHORITY; retired invocation →
STALE_OPERATION; known checked increment overflow → COUNTER_OVERFLOW; observed full-source vault drift → VAULT_CONFLICT.
Room ACCOUNT_MISMATCH maps IDENTITY_MISMATCH, LOCAL_DATA_REQUIRES_DECISION maps the same-named reauth category,
CORRUPT_AUTHORITY maps AUTHORITY_CORRUPT and UNAVAILABLE maps AUTHORITY_UNAVAILABLE. Vault CONFLICT maps VAULT_CONFLICT,
REVISION_EXHAUSTED maps COUNTER_OVERFLOW, and INVALID_RECORD/UNREADABLE/UNSUPPORTED_VERSION/UNAVAILABLE map VAULT_UNAVAILABLE.
Primitive model/archive-preservation violations are VaultFailure.INVALID_RECORD, not revision conflicts; unknown actual
revision is CONFLICT. Displays/cause/suppression stay closed. These categories remain subordinate to caller cancellation,
newest fencing and durable Room drift.

Source eligibility (never send/use the source credential): empty revision0/tombstone permitted; any occupied record must
match configured origin/retained account, plus:

- ACTIVE Room: exact same binding/operation; source ACTIVE or exact PREPARED completion-gap record.
- Retained SIGNED_OUT: source generation strictly lower than the current durable generation; authority check proves
  retained origin/account. This preserves prior fenced-session evidence, not orphan authorization.
- TRANSITIONING/PREPARING: exact current PREPARED target, OR a strictly OLDER-generation same-origin/same-account
  record with different operation (ACTIVE or PREPARED), treated only as retained evidence. The owner explicitly selected
  this rule to recover signed-out reservation gaps: fenced Room gen7/vault gen6 → reserved Room gen8/vault still gen6.
  Read-only classification is NeedsGrant, never old-credential admission. Installation requires the fresh supplied
  service-confirmed same-account grant under the exact reserved Room identity; resume never restarts the generation.
  For an older settled source, a supplied identical old refresh token refuses IDENTITY_MISMATCH; the existing uncertain
  same-token INVALID_INPUT refusal remains. No old record is used as credential authority or sent to the service.
- Other bindings, same/newer-generation incompatible operations, foreign archives or unreadable evidence refuse unchanged. Check archive membership
  too: no source or supplied grant may reactivate an archived refresh token.

`prepare`: explicitly starts a new recovery operation (not restart inference). Supplied operation must differ from source
operation too. Complete stable source/Room preflight and all checked counters before reservation. For an uncertain source,
supplied refresh token must differ from old token. Reserve once via `beginSameAccountRecovery`; verify exact durable
reserved snapshot. Reload original full source, ensure same revision/semantic record/archive, recheck Room AFTER vault
load, then ONE CAS of full supplied PREPARED target plus inherited/appended archive. Verify exact vault revision+1 and full
record. Stable TRANSITIONING pair → existing finalization → stable PREPARING pair → advisory PreparedSessionObservation.
No legacy roots may be introduced after reservation and silently admitted.

`resume`: exact expected TRANSITIONING/PREPARING identity and same-account supplied grant; no new operation/reservation.
If an exact PREPARED target is already installed, require supplied full session equality, preserve its pending/archive,
and finalize only TRANSITIONING; PREPARING is read-only idempotence. A different supplied grant is not permission to
replace the installed target (caller can explicitly `prepare` a new operation). If credentials are empty or an eligible
strictly older eligible evidence, install exact expected PREPARED target by ONE CAS, archiving any uncertain predecessor as above,
then finalize if necessary. Before a needed CAS, preflight vault increment space and, for TRANSITIONING, Room finalization
increment space; no target write when known finalization overflow already prevents completion. Read-only installed-target
idempotence needs no vault increment. Recheck local roots even for missing-target installation/PREPARING idempotence. Any installed
ACTIVE private target, same/newer-generation incompatible record or foreign evidence refuses. No grant is reconstructed by Room.

Read-only `SameAccountRecovery` internal sealed interface:
`NoRetainedAccount` (no cache, no vault probe), `EligibleForReplacement(room: RecoveryRoomSnapshot)` (SIGNED_OUT/ACTIVE
stable eligible source), `NeedsGrant(room)` (private missing/strictly older evidence), `NeedsFinalization(room, vaultRevision)`
(TRANSITIONING/exact installed target), `Prepared(observation: PreparedSessionObservation)` (PREPARING/exact target),
`Blocked(failure: SessionReauthenticationFailure)`. Fixed redacted displays. `recover` uses real authority/local-root/
provenance checks and stable pair rereads; it writes nothing, never returns credentials or app authority. Cancellation and
stale invocations propagate, not Blocked. A selected ACTIVE pair still requires existing startup admission for offline use;
EligibleForReplacement is replacement eligibility, not an instruction to require sign-in at every startup.

## Contributor independence and permitted paths

Same normative spec for both fresh native `meta/muse-spark-1.3-contributor`/high roles. Reports asynchronously to
`gym-coordinator`, never wait/poll. No Gradle without an explicit serialized slot. Coordinator owns all docs/integration/
final review/resources; lint freezes BOTH roles' sources. No contributor may read opposite-role code/diffs/reports.

Worker may read production session package, directly needed sync api/http/model/store and gym authority DAO/database
production declarations; no tests/generated/decompiled sources. Write only: new SameAccountRecoveryModels.kt and
SameAccountRecoveryCoordinator.kt; existing VaultModels.kt, VaultRecordCodec.kt, EncryptedSessionVault.kt,
RoomSessionAuthority.kt, SessionReauthenticationCoordinator.kt, SessionPreparationCoordinator.kt,
SessionRecoveryCoordinator.kt, SessionRefreshCoordinator.kt, PreparedSessionEngineCoordinator.kt. Changes outside new
boundary are confined to archive preservation/equality, Room narrow extension and vault model/codec validation. No DAO/
entity/build/manifest/app changes without coordinator escalation.

QA may read/write TEST sources only under existing JVM and Android session-package paths, plus QA-owned directly needed
HTTP test fixtures. Read-only harness reuse additionally includes the directly needed R05cTestLease/r05cGrantBarrier
in `app/src/androidTest/java/com/dragosfotache/gymtracka/data/sync/ProjectionPublicationTestSupport.kt`
for the declared private-engine completion regression; do not edit that shared test-support file. No production declarations/implementations/diffs/decompilation/schema JSON/generated sources/Worker
output. Write new SameAccountRecoveryTestFixtures.kt, SameAccountRecoveryCoordinatorTest.kt,
VaultSupersededEvidenceTest.kt (JVM) and SameAccountRecoveryRoomVaultTest.kt (instrumentation); focused old QA fixture
updates only if archive tests need them, preserving prior expected behavior. Missing facts go to coordinator.

Harness contracts: use existing QA-owned Refresh/Vault/Preparation/Reauthentication fixtures for grants/semantic pending,
coroutine gates and fakes; make a dedicated FakeSameAccountAuthority implementing the new contract. Real instrumentation
uses `Room.inMemoryDatabaseBuilder(context, GymDatabase::class.java).build()` or owned file DB/reopen, literal existing
QA journal/device/cache/retained-row seeds and real RoomSessionAuthority. `AndroidSessionVault.createForTesting(context,
namespace)` requires test-owned `gymtracka-test-...` namespace; alias is
`com.dragosfotache.gymtracka.session.<namespace>`. Clean only owned namespace/Keystore alias/databases. Existing QA
SessionReauthenticationRoomVaultTest contains literal seeds and complete retained-row preservation helpers; reuse/copy
those without production inspection. `SessionRecoveryCoordinator`/preparation/engine existing constructors/harnesses may
be reused from QA-only fixtures. No sleeps, real Google/backend fixtures or derived encoder expected bytes.

## Risk-selected verification

High risk: credential-format compatibility, uncertain evidence atomicity, retained-account provenance and generation
fencing. Select independent expected outcomes, not a quota; do not duplicate all R04/R09a–f matrices.

Distinct new obligations:

1. Independently encoded v1 read/no-write and exact no-archive v1 reencode; independent v2 grammar/bytes roundtrip with
   original whitespace/non-ASCII tokens/deadlines; strict version/count/truncation/trailing/invalid archive refusal;
   input collection mutation cannot alter retained evidence. Tombstone/removal/reorder/reinstallation refuses without write.
2. ACTIVE missing vault, retained SIGNED_OUT missing/occupied uncertain source, failed/legacy-malformed pending source,
   and incomplete baseline all reach exact private preparation with fresh supplied grant and retained data/evidence.
   Actual strict malformed HTTP replay followed by explicit fresh-grant supersession proves no permissive decoder/rekey.
3. TRANSITIONING older pending predecessor resumption, including a signed-out pre-CAS failure and restart with a
   nonadjacent retained source; prove exact operation/generation reuse and one archive append. Missing vault after reservation; installed target finalization;
   PREPARING target pending/archive read-only idempotence; wrong supplied grant/foreign/same-or-newer incompatible/unknown-origin/
   local-root/counter overflow refuse. One representative fault per shared category, not Cartesian duplication.
4. Real encrypted commit/reopen: old uncertain bytes OR new grant+exact archive, never lost evidence. Post-commit lost
   return/cancellation/invalidation, post-reservation Room/vault drift, pre-CAS Room-after-vault fence, newest invalidation
   and shared-owner/credential contention. Resume same operation/generation and never archive twice. No compensating CAS.
5. Real Room journal/device reservation/finalization predicates and postwrite trigger/count-zero/cancellation rollback;
   local root introduced after reservation blocks finalization/admission. Complete representative domain/children/outbox/
   frozen bytes/checkpoint/stage/canonical/cache/watermarks/foreign/catalogue preservation after operations and file reopen.
6. Freshly admit the archive-bearing prepared pair through existing R09b/c/d harness or a representative real engine/
   refresh/completion regression; ordinary selected-session refresh/promotion and later settled reauthentication preserve
   archives exactly. Never select archived tokens; actual transport/clock/UUID observations prove no recovery network.

Reuse all existing R04/R09a–f tests and authority/vault/parser/projection suites; no duplicate exhaustive validators.
Iteration: coordinator/explicit Worker compile-only slot, affected JVM tests and new Android-test compilation; focused real
Room class after review. Acceptance: freshly executed full offline JVM; fresh new real Room/Keystore/reopen class and
full unfiltered instrumentation brought forward because vault-format/preservation changes cross all session paths;
unsuppressed frozen-source lint, debug assembly, APK credential/test-helper/backup/preservation inspection. Inspect actual
XML names/counts/failures/errors/skips and task exit status; cached/filtered/compiler results are not full execution.
All Gradle commands use `--offline --console=plain`; per-test rerun via `:app:testDebugUnitTest --rerun` / connected task
`--rerun`, not blanket dependency reruns. No Room schema change; preserve asset/exported1–12 bytes and compile KSP.

No fresh real-service test is required for unchanged HTTP/server behavior; existing auth interoperability is reused, not
claimed fresh. Two-store real-engine HTTP convergence remains core R10 before activation; real edit/timer drains R12a,
API24/hosted CI/Google/OEM/cloud-transfer/phone/deployment journeys remain their separate milestone gates. Missing required
slice gate/model/tool/input is a blocker to escalate, not a pass. Validate changed links/anchors and both `git diff --check`.

## Acceptance and continuation

Coordinator independently reviews full production/test/new-file diffs and declared executed gates, writes scoped review,
extracts enduring facts, updates owning docs and replaces handoff, archives this spec/review, and commits scoped shared
changes first then Android. No push or history rewrite. R09g completion is inert retained-account recovery eligibility;
it does not repair historical malformed replay or activate the app. R09h remains blocked on R12a/core R10 capability
gates: escalate next-task sequencing to the user rather than adding R09 slices or bypassing prerequisites.
