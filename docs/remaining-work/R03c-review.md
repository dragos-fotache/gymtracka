# R03c — Coordinator review

Status: **R03c coordinator-accepted (2026-10-04)** after unchanged client interoperability rerun against the
independently reviewed backend correction. New issuance/rotation precision blocker is resolved; incompatible pre-fix
accepted replay remains a documented legacy limitation, NOT a repaired case or relaxation of the contract.
Owner authorized R03c only. Normative specification: [R03c task](R03c-task.md).
At this gate, no R04/R03d/R02e, commit/push, app activation, deployment or private configuration was authorized.
Later R04a acceptance and owner delivery commits are recorded below; current pickup follows the coordinator handoff.

## Delivered client boundary

- `http/HttpServiceAuthApi.kt`: unused production challenge/exchange/refresh/logout adapter. All four purposes/defaults,
  exact retained exchange/refresh UTF-8 bodies and header-only keys, no bearer/query/automatic retry, strict service
  parsing plus approved auth codecs, complete grants/original expiries and verified-empty204 logout.
- `http/AuthHttpFailures.kt`: pure operation/status/code table. Size/rate/server precedence, refresh401 foreground
  reauthentication, exchange proof rejection versus unavailable original-grant replay, exact unknown permanent codes.
  No access-bearer recovery/read-token expiry/deletion inference.
- Bounded amendment in existing `http/OkHttpServiceTransport.kt`: header-arrival ProtocolException maps to closed-cause
  MalformedResponse instead of generic Transient. OkHttp rejects forbidden nonempty204 framing before delivering headers;
  body verification alone cannot see it. Other IOException/cancellation/security/replay behavior is unchanged.

Preflight preserves frozen bytes rather than reconstructing attempts. Original scalar fidelity prevents numeric-overflow
string fallback; response timestamps remain strict/lossless. No model/codec/interface/durable version changes, arbitrary
content caps, library/build/manifest/Room/schema/asset/repository/state/UI changes or app construction/activation.

The outer auth boundary preserves safe failures/injected cancellation for active callers and rechecks current caller
activity for cancellation, safe and generic failures. Cancelled callers observe their original stored cause despite
nested stack recovery or source-close/IO-child-cancellation races. No internal coroutine APIs, cause-unwrapping heuristic,
message-only identity assertion or disabled stack recovery.

## Independent review and corrections

Fresh Pi Worker/QA sessions used meta/muse-spark-1.3-contributor/high, the same normative specification and disjoint
allowlists. Worker never inspected tests/fixtures/reports; QA never inspected production/declarations/diffs/bytecode.
Coordinator independently reviewed both layers and integration; Gradle slots were serialized.

Source corrections: redundant cancellation alias catches removed; unexpected transport seam failures closed; the exact
header-protocol amendment above; current-caller cancellation identity and category precedence. The first broad gate
caught a real source-close/cancellation race after focused identity tests had passed. Production was corrected and QA
added a deterministic 4-operation × 3-late-failure regression. Original identity/category assertions were retained.

QA fixture corrections: outer Retry-After whitespace is valid, .500Z is lossless (positive) while .5001Z rejects,
escaped duplicates must remain escaped in actual JSON, abrupt TLS reset is not orderly framing EOF, and a blocking server
wait must not starve runBlocking's child. Cancellation now gates ACTUAL source-read entry and observes the exception
inside the API caller child as a returned object, avoiding async.await stack-recovery artifacts. Five actual TLS scenarios
cover challenge/exchange/refresh success and ordinary400/best-effort exchange401 error-body reads; all assertSame on the
supplied cancellation. Fixtures always own/close trusted clients even on assertion/timeout failure.

## Actual client gates

- **553 offline JVM tests in 40 suites**, forced final execution, zero failures/errors/skips. Prior477 retained; independent
  R03c suites add63 ServiceAuthHttpApiTest and13 ServiceAuthHttpNetworkTest methods. The late-failure matrix and five TLS
  cancellation scenarios are table-driven observations within those counts, not additional test-count claims.
- **6 filtered ServiceAuthHttpAndroidTest tests** on Pixel_6a / Android17 / API37 (`emulator-5554`), final rerun after
  production corrections, zero failures/errors/skips. Auth routes/grant construction, actual platform overflow/duplicate
  rejection, verified empty204 and classifier smoke; not a full instrumentation/API24/Google pass.
- Production/Android-test Kotlin compilation, debug assembly and lint passed. Lint XML has only existing
  NewerVersionAvailable Warning. Existing KSP/test-helper/Gradle notes remain; a nonfatal new test Call.enqueue parameter
  naming warning is also recorded, not hidden as an existing warning.
- APK contains auth adapter/operation types, not relay/auth test classes/MockWebServer/TLS certificate helpers/JDK adapter/
  PostgreSQL driver. Packaged bundled v1 asset is byte-identical. Both diff checks passed; baseline preservation checked.

Final commands (serial, repository root):

```sh
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --rerun-tasks --no-configuration-cache --console=plain
./gradlew :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.dragosfotache.gymtracka.data.sync.http.ServiceAuthHttpAndroidTest' --no-configuration-cache --console=plain
./gradlew :app:testRealBackend -Pgymtracka.realBackendConfig=/tmp/gymtracka-r03c-backend/config.json --no-configuration-cache --console=plain
```

Actual XML inspected under app/build/test-results/{testDebugUnitTest,testRealBackend}/ and
app/build/outputs/androidTest-results/connected/debug/. Supplementary logs are /tmp/gymtracka-r03c-{final-gate,android,
real-backend-final}.log; they are not pickup prerequisites. Initial failures were corrected before the final client pass;
the initial real-backend failures below were retained as evidence, then resolved for new grants by the separately owned
backend correction and unchanged client rerun. The 553-offline/six-device gates above are earlier executed evidence for
the unchanged client inputs, not fresh offline/device runs during this backend-only correction.

## Initial real-service gate — historical blocker

Coordinator-owned RealBackendFixture adds guarded isolated refresh/session setup (including same-account independent
sessions and expired credentials), and constructs the production auth adapter over the existing trusted local TLS relay.
The relay adds only four fixed auth POST routes and Idempotency-Key forwarding. Existing config/DB/run guards, domain
fixtures/assertions and all46 sync scenarios are preserved. No fabricated Google proof, service auth bypass, backend source
review/change/Maven suite, private configuration or personal database. Operational build/start uses the checked-in isolated
launcher with disposable PostgreSQL16/generated test-only keys.

**56 real-backend tests in 10 suites: 48 passed, 8 FAILED, zero errors/skips.** All46 prior sync tests passed. Of10 new auth
methods, unknown/expired refresh foreground rejection and unknown/expired bodyless logout passed; seven successful-auth/
grant-dependent methods failed MalformedResponse, and one explicit precision contract assertion failed.

The diagnostic reads actual200 challenge and refresh responses through production transport, without exposing bodies or
credentials, and reports only compatibility booleans:

```text
Auth lossless-millisecond expiry: challenge=false, access=false, refresh=false
```

Challenge expiresAt, accessExpiresAt and refreshExpiresAt have NONZERO precision beyond milliseconds. The finalized
client-compatibility/auth contract permits only lossless epoch-millisecond representation; accepted R02d readers correctly
refuse these bodies. A separate sanitized challenge probe found nine fractional digits with nonzero submillisecond tail.
No nonce/token/raw body was printed or persisted by either diagnostic. This is a concrete service compatibility blocker,
not permission to round/truncate client timestamps, alter the contract or weaken assertions.

At this initial gate, successful challenge/grant prerequisites blocked refresh replay/key conflict/revocation and
invalid-Google rejection journeys. These failures were not replaced with skips or permissive client readers.
Actual verified Google exchange success remains separately unverified: no approved live provider inputs or bypass exists.

## Corrected backend and independent client rerun — passed

Backend coordinator reported independently reviewed canonical millisecond issuance, consistent stored/returned deadlines,
shared service Clock/raw at-lock expiry checks and SQL-cleanup cutoff flooring only to PostgreSQL microsecond precision.
Reported backend gates: 19/19 auth, 68/68 affected+auth and 344/344 full tests in 44 suites, zero failures/errors/skips;
137/137 frozen inputs unchanged. These are BACKEND-OWNED evidence, not suites executed or source reviewed by Android.
Backend report: its local `docs/auth-expiry-precision.md`. No backend source/pin/private/deployment changes by Android.

Android independently verified the published JAR SHA256 before/after use:
`41687b8ca223991aed4f6a88ee7cf8cb360ea2d2865cfcd867e157083489a369`.
The guarded launcher used `--no-build`, disposable PostgreSQL16 and generated test-only configuration; no Maven operation
or concurrent backend build. Production-compatible auth/sync transport used the same trusted local TLS relay.

- **Unchanged focused auth: 10/10 passed**.
- **Unchanged broad interoperability: 56/56 passed in 10 suites**, including all 46 original sync cases.
- Zero failures/errors/skips. Auth replay/key conflict, same-account independent-session revocation, previously rotated
  logout, invalid/expired refresh and invalid Google-proof rejection now execute successfully through the actual adapter.
- Sanitized expiry diagnostic: `challenge=true, access=true, refresh=true`.
- **201/201 frozen Android source/build inputs unchanged**; no test/decoder/key/body changes or rounding workaround.

```sh
python3 scripts/run-isolated-backend.py --backend-root /home/dragos/IdeaProjects/gt-backend --config-path /tmp/gymtracka-r03c-fixed-backend/config.json --port 18080 --no-build
./gradlew :app:testRealBackend --tests 'com.dragosfotache.gymtracka.data.sync.backend.RealBackendServiceAuthTest' -Pgymtracka.realBackendConfig=/tmp/gymtracka-r03c-fixed-backend/config.json --no-configuration-cache --console=plain
./gradlew :app:testRealBackend -Pgymtracka.realBackendConfig=/tmp/gymtracka-r03c-fixed-backend/config.json --no-configuration-cache --console=plain
```

Latest executed XMLs are under `app/build/test-results/testRealBackend/`; supplementary logs are
`/tmp/gymtracka-r03c-fixed-{auth,broad,launcher}.log`. Owned launcher/service/config/container were cleaned up after the
rerun. No emulator was started for this backend-only rerun. Backend slots remained closed and artifact/input freeze verified.

### Explicit legacy limitation — not repaired

Backend-owned deterministic tests/report retain original pre-fix encrypted accepted replay bytes and legacy deadlines,
even when incompatible with strict client timestamp representation. Known still-current legacy refresh can freshly
rotate to a compatible grant, but this NEVER permits minting a new key for an uncertain consumed-token predecessor.
No replay rewrite, blanket revocation, client rounding or cache/data deletion is authorized. The client still rejects
incompatible original bodies as malformed; the new-grant pass does not prove such legacy attempts recover through this
client. Carry this limitation into future session/recovery design and release review. The shared v1 rules remain unchanged.

## Stop, preservation and pickup

Both contributors are idle in the same fresh R03c sessions. Owned isolated launcher/service/container/config and emulator
were stopped; no unrelated service/pane/device/configuration was changed. At this gate, Android R03c changes were uncommitted;
shared-docs was preserved at the code/test gate, then separately authorized issue documentation was published and
owner-committed at d5d68d4; only the issue/result index is updated after this rerun. Backend's pinned shared checkout is
untouched. Pickup was clean Android6134a58 / shared61fe3f9, superseding older dirty
handoff descriptions. Authorized existing changes are this bounded transport mapping, two fixture/relay files and Android
local docs; all other baseline files (including schema/asset/build/capture/domain/UI) remain unchanged.

After this verification, the owner requested a shared backend-facing issue. It is now documented in
[shared-docs auth expiry precision issue](../../shared-docs/docs/backend-contract/issues/auth-expiry-precision.md), linked
from the shared contract README. These subsequent documentation-only changes are authorized; the shared-tree preservation
statement above describes the earlier R03c code/test gate, not this publication follow-up. That publication changed
documentation only; Android performed no backend correction or commit/push.

**Subsequent owner delivery (verified 2026-10-04):** Android
`04f7ed24c39b4396122e6d60225942493ba500e2` (`sync work`, parent `6134a58`) commits accepted R03c and subsequent
[R04a](R04a-review.md), including source/tests/harness/backup XML/local docs. Nested shared-docs
`31c969f261bd3f0fe662e633c0dfffecb3c08393` (`sync work`, parent `d5d68d4`) commits the issue-resolution/index follow-up.
Both trees, including untracked files, were clean before this documentation-only receipt update. No coordinator commit/
push, new test run, remote/push verification or repair of legacy replay is claimed. Current next task/authorization is in
[the handoff](../coordinator-handoff.md#owner-delivery-commits--verified-2026-10-04); the proposed R04 pickup below is historical.

R03c is **accepted within its bounded unused auth-transport scope**, with the explicit legacy limitation above.
Next proposed task is R04 encrypted session storage/refresh recovery, ONLY after separate owner authorization.
No R04 or deferred lifecycle implementation has started. No API24 runtime, full instrumentation/Room/migration, Python supervisor, real Google,
hosted CI or personal phone/laptop activation journey is claimed. No credentials were added to the app or persisted by the
transport. Commit/push and later slices still need separate owner authorization.
