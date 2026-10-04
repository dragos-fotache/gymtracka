# R04c acceptance review — startup/session recovery seams

Date: 2026-10-04. **Accepted within the bounded [specification](R04c-task.md).** Production and independently authored
QA layers were reviewed by the coordinator; contributor completion alone was not acceptance.

## Delivered boundary

- Three new internal recovery files supply immutable validated nonsecret authority observations, closed/redacted
  outcomes, a reads-only startup reconciler and guarded recovered handles. Minimal lease/coordinator extensions add
  optional suspending authorization checks; no rotation, retry, codec or CAS policy was replaced.
- Startup is network/write/clock/UUID-free and privacy-locked until configured origin, complete active authority and
  matching active vault survive rereads. Signed-out orphan credentials, prepared transitions, missing/unreadable stores,
  incomplete baseline and identity/origin changes cannot authorize cached-account visibility.
- Opaque operation tokens and synchronous invalidation enforce newest-start ownership. Current observed startup
  cancellation leaves privacy locked; older completion/cancellation cannot reset a newer handle. Runtime checks fence
  credential seams and transport success/error; stale handles stay dead even if original authority later reappears.
- Exact pending operation/key/body/token survives restart and transient/cancellation/strict legacy malformed replay.
  Expired replay grants persist with original expiries before a bounded fresh rotation. Access-401 retry preserves the
  callback/request, not necessarily the bearer; repeated denial latches foreground reauthentication.
- Invalidation is NOT durable sign-out. An already-started synchronous CAS may commit; its result is unusable under a
  dead handle. No real Room-authority adapter/journal, account transitions, app construction, engine, Google flow,
  scheduler, migration or production configuration was added. Room v9 and its exported schemas remain unchanged.

Durable implementation facts live in [sync implementation](../../sync/implementation.md#startup-recovery-seams);
remaining coordination is in [integration design](../../sync/integration-design.md#6-vault-and-room-two-stores-recoverable-journal-no-fake-atomicity).

## Review findings resolved

Production review corrected wrapping process counters to opaque tokens, cancellation masking at stale/error boundaries,
and publication cancellation checks under the short state lock. The current-operation cleanup never invalidates newer work.

The first independent test attempt stopped at compilation with **zero executed tests**: QA fixture/signature errors and
an incorrect coordinator-supplied retry-hint name were corrected (`retryAfterMillis`, not seconds). Subsequent focused
runs exposed four, then two test-contract defects: treating staged auth answers as committed live credentials, requesting
rollback of an already-started CAS, confusing refresh rejection with access-401 retry, fixture UUID exhaustion, and trying
to return an expired replay access token. These expectations were corrected against the accepted contract, not by
weakening valid assertions or changing production to fit tests. Added cases exercise actual request callbacks, strict
HTTP legacy parsing, escaped exact replay, current-store faults and different-account replacement.

Authority-fault interpretation is explicit: its published reason is AUTHORITY_UNAVAILABLE; permanent lease invalidation
may surface closed STALE_SESSION to an in-flight caller. Current safe transport failures remain unchanged. No rounding,
replay rewrite, new uncertain key, blanket revocation or inferred deletion was introduced.

## Executed verification

Environment: Linux x86_64, Gradle 9.8.0; launcher JDK 27, daemon criterion Java 25. Android compilation targets API 37,
minimum API 24. No device, service, personal configuration/database or real Google account was used.

Commands ran from the Android repository root, serialized with contributor slots:

```sh
# Coordinator: corrected production/KSP, forced fresh execution.
./gradlew :app:compileDebugKotlin --rerun-tasks --no-configuration-cache --console=plain

# Final independent QA focused run; coordinator independently inspected XML.
./gradlew :app:testDebugUnitTest --tests "com.dragosfotache.gymtracka.data.sync.session.SessionRecovery*Test" --rerun-tasks --no-configuration-cache --console=plain

# Coordinator: unfiltered offline suite and broader client build gates, forced execution.
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug :app:lintDebug --rerun-tasks --no-configuration-cache --console=plain

git diff --check
git -C shared-docs diff --check
```

- Corrected Kotlin/KSP: exit 0, all seven actionable tasks executed.
- Final focused XML: **73 unique cases / 2 suites**, 38 startup and 35 authorization; no failures/errors/skips.
- Full coordinator run: exit 0, **61 actionable tasks executed**. Nested XML contains **799 unique cases / 47 suites**,
  no failures/errors/skips. This includes all 73 new cases plus unchanged refresh suites (78 + 21), vault (32), cipher
  (11) and vault codec (31). Kotlin/Android-test compilation, assembly and lint completed in this same run.
- Lint XML: zero errors/fatals, one NewerVersionAvailable warning for the unchanged JVM `org.json` dependency.
- Existing warnings remain: catalogue join-table foreign-key indexes, experimental Android source-set option,
  Gradle/JVM-test deprecations and native library stripping. **Eight unused-expression warnings in new QA tests** are
  recorded; no new production Kotlin error/warning appeared in the compile output. No suppression was added.
- Kotlin LSP diagnostics were unavailable while initializing; no clean-LSP-diagnostics claim is made.
- APK DEX string-table inspection confirms recovery code present, QA fixtures/test classes/backend/JDBC helpers and
  test origin absent. Packaged `gym.db` and all three notice files are byte-identical to source; Android's dotfile
  exclusion is not a missing notice. Source database SHA-256 remains
  `defa08e9c464f90f0f48d8e3441fe20151803b48948647a91742c1bb82fa9f7d`.
- Both working-tree scope reviews and diff checks passed; shared-docs remains unchanged. New files were checked for
  whitespace/final newlines and changed documentation links/anchors verified separately.

## Residual obligations and stop

The [legacy accepted-replay limitation](../../sync/implementation.md#legacy-auth-replay-limitation) remains unrepaired.
Tests prove strict preservation/failure, not successful recovery of incompatible historical response bytes.

R09 owns the real authority adapter/journal, checked durable generations, prepared promotion, two-store recovery and
sign-out/account transitions. R12 owns edit/timer barriers and app rebinding; complete bootstrap and core convergence
still precede activation. A process handle never replaces in-transaction Room fences or durable tombstone cleanup.

No new instrumentation/runtime API-24, backend interoperability, hosted CI, Google/OEM backup, phone/laptop or deployed
TLS gate was executed. Existing records remain scoped historical evidence, not a fresh device/service pass.

**Stop:** app remains signed-out/local-only. R05 planning/implementation and all commits, pushes, deployment/private
configuration or live account actions require separate owner authorization.
