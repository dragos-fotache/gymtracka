> **Historical record, not current instructions.** See the [archive boundary](../README.md) and [current pickup](../../coordinator-handoff.md).

# R04b — Coordinator review

Status: **coordinator-accepted (2026-10-04)**. Owner: “start work at R04b”.
Normative [specification](R04b-task.md); parent [R04 split](R04-task.md).
Stop after b; R04c/startup/Room transitions/account activation require separate authorization.

## Delivered boundary — UNUSED

Four new production files in `data/sync/session/`:

- `SessionRefreshLease`: explicit origin/account/generation/activation-operation/phase authorization seam, one shared
  lease per later authorized activation. Atomic usable/foreground-reauth/invalidated states; single-snapshot admission;
  immediate idempotent invalidation with no I/O/network/run-mutex wait. No public status/reset/reactivation API.
- `SessionRefreshCoordinator`: serialized vault selection/rotation, no credential success cache; settle exact retained
  pending attempt FIRST, even with live access or expired predecessor refresh. Fresh attempt operation/key generated
  once, whole grant+pending CAS durably completes and reload-fences BEFORE send. Typed refresh receives exact body/key.
  Whole returned same-account grant+cleared pending CAS completes/reloads BEFORE bearer use. Old replay expiries are never
  extended; persist expired-access replay first, then rotate its current refresh once if needed. Bounded selection,
  no automatic transport/backoff loop or new key for uncertain predecessor. Safe failures preserve original pair.
- `withAccessToken`: callbacks outside run mutex, durable identity/lease admission and result/error fencing. Only first
  access rejection permits serialized recovery and SAME callback retry; reuse another caller's newer usable committed
  grant rather than rotate twice. Second401 or explicit reauth latches foreground recovery; nonauth failures never resend.
  Callback count at most two; initial expiry selection and 401 recovery are separate bounded selection stages.
- `SessionRefreshingGymSyncApi`: delegates to accepted HttpGymSyncApi with a per-attempt fixed tagged provider. Push
  privately deep-owns validated wrapper/envelope lists before suspension through approved LOCAL wrapper encode/decode,
  preserving EXACT mutation requestJson/IDs/order. Count/duplicate/model/version programming failures precede credential I/O.
  Both requests have identical frozen bytes/read queries, with only bearer rotation. Production HTTPS/timeouts/TLS remain
  in existing transport. No default origin, insecure fallback or account-global token cache.

New errors have fixed redacted displays, closed causes and disabled suppression. Actual caller cancellation precedes
reauth latching and dominates late safe failures/context-recovery copies; active-context injected cancellation retains
identity INSIDE caller. Successful load/clock/UUID/CAS results recheck activity/invalidation; direct access return checks
full admission. Lease invalidation before new CAS stops it; already-in-progress synchronous commit remains uncertain.
Delayed results cannot overwrite a tombstone/replaced operation or grant through stale revision CAS.

The machinery is NOT constructed by the app. No existing production/API/codec/HTTP/vault/schema/build/manifest/backup XML
or asset edits. No startup privacy/journal/Room integration, sign-in/out orchestration, Google/UI/engine/scheduling/account
activation. A lease is not proof of Room authorization; future R04c/R09 must reconcile both stores. Invalidation alone
is NOT successful durable sign-out: it cannot recall admitted bytes, erase storage or guarantee rollback of an in-progress
CAS. No multiprocess or independently constructed concurrent coordinators/duplicate activation leases are supported.

**Legacy incompatible replay remains UNREPAIRED.** Strict malformed legacy grant preserves exact uncertain pair and original
record/revision on reopen. No rounding/replay rewrite/new uncertain key/blanket revocation/inferred account/cache deletion.
A known still-current refresh without pending may freshly rotate; uncertain consumed predecessor never gets another key.

## Independent ownership and corrections

Live Worker gym-worker w4:pJ and QA gym-qa w4:pK rediscovered via list/process-info/footer, native /new verified for both,
Pi meta/muse-spark-1.3-contributor/high. Both received same normative APIs/spec with disjoint four-new-production /
three-new-JVM-test allowlists. Corrections stayed in those sessions. Coordinator owned design/full source/test review and
serialized Gradle slots. Worker briefly listed old test filenames during orientation (no source read); coordinator stopped
further test inventory and reinforced production-only access. QA never inspected production declarations/source/bytecode.

Owner subsequently required fully asynchronous reports: coordinator stopped bounded output waits immediately, no further
Worker/QA polling or waits; contributors reported through fire-and-forget Herdr. No backend/personal input/global config,
server reset, unrelated pane/process or user-focus changes. Kotlin mcpls had bounded initializing/empty-search behavior;
exact allowed reads and actual compilation supplied authority, not a clean LSP diagnostics claim.

Coordinator reviewed all seven production/test/fixture files including untracked contents. Production corrections:
missing imports/override defaults; successful-seam and pre-save fencing; cancellation-before-latch; full direct admission;
strictly newer-revision reuse; single atomic admission observation; suppression closure; private deep push ownership and
preflight type preservation. Coordinator's final edits were focused import order and explicit test coroutine opt-ins only.

Initial focused **82/82** (62+20), independently inspected, was NOT accepted by count. Review found insufficient oracles:
capture-derived “independent” body, one rather than two concurrent401s, decoder rather than decorator preflight, initial
rather than pre-save invalidation, and a cancellable wait preventing the purported racing error. Same-session QA strengthened
literal original text/reopened vault, dual rejected old-token callbacks, exact typed push preflight, post-refresh pre-save
invalidation, synchronous child cancel-then-throw races and same-lease no-latch recovery. Additional legacy-through-real-auth/
reopened-vault and JCA encrypted coordination checks passed. Corrected QA **97/97** was green.

Final review added two targeted oracles before final gates: an ALREADY-ADMITTED callback can reauth-latch while another
caller holds the selection mutex (one-shot load gate proves direct admission refuses); cancellation on the actual SECOND
401 must not latch (distinct from first401 cancellation). Coordinator independently passed **99/99** focused tests.
Inside-caller exception identity assertions are preserved, never compared through async.await stack recovery. Bare
coroutineContext in runTest lambdas can capture the parent receiver; package-qualified suspend property reads the actual
child. QA withdrew its missing-library claim; imported kotlinx.coroutines.currentCoroutineContext exists in production.
No existing test/assertion was removed or weakened. Hand-written seams script client timing only, not server domain logic.

## Actual final gates

- **726 unique offline JVM testcases in45 XML suites**, coordinator forced final execution: prior627 plus78
  SessionRefreshCoordinatorTest and21 SessionRefreshingGymSyncApiTest. Zero failures/errors/skips. Table subcases are
  not extra test-count claims. Real vault codec/CAS restart, before/after-commit uncertainty, whole-grant failure/replay,
  literal exact bodies, expiry/replay/fresh bound, strict legacy preservation, origin/account/generation/op/phase/ABA,
  concurrent expired callers/dual401s, invalidation/latch/cancellation, preflight/list ownership and closed error behavior.
- Production Kotlin, Android-test Kotlin, debug assembly and lint passed with final sources; all relevant final tasks
  executed, not cached/up-to-date test evidence. Actual HttpServiceAuthApi/HttpGymSyncApi routing/body/header and JCA AES256
  vault integration are exercised by scripted JVM fixtures, NOT a real-backend/device/Google gate.
- Lint XML: only existing NewerVersionAvailable Warning. Existing KSP catalogue index warnings, Gradle/native packaging,
  legacy backend-helper and R03c/R04a compiler notes remain. Two NEW nonfatal `Expression is unused` warnings in QA's
  blocked-callback lambdas are recorded; coroutine ExperimentalCoroutinesApi warnings were resolved by explicit opt-ins.
- Debug merged manifest parsed: min24/target37, INTERNET, cleartext=false, ordinary allowBackup=true and both existing
  backup XML references. Existing source/manifest/XML hashes unchanged; no new release processing/OEM transfer claim.
- Debug APK has production refresh classes, no new QA fixtures/test classes/JCA key helper/backend/JDBC/test TLS hosts.
  Bundled v1 asset byte-identical: SHA256 `defa08e9c464f90f0f48d8e3441fe20151803b48948647a91742c1bb82fa9f7d`.
  Room remains9, exported schemas/domain/app/state/build/backup/manifest unchanged. Tracked and new-file whitespace checks pass.

```sh
./gradlew :app:testDebugUnitTest --tests 'com.dragosfotache.gymtracka.data.sync.session.SessionRefreshCoordinatorTest' --tests 'com.dragosfotache.gymtracka.data.sync.session.SessionRefreshingGymSyncApiTest' --no-configuration-cache --console=plain
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:lintDebug --rerun-tasks --no-configuration-cache --console=plain
git diff --check
git -C shared-docs diff --check
```

Coordinator inspected every executed XML testcase/unique name/failure/error/skip in
`app/build/test-results/testDebugUnitTest/TEST-*.xml`; lint in `app/build/reports/lint-results-debug.xml` and APK in
`app/build/outputs/apk/debug/app-debug.apk`. Earlier non-JVM and full-JVM/lint reruns also passed; final combined gate
reran after coordinator's import/opt-in edits. Temporary `/tmp/gymtracka-r04b-{final-gate,full-jvm-gate,build-gate,
focused-final}.log`, initial82 XML copy and pickup manifest are supporting artifacts, not future pickup prerequisites.

## Preservation and stop

Pickup/final Android HEAD `04f7ed24c39b4396122e6d60225942493ba500e2`, shared HEAD
`31c969f261bd3f0fe662e633c0dfffecb3c08393` unchanged. Seven preexisting Android receipt-doc edits preserved; existing
R03c-review/R04a-review remain byte-identical to pickup. All279 pickup files outside the seven intentionally updated local
docs are hash-identical, including the entire shared checkout and every existing source/test/schema/build/asset file.
Four new production files, three new tests/fixtures and two new local task/review docs are uncommitted; no staging/commit/push.

No emulator/service/container/config created. No new instrumentation/API24/full-device/real-backend/Python/Google/OAuth/
Room convergence/hosted-CI/phone-laptop/deployment gate. R04a27-device and R03c auth10/broad56 remain PRIOR evidence.
No full-v1/public-release/startup activation or legacy recovery compliance claim. Worker/QA hold idle in same completed
R04b sessions; rediscover and start fresh native sessions only for another separately authorized slice.

**Next proposed pickup: R04c startup/session recovery seams, ONLY after specific owner authorization.** R09 durable
Room/account transitions and app activation remain deferred. Stop here; no continuation, backend/configuration, commit/push.
