# R09h acceptance — app-facing authority and offline recovery

**Accepted boundary: 2026-10-07.** [Specification](R09h-task.md). Owner confirmed the finite-plan boundary: deliver authority/recovery for R12c to consume, **without activating current production screens or publisher at startup**. Owner requires a scoped local commit and prepared handoff, then stopping without a successor launch.

## Delivered and reviewed

- Explicit, constructor-inert `AppSessionCoordinator` with stable read-only state, mandatory transition/publication inputs and one shared credential/run ownership composition. Exact grant expectations, origin/service UUID and fresh/reserved operation rules reject delayed/foreign callbacks without adopting data.
- Private preparation → actual bounded bootstrap tail → protected completion/vault promotion → fresh startup admission. Typed bootstrap/stage/budget stops remain private without launching another completion attempt. Matching established ACTIVE cache reopens and edits offline without consulting expiry, clock, UUID or HTTP; exact clean RoomACTIVE/vaultPREPARED gaps recover separately.
- `AppAccountSession` repositories check process revocation inside transactions before effects/commit and after result dispatch. Process-only invalidation and authority faults revoke reads/edits at unchanged generation. Guarded overloads retain legacy default/trailing-lambda UUID/clock semantics.
- Bounded reconnect over guarded recovered refresh/HTTP and PushRun, with exact frozen intent, pending-first refresh and typed stops. Network transient/reauthentication retains editing eligibility; observed credential-binding/storage/authority failure locks the exact handle. No ACTIVE projection publication or implicit cursor/stage restart.
- Fixed/redacted states/errors; cancellation precedence, acquired-lease cleanup and uncertain-write preservation. No new schema, entity, asset, vault format, build/manifest/UI configuration, backend source or shared-contract changes. Production remains signed-out/local-only.

Fresh native production Worker and independent test-only QA used `meta/muse-spark-1.3-contributor`/high with disjoint source allowlists. Coordinator reviewed both layers and owned specification, integration, resources, gates and final acceptance. Kotlin mcpls remained initializing after bounded attempts; permitted text fallback was used. No clean-LSP-diagnostics claim.

## Important findings resolved

Review corrected cancellation cleanup, stale/private presentation and same-handle revocation races, bootstrap-stop continuation, runtime missing-vault handling and final atomic status/admission checks. Existing capture regressions caught a real Kotlin compatibility defect: an added optional Unit-guard parameter stole old trailing UUID lambdas. Explicit guarded primaries plus unchanged legacy constructors fixed it; **existing assertions were not weakened or edited**.

Device iterations also exposed fixture defects (invalid UUIDs, reused post-reopen UUID supply, wrong promotion-fault category, a gate armed before activation and stale acquisition signals). These were corrected with physical file/Keystore reopen, observed entered gates and actual post-insert rollback oracles. A coordinator packet incorrectly said incremental batches increment account epoch; the owning implemented contract says they only mark dirty. The corrected oracle preserves checkpoint/canonical-row/dirty assertions and unchanged materialized epoch. Failed iterations remain failures, not acceptance evidence; final reruns below have no failures/errors/skips.

## Actual verification

Repository-root commands, serialized coordinator slots; no role builds overlapped. Linux/JDK 25, fresh isolated Android/AVD homes, Android 17 SDK 37 (installed 37.2 system image), x86_64 16-KiB-page emulator, normal-port test-owned ADB. No personal device/account/configuration or service was used.

Final build slot C11:

```sh
./gradlew :app:testDebugUnitTest --rerun :app:connectedDebugAndroidTest \
  :app:assembleDebug :app:lintDebug \
  -Pandroid.testInstrumentationRunnerArguments.package=com.dragosfotache.gymtracka.data.sync.session \
  -Pandroid.testInstrumentationRunnerArguments.timeout_msec=90000 --console=plain
```

**BUILD SUCCESSFUL.** Actual XML: offline JVM **985 tests / 58 suites**, Android session package **210 tests**, all zero failures/errors/skips. The package includes **24 new boundary cases** plus prior preparation/engine/refresh/authority regressions; this is package-filtered, not full instrumentation. Kotlin/KSP and instrumentation compilation executed while building changed source.

C10 ran each class separately through `:app:connectedDebugAndroidTest`, with the same timeout and `-Pandroid.testInstrumentationRunnerArguments.class=<fully-qualified-class>`:

| Class | Executed cases |
|---|---:|
| `com.dragosfotache.gymtracka.data.sync.ScopedRepositoryTest` | 19 |
| `com.dragosfotache.gymtracka.data.sync.CanonicalStorageCaptureTest` | 27 |
| `com.dragosfotache.gymtracka.data.sync.PushDeliveryFenceTest` | 3 |
| `com.dragosfotache.gymtracka.data.sync.PushDeliveryRollbackTest` | 2 |
| `com.dragosfotache.gymtracka.ui.AppFiniteEditGateTest` | 2 |

All **53 selective regression cases** passed with successful fresh tasks and nonempty XML, zero failures/errors/skips. Final C11 repeated JVM/session-package coverage after subsequent nonsemantic API hygiene; C10 is the unchanged constructor/capture/app-admission regression evidence, not another full-layer pass.

Lint: zero errors/fatals; one dependency-version-availability warning for the unchanged `org.json` pin. Existing AGP/JDK/compiler warnings were not gate failures. Debug APK dex/asset inspection found no new QA helpers, R09h fixture credentials/namespaces, real-backend/JDBC helpers or new test assets. Bundled database and exported v12 schema SHA-256 remain:

```text
asset gym.db: defa08e9c464f90f0f48d8e3441fe20151803b48948647a91742c1bb82fa9f7d
schema 12:    54034902818583d2374b169272606145f46764623fc4bacfdb2fd0c6f72eab68
```

Both repository diffs, new files, documentation links and whitespace were reviewed. `git diff --check` passed. Local logs/executed XML were retained in test-owned temporary evidence; no credentials/configuration contents are delivery artifacts. Earlier failed compilation/device runs were corrected, and stale XML from a failed compilation was explicitly excluded from fresh evidence.

## Stop and residual obligations

R09a–h close the finite reduced **authority/recovery** plan; this is not a claim of usable personal-account app or live activation. Next eligible integration is R12c, using delivered authority plus R12a/b and accepted [Core R10a](R10a-review.md). R12c owns scoped repository/state rebinding, navigation/pill and canonical timer-impact decisions, ACTIVE publication/full-resync orchestration and private-scope/real Compose/package gates. No permissive callback replaces that work.

Full unfiltered instrumentation and full scope/UI/timer reconciliation remain R12c gates; identity/account UI/foreground triggers remain R11/R13. Typed cursor/stage/rejection recovery needs deliberate caller integration, not automatic deletion/restart. Core R10a is reused prerequisite evidence, **not a fresh backend run** here; R10b remains deferred public. Real Google, trusted laptop connectivity, phone/account journeys and deployment remain R17/external permissions. Timer process-death reconstruction and service retention pruning are not claimed.

Legacy local-only data remains unchanged and activation refuses pending owner disposition; no migration/import/delete choice is inferred. P17 encrypted uncertain archives remain retained/fenced, with no cleanup or legacy auth-replay repair. General switching/sign-out/import-replace and lifecycle scope remain deferred. Resources/build slots are released before handoff readiness. Coordinator stops after scoped commit/handoff preparation; no successor, push or deployment is authorized by this review.
